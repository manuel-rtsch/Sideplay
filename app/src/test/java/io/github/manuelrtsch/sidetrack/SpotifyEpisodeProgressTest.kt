package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.data.FileSpotifyEpisodeProgressStore
import io.github.manuelrtsch.sidetrack.data.SpotifyEpisodeProgress
import io.github.manuelrtsch.sidetrack.data.SpotifyEpisodeProgressRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyEpisodeProgressTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `episode progress survives a process restart`() {
        val store = FileSpotifyEpisodeProgressStore(tempFile("episode-progress"))
        val first = SpotifyEpisodeProgressRepository(load = store::read, save = store::write, now = { 1_000L })
        first.record("lw5", 900_000L, 2_529_000L)

        val second = SpotifyEpisodeProgressRepository(load = store::read, save = store::write)

        assertEquals(900_000L, second.resumePosition("lw5"))
        assertEquals(2_529_000L, second.get("lw5")?.durationMs)
    }

    @Test fun `a completed episode restarts from the beginning`() {
        val repository = SpotifyEpisodeProgressRepository(now = { 1_000L })
        repository.record("lw5", 2_520_000L, 2_529_000L)

        assertTrue(repository.isComplete("lw5"))
        assertEquals(0L, repository.resumePosition("lw5"))
    }

    @Test fun `a short listen is not worth resuming`() {
        val repository = SpotifyEpisodeProgressRepository(now = { 1_000L })
        repository.record("lw5", 2_000L, 2_529_000L)

        assertEquals(0L, repository.resumePosition("lw5"))
    }

    @Test fun `starting an episode does not create an empty progress entry`() {
        val repository = SpotifyEpisodeProgressRepository(now = { 1_000L })
        repository.record("lw5", 0L, 2_529_000L)

        assertEquals(null, repository.get("lw5"))
    }

    @Test fun `clearing one episode leaves the others alone`() {
        val repository = SpotifyEpisodeProgressRepository(now = { 1_000L })
        repository.record("lw4", 100_000L, 2_307_000L)
        repository.record("lw5", 200_000L, 2_529_000L)

        repository.clear("lw4")

        assertEquals(null, repository.get("lw4"))
        assertEquals(200_000L, repository.resumePosition("lw5"))
    }

    @Test fun `marking played and unplayed round-trips through storage`() {
        val file = tempFile("episode-progress-played")
        val store = FileSpotifyEpisodeProgressStore(file)
        val repository = SpotifyEpisodeProgressRepository(load = store::read, save = store::write, now = { 1_000L })
        repository.record("lw5", 400_000L, 2_529_000L)
        repository.setCompleted("lw5", true)

        val reloaded = FileSpotifyEpisodeProgressStore(file).read()
        assertEquals(true, reloaded["lw5"]?.completed)
        assertEquals(false, repository.get("lw5")?.completed?.not())

        repository.setCompleted("lw5", false)
        assertEquals(false, repository.get("lw5")?.completed)
        assertEquals(0L, repository.resumePosition("lw5"))
    }

    @Test fun `a corrupt progress file starts empty`() {
        val file = tempFile("episode-progress-corrupt").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }

        assertEquals(emptyMap<String, SpotifyEpisodeProgress>(), FileSpotifyEpisodeProgressStore(file).read())
    }

    private fun tempFile(name: String): File = File(context.cacheDir, "sidetrack-$name-${System.nanoTime()}.bin").also { it.delete() }
}
