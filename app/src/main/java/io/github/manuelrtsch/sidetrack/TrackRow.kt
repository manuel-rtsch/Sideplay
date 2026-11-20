package io.github.manuelrtsch.sidetrack

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
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
    val onSave: (Track) -> Unit,
    val onEnsureSaved: (Track) -> Unit,
    val onAddToQueue: (Track) -> Unit
) {
    fun saved(track: Track): Boolean = track.key.encode() in savedIds
}

/** Remembers the list's gutter state and settles it once focus stops moving. */
@Composable
fun rememberTrackRows(
    saved: SavedUiState,
    onSave: (Track) -> Unit,
    onEnsureSaved: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit
): TrackRows {
    val focus = remember { TrackRowFocusState() }
    val bar = rememberRowActionBarState()
    focus.settle()
    return TrackRows(focus, bar, saved.savedTrackIds, onSave, onEnsureSaved, onAddToQueue)
}

/**
 * A track row with one gutter trigger. The trigger opens the contextual bar with Add to queue.
 *
 * Save/Unsave is deliberately page-only: it is a membership toggle with a destructive direction,
 * and Now Playing already owns it. Artist and album pages are destinations for the same reason.
 */
@Composable
fun TrackRow(
    key: Any,
    track: Track,
    rows: TrackRows,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = track.artist,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val selected = rows.bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    CloseRowActionsWhenDisposed(selected, rows.bar)
    GutterRow(key, rows.focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        MediaRow(
            track,
            Modifier
                .focusRequester(rowFocus)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(bodyModifier)
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
        // otherwise pays for a focus modifier on every row it composes.
        val squareFocus = rememberGutterSquareFocus(key, rows.focus)
        RowActionSquare(
            color = SidekitTheme.colors.surfaceA4,
            label = "Actions for ${track.title}",
            content = { MeatballIcon() },
            modifier = squareFocus,
            onClick = {
                rows.focus.touchDismissed(key)
                rows.bar.show(
                    RowActionSelection(
                        key = key,
                        title = track.title,
                        focusRequester = rowFocus,
                        actions = listOf(
                            RowAction(
                                key = "addToQueue",
                                label = "Add to queue",
                                contentDescription = "Add ${track.title} to queue",
                                icon = SidekitIcon.PlaylistAdd,
                                tone = ActionTone.Primary,
                                onClick = { rows.onAddToQueue(track) }
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
    item: MediaItem,
    focus: RowGutterState,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = item.creator,
    focusRequester: FocusRequester? = null,
    onAddToQueue: (MediaItem) -> Unit,
    onClick: () -> Unit
) {
    GutterRow(key, focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        MediaRow(
            item,
            Modifier
                .onFocusChanged { state ->
                    if (state.isFocused) focus.rowFocused(key) else focus.rowUnfocused(key)
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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
            icon = SidekitIcon.PlaylistAdd,
            label = "Add ${item.title} to queue",
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                onAddToQueue(item)
            }
        )
    }
}

/**
 * An artist row in a library list. Its gutter publishes Artist radio only. Follow/Unfollow is
 * page-only: it has a destructive direction and the artist page already owns it.
 */
@Composable
fun ArtistGutterRow(
    key: Any,
    artist: Artist,
    focus: RowGutterState,
    bar: RowActionBarState,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    canRadio: Boolean = true,
    onRadio: () -> Unit,
    onClick: () -> Unit
) {
    val selected = bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    CloseRowActionsWhenDisposed(selected, bar)
    if (!canRadio) {
        val plainModifier = modifier.then(bodyModifier).let { base ->
            if (focusRequester != null) base.focusRequester(focusRequester) else base
        }
        LibraryRow(
            artist.artRes,
            artist.name,
            "Artist",
            plainModifier,
            artUrl = artist.artUrl,
            onClick = onClick
        )
        return
    }
    val actions = listOf(
        RowAction(
            key = "artistRadio",
            label = "Artist radio",
            contentDescription = "Artist radio for ${artist.name}",
            icon = SidekitIcon.Explore,
            tone = ActionTone.Primary,
            onClick = onRadio
        )
    )
    GutterRow(
        key,
        focus,
        squares = 1,
        modifier = modifier,
        content = { gutterVisible ->
            LibraryRow(
                artist.artRes,
                artist.name,
                "Artist",
                Modifier
                    .focusRequester(rowFocus)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(bodyModifier)
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
                artUrl = artist.artUrl,
                showArrow = !gutterVisible,
                onClick = {
                    focus.touchDismissed(key)
                    onClick()
                }
            )
        }
    ) {
        val squareFocus = rememberGutterSquareFocus(key, focus)
        RowActionSquare(
            color = SidekitTheme.colors.surfaceA4,
            label = "Actions for ${artist.name}",
            content = { MeatballIcon() },
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                bar.show(RowActionSelection(key, artist.name, actions, rowFocus))
            }
        )
    }
}

/**
 * An album row in a library list. Its gutter publishes Add to queue only. Save/Unsave is page-only:
 * it has a destructive direction and the album page already owns it.
 */
@Composable
fun AlbumGutterRow(
    key: Any,
    album: Album,
    focus: RowGutterState,
    bar: RowActionBarState,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onAddToQueue: () -> Unit,
    onClick: () -> Unit
) {
    val secondary = if (album.year > 0) "${album.artist} · ${album.year}" else album.artist
    LibraryCollectionGutterRow(
        key,
        album.artRes,
        album.artUrl,
        album.title,
        secondary,
        focus,
        bar,
        RowAction(
            key = "addToQueue",
            label = "Add to queue",
            contentDescription = "Add ${album.title} to queue",
            icon = SidekitIcon.PlaylistAdd,
            tone = ActionTone.Primary,
            onClick = onAddToQueue
        ),
        modifier,
        bodyModifier,
        focusRequester,
        onClick
    )
}

/**
 * A playlist row in the library. Its gutter publishes Add to queue only; Save/Unsave stays on the
 * playlist page because it has a destructive direction.
 */
@Composable
fun PlaylistGutterRow(
    key: Any,
    playlist: Playlist,
    focus: RowGutterState,
    bar: RowActionBarState,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onAddToQueue: () -> Unit,
    onClick: () -> Unit
) {
    LibraryCollectionGutterRow(
        key,
        playlist.artRes,
        playlist.artUrl,
        playlist.name,
        playlistSubtitle(playlist),
        focus,
        bar,
        RowAction(
            key = "addToQueue",
            label = "Add to queue",
            contentDescription = "Add ${playlist.name} to queue",
            icon = SidekitIcon.PlaylistAdd,
            tone = ActionTone.Primary,
            onClick = onAddToQueue
        ),
        modifier,
        bodyModifier,
        focusRequester,
        onClick
    )
}

/**
 * A podcast show row. Its gutter publishes Add latest to queue only. Save/Unsave is page-only, and
 * show notes stay on the page where the notes row already reads them.
 */
@Composable
fun ShowGutterRow(
    key: Any,
    show: Show,
    focus: RowGutterState,
    bar: RowActionBarState,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onAddLatestToQueue: () -> Unit,
    onClick: () -> Unit
) {
    LibraryCollectionGutterRow(
        key,
        show.artRes,
        show.artUrl,
        show.name,
        show.publisher,
        focus,
        bar,
        RowAction(
            key = "addLatestToQueue",
            label = "Add latest to queue",
            contentDescription = "Add latest episode of ${show.name} to queue",
            icon = SidekitIcon.PlaylistAdd,
            tone = ActionTone.Primary,
            onClick = onAddLatestToQueue
        ),
        modifier,
        bodyModifier,
        focusRequester,
        onClick
    )
}

/** Shared meatball gutter for the three collection-shaped library rows: album, playlist and show. */
@Composable
private fun LibraryCollectionGutterRow(
    key: Any,
    @DrawableRes artRes: Int,
    artUrl: String?,
    title: String,
    secondary: String,
    focus: RowGutterState,
    bar: RowActionBarState,
    action: RowAction,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val selected = bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    CloseRowActionsWhenDisposed(selected, bar)
    val actions = listOf(action)
    GutterRow(
        key,
        focus,
        squares = 1,
        modifier = modifier,
        content = { gutterVisible ->
            LibraryRow(
                artRes,
                title,
                secondary,
                Modifier
                    .focusRequester(rowFocus)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(bodyModifier)
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
                artUrl = artUrl,
                showArrow = !gutterVisible,
                onClick = {
                    focus.touchDismissed(key)
                    onClick()
                }
            )
        }
    ) {
        val squareFocus = rememberGutterSquareFocus(key, focus)
        RowActionSquare(
            color = SidekitTheme.colors.surfaceA4,
            label = "Actions for $title",
            content = { MeatballIcon() },
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                bar.show(RowActionSelection(key, title, actions, rowFocus))
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
    item: MediaItem,
    focus: RowGutterState,
    bar: RowActionBarState,
    modifier: Modifier = Modifier,
    secondary: String = item.creator,
    focusRequester: FocusRequester? = null,
    onAddToQueue: (MediaItem) -> Unit,
    onRemove: (MediaItem) -> (() -> Unit)?,
    onClick: () -> Unit
) {
    val selected = bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    CloseRowActionsWhenDisposed(selected, bar)
    GutterRow(key, focus, squares = 1, modifier = modifier, content = { gutterVisible ->
        MediaRow(
            item,
            Modifier
                .focusRequester(rowFocus)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
                bar.show(
                    RowActionSelection(
                        key = key,
                        title = item.title,
                        focusRequester = rowFocus,
                        actions = listOf(
                            RowAction(
                                key = "addToQueue",
                                label = "Add to queue",
                                contentDescription = "Add ${item.title} to queue",
                                icon = SidekitIcon.PlaylistAdd,
                                tone = ActionTone.Primary,
                                onClick = { onAddToQueue(item) }
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
