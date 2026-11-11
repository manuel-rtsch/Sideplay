package io.github.manuelrtsch.sideplay

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.sdk.config.HostStatus
import hrln.interactive.sidekit.sdk.config.SidekitConfigClient
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import io.github.manuelrtsch.sideplay.data.SpotifySessions
import kotlinx.coroutines.flow.MutableStateFlow

const val SIDE_PLAY_ROOT = "sideplay"
private const val ROOT = SIDE_PLAY_ROOT
const val SIDE_PLAY_START = "sideplay/home"
private const val HOME = SIDE_PLAY_START
private val LIBRARY = "$ROOT/library"
private val COLLECTION = "$ROOT/collection/{type}/{id}"
private val ARTIST = "$ROOT/artist/{id}"
private val SEARCH = "$ROOT/search"
private val NOW_PLAYING = "$ROOT/now-playing"
private val QUEUE = "$ROOT/queue"
private val SETTINGS = "$ROOT/settings"
private val SIGN_IN = "$ROOT/sign-in"
private val APPEARANCE = "$ROOT/appearance"

private fun collectionRoute(type: SpotifyCollectionType, id: String) = "$ROOT/collection/${type.route}/$id"
private fun artistRoute(id: String) = "$ROOT/artist/$id"

/**
 * A tap on the playback notification opens Sideplay at Now Playing instead of the launcher.
 * The activity records the request here; the nav host consumes it once it is composed.
 */
object SideplayLinks {
    const val ACTION_OPEN_NOW_PLAYING = "io.github.manuelrtsch.sideplay.action.OPEN_NOW_PLAYING"

    private val pending = MutableStateFlow(false)

    fun handle(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN_NOW_PLAYING) return false
        pending.value = true
        return true
    }

    /** Test seam: a unit test must not leave a pending navigation for the next activity. */
    internal fun reset() {
        pending.value = false
    }

    @Composable
    fun Follow(nav: NavHostController) {
        val open by pending.collectAsState()
        LaunchedEffect(open) {
            if (!open) return@LaunchedEffect
            pending.value = false
            nav.navigate(NOW_PLAYING) { launchSingleTop = true }
        }
    }
}

private val collectionArgs = listOf(
    navArgument("type") { type = NavType.StringType },
    navArgument("id") { type = NavType.StringType }
)
private val artistArgs = listOf(navArgument("id") { type = NavType.StringType })

/** Registers the Sideplay screens. All screens share one account state holder. */
fun NavGraphBuilder.sideplayGraph(nav: NavHostController) {
    navigation(startDestination = HOME, route = ROOT) {
        composable(HOME) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.signedIn) {
                if (!state.signedIn) nav.navigate(SIGN_IN) { launchSingleTop = true }
            }
            SideplayHomeScreen(
                state,
                onNowPlaying = { nav.navigate(NOW_PLAYING) },
                onLibrary = { nav.navigate(LIBRARY) },
                onSearch = { nav.navigate(SEARCH) },
                onSettings = { nav.navigate(SETTINGS) },
                onRefresh = vm::refresh,
                onPlay = vm::playLibraryTrack,
                onQueue = { nav.navigate(QUEUE) },
                onQueueTrack = vm::addToQueue,
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(LIBRARY) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            LibraryScreen(
                state,
                onBack = { nav.popBackStack() },
                onSearch = { nav.navigate(SEARCH) },
                onFilter = vm::setFilter,
                onTrack = {
                    vm.playItem(it)
                    nav.navigate(NOW_PLAYING)
                },
                onQueue = vm::addToQueue,
                onPlaylist = { nav.navigate(collectionRoute(SpotifyCollectionType.Playlist, it.id)) },
                onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                onArtist = { nav.navigate(artistRoute(it.id)) },
                onShow = { nav.navigate(collectionRoute(SpotifyCollectionType.Show, it.id)) },
                onLoadMore = vm::loadMoreLibrary,
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(COLLECTION, arguments = collectionArgs) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            UndoWindowLifecycle(vm.undoWindow, vm::commitUndo)
            val type = SpotifyCollectionType.entries.firstOrNull { it.route == entry.arguments?.getString("type") }
            val id = entry.arguments?.getString("id").orEmpty()
            LaunchedEffect(type, id) { type?.let { vm.ensureCollection(it, id) } }
            val collection = type?.let { vm.collection(it, id) }
            if (collection == null) {
                MissingCollectionScreen(onBack = { nav.popBackStack() }, loading = type?.let { "${it.route}:$id" in state.pendingCollections } == true)
            } else {
                CollectionScreen(
                    collection,
                    state,
                    onBack = { nav.popBackStack() },
                    onPlay = { vm.playCollection(collection, 0) },
                    onItem = { vm.playCollection(collection, it) },
                    onSave = { vm.toggleSaved(collection) },
                    onArtist = {
                        collection.artistId?.let { nav.navigate(artistRoute(it)) } ?: vm.notice("This collection has no artist page")
                    },
                    onQueue = { collection.items.firstOrNull()?.let(vm::addToQueue) },
                    onQueueItem = vm::addToQueue,
                    onNowPlaying = { nav.navigate(NOW_PLAYING) },
                    onUndo = vm::undo,
                    onLoadMore = { type?.let { vm.loadMoreCollection(it, id) } },
                    loadingMore = "${type?.route}:$id" in state.collectionMorePending,
                    onDismissNotice = vm::dismissNotice
                )
            }
        }
        composable(ARTIST, arguments = artistArgs) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            UndoWindowLifecycle(vm.undoWindow, vm::commitUndo)
            val artistId = entry.arguments?.getString("id").orEmpty()
            LaunchedEffect(artistId) { vm.ensureArtist(artistId) }
            val artist = vm.artist(artistId)
            if (artist == null) {
                MissingCollectionScreen(onBack = { nav.popBackStack() }, loading = artistId in state.pendingArtists)
            } else {
                val albums = vm.albumsBy(artist)
                val tracks = vm.topTracks(artist)
                ArtistScreen(
                    artist,
                    albums,
                    tracks,
                    state,
                    onBack = { nav.popBackStack() },
                    onPlay = { vm.playArtist(artist) },
                    onTrack = { vm.play(tracks, tracks.indexOf(it)) },
                    onQueue = vm::addToQueue,
                    onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                    onFollow = { vm.toggleFollow(artist) },
                    onNowPlaying = { nav.navigate(NOW_PLAYING) },
                    onUndo = vm::undo,
                    onLoadMoreAlbums = { vm.loadMoreArtistAlbums(artist) },
                    onLoadMoreTracks = { vm.loadMoreArtistTracks(artist) },
                    albumHasMore = state.artistAlbumHasMore[artist.id] == true,
                    trackHasMore = state.artistTrackHasMore[artist.id] == true,
                    loadingMore = artist.id in state.artistMorePending,
                    onDismissNotice = vm::dismissNotice
                )
            }
        }
        composable(SEARCH) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            SpotifySearchScreen(
                state,
                onBack = { nav.popBackStack() },
                onQuery = vm::setQuery,
                onClear = vm::clearQuery,
                onToggleScope = vm::toggleScope,
                onTrack = vm::playItem,
                onQueue = vm::addToQueue,
                onArtist = { nav.navigate(artistRoute(it.id)) },
                onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                onPlaylist = { nav.navigate(collectionRoute(SpotifyCollectionType.Playlist, it.id)) },
                onShow = { nav.navigate(collectionRoute(SpotifyCollectionType.Show, it.id)) },
                onLoadMore = vm::loadMoreSearch,
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(NOW_PLAYING) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            UndoWindowLifecycle(vm.undoWindow, vm::commitUndo)
            SpotifyNowPlayingScreen(
                state,
                onBack = { nav.popBackStack() },
                onPlayPause = vm::togglePlayPause,
                onPrevious = vm::previous,
                onNext = vm::next,
                onSeek = vm::seekTo,
                onShuffle = vm::toggleShuffle,
                onRepeat = vm::cycleRepeat,
                onSave = vm::toggleSaveCurrent,
                onQueue = { nav.navigate(QUEUE) },
                onAlbum = {
                    (state.current as? SpotifyTrack)?.let { track ->
                        nav.navigate(collectionRoute(SpotifyCollectionType.Album, track.albumId))
                    }
                },
                onShow = {
                    (state.current as? SpotifyEpisode)?.let { episode ->
                        nav.navigate(collectionRoute(SpotifyCollectionType.Show, episode.showId))
                    }
                },
                onArtist = {
                    state.current?.artistId?.let { nav.navigate(artistRoute(it)) }
                },
                onUndo = vm::undo,
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(QUEUE) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            QueueScreen(
                state,
                onBack = { nav.popBackStack() },
                onNowPlaying = {
                    if (!nav.popBackStack(NOW_PLAYING, inclusive = false)) {
                        nav.navigate(NOW_PLAYING) { launchSingleTop = true }
                    }
                },
                onJump = vm::jumpTo,
                onPlayNext = vm::moveToNext,
                onRemove = vm::removeUpcoming,
                onRemoveHistory = vm::removeHistory,
                onClearUpcoming = vm::clearUpcoming,
                onRestoreUpcoming = vm::restoreUpcoming,
                onShuffle = vm::toggleShuffle,
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(SETTINGS) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val hostAvailable = remember(context) {
                SidekitConfigClient.status(context, (context as? Activity)?.intent?.let(SidekitConfigClient::authorityFrom)) == HostStatus.Available
            }
            SpotifySettingsScreen(
                state,
                onBack = { nav.popBackStack() },
                onAudioQuality = vm::cycleAudioQuality,
                onAutoplay = vm::toggleAutoplay,
                onClearCache = vm::clearCache,
                onClearHistory = vm::clearHistory,
                onSignOut = {
                    vm.signOut()
                    nav.navigate(SIGN_IN) { launchSingleTop = true }
                },
                onNotice = vm::notice,
                onDismissNotice = vm::dismissNotice,
                onAppearance = if (hostAvailable) null else ({ nav.navigate(APPEARANCE) })
            )
        }
        composable(APPEARANCE) {
            SideplayAppearanceScreen(onBack = { nav.popBackStack() })
        }
        composable(SIGN_IN) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.signedIn) {
                if (state.signedIn) {
                    nav.navigate(HOME) {
                        popUpTo(HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            SpotifySignInScreen(
                state,
                onBack = { (nav.context as? Activity)?.finish() },
                onLogIn = vm::signIn,
                onDismissNotice = vm::dismissNotice
            )
        }
    }
}

@Composable
private fun MissingCollectionScreen(onBack: () -> Unit, loading: Boolean = false) {
    SidekitScaffold(title = "Sideplay", actions = spotifyBack("Back", onBack)) { padding ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.padding(padding)
        ) {
            if (loading) {
                EmptyMessage("Loading…", hint = "Fetching from Spotify")
            } else {
                EmptyMessage("This collection isn't available", hint = "Go back and choose another")
            }
        }
    }
}

@Composable
private fun NavBackStackEntry.spotifyViewModel(nav: NavHostController): SpotifyViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    val session = remember(context) { SpotifySessions.get(context) }
    val openBrowser: suspend (String) -> Unit = remember(context) {
        { url ->
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    return viewModel(
        parent,
        factory = remember(session, openBrowser, context) {
            viewModelFactory {
                initializer {
                    SpotifyViewModel(
                        catalog = session.catalog,
                        writer = session.writer,
                        queue = session.queue,
                        history = session.history,
                        session = session,
                        openBrowser = openBrowser,
                        clearArtworkCache = { RemoteArtwork.clear(context) }
                    )
                }
            }
        }
    )
}
