package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey

/** A played item and when it last played. Timestamps are local and never uploaded. */
data class HistoryEntry(
    val item: MediaItem,
    val playedAtMs: Long
)

/**
 * Recently played history for Home. Deduplicated by item id, most-recent-first, and bounded so
 * a long-lived account cannot grow the file without limit.
 *
 * Persistence is injected as a pair of lambdas so JVM tests use memory and the app can use the
 * same atomic-file convention as the rest of Sidekit.
 */
class HistoryRepository(
    private val limit: Int = DEFAULT_LIMIT,
    private val load: () -> List<HistoryEntry> = { emptyList() },
    private val save: (List<HistoryEntry>) -> Unit = {}
) {
    private val entries = ArrayDeque<HistoryEntry>()

    init {
        // Older builds could persist the same item more than once. Home renders this list with
        // the item id as its LazyColumn key, so a duplicate would crash the draw pass.
        val seen = HashSet<MediaKey>()
        load().asSequence()
            .filter { seen.add(it.item.key) }
            .take(limit)
            .forEach(entries::addLast)
    }

    fun snapshot(): List<HistoryEntry> = entries.toList()

    fun items(): List<MediaItem> = entries.map { it.item }

    /** Marks [item] as most recently played and persists the new bounded list. */
    fun record(item: MediaItem, playedAtMs: Long): List<HistoryEntry> {
        entries.removeAll { it.item.key == item.key }
        entries.addFirst(HistoryEntry(item, playedAtMs))
        while (entries.size > limit) entries.removeLast()
        save(entries.toList())
        return snapshot()
    }

    /** Removes one entry and returns it with its position, so the caller can offer Undo. */
    fun removeEntry(item: MediaItem): Removed? {
        val index = entries.indexOfFirst { it.item.key == item.key }
        if (index < 0) return null
        val entry = entries.removeAt(index)
        save(entries.toList())
        return Removed(entry, index)
    }

    fun remove(item: MediaItem): List<HistoryEntry> {
        removeEntry(item)
        return snapshot()
    }

    /** Puts a removed entry back where it was, unless it has already been played again. */
    fun restore(removed: Removed): List<HistoryEntry> {
        if (entries.any { it.item.key == removed.entry.item.key }) return snapshot()
        entries.add(removed.index.coerceIn(0, entries.size), removed.entry)
        save(entries.toList())
        return snapshot()
    }

    data class Removed(val entry: HistoryEntry, val index: Int)

    fun clear(): List<HistoryEntry> {
        val removed = entries.toList()
        entries.clear()
        save(emptyList())
        return removed
    }

    /**
     * Puts a cleared list back in the order it had. Entries played again since the clear keep
     * their new position at the front; the restored ones follow, so Undo never reorders what the
     * user did in between.
     */
    fun restoreAll(removed: List<HistoryEntry>): List<HistoryEntry> {
        if (removed.isEmpty()) return snapshot()
        val present = entries.map { it.item.key }.toSet()
        entries.addAll(removed.filterNot { it.item.key in present })
        save(entries.toList())
        return snapshot()
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
    }
}
