package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.GUTTER_ANIM_MS
import io.github.manuelrtsch.sidetrack.ui.GUTTER_DELAY_MS
import io.github.manuelrtsch.sidetrack.ui.GUTTER_SLIDE
import io.github.manuelrtsch.sidetrack.ui.GutterGap
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.SidekitSwipeRow
import io.github.manuelrtsch.sidetrack.ui.SwipeRevealGroup
import io.github.manuelrtsch.sidetrack.ui.settle

/**
 * The row whose actions are showing in a track list.
 *
 * A row that only offers "tap to play" forces the user to start a song to like it or to reach its
 * artist, which interrupts whatever is playing. The gutter closes that gap without a second
 * screen: a focused row reveals a two-square gutter (Save, Artist), and [TrackRows.options] keeps
 * everything else one Options press away.
 */
@Stable
class TrackRowFocusState : RowGutterState() {
    /** The track whose gutter is open, so its remaining actions can appear in Options. */
    var openTrack: SpotifyTrack? by mutableStateOf(null)
        internal set

    internal fun attach(track: SpotifyTrack) {
        if (openTrack?.id != track.id) openTrack = track
    }
}

/**
 * A track row's owner: the shared gutter state, the saved set and every action the row can run.
 *
 * Built inline on each composition so a save, an unlike or a failed write is visible in the same
 * frame the state changes. [options] carries the actions that would crowd the gutter: the row is
 * 480 dp wide, and two squares are already a fifth of it.
 */
class TrackRows(
    val focus: TrackRowFocusState,
    val savedIds: Set<String>,
    val onSave: (SpotifyTrack) -> Unit,
    val onArtist: (SpotifyTrack) -> Unit,
    val onAlbum: (SpotifyTrack) -> Unit,
    val onEnsureSaved: (SpotifyTrack) -> Unit,
    val onQueue: (SpotifyTrack) -> Unit
) {
    fun saved(track: SpotifyTrack): Boolean = track.id in savedIds

    /** The open row's remaining actions. */
    fun options(currentAlbumId: String? = null): List<SideAction> {
        val track = focus.openTrack ?: return emptyList()
        // The remembered track outlives the gutter that set it; the menu must not.
        if (focus.openKey == null && focus.touchedKey == null) return emptyList()
        val albumId = track.albumId.spotifyIdOrNull() ?: return emptyList()
        // An album page does not need a row action that reopens itself.
        if (albumId == currentAlbumId) return emptyList()
        return listOf(
            SideAction("rowAlbum", "Open album", SidekitIcon.Album, onClick = { onAlbum(track) })
        )
    }
}

/** Remembers the list's gutter state and settles it once focus stops moving. */
@Composable
fun rememberTrackRows(
    state: SpotifyUiState,
    onSave: (SpotifyTrack) -> Unit,
    onArtist: (SpotifyTrack) -> Unit,
    onAlbum: (SpotifyTrack) -> Unit,
    onEnsureSaved: (SpotifyTrack) -> Unit,
    onQueue: (SpotifyTrack) -> Unit
): TrackRows {
    val focus = remember { TrackRowFocusState() }
    focus.settle()
    return TrackRows(focus, state.savedTrackIds, onSave, onArtist, onAlbum, onEnsureSaved, onQueue)
}

/**
 * A track row with the app's whole action set in reach without starting the song: touch swipe
 * reveals Add to queue, and a focused row opens a two-square gutter with Save and Artist.
 *
 * Episodes keep the plain swipe row: they have no saved-track endpoint, and a show is reached
 * from Now Playing.
 */
@Composable
fun TrackRow(
    key: Any,
    track: SpotifyTrack,
    rows: TrackRows,
    swipes: SwipeRevealGroup,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArt: Boolean = true,
    secondary: String = track.artist,
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    val square = SidekitTheme.spacing.itemHeight
    val hasArtist = track.artistId.spotifyIdOrNull() != null
    val squares = if (hasArtist) 2 else 1
    // A swiped-open row already owns the right edge: the queue button and the gutter must never
    // share it.
    val open = rows.focus.isOpen(key) && swipes.openKey != key
    val targetGutter = if (open) square * squares + GutterGap * (squares - 1) else 0.dp
    // The gutter opens and closes rather than snapping: the row narrows as the squares slide in,
    // and widens again as they leave.
    val gutter by animateDpAsState(targetGutter, tween(GUTTER_ANIM_MS), label = "trackGutter")
    val gutterAlpha by animateFloatAsState(
        if (targetGutter > 0.dp) 1f else 0f,
        tween(GUTTER_ANIM_MS),
        label = "trackGutterAlpha"
    )
    val gutterPx = with(LocalDensity.current) { gutter.toPx() }
    val saved = rows.saved(track)
    // A swipe owns the right edge: drop a long-press reveal rather than fight it for the corner.
    LaunchedEffect(swipes.openKey == key) {
        if (swipes.openKey == key) rows.focus.touchDismissed(key)
    }
    // The saved state of a row that never played is unknown: the gutter is the moment to ask.
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        rows.focus.attach(track)
        rows.onEnsureSaved(track)
    }
    // A square holds the gutter open while the keypad is inside it. It must not report the
    // "not focused" event Compose dispatches when it is first attached: that arrives on the same
    // frame the gutter opens, and treating it as a loss of focus closed the gutter immediately.
    // The row's own handler already covers the row-to-square handover.
    var squareHadFocus by remember { mutableStateOf(false) }
    val squareFocus = Modifier.onFocusChanged { state ->
        when {
            state.isFocused -> {
                squareHadFocus = true
                rows.focus.rowFocused(key)
            }

            squareHadFocus -> rows.focus.rowUnfocused(key)
        }
    }
    Box(modifier) {
        SidekitSwipeRow(
            key = key,
            group = swipes,
            // A focused row slides left to leave the gutter free on its right; its width stays
            // the same, as with a touch swipe.
            modifier = Modifier.graphicsLayer { translationX = -gutterPx },
            action = queueAction(track, rows.onQueue),
            // The gutter already gives a focused row its actions; a second focus-revealed button
            // would crowd the same edge.
            revealOnFocus = false,
            // Touch has no focus border to follow, so a long press asks for the squares.
            onLongPress = { rows.focus.reveal(key) }
        ) {
            SpotifyMediaRow(
                track,
                Modifier.onFocusChanged { state ->
                    if (state.isFocused) rows.focus.rowFocused(key) else rows.focus.rowUnfocused(key)
                },
                index = index,
                showArt = showArt,
                secondary = secondary,
                // The squares get the right edge while they are visible.
                trailing = if (gutter > 0.dp) null else formatDuration(track.durationMs),
                onClick = {
                    rows.focus.touchDismissed(key)
                    onClick()
                }
            )
        }
        // While a long press holds the gutter open, a tap on the row closes it rather than
        // starting the track — the same contract a swiped-open row already has.
        if (rows.focus.touchedKey == key && gutter > 0.dp) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(key) {
                        detectTapGestures { rows.focus.touchDismissed(key) }
                    }
            )
        }
        // Composed only while the gutter has width, so a faded-out square can never be tapped.
        if (gutter > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        alpha = gutterAlpha
                        translationX = (1f - gutterAlpha) * GUTTER_SLIDE.toPx()
                    }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(GutterGap)) {
                    RowActionSquare(
                        color = colors.successA1,
                        icon = if (saved) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                        label = if (saved) {
                            "Remove ${track.title} from Liked Songs"
                        } else {
                            "Save ${track.title} to Liked Songs"
                        },
                        modifier = squareFocus,
                        onClick = {
                            rows.focus.touchDismissed(key)
                            rows.onSave(track)
                        }
                    )
                    if (hasArtist) {
                        RowActionSquare(
                            color = colors.surfaceA4,
                            icon = SidekitIcon.Artist,
                            label = "Open ${track.artist}",
                            modifier = squareFocus,
                            onClick = {
                                rows.focus.touchDismissed(key)
                                rows.onArtist(track)
                            }
                        )
                    }
                }
            }
        }
    }
}
