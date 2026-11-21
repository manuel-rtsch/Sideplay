package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The playlist writes the UI can reach: create a playlist, append one track, take one entry back
 * out, and load the playlists that are actually addable.
 *
 * Add and remove are separate permissions ([PlaylistActions]), so both operations check the
 * playlist row itself instead of trusting the service flag alone. The picker only shows playlists
 * that pass the add check; a read-only row never becomes a target that fails at the server.
 */
internal class PlaylistController(
    private val store: UiStateStore,
    private val catalog: MediaCatalog,
    private val writer: MediaLibraryWriter?,
    private val capabilities: () -> ServiceCapabilities,
    private val onCreated: () -> Unit,
    private val online: () -> Boolean,
    private val scope: CoroutineScope
) {

    private fun playbackNotice(error: Exception): String = serviceMessage(error, online())

    private var choicesOffset = 0

    /**
     * Creates a playlist named [name], seeded with [seed] in that order, on the active service.
     * Returns null on failure so the caller can stay on the form; the reason is already in the bar.
     */
    suspend fun create(name: String, seed: List<MediaKey> = emptyList()): Playlist? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            store.update { it.say("Enter a playlist name") }
            return null
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot create playlists") }
            return null
        }
        return try {
            val playlist = writer.createPlaylist(trimmed, seed)
            if (playlist == null) {
                store.update { it.say("This service cannot create playlists") }
            } else {
                // The new row belongs in the library and in a picker that was already loaded.
                invalidateChoices()
                onCreated()
            }
            playlist
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(playbackNotice(e)) }
            null
        }
    }

    /** Loads the first page once per picker visit; a later visit reuses the loaded list. */
    fun ensureChoices() {
        val state = store.value
        if (!state.signedIn || state.playlistChoicesLoaded || state.playlistChoicesLoading) return
        loadChoices(reset = true)
    }

    fun retryChoices() {
        if (store.value.playlistChoicesLoading) return
        choicesOffset = 0
        loadChoices(reset = true)
    }

    fun loadMoreChoices() {
        val state = store.value
        if (state.playlistChoicesLoading || !state.playlistChoicesHasMore) return
        loadChoices(reset = false)
    }

    private fun loadChoices(reset: Boolean) {
        if (!store.value.signedIn) return
        val offset = if (reset) 0 else choicesOffset
        store.update {
            it.copy(
                playlistChoicesLoading = true,
                playlistChoicesError = if (reset) null else it.playlistChoicesError
            )
        }
        scope.launch {
            try {
                val page = catalog.library(LibrarySection.Playlists, offset, CATALOG_PAGE_SIZE)
                val addable = page.items.filterIsInstance<Playlist>()
                    .filter { capabilities().playlistActions(it).canAddItems }
                choicesOffset = offset + page.items.size
                store.update {
                    val merged = if (reset) {
                        addable
                    } else {
                        (it.playlistChoices + addable).distinctBy { playlist -> playlist.key.encode() }
                    }
                    it.copy(
                        playlistChoices = merged,
                        playlistChoicesLoading = false,
                        playlistChoicesLoaded = true,
                        playlistChoicesHasMore = page.hasMore,
                        playlistChoicesError = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reset) choicesOffset = 0
                store.update {
                    it.copy(
                        playlistChoicesLoading = false,
                        playlistChoicesLoaded = if (reset) false else it.playlistChoicesLoaded,
                        playlistChoicesError = playbackNotice(e)
                    )
                }
            }
        }
    }

    private fun invalidateChoices() {
        choicesOffset = 0
        store.update {
            it.copy(
                playlistChoices = emptyList(),
                playlistChoicesLoading = false,
                playlistChoicesLoaded = false,
                playlistChoicesHasMore = false,
                playlistChoicesError = null
            )
        }
    }

    /**
     * Appends one item and returns the index the service placed it at, which is what Undo removes.
     * Null means nothing was written; the failure is already in the bar.
     */
    suspend fun add(playlist: Playlist, item: MediaItem): Int? {
        if (!capabilities().playlistActions(playlist).canAddItems) {
            store.update { it.say("This playlist is read-only") }
            return null
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot edit playlists") }
            return null
        }
        return try {
            val index = writer.addToPlaylist(playlist.key, item)
            if (index == null) {
                store.update { it.say("This service cannot add to playlists") }
            } else {
                store.update { state ->
                    val count = state.playlistTrackCount(playlist.key, playlist.trackCount) + 1
                    // The target may already be on the back stack with its old track list cached.
                    state.copy(collections = state.collections - playlist.key.encode())
                        .withPlaylistTrackCount(playlist.key, count)
                }
                store.update { it.say("Added ${item.title} to ${playlist.name}", NoticeKind.Success) }
            }
            index
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(playbackNotice(e)) }
            null
        }
    }

    /** Takes back an append by removing exactly the index the service reported. */
    suspend fun undoAdd(playlist: Playlist, item: MediaItem, index: Int) {
        val writer = writer ?: return
        try {
            writer.removeFromPlaylist(playlist.key, item, index)
            store.update { state ->
                val count = (state.playlistTrackCount(playlist.key, playlist.trackCount) - 1).coerceAtLeast(0)
                state.copy(collections = state.collections - playlist.key.encode())
                    .withPlaylistTrackCount(playlist.key, count)
            }
            store.update { it.say("Removed ${item.title} from ${playlist.name}", NoticeKind.Success) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store.update { it.say(playbackNotice(e)) }
        }
    }

    /**
     * Removes the row at [index] after the writer confirms it. The local page drops exactly that
     * occurrence; `hasMore` stays as it was, so the next page's offset is still the number of rows
     * currently loaded.
     */
    fun remove(collection: Collection, item: MediaItem, index: Int) {
        if (!capabilities().playlistActions(collection).canRemoveItems) {
            store.update { it.say("This playlist is read-only") }
            return
        }
        val writer = writer ?: run {
            store.update { it.say("This service cannot edit playlists") }
            return
        }
        val cacheKey = collection.key.encode()
        scope.launch {
            try {
                writer.removeFromPlaylist(collection.key, item, index)
                store.update { state ->
                    val current = state.collections[cacheKey] ?: collection
                    val items = current.items.toMutableList()
                    val at = index.takeIf { items.getOrNull(it)?.id == item.id }
                        ?: items.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
                    if (at != null) items.removeAt(at)
                    val next = current.copy(
                        items = items,
                        totalItems = (current.totalItems - if (at != null) 1 else 0).coerceAtLeast(items.size)
                    )
                    state.copy(collections = state.collections + (cacheKey to next))
                        .withPlaylistTrackCount(collection.key, next.totalItems)
                        .say("Removed ${item.title} from ${current.title}", NoticeKind.Success)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { it.say(playbackNotice(e)) }
            }
        }
    }

    /**
     * The count currently shown for a playlist, wherever a cached row holds it. The object passed
     * from the UI can be older than the state after an earlier add or remove, so Undo must read the
     * patched count rather than `Playlist.trackCount` from the argument.
     */
    private fun SidetrackUiState.playlistTrackCount(key: MediaKey, fallback: Int): Int {
        val encoded = key.encode()
        fun Playlist.matches() = this.key.encode() == encoded

        collections[encoded]?.let { return it.totalItems }
        playlistChoices.firstOrNull { it.matches() }?.let { return it.trackCount }
        libraryPages.values.forEach { page ->
            page.items.filterIsInstance<Playlist>().firstOrNull { it.matches() }?.let { return it.trackCount }
        }
        librarySections.values.forEach { items ->
            items.filterIsInstance<Playlist>().firstOrNull { it.matches() }?.let { return it.trackCount }
        }
        searchResultsFor(searchPagesForQuery).run {
            playlists.firstOrNull { it.matches() }?.let { return it.trackCount }
        }
        return fallback
    }
}
