package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.HaltStopGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaltStopGuardTest {
    private val uri = "spotify:track:x"

    @Test fun `a halted play request is consumed even after the grace window`() {
        val guard = HaltStopGuard(graceMs = 15_000L)
        guard.arm(uri, playRequestId = 41L, now = 1_000L)

        assertTrue(guard.consume(uri, playRequestId = 41L, now = 100_000L))
        assertFalse(guard.consume(uri, playRequestId = 41L, now = 100_001L))
    }

    @Test fun `a genuine stop after resuming the same track is not consumed`() {
        val guard = HaltStopGuard(graceMs = 15_000L)
        guard.arm(uri, playRequestId = 41L, now = 1_000L)

        assertFalse(guard.consume(uri, playRequestId = 42L, now = 2_000L))
        assertTrue(guard.consume(uri, playRequestId = 41L, now = 2_001L))
    }

    @Test fun `an event without a request id falls back to the grace window`() {
        val guard = HaltStopGuard(graceMs = 15_000L)
        guard.arm(uri, playRequestId = 41L, now = 1_000L)

        assertFalse(guard.consume(uri, playRequestId = -1L, now = 20_000L))
    }

    @Test fun `a different track is never consumed`() {
        val guard = HaltStopGuard(graceMs = 15_000L)
        guard.arm(uri, playRequestId = 41L, now = 1_000L)

        assertFalse(guard.consume("spotify:track:y", playRequestId = 41L, now = 1_100L))
    }
}
