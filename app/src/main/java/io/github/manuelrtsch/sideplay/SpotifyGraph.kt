package io.github.manuelrtsch.sideplay

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import hrln.interactive.sidekit.sdk.SidekitStores
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sideplay.data.SpotifySessions
import kotlinx.coroutines.flow.MutableStateFlow

const val SIDE_PLAY_ROOT = "sideplay"
private const val ROOT = SIDE_PLAY_ROOT
const val SIDE_PLAY_START = "sideplay/home"
const val SIDE_PLAY_SIGN_IN = "sideplay/sign-in"
private const val HOME = SIDE_PLAY_START
private val HISTORY = "$ROOT/history"
private val LIBRARY = "$ROOT/library"
private val LIBRARY_FILTER = "$ROOT/library/{filter}"
private val COLLECTION = "$ROOT/collection/{type}/{id}"
private val ARTIST = "$ROOT/artist/{id}"
private val SEARCH = "$ROOT/search"
private val SEARCH_SCOPES = "$ROOT/search/scopes"
private val NOW_PLAYING = "$ROOT/now-playing"
private val QUEUE = "$ROOT/queue"
private val SETTINGS = "$ROOT/settings"
private val AUDIO_QUALITY = "$ROOT/audio-quality"
private val SIGN_IN = SIDE_PLAY_SIGN_IN
private val APPEARANCE = "$ROOT/appearance"
private val ABOUT = "$ROOT/about"
private val ARTWORK = "$ROOT/artwork/{artRes}?url={url}&label={label}&ratio={ratio}"

private fun libraryFilterRoute(filter: LibraryFilter) = "$ROOT/library/${filter.name}"
private fun collectionRoute(type: SpotifyCollectionType, id: String) = "$ROOT/collection/${type.route}/${Uri.encode(id)}"
private fun artistRoute(id: String) = "$ROOT/artist/${Uri.encode(id)}"
private fun artworkRoute(artRes: Int, artUrl: String?, label: String, ratio: Float = 1f) = "$ROOT/artwork/$artRes?url=${Uri.encode(artUrl.orEmpty())}&label=${Uri.encode(label)}&ratio=$ratio"

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
private val artworkArgs = listOf(
    navArgument("artRes") { type = NavType.IntType },
    navArgument("url") {
        type = NavType.StringType
        defaultValue = ""
    },
    navArgument("label") {
        type = NavType.StringType
        defaultValue = "Artwork"
    },
    navArgument("ratio") {
        type = NavType.FloatType
        defaultValue = 1f
    }
)

private fun NavHostController.openNowPlaying() {
    if (!popBackStack(NOW_PLAYING, inclusive = false)) {
        navigate(NOW_PLAYING) { launchSingleTop = true }
    }
}

/** A tap on the already-playing item opens Now Playing instead of restarting or toggling it. */
private fun NavHostController.openTrack(
    vm: SpotifyViewModel,
    item: SpotifyMediaItem,
    play: () -> Unit
) {
    if (vm.state.value.current?.id == item.id) openNowPlaying() else play()
}

/** Registers the Sideplay screens. All screens share one account state holder. */
fun NavGraphBuilder.sideplayGraph(nav: NavHostController, startDestination: String = HOME) {
    navigation(startDestination = startDestination, route = ROOT) {
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
                onPlay = { item -> nav.openTrack(vm, item) { vm.playRecent(item) } },
                onQueue = { nav.navigate(QUEUE) },
                onQueueTrack = vm::addToQueue,
                onHistory = { nav.navigate(HISTORY) },
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(HISTORY) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            HistoryScreen(
                state,
                onBack = { nav.popBackStack() },
                onPlay = { item -> nav.openTrack(vm, item) { vm.playRecent(item) } },
                onQueue = vm::addToQueue,
                onRemove = vm::removeHistory,
                onNowPlaying = { nav.navigate(NOW_PLAYING) },
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
                onSeeMore = { nav.navigate(libraryFilterRoute(it)) },
                onTrack = { track ->
                    if (vm.state.value.current?.id == track.id) {
                        nav.openNowPlaying()
                    } else {
                        vm.playItem(track)
                        nav.openNowPlaying()
                    }
                },
                onQueue = vm::addToQueue,
                onPlaylist = { nav.navigate(collectionRoute(SpotifyCollectionType.Playlist, it.id)) },
                onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                onArtist = { nav.navigate(artistRoute(it.id)) },
                onShow = { nav.navigate(collectionRoute(SpotifyCollectionType.Show, it.id)) },
                onNowPlaying = { nav.navigate(NOW_PLAYING) },
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(
            LIBRARY_FILTER,
            arguments = listOf(navArgument("filter") { type = NavType.StringType })
        ) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val filter = LibraryFilter.entries.firstOrNull { it.name == entry.arguments?.getString("filter") }
                ?: LibraryFilter.LikedSongs
            LaunchedEffect(filter) { vm.setFilter(filter) }
            LibraryCategoryScreen(
                filter,
                state,
                onBack = { nav.popBackStack() },
                onTrack = { track ->
                    if (vm.state.value.current?.id == track.id) {
                        nav.openNowPlaying()
                    } else {
                        vm.playItem(track)
                        nav.openNowPlaying()
                    }
                },
                onQueue = vm::addToQueue,
                onPlaylist = { nav.navigate(collectionRoute(SpotifyCollectionType.Playlist, it.id)) },
                onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                onArtist = { nav.navigate(artistRoute(it.id)) },
                onShow = { nav.navigate(collectionRoute(SpotifyCollectionType.Show, it.id)) },
                onLoadMore = vm::loadMoreLibrary,
                onNowPlaying = { nav.navigate(NOW_PLAYING) },
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(COLLECTION, arguments = collectionArgs) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val type = SpotifyCollectionType.entries.firstOrNull { it.route == entry.arguments?.getString("type") }
            val id = entry.arguments?.getString("id").orEmpty()
            LaunchedEffect(type, id) { type?.let { vm.ensureCollection(it, id) } }
            val collection = type?.let { vm.collection(it, id) }
            if (collection == null) {
                MissingCollectionScreen(
                    onBack = { nav.popBackStack() },
                    loading = type?.let { "${it.route}:$id" in state.pendingCollections } == true,
                    loadingTitle = when (type) {
                        SpotifyCollectionType.Album -> "Loading album"
                        SpotifyCollectionType.Playlist -> "Loading playlist"
                        SpotifyCollectionType.Show -> "Loading show"
                        null -> "Loading…"
                    }
                )
            } else {
                CollectionScreen(
                    collection,
                    state,
                    onBack = { nav.popBackStack() },
                    onPlay = { vm.playCollection(collection, 0) },
                    onPlayPause = vm::togglePlayPause,
                    onItem = { index ->
                        collection.items.getOrNull(index)?.let { item ->
                            nav.openTrack(vm, item) { vm.playCollection(collection, index) }
                        }
                    },
                    onSave = { vm.toggleSaved(collection) },
                    onArtist = {
                        collection.artistId.spotifyIdOrNull()?.let { nav.navigate(artistRoute(it)) }
                            ?: vm.notice("This collection has no artist page")
                    },
                    onQueue = { collection.items.firstOrNull()?.let(vm::addToQueue) },
                    onQueueItem = vm::addToQueue,
                    onNowPlaying = { nav.navigate(NOW_PLAYING) },
                    onArtwork = {
                        nav.navigate(artworkRoute(collection.artRes, collection.artUrl, collection.title))
                    },
                    onLoadMore = { type?.let { vm.loadMoreCollection(it, id) } },
                    loadingMore = "${type?.route}:$id" in state.collectionMorePending,
                    onDismissNotice = vm::dismissNotice
                )
            }
        }
        composable(ARTIST, arguments = artistArgs) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            val artistId = entry.arguments?.getString("id").orEmpty()
            LaunchedEffect(artistId) { vm.ensureArtist(artistId) }
            val artist = vm.artist(artistId)
            if (artist == null) {
                MissingCollectionScreen(
                    onBack = { nav.popBackStack() },
                    loading = artistId in state.pendingArtists,
                    loadingTitle = "Loading artist"
                )
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
                    onPlayPause = vm::togglePlayPause,
                    onTrack = { track -> nav.openTrack(vm, track) { vm.play(tracks, tracks.indexOf(track)) } },
                    onQueue = vm::addToQueue,
                    onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                    onFollow = { vm.toggleFollow(artist) },
                    onNowPlaying = { nav.navigate(NOW_PLAYING) },
                    onArtwork = {
                        nav.navigate(artworkRoute(artist.artRes, artist.artUrl, artist.name))
                    },
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
                onTrack = { track -> nav.openTrack(vm, track) { vm.playItem(track) } },
                onQueue = vm::addToQueue,
                onArtist = { nav.navigate(artistRoute(it.id)) },
                onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                onPlaylist = { nav.navigate(collectionRoute(SpotifyCollectionType.Playlist, it.id)) },
                onShow = { nav.navigate(collectionRoute(SpotifyCollectionType.Show, it.id)) },
                onLoadMore = vm::loadMoreSearch,
                onScopes = { nav.navigate(SEARCH_SCOPES) },
                onNowPlaying = { nav.navigate(NOW_PLAYING) },
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(SEARCH_SCOPES) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            SearchScopesScreen(
                state,
                onBack = { nav.popBackStack() },
                onToggleScope = vm::toggleScope,
                onNowPlaying = { nav.navigate(NOW_PLAYING) },
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(ARTWORK, arguments = artworkArgs) { entry ->
            val args = entry.arguments
            SideplayArtworkScreen(
                artRes = args?.getInt("artRes") ?: 0,
                artUrl = args?.getString("url")?.takeIf { it.isNotBlank() },
                label = args?.getString("label").orEmpty().ifBlank { "Artwork" },
                onBack = { nav.popBackStack() },
                aspectRatio = args?.getFloat("ratio") ?: 1f
            )
        }
        composable(NOW_PLAYING) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
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
                    (state.current as? SpotifyTrack)?.albumId?.spotifyIdOrNull()?.let { id ->
                        nav.navigate(collectionRoute(SpotifyCollectionType.Album, id))
                    }
                },
                onShow = {
                    (state.current as? SpotifyEpisode)?.showId?.spotifyIdOrNull()?.let { id ->
                        nav.navigate(collectionRoute(SpotifyCollectionType.Show, id))
                    }
                },
                onArtist = {
                    state.current?.artistId.spotifyIdOrNull()?.let { nav.navigate(artistRoute(it)) }
                },
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
                onJump = { id ->
                    if (state.current?.id == id) nav.openNowPlaying() else vm.jumpTo(id)
                },
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
                SidekitStores.forHostOrLocal(context, (context as? Activity)?.intent).isHostBacked
            }
            LaunchedEffect(Unit) { vm.refreshCacheSize() }
            SpotifySettingsScreen(
                state,
                onBack = { nav.popBackStack() },
                onAudioQuality = { nav.navigate(AUDIO_QUALITY) },
                onNormalization = vm::toggleNormalization,
                onGapless = vm::toggleGapless,
                onAutoplay = vm::toggleAutoplay,
                onClearCache = vm::clearCache,
                onClearHistory = vm::clearHistory,
                onSignOut = {
                    vm.signOut()
                    nav.navigate(SIGN_IN) { launchSingleTop = true }
                },
                onDismissNotice = vm::dismissNotice,
                onAppearance = if (hostAvailable) null else ({ nav.navigate(APPEARANCE) }),
                onAbout = { nav.navigate(ABOUT) }
            )
        }
        composable(AUDIO_QUALITY) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val state by vm.state.collectAsStateWithLifecycle()
            SpotifyAudioQualityScreen(
                state,
                onBack = { nav.popBackStack() },
                onSelect = vm::setAudioQuality,
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(APPEARANCE) {
            SideplayAppearanceScreen(onBack = { nav.popBackStack() })
        }
        composable(ABOUT) {
            val backLabel = if (nav.previousBackStackEntry?.destination?.route == SIGN_IN) "Sign in" else "Settings"
            SideplayAboutScreen(onBack = { nav.popBackStack() }, backLabel = backLabel)
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
                onAbout = { nav.navigate(ABOUT) },
                onSignIn = vm::signIn,
                onDismissNotice = vm::dismissNotice
            )
        }
    }
}

@Composable
private fun MissingCollectionScreen(
    onBack: () -> Unit,
    loading: Boolean = false,
    loadingTitle: String = "Loading…"
) {
    SidekitScaffold(title = "Sideplay", actions = spotifyBack("Back", onBack)) { padding ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.padding(padding)
        ) {
            if (loading) {
                SpotifyLoadingScreen(title = loadingTitle)
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
