package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The search screen's state machine: the debounce, the per-category paging and the empty-query
 * reset.
 *
 * Search is the one feature that pages five result lists at once, so its rules (a stale response
 * must not land in a newer query, a scroll position only extends its own category) are worth
 * reading without the library paging code around them.
 */
internal class SearchController(
    private val store: UiStateStore,
    private val catalog: MediaCatalog,
    private val supportedScopes: () -> Set<SearchScope>,
    private val online: () -> Boolean,
    private val coroutineScope: CoroutineScope
) {

    private var searchJob: Job? = null

    private fun playbackNotice(error: Exception): String = serviceMessage(error, online())

    fun search(debounceMs: Long) {
        searchJob?.cancel()
        val query = store.value.query
        if (query.trim().length < 2) {
            store.update {
                it.copy(
                    searchPages = emptyMap(),
                    searchPending = false,
                    searchMorePending = emptySet()
                )
            }
            return
        }
        store.update {
            it.copy(
                searchPending = true,
                searchError = null,
                searchMorePending = emptySet()
            )
        }
        searchJob = coroutineScope.launch {
            delay(debounceMs)
            val scopes = store.value.searchScopes
            var failure: String? = null
            val results = try {
                if (store.value.signedIn) catalog.search(query, scopes, 0, SEARCH_PAGE_SIZE) else SearchResults()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = playbackNotice(e)
                SearchResults()
            }
            store.update {
                if (it.query == query && it.searchScopes == scopes) {
                    val empty = results.isEmpty
                    // Each returned category becomes a first page in this query's cache. A category
                    // that is already paged deeper keeps its rows: a search triggered by toggling a
                    // scope must not flatten the lists it left untouched.
                    var next = it
                    for (scope in scopes) {
                        next = next.withSearchFirstPage(
                            scope = scope,
                            items = results.itemsFor(scope),
                            hasMore = results.sizeFor(scope) >= SEARCH_PAGE_SIZE,
                            error = null
                        )
                    }
                    next = next.copy(
                        searchPending = false,
                        searchError = if (empty) failure else null
                    )
                    // A failed search with old results stays a notice; an empty one becomes the
                    // screen's fallback so the retry action is visible.
                    if (empty || failure == null) next else next.say(failure)
                } else {
                    it
                }
            }
        }
    }

    /** Retries the current query after a failed search. */
    fun retrySearch() {
        search(debounceMs = 0L)
    }

    fun loadMoreSearch(scope: SearchScope) {
        val snapshot = store.value
        val query = snapshot.query
        if (scope !in snapshot.searchHasMore || scope in snapshot.searchMorePending) return
        val offset = snapshot.searchPagesForQuery[scope]?.items?.size ?: 0
        store.update { it.copy(searchMorePending = it.searchMorePending + scope) }
        coroutineScope.launch {
            try {
                val more = catalog.search(query, setOf(scope), offset, SEARCH_PAGE_SIZE)
                store.update {
                    if (it.query != query) {
                        it.copy(searchMorePending = it.searchMorePending - scope)
                    } else {
                        it.withSearchMorePage(
                            scope = scope,
                            next = more.itemsFor(scope),
                            hasMore = more.sizeFor(scope) >= SEARCH_PAGE_SIZE
                        ).copy(searchMorePending = it.searchMorePending - scope)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
                    it.copy(searchMorePending = it.searchMorePending - scope).say(playbackNotice(e))
                }
            }
        }
    }

    fun setQuery(query: String) {
        // A new query is a fresh browse: the pages of the previous query have no worth under it.
        store.update {
            it.copy(
                query = query,
                searchPages = emptyMap(),
                searchMorePending = emptySet()
            )
        }
        search(debounceMs = 250L)
    }

    fun clearQuery() {
        searchJob?.cancel()
        store.update {
            it.copy(
                query = "",
                searchPages = emptyMap(),
                searchPending = false,
                searchError = null,
                searchMorePending = emptySet()
            )
        }
    }

    fun toggleScope(scope: SearchScope) {
        // A category the service cannot answer is not a choice either: the scope list disables
        // it, and this guard stops a stored or programmatic call from turning it back on.
        if (scope !in supportedScopes()) return
        val current = store.value.searchScopes
        val next = toggledSearchScopes(current, scope)
        if (next == current) return
        store.update { it.copy(searchScopes = next) }
        search(debounceMs = 0L)
    }
}
