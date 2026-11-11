package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.container
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * Sideplay's own copy of the settings row while the published design library lacks the
 * trailing-arrow and end-aligned-secondary options.
 */
@Composable
fun SideplayItem(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    secondaryToEnd: Boolean = false,
    icon: SidekitIcon? = null,
    showLinkArrow: Boolean = false,
    showLinkArrowWithSecondary: Boolean = false,
    colour: ItemColour = ItemColour.Primary,
    titleDecoration: TextDecoration? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null
) {
    SidekitTile(
        modifier = modifier.fillMaxWidth(),
        color = colour.container(),
        onClick = onClick,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap)
        ) {
            leading?.invoke(this)
            if (icon != null) SidekitIconImage(icon)
            SidekitText(title, textDecoration = titleDecoration)
            if (secondary != null) {
                SidekitText(
                    secondary,
                    Modifier.weight(1f),
                    color = SidekitTheme.colors.surfaceA6,
                    textAlign = if (secondaryToEnd) TextAlign.End else null
                )
            }
            if (showLinkArrow && (secondary == null || showLinkArrowWithSecondary)) {
                Box(
                    if (secondary == null) Modifier.weight(1f) else Modifier,
                    contentAlignment = Alignment.CenterEnd
                ) {
                    SidekitIconImage(SidekitIcon.ArrowRightAlt)
                }
            }
        }
    }
}
