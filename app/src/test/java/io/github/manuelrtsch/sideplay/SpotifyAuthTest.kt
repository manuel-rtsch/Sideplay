package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.auth.SpotifyAuth
import io.github.manuelrtsch.sideplay.auth.SpotifyCredentialStore
import io.github.manuelrtsch.sideplay.auth.SpotifyCredentials
import io.github.manuelrtsch.sideplay.auth.SpotifyTokenClient
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthTest {
    private val now = 1_000_000L

    @Test fun `valid token is returned without refreshing`() = runTest {
        val store = MemoryStore(credentials(expiresAt = now + 180_000L))
        val tokens = FakeTokens()
        val auth = SpotifyAuth(store, tokens, nowMs = { now })

        assertEquals("old-access", auth.accessToken())
        assertEquals(0, tokens.refreshCalls)
    }

    @Test fun `expired token is refreshed and saved`() = runTest {
        val store = MemoryStore(credentials(expiresAt = now + 10_000L))
        val tokens = FakeTokens(refreshed = credentials(access = "new-access", expiresAt = now + 3_600_000L))
        val auth = SpotifyAuth(store, tokens, nowMs = { now })

        assertEquals("new-access", auth.accessToken())
        assertEquals(1, tokens.refreshCalls)
        assertEquals("new-access", store.saved?.accessToken)
    }

    @Test fun `refresh failure leaves the store untouched and returns null`() = runTest {
        val original = credentials(expiresAt = now + 10_000L)
        val store = MemoryStore(original)
        val auth = SpotifyAuth(store, FakeTokens(refreshFails = true), nowMs = { now })

        assertNull(auth.accessToken())
        assertEquals(original, store.saved)
    }

    @Test fun `sign in completes against the loopback callback`() = runTest {
        val store = MemoryStore(null)
        val tokens = object : SpotifyTokenClient {
            override suspend fun exchange(
                clientId: String,
                code: String,
                verifier: String,
                redirectUri: String
            ): SpotifyCredentials {
                assertEquals("test-code", code)
                assertTrue(clientId.isNotEmpty())
                assertTrue(verifier.isNotEmpty())
                return credentials(access = "fresh-access")
            }

            override suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials = credentials(access = "refreshed")
        }
        val auth = SpotifyAuth(store, tokens)
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val result = auth.signIn { url ->
            val params = URI(url).rawQuery.split('&').associate {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()) to
                    URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8.name())
            }
            val redirect = requireNotNull(params["redirect_uri"])
            val state = requireNotNull(params["state"])
            io.launch {
                delay(50)
                URL("$redirect?code=test-code&state=$state").openStream().use { it.readBytes() }
            }
        }

        assertEquals("fresh-access", result.accessToken)
        assertEquals("fresh-access", store.saved?.accessToken)
    }

    @Test fun `sign out clears credentials`() {
        val store = MemoryStore(credentials())
        val session = io.github.manuelrtsch.sideplay.data.SpotifySession(SpotifyAuth(store, FakeTokens()))

        session.signOut()

        assertNull(store.saved)
        assertTrue(!session.signedIn.value)
    }

    private fun credentials(
        access: String = "old-access",
        refresh: String? = "refresh",
        expiresAt: Long = now + 180_000L
    ) = SpotifyCredentials(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMs = expiresAt,
        scope = "streaming"
    )

    private class MemoryStore(var saved: SpotifyCredentials?) : SpotifyCredentialStore {
        override fun read(): SpotifyCredentials? = saved
        override fun save(credentials: SpotifyCredentials) {
            saved = credentials
        }

        override fun clear() {
            saved = null
        }
    }

    private class FakeTokens(
        private val refreshed: SpotifyCredentials? = null,
        private val refreshFails: Boolean = false
    ) : SpotifyTokenClient {
        var refreshCalls = 0

        override suspend fun exchange(
            clientId: String,
            code: String,
            verifier: String,
            redirectUri: String
        ): SpotifyCredentials = error("not used")

        override suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials {
            refreshCalls++
            if (refreshFails) error("refresh failed")
            return requireNotNull(refreshed)
        }
    }
}
