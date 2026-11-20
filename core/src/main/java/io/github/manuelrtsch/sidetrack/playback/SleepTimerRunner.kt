package io.github.manuelrtsch.sidetrack.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The stateful side of the sleep timer: owns the countdown job and the published state.
 *
 * The pure state model stays in SleepTimer.kt. This class only drives it: it starts the job when
 * playback is running, holds the remaining time while playback is paused, and reports expiry
 * through [onExpire] so the controller can pause without knowing the timer's internals.
 */
internal class SleepTimerRunner(
    private val scope: CoroutineScope,
    private val playing: () -> Boolean,
    private val elapsedTime: () -> Long,
    private val onExpire: () -> Unit
) {

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var job: Job? = null

    /** Replaces any running timer with a fresh countdown. */
    fun set(minutes: Int) {
        if (minutes <= 0) {
            clear()
            return
        }
        val total = minutes * MINUTE_MS
        // The countdown measures listening time, so a timer armed while paused waits for playback.
        install(
            if (playing()) {
                SleepTimerState.Countdown(
                    minutes = minutes,
                    deadlineElapsedMs = elapsedTime() + total,
                    remainingMinutes = minutes
                )
            } else {
                SleepTimerState.Countdown(
                    minutes = minutes,
                    deadlineElapsedMs = 0L,
                    remainingMinutes = minutes,
                    heldMs = total
                )
            }
        )
    }

    /** Stops when the current track ends instead of advancing the queue. */
    fun setEndOfTrack() = install(SleepTimerState.EndOfTrack)

    fun clear() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState.Off
    }

    /**
     * Called when the player reports the end of a track. Returns true once when the end-of-track
     * mode consumed the event, so normal repeat/advance must not run.
     */
    fun consumeEndOfTrack(): Boolean {
        if (_state.value != SleepTimerState.EndOfTrack) return false
        _state.value = SleepTimerState.Finished
        return true
    }

    /** Clears the finished notice once a different item starts. */
    fun clearFinished() {
        if (_state.value == SleepTimerState.Finished) _state.value = SleepTimerState.Off
    }

    /** A start or resume ends the finished notice and lets a held countdown run again. */
    fun onPlaybackStarted() {
        if (_state.value == SleepTimerState.Finished) {
            _state.value = SleepTimerState.Off
        }
        resumeClock()
    }

    /** Freezes a running countdown while no audio plays, so a paused night does not spend it. */
    fun hold() {
        val current = _state.value as? SleepTimerState.Countdown ?: return
        if (current.heldMs != null) return
        val remaining = (current.deadlineElapsedMs - elapsedTime()).coerceAtLeast(0L)
        job?.cancel()
        job = null
        if (remaining == 0L) {
            _state.value = SleepTimerState.Finished
        } else {
            _state.value = current.copy(
                heldMs = remaining,
                remainingMinutes = sleepTimerRemainingMinutes(remaining)
            )
        }
    }

    private fun install(state: SleepTimerState) {
        job?.cancel()
        job = null
        _state.value = state
        val countdown = state as? SleepTimerState.Countdown ?: return
        if (countdown.heldMs != null) return
        job = scope.launch {
            while (true) {
                val current = _state.value as? SleepTimerState.Countdown ?: return@launch
                if (current.heldMs != null) return@launch
                val now = elapsedTime()
                if (now >= current.deadlineElapsedMs) {
                    expire()
                    return@launch
                }
                val remaining = sleepTimerRemainingMinutes(current.deadlineElapsedMs, now)
                if (remaining != current.remainingMinutes) {
                    _state.value = current.copy(remainingMinutes = remaining)
                }
                // Wake exactly when the whole-minute value changes; no per-second ticking.
                val nextChange = current.deadlineElapsedMs - (remaining - 1) * MINUTE_MS
                delay((nextChange - now).coerceAtLeast(MIN_DELAY_MS))
            }
        }
    }

    private fun expire() {
        job?.cancel()
        job = null
        // Kept until playback starts again, so a returning user still learns what stopped the music.
        _state.value = SleepTimerState.Finished
        // Pause never touches system volume, and an already-paused player needs no pause call.
        if (playing()) onExpire()
    }

    /** Rebases a held countdown onto the current instant when playback starts again. */
    private fun resumeClock() {
        val current = _state.value as? SleepTimerState.Countdown ?: return
        val held = current.heldMs ?: return
        install(
            current.copy(
                deadlineElapsedMs = elapsedTime() + held,
                remainingMinutes = sleepTimerRemainingMinutes(held),
                heldMs = null
            )
        )
    }

    private companion object {
        const val MINUTE_MS = 60_000L

        /** Guards against a zero-delay spin when the clock lands exactly on a minute edge. */
        const val MIN_DELAY_MS = 1_000L
    }
}
