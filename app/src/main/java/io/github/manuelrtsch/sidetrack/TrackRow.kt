package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.GutterRow
import io.github.manuelrtsch.sidetrack.ui.MeatballIcon
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberGutterSquareFocus
import io.github.manuelrtsch.sidetrack.ui.settle

/** The row whose gutter is showing in a track list. */
@Stable
class TrackRowFocusState : RowGutterState()

/**
 * A track list's shared gutter state, contextual action bar and row actions.
 *
 * A row has one gutter trigger. Pressing it publishes the row's actions to the screen's
 * contextual bar, so the row itself never grows a second square.
 */
class TrackRows(
    val focus: TrackRowFocusState,
    val bar: RowActionBarState,
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
    val bar = rememberRowActionBarState()
    focus.settle()
    return TrackRows(focus, bar, saved.savedTrackIds, onSave, onEnsureSaved, onQueue)
}

/**
 * A track row with one gutter trigger. The trigger opens the contextual bar with Save/Unsave and
 * Add to queue.
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
    val selected = rows.bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    CloseRowActionsWhenDisposed(selected, rows.bar)
    GutterRow(key, rows.focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        SpotifyMediaRow(
            track,
            Modifier
                .focusRequester(rowFocus)
                .onFocusChanged { state ->
                    if (state.isFocused) rows.focus.rowFocused(key) else rows.focus.rowUnfocused(key)
                }
                .then(
                    if (selected) {
                        Modifier.border(2.dp, SidekitTheme.colors.light, SidekitTheme.shapes.item)
                    } else {
                        Modifier
                    }
                ),
            index = index,
            showArt = showArt,
            secondary = secondary,
            // The square gets the right edge while it is visible.
            trailing = if (gutterVisible) null else formatDuration(track.durationMs),
            onClick = {
                rows.focus.touchDismissed(key)
                onClick()
            }
        )
    }) {
        // The trigger and its focus state only exist while the gutter is open. A long list
        // otherwise pays for a focus modifier and a saved-state request on every row it composes.
        val squareFocus = rememberGutterSquareFocus(key, rows.focus)
        // The saved state of a row that never played is unknown: the gutter is the moment to ask.
        LaunchedEffect(Unit) { rows.onEnsureSaved(track) }
        RowActionSquare(
            color = SidekitTheme.colors.surfaceA4,
            label = "Actions for ${track.title}",
            content = { MeatballIcon() },
            modifier = squareFocus,
            onClick = {
                rows.focus.touchDismissed(key)
                rowFocus.requestFocus()
                rows.bar.show(
                    RowActionSelection(
                        key = key,
                        title = track.title,
                        focusRequester = rowFocus,
                        actions = listOf(
                            RowAction(
                                key = "save",
                                label = if (saved) "Unsave" else "Save",
                                contentDescription = if (saved) {
                                    "Remove ${track.title} from Liked Songs"
                                } else {
                                    "Save ${track.title} to Liked Songs"
                                },
                                icon = if (saved) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                                tone = if (saved) ActionTone.Destructive else ActionTone.Primary,
                                onClick = {
                                    rows.onSave(track)
                                    if (saved) {
                                        rows.bar.showUndo(
                                            RowActionUndo(
                                                contentDescription = "Undo remove ${track.title} from Liked Songs",
                                                onClick = { rows.onSave(track) }
                                            )
                                        )
                                    }
                                }
                            ),
                            RowAction(
                                key = "queue",
                                label = "Queue",
                                contentDescription = "Add ${track.title} to queue",
                                icon = SidekitIcon.QueueMusic,
                                tone = ActionTone.Primary,
                                onClick = { rows.onQueue(track) }
                            )
                        )
                    )
                )
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
 * are for. Its contextual bar offers the two actions the context implies — replay it later, or
 * forget it.
 */
@Composable
fun HistoryRow(
    key: Any,
    item: SpotifyMediaItem,
    focus: RowGutterState,
    bar: RowActionBarState,
    modifier: Modifier = Modifier,
    secondary: String = item.creator,
    onQueue: (SpotifyMediaItem) -> Unit,
    onRemove: (SpotifyMediaItem) -> (() -> Unit)?,
    onClick: () -> Unit
) {
    val selected = bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    CloseRowActionsWhenDisposed(selected, bar)
    GutterRow(key, focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        SpotifyMediaRow(
            item,
            Modifier
                .focusRequester(rowFocus)
                .onFocusChanged { state ->
                    if (state.isFocused) focus.rowFocused(key) else focus.rowUnfocused(key)
                }
                .then(
                    if (selected) {
                        Modifier.border(2.dp, SidekitTheme.colors.light, SidekitTheme.shapes.item)
                    } else {
                        Modifier
                    }
                ),
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
            color = SidekitTheme.colors.surfaceA4,
            label = "Actions for ${item.title}",
            content = { MeatballIcon() },
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                rowFocus.requestFocus()
                bar.show(
                    RowActionSelection(
                        key = key,
                        title = item.title,
                        focusRequester = rowFocus,
                        actions = listOf(
                            RowAction(
                                key = "queue",
                                label = "Queue",
                                contentDescription = "Add ${item.title} to queue",
                                icon = SidekitIcon.QueueMusic,
                                tone = ActionTone.Primary,
                                onClick = { onQueue(item) }
                            ),
                            RowAction(
                                key = "remove",
                                label = "Remove",
                                contentDescription = "Remove ${item.title} from history",
                                icon = SidekitIcon.DeleteForever,
                                tone = ActionTone.Destructive,
                                onClick = {
                                    val undo = onRemove(item)
                                    if (undo != null) {
                                        bar.showUndo(
                                            RowActionUndo(
                                                contentDescription = "Undo remove ${item.title} from history",
                                                onClick = undo
                                            )
                                        )
                                    }
                                }
                            )
                        )
                    )
                )
            }
        )
    }
}
