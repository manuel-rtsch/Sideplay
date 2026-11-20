package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import hrln.interactive.sidekit.sdk.ui.rememberUndoWindow

/**
 * One action offered by the contextual row bar.
 *
 * A row action is a live action, so it wears the theme accent like every option in the Options bar
 * (see `docs/options-menu-audit.md`); [ActionTone.Destructive] marks a removal, and
 * [ActionTone.Muted] is left to Cancel. Build the list with [rowActions] rather than by hand.
 */
data class RowAction(
    val key: String,
    val label: String,
    val icon: SidekitIcon,
    val tone: ActionTone = ActionTone.Primary,
    val contentDescription: String = label,
    /** Custom glyph drawn instead of [icon], for actions that need more than one symbol. */
    val art: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit
)

/**
 * A row's contextual menu, built in one place for the same reason [sidetrackOptions] is: eight rows
 * offer overlapping actions, and assembling them here keeps the wording, glyph, colour and order
 * from drifting apart.
 *
 * Two rules:
 *
 * - **Order.** Destructive actions come last, whatever order the caller lists them in, so the
 *   destructive button is never the one the focus ring lands on first.
 * - **Colour.** Accent for every action, [ActionTone.Destructive] for a removal, grey only for
 *   Cancel. Pass `null` for an action the row cannot offer and it leaves no gap.
 *
 * Shape stays with the bar: three or more actions are icon-only squares, one or two keep their
 * label, because a contextual bar earns its space from the gesture that opened it.
 */
fun rowActions(vararg entries: RowAction?): List<RowAction> = entries.filterNotNull().sortedBy { if (it.tone == ActionTone.Destructive) 1 else 0 }

fun addToQueueRowAction(
    title: String,
    label: String = "Add to queue",
    onClick: () -> Unit
) = RowAction(
    key = "addToQueue",
    label = label,
    contentDescription = "Add $title to queue",
    icon = SidekitIcon.PlaylistAdd,
    onClick = onClick
)

/** Adding to a playlist is an action like any other, not a quiet alternative. */
fun addToPlaylistRowAction(title: String, onClick: () -> Unit) = RowAction(
    key = "addToPlaylist",
    label = "Add to playlist",
    contentDescription = "Add $title to a playlist",
    icon = SidekitIcon.PlaylistAdd,
    onClick = onClick
)

fun removeFromPlaylistRowAction(title: String, onClick: () -> Unit) = RowAction(
    key = "removeFromPlaylist",
    label = "Remove from playlist",
    contentDescription = "Remove $title from this playlist",
    icon = SidekitIcon.DeleteForever,
    tone = ActionTone.Destructive,
    onClick = onClick
)

fun removeFromQueueRowAction(title: String, onClick: () -> Unit) = RowAction(
    key = "remove",
    label = "Remove",
    contentDescription = "Remove $title from queue",
    icon = SidekitIcon.DeleteForever,
    tone = ActionTone.Destructive,
    onClick = onClick
)

fun removeFromHistoryRowAction(title: String, onClick: () -> Unit) = RowAction(
    key = "remove",
    label = "Remove",
    contentDescription = "Remove $title from history",
    icon = SidekitIcon.DeleteForever,
    tone = ActionTone.Destructive,
    onClick = onClick
)

fun moveRowAction(title: String, onClick: () -> Unit) = RowAction(
    key = "move",
    label = "Move",
    contentDescription = "Move $title",
    icon = SidekitIcon.SwapVert,
    onClick = onClick
)

fun artistRadioRowAction(artist: String, onClick: () -> Unit) = RowAction(
    key = "artistRadio",
    label = "Artist radio",
    contentDescription = "Artist radio for $artist",
    icon = SidekitIcon.Explore,
    onClick = onClick
)

fun episodeNotesRowAction(title: String, onClick: () -> Unit) = RowAction(
    key = "notes",
    label = "Notes",
    contentDescription = "Read notes for $title",
    icon = SidekitIcon.Docs,
    onClick = onClick
)

/** The question a destructive row action asks before it writes. Cancel is the only grey action. */
fun cancelRowAction(onClick: () -> Unit = {}) = RowAction(
    key = "cancelRemove",
    label = "Cancel",
    icon = SidekitIcon.Close,
    tone = ActionTone.Muted,
    onClick = onClick
)

fun confirmRemoveFromPlaylistRowAction(title: String, onClick: () -> Unit) = RowAction(
    key = "confirmRemove",
    label = "Remove",
    contentDescription = "Remove $title from this playlist",
    icon = SidekitIcon.DeleteForever,
    tone = ActionTone.Destructive,
    onClick = onClick
)

/** A destructive row action that can still be taken back from the bar. */
data class RowActionUndo(
    val contentDescription: String,
    val onClick: () -> Unit
)

/** The row whose actions are currently shown in the contextual bar. */
data class RowActionSelection(
    val key: Any,
    val title: String,
    val actions: List<RowAction>,
    val focusRequester: FocusRequester? = null
)

/**
 * Screen-level state for the contextual row action bar.
 *
 * A row publishes a selection when its single gutter square is pressed. The screen's normal
 * action bar is replaced by up to three square actions and a close square on the right. Closing
 * clears the selection and restores the normal bar.
 */
@Stable
class RowActionBarState {
    var selection: RowActionSelection? by mutableStateOf(null)
        private set

    internal var pendingFocus: FocusRequester? by mutableStateOf(null)
        private set

    /** Lets the screen's Options bar close before this one opens; they never share the bar. */
    internal var beforeShow: (() -> Unit)? = null

    /** The last destructive row action, while its Undo is still offered. */
    var undo: RowActionUndo? by mutableStateOf(null)
        private set

    internal fun takePendingFocus(): FocusRequester? {
        val target = pendingFocus
        pendingFocus = null
        return target
    }

    fun show(selection: RowActionSelection) {
        beforeShow?.invoke()
        undo = null
        // A new selection owns the bar. A close that immediately opened it (the removal
        // confirmation) must not restore focus to the row a frame later.
        pendingFocus = null
        this.selection = selection
    }

    fun showUndo(undo: RowActionUndo) {
        selection = null
        this.undo = undo
    }

    fun clearUndo() {
        undo = null
    }

    fun close(restoreFocus: Boolean = true) {
        val target = selection?.focusRequester
        selection = null
        if (restoreFocus) pendingFocus = target
    }
}

@Composable
fun rememberRowActionBarState(): RowActionBarState {
    val chrome = LocalChrome.current
    val state = remember { RowActionBarState() }
    // Options and row actions both own the action bar. Opening either one dismisses the other so
    // the two menus can never stack or leave a hidden selection waiting behind the Options bar.
    state.beforeShow = { chrome?.closeOptions() }
    LaunchedEffect(chrome?.optionsOpen) {
        if (chrome?.optionsOpen == true) state.close(restoreFocus = false)
    }
    LaunchedEffect(state.selection) {
        if (state.selection == null) return@LaunchedEffect
        // The bar morphs to the row's actions in the same frame the selection changes. Asking for
        // focus before that frame has been applied leaves it on the row, so wait for the new
        // buttons to exist and retry once if the host has not registered its first slot yet.
        repeat(3) {
            withFrameNanos { }
            if (chrome?.focusActionBar() == true) return@LaunchedEffect
        }
    }
    // One Undo window for every row action in the app: five seconds of visible time, paused while
    // the screen is in the background and committed when the screen goes away.
    val undoWindow = rememberUndoWindow { state.clearUndo() }
    UndoWindowLifecycle(undoWindow) { state.clearUndo() }
    LaunchedEffect(state.undo) {
        if (state.undo == null) undoWindow.cancel() else undoWindow.start()
    }
    // The bar has to leave composition before the row can take focus back; requesting it in the
    // same frame as the click leaves the focus owner on the disappearing action bar.
    LaunchedEffect(state.pendingFocus) {
        if (state.pendingFocus == null) return@LaunchedEffect
        withFrameNanos { }
        state.takePendingFocus()?.let { runCatching { it.requestFocus() } }
    }
    return state
}

/**
 * Replaces [normal] with the contextual row bar while a row is selected.
 *
 * The row's actions come first and the close square sits on the right, so the bar never repeats
 * the row title. The selected row's border is the context.
 */
fun rowActionBarActions(
    state: RowActionBarState,
    normal: List<SideAction>
): List<SideAction> {
    state.undo?.let { pending ->
        val undo = SideAction.undo(
            key = "rowUndo",
            contentDescription = pending.contentDescription,
            onClick = {
                state.clearUndo()
                pending.onClick()
            }
        )
        val continueWith = normal.firstOrNull()
        return if (continueWith != null) undoBar(continueWith, undo) else listOf(undo)
    }
    val selection = state.selection ?: return normal
    // Three actions are icon-only squares; two (or one) get their text label so the bar stays
    // readable without turning into a wall of icons.
    val actions = selection.actions.map { action ->
        val icon = action.icon.takeIf { action.art == null }
        if (selection.actions.size >= 3) {
            SideAction(
                key = "rowAction:${action.key}",
                icon = icon,
                square = true,
                contentDescription = action.contentDescription,
                tone = action.tone,
                artKey = action.key.takeIf { action.art != null },
                art = action.art,
                onClick = {
                    state.close()
                    action.onClick()
                }
            )
        } else {
            SideAction(
                key = "rowAction:${action.key}",
                label = action.label,
                icon = icon,
                contentDescription = action.contentDescription,
                tone = action.tone,
                dense = true,
                artKey = action.key.takeIf { action.art != null },
                art = action.art,
                onClick = {
                    state.close()
                    action.onClick()
                }
            )
        }
    }
    val close = SideAction.square(
        key = "rowActionClose",
        icon = SidekitIcon.Close,
        contentDescription = "Close actions for ${selection.title}",
        tone = ActionTone.Muted,
        onClick = state::close
    )
    return actions + close
}

/** Closes the bar when the selected row leaves the lazy list's composition. */
@Composable
fun CloseRowActionsWhenDisposed(selected: Boolean, state: RowActionBarState) {
    DisposableEffect(selected) {
        onDispose { if (selected) state.close(restoreFocus = false) }
    }
}

/**
 * Closes the contextual bar when the user drags the list under it.
 *
 * Deliberately keyed on a drag, not on [LazyListState.isScrollInProgress]: opening the bar on a
 * row near the bottom focuses that row, and the list scrolls it into view. That scroll is the
 * bar's own doing, so treating it as a dismissal closed the bar in the same frame it opened.
 */
@Composable
fun RowActionBarState.dismissOnScroll(listState: LazyListState) {
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) close(restoreFocus = false)
    }
}

/** Closes the contextual bar when the keypad leaves it vertically. */
fun Modifier.dismissRowActionsOnVerticalKey(state: RowActionBarState): Modifier = onPreviewKeyEvent { event ->
    if (
        state.selection != null &&
        event.type == KeyEventType.KeyDown &&
        (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
    ) {
        state.close(restoreFocus = false)
    }
    false
}
