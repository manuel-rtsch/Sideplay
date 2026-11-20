package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter

/**
 * The bookkeeping behind optimistic saves and follows.
 *
 * When a page is loaded the service is asked whether its tracks and collections are saved, and
 * that answer fills the state. But a lookup can be in flight while the user taps the save button,
 * and the older server answer must not undo the tap. The touched sets remember what the user
 * changed since the page started loading.
 *
 * They live here, not in a loader, because both the library paging and the save toggles consult
 * them: two copies would let a stale answer win.
 */
internal class SavedHints(
    private val writer: () -> MediaLibraryWriter?
) {

    private val tracks = mutableSetOf<String>()
    private val collections = mutableSetOf<String>()
    private val follows = mutableSetOf<String>()

    fun markTrack(key: String) {
        tracks += key
    }

    fun markCollection(key: String) {
        collections += key
    }

    fun markFollow(key: String) {
        follows += key
    }

    fun trackTouched(key: String): Boolean = key in tracks

    fun collectionTouched(key: String): Boolean = key in collections

    /** The server's answer for this collection, or null when the user already decided it. */
    suspend fun savedCollection(key: MediaKey, cacheKey: String): Boolean? = if (cacheKey in collections) null else runCatching { writer()?.isSaved(key) }.getOrNull()

    /** The server's answer for this artist, or null when the user already decided it. */
    suspend fun followedArtist(key: MediaKey, touchedKey: String): Boolean? = if (touchedKey in follows) null else runCatching { writer()?.isFollowed(key) }.getOrNull()

    fun clear() {
        tracks.clear()
        collections.clear()
        follows.clear()
    }
}
