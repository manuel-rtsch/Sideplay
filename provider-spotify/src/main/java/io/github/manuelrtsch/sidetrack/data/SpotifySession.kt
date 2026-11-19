package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.DemoContent
import io.github.manuelrtsch.sidetrack.Episode
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.auth.SpotifyAuth
import io.github.manuelrtsch.sidetrack.bridge.NativeBridge
import io.github.manuelrtsch.sidetrack.playback.LibrespotPlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import io.github.manuelrtsch.sidetrack.playback.SpotifyAudioSink
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide Spotify identity. It owns the credential store and token refresh, not the UI; the
 * ViewModel observes [signedIn] and asks for an access token when a real gateway is connected.
 */
class SpotifySession(
    private val auth: SpotifyAuth = SpotifyAuth(NoopCredentialStore),
    private val tmpDir: String? = null,
    context: Context? = null,
    /**
     * Fixed catalog for demo builds, injected by :app because only it knows whether this build
     * carries a demo pack. Null in every normal build.
     */
    private val demo: DemoContent? = null
) : ServiceRuntime,
    NativePlaybackRuntime,
    QualitySettingsRuntime,
    CacheRuntime {
    override val service: ServiceId = ServiceId.Spotify
    override val capabilities: ServiceCapabilities = ServiceCapabilities.All

    // The credential store is read while the UI composes. Anything it cannot answer counts as
    // signed out, so a device that lost its keystore key still opens and offers sign-in.
    private val _signedIn = MutableStateFlow(runCatching { auth.hasCredentials() }.getOrDefault(false))
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val appContext = context?.applicationContext
    private val audioCacheDir = appContext?.let { File(it.cacheDir, "spotify-audio") }
    private val settingsStore = appContext?.let { PlaybackSettingsStore(it) }

    /** Null in tests and other context-free sessions; those are treated as online. */
    override val network: NetworkMonitor? = appContext?.let(::NetworkMonitor)
    override val isOnline: Boolean get() = network?.isOnline ?: true

    // Legacy file names kept so existing dev installs keep their queue and history.
    private val queueStore = appContext?.let { FileQueueStore(File(it.filesDir, "sidetracks-queue.bin")) }
    private val historyStore = appContext?.let { FileHistoryStore(File(it.filesDir, "sidetracks-history.bin")) }
    private val positionStore = appContext?.let { FilePositionStore(File(it.filesDir, "sidetracks-resume.bin")) }
    private val episodeProgressStore = appContext?.let {
        FileEpisodeProgressStore(File(it.filesDir, "sidetracks-episode-progress.bin"))
    }
    private val episodeMetaStore = appContext?.let {
        FileEpisodeMetaStore(File(it.filesDir, "sidetracks-episode-meta.bin"))
    }

    /** Writes the podcast state files without holding up the main thread. */
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where the last session stopped: the item and the offset, so reopening resumes it. */
    @Volatile override var resumePoint: ResumePoint? = runCatching { positionStore?.read() }.getOrNull()
        private set

    /** Per-episode podcast offsets, independent of the single-session resume point. */
    override val episodeProgress = EpisodeProgressRepository(
        load = { episodeProgressStore?.read().orEmpty() },
        save = { episodeProgressStore?.write(it) },
        saveScope = storageScope
    )

    /** Notes and identity for the episode a restored session puts back on the player. */
    override val episodeMeta = EpisodeMetaRepository(
        load = { episodeMetaStore?.read().orEmpty() },
        save = { episodeMetaStore?.write(it) },
        saveScope = storageScope
    )
    private val _settings = MutableStateFlow(
        runCatching { settingsStore?.read() }.getOrNull() ?: PlaybackSettings()
    )
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    override val nativeAvailable: Boolean get() = NativeBridge.isAvailable

    /** One line per failed access-point connection, in a file the user can share. */
    private val connectDiagnostics by lazy { appContext?.let(::ConnectDiagnostics) }

    private val nativeGateway by lazy {
        NativeSpotifyGateway(
            native = NativeBridge,
            settings = { _settings.value },
            networkAvailable = { isOnline },
            accessToken = { accessToken() },
            onConnectFailure = { reason -> connectDiagnostics?.record(reason) }
        )
    }
    val gateway: SpotifyGateway get() = nativeGateway

    private val webApi by lazy { SpotifyWebApi { accessToken() } }
    private val webCatalog by lazy { SpotifyWebCatalog(webApi) }
    private val nativeCatalog by lazy {
        NativeSpotifyCatalog(NativeBridge) { nativeGateway.ensureSession() }
    }

    val spotifyCatalog: SpotifyCatalog by lazy {
        // The demo build type serves a fixed catalog; playback still goes through the real
        // account and librespot, so only the read/write side is replaced.
        demo?.catalog()
            ?: if (nativeAvailable) {
                // Reads keep working over HTTPS when the network refuses librespot's access point;
                // see [ResilientSpotifyCatalog].
                ResilientSpotifyCatalog(nativeCatalog, webCatalog)
            } else {
                webCatalog
            }
    }

    /**
     * Null while reads are served by the Web API: that transport is read-only here, so the UI has
     * to hide the library actions instead of failing them one by one.
     */
    val spotifyWriter: SpotifyLibraryWriter? get() {
        val catalog = spotifyCatalog
        if (catalog is ResilientSpotifyCatalog) return catalog.takeUnless { it.degraded }
        return catalog as? SpotifyLibraryWriter
    }

    override val catalog: MediaCatalog by lazy { SpotifyMediaCatalog(spotifyCatalog) }
    override val writer: MediaLibraryWriter? get() = spotifyWriter?.let(::SpotifyMediaLibraryWriter)

    /** Process-wide queue and history so leaving Sidetrack does not forget what is playing. */
    override val queue = QueueRepository(
        // The queue file holds the whole upcoming list, so an episode reaches Now Playing without
        // its notes and publishing number; the metadata cache has them.
        load = { queueStore?.read()?.let(episodeMeta::hydrate) ?: QueueRepository.Snapshot() },
        save = { queueStore?.write(it) }
    )

    /**
     * True between [haltPlayback] and the next start. The ViewModel polls native events on a
     * background thread, so a "stopped" event arriving after the app-close path could otherwise
     * clear the queue that path deliberately keeps.
     */
    @Volatile override var halting = false
        private set

    /**
     * librespot emits one "stopped" event for the halt command itself; a recreated ViewModel can
     * receive it seconds later, after the user has already reopened the app (and possibly
     * resumed). The guard remembers the halted play request so that echo is consumed without
     * swallowing a genuine stop from the new playback.
     */
    private val haltStop = HaltStopGuard(HALT_STOP_GRACE_MS)

    @Volatile private var currentPlayRequestId = -1L

    @Volatile private var currentPlayRequestUri: String? = null

    override val history = HistoryRepository(
        load = { historyStore?.read().orEmpty() },
        save = { historyStore?.write(it) }
    )

    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val playback: PlaybackController by lazy {
        val sink = SpotifyAudioSink(NativeBridge, playbackScope)
        val engine = LibrespotPlaybackEngine(
            gateway = nativeGateway,
            audio = sink,
            eventSource = { if (nativeAvailable) NativeBridge.pollEvent() else null }
        )
        PlaybackController(
            engine = engine,
            scope = playbackScope,
            context = appContext
        ).also { player ->
            // A drain that stopped producing audio cannot repair itself: restarting the native
            // player needs the gateway and a fresh token, and those live here.
            player.onStalled = ::recoverPlayback
            // Adopt transport actions when no ViewModel is attached. The ViewModel installs its
            // own callbacks while it exists and clears them in onCleared().
            player.fallbackNext = { skipNext() }
            player.fallbackPrevious = { skipPrevious() }
            player.fallbackResume = { startRestoredItem() }
            player.onStopped = { queue.stop() }
        }
    }

    /** Stall recoveries started inside [STALL_RECOVERY_WINDOW_MS]; main thread only. */
    private val stallRecoveries = ArrayDeque<Long>()

    /**
     * Starts the queue's current item when a play command arrives with nothing loaded.
     *
     * The queue survives process death but the native player does not, so after a restart there is
     * no source to resume. Used when no ViewModel is attached; a ViewModel installs the accurate
     * path via [PlaybackController.onResumeWithoutSource], because only it knows the saved offsets.
     */
    private fun startRestoredItem() {
        val item = queue.snapshot.current ?: return
        val uri = item.uri ?: return
        val positionMs = playback.position
        SidetrackDebug.log("startRestoredItem uri=$uri positionMs=$positionMs")
        playbackScope.launch {
            // Nothing is loaded, so this is a cold start or a recovery: a fresh connection is
            // cheaper than discovering a dead one after the first load fails.
            runCatching { play(item.key, uri, positionMs.coerceAtLeast(0L), forceReconnect = true) }
                .onFailure {
                    SidetrackDebug.log("startRestoredItem failed", it)
                    playback.pause()
                }
        }
    }

    /**
     * Rebuilds playback after the audio pipeline went silent.
     *
     * When the access-point connection dies, librespot stops producing PCM and nothing in the
     * player notices: the app kept reporting a playing track while delivering silence, and only
     * a manual press of play recovered it. This reloads the item where the user left it, and
     * gives up (visibly) after a few attempts instead of looping through reconnects.
     */
    private fun recoverPlayback(positionMs: Long) {
        val item = playback.current ?: return
        val uri = item.uri ?: return
        val now = SystemClock.elapsedRealtime()
        while (stallRecoveries.isNotEmpty() && now - stallRecoveries.first() > STALL_RECOVERY_WINDOW_MS) {
            stallRecoveries.removeFirst()
        }
        if (stallRecoveries.size >= STALL_RECOVERY_LIMIT) {
            SidetrackDebug.log("recoverPlayback giving up after ${stallRecoveries.size} attempts")
            playback.pause()
            playback.emitNotice("Playback stopped — check the connection")
            return
        }
        stallRecoveries.addLast(now)
        SidetrackDebug.log("recoverPlayback uri=$uri positionMs=$positionMs attempt=${stallRecoveries.size}")
        playbackScope.launch {
            // A stall usually means the access-point connection is dead even when librespot has
            // not marked the session invalid yet, so rebuild it instead of reloading on the same
            // connection and stalling again.
            runCatching { play(item.key, uri, positionMs.coerceAtLeast(0L), forceReconnect = true) }
                .onSuccess { playback.emitNotice(null) }
                .onFailure {
                    SidetrackDebug.log("recoverPlayback failed", it)
                    playback.pause()
                    playback.emitNotice("Playback stopped — check the connection")
                }
        }
    }

    init {
        demo?.seed(history, queue)
        if (nativeAvailable) {
            tmpDir?.let(NativeBridge::setTmpDir)
            audioCacheDir?.let { NativeBridge.setCacheDir(it.absolutePath, AUDIO_CACHE_LIMIT_BYTES) }
            NativeBridge.initialize()
        }
    }

    override suspend fun signIn(input: AuthInput) {
        val browser = input as? AuthInput.Browser
            ?: throw IllegalArgumentException("Spotify needs a browser sign-in")
        auth.signIn(browser.openBrowser)
        _signedIn.value = true
    }

    override fun signOut() {
        rememberPosition(clear = true)
        queue.stop()
        history.clear()
        episodeProgress.clearAll()
        episodeMeta.clearAll()
        playback.clearSleepTimer()
        if (nativeAvailable) {
            playback.setNowPlaying(null)
            playback.pause()
            // Stop the decoder immediately; waiting for the coroutine below leaves a window in
            // which the current track keeps playing after the credentials have been cleared.
            NativeBridge.stop()
            playbackScope.launch { runCatching { playback.disconnect() } }
        } else {
            nativeGateway.reset()
            NativeBridge.disconnect()
        }
        auth.clear()
        clearCaches()
        _signedIn.value = false
    }

    /**
     * Connects librespot with the current access token, creating the one player on first use.
     * Returns true when the native session had to be rebuilt, which discards the loaded track.
     */
    suspend fun ensurePlaybackConnected(): Boolean = nativeGateway.ensureConnected()

    /** Bounded so a stalled access-point handshake cannot leave the play button stuck forever. */
    private suspend fun connectForPlayback(forceReconnect: Boolean = false): Boolean {
        val rebuilt = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { nativeGateway.ensureConnected(forceReconnect) }
        if (rebuilt == null) {
            SidetrackDebug.log("connectForPlayback timed out after ${CONNECT_TIMEOUT_MS}ms")
            if (!isOnline) throw SpotifyGatewayException(SpotifyFailure.Network, "No internet connection")
            throw SpotifyGatewayException(SpotifyFailure.NotConnected, "Playback connection timed out")
        }
        SidetrackDebug.log("connectForPlayback rebuilt=$rebuilt")
        return rebuilt
    }

    suspend fun play(
        key: MediaKey,
        uri: String,
        positionMs: Long = 0L,
        gapless: Boolean = false,
        forceReconnect: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ) {
        SidetrackDebug.log("session.play begin key=${key.encode()} uri=$uri positionMs=$positionMs gapless=$gapless forceReconnect=$forceReconnect")
        // A new start supersedes the close path, even when the old player's stopped event is
        // still queued behind it.
        halting = false
        connectForPlayback(forceReconnect)
        SidetrackDebug.log("session.play connected; starting playback")
        val keepAudioSink = gapless && _settings.value.gapless && positionMs == 0L
        withContext(Dispatchers.Main.immediate) { playback.start(key, uri, positionMs, keepAudioSink, headers) }
        preloadNext()
        SidetrackDebug.log("session.play returned")
    }

    /** [ServiceRuntime] entry point: the catalog resolves the item's playback detail. */
    override suspend fun play(item: MediaItem, positionMs: Long, gapless: Boolean) {
        val source = catalog.resolvePlaybackSource(item)
        play(item.key, source.uri, positionMs, gapless, headers = source.headers)
    }

    /** Warms the next queue item so the automatic transition does not have to fetch it cold. */
    private fun preloadNext() {
        val next = queue.snapshot.upcoming.firstOrNull()?.uri ?: return
        playbackScope.launch {
            runCatching { nativeGateway.preload(next) }
                .onFailure { SidetrackDebug.log("preload failed", it) }
        }
    }

    override suspend fun resume(): Boolean {
        halting = false
        val rebuilt = connectForPlayback()
        // A rebuilt session starts from an empty native player. Reload the current item instead of
        // asking the player to resume a track it no longer has.
        val current = playback.current
        val uri = current?.uri
        return if (rebuilt && current != null && uri != null) {
            withContext(Dispatchers.Main.immediate) { playback.start(current.key, uri, playback.position) }
            true
        } else {
            withContext(Dispatchers.Main.immediate) { playback.resume() }
        }
    }

    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    override fun pause() {
        rememberPosition()
        playback.pause()
    }

    suspend fun accessToken(): String? = auth.accessToken()

    override fun setAudioQuality(quality: AudioQuality) {
        if (quality == _settings.value.audioQuality) return
        _settings.update { it.copy(audioQuality = quality) }
        settingsStore?.write(_settings.value)
        applyAudioSettings()
    }

    override fun setNormalization(enabled: Boolean) {
        if (enabled == _settings.value.normalization) return
        _settings.update { it.copy(normalization = enabled) }
        settingsStore?.write(_settings.value)
        applyAudioSettings()
    }

    override fun setGapless(enabled: Boolean) {
        if (enabled == _settings.value.gapless) return
        _settings.update { it.copy(gapless = enabled) }
        settingsStore?.write(_settings.value)
        applyAudioSettings()
    }

    override fun setAutoplay(enabled: Boolean) {
        _settings.update { it.copy(autoplay = enabled) }
        settingsStore?.write(_settings.value)
    }

    /**
     * Audio settings live in librespot's PlayerConfig, so an existing player must be rebuilt.
     * Playback keeps its current item, position and pause state; a player that was never created
     * simply picks the new settings up on the next connect.
     */
    private fun applyAudioSettings() {
        if (!nativeAvailable) return
        playbackScope.launch {
            runCatching { playback.reconfigure() }
                .onFailure { SidetrackDebug.log("applyAudioSettings failed", it) }
        }
    }

    override fun togglePlayback() {
        if (playback.isActive) {
            playback.pause()
        } else {
            playbackScope.launch { runCatching { resume() } }
        }
    }

    override fun stopPlayback() {
        halting = false
        rememberEpisodeProgress()
        rememberPosition(clear = true)
        queue.stop()
        playbackScope.launch { runCatching { playback.stop() } }
    }

    /**
     * Writes the resume point: the current item and its offset. [clear] wipes it instead, for the
     * paths that end playback on purpose (sign out, the notification's Stop action).
     */
    private fun rememberPosition(clear: Boolean = false) {
        val point = if (clear) {
            null
        } else {
            playback.current?.let { ResumePoint(it.key, playback.position.coerceAtLeast(0L)) }
        }
        positionStore?.write(point)
        resumePoint = point
    }

    /**
     * Halts the audio and keeps everything else. Closing Sidetrack has to silence the player, but
     * the queue, the history and the selected track are persisted by [queue], so the next launch
     * finds them again. [halting] tells a late "stopped" event not to clear that queue.
     */
    override fun haltPlayback() {
        halting = true
        rememberEpisodeProgress()
        val uri = playback.current?.uri
        // Only trust the id when the events it came from describe this track. A queued event for
        // the previous track must not make the halt echo look like a different generation.
        val id = currentPlayRequestId.takeIf { uri != null && uri.equals(currentPlayRequestUri, ignoreCase = true) } ?: -1L
        haltStop.arm(uri, id, SystemClock.elapsedRealtime())
        rememberPosition()
        playbackScope.launch { runCatching { playback.stop(keepQueue = true) } }
    }

    /** Saves the current podcast offset when the app closes or the notification stops playback. */
    private fun rememberEpisodeProgress() {
        val current = playback.current as? Episode ?: return
        episodeProgress.record(current.id, playback.position, current.durationMs)
    }

    /** Remembers the player generation currently reporting events and, when present, its track. */
    override fun notePlayRequest(id: Long, uri: String?) {
        if (id < 0L) return
        currentPlayRequestId = id
        if (uri != null) currentPlayRequestUri = uri
    }

    /**
     * Consumes the "stopped" event produced by [haltPlayback]. Returns true when the event belongs
     * to that halted player, so the caller can ignore an echo from the generation that was closed.
     */
    override fun consumeHaltStop(uri: String?, playRequestId: Long): Boolean = haltStop.consume(uri, playRequestId, SystemClock.elapsedRealtime())

    /**
     * Advances the queue when the notification/lock screen has no ViewModel to call back into.
     * The ViewModel follows the same queue semantics, so the UI adopts this state on return.
     */
    fun skipNext() {
        val before = queue.snapshot.current ?: return
        val snapshot = queue.next(auto = false)
        if (snapshot.current !== before) snapshot.current?.let(::startFromTransport)
    }

    /** Restarts the current item past the 3 s threshold, otherwise returns to queue history. */
    fun skipPrevious() {
        val current = queue.snapshot.current ?: return
        if (playback.position > 3_000L) {
            playbackScope.launch { runCatching { seek(0L) } }
            return
        }
        val snapshot = queue.previous(playback.position)
        if (snapshot.current !== current) {
            snapshot.current?.let(::startFromTransport)
        } else {
            playbackScope.launch { runCatching { seek(0L) } }
        }
    }

    private fun startFromTransport(item: MediaItem) {
        halting = false
        history.record(item, System.currentTimeMillis())
        playback.setNowPlaying(item, 0L)
        val uri = item.uri ?: return
        playbackScope.launch {
            runCatching { play(item.key, uri, 0L) }.onFailure {
                playback.pause()
                playback.updateState(false, 0L)
            }
        }
    }

    /** Drops the on-disk audio cache. librespot re-fetches anything it still needs. */
    override fun clearAudioCache() {
        audioCacheDir?.deleteRecursively()
    }

    /** Artwork and audio bytes currently held on disk. */
    override fun cacheSizeBytes(): Long {
        val artwork = appContext?.let { File(it.cacheDir, "artwork") }
        return listOfNotNull(artwork, audioCacheDir).sumOf { it.sizeBytes() }
    }

    /** Sign-out and the Settings action both drop artwork and audio; credentials are separate. */
    override fun clearCaches() {
        appContext?.let(RemoteArtwork::clear)
        clearAudioCache()
    }

    private fun File.sizeBytes(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        /** Slightly longer than the native connect timeout, so its specific error wins. */
        const val CONNECT_TIMEOUT_MS = 25_000L

        /** Upper bound for the audio cache; librespot evicts least-recently-used entries. */
        const val AUDIO_CACHE_LIMIT_BYTES = 1L shl 30

        /** How long a close-path "stopped" event may still be waiting in the player queue. */
        const val HALT_STOP_GRACE_MS = 15_000L

        /** Stall recoveries allowed inside the window before playback gives up and says so. */
        const val STALL_RECOVERY_LIMIT = 3

        /** Window for [STALL_RECOVERY_LIMIT]; a one-off hiccup must not lock the user out. */
        const val STALL_RECOVERY_WINDOW_MS = 5 * 60_000L
    }

    private object NoopCredentialStore : io.github.manuelrtsch.sidetrack.auth.SpotifyCredentialStore {
        override fun read(): io.github.manuelrtsch.sidetrack.auth.SpotifyCredentials? = null
        override fun save(credentials: io.github.manuelrtsch.sidetrack.auth.SpotifyCredentials) = Unit
        override fun clear() = Unit
    }
}
