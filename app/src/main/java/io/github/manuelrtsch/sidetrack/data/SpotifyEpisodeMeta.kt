package io.github.manuelrtsch.sidetrack.data

import androidx.core.util.AtomicFile
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.SpotifyEpisode
import io.github.manuelrtsch.sidetrack.SpotifyMediaItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope

/**
 * The parts of an episode that the queue file cannot afford to carry.
 *
 * A description is several kilobytes of HTML, and the queue file holds the whole upcoming list,
 * so persisting episodes inside it would turn every track change into a large rewrite. A show's
 * page fetches episode metadata again anyway; this cache only exists so the item restored into
 * Now Playing still knows its notes, its publishing number and its explicit marker.
 */
data class SpotifyEpisodeMeta(
    val episodeId: String,
    val description: String,
    val episodeNumber: Int?,
    val explicit: Boolean,
    val updatedAtMs: Long,
    val service: ServiceId = ServiceId.Spotify
)

/** Persists [SpotifyEpisodeMeta] by episode id. */
interface SpotifyEpisodeMetaStore {
    fun read(): Map<String, SpotifyEpisodeMeta>
    fun write(entries: Map<String, SpotifyEpisodeMeta>)
}

/** A small, bounded, write-behind cache of episode metadata. */
class SpotifyEpisodeMetaRepository(
    private val load: () -> Map<String, SpotifyEpisodeMeta> = { emptyMap() },
    private val save: (Map<String, SpotifyEpisodeMeta>) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Null writes inline; the app passes a background scope so the queue never waits on disk. */
    saveScope: CoroutineScope? = null
) {
    private var entries: Map<String, SpotifyEpisodeMeta> = prune(runCatching(load).getOrDefault(emptyMap()))
    private val saver = saveScope?.let { scope ->
        ConflatedSaver<Map<String, SpotifyEpisodeMeta>>(scope) { next -> save(next) }
    }

    fun get(episodeId: String): SpotifyEpisodeMeta? = entries[episodeId]

    /** Remembers an episode the player is showing. Cheap to call on every item change. */
    fun remember(episode: SpotifyEpisode) {
        if (episode.id.isEmpty()) return
        val previous = entries[episode.id]
        if (previous != null &&
            previous.description == episode.description &&
            previous.episodeNumber == episode.episodeNumber &&
            previous.explicit == episode.explicit
        ) {
            return
        }
        val next = entries + (
            episode.id to SpotifyEpisodeMeta(
                episodeId = episode.id,
                // `writeUTF` tops out below 64 KiB, and a description longer than this is more
                // than the reading screen can ever need from a restored episode.
                description = episode.description.take(MAX_DESCRIPTION_CHARS),
                episodeNumber = episode.episodeNumber,
                explicit = episode.explicit,
                updatedAtMs = now(),
                service = episode.service
            )
            )
        entries = prune(next)
        val snapshot = entries
        if (saver != null) saver.submit(snapshot) else runCatching { save(snapshot) }
    }

    /** Fills a restored episode's notes and identity from the cache. */
    fun hydrate(item: SpotifyMediaItem?): SpotifyMediaItem? {
        val episode = item as? SpotifyEpisode ?: return item
        if (episode.description.isNotEmpty() && episode.episodeNumber != null && episode.explicit) return item
        val meta = entries[episode.id] ?: return item
        return episode.copy(
            description = episode.description.ifEmpty { meta.description },
            episodeNumber = episode.episodeNumber ?: meta.episodeNumber,
            explicit = episode.explicit || meta.explicit
        )
    }

    /** Hydrates the lists a restored session can start playing from. */
    fun hydrate(snapshot: SpotifyQueueRepository.Snapshot): SpotifyQueueRepository.Snapshot = snapshot.copy(
        current = hydrate(snapshot.current),
        upcoming = snapshot.upcoming.map { hydrate(it) ?: it },
        history = snapshot.history.map { hydrate(it) ?: it }
    )

    fun clearAll() {
        if (entries.isEmpty()) return
        entries = emptyMap()
        val snapshot = entries
        if (saver != null) saver.submit(snapshot) else runCatching { save(snapshot) }
    }

    private fun prune(entries: Map<String, SpotifyEpisodeMeta>): Map<String, SpotifyEpisodeMeta> {
        if (entries.size <= MAX_ENTRIES) return entries
        return entries.values
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_ENTRIES)
            .associateByTo(LinkedHashMap(MAX_ENTRIES * 2)) { it.episodeId }
    }

    private companion object {
        const val MAX_ENTRIES = 200
        const val MAX_DESCRIPTION_CHARS = 8_000
    }
}

/** Binary metadata cache, replaced atomically like the queue, history and resume stores. */
class FileSpotifyEpisodeMetaStore(file: File) : SpotifyEpisodeMetaStore {
    private val file = AtomicFile(file)

    override fun read(): Map<String, SpotifyEpisodeMeta> {
        val stream = try {
            file.openRead()
        } catch (_: IOException) {
            return emptyMap()
        }
        return try {
            DataInputStream(BufferedInputStream(stream)).use { input ->
                if (input.readInt() != MAGIC) return emptyMap()
                val version = input.readInt()
                if (version != VERSION_1 && version != VERSION_2) return emptyMap()
                val count = input.readInt()
                if (count !in 0..MAX_ENTRIES) throw IOException("Invalid episode metadata count $count")
                buildMap {
                    repeat(count) {
                        val service = if (version >= VERSION_2) ServiceId(input.readUTF()) else ServiceId.Spotify
                        val id = input.readUTF()
                        val description = readNullableString(input)
                        val number = input.readInt().takeIf { it > 0 }
                        val explicit = input.readBoolean()
                        val updated = input.readLong()
                        if (id.isNotEmpty()) {
                            put(id, SpotifyEpisodeMeta(id, description, number, explicit, updated, service))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun write(entries: Map<String, SpotifyEpisodeMeta>) {
        val out = file.startWrite()
        try {
            val data = DataOutputStream(BufferedOutputStream(out))
            data.writeInt(MAGIC)
            data.writeInt(VERSION_2)
            val values = entries.values.sortedByDescending { it.updatedAtMs }.take(MAX_ENTRIES)
            data.writeInt(values.size)
            values.forEach { entry ->
                data.writeUTF(entry.service.value)
                data.writeUTF(entry.episodeId)
                writeNullableString(data, entry.description)
                data.writeInt(entry.episodeNumber ?: 0)
                data.writeBoolean(entry.explicit)
                data.writeLong(entry.updatedAtMs)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun writeNullableString(data: DataOutputStream, value: String) {
        data.writeBoolean(value.isNotEmpty())
        if (value.isNotEmpty()) data.writeUTF(value)
    }

    private fun readNullableString(input: DataInputStream): String = if (input.readBoolean()) input.readUTF() else ""

    private companion object {
        const val MAGIC = 0x534B544D // "SKTM"
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val MAX_ENTRIES = 20_000
    }
}
