package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.icons.SidekitIcon

/**
 * The arrow on a row that opens something: library rows, settings rows and the Load more footer.
 *
 * Sidekit draws its own link arrow at `spacing.iconSize`, which is 40 dp and wider than these rows
 * can spare beside a long title on the sidephone, so every row arrow in Sidetrack is drawn at this
 * one size instead. The dense list rows set the size; the footer follows them.
 */
val RowLinkArrowSize: Dp = 30.dp

/** The smaller arrow inside a line of text that acts as a link, such as the album's artist line. */
val InlineLinkArrowSize: Dp = 18.dp

/**
 * The one link arrow. It carries no description of its own: the row or line around it already
 * exposes the action, so the arrow is decoration.
 *
 * [tint] defaults to the content colour Sidekit applies inside a tile. A row that paints its own
 * container passes the colour it resolved for that container instead.
 */
@Composable
fun LinkArrow(
    modifier: Modifier = Modifier,
    size: Dp = RowLinkArrowSize,
    tint: Color? = null
) {
    if (tint == null) {
        SidekitIconImage(SidekitIcon.ArrowRightAlt, modifier, size = size)
    } else {
        SidekitIconImage(SidekitIcon.ArrowRightAlt, modifier, size = size, tint = tint)
    }
}
