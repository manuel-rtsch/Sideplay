package io.github.manuelrtsch.sideplay

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitCompactItem
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.media.RemoteArtworkImage
import io.github.manuelrtsch.sideplay.playback.SleepTimerState
import io.github.manuelrtsch.sideplay.playback.formatDuration
import io.github.manuelrtsch.sideplay.playback.tileText
import io.github.manuelrtsch.sideplay.ui.LocalSwipeActionRevealed
import kotlinx.coroutines.flow.StateFlow

/**
 * Artwork shown beside text inside a 104 dp list tile. 80 dp leaves a 12 dp margin on every side
 * and gives the cover a little more presence than the two-line text block next to it.
 */
private val ListArtworkSize = 80.dp

/**
 * Fixed slot for a track's position in a collection. Wide enough for three digits at the small
 * type size, so row 100+ does not ellipsize while every row keeps the same text alignment.
 */
private val TrackNumberWidth = 44.dp

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
                SpotifyCover(item.artRes, Modifier.size(ListArtworkSize), shape = SidekitTheme.shapes.artwork, artUrl = item.artUrl)
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

/**
 * Repeat is a three-state cycle (off, context, track), so the two on-states need distinct
 * glyphs: a tone change alone cannot say whether the queue repeats everything or only the
 * current track. The plain repeat glyph in the passive tone is off, plain repeat is the context,
 * and repeat-one is the current track.
 */
@Composable
fun RowScope.SpotifyRepeatTile(
    repeat: RepeatMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 0.85f
) {
    val colors = SidekitTheme.colors
    val active = repeat != RepeatMode.Off
    val icon = if (repeat == RepeatMode.Track) R.drawable.ic_repeat_one else R.drawable.ic_repeat
    SidekitTile(
        modifier = modifier.weight(weight),
        color = (if (active) SpotifyTileTone.On else SpotifyTileTone.Neutral).colour(colors),
        onClick = onClick,
        onClickLabel = repeat.label
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = repeat.label,
            modifier = Modifier.size(SidekitTheme.spacing.tileIconSize),
            colorFilter = ColorFilter.tint(colors.light)
        )
    }
}

enum class SpotifyTileTone {
    Neutral,
    Play,
    Library,
    On,
    Muted
    ;

    fun colour(colors: hrln.interactive.sidekit.design.theme.SidekitColors): Color = when (this) {
        Neutral -> colors.surfaceA3
        Play -> colors.emphasisA1
        Library -> colors.successA1
        On -> colors.surfaceA5
        Muted -> colors.surfaceA2
    }
}

@Composable
fun NowPlayingTile(
    current: SpotifyMediaItem?,
    sleepTimer: StateFlow<SleepTimerState>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SidekitTheme.colors
    val timer by sleepTimer.collectAsStateWithLifecycle()
    val timerLabel = timer.tileText()
    SidekitTile(modifier, onClick = onClick, onClickLabel = "Now playing") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (current == null) {
                Box(Modifier.size(ListArtworkSize).clip(SidekitTheme.shapes.artwork).background(colors.surfaceA2), contentAlignment = Alignment.Center) {
                    SidekitIconImage(SidekitIcon.MusicNote2, size = 36.dp)
                }
            } else {
                SpotifyCover(current.artRes, Modifier.size(ListArtworkSize), shape = SidekitTheme.shapes.artwork, artUrl = current.artUrl)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // With a live timer the overline becomes the indicator, so the title keeps the
                // full width and never competes with a trailing label.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (timerLabel != null) {
                        SidekitIconImage(
                            SidekitIcon.Bedtime,
                            size = 24.dp,
                            tint = colors.surfaceA6
                        )
                        SidekitText(
                            timerLabel,
                            style = SidekitTheme.typography.small,
                            color = colors.surfaceA6,
                            maxLines = 1
                        )
                    } else {
                        SidekitText(
                            if (current == null) "NOTHING PLAYING" else "NOW PLAYING",
                            style = SidekitTheme.typography.small,
                            color = colors.surfaceA6
                        )
                    }
                }
                SidekitText(
                    current?.title ?: "Pick a track",
                    style = SidekitTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * The transient "now playing" confirmation. It replaces the action bar for a few seconds after a
 * new item starts, then the normal actions return. Tapping it opens Now Playing; the square X
 * dismisses it immediately.
 *
 * The Options key is deliberately not a dismiss: Sidekit's `ChromeState.toggleOptions()` owns that
 * key and opens the screen's options instead. A later Sidekit improvement can make Options dismiss
 * an auto-dismiss action before opening options; see docs/SIDEPLAY.md.
 */
fun nowPlayingNoticeActions(
    item: SpotifyMediaItem,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
): List<SideAction> = listOf(
    SideAction(
        key = "nowPlayingNotice:${item.id}",
        label = item.title,
        icon = SidekitIcon.ArrowRightAlt,
        iconAtEnd = true,
        tone = ActionTone.Primary,
        motion = ActionMotion.Up,
        contentDescription = "Playing ${item.title}. Open Now Playing",
        artKey = item.id,
        art = { NowPlayingNoticeArt(item) },
        onClick = onOpen
    ),
    SideAction.square(
        key = "nowPlayingNoticeDismiss",
        icon = SidekitIcon.Close,
        contentDescription = "Dismiss now playing notice",
        onClick = onDismiss,
        tone = ActionTone.Muted,
        motion = ActionMotion.Up
    )
)

@Composable
private fun NowPlayingNoticeArt(item: SpotifyMediaItem) {
    Box(Modifier.size(48.dp)) {
        SpotifyCover(
            item.artRes,
            Modifier.fillMaxSize(),
            shape = SidekitTheme.shapes.artwork,
            artUrl = item.artUrl
        )
        PlayingBars(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .size(20.dp)
        )
    }
}

/** Three small bars over the artwork, so the confirmation reads as playing rather than paused. */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "nowPlayingBars")
    val bars = listOf(0.45f, 1f, 0.65f).mapIndexed { index, peak ->
        transition.animateFloat(
            initialValue = peak * 0.35f,
            targetValue = peak,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 360 + index * 90, easing = LinearEasing),
                repeatMode = AnimationRepeatMode.Reverse
            ),
            label = "bar$index"
        )
    }
    Canvas(modifier) {
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = size.minDimension / 2f)
        val barWidth = size.width * 0.14f
        val gap = size.width * 0.08f
        val total = barWidth * 3 + gap * 2
        val startX = (size.width - total) / 2f
        val bottom = size.height * 0.72f
        bars.forEachIndexed { index, bar ->
            val height = size.height * 0.5f * bar.value
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(startX + index * (barWidth + gap), bottom - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

@Composable
fun LibraryTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SidekitTile(
        modifier,
        color = SidekitTheme.colors.successA1,
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
fun ArtworkTile(
    @DrawableRes artRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    artUrl: String? = null,
    onClick: (() -> Unit)? = null
) {
    SidekitTile(modifier, onClick = onClick, onClickLabel = if (onClick != null) "View artwork" else null) {
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

/**
 * The vinyl record used by the loading and status screens. It is drawn once and can be rotated by
 * the caller: spinning means work is in flight, still means the screen has settled on a state.
 */
@Composable
private fun VinylRecord(angle: Float, modifier: Modifier = Modifier) {
    val colors = SidekitTheme.colors
    Canvas(modifier.rotate(angle)) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(colors.surfaceA2, radius, centre)
        drawCircle(colors.surfaceA4, radius, centre, style = Stroke(width = 1.dp.toPx()))
        for (groove in 1..5) {
            drawCircle(
                colors.surfaceA3,
                radius * (0.52f + groove * 0.075f),
                centre,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        drawCircle(colors.primaryA1, radius * 0.34f, centre)
        drawCircle(colors.primaryA3, radius * 0.34f, centre, style = Stroke(width = 1.dp.toPx()))
        drawCircle(colors.surfaceA1, radius * 0.055f, centre)
        drawArc(
            color = colors.light.copy(alpha = 0.18f),
            startAngle = -60f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(centre.x - radius * 0.86f, centre.y - radius * 0.86f),
            size = Size(radius * 1.72f, radius * 1.72f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * A centred vinyl record for the first load of an artist or collection. The record turns while the
 * catalog request is in flight, so the screen reads as activity rather than a stalled empty state.
 */
@Composable
fun SpotifyLoadingScreen(
    modifier: Modifier = Modifier,
    title: String = "Loading…",
    hint: String? = "Fetching from Spotify"
) {
    val colors = SidekitTheme.colors
    val transition = rememberInfiniteTransition(label = "record")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = AnimationRepeatMode.Restart
        ),
        label = "recordAngle"
    )
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VinylRecord(
            angle,
            Modifier
                .size(132.dp)
                .semantics { contentDescription = title }
        )
        Spacer(Modifier.height(20.dp))
        SidekitText(title, style = SidekitTheme.typography.bodySmall, color = colors.light)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            SidekitText(
                hint,
                style = SidekitTheme.typography.caption,
                color = colors.surfaceA6,
                maxLines = 1
            )
        }
    }
}

/**
 * The settled counterpart to [SpotifyLoadingScreen]: the same record, but still, with an optional
 * retry action. Used for empty and failed library loads so those states look intentional instead
 * of like a blank list.
 */
@Composable
fun SpotifyStatusScreen(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = SidekitTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VinylRecord(
            0f,
            Modifier
                .size(132.dp)
                .semantics { contentDescription = title }
        )
        Spacer(Modifier.height(20.dp))
        SidekitText(
            title,
            style = SidekitTheme.typography.bodySmall,
            color = colors.light,
            textAlign = TextAlign.Center
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            SidekitText(
                hint,
                style = SidekitTheme.typography.caption,
                color = colors.surfaceA6,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            SpotifyCenteredLineButton(actionLabel, onAction)
        }
    }
}

/**
 * Sidekit's line button is full-width but left-aligns its label. A short fallback action such as
 * "Try again" reads as stranded in that width, so this keeps the full-width target and centres
 * the icon and label instead. The SDK has no alignment parameter for its line button.
 */
@Composable
private fun SpotifyCenteredLineButton(label: String, onClick: () -> Unit) {
    SidekitTile(
        modifier = Modifier.fillMaxWidth(),
        color = SidekitTheme.colors.surfaceA3,
        onClick = onClick,
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SidekitTheme.spacing.itemPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.itemContentGap)
        ) {
            SidekitIconImage(SidekitIcon.Undo)
            SidekitText(label)
        }
    }
}

/**
 * A compact navigation row for list-level actions such as "See more" and "Search in".
 * The SDK renders the short row, the success fill and the type scale.
 */
@Composable
fun TerminalRow(
    label: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    onClick: (() -> Unit)? = null
) {
    SidekitCompactItem(
        title = label,
        modifier = modifier,
        secondary = secondary,
        colour = ItemColour.Success,
        showLinkArrow = secondary == null && onClick != null,
        onClick = onClick
    )
}

/**
 * Loads the next page when the footer enters composition. Lazy lists only compose visible rows
 * (plus a small prefetch window), so this keeps both network and rendering work proportional to
 * what the user is actually looking at.
 */
@Composable
fun PagingFooter(loading: Boolean, onLoadMore: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(loading) {
        if (!loading) onLoadMore()
    }
    EmptyMessage(if (loading) "Loading more…" else "More", modifier, hint = null)
}

/** A shallow terminal row that loads the next page only when the user presses it. */
@Composable
fun SeeMoreRow(label: String, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TerminalRow(
        label,
        modifier = modifier,
        secondary = if (loading) "Loading…" else null,
        onClick = if (loading) null else onClick
    )
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

/**
 * The screen's action bar, with the transient now-playing confirmation taking precedence over the
 * normal actions. A real notice (an error or confirmation) wins over the now-playing confirmation.
 */
fun spotifyActions(
    state: SpotifyUiState,
    onDismissNotice: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    actions: List<SideAction>,
    showNowPlayingNotice: Boolean = true
): List<SideAction> = when {
    state.notice != null -> withNotice(state.notice, onDismissNotice, actions)

    showNowPlayingNotice && state.nowPlayingNotice != null ->
        nowPlayingNoticeActions(state.nowPlayingNotice, onOpenNowPlaying, onDismissNowPlaying)

    else -> actions
}

fun spotifyBack(label: String, onBack: () -> Unit) = listOf(SideAction.back("back", label, onBack))

/** The action bar for screens that can change saved state, plus any important notice. */
fun spotifyBar(
    state: SpotifyUiState,
    onDismissNotice: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    continueWith: SideAction,
    extra: List<SideAction> = emptyList(),
    showNowPlayingNotice: Boolean = true
): List<SideAction> = spotifyActions(
    state,
    onDismissNotice,
    onOpenNowPlaying,
    onDismissNowPlaying,
    listOf(continueWith) + extra,
    showNowPlayingNotice
)

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
    // Clicking or dragging the bar puts it in slide mode: left/right then nudge the track instead
    // of moving focus. Losing focus leaves the mode.
    var sliding by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val shownFraction = dragFraction ?: fraction
    val shownPositionMs = if (dragFraction != null) (shownFraction * durationMs).toLong() else positionMs
    // The position flow ticks at 10 Hz. Animating between those samples keeps the fill and knob
    // moving continuously instead of stepping a couple of pixels at a time. Dragging bypasses
    // the animation so the bar still follows the finger exactly.
    val animatedFraction by animateFloatAsState(
        targetValue = shownFraction,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "seekFraction"
    )
    val barFraction = if (dragFraction != null) shownFraction else animatedFraction
    val trackColor = SidekitTheme.colors.surfaceA4
    val progressColor = SidekitTheme.colors.light
    // The knob turns accent-coloured while the bar owns the keypad, so slide mode is visible.
    val knobColor = if (sliding) SidekitTheme.colors.emphasisA1 else progressColor
    // Slide mode gives the bar a taller focus strip and a little side inset. The extra height
    // comes out of the artwork, which is fine while the user is scrubbing.
    val blockHeight = if (sliding) 64.dp else 48.dp
    val stripHeight = if (sliding) 40.dp else 24.dp
    val barInset = if (sliding) 16.dp else 0.dp
    // The times sit above a full-width track inside the same touch target. Keeping them out of
    // the bar's row means the track can span the whole body without making the screen taller and
    // shrinking the artwork. Tabular digits keep the counting label from jittering.
    val labelStyle = SidekitTheme.typography.caption
    val timeStyle = remember(labelStyle) { labelStyle.copy(fontFeatureSettings = "tnum") }
    val interaction = remember { MutableInteractionSource() }
    fun seekTo(target: Float) {
        if (seekable) onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
    }

    // Slide mode always steps 10 s. The first key press enters the mode, so the step is computed
    // here rather than from the `sliding` value captured before the event.
    fun nudge(direction: Float) {
        sliding = true
        if (seekable) seekTo(fraction + direction * (10_000f / durationMs))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(blockHeight)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    focusRequester.requestFocus()
                    sliding = true
                    seekTo(offset.x / size.width)
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        focusRequester.requestFocus()
                        sliding = true
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
    ) {
        Row(
            // Lift the labels out of the block's top edge so they sit close to the artist line
            // while the bar keeps its distance below them. The extra 2 dp keeps the times from
            // crowding the track; the block height is unchanged, so the artwork does not shrink.
            Modifier
                .fillMaxWidth()
                .offset(y = if (sliding) (-8).dp else (-4).dp)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SidekitText(formatDuration(shownPositionMs), style = timeStyle)
            SidekitText(formatDuration(durationMs), style = timeStyle)
        }
        // The focus ring belongs to the bar, not the labels above it. Keeping the focusable node
        // on this shorter bottom strip makes the ring hug the track and knob.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(stripHeight)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            nudge(-1f)
                            true
                        }

                        Key.DirectionRight -> {
                            nudge(1f)
                            true
                        }

                        else -> false
                    }
                }
                .indication(interaction, LocalIndication.current)
                .focusRequester(focusRequester)
                .onFocusChanged { sliding = it.isFocused }
                .focusable(interactionSource = interaction)
                .semantics {
                    if (seekable) {
                        contentDescription = "Seek"
                        stateDescription = "${formatDuration(shownPositionMs)} of ${formatDuration(durationMs)}"
                        progressBarRangeInfo = ProgressBarRangeInfo(shownFraction, 0f..1f)
                        setProgress { target ->
                            seekTo(target)
                            true
                        }
                    }
                }
                .drawBehind {
                    val h = 6.dp.toPx()
                    val knob = 9.dp.toPx()
                    // The focus ring stays full width; only the track and knob pull in while
                    // focused so they do not collide with the ring.
                    val inset = barInset.toPx()
                    val trackWidth = size.width - inset * 2
                    // Centre the track and knob in the focus strip so the ring has equal air
                    // above and below them.
                    val y = (size.height - h) / 2
                    drawRoundRect(trackColor, Offset(inset, y), Size(trackWidth, h), CornerRadius(h / 2))
                    if (barFraction > 0f) {
                        val filled = inset + trackWidth * barFraction
                        drawRoundRect(
                            progressColor,
                            Offset(inset, y),
                            Size(trackWidth * barFraction, h),
                            CornerRadius(h / 2)
                        )
                        drawCircle(knobColor, knob, Offset(filled, y + h / 2))
                    }
                }
        )
    }
}

@Composable
fun PlayTile(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SidekitIconItem(
        if (playing) SidekitIcon.Pause else SidekitIcon.PlayArrow,
        if (playing) "Pause" else "Play",
        modifier,
        color = SidekitTheme.colors.emphasisA1,
        iconSize = SidekitTheme.spacing.tileIconSize,
        onClick = onClick
    )
}

/**
 * A page-local play control. It only becomes a pause button after this page started playback,
 * and only while the current item still belongs to the page's context, so it can never pause
 * unrelated content.
 */
@Composable
fun ContextPlayTile(
    contextIds: Set<String>,
    currentId: String?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    var startedHere by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    val controlsHere = startedHere && currentId in contextIds
    LaunchedEffect(currentId) {
        when {
            currentId == null -> Unit
            pendingStart && currentId in contextIds -> pendingStart = false
            !pendingStart && currentId !in contextIds -> startedHere = false
        }
    }
    PlayTile(
        playing = controlsHere && isPlaying,
        onClick = {
            if (controlsHere) {
                onPlayPause()
            } else {
                pendingStart = true
                startedHere = true
                onPlay()
            }
        },
        modifier = modifier
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
            SpotifyCover(artRes, Modifier.size(ListArtworkSize), shape = SidekitTheme.shapes.artwork, artUrl = artUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SidekitText(title, style = SidekitTheme.typography.bodySmall, maxLines = 1)
                SidekitText(secondary, style = SidekitTheme.typography.caption, color = colors.surfaceA6, maxLines = 1)
            }
            SidekitIconImage(SidekitIcon.ArrowRightAlt, size = 30.dp, contentDescription = null)
        }
    }
}
