package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.playbackStreamUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the choice `SdkJellyfinApi.playback()` makes between the server's `transcodingUrl` and the
 * direct-play stream URL.
 *
 * This is the branch the 0.4.0 regression lived in: Jellyfin 12 answers `/Audio/{id}/universal`
 * with an HTML error page, so the player must be handed `/Audio/{id}/stream?static=true` whenever
 * the server did not ask for a transcode. `FakeJellyfinApi` stubs `playback()` wholesale, so
 * without this test the branch is only covered by the opt-in integration suite.
 *
 * No SDK and no server: `playbackStreamUrl` takes the three values the branch reads.
 */
class JellyfinPlaybackChoiceTest {
    private val baseUrl = "https://music.example.org/stable"
    private val directUrl = "$baseUrl/Audio/90b750a2-cec4-eb33-8c0d-ab3b40b8bb13/stream?static=true"

    @Test fun `no transcoding url keeps the direct play`() {
        assertEquals(directUrl, playbackStreamUrl(transcodingUrl = null, baseUrl = baseUrl, directUrl = directUrl))
    }

    @Test fun `a blank transcoding url keeps the direct play`() {
        // Some servers answer with an empty string rather than omitting the field.
        assertEquals(directUrl, playbackStreamUrl(transcodingUrl = "", baseUrl = baseUrl, directUrl = directUrl))
        assertEquals(directUrl, playbackStreamUrl(transcodingUrl = "  ", baseUrl = baseUrl, directUrl = directUrl))
    }

    @Test fun `an absolute transcoding url is used as is`() {
        val transcoded = "https://cdn.example.org/Audio/90b750a2/universal?maxStreamingBitrate=128000"

        assertEquals(
            transcoded,
            playbackStreamUrl(transcodingUrl = transcoded, baseUrl = baseUrl, directUrl = directUrl)
        )
    }

    @Test fun `a relative transcoding url is joined to the server root`() {
        val path = "/Audio/90b750a2-cec4-eb33-8c0d-ab3b40b8bb13/universal?maxStreamingBitrate=128000"

        assertEquals(
            baseUrl + path,
            playbackStreamUrl(transcodingUrl = path, baseUrl = baseUrl, directUrl = directUrl)
        )
    }

    @Test fun `a transcoding url wins even when the source claims direct play`() {
        // `directPlay` is reported from the same source, but a transcode cannot be served by the
        // static endpoint — so the URL choice must follow the transcoding url, not the flag.
        assertEquals(
            baseUrl + "/Audio/x/universal",
            playbackStreamUrl(
                transcodingUrl = "/Audio/x/universal",
                baseUrl = baseUrl,
                directUrl = directUrl
            )
        )
    }
}
