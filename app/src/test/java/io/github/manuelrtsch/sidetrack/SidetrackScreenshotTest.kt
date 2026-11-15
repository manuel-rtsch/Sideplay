package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.design.input.KeypadModule
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidetrackScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { SidekitPreviewFrame(content) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private val recent = MockSpotifyDataSource.recentlyPlayed
    private val current = MockSpotifyDataSource.recentlyPlayed.first()
    private val state = SpotifyUiState(
        current = current,
        upcoming = recent.drop(1),
        history = recent.drop(3),
        recent = recent,
        libraryItems = runBlocking { MockSpotifyDataSource.library(LibraryFilter.Albums).items },
        librarySections = runBlocking {
            LibraryFilter.entries.associateWith { MockSpotifyDataSource.library(it, 0, LIBRARY_SECTION_SIZE).items }
        },
        librarySectionHasMore = LibraryFilter.entries.toSet(),
        searchResults = runBlocking { MockSpotifyDataSource.search("night", SearchScope.entries.toSet()) },
        searchHasMore = SearchScope.entries.toSet(),
        isPlaying = true,
        savedTrackIds = setOf(current.id),
        filter = LibraryFilter.Albums
    )

    @Test fun home() = shoot("sidetrack_home") {
        SidetrackHomeScreen(
            state,
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onQueue = {},
            onQueueTrack = {},
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun homeWithSleepTimer() = shoot("sidetrack_home_sleep_timer") {
        SidetrackHomeScreen(
            state,
            sleepTimer = MutableStateFlow(SleepTimerState.Countdown(30, 1_800_000L, 23)),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onQueue = {},
            onQueueTrack = {},
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun nowPlayingNotice() = shoot("sidetrack_now_playing_notice") {
        SidetrackHomeScreen(
            state.copy(nowPlayingNotice = current),
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onQueue = {},
            onQueueTrack = {},
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun history() = shoot("sidetrack_history") {
        val episode = MockSpotifyDataSource.shows.first().episodes.first()
        HistoryScreen(
            state.copy(recent = listOf(episode) + state.recent),
            onBack = {},
            onPlay = {},
            onQueue = {},
            onRemove = { -1 },
            onNowPlaying = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun library() = shoot("sidetrack_library") {
        LibraryScreen(
            state,
            onBack = {},
            onSearch = {},
            onSeeMore = {},
            onTrack = {},
            onQueue = {},
            onPlaylist = {},
            onAlbum = {},
            onArtist = {},
            onShow = {},
            onNowPlaying = {},
            onRetry = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun libraryCategory() = shoot("sidetrack_library_albums") {
        LibraryCategoryScreen(
            LibraryFilter.Albums,
            state,
            onBack = {},
            onTrack = {},
            onQueue = {},
            onPlaylist = {},
            onAlbum = {},
            onArtist = {},
            onShow = {},
            onLoadMore = {},
            onNowPlaying = {},
            onRetry = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun album() = shoot("sidetrack_album") {
        CollectionScreen(
            runBlocking { MockSpotifyDataSource.collection(SpotifyCollectionType.Album, "night_signals") }!!,
            state,
            onBack = {},
            onPlay = {},
            onPlayPause = {},
            onItem = {},
            onSave = {},
            onArtist = {},
            onQueue = {},
            onQueueItem = {},
            onNowPlaying = {},
            onArtwork = {},
            onLoadMore = {},
            loadingMore = false,
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun artist() = shoot("sidetrack_artist") {
        val artist = MockSpotifyDataSource.artists.first()
        ArtistScreen(
            artist,
            runBlocking { MockSpotifyDataSource.albumsBy(artist).items },
            runBlocking { MockSpotifyDataSource.topTracks(artist).items },
            state,
            onBack = {},
            onPlay = {},
            onPlayPause = {},
            onTrack = {},
            onQueue = {},
            onAlbum = {},
            onFollow = {},
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

    @Test fun search() = shoot("sidetrack_search") {
        SpotifySearchScreen(
            state.copy(query = "night"),
            onBack = {},
            onQuery = {},
            onClear = {},
            onToggleScope = {},
            onTrack = {},
            onQueue = {},
            onArtist = {},
            onAlbum = {},
            onPlaylist = {},
            onShow = {},
            onLoadMore = {},
            onRetry = {},
            onScopes = {},
            onNowPlaying = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun artwork() = shoot("sidetrack_artwork") {
        val album = runBlocking { MockSpotifyDataSource.collection(SpotifyCollectionType.Album, "night_signals") }!!
        SidetrackArtworkScreen(
            artRes = album.artRes,
            artUrl = null,
            label = "${album.title} artwork",
            onBack = {}
        )
    }

    @Test fun nowPlaying() = shoot("sidetrack_now_playing") {
        SpotifyNowPlayingScreen(
            state,
            playbackPositionMs = MutableStateFlow(68_000L),
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
            onBack = {},
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onSeek = {},
            onShuffle = {},
            onRepeat = {},
            onSave = {},
            onQueue = {},
            onAlbum = {},
            onShow = {},
            onArtist = {},
            onSleepTimer = {},
            onDismissNotice = {}
        )
    }

    @Test fun nowPlayingWithSleepTimer() = shoot("sidetrack_now_playing_sleep_timer") {
        SpotifyNowPlayingScreen(
            state,
            playbackPositionMs = MutableStateFlow(68_000L),
            sleepTimer = MutableStateFlow(SleepTimerState.Countdown(30, 1_800_000L, 23)),
            onBack = {},
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onSeek = {},
            onShuffle = {},
            onRepeat = {},
            onSave = {},
            onQueue = {},
            onAlbum = {},
            onShow = {},
            onArtist = {},
            onSleepTimer = {},
            onDismissNotice = {}
        )
    }

    @Test fun sleepTimer() = shoot("sidetrack_sleep_timer") {
        SpotifySleepTimerScreen(
            sleepTimer = MutableStateFlow(SleepTimerState.Countdown(30, 1_800_000L, 23)),
            onBack = {},
            onOff = {},
            onDuration = {},
            onEndOfTrack = {}
        )
    }

    @Test fun queue() = shoot("sidetrack_queue") {
        QueueScreen(
            state,
            onBack = {},
            onNowPlaying = {},
            onJump = {},
            onPlayNext = {},
            onMoveUpcoming = { _, _ -> },
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
        SpotifySettingsScreen(
            state,
            onBack = {},
            onAudioQuality = {},
            onNormalization = {},
            onGapless = {},
            onAutoplay = {},
            onClearCache = {},
            onClearHistory = {},
            onSignOut = {},
            onDismissNotice = {},
            onAppearance = {},
            onAbout = {}
        )
    }

    @Test fun audioQuality() = shoot("sidetrack_audio_quality") {
        SpotifyAudioQualityScreen(
            state,
            onBack = {},
            onSelect = {},
            onDismissNotice = {}
        )
    }

    @Test fun about() = shoot("sidetrack_about") {
        SidetrackAboutScreen(onBack = {})
    }

    @Test fun loading() = shoot("sidetrack_loading") {
        SpotifyLoadingScreen(title = "Loading artist")
    }

    @Test fun onboarding() = shoot("sidetrack_welcome") {
        SidetrackOnboardingContent(
            module = KeypadModule.T9,
            keyboardLabel = KeypadModule.T9.label,
            keyboardHint = "Press Keyboard to switch modules.",
            canSwitchKeyboard = true,
            onSwitchKeyboard = {},
            onAbout = {},
            onDone = {}
        )
    }

    @Test fun signIn() = shoot("sidetrack_sign_in") {
        SpotifySignInScreen(
            SpotifyUiState(signedIn = false),
            onAbout = {},
            onSignIn = {},
            onDismissNotice = {}
        )
    }
}
