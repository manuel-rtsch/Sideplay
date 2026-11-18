package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.auth.EncryptedSpotifyCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SpotifyAuth
import io.github.manuelrtsch.sidetrack.auth.SpotifyCredentials
import io.github.manuelrtsch.sidetrack.data.SpotifySession
import io.github.manuelrtsch.sidetrack.data.SpotifySettings
import io.github.manuelrtsch.sidetrack.data.SpotifySettingsStore
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import javax.crypto.AEADBadTagException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A keystore key can be invalidated by a ROM flash, a GSI install or a device restore. The SDK's
 * encrypted store then throws from `read()`, and every Sidetrack read happens while the UI
 * composes — so these guards are what keep a lost key from becoming a launch crash.
 */
@RunWith(RobolectricTestRunner::class)
class CredentialStoreGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `an unreadable credential file reads as signed out`() {
        val store = EncryptedSpotifyCredentialStore(context, "guard-test", ThrowingReadStore())

        assertNull(store.read())
        assertFalse(SpotifyAuth(store).hasCredentials())
    }

    @Test fun `a session built on an unreadable store starts signed out`() {
        val store = EncryptedSpotifyCredentialStore(context, "guard-test", ThrowingReadStore())

        val session = SpotifySession(SpotifyAuth(store))

        assertFalse(session.signedIn.value)
    }

    @Test fun `a credential write failure is reported as a persistence problem`() {
        val store = EncryptedSpotifyCredentialStore(context, "guard-test", ThrowingWriteStore())

        val failure = assertThrows(CredentialPersistenceException::class.java) {
            store.save(
                SpotifyCredentials(
                    accessToken = "access",
                    refreshToken = "refresh",
                    expiresAtMs = 1L,
                    scope = "streaming"
                )
            )
        }

        assertEquals(
            "Signed in, but this device could not store the session securely. " +
                "You will need to sign in again next time.",
            failure.message
        )
    }

    @Test fun `settings fall back to defaults when the store cannot be read`() {
        val store = SpotifySettingsStore(context, "guard-test", ThrowingReadStore())

        assertEquals(SpotifySettings(), store.read())
    }

    @Test fun `a settings write failure does not reach the caller`() {
        val store = SpotifySettingsStore(context, "guard-test", ThrowingWriteStore())

        store.write(SpotifySettings(normalization = true))
    }

    private class ThrowingReadStore : JsonStore {
        override fun read(): JSONObject = throw AEADBadTagException("key was invalidated")
        override fun write(value: JSONObject) = Unit
        override fun clear() = Unit
    }

    private class ThrowingWriteStore : JsonStore {
        override fun read(): JSONObject = JSONObject()
        override fun write(value: JSONObject) = throw EncryptedStoreException("keystore unavailable")
        override fun clear() = Unit
    }
}
