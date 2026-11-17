package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon

/** One action offered by the contextual row bar. */
data class RowAction(
    val key: String,
    val label: String,
    val icon: SidekitIcon,
    val tone: ActionTone = ActionTone.Muted,
    val onClick: () -> Unit
)

/** The row whose actions are currently shown in the contextual bar. */
data class RowActionSelection(
    val key: Any,
    val title: String,
    val actions: List<RowAction>
)

/**
 * Screen-level state for the contextual row action bar.
 *
 * A row publishes a selection when its single gutter square is pressed. The screen's normal
 * action bar is replaced by the selection's title/close action and up to three square actions.
 * Closing clears the selection and restores the normal bar.
 */
@Stable
class RowActionBarState {
    var selection: RowActionSelection? by mutableStateOf(null)
        private set

    fun show(selection: RowActionSelection) {
        this.selection = selection
    }

    fun close() {
        selection = null
    }
}

@Composable
fun rememberRowActionBarState(): RowActionBarState {
    val chrome = LocalChrome.current
    val state = remember { RowActionBarState() }
    LaunchedEffect(state.selection) {
        if (state.selection != null) chrome?.focusActionBar()
    }
    return state
}

/**
 * Replaces [normal] with the contextual row bar while a row is selected.
 *
 * The leading action carries the row title and a close glyph, so the bar says what it applies to
 * and how to leave it. The trailing actions are the row's square actions.
 */
fun rowActionBarActions(
    state: RowActionBarState,
    normal: List<SideAction>
): List<SideAction> {
    val selection = state.selection ?: return normal
    val close = SideAction(
        key = "rowActionClose",
        label = selection.title,
        icon = SidekitIcon.Close,
        iconAtEnd = true,
        tone = ActionTone.Muted,
        dense = true,
        contentDescription = "Close actions for ${selection.title}",
        onClick = state::close
    )
    val actions = selection.actions.map { action ->
        SideAction.square(
            key = "rowAction:${action.key}",
            icon = action.icon,
            contentDescription = action.label,
            tone = action.tone,
            onClick = {
                state.close()
                action.onClick()
            }
        )
    }
    return listOf(close) + actions
}
