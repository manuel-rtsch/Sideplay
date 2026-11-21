package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.playback.DrainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the start-of-track underrun and the silent-stall recovery. Pure timing, so
 * they are pinned here rather than on a device.
 */
class DrainPolicyTest {
    private val prefill = 40_000
    private val minStart = 4_096
    private val prefillTimeout = 1_500L
    private val starveTimeout = 15_000L
    private val playingStarveTimeout = 3_000L

    private fun policy() =
        DrainPolicy(prefill, minStart, prefillTimeout, starveTimeout, playingStarveTimeout)

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

    @Test fun `plays a partial cushion once the prefill deadline expires`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)
        policy.onData(minStart, nowMs = 50L)

        assertFalse(policy.mayPlay(nowMs = prefillTimeout - 1))
        assertTrue(policy.mayPlay(nowMs = prefillTimeout))
    }

    @Test fun `never starts a track that holds less than the minimum cushion`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)
        policy.onData(minStart - 1, nowMs = 50L)

        assertFalse(policy.mayPlay(nowMs = prefillTimeout))
        assertFalse(policy.mayPlay(nowMs = starveTimeout - 1))

        // The next buffer arrives: it is still short of the prefill target but playable.
        policy.onData(1, nowMs = starveTimeout - 1)

        assertTrue(policy.mayPlay(nowMs = starveTimeout - 1))
    }

    @Test fun `a caller that already started the track is not told to start it again`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = true)
        policy.onData(prefill, nowMs = 10L)

        assertFalse(policy.mayPlay(nowMs = 10L))
    }

    @Test fun `a running track starves after the short playing budget`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = true)

        assertFalse(policy.starved(nowMs = playingStarveTimeout - 1))
        assertTrue(policy.starved(nowMs = playingStarveTimeout))
    }

    @Test fun `a track that starts playing switches to the short budget`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)
        policy.onData(prefill, nowMs = 100L)

        assertTrue(policy.mayPlay(nowMs = 100L))
        assertFalse(policy.starved(nowMs = 100L + playingStarveTimeout - 1))
        assertTrue(policy.starved(nowMs = 100L + playingStarveTimeout))
    }

    @Test fun `a slow first fetch still gets the long pre-play budget`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)

        assertFalse(policy.starved(nowMs = playingStarveTimeout))
        assertFalse(policy.starved(nowMs = starveTimeout - 1))
        assertTrue(policy.starved(nowMs = starveTimeout))
    }

    @Test fun `idle time drives the buffering indicator before the stall budget`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = true)

        assertEquals(1_500L, policy.idleFor(nowMs = 1_500L))

        policy.onData(4_096, nowMs = 2_000L)

        assertEquals(500L, policy.idleFor(nowMs = 2_500L))
    }

    @Test fun `PCM resets the starvation clock`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = true)

        policy.onData(4_096, nowMs = playingStarveTimeout - 1_000L)

        assertFalse(policy.starved(nowMs = playingStarveTimeout))
        assertTrue(policy.starved(nowMs = playingStarveTimeout * 2 - 1_000L))
    }

    @Test fun `a track that never gets PCM stalls instead of starting empty`() {
        val policy = policy()
        policy.begin(nowMs = 0L, playing = false)

        // Waiting for the prefill is the preplay timeout's business; waiting forever is not.
        assertFalse(policy.mayPlay(nowMs = prefillTimeout))
        assertFalse(policy.starved(nowMs = starveTimeout - 1))
        assertTrue(policy.starved(nowMs = starveTimeout))
    }
}
