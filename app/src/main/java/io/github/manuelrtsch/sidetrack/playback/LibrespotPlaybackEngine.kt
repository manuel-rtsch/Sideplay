package io.github.manuelrtsch.sidetrack.playback

import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.data.SpotifyGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [PlaybackEngine] over librespot: the existing native gateway and PCM sink, with the private
 * JSON event protocol moved behind [pollEvent].
 *
 * This is deliberately a transport object. Media session, notification, audio focus, the noisy
 * receiver and the sleep timer stay in [PlaybackController].
 */
class LibrespotPlaybackEngine(
    private val gateway: SpotifyGateway,
    private val audio: SpotifyAudioSink?,
    /** The native event queue. Passed in so the engine owns polling but not the JNI bridge. */
    private val eventSource: () -> String? = { null }
) : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackEngineState())
    override val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    /**
     * Reports a drain that stopped producing audio. The session that owns the native player
     * installs the recovery path; the sink cannot restart a dead librespot session itself.
     */
    override var onFailure: (Throwable) -> Unit = {}

    /** Invalidates a pending command when a newer one arrives. Main-thread only. */
    private var commandGeneration = 0

    /** Serializes player rebuilds so two settings toggles cannot install an old config last. */
    private val reconfigureLock = Mutex()

    /**
     * A setting changed that needs a new native player while something was playing. A rebuild
     * stops the sink and fetches the item again, which is audible, so the change waits for a
     * moment that is already silent: the next start that resets the sink, or the next pause.
     */
    private var reconfigurePending = false

    init {
        audio?.onBuffering = { buffering ->
            _state.update { it.copy(isBuffering = buffering) }
        }
        audio?.onFailure = { error -> onFailure(error) }
    }

    override suspend fun start(source: PlaybackSource, positionMs: Long, keepAudioSink: Boolean) {
        SidetrackDebug.log("engine.start uri=${source.uri} positionMs=$positionMs keepAudioSink=$keepAudioSink")
        val generation = ++commandGeneration
        _state.update {
            it.copy(source = source, isPlaying = true, positionMs = positionMs, isBuffering = false)
        }
        // Drop the previous item's audio before loading the next one. Draining also unblocks a
        // player thread that may still be waiting in PcmSink::write, so the load command can be
        // processed. The AudioTrack itself is kept for the next item: recreating it re-configures
        // the output route, which is audible as a gap on Bluetooth.
        val sinkKept = keepAudioSink && audio?.isRunning == true
        if (!sinkKept) {
            // A setting changed while the previous item was playing. This start resets the sink
            // anyway, so the new player costs no extra silence; the load below gives it a source.
            recreatePlayerIfPending()
            audio?.reset()
        }
        if (generation != commandGeneration) return
        try {
            SidetrackDebug.log("engine.start calling gateway.load")
            gateway.load(source.uri, positionMs, autoplay = true)
            if (generation != commandGeneration) return
            if (!sinkKept) {
                SidetrackDebug.log("engine.start gateway.load returned; starting audio sink")
                audio?.start(positionMs)
            }
            SidetrackDebug.log("engine.start audio sink started")
        } catch (t: Throwable) {
            SidetrackDebug.log("engine.start failed", t)
            _state.update { it.copy(isPlaying = false) }
            audio?.stop()
            throw t
        }
    }

    override suspend fun resume(positionMs: Long): Boolean = reconfigureLock.withLock {
        SidetrackDebug.log("engine.resume positionMs=$positionMs audio=${audio != null}")
        val generation = ++commandGeneration
        _state.update { it.copy(isPlaying = true, positionMs = positionMs) }
        val reused = audio?.resume(positionMs) ?: false
        if (generation != commandGeneration) return@withLock false
        if (!reused) {
            // The AudioTrack buffer was lost (for example after a long pause). Realign the
            // player with the UI position before playing, otherwise it resumes from its decoder
            // position and skips the buffered audio.
            SidetrackDebug.log("engine.resume audio buffer lost; seeking to $positionMs")
            runCatching { gateway.seek(positionMs) }
        }
        if (generation != commandGeneration) return@withLock false
        SidetrackDebug.log("engine.resume calling gateway.play")
        gateway.play()
        SidetrackDebug.log("engine.resume gateway.play returned")
        true
    }

    override suspend fun pause() {
        SidetrackDebug.log("engine.pause")
        val generation = ++commandGeneration
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        // Queue the native pause while the AudioTrack drain is still running. The drain is the
        // real-time clock and also unblocks a player thread waiting in PcmSink::write, so the
        // player can process the pause command. Stopping the drain first would wedge that thread
        // and leave the UI showing a stale playing state.
        if (generation != commandGeneration) return
        runCatching { gateway.pause() }
        delay(PAUSE_DRAIN_GRACE_MS)
        if (generation == commandGeneration && _state.value.isPlaying.not()) {
            audio?.pause()
            // The player is silent now, so a deferred setting change can rebuild it here
            // instead of cutting into the next track.
            applyPendingReconfigure()
        }
    }

    override suspend fun stop() {
        SidetrackDebug.log("engine.stop")
        val generation = ++commandGeneration
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
        // Same ordering as pause: let the player process stop while the drain is still alive.
        if (generation != commandGeneration) return
        runCatching { gateway.stop() }
        delay(PAUSE_DRAIN_GRACE_MS)
        if (generation != commandGeneration) return
        audio?.stop()
    }

    override suspend fun seek(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        gateway.seek(positionMs)
        audio?.seek(positionMs)
    }

    override suspend fun preload(source: PlaybackSource) {
        runCatching { gateway.preload(source.uri) }
            .onFailure { SidetrackDebug.log("engine.preload failed", it) }
    }

    override suspend fun reconfigure(
        source: PlaybackSource?,
        positionMs: Long,
        wasPlaying: Boolean
    ): Boolean = reconfigureLock.withLock {
        if (_state.value.isPlaying && source != null) {
            reconfigurePending = true
            SidetrackDebug.log("engine.reconfigure deferred until playback is silent")
            return@withLock false
        }
        rebuildWhileSilent(source, positionMs, wasPlaying)
    }

    /**
     * Rebuilds the player and reloads the item at [positionMs]. Only called while the sink is
     * silent: the rebuild releases the AudioTrack and fetches the item again, so it must never
     * run under a playing track.
     */
    private suspend fun rebuildWhileSilent(
        source: PlaybackSource?,
        positionMs: Long,
        wasPlaying: Boolean
    ): Boolean {
        val generation = ++commandGeneration
        SidetrackDebug.log(
            "engine.reconfigure uri=${source?.uri} positionMs=$positionMs wasPlaying=$wasPlaying"
        )
        audio?.stop()
        try {
            // No native player yet: the next connect picks the new settings up.
            if (!gateway.recreatePlayer()) return false
            if (source != null) gateway.load(source.uri, positionMs, autoplay = wasPlaying)
        } catch (t: Throwable) {
            SidetrackDebug.log("engine.reconfigure failed", t)
            _state.update { it.copy(isPlaying = false) }
            throw t
        }
        if (generation != commandGeneration) return false
        _state.update {
            it.copy(source = source, isPlaying = wasPlaying, positionMs = positionMs)
        }
        if (wasPlaying && source != null) audio?.start(positionMs)
        return true
    }

    /** Applies a deferred rebuild at a moment that is already silent. */
    private suspend fun applyPendingReconfigure() {
        reconfigureLock.withLock {
            if (!reconfigurePending) return@withLock
            reconfigurePending = false
            val state = _state.value
            runCatching { rebuildWhileSilent(state.source, state.positionMs, wasPlaying = false) }
                .onFailure { SidetrackDebug.log("deferred reconfigure failed", it) }
        }
    }

    /**
     * Rebuilds the player for a deferred setting change without reloading anything: the caller
     * loads the next item itself, so the new player gets its source from that load.
     */
    private suspend fun recreatePlayerIfPending() {
        reconfigureLock.withLock {
            if (!reconfigurePending) return@withLock
            reconfigurePending = false
            runCatching { gateway.recreatePlayer() }
                .onFailure { SidetrackDebug.log("deferred player rebuild failed", it) }
        }
    }

    override suspend fun disconnect() {
        stop()
        gateway.disconnect()
    }

    override suspend fun pollEvent(): PlaybackEvent? = eventSource()?.let(PlaybackEvent::fromJson)

    override fun invalidateCommands() {
        commandGeneration++
        _state.update { it.copy(isPlaying = false, isBuffering = false) }
    }

    override fun updatePosition(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun updateState(isPlaying: Boolean, positionMs: Long) {
        _state.update { it.copy(isPlaying = isPlaying, positionMs = positionMs) }
    }

    private companion object {
        /**
         * How long the AudioTrack drain keeps running after a pause/stop is queued. One PCM
         * buffer is ~23 ms, so this is enough for a player thread blocked in PcmSink::write to
         * wake, process the command, and stop producing audio.
         */
        const val PAUSE_DRAIN_GRACE_MS = 60L
    }
}

/**
 * Builds the librespot-backed controller. Kept here, next to the engine, so the neutral
 * [PlaybackController] does not import the native gateway or audio sink.
 */
fun librespotPlaybackController(
    gateway: SpotifyGateway,
    scope: kotlinx.coroutines.CoroutineScope,
    registry: MediaSessionRegistry = MediaSessions,
    audio: SpotifyAudioSink? = null,
    context: android.content.Context? = null,
    elapsedTime: () -> Long = { android.os.SystemClock.elapsedRealtime() }
): PlaybackController = PlaybackController(
    engine = LibrespotPlaybackEngine(gateway, audio),
    scope = scope,
    registry = registry,
    context = context,
    elapsedTime = elapsedTime
)
