package io.github.manuelrtsch.sideplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

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
            .size(SidekitTheme.spacing.itemHeight)
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
