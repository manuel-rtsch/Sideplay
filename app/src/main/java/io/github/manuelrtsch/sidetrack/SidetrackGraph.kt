package io.github.manuelrtsch.sidetrack

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.data.AppSessions
import io.github.manuelrtsch.sidetrack.data.KnownServices
import io.github.manuelrtsch.sidetrack.data.NavidromeDemo
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

const val SIDE_PLAY_ROOT = "sidetrack"
private const val ROOT = SIDE_PLAY_ROOT
const val SIDE_PLAY_START = "sidetrack/home"
const val SIDE_PLAY_ONBOARDING = "sidetrack/welcome"
const val SIDE_PLAY_SIGN_IN = "sidetrack/sign-in"
const val SIDE_PLAY_SERVICE_PICKER = "sidetrack/service"
const val SIDE_PLAY_SEARCH = "sidetrack/search"
private const val HOME = SIDE_PLAY_START
private const val ONBOARDING = SIDE_PLAY_ONBOARDING
private val HISTORY = "$ROOT/history"
private val LIBRARY = "$ROOT/library"
private val COLLECTION = "$ROOT/collection/{key}"
private val ARTIST = "$ROOT/artist/{key}"
private val CREATE_PLAYLIST = "$ROOT/playlist/create"
private val SEARCH = SIDE_PLAY_SEARCH
private val SEARCH_SCOPES = "$ROOT/search/scopes"
private val NOW_PLAYING = "$ROOT/now-playing"
private val QUEUE = "$ROOT/queue"
private val RADIO = "$ROOT/song-radio"
private val ARTIST_RADIO = "$ROOT/artist-radio/{key}"
private val SLEEP_TIMER = "$ROOT/sleep-timer"
private val SETTINGS = "$ROOT/settings"
private val SERVICE_PICKER = SIDE_PLAY_SERVICE_PICKER
private val AUDIO_QUALITY = "$ROOT/audio-quality"
private val SIGN_IN = SIDE_PLAY_SIGN_IN
private val APPEARANCE = "$ROOT/appearance"
private val KEYBOARD = "$ROOT/keyboard"
private val ABOUT = "$ROOT/about"
private val ARTWORK = "$ROOT/artwork/{artRes}?url={url}&label={label}&ratio={ratio}"
private val NOTES = "$ROOT/notes/{key}"

private fun collectionRoute(key: MediaKey) = "$ROOT/collection/${Uri.encode(key.encode())}"
private fun artistRoute(key: MediaKey) = "$ROOT/artist/${Uri.encode(key.encode())}"
private fun artistRadioRoute(key: MediaKey) = "$ROOT/artist-radio/${Uri.encode(key.encode())}"
private fun MediaKey.with(type: MediaType, id: String) = MediaKey(service, type, id)
private fun artworkRoute(artRes: Int, artUrl: String?, label: String, ratio: Float = 1f) = "$ROOT/artwork/$artRes?url=${Uri.encode(artUrl.orEmpty())}&label=${Uri.encode(label)}&ratio=$ratio"
private fun notesRoute(key: String) = "$ROOT/notes/${Uri.encode(key)}"

/**
 * Routes with a way back: either a Back action (or a named "Home"/"Library" back), or, for the
 * service picker, no action bar at all but a step behind it. The keypad's Back key uses the same
 * set, so it must not pop a screen that offers no way out: Home, Sign in and Welcome leave the
 * press to the system.
 */
private val KEYPAD_BACK_ROUTES = setOf(
    HISTORY,
    LIBRARY,
    COLLECTION,
    CREATE_PLAYLIST,
    ARTIST,
    SEARCH,
    SEARCH_SCOPES,
    NOW_PLAYING,
    QUEUE,
    RADIO,
    ARTIST_RADIO,
    SLEEP_TIMER,
    SETTINGS,
    SERVICE_PICKER,
    AUDIO_QUALITY,
    APPEARANCE,
    KEYBOARD,
    ABOUT,
    ARTWORK,
    NOTES
)

internal fun keypadBackIsLeftAction(route: String?, canPop: Boolean): Boolean = canPop && route in KEYPAD_BACK_ROUTES

/** The keypad Back key is the touch/keypad equivalent of the screen's left action. */
internal fun NavHostController.keypadBack(): Boolean {
    if (!keypadBackIsLeftAction(currentDestination?.route, previousBackStackEntry != null)) {
        return false
    }
    return popBackStack()
}

/**
 * A tap on the playback notification opens Sidetrack at Now Playing instead of the launcher.
 * The activity records the request here; the nav host consumes it once it is composed.
 */
object SidetrackLinks {
    const val ACTION_OPEN_NOW_PLAYING = "io.github.manuelrtsch.sidetrack.action.OPEN_NOW_PLAYING"

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
        val open by pending.collectAsStateWithLifecycle()
        LaunchedEffect(open) {
            if (!open) return@LaunchedEffect
            pending.value = false
            nav.navigate(NOW_PLAYING) { launchSingleTop = true }
        }
    }
}

private val collectionArgs = listOf(navArgument("key") { type = NavType.StringType })
private val artistArgs = listOf(navArgument("key") { type = NavType.StringType })

private fun MediaType.toCollectionType(): CollectionType? = when (this) {
    MediaType.Album -> CollectionType.Album
    MediaType.Playlist -> CollectionType.Playlist
    MediaType.Show -> CollectionType.Show
    else -> null
}
private val notesArgs = listOf(
    navArgument("key") {
        type = NavType.StringType
        defaultValue = ""
    }
)
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

private fun NavHostController.openNowPlaying(vm: SidetrackViewModel) {
    vm.dismissNowPlayingNotice()
    if (!popBackStack(NOW_PLAYING, inclusive = false)) {
        navigate(NOW_PLAYING) { launchSingleTop = true }
    }
}

/** A tap on the already-playing item opens Now Playing instead of restarting or toggling it. */
private fun NavHostController.openTrack(
    vm: SidetrackViewModel,
    item: MediaItem,
    play: () -> Unit
) {
    if (vm.state.value.current?.id == item.id) openNowPlaying(vm) else play()
}

/**
 * Registers the Sidetrack screens. All screens share one account state holder.
 *
 * When [hostBacked] is true a compatible Sidekit launcher is installed and its configuration is
 * readable, so it owns appearance and keyboard settings; Sidetrack hides its own copies.
 */
fun NavGraphBuilder.sidetrackGraph(
    nav: NavHostController,
    startDestination: String = HOME,
    hostBacked: Boolean = false,
    onKeypadBackOverride: ((() -> Boolean)?) -> Unit = {}
) {
    navigation(startDestination = startDestination, route = ROOT) {
        composable(HOME) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val account by vm.accountState.collectAsStateWithLifecycle()
            LaunchedEffect(account.signedIn) {
                if (!account.signedIn) nav.navigate(SIGN_IN) { launchSingleTop = true }
            }
            SidetrackHomeScreen(
                playback,
                chrome,
                vm.sleepTimer,
                onNowPlaying = { nav.openNowPlaying(vm) },
                onLibrary = { nav.navigate(LIBRARY) },
                onSearch = { nav.navigate(SEARCH) },
                onSettings = { nav.navigate(SETTINGS) },
                onPlay = { item -> nav.openTrack(vm, item) { vm.playRecent(item) } },
                onOpenQueue = { nav.navigate(QUEUE) },
                onAddToQueue = vm::addToQueue,
                onRemoveHistory = vm::removeHistory,
                onHistory = { nav.navigate(HISTORY) },
                onDismissNotice = vm::dismissNotice,
                onDismissNowPlaying = vm::dismissNowPlayingNotice
            )
        }
        composable(HISTORY) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            HistoryScreen(
                playback,
                chrome,
                onBack = { nav.popBackStack() },
                onPlay = { item -> nav.openTrack(vm, item) { vm.playRecent(item) } },
                onAddToQueue = vm::addToQueue,
                onRemove = vm::removeHistory,
                onNowPlaying = { nav.openNowPlaying(vm) },
                onDismissNotice = vm::dismissNotice,
                onDismissNowPlaying = vm::dismissNowPlayingNotice
            )
        }
        composable(LIBRARY) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val library by vm.libraryState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            // The startup pre-cache may have run offline. Opening the Library refreshes the
            // selected category, and also recovers from a failed first load.
            LaunchedEffect(Unit) { vm.refreshLibrary() }
            LibraryScreen(
                library,
                saved,
                chrome,
                onBack = { nav.popBackStack() },
                onSearch = { nav.navigate(SEARCH) },
                onFilter = vm::setFilter,
                onTrack = { track -> nav.openTrack(vm, track) { vm.playNow(track) } },
                onAddToQueue = vm::addToQueue,
                onPlaylist = { nav.navigate(collectionRoute(it.key)) },
                onAlbum = { nav.navigate(collectionRoute(it.key)) },
                onArtist = { nav.navigate(artistRoute(it.key)) },
                onShow = { nav.navigate(collectionRoute(it.key)) },
                onSaveTrack = { vm.toggleSaved(it) },
                onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                onLoadMore = vm::loadMoreLibrary,
                onNowPlaying = { nav.openNowPlaying(vm) },
                onRetry = vm::retryLibrary,
                onCreatePlaylist = { nav.navigate(CREATE_PLAYLIST) },
                canCreatePlaylist = vm.capabilities.supportsPlaylistCreate,
                onDismissNotice = vm::dismissNotice,
                onDismissNowPlaying = vm::dismissNowPlayingNotice
            )
        }
        composable(CREATE_PLAYLIST) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val scope = rememberCoroutineScope()
            CreatePlaylistScreen(
                onBack = { nav.popBackStack() },
                onSubmit = { name ->
                    scope.launch {
                        val playlist = runCatching { vm.createPlaylist(name) }.getOrNull()
                        if (playlist != null) {
                            nav.navigate(collectionRoute(playlist.key)) {
                                launchSingleTop = true
                                popUpTo(CREATE_PLAYLIST) { inclusive = true }
                            }
                        }
                    }
                }
            )
        }
        composable(COLLECTION, arguments = collectionArgs) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val library by vm.libraryState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val episodeProgress by vm.episodeProgress.collectAsStateWithLifecycle()
            val mediaKey = MediaKey.decode(entry.arguments?.getString("key").orEmpty())
            val type = mediaKey?.type?.toCollectionType()
            val id = mediaKey?.id.orEmpty()
            LaunchedEffect(mediaKey) { mediaKey?.let { vm.ensureCollection(it) } }
            val collection = mediaKey?.let { vm.collection(it) }
            if (collection == null) {
                val key = mediaKey?.encode()
                MissingCollectionScreen(
                    onBack = { nav.popBackStack() },
                    loading = key != null && key in library.pendingCollections,
                    loadingTitle = when (type) {
                        CollectionType.Album -> "Loading album"
                        CollectionType.Playlist -> "Loading playlist"
                        CollectionType.Show -> "Loading show"
                        null -> "Loading…"
                    },
                    error = key?.let(library.collectionErrors::get),
                    errorTitle = when (type) {
                        CollectionType.Album -> "Couldn't load this album"
                        CollectionType.Playlist -> "Couldn't load this playlist"
                        CollectionType.Show -> "Couldn't load this show"
                        null -> "Couldn't load this collection"
                    },
                    missingTitle = "This collection isn't available",
                    onRetry = { mediaKey?.let { vm.retryCollection(it) } }
                )
            } else if (collection.type == CollectionType.Show) {
                ShowScreen(
                    collection,
                    saved,
                    playback,
                    chrome,
                    episodeProgress,
                    onBack = { nav.popBackStack() },
                    onPlay = {
                        (collection.items.firstOrNull() as? Episode)?.let(vm::playEpisode)
                    },
                    onPlayPause = vm::togglePlayPause,
                    onItem = { index ->
                        val episode = collection.items.getOrNull(index) as? Episode
                        if (episode != null) nav.openTrack(vm, episode) { vm.playEpisode(episode) }
                    },
                    onSave = { vm.toggleSaved(collection) },
                    onAddLatestToQueue = { collection.items.firstOrNull()?.let(vm::addToQueue) },
                    onAddItemToQueue = vm::addToQueue,
                    onNowPlaying = { nav.openNowPlaying(vm) },
                    onArtwork = {
                        nav.navigate(artworkRoute(collection.artRes, collection.artUrl, collection.title))
                    },
                    onLoadMore = { mediaKey?.let { vm.loadMoreCollection(it) } },
                    loadingMore = mediaKey?.encode() in library.collectionMorePending,
                    onShowNotes = {
                        nav.navigate(
                            notesRoute(
                                SidetrackNotes.remember(
                                    collection.title,
                                    plainPodcastText(collection.description),
                                    "Show notes"
                                )
                            )
                        )
                    },
                    onEpisodeNotes = { episode ->
                        nav.navigate(
                            notesRoute(
                                SidetrackNotes.remember(
                                    episode.title,
                                    plainPodcastText(episode.description),
                                    "Episode notes"
                                )
                            )
                        )
                    },
                    onDismissNotice = vm::dismissNotice,
                    onDismissNowPlaying = vm::dismissNowPlayingNotice
                )
            } else {
                CollectionScreen(
                    collection,
                    saved,
                    playback,
                    chrome,
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
                        collection.artistId.mediaIdOrNull()?.let { nav.navigate(artistRoute(collection.key.with(MediaType.Artist, it))) }
                            ?: vm.notice("This collection has no artist page")
                    },
                    onAddToQueue = { vm.queueCollection(collection) },
                    onAddItemToQueue = vm::addToQueue,
                    onSaveTrack = { vm.toggleSaved(it) },
                    onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                    onNowPlaying = { nav.openNowPlaying(vm) },
                    onArtwork = {
                        nav.navigate(artworkRoute(collection.artRes, collection.artUrl, collection.title))
                    },
                    onLoadMore = { mediaKey?.let { vm.loadMoreCollection(it) } },
                    loadingMore = mediaKey?.encode() in library.collectionMorePending,
                    onDismissNotice = vm::dismissNotice,
                    onDismissNowPlaying = vm::dismissNowPlayingNotice
                )
            }
        }
        composable(ARTIST, arguments = artistArgs) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val library by vm.libraryState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val artistKey = MediaKey.decode(entry.arguments?.getString("key").orEmpty())
            val artistId = artistKey?.id.orEmpty()
            LaunchedEffect(artistKey) { artistKey?.let { vm.ensureArtist(it.id) } }
            val artist = artistKey?.let { vm.artist(it.id) }
            if (artist == null) {
                MissingCollectionScreen(
                    onBack = { nav.popBackStack() },
                    loading = artistId in library.pendingArtists,
                    loadingTitle = "Loading artist",
                    error = library.artistErrors[artistId],
                    errorTitle = "Couldn't load this artist",
                    missingTitle = "This artist isn't available",
                    onRetry = { vm.retryArtist(artistId) }
                )
            } else {
                val albums = vm.albumsBy(artist)
                val tracks = vm.topTracks(artist)
                ArtistScreen(
                    artist,
                    albums,
                    tracks,
                    saved,
                    playback,
                    chrome,
                    onBack = { nav.popBackStack() },
                    onPlay = { vm.playArtist(artist) },
                    onPlayPause = vm::togglePlayPause,
                    onTrack = { track -> nav.openTrack(vm, track) { vm.play(tracks, tracks.indexOf(track)) } },
                    onAddToQueue = vm::addToQueue,
                    onAlbum = { nav.navigate(collectionRoute(it.key)) },
                    onSaveTrack = { vm.toggleSaved(it) },
                    onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                    onFollow = { vm.toggleFollow(artist) },
                    onRadio = { nav.navigate(artistRadioRoute(artist.key)) },
                    canFollow = vm.capabilities.supportsFollowArtist,
                    canRadio = vm.capabilities.supportsRadio,
                    onNowPlaying = { nav.openNowPlaying(vm) },
                    onArtwork = {
                        nav.navigate(artworkRoute(artist.artRes, artist.artUrl, artist.name))
                    },
                    onLoadMoreAlbums = { vm.loadMoreArtistAlbums(artist) },
                    onLoadMoreTracks = { vm.loadMoreArtistTracks(artist) },
                    albumHasMore = library.artistAlbumHasMore[artist.id] == true,
                    trackHasMore = library.artistTrackHasMore[artist.id] == true,
                    loadingMore = artist.id in library.artistMorePending,
                    onDismissNotice = vm::dismissNotice,
                    onDismissNowPlaying = vm::dismissNowPlayingNotice
                )
            }
        }
        composable(SEARCH) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val search by vm.searchState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            SearchScreen(
                search,
                saved,
                chrome,
                onBack = { nav.popBackStack() },
                onQuery = vm::setQuery,
                onClear = vm::clearQuery,
                onToggleScope = vm::toggleScope,
                onTrack = { track -> nav.openTrack(vm, track) { vm.playNow(track) } },
                onAddToQueue = vm::addToQueue,
                onArtist = { nav.navigate(artistRoute(it.key)) },
                onAlbum = { nav.navigate(collectionRoute(it.key)) },
                onPlaylist = { nav.navigate(collectionRoute(it.key)) },
                onShow = { nav.navigate(collectionRoute(it.key)) },
                onSaveTrack = { vm.toggleSaved(it) },
                onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                onLoadMore = vm::loadMoreSearch,
                onRetry = vm::retrySearch,
                onScopes = { nav.navigate(SEARCH_SCOPES) },
                onNowPlaying = { nav.openNowPlaying(vm) },
                onDismissNotice = vm::dismissNotice,
                onDismissNowPlaying = vm::dismissNowPlayingNotice
            )
        }
        composable(SEARCH_SCOPES) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val search by vm.searchState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            SearchScopesScreen(
                search,
                chrome,
                onBack = { nav.popBackStack() },
                onToggleScope = vm::toggleScope,
                onNowPlaying = { nav.openNowPlaying(vm) },
                onDismissNotice = vm::dismissNotice,
                onDismissNowPlaying = vm::dismissNowPlayingNotice
            )
        }
        composable(ARTWORK, arguments = artworkArgs) { entry ->
            val args = entry.arguments
            SidetrackArtworkScreen(
                artRes = args?.getInt("artRes") ?: 0,
                artUrl = args?.getString("url")?.takeIf { it.isNotBlank() },
                label = args?.getString("label").orEmpty().ifBlank { "Artwork" },
                onBack = { nav.popBackStack() },
                aspectRatio = args?.getFloat("ratio") ?: 1f
            )
        }
        composable(NOTES, arguments = notesArgs) { entry ->
            // The body travels through the registry instead of the route: the back stack is part
            // of the saved instance state, and show notes are unbounded text.
            val note = SidetrackNotes.get(entry.arguments?.getString("key"))
            NotesScreen(
                title = note?.title.orEmpty(),
                body = note?.body.orEmpty(),
                label = note?.label.orEmpty().ifBlank { "Notes" },
                onBack = { nav.popBackStack() }
            )
        }
        composable(NOW_PLAYING) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            // The position is collected inside the seek composable, so the playback position flow
            // only recomposes the small seek bar, not the whole Now Playing screen.
            NowPlayingScreen(
                playback,
                saved,
                chrome,
                vm.playbackPositionMs,
                vm.sleepTimer,
                onBack = { nav.popBackStack() },
                onPlayPause = vm::togglePlayPause,
                onPrevious = vm::previous,
                onNext = vm::next,
                onSeek = vm::seekTo,
                onSkipBack = vm::skipBack,
                onSkipForward = vm::skipForward,
                onShuffle = vm::toggleShuffle,
                onRepeat = vm::cycleRepeat,
                onSave = vm::toggleSaveCurrent,
                onOpenQueue = { nav.navigate(QUEUE) },
                onRadio = { nav.navigate(RADIO) },
                onAlbum = {
                    (playback.current as? Track)?.albumId?.mediaIdOrNull()?.let { id ->
                        nav.navigate(collectionRoute(playback.current!!.key.with(MediaType.Album, id)))
                    }
                },
                onShow = {
                    (playback.current as? Episode)?.showId?.mediaIdOrNull()?.let { id ->
                        nav.navigate(collectionRoute(playback.current!!.key.with(MediaType.Show, id)))
                    }
                },
                onArtist = {
                    playback.current?.artistId.mediaIdOrNull()?.let { nav.navigate(artistRoute(playback.current!!.key.with(MediaType.Artist, it))) }
                },
                onSleepTimer = { nav.navigate(SLEEP_TIMER) },
                onEpisodeNotes = {
                    (playback.current as? Episode)?.let { episode ->
                        nav.navigate(
                            notesRoute(
                                SidetrackNotes.remember(
                                    episode.title,
                                    plainPodcastText(episode.description),
                                    "Episode notes"
                                )
                            )
                        )
                    }
                },
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(SLEEP_TIMER) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            SleepTimerScreen(
                vm.sleepTimer,
                onBack = { nav.popBackStack() },
                onOff = {
                    vm.clearSleepTimer()
                    nav.popBackStack()
                },
                onDuration = { minutes ->
                    vm.setSleepTimer(minutes)
                    nav.popBackStack()
                },
                onEndOfTrack = {
                    vm.setSleepTimerEndOfTrack()
                    nav.popBackStack()
                }
            )
        }
        composable(RADIO) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val radioState by vm.radioState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { vm.ensureRadio() }
            val radio = radioState.radio
            if (radio == null) {
                MissingCollectionScreen(
                    onBack = { nav.popBackStack() },
                    title = "Song radio",
                    // Before the LaunchedEffect runs there is no request state yet; treat that
                    // first frame as loading instead of flashing the empty station message.
                    loading = radioState.radioPending || radioState.radioError == null,
                    loadingTitle = "Starting radio",
                    error = radioState.radioError,
                    errorTitle = "Couldn't start song radio",
                    missingTitle = "No radio for this track",
                    onRetry = vm::retryRadio
                )
            } else {
                CollectionScreen(
                    radio,
                    saved,
                    playback,
                    chrome,
                    onBack = { nav.popBackStack() },
                    onPlay = { vm.playRadio(0) },
                    onPlayPause = vm::togglePlayPause,
                    onItem = { vm.playRadio(it) },
                    onSave = { vm.toggleSaved(radio) },
                    onArtist = {},
                    onAddToQueue = { vm.queueCollection(radio) },
                    onAddItemToQueue = vm::addToQueue,
                    onSaveTrack = { vm.toggleSaved(it) },
                    onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                    onNowPlaying = { nav.openNowPlaying(vm) },
                    onArtwork = { nav.openNowPlaying(vm) },
                    onLoadMore = {},
                    loadingMore = false,
                    onDismissNotice = vm::dismissNotice,
                    onDismissNowPlaying = vm::dismissNowPlayingNotice,
                    showSave = radio.saveable
                )
            }
        }
        composable(ARTIST_RADIO, arguments = artistArgs) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val library by vm.libraryState.collectAsStateWithLifecycle()
            val radioState by vm.radioState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val artistKey = MediaKey.decode(entry.arguments?.getString("key").orEmpty())
            val artistId = artistKey?.id.orEmpty()
            LaunchedEffect(artistKey) { artistKey?.let { vm.ensureArtist(it.id) } }
            val artist = artistKey?.let { vm.artist(it.id) }
            if (artist == null) {
                MissingCollectionScreen(
                    onBack = { nav.popBackStack() },
                    title = "Artist radio",
                    loading = artistId in library.pendingArtists,
                    loadingTitle = "Loading artist",
                    error = library.artistErrors[artistId],
                    errorTitle = "Couldn't load this artist",
                    missingTitle = "This artist isn't available",
                    onRetry = { vm.retryArtist(artistId) }
                )
            } else {
                LaunchedEffect(artist.id) { vm.ensureArtistRadio(artist) }
                // A station built for another artist is not this screen's station, even before the
                // request above has reset the state.
                val radio = radioState.artistRadio?.takeIf { radioState.artistRadioArtistId == artist.id }
                if (radio == null) {
                    MissingCollectionScreen(
                        onBack = { nav.popBackStack() },
                        title = "Artist radio",
                        // Before the LaunchedEffect runs there is no request state yet.
                        loading = radioState.artistRadioPending || radioState.artistRadioError == null,
                        loadingTitle = "Starting radio",
                        error = radioState.artistRadioError,
                        errorTitle = "Couldn't start artist radio",
                        missingTitle = "No radio for this artist",
                        onRetry = { vm.retryArtistRadio(artist) }
                    )
                } else {
                    CollectionScreen(
                        radio,
                        saved,
                        playback,
                        chrome,
                        onBack = { nav.popBackStack() },
                        onPlay = { vm.playArtistRadio(0) },
                        onPlayPause = vm::togglePlayPause,
                        onItem = { vm.playArtistRadio(it) },
                        onSave = { vm.toggleSaved(radio) },
                        onArtist = {},
                        onAddToQueue = { vm.queueCollection(radio) },
                        onAddItemToQueue = vm::addToQueue,
                        onSaveTrack = { vm.toggleSaved(it) },
                        onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                        onNowPlaying = { nav.openNowPlaying(vm) },
                        onArtwork = { nav.navigate(artworkRoute(artist.artRes, artist.artUrl, artist.name)) },
                        onLoadMore = {},
                        loadingMore = false,
                        onDismissNotice = vm::dismissNotice,
                        onDismissNowPlaying = vm::dismissNowPlayingNotice,
                        showSave = radio.saveable
                    )
                }
            }
        }
        composable(QUEUE) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            QueueScreen(
                playback,
                chrome,
                onBack = { nav.popBackStack() },
                onKeypadBackOverride = onKeypadBackOverride,
                onNowPlaying = { nav.openNowPlaying(vm) },
                onSleepTimer = { nav.navigate(SLEEP_TIMER) },
                onJump = vm::jumpTo,
                onMoveUpcoming = vm::moveUpcoming,
                onCommitReorder = vm::finishReorder,
                onRemove = vm::removeUpcoming,
                onRestoreRemoved = vm::restoreRemovedUpcoming,
                onClearUpcoming = vm::clearUpcoming,
                onRestoreUpcoming = vm::restoreUpcoming,
                onShuffle = vm::toggleShuffle,
                onDismissNotice = vm::dismissNotice,
                onDismissNowPlaying = vm::dismissNowPlayingNotice
            )
        }
        composable(SETTINGS) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val account by vm.accountState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val appSession = remember(context) { AppSessions.get(context) }
            LaunchedEffect(Unit) { vm.refreshCacheSize() }
            SettingsScreen(
                account,
                chrome,
                playback.recent.size,
                serviceName = appSession.serviceName,
                capabilities = vm.capabilities,
                onBack = { nav.popBackStack() },
                onAudioQuality = { nav.navigate(AUDIO_QUALITY) },
                onNormalization = vm::toggleNormalization,
                onGapless = vm::toggleGapless,
                onAutoplay = vm::toggleAutoplay,
                onClearCache = vm::clearCache,
                onClearHistory = vm::clearHistory,
                onUndoClearHistory = vm::undoClearHistory,
                onSignOut = {
                    vm.signOut()
                    // Signing out is the only route back to the picker: choosing a service is a
                    // provisioning step, not a setting, so it must not happen behind a live app.
                    nav.navigate(SERVICE_PICKER) {
                        popUpTo(ROOT) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onDismissNotice = vm::dismissNotice,
                // A host-backed Sidekit owns appearance and keyboard settings. Passing null hides
                // those rows instead of offering edits the host would ignore.
                onAppearance = if (hostBacked) null else ({ nav.navigate(APPEARANCE) }),
                onKeyboard = if (hostBacked) null else ({ nav.navigate(KEYBOARD) }),
                onAbout = { nav.navigate(ABOUT) }
            )
        }
        composable(SERVICE_PICKER) {
            val context = LocalContext.current
            val appSession = remember(context) { AppSessions.get(context) }
            // The picker is the first screen on a fresh install and a pushed screen when it comes
            // from Sign in. Either way it is the same neutral screen: no pre-selection and no
            // action bar, so the Sign in buttons are not morphed into a back button here.
            ServicePickerScreen(
                services = KnownServices,
                onSelect = { id ->
                    if (!AppSessions.select(context, id)) {
                        false
                    } else {
                        // A service that still holds credentials goes straight in; a new one starts
                        // at sign-in, exactly as it would on the next launch.
                        val next = if (appSession.signedIn.value) HOME else SIGN_IN
                        nav.navigate(next) {
                            popUpTo(ROOT) { inclusive = false }
                            launchSingleTop = true
                        }
                        true
                    }
                }
            )
        }
        composable(AUDIO_QUALITY) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val account by vm.accountState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            AudioQualityScreen(
                account,
                chrome,
                onBack = { nav.popBackStack() },
                onSelect = vm::setAudioQuality,
                onDismissNotice = vm::dismissNotice
            )
        }
        composable(APPEARANCE) {
            SidetrackAppearanceScreen(onBack = { nav.popBackStack() })
        }
        composable(KEYBOARD) {
            val backLabel = if (nav.previousBackStackEntry?.destination?.route == ONBOARDING) {
                "Welcome"
            } else {
                "Settings"
            }
            SidetrackKeyboardScreen(
                onBack = { nav.popBackStack() },
                backLabel = backLabel,
                keypadAccessAvailable = !hostBacked
            )
        }
        composable(ABOUT) {
            val backLabel = when (nav.previousBackStackEntry?.destination?.route) {
                SIGN_IN -> "Sign in"
                ONBOARDING -> "Welcome"
                else -> "Settings"
            }
            SidetrackAboutScreen(onBack = { nav.popBackStack() }, backLabel = backLabel)
        }
        composable(ONBOARDING) {
            SidetrackOnboardingScreen(
                hostBacked = hostBacked,
                onAbout = { nav.navigate(ABOUT) },
                onKeyboard = { nav.navigate(KEYBOARD) },
                onDone = {
                    nav.navigate(HOME) {
                        // Replace Welcome (and anything still below it, such as Sign in), so Back
                        // from Home leaves Sidetrack instead of replaying the welcome. The flag is
                        // written by the screen before this runs.
                        popUpTo(ROOT) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(SIGN_IN) { entry ->
            val vm = entry.sidetrackViewModel(nav)
            val account by vm.accountState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            LaunchedEffect(account.signedIn) {
                if (account.signedIn) {
                    val next = if (SidetrackLocalSettings(context).needsOnboarding()) ONBOARDING else HOME
                    nav.navigate(next) {
                        popUpTo(ROOT) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            val appSession = AppSessions.get(context)
            val passwordAuth = appSession.provider?.id == ServiceId.Navidrome
            SignInScreen(
                chrome,
                onAbout = { nav.navigate(ABOUT) },
                onSignIn = vm::signIn,
                onDismissNotice = vm::dismissNotice,
                onChangeService = { nav.navigate(SERVICE_PICKER) { launchSingleTop = true } },
                serviceName = appSession.serviceName.ifEmpty { "your music service" },
                passwordAuth = passwordAuth,
                // Navidrome's form offers the public demo, so the app can be tried without a
                // server of one's own. Other providers keep their own sign-in route.
                defaults = if (passwordAuth) {
                    PasswordSignInDefaults(
                        NavidromeDemo.SERVER_URL,
                        NavidromeDemo.USERNAME,
                        NavidromeDemo.PASSWORD
                    )
                } else {
                    null
                },
                onPasswordSignIn = vm::signIn
            )
        }
    }
}

@Composable
private fun MissingCollectionScreen(
    onBack: () -> Unit,
    title: String = "Sidetrack",
    loading: Boolean = false,
    loadingTitle: String = "Loading…",
    error: String? = null,
    errorTitle: String = "Couldn't load this",
    missingTitle: String = "This collection isn't available",
    onRetry: (() -> Unit)? = null
) {
    SidekitScaffold(title = title, actions = sidetrackBack("Back", onBack)) { padding ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            when {
                loading -> LoadingScreen(title = loadingTitle)

                error != null -> StatusScreen(
                    errorTitle,
                    hint = error,
                    actionLabel = "Try again",
                    onAction = onRetry
                )

                else -> StatusScreen(
                    missingTitle,
                    hint = "Go back and choose another item"
                )
            }
        }
    }
}

@Composable
private fun NavBackStackEntry.sidetrackViewModel(nav: NavHostController): SidetrackViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    val appSession = remember(context) { AppSessions.get(context) }
    // Observe the selected service so provisioning it recreates the ViewModel for its runtime.
    appSession.active.collectAsStateWithLifecycle()
    val runtime = appSession.runtime
    val openBrowser: suspend (String) -> Unit = remember(context) {
        { url ->
            // A ROM without a browser (a bare GSI, a stripped keypad build) has nothing to hand
            // the sign-in page to. Say so instead of surfacing ActivityNotFoundException.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                throw IOException("No browser is installed to finish sign-in", e)
            }
        }
    }
    val vm = viewModel<SidetrackViewModel>(
        parent,
        key = "sidetrack-${runtime.service.value}",
        factory = remember(runtime, openBrowser, context) {
            viewModelFactory {
                initializer {
                    SidetrackViewModel(
                        catalog = runtime.catalog,
                        writer = runtime.writer,
                        queue = runtime.queue,
                        history = runtime.history,
                        episodeProgressRepository = runtime.episodeProgress,
                        episodeMetaRepository = runtime.episodeMeta,
                        session = runtime,
                        openBrowser = openBrowser,
                        clearArtworkCache = { RemoteArtwork.clear(context) }
                    )
                }
            }
        }
    )
    // The position tick is only useful while the nav host is on screen. The parent entry's
    // lifecycle tracks the whole graph, so one observer per destination is enough and the
    // boolean is idempotent when several destinations are composed during a transition.
    DisposableEffect(parent, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setUiVisible(true)
                Lifecycle.Event.ON_STOP -> vm.setUiVisible(false)
                else -> Unit
            }
        }
        parent.lifecycle.addObserver(observer)
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    return vm
}
