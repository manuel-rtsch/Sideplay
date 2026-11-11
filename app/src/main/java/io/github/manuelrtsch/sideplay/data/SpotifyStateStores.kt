package io.github.manuelrtsch.sideplay.data

import androidx.core.util.AtomicFile
import io.github.manuelrtsch.sideplay.RepeatMode
import io.github.manuelrtsch.sideplay.SpotifyEpisode
import io.github.manuelrtsch.sideplay.SpotifyMediaItem
import io.github.manuelrtsch.sideplay.SpotifyTrack
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Persists the process-wide queue, including its history and the pre-shuffle order. */
interface SpotifyQueueStore {
    fun read(): SpotifyQueueRepository.Snapshot
    fun write(snapshot: SpotifyQueueRepository.Snapshot)
}

/** Persists the recently played list shown on Home. */
interface SpotifyHistoryStore {
    fun read(): List<SpotifyHistoryEntry>
    fun write(entries: List<SpotifyHistoryEntry>)
}

/**
 * Small binary files replaced atomically ([AtomicFile]), matching playlists, reminders and
 * Sidenotes. A corrupt or missing file falls back to an empty state instead of blocking startup.
 */
class FileSpotifyQueueStore(file: File) : SpotifyQueueStore {
    private val file = AtomicFile(file)

    override fun read(): SpotifyQueueRepository.Snapshot {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return SpotifyQueueRepository.Snapshot()
        }
        return try {
            DataInputStream(BufferedInputStream(stream)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return SpotifyQueueRepository.Snapshot()
                val shuffle = input.readBoolean()
                val repeat = RepeatMode.entries.getOrNull(input.readInt()) ?: RepeatMode.Off
                SpotifyQueueRepository.Snapshot(
                    current = input.readItemOrNull(),
                    upcoming = input.readItems(),
                    history = input.readItems(),
                    shuffle = shuffle,
                    repeat = repeat,
                    context = input.readItems(),
                    shuffleBackup = input.readItemsOrNull()
                )
            }
        } catch (_: Exception) {
            SpotifyQueueRepository.Snapshot()
        }
    }

    override fun write(snapshot: SpotifyQueueRepository.Snapshot) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeBoolean(snapshot.shuffle)
            data.writeInt(snapshot.repeat.ordinal)
            data.writeItemOrNull(snapshot.current)
            data.writeItems(snapshot.upcoming)
            data.writeItems(snapshot.history)
            data.writeItems(snapshot.context)
            data.writeItemsOrNull(snapshot.shuffleBackup)
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B5451 // "SKTQ"
        const val VERSION = 1
    }
}

class FileSpotifyHistoryStore(file: File) : SpotifyHistoryStore {
    private val file = AtomicFile(file)

    override fun read(): List<SpotifyHistoryEntry> {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return emptyList()
        }
        return try {
            DataInputStream(BufferedInputStream(stream)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
                List(input.readCount(MAX_HISTORY)) {
                    SpotifyHistoryEntry(input.readItem(), input.readLong())
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun write(entries: List<SpotifyHistoryEntry>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(entries.size)
            entries.forEach { entry ->
                data.writeItem(entry.item)
                data.writeLong(entry.playedAtMs)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B5448 // "SKTH"
        const val VERSION = 1
        const val MAX_HISTORY = 10_000
    }
}

private const val TYPE_TRACK = 1
private const val TYPE_EPISODE = 2
private const val MAX_ITEMS = 100_000

private fun DataOutputStream.writeItems(items: List<SpotifyMediaItem>) {
    writeInt(items.size)
    items.forEach(::writeItem)
}

private fun DataOutputStream.writeItemsOrNull(items: List<SpotifyMediaItem>?) {
    writeBoolean(items != null)
    items?.let(::writeItems)
}

private fun DataOutputStream.writeItemOrNull(item: SpotifyMediaItem?) {
    writeBoolean(item != null)
    item?.let(::writeItem)
}

private fun DataOutputStream.writeItem(item: SpotifyMediaItem) {
    when (item) {
        is SpotifyTrack -> {
            writeByte(TYPE_TRACK)
            writeUTF(item.id)
            writeUTF(item.title)
            writeUTF(item.artist)
            writeUTF(item.artistId)
            writeUTF(item.albumId)
            writeInt(item.artRes)
            writeLong(item.durationMs)
            writeBoolean(item.explicit)
            writeBoolean(item.playable)
            writeNullableString(item.uri)
            writeNullableString(item.artUrl)
        }

        is SpotifyEpisode -> {
            writeByte(TYPE_EPISODE)
            writeUTF(item.id)
            writeUTF(item.title)
            writeUTF(item.show)
            writeUTF(item.showId)
            writeUTF(item.published)
            writeInt(item.artRes)
            writeLong(item.durationMs)
            writeBoolean(item.video)
            writeNullableString(item.uri)
            writeNullableString(item.artUrl)
        }
    }
}

private fun DataInputStream.readItems(): List<SpotifyMediaItem> = List(readCount(MAX_ITEMS)) { readItem() }

private fun DataInputStream.readItemsOrNull(): List<SpotifyMediaItem>? = if (readBoolean()) readItems() else null

private fun DataInputStream.readItemOrNull(): SpotifyMediaItem? = if (readBoolean()) readItem() else null

private fun DataInputStream.readItem(): SpotifyMediaItem = when (val type = readUnsignedByte()) {
    TYPE_TRACK -> SpotifyTrack(
        id = readUTF(),
        title = readUTF(),
        artist = readUTF(),
        artistId = readUTF(),
        albumId = readUTF(),
        artRes = readInt(),
        durationMs = readLong(),
        explicit = readBoolean(),
        playable = readBoolean(),
        uri = readNullableString(),
        artUrl = readNullableString()
    )

    TYPE_EPISODE -> SpotifyEpisode(
        id = readUTF(),
        title = readUTF(),
        show = readUTF(),
        showId = readUTF(),
        published = readUTF(),
        artRes = readInt(),
        durationMs = readLong(),
        video = readBoolean(),
        uri = readNullableString(),
        artUrl = readNullableString()
    )

    else -> throw IOException("Unknown Sideplay media item type $type")
}

private fun DataInputStream.readCount(max: Int): Int {
    val count = readInt()
    if (count !in 0..max) throw IOException("Invalid Sideplay item count $count")
    return count
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null
