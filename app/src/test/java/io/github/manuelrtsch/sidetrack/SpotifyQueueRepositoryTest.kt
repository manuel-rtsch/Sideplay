package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.QueueRepository
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
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.play(tracks, 2)

        assertEquals("c", queue.snapshot.current?.id)
        assertEquals(listOf("d"), queue.snapshot.upcoming.map { it.id })
        assertEquals(listOf("a"), queue.snapshot.history.map { it.id })
    }

    @Test fun `replaying the current item does not duplicate history`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 1)
        queue.play(tracks, 1)

        assertEquals("b", queue.snapshot.current?.id)
        assertTrue(queue.snapshot.history.isEmpty())
    }

    @Test fun `play next moves an existing upcoming item instead of duplicating it`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.playNext(tracks[2])

        assertEquals(listOf("c", "b", "d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `restore adopts a process-wide snapshot`() {
        val queue = QueueRepository(Random(1))
        val snapshot = queue.play(tracks, 1)

        val restored = QueueRepository(Random(2)).restore(snapshot)

        assertEquals("b", restored.current?.id)
        assertEquals(listOf("c", "d"), restored.upcoming.map { it.id })
    }

    @Test fun `add to queue appends`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.addToQueue(track("z"))

        assertEquals(listOf("b", "c", "d", "z"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `adding several items appends them in order`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.addToQueue(listOf(track("x"), track("y")))

        assertEquals(listOf("b", "c", "d", "x", "y"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `items added while shuffled stay in the queue when shuffle is switched off`() {
        val queue = QueueRepository(Random(7))
        queue.play(tracks, 0)
        queue.setShuffle(true)

        queue.addToQueue(listOf(track("x"), track("y")))
        queue.setShuffle(false)

        assertEquals(listOf("b", "c", "d", "x", "y"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `next moves the current item into history`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.next(auto = false)

        assertEquals("b", queue.snapshot.current?.id)
        assertEquals(listOf("a"), queue.snapshot.history.map { it.id })
        assertEquals(listOf("c", "d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `previous restarts inside the threshold and rewinds outside it`() {
        val queue = QueueRepository(Random(1))
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
        val queue = QueueRepository(Random(7))
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
        val queue = QueueRepository(Random(7))
        queue.play(tracks, 0)

        queue.setShuffle(true)
        val shuffled = queue.snapshot.upcoming.map { it.id }
        queue.setShuffle(true)

        assertEquals(shuffled, queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `adopting shuffle off restores the original tail`() {
        val queue = QueueRepository(Random(7))
        queue.play(tracks, 0)
        val original = queue.snapshot.upcoming.map { it.id }

        queue.setShuffle(true)
        queue.setShuffle(false)

        assertEquals(original, queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `repeat off stops at the end of the queue`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks.take(2), 0)

        queue.next(auto = true)
        queue.next(auto = true)

        assertNull(queue.snapshot.current)
    }

    @Test fun `repeat context replays the collection when the queue runs out`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks.take(2), 0)
        queue.setRepeat(RepeatMode.Context)

        queue.next(auto = true)
        queue.next(auto = true)

        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("b"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `repeat track leaves the current item in place`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.setRepeat(RepeatMode.Track)

        queue.next(auto = true)

        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("b", "c", "d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `jump to an upcoming item moves the skipped items into history`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.jumpTo(1)

        assertEquals("c", queue.snapshot.current?.id)
        assertEquals(listOf("a", "b"), queue.snapshot.history.map { it.id })
        assertEquals(listOf("d"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `removing history only drops the chosen item`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.next(auto = false)
        queue.next(auto = false)

        queue.removeHistory(track("a"))

        assertEquals(listOf("b"), queue.snapshot.history.map { it.id })
    }

    @Test fun `removed history can be restored at its old position`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.next(auto = false)
        queue.next(auto = false)

        queue.removeHistory(track("b"))
        queue.restoreHistory(index = 0, item = track("b"))

        assertEquals(listOf("b", "a"), queue.snapshot.history.map { it.id })
    }

    @Test fun `moving an upcoming item reorders only the tail`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.moveUpcoming(from = 2, to = 0)

        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("d", "b", "c"), queue.snapshot.upcoming.map { it.id })
        assertTrue(queue.snapshot.history.isEmpty())
    }

    @Test fun `moving an upcoming item clamps to the tail and ignores a no-op`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.moveUpcoming(from = 0, to = 99)
        assertEquals(listOf("c", "d", "b"), queue.snapshot.upcoming.map { it.id })

        queue.moveUpcoming(from = 1, to = 1)
        queue.moveUpcoming(from = 9, to = 0)
        assertEquals(listOf("c", "d", "b"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `cancelling a manual reorder keeps shuffle and its backup`() {
        val queue = QueueRepository(Random(7))
        queue.play(tracks, 0)
        queue.setShuffle(true)
        val before = queue.snapshot.upcoming.map { it.id }
        val backup = queue.snapshot.shuffleBackup?.map { it.id }

        queue.moveUpcoming(from = 0, to = 2)
        queue.moveUpcoming(from = 2, to = 0)

        assertTrue(queue.snapshot.shuffle)
        assertEquals(before, queue.snapshot.upcoming.map { it.id })
        assertEquals(backup, queue.snapshot.shuffleBackup?.map { it.id })
    }

    @Test fun `finishing a manual reorder turns shuffle off and keeps the chosen order`() {
        val queue = QueueRepository(Random(7))
        queue.play(tracks, 0)
        queue.setShuffle(true)

        queue.moveUpcoming(from = 0, to = 2)
        val arranged = queue.snapshot.upcoming.map { it.id }
        queue.finishReorder()

        assertTrue(!queue.snapshot.shuffle)
        assertNull(queue.snapshot.shuffleBackup)
        assertEquals(arranged, queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `removing one duplicate leaves the others alone`() {
        val queue = QueueRepository(Random(1))
        queue.play(listOf(track("a"), track("b"), track("b"), track("c")), 0)

        val removed = queue.removeUpcoming(1)

        assertEquals(1, removed)
        assertEquals(listOf("b", "c"), queue.snapshot.upcoming.map { it.id })

        queue.restoreRemovedUpcoming(1, track("b"))
        assertEquals(listOf("b", "b", "c"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `play next moves one duplicate and keeps the other`() {
        val queue = QueueRepository(Random(1))
        queue.play(listOf(track("a"), track("c"), track("b"), track("d"), track("b")), 0)

        queue.playNext(track("b"))

        assertEquals(listOf("b", "c", "d", "b"), queue.snapshot.upcoming.map { it.id })
    }

    @Test fun `jumping to one duplicate uses the occurrence index`() {
        val queue = QueueRepository(Random(1))
        queue.play(listOf(track("a"), track("b"), track("b"), track("c")), 0)

        queue.jumpTo(2)

        assertEquals("c", queue.snapshot.current?.id)
        assertTrue(queue.snapshot.upcoming.isEmpty())
    }

    @Test fun `stop clears the current item, upcoming, history and context`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        queue.next(auto = false)

        queue.stop()

        assertNull(queue.snapshot.current)
        assertTrue(queue.snapshot.upcoming.isEmpty())
        assertTrue(queue.snapshot.history.isEmpty())
        assertTrue(queue.snapshot.context.isEmpty())
        assertNull(queue.snapshot.shuffleBackup)
    }

    @Test fun `previous at the first item restarts instead of rewinding`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)

        queue.previous(1_000L)

        assertEquals("a", queue.snapshot.current?.id)
        assertEquals(listOf("b", "c", "d"), queue.snapshot.upcoming.map { it.id })
        assertTrue(queue.snapshot.history.isEmpty())
    }

    @Test fun `an unplayable item does not replace the queue`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)
        val before = queue.snapshot

        queue.play(listOf(track("x").copy(playable = false)), 0)

        assertEquals(before, queue.snapshot)
    }

    @Test fun `history is bounded to the most recent twenty items`() {
        val queue = QueueRepository(Random(1))
        val many = (1..25).map { track("t$it") }
        queue.play(many, 0)

        repeat(24) { queue.next(auto = false) }

        assertEquals(20, queue.snapshot.history.size)
        assertEquals("t5", queue.snapshot.history.last().id)
    }

    @Test fun `clearing upcoming leaves shuffle off`() {
        val queue = QueueRepository(Random(7))
        queue.play(tracks, 0)
        queue.setShuffle(true)

        queue.clearUpcoming()

        assertTrue(!queue.snapshot.shuffle)
        assertNull(queue.snapshot.shuffleBackup)
    }

    @Test fun `clearing upcoming can be undone`() {
        val queue = QueueRepository(Random(1))
        queue.play(tracks, 0)

        val removed = queue.clearUpcoming()
        assertTrue(queue.snapshot.upcoming.isEmpty())

        queue.restoreUpcoming(removed)
        assertEquals(listOf("b", "c", "d"), queue.snapshot.upcoming.map { it.id })
    }

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )
}
