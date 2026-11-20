package io.github.manuelrtsch.sidetrack

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.LinkArrow
import io.github.manuelrtsch.sidetrack.ui.LocalSwipeActionRevealed

/**
 * Fixed slot for a track's position in a collection. Wide enough for three digits at the small
 * type size, so row 100+ does not ellipsize while every row keeps the same text alignment.
 */
private val TrackNumberWidth = 44.dp

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    SidekitText(
        text.uppercase(),
        modifier.padding(start = 18.dp, top = 7.dp, bottom = 1.dp),
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
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    // A revealed side button is the thing being read: give its row the trailing space back.
    val actionRevealed = LocalSwipeActionRevealed.current
    SidekitTile(
        modifier = modifier.fillMaxWidth(),
        color = if (item.playable) colors.surfaceA3 else colors.surfaceA2,
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (showArt) {
                Cover(item.artRes, Modifier.size(ListArtworkSize), shape = SidekitTheme.shapes.artwork, artUrl = item.artUrl, requestSize = ListArtworkSize)
            } else if (index != null) {
                Box(Modifier.width(TrackNumberWidth), contentAlignment = Alignment.Center) {
                    SidekitText("${index + 1}", style = SidekitTheme.typography.small, color = colors.surfaceA6)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SidekitText(item.title, style = SidekitTheme.typography.bodySmall, maxLines = 1)
                SidekitText(
                    secondary + if (item.explicit) " · E" else "",
                    style = SidekitTheme.typography.caption,
                    color = colors.surfaceA6,
                    maxLines = 1
                )
            }
            if (trailingContent != null) {
                trailingContent()
            } else if (trailing != null && !actionRevealed) {
                SidekitText(
                    trailing,
                    style = SidekitTheme.typography.caption,
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
    SidekitTile(modifier.fillMaxWidth(), color = colors.surfaceA3, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Cover(artRes, Modifier.size(ListArtworkSize), shape = SidekitTheme.shapes.artwork, artUrl = artUrl, requestSize = ListArtworkSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SidekitText(title, style = SidekitTheme.typography.bodySmall, maxLines = 1)
                SidekitText(secondary, style = SidekitTheme.typography.caption, color = colors.surfaceA6, maxLines = 1)
            }
            if (showArrow) LinkArrow()
        }
    }
}
