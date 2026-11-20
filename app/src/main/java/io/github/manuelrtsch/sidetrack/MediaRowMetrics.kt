package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * Whether track, album, playlist and the other media rows use the denser layout.
 *
 * Provided once above the navigation graph so a list does not re-read the preference per row.
 * Off by default: the four-row layout is the design the app ships with, and the dense one buys a
 * fifth row at the cost of smaller artwork and type.
 */
val LocalCompactRows = compositionLocalOf { false }

/** The dense layout shrinks a row by about a fifth: 104 dp rows become 82 dp. */
private val CompactRowHeight = 82.dp
private val CompactArtworkSize = 64.dp
private val CompactHorizontalPadding = 12.dp
private val CompactContentGap = 10.dp
private val CompactTextGap = 1.dp
private val CompactTrackNumberWidth = 36.dp

/**
 * The measured sizes of a media row. Both branches name every value, so a row reads one object
 * instead of deciding each dimension for itself and drifting from the list's pitch.
 */
@Immutable
data class MediaRowMetrics(
    val height: Dp,
    val artworkSize: Dp,
    val horizontalPadding: Dp,
    val contentGap: Dp,
    val textGap: Dp,
    val trackNumberWidth: Dp,
    val titleStyle: TextStyle,
    val secondaryStyle: TextStyle
)

/** The metrics for the rows currently on screen, following the compact-rows setting. */
@Composable
fun rememberMediaRowMetrics(): MediaRowMetrics {
    val compact = LocalCompactRows.current
    val typography = SidekitTheme.typography
    val spacing = SidekitTheme.spacing
    return remember(compact, typography, spacing) {
        if (compact) {
            MediaRowMetrics(
                height = CompactRowHeight,
                artworkSize = CompactArtworkSize,
                horizontalPadding = CompactHorizontalPadding,
                contentGap = CompactContentGap,
                textGap = CompactTextGap,
                trackNumberWidth = CompactTrackNumberWidth,
                // 32 -> 27 and 28 -> 24: the two-line block still reads as a title over a
                // subtitle inside the shorter row without dropping to the tiny label size.
                titleStyle = typography.bodySmall.copy(fontSize = 27.sp, lineHeight = 33.sp),
                secondaryStyle = typography.small
            )
        } else {
            MediaRowMetrics(
                height = spacing.itemHeight,
                artworkSize = ListArtworkSize,
                horizontalPadding = 15.dp,
                contentGap = 13.dp,
                textGap = 2.dp,
                trackNumberWidth = 44.dp,
                titleStyle = typography.bodySmall,
                secondaryStyle = typography.caption
            )
        }
    }
}

/** The vertical distance between two rows, used wherever layout is measured in row pitches. */
@Composable
fun rememberMediaRowPitch(): Dp = rememberMediaRowMetrics().height + SidekitTheme.spacing.itemOuterGap

/** Reads the saved compact-rows choice and observes later changes to it. */
@Composable
fun rememberCompactRows(): Boolean {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SidetrackLocalSettings(context) }
    var compact by remember(settings) { mutableStateOf(settings.compactRows) }
    DisposableEffect(settings) {
        val stop = settings.observeCompactRows { compact = settings.compactRows }
        onDispose(stop)
    }
    return compact
}
