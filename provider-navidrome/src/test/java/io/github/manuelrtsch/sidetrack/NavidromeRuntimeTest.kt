package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.auth.NavidromeAuth
import io.github.manuelrtsch.sidetrack.auth.NavidromeCredentialStore
import io.github.manuelrtsch.sidetrack.data.FilePositionStore
import io.github.manuelrtsch.sidetrack.data.NavidromeCredentials
import io.github.manuelrtsch.sidetrack.data.NavidromeProvider
import io.github.manuelrtsch.sidetrack.data.NavidromeRuntime
import io.github.manuelrtsch.sidetrack.data.NavidromeService
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Navidrome runtime is the object the app actually talks to once a server is configured. The
 * network-facing parts (catalog, API client) are covered by [NavidromeApiTest]; these tests pin the
 * account-scoped state that must survive process death and must never leak into another service:
 * sign-in state, settings, queue, history and the resume point.
 */
@RunWith(RobolectricTestRunner::class)
class NavidromeRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun credentials() = NavidromeCredentials("http://nas.local:4533", "joe", "sesame")

    private fun auth(store: JsonStore) = NavidromeAuth(NavidromeCredentialStore(context, "navidrome-runtime-test", store))

    private fun runtime(store: JsonStore = MemoryStore()) = NavidromeRuntime(context, auth(store))

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artist = "Aurora",
        artistId = "artist-1",
        albumId = "album-1",
        artRes = 0,
        durationMs = 180_000L,
        service = ServiceId.Navidrome
    )

    private fun files() = listOf(
        "sidetracks-navidrome-queue.bin",
        "sidetracks-navidrome-history.bin",
        "sidetracks-navidrome-resume.bin"
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
        assertEquals(ServiceId.Navidrome, runtime.service)
        assertEquals(ServiceCapabilities.Navidrome, runtime.capabilities)
        assertEquals(PlaybackSettings(), runtime.settings.value)
    }

    @Test fun `a stored credential starts the runtime signed in`() {
        val store = MemoryStore().apply {
            write(
                JSONObject()
                    .put("server_url", credentials().serverUrl)
                    .put("username", credentials().username)
                    .put("password", credentials().password)
            )
        }

        val runtime = runtime(store)

        assertTrue(runtime.signedIn.value)
    }

    @Test fun `settings changes apply immediately`() {
        val first = runtime()
        first.setAutoplay(false)
        first.setAudioQuality(AudioQuality.Normal)
        first.setNormalization(true)
        first.setGapless(false)

        assertEquals(
            PlaybackSettings(
                audioQuality = AudioQuality.Normal,
                normalization = true,
                gapless = false,
                autoplay = false
            ),
            first.settings.value
        )
    }

    @Test fun `the queue survives a new runtime for the same account`() {
        val first = runtime()
        first.queue.playItem(track("one"))

        val second = runtime()

        assertEquals("one", second.queue.snapshot.current?.id)
        assertEquals(ServiceId.Navidrome, second.queue.snapshot.current?.key?.service)
    }

    @Test fun `history survives a new runtime for the same account`() {
        val first = runtime()
        first.history.record(track("one"), playedAtMs = 1_000L)

        val second = runtime()

        assertEquals(listOf("one"), second.history.items().map { it.id })
    }

    @Test fun `the resume point is read from this account's own file`() {
        FilePositionStore(File(context.filesDir, "sidetracks-navidrome-resume.bin")).write(
            ResumePoint(MediaKey(ServiceId.Navidrome, MediaType.Track, "one"), 42_000L)
        )

        val runtime = runtime()

        assertEquals(MediaKey(ServiceId.Navidrome, MediaType.Track, "one"), runtime.resumePoint?.key)
        assertEquals(42_000L, runtime.resumePoint?.positionMs)
    }

    @Test fun `signing out clears the session and the account state`() {
        val store = MemoryStore().apply {
            write(
                JSONObject()
                    .put("server_url", credentials().serverUrl)
                    .put("username", credentials().username)
                    .put("password", credentials().password)
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
        assertNull(NavidromeCredentialStore(context, "navidrome-runtime-test", store).read())
    }

    @Test fun `the provider identity and password defaults come from the runtime`() {
        val runtime = runtime()
        val provider = NavidromeProvider(runtime)

        assertEquals(ServiceId.Navidrome, provider.id)
        assertEquals("Navidrome", provider.displayName)
        assertEquals(ServiceCapabilities.Navidrome, provider.capabilities)
        assertEquals(NavidromeService.DISPLAY_NAME, provider.displayName)
        assertTrue(provider.createAuth() === runtime.auth)
    }

    @Test fun `clearing the audio cache removes cached artwork`() {
        val artwork = File(context.cacheDir, "artwork").apply { mkdirs() }
        File(artwork, "cover").writeBytes(ByteArray(64))

        val runtime = runtime()

        assertTrue(runtime.cacheSizeBytes() >= 64L)
        runtime.clearAudioCache()
        assertEquals(0L, runtime.cacheSizeBytes())
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
}
