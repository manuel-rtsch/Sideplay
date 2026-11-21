package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.PlexPlaybackReporter
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plex gets a timeline report when playback starts, pauses and resumes, a throttled progress
 * report while playing, and exactly one scrobble when the track ends. Skipping stops the old
 * track without counting it as played.
 */
class PlexPlaybackReporterTest {
    private val api = FakePlexApi()
    private val state = MutableStateFlow(PlaybackEngineState())
    private val ended = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    private var position = 0L
    private var now = 1_000L

    private val item = Track(
        id = "3",
        title = "Bau auf",
        artist = "DDR",
        artistId = "1",
        albumId = "2",
        artRes = 0,
        durationMs = 195_792L,
        service = ServiceId.Plex
    )

    private fun reporter(scope: CoroutineScope) = PlexPlaybackReporter(
        api = { api },
        state = state,
        ended = ended,
        currentItem = { item },
        position = { position },
        scope = scope,
        clock = { now }
    )

    @Test fun `start pause resume progress and end reach the server`() = runTest {
        val reporter = reporter(this)
        reporter.start()

        now = 2_000L
        state.value = PlaybackEngineState(
            source = PlaybackSource("plex:track:3", "http://plex.test:32400/library/parts/3/file.mp3"),
            isPlaying = true,
            positionMs = 0
        )
        advanceUntilIdle()
        assertEquals(listOf(Triple("3", "playing", 0L)), api.timelines)

        position = 20_000L
        state.value = state.value.copy(isPlaying = false, positionMs = position)
        advanceUntilIdle()
        assertEquals("paused", api.timelines.last().second)
        assertEquals(20_000L, api.timelines.last().third)

        now = 5_000L
        state.value = state.value.copy(isPlaying = true, positionMs = position)
        advanceUntilIdle()
        assertEquals(3, api.timelines.size)
        assertEquals("playing", api.timelines.last().second)

        // Progress inside the ten-second interval is not sent.
        now = 9_000L
        position = 25_000L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()
        assertEquals(3, api.timelines.size)

        now = 16_000L
        position = 40_000L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()
        assertEquals(4, api.timelines.size)
        assertEquals(40_000L, api.timelines.last().third)

        ended.emit("http://plex.test:32400/library/parts/3/file.mp3")
        advanceUntilIdle()
        assertEquals("stopped", api.timelines.last().second)
        assertEquals(195_792L, api.timelines.last().third)
        assertEquals(listOf("3"), api.scrobbles)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `switching tracks stops the old one but does not scrobble`() = runTest {
        val reporter = reporter(this)
        reporter.start()
        position = 5_000L
        state.value = PlaybackEngineState(
            source = PlaybackSource("plex:track:3", "http://plex.test/3.mp3"),
            isPlaying = true
        )
        advanceUntilIdle()

        position = 5_000L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()

        position = 0L
        state.value = PlaybackEngineState(
            source = PlaybackSource("plex:track:4", "http://plex.test/4.mp3"),
            isPlaying = true
        )
        advanceUntilIdle()

        assertEquals("stopped", api.timelines[1].second)
        assertEquals(5_000L, api.timelines[1].third)
        assertTrue(api.scrobbles.isEmpty())
        assertEquals(2, api.timelines.count { it.second == "playing" })
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `a non-Plex source is ignored`() = runTest {
        val reporter = reporter(this)
        reporter.start()
        state.value = PlaybackEngineState(
            source = PlaybackSource("jellyfin:track:3", "http://jellyfin/3.mp3"),
            isPlaying = true
        )
        advanceUntilIdle()
        assertTrue(api.timelines.isEmpty())
        reporter.stop()
        advanceUntilIdle()
    }
}
