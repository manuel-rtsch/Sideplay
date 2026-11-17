package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.GutterRow
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberGutterSquareFocus
import io.github.manuelrtsch.sidetrack.ui.settle

/** The row whose gutter is showing in a track list. */
@Stable
class TrackRowFocusState : RowGutterState()

/**
 * A track list's shared gutter state, saved set and row actions.
 *
 * A row carries at most two actions. Screens do not add a second row menu on top: the gutter is
 * the menu, and the same pair has to make sense for the list it appears in.
 */
class TrackRows(
    val focus: TrackRowFocusState,
    val savedIds: Set<String>,
    val onSave: (SpotifyTrack) -> Unit,
    val onEnsureSaved: (SpotifyTrack) -> Unit,
    val onQueue: (SpotifyTrack) -> Unit
) {
    fun saved(track: SpotifyTrack): Boolean = track.id in savedIds
}

/** Remembers the list's gutter state and settles it once focus stops moving. */
@Composable
fun rememberTrackRows(
    saved: SavedUiState,
    onSave: (SpotifyTrack) -> Unit,
    onEnsureSaved: (SpotifyTrack) -> Unit,
    onQueue: (SpotifyTrack) -> Unit
): TrackRows {
    val focus = remember { TrackRowFocusState() }
    focus.settle()
    return TrackRows(focus, saved.savedTrackIds, onSave, onEnsureSaved, onQueue)
}

/**
 * A track row with two context actions: Save/Unsave and Add to queue.
 *
 * Those are the two verbs that belong to browsing a track list. Artist and album pages are not
 * row actions: they are destinations, reached from Now Playing when the track is started.
 */
@Composable
fun TrackRow(
    key: Any,
    track: SpotifyTrack,
    rows: TrackRows,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = track.artist,
    onClick: () -> Unit
) {
    val saved = rows.saved(track)
    GutterRow(key, rows.focus, squares = 2, modifier = modifier, content = { gutterVisible ->
        SpotifyMediaRow(
            track,
            Modifier.onFocusChanged { state ->
                if (state.isFocused) rows.focus.rowFocused(key) else rows.focus.rowUnfocused(key)
            },
            index = index,
            showArt = showArt,
            secondary = secondary,
            // The squares get the right edge while they are visible.
            trailing = if (gutterVisible) null else formatDuration(track.durationMs),
            onClick = {
                rows.focus.touchDismissed(key)
                onClick()
            }
        )
    }) {
        // The squares and their focus state only exist while the gutter is open. A long list
        // otherwise pays for a focus modifier and a saved-state request on every row it composes.
        val squareFocus = rememberGutterSquareFocus(key, rows.focus)
        // The saved state of a row that never played is unknown: the gutter is the moment to ask.
        LaunchedEffect(Unit) { rows.onEnsureSaved(track) }
        RowActionSquare(
            color = SidekitTheme.colors.successA1,
            icon = if (saved) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
            label = if (saved) {
                "Remove ${track.title} from Liked Songs"
            } else {
                "Save ${track.title} to Liked Songs"
            },
            modifier = squareFocus,
            onClick = {
                rows.focus.touchDismissed(key)
                rows.onSave(track)
            }
        )
        RowActionSquare(
            color = SidekitTheme.colors.successA1,
            icon = SidekitIcon.QueueMusic,
            label = "Add ${track.title} to queue",
            modifier = squareFocus,
            onClick = {
                rows.focus.touchDismissed(key)
                rows.onQueue(track)
            }
        )
    }
}

/**
 * A non-track media row with one context action: Add to queue. Episodes have no saved-track
 * endpoint, so Save stays track-only; their show is reached from Now Playing.
 */
@Composable
fun GutterMediaRow(
    key: Any,
    item: SpotifyMediaItem,
    focus: RowGutterState,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = item.creator,
    onQueue: (SpotifyMediaItem) -> Unit,
    onClick: () -> Unit
) {
    GutterRow(key, focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        SpotifyMediaRow(
            item,
            Modifier.onFocusChanged { state ->
                if (state.isFocused) focus.rowFocused(key) else focus.rowUnfocused(key)
            },
            index = index,
            showArt = showArt,
            secondary = secondary,
            trailing = if (gutterVisible) null else formatDuration(item.durationMs),
            onClick = {
                focus.touchDismissed(key)
                onClick()
            }
        )
    }) {
        val squareFocus = rememberGutterSquareFocus(key, focus)
        RowActionSquare(
            color = SidekitTheme.colors.successA1,
            icon = SidekitIcon.QueueMusic,
            label = "Add ${item.title} to queue",
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                onQueue(item)
            }
        )
    }
}

/**
 * A Recently played/History row. Saving does not belong to history: that is what the saved lists
 * are for. The two actions here are the ones the context implies — replay it later, or forget it.
 */
@Composable
fun HistoryRow(
    key: Any,
    item: SpotifyMediaItem,
    focus: RowGutterState,
    modifier: Modifier = Modifier,
    secondary: String = item.creator,
    onQueue: (SpotifyMediaItem) -> Unit,
    onRemove: (SpotifyMediaItem) -> Unit,
    onClick: () -> Unit
) {
    GutterRow(key, focus, squares = 2, modifier = modifier, content = { gutterVisible ->
        SpotifyMediaRow(
            item,
            Modifier.onFocusChanged { state ->
                if (state.isFocused) focus.rowFocused(key) else focus.rowUnfocused(key)
            },
            secondary = secondary,
            trailing = if (gutterVisible) null else formatDuration(item.durationMs),
            onClick = {
                focus.touchDismissed(key)
                onClick()
            }
        )
    }) {
        val squareFocus = rememberGutterSquareFocus(key, focus)
        RowActionSquare(
            color = SidekitTheme.colors.successA1,
            icon = SidekitIcon.QueueMusic,
            label = "Add ${item.title} to queue",
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                onQueue(item)
            }
        )
        RowActionSquare(
            color = SidekitTheme.colors.dangerA1,
            icon = SidekitIcon.DeleteForever,
            label = "Remove ${item.title} from history",
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                onRemove(item)
            }
        )
    }
}
