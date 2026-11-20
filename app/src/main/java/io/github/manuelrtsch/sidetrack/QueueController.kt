package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Edits to Up next and History: add, remove, reorder, clear and undo.
 *
 * The screens treat the queue as a list they own, so each edit is a single commit and a single
 * undo. Paged sources (a playlist behind "See more") are resolved to their complete list first,
 * so the queue never reflects a page the user was still loading.
 */
internal class QueueController(
    private val store: UiStateStore,
    private val queue: QueueRepository,
    private val history: HistoryRepository,
    private val effects: PlaybackEffects,
    private val catalog: MediaCatalog,
    private val library: LibraryController,
    private val mediaKeys: MediaKeys,
    private val online: () -> Boolean,
    private val scope: CoroutineScope
) {

    private fun playbackNotice(error: Exception): String = serviceMessage(error, online())

    /**
     * Removes one upcoming occurrence by index and returns where it was, so the caller can offer
     * Undo. The index keeps duplicated tracks from taking each other with them.
     */
    fun removeUpcoming(index: Int): Int {
        val at = queue.removeUpcoming(index)
        if (at >= 0) effects.applyQueue(queue.snapshot, syncNowPlaying = false)
        return at
    }

    fun restoreRemovedUpcoming(index: Int, item: MediaItem) = effects.applyQueue(queue.restoreRemovedUpcoming(index, item), syncNowPlaying = false)

    fun moveUpcoming(from: Int, to: Int) {
        effects.applyQueue(queue.moveUpcoming(from, to), syncNowPlaying = false)
    }

    /** The user kept the hand-picked order: shuffle no longer describes it. */
    fun finishReorder() {
        effects.applyQueue(queue.finishReorder(), syncNowPlaying = false)
    }

    /** Removes one history entry and returns the action that puts it back, if it was there. */
    fun removeHistory(item: MediaItem): (() -> Unit)? {
        val removed = history.removeEntry(item) ?: return null
        val queueIndex = queue.snapshot.history.indexOfFirst { it.id == item.id }
        effects.applyQueue(queue.removeHistory(item), syncNowPlaying = false)
        store.update { it.copy(recent = history.items()) }
        return {
            history.restore(removed)
            effects.applyQueue(queue.restoreHistory(queueIndex, item), syncNowPlaying = false)
            store.update { it.copy(recent = history.items()) }
        }
    }

    fun addToQueue(item: MediaItem) {
        effects.applyQueue(queue.addToQueue(item), syncNowPlaying = false)
        // Queueing is silent in the player, so the bar is the only confirmation the action landed
        // — especially before anything is playing, when there is no Now Playing screen to check.
        store.update { it.say("Added ${item.title} to queue", NoticeKind.Success) }
    }

    /**
     * Queues a whole collection. Paged collections are resolved first so the queue always matches
     * the list the page represents, and the items land as one batch instead of one commit and one
     * queue-file write per track. If a page cannot be loaded, nothing is queued rather than a
     * partial playlist.
     */
    fun queueCollection(collection: Collection) {
        if (!collection.hasMore) {
            addToQueue(collection.items)
            return
        }
        val key = mediaKeys.collection(collection.type, collection.id)
        if (key in store.value.collectionMorePending) {
            store.update { it.say("This collection is still loading — try again") }
            return
        }
        store.update { it.copy(collectionMorePending = it.collectionMorePending + key) }
        scope.launch {
            try {
                val complete = library.loadAll(collection)
                if (complete.hasMore) {
                    store.update { it.say("Couldn't load the whole collection — nothing added") }
                    return@launch
                }
                addToQueue(complete.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { it.say(playbackNotice(e)) }
            } finally {
                store.update { it.copy(collectionMorePending = it.collectionMorePending - key) }
            }
        }
    }

    /**
     * Queues an album straight from a library row. The album's tracks are not loaded until its page
     * opens, so the collection is fetched first and queued as one complete batch.
     */
    fun queueAlbum(album: Album) = queueLibraryCollection(album.key, "album")

    /** Playlist pages are paged too, so the same complete-collection rule applies. */
    fun queuePlaylist(playlist: Playlist) = queueLibraryCollection(playlist.key, "playlist")

    /** Queues the newest episode from a show row without opening the show page. */
    fun queueLatestEpisode(show: Show) {
        val latest = show.episodes.firstOrNull()
        if (latest == null) {
            store.update { it.say("No episodes to queue yet") }
            return
        }
        addToQueue(latest)
    }

    private fun queueLibraryCollection(key: MediaKey, noun: String) {
        val cacheKey = key.encode()
        store.value.collections[cacheKey]?.let { cached ->
            queueCollection(cached)
            return
        }
        if (cacheKey in store.value.collectionMorePending) {
            store.update { it.say("This $noun is still loading — try again") }
            return
        }
        store.update { it.copy(collectionMorePending = it.collectionMorePending + cacheKey) }
        scope.launch {
            try {
                val first = catalog.collection(key, 0, CATALOG_PAGE_SIZE)
                if (first == null) {
                    store.update { it.say("Nothing to queue") }
                    return@launch
                }
                val complete = library.loadAll(first)
                if (complete.hasMore) {
                    store.update { it.say("Couldn't load the whole $noun — nothing added") }
                    return@launch
                }
                addToQueue(complete.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { it.say(playbackNotice(e)) }
            } finally {
                store.update { it.copy(collectionMorePending = it.collectionMorePending - cacheKey) }
            }
        }
    }

    /** Appends a batch with one queue commit; the collection screen uses this after paging. */
    fun addToQueue(items: List<MediaItem>) {
        if (items.isEmpty()) {
            store.update { it.say("Nothing to queue") }
            return
        }
        effects.applyQueue(queue.addToQueue(items), syncNowPlaying = false)
        store.update {
            it.say(
                "Added ${items.size} ${if (items.size == 1) "track" else "tracks"} to queue",
                NoticeKind.Success
            )
        }
    }

    fun clearUpcoming(): List<MediaItem> {
        val removed = queue.clearUpcoming()
        effects.applyQueue(queue.snapshot, syncNowPlaying = false)
        return removed
    }

    fun restoreUpcoming(items: List<MediaItem>) = effects.applyQueue(queue.restoreUpcoming(items), syncNowPlaying = false)
}
