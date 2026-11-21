package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.PlaylistActions
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track

/** The Plex object kinds Sidetrack maps onto its own domain model. */
enum class PlexKind { Track, Album, Artist, Playlist, Other }

/**
 * One Plex metadata item reduced to the fields Sidetrack reads.
 *
 * Plex uses `ratingKey` as the stable id; `parentRatingKey` / `grandparentRatingKey` are the album
 * and artist. Track artwork lives on the album, which is why [thumb] prefers [parentThumb].
 */
data class PlexItem(
    val ratingKey: String,
    val title: String,
    val kind: PlexKind,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val durationMs: Long = 0L,
    val year: Int? = null,
    val userRating: Double? = null,
    val viewCount: Int? = null,
    val partKey: String? = null,
    val playlistItemId: String? = null,
    val leafCount: Int? = null,
    val smart: Boolean = false,
    val playlistType: String? = null,
    val composite: String? = null
) {
    /** Track rows use the album cover; albums and artists carry their own `thumb`. */
    val artThumb: String? get() = parentThumb ?: thumb
}

/** One page of a Plex list. `total` is the server's `totalSize` when it reports one. */
data class PlexPage(
    val items: List<PlexItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0
)

/** A Plex library section. Only `type == "artist"` sections hold music. */
data class PlexSection(val key: String, val title: String, val type: String)

/** Search hubs partitioned into the four scopes Sidetrack offers. */
data class PlexSearchResult(
    val tracks: List<PlexItem> = emptyList(),
    val artists: List<PlexItem> = emptyList(),
    val albums: List<PlexItem> = emptyList(),
    val playlists: List<PlexItem> = emptyList()
)

/** A playlist's permissions: smart playlists are generated and cannot take edits. */
val PlexItem.playlistActions: PlaylistActions
    get() = if (smart) PlaylistActions.None else PlaylistActions.All

private const val PLEX_SAVED_RATING = 10.0

internal fun PlexItem.toTrack(api: PlexApi): Track = Track(
    id = ratingKey,
    title = title,
    artist = grandparentTitle ?: parentTitle.orEmpty(),
    artistId = grandparentRatingKey.orEmpty(),
    albumId = parentRatingKey.orEmpty(),
    artRes = 0,
    durationMs = durationMs,
    uri = partKey,
    artUrl = api.artUrl(artThumb),
    service = ServiceId.Plex
)

internal fun PlexItem.toAlbum(api: PlexApi): Album = Album(
    id = ratingKey,
    title = title,
    artist = parentTitle ?: grandparentTitle.orEmpty(),
    artistId = parentRatingKey ?: grandparentRatingKey.orEmpty(),
    year = year ?: 0,
    artRes = 0,
    tracks = emptyList(),
    artUrl = api.artUrl(artThumb),
    trackCount = leafCount ?: 0,
    service = ServiceId.Plex
)

internal fun PlexItem.toArtist(api: PlexApi): Artist = Artist(
    id = ratingKey,
    name = title,
    artRes = 0,
    albumIds = emptyList(),
    artUrl = api.artUrl(thumb),
    albumCount = leafCount ?: 0,
    service = ServiceId.Plex
)

internal fun PlexItem.toPlaylist(api: PlexApi): Playlist = Playlist(
    id = ratingKey,
    name = title,
    owner = PLEX_PLAYLIST_OWNER,
    ownerId = null,
    artRes = 0,
    tracks = emptyList(),
    artUrl = api.artUrl(composite),
    trackCount = leafCount ?: 0,
    actions = playlistActions,
    service = ServiceId.Plex
)

/** True when Plex's rating convention marks the item as saved/liked (five stars). */
internal fun PlexItem.isSaved(): Boolean? = userRating?.let { it >= PLEX_SAVED_RATING }

/** The rating Plex stores for a Save/Like toggle. */
internal fun savedRating(saved: Boolean): Int = if (saved) PLEX_SAVED_RATING.toInt() else 0

internal fun PlexKind.toMediaType(): MediaType? = when (this) {
    PlexKind.Track -> MediaType.Track
    PlexKind.Album -> MediaType.Album
    PlexKind.Artist -> MediaType.Artist
    PlexKind.Playlist -> MediaType.Playlist
    PlexKind.Other -> null
}

internal const val PLEX_PLAYLIST_OWNER = "Plex"
