package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare

/** How long a row must hold the focus before its gutter control appears. */
internal const val GUTTER_DELAY_MS = 250L

/** A lifted row only lends the arrows a sliver of width, as Arrange apps does. */
internal val LiftedGutter = 30.dp

/** How far the gutter control slides in from the right as it appears. */
internal val GUTTER_SLIDE = 12.dp

/** How long the gutter takes to open or close. */
internal const val GUTTER_ANIM_MS = 160

internal enum class GutterMode { Lifted, Actions }

/** Icon size of the bare arrows beside a lifted row. */
internal val LiftedIconSize = 24.dp

/**
 * A full-height square row control, the same footprint as the swipe reveal's Remove from queue
 * block: one item-height square, the item's corner radius, a large icon.
 */
@Composable
internal fun GutterSquare(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: SidekitIcon? = null,
    content: (@Composable () -> Unit)? = null
) = RowActionSquare(color, label, onClick, modifier, icon, content)

/**
 * A directional symbol beside a picked-up row. It is inert — one shows which way the row can
 * travel, it is not focusable or clickable. Keypad moving happens through the lift's key handler.
 */
@Composable
internal fun ReorderArrow(
    up: Boolean,
    size: Dp = SidekitTheme.spacing.iconSize,
    tint: Color = SidekitTheme.colors.surfaceA5
) {
    SidekitIconImage(
        SidekitIcon.ArrowUpward,
        modifier = if (up) Modifier else Modifier.rotate(180f),
        size = size,
        tint = tint,
        contentDescription = null
    )
}
