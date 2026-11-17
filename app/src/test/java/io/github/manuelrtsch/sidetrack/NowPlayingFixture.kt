package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Now Playing screen with every callback stubbed. It exists so the screenshot suite and the
 * backdrop toggle test drive the same 27-argument call instead of drifting apart.
 */
@Composable
internal fun NowPlayingUnderTest(
    state: SpotifyUiState,
    timer: SleepTimerState = SleepTimerState.Off,
    onAlbum: () -> Unit = {}
) {
    val position = remember { MutableStateFlow(68_000L) }
    val sleepTimer = remember(timer) { MutableStateFlow(timer) }
    SpotifyNowPlayingScreen(
        state.playback,
        state.saved,
        state.chrome,
        playbackPositionMs = position,
        sleepTimer = sleepTimer,
        onBack = {},
        onPlayPause = {},
        onPrevious = {},
        onNext = {},
        onSeek = {},
        onSkipBack = {},
        onSkipForward = {},
        onShuffle = {},
        onRepeat = {},
        onSave = {},
        onOpenQueue = {},
        onRadio = {},
        onAlbum = onAlbum,
        onShow = {},
        onArtist = {},
        onSleepTimer = {},
        onEpisodeNotes = {},
        onDismissNotice = {}
    )
}
