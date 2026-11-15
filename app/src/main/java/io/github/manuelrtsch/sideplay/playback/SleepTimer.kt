package io.github.manuelrtsch.sideplay.playback

/** The durations the Sleep timer picker offers, in minutes. */
val SleepTimerDurations = listOf(5, 15, 30, 45, 60, 90)

private const val MINUTE_MS = 60_000L

/**
 * The process-wide sleep timer. It is deliberately a small, pure state model: the deadline is an
 * elapsed-realtime value so clock changes cannot move it, and `EndOfTrack` is a mode rather than a
 * computed position so seeking never desynchronises it.
 */
sealed interface SleepTimerState {
    data object Off : SleepTimerState

    data class Countdown(
        /** The duration the user picked, kept so the picker can label the active row. */
        val minutes: Int,
        val deadlineElapsedMs: Long,
        /** Whole minutes still on the clock, rounded up. Updated once per minute. */
        val remainingMinutes: Int = minutes
    ) : SleepTimerState

    data object EndOfTrack : SleepTimerState

    val active: Boolean get() = this != Off
}

/** Whole minutes left, rounded up so the first minute still reads as the chosen duration. */
fun sleepTimerRemainingMinutes(deadlineElapsedMs: Long, nowElapsedMs: Long): Int {
    val left = deadlineElapsedMs - nowElapsedMs
    if (left <= 0L) return 0
    return ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()
}

/** Slim status line on Now Playing. Null when no timer is active. */
fun SleepTimerState.statusText(): String? = when (this) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfTrack -> "ends with this track"
    is SleepTimerState.Countdown -> "$remainingMinutes min left"
}

private fun SleepTimerState.Countdown.minuteWord(): String =
    if (remainingMinutes == 1) "minute" else "minutes"

/** TalkBack description for the non-interactive status line. */
fun SleepTimerState.statusDescription(): String? = when (this) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfTrack -> "Sleep timer, ends with this track"
    is SleepTimerState.Countdown -> "Sleep timer, $remainingMinutes ${minuteWord()} left"
}

/** Overline indicator on Home's now-playing tile, replacing the NOW PLAYING label. */
fun SleepTimerState.tileText(): String? = when (this) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfTrack -> "end of track"
    is SleepTimerState.Countdown -> "$remainingMinutes min left"
}

/** Secondary notification line, appended to the artist. Null when no timer is active. */
fun SleepTimerState.notificationSuffix(): String? = when (this) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfTrack -> "ends with this track"
    is SleepTimerState.Countdown -> "sleep ${remainingMinutes}m"
}
