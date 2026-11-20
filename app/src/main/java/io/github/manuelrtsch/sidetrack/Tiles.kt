package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

@Composable
fun RowScope.IconTile(
    icon: SidekitIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: TileTone = TileTone.Neutral,
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
fun RowScope.RepeatTile(
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
        color = (if (active) TileTone.On else TileTone.Neutral).colour(colors),
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

/**
 * A deliberate text tile for podcast transport (±30 s). It avoids inventing a new icon while
 * keeping the five-tile transport row intact.
 */
@Composable
fun RowScope.SkipTile(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 0.85f
) {
    SidekitTile(
        modifier = modifier.weight(weight),
        color = TileTone.Neutral.colour(SidekitTheme.colors),
        onClick = onClick,
        onClickLabel = contentDescription
    ) {
        SidekitText(
            label,
            style = SidekitTheme.typography.label,
            color = SidekitTheme.colors.light
        )
    }
}

fun repeatLabel(repeat: RepeatMode) = repeat.label

@Composable
fun PlayTile(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playLabel: String = "Play",
    pauseLabel: String = "Pause"
) {
    SidekitIconItem(
        if (playing) SidekitIcon.Pause else SidekitIcon.PlayArrow,
        if (playing) pauseLabel else playLabel,
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
    modifier: Modifier = Modifier,
    playLabel: String = "Play",
    pauseLabel: String = "Pause"
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
        modifier = modifier,
        playLabel = playLabel,
        pauseLabel = pauseLabel
    )
}
