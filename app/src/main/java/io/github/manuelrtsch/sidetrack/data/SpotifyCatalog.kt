package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.SPOTIFY_PAGE_SIZE
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.SpotifyAlbum
import io.github.manuelrtsch.sidetrack.SpotifyArtist
import io.github.manuelrtsch.sidetrack.SpotifyCollection
import io.github.manuelrtsch.sidetrack.SpotifyCollectionType
import io.github.manuelrtsch.sidetrack.SpotifyPage
import io.github.manuelrtsch.sidetrack.SpotifySearchResults
import io.github.manuelrtsch.sidetrack.SpotifyTrack

/**
 * Read side of the Spotify account, as the UI needs it. The production implementation is the Web
 * API; tests supply their own catalog. No screen or ViewModel may call an endpoint directly.
 */
interface SpotifyCatalog {
    suspend fun library(
        filter: LibraryFilter,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifyPage<Any>

    suspend fun collection(
        type: SpotifyCollectionType,
        id: String,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifyCollection?

    suspend fun artist(id: String): SpotifyArtist?
    suspend fun albumsBy(
        artist: SpotifyArtist,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifyPage<SpotifyAlbum>

    suspend fun topTracks(
        artist: SpotifyArtist,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifyPage<SpotifyTrack>

    /**
     * Tracks for a station seeded by [trackId]. The native catalog uses librespot's first-party
     * radio endpoint; transports that cannot build a station return an empty list.
     */
    suspend fun songRadio(trackId: String, limit: Int = SPOTIFY_PAGE_SIZE): List<SpotifyTrack> = emptyList()

    suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifySearchResults
}

/**
 * Write side of the user's Spotify library. Kept separate from [SpotifyCatalog] so read-only
 * fallbacks and test catalogs do not have to pretend they can mutate an account.
 */
interface SpotifyLibraryWriter {
    suspend fun isTrackSaved(id: String): Boolean?
    suspend fun isSaved(type: SpotifyCollectionType, id: String): Boolean?
    suspend fun isArtistFollowed(id: String): Boolean?
    suspend fun setTrackSaved(id: String, saved: Boolean)
    suspend fun setSaved(type: SpotifyCollectionType, id: String, saved: Boolean)
    suspend fun setArtistFollowed(id: String, followed: Boolean)
}
