package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** How long a row must hold the focus before its gutter appears. */
const val GUTTER_DELAY_MS = 250L

/** How long the gutter takes to open or close. */
const val GUTTER_ANIM_MS = 160

/** Gap between two gutter squares. */
val GutterGap = 3.dp

/** How far the gutter squares slide in from the right as they appear. */
val GUTTER_SLIDE = 12.dp

/**
 * A full-height square row control, the same footprint as the swipe reveal's action block: one
 * item-height square, the item's corner radius, a large icon. Only the colour and the glyph change
 * between play, queue, rearrange and remove.
 */
@Composable
fun RowActionSquare(
    color: Color,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: SidekitIcon? = null,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier
            .width(SidekitTheme.spacing.itemHeight)
            .fillMaxHeight()
            .clip(SidekitTheme.shapes.item)
            .background(color)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
            content()
        } else if (icon != null) {
            SidekitIconImage(icon, tint = SidekitTheme.colors.light, contentDescription = null)
        }
    }
}

/** The horizontal three-dot "more" glyph used by the row action gutter. */
@Composable
fun MeatballIcon(modifier: Modifier = Modifier) {
    val color = SidekitTheme.colors.light
    Canvas(modifier.size(SidekitTheme.spacing.iconSize)) {
        val radius = size.minDimension * 0.09f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val gap = size.width * 0.24f
        drawCircle(color, radius, Offset(cx - gap, cy))
        drawCircle(color, radius, Offset(cx, cy))
        drawCircle(color, radius, Offset(cx + gap, cy))
    }
}
