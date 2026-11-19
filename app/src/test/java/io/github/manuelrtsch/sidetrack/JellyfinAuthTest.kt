package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuth
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuthenticator
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentialStore
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentials
import io.github.manuelrtsch.sidetrack.auth.jellyfinFailure
import io.github.manuelrtsch.sidetrack.auth.normalizeJellyfinServerUrl
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Jellyfin keeps the token, not the password, so these tests pin what crosses the store and what
 * the artwork loader gets. The SDK sits behind [JellyfinAuthenticator]: no server, no `Context`
 * and no Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinAuthTest {
    private val backing = InMemoryStore()
    private val store = JellyfinCredentialStore(backing)

    @After
    fun clearArtwork() = ArtworkAccess.clear()

    @Test
    fun `sign-in stores the token and returns the account`() = runTest {
        val auth = JellyfinAuth(store, FakeAuthenticator())

        val account = auth.signIn(AuthInput.Password(SERVER, "listener", "secret"))

        assertEquals(ServiceId.Jellyfin, account.service)
        assertEquals("user-1", account.userId)
        assertEquals("listener", account.displayName)
        assertEquals(SERVER, account.serverUrl)

        val stored = store.read()!!
        assertEquals("token-abc", stored.accessToken)
        // The password is needed once, for the authentication call: it must not be persisted.
        assertFalse(backing.lastWritten.toString().contains("secret"))
    }

    @Test
    fun `sign-in installs the authorization header for artwork`() = runTest {
        val auth = JellyfinAuth(store, FakeAuthenticator())

        auth.signIn(AuthInput.Password(SERVER, "listener", "secret"))

        assertEquals(mapOf("Authorization" to HEADER), ArtworkAccess.current())
    }

    @Test
    fun `a trailing slash and a missing scheme are normalized`() = runTest {
        val auth = JellyfinAuth(store, FakeAuthenticator())

        auth.signIn(AuthInput.Password("music.example.org:8096/", "listener", "secret"))

        assertEquals("http://music.example.org:8096", store.read()!!.serverUrl)
    }

    @Test
    fun `an unusable address is rejected before a request is made`() {
        val failure = runCatching { normalizeJellyfinServerUrl("ftp://music.example.org") }
            .exceptionOrNull() as ServiceException

        assertEquals(ServiceFailure.NotConnected, failure.failure)
    }

    @Test
    fun `blank credentials are rejected before a request is made`() = runTest {
        val authenticator = FakeAuthenticator()
        val auth = JellyfinAuth(store, authenticator)

        val failure = runCatching { auth.signIn(AuthInput.Password(SERVER, " ", "secret")) }
            .exceptionOrNull() as ServiceException

        assertEquals(ServiceFailure.NotConnected, failure.failure)
        assertEquals(0, authenticator.calls)
    }

    @Test
    fun `restore reads the stored session and reinstalls the artwork header`() = runTest {
        store.save(credentials())
        ArtworkAccess.clear()

        val account = JellyfinAuth(store, FakeAuthenticator()).restore()

        assertEquals("user-1", account?.userId)
        assertEquals(mapOf("Authorization" to HEADER), ArtworkAccess.current())
    }

    @Test
    fun `sign-out forgets the session and the artwork header`() = runTest {
        val auth = JellyfinAuth(store, FakeAuthenticator())
        auth.signIn(AuthInput.Password(SERVER, "listener", "secret"))

        auth.signOut()

        assertNull(store.read())
        assertNull(auth.restore())
        assertTrue(ArtworkAccess.current().isEmpty())
    }

    @Test
    fun `a store that cannot write reports a persistence failure`() = runTest {
        val store = JellyfinCredentialStore(ThrowingWriteStore())

        val failure = runCatching {
            JellyfinAuth(store, FakeAuthenticator()).signIn(AuthInput.Password(SERVER, "listener", "secret"))
        }.exceptionOrNull()

        assertTrue(failure is CredentialPersistenceException)
        assertEquals(
            "Signed in, but this device could not store the session securely. " +
                "You will need to sign in again next time.",
            failure!!.message
        )
    }

    @Test
    fun `a rejected password maps to an expired session`() {
        assertEquals(ServiceFailure.AuthenticationExpired, jellyfinFailure(InvalidStatusException(401)).failure)
    }

    @Test
    fun `a missing server maps to not connected`() {
        assertEquals(ServiceFailure.NotConnected, jellyfinFailure(InvalidStatusException(404)).failure)
    }

    @Test
    fun `a server error maps to unavailable`() {
        assertEquals(ServiceFailure.Unavailable, jellyfinFailure(InvalidStatusException(503)).failure)
    }

    @Test
    fun `timeouts and certificates map to the network bucket`() {
        assertEquals(ServiceFailure.Network, jellyfinFailure(TimeoutException()).failure)
        assertEquals(ServiceFailure.Network, jellyfinFailure(SecureConnectionException()).failure)
    }

    private class FakeAuthenticator : JellyfinAuthenticator {
        var calls = 0

        override suspend fun signIn(serverUrl: String, username: String, password: String): JellyfinCredentials {
            calls++
            return JellyfinCredentials(serverUrl, "token-abc", HEADER, "user-1", "listener")
        }
    }

    private class InMemoryStore : JsonStore {
        private var value: JSONObject? = null
        var lastWritten: JSONObject = JSONObject()

        override fun read(): JSONObject = value ?: JSONObject()

        override fun write(value: JSONObject) {
            this.value = value
            lastWritten = value
        }

        override fun clear() {
            value = null
        }
    }

    private class ThrowingWriteStore : JsonStore {
        override fun read(): JSONObject = JSONObject()

        override fun write(value: JSONObject) = throw EncryptedStoreException("keystore unavailable")

        override fun clear() = Unit
    }

    private fun credentials() = JellyfinCredentials(SERVER, "token-abc", HEADER, "user-1", "listener")

    private companion object {
        const val SERVER = "http://music.example.org:8096"
        const val HEADER =
            "MediaBrowser Client=\"Sidetrack\", Device=\"Pixel\", DeviceId=\"device-1\", " +
                "Version=\"0.0.0\", Token=\"token-abc\""
    }
}
