package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuth
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SdkJellyfinAuthenticator
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
 * A signed-in Jellyfin account.
 *
 * Catalog, auth and writer are the SDK adapter; playback is the shared [PlaybackController] over
 * Media3/ExoPlayer, so the media session, notification and audio focus behave exactly as they do
 * for Navidrome. Queue, history and resume files are Jellyfin-specific, so switching services
 * never mixes two accounts.
 *
 * One [SdkJellyfinApi] is kept per access token. The SDK client is expensive to build and carries
 * the device identity the server keys a session on, so it must not be recreated per request.
 */
class JellyfinRuntime(
    context: Context,
    val auth: JellyfinAuth = JellyfinAuth(
        JellyfinCredentialStore(context),
        SdkJellyfinAuthenticator(context)
    )
) : ServiceRuntime,
    CacheRuntime,
    QualitySettingsRuntime {
    private val appContext = context.applicationContext

    override val service: ServiceId = ServiceId.Jellyfin
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Jellyfin

    private val _signedIn = MutableStateFlow(runCatching { auth.hasCredentials() }.getOrDefault(false))
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    @Volatile
    private var client: JellyfinApi? = null

    @Volatile
    private var clientToken: String? = null

    /**
     * The SDK client for the current token, or null before sign-in. Reading the credentials is
     * cheap (cached in [JellyfinAuth]); building the client is not, hence the token check.
     */
    private fun api(): JellyfinApi? {
        val credentials = runCatching { auth.credentials() }.getOrNull() ?: return null
        client?.let { if (clientToken == credentials.accessToken) return it }
        val created = runCatching { SdkJellyfinApi(appContext, credentials) }
            .getOrNull()
        client = created
        clientToken = credentials.accessToken
        return created
    }

    override val catalog: JellyfinCatalog = JellyfinCatalog(
        api = ::api,
        settings = { _settings.value }
    )
    override val writer: MediaLibraryWriter get() = catalog as MediaLibraryWriter

    override val network: NetworkMonitor = NetworkMonitor(appContext)
    override val isOnline: Boolean get() = network.isOnline

    private val settingsStore = PlaybackSettingsStore(appContext, "sidetracks_settings_jellyfin")
    private val _settings = MutableStateFlow(
        runCatching { settingsStore.read() }.getOrDefault(PlaybackSettings())
    )
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    private val queueStore = FileQueueStore(File(appContext.filesDir, "sidetracks-jellyfin-queue.bin"))
    private val historyStore = FileHistoryStore(File(appContext.filesDir, "sidetracks-jellyfin-history.bin"))
    private val positionStore = FilePositionStore(File(appContext.filesDir, "sidetracks-jellyfin-resume.bin"))

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
        if (_signedIn.value) runCatching { auth.installArtworkHeaders() }
    }

    private fun startReporting(controller: PlaybackController) {
        JellyfinPlaybackReporter(
            api = ::api,
            playbackInfo = catalog::playbackInfo,
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
            // The server accepted the sign-in and the in-memory session works; only the next
            // launch will have to ask again.
            _signedIn.value = true
            throw e
        }
        // Reset the client so the new token gets its own SDK instance.
        client = null
        clientToken = null
        _signedIn.value = true
        seedFromServer()
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
        // Jellyfin has no loudness parameter (D4/capability), but the preference is kept so the
        // settings row behaves the same when the service can use it.
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

    /**
     * Tells the server this client exists (so its session list is honest) and seeds the resume
     * point from the account's own resume list (D13). Both are best-effort: a server that is
     * briefly slow must not turn a successful sign-in into a failure.
     */
    private suspend fun seedFromServer() {
        val client = runCatching { api() }.getOrNull() ?: return
        runCatching { client.announceCapabilities() }
        val resumed = runCatching { client.resumeItems(1) }.getOrNull()?.items?.firstOrNull() ?: return
        val ticks = resumed.playbackPositionTicks ?: 0L
        if (ticks <= 0L) return
        val type = resumed.kind.toMediaType() ?: return
        val point = ResumePoint(MediaKey(ServiceId.Jellyfin, type, resumed.id), ticks.ticksToMs())
        positionStore.write(point)
        resumePoint = point
    }

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

/** Jellyfin's identity and label, available without building a session or a runtime. */
object JellyfinService {
    val id: ServiceId get() = ServiceId.Jellyfin
    const val DISPLAY_NAME: String = "Jellyfin"
}

/** Jellyfin as a registered service; the picker and sign-in screen read these values. */
class JellyfinProvider(
    private val session: JellyfinRuntime
) : MusicServiceProvider {
    override val id: ServiceId = JellyfinService.id
    override val displayName: String = JellyfinService.DISPLAY_NAME
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Jellyfin

    /**
     * Password sign-in only in v1 (D5). Quick Connect needs an `AuthInput` variant and a code
     * screen, so it stays a follow-up. The public demo prefills the form, and its account has no
     * password, which the provider accepts.
     */
    override val signInKind: SignInKind = SignInKind.Password(
        PasswordDefaults(JellyfinDemo.SERVER_URL, JellyfinDemo.USERNAME, JellyfinDemo.PASSWORD)
    )

    override fun createAuth(): ServiceAuth = session.auth
}

/** The catalog kind a server resume item can seed. Music libraries only resume tracks. */
internal fun JellyfinKind.toMediaType(): MediaType? = when (this) {
    JellyfinKind.Audio -> MediaType.Track
    JellyfinKind.Album -> MediaType.Album
    JellyfinKind.Artist -> MediaType.Artist
    JellyfinKind.Playlist -> MediaType.Playlist
    JellyfinKind.Other -> null
}
