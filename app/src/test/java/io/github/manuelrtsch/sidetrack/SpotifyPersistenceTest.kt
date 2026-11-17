package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.data.FileHistoryStore
import io.github.manuelrtsch.sidetrack.data.FilePositionStore
import io.github.manuelrtsch.sidetrack.data.FileQueueStore
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import java.io.BufferedOutputStream
import java.io.DataOutputStream
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
        val store = FileQueueStore(tempFile("queue"))
        val first = QueueRepository(Random(1), load = store::read, save = store::write)
        first.play(tracks, 0)
        first.play(tracks, 1)
        first.toggleShuffle()
        first.addToQueue(episode("e"))

        val second = QueueRepository(Random(2), load = store::read, save = store::write)

        assertEquals(first.snapshot, second.snapshot)
        assertEquals("b", second.snapshot.current?.id)
        assertEquals(listOf("a"), second.snapshot.history.map { it.id })
        assertTrue(second.snapshot.shuffle)
        assertEquals(listOf("c", "d", "e"), second.snapshot.shuffleBackup?.map { it.id })
        assertTrue(second.snapshot.upcoming.last() is Episode)
    }

    @Test fun `queue keeps repeat and its original context across a restart`() {
        val store = FileQueueStore(tempFile("queue-context"))
        val first = QueueRepository(Random(1), load = store::read, save = store::write)
        first.play(tracks, 1)
        first.setRepeat(RepeatMode.Context)

        val second = QueueRepository(Random(2), load = store::read, save = store::write)

        assertEquals(RepeatMode.Context, second.snapshot.repeat)
        assertEquals(tracks.map { it.id }, second.snapshot.context.map { it.id })
        assertEquals("b", second.snapshot.current?.id)
    }

    @Test fun `a queue file from an unknown version falls back to an empty queue`() {
        val file = tempFile("queue-version")
        val store = FileQueueStore(file)
        store.write(QueueRepository.Snapshot(current = track("a")))
        val bytes = file.readBytes()
        // MAGIC is the first int; the version follows it. Bump it to a version this build cannot read.
        bytes[7] = 99
        file.writeBytes(bytes)

        assertEquals(QueueRepository.Snapshot(), FileQueueStore(file).read())
    }

    @Test fun `recently played history survives a process restart`() {
        val store = FileHistoryStore(tempFile("history"))
        val first = HistoryRepository(load = store::read, save = store::write)
        first.record(track("a"), 10)
        first.record(episode("b"), 20)

        val second = HistoryRepository(load = store::read, save = store::write)

        assertEquals(listOf("b", "a"), second.items().map { it.id })
        assertEquals(20L, second.snapshot().first().playedAtMs)
    }

    @Test fun `removing a history entry persists the shorter list`() {
        val store = FileHistoryStore(tempFile("history-remove"))
        val history = HistoryRepository(load = store::read, save = store::write)
        history.record(track("a"), 10)
        history.record(track("b"), 20)

        history.remove(track("b"))

        assertEquals(listOf("a"), HistoryRepository(load = store::read).items().map { it.id })
    }

    @Test fun `a corrupt queue file falls back to an empty queue`() {
        val file = tempFile("corrupt").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val snapshot = FileQueueStore(file).read()

        assertEquals(QueueRepository.Snapshot(), snapshot)
    }

    @Test fun `resume point survives a restart with its offset`() {
        val file = tempFile("resume")
        FilePositionStore(file).write(ResumePoint(MediaKey(ServiceId.Spotify, MediaType.Track, "b"), 42_500L))

        assertEquals(ResumePoint(MediaKey(ServiceId.Spotify, MediaType.Track, "b"), 42_500L), FilePositionStore(file).read())
    }

    @Test fun `a cleared resume point reads back as nothing`() {
        val file = tempFile("resume-cleared")
        val store = FilePositionStore(file)
        store.write(ResumePoint(MediaKey(ServiceId.Spotify, MediaType.Track, "b"), 1_000L))
        store.write(null)

        assertEquals(null, FilePositionStore(file).read())
    }

    @Test fun `a corrupt resume point falls back to the start`() {
        val file = tempFile("resume-corrupt")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertEquals(null, FilePositionStore(file).read())
    }

    @Test fun `a version 1 queue file is read as Spotify and rewritten as version 2`() {
        val file = tempFile("queue-v1")
        writeV1Queue(file, listOf("a", "b"), current = "b")

        val store = FileQueueStore(file)
        val snapshot = store.read()

        assertEquals("b", snapshot.current?.id)
        assertEquals(ServiceId.Spotify, snapshot.current?.service)
        assertEquals(listOf("a", "b"), snapshot.upcoming.map { it.id })
        assertEquals(MediaKey.spotify(MediaType.Track, "b"), snapshot.current?.key)

        // The next save upgrades the file; reading it back must still produce the same queue.
        store.write(snapshot)
        assertEquals(snapshot, FileQueueStore(file).read())
    }

    @Test fun `a version 1 history file is read as Spotify`() {
        val file = tempFile("history-v1")
        writeV1History(file, listOf("a", "b"))

        val entries = FileHistoryStore(file).read()

        assertEquals(listOf("a", "b"), entries.map { it.item.id })
        assertTrue(entries.all { it.item.service == ServiceId.Spotify })
    }

    @Test fun `a version 1 resume point is read as a Spotify track`() {
        val file = tempFile("resume-v1")
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(0x534B5452)
            out.writeInt(1)
            out.writeUTF("b")
            out.writeLong(42_500L)
        }

        assertEquals(ResumePoint(MediaKey(ServiceId.Spotify, MediaType.Track, "b"), 42_500L), FilePositionStore(file).read())
    }

    @Test fun `a version 2 queue keeps the service of each item`() {
        val file = tempFile("queue-service")
        val navidrome = track("a").copy(service = ServiceId.Navidrome)
        FileQueueStore(file).write(QueueRepository.Snapshot(current = navidrome))

        val read = FileQueueStore(file).read()

        assertEquals(ServiceId.Navidrome, read.current?.service)
        assertEquals(MediaKey(ServiceId.Navidrome, MediaType.Track, "a"), read.current?.key)
    }

    @Test fun `media keys keep equal ids from different services apart`() {
        val spotify = MediaKey(ServiceId.Spotify, MediaType.Track, "same")
        val navidrome = MediaKey(ServiceId.Navidrome, MediaType.Track, "same")

        assertEquals(spotify, MediaKey.decode(spotify.encode()))
        assertEquals(navidrome, MediaKey.decode(navidrome.encode()))
        assertTrue(spotify != navidrome)
        assertEquals(null, MediaKey.decode("not-a-key"))
    }

    private fun writeV1Queue(file: File, ids: List<String>, current: String?) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(0x534B5451)
            out.writeInt(1)
            out.writeBoolean(false)
            out.writeInt(RepeatMode.Off.ordinal)
            out.writeBoolean(current != null)
            current?.let { writeV1Track(out, it) }
            out.writeInt(ids.size)
            ids.forEach { writeV1Track(out, it) }
            out.writeInt(0)
            out.writeInt(0)
            out.writeBoolean(false)
        }
    }

    private fun writeV1History(file: File, ids: List<String>) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeInt(0x534B5448)
            out.writeInt(1)
            out.writeInt(ids.size)
            ids.forEachIndexed { index, id ->
                writeV1Track(out, id)
                out.writeLong(index.toLong())
            }
        }
    }

    private fun writeV1Track(out: DataOutputStream, id: String) {
        out.writeByte(1)
        out.writeUTF(id)
        out.writeUTF("Track $id")
        out.writeUTF("Artist")
        out.writeUTF("artist")
        out.writeUTF("album")
        out.writeInt(0)
        out.writeLong(180_000L)
        out.writeBoolean(false)
        out.writeBoolean(true)
        out.writeBoolean(false)
        out.writeBoolean(false)
    }

    private fun tempFile(name: String) = File(context.cacheDir, "sidetrack-$name-${System.nanoTime()}.bin").also { it.delete() }

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )

    private fun episode(id: String) = Episode(
        id = id,
        title = "Episode $id",
        show = "Show",
        showId = "show",
        published = "Today",
        artRes = 0,
        durationMs = 1_800_000L
    )
}
