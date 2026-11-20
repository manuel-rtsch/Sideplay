package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

// The search page's reading-position rules mirror the library's: which category was left on, and
// how Up from the first result returns to the category row. Kept out of SearchScreen's body so the
// composition reads as layout rather than focus choreography.

/**
 * What [rememberSearchViewport] hands back to the screen: the category-row switch callback and
 * the modifier the first data row attaches so Up returns to the category row.
 */
internal class SearchViewportState(
    val switchScope: (SearchScope) -> Unit,
    val firstRowModifier: Modifier
)

/**
 * Remembers one reading position per category, discarded whenever the query changes because a new
 * query is a fresh browse.
 *
 * Only the category-row path is active today: selecting a category keeps the cursor on the row and
 * the returned-to category paints from the top, exactly like the library's row switches. A held
 * arrow from a row body, which would restore a deeper list position, is not part of search yet, so
 * the saved positions only await that step rather than being consumed here.
 */
@Composable
internal fun rememberSearchViewport(
    query: String,
    scope: SearchScope,
    setScope: (SearchScope) -> Unit,
    supportedScopes: Set<SearchScope>,
    includedScopes: Set<SearchScope>,
    showFilters: Boolean,
    failed: Boolean,
    results: SearchResults,
    listState: LazyListState,
    rows: TrackRows,
    moreFocus: LoadMoreFocusState,
    scopeFocus: Map<SearchScope, FocusRequester>
): SearchViewportState {
    // A category's place while this query is on screen; a new query resets it because a fresh
    // search has no reading position to come back to.
    val viewports = remember(query) { mutableStateMapOf<SearchScope, LibraryViewport>() }
    var lastFocusedRowKey by remember { mutableStateOf<String?>(null) }
    var focusFilterRow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { rows.focus.focusedKey }.collect { key ->
            if (key != null) lastFocusedRowKey = key.toString()
        }
    }

    // A row that has settled under the cursor is a reading position: record it so a switch away
    // and back can return to it once a held-arrow switch exists.
    val items = results.itemsFor(scope)
    val settledRowKey = rows.focus.openKey
    LaunchedEffect(settledRowKey, scope) {
        val settled = settledRowKey ?: return@LaunchedEffect
        if (!showFilters || failed) return@LaunchedEffect
        val itemIndex = items.indexOfFirst { libraryRowFocusKey(it) == settled.toString() }
        if (itemIndex < 0) return@LaunchedEffect
        viewports[scope] = listState.captureViewport(settled.toString())
    }

    // A query may return nothing in the selected category, and toggling categories can remove the
    // selected one entirely. Move the selection rather than letting the row and the list disagree;
    // the scope-change effect below puts the cursor on the row it chose.
    LaunchedEffect(query, includedScopes, results) {
        if (scope !in includedScopes) {
            SearchScope.entries.firstOrNull { it in includedScopes }
                ?.let { if (it != scope) setScope(it) }
        } else if (results.sizeFor(scope) == 0) {
            SearchScope.entries.firstOrNull {
                it in supportedScopes && it in includedScopes && results.sizeFor(it) > 0
            }?.let { if (it != scope) setScope(it) }
        }
    }

    val switchScope: (SearchScope) -> Unit = { next ->
        if (next != scope) {
            val focusedKey = rows.focus.focusedKey?.toString() ?: lastFocusedRowKey
            val focusedIndex = focusedKey?.let { key ->
                items.indexOfFirst { libraryRowFocusKey(it) == key }
            } ?: -1
            val focusedVisible = focusedIndex >= 0 &&
                listState.layoutInfo.visibleItemsInfo.any { it.index == focusedIndex + 1 }
            // A quick switch from the list records the exact position; a switch from the category
            // row keeps the last settled viewport instead of the temporary top it climbed through.
            if (focusedVisible || viewports[scope] == null) {
                viewports[scope] = listState.captureViewport(focusedKey)
            }
            lastFocusedRowKey = null
            setScope(next)
        }
    }

    // Chosen a category (row click or Left/Right): the new page is a repaint from the top and the
    // cursor stays on the category row so Left/Right can keep stepping. The typing and failed
    // states own their own focus, so only the filter-row states scroll and re-point the cursor.
    LaunchedEffect(scope) {
        moreFocus.reset()
        if (showFilters && !failed) {
            listState.scrollToItem(0)
            focusFilterRow = true
        }
    }

    // Up from the first result returns to the category row rather than letting focus search pick
    // the geometrically nearest button. The row can sit outside the composed window when the first
    // item is at the top, so scroll it back in and retry its requester across a few frames.
    LaunchedEffect(focusFilterRow) {
        if (!focusFilterRow) return@LaunchedEffect
        listState.scrollToItem(0)
        repeat(3) {
            withFrameNanos { }
            if (runCatching { scopeFocus.getValue(scope).requestFocus() }.isSuccess) {
                focusFilterRow = false
                return@LaunchedEffect
            }
        }
        focusFilterRow = false
    }

    val firstRowModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            focusFilterRow = true
            true
        } else {
            false
        }
    }

    return SearchViewportState(
        switchScope = { switchScope(it) },
        firstRowModifier = firstRowModifier
    )
}
