package io.github.manuelrtsch.sidetrack.playback

/**
 * Timing rules for one [android.media.AudioTrack] drain: when a freshly created track may start
 * playing, and when a running one has gone silent for long enough to call it dead.
 *
 * Kept away from [SpotifyAudioSink] so the rules can be exercised without an AudioTrack, a
 * device or a real clock. Every timestamp is `SystemClock.elapsedRealtime()` milliseconds.
 *
 * The two rules exist for two observed failures:
 *  - A track that starts playing before it holds any PCM underruns as soon as the first buffer
 *    takes longer than the buffer length to arrive, which Android reports as "disabled due to
 *    previous underrun, restarting" and the listener hears as a gap at the start of a track.
 *  - A drain that keeps polling an empty native channel forever looks exactly like playback from
 *    the UI's point of view while it is silent. After a dead access-point connection the app
 *    stayed in that state for half an hour, so the silence needs a deadline.
 */
internal class DrainPolicy(
    /** Bytes to buffer before playback starts; half the AudioTrack buffer in practice. */
    private val prefillBytes: Int,
    /** How long to wait for that prefill before starting anyway. */
    private val prefillTimeoutMs: Long,
    /** How long a playing drain may go without any PCM before it counts as starved. */
    private val starveTimeoutMs: Long
) {
    private var startedAtMs = 0L
    private var bufferedBytes = 0L
    private var lastDataAtMs = 0L
    private var playing = false

    /** Starts a new track. [playing] is true when the caller already started the AudioTrack. */
    fun begin(nowMs: Long, playing: Boolean) {
        startedAtMs = nowMs
        lastDataAtMs = nowMs
        bufferedBytes = 0L
        this.playing = playing
    }

    /** Records PCM handed to the AudioTrack. */
    fun onData(bytes: Int, nowMs: Long) {
        bufferedBytes += bytes
        lastDataAtMs = nowMs
    }

    /**
     * True exactly once: when the buffered audio reached the prefill target, or the prefill
     * deadline expired with less than that. Timer-based because a source that never delivers is
     * a stall to report, not a reason to sit silently on an unplayed track.
     */
    fun mayPlay(nowMs: Long): Boolean {
        if (playing) return false
        if (bufferedBytes < prefillBytes && nowMs - startedAtMs < prefillTimeoutMs) return false
        playing = true
        return true
    }

    /** How long the drain has gone without receiving PCM. */
    fun idleFor(nowMs: Long): Long = nowMs - lastDataAtMs

    /** True once a *playing* track has received no PCM for the whole stall budget. */
    fun starved(nowMs: Long): Boolean = playing && nowMs - lastDataAtMs >= starveTimeoutMs
}
