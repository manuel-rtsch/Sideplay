package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.Track

/** [SpotifyCatalog] over the Web API. */
class SpotifyWebCatalog(private val api: SpotifyWebApi) : SpotifyCatalog {
    override suspend fun library(
        filter: LibraryFilter,
        offset: Int,
        limit: Int
    ): Page<LibraryEntry> = api.library(filter, offset, limit)

    override suspend fun collection(
        type: CollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): Collection? = api.collection(type, id, offset, limit)

    override suspend fun artist(id: String): Artist? = api.artist(id)

    override suspend fun albumsBy(
        artist: Artist,
        offset: Int,
        limit: Int
    ): Page<Album> = api.albumsBy(artist, offset, limit)

    override suspend fun topTracks(
        artist: Artist,
        offset: Int,
        limit: Int
    ): Page<Track> = api.topTracks(artist, offset, limit)

    override suspend fun songRadio(trackId: String, limit: Int): List<Track> = api.songRadio(trackId, limit)

    override suspend fun artistRadio(artistId: String, limit: Int): List<Track> = api.artistRadio(artistId, limit)

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SearchResults = api.search(query, scopes, offset, limit)
}
