package io.github.manuelrtsch.sideplay.playback

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
import io.github.manuelrtsch.sideplay.SideplayActivity
import io.github.manuelrtsch.sideplay.SideplayLinks
import io.github.manuelrtsch.sideplay.data.SpotifySessions

/**
 * Keeps Sideplay playing when SideKit is not in front. It mirrors [SpotifyPlayback]'s media
 * session in a platform media-style notification and forwards transport actions back to the
 * process-wide player.
 */
class SpotifyPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        val playback = SpotifySessions.get(this).playback
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(playback.notificationChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    playback.notificationChannelId,
                    playback.notificationChannelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = SpotifySessions.get(this)
        val playback = session.playback
        startForeground(
            playback.notificationId,
            notification(playback, playing = playback.isActive),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> session.togglePlayback()
            ACTION_NEXT -> playback.requestNext()
            ACTION_PREVIOUS -> playback.requestPrevious()
            ACTION_STOP -> session.stopPlayback()
        }
        update(playback)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    private fun update(playback: SpotifyPlayback) {
        when {
            playback.current == null -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            playback.isActive -> startForeground(
                playback.notificationId,
                notification(playback, playing = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )

            else -> {
                getSystemService(NotificationManager::class.java)
                    .notify(playback.notificationId, notification(playback, playing = false))
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        }
    }

    private fun notification(playback: SpotifyPlayback, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SideplayActivity::class.java)
                .setAction(SideplayLinks.ACTION_OPEN_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, playback.notificationChannelId)
            .setSmallIcon(SidekitIcon.MusicNote2.drawable)
            .setContentTitle(playback.current?.title ?: "Sideplay")
            .setContentText(playback.current?.creator.orEmpty())
            .setLargeIcon(playback.notificationArt)
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP))
            .setOngoing(playing)
            .setShowWhen(false)
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

    private fun action(action: String): PendingIntent = PendingIntent.getForegroundService(
        this,
        action.hashCode(),
        Intent(this, SpotifyPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val ACTION_PLAY_PAUSE = "io.github.manuelrtsch.sideplay.PLAY_PAUSE"
        private const val ACTION_NEXT = "io.github.manuelrtsch.sideplay.NEXT"
        private const val ACTION_PREVIOUS = "io.github.manuelrtsch.sideplay.PREVIOUS"
        private const val ACTION_STOP = "io.github.manuelrtsch.sideplay.STOP"

        private var running: SpotifyPlaybackService? = null

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
