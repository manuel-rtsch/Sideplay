package io.github.manuelrtsch.sidetrack.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.motion.SidekitMotion
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.rememberMediaRowMetrics
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** True while a swipe row's action button is visible, so its content can drop trailing metadata. */
val LocalSwipeActionRevealed = compositionLocalOf { false }

/** The action revealed by a left swipe. Delete is the default; other actions pass their own. */
data class SwipeAction(
    val label: String,
    val icon: SidekitIcon,
    val color: Color,
    val armedColor: Color = color,
    val destructive: Boolean = false,
    /** Overrides [icon] with a drawable that has no Sidekit icon equivalent. */
    @DrawableRes val iconRes: Int? = null,
    val onClick: () -> Unit
)

/** Keeps at most one row of a list swiped open, like iOS. */
@Stable
class SwipeRevealGroup {
    /** Key of the row currently open or being dragged, or null. */
    var openKey: Any? by mutableStateOf(null)
        internal set

    fun closeAll() {
        openKey = null
    }
}

@Composable
fun rememberSwipeRevealGroup(): SwipeRevealGroup = remember { SwipeRevealGroup() }

private class SwipeMetrics {
    var width = 0
}

/**
 * A list row that swipes left to reveal an action button (iOS style). Delete is the default;
 * pass [action] for a non-destructive action such as Add to queue.
 *
 * - Swipe past half the button and release to leave it open; tap it to run the action.
 * - Keep dragging past [FULL_SWIPE_FRACTION] of the row and the button stretches to fill it
 *   (with a haptic tick); release there to run the action without the extra tap.
 * - Tapping the row while open closes it instead of opening the item.
 * - Opening one row closes any other row in [group].
 * - With [revealOnFocus], a row focused by the D-pad reveals its action just like a swipe does,
 *   after a short delay so holding Up/Down through a list does not flash every button.
 * - With [onLongPress], holding the row reports a long press instead of the tap it would otherwise
 *   become. This is the touch route to a row's other actions: a touch screen has no focus border
 *   for a gutter to follow.
 *
 * Cost per drag frame: one float state write, a RenderNode translation for the row (no
 * re-record of its contents) and a redraw of the button behind it. Nothing recomposes except
 * when the row crosses open/closed or armed/unarmed.
 */
@Composable
fun SidekitSwipeRow(
    key: Any,
    group: SwipeRevealGroup,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    deleteLabel: String = "Delete",
    action: SwipeAction? = null,
    revealOnFocus: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    /** Height of the action square, so it matches the row that reveals it. */
    rowHeight: Dp = rememberMediaRowMetrics().height,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val colors = SidekitTheme.colors
    val spacing = SidekitTheme.spacing
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val swipeAction = action ?: SwipeAction(
        label = deleteLabel,
        icon = SidekitIcon.DeleteForever,
        color = colors.dangerA1,
        armedColor = colors.dangerA2,
        destructive = true,
        onClick = onDelete
    )
    val buttonPx = with(density) { rowHeight.toPx() }
    val gapPx = with(density) { spacing.itemOuterGap.toPx() }
    val radiusPx = with(density) { 36.dp.toPx() }
    val iconPx = with(density) { spacing.iconSize.toPx() }
    val openOffset = -(buttonPx + gapPx)

    val metrics = remember { SwipeMetrics() }
    var offset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    // null until the row has reported focus at least once, so the first composition does not
    // close a row that a touch swipe opened.
    var focused by remember { mutableStateOf<Boolean?>(null) }
    // The action button is a sibling focus target, not a child of the row's focus target. Track it
    // separately so moving focus into the button does not look like the row losing focus.
    var actionFocused by remember { mutableStateOf(false) }
    val arm = remember { Animatable(0f) }

    val isOpen by remember { derivedStateOf { offset < -1f } }
    val armed by remember { derivedStateOf { metrics.width > 0 && -offset > metrics.width * FULL_SWIPE_FRACTION } }

    fun settle(target: Float, velocity: Float = 0f, then: (() -> Unit)? = null) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offset, target, velocity, SidekitMotion.settle) { value, _ -> offset = value }
            then?.invoke()
        }
    }

    fun performAction() {
        group.closeAll()
        swipeAction.onClick()
    }

    LaunchedEffect(armed) {
        if (armed && dragging) view.armedHaptic()
        arm.animateTo(if (armed) 1f else 0f, SidekitMotion.settle)
    }
    LaunchedEffect(isOpen) {
        if (isOpen) {
            group.openKey = key
        } else {
            // The button is gone, so it cannot still own focus.
            actionFocused = false
            if (group.openKey == key && !dragging) group.openKey = null
        }
    }
    LaunchedEffect(group, key) {
        snapshotFlow { group.openKey }.collect { open -> if (open != key && !dragging && offset != 0f) settle(0f) }
    }
    LaunchedEffect(focused, actionFocused) {
        when {
            focused == true -> {
                // The queue's gutter waits for the focus to settle; a list that is being
                // scrubbed with Up/Down must not flash a button on every row it passes.
                delay(FOCUS_REVEAL_DELAY_MS)
                if (!dragging) {
                    group.openKey = key
                    settle(openOffset)
                }
            }

            focused == false && !actionFocused -> {
                // Focus moving from the row into the action button arrives as two events: the row
                // goes false and the button goes true. Give the button's event one frame to land
                // before treating this as the row being left for good.
                withFrameNanos { }
                if (!actionFocused && !dragging && group.openKey == key) group.closeAll()
            }

            else -> Unit
        }
    }

    val dragState = rememberDraggableState { delta ->
        val next = offset + delta
        val width = metrics.width.toFloat()
        // Rubber-band past either end.
        offset = when {
            next > 0f -> (offset + delta * RESISTANCE).coerceAtMost(gapPx * 8)
            width > 0f && next < -width -> offset + delta * RESISTANCE
            else -> next
        }
    }

    val actionIcon = rememberVectorPainter(
        ImageVector.vectorResource(swipeAction.iconRes ?: swipeAction.icon.drawable)
    )
    val tint = remember(colors.light) { ColorFilter.tint(colors.light) }

    Box(
        modifier
            .onSizeChanged { metrics.width = it.width }
            .then(
                if (revealOnFocus) {
                    Modifier.onFocusChanged { focusState -> focused = focusState.hasFocus }
                } else {
                    Modifier
                }
            )
            .drawBehind {
                val revealed = -offset - gapPx
                if (revealed <= 0f) return@drawBehind
                val a = arm.value
                val left = size.width - revealed
                val r = min(radiusPx, min(revealed, size.height) / 2f)
                drawRoundRect(
                    color = lerp(swipeAction.color, swipeAction.armedColor, a),
                    topLeft = Offset(left, 0f),
                    size = Size(revealed, size.height),
                    cornerRadius = CornerRadius(r, r)
                )
                // Icon sits centred in the button, then jumps to the leading edge once armed.
                val cx = if (revealed <= buttonPx) {
                    left + revealed / 2f
                } else {
                    lerp(size.width - buttonPx / 2f, left + buttonPx / 2f, a)
                }
                val iconAlpha = ((revealed - iconPx * 0.5f) / iconPx).coerceIn(0f, 1f)
                val s = iconPx * lerp(0.6f, 1f, iconAlpha) * (1f + 0.15f * a)
                translate(cx - s / 2f, (size.height - s) / 2f) {
                    with(actionIcon) { draw(Size(s, s), alpha = iconAlpha, colorFilter = tint) }
                }
            }
            .then(
                if (onLongPress == null) {
                    Modifier
                } else {
                    Modifier.rowLongPress {
                        view.longPressHaptic()
                        onLongPress()
                    }
                }
            )
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    settleJob?.cancel()
                    dragging = true
                    group.openKey = key
                },
                onDragStopped = { velocity ->
                    dragging = false
                    val width = metrics.width.toFloat()
                    when {
                        armed -> settle(-width, velocity) { performAction() }

                        velocity < -FLING_VELOCITY || (offset < openOffset / 2f && velocity < FLING_VELOCITY) ->
                            settle(openOffset, velocity)

                        else -> {
                            settle(0f, velocity)
                            if (group.openKey == key) group.closeAll()
                        }
                    }
                }
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(swipeAction.label) {
                        performAction()
                        true
                    }
                )
            }
    ) {
        CompositionLocalProvider(LocalSwipeActionRevealed provides isOpen) {
            Box(Modifier.graphicsLayer { translationX = offset }) { content() }
        }
        if (isOpen) {
            // While open, a tap on the row closes it rather than opening the item.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { translationX = offset }
                    .pointerInput(Unit) {
                        detectTapGestures {
                            settle(0f)
                            group.closeAll()
                        }
                    }
            )
            // matchParentSize: rows sit in a lazy list (unbounded height), so the tap target takes
            // the row's measured height rather than fillMaxHeight's (which would be 0).
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .width(rowHeight)
                        .fillMaxHeight()
                        .onFocusChanged { actionFocused = it.isFocused }
                        .clickable(role = Role.Button, onClickLabel = swipeAction.label) {
                            view.actionHaptic(swipeAction.destructive)
                            performAction()
                        }
                        .semantics { contentDescription = swipeAction.label }
                )
            }
        }
    }
}

/** Fraction of the row width past which releasing deletes outright. */
const val FULL_SWIPE_FRACTION = 0.6f

/** How long a row must hold the focus before its action button appears. */
private const val FOCUS_REVEAL_DELAY_MS = 250L

private const val RESISTANCE = 0.3f
private const val FLING_VELOCITY = 900f
