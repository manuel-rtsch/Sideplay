package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

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
fun LoadingScreen(
    modifier: Modifier = Modifier,
    title: String = "Loading…",
    hint: String? = "Fetching"
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
 * The settled counterpart to [LoadingScreen]: the same record, but still, with an optional
 * retry action. Used for empty and failed library loads so those states look intentional instead
 * of like a blank list.
 *
 * The block fills whatever space it is given and centres in it. The record shrinks with the
 * available height, and disappears when there is no room for it, so the title, hint and retry
 * action always fit: on a 480 x 640 Sidephone the area under a library filter row is less than
 * half a page, and a full-size record would push the text off the bottom.
 */
@Composable
fun StatusScreen(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    artwork: Boolean = true
) {
    val colors = SidekitTheme.colors
    BoxWithConstraints(
        modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val hasAction = actionLabel != null && onAction != null
        val record = when {
            !artwork -> null

            // The retry button is an item-height target, so an error needs the space more than
            // the record does. Below a full page it gives way entirely.
            hasAction && maxHeight < 360.dp -> null

            maxHeight < 240.dp -> null

            maxHeight < 300.dp -> 64.dp

            maxHeight < 380.dp -> 80.dp

            maxHeight < 460.dp -> 104.dp

            else -> 132.dp
        }
        // Scroll only when the host gives a bounded height. A list item measures with an
        // infinite maximum height, and a scrollable column there would crash the list.
        val scrollState = rememberScrollState()
        val fit = if (maxHeight == Dp.Infinity) {
            Modifier
        } else {
            Modifier.verticalScroll(scrollState)
        }
        Column(
            Modifier.fillMaxWidth().then(fit),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (record != null) {
                VinylRecord(
                    0f,
                    Modifier
                        .size(record)
                        .semantics { contentDescription = title }
                )
                Spacer(Modifier.height(16.dp))
            }
            SidekitText(
                title,
                style = SidekitTheme.typography.bodySmall,
                color = colors.light,
                textAlign = TextAlign.Center,
                maxLines = 3
            )
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                SidekitText(
                    hint,
                    style = SidekitTheme.typography.caption,
                    color = colors.surfaceA6,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                CenteredLineButton(actionLabel, onAction)
            }
        }
    }
}

/**
 * Sidekit's line button is full-width but left-aligns its label. A short fallback action such as
 * "Try again" reads as stranded in that width, so this keeps the full-width target and centres
 * the icon and label instead. The SDK has no alignment parameter for its line button.
 */
@Composable
private fun CenteredLineButton(label: String, onClick: () -> Unit) {
    SidekitTile(
        modifier = Modifier
            .fillMaxWidth()
            .height(SidekitTheme.spacing.compactRowHeight),
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
