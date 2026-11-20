package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.playback.statusDescription
import io.github.manuelrtsch.sidetrack.playback.statusText
import kotlinx.coroutines.flow.StateFlow

/**
 * The seek bar on its own, so only it recomposes when the playback position ticks. Reading the
 * position inside this small composable keeps the 10x/s position updates away from the rest of
 * the Now Playing layout (artwork, transport, action bar).
 */
@Composable
internal fun NowPlayingSeek(
    playbackPositionMs: StateFlow<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    buffering: Boolean = false
) {
    // The state object, not its value: the value ticks five times a second and [Seek] reads it in
    // its draw lambda, so collecting it here as a value would recompose this row on every tick.
    Seek(playbackPositionMs.collectAsStateWithLifecycle(), durationMs, onSeek, modifier, buffering)
}

@Composable
fun NowPlayingScreen(
    playback: PlaybackUiState,
    saved: SavedUiState,
    chrome: ChromeUiState,
    playbackPositionMs: StateFlow<Long>,
    sleepTimer: StateFlow<SleepTimerState>,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSave: () -> Unit,
    onOpenQueue: () -> Unit,
    onRadio: () -> Unit,
    onAlbum: () -> Unit,
    onShow: () -> Unit,
    onArtist: () -> Unit,
    onSleepTimer: () -> Unit,
    onEpisodeNotes: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val current = playback.current
    val savedCurrent = current?.let { it.key.encode() in saved.savedTrackIds } ?: false
    val backdrop = rememberArtworkBackdropSetting()
    val backdropped = current is Track && backdrop.enabled
    val timer by sleepTimer.collectAsStateWithLifecycle()
    // Episodes have no saved-track endpoint, so Save is a track-only action.
    val save = remember(current?.id, savedCurrent) {
        (current as? Track)?.let {
            SideAction.square(
                "save",
                if (savedCurrent) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                if (savedCurrent) "Saved" else "Save",
                onSave,
                tone = if (savedCurrent) ActionTone.Secondary else ActionTone.Muted,
                motion = ActionMotion.Up
            )
        }
    }
    val back = remember { sidetrackBack("Back", onBack).first() }
    val actions = sidetrackBar(
        chrome,
        onDismissNotice,
        onOpenNowPlaying = {},
        onDismissNowPlaying = {},
        continueWith = back,
        extra = listOfNotNull(save),
        showNowPlayingNotice = false
    )
    val options = if (current == null) {
        emptyList()
    } else {
        buildList {
            add(SideAction.square("queue", SidekitIcon.QueueMusic, "Open queue", onOpenQueue))
            // Three options at most: the bar is icon-only and a fourth square crowds it. Song
            // radio stays in the middle in both layouts; the backdrop layout opens the album by
            // tapping the artwork, so it does not need a square of its own. The art backdrop
            // toggle itself lives in Settings.
            when (current) {
                is Track ->
                    add(SideAction.square("songRadio", SidekitIcon.Explore, "Song radio", onRadio))

                is Episode ->
                    add(SideAction.square("notes", SidekitIcon.Docs, "Episode notes", onEpisodeNotes))
            }
            add(
                SideAction.square(
                    "sleepTimer",
                    SidekitIcon.Bedtime,
                    if (timer.active) "Sleep timer on" else "Sleep timer",
                    onSleepTimer
                )
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (backdropped && current is Track) {
            // The artwork is the screen: behind the scaffold, so it can run under the chrome,
            // which the canvas paints after the screens. The body has no cover tile of its own.
            ArtworkBackdrop(current, onOpenAlbum = onAlbum, modifier = Modifier.matchParentSize())
            // Full-bleed transport scrim, anchored to the real chrome rather than a fraction of
            // the screen: solid black from the bottom edge to the action bar's top, then a fade
            // from black there to transparent at the top of the five-tile transport row, where
            // the backdrop starts. The body's transport row is the last thing in the content, so
            // its top is the content bottom less one item height. Measuring here instead of on the
            // whole screen keeps the scaffold out of this subcomposition.
            BoxWithConstraints(Modifier.matchParentSize()) {
                val spacing = SidekitTheme.spacing
                val actionBarTop = maxHeight - spacing.pagePadding - spacing.actionBarHeight
                val transportTop = actionBarTop - spacing.pageGap - spacing.itemHeight
                val scrimHeight = maxHeight - transportTop
                val blackAt = ((actionBarTop - transportTop) / scrimHeight).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(scrimHeight)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                blackAt to Color.Black,
                                1f to Color.Black
                            )
                        )
                )
            }
        }
        SidekitScaffold(title = null, actions = actions, options = options) { padding ->
            if (current == null) {
                // The same status block the queue and the library use: vinyl, title and hint, so an
                // empty Now Playing reads like every other empty page instead of a bare line of text.
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    StatusScreen(
                        "Nothing playing",
                        Modifier.fillMaxWidth(),
                        hint = "Pick something from your library"
                    )
                }
            } else if (backdropped && current is Track) {
                BackdroppedNowPlayingBody(
                    playback,
                    current,
                    playbackPositionMs,
                    sleepTimer,
                    padding,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onArtist = onArtist
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val art = minOf(maxWidth, maxHeight)
                        Cover(
                            current.artRes,
                            Modifier.size(art),
                            shape = SidekitTheme.shapes.item,
                            bordered = true,
                            contentDescription = "${current.title} artwork",
                            artUrl = current.artUrl,
                            onClick = when (current) {
                                is Track -> onAlbum
                                is Episode -> onShow
                            },
                            onClickLabel = when (current) {
                                is Track -> "Open album"
                                is Episode -> "Open show"
                            }
                        )
                    }
                    val episode = current as? Episode
                    // The creator line is a destination: an artist for a track, the show for an
                    // episode. Episodes also carry their publishing identity below it.
                    val creatorAction = when (current) {
                        is Track -> onArtist.takeIf { current.artistId.mediaIdOrNull() != null }
                        is Episode -> onShow
                    }
                    val creatorLabel = if (current is Episode) "Open show" else "Open artist"
                    val notes = remember(episode?.description) { plainPodcastText(episode?.description.orEmpty()) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SidekitText(
                            current.title,
                            Modifier.fillMaxWidth(),
                            style = SidekitTheme.typography.headline,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        if (current.creator.isNotBlank()) {
                            SidekitText(
                                current.creator,
                                Modifier.fillMaxWidth().clickable(
                                    enabled = creatorAction != null,
                                    onClickLabel = creatorLabel,
                                    role = Role.Button,
                                    onClick = { creatorAction?.invoke() }
                                ),
                                style = SidekitTheme.typography.label,
                                color = SidekitTheme.colors.surfaceA6,
                                textAlign = TextAlign.Center
                            )
                        }
                        if (episode != null) {
                            if (notes.isNotBlank()) {
                                SidekitText(
                                    notes,
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp)
                                        .clickable(
                                            onClickLabel = "Read full episode notes",
                                            role = Role.Button,
                                            onClick = onEpisodeNotes
                                        ),
                                    style = SidekitTheme.typography.caption,
                                    color = SidekitTheme.colors.surfaceA6,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                    NowPlayingSleepTimer(sleepTimer)
                    // Only the seek bar subscribes to the playback position flow, so the 10x/s
                    // position ticks recompose just this small composable, not the whole screen.
                    // The seek block owns its own height, so the labels can sit close to the artist
                    // line while the bar keeps a little air above the transport row.
                    NowPlayingSeek(
                        playbackPositionMs,
                        current.durationMs,
                        onSeek,
                        buffering = playback.isBuffering
                    )
                    if (current is Episode) {
                        // Podcasts do not repeat or shuffle. The two outer tiles are the podcast
                        // controls a song player does not need: rewind and fast-forward by 30 s.
                        SidekitItemRow {
                            SkipTile("−30", "Back 30 seconds", onSkipBack)
                            IconTile(SidekitIcon.SkipPrevious, "Previous episode", onPrevious, weight = 0.85f)
                            IconTile(
                                if (playback.isPlaying) SidekitIcon.Pause else SidekitIcon.PlayArrow,
                                if (playback.isPlaying) "Pause" else "Play",
                                onPlayPause,
                                tone = TileTone.Play,
                                weight = 1.2f
                            )
                            IconTile(SidekitIcon.SkipNext, "Next", onNext, weight = 0.85f)
                            SkipTile("+30", "Forward 30 seconds", onSkipForward)
                        }
                    } else {
                        SidekitItemRow {
                            IconTile(
                                SidekitIcon.Shuffle,
                                if (playback.shuffle) "Shuffle on" else "Shuffle off",
                                onShuffle,
                                tone = if (playback.shuffle) TileTone.On else TileTone.Neutral,
                                weight = 0.85f
                            )
                            IconTile(SidekitIcon.SkipPrevious, "Previous", onPrevious, weight = 0.85f)
                            IconTile(
                                if (playback.isPlaying) SidekitIcon.Pause else SidekitIcon.PlayArrow,
                                if (playback.isPlaying) "Pause" else "Play",
                                onPlayPause,
                                tone = TileTone.Play,
                                weight = 1.2f
                            )
                            IconTile(SidekitIcon.SkipNext, "Next", onNext, weight = 0.85f)
                            RepeatTile(playback.repeat, onRepeat)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A slim, deliberately non-interactive status record between the artist and the seek row. It is
 * not a 48 dp target because it is not a control: changes go through Options.
 */
@Composable
internal fun NowPlayingSleepTimer(sleepTimer: StateFlow<SleepTimerState>) {
    val timer by sleepTimer.collectAsStateWithLifecycle()
    val label = timer.statusText() ?: return
    val description = timer.statusDescription() ?: return
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SidekitIconImage(
            SidekitIcon.Bedtime,
            size = 24.dp,
            tint = SidekitTheme.colors.surfaceA6
        )
        SidekitText(
            label,
            style = SidekitTheme.typography.small,
            color = SidekitTheme.colors.surfaceA6
        )
    }
}
