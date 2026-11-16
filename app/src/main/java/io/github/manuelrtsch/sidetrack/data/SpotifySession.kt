package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import android.os.SystemClock
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.DemoContentSource
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.SpotifyEpisode
import io.github.manuelrtsch.sidetrack.SpotifyMediaItem
import io.github.manuelrtsch.sidetrack.SpotifyTrack
import io.github.manuelrtsch.sidetrack.auth.EncryptedSpotifyCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SpotifyAuth
import io.github.manuelrtsch.sidetrack.bridge.NativeBridge
import io.github.manuelrtsch.sidetrack.playback.SpotifyAudioSink
import io.github.manuelrtsch.sidetrack.playback.SpotifyPlayback
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
    context: Context? = null
) {
    private val _signedIn = MutableStateFlow(auth.hasCredentials())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val appContext = context?.applicationContext
    private val audioCacheDir = appContext?.let { File(it.cacheDir, "spotify-audio") }
    private val settingsStore = appContext?.let { SpotifySettingsStore(it) }

    /** Null in tests and other context-free sessions; those are treated as online. */
    val network: NetworkMonitor? = appContext?.let(::NetworkMonitor)
    val isOnline: Boolean get() = network?.isOnline ?: true

    // Legacy file names kept so existing dev installs keep their queue and history.
    private val queueStore = appContext?.let { FileSpotifyQueueStore(File(it.filesDir, "sidetracks-queue.bin")) }
    private val historyStore = appContext?.let { FileSpotifyHistoryStore(File(it.filesDir, "sidetracks-history.bin")) }
    private val positionStore = appContext?.let { FileSpotifyPositionStore(File(it.filesDir, "sidetracks-resume.bin")) }
    private val episodeProgressStore = appContext?.let {
        FileSpotifyEpisodeProgressStore(File(it.filesDir, "sidetracks-episode-progress.bin"))
    }

    /** Where the last session stopped: the item and the offset, so reopening resumes it. */
    @Volatile var resumePoint: SpotifyResumePoint? = positionStore?.read()
        private set

    /** Per-episode podcast offsets, independent of the single-session resume point. */
    val episodeProgress = SpotifyEpisodeProgressRepository(
        load = { episodeProgressStore?.read().orEmpty() },
        save = { episodeProgressStore?.write(it) }
    )
    private val _settings = MutableStateFlow(settingsStore?.read() ?: SpotifySettings())
    val settings: StateFlow<SpotifySettings> = _settings.asStateFlow()

    val nativeAvailable: Boolean get() = NativeBridge.isAvailable

    private val nativeGateway by lazy {
        NativeSpotifyGateway(NativeBridge, { _settings.value }, { isOnline }, { accessToken() })
    }
    val gateway: SpotifyGateway get() = nativeGateway

    private val webApi by lazy { SpotifyWebApi { accessToken() } }
    val catalog: SpotifyCatalog by lazy {
        // The demo build type serves a fixed catalog; playback still goes through the real
        // account and librespot, so only the read/write side is replaced.
        DemoContentSource.current?.catalog()
            ?: if (nativeAvailable) {
                NativeSpotifyCatalog(NativeBridge) { nativeGateway.ensureSession() }
            } else {
                SpotifyWebCatalog(webApi)
            }
    }
    val writer: SpotifyLibraryWriter? get() = catalog as? SpotifyLibraryWriter

    /** Process-wide queue and history so leaving Sidetrack does not forget what is playing. */
    val queue = SpotifyQueueRepository(
        load = { queueStore?.read() ?: SpotifyQueueRepository.Snapshot() },
        save = { queueStore?.write(it) }
    )

    /**
     * True between [haltPlayback] and the next start. The ViewModel polls native events on a
     * background thread, so a "stopped" event arriving after the app-close path could otherwise
     * clear the queue that path deliberately keeps.
     */
    @Volatile var halting = false
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

    val history = SpotifyHistoryRepository(
        load = { historyStore?.read().orEmpty() },
        save = { historyStore?.write(it) }
    )

    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val playback: SpotifyPlayback by lazy {
        val sink = SpotifyAudioSink(NativeBridge, playbackScope)
        SpotifyPlayback(
            gateway = nativeGateway,
            scope = playbackScope,
            audio = sink,
            context = appContext
        ).also { player ->
            // A drain that stopped producing audio cannot repair itself: restarting the native
            // player needs the gateway and a fresh token, and those live here.
            sink.onFailure = player::onAudioStalled
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
     * path via [SpotifyPlayback.onResumeWithoutSource], because only it knows the saved offsets.
     */
    private fun startRestoredItem() {
        val item = queue.snapshot.current ?: return
        val uri = item.uri ?: return
        val positionMs = playback.position
        SidetrackDebug.log("startRestoredItem uri=$uri positionMs=$positionMs")
        playbackScope.launch {
            runCatching { play(uri, positionMs.coerceAtLeast(0L)) }
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
            runCatching { play(uri, positionMs.coerceAtLeast(0L)) }
                .onSuccess { playback.emitNotice(null) }
                .onFailure {
                    SidetrackDebug.log("recoverPlayback failed", it)
                    playback.pause()
                    playback.emitNotice("Playback stopped — check the connection")
                }
        }
    }

    init {
        DemoContentSource.current?.seed(history, queue)
        if (nativeAvailable) {
            tmpDir?.let(NativeBridge::setTmpDir)
            audioCacheDir?.let { NativeBridge.setCacheDir(it.absolutePath, AUDIO_CACHE_LIMIT_BYTES) }
            NativeBridge.initialize()
        }
    }

    suspend fun signIn(openBrowser: suspend (String) -> Unit) {
        auth.signIn(openBrowser)
        _signedIn.value = true
    }

    fun signOut() {
        rememberPosition(clear = true)
        queue.stop()
        history.clear()
        episodeProgress.clearAll()
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
    private suspend fun connectForPlayback(): Boolean {
        val rebuilt = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { nativeGateway.ensureConnected() }
        if (rebuilt == null) {
            SidetrackDebug.log("connectForPlayback timed out after ${CONNECT_TIMEOUT_MS}ms")
            if (!isOnline) throw SpotifyGatewayException(SpotifyFailure.Network, "No internet connection")
            throw SpotifyGatewayException(SpotifyFailure.NotConnected, "Playback connection timed out")
        }
        SidetrackDebug.log("connectForPlayback rebuilt=$rebuilt")
        return rebuilt
    }

    suspend fun play(uri: String, positionMs: Long = 0L, gapless: Boolean = false) {
        SidetrackDebug.log("session.play begin uri=$uri positionMs=$positionMs gapless=$gapless")
        // A new start supersedes the close path, even when the old player's stopped event is
        // still queued behind it.
        halting = false
        connectForPlayback()
        SidetrackDebug.log("session.play connected; starting playback")
        val keepAudioSink = gapless && _settings.value.gapless && positionMs == 0L
        withContext(Dispatchers.Main.immediate) { playback.start(uri, positionMs, keepAudioSink) }
        preloadNext()
        SidetrackDebug.log("session.play returned")
    }

    /** Warms the next queue item so the automatic transition does not have to fetch it cold. */
    private fun preloadNext() {
        val next = queue.snapshot.upcoming.firstOrNull()?.uri ?: return
        playbackScope.launch {
            runCatching { nativeGateway.preload(next) }
                .onFailure { SidetrackDebug.log("preload failed", it) }
        }
    }

    suspend fun resume(): Boolean {
        halting = false
        val rebuilt = connectForPlayback()
        // A rebuilt session starts from an empty native player. Reload the current item instead of
        // asking the player to resume a track it no longer has.
        val uri = playback.current?.uri
        return if (rebuilt && uri != null) {
            withContext(Dispatchers.Main.immediate) { playback.start(uri, playback.position) }
            true
        } else {
            withContext(Dispatchers.Main.immediate) { playback.resume() }
        }
    }

    suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    fun pause() {
        rememberPosition()
        playback.pause()
    }

    suspend fun accessToken(): String? = auth.accessToken()

    fun setAudioQuality(quality: AudioQuality) {
        if (quality == _settings.value.audioQuality) return
        _settings.update { it.copy(audioQuality = quality) }
        settingsStore?.write(_settings.value)
        applyAudioSettings()
    }

    fun setNormalization(enabled: Boolean) {
        if (enabled == _settings.value.normalization) return
        _settings.update { it.copy(normalization = enabled) }
        settingsStore?.write(_settings.value)
        applyAudioSettings()
    }

    fun setGapless(enabled: Boolean) {
        if (enabled == _settings.value.gapless) return
        _settings.update { it.copy(gapless = enabled) }
        settingsStore?.write(_settings.value)
        applyAudioSettings()
    }

    fun setAutoplay(enabled: Boolean) {
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

    fun togglePlayback() {
        if (playback.isActive) {
            playback.pause()
        } else {
            playbackScope.launch { runCatching { resume() } }
        }
    }

    fun stopPlayback() {
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
            playback.current?.let { SpotifyResumePoint(it.id, playback.position.coerceAtLeast(0L)) }
        }
        positionStore?.write(point)
        resumePoint = point
    }

    /**
     * Halts the audio and keeps everything else. Closing Sidetrack has to silence the player, but
     * the queue, the history and the selected track are persisted by [queue], so the next launch
     * finds them again. [halting] tells a late "stopped" event not to clear that queue.
     */
    fun haltPlayback() {
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
        val current = playback.current as? SpotifyEpisode ?: return
        episodeProgress.record(current.id, playback.position, current.durationMs)
    }

    /** Remembers the player generation currently reporting events and, when present, its track. */
    fun notePlayRequest(id: Long, uri: String?) {
        if (id < 0L) return
        currentPlayRequestId = id
        if (uri != null) currentPlayRequestUri = uri
    }

    /**
     * Consumes the "stopped" event produced by [haltPlayback]. Returns true when the event belongs
     * to that halted player, so the caller can ignore an echo from the generation that was closed.
     */
    fun consumeHaltStop(uri: String?, playRequestId: Long): Boolean = haltStop.consume(uri, playRequestId, SystemClock.elapsedRealtime())

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

    private fun startFromTransport(item: SpotifyMediaItem) {
        halting = false
        history.record(item, System.currentTimeMillis())
        playback.setNowPlaying(item, 0L)
        val uri = item.uri ?: return
        playbackScope.launch {
            runCatching { play(uri, 0L) }.onFailure {
                playback.pause()
                playback.updateState(false, 0L)
            }
        }
    }

    /** One librespot player event, or null when nothing has happened since the last poll. */
    fun pollPlaybackEvent(): String? = if (nativeAvailable) NativeBridge.pollEvent() else null

    /** Drops the on-disk audio cache. librespot re-fetches anything it still needs. */
    fun clearAudioCache() {
        audioCacheDir?.deleteRecursively()
    }

    /** Artwork and audio bytes currently held on disk. */
    fun cacheSizeBytes(): Long {
        val artwork = appContext?.let { File(it.cacheDir, "artwork") }
        return listOfNotNull(artwork, audioCacheDir).sumOf { it.sizeBytes() }
    }

    /** Sign-out and the Settings action both drop artwork and audio; credentials are separate. */
    fun clearCaches() {
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

/** Lazily creates the one session for the process, matching how Sidekit owns local playback. */
object SpotifySessions {
    @Volatile
    private var instance: SpotifySession? = null

    fun get(context: Context): SpotifySession = instance ?: synchronized(this) {
        instance ?: SpotifySession(
            SpotifyAuth(EncryptedSpotifyCredentialStore(context.applicationContext)),
            context.applicationContext.cacheDir.absolutePath,
            context.applicationContext
        ).also { instance = it }
    }
}
