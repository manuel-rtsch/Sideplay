package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.PlexApi
import io.github.manuelrtsch.sidetrack.data.PlexItem
import io.github.manuelrtsch.sidetrack.data.PlexKind
import io.github.manuelrtsch.sidetrack.data.PlexPage
import io.github.manuelrtsch.sidetrack.data.PlexSearchResult
import io.github.manuelrtsch.sidetrack.data.PlexSection

/** A configurable in-memory Plex API for catalog and reporter tests. */
class FakePlexApi : PlexApi {
    override val baseUrl: String = "http://plex.test:32400"
    override val headers: Map<String, String> = mapOf("X-Plex-Token" to "token")

    var sections: List<PlexSection> = listOf(PlexSection("1", "Music", "artist"))
    var tracks = mutableListOf<PlexItem>()
    var albums = mutableListOf<PlexItem>()
    var artists = mutableListOf<PlexItem>()
    var playlists = mutableListOf<PlexItem>()
    var playlistItems = mutableListOf<PlexItem>()
    var searchResult = PlexSearchResult()
    var items = mutableMapOf<String, PlexItem>()
    var machine = "machine-1"

    val ratings = mutableListOf<Pair<String, Int>>()
    val added = mutableListOf<Pair<String, String>>()
    val removed = mutableListOf<Pair<String, String>>()
    val timelines = mutableListOf<Triple<String, String, Long>>()
    val scrobbles = mutableListOf<String>()

    override suspend fun musicSections(): List<PlexSection> = sections

    override suspend fun tracks(sectionKey: String, offset: Int, limit: Int, likedOnly: Boolean): PlexPage = page(tracks, offset, limit)

    override suspend fun albums(sectionKey: String, offset: Int, limit: Int): PlexPage = page(albums, offset, limit)

    override suspend fun artists(sectionKey: String, offset: Int, limit: Int): PlexPage = page(artists, offset, limit)

    override suspend fun playlists(offset: Int, limit: Int): PlexPage = page(playlists, offset, limit)

    override suspend fun playlistItems(playlistKey: String, offset: Int, limit: Int): PlexPage = page(playlistItems, offset, limit)

    override suspend fun item(ratingKey: String): PlexItem? = items[ratingKey]

    override suspend fun albumTracks(albumKey: String, offset: Int, limit: Int): PlexPage = page(playlistItems, offset, limit)

    override suspend fun artistAlbums(artistKey: String, offset: Int, limit: Int): PlexPage = page(albums, offset, limit)

    override suspend fun artistTracks(artistKey: String, offset: Int, limit: Int): PlexPage = page(tracks, offset, limit)

    override suspend fun search(query: String, limit: Int): PlexSearchResult = searchResult

    override suspend fun setRating(ratingKey: String, rating: Int) {
        ratings += ratingKey to rating
    }

    override suspend fun addToPlaylist(playlistKey: String, ratingKey: String) {
        added += playlistKey to ratingKey
    }

    override suspend fun removeFromPlaylist(playlistKey: String, playlistItemId: String) {
        removed += playlistKey to playlistItemId
    }

    override suspend fun machineIdentifier(): String = machine

    override suspend fun reportTimeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long) {
        timelines += Triple(ratingKey, state, timeMs)
    }

    override suspend fun reportScrobble(ratingKey: String) {
        scrobbles += ratingKey
    }

    override fun streamUrl(partKey: String): String = baseUrl + partKey

    override fun artUrl(thumb: String?): String? = thumb?.let { baseUrl + it }

    private fun page(items: List<PlexItem>, offset: Int, limit: Int): PlexPage {
        val slice = items.drop(offset).take(limit)
        return PlexPage(slice, items.size, offset)
    }
}

internal fun trackItem(id: String, title: String = "Track $id", partKey: String = "/library/parts/$id/file.mp3") = PlexItem(
    ratingKey = id,
    title = title,
    kind = PlexKind.Track,
    parentRatingKey = "album-$id",
    grandparentRatingKey = "artist-$id",
    parentTitle = "Album $id",
    grandparentTitle = "Artist $id",
    thumb = "/library/metadata/album-$id/thumb/1",
    parentThumb = "/library/metadata/album-$id/thumb/1",
    durationMs = 200_000L,
    partKey = partKey
)

internal fun playlistItem(id: String, title: String = "Playlist $id", smart: Boolean = false) = PlexItem(
    ratingKey = id,
    title = title,
    kind = PlexKind.Playlist,
    leafCount = 2,
    smart = smart,
    composite = "/playlists/$id/composite/1"
)
