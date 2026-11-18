package io.github.manuelrtsch.sidetrack

import androidx.annotation.DrawableRes

/**
 * Service ids as they can safely travel through a navigation route.
 *
 * org.json's `optString` returns the literal string "null" for a JSON null, and Navigation's
 * `NavType.StringType` parses the literal "null" back into a real null. A non-nullable route
 * argument then fails verification while navigating and takes the process down, so ids are
 * normalised here before they reach a route.
 */
internal fun String?.mediaIdOrNull(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

/** A saved album, playlist or show. Collections share one screen, so they share one shape. */
enum class CollectionType(val route: String, val mediaType: MediaType) {
    Album("album", MediaType.Album),
    Playlist("playlist", MediaType.Playlist),
    Show("show", MediaType.Show)
}

/**
 * Everything that can be played. Albums and playlists hold [Track]s, shows hold [Episode]s; the
 * queue and Now Playing never need to know which screen it came from.
 *
 * [key] is the identity. [id] and [uri] are service details kept for the service adapter and the
 * existing UI; new code should compare and persist [key].
 */
sealed interface MediaItem : LibraryEntry {
    override val key: MediaKey
    val service: ServiceId
    val id: String
    val title: String
    val creator: String
    val artistId: String?
    val artRes: Int
    val durationMs: Long
    val playable: Boolean
    val explicit: Boolean

    /** Canonical service URI for the real catalog; null for the local test fixtures. */
    val uri: String? get() = null

    /** Remote cover art for the real catalog; null for local fixtures and placeholder art. */
    val artUrl: String? get() = null
}

data class Track(
    override val id: String,
    override val title: String,
    val artist: String,
    override val artistId: String,
    val albumId: String,
    override val artRes: Int,
    override val durationMs: Long,
    override val explicit: Boolean = false,
    override val playable: Boolean = true,
    override val uri: String? = null,
    override val artUrl: String? = null,
    override val service: ServiceId = ServiceId.Local
) : MediaItem {
    override val creator: String get() = artist
    override val key: MediaKey get() = MediaKey(service, MediaType.Track, id)
}

data class Episode(
    override val id: String,
    override val title: String,
    val show: String,
    val showId: String,
    val published: String,
    override val artRes: Int,
    override val durationMs: Long,
    val video: Boolean = false,
    override val uri: String? = null,
    override val artUrl: String? = null,
    /** Show notes, already plain text. The catalog may return HTML; [plainPodcastText] cleans it. */
    val description: String = "",
    /** The service's publishing number, when its metadata carries one. */
    val episodeNumber: Int? = null,
    override val explicit: Boolean = false,
    override val service: ServiceId = ServiceId.Local
) : MediaItem {
    override val creator: String get() = show
    override val artistId: String? get() = null
    override val playable: Boolean get() = !video
    override val key: MediaKey get() = MediaKey(service, MediaType.Episode, id)
}

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val year: Int,
    @param:DrawableRes val artRes: Int,
    val tracks: List<Track>,
    val artUrl: String? = null,
    val trackCount: Int = tracks.size,
    val service: ServiceId = ServiceId.Local
) : LibraryEntry {
    override val key: MediaKey get() = MediaKey(service, MediaType.Album, id)
}

data class Playlist(
    val id: String,
    val name: String,
    val owner: String,
    val ownerId: String?,
    @param:DrawableRes val artRes: Int,
    val tracks: List<Track>,
    val artUrl: String? = null,
    val trackCount: Int = tracks.size,
    /** Whether the signed-in user may add or remove items. The add-to-playlist picker filters on it. */
    val editable: Boolean = false,
    val folder: String? = null,
    val service: ServiceId = ServiceId.Local
) : LibraryEntry {
    override val key: MediaKey get() = MediaKey(service, MediaType.Playlist, id)
}

data class Show(
    val id: String,
    val name: String,
    val publisher: String,
    @param:DrawableRes val artRes: Int,
    val episodes: List<Episode>,
    val artUrl: String? = null,
    val episodeCount: Int = episodes.size,
    val description: String = "",
    val service: ServiceId = ServiceId.Local
) : LibraryEntry {
    override val key: MediaKey get() = MediaKey(service, MediaType.Show, id)
}

data class Artist(
    val id: String,
    val name: String,
    @param:DrawableRes val artRes: Int,
    val albumIds: List<String>,
    val artUrl: String? = null,
    val albumCount: Int = albumIds.size,
    val service: ServiceId = ServiceId.Local
) : LibraryEntry {
    override val key: MediaKey get() = MediaKey(service, MediaType.Artist, id)
}

/** One screen's worth of a collection, already resolved by the gateway. */
data class Collection(
    val type: CollectionType,
    val id: String,
    val title: String,
    val subtitle: String,
    val artRes: Int,
    val items: List<MediaItem>,
    val artistId: String? = null,
    val artUrl: String? = null,
    val totalItems: Int = items.size,
    val hasMore: Boolean = false,
    /** False for generated pages (a station without a backing playlist) that cannot be saved. */
    val saveable: Boolean = true,
    /** Show notes for a podcast collection; the other collection kinds do not have any. */
    val description: String = "",
    val service: ServiceId = ServiceId.Local
) {
    val key: MediaKey get() = MediaKey(service, type.mediaType, id)
    val kind: MediaType get() = type.mediaType
}

/**
 * A radio station: its resolved tracks and, when the transport can report it, the playlist that
 * backs the station. Saving that playlist is how a station becomes a library playlist.
 */
data class Station(
    val tracks: List<Track>,
    val playlistId: String? = null
)

/**
 * How many rows every catalog request and every on-screen list asks for. One batch size keeps
 * the list, the network and the local history in step: 20 rows, then a Load more button.
 */
const val CATALOG_PAGE_SIZE = 20

/** Search reuses the shared page size so every scope fills the same visible batch. */
const val SEARCH_PAGE_SIZE = CATALOG_PAGE_SIZE

/** Rows cached per category while the Library preloads, and used for saved-state hints. */
const val LIBRARY_SECTION_SIZE = CATALOG_PAGE_SIZE

/** Recently played rows on Home before "See more history". */
const val RECENT_PREVIEW_SIZE = CATALOG_PAGE_SIZE
const val ARTIST_PAGE_SIZE = CATALOG_PAGE_SIZE

/** One page of a catalog list. [total] is null when the endpoint doesn't report it. */
data class Page<T>(
    val items: List<T> = emptyList(),
    val total: Int? = null,
    val hasMore: Boolean = false
) {
    companion object {
        fun <T> of(items: List<T>, offset: Int, limit: Int, total: Int? = null): Page<T> = Page(
            items = items,
            total = total,
            hasMore = if (total != null) offset + items.size < total else items.size >= limit
        )
    }
}

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val shows: List<Show> = emptyList()
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && shows.isEmpty()
}

enum class LibraryFilter(val label: String, val empty: String) {
    LikedSongs("Liked songs", "No liked songs"),
    Playlists("Playlists", "No saved playlists"),
    Albums("Albums", "No saved albums"),
    Artists("Followed artists", "No followed artists"),
    Shows("Shows", "No saved shows")
}

enum class SearchScope(val label: String) {
    Tracks("Tracks"),
    Artists("Artists"),
    Albums("Albums"),
    Playlists("Playlists"),
    Shows("Shows")
}

/**
 * Toggles one category of the search scope, but always keeps at least one. An empty set has no
 * result list to show, and the native catalog used to read it as "search everything".
 */
fun toggledSearchScopes(current: Set<SearchScope>, scope: SearchScope): Set<SearchScope> = when {
    scope !in current -> current + scope
    current.size > 1 -> current - scope
    else -> current
}

enum class RepeatMode(val label: String) {
    Off("Repeat: off"),
    Context("Repeat: context"),
    Track("Repeat: track")
    ;

    fun next(): RepeatMode = when (this) {
        Off -> Context
        Context -> Track
        Track -> Off
    }
}

enum class AudioQuality(val label: String, val bitrateKbps: Int) {
    Normal("Normal", 96),
    High("High", 160),
    VeryHigh("Max", 320)
    ;

    fun next(): AudioQuality = entries[(ordinal + 1) % entries.size]
}

data class QueueItem(
    val item: MediaItem,
    val isCurrent: Boolean = false
)

/** Playlist subtitle, prefixed with its library folder when the rootlist places it in one. */
fun playlistSubtitle(item: Playlist): String = buildString {
    item.folder?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
    append(item.owner).append(" · ").append(item.trackCount).append(" tracks")
}

/** Service metadata dates are ISO calendar dates; show them the way Sidekit shows release dates. */
fun formatPublishedDate(value: String): String {
    val date = value.substringBefore('T').substringBefore(' ')
    if (date.length != 10 || date[4] != '-' || date[7] != '-') return value
    return runCatching {
        java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu"))
    }.getOrDefault(value)
}
