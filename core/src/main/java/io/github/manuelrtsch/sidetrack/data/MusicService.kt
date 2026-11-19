package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.LibrarySection
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Station
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/**
 * The account a provider is currently signed in with. [credentials] is deliberately opaque: each
 * provider decides what it needs to reconnect, and nothing outside the provider reads it.
 */
data class ServiceAccount(
    val service: ServiceId,
    val userId: String,
    val displayName: String,
    val serverUrl: String? = null,
    val credentials: Map<String, String> = emptyMap()
)

/** A provider-neutral failure category, so the UI can explain an error without naming a service. */
enum class ServiceFailure {
    Unavailable,
    NotConnected,
    AuthenticationExpired,
    Network,
    RateLimited,
    TrackUnavailable,
    Unknown
}

/**
 * What the sign-in screen must offer for this service.
 *
 * A provider describes its own sign-in instead of the UI comparing service ids, so a new
 * service can reuse an existing form without editing shared code.
 */
sealed interface SignInKind {
    /** OAuth/custom tab, no fields. */
    data object Browser : SignInKind

    /** Server address, username and password. [defaults] pre-fills a public demo, if any. */
    data class Password(val defaults: PasswordDefaults? = null) : SignInKind
}

/** The values a public demo prefills on a password sign-in form. */
data class PasswordDefaults(val serverUrl: String, val username: String, val password: String)

/** A catalog or playback failure carrying a [ServiceFailure] the UI can map to copy. */
class ServiceException(
    val failure: ServiceFailure,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** What a sign-in attempt needs. A browser flow or a self-hosted server's password. */
sealed interface AuthInput {
    /** A provider that signs in through a browser or custom tab. */
    data class Browser(val openBrowser: suspend (String) -> Unit) : AuthInput

    data class Password(
        val serverUrl: String,
        val username: String,
        val password: String
    ) : AuthInput
}

/** Provider-owned authentication. The registry never sees tokens or passwords. */
interface ServiceAuth {
    suspend fun signIn(input: AuthInput): ServiceAccount
    suspend fun restore(): ServiceAccount?
    suspend fun signOut()
}

/**
 * Read side of a music service, expressed in the neutral domain model. Each provider maps its
 * own catalog onto this contract; the UI never sees a provider-specific type.
 *
 * This is the only catalog the UI sees. Service-specific ids live inside [MediaKey]; nothing here
 * assumes a particular service's URI or collection vocabulary.
 */
interface MediaCatalog {
    val capabilities: ServiceCapabilities

    suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry>
    suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection?
    suspend fun artist(key: MediaKey): Artist?
    suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album>
    suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track>
    suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults

    /** Resolves the URL and request headers the playback engine needs for [item]. */
    suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource

    /** Services without radio return an empty page; the UI hides the action by capability. */
    suspend fun radio(seed: MediaKey, offset: Int, limit: Int): Page<Track> = Page()

    /** A station plus the playlist that backs it, when the service can report one. */
    suspend fun radioStation(seed: MediaKey, limit: Int): Station = Station(radio(seed, 0, limit).items)
}

/** Write side of a music service. Optional operations are capability-gated by the caller. */
interface MediaLibraryWriter {
    suspend fun isSaved(item: MediaKey): Boolean?
    suspend fun isFollowed(artist: MediaKey): Boolean?
    suspend fun setSaved(item: MediaKey, saved: Boolean)
    suspend fun setFollowed(artist: MediaKey, followed: Boolean)

    /** Appends [item] to [playlist] and returns the index it landed at, for Undo. */
    suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? = null

    suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem) = Unit

    /**
     * Creates a new empty playlist named [name] and returns it, or null when the service cannot
     * (the app never creates playlists it owns on a service that does not support it).
     */
    suspend fun createPlaylist(name: String): Playlist? = null
}

/**
 * A service this build can provision, whether or not it is the selected one.
 *
 * The picker needs a name before any runtime exists, so identity and label live here rather than
 * on [MusicServiceProvider]. Both come from the same constants the provider uses, so a name can
 * never drift between the picker and the running service.
 */
data class ServiceChoice(val id: ServiceId, val displayName: String)

/**
 * One music service. The app owns exactly one selected provider; the UI asks the provider's
 * [capabilities] instead of asking which service is running.
 */
interface MusicServiceProvider {
    val id: ServiceId
    val displayName: String
    val capabilities: ServiceCapabilities

    /** What the sign-in screen must offer for this service. */
    val signInKind: SignInKind

    fun createAuth(): ServiceAuth
}
