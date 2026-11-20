package io.github.manuelrtsch.sidetrack

// The pure half of search paging: how server pages fold into the per-query, per-category cache.
//
// Like the library, these are extension functions on SidetrackUiState rather than methods on the
// ViewModel. They read no service, no coroutines and no Android types, so the rules that keep the
// five result lists coherent (a toggle must not collapse a list the user has paged into, a load
// more must only ever extend its own category) are testable on their own.

/** How long a fetched search page counts as current within its query. */
const val SEARCH_CACHE_FRESH_FOR_MS = 60_000L

/** Turns a query's per-category pages into the flat result set the search screen renders. */
internal fun searchResultsFor(pages: Map<SearchScope, LibraryPage>): SearchResults = SearchResults(
    tracks = pages[SearchScope.Tracks]?.items?.filterIsInstance<Track>().orEmpty(),
    artists = pages[SearchScope.Artists]?.items?.filterIsInstance<Artist>().orEmpty(),
    albums = pages[SearchScope.Albums]?.items?.filterIsInstance<Album>().orEmpty(),
    playlists = pages[SearchScope.Playlists]?.items?.filterIsInstance<Playlist>().orEmpty(),
    shows = pages[SearchScope.Shows]?.items?.filterIsInstance<Show>().orEmpty()
)

private fun SidetrackUiState.withSearchPage(scope: SearchScope, page: LibraryPage): SidetrackUiState = copy(
    searchPages = searchPages + (query to (searchPagesForQuery + (scope to page)))
)

/**
 * Writes a first page for [scope] into the active query's cache.
 *
 * A first page never collapses a list the user has already paged deeper into: if the cache for
 * this scope is [LibraryPage.isExpanded], the deeper page wins. This mirrors the library's guard
 * against a background revalidate renumbering rows under the cursor.
 */
internal fun SidetrackUiState.withSearchFirstPage(
    scope: SearchScope,
    items: List<Any>,
    hasMore: Boolean,
    fetchedAt: Long = System.currentTimeMillis(),
    error: String? = null
): SidetrackUiState {
    val existing = searchPagesForQuery[scope]
    if (existing != null && existing.isExpanded) return this
    return withSearchPage(scope, LibraryPage(items = items, hasMore = hasMore, fetchedAt = fetchedAt, error = error))
}

/**
 * Appends one more page of [scope] to its cached page, deduplicating against what is already
 * known. A page that is not cached (its query changed, or it was never fetched) is left alone.
 */
internal fun SidetrackUiState.withSearchMorePage(
    scope: SearchScope,
    next: List<Any>,
    hasMore: Boolean,
    fetchedAt: Long = System.currentTimeMillis()
): SidetrackUiState {
    val existing = searchPagesForQuery[scope] ?: return this
    val merged = existing.copy(
        items = (existing.items + next).distinctBy { libraryItemKey(it) },
        hasMore = hasMore,
        fetchedAt = fetchedAt
    )
    return withSearchPage(scope, merged)
}
