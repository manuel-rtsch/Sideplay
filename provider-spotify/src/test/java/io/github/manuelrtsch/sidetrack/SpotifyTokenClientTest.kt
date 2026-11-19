package io.github.manuelrtsch.sidetrack

import com.sun.net.httpserver.HttpServer
import io.github.manuelrtsch.sidetrack.auth.HttpSpotifyTokenClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Token exchange and refresh are where Spotify auth fails silently: a rotated refresh token that is
 * dropped, a 400 mapped to a blank message, or an expiry that is computed from the wrong field all
 * look like "the app logged itself out" to a user. These tests drive the real HTTP client against a
 * local endpoint and pin the request form and every failure message.
 */
@RunWith(RobolectricTestRunner::class)
class SpotifyTokenClientTest {
    private var server: HttpServer? = null

    @After fun stopServer() {
        server?.stop(0)
    }

    /** Starts a one-request endpoint and returns the client pointed at it. */
    private fun client(
        status: Int,
        body: String,
        onRequest: (headers: Map<String, List<String>>, form: Map<String, String>) -> Unit = { _, _ -> }
    ): HttpSpotifyTokenClient {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/token") { exchange ->
            val text = exchange.requestBody.bufferedReader().use { it.readText() }
            val form = text.split("&").filter { it.isNotEmpty() }.associate { part ->
                val index = part.indexOf('=')
                URLDecoder.decode(part.substring(0, index), "UTF-8") to
                    URLDecoder.decode(part.substring(index + 1), "UTF-8")
            }
            onRequest(exchange.requestHeaders.mapValues { it.value.toList() }, form)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return HttpSpotifyTokenClient("http://127.0.0.1:${http.address.port}/token")
    }

    @Test fun `the authorization-code exchange sends the PKCE verifier and form-encodes it`() = runTest {
        var form: Map<String, String> = emptyMap()
        var contentType: String? = null
        val client = client(
            200,
            """{"access_token":"access","refresh_token":"refresh","expires_in":3600,
                "scope":"streaming user-read-private","token_type":"Bearer"}"""
        ) { headers, sent ->
            contentType = headers["Content-type"]?.firstOrNull()
            form = sent
        }

        val credentials = client.exchange(
            clientId = "client id",
            code = "code/with+chars",
            verifier = "verifier-=",
            redirectUri = "sidetrack://callback"
        )

        assertEquals("authorization_code", form["grant_type"])
        assertEquals("code/with+chars", form["code"])
        assertEquals("verifier-=", form["code_verifier"])
        assertEquals("sidetrack://callback", form["redirect_uri"])
        assertEquals("client id", form["client_id"])
        assertTrue(contentType!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("access", credentials.accessToken)
        assertEquals("refresh", credentials.refreshToken)
        assertEquals("streaming user-read-private", credentials.scope)
        assertEquals("Bearer", credentials.tokenType)
        assertTrue(credentials.expiresAtMs > System.currentTimeMillis() + 3_500_000L)
    }

    @Test fun `a refresh without a rotated token keeps the previous one`() = runTest {
        var form: Map<String, String> = emptyMap()
        val client = client(
            200,
            """{"access_token":"new-access","expires_in":1800,"token_type":"Bearer"}"""
        ) { _, sent -> form = sent }

        val credentials = client.refresh("client", "old-refresh")

        assertEquals("refresh_token", form["grant_type"])
        assertEquals("old-refresh", form["refresh_token"])
        assertEquals("client", form["client_id"])
        assertEquals("new-access", credentials.accessToken)
        assertEquals("old-refresh", credentials.refreshToken)
        assertEquals("Bearer", credentials.tokenType)
    }

    @Test fun `a rotated refresh token replaces the old one`() = runTest {
        val client = client(
            200,
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":1800}"""
        )

        assertEquals("new-refresh", client.refresh("client", "old-refresh").refreshToken)
    }

    @Test fun `a missing token type falls back to Bearer and a missing scope is empty`() = runTest {
        val client = client(200, """{"access_token":"access","expires_in":0}""")

        val credentials = client.exchange("client", "code", "verifier", "sidetrack://callback")

        assertEquals("Bearer", credentials.tokenType)
        assertEquals("", credentials.scope)
        assertEquals(null, credentials.refreshToken)
    }

    @Test fun `an error response surfaces Spotify's own description`() = runTest {
        val client = client(
            400,
            """{"error":"invalid_grant","error_description":"Invalid authorization code"}"""
        )

        val failure = runCatching {
            client.exchange("client", "code", "verifier", "sidetrack://callback")
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("Invalid authorization code", failure!!.message)
    }

    @Test fun `an error response without a description names the HTTP status`() = runTest {
        val client = client(503, """{"error":"temporarily_unavailable"}""")

        val failure = runCatching { client.refresh("client", "refresh") }.exceptionOrNull()

        assertEquals("Spotify token request failed (HTTP 503)", failure!!.message)
    }

    @Test fun `an unreadable body is reported instead of parsed as an empty session`() = runTest {
        val client = client(200, "<html>gateway</html>")

        val failure = runCatching {
            client.exchange("client", "code", "verifier", "sidetrack://callback")
        }.exceptionOrNull()

        assertEquals(
            "Spotify returned an unreadable token response (HTTP 200)",
            failure!!.message
        )
    }

    @Test fun `a successful response without an access token is a failure`() = runTest {
        val client = client(200, """{"token_type":"Bearer","expires_in":3600}""")

        val failure = runCatching { client.refresh("client", "refresh") }.exceptionOrNull()

        assertEquals("Spotify did not return an access token", failure!!.message)
    }
}
