package io.github.manuelrtsch.sidetrack

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The seek bar's clock, and the small state machine that decides which position it believes.
 *
 * The UI position is not the player's position. It is interpolated from a monotonic anchor so the
 * bar moves at 1.0x through look-ahead and buffering, and it ignores player events while a local
 * seek, a newly started item or a resumed item still owns the truth. Those four claims on the
 * position used to be loose fields inside the ViewModel; keeping them together is what makes the
 * rules (see the `RESUME_*`, `SEEK_*` and `POSITION_*` constants) inspectable in one place.
 *
 * Collaborators supply the effects: [onPosition] mirrors a position onto the media session,
 * [onPlaying] reports a confirmed resume, and [onEpisodeProgress] writes the podcast resume point.
 */
internal class PlaybackClock(
    private val snapshot: () -> SidetrackUiState,
    private val onPosition: (Long) -> Unit,
    private val onPlaying: (Long) -> Unit,
    private val onEpisodeProgress: () -> Unit,
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private val _positionMs = MutableStateFlow(0L)

    /** The position the seek bar renders. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** The current position without going through the flow. */
    val value: Long get() = _positionMs.value

    private var anchorMs = 0L
    private var anchorAt = 0L

    /** Set while a newly started item has not been confirmed by the native player. */
    private var awaitingStart = false
    private var awaitingStartAt = 0L

    /** The target of the most recent local seek, until the player confirms it. */
    private var seek: Seek? = null

    /** Which offset a restored track was started at, so the old generation's events can be spotted. */
    private var resume: Resume? = null

    private data class Seek(val targetMs: Long, val atMs: Long)

    private data class Resume(val itemId: String, val positionMs: Long, val atMs: Long)

    /** True while a just-started item still owns the position and player events must be ignored. */
    val awaitingStartConfirmation: Boolean get() = awaitingStart

    fun set(positionMs: Long) {
        _positionMs.value = positionMs
    }

    /** Anchors the interpolation clock; the position itself is unchanged. */
    fun anchor(positionMs: Long) {
        anchorMs = positionMs
        anchorAt = now()
    }

    fun anchorCurrent() = anchor(_positionMs.value)

    /** A real item is about to start: the bar holds at [positionMs] until the player confirms it. */
    fun beginStart(positionMs: Long, resumeFor: String? = null) {
        seek = null
        anchor(positionMs)
        awaitingStart = true
        awaitingStartAt = now()
        resume = resumeFor?.let { Resume(it, positionMs, now()) }
    }

    fun endStart() {
        awaitingStart = false
    }

    /** The audio is running at [positionMs]: let the bar run from there instead of waiting for an event. */
    fun confirmStart(positionMs: Long) {
        awaitingStart = false
        anchor(positionMs)
        _positionMs.value = positionMs
        onPlaying(positionMs)
    }

    fun beginSeek(targetMs: Long) {
        seek = Seek(targetMs, now())
        anchor(targetMs)
        _positionMs.value = targetMs
    }

    fun clearSeek() {
        seek = null
    }

    /** The active local seek target, or null once the player has had enough time to confirm it. */
    fun activeSeekTarget(): Long? {
        val hold = seek ?: return null
        if (now() - hold.atMs > SEEK_CONFIRM_TIMEOUT_MS) {
            seek = null
            return null
        }
        return hold.targetMs
    }

    /** Adopts a confirmed seek position, keeping the UI and the media session on the audio clock. */
    fun adoptSeek(positionMs: Long) {
        seek = null
        anchor(positionMs)
        onPosition(positionMs)
        _positionMs.value = positionMs
    }

    /**
     * True while a player event still describes the generation that was stopped: it reports a
     * position well below the offset the user resumed from. Adopting it would move the bar back to
     * the start even though the audio is playing further in.
     */
    fun staleResumeEvent(eventPositionMs: Long): Boolean {
        val anchor = resume ?: return false
        if (now() - anchor.atMs > RESUME_GRACE_MS) {
            resume = null
            return false
        }
        return eventPositionMs + RESUME_EVENT_SLACK_MS < anchor.positionMs
    }

    /** Drops the resume window when the queue moves to a different item. */
    fun onItemChanged(itemId: String, previousId: String?) {
        if (itemId != previousId) {
            seek = null
        }
    }

    /**
     * Advances the seek bar from the monotonic anchor. The AudioTrack playback head is not stable
     * across pause/resume on this device, so the system clock is used instead: it advances at the
     * same 1.0x rate and never jumps.
     */
    fun advanceTick() {
        val state = snapshot()
        if (!state.isPlaying || state.isBuffering) return
        val current = state.current ?: return
        // The bar has been running from the seek target. Once the player had ample time to
        // confirm, stop suppressing unrelated position events.
        val hold = seek
        if (hold != null && now() - hold.atMs > SEEK_CONFIRM_TIMEOUT_MS) {
            seek = null
        }
        if (awaitingStart) {
            if (now() - awaitingStartAt < PLAYBACK_START_TIMEOUT_MS) return
            awaitingStart = false
        }
        val next = anchorMs + (now() - anchorAt)
        val clamped = next.coerceIn(0L, current.durationMs)
        if (clamped != _positionMs.value) {
            onPosition(clamped)
            _positionMs.value = clamped
            onEpisodeProgress()
        }
    }
}
