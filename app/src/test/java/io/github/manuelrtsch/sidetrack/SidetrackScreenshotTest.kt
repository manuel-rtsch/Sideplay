package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.design.input.KeypadModule
import io.github.manuelrtsch.sidetrack.data.EpisodeProgress
import io.github.manuelrtsch.sidetrack.data.KnownServices
import io.github.manuelrtsch.sidetrack.data.NavidromeDemo
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidetrackScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private val state = screenshotState()

    @Test fun home() = shoot("sidetrack_home") {
        SidetrackHomeScreen(
            state.playback,
            state.chrome,
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onOpenQueue = {},
            onAddToQueue = {},
            onRemoveHistory = { null },
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun homeWithSleepTimer() = shoot("sidetrack_home_sleep_timer") {
        SidetrackHomeScreen(
            state.playback,
            state.chrome,
            sleepTimer = MutableStateFlow(SleepTimerState.Countdown(30, 1_800_000L, 23)),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onOpenQueue = {},
            onAddToQueue = {},
            onRemoveHistory = { null },
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun homePaused() = shoot("sidetrack_home_paused") {
        SidetrackHomeScreen(
            state.copy(isPlaying = false).playback,
            state.chrome,
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onOpenQueue = {},
            onAddToQueue = {},
            onRemoveHistory = { null },
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun homePausedWithSleepTimer() = shoot("sidetrack_home_paused_timer") {
        SidetrackHomeScreen(
            state.copy(isPlaying = false).playback,
            state.chrome,
            sleepTimer = MutableStateFlow(SleepTimerState.Countdown(30, 1_800_000L, 23)),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onOpenQueue = {},
            onAddToQueue = {},
            onRemoveHistory = { null },
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun homeSleepTimerFinished() = shoot("sidetrack_home_sleep_timer_finished") {
        SidetrackHomeScreen(
            state.copy(isPlaying = false).playback,
            state.chrome,
            sleepTimer = MutableStateFlow(SleepTimerState.Finished),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onOpenQueue = {},
            onAddToQueue = {},
            onRemoveHistory = { null },
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun nowPlayingNotice() = shoot("sidetrack_now_playing_notice") {
        SidetrackHomeScreen(
            state.copy(nowPlayingNotice = state.current).playback,
            state.copy(nowPlayingNotice = state.current).chrome,
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onOpenQueue = {},
            onAddToQueue = {},
            onRemoveHistory = { null },
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun history() = shoot("sidetrack_history") {
        val episode = MockSpotifyDataSource.shows.first().episodes.first()
        HistoryScreen(
            state.copy(recent = listOf(episode) + state.recent).playback,
            state.copy(recent = listOf(episode) + state.recent).chrome,
            onBack = {},
            onPlay = {},
            onAddToQueue = {},
            onRemove = { null },
            onNowPlaying = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun library() = shoot("sidetrack_library") {
        LibraryScreen(
            state.library,
            state.saved,
            state.chrome,
            onBack = {},
            onSearch = {},
            onFilter = {},
            onTrack = {},
            onAddToQueue = {},
            onPlaylist = {},
            onAlbum = {},
            onArtist = {},
            onShow = {},
            onSaveTrack = {},
            onEnsureSavedTrack = {},
            onLoadMore = {},
            onNowPlaying = {},
            onRetry = {},
            onCreatePlaylist = {},
            canCreatePlaylist = false,
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun album() = shoot("sidetrack_album") {
        CollectionScreen(
            runBlocking { MockSpotifyDataSource.collection(CollectionType.Album, "night_signals") }!!,
            state.saved,
            state.playback,
            state.chrome,
            onBack = {},
            onPlay = {},
            onPlayPause = {},
            onItem = {},
            onSave = {},
            onArtist = {},
            onAddToQueue = {},
            onAddItemToQueue = {},
            onSaveTrack = {},
            onEnsureSavedTrack = {},
            onNowPlaying = {},
            onArtwork = {},
            onLoadMore = {},
            loadingMore = false,
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun show() = shoot("sidetrack_show") {
        ShowScreen(
            runBlocking { MockSpotifyDataSource.collection(CollectionType.Show, "long_way_home") }!!,
            state.saved,
            state.playback,
            state.chrome,
            episodeProgress = mapOf(
                "lw1" to EpisodeProgress("lw1", 1_200_000L, 2_442_000L, 0L, completed = false)
            ),
            onBack = {},
            onPlay = {},
            onPlayPause = {},
            onItem = {},
            onSave = {},
            onAddLatestToQueue = {},
            onAddItemToQueue = {},
            onNowPlaying = {},
            onArtwork = {},
            onLoadMore = {},
            loadingMore = false,
            onShowNotes = {},
            onEpisodeNotes = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun nowPlayingEpisode() = shoot("sidetrack_now_playing_episode") {
        val episode = MockSpotifyDataSource.shows.first().episodes.last()
        NowPlayingScreen(
            state.copy(current = episode, isPlaying = true).playback,
            state.copy(current = episode, isPlaying = true).saved,
            state.copy(current = episode, isPlaying = true).chrome,
            playbackPositionMs = MutableStateFlow(68_000L),
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
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
            onAlbum = {},
            onShow = {},
            onArtist = {},
            onSleepTimer = {},
            onEpisodeNotes = {},
            onDismissNotice = {}
        )
    }

    @Test fun notes() = shoot("sidetrack_notes") {
        NotesScreen(
            title = "The Last Bus",
            body = "The last bus leaves before the town wakes up. This is what it sounds like from the back seat: " +
                "the driver counting change, the heater clicking on, and a town that has not decided whether " +
                "it is still yesterday. Recorded over three nights, with the permission of everyone aboard.",
            label = "Episode notes",
            onBack = {}
        )
    }

    @Test fun artist() = shoot("sidetrack_artist") {
        val artist = MockSpotifyDataSource.artists.first()
        ArtistScreen(
            artist,
            runBlocking { MockSpotifyDataSource.albumsBy(artist).items },
            runBlocking { MockSpotifyDataSource.topTracks(artist).items },
            state.saved,
            state.playback,
            state.chrome,
            onBack = {},
            onPlay = {},
            onPlayPause = {},
            onTrack = {},
            onAddToQueue = {},
            onAlbum = {},
            onSaveTrack = {},
            onEnsureSavedTrack = {},
            onFollow = {},
            onRadio = {},
            onNowPlaying = {},
            onArtwork = {},
            onLoadMoreAlbums = {},
            onLoadMoreTracks = {},
            albumHasMore = true,
            trackHasMore = true,
            loadingMore = false,
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun searchLanding() = shoot("sidetrack_search_landing") {
        SearchScreen(
            SearchUiState(
                query = "night",
                searchHistory = listOf("night", "aurora", "midnight radio")
            ),
            state.chrome,
            onBack = {},
            onSearch = {},
            onClear = {},
            onRemoveHistory = {},
            onScopes = {},
            onNowPlaying = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun search() = shoot("sidetrack_search") {
        SearchResultsScreen(
            state.copy(query = "night").search,
            state.copy(query = "night").saved,
            state.copy(query = "night").chrome,
            onBack = {},
            onTrack = {},
            onAddToQueue = {},
            onArtist = {},
            onAlbum = {},
            onPlaylist = {},
            onShow = {},
            onSaveTrack = {},
            onEnsureSavedTrack = {},
            onLoadMore = {},
            onRetry = {},
            onScopes = {},
            onNowPlaying = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun artwork() = shoot("sidetrack_artwork") {
        val album = runBlocking { MockSpotifyDataSource.collection(CollectionType.Album, "night_signals") }!!
        SidetrackArtworkScreen(
            artRes = album.artRes,
            artUrl = null,
            label = "${album.title} artwork",
            onBack = {}
        )
    }

    @Composable
    private fun NowPlayingShot(timer: SleepTimerState = SleepTimerState.Off) = NowPlayingUnderTest(state, timer)

    @Test fun nowPlaying() = shoot("sidetrack_now_playing") { NowPlayingShot() }

    @Test fun nowPlayingWithSleepTimer() = shoot("sidetrack_now_playing_sleep_timer") {
        NowPlayingShot(SleepTimerState.Countdown(30, 1_800_000L, 23))
    }

    @Test
    fun nowPlayingBackdrop() {
        val settings = SidetrackLocalSettings(RuntimeEnvironment.getApplication())
        settings.nowPlayingBackdrop = true
        try {
            shoot("sidetrack_now_playing_backdrop") { NowPlayingShot() }
        } finally {
            settings.nowPlayingBackdrop = false
        }
    }

    @Test fun sleepTimer() = shoot("sidetrack_sleep_timer") {
        SleepTimerScreen(
            sleepTimer = MutableStateFlow(SleepTimerState.Countdown(30, 1_800_000L, 23)),
            onBack = {},
            onOff = {},
            onDuration = {},
            onEndOfTrack = {}
        )
    }

    @Test fun queue() = shoot("sidetrack_queue") {
        QueueScreen(
            state.playback,
            state.chrome,
            onBack = {},
            onNowPlaying = {},
            onSleepTimer = {},
            onJump = {},
            onMoveUpcoming = { _, _ -> },
            onCommitReorder = {},
            onRemove = { -1 },
            onClearUpcoming = { emptyList() },
            onRestoreUpcoming = {},
            onRestoreRemoved = { _, _ -> },
            onShuffle = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun settings() = shoot("sidetrack_settings") {
        SettingsScreen(
            state.account,
            state.chrome,
            state.recent.size,
            serviceName = "Spotify",
            onBack = {},
            onAudioQuality = {},
            onNormalization = {},
            onGapless = {},
            onAutoplay = {},
            onClearCache = {},
            onClearHistory = { true },
            onUndoClearHistory = {},
            onSignOut = {},
            onDismissNotice = {},
            onAppearance = {},
            onAbout = {}
        )
    }

    @Test fun audioQuality() = shoot("sidetrack_audio_quality") {
        AudioQualityScreen(
            state.account,
            state.chrome,
            onBack = {},
            onSelect = {},
            onDismissNotice = {}
        )
    }

    @Test fun about() = shoot("sidetrack_about") {
        SidetrackAboutScreen(onBack = {})
    }

    @Test fun loading() = shoot("sidetrack_loading") {
        LoadingScreen(title = "Loading artist")
    }

    @Test fun onboarding() = shoot("sidetrack_welcome") {
        SidetrackOnboardingContent(
            module = KeypadModule.T9,
            canOpenKeyboard = true,
            onOpenKeyboard = {},
            canManageKeypadAccess = true,
            keypadBackEnabled = false,
            onAbout = {},
            onDone = {}
        )
    }

    @Test fun keyboard() = shoot("sidetrack_keyboard") {
        SidetrackKeyboardScreen(onBack = {})
    }

    @Test fun signIn() = shoot("sidetrack_sign_in") {
        SignInScreen(
            SidetrackUiState(signedIn = false).chrome,
            onBack = {},
            onSignIn = {},
            onDismissNotice = {}
        )
    }

    @Test fun servicePicker() = shoot("sidetrack_service_picker") {
        ServicePickerScreen(
            services = KnownServices,
            onSelect = { true },
            onAbout = {}
        )
    }

    @Test fun navidromeSignIn() = shoot("sidetrack_sign_in_navidrome") {
        SignInScreen(
            SidetrackUiState(signedIn = false).chrome,
            onBack = {},
            onSignIn = {},
            onDismissNotice = {},
            serviceName = "Navidrome",
            passwordAuth = true,
            defaults = PasswordSignInDefaults(
                NavidromeDemo.SERVER_URL,
                NavidromeDemo.USERNAME,
                NavidromeDemo.PASSWORD
            )
        )
    }
}
