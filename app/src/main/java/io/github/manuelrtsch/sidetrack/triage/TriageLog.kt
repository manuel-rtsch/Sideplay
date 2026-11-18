package io.github.manuelrtsch.sidetrack.triage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Triage-only diagnostics. Not part of a release: this branch exists so a device that cannot run
 * `adb` can still hand over a log.
 *
 * Every line is written twice: to `Download/Sidetrack/sidetrack-triage.log`, which any file
 * manager opens and shares, and to the app's own external folder as a fallback for devices whose
 * MediaProvider refuses the insert. The same lines also go to logcat under [TAG]. Nothing here may
 * throw: a logging failure must never become the crash it is meant to explain.
 */
object TriageLog {
    const val TAG = "SidetrackTriage"

    private const val DIR = "Sidetrack"
    private const val FILE_NAME = "sidetrack-triage.txt"
    private const val MAX_BYTES = 512L * 1024L
    private const val MAX_SESSION_CHARS = 256 * 1024

    private val lock = Any()
    private val pending = StringBuilder()
    private val session = StringBuilder()
    private var sinks: List<Sink> = emptyList()
    private var started = false

    /** Where the log ended up, for the "send me this file" instruction. */
    @Volatile
    var location: String = "unavailable"
        private set

    fun init(context: Context) {
        synchronized(lock) {
            if (started) return
            started = true
            val app = context.applicationContext
            val fileSink = openFileSink(app)
            val downloadsSink = openDownloadsSink(app)
            sinks = listOfNotNull(fileSink, downloadsSink)
            location = downloadsSink?.location ?: fileSink?.location ?: "unavailable"
            installCrashHandler()
            writeLocked("I", "triage", "log files: ${sinks.joinToString { it.location }}")
            writeLocked("I", "triage", "process start pid=${android.os.Process.myPid()}")
        }
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        runCatching {
            when (level) {
                "E" -> Log.e(TAG, "$tag: $message", error)
                "W" -> Log.w(TAG, "$tag: $message", error)
                else -> Log.i(TAG, "$tag: $message")
            }
        }
        synchronized(lock) {
            if (!started) return
            writeLocked(level, tag, message, error)
        }
    }

    private fun writeLocked(level: String, tag: String, message: String, error: Throwable? = null) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        pending.append(stamp).append(' ').append(level).append(' ').append(tag).append(": ")
            .append(message).append('\n')
        if (error != null) {
            error.stackTraceToString().lineSequence().forEach { line ->
                pending.append("    ").append(line).append('\n')
            }
        }
        flushLocked()
    }

    private fun flushLocked() {
        if (pending.isEmpty()) return
        val delta = pending.toString()
        pending.setLength(0)
        if (session.length < MAX_SESSION_CHARS) session.append(delta)
        sinks.forEach { sink ->
            runCatching { sink.append(delta, session.toString()) }
                .onFailure { Log.w(TAG, "could not write ${sink.location}", it) }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                synchronized(lock) {
                    writeLocked("E", "crash", "uncaught on thread '${thread.name}'", error)
                    flushLocked()
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * The shareable copy. Android 10+ writes it through MediaStore so it lands in Downloads
     * without a storage permission. Returns null when the provider refuses, which is why the app
     * folder copy exists.
     */
    private fun openDownloadsSink(context: Context): Sink? = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            null
        } else {
            openDownloadsSinkQ(context)
        }
    } catch (e: Exception) {
        TriageLog.e("triage", "Downloads copy unavailable, keeping the app-folder copy", e)
        null
    }

    private fun openDownloadsSinkQ(context: Context): Sink? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR/"

        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(FILE_NAME, relative),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getLong(1) else null
        }

        val uri: Uri = if (existing != null && existing.second < MAX_BYTES) {
            ContentUris.withAppendedId(collection, existing.first)
        } else {
            if (existing != null) {
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, existing.first), null, null) }
            }
            val name = if (existing == null) FILE_NAME else "sidetrack-triage-${stamp()}.log"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val created = resolver.insert(collection, values) ?: return null
            // The documented order: write the first bytes while pending, then publish the item.
            resolver.openOutputStream(created, "w")?.use { it.write(header(context).toByteArray()) }
                ?: return null
            resolver.update(
                created,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            created
        }

        return DownloadsSink(resolver, uri)
    }

    /** The copy that always works: the app's own external folder. */
    private fun openFileSink(context: Context): Sink? = runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, FILE_NAME)
        file.appendText(header(context))
        FileSink(file)
    }.onFailure { Log.w(TAG, "could not open the app-folder log", it) }.getOrNull()

    private fun header(context: Context): String = buildString {
        append("\n===== Sidetrack triage log =====\n")
        append("started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        append("package: ${context.packageName}\n")
        append("version: ${versionName(context)}\n")
        append("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        append("fingerprint: ${Build.FINGERPRINT}\n")
        append("abis: ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("================================\n")
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private interface Sink {
        val location: String
        fun append(delta: String, full: String)
    }

    private class DownloadsSink(
        private val resolver: android.content.ContentResolver,
        private val uri: Uri
    ) : Sink {
        override val location = "Download/$DIR/$FILE_NAME"

        override fun append(delta: String, full: String) {
            try {
                resolver.openOutputStream(uri, "wa")?.use { it.write(delta.toByteArray()) }
                    ?: throw IllegalStateException("no stream for $uri")
            } catch (e: Exception) {
                // Some providers refuse append mode; rewriting the session is the fallback.
                resolver.openOutputStream(uri, "w")?.use { it.write(full.toByteArray()) }
                    ?: throw e
            }
        }
    }

    private class FileSink(private val file: File) : Sink {
        override val location = file.absolutePath

        override fun append(delta: String, full: String) {
            runCatching { file.appendText(delta) }
                .onFailure { file.writeText(full) }
        }
    }
}
