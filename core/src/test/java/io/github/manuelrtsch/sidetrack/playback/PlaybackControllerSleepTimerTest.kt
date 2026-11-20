package io.github.manuelrtsch.sidetrack.playback

import android.media.AudioManager
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sleep timer's contract: the countdown measures listening time, holds while paused, reports
 * expiry once and lets a finished notice clear when the next item starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackControllerSleepTimerTest : PlaybackControllerTestBase() {
    @Test fun `a running sleep timer pauses at the deadline`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context, elapsedTime = { testScheduler.currentTime })
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        controller.setSleepTimer(1)

        advanceTimeBy(61_000L)
        runCurrent()

        assertEquals(SleepTimerState.Finished, controller.sleepTimer.value)
        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)
    }

    @Test fun `a zero-minute sleep timer clears instead of installing`() = runTest {
        controller = newController(FakeEngine(), elapsedTime = { testScheduler.currentTime })
        controller.setSleepTimer(5)

        controller.setSleepTimer(0)

        assertEquals(SleepTimerState.Off, controller.sleepTimer.value)
    }

    @Test fun `a timer armed while idle waits for playback`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine, elapsedTime = { testScheduler.currentTime })
        controller.setSleepTimer(1)
        val held = controller.sleepTimer.value as SleepTimerState.Countdown
        assertEquals(60_000L, held.heldMs)

        advanceTimeBy(61_000L)
        runCurrent()

        assertEquals(held, controller.sleepTimer.value)
        assertEquals(0, engine.pauses)
    }

    @Test fun `pausing holds the countdown and resuming continues it`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context, elapsedTime = { testScheduler.currentTime })
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        controller.setSleepTimer(2)
        advanceTimeBy(61_000L)
        runCurrent()

        controller.pause()
        runCurrent()
        val held = controller.sleepTimer.value as SleepTimerState.Countdown
        assertEquals(1, held.remainingMinutes)
        assertEquals(59_000L, held.heldMs)

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(held, controller.sleepTimer.value)

        controller.resume()
        runCurrent()
        val resumed = controller.sleepTimer.value as SleepTimerState.Countdown
        assertNull(resumed.heldMs)
        assertEquals(1, resumed.remainingMinutes)

        advanceTimeBy(61_000L)
        runCurrent()
        assertEquals(SleepTimerState.Finished, controller.sleepTimer.value)
    }

    @Test fun `sleep-timer ticks refresh the notification`() = runTest {
        var refreshes = 0
        controller = newController(
            FakeEngine(),
            context = context,
            elapsedTime = { testScheduler.currentTime },
            notifyRefresh = { _, _ -> refreshes++ }
        )
        runCurrent()
        val initial = refreshes
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)

        controller.setSleepTimer(2)
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()

        assertTrue("a countdown tick must rebuild the notification", refreshes > initial + 1)
    }

    @Test fun `an end-of-track timer is consumed once`() = runTest {
        controller = newController(FakeEngine())
        controller.setSleepTimerEndOfTrack()

        assertTrue(controller.consumeEndOfTrackTimer())
        assertEquals(SleepTimerState.Finished, controller.sleepTimer.value)
        assertFalse(controller.consumeEndOfTrackTimer())
    }

    @Test fun `finished clears when the next track starts`() = runTest {
        controller = newController(FakeEngine())
        controller.setSleepTimerEndOfTrack()
        controller.consumeEndOfTrackTimer()

        controller.setNowPlaying(track(uri = "spotify:track:b"), 0L)

        assertEquals(SleepTimerState.Off, controller.sleepTimer.value)
    }

    @Test fun `setNowPlaying keeps the source and the engine position in step`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)

        controller.setNowPlaying(track(uri = "spotify:track:a"), 12_000L)

        assertEquals(12_000L, engine.updatedPosition)
        assertEquals("spotify:track:a", controller.currentSource?.uri)
        controller.updatePosition(13_000L)
        controller.updateState(isPlaying = true, position = 14_000L)
        assertEquals(14_000L, engine.updatedPosition)
        assertTrue(engine.updatedIsPlaying)
        assertEquals(14_000L, controller.position)
    }

    @Test fun `an explicit source is kept instead of deriving it from the item`() = runTest {
        controller = newController(FakeEngine())

        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L, PlaybackSource("stream", "https://example/a"))

        assertEquals("https://example/a", controller.currentSource?.uri)
        assertEquals("stream", controller.currentSource?.id)
    }

    @Test fun `a notice can be set and cleared`() = runTest {
        controller = newController(FakeEngine())

        controller.emitNotice("Reconnecting…")
        assertEquals("Reconnecting…", controller.notice.value)

        controller.emitNotice(null)
        assertNull(controller.notice.value)
    }
}
