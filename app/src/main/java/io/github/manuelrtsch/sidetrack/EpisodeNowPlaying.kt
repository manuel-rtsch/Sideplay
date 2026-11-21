package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.LocalHeaderOptionsInset
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.StateFlow

/*
 * The podcast Now Playing layout: the show's cover beside the episode's name, then the notes, the
 * sleep-timer record, the seek bar and the 30 s transport.
 *
 * It is a separate file for the same reason [NowPlayingBackdrop] is: it is an adaptation of the
 * screen, not the screen. The chrome, the Options bar and the widgets every layout shares (the seek
 * bar, the sleep-timer record, the song transport row) stay in [NowPlayingScreen]; what is specific
 * to an episode lives here.
 */

/**
 * The show's cover in the episode header. A third of the square a song spends on its artwork:
 * enough to recognise the show at a glance, small enough that the episode's name and notes are
 * what the panel is about.
 */
private val EpisodeCoverSize = 100.dp

/**
 * An episode on Now Playing.
 *
 * A podcast is read as much as it is heard, so the show's cover moves beside the episode's name
 * instead of taking the top square for itself: the notes are the one thing this screen has more of
 * than a song, and the lines the old cover cost them now belong to the notes. The card is read from
 * the top and the transport stays pinned above the action bar; the air in between is the slack the
 * shorter header won back.
 */
@Composable
internal fun EpisodeNowPlayingBody(
    playback: PlaybackUiState,
    episode: Episode,
    playbackPositionMs: StateFlow<Long>,
    sleepTimer: StateFlow<SleepTimerState>,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onOpenShow: () -> Unit,
    onOpenEpisodeNotes: () -> Unit
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        EpisodeHeader(episode, onOpenShow = onOpenShow)
        val notes = remember(episode.description) { plainPodcastText(episode.description) }
        if (notes.isNotBlank()) {
            EpisodeNotes(notes, onOpenEpisodeNotes = onOpenEpisodeNotes)
        }
        Spacer(Modifier.weight(1f))
        NowPlayingSleepTimer(sleepTimer)
        // Only the seek bar subscribes to the playback position flow, so the 10x/s position ticks
        // recompose just this small composable, not the whole screen.
        NowPlayingSeek(
            playbackPositionMs,
            episode.durationMs,
            onSeek,
            buffering = playback.isBuffering
        )
        EpisodeTransportRow(
            playing = playback.isPlaying,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward
        )
    }
}

/**
 * The show's cover beside the episode's identity, read as one card. Both the cover and the show
 * name open the show, which is what tapping the artwork did when it was the top of the screen.
 *
 * The show name is set one step below the title, in the notes' own size, because the card has to
 * fit beside the cover: the title keeps `body` (36) for two lines, the show drops to `caption` (28),
 * which is what keeps a name like "The Long Way Home" (256 dp) on one line in the ~264 dp the cover
 * and the header's Options corner leave.
 */
@Composable
private fun EpisodeHeader(episode: Episode, onOpenShow: () -> Unit) {
    Row(
        // The card sits under the shared header, so its top line shares the row with the touch
        // Options button. Now Playing publishes no title, so the space the button takes is not
        // reserved for it: Sidekit publishes that reservation and the card takes it here. It is
        // zero on the keypad, which has no button to avoid, so the card gets the width back.
        Modifier.fillMaxWidth().padding(end = LocalHeaderOptionsInset.current),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(
            episode.artRes,
            Modifier.size(EpisodeCoverSize),
            shape = SidekitTheme.shapes.item,
            bordered = true,
            contentDescription = "${episode.title} artwork",
            artUrl = episode.artUrl,
            onClick = onOpenShow,
            onClickLabel = "Open show",
            requestSize = EpisodeCoverSize
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SidekitText(
                episode.title,
                Modifier.fillMaxWidth(),
                style = SidekitTheme.typography.body,
                maxLines = 2
            )
            if (episode.show.isNotBlank()) {
                SidekitText(
                    episode.show,
                    Modifier.fillMaxWidth().clickable(
                        onClickLabel = "Open show",
                        role = Role.Button,
                        onClick = onOpenShow
                    ),
                    style = SidekitTheme.typography.caption,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * The first lines of the episode notes, left-aligned so the block reads as a paragraph instead of
 * a centred caption, and four lines deep because that is the height the cover gave back. A tap
 * opens the full notes.
 */
@Composable
private fun EpisodeNotes(notes: String, onOpenEpisodeNotes: () -> Unit) {
    SidekitText(
        notes,
        Modifier
            .fillMaxWidth()
            // A little air under the card: the show name and the notes share a size, so the gap is
            // what keeps them from reading as one paragraph.
            .padding(top = 4.dp)
            .clickable(
                onClickLabel = "Read full episode notes",
                role = Role.Button,
                onClick = onOpenEpisodeNotes
            ),
        style = SidekitTheme.typography.caption,
        color = SidekitTheme.colors.surfaceA6,
        maxLines = 4
    )
}

/**
 * Podcasts do not repeat or shuffle. The two outer tiles are the podcast controls a song player
 * does not need: rewind and fast-forward by 30 s.
 */
@Composable
private fun EpisodeTransportRow(
    playing: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit
) {
    SidekitItemRow {
        SkipTile("−30", "Back 30 seconds", onSkipBack)
        IconTile(SidekitIcon.SkipPrevious, "Previous episode", onPrevious, weight = 0.85f)
        IconTile(
            if (playing) SidekitIcon.Pause else SidekitIcon.PlayArrow,
            if (playing) "Pause" else "Play",
            onPlayPause,
            tone = TileTone.Play,
            weight = 1.2f
        )
        IconTile(SidekitIcon.SkipNext, "Next", onNext, weight = 0.85f)
        SkipTile("+30", "Forward 30 seconds", onSkipForward)
    }
}
