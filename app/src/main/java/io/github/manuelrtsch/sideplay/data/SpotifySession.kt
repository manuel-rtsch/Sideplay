package io.github.manuelrtsch.sideplay.data

import android.content.Context
import io.github.manuelrtsch.sideplay.AudioQuality
import io.github.manuelrtsch.sideplay.SideplayDebug
import io.github.manuelrtsch.sideplay.SpotifyMediaItem
import io.github.manuelrtsch.sideplay.SpotifyTrack
import io.github.manuelrtsch.sideplay.auth.EncryptedSpotifyCredentialStore
import io.github.manuelrtsch.sideplay.auth.SpotifyAuth
import io.github.manuelrtsch.sideplay.bridge.NativeBridge
import io.github.manuelrtsch.sideplay.playback.SpotifyAudioSink
import io.github.manuelrtsch.sideplay.playback.SpotifyPlayback
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

    // Legacy file names kept so existing dev installs keep their queue and history.
    private val queueStore = appContext?.let { FileSpotifyQueueStore(File(it.filesDir, "sidetracks-queue.bin")) }
    private val historyStore = appContext?.let { FileSpotifyHistoryStore(File(it.filesDir, "sidetracks-history.bin")) }
    private val _settings = MutableStateFlow(settingsStore?.read() ?: SpotifySettings())
    val settings: StateFlow<SpotifySettings> = _settings.asStateFlow()

    val nativeAvailable: Boolean get() = NativeBridge.isAvailable

    private val nativeGateway by lazy {
        NativeSpotifyGateway(NativeBridge, { _settings.value }, { accessToken() })
    }
    val gateway: SpotifyGateway get() = nativeGateway

    private val webApi by lazy { SpotifyWebApi { accessToken() } }
    val catalog: SpotifyCatalog by lazy {
        if (nativeAvailable) NativeSpotifyCatalog(NativeBridge) { nativeGateway.ensureSession() } else SpotifyWebCatalog(webApi)
    }
    val writer: SpotifyLibraryWriter? get() = catalog as? SpotifyLibraryWriter

    /** Process-wide queue and history so leaving Sideplay does not forget what is playing. */
    val queue = SpotifyQueueRepository(
        load = { queueStore?.read() ?: SpotifyQueueRepository.Snapshot() },
        save = { queueStore?.write(it) }
    )
    val history = SpotifyHistoryRepository(
        load = { historyStore?.read().orEmpty() },
        save = { historyStore?.write(it) }
    )

    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val playback: SpotifyPlayback by lazy {
        SpotifyPlayback(
            gateway = nativeGateway,
            scope = playbackScope,
            audio = SpotifyAudioSink(NativeBridge, playbackScope),
            context = appContext
        ).also { player ->
            // Adopt transport actions when no ViewModel is attached. The ViewModel installs its
            // own callbacks while it exists and clears them in onCleared().
            player.fallbackNext = { skipNext() }
            player.fallbackPrevious = { skipPrevious() }
            player.onStopped = { queue.stop() }
        }
    }

    init {
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
        queue.stop()
        history.clear()
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
            SideplayDebug.log("connectForPlayback timed out after ${CONNECT_TIMEOUT_MS}ms")
            throw SpotifyGatewayException(SpotifyFailure.NotConnected, "Playback connection timed out")
        }
        SideplayDebug.log("connectForPlayback rebuilt=$rebuilt")
        return rebuilt
    }

    suspend fun play(uri: String, positionMs: Long = 0L) {
        SideplayDebug.log("session.play begin uri=$uri positionMs=$positionMs")
        connectForPlayback()
        SideplayDebug.log("session.play connected; starting playback")
        withContext(Dispatchers.Main.immediate) { playback.start(uri, positionMs) }
        SideplayDebug.log("session.play returned")
    }

    suspend fun resume(): Boolean {
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

    fun pause() = playback.pause()

    suspend fun accessToken(): String? = auth.accessToken()

    fun setAudioQuality(quality: AudioQuality) {
        _settings.update { it.copy(audioQuality = quality) }
        settingsStore?.write(_settings.value)
    }

    fun setAutoplay(enabled: Boolean) {
        _settings.update { it.copy(autoplay = enabled) }
        settingsStore?.write(_settings.value)
    }

    fun togglePlayback() {
        if (playback.isActive) {
            playback.pause()
        } else {
            playbackScope.launch { runCatching { resume() } }
        }
    }

    fun stopPlayback() {
        queue.stop()
        playbackScope.launch { runCatching { playback.stop() } }
    }

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
        if (item is SpotifyTrack) history.record(item, System.currentTimeMillis())
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

    private companion object {
        /** Slightly longer than the native connect timeout, so its specific error wins. */
        const val CONNECT_TIMEOUT_MS = 25_000L

        /** Upper bound for the audio cache; librespot evicts least-recently-used entries. */
        const val AUDIO_CACHE_LIMIT_BYTES = 1L shl 30
    }

    private object NoopCredentialStore : io.github.manuelrtsch.sideplay.auth.SpotifyCredentialStore {
        override fun read(): io.github.manuelrtsch.sideplay.auth.SpotifyCredentials? = null
        override fun save(credentials: io.github.manuelrtsch.sideplay.auth.SpotifyCredentials) = Unit
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
