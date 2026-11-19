package io.github.manuelrtsch.sidetrack

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * A minimal HTTP/1.1 music server for instrumented playback tests.
 *
 * `com.sun.net.httpserver` is not part of Android, so this speaks just enough HTTP for ExoPlayer:
 * a request line, headers, `Range`, and a PCM WAV body. It records every request line and every
 * `Range` it answered so a test can state what the player actually asked for — note that ExoPlayer
 * opens a fresh item at position 0 without a `Range` and only sends one when it resumes or seeks
 * inside the file, so a non-empty [requests] is the portable claim.
 */
class TestHttpStream : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = java.util.concurrent.atomic.AtomicBoolean(true)

    val ranges = CopyOnWriteArrayList<String>()

    /** Request lines the server has answered, e.g. `GET /stream.wav HTTP/1.1`. */
    val requests = CopyOnWriteArrayList<String>()

    val url: String get() = "http://127.0.0.1:${server.localPort}/stream.wav"

    private val worker = thread(name = "test-http-stream", isDaemon = true) {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            if (!requestLine.contains("/stream.wav")) {
                writeResponse(it.getOutputStream(), 404, emptyMap(), ByteArray(0))
                return
            }
            requests += requestLine
            val range = headers["range"]
            if (range != null) ranges += range
            val (start, end) = parseRange(range, WAV.size)
            val body = WAV.copyOfRange(start, end + 1)
            val responseHeaders = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Content-Type" to "audio/wav",
                "Connection" to "close"
            )
            if (range != null) {
                responseHeaders["Content-Range"] = "bytes $start-$end/${WAV.size}"
                writeResponse(it.getOutputStream(), 206, responseHeaders, body)
            } else {
                writeResponse(it.getOutputStream(), 200, responseHeaders, body)
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val reason = if (code == 206) {
            "Partial Content"
        } else if (code == 200) {
            "OK"
        } else {
            "Not Found"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun parseRange(range: String?, size: Int): Pair<Int, Int> {
        if (range == null) return 0 to size - 1
        val spec = range.removePrefix("bytes=").substringBefore(',')
        val start = spec.substringBefore('-').toIntOrNull() ?: 0
        val end = spec.substringAfter('-', "").toIntOrNull() ?: (size - 1)
        return start.coerceIn(0, size - 1) to end.coerceIn(start, size - 1)
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        worker.join(1_000)
    }

    companion object {
        /** 1 s of a 440 Hz tone, 16-bit mono PCM at 8 kHz. */
        val WAV: ByteArray = run {
            val sampleRate = 8_000
            val samples = sampleRate
            val dataSize = samples * 2
            val out = ByteArray(44 + dataSize)
            fun putAscii(offset: Int, value: String) {
                value.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
            }
            fun putInt(offset: Int, value: Int) {
                out[offset] = (value and 0xff).toByte()
                out[offset + 1] = (value shr 8 and 0xff).toByte()
                out[offset + 2] = (value shr 16 and 0xff).toByte()
                out[offset + 3] = (value shr 24 and 0xff).toByte()
            }
            fun putShort(offset: Int, value: Int) {
                out[offset] = (value and 0xff).toByte()
                out[offset + 1] = (value shr 8 and 0xff).toByte()
            }
            putAscii(0, "RIFF")
            putInt(4, 36 + dataSize)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, 1)
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2)
            putShort(32, 2)
            putShort(34, 16)
            putAscii(36, "data")
            putInt(40, dataSize)
            for (i in 0 until samples) {
                val value = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * Short.MAX_VALUE * 0.2).toInt()
                putShort(44 + i * 2, value)
            }
            out
        }
    }
}
