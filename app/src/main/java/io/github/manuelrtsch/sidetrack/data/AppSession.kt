package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import android.util.Log
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.EncryptedSpotifyCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SpotifyAuth
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The services this build knows about and which one is active. One active service for v1: the
 * queue, history and resume files are already namespaced by [MediaKey], so switching later does
 * not mix two accounts.
 */
class ServiceRegistry(
    providers: List<MusicServiceProvider>,
    initial: ServiceId = ServiceId.Spotify,
    private val onActiveChanged: (ServiceId) -> Unit = {}
) {
    private val byId = providers.associateBy { it.id }
    private val _active = MutableStateFlow(initial)

    val active: StateFlow<ServiceId> = _active
    val providers: List<MusicServiceProvider> get() = byId.values.toList()

    /** The signed-in account for the active service, when there is one. */
    var account: ServiceAccount? = null

    fun provider(id: ServiceId = _active.value): MusicServiceProvider = byId[id] ?: error("No music service registered for ${id.value}")

    /** Returns false when the service is not installed in this build. */
    fun switchTo(id: ServiceId): Boolean {
        if (id !in byId) return false
        _active.value = id
        onActiveChanged(id)
        return true
    }
}

/**
 * Process-wide app session. It owns the active [ServiceRegistry] and one [ServiceRuntime] per
 * installed service. The UI talks to [runtime], never to a provider-specific session, so adding a
 * service does not add a second data path.
 */
class AppSession(
    val registry: ServiceRegistry,
    runtimes: List<ServiceRuntime>
) {
    private val runtimes: Map<ServiceId, ServiceRuntime> = runtimes.associateBy { it.service }

    val activeService: ServiceId get() = registry.active.value
    val provider: MusicServiceProvider get() = registry.provider()
    val runtime: ServiceRuntime
        get() = runtimes[activeService]
            ?: error("No runtime registered for ${activeService.value}")
    val capabilities: ServiceCapabilities get() = runtime.capabilities
    val account: ServiceAccount?
        get() = if (runtime.signedIn.value) {
            registry.account?.takeIf { it.service == activeService }
                ?: ServiceAccount(runtime.service, runtime.service.value, provider.displayName)
        } else {
            null
        }
    val availableServices: List<MusicServiceProvider> get() = registry.providers

    fun switchService(id: ServiceId): Boolean {
        if (id == activeService) return true
        val previous = runtime
        if (!registry.switchTo(id)) return false
        // One active service for v1: silence the old one and let the new owner claim the media
        // session on its next play. The queue and history stay with their own runtime.
        runCatching { previous.pause() }
        runCatching { runtime.playback.register() }
        return true
    }

    val signedIn: StateFlow<Boolean> get() = runtime.signedIn
    val playback: PlaybackController get() = runtime.playback
    val catalog: MediaCatalog get() = runtime.catalog
    val writer: MediaLibraryWriter? get() = runtime.writer
    val queue: QueueRepository get() = runtime.queue
    val history: HistoryRepository get() = runtime.history
    val episodeProgress: EpisodeProgressRepository get() = runtime.episodeProgress
    val episodeMeta: EpisodeMetaRepository get() = runtime.episodeMeta
    val settings: StateFlow<PlaybackSettings> get() = runtime.settings
    val resumePoint: ResumePoint? get() = runtime.resumePoint
    val nativeAvailable: Boolean get() = runtime.nativeAvailable
    val isOnline: Boolean get() = runtime.isOnline
    val halting: Boolean get() = runtime.halting

    suspend fun signIn(input: AuthInput) {
        runtime.signIn(input)
        registry.account = provider.createAuth().restore()
    }

    fun signOut() {
        runtime.signOut()
        if (registry.account?.service == activeService) registry.account = null
    }

    fun pause() = runtime.pause()
    suspend fun play(item: MediaItem, positionMs: Long = 0L, gapless: Boolean = false) = runtime.play(item, positionMs, gapless)

    suspend fun resume(): Boolean = runtime.resume()
    suspend fun seek(positionMs: Long) = runtime.seek(positionMs)
    fun togglePlayback() = runtime.togglePlayback()
    fun stopPlayback() = runtime.stopPlayback()
    fun haltPlayback() = runtime.haltPlayback()
    fun setAudioQuality(quality: AudioQuality) = runtime.setAudioQuality(quality)
    fun setNormalization(enabled: Boolean) = runtime.setNormalization(enabled)
    fun setGapless(enabled: Boolean) = runtime.setGapless(enabled)
    fun setAutoplay(enabled: Boolean) = runtime.setAutoplay(enabled)
    fun cacheSizeBytes(): Long = runtime.cacheSizeBytes()
    fun clearAudioCache() = runtime.clearAudioCache()
    fun clearCaches() = runtime.clearCaches()
    fun notePlayRequest(id: Long, uri: String?) = runtime.notePlayRequest(id, uri)
    fun consumeHaltStop(uri: String?, playRequestId: Long): Boolean = runtime.consumeHaltStop(uri, playRequestId)
}

/** Remembers the last selected service; not secret, but it belongs with the other encrypted state. */
private class ActiveServiceStore(context: Context) {
    private val store = GuardedEncryptedStore(context, "sidetracks_service")

    fun read(): ServiceId? = runCatching {
        val value = store.read().optString(KEY_ACTIVE).takeIf { it.isNotEmpty() } ?: return null
        ServiceId(value).takeIf { it == ServiceId.Spotify || it == ServiceId.Navidrome }
    }.getOrNull()

    fun write(id: ServiceId) {
        runCatching { store.write(org.json.JSONObject().put(KEY_ACTIVE, id.value)) }
    }

    private companion object {
        const val KEY_ACTIVE = "active"
    }
}

/** Lazily creates the one app session for the process, matching how Sidekit owns local playback. */
object AppSessions {
    @Volatile
    private var instance: AppSession? = null

    fun get(context: Context): AppSession = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): AppSession {
        // A session that cannot be built must not take the launch down with it: the context-free
        // fallback still composes a signed-out app, and the next launch retries the real store.
        val spotify = try {
            SpotifySession(
                SpotifyAuth(EncryptedSpotifyCredentialStore(context)),
                context.cacheDir.absolutePath,
                context
            )
        } catch (e: Exception) {
            Log.w("SidetrackSession", "Falling back to a signed-out session", e)
            SpotifySession()
        }
        // Navidrome is always installed, signed in or not: the service picker must be able to offer
        // it on a fresh install. Its encrypted store is read lazily, so a lost key is signed out.
        val navidrome = runCatching { NavidromeRuntime(context) }
            .onFailure { Log.w("SidetrackSession", "Could not build the Navidrome session", it) }
            .getOrNull()
        val serviceStore = runCatching { ActiveServiceStore(context) }.getOrNull()
        val initial = serviceStore?.read()
            ?.takeIf { id -> id == ServiceId.Spotify || (id == ServiceId.Navidrome && navidrome != null) }
            ?: ServiceId.Spotify
        val registry = ServiceRegistry(
            listOfNotNull(
                SpotifyProvider(spotify),
                navidrome?.let(::NavidromeProvider)
            ),
            initial = initial,
            onActiveChanged = { serviceStore?.write(it) }
        )
        if (spotify.signedIn.value) {
            registry.account = ServiceAccount(ServiceId.Spotify, "spotify", "Spotify")
        }
        return AppSession(registry, listOfNotNull(spotify, navidrome))
    }
}
