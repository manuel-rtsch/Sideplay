package io.github.manuelrtsch.sideplay

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SideplayScreenshotTest {
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
        searchResults = runBlocking { MockSpotifyDataSource.search("night", SearchScope.entries.toSet()) },
        isPlaying = true,
        positionMs = 68_000L,
        savedTrackIds = setOf(current.id),
        filter = LibraryFilter.Albums
    )

    @Test fun home() = shoot("sideplay_home") {
        SideplayHomeScreen(
            state,
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onRefresh = {},
            onPlay = {},
            onQueue = {},
            onQueueTrack = {},
            onDismissNotice = {}
        )
    }

    @Test fun library() = shoot("sideplay_library_albums") {
        LibraryScreen(
            state,
            onBack = {},
            onSearch = {},
            onFilter = {},
            onTrack = {},
            onQueue = {},
            onPlaylist = {},
            onAlbum = {},
            onArtist = {},
            onShow = {},
            onLoadMore = {},
            onDismissNotice = {}
        )
    }

    @Test fun album() = shoot("sideplay_album") {
        CollectionScreen(
            runBlocking { MockSpotifyDataSource.collection(SpotifyCollectionType.Album, "night_signals") }!!,
            state,
            onBack = {},
            onPlay = {},
            onItem = {},
            onSave = {},
            onArtist = {},
            onQueue = {},
            onQueueItem = {},
            onNowPlaying = {},
            onUndo = {},
            onLoadMore = {},
            loadingMore = false,
            onDismissNotice = {}
        )
    }

    @Test fun artist() = shoot("sideplay_artist") {
        val artist = MockSpotifyDataSource.artists.first()
        ArtistScreen(
            artist,
            runBlocking { MockSpotifyDataSource.albumsBy(artist).items },
            runBlocking { MockSpotifyDataSource.topTracks(artist).items },
            state,
            onBack = {},
            onPlay = {},
            onTrack = {},
            onQueue = {},
            onAlbum = {},
            onFollow = {},
            onNowPlaying = {},
            onUndo = {},
            onLoadMoreAlbums = {},
            onLoadMoreTracks = {},
            albumHasMore = false,
            trackHasMore = false,
            loadingMore = false,
            onDismissNotice = {}
        )
    }

    @Test fun search() = shoot("sideplay_search") {
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
            onDismissNotice = {}
        )
    }

    @Test fun nowPlaying() = shoot("sideplay_now_playing") {
        SpotifyNowPlayingScreen(
            state,
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
            onUndo = {},
            onDismissNotice = {}
        )
    }

    @Test fun queue() = shoot("sideplay_queue") {
        QueueScreen(
            state,
            onBack = {},
            onNowPlaying = {},
            onJump = {},
            onPlayNext = {},
            onRemove = {},
            onRemoveHistory = {},
            onClearUpcoming = { emptyList() },
            onRestoreUpcoming = {},
            onShuffle = {},
            onDismissNotice = {}
        )
    }

    @Test fun settings() = shoot("sideplay_settings") {
        SpotifySettingsScreen(
            state,
            onBack = {},
            onAudioQuality = {},
            onAutoplay = {},
            onClearCache = {},
            onClearHistory = {},
            onSignOut = {},
            onNotice = {},
            onDismissNotice = {}
        )
    }

    @Test fun signIn() = shoot("sideplay_sign_in") {
        SpotifySignInScreen(
            SpotifyUiState(signedIn = false),
            onBack = {},
            onLogIn = {},
            onDismissNotice = {}
        )
    }
}
