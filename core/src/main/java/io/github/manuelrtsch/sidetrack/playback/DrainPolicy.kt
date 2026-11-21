package io.github.manuelrtsch.sidetrack.playback

/**
 * Timing rules for one [android.media.AudioTrack] drain: when a freshly created track may start
 * playing, and when a running one has gone silent for long enough to call it dead.
 *
 * Kept away from the audio sink so the rules can be exercised without an AudioTrack, a
 * device or a real clock. Every timestamp is `SystemClock.elapsedRealtime()` milliseconds.
 *
 * The two rules exist for two observed failures:
 *  - A track that starts playing before it holds any PCM underruns as soon as the first buffer
 *    takes longer than the buffer length to arrive, which Android reports as "disabled due to
 *    previous underrun, restarting" and the listener hears as a gap at the start of a track.
 *    The prefill deadline used to start the track on whatever it had, including nothing, which
 *    is that same underrun one fetch stall later; the deadline now only gives up on the *full*
 *    prefill, never on having something in hand.
 *  - A drain that keeps polling an empty native channel forever looks exactly like playback from
 *    the UI's point of view while it is silent. After a dead access-point connection the app
 *    stayed in that state for half an hour, so the silence needs a deadline.
 */
class DrainPolicy(
    /** Bytes to buffer before playback starts; half the AudioTrack buffer in practice. */
    private val prefillBytes: Int,
    /** The smallest cushion a track may ever start with once the prefill deadline expired. */
    private val minStartBytes: Int,
    /** How long to wait for that prefill before starting anyway. */
    private val prefillTimeoutMs: Long,
    /** How long a drain that never produced PCM may wait before it counts as starved. */
    private val starveTimeoutMs: Long,
    /**
     * How long a drain that was already playing may go silent before it counts as starved.
     *
     * Much shorter than [starveTimeoutMs]: once audio has flowed, a multi-second hole is a dead
     * access point, not a slow first fetch. The old single 15 s budget turned a dropped
     * connection into 16 s of silence before recovery even started.
     */
    private val playingStarveTimeoutMs: Long = starveTimeoutMs
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
     * deadline expired with at least [minStartBytes] to play.
     *
     * A late first buffer is a stall to report, but starting the track to report it is worse than
     * waiting: an empty track underruns before anything is audible, AudioFlinger notes it as
     * "disabled due to previous underrun, restarting", and the track is dead until it is
     * rewritten. So the deadline relaxes the target down to [minStartBytes] and no further; a
     * source that delivers nothing at all is caught by [starved] instead.
     */
    fun mayPlay(nowMs: Long): Boolean {
        if (playing) return false
        if (bufferedBytes >= prefillBytes) {
            playing = true
            return true
        }
        if (nowMs - startedAtMs < prefillTimeoutMs) return false
        if (bufferedBytes < minStartBytes) return false
        playing = true
        return true
    }

    /** How long the drain has gone without receiving PCM. */
    fun idleFor(nowMs: Long): Long = nowMs - lastDataAtMs

    /**
     * True once the drain has received no PCM for the whole stall budget, playing or not: a
     * track that never gets enough to start is the same silence from the listener's side, and
     * waiting for it forever is what put the app into half an hour of silence.
     */
    fun starved(nowMs: Long): Boolean {
        val budget = if (playing) playingStarveTimeoutMs else starveTimeoutMs
        return nowMs - lastDataAtMs >= budget
    }
}
