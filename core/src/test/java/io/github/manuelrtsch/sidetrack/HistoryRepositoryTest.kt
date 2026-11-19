package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.HistoryEntry
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRepositoryTest {
    @Test fun `most recent item is first and duplicates move up`() {
        val saved = mutableListOf<List<HistoryEntry>>()
        val history = HistoryRepository(save = { saved += it })

        history.record(track("a"), 1)
        history.record(track("b"), 2)
        history.record(track("a"), 3)

        assertEquals(listOf("a", "b"), history.items().map { it.id })
        assertEquals(3, saved.last().first().playedAtMs)
    }

    @Test fun `history is bounded and drops the oldest entry`() {
        val history = HistoryRepository(limit = 3)

        listOf("a", "b", "c", "d").forEachIndexed { index, id -> history.record(track(id), index.toLong()) }

        assertEquals(listOf("d", "c", "b"), history.items().map { it.id })
    }

    @Test fun `removed entry can be restored at its old position`() {
        val history = HistoryRepository()
        history.record(track("a"), 1)
        history.record(track("b"), 2)
        history.record(track("c"), 3)

        val removed = history.removeEntry(track("b"))!!

        assertEquals(listOf("c", "a"), history.items().map { it.id })
        history.restore(removed)
        assertEquals(listOf("c", "b", "a"), history.items().map { it.id })
    }

    @Test fun `clear empties memory and persists the empty list`() {
        var saved: List<HistoryEntry>? = null
        val history = HistoryRepository(save = { saved = it })
        history.record(track("a"), 1)

        history.clear()

        assertTrue(history.items().isEmpty())
        assertTrue(saved!!.isEmpty())
    }

    @Test fun `clear returns the removed entries so Undo can restore them`() {
        val history = HistoryRepository()
        history.record(track("a"), 1)
        history.record(track("b"), 2)

        val removed = history.clear()

        assertEquals(listOf("b", "a"), removed.map { it.item.id })
        assertEquals(listOf(2L, 1L), removed.map { it.playedAtMs })
        history.restoreAll(removed)
        assertEquals(listOf("b", "a"), history.items().map { it.id })
    }

    @Test fun `restoreAll keeps an item played after the clear at the front`() {
        val history = HistoryRepository()
        history.record(track("a"), 1)
        history.record(track("b"), 2)
        val removed = history.clear()

        history.record(track("c"), 3)
        history.restoreAll(removed)

        assertEquals(listOf("c", "b", "a"), history.items().map { it.id })
    }

    @Test fun `removing an unknown item returns null and leaves history alone`() {
        val history = HistoryRepository()
        history.record(track("a"), 1)

        val removed = history.removeEntry(track("missing"))

        assertEquals(null, removed)
        assertEquals(listOf("a"), history.items().map { it.id })
    }

    @Test fun `restoring an item played again does not duplicate it`() {
        val history = HistoryRepository()
        history.record(track("a"), 1)
        history.record(track("b"), 2)
        val removed = history.removeEntry(track("a"))!!

        history.record(track("a"), 3)
        history.restore(removed)

        assertEquals(listOf("a", "b"), history.items().map { it.id })
        assertEquals(3, history.snapshot().first().playedAtMs)
    }

    @Test fun `load seeds the repository in order`() {
        val stored = listOf(
            HistoryEntry(track("a"), 2),
            HistoryEntry(track("b"), 1)
        )
        val history = HistoryRepository(load = { stored })

        assertEquals(listOf("a", "b"), history.items().map { it.id })
    }

    @Test fun `load drops duplicate ids from older files`() {
        val stored = listOf(
            HistoryEntry(track("a"), 3),
            HistoryEntry(track("b"), 2),
            HistoryEntry(track("a"), 1)
        )
        val history = HistoryRepository(load = { stored })

        assertEquals(listOf("a", "b"), history.items().map { it.id })
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
