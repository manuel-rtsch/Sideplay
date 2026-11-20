package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

// The library's reading-position rules: where each category was left, which row gets focus when
// it comes back, and how a category switch is recorded. Kept out of LibraryScreen's body so the
// composition reads as layout rather than focus choreography.

/**
 * What [rememberLibraryViewport] hands back to the screen: the current body branch, the focus
 * pieces the list needs while composing rows, and the two input modifiers the filter row and the
 * first row attach. Everything else stays private to the controller.
 */
internal class LibraryViewportState(
    val restoreFocusIndex: Int?,
    val restoreFocus: FocusRequester,
    val switchFilter: (LibraryFilter) -> Unit,
    val holdSwitch: Modifier,
    val firstRowModifier: Modifier
)

/**
 * Remembers one viewport per category across a switch and across process death.
 *
 * The focused row is keyed by the row, not its old index: saving or removing a row above the
 * cursor must not move the cursor to a different item when the category comes back. A switch
 * from the list records the exact position; a switch from the category row keeps the last
 * settled viewport instead of the temporary top the user climbed through.
 */
@Composable
internal fun rememberLibraryViewport(
    library: LibraryUiState,
    listState: LazyListState,
    rows: TrackRows,
    libraryGutters: RowGutterState,
    filterFocus: Map<LibraryFilter, FocusRequester>,
    moreFocus: LoadMoreFocusState,
    supportedFilters: Set<LibraryFilter>,
    gate: CategoryHoldGate,
    onFilter: (LibraryFilter) -> Unit
): LibraryViewportState {
    val filter = library.filter
    val items = library.libraryItems
    val loading = items.isEmpty() && library.libraryLoading
    val viewports = rememberSaveable(saver = LibraryViewportsSaver) {
        mutableStateMapOf<LibraryFilter, LibraryViewport>()
    }
    var lastFocusedRowKey by remember { mutableStateOf<String?>(null) }
    var pendingViewport by remember { mutableStateOf<LibraryViewport?>(null) }
    var restoreFocusIndex by remember { mutableStateOf<Int?>(null) }
    var focusFilterRow by remember { mutableStateOf(false) }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        snapshotFlow { rows.focus.focusedKey ?: libraryGutters.focusedKey }.collect { key ->
            if (key != null) lastFocusedRowKey = key.toString()
        }
    }
    // Switching category swaps this screen between the status body and the list, which
    // re-creates the filter row and drops focus. Re-attach focus to the selected category
    // whenever the category or the body branch changes, so keyboard navigation across the row
    // never falls through to the action bar.
    val branch = when {
        loading -> "loading"
        items.isEmpty() && library.libraryError != null -> "error"
        items.isEmpty() -> "empty"
        else -> "list"
    }
    // A row that has settled under the focus is a reading position: the user stopped moving
    // through the list. Recording it here keeps the last real position even when the next
    // thing they do is climb all the way up to the category row.
    val settledRowKey = rows.focus.openKey ?: libraryGutters.openKey
    LaunchedEffect(settledRowKey, filter, branch) {
        val settled = settledRowKey ?: return@LaunchedEffect
        if (branch != "list") return@LaunchedEffect
        val itemIndex = items.indexOfFirst { libraryRowFocusKey(it) == settled.toString() }
        if (itemIndex < 0) return@LaunchedEffect
        viewports[filter] = listState.captureViewport(settled.toString())
    }
    val switchFilter: (LibraryFilter) -> Unit = { next ->
        if (next != filter) {
            val focusedKey = rows.focus.focusedKey ?: libraryGutters.focusedKey
            val focusedIndex = focusedKey?.let { key ->
                items.indexOfFirst { libraryRowFocusKey(it) == key.toString() }
            } ?: -1
            val focusedVisible = focusedIndex >= 0 &&
                listState.layoutInfo.visibleItemsInfo.any { it.index == focusedIndex + 1 }
            // A quick switch from the list records the exact position. A switch from the
            // category row keeps the last settled viewport instead of the temporary top of
            // the list the user climbed through to reach the row.
            if (focusedVisible || viewports[filter] == null) {
                viewports[filter] = listState.captureViewport(
                    focusedKey?.toString() ?: lastFocusedRowKey
                )
            }
            pendingViewport = viewports[next]
            restoreFocusIndex = null
            lastFocusedRowKey = null
            onFilter(next)
        }
    }
    val holdSwitch = Modifier.libraryCategoryHoldSwitch(
        filter = filter,
        supportedFilters = supportedFilters,
        enabled = rows.bar.selection == null,
        gate = gate,
        onSwitch = switchFilter
    )
    LaunchedEffect(filter, branch) {
        if (branch != "list") {
            moreFocus.reset()
            runCatching { filterFocus.getValue(filter).requestFocus() }
            return@LaunchedEffect
        }
        val viewport = pendingViewport
        if (viewport == null) {
            listState.scrollToItem(0)
            runCatching { filterFocus.getValue(filter).requestFocus() }
            return@LaunchedEffect
        }
        pendingViewport = null
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
    LaunchedEffect(filter) {
        moreFocus.reset()
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
    // Up from the first row returns to the keyboard row rather than letting focus search pick
    // the geometrically nearest button, which is the middle one. The category row can be
    // outside the composed window when the first item sits at the top, so scroll it back in
    // and attach its focus requester before asking it to take the cursor.
    LaunchedEffect(focusFilterRow) {
        if (!focusFilterRow) return@LaunchedEffect
        listState.scrollToItem(0)
        repeat(3) {
            withFrameNanos { }
            if (runCatching { filterFocus.getValue(filter).requestFocus() }.isSuccess) {
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
    return LibraryViewportState(
        restoreFocusIndex = restoreFocusIndex,
        restoreFocus = restoreFocus,
        switchFilter = switchFilter,
        holdSwitch = holdSwitch,
        firstRowModifier = firstRowModifier
    )
}
