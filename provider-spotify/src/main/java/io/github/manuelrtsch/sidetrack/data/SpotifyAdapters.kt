package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
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
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Station
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/** Maps the neutral library sections onto the Spotify catalog's filters. */
private fun LibrarySection.toFilter(): LibraryFilter = when (this) {
    LibrarySection.Liked -> LibraryFilter.LikedSongs
    LibrarySection.Playlists -> LibraryFilter.Playlists
    LibrarySection.Albums -> LibraryFilter.Albums
    LibrarySection.Artists -> LibraryFilter.Artists
    LibrarySection.Shows -> LibraryFilter.Shows
}

private fun MediaType.toCollectionType(): CollectionType? = when (this) {
    MediaType.Album -> CollectionType.Album
    MediaType.Playlist -> CollectionType.Playlist
    MediaType.Show -> CollectionType.Show
    else -> null
}

/** Spotify's existing catalog behind the neutral [MediaCatalog] contract. */
class SpotifyMediaCatalog(
    private val catalog: SpotifyCatalog
) : MediaCatalog {
    override val capabilities: ServiceCapabilities = ServiceCapabilities.All

    override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> = catalog.library(section.toFilter(), offset, limit)

    override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? {
        val type = key.type.toCollectionType() ?: return null
        return catalog.collection(type, key.id, offset, limit)
    }

    override suspend fun artist(key: MediaKey): Artist? = catalog.artist(key.id)

    override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource {
        val uri = item.uri ?: throw IllegalStateException("Spotify item ${item.key.encode()} has no URI")
        return PlaybackSource(id = item.key.encode(), uri = uri)
    }

    override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> {
        val resolved = catalog.artist(artist.id) ?: return Page()
        return catalog.albumsBy(resolved, offset, limit)
    }

    override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> {
        val resolved = catalog.artist(artist.id) ?: return Page()
        return catalog.topTracks(resolved, offset, limit)
    }

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SearchResults = catalog.search(query, scopes, offset, limit)

    override suspend fun radio(seed: MediaKey, offset: Int, limit: Int): Page<Track> {
        val tracks = if (seed.type == MediaType.Artist) {
            catalog.artistRadio(seed.id, limit)
        } else {
            catalog.songRadio(seed.id, limit)
        }
        return Page(items = tracks, hasMore = tracks.size >= limit)
    }

    override suspend fun radioStation(seed: MediaKey, limit: Int): Station = if (seed.type == MediaType.Artist) {
        catalog.artistRadioStation(seed.id, limit)
    } else {
        catalog.songRadioStation(seed.id, limit)
    }
}

/** Spotify's existing writer behind the neutral [MediaLibraryWriter] contract. */
class SpotifyMediaLibraryWriter(
    private val writer: SpotifyLibraryWriter
) : MediaLibraryWriter {
    override suspend fun isSaved(item: MediaKey): Boolean? = when (item.type) {
        MediaType.Track -> writer.isTrackSaved(item.id)
        else -> item.type.toCollectionType()?.let { writer.isSaved(it, item.id) }
    }

    override suspend fun isFollowed(artist: MediaKey): Boolean? = writer.isArtistFollowed(artist.id)

    override suspend fun setSaved(item: MediaKey, saved: Boolean) {
        if (item.type == MediaType.Track) {
            writer.setTrackSaved(item.id, saved)
            return
        }
        val type = item.type.toCollectionType() ?: return
        writer.setSaved(type, item.id, saved)
    }

    override suspend fun setFollowed(artist: MediaKey, followed: Boolean) {
        writer.setArtistFollowed(artist.id, followed)
    }

    override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? {
        val uri = item.uri ?: return null
        return writer.addToPlaylist(playlist.id, uri)
    }

    // Spotify's own writer removes by uri and takes every occurrence of it, so the row index the
    // caller knows has nothing to select here.
    override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) {
        val uri = item.uri ?: return
        writer.removeFromPlaylist(playlist.id, uri)
    }
}

/** Spotify's OAuth flow behind [ServiceAuth]. */
class SpotifyServiceAuth(
    private val session: SpotifySession
) : ServiceAuth {
    override suspend fun signIn(input: AuthInput): ServiceAccount {
        val browser = input as? AuthInput.Browser
            ?: throw IllegalArgumentException("Spotify needs a browser sign-in")
        session.signIn(browser)
        return account()
    }

    override suspend fun restore(): ServiceAccount? = if (session.signedIn.value) account() else null

    override suspend fun signOut() {
        session.signOut()
    }

    private fun account() = ServiceAccount(
        service = ServiceId.Spotify,
        userId = "spotify",
        displayName = "Spotify"
    )
}

/** Spotify's identity and label, available without building a session or a runtime. */
object SpotifyService {
    val id: ServiceId get() = ServiceId.Spotify
    const val DISPLAY_NAME: String = "Spotify"
}

/**
 * The Spotify provider. It wraps the existing session, catalog, writer and librespot engine so
 * the app can treat Spotify as one service among several without changing playback.
 */
class SpotifyProvider(
    private val session: SpotifySession
) : MusicServiceProvider {
    override val id: ServiceId = SpotifyService.id
    override val displayName: String = SpotifyService.DISPLAY_NAME
    override val capabilities: ServiceCapabilities = ServiceCapabilities.All
    override val signInKind: SignInKind = SignInKind.Browser

    override fun createAuth(): ServiceAuth = SpotifyServiceAuth(session)
}
