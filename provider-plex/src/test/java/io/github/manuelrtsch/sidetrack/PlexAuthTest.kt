package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.auth.PlexAccountClient
import io.github.manuelrtsch.sidetrack.auth.PlexAccountInfo
import io.github.manuelrtsch.sidetrack.auth.PlexAuth
import io.github.manuelrtsch.sidetrack.auth.PlexConnection
import io.github.manuelrtsch.sidetrack.auth.PlexCredentialStore
import io.github.manuelrtsch.sidetrack.auth.PlexPin
import io.github.manuelrtsch.sidetrack.auth.PlexResource
import io.github.manuelrtsch.sidetrack.auth.PlexServerIdentity
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlexAuthTest {
    private val store = MemoryStore()
    private val client = FakePlexAccountClient()

    private fun auth(timeoutMs: Long = 5_000L) = PlexAuth(
        store = PlexCredentialStore(store),
        clientIdentifier = "client-1",
        client = client,
        pollIntervalMs = 0,
        timeoutMs = timeoutMs
    )

    @After fun clearArtwork() {
        ArtworkAccess.clear()
    }

    @Test fun `a pin sign-in stores the token and discovers the local server over http`() = runTest {
        var opened: String? = null
        val auth = auth()
        val account = auth.signIn(AuthInput.Browser { opened = it })

        assertEquals(ServiceId.Plex, account.service)
        assertEquals("demo", account.userId)
        assertEquals("http://127.0.0.1:32400", account.serverUrl)
        assertTrue(opened!!.startsWith("https://app.plex.tv/auth#?"))
        assertTrue(opened!!.contains("client-1"))
        assertTrue(opened!!.contains("code=pin-code"))

        val saved = PlexCredentialStore(store).read()
        assertNotNull(saved)
        assertEquals("server-token", saved!!.token)
        assertEquals("machine-1", saved.machineIdentifier)
        assertEquals("server-token", ArtworkAccess.current()["X-Plex-Token"])
        assertTrue(client.identityCalls.contains("http://127.0.0.1:32400"))
    }

    @Test fun `restore reads the stored session and sign-out clears it`() = runTest {
        val auth = auth()
        auth.signIn(AuthInput.Browser { })
        val restored = auth.restore()
        assertEquals("demo", restored!!.userId)
        auth.signOut()
        assertFalse(auth.hasCredentials())
        assertEquals(0, store.read().length())
    }

    @Test fun `no linked server is reported instead of a half session`() = runTest {
        client.resourcesValue = emptyList()
        val auth = auth()
        val error = runCatching { auth.signIn(AuthInput.Browser { }) }.exceptionOrNull()
        assertTrue(error is ServiceException)
        assertEquals(ServiceFailure.NotConnected, (error as? ServiceException)?.failure ?: ServiceFailure.Unknown)
        assertFalse(auth.hasCredentials())
    }

    @Test fun `an unapproved pin times out without storing a token`() = runTest {
        client.tokenValue = null
        val auth = auth(timeoutMs = 0)
        val error = runCatching { auth.signIn(AuthInput.Browser { }) }.exceptionOrNull()
        assertTrue(error is ServiceException)
        assertFalse(auth.hasCredentials())
    }

    @Test fun `an unclaimed server is skipped`() = runTest {
        client.claimed = false
        val auth = auth()
        val error = runCatching { auth.signIn(AuthInput.Browser { }) }.exceptionOrNull()
        assertTrue(error is ServiceException)
        assertEquals(ServiceFailure.Network, (error as ServiceException).failure)
    }

    private class FakePlexAccountClient : PlexAccountClient {
        var tokenValue: String? = "token-1"
        var resourcesValue: List<PlexResource> = listOf(
            PlexResource(
                name = "Plex Demo",
                clientIdentifier = "machine-1",
                accessToken = "server-token",
                provides = "server",
                owned = true,
                connections = listOf(
                    PlexConnection(
                        uri = "https://bad.example:32400",
                        address = "127.0.0.1",
                        port = 32400,
                        protocol = "https",
                        local = true,
                        relay = false
                    )
                )
            )
        )
        var claimed = true
        val identityCalls = mutableListOf<String>()

        override suspend fun createPin(clientIdentifier: String) = PlexPin(1, "pin-code")

        override suspend fun pinToken(clientIdentifier: String, pinId: Long): String? = tokenValue

        override suspend fun resources(clientIdentifier: String, token: String): List<PlexResource> = resourcesValue

        override suspend fun identity(serverUrl: String, token: String, clientIdentifier: String): PlexServerIdentity? {
            identityCalls += serverUrl
            if (!serverUrl.startsWith("http://127.0.0.1")) return null
            return PlexServerIdentity("machine-1", claimed, "1.43")
        }

        override suspend fun account(clientIdentifier: String, token: String) = PlexAccountInfo("demo")
    }

    private class MemoryStore : JsonStore {
        private var value = JSONObject()

        override fun read(): JSONObject = JSONObject(value.toString())

        override fun write(value: JSONObject) {
            this.value = JSONObject(value.toString())
        }

        override fun clear() {
            value = JSONObject()
        }
    }
}
