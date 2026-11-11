package io.github.manuelrtsch.sideplay.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.manuelrtsch.sideplay.SideplayDebug
import io.github.manuelrtsch.sideplay.bridge.NativeBridge
import io.github.manuelrtsch.sideplay.bridge.SpotifyNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Drains PCM from librespot into a streaming [AudioTrack].
 *
 * The native sink uses a bounded channel and blocks when this consumer stops. That backpressure
 * is what keeps librespot's decode position tied to the speaker instead of racing through the
 * track. The important consequence is that the player thread can only process pause/play/load
 * commands while this drain is running, so pause/stop must keep draining until the player has
 * acknowledged the command.
 *
 * Pause keeps the [AudioTrack] alive and captures the PCM still queued in the native channel.
 * On resume the track's own buffer plays first, then the captured PCM, then new PCM from the
 * player. Nothing is discarded, so the audio continues exactly where it stopped.
 */
class SpotifyAudioSink(
    private val native: SpotifyNative = NativeBridge,
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private var track: AudioTrack? = null
    private var drain: Job? = null

    /**
     * Serializes track lifecycle operations. The drain job writes to the AudioTrack on its own
     * coroutine; releasing or flushing the track while that write is in flight crashes inside
     * libaudioclient. Every public operation takes this lock and stops the drain first.
     */
    private val lock = Mutex()

    /** PCM read from the native channel after the AudioTrack was paused. */
    private val pendingPcm = ArrayDeque<ByteArray>()

    /** Position of the first frame written to the current AudioTrack. */
    private var startPositionMs = 0L
    private var startHeadFrames = 0L
    private var lastHeadFrames = 0L
    private var headWrapOffset = 0L

    val isRunning: Boolean get() = drain?.isActive == true

    /** Starts a new track. Discards any audio left over from the previous one. */
    suspend fun start(positionMs: Long = 0L) = lock.withLock {
        startLocked(positionMs)
    }

    private suspend fun startLocked(positionMs: Long) {
        stopDrain()
        pendingPcm.clear()
        releaseTrack()
        // Unblock a player thread that may still be waiting in PcmSink::write for the previous
        // track. The load command cannot be processed until that thread is free.
        native.drainAudio()

        val format = readFormat()
        SideplayDebug.log("audio.start format=${format.sampleRate}Hz channels=${format.channels}")
        val output = createTrack(format)
        if (output == null) {
            SideplayDebug.log("audio.start aborted: AudioTrack creation failed")
            return
        }
        track = output
        startPositionMs = positionMs
        startHeadFrames = 0L
        lastHeadFrames = 0L
        headWrapOffset = 0L
        output.play()
        SideplayDebug.log("audio.start AudioTrack playing positionMs=$positionMs")
        startDrain(output)
    }

    /**
     * Resumes the paused track, replaying the PCM captured at pause before new PCM.
     *
     * @return true when the existing AudioTrack (and therefore its buffered audio) was reused.
     *   false means the buffer was lost and the player must be realigned with [positionMs].
     */
    suspend fun resume(positionMs: Long): Boolean = lock.withLock {
        val output = track
        if (output == null || output.playState != AudioTrack.PLAYSTATE_PAUSED) {
            startLocked(positionMs)
            return@withLock false
        }
        SideplayDebug.log("audio.resume pending=${pendingPcm.size}")
        output.play()
        startDrain(output)
        true
    }

    /**
     * Pauses the track without releasing it. The AudioTrack buffer and the PCM still queued in
     * the native channel are both preserved, so resume is sample-continuous.
     */
    suspend fun pause() = lock.withLock {
        stopDrain()
        capturePendingPcm()
        runCatching { track?.pause() }
        SideplayDebug.log("audio.pause pending=${pendingPcm.size}")
    }

    /** Stops and releases the track. Used when changing tracks or stopping playback. */
    suspend fun stop() = lock.withLock {
        stopDrain()
        pendingPcm.clear()
        releaseTrack()
        native.drainAudio()
    }

    /**
     * The actual audio position, taken from the AudioTrack playback head. This is the hardware
     * clock, so the UI can follow it without guessing from librespot's event cadence.
     */
    fun currentPositionMs(): Long? {
        val output = track ?: return null
        val head = runCatching { output.playbackHeadPosition.toLong() }.getOrNull() ?: return null
        if (head < lastHeadFrames) headWrapOffset += 1L shl 32
        lastHeadFrames = head
        val frames = head + headWrapOffset - startHeadFrames
        return startPositionMs + frames * 1_000L / output.sampleRate
    }

    /** Re-bases the playback-head clock after a seek without recreating the AudioTrack. */
    suspend fun seek(positionMs: Long) = lock.withLock {
        val output = track ?: return@withLock
        val wasPlaying = output.playState == AudioTrack.PLAYSTATE_PLAYING
        stopDrain()
        pendingPcm.clear()
        // Discard PCM decoded for the old position and unblock the player so it can process the
        // seek command.
        native.drainAudio()
        runCatching { output.pause() }
        runCatching { output.flush() }
        val head = runCatching { output.playbackHeadPosition.toLong() }.getOrDefault(0L)
        startPositionMs = positionMs
        startHeadFrames = head
        lastHeadFrames = head
        headWrapOffset = 0L
        if (wasPlaying) {
            output.play()
            startDrain(output)
        }
    }

    private suspend fun startDrain(output: AudioTrack) {
        stopDrain()
        drain = scope.launch(Dispatchers.IO) {
            var reads = 0
            var bytes = 0L
            try {
                // Replay PCM captured at pause before reading anything new from the player.
                while (pendingPcm.isNotEmpty()) {
                    val pcm = pendingPcm.removeFirst()
                    writeBlocking(output, pcm)
                    reads++
                    bytes += pcm.size
                }
                while (isActive) {
                    val pcm = native.readAudio(READ_TIMEOUT_MS) ?: continue
                    if (pcm.isEmpty()) continue
                    if (!isActive) {
                        // Cancellation raced with the read. Keep this buffer for the next resume
                        // instead of losing it.
                        pendingPcm.addFirst(pcm)
                        break
                    }
                    if (reads == 0) SideplayDebug.log("audio first PCM read bytes=${pcm.size}")
                    writeBlocking(output, pcm)
                    reads++
                    bytes += pcm.size
                    if (reads % 100 == 0) SideplayDebug.log("audio drained reads=$reads bytes=$bytes")
                }
            } catch (t: Throwable) {
                SideplayDebug.log("audio drain failed reads=$reads bytes=$bytes", t)
                if (isActive) onFailure(t)
            } finally {
                SideplayDebug.log("audio drain ended reads=$reads bytes=$bytes")
            }
        }
    }

    private fun writeBlocking(output: AudioTrack, pcm: ByteArray) {
        val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
    }

    private suspend fun stopDrain() {
        drain?.cancelAndJoin()
        drain = null
    }

    private fun capturePendingPcm() {
        while (true) {
            val pcm = native.readAudio(0) ?: break
            if (pcm.isNotEmpty()) pendingPcm.addLast(pcm)
        }
    }

    private fun releaseTrack() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
    }

    private fun readFormat(): Format {
        val raw = native.audioFormat() ?: return Format()
        return runCatching {
            val json = JSONObject(raw)
            Format(
                sampleRate = json.optInt("sampleRate", 44_100),
                channels = json.optInt("channels", 2)
            )
        }.getOrDefault(Format())
    }

    private fun createTrack(format: Format): AudioTrack? {
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
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.onFailure(onFailure).getOrNull()
    }

    private data class Format(val sampleRate: Int = 44_100, val channels: Int = 2)

    private companion object {
        const val READ_TIMEOUT_MS = 100L
    }
}
