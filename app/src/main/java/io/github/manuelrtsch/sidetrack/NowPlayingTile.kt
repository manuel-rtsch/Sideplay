package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.playback.tileText
import kotlinx.coroutines.flow.StateFlow

@Composable
fun NowPlayingTile(
    current: MediaItem?,
    isPlaying: Boolean,
    sleepTimer: StateFlow<SleepTimerState>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SidekitTheme.colors
    val timer by sleepTimer.collectAsStateWithLifecycle()
    val timerLabel = timer.tileText()
    val countdown = timer as? SleepTimerState.Countdown
    // One overline slot, ranked: the sleep timer's outcome explains the pause, a frozen or live
    // timer replaces the playing label, and the icon beside the text carries playback state so the
    // text itself can stay inside the tile's one-line budget.
    val paused = current != null && !isPlaying
    val overlineIcon: SidekitIcon?
    val overlineLabel: String
    when {
        current == null -> {
            overlineIcon = null
            overlineLabel = "NOTHING PLAYING"
        }

        timer == SleepTimerState.Finished && timerLabel != null -> {
            overlineIcon = SidekitIcon.Bedtime
            overlineLabel = timerLabel
        }

        paused && countdown != null -> {
            // The pause icon already says "PAUSED", so the text is the timer itself: at 11
            // characters it sits well inside the slot that "NOTHING PLAYING" sets.
            overlineIcon = SidekitIcon.Pause
            overlineLabel = "${countdown.remainingMinutes} MIN LEFT"
        }

        paused && timerLabel != null -> {
            // Same label as a playing end-of-track timer; only the icon differs.
            overlineIcon = SidekitIcon.Pause
            overlineLabel = timerLabel
        }

        paused -> {
            overlineIcon = SidekitIcon.Pause
            overlineLabel = "PAUSED"
        }

        timerLabel != null -> {
            overlineIcon = SidekitIcon.Bedtime
            overlineLabel = timerLabel
        }

        else -> {
            overlineIcon = null
            overlineLabel = "NOW PLAYING"
        }
    }
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
                Cover(current.artRes, Modifier.size(ListArtworkSize), shape = SidekitTheme.shapes.artwork, artUrl = current.artUrl, requestSize = ListArtworkSize)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // The overline carries the state, so the title keeps the full width and never
                // competes with a trailing label.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    overlineIcon?.let {
                        SidekitIconImage(it, size = 24.dp, tint = colors.surfaceA6)
                    }
                    SidekitText(
                        overlineLabel,
                        style = SidekitTheme.typography.small,
                        color = colors.surfaceA6,
                        maxLines = 1
                    )
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
 * an auto-dismiss action before opening options.
 */
fun nowPlayingNoticeActions(
    item: MediaItem,
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
private fun NowPlayingNoticeArt(item: MediaItem) {
    Box(Modifier.size(48.dp)) {
        Cover(
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
