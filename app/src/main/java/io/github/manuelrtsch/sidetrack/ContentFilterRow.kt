package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

// The shared square category row. Library and Search both attach it, so focus order and the
// disabled-slot behaviour stay identical across the two pages.

/**
 * Five square category buttons. Focus selects a category; clicking does too. A category the
 * connected service cannot fill keeps its slot but is muted and skipped, so the row never reflows
 * and no button leads to an empty page.
 */
@Composable
internal fun LibraryFilterRow(
    selected: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    focusRequesters: Map<LibraryFilter, FocusRequester>,
    modifier: Modifier = Modifier,
    supportedFilters: Set<LibraryFilter> = LibraryFilter.entries.toSet(),
    acceptsFocus: Boolean = true
) {
    ContentFilterRow(
        filters = LibraryFilter.entries,
        selected = selected,
        icon = { it.icon },
        label = { it.label },
        onSelect = onFilter,
        focusRequesters = focusRequesters,
        modifier = modifier,
        enabled = { it in supportedFilters },
        // A row action bar owns the focus while it is open. Leaving the category buttons
        // focusable let Android's exit from touch mode land on the first one, select it, and
        // dispose the very row whose contextual bar had just opened.
        acceptsFocus = acceptsFocus,
        // Unlike a search category that is merely switched off, this one cannot be switched on
        // anywhere: it is a fact about the service, not a setting.
        disabledLabel = { "not available" }
    )
}

/**
 * A row of equally wide square category buttons, one per entry of [filters]. Moving focus with
 * Left/Right (or clicking) selects a category; the screen then shows that category's content.
 * Library and Search share the row so both pages behave the same under the pointer and keypad.
 */
@Composable
internal fun <T> ContentFilterRow(
    filters: List<T>,
    selected: T,
    icon: (T) -> SidekitIcon,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    focusRequesters: Map<T, FocusRequester>,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    /** False while another surface (a row action bar) owns the focus. */
    acceptsFocus: Boolean = true,
    /** How a disabled button is announced: "off" when it could be switched on, otherwise why not. */
    disabledLabel: (T) -> String = { "off" }
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = filters.size
        val cell = (maxWidth - SidekitTheme.spacing.itemOuterGap * (columns - 1)) / columns.toFloat()
        SidekitItemRow {
            filters.forEachIndexed { index, filter ->
                val isSelected = filter == selected
                val isEnabled = enabled(filter)
                SidekitIconItem(
                    icon = icon(filter),
                    // An excluded category keeps its slot so the row never reflows; it only loses
                    // its click, its muted colour says why.
                    contentDescription = if (isEnabled) label(filter) else "${label(filter)}, ${disabledLabel(filter)}",
                    modifier = Modifier
                        .weight(1f)
                        .height(cell)
                        .focusRequester(focusRequesters.getValue(filter))
                        .then(
                            if (acceptsFocus) {
                                Modifier
                            } else {
                                Modifier.focusProperties { canFocus = false }
                            }
                        )
                        // Left/Right move to the adjacent enabled category by request. Geometric
                        // focus search picked the action bar (below and to the right) instead of
                        // the neighbouring button, and the loading swap re-created the row, so the
                        // buttons to the right were unreachable. Down/Up keep their default path
                        // into and out of the content below. Disabled buttons are skipped.
                        // Only a deliberate Left/Right (or a click) selects a category. Selecting
                        // on any focus change meant a focus restoration could walk the row by
                        // itself: when a held arrow's row was removed on a category switch, the
                        // focus system parked on the first button and instantly selected it.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionRight -> {
                                        focusAdjacent(filters, index, 1, enabled, focusRequesters)
                                            ?.let(onSelect)
                                        true
                                    }

                                    Key.DirectionLeft -> {
                                        focusAdjacent(filters, index, -1, enabled, focusRequesters)
                                            ?.let(onSelect)
                                        true
                                    }

                                    else -> false
                                }
                            }
                        }
                        .semantics {
                            this.selected = isSelected
                            if (!isEnabled) disabled()
                        },
                    color = when {
                        !isEnabled -> TileTone.Muted
                        isSelected -> TileTone.Library
                        else -> TileTone.Neutral
                    }.colour(SidekitTheme.colors),
                    iconSize = SidekitTheme.spacing.tileIconSize,
                    onClick = if (isEnabled) ({ onSelect(filter) }) else null
                )
            }
        }
    }
}

/**
 * Moves focus past disabled entries in the row, returning the category that took it, or null when
 * there is no enabled one left. The caller selects the returned category: the focus move alone is
 * navigation, so a focus restoration can never change the shown page.
 */
private fun <T> focusAdjacent(
    filters: List<T>,
    from: Int,
    step: Int,
    enabled: (T) -> Boolean,
    focusRequesters: Map<T, FocusRequester>
): T? {
    var index = from + step
    while (index in filters.indices) {
        val candidate = filters[index]
        if (enabled(candidate)) {
            focusRequesters[candidate]?.requestFocus()
            return candidate
        }
        index += step
    }
    return null
}

private val LibraryFilter.icon: SidekitIcon
    get() = when (this) {
        LibraryFilter.LikedSongs -> SidekitIcon.Favorite
        LibraryFilter.Playlists -> SidekitIcon.QueueMusic
        LibraryFilter.Albums -> SidekitIcon.Album
        LibraryFilter.Artists -> SidekitIcon.Artist
        LibraryFilter.Shows -> SidekitIcon.SmartDisplay
    }
