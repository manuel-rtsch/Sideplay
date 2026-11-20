package io.github.manuelrtsch.sidetrack.playback

import android.content.Context

/**
 * App-installed notification hooks, plus the channel identity the media player uses, kept together
 * so core's [PlaybackController] and the app's notification service cannot drift apart.
 *
 * Core never names the app's service: it calls these hooks, and the app installs the real
 * implementations once at startup (see PlaybackService). Defaults are no-ops so tests and any
 * player built before the hooks are installed never touch the notification system.
 */
object PlaybackNotificationHooks {
    const val CHANNEL_ID = "sidetrack"
    const val CHANNEL_NAME = "Sidetrack playback"
    const val NOTIFICATION_ID = 0x6D76

    /** Rebuilds the notification for a state-only change, such as a sleep-timer minute tick. */
    var refresh: (Context, PlaybackController) -> Unit = { _, _ -> }

    /** Starts or brings the notification in line with the player. Main thread only. */
    var sync: (Context, PlaybackController) -> Unit = { _, _ -> }
}
