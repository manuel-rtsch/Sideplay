package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaType as SidetrackMediaType
import io.github.manuelrtsch.sidetrack.SearchScope

/**
 * The Jellyfin operations Sidetrack needs, in Sidetrack's vocabulary.
 *
 * A fake implements this for catalog tests; [SdkJellyfinApi] is the only class that knows the
 * official Kotlin SDK. Failures are expected to surface as [ServiceException].
 */
interface JellyfinApi {
    suspend fun likedSongs(offset: Int, limit: Int): JellyfinPage
    suspend fun playlists(offset: Int, limit: Int): JellyfinPage
    suspend fun albums(offset: Int, limit: Int): JellyfinPage
    suspend fun favoriteArtists(offset: Int, limit: Int): JellyfinPage

    suspend fun item(id: String): JellyfinItem?
    suspend fun albumTracks(albumId: String, offset: Int, limit: Int): JellyfinPage
    suspend fun playlist(id: String): JellyfinPlaylist?
    suspend fun playlistItems(id: String, offset: Int, limit: Int): JellyfinPage
    suspend fun artistAlbums(artistId: String, offset: Int, limit: Int): JellyfinPage
    suspend fun artistTopTracks(artistId: String, offset: Int, limit: Int): JellyfinPage

    suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): JellyfinSearchResult
    suspend fun instantMix(seed: String, type: SidetrackMediaType, limit: Int): JellyfinPage

    suspend fun userData(id: String): JellyfinUserData?
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun markPlayed(id: String)

    suspend fun createPlaylist(name: String, itemIds: List<String> = emptyList()): String?
    suspend fun addToPlaylist(playlistId: String, itemId: String): Int
    suspend fun removeFromPlaylist(playlistId: String, itemId: String, index: Int? = null)

    suspend fun playback(itemId: String, maxStreamingBitrate: Int?): JellyfinPlaybackInfo
    fun imageUrl(id: String, tag: String?, maxHeight: Int = ARTWORK_MAX_HEIGHT): String

    suspend fun announceCapabilities()
    suspend fun reportStart(report: JellyfinPlaybackReport)
    suspend fun reportProgress(report: JellyfinPlaybackReport)
    suspend fun reportStopped(report: JellyfinPlaybackReport, failed: Boolean = false)

    /** The account's resume list, used once at sign-in to seed the local resume point (D13). */
    suspend fun resumeItems(limit: Int): JellyfinPage
}
