package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.MediaItem

/**
 * Owns the platform [MediaSession]: it is created on first use, its transport callbacks are routed
 * to the controller, and its playback state and metadata are kept in step with the player.
 *
 * Metadata is throttled because a session update is a binder call and the position changes far
 * more often than the track does; artwork only goes out with the item it belongs to.
 */
internal class PlaybackSessionBinder(
    private val context: Context?,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onStop: () -> Unit
) {
    private var session: MediaSession? = null
    private var lastMetadataAt = 0L

    val token: MediaSession.Token? get() = session()?.sessionToken

    fun publish(item: MediaItem?, playing: Boolean, positionMs: Long, art: Bitmap?) {
        val session = session() ?: return
        val now = SystemClock.elapsedRealtime()
        if (item != null) updateMetadata(session, item, art, now)
        session.setPlaybackState(
            SessionState.Builder()
                .setActions(
                    SessionState.ACTION_PLAY or SessionState.ACTION_PAUSE or
                        SessionState.ACTION_PLAY_PAUSE or SessionState.ACTION_SKIP_TO_NEXT or
                        SessionState.ACTION_SKIP_TO_PREVIOUS or SessionState.ACTION_SEEK_TO or
                        SessionState.ACTION_STOP
                )
                .setState(
                    when {
                        item == null -> SessionState.STATE_STOPPED
                        playing -> SessionState.STATE_PLAYING
                        else -> SessionState.STATE_PAUSED
                    },
                    positionMs,
                    if (playing) 1f else 0f,
                    now
                )
                .build()
        )
    }

    /** Writes the metadata now, unless the throttle window has not elapsed yet. */
    fun updateMetadata(item: MediaItem?, art: Bitmap?) {
        val session = session() ?: return
        item ?: return
        updateMetadata(session, item, art, SystemClock.elapsedRealtime())
    }

    /** Lets the next update carry new artwork even if the throttle window is still open. */
    fun clearMetadataThrottle() {
        lastMetadataAt = 0L
    }

    /** Marks the session stopped while keeping it alive for the next item. */
    fun markStopped() {
        session?.setPlaybackState(
            SessionState.Builder()
                .setState(SessionState.STATE_STOPPED, 0L, 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    fun release() {
        session?.release()
        session = null
    }

    private fun updateMetadata(session: MediaSession, item: MediaItem, art: Bitmap?, now: Long) {
        if (lastMetadataAt != 0L && now - lastMetadataAt < METADATA_INTERVAL_MS) return
        lastMetadataAt = now
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.creator)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item.creator)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, item.durationMs)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
    }

    private fun session(): MediaSession? {
        session?.let { return it }
        val context = context ?: return null
        val created = MediaSession(context, "SideKit Sidetrack").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = this@PlaybackSessionBinder.onPlay()

                    override fun onPause() = this@PlaybackSessionBinder.onPause()

                    override fun onSkipToNext() = this@PlaybackSessionBinder.onNext()

                    override fun onSkipToPrevious() = this@PlaybackSessionBinder.onPrevious()

                    override fun onSeekTo(pos: Long) = this@PlaybackSessionBinder.onSeekTo(pos)

                    override fun onStop() = this@PlaybackSessionBinder.onStop()
                }
            )
            isActive = true
        }
        session = created
        return created
    }

    private companion object {
        /** Minimum gap between metadata writes; the position is published separately. */
        const val METADATA_INTERVAL_MS = 250L
    }
}
