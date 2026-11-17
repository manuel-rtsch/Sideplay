package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * [Media3Player] backed by a real ExoPlayer.
 *
 * Audio focus and the "becoming noisy" receiver stay in [PlaybackController], so both are turned
 * off here; otherwise ExoPlayer and the controller would fight over the same events.
 *
 * ExoPlayer is single-threaded: create and use this from the main thread.
 */
class ExoPlayerMedia3Player(
    context: Context,
    /** Applied to every request, so authenticated streams need no token in the URL. */
    private val userAgent: String = DEFAULT_USER_AGENT
) : Media3Player {
    override var listener: Media3PlayerListener? = null

    /**
     * ExoPlayer builds its data source per load, so the current source's headers are read here
     * instead of rebuilding the player for every track.
     */
    private val headers = HeaderDataSourceFactory(userAgent)

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(headers))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */
            false
        )
        .setHandleAudioBecomingNoisy(false)
        .build()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    listener?.onStatusChanged(playbackState.toStatus())
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listener?.onIsPlayingChanged(isPlaying)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    listener?.onPositionDiscontinuity(newPosition.positionMs)
                }

                override fun onPlayerError(error: PlaybackException) {
                    listener?.onPlayerError(error)
                }
            }
        )
    }

    override fun prepare(source: PlaybackSource, startPositionMs: Long, playWhenReady: Boolean) {
        headers.headers = source.headers
        player.setMediaItem(MediaItem.fromUri(source.uri), startPositionMs)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun release() {
        player.release()
    }

    override val isPlaying: Boolean get() = player.isPlaying

    override val currentPositionMs: Long get() = player.currentPosition

    override val status: Media3PlayerStatus get() = player.playbackState.toStatus()

    private fun Int.toStatus(): Media3PlayerStatus = when (this) {
        Player.STATE_BUFFERING -> Media3PlayerStatus.Buffering
        Player.STATE_READY -> Media3PlayerStatus.Ready
        Player.STATE_ENDED -> Media3PlayerStatus.Ended
        else -> Media3PlayerStatus.Idle
    }

    private class HeaderDataSourceFactory(
        private val userAgent: String
    ) : DataSource.Factory {
        @Volatile
        var headers: Map<String, String> = emptyMap()

        override fun createDataSource(): DataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            // Subsonic servers commonly redirect to a CDN or another host.
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
            .createDataSource()
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "Sidetrack"
    }
}
