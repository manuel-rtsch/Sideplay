package io.github.manuelrtsch.sideplay.data

import io.github.manuelrtsch.sideplay.SpotifyMediaItem

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

    fun remove(item: SpotifyMediaItem): List<SpotifyHistoryEntry> {
        if (entries.removeAll { it.item.id == item.id }) save(entries.toList())
        return snapshot()
    }

    fun clear(): List<SpotifyHistoryEntry> {
        entries.clear()
        save(emptyList())
        return emptyList()
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
    }
}
