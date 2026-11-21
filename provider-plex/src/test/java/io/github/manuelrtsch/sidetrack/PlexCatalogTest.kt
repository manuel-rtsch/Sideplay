package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.PlexCatalog
import io.github.manuelrtsch.sidetrack.data.PlexItem
import io.github.manuelrtsch.sidetrack.data.PlexKind
import io.github.manuelrtsch.sidetrack.data.PlexSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexCatalogTest {
    private val api = FakePlexApi()
    private val catalog = PlexCatalog { api }

    private val track3 = Track(
        id = "3",
        title = "Track 3",
        artist = "Artist",
        artistId = "1",
        albumId = "2",
        artRes = 0,
        durationMs = 200_000L,
        uri = "/library/parts/3/file.mp3",
        service = ServiceId.Plex
    )

    @Test fun `library sections map Plex items onto the neutral rows`() = runTest {
        api.tracks += trackItem("3", "Liked")
        api.playlists += playlistItem("47")
        api.albums += PlexItem(
            ratingKey = "2",
            title = "Album",
            kind = PlexKind.Album,
            parentRatingKey = "1",
            parentTitle = "Artist",
            year = 2024,
            thumb = "/library/metadata/2/thumb/1",
            leafCount = 4
        )
        api.artists += PlexItem(
            ratingKey = "1",
            title = "Artist",
            kind = PlexKind.Artist,
            thumb = "/library/metadata/1/thumb/1",
            leafCount = 2
        )

        assertEquals(listOf("Liked"), catalog.library(LibrarySection.Liked, 0, 20).items.map { (it as Track).title })
        assertEquals(listOf("Playlist 47"), catalog.library(LibrarySection.Playlists, 0, 20).items.map { (it as Playlist).name })
        assertEquals(listOf("Album"), catalog.library(LibrarySection.Albums, 0, 20).items.map { (it as Album).title })
        assertEquals(listOf("Artist"), catalog.library(LibrarySection.Artists, 0, 20).items.map { (it as Artist).name })

        val liked = catalog.library(LibrarySection.Liked, 0, 20).items.single() as Track
        assertEquals("Artist 3", liked.artist)
        assertEquals("http://plex.test:32400/library/metadata/album-3/thumb/1", liked.artUrl)
    }

    @Test fun `a server without a music section has empty library pages`() = runTest {
        api.sections = listOf(PlexSection("2", "Movies", "movie"))
        val page = catalog.library(LibrarySection.Albums, 0, 20)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total ?: 0)
    }

    @Test fun `an album collection pages its tracks`() = runTest {
        api.items["2"] = PlexItem(
            ratingKey = "2",
            title = "Album",
            kind = PlexKind.Album,
            parentRatingKey = "1",
            parentTitle = "Artist",
            year = 2024,
            thumb = "/library/metadata/2/thumb/1",
            leafCount = 2
        )
        api.playlistItems += trackItem("3")
        api.playlistItems += trackItem("4")

        val collection = catalog.collection(MediaKey(ServiceId.Plex, MediaType.Album, "2"), 0, 1)!!
        assertEquals(CollectionType.Album, collection.type)
        assertEquals("Artist", collection.subtitle)
        assertEquals(1, collection.items.size)
        assertEquals(2, collection.totalItems)
        assertTrue(collection.hasMore)
    }

    @Test fun `a smart playlist is read-only`() = runTest {
        api.items["47"] = playlistItem("47", smart = true)
        api.playlistItems += trackItem("3")
        val collection = catalog.collection(MediaKey(ServiceId.Plex, MediaType.Playlist, "47"), 0, 20)!!
        assertEquals(CollectionType.Playlist, collection.type)
        assertEquals(PlaylistActions.None, collection.playlistActions)
        assertEquals("Plex", collection.subtitle)
    }

    @Test fun `playback resolves the part URL with the Plex headers`() = runTest {
        val track = Track(
            id = "3",
            title = "Track 3",
            artist = "Artist",
            artistId = "1",
            albumId = "2",
            artRes = 0,
            durationMs = 200_000L,
            uri = "/library/parts/3/file.mp3",
            service = ServiceId.Plex
        )
        val source = catalog.resolvePlaybackSource(track)
        assertEquals("http://plex.test:32400/library/parts/3/file.mp3", source.uri)
        assertEquals("token", source.headers["X-Plex-Token"])
    }

    @Test fun `saving a track writes Plex's five-star rating`() = runTest {
        catalog.setSaved(MediaKey(ServiceId.Plex, MediaType.Track, "3"), true)
        catalog.setSaved(MediaKey(ServiceId.Plex, MediaType.Track, "3"), false)
        assertEquals(listOf("3" to 10, "3" to 0), api.ratings)
    }

    @Test fun `removing a duplicate playlist entry respects the named row`() = runTest {
        api.playlistItems += trackItem("3").copy(playlistItemId = "10")
        api.playlistItems += trackItem("4").copy(playlistItemId = "11")
        api.playlistItems += trackItem("3").copy(playlistItemId = "12")

        catalog.removeFromPlaylist(MediaKey(ServiceId.Plex, MediaType.Playlist, "47"), track3, index = 2)
        assertEquals(listOf("47" to "12"), api.removed)

        api.removed.clear()
        catalog.removeFromPlaylist(MediaKey(ServiceId.Plex, MediaType.Playlist, "47"), track3, index = null)
        assertEquals(listOf("47" to "12"), api.removed)
    }

    @Test fun `unsupported Plex features stay empty`() = runTest {
        assertNull(catalog.isFollowed(MediaKey(ServiceId.Plex, MediaType.Artist, "1")))
        assertNull(catalog.createPlaylist("New"))
        assertTrue(catalog.radio(MediaKey(ServiceId.Plex, MediaType.Track, "3"), 0, 20).items.isEmpty())
        assertFalse(ServiceCapabilities.Plex.supportsRadio)
        assertFalse(ServiceCapabilities.Plex.supportsPlaylistCreate)
        assertFalse(ServiceCapabilities.Plex.supportsFollowArtist)
    }

    @Test fun `search windows rows so Load more appends`() = runTest {
        api.searchResult = io.github.manuelrtsch.sidetrack.data.PlexSearchResult(
            tracks = listOf(trackItem("3"), trackItem("4"), trackItem("5"))
        )
        val results = catalog.search("x", setOf(SearchScope.Tracks), offset = 1, limit = 1)
        assertEquals(listOf("Track 4"), results.tracks.map { it.title })
    }

    @Test fun `search only returns the scopes that were asked for`() = runTest {
        api.searchResult = io.github.manuelrtsch.sidetrack.data.PlexSearchResult(
            tracks = listOf(trackItem("3")),
            artists = listOf(PlexItem("1", "Artist", PlexKind.Artist)),
            albums = listOf(PlexItem("2", "Album", PlexKind.Album)),
            playlists = listOf(playlistItem("47"))
        )
        val results = catalog.search("x", setOf(SearchScope.Tracks, SearchScope.Playlists), 0, 20)
        assertEquals(1, results.tracks.size)
        assertEquals(1, results.playlists.size)
        assertTrue(results.artists.isEmpty())
        assertTrue(results.albums.isEmpty())
    }
}
