package io.github.manuelrtsch.sidetrack

import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.auth.NavidromeAuth
import io.github.manuelrtsch.sidetrack.auth.NavidromeCredentialStore
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.NavidromeApi
import io.github.manuelrtsch.sidetrack.data.NavidromeCatalog
import io.github.manuelrtsch.sidetrack.data.NavidromeCredentials
import io.github.manuelrtsch.sidetrack.data.NavidromeTransport
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.normalized
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeApiTest {
    @Test fun `normalises a bare host to http and trims slashes`() {
        val credentials = NavidromeCredentials(" nas.local:4533/ ", " joe ", "sesame").normalized()
        assertEquals("http://nas.local:4533", credentials.serverUrl)
        assertEquals("joe", credentials.username)
    }

    @Test fun `ping sends the subsonic token instead of the password`() = runTest {
        val transport = FakeTransport {
            ok("""{"status":"ok"}""")
        }
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local:4533", "joe", "sesame"),
            transport,
            salt = { "c19b2d" }
        )

        api.ping()

        val query = transport.lastQuery()
        assertEquals("joe", query["u"])
        assertEquals("c19b2d", query["s"])
        assertEquals("26719a1196d2a940705a59634eb18eab", query["t"])
        assertEquals("1.16.1", query["v"])
        assertEquals("Sidetrack", query["c"])
        assertEquals("json", query["f"])
        assertFalse(transport.urls.single().contains("sesame"))
    }

    @Test fun `a failed envelope maps the subsonic error code`() = runTest {
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local", "joe", "wrong"),
            FakeTransport { ok("""{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}""") },
            salt = { "salt" }
        )

        val error = runCatching { api.ping() }.exceptionOrNull()

        assertTrue(error is ServiceException)
        assertEquals(ServiceFailure.AuthenticationExpired, (error as ServiceException).failure)
        assertEquals("Wrong username or password", error.message)
    }

    @Test fun `artists carry stable ids and authenticated cover urls`() = runTest {
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local", "joe", "sesame"),
            FakeTransport {
                ok(
                    """{"status":"ok","artists":{"index":[{"name":"A","artist":[
                        {"id":"ar1","name":"Aurora","albumCount":2,"coverArt":"ar1"}
                    ]}]}}"""
                )
            },
            salt = { "salt" }
        )

        val artist = api.artists().single()

        assertEquals("ar1", artist.id)
        assertEquals("Aurora", artist.name)
        assertEquals(ServiceId.Navidrome, artist.service)
        assertTrue(artist.artUrl!!.contains("/rest/getCoverArt"))
        assertTrue(artist.artUrl!!.contains("id=ar1"))
    }

    @Test fun `a json null cover id means the playlist has no artwork`() = runTest {
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local", "joe", "sesame"),
            FakeTransport {
                ok(
                    """{"status":"ok","playlists":{"playlist":[
                        {"id":"p1","name":"Night Drive","coverArt":null}
                    ]}}"""
                )
            },
            salt = { "salt" }
        )

        val playlist = api.playlists().single()

        assertEquals("p1", playlist.id)
        assertNull(playlist.artUrl)
    }

    @Test fun `search3 maps each requested scope`() = runTest {
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local", "joe", "sesame"),
            FakeTransport {
                ok(
                    """{"status":"ok","searchResult3":{
                        "song":[{"id":"s1","title":"Night","artist":"Aurora","artistId":"ar1","albumId":"al1","duration":180}],
                        "album":[{"id":"al1","name":"Night Album","artist":"Aurora","artistId":"ar1","songCount":1}],
                        "artist":[{"id":"ar1","name":"Aurora","albumCount":1}]
                    }}"""
                )
            },
            salt = { "salt" }
        )

        val result = api.search("night", SearchScope.entries.toSet(), 0, 20)

        assertEquals("s1", result.tracks.single().id)
        assertEquals(180_000L, result.tracks.single().durationMs)
        assertEquals("al1", result.albums.single().id)
        assertEquals("ar1", result.artists.single().id)
    }

    @Test fun `album lists page with offset and size`() = runTest {
        val transport = FakeTransport {
            ok("""{"status":"ok","albumList2":{"album":[{"id":"al1","name":"One","artist":"A","songCount":1}]}}""")
        }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })

        val page = api.albumList(offset = 40, limit = 20)

        assertEquals("al1", page.items.single().id)
        assertEquals("40", transport.lastQuery()["offset"])
        assertEquals("20", transport.lastQuery()["size"])
        assertFalse(page.hasMore)
    }

    @Test fun `stream and cover urls carry auth but no password`() = runTest {
        val transport = FakeTransport { ok("""{"status":"ok"}""") }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })

        val stream = api.streamUrl("song 1")
        val cover = api.coverUrl("cover 1")!!

        assertTrue(stream.contains("/rest/stream"))
        assertTrue(stream.contains("id=song+1"))
        assertTrue(cover.contains("/rest/getCoverArt"))
        assertTrue(cover.contains("id=cover+1"))
        assertFalse(stream.contains("sesame"))
    }

    @Test fun `stream url honors maxBitRate and replay gain`() = runTest {
        val transport = FakeTransport { ok("""{"status":"ok"}""") }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })

        val capped = api.streamUrl("s1", maxBitRateKbps = 96, replayGain = true)
        assertTrue(capped.contains("/rest/stream"))
        assertTrue(capped.contains("id=s1"))
        assertTrue(capped.contains("maxBitRate=96"))
        assertTrue(capped.contains("replayGain=true"))

        val plain = api.streamUrl("s2")
        assertFalse(plain.contains("maxBitRate"))
        assertFalse(plain.contains("replayGain"))
    }

    @Test fun `catalog applies quality and normalization from settings to the stream`() = runTest {
        val transport = FakeTransport { ok("""{"status":"ok"}""") }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })
        val catalog = NavidromeCatalog(
            { api },
            { PlaybackSettings(audioQuality = AudioQuality.Normal, normalization = true) }
        )

        val track = Track(
            id = "s1",
            title = "One",
            artist = "A",
            artistId = "a1",
            albumId = "al1",
            artRes = 0,
            durationMs = 60_000,
            service = ServiceId.Navidrome
        )

        val source = catalog.resolvePlaybackSource(track)
        assertTrue(source.uri.contains("maxBitRate=96"))
        assertTrue(source.uri.contains("replayGain=true"))
    }

    @Test fun `createPlaylist maps the new playlist`() = runTest {
        val transport = FakeTransport { ok("""{"status":"ok","playlist":{"id":"p-new","name":"Road Trip"}}""") }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })

        val playlist = api.createPlaylist("Road Trip")

        assertEquals("p-new", playlist.id)
        assertEquals("Road Trip", playlist.name)
        assertEquals(ServiceId.Navidrome, playlist.service)
        assertTrue(transport.urls.single().contains("createPlaylist"))
        assertTrue(transport.urls.single().contains("name=Road+Trip"))
        assertEquals(PlaylistActions.All, playlist.actions)
    }

    @Test fun `a playlist owned by another account is read-only`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("getPlaylist") -> ok(
                    """{"status":"ok","playlist":{"id":"p1","name":"Mix","owner":"alex","songCount":1,"entry":[{"id":"s1"}]}}"""
                )

                else -> ok("""{"status":"ok"}""")
            }
        }
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local", "joe", "sesame"),
            transport,
            salt = { "salt" }
        )

        val playlist = api.playlist("p1")!!

        assertEquals("alex", playlist.owner)
        assertEquals(PlaylistActions.None, playlist.actions)
        assertFalse(playlist.editable)

        val add = runCatching { api.addToPlaylist("p1", "s1") }.exceptionOrNull()
        assertEquals(ServiceFailure.NotAllowed, (add as ServiceException).failure)

        val remove = runCatching { api.removeFromPlaylist("p1", "s1") }.exceptionOrNull()
        assertEquals(ServiceFailure.NotAllowed, (remove as ServiceException).failure)
        assertFalse(transport.urls.any { it.contains("updatePlaylist") })
    }

    @Test fun `a playlist without an owner is offered for editing and the server decides`() = runTest {
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local", "joe", "sesame"),
            FakeTransport { ok("""{"status":"ok","playlist":{"id":"p1","name":"Mix"}}""") },
            salt = { "salt" }
        )

        assertEquals(PlaylistActions.ServerDecides, api.playlist("p1")!!.actions)
    }

    @Test fun `saved state reads the starred marker`() = runTest {
        val api = NavidromeApi(
            NavidromeCredentials("http://nas.local", "joe", "sesame"),
            FakeTransport { ok("""{"status":"ok","song":{"id":"s1","starred":"2024-01-01"}}""") },
            salt = { "salt" }
        )

        assertTrue(api.isStarred(MediaKey(ServiceId.Navidrome, MediaType.Track, "s1")))
    }

    @Test fun `auth signs in against a ping and stores the credentials`() = runTest {
        val store = InMemoryStore()
        val transport = FakeTransport { ok("""{"status":"ok"}""") }
        val auth = NavidromeAuth(
            NavidromeCredentialStore(ApplicationProvider.getApplicationContext(), "nav-test", store),
            transport
        )

        val account = auth.signIn(AuthInput.Password(" http://nas.local:4533/ ", "joe", "sesame"))

        assertEquals(ServiceId.Navidrome, account.service)
        assertEquals("http://nas.local:4533", account.serverUrl)
        assertEquals("joe", auth.credentials()?.username)
        assertEquals("sesame", store.read().optString("password"))
        assertEquals(ServiceId.Navidrome, auth.restore()?.service)

        auth.clear()
        assertFalse(auth.hasCredentials())
    }

    @Test fun `catalog resolves a stream and paginates a playlist`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("getPlaylist") -> ok(
                    """{"status":"ok","playlist":{"id":"p1","name":"Mix","owner":"joe","songCount":3,"entry":[
                        {"id":"s1","title":"One","artist":"A","duration":60},
                        {"id":"s2","title":"Two","artist":"A","duration":60},
                        {"id":"s3","title":"Three","artist":"A","duration":60}
                    ]}}"""
                )

                url.contains("stream") -> ok("""{"status":"ok"}""")

                else -> ok("""{"status":"ok"}""")
            }
        }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })
        val catalog = NavidromeCatalog(api = { api })

        val playlist = catalog.collection(MediaKey(ServiceId.Navidrome, MediaType.Playlist, "p1"), 1, 1)!!
        assertEquals(listOf("s2"), playlist.items.map { it.id })
        assertTrue(playlist.hasMore)

        val source = catalog.resolvePlaybackSource(playlist.items.single())
        assertTrue(source.uri.contains("/rest/stream"))
        assertTrue(source.uri.contains("id=s2"))
        assertEquals("navidrome:track:s2", source.id)
    }

    /**
     * Subsonic removes by index, and the old code always picked `indexOfFirst`, so with
     * `[A, B, C, A]` removing the second `A` deleted the first one: the size stayed right and the
     * playlist rotated. The row the caller names wins; without one, the last match (the copy an
     * append left at the end) is removed.
     */
    @Test fun `removing a duplicated track removes the occurrence asked for`() = runTest {
        val transport = FakeTransport { url ->
            if (url.contains("getPlaylist")) {
                ok(
                    """{"status":"ok","playlist":{"id":"p1","name":"Mix","owner":"joe","songCount":4,"entry":[
                        {"id":"s1","title":"One"},{"id":"s2","title":"Two"},
                        {"id":"s3","title":"Three"},{"id":"s1","title":"One"}
                    ]}}"""
                )
            } else {
                ok("""{"status":"ok"}""")
            }
        }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })
        val playlist = MediaKey(ServiceId.Navidrome, MediaType.Playlist, "p1")
        val song = Track(id = "s1", title = "One", artist = "A", artistId = "ar", albumId = "al", artRes = 0, durationMs = 0)

        api.removeFromPlaylist(playlist.id, song.id, index = 0)
        assertEquals("0", transport.lastQuery()["songIndexToRemove"])

        api.removeFromPlaylist(playlist.id, song.id)
        assertEquals("3", transport.lastQuery()["songIndexToRemove"])

        // A row that no longer holds this song is not trusted.
        api.removeFromPlaylist(playlist.id, song.id, index = 1)
        assertEquals("3", transport.lastQuery()["songIndexToRemove"])
    }

    @Test fun `a track that is not in the playlist sends nothing`() = runTest {
        val transport = FakeTransport { url ->
            if (url.contains("getPlaylist")) {
                ok("""{"status":"ok","playlist":{"id":"p1","name":"Mix","owner":"joe","songCount":1,"entry":[{"id":"s1","title":"One"}]}}""")
            } else {
                ok("""{"status":"ok"}""")
            }
        }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })

        api.removeFromPlaylist("p1", "s9")

        assertFalse(transport.urls.any { it.contains("updatePlaylist") })
    }

    @Test fun `writer stars and edits a playlist`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("getPlaylist") -> ok(
                    """{"status":"ok","playlist":{"id":"p1","name":"Mix","owner":"joe","songCount":2,"entry":[
                        {"id":"s1","title":"One"},{"id":"s2","title":"Two"}
                    ]}}"""
                )

                else -> ok("""{"status":"ok"}""")
            }
        }
        val api = NavidromeApi(NavidromeCredentials("http://nas.local", "joe", "sesame"), transport, salt = { "salt" })
        val catalog = NavidromeCatalog(api = { api })
        val key = MediaKey(ServiceId.Navidrome, MediaType.Track, "s2")

        catalog.setSaved(key, true)
        assertTrue(transport.urls.any { it.contains("/rest/star") && it.contains("id=s2") })

        catalog.removeFromPlaylist(
            MediaKey(ServiceId.Navidrome, MediaType.Playlist, "p1"),
            Track(
                id = "s2",
                title = "Two",
                artist = "A",
                artistId = "ar",
                albumId = "al",
                artRes = 0,
                durationMs = 0
            )
        )
        assertTrue(transport.urls.any { it.contains("/rest/updatePlaylist") && it.contains("songIndexToRemove=1") })

        assertEquals(
            2,
            catalog.addToPlaylist(
                MediaKey(ServiceId.Navidrome, MediaType.Playlist, "p1"),
                Track(id = "s3", title = "Three", artist = "A", artistId = "ar", albumId = "al", artRes = 0, durationMs = 0)
            )
        )
    }

    private class InMemoryStore : JsonStore {
        private var value: JSONObject? = null

        override fun read(): JSONObject = value ?: JSONObject()

        override fun write(value: JSONObject) {
            this.value = value
        }

        override fun clear() {
            value = null
        }
    }

    private class FakeTransport(private val handler: (String) -> JSONObject) : NavidromeTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String): JSONObject {
            urls += url
            return handler(url)
        }

        fun lastQuery(): Map<String, String> = URLDecoder.decode(urls.last().substringAfter('?'), Charsets.UTF_8.name())
            .split('&')
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun ok(json: String): JSONObject = JSONObject("""{"subsonic-response":$json}""")
}
