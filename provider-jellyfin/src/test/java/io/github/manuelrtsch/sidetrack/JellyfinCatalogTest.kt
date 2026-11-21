package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.JellyfinCatalog
import io.github.manuelrtsch.sidetrack.data.JellyfinItem
import io.github.manuelrtsch.sidetrack.data.JellyfinKind
import io.github.manuelrtsch.sidetrack.data.JellyfinPage
import io.github.manuelrtsch.sidetrack.data.JellyfinPlaylist
import io.github.manuelrtsch.sidetrack.data.JellyfinSearchHint
import io.github.manuelrtsch.sidetrack.data.JellyfinSearchResult
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The neutral catalog, driven through the provider seam. No server, no Android, no SDK here —
 * that is exactly the point of `JellyfinApi`.
 */
class JellyfinCatalogTest {
    private val api = FakeJellyfinApi()
    private var settings = PlaybackSettings()
    private val catalog = JellyfinCatalog(api = { api }, settings = { settings })

    @Test fun `library sections map their pages and report whether more remain`() = runTest {
        api.liked = JellyfinPage(listOf(track("t1"), track("t2")), total = 5, startIndex = 0)
        val liked = catalog.library(LibrarySection.Liked, offset = 0, limit = 2)
        assertEquals(2, liked.items.size)
        assertEquals(5, liked.total)
        assertTrue(liked.hasMore)
        assertEquals(JellyfinKind.Audio, api.liked.items.first().kind)

        api.playlists = JellyfinPage(
            listOf(JellyfinItem(id = "p1", name = "Mix", kind = JellyfinKind.Playlist, childCount = 3)),
            total = 1
        )
        val playlists = catalog.library(LibrarySection.Playlists, offset = 0, limit = 10)
        val playlist = playlists.items.single() as Playlist
        assertTrue(playlist.editable)
        assertTrue(playlist.actions.unconfirmed)
        assertEquals(3, playlist.trackCount)
        assertEquals(ServiceId.Jellyfin, playlist.service)
        // No primary image tag means the server has no artwork for this playlist.
        assertNull(playlist.artUrl)

        api.albums = JellyfinPage(listOf(album("a1")), total = 1)
        assertEquals("a1", (catalog.library(LibrarySection.Albums, 0, 10).items.single() as Album).id)

        api.artists = JellyfinPage(listOf(artist("ar1")), total = 1)
        assertEquals("ar1", (catalog.library(LibrarySection.Artists, 0, 10).items.single() as Artist).id)

        assertTrue(catalog.library(LibrarySection.Shows, 0, 10).items.isEmpty())
    }

    @Test fun `an album collection loads its tracks and artwork`() = runTest {
        api.items["a1"] = album("a1", trackCount = 2)
        api.albumTracks = JellyfinPage(listOf(track("t1"), track("t2")), total = 2)

        val collection = catalog.collection(MediaKey(ServiceId.Jellyfin, MediaType.Album, "a1"), 0, 10)!!

        assertEquals(CollectionType.Album, collection.type)
        assertEquals("Signals", collection.title)
        assertEquals("Aurora Vale", collection.subtitle)
        assertEquals(2, collection.items.size)
        assertEquals("ar1", collection.artistId)
        assertEquals(2, collection.totalItems)
        assertFalse(collection.hasMore)
        assertTrue(collection.saveable)
        assertTrue(collection.artUrl!!.contains("tag-album"))
    }

    @Test fun `a playlist collection carries editability and its owner item ids`() = runTest {
        api.playlistDetails["p1"] = JellyfinPlaylist(
            item = JellyfinItem(
                id = "p1",
                name = "Night Drive",
                kind = JellyfinKind.Playlist,
                childCount = 2,
                imageTag = "tag-list"
            ),
            itemIds = listOf("t1", "t2"),
            editable = true
        )
        api.playlistItems = JellyfinPage(listOf(track("t1"), track("t2")), total = 2)

        val collection = catalog.collection(MediaKey(ServiceId.Jellyfin, MediaType.Playlist, "p1"), 0, 10)!!

        assertEquals(CollectionType.Playlist, collection.type)
        assertEquals("Night Drive", collection.title)
        assertEquals(2, collection.items.size)
        assertTrue(collection.artUrl!!.contains("tag-list"))
        assertFalse(collection.saveable)
        assertFalse(collection.hasMore)
    }

    @Test fun `artist pages come from the artist endpoints`() = runTest {
        api.items["ar1"] = artist("ar1")
        api.artistAlbums = JellyfinPage(listOf(album("a1")), total = 1)
        api.artistTopTracks = JellyfinPage(listOf(track("t1")), total = 1)

        assertEquals("Aurora Vale", catalog.artist(MediaKey(ServiceId.Jellyfin, MediaType.Artist, "ar1"))!!.name)
        assertEquals("a1", catalog.albums(MediaKey(ServiceId.Jellyfin, MediaType.Artist, "ar1"), 0, 10).items.single().id)
        assertEquals("t1", catalog.topTracks(MediaKey(ServiceId.Jellyfin, MediaType.Artist, "ar1"), 0, 10).items.single().id)
    }

    @Test fun `search splits hints into tracks artists albums and playlists`() = runTest {
        api.searchResult = JellyfinSearchResult(
            hints = listOf(
                JellyfinSearchHint(itemId = "t1", name = "Night Signals", kind = JellyfinKind.Audio, imageTag = "tag-track"),
                JellyfinSearchHint(itemId = "ar1", name = "Aurora Vale", kind = JellyfinKind.Artist, imageTag = "tag-artist"),
                JellyfinSearchHint(itemId = "a1", name = "Signals", kind = JellyfinKind.Album, imageTag = "tag-album"),
                JellyfinSearchHint(itemId = "p1", name = "Night Drive", kind = JellyfinKind.Playlist, imageTag = "tag-list")
            ),
            total = 4
        )

        val results = catalog.search("night", setOf(SearchScope.Tracks, SearchScope.Artists), 0, 10)

        assertEquals(listOf("t1"), results.tracks.map { it.id })
        assertEquals(listOf("ar1"), results.artists.map { it.id })
        assertEquals(listOf("a1"), results.albums.map { it.id })
        assertEquals(listOf("p1"), results.playlists.map { it.id })
        // A playlist from search is offered as editable like one from the library list: the server
        // does not say who may edit it, so the action is offered unconfirmed.
        assertTrue(results.playlists.single().editable)
        assertTrue(results.playlists.single().actions.unconfirmed)
    }

    @Test fun `radio asks for offset plus limit and drops the front`() = runTest {
        api.mix = JellyfinPage(listOf(track("t1"), track("t2"), track("t3")), total = 3)

        val page = catalog.radio(MediaKey(ServiceId.Jellyfin, MediaType.Track, "seed"), offset = 1, limit = 2)

        assertEquals(listOf("t2", "t3"), page.items.map { it.id })
        assertEquals(3, page.total)
        assertEquals(MediaType.Track, api.lastMixSeed?.second)
    }

    @Test fun `quality chooses the bitrate the server is asked to transcode to`() = runTest {
        settings = PlaybackSettings(audioQuality = AudioQuality.VeryHigh)

        val source = catalog.resolvePlaybackSource(
            io.github.manuelrtsch.sidetrack.Track(
                id = "t1",
                title = "Night",
                artist = "Aurora",
                artistId = "ar1",
                albumId = "a1",
                artRes = 0,
                durationMs = 1000,
                service = ServiceId.Jellyfin
            )
        )

        assertEquals(320_000, api.lastBitrate)
        assertEquals("https://jellyfin.example/Audio/stream", source.uri)
        assertEquals("jellyfin:track:t1", source.id)
        assertEquals("token", source.headers["X-Emby-Token"])
        assertEquals("session-1", catalog.playbackInfo("t1")!!.playSessionId)

        settings = PlaybackSettings(audioQuality = AudioQuality.High)
        catalog.resolvePlaybackSource(
            io.github.manuelrtsch.sidetrack.Track(
                id = "t2",
                title = "Night",
                artist = "Aurora",
                artistId = "ar1",
                albumId = "a1",
                artRes = 0,
                durationMs = 1000,
                service = ServiceId.Jellyfin
            )
        )
        assertNull(api.lastBitrate)
    }

    @Test fun `writes reach the server and unsupported saves stay silent`() = runTest {
        val trackKey = MediaKey(ServiceId.Jellyfin, MediaType.Track, "t1")

        catalog.setSaved(trackKey, true)
        catalog.setFollowed(MediaKey(ServiceId.Jellyfin, MediaType.Artist, "ar1"), true)
        catalog.setSaved(MediaKey(ServiceId.Jellyfin, MediaType.Playlist, "p1"), true)

        assertEquals(listOf("t1" to true, "ar1" to true), api.favorites)
        assertEquals(7, catalog.addToPlaylist(MediaKey(ServiceId.Jellyfin, MediaType.Playlist, "p1"), mediaTrack("t1")))
        catalog.removeFromPlaylist(MediaKey(ServiceId.Jellyfin, MediaType.Playlist, "p1"), mediaTrack("t1"))
        assertEquals(listOf("p1" to "t1"), api.added)
        assertEquals(listOf("p1" to "t1"), api.removed)
    }

    @Test fun `creating a playlist returns the server item`() = runTest {
        api.items["new-playlist"] = JellyfinItem(
            id = "new-playlist",
            name = "Fresh",
            kind = JellyfinKind.Playlist,
            childCount = 0
        )

        val seed = listOf(
            MediaKey(ServiceId.Jellyfin, MediaType.Track, "t1"),
            MediaKey(ServiceId.Jellyfin, MediaType.Track, "t2")
        )
        val playlist = catalog.createPlaylist("Fresh", seed)!!

        assertEquals("Fresh", api.created.single())
        assertEquals(listOf("t1", "t2"), api.createdIds)
        assertEquals("new-playlist", playlist.id)
        assertEquals("Fresh", playlist.name)
        assertEquals(ServiceId.Jellyfin, playlist.service)
        // 12.4 answers a playlist the account just created with OpenAccess:false and Shares:[], so
        // deriving editability from those fields made a creator's own playlist read as read-only.
        assertTrue(playlist.editable)
        assertTrue(playlist.actions.unconfirmed)
    }

    @Test fun `a removal passes the row the caller acted on`() = runTest {
        catalog.removeFromPlaylist(
            MediaKey(ServiceId.Jellyfin, MediaType.Playlist, "p1"),
            mediaTrack("t1"),
            index = 3
        )

        assertEquals(3, api.lastRemoveIndex)
    }

    @Test fun `saved state comes from user data and is absent when the server does not say`() = runTest {
        api.userData["t1"] = io.github.manuelrtsch.sidetrack.data.JellyfinUserData(
            itemId = "t1",
            isFavorite = true,
            playbackPositionTicks = 0,
            played = false
        )

        assertEquals(true, catalog.isSaved(MediaKey(ServiceId.Jellyfin, MediaType.Track, "t1")))
        assertNull(catalog.isSaved(MediaKey(ServiceId.Jellyfin, MediaType.Playlist, "p1")))
    }

    private fun mediaTrack(id: String) = Track(
        id = id,
        title = "Track $id",
        artist = "Aurora Vale",
        artistId = "ar1",
        albumId = "a1",
        artRes = 0,
        durationMs = 200_000L,
        service = ServiceId.Jellyfin
    )

    private fun track(id: String, name: String = "Track $id") = JellyfinItem(
        id = id,
        name = name,
        kind = JellyfinKind.Audio,
        albumId = "a1",
        albumArtist = "Aurora Vale",
        albumArtistId = "ar1",
        runTimeTicks = 200_000_000L,
        imageTag = "tag-$id"
    )

    private fun album(id: String, trackCount: Int = 1) = JellyfinItem(
        id = id,
        name = "Signals",
        kind = JellyfinKind.Album,
        albumArtist = "Aurora Vale",
        albumArtistId = "ar1",
        childCount = trackCount,
        imageTag = "tag-album"
    )

    private fun artist(id: String) = JellyfinItem(
        id = id,
        name = "Aurora Vale",
        kind = JellyfinKind.Artist,
        childCount = 1,
        imageTag = "tag-artist"
    )
}
