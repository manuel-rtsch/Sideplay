package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.CATALOG_PAGE_SIZE
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.Station
import io.github.manuelrtsch.sidetrack.Track

/**
 * Read side of the Spotify account, as the UI needs it. The production implementation is the Web
 * API; tests supply their own catalog. No screen or ViewModel may call an endpoint directly.
 */
interface SpotifyCatalog {
    /**
     * One page of a library category. Implementations that know the category size must report it as
     * [Page.total]; paging and the overview's "See more" row rely on it because a catalog
     * can drop entries whose metadata failed to load.
     */
    suspend fun library(
        filter: LibraryFilter,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): Page<LibraryEntry>

    suspend fun collection(
        type: CollectionType,
        id: String,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): Collection?

    suspend fun artist(id: String): Artist?
    suspend fun albumsBy(
        artist: Artist,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): Page<Album>

    suspend fun topTracks(
        artist: Artist,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): Page<Track>

    /**
     * Tracks for a station seeded by [trackId]. The native catalog uses librespot's first-party
     * radio endpoint; transports that cannot build a station return an empty list.
     */
    suspend fun songRadio(trackId: String, limit: Int = CATALOG_PAGE_SIZE): List<Track> = emptyList()

    /**
     * Tracks for a station seeded by [artistId]. Same contract as [songRadio]: transports that
     * cannot build a station return an empty list rather than inventing tracks.
     */
    suspend fun artistRadio(artistId: String, limit: Int = CATALOG_PAGE_SIZE): List<Track> = emptyList()

    /**
     * A station seeded by [trackId], including the playlist that backs it when the transport can
     * report one. The default wraps [songRadio], so transports that only return tracks still work.
     */
    suspend fun songRadioStation(trackId: String, limit: Int = CATALOG_PAGE_SIZE): Station = Station(songRadio(trackId, limit))

    /** A station seeded by [artistId]; same contract as [songRadioStation]. */
    suspend fun artistRadioStation(artistId: String, limit: Int = CATALOG_PAGE_SIZE): Station = Station(artistRadio(artistId, limit))

    suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): SearchResults
}

/**
 * Write side of the user's Spotify library. Kept separate from [SpotifyCatalog] so read-only
 * fallbacks and test catalogs do not have to pretend they can mutate an account.
 */
interface SpotifyLibraryWriter {
    suspend fun isTrackSaved(id: String): Boolean?
    suspend fun isSaved(type: CollectionType, id: String): Boolean?
    suspend fun isArtistFollowed(id: String): Boolean?
    suspend fun setTrackSaved(id: String, saved: Boolean)
    suspend fun setSaved(type: CollectionType, id: String, saved: Boolean)
    suspend fun setArtistFollowed(id: String, followed: Boolean)

    /**
     * Appends [uri] to [playlistId] and returns the index it landed at, which is what Undo needs.
     * Returns null when the transport cannot edit playlists; callers should hide the action rather
     * than report a failure.
     */
    suspend fun addToPlaylist(playlistId: String, uri: String): Int? = null

    /** Removes the most recent occurrence of [uri] from [playlistId]. */
    suspend fun removeFromPlaylist(playlistId: String, uri: String) = Unit
}
