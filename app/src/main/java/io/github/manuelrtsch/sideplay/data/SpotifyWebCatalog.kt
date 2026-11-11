package io.github.manuelrtsch.sideplay.data

import io.github.manuelrtsch.sideplay.LibraryFilter
import io.github.manuelrtsch.sideplay.SearchScope
import io.github.manuelrtsch.sideplay.SpotifyAlbum
import io.github.manuelrtsch.sideplay.SpotifyArtist
import io.github.manuelrtsch.sideplay.SpotifyCollection
import io.github.manuelrtsch.sideplay.SpotifyCollectionType
import io.github.manuelrtsch.sideplay.SpotifyPage
import io.github.manuelrtsch.sideplay.SpotifySearchResults
import io.github.manuelrtsch.sideplay.SpotifyTrack

/** [SpotifyCatalog] over the Web API. */
class SpotifyWebCatalog(private val api: SpotifyWebApi) : SpotifyCatalog {
    override suspend fun library(
        filter: LibraryFilter,
        offset: Int,
        limit: Int
    ): SpotifyPage<Any> = api.library(filter, offset, limit)

    override suspend fun collection(
        type: SpotifyCollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): SpotifyCollection? = api.collection(type, id, offset, limit)

    override suspend fun artist(id: String): SpotifyArtist? = api.artist(id)

    override suspend fun albumsBy(
        artist: SpotifyArtist,
        offset: Int,
        limit: Int
    ): SpotifyPage<SpotifyAlbum> = api.albumsBy(artist, offset, limit)

    override suspend fun topTracks(
        artist: SpotifyArtist,
        offset: Int,
        limit: Int
    ): SpotifyPage<SpotifyTrack> = api.topTracks(artist, offset, limit)

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SpotifySearchResults = api.search(query, scopes, offset, limit)
}
