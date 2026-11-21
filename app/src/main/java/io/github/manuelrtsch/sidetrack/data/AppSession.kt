package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import android.util.Log
import io.github.manuelrtsch.sidetrack.BuildConfig
import io.github.manuelrtsch.sidetrack.DemoContentSource
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.EncryptedSpotifyCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SpotifyAuth
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every service this build can provision, in the order the picker offers them.
 *
 * The Subsonic family is listed once per server product even though one adapter serves them all,
 * because the entry a user picks is what they recognise; the id keeps their credentials, library
 * and local stores apart from the other entries. The picker sorts alphabetically by what the row
 * says, so a new entry lands where a user would look for it instead of at the end of a list.
 */
val KnownServices: List<ServiceChoice> = buildList {
    add(ServiceChoice(SpotifyService.id, SpotifyService.DISPLAY_NAME))
    SubsonicFamily.entries.forEach { add(ServiceChoice(it.id, it.displayName)) }
    add(ServiceChoice(JellyfinService.id, JellyfinService.DISPLAY_NAME))
    add(ServiceChoice(PlexService.id, PlexService.DISPLAY_NAME))
}.sortedBy { it.displayName.lowercase() }

/** The picker's view of [id], or null when this build does not know the service. */
fun serviceChoice(id: ServiceId): ServiceChoice? = KnownServices.firstOrNull { it.id == id }

/**
 * Process-wide app session for the one selected service.
 *
 * A service is chosen once, before sign-in, and remembered. Only that service's runtime is ever
 * built, so no other provider keeps a media session, a queue or a store open behind the UI.
 * Changing services means signing out first, which is the only way back to the picker.
 */
class AppSession internal constructor(
    private val store: ActiveServiceStore?
) {
    private data class Installed(
        val choice: ServiceChoice,
        val provider: MusicServiceProvider,
        val runtime: ServiceRuntime
    )

    @Volatile
    private var installed: Installed? = null
    private val _active = MutableStateFlow<ServiceId?>(null)

    /** The selected service, or null until the picker provisions one. Screens observe this. */
    val active: StateFlow<ServiceId?> = _active.asStateFlow()

    val provisioned: Boolean get() = installed != null
    val serviceName: String get() = installed?.choice?.displayName.orEmpty()
    val provider: MusicServiceProvider? get() = installed?.provider

    /** The selected runtime, or null before provisioning. */
    val runtimeOrNull: ServiceRuntime? get() = installed?.runtime

    /**
     * The selected runtime. Only screens behind provisioning may ask for this; the launch path
     * checks [provisioned] first, so a missing service is a programming error, not a user state.
     */
    val runtime: ServiceRuntime
        get() = installed?.runtime ?: error("No music service is selected yet")

    val signedIn: StateFlow<Boolean> get() = installed?.runtime?.signedIn ?: SignedOut
    val playback: PlaybackController get() = runtime.playback

    /** The player, when one is selected. Notification callbacks can arrive before provisioning. */
    val playbackOrNull: PlaybackController? get() = installed?.runtime?.playback

    /** Selects a service for this process and for the next launch. */
    internal fun install(choice: ServiceChoice, provider: MusicServiceProvider, runtime: ServiceRuntime) {
        installed = Installed(choice, provider, runtime)
        _active.value = choice.id
        store?.write(choice.id)
    }

    // A notification can reach the service before the picker has run, so transport calls are
    // silent until a service exists instead of throwing at the platform.
    fun pause() {
        installed?.runtime?.pause()
    }
    fun togglePlayback() {
        installed?.runtime?.togglePlayback()
    }
    fun stopPlayback() {
        installed?.runtime?.stopPlayback()
    }
    fun haltPlayback() {
        installed?.runtime?.haltPlayback()
    }

    private companion object {
        val SignedOut: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

/** Remembers the selected service; not secret, but it belongs with the other encrypted state. */
internal class ActiveServiceStore(context: Context) {
    private val store = GuardedEncryptedStore(context, "sidetracks_service")

    fun read(): ServiceId? = runCatching {
        val value = store.read().optString(KEY_ACTIVE).takeIf { it.isNotEmpty() } ?: return null
        ServiceId(value).takeIf { serviceChoice(it) != null }
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
    private data class Selection(val provider: MusicServiceProvider, val runtime: ServiceRuntime)

    @Volatile
    private var instance: AppSession? = null

    fun get(context: Context): AppSession = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    /**
     * Provisions [id] for this process and the next launch. Returns false when the service is
     * unknown to this build or its runtime cannot be opened, so the picker can say so.
     */
    fun select(context: Context, id: ServiceId): Boolean {
        val app = get(context)
        val choice = serviceChoice(id) ?: return false
        // Re-selecting the service that is already installed keeps its runtime: signing out
        // cleared the credentials, and rebuilding a native session to show the same form is waste.
        if (app.provisioned && app.active.value == id) return true
        val selection = build(context.applicationContext, choice) ?: return false
        synchronized(this) {
            // The picker is only reachable signed out, so nothing should be playing. Drop the old
            // runtime anyway: only one service may hold a media session.
            runCatching { app.runtimeOrNull?.pause() }
            app.install(choice, selection.provider, selection.runtime)
        }
        return true
    }

    private fun create(context: Context): AppSession {
        // A session that cannot be built must not take the launch down with it: without a stored
        // selection the app opens at the picker, and the next launch retries the real store.
        val store = runCatching { ActiveServiceStore(context) }
            .onFailure { Log.w("SidetrackSession", "Could not read the selected service", it) }
            .getOrNull()
        val app = AppSession(store)
        val choice = store?.read()?.let(::serviceChoice) ?: adoptedSpotify(context) ?: return app
        val selection = build(context, choice) ?: return app
        app.install(choice, selection.provider, selection.runtime)
        return app
    }

    /**
     * Spotify for an install that predates the picker.
     *
     * Nothing stored the selection before this screen existed, so a signed-in user would
     * otherwise be sent to the picker on the first launch after updating. A stored Spotify
     * session is the one thing such an install must have, so it decides the answer and
     * [AppSession.install] records it. A fresh install has no session and starts at the picker.
     */
    private fun adoptedSpotify(context: Context): ServiceChoice? {
        val signedIn = runCatching {
            SpotifyAuth(EncryptedSpotifyCredentialStore(context)).hasCredentials()
        }.getOrDefault(false)
        return if (signedIn) serviceChoice(ServiceId.Spotify) else null
    }

    /** Builds one provider and its runtime, or null when this service cannot be opened at all. */
    private fun build(context: Context, choice: ServiceChoice): Selection? = when (choice.id) {
        ServiceId.Spotify -> {
            val session = try {
                SpotifySession(
                    SpotifyAuth(
                        EncryptedSpotifyCredentialStore(context),
                        authScheme = BuildConfig.AUTH_SCHEME
                    ),
                    context.cacheDir.absolutePath,
                    context,
                    // Only the app knows whether this build carries a demo pack (see DemoContent).
                    demo = DemoContentSource.current
                )
            } catch (e: Exception) {
                Log.w("SidetrackSession", "Falling back to a signed-out Spotify session", e)
                SpotifySession()
            }
            Selection(SpotifyProvider(session), session)
        }

        ServiceId.Jellyfin -> runCatching { JellyfinRuntime(context) }
            .onFailure { Log.w("SidetrackSession", "Could not build the Jellyfin session", it) }
            .getOrNull()
            ?.let { Selection(JellyfinProvider(it), it) }

        ServiceId.Plex -> runCatching { PlexRuntime(context) }
            .onFailure { Log.w("SidetrackSession", "Could not build the Plex session", it) }
            .getOrNull()
            ?.let { Selection(PlexProvider(it), it) }

        // Every remaining service is a member of the Subsonic family: one adapter, one runtime
        // class, and only the entry's identity, label and store names differ.
        else -> SubsonicFamily.of(choice.id)?.let { family ->
            runCatching { SubsonicRuntime(context, family) }
                .onFailure { Log.w("SidetrackSession", "Could not build the ${family.displayName} session", it) }
                .getOrNull()
                ?.let { Selection(SubsonicProvider(it, family), it) }
        }
    }
}
