package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.SubsonicAuth
import io.github.manuelrtsch.sidetrack.auth.SubsonicCredentialStore
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
 * A signed-in account on one member of the Subsonic family.
 *
 * Catalog, auth and writer are the shared Subsonic adapter; [family] only supplies the identity,
 * label and store names, so Navidrome, Airsonic, Gonic, Nextcloud Music and Subsonic itself all run
 * the same code. Playback is the shared [PlaybackController] over Media3/ExoPlayer, so the media
 * session, notification and audio focus behave exactly as they do for Spotify. Queue, history and
 * resume files are per family entry, so switching services never mixes two accounts.
 */
class SubsonicRuntime(
    context: Context,
    val family: SubsonicFamily = SubsonicFamily.Navidrome,
    val auth: SubsonicAuth = SubsonicAuth(
        SubsonicCredentialStore(context, family.credentialStoreName),
        family
    )
) : ServiceRuntime,
    CacheRuntime,
    QualitySettingsRuntime {
    private val appContext = context.applicationContext

    override val service: ServiceId = family.id
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic

    private val _signedIn = MutableStateFlow(runCatching { auth.hasCredentials() }.getOrDefault(false))
    override val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    override val catalog: MediaCatalog = SubsonicCatalog(
        family = family,
        api = { auth.api() },
        settings = { _settings.value }
    )
    override val writer: MediaLibraryWriter get() = catalog as MediaLibraryWriter

    override val network: NetworkMonitor = NetworkMonitor(appContext)
    override val isOnline: Boolean get() = network.isOnline

    private val settingsStore = PlaybackSettingsStore(appContext, family.settingsStoreName)
    private val _settings = MutableStateFlow(
        runCatching { settingsStore.read() }.getOrDefault(PlaybackSettings())
    )
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    private val queueStore = FileQueueStore(File(appContext.filesDir, family.localStoreName("queue")))
    private val historyStore = FileHistoryStore(File(appContext.filesDir, family.localStoreName("history")))
    private val positionStore = FilePositionStore(File(appContext.filesDir, family.localStoreName("resume")))

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
    override val playback: PlaybackController by lazy {
        val engine = Media3PlaybackEngine {
            ExoPlayerMedia3Player(appContext)
        }
        PlaybackController(
            engine = engine,
            scope = playbackScope,
            context = appContext
        )
    }

    override suspend fun signIn(input: AuthInput) {
        try {
            auth.signIn(input)
        } catch (e: io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException) {
            // The server accepted the sign-in and the in-memory credentials work; only the next
            // launch will have to ask again.
            _signedIn.value = true
            throw e
        }
        _signedIn.value = true
    }

    override fun signOut() {
        rememberPosition(clear = true)
        queue.stop()
        history.clear()
        playbackScope.launch { runCatching { playback.stop() } }
        auth.clear()
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
        // Gapless is not surfaced until the Media3 engine applies per-track seamless handoff;
        // persist the preference so it is ready when that lands.
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

/**
 * One family member as a registered service, including the auth object [AppSession] asks for after
 * sign-in. Identity and label come from [family], so the picker and the running session cannot
 * disagree about what the user selected.
 */
class SubsonicProvider(
    private val session: SubsonicRuntime,
    private val family: SubsonicFamily = session.family
) : MusicServiceProvider {
    override val id: ServiceId = family.id
    override val displayName: String = family.displayName
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic
    override val signInKind: SignInKind = SignInKind.Password(family.passwordDefaults)

    override fun createAuth(): ServiceAuth = session.auth
}
