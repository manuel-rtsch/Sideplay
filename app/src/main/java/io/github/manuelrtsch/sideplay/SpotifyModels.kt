package io.github.manuelrtsch.sideplay

import androidx.annotation.DrawableRes

/** A saved album, playlist or show. Collections share one screen, so they share one shape. */
enum class SpotifyCollectionType(val route: String) {
    Album("album"),
    Playlist("playlist"),
    Show("show")
}

/**
 * Everything that can be played. Albums and playlists hold [TrackItem]s, shows hold
 * [EpisodeItem]s; the queue and Now Playing never need to know which screen it came from.
 */
sealed interface SpotifyMediaItem {
    val id: String
    val title: String
    val creator: String
    val artistId: String?
    val artRes: Int
    val durationMs: Long
    val playable: Boolean
    val explicit: Boolean

    /** Canonical Spotify URI for the real catalog; null for the local test fixtures. */
    val uri: String? get() = null

    /** Remote cover art for the real catalog; null for local fixtures and placeholder art. */
    val artUrl: String? get() = null
}

data class SpotifyTrack(
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
    override val artUrl: String? = null
) : SpotifyMediaItem {
    override val creator: String get() = artist
}

data class SpotifyEpisode(
    override val id: String,
    override val title: String,
    val show: String,
    val showId: String,
    val published: String,
    override val artRes: Int,
    override val durationMs: Long,
    val video: Boolean = false,
    override val uri: String? = null,
    override val artUrl: String? = null
) : SpotifyMediaItem {
    override val creator: String get() = show
    override val artistId: String? get() = null
    override val playable: Boolean get() = !video
    override val explicit: Boolean get() = false
}

data class SpotifyAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val year: Int,
    @param:DrawableRes val artRes: Int,
    val tracks: List<SpotifyTrack>,
    val artUrl: String? = null,
    val trackCount: Int = tracks.size
)

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val ownerId: String?,
    @param:DrawableRes val artRes: Int,
    val tracks: List<SpotifyTrack>,
    val artUrl: String? = null,
    val trackCount: Int = tracks.size,
    val folder: String? = null
)

data class SpotifyShow(
    val id: String,
    val name: String,
    val publisher: String,
    @param:DrawableRes val artRes: Int,
    val episodes: List<SpotifyEpisode>,
    val artUrl: String? = null,
    val episodeCount: Int = episodes.size
)

data class SpotifyArtist(
    val id: String,
    val name: String,
    @param:DrawableRes val artRes: Int,
    val albumIds: List<String>,
    val artUrl: String? = null,
    val albumCount: Int = albumIds.size
)

/** One screen's worth of a collection, already resolved by the gateway. */
data class SpotifyCollection(
    val type: SpotifyCollectionType,
    val id: String,
    val title: String,
    val subtitle: String,
    val artRes: Int,
    val items: List<SpotifyMediaItem>,
    val artistId: String? = null,
    val artUrl: String? = null,
    val totalItems: Int = items.size,
    val hasMore: Boolean = false
)

/** How many rows a catalog request asks for. Kept small for the Sidephone's weak CPU. */
const val SPOTIFY_PAGE_SIZE = 50
const val SPOTIFY_SEARCH_PAGE_SIZE = 5

/** Rows shown per category on the Library overview before "See more". */
const val LIBRARY_SECTION_SIZE = 5

/** Recently played rows on Home before "See more history". */
const val RECENT_PREVIEW_SIZE = 5
const val SPOTIFY_ARTIST_PAGE_SIZE = 5

/** One page of a catalog list. [total] is null when the endpoint doesn't report it. */
data class SpotifyPage<T>(
    val items: List<T> = emptyList(),
    val total: Int? = null,
    val hasMore: Boolean = false
) {
    companion object {
        fun <T> of(items: List<T>, offset: Int, limit: Int, total: Int? = null): SpotifyPage<T> = SpotifyPage(
            items = items,
            total = total,
            hasMore = if (total != null) offset + items.size < total else items.size >= limit
        )
    }
}

data class SpotifySearchResults(
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val shows: List<SpotifyShow> = emptyList()
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
    VeryHigh("Very high", 320)
    ;

    fun next(): AudioQuality = entries[(ordinal + 1) % entries.size]
}

data class SpotifyQueueItem(
    val item: SpotifyMediaItem,
    val isCurrent: Boolean = false
)

/** Playlist subtitle, prefixed with its library folder when the rootlist places it in one. */
fun playlistSubtitle(item: SpotifyPlaylist): String = buildString {
    item.folder?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
    append(item.owner).append(" · ").append(item.trackCount).append(" tracks")
}

/** Spotify metadata dates are ISO calendar dates; show them the way Sidekit shows release dates. */
fun formatPublishedDate(value: String): String {
    val date = value.substringBefore('T').substringBefore(' ')
    if (date.length != 10 || date[4] != '-' || date[7] != '-') return value
    return runCatching {
        java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu"))
    }.getOrDefault(value)
}
