package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.JellyfinPlaybackInfo
import io.github.manuelrtsch.sidetrack.data.JellyfinPlaybackReporter
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12: a finished track is reported and marked played; a skipped track is reported stopped but
 * never marked played. Position reports are throttled and a paused player reports its pause once.
 */
class JellyfinPlaybackReporterTest {
    private val api = FakeJellyfinApi()
    private val state = MutableStateFlow(PlaybackEngineState())
    private val ended = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    private var position = 0L
    private var now = 1_000L

    private val item = Track(
        id = "t1",
        title = "Night Signals",
        artist = "Aurora Vale",
        artistId = "ar1",
        albumId = "a1",
        artRes = 0,
        durationMs = 200_000L,
        service = ServiceId.Jellyfin
    )

    private val info = JellyfinPlaybackInfo(
        url = "https://stream",
        headers = emptyMap(),
        playSessionId = "session-1",
        mediaSourceId = "source-1",
        directPlay = true
    )

    private fun reporter(scope: kotlinx.coroutines.CoroutineScope) = JellyfinPlaybackReporter(
        api = { api },
        playbackInfo = { info },
        state = state,
        ended = ended,
        currentItem = { item },
        position = { position },
        scope = scope,
        clock = { now }
    )

    @Test fun `start progress and end reach the server once and the track is marked played`() = runTest {
        val reporter = reporter(this)
        reporter.start()

        now = 2_000L
        state.value = PlaybackEngineState(
            source = PlaybackSource("jellyfin:track:t1", "https://stream"),
            isPlaying = true,
            positionMs = 0
        )
        advanceUntilIdle()
        assertEquals(1, api.starts.size)
        assertEquals("t1", api.starts.single().itemId)
        assertEquals("session-1", api.starts.single().playSessionId)
        assertEquals(false, api.starts.single().paused)

        position = 40_000L
        now = 12_001L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()
        assertEquals(1, api.progress.size)
        assertEquals(40_000L, api.progress.single().positionMs)

        // Another tick inside the interval must not add a request.
        now = 15_000L
        position = 43_000L
        state.value = state.value.copy(positionMs = position)
        advanceUntilIdle()
        assertEquals(1, api.progress.size)

        ended.emit("https://stream")
        advanceUntilIdle()
        assertEquals(1, api.stopped.size)
        assertEquals(false, api.stopped.single().second)
        assertEquals(200_000L, api.stopped.single().first.positionMs)
        assertEquals(listOf("t1"), api.played)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `switching tracks stops the old one without marking it played`() = runTest {
        val reporter = reporter(this)
        reporter.start()
        position = 5_000L
        now = 2_000L
        state.value = PlaybackEngineState(
            source = PlaybackSource("jellyfin:track:t1", "https://stream"),
            isPlaying = true
        )
        advanceUntilIdle()

        position = 0L
        now = 3_000L
        state.value = PlaybackEngineState(
            source = PlaybackSource("jellyfin:track:t2", "https://stream-2"),
            isPlaying = true
        )
        advanceUntilIdle()

        assertEquals(1, api.stopped.size)
        assertEquals("t1", api.stopped.single().first.itemId)
        assertTrue(api.played.isEmpty())
        assertEquals(2, api.starts.size)
        assertEquals("t2", api.starts.last().itemId)
        reporter.stop()
        advanceUntilIdle()
    }

    @Test fun `a pause reports once and position is not sent repeatedly`() = runTest {
        val reporter = reporter(this)
        reporter.start()
        now = 2_000L
        state.value = PlaybackEngineState(
            source = PlaybackSource("jellyfin:track:t1", "https://stream"),
            isPlaying = true
        )
        advanceUntilIdle()

        position = 30_000L
        now = 5_000L
        state.value = state.value.copy(isPlaying = false, positionMs = position)
        advanceUntilIdle()
        assertEquals(1, api.progress.size)
        assertEquals(true, api.progress.single().paused)
        assertEquals(30_000L, api.progress.single().positionMs)

        state.value = state.value.copy(positionMs = 31_000L)
        advanceUntilIdle()
        assertEquals(1, api.progress.size)
        reporter.stop()
        advanceUntilIdle()
    }
}
