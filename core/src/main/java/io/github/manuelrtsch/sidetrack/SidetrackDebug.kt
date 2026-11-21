package io.github.manuelrtsch.sidetrack

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Live-debug logging for the Sidetrack playback path.
 *
 * Enable on device with:
 *   adb shell setprop log.tag.SidetrackDebug DEBUG
 *
 * The tag is checked at every call, so the property can be toggled without reinstalling.
 *
 * Logcat alone is not durable enough for playback bugs: logd keeps only a few minutes of this
 * app's lines on a busy device, so a stall is usually gone by the time anyone dumps the buffer.
 * [installFileLog] mirrors the same lines into a small rolling file under the app's external
 * files directory, where `adb pull` (or a debug build's `run-as`) can still reach them hours
 * later. Writes are queued on one daemon thread, so the audio drain never waits for the disk.
 *
 * Shared across modules, hence public.
 */
object SidetrackDebug {
    const val TAG = "SidetrackDebug"

    /** Debug and dev builds keep the file on unconditionally; release follows the log tag. */
    @Volatile
    private var always = false

    @Volatile
    private var file: RollingLogFile? = null

    val enabled: Boolean get() = always || Log.isLoggable(TAG, Log.DEBUG)

    /**
     * Starts mirroring [log] into `<external files>/logs/sidetrack.log`.
     *
     * Safe to call more than once; the previous writer is closed. [always] keeps the file going
     * without the log tag, which is what debug and dev builds want for a field capture.
     */
    fun installFileLog(context: Context, always: Boolean = false) {
        this.always = always
        val directory = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        val previous = synchronized(this) {
            val old = file
            file = RollingLogFile(directory)
            old
        }
        previous?.close()
        log("diagnostic file: ${File(directory, RollingLogFile.NAME)}")
    }

    fun log(message: String) {
        if (!enabled) return
        Log.d(TAG, message)
        file?.write(message)
    }

    /**
     * Lazy variant for hot paths: the message is only built when the tag is enabled, so a
     * disabled debug build does not allocate a string on every publish or audio read.
     */
    inline fun log(message: () -> String) {
        if (!enabled) return
        val text = message()
        Log.d(TAG, text)
        writeResolved(text)
    }

    /** Inline [log] can only call published members; the file itself stays private. */
    @PublishedApi
    internal fun writeResolved(message: String) {
        file?.write(message)
    }

    fun log(message: String, error: Throwable) {
        if (!enabled) return
        Log.d(TAG, message, error)
        file?.write("$message\n${Log.getStackTraceString(error)}")
    }
}

/**
 * A bounded, rotating text log.
 *
 * One worker owns the writer, so callers (including the audio drain) only enqueue a line. When
 * the queue is full the newest line wins; losing a debug line is always better than blocking
 * playback. The file is capped at [maxBytes] and keeps [backups] older files, enough for an
 * hour or two of playback at the current verbosity.
 */
internal class RollingLogFile(
    private val directory: File,
    private val maxBytes: Long = 1_500_000L,
    private val backups: Int = 2
) {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2048),
        { runnable -> Thread(runnable, "sidetrack-log").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private var writer: BufferedWriter? = null
    private var written = 0L
    private var broken = false
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun write(message: String) {
        if (broken) return
        val line = "${LocalDateTime.now().format(stamp)} $message"
        runCatching { executor.execute { append(line) } }
    }

    fun close() {
        runCatching {
            executor.execute {
                runCatching { writer?.close() }
                writer = null
            }
        }
        executor.shutdown()
    }

    private fun append(line: String) {
        if (broken) return
        runCatching {
            directory.mkdirs()
            if (written + line.length + 1 > maxBytes) rotate()
            val out = writer ?: open()
            out.append(line).append('\n')
            // Flush every line: a crash or a killed process is exactly when the log matters.
            out.flush()
            written += line.length + 1
        }.onFailure {
            broken = true
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun open(): BufferedWriter {
        val target = File(directory, NAME)
        val out = BufferedWriter(FileWriter(target, true))
        writer = out
        written = target.length()
        return out
    }

    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        File(directory, "$NAME.$backups").delete()
        for (index in backups - 1 downTo 1) {
            val from = File(directory, "$NAME.$index")
            if (from.exists()) from.renameTo(File(directory, "$NAME.${index + 1}"))
        }
        val current = File(directory, NAME)
        if (current.exists()) current.renameTo(File(directory, "$NAME.1"))
        written = 0L
    }

    companion object {
        const val NAME = "sidetrack.log"
    }
}
