package io.github.manuelrtsch.sidetrack

import com.sun.net.httpserver.HttpServer
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifyWebApi
import java.net.InetSocketAddress
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real HTTP path against a loopback server: request shape, JSON mapping, page maths and
 * the error translation the UI depends on. No part of the class is stubbed except the token.
 */
@RunWith(RobolectricTestRunner::class)
class SpotifyWebApiHttpTest {
    private val server = FakeServer()

    @After fun stop() = server.stop()

    private fun api(token: String? = "tok"): SpotifyWebApi = SpotifyWebApi(server.base) { token }

    // --- library -------------------------------------------------------------------------------

    @Test fun `liked songs unwrap the saved-track envelope and report the total`() = runTest {
        server.body = """{"items":[{"track":$TRACK},{"track":null},{"name":"no track"}],"total":42}"""

        val page = api().library(LibraryFilter.LikedSongs, offset = 40, limit = 10)

        assertEquals(listOf("t1"), page.items.map { (it as Track).id })
        assertEquals(42, page.total)
        assertEquals("/me/tracks?limit=10&offset=40", server.requests.single())
        assertEquals("Bearer tok", server.auth.single())
    }

    @Test fun `playlists carry the owner and its fallback`() = runTest {
        server.body = """
            {"items":[
              {"name":"Late Night","id":"pl1","owner":{"id":"u1","display_name":"Manuel"},
               "images":[{"url":"http://img/p.jpg","width":300}],"tracks":{"total":9}},
              {"id":"pl2","owner":{"id":"u2"},"tracks":{"total":0}}
            ],"total":2}
        """

        val page = api().library(LibraryFilter.Playlists)

        assertEquals(listOf("pl1", "pl2"), page.items.map { (it as Playlist).id })
        val first = page.items[0] as Playlist
        assertEquals("Manuel", first.owner)
        assertEquals("u1", first.ownerId)
        assertEquals(9, first.trackCount)
        assertEquals("http://img/p.jpg", first.artUrl)
        assertEquals("Spotify", (page.items[1] as Playlist).owner)
        assertEquals("/me/playlists?limit=20&offset=0", server.requests.single())
    }

    @Test fun `saved albums map year tracks and artwork`() = runTest {
        server.body = """
            {"items":[{"album":{
              "id":"al1","name":"Signals","release_date":"2024-05-01",
              "artists":[{"id":"ar1","name":"Aurora Vale"}],
              "images":[{"url":"http://img/32.jpg","width":32},{"url":"http://img/64.jpg","width":64},
                        {"url":"http://img/300.jpg","width":300},{"url":"http://img/640.jpg","width":640}],
              "tracks":{"total":12,"items":[$TRACK]}
            }}],"total":1}
        """

        val album = api().library(LibraryFilter.Albums).items.single() as Album

        assertEquals("Signals", album.title)
        assertEquals("Aurora Vale", album.artist)
        assertEquals("ar1", album.artistId)
        assertEquals(2024, album.year)
        assertEquals(12, album.trackCount)
        assertEquals(listOf("t1"), album.tracks.map { it.id })
        // The smallest image that is at least 64px wide.
        assertEquals("http://img/64.jpg", album.artUrl)
        assertEquals("/me/albums?limit=20&offset=0", server.requests.single())
    }

    @Test fun `followed artists come out of the nested page`() = runTest {
        server.body = """
            {"artists":{"items":[
              {"id":"ar1","name":"Aurora Vale","images":[{"url":"http://img/a.jpg","width":300}]},
              {"name":"No id"}
            ],"total":1}}
        """

        val page = api().library(LibraryFilter.Artists)

        assertEquals(listOf("ar1"), page.items.map { (it as Artist).id })
        assertEquals(1, page.total)
        assertEquals("http://img/a.jpg", (page.items.single() as Artist).artUrl)
        assertEquals("/me/following?type=artist&limit=20&offset=0", server.requests.single())
    }

    @Test fun `saved shows keep their episodes and publisher`() = runTest {
        server.body = """
            {"items":[{"show":{
              "id":"sh1","name":"Deep Field","publisher":"Aurora",
              "description":"Notes","images":[{"url":"http://img/s.jpg","width":300}],
              "episodes":{"total":2,"items":[
                {"id":"ep1","name":"One","release_date":"2024-01-01","duration_ms":60000,
                 "uri":"spotify:episode:ep1","description":"First"}
              ]}
            }}],"total":1}
        """

        val show = api().library(LibraryFilter.Shows).items.single() as Show

        assertEquals("Deep Field", show.name)
        assertEquals("Aurora", show.publisher)
        assertEquals("Notes", show.description)
        assertEquals(2, show.episodeCount)
        assertEquals(listOf("ep1"), show.episodes.map { it.id })
        assertEquals("http://img/s.jpg", show.episodes.single().artUrl)
        assertEquals("/me/shows?limit=20&offset=0", server.requests.single())
    }

    // --- collections ---------------------------------------------------------------------------

    @Test fun `an album collection fetches detail then a page of tracks`() = runTest {
        server.respond(
            """{"id":"al1","name":"Signals","release_date":"2024-05-01",
                "artists":[{"id":"ar1","name":"Aurora Vale"}],
                "images":[{"url":"http://img/a.jpg","width":300}],
                "tracks":{"total":5}}""",
            """{"items":[$TRACK],"total":5}"""
        )

        val collection = api().collection(CollectionType.Album, "al1", offset = 1, limit = 2)!!

        assertEquals("Signals", collection.title)
        assertEquals("Aurora Vale · 2024 · 5 tracks", collection.subtitle)
        assertEquals("ar1", collection.artistId)
        assertEquals("http://img/a.jpg", collection.artUrl)
        assertEquals(5, collection.totalItems)
        assertTrue(collection.hasMore)
        assertEquals(ServiceId.Spotify, collection.service)
        assertEquals(listOf("t1"), collection.items.map { it.id })
        assertEquals(
            listOf("/albums/al1", "/albums/al1/tracks?limit=2&offset=1"),
            server.requests
        )
    }

    @Test fun `a playlist collection wraps the tracks and stops at the count`() = runTest {
        server.respond(
            """{"id":"pl1","name":"Late Night","owner":{"id":"u1","display_name":"Manuel"},
                "images":[{"url":"http://img/p.jpg","width":300}],"tracks":{"total":2}}""",
            """{"items":[{"track":$TRACK},{"track":{"id":"t2","name":"Two","uri":"spotify:track:t2"}}]}"""
        )

        val collection = api().collection(CollectionType.Playlist, "pl1")!!

        assertEquals("Late Night", collection.title)
        assertEquals("Manuel · 2 tracks", collection.subtitle)
        assertEquals("u1", collection.artistId)
        assertEquals(2, collection.totalItems)
        assertFalse(collection.hasMore)
        assertEquals(listOf("t1", "t2"), collection.items.map { it.id })
        assertEquals(
            listOf("/playlists/pl1", "/playlists/pl1/tracks?limit=20&offset=0&market=from_token"),
            server.requests
        )
    }

    @Test fun `a show collection pages episodes and keeps show notes`() = runTest {
        server.respond(
            """{"id":"sh1","name":"Deep Field","publisher":"Aurora",
                "description":"Notes","images":[{"url":"http://img/s.jpg","width":300}],
                "episodes":{"total":4}}""",
            """{"items":[
              {"id":"ep1","name":"One","uri":"spotify:episode:ep1","description":"First"},
              {"name":"No id"}
            ]}"""
        )

        val collection = api().collection(CollectionType.Show, "sh1")!!

        assertEquals("Deep Field", collection.title)
        assertEquals("Aurora · 4 episodes", collection.subtitle)
        assertEquals("Notes", collection.description)
        assertEquals("sh1", collection.id)
        val episode = collection.items.single() as Episode
        assertEquals("Deep Field", episode.show)
        assertEquals("sh1", episode.showId)
        assertEquals("Podcast episode", episode.published)
        assertEquals("http://img/s.jpg", episode.artUrl)
        assertFalse(episode.video)
        assertEquals(
            listOf("/shows/sh1", "/shows/sh1/episodes?limit=20&offset=0&market=from_token"),
            server.requests
        )
    }

    @Test fun `a detail request that fails makes the collection unavailable instead of throwing`() = runTest {
        server.status = 500

        assertNull(api().collection(CollectionType.Album, "gone"))
        assertNull(api().collection(CollectionType.Playlist, "gone"))
        assertNull(api().collection(CollectionType.Show, "gone"))
    }

    // --- search --------------------------------------------------------------------------------

    @Test fun `a short query or no scope never reaches the network`() = runTest {
        assertTrue(api().search(" a ", setOf(SearchScope.Tracks)).isEmpty)
        assertTrue(api().search("aurora", emptySet()).isEmpty)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `search trims encodes clamps and orders the requested types`() = runTest {
        server.body = "{}"

        api().search("  aurora vale  ", SearchScope.entries.toSet(), offset = 5, limit = 999)

        val request = server.requests.single()
        assertTrue(request.startsWith("/search?q=aurora+vale&type=track,artist,album,playlist,show"))
        assertTrue(request.contains("limit=50"))
        assertTrue(request.contains("offset=5"))
        assertTrue(request.contains("market=from_token"))
    }

    @Test fun `search maps every section and tolerates missing ones`() = runTest {
        server.body = """
            {"tracks":{"items":[$TRACK]},
             "artists":{"items":[{"id":"ar1","name":"Aurora"}]},
             "albums":{"items":[{"id":"al1","name":"Signals","artists":[{"id":"ar1","name":"Aurora"}],
                                 "release_date":"2024","tracks":{"total":1}}]},
             "playlists":{"items":[{"id":"pl1","name":"Late"}]},
             "shows":{"items":[{"id":"sh1","name":"Deep","publisher":"Aurora"}]}}
        """

        val results = api().search("aurora", SearchScope.entries.toSet())

        assertEquals(listOf("t1"), results.tracks.map { it.id })
        assertEquals(listOf("ar1"), results.artists.map { it.id })
        assertEquals(listOf("al1"), results.albums.map { it.id })
        assertEquals(listOf("pl1"), results.playlists.map { it.id })
        assertEquals(listOf("sh1"), results.shows.map { it.id })

        server.body = """{"tracks":{"items":[]}}"""
        assertTrue(api().search("aurora", setOf(SearchScope.Tracks)).albums.isEmpty())
    }

    // --- artist, albums and radio --------------------------------------------------------------

    @Test fun `an artist lookup maps or returns null on failure`() = runTest {
        server.body = """{"id":"ar1","name":"Aurora","images":[{"url":"http://img/a.jpg","width":300}]}"""

        val artist = api().artist("ar 1")!!

        assertEquals("Aurora", artist.name)
        assertEquals("http://img/a.jpg", artist.artUrl)
        assertEquals("/artists/ar+1", server.requests.single())

        server.body = """{"name":"No id"}"""
        assertNull(api().artist("gone"))

        server.status = 500
        assertNull(api().artist("gone"))
    }

    @Test fun `albums by an artist page and fall back to empty on failure`() = runTest {
        server.body = """
            {"items":[{"id":"al1","name":"Signals","artists":[{"id":"ar1","name":"Aurora"}],
                       "release_date":"2024","tracks":{"total":3}}],"total":3}
        """

        val page = api().albumsBy(Artist("ar1", "Aurora", 0, emptyList()), offset = 2, limit = 1)

        assertEquals(listOf("al1"), page.items.map { it.id })
        assertEquals(3, page.total)
        assertEquals(
            "/artists/ar1/albums?include_groups=album,single&limit=1&offset=2&market=from_token",
            server.requests.single()
        )

        server.status = 500
        val failed = api().albumsBy(Artist("ar1", "Aurora", 0, emptyList()))
        assertTrue(failed.items.isEmpty())
        assertNull(failed.total)
        assertFalse(failed.hasMore)
    }

    @Test fun `top tracks are sliced locally because Spotify ignores paging`() = runTest {
        server.body = """{"tracks":[{"id":"t1","name":"One"},{"id":"t2","name":"Two"},{"id":"t3","name":"Three"}]}"""

        val page = api().topTracks(Artist("ar1", "Aurora", 0, emptyList()), offset = 1, limit = 1)

        assertEquals(listOf("t2"), page.items.map { it.id })
        assertEquals("/artists/ar1/top-tracks?market=from_token", server.requests.single())

        server.status = 500
        assertTrue(api().topTracks(Artist("ar1", "Aurora", 0, emptyList())).items.isEmpty())
    }

    @Test fun `both radios seed recommendations and clamp the limit`() = runTest {
        server.body = """{"tracks":[$TRACK]}"""

        assertEquals(listOf("t1"), api().songRadio("t 1", limit = 999).map { it.id })
        assertTrue(server.requests.last().startsWith("/recommendations?seed_tracks=t+1&limit=100"))

        assertEquals(listOf("t1"), api().artistRadio("ar1", limit = 0).map { it.id })
        assertEquals("/recommendations?seed_artists=ar1&limit=1&market=from_token", server.requests.last())
    }

    // --- transport failures --------------------------------------------------------------------

    @Test fun `an unauthenticated response means the session expired`() = runTest {
        server.status = 401

        val error = runCatching { api().library(LibraryFilter.LikedSongs) }.exceptionOrNull()

        assertEquals(SpotifyFailure.AuthenticationExpired, (error as SpotifyGatewayException).failure)
        assertEquals("Sign in again", error.message)
    }

    @Test fun `rate limiting and server failures keep their own categories`() = runTest {
        server.status = 429
        assertEquals(
            SpotifyFailure.RateLimited,
            (runCatching { api().library(LibraryFilter.LikedSongs) }.exceptionOrNull() as SpotifyGatewayException).failure
        )

        server.status = 404
        val notFound = runCatching { api().library(LibraryFilter.LikedSongs) }.exceptionOrNull() as SpotifyGatewayException
        assertEquals(SpotifyFailure.Network, notFound.failure)
        assertEquals("Spotify returned HTTP 404", notFound.message)
    }

    @Test fun `an unreadable body is an unknown failure`() = runTest {
        server.body = "not json"

        val error = runCatching { api().library(LibraryFilter.LikedSongs) }.exceptionOrNull() as SpotifyGatewayException

        assertEquals(SpotifyFailure.Unknown, error.failure)
        assertEquals("Spotify returned an unreadable response", error.message)
    }

    @Test fun `a missing token fails before dialling`() = runTest {
        val error = runCatching { api(token = null).library(LibraryFilter.LikedSongs) }
            .exceptionOrNull() as SpotifyGatewayException

        assertEquals(SpotifyFailure.AuthenticationExpired, error.failure)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `an unreachable server is a network failure with the cause`() = runTest {
        server.stop()

        val error = runCatching { api().library(LibraryFilter.LikedSongs) }
            .exceptionOrNull() as SpotifyGatewayException

        assertEquals(SpotifyFailure.Network, error.failure)
        assertEquals("Couldn't reach Spotify", error.message)
    }

    // --- mapping details -----------------------------------------------------------------------

    @Test fun `a sparse track gets the neutral defaults`() = runTest {
        server.body = """{"items":[{"track":{"id":"bare"}}],"total":1}"""

        val track = api().library(LibraryFilter.LikedSongs).items.single() as Track

        assertEquals("bare", track.id)
        assertEquals("Unknown track", track.title)
        assertEquals("Unknown artist", track.artist)
        assertEquals("", track.artistId)
        assertEquals("", track.albumId)
        assertEquals(0L, track.durationMs)
        assertFalse(track.explicit)
        assertTrue(track.playable)
        assertNull(track.uri)
        assertNull(track.artUrl)
        assertEquals(ServiceId.Spotify, track.service)
    }

    @Test fun `artwork prefers the smallest usable image and falls back to the first`() = runTest {
        server.body = """
            {"items":[
              {"album":{"id":"a1","images":[{"url":"http://img/small.jpg","width":32},
                                            {"url":"","width":600}]}},
              {"album":{"id":"a2","images":[{"url":"http://img/only.jpg"}]}},
              {"album":{"id":"a3","images":[]}}
            ],"total":3}
        """

        val albums = api().library(LibraryFilter.Albums).items.map { it as Album }

        // No image reaches 64px, so the first non-blank one is used.
        assertEquals("http://img/small.jpg", albums[0].artUrl)
        // A missing width is treated as unknown and does not win the "smallest usable" race.
        assertEquals("http://img/only.jpg", albums[1].artUrl)
        assertNull(albums[2].artUrl)
    }

    @Test fun `an episode without show metadata still maps safely`() = runTest {
        server.respond(
            """{"id":"sh1","name":"Deep Field","publisher":"Aurora","episodes":{"total":1}}""",
            """{"items":[{"id":"ep1","name":"One"}]}"""
        )

        val episode = api().collection(CollectionType.Show, "sh1")!!.items.single() as Episode

        assertEquals("Deep Field", episode.show)
        assertEquals("sh1", episode.showId)
        assertEquals("Podcast episode", episode.published)
        assertNull(episode.artUrl)
        assertNull(episode.uri)
        assertEquals("", episode.description)
    }

    // --- fixture -------------------------------------------------------------------------------

    private fun runTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    private class FakeServer {
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val auth: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        var status = 200
        var body = "{}"
        private val queue = ArrayDeque<String>()

        val base: String get() = "http://127.0.0.1:${http.address.port}"

        init {
            http.createContext("/") { exchange ->
                val uri = exchange.requestURI
                requests += if (uri.query == null) uri.path else "${uri.path}?${uri.query}"
                auth += exchange.requestHeaders.getFirst("Authorization")
                val next = synchronized(queue) { if (queue.isEmpty()) body else queue.removeFirst() }
                val bytes = next.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            http.start()
        }

        fun stop() = http.stop(0)

        fun respond(vararg bodies: String) {
            synchronized(queue) {
                queue.clear()
                queue.addAll(bodies)
            }
        }
    }

    private companion object {
        const val TRACK = """
            {"id":"t1","name":"Night Signals","duration_ms":213000,"explicit":true,
             "is_playable":false,"uri":"spotify:track:t1",
             "artists":[{"id":"ar1","name":"Aurora Vale"}],
             "album":{"id":"al1","name":"Signals","images":[{"url":"http://img/t.jpg","width":300}]}}
        """
    }
}
