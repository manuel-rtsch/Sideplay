package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import io.github.manuelrtsch.sidetrack.data.NativeSpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSpotifyCatalogTest {
    @Test fun `track save writes the collection set`() = runTest {
        val native = FakeNative()
        val catalog = NativeSpotifyCatalog(native) {}

        catalog.setTrackSaved("abc", true)

        assertEquals("collection", native.set)
        assertEquals("[\"spotify:track:abc\"]", native.uris)
        assertFalse(native.removed)
    }

    @Test fun `album and show saves use their collection sets`() = runTest {
        val native = FakeNative()
        val catalog = NativeSpotifyCatalog(native) {}

        catalog.setSaved(CollectionType.Album, "album1", false)
        assertEquals("collection", native.set)
        assertTrue(native.removed)

        catalog.setSaved(CollectionType.Show, "show1", true)
        assertEquals("show", native.set)
        assertFalse(native.removed)
    }

    @Test fun `playlist save uses the rootlist endpoint`() = runTest {
        val native = FakeNative()
        val catalog = NativeSpotifyCatalog(native) {}

        catalog.setSaved(CollectionType.Playlist, "playlist1", true)

        assertEquals("playlist1", native.playlistId)
        assertTrue(native.playlistSave)
    }

    @Test fun `add to playlist forwards the uri and reports the index`() = runTest {
        val native = FakeNative().apply { playlistItemsAddResult = "7" }
        val catalog = NativeSpotifyCatalog(native) {}

        assertEquals(7, catalog.addToPlaylist("playlist1", "spotify:track:abc"))
        assertEquals("playlist1", native.playlistItemsId)
        assertEquals("[\"spotify:track:abc\"]", native.playlistItemsUris)
    }

    @Test fun `add to playlist fails when the native write is rejected`() = runTest {
        val native = FakeNative().apply { playlistItemsAddResult = null }
        val catalog = NativeSpotifyCatalog(native) {}

        val error = runCatching { catalog.addToPlaylist("playlist1", "spotify:track:abc") }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertEquals(SpotifyFailure.Network, (error as SpotifyGatewayException).failure)
    }

    @Test fun `remove from playlist forwards the uri`() = runTest {
        val native = FakeNative()
        val catalog = NativeSpotifyCatalog(native) {}

        catalog.removeFromPlaylist("playlist1", "spotify:track:abc")

        assertEquals("playlist1", native.playlistItemsId)
        assertEquals("[\"spotify:track:abc\"]", native.playlistItemsUris)
        assertTrue(native.playlistItemsRemoved)
    }

    @Test fun `contains responses seed saved state`() = runTest {
        val native = FakeNative().apply {
            contains = "[true]"
            playlistSaved = "true"
        }
        val catalog = NativeSpotifyCatalog(native) {}

        assertTrue(catalog.isTrackSaved("abc") == true)
        assertTrue(catalog.isSaved(CollectionType.Playlist, "playlist1") == true)
    }

    @Test fun `library forwards paging and reports another page`() = runTest {
        val tracks = JSONArray().apply {
            repeat(50) { put(JSONObject().put("id", "t$it")) }
        }
        val native = FakeNative().apply { libraryResult = tracks.toString() }
        val catalog = NativeSpotifyCatalog(native) {}

        val page = catalog.library(LibraryFilter.LikedSongs, offset = 50, limit = 50)

        assertEquals(50, page.items.size)
        assertTrue(page.hasMore)
        assertEquals(50L, native.libraryOffset)
        assertEquals(50L, native.libraryLimit)
    }

    @Test fun `library reports the category total from the native payload`() = runTest {
        val shows = JSONArray().apply {
            put(JSONObject().put("id", "show1"))
        }
        val native = FakeNative().apply {
            libraryResult = JSONObject()
                .put("items", shows)
                .put("total", 12)
                .toString()
        }
        val catalog = NativeSpotifyCatalog(native) {}

        val page = catalog.library(LibraryFilter.Shows, offset = 0, limit = 6)

        assertEquals(1, page.items.size)
        assertEquals(12, page.total)
        // A short page (hydrated rows dropped) still knows the category has more rows.
        assertTrue(page.hasMore)
    }

    @Test fun `library without a total falls back to the page size`() = runTest {
        val shows = JSONArray().apply {
            repeat(6) { put(JSONObject().put("id", "show$it")) }
        }
        val native = FakeNative().apply {
            libraryResult = JSONObject().put("items", shows).toString()
        }
        val catalog = NativeSpotifyCatalog(native) {}

        val page = catalog.library(LibraryFilter.Shows, offset = 0, limit = 6)

        assertEquals(null, page.total)
        assertTrue(page.hasMore)
    }

    @Test fun `collection preserves total and page state`() = runTest {
        val native = FakeNative().apply {
            collectionResult = JSONObject()
                .put("id", "p1")
                .put("title", "Long playlist")
                .put("subtitle", "You · 202 tracks")
                .put("items", JSONArray())
                .put("totalItems", 202)
                .put("hasMore", true)
                .toString()
        }
        val catalog = NativeSpotifyCatalog(native) {}

        val collection = catalog.collection(CollectionType.Playlist, "p1", offset = 50, limit = 50)

        assertEquals(202, collection?.totalItems)
        assertTrue(collection?.hasMore == true)
        assertEquals(50L, native.collectionOffset)
        assertEquals(50L, native.collectionLimit)
    }

    @Test fun `search forwards its offset and limit`() = runTest {
        val native = FakeNative().apply { searchResult = "{\"tracks\":[]}" }
        val catalog = NativeSpotifyCatalog(native) {}

        catalog.search("night", setOf(SearchScope.Tracks), offset = 50, limit = 50)

        assertEquals(50L, native.searchOffset)
        assertEquals(50L, native.searchLimit)
    }

    @Test fun `malformed library object maps to Unknown`() = runTest {
        val native = FakeNative().apply { libraryResult = "{" }
        val catalog = NativeSpotifyCatalog(native) {}

        val error = runCatching { catalog.library(LibraryFilter.Shows, 0, 50) }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertEquals(SpotifyFailure.Unknown, (error as SpotifyGatewayException).failure)
    }

    @Test fun `malformed library response maps to Unknown`() = runTest {
        val native = FakeNative().apply { libraryResult = "not json" }
        val catalog = NativeSpotifyCatalog(native) {}

        val error = runCatching { catalog.library(LibraryFilter.LikedSongs, 0, 50) }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertEquals(SpotifyFailure.Unknown, (error as SpotifyGatewayException).failure)
    }

    @Test fun `missing library response maps to Network`() = runTest {
        val native = FakeNative().apply { libraryResult = null }
        val catalog = NativeSpotifyCatalog(native) {}

        val error = runCatching { catalog.library(LibraryFilter.LikedSongs, 0, 50) }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertEquals(SpotifyFailure.Network, (error as SpotifyGatewayException).failure)
    }

    @Test fun `session error maps to AuthenticationExpired`() = runTest {
        val native = FakeNative().apply { libraryResult = """{"error":"SESSION expired"}""" }
        val catalog = NativeSpotifyCatalog(native) {}

        val error = runCatching { catalog.library(LibraryFilter.LikedSongs, 0, 50) }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertEquals(SpotifyFailure.AuthenticationExpired, (error as SpotifyGatewayException).failure)
    }

    @Test fun `other catalog error maps to Network`() = runTest {
        val native = FakeNative().apply { libraryResult = """{"error":"rate limited"}""" }
        val catalog = NativeSpotifyCatalog(native) {}

        val error = runCatching { catalog.library(LibraryFilter.LikedSongs, 0, 50) }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertEquals(SpotifyFailure.Network, (error as SpotifyGatewayException).failure)
    }

    @Test fun `artist albums and top tracks parse native fields`() = runTest {
        val native = FakeNative().apply {
            artistResult = """{"id":"a1","name":"Artist","artUrl":"https://example.test/a.jpg"}"""
            albumsResult = """[{"id":"al1","title":"Album","artist":"Artist","year":2024,"trackCount":3}]"""
            topTracksResult = """[{"id":"t1","title":"Track","artist":"Artist","durationMs":1234}]"""
        }
        val catalog = NativeSpotifyCatalog(native) {}

        val artist = catalog.artist("a1")
        assertEquals("Artist", artist?.name)
        val albums = catalog.albumsBy(artist!!, 0, 5)
        assertEquals(1, albums.items.size)
        assertEquals("Album", albums.items.first().title)
        val tracks = catalog.topTracks(artist, 0, 5)
        assertEquals(1, tracks.items.size)
        assertEquals("Track", tracks.items.first().title)
    }

    @Test fun `song radio parses tracks and forwards the limit`() = runTest {
        val native = FakeNative().apply {
            songRadioResult = """[{"id":"t2","title":"Similar","artist":"Artist","durationMs":2000,"uri":"spotify:track:t2"}]"""
        }
        val catalog = NativeSpotifyCatalog(native) {}

        val tracks = catalog.songRadio("t1", 50)

        assertEquals(1, tracks.size)
        assertEquals("Similar", tracks.first().title)
        assertEquals(50L, native.radioLimit)
    }

    @Test fun `song radio keeps the station playlist id`() = runTest {
        val native = FakeNative().apply {
            songRadioResult = """{"playlistId":"37i9dQZF1DXcBWIGoYBM5M","tracks":[{"id":"t2","title":"Similar","artist":"Artist","durationMs":2000}]}"""
        }
        val catalog = NativeSpotifyCatalog(native) {}

        val station = catalog.songRadioStation("t1", 50)

        assertEquals("37i9dQZF1DXcBWIGoYBM5M", station.playlistId)
        assertEquals(listOf("t2"), station.tracks.map { it.id })
    }

    @Test fun `artist radio parses tracks and forwards the limit`() = runTest {
        val native = FakeNative().apply {
            artistRadioResult = """[{"id":"t3","title":"Station","artist":"Artist","durationMs":3000}]"""
        }
        val catalog = NativeSpotifyCatalog(native) {}

        val tracks = catalog.artistRadio("a1", 40)

        assertEquals(1, tracks.size)
        assertEquals("Station", tracks.first().title)
        assertEquals(40L, native.artistRadioLimit)
    }

    @Test fun `failed write surfaces a network failure`() = runTest {
        val native = FakeNative().apply { writeOk = false }
        val catalog = NativeSpotifyCatalog(native) {}

        val error = runCatching { catalog.setTrackSaved("abc", true) }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertEquals(SpotifyFailure.Network, (error as SpotifyGatewayException).failure)
    }

    private class FakeNative : SpotifyNative {
        override val isAvailable = true
        var set: String? = null
        var uris: String? = null
        var removed = false
        var writeOk = true
        var contains = "[false]"
        var playlistSaved = "false"
        var playlistId: String? = null
        var playlistSave = false
        var playlistItemsId: String? = null
        var playlistItemsUris: String? = null
        var playlistItemsAddResult: String? = "0"
        var playlistItemsRemoved = false
        var libraryResult: String? = "[]"
        var libraryOffset = -1L
        var libraryLimit = -1L
        var collectionResult: String? = "{}"
        var collectionOffset = -1L
        var collectionLimit = -1L
        var artistResult: String? = null
        var albumsResult: String? = "[]"
        var topTracksResult: String? = "[]"
        var songRadioResult: String? = "[]"
        var radioLimit = -1L
        var artistRadioResult: String? = "[]"
        var artistRadioLimit = -1L
        var searchResult: String? = "{}"
        var searchOffset = -1L
        var searchLimit = -1L

        override fun initialize() = true
        override fun setTmpDir(path: String) = Unit
        override fun setCacheDir(path: String, sizeLimitBytes: Long) = Unit
        override fun connect(accessToken: String): String? = null
        override fun setAccessToken(accessToken: String) = true
        override fun disconnect() = Unit
        override fun hasPlayer() = false
        override fun createPlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean) = true
        override fun recreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean) = true
        override fun preload(uri: String) = true
        override fun load(uri: String, positionMs: Long, autoplay: Boolean) = true
        override fun play() = true
        override fun pause() = true
        override fun stop() = true
        override fun seek(positionMs: Long) = true
        override fun readAudio(timeoutMs: Long): ByteArray? = null
        override fun audioFormat(): String? = null
        override fun pollEvent(): String? = null
        override fun drainAudio() = Unit
        override fun catalogLibrary(filter: String, offset: Long, limit: Long): String? {
            libraryOffset = offset
            libraryLimit = limit
            return libraryResult
        }
        override fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String? {
            collectionOffset = offset
            collectionLimit = limit
            return collectionResult
        }
        override fun catalogArtist(id: String): String? = artistResult
        override fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String? = albumsResult
        override fun catalogTopTracks(id: String, offset: Long, limit: Long): String? = topTracksResult
        override fun catalogSongRadio(id: String, limit: Long): String? {
            radioLimit = limit
            return songRadioResult
        }
        override fun catalogArtistRadio(id: String, limit: Long): String? {
            artistRadioLimit = limit
            return artistRadioResult
        }
        override fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String? {
            searchOffset = offset
            searchLimit = limit
            return searchResult
        }
        override fun collectionContains(set: String, uris: String): String? = contains
        override fun collectionWrite(set: String, uris: String, removed: Boolean): Boolean {
            this.set = set
            this.uris = uris
            this.removed = removed
            return writeOk
        }
        override fun playlistSaved(id: String): String? = playlistSaved
        override fun playlistSave(id: String, save: Boolean): Boolean {
            playlistId = id
            playlistSave = save
            return writeOk
        }
        override fun playlistItemsAdd(id: String, uris: String): String? {
            playlistItemsId = id
            playlistItemsUris = uris
            return playlistItemsAddResult
        }
        override fun playlistItemsRemove(id: String, uris: String): Boolean {
            playlistItemsId = id
            playlistItemsUris = uris
            playlistItemsRemoved = true
            return writeOk
        }
    }
}
