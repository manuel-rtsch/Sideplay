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
                    searchResults = SearchResults(),
                    searchPending = false,
                    searchHasMore = emptySet(),
                    searchMorePending = emptySet()
                )
            }
            return
        }
        store.update {
            it.copy(
                searchPending = true,
                searchError = null,
                searchHasMore = emptySet(),
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
                    val next = it.copy(
                        searchResults = results,
                        searchPending = false,
                        searchError = if (empty) failure else null,
                        searchHasMore = scopes.filterTo(mutableSetOf()) { scope -> results.sizeFor(scope) >= SEARCH_PAGE_SIZE }
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
        val offset = snapshot.searchResults.sizeFor(scope)
        store.update { it.copy(searchMorePending = it.searchMorePending + scope) }
        coroutineScope.launch {
            try {
                val more = catalog.search(query, setOf(scope), offset, SEARCH_PAGE_SIZE)
                store.update {
                    if (it.query != query) {
                        it.copy(searchMorePending = it.searchMorePending - scope)
                    } else {
                        val hasMore = if (more.sizeFor(scope) >= SEARCH_PAGE_SIZE) {
                            it.searchHasMore + scope
                        } else {
                            it.searchHasMore - scope
                        }
                        it.copy(
                            searchResults = it.searchResults.merge(scope, more),
                            searchHasMore = hasMore,
                            searchMorePending = it.searchMorePending - scope
                        )
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

    private fun SearchResults.sizeFor(scope: SearchScope): Int = when (scope) {
        SearchScope.Tracks -> tracks.size
        SearchScope.Artists -> artists.size
        SearchScope.Albums -> albums.size
        SearchScope.Playlists -> playlists.size
        SearchScope.Shows -> shows.size
    }

    private fun SearchResults.merge(scope: SearchScope, next: SearchResults): SearchResults = when (scope) {
        SearchScope.Tracks -> copy(tracks = (tracks + next.tracks).distinctBy { it.id })
        SearchScope.Artists -> copy(artists = (artists + next.artists).distinctBy { it.id })
        SearchScope.Albums -> copy(albums = (albums + next.albums).distinctBy { it.id })
        SearchScope.Playlists -> copy(playlists = (playlists + next.playlists).distinctBy { it.id })
        SearchScope.Shows -> copy(shows = (shows + next.shows).distinctBy { it.id })
    }

    fun setQuery(query: String) {
        store.update { it.copy(query = query) }
        search(debounceMs = 250L)
    }

    fun clearQuery() {
        searchJob?.cancel()
        store.update {
            it.copy(
                query = "",
                searchResults = SearchResults(),
                searchPending = false,
                searchError = null,
                searchHasMore = emptySet(),
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
