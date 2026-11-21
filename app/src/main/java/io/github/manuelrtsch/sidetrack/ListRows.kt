package io.github.manuelrtsch.sidetrack

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.LinkArrow
import io.github.manuelrtsch.sidetrack.ui.LocalSwipeActionRevealed

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    SidekitText(
        text.uppercase(),
        // Keep the label sitting the same distance inside the row content it heads.
        modifier.padding(start = rememberMediaRowMetrics().horizontalPadding + 3.dp, top = 7.dp, bottom = 1.dp),
        style = SidekitTheme.typography.small,
        color = SidekitTheme.colors.surfaceA6
    )
}

@Composable
fun MediaRow(
    item: MediaItem,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = item.creator,
    trailing: String? = formatDuration(item.durationMs),
    trailingContent: (@Composable () -> Unit)? = null,
    /** Non-null turns the row into a checkbox with this checked state; [onClick] toggles it. */
    checked: Boolean? = null,
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    val metrics = rememberMediaRowMetrics()
    // A revealed side button is the thing being read: give its row the trailing space back.
    val actionRevealed = LocalSwipeActionRevealed.current
    SidekitTile(
        modifier = modifier.fillMaxWidth().height(metrics.height)
            .then(
                if (checked != null) {
                    Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
                } else {
                    Modifier
                }
            ),
        color = if (item.playable) colors.surfaceA3 else colors.surfaceA2,
        onClick = if (checked == null) onClick else null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = metrics.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.contentGap)
        ) {
            if (showArt) {
                Cover(item.artRes, Modifier.size(metrics.artworkSize), shape = SidekitTheme.shapes.artwork, artUrl = item.artUrl, requestSize = metrics.artworkSize)
            } else if (index != null) {
                Box(Modifier.width(metrics.trackNumberWidth), contentAlignment = Alignment.Center) {
                    SidekitText("${index + 1}", style = metrics.trackNumberStyle, color = colors.surfaceA6)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(metrics.textGap)) {
                SidekitText(item.title, style = metrics.titleStyle, maxLines = 1)
                SidekitText(
                    secondary + if (item.explicit) " · E" else "",
                    style = metrics.secondaryStyle,
                    color = colors.surfaceA6,
                    maxLines = 1
                )
            }
            if (trailingContent != null) {
                trailingContent()
            } else if (trailing != null && !actionRevealed) {
                SidekitText(
                    trailing,
                    style = metrics.secondaryStyle,
                    color = colors.surfaceA6
                )
            }
        }
    }
}

@Composable
fun EmptyMessage(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SidekitText(title, style = SidekitTheme.typography.bodySmall, color = SidekitTheme.colors.surfaceA6)
        if (hint != null) SidekitText(hint, style = SidekitTheme.typography.caption, color = SidekitTheme.colors.surfaceA6)
    }
}

@Composable
fun LibraryRow(
    @DrawableRes artRes: Int,
    title: String,
    secondary: String,
    modifier: Modifier = Modifier,
    artUrl: String? = null,
    /** False while a row gutter is open: the squares take the right edge from the arrow. */
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    val metrics = rememberMediaRowMetrics()
    SidekitTile(
        modifier.fillMaxWidth().height(metrics.height),
        color = colors.surfaceA3,
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = metrics.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.contentGap)
        ) {
            Cover(artRes, Modifier.size(metrics.artworkSize), shape = SidekitTheme.shapes.artwork, artUrl = artUrl, requestSize = metrics.artworkSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(metrics.textGap)) {
                SidekitText(title, style = metrics.titleStyle, maxLines = 1)
                SidekitText(secondary, style = metrics.secondaryStyle, color = colors.surfaceA6, maxLines = 1)
            }
            if (showArrow) LinkArrow()
        }
    }
}
