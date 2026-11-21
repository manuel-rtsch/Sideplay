package io.github.manuelrtsch.sidetrack.playback

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
        val remainingMinutes: Int = minutes,
        /**
         * Exact time left when playback paused. While set the clock is stopped: the deadline is
         * stale and [remainingMinutes] is held until playback resumes and rebases the deadline.
         */
        val heldMs: Long? = null
    ) : SleepTimerState

    data object EndOfTrack : SleepTimerState

    /**
     * Playback was stopped by the timer. It stays visible, explaining the pause, until playback
     * starts again or the process that holds the timer ends.
     */
    data object Finished : SleepTimerState

    val active: Boolean get() = this == EndOfTrack || this is Countdown
}

/** Whole minutes left, rounded up so the first minute still reads as the chosen duration. */
fun sleepTimerRemainingMinutes(deadlineElapsedMs: Long, nowElapsedMs: Long): Int = sleepTimerRemainingMinutes(deadlineElapsedMs - nowElapsedMs)

/** Whole minutes left in an already-computed remainder, rounded up. */
fun sleepTimerRemainingMinutes(remainingMs: Long): Int {
    if (remainingMs <= 0L) return 0
    return ((remainingMs + MINUTE_MS - 1) / MINUTE_MS).toInt()
}

/**
 * Slim status line on Now Playing. Every state describes what the timer does rather than naming a
 * bare remainder, and all three share the verb: "Pauses in 23 min", "Pauses after this track",
 * "Paused by sleep timer". The line reads on its own, with or without the bedtime icon, and the
 * wording holds for music and podcasts where "sleep" would be a metaphor too many. Null when no
 * timer is active.
 */
fun SleepTimerState.statusText(): String? = when (this) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfTrack -> "Pauses after this track"
    is SleepTimerState.Countdown -> "Pauses in $remainingMinutes min"
    SleepTimerState.Finished -> "Paused by sleep timer"
}

private fun SleepTimerState.Countdown.minuteWord(): String = if (remainingMinutes == 1) "minute" else "minutes"

/** TalkBack description for the non-interactive status line. */
fun SleepTimerState.statusDescription(): String? = when (this) {
    SleepTimerState.Off -> null
    SleepTimerState.EndOfTrack -> "Sleep timer, pauses after this track"
    is SleepTimerState.Countdown -> "Sleep timer, pauses in $remainingMinutes ${minuteWord()}"
    SleepTimerState.Finished -> "Sleep timer finished, playback paused"
}

/**
 * Overline indicator on Home's now-playing tile, replacing the NOW PLAYING label. Uppercase, like
 * every label in that eyebrow slot, and short: the tile shows one line, so the widest entry here
 * sets the budget. Nothing exceeds "NOTHING PLAYING" (15 characters); the tile's icon carries the
 * playback state, so the timer wording does not repeat "PAUSED".
 */
fun SleepTimerState.tileText(): String? = when (this) {
    SleepTimerState.Off -> null

    SleepTimerState.EndOfTrack -> "END OF TRACK"

    is SleepTimerState.Countdown -> "$remainingMinutes MIN LEFT"

    // Shorter than the Now Playing line: the home overline must not ellipsize.
    SleepTimerState.Finished -> "PAUSED BY TIMER"
}

/** Secondary notification line, appended to the artist. Null when no timer is active. */
fun SleepTimerState.notificationSuffix(): String? = when (this) {
    SleepTimerState.Off -> null

    SleepTimerState.EndOfTrack -> "pauses after this track"

    is SleepTimerState.Countdown -> "pauses in ${remainingMinutes} min"

    // The timer already did its job; a suffix would claim one is still running.
    SleepTimerState.Finished -> null
}
