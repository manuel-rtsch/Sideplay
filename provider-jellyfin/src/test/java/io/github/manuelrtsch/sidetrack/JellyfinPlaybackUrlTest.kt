package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.data.directPlayStreamUrl
import java.util.UUID
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.operations.AudioApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the URL a direct play is handed to the player.
 *
 * Jellyfin 12 answers `/Audio/{id}/universal` with **500** and an HTML error page when the request
 * does not name a container, which ExoPlayer reports as "none of the available extractors could
 * read the stream". The static stream endpoint is the one real clients use, so this test fails if
 * the fallback ever drifts back to an endpoint that cannot serve the original file.
 *
 * No server: only the SDK's URL builder runs. The device info is stated rather than derived, so
 * the test does not depend on what Robolectric reports for `Build.MODEL`.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinPlaybackUrlTest {
    private fun audioApi(baseUrl: String = "https://music.example.org/stable"): AudioApi = createJellyfin {
        context = ApplicationProvider.getApplicationContext<Context>()
        clientInfo = ClientInfo(name = "Sidetrack", version = "test")
        deviceInfo = DeviceInfo(name = "Test Phone", id = "test-device")
    }.createApi(baseUrl = baseUrl, accessToken = "token").audioApi

    private val itemId = UUID.fromString("90b750a2-cec4-eb33-8c0d-ab3b40b8bb13")

    @Test fun `a direct play asks for the static stream so the original file is served`() {
        val url = directPlayStreamUrl(
            audio = audioApi(),
            itemId = itemId,
            container = "flac",
            mediaSourceId = "90b750a2cec4eb338c0dab3b40b8bb13",
            deviceId = "device-1",
            playSessionId = "session-1"
        )

        assertTrue(url, url.startsWith("https://music.example.org/stable/Audio/90b750a2-cec4-eb33-8c0d-ab3b40b8bb13/stream"))
        assertTrue(url, url.contains("static=true"))
        assertTrue(url, url.contains("container=flac"))
        assertTrue(url, url.contains("mediaSourceId=90b750a2cec4eb338c0dab3b40b8bb13"))
        assertTrue(url, url.contains("deviceId=device-1"))
        assertTrue(url, url.contains("playSessionId=session-1"))
    }

    @Test fun `a direct play never points at the universal endpoint`() {
        val url = directPlayStreamUrl(
            audio = audioApi(),
            itemId = itemId,
            container = null,
            mediaSourceId = null,
            deviceId = null,
            playSessionId = null
        )

        assertFalse(url, url.contains("/universal"))
        assertTrue(url, url.contains("static=true"))
    }
}
