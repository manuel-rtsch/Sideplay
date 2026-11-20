package io.github.manuelrtsch.sidetrack

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentials
import io.github.manuelrtsch.sidetrack.data.SdkJellyfinApi
import java.net.InetSocketAddress
import java.util.Collections
import org.junit.After
import org.junit.Before
import org.junit.Test

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

/**
 * The loopback fixture shared by the SDK seam tests: a Robolectric context, a fake Jellyfin server,
 * the item and source payload builders, and the credentials the calls need.
 */
abstract class JellyfinSdkApiTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val server = FakeJellyfin()

    @Before fun deviceId() {
        // The SDK's AndroidDevice reads this; Robolectric leaves it null by default.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "sidetrack-test-device")
    }

    @After fun stop() = server.stop()

    protected fun api(serverUrl: String = server.base): SdkJellyfinApi = SdkJellyfinApi(
        context,
        JellyfinCredentials(serverUrl, "token", "MediaBrowser Token=token", USER_ID, "user")
    )

    // --- helpers -------------------------------------------------------------------------------

    protected fun page(item: String): String = """{"Items":[$item],"TotalRecordCount":1,"StartIndex":5}"""

    protected fun source(
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

    protected class FakeJellyfin {
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
}
