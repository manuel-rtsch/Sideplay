package io.github.manuelrtsch.sidetrack

/**
 * How long a fetched library category counts as current. Coming back to it inside this window
 * paints from the cache and touches no network at all; coming back later still paints from the
 * cache first and revalidates behind the list.
 */
const val LIBRARY_CACHE_FRESH_FOR_MS = 60_000L

/**
 * One library category as the app remembers it: the rows paged in so far, whether the service has
 * more, and when they were fetched.
 *
 * The library used to hold only the open category, so walking playlists → albums → playlists
 * refetched playlists from the top and dropped every page loaded past the first. A set per
 * category lets a switch paint immediately, which matters most on the keypad, where moving across
 * the five buttons is a single keypress.
 */
data class LibraryPage(
    val items: List<Any> = emptyList(),
    val hasMore: Boolean = false,
    /** Stamp of the fetch that produced [items]; 0 means "never fetched". */
    val fetchedAt: Long = 0L,
    /**
     * Set when the last first-page fetch failed. A category that still has cached rows keeps them
     * and reports the failure as a notice instead, so this stays null whenever there is content.
     */
    val error: String? = null
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * True once the list reaches past its first page. A background revalidate must not touch such
     * a list: it would renumber the rows under the cursor of someone deep in their albums.
     */
    val isExpanded: Boolean get() = items.size > CATALOG_PAGE_SIZE
}
