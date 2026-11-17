package io.github.manuelrtsch.sidetrack.data

import androidx.core.util.AtomicFile
import io.github.manuelrtsch.sidetrack.Episode
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.RepeatMode
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
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
 *
 * Version 2 adds a service id to every record. Version 1 files are read as Spotify and rewritten
 * as version 2 on the next save, so an existing install keeps its queue and history.
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
                if (input.readInt() != MAGIC) return SpotifyQueueRepository.Snapshot()
                when (input.readInt()) {
                    VERSION_1 -> input.readQueue(service = ServiceId.Spotify, versioned = false)
                    VERSION_2 -> input.readQueue(service = null, versioned = true)
                    else -> SpotifyQueueRepository.Snapshot()
                }
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
            data.writeInt(VERSION_2)
            data.writeBoolean(snapshot.shuffle)
            data.writeInt(snapshot.repeat.ordinal)
            data.writeItemOrNull(snapshot.current, versioned = true)
            data.writeItems(snapshot.upcoming, versioned = true)
            data.writeItems(snapshot.history, versioned = true)
            data.writeItems(snapshot.context, versioned = true)
            data.writeItemsOrNull(snapshot.shuffleBackup, versioned = true)
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun DataInputStream.readQueue(service: ServiceId?, versioned: Boolean): SpotifyQueueRepository.Snapshot {
        val shuffle = readBoolean()
        val repeat = RepeatMode.entries.getOrNull(readInt()) ?: RepeatMode.Off
        return SpotifyQueueRepository.Snapshot(
            current = readItemOrNull(service, versioned),
            upcoming = readItems(service, versioned),
            history = readItems(service, versioned),
            shuffle = shuffle,
            repeat = repeat,
            context = readItems(service, versioned),
            shuffleBackup = readItemsOrNull(service, versioned)
        )
    }

    private companion object {
        const val MAGIC = 0x534B5451 // "SKTQ"
        const val VERSION_1 = 1
        const val VERSION_2 = 2
    }
}

/**
 * Where the last session left off: the item that was playing and the offset it had reached, so
 * reopening Sidetrack can pick the same track up at the same second instead of starting at 0:00.
 */
data class SpotifyResumePoint(val key: MediaKey, val positionMs: Long) {
    /** Legacy accessor for the Spotify adapter and the existing UI. */
    val trackId: String get() = key.id

    /** Legacy constructor: a bare id is a Spotify track. */
    constructor(trackId: String, positionMs: Long) : this(
        MediaKey(ServiceId.Spotify, MediaType.Track, trackId),
        positionMs
    )
}

/** Persists the resume point. A missing or unreadable file simply means "start at the beginning". */
interface SpotifyPositionStore {
    fun read(): SpotifyResumePoint?
    fun write(point: SpotifyResumePoint?)
}

class FileSpotifyPositionStore(file: File) : SpotifyPositionStore {
    private val file = AtomicFile(file)

    override fun read(): SpotifyResumePoint? {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return null
        }
        return try {
            DataInputStream(BufferedInputStream(stream)).use { input ->
                if (input.readInt() != MAGIC) return null
                when (input.readInt()) {
                    VERSION_1 -> {
                        val id = input.readUTF()
                        val position = input.readLong()
                        if (id.isEmpty()) null else SpotifyResumePoint(id, position.coerceAtLeast(0L))
                    }

                    VERSION_2 -> {
                        val encoded = input.readUTF()
                        val position = input.readLong()
                        val key = MediaKey.decode(encoded) ?: return null
                        SpotifyResumePoint(key, position.coerceAtLeast(0L))
                    }

                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun write(point: SpotifyResumePoint?) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION_2)
            data.writeUTF(point?.key?.encode().orEmpty())
            data.writeLong(point?.positionMs ?: 0L)
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B5452 // "SKTR"
        const val VERSION_1 = 1
        const val VERSION_2 = 2
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
                if (input.readInt() != MAGIC) return emptyList()
                when (input.readInt()) {
                    VERSION_1 -> input.readHistory(service = ServiceId.Spotify, versioned = false)
                    VERSION_2 -> input.readHistory(service = null, versioned = true)
                    else -> emptyList()
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
            data.writeInt(VERSION_2)
            data.writeInt(entries.size)
            entries.forEach { entry ->
                data.writeItem(entry.item, versioned = true)
                data.writeLong(entry.playedAtMs)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun DataInputStream.readHistory(service: ServiceId?, versioned: Boolean): List<SpotifyHistoryEntry> = List(readCount(MAX_HISTORY)) {
        SpotifyHistoryEntry(readItem(service, versioned), readLong())
    }

    private companion object {
        const val MAGIC = 0x534B5448 // "SKTH"
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val MAX_HISTORY = 10_000
    }
}

private const val TYPE_TRACK = 1
private const val TYPE_EPISODE = 2
private const val MAX_ITEMS = 100_000

private fun DataOutputStream.writeItems(items: List<MediaItem>, versioned: Boolean) {
    writeInt(items.size)
    items.forEach { writeItem(it, versioned) }
}

private fun DataOutputStream.writeItemsOrNull(items: List<MediaItem>?, versioned: Boolean) {
    writeBoolean(items != null)
    items?.let { writeItems(it, versioned) }
}

private fun DataOutputStream.writeItemOrNull(item: MediaItem?, versioned: Boolean) {
    writeBoolean(item != null)
    item?.let { writeItem(it, versioned) }
}

private fun DataOutputStream.writeItem(item: MediaItem, versioned: Boolean) {
    if (versioned) writeUTF(item.service.value)
    when (item) {
        is Track -> {
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

        is Episode -> {
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

private fun DataInputStream.readItems(service: ServiceId?, versioned: Boolean): List<MediaItem> = List(readCount(MAX_ITEMS)) { readItem(service, versioned) }

private fun DataInputStream.readItemsOrNull(service: ServiceId?, versioned: Boolean): List<MediaItem>? = if (readBoolean()) readItems(service, versioned) else null

private fun DataInputStream.readItemOrNull(service: ServiceId?, versioned: Boolean): MediaItem? = if (readBoolean()) readItem(service, versioned) else null

private fun DataInputStream.readItem(service: ServiceId?, versioned: Boolean): MediaItem {
    val itemService = if (versioned) ServiceId(readUTF()) else service ?: ServiceId.Spotify
    return when (val type = readUnsignedByte()) {
        TYPE_TRACK -> Track(
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
            artUrl = readNullableString(),
            service = itemService
        )

        TYPE_EPISODE -> Episode(
            id = readUTF(),
            title = readUTF(),
            show = readUTF(),
            showId = readUTF(),
            published = readUTF(),
            artRes = readInt(),
            durationMs = readLong(),
            video = readBoolean(),
            uri = readNullableString(),
            artUrl = readNullableString(),
            service = itemService
        )

        else -> throw IOException("Unknown Sidetrack media item type $type")
    }
}

private fun DataInputStream.readCount(max: Int): Int {
    val count = readInt()
    if (count !in 0..max) throw IOException("Invalid Sidetrack item count $count")
    return count
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null
