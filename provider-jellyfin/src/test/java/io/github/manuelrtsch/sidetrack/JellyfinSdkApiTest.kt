package io.github.manuelrtsch.sidetrack

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentials
import io.github.manuelrtsch.sidetrack.data.JellyfinKind
import io.github.manuelrtsch.sidetrack.data.JellyfinPlaybackReport
import io.github.manuelrtsch.sidetrack.data.SdkJellyfinApi
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The SDK-backed seam against a real loopback HTTP server. This pins the request parameters the
 * server actually receives, the generated-DTO mapping, and the error translation; the SDK's own
 * serializer is exercised rather than replaced with a hand-written fake.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinSdkApiTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = FakeJellyfin()

    @Before fun deviceId() {
        // The SDK's AndroidDevice reads this; Robolectric leaves it null by default.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "sidetrack-test-device")
    }

    @After fun stop() = server.stop()

    private fun api(serverUrl: String = server.base): SdkJellyfinApi = SdkJellyfinApi(
        context,
        JellyfinCredentials(serverUrl, "token", "MediaBrowser Token=token", USER_ID, "user")
    )

    // --- item queries --------------------------------------------------------------------------

    @Test fun `library queries ask for the right kinds and map the page`() = runBlocking {
        server.reply { req ->
            if (req.path == "/Artists") {
                FakeJellyfin.Res(200, page(ARTIST))
            } else {
                FakeJellyfin.Res(200, page(ITEM))
            }
        }
        val api = api()

        val liked = api.likedSongs(5, 10)
        val playlists = api.playlists(0, 20)
        val albums = api.albums(0, 20)
        val artists = api.favoriteArtists(0, 20)
        val tracks = api.albumTracks(ALBUM_ID, 0, 20)
        val byArtist = api.artistAlbums(ARTIST_ID, 0, 20)
        val top = api.artistTopTracks(ARTIST_ID, 0, 20)
        val resume = api.resumeItems(20)

        listOf(liked, playlists, albums, tracks, byArtist, top, resume).forEach {
            assertEquals(1, it.total)
            assertEquals(5, it.startIndex)
            assertEquals("Night Signals", it.items.single().name)
            assertEquals(JellyfinKind.Audio, it.items.single().kind)
        }
        assertEquals(JellyfinKind.Artist, artists.items.single().kind)

        val requests = server.requests.toList()
        val itemsQuery = { index: Int -> requests[index].query.orEmpty() }
        assertTrue(itemsQuery(0).contains("filters=IsFavorite"))
        assertTrue(itemsQuery(0).contains("includeItemTypes=Audio"))
        assertTrue(itemsQuery(0).contains("startIndex=5"))
        assertTrue(itemsQuery(1).contains("includeItemTypes=Playlist"))
        assertTrue(itemsQuery(2).contains("includeItemTypes=MusicAlbum"))
        assertTrue(requests[3].path == "/Artists")
        assertTrue(itemsQuery(3).contains("isFavorite=true"))
        assertTrue(itemsQuery(4).contains("parentId=$ALBUM_ID"))
        assertTrue(itemsQuery(4).contains("recursive=false"))
        assertTrue(itemsQuery(5).contains("albumArtistIds=$ARTIST_ID"))
        assertTrue(itemsQuery(6).contains("artistIds=$ARTIST_ID"))
        assertTrue(itemsQuery(6).contains("sortBy=PlayCount"))
        assertTrue(itemsQuery(6).contains("sortOrder=Descending"))
        assertTrue(requests[7].path.endsWith("/Resume"))
        assertTrue(itemsQuery(7).contains("mediaTypes=Audio"))
    }

    @Test fun `an item maps every field the UI reads and a 404 becomes null`() = runBlocking {
        server.reply { FakeJellyfin.Res(200, ITEM) }
        val api = api()

        val item = api.item(ITEM_ID)!!

        assertEquals(ITEM_ID, item.id)
        assertEquals("Night Signals", item.name)
        assertEquals(JellyfinKind.Audio, item.kind)
        assertEquals(ALBUM_ID, item.albumId)
        assertEquals("Signals", item.album)
        assertEquals("Aurora", item.albumArtist)
        assertEquals(ARTIST_ID, item.albumArtistId)
        assertEquals(listOf(ARTIST_ID), item.artistIds)
        assertEquals(2_134_000_000L, item.runTimeTicks)
        assertEquals(2024, item.productionYear)
        assertEquals(12, item.childCount)
        assertEquals(13, item.recursiveItemCount)
        assertEquals("tag", item.imageTag)
        assertEquals(true, item.isFavorite)
        assertEquals(120_000_000L, item.playbackPositionTicks)
        assertTrue(server.requests.single().path.endsWith("/$ITEM_ID"))

        server.reply { FakeJellyfin.Res(404, """{"error":"missing"}""") }
        assertNull(api.item(ITEM_ID))
    }

    @Test fun `an id Sidetrack cannot parse is a track failure, not a generic one`() = runBlocking {
        val error = runCatching { api().item("not-a-uuid") }.exceptionOrNull() as ServiceException

        assertEquals(ServiceFailure.TrackUnavailable, error.failure)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `library errors keep their categories`() = runBlocking {
        server.reply { FakeJellyfin.Res(401, "{}") }
        assertEquals(
            ServiceFailure.AuthenticationExpired,
            (runCatching { api().likedSongs(0, 20) }.exceptionOrNull() as ServiceException).failure
        )

        server.reply { FakeJellyfin.Res(500, "{}") }
        assertEquals(
            ServiceFailure.Unavailable,
            (runCatching { api().likedSongs(0, 20) }.exceptionOrNull() as ServiceException).failure
        )
    }

    // --- playlists -----------------------------------------------------------------------------

    @Test fun `playlist detail carries shares entries and editability`() = runBlocking {
        server.reply { req ->
            when {
                req.path.startsWith("/Playlists/") && req.path.endsWith("/Items") ->
                    FakeJellyfin.Res(200, page("""{"Id":"$ITEM_ID","Name":"Entry","Type":"Audio","PlaylistItemId":"entry-1"}"""))

                req.path.startsWith("/Playlists/") -> FakeJellyfin.Res(
                    200,
                    """{"OpenAccess":false,"ItemIds":["$ITEM_ID"],"Shares":[{"UserId":"$USER_ID","CanEdit":true}]}"""
                )

                else -> FakeJellyfin.Res(200, """{"Id":"$PLAYLIST_ID","Name":"Late Night","Type":"Playlist"}""")
            }
        }
        val api = api()

        val playlist = api.playlist(PLAYLIST_ID)!!

        assertEquals("Late Night", playlist.item.name)
        assertEquals(JellyfinKind.Playlist, playlist.item.kind)
        assertEquals(listOf(ITEM_ID), playlist.itemIds)
        assertTrue(playlist.editable)
        assertEquals(USER_ID, playlist.ownerId)

        val entries = api.playlistItems(PLAYLIST_ID, 0, 20)
        assertEquals("entry-1", entries.items.single().playlistItemId)
    }

    @Test fun `an open playlist is editable without a share and missing entries fall back`() = runBlocking {
        server.reply { req ->
            when {
                req.path.startsWith("/Playlists/") && req.path.endsWith("/Items") ->
                    FakeJellyfin.Res(200, page("""{"Id":"$ALBUM_ID","Name":"Other","Type":"Audio","PlaylistItemId":"entry-9"}"""))

                req.path.startsWith("/Playlists/") -> FakeJellyfin.Res(200, """{"OpenAccess":true,"Shares":[],"ItemIds":[]}""")

                else -> FakeJellyfin.Res(200, """{"Id":"$PLAYLIST_ID","Name":"Late Night","Type":"Playlist"}""")
            }
        }
        val api = api()

        val playlist = api.playlist(PLAYLIST_ID)!!
        assertTrue(playlist.editable)
        assertTrue(playlist.itemIds.isEmpty())

        // The entry id in the scan is for a different media item, so the raw id is used.
        api.removeFromPlaylist(PLAYLIST_ID, ITEM_ID)
        assertTrue(server.requests.last().method == "DELETE")
        assertTrue(server.requests.last().query.orEmpty().contains("entryIds=$ITEM_ID"))
    }

    @Test fun `a deleted playlist is null and creating one returns the server id`() = runBlocking {
        server.reply { FakeJellyfin.Res(404, "{}") }
        assertNull(api().playlist(PLAYLIST_ID))

        server.reply { req ->
            if (req.method == "POST" && req.path == "/Playlists") {
                FakeJellyfin.Res(200, """{"Id":"new-playlist"}""")
            } else {
                FakeJellyfin.Res(404, "{}")
            }
        }
        val created = api().createPlaylist("Road Trip")
        assertEquals("new-playlist", created)
        val post = server.requests.last()
        assertEquals("POST", post.method)
        assertTrue(post.body.contains("Road Trip"))
        assertTrue(post.body.contains("Audio"))
    }

    @Test fun `adding to a playlist reports the size before the append`() = runBlocking {
        server.reply { req ->
            when {
                req.method == "GET" -> FakeJellyfin.Res(200, """{"OpenAccess":true,"Shares":[],"ItemIds":["$ITEM_ID","$ALBUM_ID"]}""")
                else -> FakeJellyfin.Res(200, "{}")
            }
        }
        val api = api()

        assertEquals(2, api.addToPlaylist(PLAYLIST_ID, ITEM_ID))

        val add = server.requests.last()
        assertEquals("POST", add.method)
        assertTrue(add.query.orEmpty().contains("ids=$ITEM_ID"))
        assertTrue(add.query.orEmpty().contains("userId=$USER_ID"))
    }

    // --- search, mixes and user data -----------------------------------------------------------

    @Test fun `search sends the selected kinds and only asks for artists when scoped`() = runBlocking {
        server.reply {
            FakeJellyfin.Res(
                200,
                """{"SearchHints":[{"ItemId":"$ITEM_ID","Id":"$ITEM_ID","Name":"Night Signals",
                    "Type":"Audio","AlbumId":"$ALBUM_ID","Album":"Signals","AlbumArtist":"Aurora",
                    "Artists":["Aurora"],"RunTimeTicks":2134000000,"ProductionYear":2024,
                    "PrimaryImageTag":"hint","ThumbImageItemId":"$ALBUM_ID"}],"TotalRecordCount":1}"""
            )
        }
        val api = api()

        val result = api.search(
            "night",
            setOf(SearchScope.Tracks, SearchScope.Artists, SearchScope.Playlists),
            0,
            10
        )

        val hint = result.hints.single()
        assertEquals(ITEM_ID, hint.itemId)
        assertEquals(JellyfinKind.Audio, hint.kind)
        assertEquals("Signals", hint.album)
        assertEquals("hint", hint.imageTag)
        assertEquals(ALBUM_ID, hint.imageItemId)
        assertEquals(1, result.total)

        val request = server.requests.single()
        assertEquals("/Search/Hints", request.path)
        assertTrue(request.query.orEmpty().contains("searchTerm=night"))
        assertTrue(request.query.orEmpty().contains("includeArtists=true"))
        assertTrue(request.query.orEmpty().contains("includeItemTypes=Audio"))
        assertTrue(request.query.orEmpty().contains("MusicArtist"))
        assertTrue(request.query.orEmpty().contains("Playlist"))

        server.requests.clear()
        api.search("night", setOf(SearchScope.Albums), 0, 10)
        assertTrue(server.requests.single().query.orEmpty().contains("includeArtists=false"))

        server.requests.clear()
        assertTrue(api.search("night", emptySet(), 0, 10).hints.isEmpty())
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `instant mix maps a page and user data maps or becomes null`() = runBlocking {
        server.reply { req ->
            when {
                req.path.endsWith("/UserData") -> FakeJellyfin.Res(
                    200,
                    """{"ItemId":"$ITEM_ID","IsFavorite":true,"PlaybackPositionTicks":90000000,"Played":true,"PlayCount":1,"Key":"k"}"""
                )

                else -> FakeJellyfin.Res(200, page(ITEM))
            }
        }
        val api = api()

        val mix = api.instantMix(ITEM_ID, MediaType.Track, 25)
        assertEquals("Night Signals", mix.items.single().name)
        assertTrue(server.requests.last().path.endsWith("/InstantMix"))
        assertTrue(server.requests.last().query.orEmpty().contains("limit=25"))

        val data = api.userData(ITEM_ID)!!
        assertEquals(ITEM_ID, data.itemId)
        assertTrue(data.isFavorite == true)
        assertEquals(90_000_000L, data.playbackPositionTicks)

        server.reply { FakeJellyfin.Res(404, "{}") }
        assertNull(api.userData(ITEM_ID))
    }

    @Test fun `favorite played and capability calls reach their endpoints`() = runBlocking {
        server.reply { req ->
            if (req.path.contains("/Sessions/")) {
                FakeJellyfin.Res(200, "{}")
            } else {
                FakeJellyfin.Res(
                    200,
                    """{"ItemId":"$ITEM_ID","IsFavorite":true,"PlaybackPositionTicks":0,
                        "Played":true,"PlayCount":1,"Key":"k"}"""
                )
            }
        }
        val api = api()

        api.setFavorite(ITEM_ID, true)
        assertEquals("POST", server.requests.last().method)
        assertTrue(server.requests.last().path.endsWith("/UserFavoriteItems/$ITEM_ID"))

        api.setFavorite(ITEM_ID, false)
        assertEquals("DELETE", server.requests.last().method)

        api.markPlayed(ITEM_ID)
        assertTrue(server.requests.last().path.endsWith("/UserPlayedItems/$ITEM_ID"))

        api.announceCapabilities()
        assertTrue(server.requests.last().path.contains("/Sessions/Capabilities"))

        val report = JellyfinPlaybackReport(ITEM_ID, 12_000L, "src", "session", paused = true, directPlay = false)
        api.reportStart(report)
        assertTrue(server.requests.last().path.endsWith("/Sessions/Playing"))
        assertTrue(server.requests.last().body.contains("\"PositionTicks\":120000000"))

        api.reportProgress(report)
        assertTrue(server.requests.last().path.endsWith("/Sessions/Playing/Progress"))

        api.reportStopped(report, failed = true)
        val stopped = server.requests.last()
        assertTrue(stopped.path.endsWith("/Sessions/Playing/Stopped"))
        assertTrue(stopped.body.contains("\"Failed\":true"))
    }

    // --- playback info and artwork -------------------------------------------------------------

    @Test fun `a transcoding source becomes an absolute url with its headers`() = runBlocking {
        server.reply {
            FakeJellyfin.Res(
                200,
                """{"MediaSources":[${source(supportsDirectPlay = false, transcodingUrl = "/Audio/$ITEM_ID/stream.mp3?x=1")}],
                    "PlaySessionId":"session"}"""
            )
        }

        val info = api().playback(ITEM_ID, 96_000)

        assertTrue(info.url.startsWith(server.base))
        assertTrue(info.url.endsWith("/Audio/$ITEM_ID/stream.mp3?x=1"))
        assertEquals("session", info.playSessionId)
        assertEquals("src", info.mediaSourceId)
        assertFalse(info.directPlay)
        assertEquals("1", info.headers["X-Test"])
        assertEquals("MediaBrowser Token=token", info.headers["Authorization"])

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.path.endsWith("/PlaybackInfo"))
        assertTrue(request.body.contains("\"MaxStreamingBitrate\":96000"))
    }

    @Test fun `an absolute transcoding url is left alone`() = runBlocking {
        server.reply {
            FakeJellyfin.Res(
                200,
                """{"MediaSources":[${source(transcodingUrl = "https://cdn.example/stream")}],"PlaySessionId":"s"}"""
            )
        }

        val info = api().playback(ITEM_ID, null)

        assertEquals("https://cdn.example/stream", info.url)
        assertFalse(info.directPlay)
    }

    @Test fun `direct play streams the static file instead of the universal endpoint`() = runBlocking {
        server.reply {
            FakeJellyfin.Res(
                200,
                """{"MediaSources":[${source()}],"PlaySessionId":"s"}"""
            )
        }

        val info = api().playback(ITEM_ID, 320_000)

        assertTrue(info.url.contains("/Audio/$ITEM_ID/stream"))
        assertTrue(info.url.contains("static=true"))
        assertTrue(info.directPlay)
        assertEquals("src", info.mediaSourceId)
    }

    @Test fun `no media source still yields a playable fallback`() = runBlocking {
        server.reply { FakeJellyfin.Res(200, """{"MediaSources":[],"PlaySessionId":"s"}""") }

        val info = api().playback(ITEM_ID, null)

        assertTrue(info.url.contains("/Audio/$ITEM_ID/stream"))
        assertNull(info.mediaSourceId)
        assertFalse(info.directPlay)
    }

    @Test fun `image urls carry the item the tag and the height`() {
        val url = api().imageUrl(ITEM_ID, "tag", maxHeight = 300)

        assertTrue(url.contains("/Items/$ITEM_ID/Images/Primary"))
        assertTrue(url.contains("maxHeight=300"))
        assertTrue(url.contains("tag=tag"))
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun page(item: String): String = """{"Items":[$item],"TotalRecordCount":1,"StartIndex":5}"""

    private fun source(
        supportsDirectPlay: Boolean = true,
        transcodingUrl: String? = null
    ): String = buildString {
        append(
            """{"Id":"src","Protocol":"File","Type":"Default","IsRemote":false,
               "ReadAtNativeFramerate":false,"IgnoreDts":false,"IgnoreIndex":false,
               "GenPtsInput":false,"SupportsTranscoding":true,"SupportsDirectStream":true,
               "SupportsDirectPlay":$supportsDirectPlay,"IsInfiniteStream":false,
               "RequiresOpening":false,"RequiresClosing":false,"RequiresLooping":false,
               "SupportsProbing":false,"TranscodingSubProtocol":"http","HasSegments":false,
               "RequiredHttpHeaders":{"X-Test":"1"}"""
        )
        if (transcodingUrl != null) append(""","TranscodingUrl":"$transcodingUrl"""")
        append("}")
    }

    private class FakeJellyfin {
        data class Req(
            val method: String,
            val path: String,
            val query: String?,
            val body: String
        )

        data class Res(val status: Int, val body: String)

        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
        private var responder: (Req) -> Res = { Res(200, "{}") }

        val base: String get() = "http://127.0.0.1:${http.address.port}"

        init {
            http.createContext("/") { exchange ->
                val uri = exchange.requestURI
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val request = Req(exchange.requestMethod, uri.path, uri.query, body)
                requests += request
                val response = responder(request)
                val bytes = response.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                if (response.status == 204) {
                    exchange.sendResponseHeaders(204, -1)
                } else {
                    exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            http.start()
        }

        fun reply(block: (Req) -> Res) {
            responder = block
        }

        fun stop() = http.stop(0)
    }

    private companion object {
        const val USER_ID = "11111111-1111-1111-1111-111111111111"
        const val ITEM_ID = "22222222-2222-2222-2222-222222222222"
        const val ALBUM_ID = "33333333-3333-3333-3333-333333333333"
        const val ARTIST_ID = "44444444-4444-4444-4444-444444444444"
        const val PLAYLIST_ID = "55555555-5555-5555-5555-555555555555"

        const val ITEM = """
            {"Id":"$ITEM_ID","Name":"Night Signals","Type":"Audio",
             "AlbumId":"$ALBUM_ID","Album":"Signals","AlbumArtist":"Aurora",
             "AlbumArtists":[{"Name":"Aurora","Id":"$ARTIST_ID"}],
             "Artists":["Aurora"],"ArtistItems":[{"Name":"Aurora","Id":"$ARTIST_ID"}],
             "RunTimeTicks":2134000000,"ProductionYear":2024,
             "ImageTags":{"Primary":"tag"},
             "ChildCount":12,"RecursiveItemCount":13,
             "UserData":{"ItemId":"$ITEM_ID","PlaybackPositionTicks":120000000,
                          "PlayCount":3,"IsFavorite":true,"Played":true,"Key":"k"}}
        """

        const val ARTIST = """
            {"Id":"$ARTIST_ID","Name":"Aurora","Type":"MusicArtist",
             "ImageTags":{"Primary":"artist-tag"}}
        """
    }
}
