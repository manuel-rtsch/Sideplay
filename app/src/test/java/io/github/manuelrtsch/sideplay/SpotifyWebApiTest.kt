package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.data.SpotifyWebApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyWebApiTest {
    private val api = SpotifyWebApi { null }

    @Test
    fun `maps a track search result to the player shape`() {
        val track = api.parseSearchTracks(JSONObject(RESULT)).single()

        assertEquals("4uLU6hMCjMI75M1A2tKUQC", track.id)
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals("0gxyHStUsqpMadRV0Di1Qt", track.artistId)
        assertEquals("6XhjNHCyCDyyGJRM5mg40G", track.albumId)
        assertEquals(213_573L, track.durationMs)
        assertFalse(track.explicit)
        assertEquals("spotify:track:4uLU6hMCjMI75M1A2tKUQC", track.uri)
        assertTrue(track.playable)
    }

    @Test
    fun `an empty envelope yields no rows`() {
        assertTrue(api.parseSearchTracks(JSONObject("""{"tracks":{"items":[]}}""")).isEmpty())
        assertTrue(api.parseSearchTracks(JSONObject("{}")).isEmpty())
    }

    @Test
    fun `a row without an id is skipped`() {
        val json = JSONObject(
            """{"tracks":{"items":[{"name":"No id"},{"id":"abc","name":"Kept","uri":"spotify:track:abc"}]}}"""
        )

        assertEquals(listOf("abc"), api.parseSearchTracks(json).map { it.id })
    }

    private companion object {
        const val RESULT = """
            {
              "tracks": {
                "items": [
                  {
                    "id": "4uLU6hMCjMI75M1A2tKUQC",
                    "name": "Never Gonna Give You Up",
                    "duration_ms": 213573,
                    "explicit": false,
                    "uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                    "artists": [{"id": "0gxyHStUsqpMadRV0Di1Qt", "name": "Rick Astley"}],
                    "album": {"id": "6XhjNHCyCDyyGJRM5mg40G"}
                  }
                ]
              }
            }
        """
    }
}
