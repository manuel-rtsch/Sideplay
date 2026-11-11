package io.github.manuelrtsch.sideplay

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.media.RemoteArtworkImage
import io.github.manuelrtsch.sideplay.playback.formatDuration

/** Square artwork: remote when the catalog has a URL, otherwise a local fixture or icon. */
@Composable
fun SpotifyCover(
    @DrawableRes artRes: Int,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = SidekitTheme.shapes.control,
    bordered: Boolean = false,
    contentDescription: String? = null,
    artUrl: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null
) {
    val colors = SidekitTheme.colors
    Box(
        modifier
            .clip(shape)
            .background(colors.surfaceA2)
            .then(if (bordered) Modifier.border(1.dp, colors.light, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
    ) {
        when {
            artUrl != null -> RemoteArtworkImage(
                artUrl,
                Modifier.fillMaxSize(),
                placeholder = SidekitIcon.Album,
                contentDescription = contentDescription
            )

            artRes != 0 -> Image(
                painter = painterResource(artRes),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            else -> SidekitIconImage(
                SidekitIcon.Album,
                Modifier.align(Alignment.Center),
                contentDescription = contentDescription
            )
        }
    }
}

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
fun SpotifyMediaRow(
    item: SpotifyMediaItem,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = item.creator,
    trailing: String? = formatDuration(item.durationMs),
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
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
                SpotifyCover(item.artRes, Modifier.size(68.dp), artUrl = item.artUrl)
            } else if (index != null) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    SidekitText("${index + 1}", style = SidekitTheme.typography.label, color = colors.surfaceA6)
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
            if (trailing != null) {
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
fun RowScope.SpotifyIconTile(
    icon: SidekitIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: SpotifyTileTone = SpotifyTileTone.Neutral,
    weight: Float = 1f
) {
    val colors = SidekitTheme.colors
    SidekitIconItem(
        icon,
        label,
        modifier.weight(weight),
        color = tone.colour(colors),
        iconSize = SidekitTheme.spacing.tileIconSize,
        onClick = onClick
    )
}

enum class SpotifyTileTone {
    Neutral,
    Play,
    On,
    Muted
    ;

    fun colour(colors: hrln.interactive.sidekit.design.theme.SidekitColors): Color = when (this) {
        Neutral -> colors.surfaceA3
        Play -> colors.emphasisA1
        On -> colors.surfaceA5
        Muted -> colors.surfaceA2
    }
}

@Composable
fun NowPlayingTile(
    current: SpotifyMediaItem?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SidekitTheme.colors
    SidekitTile(modifier, onClick = onClick, onClickLabel = "Now Playing") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (current == null) {
                Box(Modifier.size(72.dp).clip(SidekitTheme.shapes.control).background(colors.surfaceA2), contentAlignment = Alignment.Center) {
                    SidekitIconImage(SidekitIcon.MusicNote2, size = 36.dp)
                }
            } else {
                SpotifyCover(current.artRes, Modifier.size(72.dp), artUrl = current.artUrl)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SidekitText(
                    if (current == null) "NOTHING PLAYING" else "PLAYING",
                    style = SidekitTheme.typography.small,
                    color = colors.surfaceA6
                )
                SidekitText(current?.title ?: "Pick a track", style = SidekitTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

@Composable
fun LibraryTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SidekitTile(
        modifier,
        color = SidekitTheme.colors.emphasisA1,
        onClick = onClick,
        onClickLabel = "Library"
    ) {
        SidekitIconImage(
            SidekitIcon.Album,
            size = SidekitTheme.spacing.tileIconSize,
            contentDescription = "Library"
        )
    }
}

@Composable
fun ArtworkTile(@DrawableRes artRes: Int, label: String, modifier: Modifier = Modifier, artUrl: String? = null) {
    SidekitTile(modifier) {
        SpotifyCover(artRes, Modifier.fillMaxSize(), shape = SidekitTheme.shapes.item, contentDescription = label, artUrl = artUrl)
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
fun TerminalRow(label: String, secondary: String? = null, onClick: (() -> Unit)? = null) {
    SidekitItem(
        label,
        secondary = secondary,
        showLinkArrow = onClick != null,
        colour = hrln.interactive.sidekit.design.components.ItemColour.Secondary,
        onClick = onClick
    )
}

/**
 * Loads the next page when the footer enters composition. Lazy lists only compose visible rows
 * (plus a small prefetch window), so this keeps both network and rendering work proportional to
 * what the user is actually looking at.
 */
@Composable
fun PagingFooter(loading: Boolean, onLoadMore: () -> Unit) {
    LaunchedEffect(loading) {
        if (!loading) onLoadMore()
    }
    EmptyMessage(if (loading) "Loading more…" else "More", hint = null)
}

fun withNotice(notice: String?, onDismiss: () -> Unit, actions: List<SideAction>): List<SideAction> = if (notice == null) {
    actions
} else {
    listOf(
        SideAction(
            "notice",
            notice,
            tone = ActionTone.Muted,
            dense = true,
            motion = ActionMotion.Up,
            onClick = onDismiss
        )
    )
}

fun spotifyBack(label: String, onBack: () -> Unit) = listOf(SideAction.back("back", label, onBack))

/**
 * The action bar for screens that can change saved state. While an Undo offer is live it takes the
 * right slot; otherwise the bar shows the screen's normal actions and any notice.
 */
fun spotifyBar(
    state: SpotifyUiState,
    onDismissNotice: () -> Unit,
    onUndo: () -> Unit,
    continueWith: SideAction,
    extra: List<SideAction> = emptyList()
): List<SideAction> {
    val undo = state.undo
    return if (undo != null) {
        undoBar(continueWith, SideAction.undo(contentDescription = undo.contentDescription, onClick = onUndo))
    } else {
        withNotice(state.notice, onDismissNotice, listOf(continueWith) + extra)
    }
}

fun repeatLabel(repeat: RepeatMode) = repeat.label

/**
 * The seek row: tap or drag, or step with D-pad left/right. It mirrors Music's seek row so the
 * transport feels the same in both apps.
 */
@Composable
fun SpotifySeek(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val seekable = durationMs > 0
    val fraction = if (seekable) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // While the finger is down the bar follows the finger locally. The player is only asked to
    // seek once, on release: issuing a seek for every drag delta floods the native player and
    // makes the bar take seconds to settle on the final position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shownFraction = dragFraction ?: fraction
    val shownPositionMs = if (dragFraction != null) (shownFraction * durationMs).toLong() else positionMs
    val trackColor = SidekitTheme.colors.surfaceA4
    val progressColor = SidekitTheme.colors.light
    val labelStyle = SidekitTheme.typography.label
    val timeStyle = remember(labelStyle) { labelStyle.copy(fontFeatureSettings = "tnum") }
    val widest = remember(durationMs) { formatDuration(durationMs).replace(Regex("\\d"), "0") }
    val interaction = remember { MutableInteractionSource() }
    fun seekTo(target: Float) {
        if (seekable) onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
    }
    val step = if (seekable) 5_000f / durationMs else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(contentAlignment = Alignment.CenterEnd) {
            SidekitText(widest, Modifier.alpha(0f).clearAndSetSemantics {}, style = timeStyle)
            SidekitText(formatDuration(shownPositionMs), style = timeStyle)
        }
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            seekTo(fraction - step)
                            true
                        }

                        Key.DirectionRight -> {
                            seekTo(fraction + step)
                            true
                        }

                        else -> false
                    }
                }
                .indication(interaction, LocalIndication.current)
                .focusable(interactionSource = interaction)
                .pointerInput(durationMs) { detectTapGestures { offset -> seekTo(offset.x / size.width) } }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (seekable) dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onHorizontalDrag = { change, _ ->
                            if (seekable) dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val target = dragFraction
                            dragFraction = null
                            if (target != null) seekTo(target)
                        },
                        onDragCancel = { dragFraction = null }
                    )
                }
                .semantics {
                    if (seekable) {
                        contentDescription = "Seek"
                        progressBarRangeInfo = ProgressBarRangeInfo(shownFraction, 0f..1f)
                        setProgress { target ->
                            seekTo(target)
                            true
                        }
                    }
                }
                .drawBehind {
                    val h = 6.dp.toPx()
                    val y = (size.height - h) / 2
                    drawRoundRect(trackColor, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                    if (shownFraction > 0f) {
                        val filled = size.width * shownFraction
                        drawRoundRect(progressColor, Offset(0f, y), Size(filled, h), CornerRadius(h / 2))
                        drawCircle(progressColor, 9.dp.toPx(), Offset(filled, size.height / 2))
                    }
                }
        )
        SidekitText(formatDuration(durationMs), style = timeStyle)
    }
}

@Composable
fun PlayTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SidekitIconItem(
        SidekitIcon.PlayArrow,
        "Play",
        modifier,
        color = SidekitTheme.colors.emphasisA1,
        iconSize = SidekitTheme.spacing.tileIconSize,
        onClick = onClick
    )
}

@Composable
fun SpotifyLibraryRow(
    @DrawableRes artRes: Int,
    title: String,
    secondary: String,
    modifier: Modifier = Modifier,
    artUrl: String? = null,
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    SidekitTile(modifier.fillMaxWidth(), color = colors.surfaceA3, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            SpotifyCover(artRes, Modifier.size(68.dp), artUrl = artUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SidekitText(title, style = SidekitTheme.typography.bodySmall, maxLines = 1)
                SidekitText(secondary, style = SidekitTheme.typography.caption, color = colors.surfaceA6, maxLines = 1)
            }
            SidekitIconImage(SidekitIcon.ArrowRightAlt, size = 30.dp, contentDescription = null)
        }
    }
}
