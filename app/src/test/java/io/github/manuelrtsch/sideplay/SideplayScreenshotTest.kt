package io.github.manuelrtsch.sideplay

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        librarySections = runBlocking {
            LibraryFilter.entries.associateWith { MockSpotifyDataSource.library(it, 0, LIBRARY_SECTION_SIZE).items }
        },
        librarySectionHasMore = LibraryFilter.entries.toSet(),
        searchResults = runBlocking { MockSpotifyDataSource.search("night", SearchScope.entries.toSet()) },
        searchHasMore = SearchScope.entries.toSet(),
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
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun nowPlayingNotice() = shoot("sideplay_now_playing_notice") {
        SideplayHomeScreen(
            state.copy(nowPlayingNotice = current),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onRefresh = {},
            onPlay = {},
            onQueue = {},
            onQueueTrack = {},
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun history() = shoot("sideplay_history") {
        val episode = MockSpotifyDataSource.shows.first().episodes.first()
        HistoryScreen(
            state.copy(recent = listOf(episode) + state.recent),
            onBack = {},
            onPlay = {},
            onQueue = {},
            onRemove = {},
            onNowPlaying = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun library() = shoot("sideplay_library") {
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

    @Test fun libraryCategory() = shoot("sideplay_library_albums") {
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

    @Test fun album() = shoot("sideplay_album") {
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

    @Test fun artist() = shoot("sideplay_artist") {
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
            onScopes = {},
            onNowPlaying = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun artwork() = shoot("sideplay_artwork") {
        val album = runBlocking { MockSpotifyDataSource.collection(SpotifyCollectionType.Album, "night_signals") }!!
        SideplayArtworkScreen(
            artRes = album.artRes,
            artUrl = null,
            label = "${album.title} artwork",
            onBack = {}
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
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun settings() = shoot("sideplay_settings") {
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

    @Test fun audioQuality() = shoot("sideplay_audio_quality") {
        SpotifyAudioQualityScreen(
            state,
            onBack = {},
            onSelect = {},
            onDismissNotice = {}
        )
    }

    @Test fun about() = shoot("sideplay_about") {
        SideplayAboutScreen(onBack = {})
    }

    @Test fun loading() = shoot("sideplay_loading") {
        SpotifyLoadingScreen(title = "Loading artist")
    }

    @Test fun signIn() = shoot("sideplay_sign_in") {
        SpotifySignInScreen(
            SpotifyUiState(signedIn = false),
            onAbout = {},
            onSignIn = {},
            onDismissNotice = {}
        )
    }
}
