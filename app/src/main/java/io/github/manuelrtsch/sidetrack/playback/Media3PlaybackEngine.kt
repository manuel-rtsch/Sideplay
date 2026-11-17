package io.github.manuelrtsch.sidetrack.playback

import io.github.manuelrtsch.sidetrack.SidetrackDebug
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The player states [Media3PlaybackEngine] cares about, independent of ExoPlayer's constants. */
enum class Media3PlayerStatus { Idle, Buffering, Ready, Ended }

/**
 * The small slice of a Media3 player the engine needs.
 *
 * Keeping this as an interface does two things: the engine can be unit-tested without an Android
 * audio device, and the ExoPlayer-specific code stays in [ExoPlayerMedia3Player]. Implementations
 * must be used from the thread that created them (the main thread for ExoPlayer).
 */
interface Media3Player {
    var listener: Media3PlayerListener?

    /** Replaces the current item and starts preparing it. */
    fun prepare(source: PlaybackSource, startPositionMs: Long, playWhenReady: Boolean)

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()

    val isPlaying: Boolean
    val currentPositionMs: Long
    val status: Media3PlayerStatus
}

/** Player callbacks, already translated out of ExoPlayer's listener vocabulary. */
interface Media3PlayerListener {
    fun onStatusChanged(status: Media3PlayerStatus)
    fun onIsPlayingChanged(isPlaying: Boolean)
    fun onPositionDiscontinuity(positionMs: Long)
    fun onPlayerError(error: Throwable)
}

/**
 * [PlaybackEngine] over Media3/ExoPlayer for HTTP music services (Navidrome and friends).
 *
 * Spotify keeps [LibrespotPlaybackEngine]; this engine never sees a Spotify URI and librespot
 * never sees an HTTP stream. The controller, media session, notification, audio focus and sleep
 * timer are shared and unchanged.
 *
 * [PlaybackSource.headers] are applied to every request the player makes, so an authenticated
 * stream URL does not have to carry its token in the query string.
 */
class Media3PlaybackEngine(
    private val playerFactory: () -> Media3Player
) : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackEngineState())
    override val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    override var onFailure: (Throwable) -> Unit = {}

    /** Serializes rebuilds so two settings toggles cannot install an old player last. */
    private val reconfigureLock = Mutex()

    private val events = ConcurrentLinkedQueue<PlaybackEvent>()

    private var player: Media3Player? = null

    /** The source the current player was prepared with; events carry its URI. */
    @Volatile
    private var activeSource: PlaybackSource? = null

    private val listener = object : Media3PlayerListener {
        override fun onStatusChanged(status: Media3PlayerStatus) {
            when (status) {
                Media3PlayerStatus.Buffering -> {
                    _state.update { it.copy(isBuffering = true) }
                    events.add(PlaybackEvent.Loading(activeSource?.uri, _state.value.positionMs))
                }

                Media3PlayerStatus.Ready -> _state.update { it.copy(isBuffering = false) }

                Media3PlayerStatus.Ended -> {
                    _state.update { it.copy(isPlaying = false, isBuffering = false) }
                    events.add(PlaybackEvent.Ended(activeSource?.uri))
                }

                Media3PlayerStatus.Idle -> _state.update { it.copy(isBuffering = false) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            val uri = activeSource?.uri
            val position = _state.value.positionMs
            events.add(
                if (isPlaying) {
                    PlaybackEvent.Playing(uri, position)
                } else {
                    PlaybackEvent.Paused(uri, position)
                }
            )
        }

        override fun onPositionDiscontinuity(positionMs: Long) {
            _state.update { it.copy(positionMs = positionMs) }
            events.add(PlaybackEvent.Seeked(positionMs))
        }

        override fun onPlayerError(error: Throwable) {
            SidetrackDebug.log("media3 player error", error)
            _state.update { it.copy(isPlaying = false, isBuffering = false) }
            events.add(PlaybackEvent.Unavailable(activeSource?.uri))
            onFailure(error)
        }
    }

    override suspend fun start(source: PlaybackSource, positionMs: Long, keepAudioSink: Boolean) {
        SidetrackDebug.log("media3.start uri=${source.uri} positionMs=$positionMs")
        activeSource = source
        _state.update {
            it.copy(source = source, isPlaying = true, positionMs = positionMs, isBuffering = false)
        }
        try {
            ensurePlayer().prepare(source, positionMs, playWhenReady = true)
        } catch (t: Throwable) {
            SidetrackDebug.log("media3.start failed", t)
            _state.update { it.copy(isPlaying = false) }
            throw t
        }
    }

    override suspend fun resume(positionMs: Long): Boolean {
        val player = player ?: return false
        if (activeSource == null) return false
        _state.update { it.copy(isPlaying = true, positionMs = positionMs) }
        player.play()
        return true
    }

    override suspend fun pause() {
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        player?.pause()
    }

    override suspend fun stop() {
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        player?.stop()
    }

    override suspend fun seek(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        player?.seekTo(positionMs)
    }

    /**
     * ExoPlayer has no single-item preload. The next item is warmed by the queue owner resolving
     * its stream URL; the player itself only ever holds the current source.
     */
    override suspend fun preload(source: PlaybackSource) = Unit

    override suspend fun reconfigure(
        source: PlaybackSource?,
        positionMs: Long,
        wasPlaying: Boolean
    ): Boolean = reconfigureLock.withLock {
        SidetrackDebug.log(
            "media3.reconfigure uri=${source?.uri} positionMs=$positionMs wasPlaying=$wasPlaying"
        )
        releasePlayer()
        activeSource = source
        _state.update {
            it.copy(source = source, isPlaying = wasPlaying, positionMs = positionMs, isBuffering = false)
        }
        if (source != null) {
            try {
                ensurePlayer().prepare(source, positionMs, playWhenReady = wasPlaying)
            } catch (t: Throwable) {
                SidetrackDebug.log("media3.reconfigure failed", t)
                _state.update { it.copy(isPlaying = false) }
                throw t
            }
        }
        true
    }

    override suspend fun disconnect() {
        stop()
        releasePlayer()
        activeSource = null
        _state.value = PlaybackEngineState()
    }

    override suspend fun pollEvent(): PlaybackEvent? = events.poll()

    override fun invalidateCommands() {
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
    }

    override fun updatePosition(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun updateState(isPlaying: Boolean, positionMs: Long) {
        _state.update { it.copy(isPlaying = isPlaying, positionMs = positionMs) }
    }

    private fun ensurePlayer(): Media3Player = player ?: playerFactory().also {
        it.listener = listener
        player = it
    }

    private fun releasePlayer() {
        player?.let {
            it.listener = null
            runCatching { it.release() }
        }
        player = null
    }
}
