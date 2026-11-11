package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.data.SpotifyHistoryEntry
import io.github.manuelrtsch.sideplay.data.SpotifyHistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHistoryRepositoryTest {
    @Test fun `most recent item is first and duplicates move up`() {
        val saved = mutableListOf<List<SpotifyHistoryEntry>>()
        val history = SpotifyHistoryRepository(save = { saved += it })

        history.record(track("a"), 1)
        history.record(track("b"), 2)
        history.record(track("a"), 3)

        assertEquals(listOf("a", "b"), history.items().map { it.id })
        assertEquals(3, saved.last().first().playedAtMs)
    }

    @Test fun `history is bounded and drops the oldest entry`() {
        val history = SpotifyHistoryRepository(limit = 3)

        listOf("a", "b", "c", "d").forEachIndexed { index, id -> history.record(track(id), index.toLong()) }

        assertEquals(listOf("d", "c", "b"), history.items().map { it.id })
    }

    @Test fun `clear empties memory and persists the empty list`() {
        var saved: List<SpotifyHistoryEntry>? = null
        val history = SpotifyHistoryRepository(save = { saved = it })
        history.record(track("a"), 1)

        history.clear()

        assertTrue(history.items().isEmpty())
        assertTrue(saved!!.isEmpty())
    }

    @Test fun `load seeds the repository in order`() {
        val stored = listOf(
            SpotifyHistoryEntry(track("a"), 2),
            SpotifyHistoryEntry(track("b"), 1)
        )
        val history = SpotifyHistoryRepository(load = { stored })

        assertEquals(listOf("a", "b"), history.items().map { it.id })
    }

    @Test fun `load drops duplicate ids from older files`() {
        val stored = listOf(
            SpotifyHistoryEntry(track("a"), 3),
            SpotifyHistoryEntry(track("b"), 2),
            SpotifyHistoryEntry(track("a"), 1)
        )
        val history = SpotifyHistoryRepository(load = { stored })

        assertEquals(listOf("a", "b"), history.items().map { it.id })
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
