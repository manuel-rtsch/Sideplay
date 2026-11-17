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

/** One action offered by the contextual row bar. */
data class RowAction(
    val key: String,
    val label: String,
    val icon: SidekitIcon,
    val tone: ActionTone = ActionTone.Muted,
    val contentDescription: String = label,
    /** Custom glyph drawn instead of [icon], for actions that need more than one symbol. */
    val art: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit
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
