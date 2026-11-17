package io.github.manuelrtsch.sidetrack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sin

/**
 * A tiny local HTTP music source for playback tests.
 *
 * It serves a real PCM WAV and honours `Range`, so a test can prove that a [PlaybackSource]'s
 * URL and headers reach the transport and that the server supports the range requests ExoPlayer
 * makes. It is deliberately dependency-free: the JDK's HTTP server is enough.
 */
class FakeHttpStream(
    private val requireToken: String? = null
) : AutoCloseable {
    data class Request(
        val path: String,
        val range: String?,
        val token: String?
    )

    val requests = CopyOnWriteArrayList<Request>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/stream.wav") { exchange -> serve(exchange) }
        createContext("/auth.wav") { exchange -> serve(exchange) }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/stream.wav"

    val authUrl: String get() = "http://127.0.0.1:${server.address.port}/auth.wav"

    private fun serve(exchange: HttpExchange) {
        try {
            val token = exchange.requestHeaders.getFirst(TOKEN_HEADER)
            requests += Request(
                path = exchange.requestURI.path,
                range = exchange.requestHeaders.getFirst("Range"),
                token = token
            )
            if (requireToken != null && token != requireToken) {
                exchange.sendResponseHeaders(401, -1)
                return
            }
            val range = exchange.requestHeaders.getFirst("Range")
            val (start, end) = parseRange(range, WAV.size)
            val body = WAV.copyOfRange(start, end + 1)
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${WAV.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
            }
            exchange.responseBody.use { it.write(body) }
        } finally {
            exchange.close()
        }
    }

    private fun parseRange(range: String?, size: Int): Pair<Int, Int> {
        if (range == null) return 0 to size - 1
        val spec = range.removePrefix("bytes=").substringBefore(',')
        val start = spec.substringBefore('-').toIntOrNull() ?: 0
        val end = spec.substringAfter('-', "").toIntOrNull() ?: (size - 1)
        return start.coerceIn(0, size - 1) to end.coerceIn(start, size - 1)
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val TOKEN_HEADER = "X-Test-Token"

        /** 0.5 s of a 440 Hz tone, 16-bit mono PCM at 8 kHz. */
        val WAV: ByteArray = run {
            val sampleRate = 8_000
            val samples = sampleRate / 2
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
            putShort(20, 1) // PCM
            putShort(22, 1) // mono
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2) // byte rate
            putShort(32, 2) // block align
            putShort(34, 16) // bits per sample
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
