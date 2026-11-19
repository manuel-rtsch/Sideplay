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
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.Station
import io.github.manuelrtsch.sidetrack.Track

/**
 * Catalog that keeps browsing working when librespot cannot reach an access point.
 *
 * The native catalog rides on the same access-point connection that plays audio. Some networks
 * refuse it while ordinary HTTPS keeps working: the OAuth token exchange and the Web API are on
 * `*.spotify.com:443`, but librespot dials `ap-*.spotify.com:4070`, and a blocked port or a
 * sinkholed host leaves the account signed in yet every page empty. Silence there looked like an
 * expired session, so the first transport failure that is not the account's fault switches reads to
 * the [web] catalog and keeps them there for the process.
 *
 * Degradation is deliberately one-way: a network that dropped the access point once may do it
 * again, and retrying it for every page is what produced a connect per library row. Playback stays
 * on the native transport, so it keeps reporting the real reason instead of pretending.
 *
 * Writes are not degraded: the Web API transport in this app is read-only, so callers hide the
 * save actions when [degraded] is set rather than failing them one by one.
 */
class ResilientSpotifyCatalog(
    private val native: SpotifyCatalog,
    private val web: SpotifyCatalog,
    private val onDegrade: (ServiceException) -> Unit = {}
) : SpotifyCatalog,
    SpotifyLibraryWriter {
    /** True once reads have moved to the Web API for this process. */
    @Volatile
    var degraded: Boolean = false
        private set

    private val nativeWriter: SpotifyLibraryWriter? get() = native as? SpotifyLibraryWriter

    override suspend fun library(
        filter: LibraryFilter,
        offset: Int,
        limit: Int
    ): Page<LibraryEntry> = read(
        { native.library(filter, offset, limit) },
        { web.library(filter, offset, limit) }
    )

    override suspend fun collection(
        type: CollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): Collection? = read(
        { native.collection(type, id, offset, limit) },
        { web.collection(type, id, offset, limit) }
    )

    override suspend fun artist(id: String): Artist? = read({ native.artist(id) }, { web.artist(id) })

    override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> = read(
        { native.albumsBy(artist, offset, limit) },
        { web.albumsBy(artist, offset, limit) }
    )

    override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> = read(
        { native.topTracks(artist, offset, limit) },
        { web.topTracks(artist, offset, limit) }
    )

    override suspend fun songRadio(trackId: String, limit: Int): List<Track> = read(
        { native.songRadio(trackId, limit) },
        { web.songRadio(trackId, limit) }
    )

    override suspend fun artistRadio(artistId: String, limit: Int): List<Track> = read(
        { native.artistRadio(artistId, limit) },
        { web.artistRadio(artistId, limit) }
    )

    // The station overrides carry the backing playlist on the native side, so they are delegated
    // rather than left to the interface default, which would drop it.
    override suspend fun songRadioStation(trackId: String, limit: Int): Station = read(
        { native.songRadioStation(trackId, limit) },
        { web.songRadioStation(trackId, limit) }
    )

    override suspend fun artistRadioStation(artistId: String, limit: Int): Station = read(
        { native.artistRadioStation(artistId, limit) },
        { web.artistRadioStation(artistId, limit) }
    )

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SearchResults = read(
        { native.search(query, scopes, offset, limit) },
        { web.search(query, scopes, offset, limit) }
    )

    override suspend fun isTrackSaved(id: String): Boolean? = nativeWriter?.isTrackSaved(id)

    override suspend fun isSaved(type: CollectionType, id: String): Boolean? = nativeWriter?.isSaved(type, id)

    override suspend fun isArtistFollowed(id: String): Boolean? = nativeWriter?.isArtistFollowed(id)

    override suspend fun setTrackSaved(id: String, saved: Boolean) {
        writer().setTrackSaved(id, saved)
    }

    override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
        writer().setSaved(type, id, saved)
    }

    override suspend fun setArtistFollowed(id: String, followed: Boolean) {
        writer().setArtistFollowed(id, followed)
    }

    override suspend fun addToPlaylist(playlistId: String, uri: String): Int? = nativeWriter?.addToPlaylist(playlistId, uri)

    override suspend fun removeFromPlaylist(playlistId: String, uri: String) {
        nativeWriter?.removeFromPlaylist(playlistId, uri)
    }

    /**
     * Runs the native read, and switches to [web] once when the transport says the access point is
     * unreachable. Anything else — a bad response, a deleted item — is the caller's business and is
     * rethrown unchanged.
     */
    private suspend fun <T> read(nativeCall: suspend () -> T, webCall: suspend () -> T): T {
        if (degraded) return webCall()
        return try {
            nativeCall()
        } catch (error: ServiceException) {
            if (!unreachable(error)) throw error
            degraded = true
            SidetrackDebug.log("catalog degraded to the Web API", error)
            onDegrade(error)
            webCall()
        }
    }

    private fun writer(): SpotifyLibraryWriter {
        val writer = nativeWriter
        if (writer == null || degraded) {
            throw ServiceException(
                ServiceFailure.Unavailable,
                "Changing the library needs Spotify's access point"
            )
        }
        return writer
    }

    private fun unreachable(error: ServiceException): Boolean = error.failure == ServiceFailure.Network || error.failure == ServiceFailure.Unavailable
}
