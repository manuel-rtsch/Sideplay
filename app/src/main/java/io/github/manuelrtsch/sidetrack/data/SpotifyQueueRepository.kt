package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.RepeatMode
import io.github.manuelrtsch.sidetrack.SpotifyMediaItem
import kotlin.random.Random

/**
 * The queue is owned by Sidetrack, not librespot: librespot plays one item at a time and this
 * repository decides what comes next. librespot 0.8 exposes shuffle/repeat events but has no
 * setters, so this repository is authoritative for both; native events are adopted when another
 * client changes them.
 *
 * History and upcoming are plain ordered lists. Shuffle is a view over upcoming only, so the
 * current item and history never move. The repository is deliberately free of Android and
 * Compose types; playback, persistence and the UI observe it through [snapshot].
 */
class SpotifyQueueRepository(
    private val random: Random = Random.Default,
    private val load: () -> Snapshot = { Snapshot() },
    private val save: (Snapshot) -> Unit = {}
) {
    data class Snapshot(
        val current: SpotifyMediaItem? = null,
        val upcoming: List<SpotifyMediaItem> = emptyList(),
        val history: List<SpotifyMediaItem> = emptyList(),
        val shuffle: Boolean = false,
        val repeat: RepeatMode = RepeatMode.Off,
        val context: List<SpotifyMediaItem> = emptyList(),
        /** Upcoming order before shuffle, kept so shuffle can be turned off after process death. */
        val shuffleBackup: List<SpotifyMediaItem>? = null
    )

    var snapshot: Snapshot = runCatching(load).getOrDefault(Snapshot())
        private set

    /** Replaces the queue, used when a new ViewModel adopts the process-wide playback state. */
    fun restore(snapshot: Snapshot): Snapshot = commit(snapshot)

    /**
     * Starts playback from [index] within [items], replacing the queue. The rows after the
     * chosen item become upcoming; the item that was playing moves into history.
     */
    fun play(items: List<SpotifyMediaItem>, index: Int): Snapshot {
        if (index !in items.indices) return snapshot
        val item = items[index]
        if (!item.playable) return snapshot

        val previous = snapshot.current
        val nextUpcoming = items.drop(index + 1).let { if (snapshot.shuffle) it.shuffled(random) else it }
        return commit(
            snapshot.copy(
                current = item,
                upcoming = nextUpcoming,
                history = if (previous == null || previous.id == item.id) {
                    snapshot.history
                } else {
                    (listOf(previous) + snapshot.history).take(HISTORY_LIMIT)
                },
                context = items,
                shuffleBackup = if (snapshot.shuffle) items.drop(index + 1) else null
            )
        )
    }

    fun playItem(item: SpotifyMediaItem): Snapshot = play(listOf(item), 0)

    /** Stops and clears the queue. */
    fun stop(): Snapshot = commit(
        snapshot.copy(
            current = null,
            upcoming = emptyList(),
            history = emptyList(),
            context = emptyList(),
            shuffleBackup = null
        )
    )

    fun toggleShuffle(): Snapshot = setShuffle(!snapshot.shuffle)

    /** Adopts a shuffle change from the native player without re-shuffling an already shuffled tail. */
    fun setShuffle(enabled: Boolean): Snapshot = when {
        enabled == snapshot.shuffle -> snapshot

        enabled -> commit(
            snapshot.copy(
                shuffle = true,
                upcoming = snapshot.upcoming.shuffled(random),
                shuffleBackup = snapshot.upcoming
            )
        )

        else -> commit(
            snapshot.copy(
                shuffle = false,
                upcoming = snapshot.shuffleBackup ?: snapshot.upcoming,
                shuffleBackup = null
            )
        )
    }

    fun clearHistory(): Snapshot = commit(snapshot.copy(history = emptyList()))

    fun removeHistory(item: SpotifyMediaItem): Snapshot = commit(snapshot.copy(history = snapshot.history.filterNot { it.id == item.id }))

    fun cycleRepeat(): Snapshot = commit(snapshot.copy(repeat = snapshot.repeat.next()))

    fun setRepeat(mode: RepeatMode): Snapshot = commit(snapshot.copy(repeat = mode))

    /**
     * Moves to the next upcoming item. [auto] distinguishes the end of a track from the user
     * pressing Next: an empty queue only stops automatically.
     */
    fun next(auto: Boolean): Snapshot {
        val current = snapshot.current ?: return snapshot
        if (auto && snapshot.repeat == RepeatMode.Track) return snapshot
        val next = snapshot.upcoming.firstOrNull()
        if (next != null) {
            return commit(
                snapshot.copy(
                    current = next,
                    upcoming = snapshot.upcoming.drop(1),
                    history = (listOf(current) + snapshot.history).take(HISTORY_LIMIT)
                )
            )
        }
        if (!auto) return snapshot
        return when (snapshot.repeat) {
            RepeatMode.Context -> replayContext()
            RepeatMode.Track -> snapshot
            RepeatMode.Off -> commit(snapshot.copy(current = null, history = snapshot.history))
        }
    }

    /** User pressed Previous. Restarts when past the 3 s threshold. */
    fun previous(positionMs: Long): Snapshot {
        if (snapshot.current == null) return snapshot
        if (positionMs > RESTART_THRESHOLD_MS) return snapshot
        val previous = snapshot.history.firstOrNull() ?: return snapshot
        return commit(
            snapshot.copy(
                current = previous,
                upcoming = listOfNotNull(snapshot.current) + snapshot.upcoming,
                history = snapshot.history.drop(1)
            )
        )
    }

    /**
     * Jumps to one upcoming occurrence. The index, not the track id, identifies the row so that
     * duplicates can be told apart.
     */
    fun jumpTo(index: Int): Snapshot {
        val current = snapshot.current ?: return snapshot
        if (index !in snapshot.upcoming.indices) return snapshot
        val skipped = snapshot.upcoming.take(index)
        val target = snapshot.upcoming[index]
        return commit(
            snapshot.copy(
                current = target,
                upcoming = snapshot.upcoming.drop(index + 1),
                history = (listOf(current) + skipped + snapshot.history).take(HISTORY_LIMIT)
            )
        )
    }

    /**
     * Puts one occurrence directly after the current item. An existing occurrence is moved, not
     * duplicated; the first match is enough because the queue UI has no id-based Play next.
     */
    fun playNext(item: SpotifyMediaItem): Snapshot {
        if (item.id == snapshot.current?.id) return snapshot
        val upcoming = snapshot.upcoming.toMutableList()
        val at = upcoming.indexOfFirst { it.id == item.id }
        if (at >= 0) upcoming.removeAt(at)
        upcoming.add(0, item)
        return commit(snapshot.copy(upcoming = upcoming))
    }

    fun addToQueue(item: SpotifyMediaItem): Snapshot = addToQueue(listOf(item))

    fun addToQueue(items: List<SpotifyMediaItem>): Snapshot = commit(snapshot.copy(upcoming = snapshot.upcoming + items))

    /**
     * Removes one upcoming occurrence by index and returns where it was, so the caller can offer
     * Undo. Removing by index is what keeps a duplicated track from taking its other copies with
     * it.
     */
    fun removeUpcoming(index: Int): Int {
        if (index !in snapshot.upcoming.indices) return -1
        commit(snapshot.copy(upcoming = snapshot.upcoming.filterIndexed { at, _ -> at != index }))
        return index
    }

    /** Puts a removed occurrence back where it was, or as close to it as the tail allows. */
    fun restoreRemovedUpcoming(index: Int, item: SpotifyMediaItem): Snapshot {
        val upcoming = snapshot.upcoming.toMutableList()
        upcoming.add(index.coerceIn(0, upcoming.size), item)
        return commit(snapshot.copy(upcoming = upcoming))
    }

    /**
     * Moves an upcoming item from [from] to [to], clamping both to the tail's bounds. Only the tail
     * moves: the current item and history keep their place. A manual reorder is authoritative, so a
     * shuffled queue drops its pre-shuffle backup — switching shuffle off afterwards keeps the
     * order the user just chose instead of bringing the old one back.
     */
    fun moveUpcoming(from: Int, to: Int): Snapshot {
        val upcoming = snapshot.upcoming
        if (from !in upcoming.indices) return snapshot
        val target = to.coerceIn(0, upcoming.lastIndex)
        if (from == target) return snapshot
        val moved = upcoming.toMutableList().apply { add(target, removeAt(from)) }
        // Keep shuffle and its backup untouched until the reorder is committed. A cancelled lift
        // moves the track back and must leave the pre-reorder shuffle state exactly as it was.
        return commit(snapshot.copy(upcoming = moved))
    }

    /**
     * Commits a hand-picked order. The user has arranged the tail themselves, so shuffle no longer
     * describes it and its pre-shuffle backup must not replace the chosen order later.
     */
    fun finishReorder(): Snapshot = if (!snapshot.shuffle && snapshot.shuffleBackup == null) {
        snapshot
    } else {
        commit(snapshot.copy(shuffle = false, shuffleBackup = null))
    }

    /** Removes every upcoming item and returns them so the caller can offer Undo. */
    fun clearUpcoming(): List<SpotifyMediaItem> {
        val removed = snapshot.upcoming
        commit(snapshot.copy(upcoming = emptyList(), shuffle = false, shuffleBackup = null))
        return removed
    }

    /** Restores a cleared tail in front of whatever was appended in the meantime. */
    fun restoreUpcoming(items: List<SpotifyMediaItem>): Snapshot = commit(snapshot.copy(upcoming = items + snapshot.upcoming))

    private fun replayContext(): Snapshot {
        val items = snapshot.context
        if (items.isEmpty()) return commit(snapshot.copy(current = null))
        val first = items.first()
        return commit(
            snapshot.copy(
                current = first,
                upcoming = items.drop(1).let { if (snapshot.shuffle) it.shuffled(random) else it },
                history = snapshot.history,
                shuffleBackup = if (snapshot.shuffle) items.drop(1) else null
            )
        )
    }

    private fun commit(next: Snapshot): Snapshot {
        if (next != snapshot) {
            snapshot = next
            runCatching { save(next) }
        }
        return snapshot
    }

    private companion object {
        const val HISTORY_LIMIT = 20
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
