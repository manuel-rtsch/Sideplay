package io.github.manuelrtsch.sidetrack

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.GutterRow
import io.github.manuelrtsch.sidetrack.ui.MeatballIcon
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberGutterSquareFocus

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
    val actions = rowActions(artistRadioRowAction(artist.name, onRadio))
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
        addToQueueRowAction(album.title, onClick = onAddToQueue),
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
        addToQueueRowAction(playlist.name, onClick = onAddToQueue),
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
        addToQueueRowAction("latest episode of ${show.name}", label = "Add latest to queue", onClick = onAddLatestToQueue),
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
                        actions = rowActions(
                            addToQueueRowAction(item.title) { onAddToQueue(item) },
                            removeFromHistoryRowAction(item.title) {
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
            }
        )
    }
}
