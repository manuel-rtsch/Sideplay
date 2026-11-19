package io.github.manuelrtsch.sidetrack.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the transport's own reason for a failed access-point connection where it can be read
 * without adb.
 *
 * A failed connect happens before any request is made, so nothing on the Kotlin side can describe
 * it: the reason names the host, the port or the TLS state that refused, and a device that cannot
 * run adb had no way to report it. Every failure appends one line to
 * `Download/Sidetrack/sidetrack-diagnostics.txt`, which a file manager can open and share.
 *
 * Nothing here may throw into the connection path: a failure to write is a lost diagnostic, not a
 * reason for the connect to fail differently.
 */
class ConnectDiagnostics(context: Context) {
    private val app = context.applicationContext
    private val fallback = File(app.getExternalFilesDir(null) ?: app.filesDir, FILE_NAME)

    /** Kept off the caller's thread: [record] runs inside the connection path. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(reason: String) {
        val line = "${stamp()} ${reason.replace('\n', ' ').take(MAX_REASON)}\n"
        scope.launch {
            runCatching { appendToDownloads(line) }
                .onFailure { error ->
                    SidetrackDebug.log("diagnostics: Downloads unavailable", error)
                    runCatching { fallback.appendText(line) }
                }
        }
    }

    /**
     * Android 10+ writes into Downloads through MediaStore, so the file is shareable without a
     * storage permission. Append mode is a documented request but providers may refuse it; the
     * caller then keeps the app-folder copy instead.
     */
    private fun appendToDownloads(line: String) {
        val resolver = app.contentResolver
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

        val uri = when {
            existing != null && existing.second < MAX_BYTES ->
                ContentUris.withAppendedId(collection, existing.first)

            else -> create(resolver, collection, relative, replacing = existing != null)
        }
        resolver.openOutputStream(uri, "wa")?.use { it.write(line.toByteArray()) }
            ?: error("no stream for $uri")
    }

    private fun create(
        resolver: ContentResolver,
        collection: Uri,
        relative: String,
        replacing: Boolean
    ): Uri {
        if (replacing) {
            runCatching {
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(relative, FILE_NAME)
                )
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val created = resolver.insert(collection, values) ?: error("no item for $relative$FILE_NAME")
        return created
    }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val DIR = "Sidetrack"
        const val FILE_NAME = "sidetrack-diagnostics.txt"
        const val MAX_BYTES = 64L * 1024L
        const val MAX_REASON = 400
    }
}
