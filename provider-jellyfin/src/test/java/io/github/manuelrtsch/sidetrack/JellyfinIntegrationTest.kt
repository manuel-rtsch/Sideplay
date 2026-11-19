package io.github.manuelrtsch.sidetrack

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.auth.SdkJellyfinAuthenticator
import io.github.manuelrtsch.sidetrack.auth.normalizeJellyfinServerUrl
import io.github.manuelrtsch.sidetrack.data.SdkJellyfinApi
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Opt-in smoke test against a real Jellyfin server with a music library.
 *
 * `JELLYFIN_TEST_URL=http://127.0.0.1:8096 JELLYFIN_TEST_USER=... JELLYFIN_TEST_PASSWORD=...`
 *
 * Skips without those variables, so CI and normal local runs stay offline.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinIntegrationTest {
    private val baseUrl = System.getenv("JELLYFIN_TEST_URL").orEmpty()
    private val username = System.getenv("JELLYFIN_TEST_USER").orEmpty()
    private val password = System.getenv("JELLYFIN_TEST_PASSWORD").orEmpty()

    /**
     * The SDK builds its `DeviceInfo` from `Settings.Global.DEVICE_NAME` and
     * `Settings.Secure.ANDROID_ID`, and it requires the id; Robolectric leaves the id null, so
     * `createJellyfin` threw a `NullPointerException` before any request was made and this suite
     * could never run — even with the env vars set. Stating both here is what makes the only
     * end-to-end verification of this adapter (D14) actually executable.
     */
    @Before fun stateADevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "Sidetrack Test Phone")
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "sidetrack-test-device")
    }

    private fun requireServer() {
        assumeTrue(
            "Set JELLYFIN_TEST_URL/USER/PASSWORD to run this test",
            baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        )
    }

    private suspend fun api(): SdkJellyfinApi {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = SdkJellyfinAuthenticator(context).signIn(
            normalizeJellyfinServerUrl(baseUrl),
            username,
            password
        )
        return SdkJellyfinApi(context, credentials)
    }

    @Test fun `reads albums tracks and search from a real server`() = runTest {
        requireServer()
        val api = api()

        val albums = api.albums(0, 5)
        assumeTrue("The account has no album in its favourite/library view", albums.items.isNotEmpty())
        val album = albums.items.first()
        assertTrue(album.name.isNotBlank())

        val tracks = api.albumTracks(album.id, 0, 10)
        assertTrue(tracks.items.isNotEmpty())
        assertTrue(tracks.items.first().name.isNotBlank())
        assertTrue((tracks.items.first().runTimeTicks ?: 0L) > 0L)

        val search = api.search(album.name, setOf(SearchScope.Tracks, SearchScope.Albums), 0, 5)
        assertTrue(search.hints.isNotEmpty())
        assertTrue(search.hints.any { it.kind == io.github.manuelrtsch.sidetrack.data.JellyfinKind.Audio })
    }

    @Test fun `resolves a stream URL that answers with audio bytes and the auth header`() = runTest {
        requireServer()
        val api = api()
        val albums = api.albums(0, 5)
        assumeTrue(albums.items.isNotEmpty())
        val tracks = api.albumTracks(albums.items.first().id, 0, 1)
        assumeTrue(tracks.items.isNotEmpty())
        val track = tracks.items.first()

        val info = api.playback(track.id, maxStreamingBitrate = 320)
        assertTrue(info.url.startsWith("http"))

        val connection = URL(info.url).openConnection() as HttpURLConnection
        info.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        try {
            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
            val bytes = connection.inputStream.use { it.readBytes() }
            assertTrue("Expected audio, got ${bytes.size} bytes", bytes.size > 1_000)
        } finally {
            connection.disconnect()
        }

        val artUrl = api.imageUrl(track.artItemId, track.imageTag)
        assertFalse(artUrl.isBlank())
    }
}
