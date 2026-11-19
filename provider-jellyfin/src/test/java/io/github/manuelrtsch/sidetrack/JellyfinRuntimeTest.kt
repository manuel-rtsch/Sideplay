package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuth
import io.github.manuelrtsch.sidetrack.auth.JellyfinAuthenticator
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentialStore
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentials
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.FilePositionStore
import io.github.manuelrtsch.sidetrack.data.JellyfinKind
import io.github.manuelrtsch.sidetrack.data.JellyfinProvider
import io.github.manuelrtsch.sidetrack.data.JellyfinRuntime
import io.github.manuelrtsch.sidetrack.data.JellyfinService
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.data.SignInKind
import io.github.manuelrtsch.sidetrack.data.toMediaType
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Jellyfin runtime owns the account-scoped state the app reads before a server call: sign-in
 * state, settings, queue, history and resume point. The SDK mapping and auth are covered by
 * [JellyfinApiTest] and [JellyfinAuthTest]; these tests keep the runtime from mixing accounts or
 * losing state when the runtime is rebuilt.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val credentials = JellyfinCredentials(
        serverUrl = "http://127.0.0.1:1",
        accessToken = "token",
        authorizationHeader = "MediaBrowser Token=\"token\"",
        userId = "user-1",
        userName = "joe"
    )

    private fun runtime(
        store: JsonStore = MemoryStore(),
        authenticator: JellyfinAuthenticator = JellyfinAuthenticator { _, _, _ -> credentials }
    ) = JellyfinRuntime(context, JellyfinAuth(JellyfinCredentialStore(store), authenticator))

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artist = "Aurora",
        artistId = "artist-1",
        albumId = "album-1",
        artRes = 0,
        durationMs = 180_000L,
        service = ServiceId.Jellyfin
    )

    private fun files() = listOf(
        "sidetracks-jellyfin-queue.bin",
        "sidetracks-jellyfin-history.bin",
        "sidetracks-jellyfin-resume.bin"
    ).map { File(context.filesDir, it) }

    @org.junit.Before fun clearState() {
        files().forEach { it.delete() }
    }

    @Test fun `an empty credential store starts signed out with empty account state`() {
        val runtime = runtime()

        assertFalse(runtime.signedIn.value)
        assertNull(runtime.resumePoint)
        assertNull(runtime.queue.snapshot.current)
        assertTrue(runtime.history.snapshot().isEmpty())
        assertEquals(ServiceId.Jellyfin, runtime.service)
        assertEquals(ServiceCapabilities.Jellyfin, runtime.capabilities)
        assertEquals(PlaybackSettings(), runtime.settings.value)
    }

    @Test fun `a stored session starts the runtime signed in`() {
        val store = MemoryStore().apply {
            write(
                JSONObject()
                    .put("server_url", credentials.serverUrl)
                    .put("access_token", credentials.accessToken)
                    .put("authorization_header", credentials.authorizationHeader)
                    .put("user_id", credentials.userId)
                    .put("user_name", credentials.userName)
            )
        }

        assertTrue(runtime(store).signedIn.value)
    }

    @Test fun `signing in stores the session and reports it to the UI`() = runTest {
        val store = MemoryStore()
        val runtime = runtime(store)

        runtime.signIn(AuthInput.Password(credentials.serverUrl, "joe", "secret"))

        assertTrue(runtime.signedIn.value)
        assertEquals(credentials.serverUrl, store.read().optString("server_url"))
    }

    @Test fun `a session that could not be persisted still works for this process`() = runTest {
        val runtime = runtime(FailingWriteStore())

        val failure = runCatching {
            runtime.signIn(AuthInput.Password(credentials.serverUrl, "joe", "secret"))
        }.exceptionOrNull()

        assertTrue(failure is CredentialPersistenceException)
        assertTrue(runtime.signedIn.value)
    }

    @Test fun `settings changes apply immediately`() {
        val runtime = runtime()
        runtime.setAutoplay(false)
        runtime.setAudioQuality(AudioQuality.Normal)
        runtime.setNormalization(true)
        runtime.setGapless(false)

        assertEquals(
            PlaybackSettings(
                audioQuality = AudioQuality.Normal,
                normalization = true,
                gapless = false,
                autoplay = false
            ),
            runtime.settings.value
        )
    }

    @Test fun `the queue survives a new runtime for the same account`() {
        runtime().queue.playItem(track("one"))

        val second = runtime()

        assertEquals("one", second.queue.snapshot.current?.id)
        assertEquals(ServiceId.Jellyfin, second.queue.snapshot.current?.key?.service)
    }

    @Test fun `history survives a new runtime for the same account`() {
        runtime().history.record(track("one"), playedAtMs = 1_000L)

        assertEquals(listOf("one"), runtime().history.items().map { it.id })
    }

    @Test fun `the resume point is read from this account's own file`() {
        FilePositionStore(File(context.filesDir, "sidetracks-jellyfin-resume.bin")).write(
            ResumePoint(MediaKey(ServiceId.Jellyfin, MediaType.Track, "one"), 42_000L)
        )

        val runtime = runtime()

        assertEquals(MediaKey(ServiceId.Jellyfin, MediaType.Track, "one"), runtime.resumePoint?.key)
        assertEquals(42_000L, runtime.resumePoint?.positionMs)
    }

    @Test fun `signing out clears the session and the account state`() {
        val store = MemoryStore().apply {
            write(
                JSONObject()
                    .put("server_url", credentials.serverUrl)
                    .put("access_token", credentials.accessToken)
                    .put("authorization_header", credentials.authorizationHeader)
                    .put("user_id", credentials.userId)
                    .put("user_name", credentials.userName)
            )
        }
        val runtime = runtime(store)
        runtime.queue.playItem(track("one"))
        runtime.history.record(track("one"), playedAtMs = 1_000L)

        runtime.signOut()

        assertFalse(runtime.signedIn.value)
        assertNull(runtime.resumePoint)
        assertNull(runtime.queue.snapshot.current)
        assertTrue(runtime.history.snapshot().isEmpty())
        assertNull(JellyfinCredentialStore(store).read())
    }

    @Test fun `clearing the audio cache removes cached artwork`() {
        val artwork = File(context.cacheDir, "artwork").apply { mkdirs() }
        File(artwork, "cover").writeBytes(ByteArray(64))

        val runtime = runtime()

        assertTrue(runtime.cacheSizeBytes() >= 64L)
        runtime.clearAudioCache()
        assertEquals(0L, runtime.cacheSizeBytes())
    }

    @Test fun `a server resume item maps only the kinds a music library can hold`() {
        assertEquals(MediaType.Track, JellyfinKind.Audio.toMediaType())
        assertEquals(MediaType.Album, JellyfinKind.Album.toMediaType())
        assertEquals(MediaType.Artist, JellyfinKind.Artist.toMediaType())
        assertEquals(MediaType.Playlist, JellyfinKind.Playlist.toMediaType())
        assertNull(JellyfinKind.Other.toMediaType())
    }

    @Test fun `the provider identity and sign-in kind come from the runtime`() {
        val runtime = runtime()
        val provider = JellyfinProvider(runtime)

        assertEquals(ServiceId.Jellyfin, provider.id)
        assertEquals("Jellyfin", provider.displayName)
        assertEquals(JellyfinService.DISPLAY_NAME, provider.displayName)
        assertEquals(ServiceCapabilities.Jellyfin, provider.capabilities)
        assertTrue(provider.signInKind is SignInKind.Password)
        assertTrue(provider.createAuth() === runtime.auth)
    }

    /** A JsonStore that keeps the value in memory, so no keystore is involved. */
    private class MemoryStore : JsonStore {
        private var value: JSONObject? = null

        override fun read(): JSONObject = value?.let { JSONObject(it.toString()) } ?: JSONObject()

        override fun write(value: JSONObject) {
            this.value = JSONObject(value.toString())
        }

        override fun clear() {
            value = null
        }
    }

    /** Simulates a keystore that lost its key while the server accepted the sign-in. */
    private class FailingWriteStore : JsonStore {
        override fun read(): JSONObject = JSONObject()

        override fun write(value: JSONObject): Unit = throw EncryptedStoreException("keystore unavailable")

        override fun clear() = Unit
    }
}
