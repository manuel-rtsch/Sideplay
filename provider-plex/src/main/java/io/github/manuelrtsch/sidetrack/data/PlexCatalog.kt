package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.CATALOG_PAGE_SIZE
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
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
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/**
 * Plex's read and write side behind Sidetrack's neutral catalog contracts.
 *
 * Plex has one music section in a normal install; the adapter reads the first `artist` section and
 * leaves the others alone. A Plex server with several music libraries therefore shows the first
 * one, which is the same constraint the picker already places on a single account.
 */
class PlexCatalog(
    private val api: () -> PlexApi?
) : MediaCatalog,
    MediaLibraryWriter {
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Plex

    private fun client(): PlexApi = api()
        ?: throw ServiceException(ServiceFailure.NotConnected, "Connect Plex in Settings")

    private suspend fun musicSection(): PlexSection? = client().musicSections().firstOrNull()

    override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> {
        val client = client()
        val music = musicSection() ?: return Page()
        return when (section) {
            LibrarySection.Liked -> client.tracks(music.key, offset, limit, likedOnly = true)
                .mapEntries { it.toTrack(client) }

            LibrarySection.Playlists -> client.playlists(offset, limit)
                .mapEntries { it.toPlaylist(client) }

            LibrarySection.Albums -> client.albums(music.key, offset, limit)
                .mapEntries { it.toAlbum(client) }

            LibrarySection.Artists -> client.artists(music.key, offset, limit)
                .mapEntries { it.toArtist(client) }

            LibrarySection.Shows -> Page()
        }
    }

    override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? {
        val client = client()
        return when (key.type) {
            MediaType.Album -> {
                val album = client.item(key.id)?.toAlbum(client) ?: return null
                val page = client.albumTracks(key.id, offset, limit)
                val total = page.total.takeIf { it > 0 } ?: album.trackCount
                Collection(
                    type = CollectionType.Album,
                    id = album.id,
                    title = album.title,
                    subtitle = album.artist,
                    artRes = 0,
                    items = page.items.map { it.toTrack(client) },
                    artistId = album.artistId.takeIf { it.isNotEmpty() },
                    artUrl = album.artUrl,
                    totalItems = total,
                    hasMore = offset + page.items.size < total,
                    saveable = true,
                    service = ServiceId.Plex
                )
            }

            MediaType.Playlist -> {
                val playlist = client.item(key.id) ?: return null
                val page = client.playlistItems(key.id, offset, limit)
                val total = playlist.leafCount ?: page.total
                Collection(
                    type = CollectionType.Playlist,
                    id = playlist.ratingKey,
                    title = playlist.title,
                    subtitle = PLEX_PLAYLIST_OWNER,
                    artRes = 0,
                    items = page.items.map { it.toTrack(client) },
                    artUrl = client.artUrl(playlist.composite),
                    totalItems = total,
                    hasMore = offset + page.items.size < total,
                    saveable = false,
                    playlistActions = playlist.playlistActions,
                    service = ServiceId.Plex
                )
            }

            else -> null
        }
    }

    override suspend fun artist(key: MediaKey): Artist? = client().item(key.id)?.toArtist(client())

    override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> = client().artistAlbums(artist.id, offset, limit).mapEntries { it.toAlbum(client()) }

    override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> = client().artistTracks(artist.id, offset, limit).mapEntries { it.toTrack(client()) }

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults {
        val client = client()
        // Plex hubs have no pagination parameters; ask for enough rows to cover the requested
        // window and slice locally so Load more appends new rows instead of repeating the first.
        val result = client.search(query, (offset + limit).coerceAtLeast(LIBRARY_SEARCH_LIMIT))
        fun <T> List<T>.window(): List<T> = drop(offset).take(limit)
        return SearchResults(
            tracks = if (SearchScope.Tracks in scopes) result.tracks.window().map { it.toTrack(client) } else emptyList(),
            artists = if (SearchScope.Artists in scopes) result.artists.window().map { it.toArtist(client) } else emptyList(),
            albums = if (SearchScope.Albums in scopes) result.albums.window().map { it.toAlbum(client) } else emptyList(),
            playlists = if (SearchScope.Playlists in scopes) result.playlists.window().map { it.toPlaylist(client) } else emptyList()
        )
    }

    override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource {
        if (item.service != ServiceId.Plex) {
            throw ServiceException(ServiceFailure.TrackUnavailable, "This item belongs to another service")
        }
        val client = client()
        val partKey = item.uri ?: client.item(item.id)?.partKey
            ?: throw ServiceException(ServiceFailure.TrackUnavailable, "Plex did not return a media part for this track")
        return PlaybackSource(
            id = item.key.encode(),
            uri = client.streamUrl(partKey),
            headers = client.headers
        )
    }

    override suspend fun radio(seed: MediaKey, offset: Int, limit: Int): Page<Track> = Page()

    override suspend fun isSaved(item: MediaKey): Boolean? = when (item.type) {
        MediaType.Playlist, MediaType.Show -> null
        else -> runCatching { client().item(item.id)?.isSaved() }.getOrNull()
    }

    override suspend fun isFollowed(artist: MediaKey): Boolean? = null

    override suspend fun setSaved(item: MediaKey, saved: Boolean) {
        if (item.type == MediaType.Playlist || item.type == MediaType.Show) return
        client().setRating(item.id, savedRating(saved))
    }

    override suspend fun setFollowed(artist: MediaKey, followed: Boolean) = Unit

    override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? {
        client().addToPlaylist(playlist.id, item.id)
        // Plex's add response does not name the inserted position, so the row bar offers no Undo.
        return null
    }

    override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) {
        val client = client()
        val entry = client.findPlaylistEntry(playlist.id, item.id, index) ?: return
        val playlistItemId = entry.playlistItemId ?: return
        client.removeFromPlaylist(playlist.id, playlistItemId)
    }

    override suspend fun createPlaylist(name: String, seed: List<MediaKey>): Playlist? = null

    private fun <R> PlexPage.mapEntries(transform: (PlexItem) -> R): Page<R> = Page(
        items = items.map(transform),
        total = total,
        hasMore = offset + items.size < total
    )

    private companion object {
        /** Plex's hub search has no pagination; one generous page covers the visible scopes. */
        const val LIBRARY_SEARCH_LIMIT = 50
    }
}

/**
 * Finds the playlist entry Sidetrack was asked to remove.
 *
 * Plex removes by `playlistItemID`, not by the track's `ratingKey`, and every backend removes by
 * position when the same track appears twice. A named [index] picks that row; without one the last
 * match goes, which matches the append-then-undo convention the other providers use.
 */
internal suspend fun PlexApi.findPlaylistEntry(
    playlistKey: String,
    itemId: String,
    index: Int?
): PlexItem? {
    var start = 0
    var lastMatch: PlexItem? = null
    while (true) {
        val page = playlistItems(playlistKey, start, CATALOG_PAGE_SIZE)
        for ((offset, entry) in page.items.withIndex()) {
            if (entry.ratingKey != itemId) continue
            if (index == start + offset) return entry
            lastMatch = entry
        }
        if (page.items.size < CATALOG_PAGE_SIZE) return lastMatch
        start += page.items.size
    }
}
