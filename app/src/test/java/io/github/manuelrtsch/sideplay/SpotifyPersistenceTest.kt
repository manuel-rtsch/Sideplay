package io.github.manuelrtsch.sideplay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sideplay.data.FileSpotifyHistoryStore
import io.github.manuelrtsch.sideplay.data.FileSpotifyQueueStore
import io.github.manuelrtsch.sideplay.data.SpotifyHistoryRepository
import io.github.manuelrtsch.sideplay.data.SpotifyQueueRepository
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tracks = listOf(
        track("a"),
        track("b"),
        track("c"),
        track("d")
    )

    @Test fun `queue survives a process restart with shuffle and history`() {
        val store = FileSpotifyQueueStore(tempFile("queue"))
        val first = SpotifyQueueRepository(Random(1), load = store::read, save = store::write)
        first.play(tracks, 0)
        first.play(tracks, 1)
        first.toggleShuffle()
        first.addToQueue(episode("e"))

        val second = SpotifyQueueRepository(Random(2), load = store::read, save = store::write)

        assertEquals(first.snapshot, second.snapshot)
        assertEquals("b", second.snapshot.current?.id)
        assertEquals(listOf("a"), second.snapshot.history.map { it.id })
        assertTrue(second.snapshot.shuffle)
        assertEquals(listOf("c", "d"), second.snapshot.shuffleBackup?.map { it.id })
        assertTrue(second.snapshot.upcoming.last() is SpotifyEpisode)
    }

    @Test fun `recently played history survives a process restart`() {
        val store = FileSpotifyHistoryStore(tempFile("history"))
        val first = SpotifyHistoryRepository(load = store::read, save = store::write)
        first.record(track("a"), 10)
        first.record(episode("b"), 20)

        val second = SpotifyHistoryRepository(load = store::read, save = store::write)

        assertEquals(listOf("b", "a"), second.items().map { it.id })
        assertEquals(20L, second.snapshot().first().playedAtMs)
    }

    @Test fun `removing a history entry persists the shorter list`() {
        val store = FileSpotifyHistoryStore(tempFile("history-remove"))
        val history = SpotifyHistoryRepository(load = store::read, save = store::write)
        history.record(track("a"), 10)
        history.record(track("b"), 20)

        history.remove(track("b"))

        assertEquals(listOf("a"), SpotifyHistoryRepository(load = store::read).items().map { it.id })
    }

    @Test fun `a corrupt queue file falls back to an empty queue`() {
        val file = tempFile("corrupt").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val snapshot = FileSpotifyQueueStore(file).read()

        assertEquals(SpotifyQueueRepository.Snapshot(), snapshot)
    }

    private fun tempFile(name: String) = File(context.cacheDir, "sideplay-$name-${System.nanoTime()}.bin").also { it.delete() }

    private fun track(id: String) = SpotifyTrack(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )

    private fun episode(id: String) = SpotifyEpisode(
        id = id,
        title = "Episode $id",
        show = "Show",
        showId = "show",
        published = "Today",
        artRes = 0,
        durationMs = 1_800_000L
    )
}
