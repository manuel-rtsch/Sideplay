package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.data.SpotifyQueueRepository
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyQueueRepositoryTest {
    private val tracks = listOf(
        track("a"),
        track("b"),
        track("c"),
        track("d")
    )

    @Test fun `play from a row replaces the queue and records the previous item`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.play(tracks, 2)

        assertEquals("c", queue.snapshot.current?.id)
        assertEquals(listOf("d"), queue.snapshot.upcoming.map { it.id })
        assertEquals(listOf("a"), queue.snapshot.history.map { it.id })
    }

    @Test fun `replaying the current item does not duplicate history`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 1)
        queue.play(tracks, 1)

        assertEquals("b", queue.snapshot.current?.id)
        assertTrue(queue.snapshot.history.isEmpty())
    }

    @Test fun `play next moves an existing upcoming item instead of duplicating it`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.playNext(tracks[2])

        assertEquals(listOf("c", "b", "d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `restore adopts a process-wide snapshot`() {
        val queue = SpotifyQueueRepository(Random(1))
        val snapshot = queue.play(tracks, 1)

        val restored = SpotifyQueueRepository(Random(2)).restore(snapshot)

        assertEquals("b", restored.current?.id)
        assertEquals(listOf("c", "d"), restored.upcoming.map { it.id })
    }

    @Test fun `add to queue appends`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.addToQueue(track("z"))

        assertEquals(listOf("b", "c", "d", "z"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `adding several items appends them in order`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.addToQueue(listOf(track("x"), track("y")))

        assertEquals(listOf("b", "c", "d", "x", "y"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `next moves the current item into history`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.next(auto = false)

        assertEquals("b", queue.snapshot.current?.id)
        assertEquals(listOf("a"), queue.snapshot.history.map { it.id })
        assertEquals(listOf("c", "d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `previous restarts inside the threshold and rewinds outside it`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.next(auto = false)
        assertEquals("b", queue.snapshot.current?.id)

        queue.previous(4_000L)
        assertEquals("b", queue.snapshot.current?.id)

        queue.previous(1_000L)
        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("b", "c", "d"), queue.snapshot.upcoming.map { it.id })
        assertTrue(queue.snapshot.history.isEmpty())
    }

    @Test fun `shuffle only reorders upcoming and restores it when switched off`() {
        val queue = SpotifyQueueRepository(Random(7))
        queue.play(tracks, 0)
        val original = queue.snapshot.upcoming.map { it.id }

        queue.toggleShuffle()
        assertTrue(queue.snapshot.shuffle)
        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(original.toSet(), queue.snapshot.upcoming.map { it.id }.toSet())

        queue.toggleShuffle()
        assertEquals(original, queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `adopting the same shuffle state does not reorder the tail`() {
        val queue = SpotifyQueueRepository(Random(7))
        queue.play(tracks, 0)

        queue.setShuffle(true)
        val shuffled = queue.snapshot.upcoming.map { it.id }
        queue.setShuffle(true)

        assertEquals(shuffled, queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `adopting shuffle off restores the original tail`() {
        val queue = SpotifyQueueRepository(Random(7))
        queue.play(tracks, 0)
        val original = queue.snapshot.upcoming.map { it.id }

        queue.setShuffle(true)
        queue.setShuffle(false)

        assertEquals(original, queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `repeat off stops at the end of the queue`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks.take(2), 0)

        queue.next(auto = true)
        queue.next(auto = true)

        assertNull(queue.snapshot.current)
    }

    @Test fun `repeat context replays the collection when the queue runs out`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks.take(2), 0)
        queue.setRepeat(RepeatMode.Context)

        queue.next(auto = true)
        queue.next(auto = true)

        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("b"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `repeat track leaves the current item in place`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.setRepeat(RepeatMode.Track)

        queue.next(auto = true)

        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("b", "c", "d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `jump to an upcoming item moves the skipped items into history`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.jumpTo("c")

        assertEquals("c", queue.snapshot.current?.id)
        assertEquals(listOf("a", "b"), queue.snapshot.history.map { it.id })
        assertEquals(listOf("d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `removing history only drops the chosen item`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.next(auto = false)
        queue.next(auto = false)

        queue.removeHistory(track("a"))

        assertEquals(listOf("b"), queue.snapshot.history.map { it.id })
    }

    @Test fun `moving an upcoming item reorders only the tail`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.moveUpcoming(from = 2, to = 0)

        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("d", "b", "c"), queue.snapshot.upcoming.map { it.id })
        assertTrue(queue.snapshot.history.isEmpty())
    }

    @Test fun `moving an upcoming item clamps to the tail and ignores a no-op`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.moveUpcoming(from = 0, to = 99)
        assertEquals(listOf("c", "d", "b"), queue.snapshot.upcoming.map { it.id })

        queue.moveUpcoming(from = 1, to = 1)
        queue.moveUpcoming(from = 9, to = 0)
        assertEquals(listOf("c", "d", "b"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `a manual reorder survives switching shuffle off`() {
        val queue = SpotifyQueueRepository(Random(7))
        queue.play(tracks, 0)
        queue.setShuffle(true)

        queue.moveUpcoming(from = 0, to = 2)
        val arranged = queue.snapshot.upcoming.map { it.id }
        queue.setShuffle(false)

        assertEquals(arranged, queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `clearing upcoming can be undone`() {
        val queue = SpotifyQueueRepository(Random(1))
        queue.play(tracks, 0)

        val removed = queue.clearUpcoming()
        assertTrue(queue.snapshot.upcoming.isEmpty())

        queue.restoreUpcoming(removed)
        assertEquals(listOf("b", "c", "d"), queue.snapshot.upcoming.map { it.id })
    }

    private fun track(id: String) = SpotifyTrack(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )
}
