package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.playback.notificationSuffix
import io.github.manuelrtsch.sidetrack.playback.sleepTimerRemainingMinutes
import io.github.manuelrtsch.sidetrack.playback.statusDescription
import io.github.manuelrtsch.sidetrack.playback.statusText
import io.github.manuelrtsch.sidetrack.playback.tileText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    @Test fun `remaining minutes round up through the last partial minute`() {
        assertEquals(5, sleepTimerRemainingMinutes(300_000L, 0L))
        assertEquals(5, sleepTimerRemainingMinutes(300_000L, 1L))
        assertEquals(5, sleepTimerRemainingMinutes(300_000L, 59_999L))
        assertEquals(4, sleepTimerRemainingMinutes(300_000L, 60_000L))
        assertEquals(1, sleepTimerRemainingMinutes(300_000L, 299_999L))
        assertEquals(0, sleepTimerRemainingMinutes(300_000L, 300_000L))
    }

    @Test fun `status text reports the live remainder`() {
        val timer = SleepTimerState.Countdown(30, 1_800_000L, 23)

        assertEquals("Pauses in 23 min", timer.statusText())
        assertEquals("Sleep timer, pauses in 23 minutes", timer.statusDescription())
        assertEquals("23 MIN LEFT", timer.tileText())
        assertEquals("pauses in 23 min", timer.notificationSuffix())
        assertEquals(
            "Sleep timer, pauses in 1 minute",
            timer.copy(remainingMinutes = 1).statusDescription()
        )
        assertTrue(timer.active)
    }

    @Test fun `end of track is a mode, not a countdown`() {
        assertEquals("Pauses after this track", SleepTimerState.EndOfTrack.statusText())
        assertEquals("Sleep timer, pauses after this track", SleepTimerState.EndOfTrack.statusDescription())
        assertEquals("END OF TRACK", SleepTimerState.EndOfTrack.tileText())
        assertEquals("pauses after this track", SleepTimerState.EndOfTrack.notificationSuffix())
        assertTrue(SleepTimerState.EndOfTrack.active)
    }

    @Test fun `off clears every surface`() {
        assertNull(SleepTimerState.Off.statusText())
        assertNull(SleepTimerState.Off.statusDescription())
        assertNull(SleepTimerState.Off.tileText())
        assertNull(SleepTimerState.Off.notificationSuffix())
        assertFalse(SleepTimerState.Off.active)
    }

    @Test fun `finished explains the pause but is no longer armed`() {
        assertEquals("Paused by sleep timer", SleepTimerState.Finished.statusText())
        assertEquals("Sleep timer finished, playback paused", SleepTimerState.Finished.statusDescription())
        assertEquals("PAUSED BY TIMER", SleepTimerState.Finished.tileText())
        assertNull(SleepTimerState.Finished.notificationSuffix())
        assertFalse(SleepTimerState.Finished.active)
    }

    @Test fun `a held countdown keeps its rounded remainder`() {
        val held = SleepTimerState.Countdown(2, 0L, 1, heldMs = 59_000L)

        assertEquals(59_000L, held.heldMs)
        assertEquals(1, sleepTimerRemainingMinutes(held.heldMs!!))
        assertTrue(held.active)
    }
}
