package io.github.manuelrtsch.sidetrack.data

import androidx.core.util.AtomicFile
import io.github.manuelrtsch.sidetrack.ServiceId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a local podcast resume point needs to know about one episode. */
data class SpotifyEpisodeProgress(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean,
    val service: ServiceId = ServiceId.Spotify
) {
    /** How much of the episode is behind the listener, clamped for the row's progress line. */
    fun fraction(): Float {
        if (completed) return 1f
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

/** Persists podcast progress by episode id. */
interface SpotifyEpisodeProgressStore {
    fun read(): Map<String, SpotifyEpisodeProgress>
    fun write(entries: Map<String, SpotifyEpisodeProgress>)
}

/**
 * Local-only episode progress. It is deliberately separate from the single-session resume file:
 * a show can remember where several episodes were left off, and finishing an episode can clear
 * its resume point without touching anything else.
 */
class SpotifyEpisodeProgressRepository(
    private val load: () -> Map<String, SpotifyEpisodeProgress> = { emptyMap() },
    private val save: (Map<String, SpotifyEpisodeProgress>) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Where [save] runs. Null writes inline, which keeps tests deterministic; the app hands in a
     * background scope so a five-second checkpoint never touches the main thread.
     */
    saveScope: CoroutineScope? = null
) {
    private var entries: Map<String, SpotifyEpisodeProgress> =
        prune(runCatching(load).getOrDefault(emptyMap()))
    private val _progress = MutableStateFlow(displayOf(entries))
    val progress: StateFlow<Map<String, SpotifyEpisodeProgress>> = _progress.asStateFlow()
    private val saver = saveScope?.let { scope ->
        ConflatedSaver<Map<String, SpotifyEpisodeProgress>>(scope) { next -> save(next) }
    }

    fun get(episodeId: String): SpotifyEpisodeProgress? = entries[episodeId]

    /**
     * Records a playback position. Episodes are considered finished when they are within half a
     * minute of the end, which skips outro credits without making short episodes finish instantly.
     */
    fun record(
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean? = null
    ) {
        if (episodeId.isEmpty()) return
        val duration = durationMs.coerceAtLeast(0L)
        val position = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        val finished = completed ?: isComplete(position, duration)
        val previous = entries[episodeId]
        if (previous == null && position == 0L && !finished) return
        if (
            previous != null &&
            previous.positionMs == position &&
            previous.durationMs == duration &&
            previous.completed == finished
        ) {
            return
        }
        commit(entries + (episodeId to SpotifyEpisodeProgress(episodeId, position, duration, now(), finished)))
    }

    /** Marks an episode played or unplayed without inventing a playback position. */
    fun setCompleted(episodeId: String, completed: Boolean, durationMs: Long = 0L) {
        if (episodeId.isEmpty()) return
        val previous = entries[episodeId]
        val duration = durationMs.takeIf { it > 0L } ?: previous?.durationMs ?: 0L
        val position = if (completed) duration else 0L
        commit(entries + (episodeId to SpotifyEpisodeProgress(episodeId, position, duration, now(), completed)))
    }

    /** The offset playback should start at, or zero when there is nothing useful to resume. */
    fun resumePosition(episodeId: String): Long {
        val entry = entries[episodeId] ?: return 0L
        if (entry.completed) return 0L
        return entry.positionMs.takeIf { it >= MIN_RESUME_MS } ?: 0L
    }

    fun isComplete(episodeId: String): Boolean = entries[episodeId]?.completed == true

    fun clear(episodeId: String) {
        if (episodeId !in entries) return
        commit(entries - episodeId)
    }

    fun clearAll() {
        if (entries.isEmpty()) return
        commit(emptyMap())
    }

    private fun isComplete(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs <= 0L || durationMs <= 0L) return false
        val threshold = maxOf(durationMs - COMPLETION_SLACK_MS, durationMs * 95L / 100L)
        return positionMs >= threshold
    }

    private fun commit(next: Map<String, SpotifyEpisodeProgress>) {
        entries = prune(next)
        // The UI only prints whole minutes and draws a 4 dp line, so publishing an entry for every
        // five-second tick would recompose the show page for a change nobody can see.
        val displayed = displayOf(entries)
        if (displayed != _progress.value) _progress.value = displayed
        val snapshot = entries
        if (saver != null) saver.submit(snapshot) else runCatching { save(snapshot) }
    }

    /** Keeps the newest [MAX_ENTRIES] episodes so the file and the map stay bounded. */
    private fun prune(entries: Map<String, SpotifyEpisodeProgress>): Map<String, SpotifyEpisodeProgress> {
        if (entries.size <= MAX_ENTRIES) return entries
        return entries.values
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_ENTRIES)
            .associateByTo(LinkedHashMap(MAX_ENTRIES * 2)) { it.episodeId }
    }

    /** The map the UI reads: positions rounded to the display step, completed at the end. */
    private fun displayOf(
        entries: Map<String, SpotifyEpisodeProgress>
    ): Map<String, SpotifyEpisodeProgress> {
        if (entries.isEmpty()) return entries
        return entries.mapValues { (_, entry) ->
            val position = if (entry.completed) {
                entry.durationMs
            } else {
                entry.positionMs / DISPLAY_STEP_MS * DISPLAY_STEP_MS
            }
            if (position == entry.positionMs) entry else entry.copy(positionMs = position)
        }
    }

    private companion object {
        const val COMPLETION_SLACK_MS = 30_000L
        const val MIN_RESUME_MS = 5_000L

        /** Episodes remembered on disk; older ones are dropped rather than growing the file. */
        const val MAX_ENTRIES = 500

        /** How much the published progress may move before the UI is told about it. */
        const val DISPLAY_STEP_MS = 15_000L
    }
}

/** Binary progress file, replaced atomically like the queue, history and resume stores. */
class FileSpotifyEpisodeProgressStore(file: File) : SpotifyEpisodeProgressStore {
    private val file = AtomicFile(file)

    override fun read(): Map<String, SpotifyEpisodeProgress> {
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
                if (count !in 0..MAX_ENTRIES) throw IOException("Invalid episode progress count $count")
                buildMap {
                    repeat(count) {
                        val service = if (version >= VERSION_2) ServiceId(input.readUTF()) else ServiceId.Spotify
                        val id = input.readUTF()
                        val position = input.readLong().coerceAtLeast(0L)
                        val duration = input.readLong().coerceAtLeast(0L)
                        val updated = input.readLong()
                        val completed = input.readBoolean()
                        if (id.isNotEmpty()) {
                            put(id, SpotifyEpisodeProgress(id, position, duration, updated, completed, service))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun write(entries: Map<String, SpotifyEpisodeProgress>) {
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
                data.writeLong(entry.positionMs)
                data.writeLong(entry.durationMs)
                data.writeLong(entry.updatedAtMs)
                data.writeBoolean(entry.completed)
            }
            data.flush()
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val MAGIC = 0x534B5450 // "SKTP"
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val MAX_ENTRIES = 20_000
    }
}
