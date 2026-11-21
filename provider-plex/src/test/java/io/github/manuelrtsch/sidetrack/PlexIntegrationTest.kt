package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.auth.PlexAccountHttpClient
import io.github.manuelrtsch.sidetrack.auth.PlexCredentials
import io.github.manuelrtsch.sidetrack.data.HttpPlexApi
import io.github.manuelrtsch.sidetrack.data.PlexCatalog
import io.github.manuelrtsch.sidetrack.data.PlexKind
import io.github.manuelrtsch.sidetrack.data.normalizePlexServerUrl
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Opt-in smoke test against a real Plex Media Server with a music library.
 *
 * `PLEX_TEST_URL=http://127.0.0.1:32400 PLEX_TEST_TOKEN=... ./gradlew :provider-plex:test \
 *   -PsidetrackIntegration --tests '*PlexIntegrationTest'`
 *
 * Skips without those variables, so CI and normal local runs stay offline.
 */
@RunWith(RobolectricTestRunner::class)
class PlexIntegrationTest {
    private val serverUrl = System.getenv("PLEX_TEST_URL").orEmpty()
    private val token = System.getenv("PLEX_TEST_TOKEN").orEmpty()

    private fun requireServer() {
        assumeTrue("Set PLEX_TEST_URL and PLEX_TEST_TOKEN to run this test", serverUrl.isNotBlank() && token.isNotBlank())
    }

    private fun api() = HttpPlexApi(
        PlexCredentials(
            serverUrl = serverUrl.trimEnd('/'),
            token = token,
            clientIdentifier = "sidetrack-integration-test",
            userName = "integration"
        )
    )

    @Test fun `reads sections artists albums tracks and search from a real server`() = runTest {
        requireServer()
        val api = api()
        val section = api.musicSections().firstOrNull()
        assumeTrue("The server has no music library", section != null)
        assertTrue(section!!.type == "artist")

        val artists = api.artists(section.key, 0, 5)
        assumeTrue("The music library is empty", artists.items.isNotEmpty())
        assertTrue(artists.items.all { it.kind == PlexKind.Artist })

        val albums = api.albums(section.key, 0, 5)
        assertTrue(albums.items.all { it.kind == PlexKind.Album })

        val tracks = api.tracks(section.key, 0, 10)
        assumeTrue("The music library has no tracks", tracks.items.isNotEmpty())
        val track = tracks.items.first()
        assertEquals(PlexKind.Track, track.kind)
        assertTrue(track.title.isNotBlank())
        assertNotNull(track.partKey)

        val search = api.search(track.title, 10)
        assertTrue(search.tracks.isNotEmpty() || search.albums.isNotEmpty() || search.artists.isNotEmpty())
    }

    @Test fun `resolves a stream URL that answers with audio bytes`() = runTest {
        requireServer()
        val api = api()
        val section = api.musicSections().firstOrNull() ?: return@runTest
        val tracks = api.tracks(section.key, 0, 5)
        assumeTrue(tracks.items.isNotEmpty())
        val track = tracks.items.first()
        val url = api.streamUrl(requireNotNull(track.partKey))

        val connection = URL(url).openConnection() as HttpURLConnection
        api.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.setRequestProperty("Range", "bytes=0-1023")
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        try {
            assertTrue(connection.responseCode in 200..206)
            assertTrue(connection.contentType.orEmpty().startsWith("audio/"))
            val bytes = connection.inputStream.use { it.readNBytes(256) }
            assertTrue(bytes.isNotEmpty())
        } finally {
            connection.disconnect()
        }
    }

    @Test fun `an unapproved pin has no auth token`() = runTest {
        val clientId = "sidetrack-integration-test-${System.currentTimeMillis()}"
        val client = PlexAccountHttpClient(clientId)
        val pin = client.createPin(clientId)
        assertTrue(pin.id > 0)
        assertTrue(pin.code.isNotBlank())
        assertNull(client.pinToken(clientId, pin.id))
    }

    @Test fun `plex tv reports the account and the demo server`() = runTest {
        requireServer()
        val clientId = "sidetrack-integration-test"
        val client = PlexAccountHttpClient(clientId)

        val account = client.account(clientId, token)
        assertTrue(!account?.username.isNullOrBlank())

        val resources = client.resources(clientId, token)
        val server = resources.firstOrNull { resource -> resource.provides.split(',').any { it.trim() == "server" } }
        assumeTrue(server != null)
        assertTrue(server!!.connections.isNotEmpty())

        var identity: io.github.manuelrtsch.sidetrack.auth.PlexServerIdentity? = null
        for (connection in server.connections) {
            val url = runCatching { normalizePlexServerUrl("http://${connection.address}:${connection.port}") }
                .getOrNull() ?: continue
            identity = client.identity(url, server.accessToken ?: token, clientId)
            if (identity != null) break
        }
        assertTrue(identity != null)
        assertTrue(identity!!.claimed)
        assertTrue(identity.machineIdentifier.isNotBlank())
    }

    @Test fun `the catalog reads pages and resolves playback from the real server`() = runTest {
        requireServer()
        val api = api()
        val catalog = PlexCatalog { api }

        val albums = catalog.library(LibrarySection.Albums, 0, 5)
        assumeTrue(albums.items.isNotEmpty())
        val album = albums.items.first() as Album
        val page = catalog.collection(album.key, 0, 5)
        assumeTrue(page != null)
        assertTrue(page!!.items.isNotEmpty())
        assertTrue(page.items.all { it is Track })

        val track = page.items.first() as Track
        val source = catalog.resolvePlaybackSource(track)
        assertTrue(source.uri.startsWith(serverUrl))
        assertEquals(token, source.headers["X-Plex-Token"])

        val artists = catalog.library(LibrarySection.Artists, 0, 5)
        assertTrue(artists.items.all { it is Artist })

        val playlists = catalog.library(LibrarySection.Playlists, 0, 20)
        val playlist = playlists.items.firstOrNull() as Playlist?
        if (playlist != null) {
            val items = catalog.collection(playlist.key, 0, 20)
            assertTrue(items != null)
            assertTrue(items!!.items.all { it is Track })
        }
    }

    @Test fun `artwork and playlist reads use the same token`() = runTest {
        requireServer()
        val api = api()
        val section = api.musicSections().firstOrNull() ?: return@runTest
        val albums = api.albums(section.key, 0, 5)
        val art = albums.items.firstOrNull()?.artThumb?.let(api::artUrl)
        assumeTrue(art != null)
        val artUrl = requireNotNull(art)

        val connection = URL(artUrl).openConnection() as HttpURLConnection
        api.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        try {
            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
            assertTrue(connection.contentType.orEmpty().startsWith("image/"))
        } finally {
            connection.disconnect()
        }

        val playlists = api.playlists(0, 20)
        val playlist = playlists.items.firstOrNull()
        if (playlist != null) {
            val items = api.playlistItems(playlist.ratingKey, 0, 20)
            assertTrue(items.items.all { it.kind == PlexKind.Track })
        }
        assertTrue(!api.machineIdentifier().isNullOrBlank())
    }
}
