package io.github.manuelrtsch.sidetrack.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import hrln.interactive.sidekit.design.icons.SidekitIcon
import io.github.manuelrtsch.sidetrack.SidetrackActivity
import io.github.manuelrtsch.sidetrack.SidetrackLinks
import io.github.manuelrtsch.sidetrack.data.SpotifySessions

/**
 * Keeps Sidetrack playing when SideKit is not in front. It mirrors [SpotifyPlayback]'s media
 * session in a platform media-style notification and forwards transport actions back to the
 * process-wide player.
 */
class SpotifyPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        // Only the channel here, and deliberately without resolving SpotifySessions: building the
        // session constructs the native library, reads the encrypted credential store and touches
        // disk, none of which may sit inside the five second foreground-service deadline.
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    /** True while the service holds a foreground notification; guarded so a warm start is quiet. */
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote before anything that can block. The deadline runs from the
        // startForegroundService() call, so a cold process that spends seconds in SpotifySessions
        // would otherwise die with ForegroundServiceDidNotStartInTimeException — which is exactly
        // how playback used to crash on the first track of a fresh process. Every transport action
        // from the notification starts the service again, so only do this when it is not already
        // foreground: otherwise the real notification is replaced by the placeholder on each press.
        if (!foreground) {
            startForeground(
                NOTIFICATION_ID,
                placeholderNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            foreground = true
        }
        val session = SpotifySessions.get(this)
        val playback = session.playback
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> session.togglePlayback()
            ACTION_NEXT -> playback.requestNext()
            ACTION_PREVIOUS -> playback.requestPrevious()
            ACTION_STOP -> session.stopPlayback()
        }
        update(playback)
        return START_NOT_STICKY
    }

    /**
     * Closing the app halts the audio: the window is gone, so nothing should keep playing behind
     * the launcher. Only the sound goes away, though — the queue, the history and the selected
     * track stay saved, so reopening Sidetrack restores them. Merely leaving the app in the
     * background is not a close, so the notification keeps playing there.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        SpotifySessions.get(this).haltPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (running === this) running = null
        foreground = false
        super.onDestroy()
    }

    private fun update(playback: SpotifyPlayback) {
        when {
            playback.current == null -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
            }

            playback.isActive -> {
                startForeground(
                    playback.notificationId,
                    notification(playback, playing = true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                foreground = true
            }

            else -> {
                getSystemService(NotificationManager::class.java)
                    .notify(playback.notificationId, notification(playback, playing = false))
                stopForeground(STOP_FOREGROUND_DETACH)
                foreground = false
            }
        }
    }

    /**
     * Notification posted the instant the service starts, before the player state is available.
     * It only has to satisfy the foreground requirement; [update] replaces it right after.
     */
    private fun placeholderNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(SidekitIcon.MusicNote2.drawable)
        .setContentTitle("Sidetrack")
        .setContentIntent(openIntent())
        .setOngoing(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .build()

    private fun notification(playback: SpotifyPlayback, playing: Boolean): Notification {
        val timer = playback.sleepTimer.value
        val timerSuffix = timer.notificationSuffix()
        val creator = playback.current?.creator.orEmpty()
        val contentText = buildString {
            append(creator)
            if (timerSuffix != null) {
                if (isNotEmpty()) append(" · ")
                append(timerSuffix)
            }
        }
        return Notification.Builder(this, playback.notificationChannelId)
            .setSmallIcon(SidekitIcon.MusicNote2.drawable)
            .setContentTitle(playback.current?.title ?: "Sidetrack")
            .setContentText(contentText)
            .setSubText(if (timerSuffix != null) "Sleep timer" else null)
            .setLargeIcon(playback.notificationArt)
            .setContentIntent(openIntent())
            .setDeleteIntent(action(ACTION_STOP))
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, SidekitIcon.SkipPrevious.drawable),
                    "Previous",
                    action(ACTION_PREVIOUS)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (playing) SidekitIcon.Pause.drawable else SidekitIcon.PlayArrow.drawable
                    ),
                    if (playing) "Pause" else "Play",
                    action(ACTION_PLAY_PAUSE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, SidekitIcon.SkipNext.drawable),
                    "Next",
                    action(ACTION_NEXT)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(playback.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, SidetrackActivity::class.java)
            .setAction(SidetrackLinks.ACTION_OPEN_NOW_PLAYING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(action: String): PendingIntent = PendingIntent.getForegroundService(
        this,
        action.hashCode(),
        Intent(this, SpotifyPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        /**
         * Channel and id live here so the placeholder can be posted without a player, and
         * [SpotifyPlayback] reads them from here so the two cannot drift apart.
         */
        const val CHANNEL_ID = "sidetrack"
        const val CHANNEL_NAME = "Sidetrack playback"
        const val NOTIFICATION_ID = 0x6D76

        private const val ACTION_PLAY_PAUSE = "io.github.manuelrtsch.sidetrack.PLAY_PAUSE"
        private const val ACTION_NEXT = "io.github.manuelrtsch.sidetrack.NEXT"
        private const val ACTION_PREVIOUS = "io.github.manuelrtsch.sidetrack.PREVIOUS"
        private const val ACTION_STOP = "io.github.manuelrtsch.sidetrack.STOP"

        private var running: SpotifyPlaybackService? = null

        /** Rebuilds the notification for a state-only change, such as a sleep-timer minute tick. */
        fun refresh(context: Context, playback: SpotifyPlayback) {
            running?.update(playback)
        }

        /** Brings the service in line with the player. Main thread only, like the player. */
        fun sync(context: Context, playback: SpotifyPlayback) {
            val service = running
            if (service != null) {
                service.update(playback)
                return
            }
            if (!playback.isActive || playback.current == null) return
            try {
                context.startForegroundService(Intent(context, SpotifyPlaybackService::class.java))
            } catch (_: IllegalStateException) {
                // Not allowed from the background. Playback still works, just without the
                // foreground-service protection on this edge.
            }
        }
    }
}
