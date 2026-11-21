package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.auth.PlexCredentials
import io.github.manuelrtsch.sidetrack.data.HttpPlexApi
import io.github.manuelrtsch.sidetrack.data.PlexKind
import io.github.manuelrtsch.sidetrack.data.PlexTransport
import io.github.manuelrtsch.sidetrack.data.normalizePlexServerUrl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlexApiTest {
    @Test fun `server addresses get Plex's default scheme and port`() {
        assertEquals("http://plex.local:32400", normalizePlexServerUrl("plex.local"))
        assertEquals("http://192.168.1.20:32400", normalizePlexServerUrl("192.168.1.20"))
        assertEquals("https://plex.example.org", normalizePlexServerUrl("https://plex.example.org"))
        assertEquals("http://plex.local:4555", normalizePlexServerUrl("http://plex.local:4555/"))
    }

    @Test fun `only artist sections become music libraries`() = runTest {
        val transport = RecordingTransport(
            "/library/sections" to """{"MediaContainer":{"Directory":[
                {"key":"1","type":"artist","title":"Music"},
                {"key":"2","type":"movie","title":"Movies"}
            ]}}"""
        )
        val api = api(transport)
        val sections = api.musicSections()
        assertEquals(listOf("1"), sections.map { it.key })
    }

    @Test fun `tracks carry the media part and the liked filter`() = runTest {
        val transport = RecordingTransport(
            "/library/sections/1/all" to """{"MediaContainer":{"size":1,"totalSize":9,"offset":0,"Metadata":[
                {"ratingKey":"3","type":"track","title":"Bau auf","grandparentTitle":"DDR",
                 "grandparentRatingKey":"1","parentTitle":"Album","parentRatingKey":"2",
                 "parentThumb":"/library/metadata/2/thumb/1","duration":195792,
                 "Media":[{"Part":[{"key":"/library/parts/1/file.mp3"}]}]}
            ]}}"""
        )
        val api = api(transport)
        val page = api.tracks("1", 0, 20, likedOnly = true)
        val track = page.items.single()
        assertEquals(PlexKind.Track, track.kind)
        assertEquals("Bau auf", track.title)
        assertEquals("/library/parts/1/file.mp3", track.partKey)
        assertEquals("/library/metadata/2/thumb/1", track.artThumb)
        assertEquals(9, page.total)
        assertTrue(transport.urls.single().contains("userRating%3E=8"))
        assertEquals("http://plex.test:32400/library/parts/1/file.mp3", api.streamUrl(track.partKey!!))
    }

    @Test fun `playlists and their items are read with the audio filter`() = runTest {
        val transport = RecordingTransport(
            "/playlists?" to """{"MediaContainer":{"size":1,"totalSize":1,"Metadata":[
                {"ratingKey":"47","type":"playlist","title":"Mix","playlistType":"audio",
                 "leafCount":2,"composite":"/playlists/47/composite/1"}
            ]}}""",
            "/playlists/47/items" to """{"MediaContainer":{"size":1,"totalSize":2,"offset":0,"Metadata":[
                {"ratingKey":"3","type":"track","title":"Bau auf","playlistItemID":"9",
                 "parentThumb":"/library/metadata/2/thumb/1","duration":195792,
                 "Media":[{"Part":[{"key":"/library/parts/1/file.mp3"}]}]}
            ]}}"""
        )
        val api = api(transport)
        val playlists = api.playlists(0, 20)
        assertEquals("Mix", playlists.items.single().title)
        assertEquals(2, playlists.items.single().leafCount)
        val items = api.playlistItems("47", 0, 20)
        assertEquals("9", items.items.single().playlistItemId)
        assertTrue(transport.urls.first().contains("playlistType=audio"))
    }

    @Test fun `search partitions Plex hubs into tracks artists albums and playlists`() = runTest {
        val transport = RecordingTransport(
            "/hubs/search" to """{"MediaContainer":{"Hub":[
                {"type":"track","Metadata":[{"ratingKey":"3","type":"track","title":"Song"}]},
                {"type":"artist","Metadata":[{"ratingKey":"1","type":"artist","title":"Artist"}]},
                {"type":"album","Metadata":[{"ratingKey":"2","type":"album","title":"Album"}]},
                {"type":"playlist","Metadata":[{"ratingKey":"47","type":"playlist","title":"List"}]}
            ]}}"""
        )
        val result = api(transport).search("song", 10)
        assertEquals(listOf("Song"), result.tracks.map { it.title })
        assertEquals(listOf("Artist"), result.artists.map { it.title })
        assertEquals(listOf("Album"), result.albums.map { it.title })
        assertEquals(listOf("List"), result.playlists.map { it.title })
    }

    @Test fun `rating and playlist edits use Plex's endpoints`() = runTest {
        val transport = RecordingTransport(
            "/identity" to """{"MediaContainer":{"machineIdentifier":"machine-1"}}""",
            "/:/rate" to "",
            "/playlists/47/items" to """{"MediaContainer":{"size":1,"leafCountAdded":1}}"""
        )
        val api = api(transport)
        api.setRating("3", 10)
        api.addToPlaylist("47", "3")
        val calls = transport.calls.map { it.first to it.second.substringBefore('?') }
        assertEquals("PUT" to "http://plex.test:32400/:/rate", calls[0])
        assertEquals("GET" to "http://plex.test:32400/identity", calls[1])
        assertEquals("PUT" to "http://plex.test:32400/playlists/47/items", calls[2])
        assertTrue(transport.calls[0].second.contains("rating=10"))
        assertTrue(transport.calls[2].second.contains("uri=server%3A%2F%2Fmachine-1"))
    }

    @Test fun `an item without artwork has no art URL`() {
        val api = api(RecordingTransport())
        assertNull(api.artUrl(null))
        assertNull(api.artUrl(""))
        assertEquals("http://plex.test:32400/library/metadata/3/thumb/1", api.artUrl("/library/metadata/3/thumb/1"))
    }

    private fun api(transport: PlexTransport) = HttpPlexApi(
        PlexCredentials(
            serverUrl = "http://plex.test:32400",
            token = "token",
            clientIdentifier = "client",
            userName = "demo"
        ),
        transport
    )

    private class RecordingTransport(private vararg val responses: Pair<String, String>) : PlexTransport {
        val urls = mutableListOf<String>()
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun get(url: String): String {
            urls += url
            calls += "GET" to url
            return response(url)
        }

        override suspend fun send(method: String, url: String, body: String?): String? {
            urls += url
            calls += method to url
            return response(url)
        }

        private fun response(url: String): String {
            val match = responses.firstOrNull { url.contains(it.first) } ?: return "{}"
            return match.second
        }
    }
}
