package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.SpotifyMediaItem

/** A played item and when it last started. Timestamps are local and never uploaded. */
data class SpotifyHistoryEntry(
    val item: SpotifyMediaItem,
    val playedAtMs: Long
)

/**
 * Recently played history for Home. Deduplicated by item id, most-recent-first, and bounded so
 * a long-lived account cannot grow the file without limit.
 *
 * Persistence is injected as a pair of lambdas so JVM tests use memory and the app can use the
 * same atomic-file convention as the rest of Sidekit.
 */
class SpotifyHistoryRepository(
    private val limit: Int = DEFAULT_LIMIT,
    private val load: () -> List<SpotifyHistoryEntry> = { emptyList() },
    private val save: (List<SpotifyHistoryEntry>) -> Unit = {}
) {
    private val entries = ArrayDeque<SpotifyHistoryEntry>()

    init {
        // Older builds could persist the same item more than once. Home renders this list with
        // the item id as its LazyColumn key, so a duplicate would crash the draw pass.
        val seen = HashSet<String>()
        load().asSequence()
            .filter { seen.add(it.item.id) }
            .take(limit)
            .forEach(entries::addLast)
    }

    fun snapshot(): List<SpotifyHistoryEntry> = entries.toList()

    fun items(): List<SpotifyMediaItem> = entries.map { it.item }

    /** Marks [item] as most recently played and persists the new bounded list. */
    fun record(item: SpotifyMediaItem, playedAtMs: Long): List<SpotifyHistoryEntry> {
        entries.removeAll { it.item.id == item.id }
        entries.addFirst(SpotifyHistoryEntry(item, playedAtMs))
        while (entries.size > limit) entries.removeLast()
        save(entries.toList())
        return snapshot()
    }

    /** Removes one entry and returns it with its position, so the caller can offer Undo. */
    fun removeEntry(item: SpotifyMediaItem): Removed? {
        val index = entries.indexOfFirst { it.item.id == item.id }
        if (index < 0) return null
        val entry = entries.removeAt(index)
        save(entries.toList())
        return Removed(entry, index)
    }

    fun remove(item: SpotifyMediaItem): List<SpotifyHistoryEntry> {
        removeEntry(item)
        return snapshot()
    }

    /** Puts a removed entry back where it was, unless it has already been played again. */
    fun restore(removed: Removed): List<SpotifyHistoryEntry> {
        if (entries.any { it.item.id == removed.entry.item.id }) return snapshot()
        entries.add(removed.index.coerceIn(0, entries.size), removed.entry)
        save(entries.toList())
        return snapshot()
    }

    data class Removed(val entry: SpotifyHistoryEntry, val index: Int)

    fun clear(): List<SpotifyHistoryEntry> {
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
    fun restoreAll(removed: List<SpotifyHistoryEntry>): List<SpotifyHistoryEntry> {
        if (removed.isEmpty()) return snapshot()
        val present = entries.map { it.item.id }.toSet()
        entries.addAll(removed.filterNot { it.item.id in present })
        save(entries.toList())
        return snapshot()
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
    }
}
