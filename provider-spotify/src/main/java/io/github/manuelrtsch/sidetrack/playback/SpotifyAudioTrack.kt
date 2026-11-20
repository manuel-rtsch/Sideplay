package io.github.manuelrtsch.sidetrack.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import org.json.JSONObject

/** The PCM format the native player negotiated. */
internal data class Format(val sampleRate: Int = 44_100, val channels: Int = 2)

/**
 * Builds the streaming [AudioTrack] the sink writes into, and the buffer figures derived from it.
 *
 * The platform minimum is a latency figure, not a jitter budget: it holds a few tens of
 * milliseconds, so any pause in decoding (a track's first fetch, a route change, a GC on the UI
 * process) drains it and AudioFlinger marks the track as underrun. A few hundred milliseconds
 * comfortably covers those stalls; seeking flushes it, so nothing waits behind it.
 */
internal class AudioTrackBuilder(
    private val native: SpotifyNative,
    private val onFailure: (Throwable) -> Unit
) {
    /** Bytes the current track can hold; 0 until a track is built. */
    var bufferBytes: Int = 0
        private set

    /** Bytes per second of the current format; 0 until a track is built. */
    var bytesPerSecond: Int = 0
        private set

    fun reset() {
        bufferBytes = 0
        bytesPerSecond = 0
    }

    fun prefillBytes(): Int = (bufferBytes / PREFILL_FRACTION).coerceAtLeast(MIN_PREFILL_BYTES)

    /**
     * The least a track may be started with when the prefill deadline expires first. Enough to
     * keep AudioFlinger fed for a moment, so the timeout relaxes the target instead of making the
     * track underrun the instant it plays. Never above the prefill target itself.
     */
    fun minStartBytes(prefill: Int): Int = (bytesPerSecond * MIN_START_MS / 1_000L).toInt()
        .coerceAtLeast(MIN_PREFILL_BYTES)
        .coerceAtMost(prefill)

    fun readFormat(): Format {
        val raw = native.audioFormat() ?: return Format()
        return runCatching {
            val json = JSONObject(raw)
            Format(
                sampleRate = json.optInt("sampleRate", 44_100),
                channels = json.optInt("channels", 2)
            )
        }.getOrDefault(Format())
    }

    fun build(format: Format): AudioTrack? {
        val channelMask = if (format.channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            format.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onFailure(IllegalStateException("AudioTrack rejected the PCM format"))
            return null
        }
        val frameBytes = BYTES_PER_SAMPLE * format.channels
        val targetBuffer = format.sampleRate * frameBytes * TARGET_BUFFER_MS / 1_000
        val buffer = maxOf(minBuffer * 2, targetBuffer)
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.onFailure(onFailure).getOrNull()?.also {
            bufferBytes = buffer
            bytesPerSecond = format.sampleRate * BYTES_PER_SAMPLE * format.channels
            SidetrackDebug.log(
                "audio.track bufferMs=${buffer * 1_000L / bytesPerSecond} minBuffer=$minBuffer"
            )
        }
    }

    private companion object {
        /** S16LE: two bytes per sample per channel. */
        const val BYTES_PER_SAMPLE = 2

        /**
         * How much PCM the AudioTrack holds. Bigger than the platform minimum on purpose: it is
         * the cushion that turns a stall in decoding into a late buffer instead of an underrun.
         */
        const val TARGET_BUFFER_MS = 400

        /** The floor a track may start with once the prefill deadline expired. */
        const val MIN_START_MS = 100L

        /** Start playing once half the track buffer holds audio. */
        const val PREFILL_FRACTION = 2

        /** Floor for the prefill target, so a small track still starts with something queued. */
        const val MIN_PREFILL_BYTES = 8 * 1024
    }
}
