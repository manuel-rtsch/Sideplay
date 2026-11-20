package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Saving a track or a collection and following an artist.
 *
 * All three are optimistic: the state flips first, the service is told after, and a failure puts
 * the tile back with a notice. The touched sets in [SavedHints] keep a page load that is still in
 * flight from overwriting the tap.
 */
internal class SavesController(
    private val store: UiStateStore,
    private val writer: MediaLibraryWriter?,
    private val hints: SavedHints,
    private val mediaKeys: MediaKeys,
    private val online: () -> Boolean,
    private val scope: CoroutineScope
) {

    private fun playbackNotice(error: Exception): String = serviceMessage(error, online())

    fun toggleSaveCurrent() {
        val track = store.value.current as? Track ?: return
        toggleTrackSaved(track.id, track)
    }

    fun toggleSaved(track: Track) = toggleTrackSaved(track.id, track)

    fun toggleTrackSaved(
        id: String,
        track: Track?
    ) {
        val savedKey = mediaKeys.track(id).encode()
        val wasSaved = savedKey in store.value.savedTrackIds
        val saved = !wasSaved
        hints.markTrack(savedKey)
        store.update {
            val ids = it.savedTrackIds.toMutableSet()
            if (saved) ids.add(savedKey) else ids.remove(savedKey)
            var next = it.copy(savedTrackIds = ids)
            next = if (saved) {
                track?.let { item -> next.withLibraryItemAdded(LibraryFilter.LikedSongs, item, mediaKeys) } ?: next
            } else {
                next.withLibraryItemRemoved(LibraryFilter.LikedSongs, id, mediaKeys)
            }
            next
        }
        val writer = writer ?: return
        scope.launch {
            try {
                writer.setSaved(mediaKeys.track(id), saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
                    val ids = it.savedTrackIds.toMutableSet()
                    if (wasSaved) ids.add(savedKey) else ids.remove(savedKey)
                    var next = it.copy(savedTrackIds = ids).say(playbackNotice(e))
                    next = if (wasSaved) {
                        track?.let { item -> next.withLibraryItemAdded(LibraryFilter.LikedSongs, item, mediaKeys) } ?: next
                    } else {
                        next.withLibraryItemRemoved(LibraryFilter.LikedSongs, id, mediaKeys)
                    }
                    next
                }
            }
        }
    }

    fun savedCollection(collection: Collection): Boolean = mediaKeys.collection(collection.type, collection.id) in store.value.savedCollectionKeys

    fun toggleSaved(collection: Collection) = toggleSaved(collection.type, collection.id, collection)

    private fun toggleSaved(type: CollectionType, id: String, collection: Collection) {
        val key = mediaKeys.collection(type, id)
        val wasSaved = key in store.value.savedCollectionKeys
        val saved = !wasSaved
        val addedItem = libraryItemFor(collection)
        hints.markCollection(key)
        store.update {
            val keys = it.savedCollectionKeys.toMutableSet()
            if (saved) keys.add(key) else keys.remove(key)
            val next = it.copy(savedCollectionKeys = keys)
            if (saved) {
                addedItem?.let { item -> next.withLibraryItemAdded(libraryFilterFor(type), item, mediaKeys) } ?: next
            } else {
                next.withLibraryItemRemoved(libraryFilterFor(type), id, mediaKeys)
            }
        }
        val writer = writer ?: return
        scope.launch {
            try {
                writer.setSaved(mediaKeys.media(type, id), saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    if (wasSaved) keys.add(key) else keys.remove(key)
                    val next = it.copy(savedCollectionKeys = keys).say(playbackNotice(e))
                    if (wasSaved) {
                        addedItem?.let { item -> next.withLibraryItemAdded(libraryFilterFor(type), item, mediaKeys) } ?: next
                    } else {
                        next.withLibraryItemRemoved(libraryFilterFor(type), id, mediaKeys)
                    }
                }
            }
        }
    }

    fun followed(artist: Artist): Boolean = mediaKeys.artist(artist.id).encode() in store.value.followedArtistIds

    fun toggleFollow(artist: Artist) {
        val id = artist.id
        val followedKey = mediaKeys.artist(id).encode()
        val wasFollowing = followedKey in store.value.followedArtistIds
        val following = !wasFollowing
        hints.markFollow(followedKey)
        store.update {
            val ids = it.followedArtistIds.toMutableSet()
            if (following) ids.add(followedKey) else ids.remove(followedKey)
            val next = it.copy(followedArtistIds = ids)
            if (following) {
                next.withLibraryItemAdded(LibraryFilter.Artists, artist, mediaKeys)
            } else {
                next.withLibraryItemRemoved(LibraryFilter.Artists, id, mediaKeys)
            }
        }
        val writer = writer ?: return
        scope.launch {
            try {
                writer.setFollowed(mediaKeys.artist(id), following)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
                    val ids = it.followedArtistIds.toMutableSet()
                    if (wasFollowing) ids.add(followedKey) else ids.remove(followedKey)
                    val next = it.copy(followedArtistIds = ids).say(playbackNotice(e))
                    if (wasFollowing) {
                        next.withLibraryItemAdded(LibraryFilter.Artists, artist, mediaKeys)
                    } else {
                        next.withLibraryItemRemoved(LibraryFilter.Artists, id, mediaKeys)
                    }
                }
            }
        }
    }

    /**
     * Reads a track's saved state when it first becomes relevant: starting it, or opening its
     * row's gutter. Idempotent, so a list can call it on every reveal.
     */
    fun ensureTrackSaved(id: String) {
        val writer = writer ?: return
        val savedKey = mediaKeys.track(id).encode()
        if (savedKey in store.value.savedTrackIds || hints.trackTouched(savedKey)) return
        scope.launch {
            val saved = runCatching { writer.isSaved(mediaKeys.track(id)) }.getOrNull() ?: return@launch
            if (saved && !hints.trackTouched(savedKey)) {
                store.update { it.copy(savedTrackIds = it.savedTrackIds + savedKey) }
            }
        }
    }
}
