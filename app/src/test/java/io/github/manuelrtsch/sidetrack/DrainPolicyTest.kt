package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.playback.DrainPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the start-of-track underrun and the silent-stall recovery. Pure timing, so
 * they are pinned here rather than on a device.
 */
class DrainPolicyTest {
    private val prefill = 40_000
    private val prefillTimeout = 1_500L
    private val starveTimeout = 15_000L

    private fun policy() = DrainPolicy(prefill, prefillTimeout, starveTimeout)

    @Test fun `does not play before the track is primed`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)
        policy.onData(4_096, nowMs = 10L)

        assertFalse(policy.mayPlay(nowMs = 20L))
    }

    @Test fun `plays once the prefill target is buffered`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)
        policy.onData(prefill, nowMs = 100L)

        assertTrue(policy.mayPlay(nowMs = 100L))
        // Only the first call starts the track; the drain must not restart it on every buffer.
        assertFalse(policy.mayPlay(nowMs = 200L))
        assertFalse(policy.mayPlay(nowMs = 5_000L))
    }

    @Test fun `plays anyway when the first PCM is late`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)
        policy.onData(4_096, nowMs = 50L)

        assertFalse(policy.mayPlay(nowMs = prefillTimeout - 1))
        assertTrue(policy.mayPlay(nowMs = prefillTimeout))
    }

    @Test fun `a caller that already started the track is not told to start it again`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = true)
        policy.onData(prefill, nowMs = 10L)

        assertFalse(policy.mayPlay(nowMs = 10L))
    }

    @Test fun `a running track starves only after the whole budget`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = true)

        assertFalse(policy.starved(nowMs = starveTimeout - 1))
        assertTrue(policy.starved(nowMs = starveTimeout))
    }

    @Test fun `PCM resets the starvation clock`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = true)

        policy.onData(4_096, nowMs = starveTimeout - 1_000L)

        assertFalse(policy.starved(nowMs = starveTimeout))
        assertTrue(policy.starved(nowMs = starveTimeout * 2 - 1_000L))
    }

    @Test fun `a track that has not started yet cannot starve`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)

        // Waiting for the prefill is the preplay timeout's business, not the stall budget's.
        assertFalse(policy.starved(nowMs = starveTimeout * 2))
        assertTrue(policy.mayPlay(nowMs = starveTimeout * 2))
        assertTrue(policy.starved(nowMs = starveTimeout * 2 + starveTimeout))
    }
}
