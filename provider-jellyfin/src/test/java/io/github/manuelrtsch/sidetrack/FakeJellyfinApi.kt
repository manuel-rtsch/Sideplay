package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.data.JellyfinApi
import io.github.manuelrtsch.sidetrack.data.JellyfinItem
import io.github.manuelrtsch.sidetrack.data.JellyfinPage
import io.github.manuelrtsch.sidetrack.data.JellyfinPlaybackInfo
import io.github.manuelrtsch.sidetrack.data.JellyfinPlaybackReport
import io.github.manuelrtsch.sidetrack.data.JellyfinPlaylist
import io.github.manuelrtsch.sidetrack.data.JellyfinSearchResult
import io.github.manuelrtsch.sidetrack.data.JellyfinUserData

/** A scriptable seam: no SDK, no server, and every write is recorded for assertions. */
internal class FakeJellyfinApi : JellyfinApi {
    var liked = JellyfinPage()
    var playlists = JellyfinPage()
    var albums = JellyfinPage()
    var artists = JellyfinPage()
    var albumTracks = JellyfinPage()
    var playlistItems = JellyfinPage()
    var artistAlbums = JellyfinPage()
    var artistTopTracks = JellyfinPage()
    var searchResult = JellyfinSearchResult()
    var mix = JellyfinPage()
    var resume = JellyfinPage()

    val items = mutableMapOf<String, JellyfinItem>()
    val playlistDetails = mutableMapOf<String, JellyfinPlaylist>()
    val userData = mutableMapOf<String, JellyfinUserData>()
    var playback: JellyfinPlaybackInfo = JellyfinPlaybackInfo(
        url = "https://jellyfin.example/Audio/stream",
        headers = mapOf("X-Emby-Token" to "token"),
        playSessionId = "session-1",
        mediaSourceId = "source-1",
        directPlay = true
    )

    val favorites = mutableListOf<Pair<String, Boolean>>()
    val added = mutableListOf<Pair<String, String>>()
    val removed = mutableListOf<Pair<String, String>>()
    var lastRemoveIndex: Int? = null
    val created = mutableListOf<String>()
    var createdIds: List<String> = emptyList()
    val starts = mutableListOf<JellyfinPlaybackReport>()
    val progress = mutableListOf<JellyfinPlaybackReport>()
    val stopped = mutableListOf<Pair<JellyfinPlaybackReport, Boolean>>()
    val played = mutableListOf<String>()
    var announced = false
    var lastBitrate: Int? = null
    var lastMixSeed: Pair<String, MediaType>? = null

    override suspend fun likedSongs(offset: Int, limit: Int): JellyfinPage = liked
    override suspend fun playlists(offset: Int, limit: Int): JellyfinPage = playlists
    override suspend fun albums(offset: Int, limit: Int): JellyfinPage = albums
    override suspend fun favoriteArtists(offset: Int, limit: Int): JellyfinPage = artists
    override suspend fun resumeItems(limit: Int): JellyfinPage = resume
    override suspend fun item(id: String): JellyfinItem? = items[id]
    override suspend fun albumTracks(albumId: String, offset: Int, limit: Int): JellyfinPage = albumTracks
    override suspend fun playlist(id: String): JellyfinPlaylist? = playlistDetails[id]
    override suspend fun playlistItems(playlistId: String, offset: Int, limit: Int): JellyfinPage = playlistItems
    override suspend fun artistAlbums(artistId: String, offset: Int, limit: Int): JellyfinPage = artistAlbums
    override suspend fun artistTopTracks(artistId: String, offset: Int, limit: Int): JellyfinPage = artistTopTracks
    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): JellyfinSearchResult = searchResult

    override suspend fun instantMix(seedId: String, seedType: MediaType, limit: Int): JellyfinPage {
        lastMixSeed = seedId to seedType
        return mix
    }
    override suspend fun userData(id: String): JellyfinUserData? = userData[id]
    override suspend fun setFavorite(id: String, favorite: Boolean) {
        favorites += id to favorite
    }

    override suspend fun markPlayed(id: String) {
        played += id
    }

    override suspend fun createPlaylist(name: String, itemIds: List<String>): String? {
        created += name
        createdIds = itemIds
        return "new-playlist"
    }

    override suspend fun addToPlaylist(playlistId: String, itemId: String): Int {
        added += playlistId to itemId
        return 7
    }

    override suspend fun removeFromPlaylist(playlistId: String, itemId: String, index: Int?) {
        removed += playlistId to itemId
        lastRemoveIndex = index
    }

    override suspend fun playback(itemId: String, maxStreamingBitrate: Int?): JellyfinPlaybackInfo {
        lastBitrate = maxStreamingBitrate
        return playback
    }

    override fun imageUrl(id: String, tag: String?, maxHeight: Int): String = "https://jellyfin.example/Items/$id/Images/Primary" + (tag?.let { "?tag=$it" } ?: "")

    override suspend fun announceCapabilities() {
        announced = true
    }

    override suspend fun reportStart(report: JellyfinPlaybackReport) {
        starts += report
    }

    override suspend fun reportProgress(report: JellyfinPlaybackReport) {
        progress += report
    }

    override suspend fun reportStopped(report: JellyfinPlaybackReport, failed: Boolean) {
        stopped += report to failed
    }
}
