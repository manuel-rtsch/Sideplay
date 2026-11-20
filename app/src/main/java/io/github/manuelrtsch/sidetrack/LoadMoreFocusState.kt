package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.container
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.LinkArrow

/**
 * A compact navigation row for list-level actions such as "See more", "Search in" and the
 * pagination footer. It mirrors Sidekit's compact item on the same primitives, because the SDK
 * draws its link arrow at `spacing.iconSize` and Sidetrack rows share a narrower one.
 *
 * [linkArrow] defaults to showing the arrow only when the row has no [secondary] value beside it:
 * a row that already shows something on the right ("Search in" with its scope) reads as a value,
 * not as a link. A footer that reports a status in [secondary] passes `true` to keep its arrow.
 * A row that cannot be pressed never shows one.
 */
@Composable
fun TerminalRow(
    label: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    linkArrow: Boolean = secondary == null,
    onClick: (() -> Unit)? = null
) {
    SidekitTile(
        modifier.fillMaxWidth().height(SidekitTheme.spacing.compactRowHeight),
        color = ItemColour.Success.container(),
        onClick = onClick,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap)
        ) {
            SidekitText(
                label,
                style = SidekitTheme.typography.bodySmall,
                maxLines = 1
            )
            if (secondary != null) {
                SidekitText(
                    secondary,
                    Modifier.weight(1f),
                    style = SidekitTheme.typography.small,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 1,
                    textAlign = TextAlign.End
                )
            }
            if (linkArrow && onClick != null) {
                // Without a value beside it the arrow takes the slack and sits at the end; with one
                // it keeps its own width so the value is not squeezed into half the row.
                Box(
                    if (secondary == null) Modifier.weight(1f) else Modifier,
                    contentAlignment = Alignment.CenterEnd
                ) { LinkArrow() }
            }
        }
    }
}

/**
 * Keeps the keypad on the Load more row while the next page is in flight, then hands focus to the
 * first row the page added. A Load more row that turns non-clickable while loading is no longer
 * focusable, so Compose drops focus and the next focus search lands somewhere up the list.
 */
@Stable
class LoadMoreFocusState {
    private var pendingFrom: Int? = null

    /** The data index of the first row the last page added, while it still needs focus. */
    var targetIndex: Int? by mutableStateOf(null)
        private set

    val moreFocus = FocusRequester()
    val firstNewFocus = FocusRequester()

    /** Call before requesting the next page, with the number of rows currently shown. */
    fun onLoadMore(itemCount: Int) {
        pendingFrom = itemCount
    }

    /** Call whenever the row count or the loading flag changes. */
    fun onItems(itemCount: Int, loading: Boolean) {
        val from = pendingFrom ?: return
        if (loading) return
        pendingFrom = null
        if (itemCount > from) targetIndex = from
    }

    fun clearTarget() {
        targetIndex = null
    }

    /** Forget a page that was requested for a list that is no longer on screen. */
    fun reset() {
        pendingFrom = null
        targetIndex = null
    }
}

@Composable
fun rememberLoadMoreFocus(): LoadMoreFocusState = remember { LoadMoreFocusState() }

/** The modifier for the row at [index] that should take focus after a page loads. */
fun LoadMoreFocusState.rowModifier(index: Int): Modifier = if (index == targetIndex) Modifier.focusRequester(firstNewFocus) else Modifier

/**
 * The requester for the row at [index] that should take focus after a page loads, or null. Row
 * wrappers that hide their focusable element behind another layout pass pass this down instead of
 * putting [rowModifier] on the wrapper, where it would have no focus target to attach to.
 */
fun LoadMoreFocusState.requesterFor(index: Int): FocusRequester? = if (index == targetIndex) firstNewFocus else null

/**
 * Watches [itemCount] and [loading] for a page requested through [state], then scrolls to the
 * first new row and focuses it. [firstItemListIndex] is the list index of data row 0, so the
 * effect can scroll to a row that the lazy list has not composed yet.
 */
@Composable
fun LoadMoreFocus(
    state: LoadMoreFocusState,
    listState: LazyListState,
    itemCount: Int,
    loading: Boolean,
    firstItemListIndex: Int
) {
    LaunchedEffect(itemCount, loading) { state.onItems(itemCount, loading) }
    LaunchedEffect(state.targetIndex) {
        val index = state.targetIndex ?: return@LaunchedEffect
        listState.scrollToItem(firstItemListIndex + index)
        // The scroll composes the row; give the frame a chance to attach the requester.
        repeat(3) {
            withFrameNanos { }
            if (runCatching { state.firstNewFocus.requestFocus() }.isSuccess) {
                state.clearTarget()
                return@LaunchedEffect
            }
        }
        state.clearTarget()
    }
}

/**
 * The one pagination footer every list uses. It only asks for the next batch when the user
 * presses it, so a list never fetches or composes rows the user has not asked to see.
 */
@Composable
fun LoadMoreRow(
    label: String,
    loading: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    focusState: LoadMoreFocusState? = null
) {
    val rowModifier = if (focusState != null) {
        modifier.focusRequester(focusState.moreFocus)
    } else {
        modifier
    }
    TerminalRow(
        label,
        modifier = rowModifier,
        secondary = if (loading) "Loading…" else null,
        // The arrow stays while the page is in flight: the row is still the thing the user pressed,
        // and the status sits beside it rather than in place of it.
        linkArrow = true,
        // Keep the row clickable while the page is in flight. A non-clickable row is not
        // focusable, so turning it off would drop the keypad and let focus search land up the
        // list. The view model ignores a second press; the local pages simply load another page.
        onClick = if (loading) ({}) else onLoadMore
    )
}

/** A shallow terminal row that loads the next page only when the user presses it. */
@Composable
fun SeeMoreRow(label: String, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TerminalRow(
        label,
        modifier = modifier,
        secondary = if (loading) "Loading…" else null,
        linkArrow = true,
        // Same rule as the Load more row: a row that turns non-clickable while loading also stops
        // being focusable, which drops the keypad off the footer.
        onClick = if (loading) ({}) else onClick
    )
}
