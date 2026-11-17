package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.components.container
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.contentColorFor

/**
 * Sidetrack's own copy of the settings row while the published design library lacks the
 * trailing-arrow and end-aligned-secondary options.
 */
@Composable
fun SidetrackItem(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    secondaryToEnd: Boolean = false,
    secondaryNoEllipsis: Boolean = false,
    icon: SidekitIcon? = null,
    showLinkArrow: Boolean = false,
    showLinkArrowWithSecondary: Boolean = false,
    colour: ItemColour = ItemColour.Primary,
    /** Overrides [colour]'s container, e.g. to paint an active row in the action colour. */
    containerColor: Color? = null,
    /** Overrides the trailing value's colour; defaults to the muted surface tone. */
    secondaryColor: Color? = null,
    titleDecoration: TextDecoration? = null,
    /** When set, the row is exposed as a switch/checkbox with this checked state. */
    toggleable: Boolean? = null,
    toggleRole: Role = Role.Switch,
    /** When set, the row is exposed as a radio/selection row with this selected state. */
    selected: Boolean? = null,
    selectionRole: Role = Role.RadioButton,
    onClick: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null
) {
    val background = containerColor ?: colour.container()
    val contentColor = SidekitTheme.colors.contentColorFor(background)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap)
        ) {
            leading?.invoke(this)
            if (icon != null) SidekitIconImage(icon, tint = contentColor)
            SidekitText(title, color = contentColor, textDecoration = titleDecoration)
            if (secondary != null) {
                if (secondaryNoEllipsis) Spacer(Modifier.weight(1f))
                SidekitText(
                    secondary,
                    if (secondaryNoEllipsis) Modifier else Modifier.weight(1f),
                    color = secondaryColor ?: SidekitTheme.colors.surfaceA6,
                    textAlign = if (secondaryToEnd) TextAlign.End else null
                )
            }
            if (showLinkArrow && (secondary == null || showLinkArrowWithSecondary)) {
                Box(
                    if (secondary == null) Modifier.weight(1f) else Modifier,
                    contentAlignment = Alignment.CenterEnd
                ) {
                    SidekitIconImage(SidekitIcon.ArrowRightAlt, tint = contentColor)
                }
            }
        }
    }
    when {
        toggleable != null -> Box(
            modifier = modifier.fillMaxWidth()
                .height(SidekitTheme.spacing.itemHeight)
                .clip(SidekitTheme.shapes.item)
                .background(background)
                .toggleable(value = toggleable, role = toggleRole, onValueChange = { onClick?.invoke() }),
            contentAlignment = Alignment.CenterStart
        ) { content() }

        selected != null -> Box(
            modifier = modifier.fillMaxWidth()
                .height(SidekitTheme.spacing.itemHeight)
                .clip(SidekitTheme.shapes.item)
                .background(background)
                .selectable(selected = selected, role = selectionRole, onClick = { onClick?.invoke() }),
            contentAlignment = Alignment.CenterStart
        ) { content() }

        else -> SidekitTile(
            modifier = modifier.fillMaxWidth(),
            color = background,
            onClick = onClick,
            contentAlignment = Alignment.CenterStart
        ) { content() }
    }
}
