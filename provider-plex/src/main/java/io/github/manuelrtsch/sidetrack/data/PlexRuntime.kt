package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.auth.PlexAccountHttpClient
import io.github.manuelrtsch.sidetrack.auth.PlexAuth
import io.github.manuelrtsch.sidetrack.auth.PlexCredentialStore
import io.github.manuelrtsch.sidetrack.auth.plexClientIdentifier
import io.github.manuelrtsch.sidetrack.playback.ExoPlayerMedia3Player
import io.github.manuelrtsch.sidetrack.playback.Media3PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A signed-in Plex account.
 *
 * Catalog and writer are the Plex adapter; playback is the shared [PlaybackController] over
 * Media3/ExoPlayer, so the media session, notification and audio focus behave as they do for the
 * other providers. Queue, history and resume files are Plex-specific, so switching services never
 * mixes two accounts.
 */
class PlexRuntime(
    context: Context,
    val auth: PlexAuth = defaultPlexAuth(context)
) : ServiceRuntime,
    CacheRuntime,
    QualitySettingsRuntime {
    private val appContext = context.applicationContext

    override val service: ServiceId = ServiceId.Plex
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Plex

    private val _signedIn = MutableStateFlow(runCatching { auth.hasCredentials() }.getOrDefault(false))
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    @Volatile
    private var client: PlexApi? = null

    @Volatile
    private var clientToken: String? = null

    /**
     * The API client for the current token. Building one is cheap compared with the SDK clients
     * other providers use, but the token check keeps a signed-out session from making requests.
     */
    private fun api(): PlexApi? {
        val credentials = runCatching { auth.credentials() }.getOrNull() ?: return null
        client?.let { if (clientToken == credentials.token) return it }
        val created = runCatching { HttpPlexApi(credentials) }.getOrNull()
        client = created
        clientToken = credentials.token
        return created
    }

    override val catalog: PlexCatalog = PlexCatalog(::api)
    override val writer: MediaLibraryWriter get() = catalog

    override val network: NetworkMonitor = NetworkMonitor(appContext)
    override val isOnline: Boolean get() = network.isOnline

    private val settingsStore = PlaybackSettingsStore(appContext, "sidetracks_settings_plex")
    private val _settings = MutableStateFlow(
        runCatching { settingsStore.read() }.getOrDefault(PlaybackSettings())
    )
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    private val queueStore = FileQueueStore(File(appContext.filesDir, "sidetracks-plex-queue.bin"))
    private val historyStore = FileHistoryStore(File(appContext.filesDir, "sidetracks-plex-history.bin"))
    private val positionStore = FilePositionStore(File(appContext.filesDir, "sidetracks-plex-resume.bin"))

    override val queue = QueueRepository(
        load = { queueStore.read() },
        save = { queueStore.write(it) }
    )
    override val history = HistoryRepository(
        load = { historyStore.read() },
        save = { historyStore.write(it) }
    )
    override val episodeProgress = EpisodeProgressRepository()
    override val episodeMeta = EpisodeMetaRepository()

    @Volatile
    override var resumePoint: ResumePoint? = runCatching { positionStore.read() }.getOrNull()
        private set

    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val playback: PlaybackController by lazy {
        PlaybackController(
            engine = Media3PlaybackEngine { ExoPlayerMedia3Player(appContext) },
            scope = playbackScope,
            context = appContext
        ).also(::startReporting)
    }

    init {
        // A stored session installs the artwork header before the first row is drawn.
        if (_signedIn.value) runCatching { api() }
    }

    private fun startReporting(controller: PlaybackController) {
        PlexPlaybackReporter(
            api = ::api,
            state = controller.engine.state,
            ended = controller.ended,
            currentItem = { controller.current },
            position = { controller.position },
            scope = reportScope
        ).start()
    }

    override suspend fun signIn(input: AuthInput) {
        try {
            auth.signIn(input)
        } catch (e: CredentialPersistenceException) {
            _signedIn.value = true
            throw e
        }
        client = null
        clientToken = null
        _signedIn.value = true
    }

    override fun signOut() {
        rememberPosition(clear = true)
        queue.stop()
        history.clear()
        playbackScope.launch { runCatching { playback.stop() } }
        auth.clear()
        client = null
        clientToken = null
        _signedIn.value = false
    }

    override suspend fun play(item: MediaItem, positionMs: Long, gapless: Boolean) {
        val source = catalog.resolvePlaybackSource(item)
        playback.start(item.key, source.uri, positionMs, headers = source.headers)
    }

    override suspend fun resume(): Boolean {
        val current = playback.current
        if (current != null && playback.engine.state.value.source == null) {
            val source = catalog.resolvePlaybackSource(current)
            playback.start(current.key, source.uri, playback.position, headers = source.headers)
            return true
        }
        return playback.resume()
    }

    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    override fun pause() {
        rememberPosition()
        playback.pause()
    }

    override fun togglePlayback() {
        if (playback.isActive) pause() else playbackScope.launch { runCatching { resume() } }
    }

    override fun stopPlayback() {
        rememberPosition(clear = true)
        queue.stop()
        playbackScope.launch { runCatching { playback.stop() } }
    }

    override fun haltPlayback() {
        rememberPosition()
        playbackScope.launch { runCatching { playback.stop(keepQueue = true) } }
    }

    override fun setAutoplay(enabled: Boolean) {
        _settings.update { it.copy(autoplay = enabled) }
        settingsStore.write(_settings.value)
    }

    override fun setAudioQuality(quality: AudioQuality) {
        _settings.update { it.copy(audioQuality = quality) }
        settingsStore.write(_settings.value)
    }

    override fun setNormalization(enabled: Boolean) {
        _settings.update { it.copy(normalization = enabled) }
        settingsStore.write(_settings.value)
    }

    override fun setGapless(enabled: Boolean) {
        _settings.update { it.copy(gapless = enabled) }
        settingsStore.write(_settings.value)
    }

    override fun clearCaches() {
        RemoteArtwork.clear(appContext)
    }

    override fun cacheSizeBytes(): Long = File(appContext.cacheDir, "artwork")
        .walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    override fun clearAudioCache() = clearCaches()

    private fun rememberPosition(clear: Boolean = false) {
        val point = if (clear) {
            null
        } else {
            playback.current?.let { ResumePoint(it.key, playback.position.coerceAtLeast(0L)) }
        }
        positionStore.write(point)
        resumePoint = point
    }
}

/** Plex's identity and label, available without building a session. */
object PlexService {
    val id: ServiceId get() = ServiceId.Plex
    const val DISPLAY_NAME: String = "Plex"
}

/** Plex as a registered service; the picker and sign-in screen read these values. */
class PlexProvider(
    private val session: PlexRuntime
) : MusicServiceProvider {
    override val id: ServiceId = PlexService.id
    override val displayName: String = PlexService.DISPLAY_NAME
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Plex

    /** Plex signs in with a PIN on plex.tv; Sidetrack never sees the account password. */
    override val signInKind: SignInKind = SignInKind.Browser

    override fun createAuth(): ServiceAuth = session.auth
}

private fun defaultPlexAuth(context: Context): PlexAuth {
    val clientIdentifier = plexClientIdentifier(context)
    return PlexAuth(
        store = PlexCredentialStore(context),
        clientIdentifier = clientIdentifier,
        client = PlexAccountHttpClient(clientIdentifier)
    )
}
