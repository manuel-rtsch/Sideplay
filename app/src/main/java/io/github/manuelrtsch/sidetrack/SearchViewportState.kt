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
import io.github.manuelrtsch.sidetrack.ui.RowGutterState

// The search page's reading-position rules mirror the library's: where each category was left,
// which row takes focus when a held arrow brings it back, and how a category switch is recorded.
// Kept out of SearchScreen's body so the composition reads as layout rather than focus choreography.

/**
 * What [rememberSearchViewport] hands back to the screen: the category-row switch, the held-arrow
 * switch modifier, the restore pieces the list needs while composing rows, and the first-row
 * modifier that returns Up to the category row.
 */
internal class SearchViewportState(
    val restoreFocusIndex: Int?,
    val restoreFocus: FocusRequester,
    val switchScope: (SearchScope) -> Unit,
    val holdSwitch: Modifier,
    val firstRowModifier: Modifier
)

/**
 * Remembers one reading position per category, discarded whenever the query changes because a new
 * query is a fresh browse. The focused row is keyed by the row, not its index, so an item inserted
 * above it does not move the cursor to a stranger.
 *
 * A switch from the category row keeps the cursor on the row so Left/Right can keep stepping; a
 * held arrow from a row body restores that category's settled position.
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
    searchGutters: RowGutterState,
    moreFocus: LoadMoreFocusState,
    scopeFocus: Map<SearchScope, FocusRequester>,
    gate: CategoryHoldGate
): SearchViewportState {
    val viewports = remember(query) { mutableStateMapOf<SearchScope, LibraryViewport>() }
    var lastFocusedRowKey by remember { mutableStateOf<String?>(null) }
    var pendingViewport by remember { mutableStateOf<LibraryViewport?>(null) }
    var restoreListFocus by remember { mutableStateOf(true) }
    var restoreFocusIndex by remember { mutableStateOf<Int?>(null) }
    var focusFilterRow by remember { mutableStateOf(false) }
    // The results page has no search field to hand the cursor down from. When the first page
    // arrives (or the page opens with results already cached), the category row takes it instead.
    var entryFocusPending by remember { mutableStateOf(true) }
    val restoreFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        snapshotFlow { rows.focus.focusedKey ?: searchGutters.focusedKey }.collect { key ->
            if (key != null) lastFocusedRowKey = key.toString()
        }
    }

    val items = results.itemsFor(scope)
    val branch = when {
        !showFilters || failed -> "top"
        items.isEmpty() -> "empty"
        else -> "list"
    }
    // A row that has settled under the cursor is a reading position: record it so a held arrow can
    // bring that category back to where it was.
    val settledRowKey = rows.focus.openKey ?: searchGutters.openKey
    LaunchedEffect(settledRowKey, scope) {
        val settled = settledRowKey ?: return@LaunchedEffect
        if (branch != "list") return@LaunchedEffect
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

    LaunchedEffect(showFilters, failed) {
        if (!showFilters || failed || !entryFocusPending) return@LaunchedEffect
        entryFocusPending = false
        focusFilterRow = true
    }

    val switchScope: (SearchScope, Boolean) -> Unit = { next, fromRow ->
        if (next != scope) {
            val focusedKey = (rows.focus.focusedKey ?: searchGutters.focusedKey)?.toString() ?: lastFocusedRowKey
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
            pendingViewport = viewports[next]
            restoreListFocus = !fromRow
            restoreFocusIndex = null
            lastFocusedRowKey = null
            setScope(next)
        }
    }

    val holdSwitch = Modifier.searchCategoryHoldSwitch(
        scope = scope,
        includedScopes = includedScopes,
        enabled = rows.bar.selection == null,
        gate = gate,
        onSwitch = { switchScope(it, false) }
    )

    // Chosen a category (row click or Left/Right): the cursor stays on the category row so Left/
    // Right can keep stepping. A held arrow from a row body restores that category's position. The
    // typing, loading and failed states own their own focus, so only the browse states touch it
    // here; an empty category has no position to restore, so its cursor stays on the row too.
    LaunchedEffect(scope) {
        moreFocus.reset()
        if (!showFilters || failed) return@LaunchedEffect
        val viewport = pendingViewport
        pendingViewport = null
        if (viewport == null || !restoreListFocus || branch == "empty") {
            listState.scrollToItem(0)
            focusFilterRow = true
            return@LaunchedEffect
        }
        listState.scrollToItem(
            viewport.index.coerceIn(0, items.size),
            viewport.offset.coerceAtLeast(0)
        )
        val focusedIndex = viewport.focusedRowKey?.let { key ->
            items.indexOfFirst { libraryRowFocusKey(it) == key }.takeIf { it >= 0 }
        }
        restoreFocusIndex = focusedIndex
            ?: (viewport.index - 1).coerceIn(0, items.lastIndex)
    }

    LaunchedEffect(restoreFocusIndex) {
        if (restoreFocusIndex == null) return@LaunchedEffect
        // The scroll above composes the restored row. Give that frame a chance to attach the
        // requester before asking it to take the cursor.
        repeat(3) {
            withFrameNanos { }
            if (runCatching { restoreFocus.requestFocus() }.isSuccess) {
                restoreFocusIndex = null
                return@LaunchedEffect
            }
        }
        restoreFocusIndex = null
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
        restoreFocusIndex = restoreFocusIndex,
        restoreFocus = restoreFocus,
        switchScope = { switchScope(it, true) },
        holdSwitch = holdSwitch,
        firstRowModifier = firstRowModifier
    )
}
