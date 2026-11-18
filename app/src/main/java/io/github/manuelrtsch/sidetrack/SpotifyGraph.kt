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
import java.io.IOException
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.data.SpotifySessions
import kotlinx.coroutines.flow.MutableStateFlow

const val SIDE_PLAY_ROOT = "sidetrack"
private const val ROOT = SIDE_PLAY_ROOT
const val SIDE_PLAY_START = "sidetrack/home"
const val SIDE_PLAY_ONBOARDING = "sidetrack/welcome"
const val SIDE_PLAY_SIGN_IN = "sidetrack/sign-in"
const val SIDE_PLAY_SEARCH = "sidetrack/search"
private const val HOME = SIDE_PLAY_START
private const val ONBOARDING = SIDE_PLAY_ONBOARDING
private val HISTORY = "$ROOT/history"
private val LIBRARY = "$ROOT/library"
private val COLLECTION = "$ROOT/collection/{type}/{id}"
private val ARTIST = "$ROOT/artist/{id}"
private val SEARCH = SIDE_PLAY_SEARCH
private val SEARCH_SCOPES = "$ROOT/search/scopes"
private val NOW_PLAYING = "$ROOT/now-playing"
private val QUEUE = "$ROOT/queue"
private val RADIO = "$ROOT/song-radio"
private val ARTIST_RADIO = "$ROOT/artist-radio/{id}"
private val SLEEP_TIMER = "$ROOT/sleep-timer"
private val SETTINGS = "$ROOT/settings"
private val AUDIO_QUALITY = "$ROOT/audio-quality"
private val SIGN_IN = SIDE_PLAY_SIGN_IN
private val APPEARANCE = "$ROOT/appearance"
private val KEYBOARD = "$ROOT/keyboard"
private val ABOUT = "$ROOT/about"
private val ARTWORK = "$ROOT/artwork/{artRes}?url={url}&label={label}&ratio={ratio}"
private val NOTES = "$ROOT/notes/{key}"

private fun collectionRoute(type: SpotifyCollectionType, id: String) = "$ROOT/collection/${type.route}/${Uri.encode(id)}"
private fun artistRoute(id: String) = "$ROOT/artist/${Uri.encode(id)}"
private fun artistRadioRoute(id: String) = "$ROOT/artist-radio/${Uri.encode(id)}"
private fun artworkRoute(artRes: Int, artUrl: String?, label: String, ratio: Float = 1f) = "$ROOT/artwork/$artRes?url=${Uri.encode(artUrl.orEmpty())}&label=${Uri.encode(label)}&ratio=$ratio"
private fun notesRoute(key: String) = "$ROOT/notes/${Uri.encode(key)}"

/**
 * Routes whose left action is Back (or a named "Home"/"Library" back). The keypad's Back key uses
 * the same set, so it must not pop a screen that has no back action: Home, Sign in and Welcome
 * leave the press to the system.
 */
private val KEYPAD_BACK_ROUTES = setOf(
    HISTORY,
    LIBRARY,
    COLLECTION,
    ARTIST,
    SEARCH,
    SEARCH_SCOPES,
    NOW_PLAYING,
    QUEUE,
    RADIO,
    ARTIST_RADIO,
    SLEEP_TIMER,
    SETTINGS,
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

private val collectionArgs = listOf(
    navArgument("type") { type = NavType.StringType },
    navArgument("id") { type = NavType.StringType }
)
private val artistArgs = listOf(navArgument("id") { type = NavType.StringType })
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

private fun NavHostController.openNowPlaying(vm: SpotifyViewModel) {
    vm.dismissNowPlayingNotice()
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
            val vm = entry.spotifyViewModel(nav)
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
            val vm = entry.spotifyViewModel(nav)
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
            val vm = entry.spotifyViewModel(nav)
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
                onTrack = { track -> nav.openTrack(vm, track) { vm.playItem(track) } },
                onAddToQueue = vm::addToQueue,
                onPlaylist = { nav.navigate(collectionRoute(SpotifyCollectionType.Playlist, it.id)) },
                onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                onArtist = { nav.navigate(artistRoute(it.id)) },
                onShow = { nav.navigate(collectionRoute(SpotifyCollectionType.Show, it.id)) },
                onSaveTrack = { vm.toggleSaved(it) },
                onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                onLoadMore = vm::loadMoreLibrary,
                onNowPlaying = { nav.openNowPlaying(vm) },
                onRetry = vm::retryLibrary,
                onDismissNotice = vm::dismissNotice,
                onDismissNowPlaying = vm::dismissNowPlayingNotice
            )
        }
        composable(COLLECTION, arguments = collectionArgs) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val library by vm.libraryState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val episodeProgress by vm.episodeProgress.collectAsStateWithLifecycle()
            val type = SpotifyCollectionType.entries.firstOrNull { it.route == entry.arguments?.getString("type") }
            val id = entry.arguments?.getString("id").orEmpty()
            LaunchedEffect(type, id) { type?.let { vm.ensureCollection(it, id) } }
            val collection = type?.let { vm.collection(it, id) }
            if (collection == null) {
                val key = type?.let { "${it.route}:$id" }
                MissingCollectionScreen(
                    onBack = { nav.popBackStack() },
                    loading = key != null && key in library.pendingCollections,
                    loadingTitle = when (type) {
                        SpotifyCollectionType.Album -> "Loading album"
                        SpotifyCollectionType.Playlist -> "Loading playlist"
                        SpotifyCollectionType.Show -> "Loading show"
                        null -> "Loading…"
                    },
                    error = key?.let(library.collectionErrors::get),
                    errorTitle = when (type) {
                        SpotifyCollectionType.Album -> "Couldn't load this album"
                        SpotifyCollectionType.Playlist -> "Couldn't load this playlist"
                        SpotifyCollectionType.Show -> "Couldn't load this show"
                        null -> "Couldn't load this collection"
                    },
                    missingTitle = "This collection isn't available",
                    onRetry = { if (type != null) vm.retryCollection(type, id) }
                )
            } else if (collection.type == SpotifyCollectionType.Show) {
                ShowScreen(
                    collection,
                    saved,
                    playback,
                    chrome,
                    episodeProgress,
                    onBack = { nav.popBackStack() },
                    onPlay = {
                        (collection.items.firstOrNull() as? SpotifyEpisode)?.let(vm::playEpisode)
                    },
                    onPlayPause = vm::togglePlayPause,
                    onItem = { index ->
                        val episode = collection.items.getOrNull(index) as? SpotifyEpisode
                        if (episode != null) nav.openTrack(vm, episode) { vm.playEpisode(episode) }
                    },
                    onSave = { vm.toggleSaved(collection) },
                    onAddLatestToQueue = { collection.items.firstOrNull()?.let(vm::addToQueue) },
                    onAddItemToQueue = vm::addToQueue,
                    onNowPlaying = { nav.openNowPlaying(vm) },
                    onArtwork = {
                        nav.navigate(artworkRoute(collection.artRes, collection.artUrl, collection.title))
                    },
                    onLoadMore = { type?.let { vm.loadMoreCollection(it, id) } },
                    loadingMore = "${type?.route}:$id" in library.collectionMorePending,
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
                        collection.artistId.spotifyIdOrNull()?.let { nav.navigate(artistRoute(it)) }
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
                    onLoadMore = { type?.let { vm.loadMoreCollection(it, id) } },
                    loadingMore = "${type?.route}:$id" in library.collectionMorePending,
                    onDismissNotice = vm::dismissNotice,
                    onDismissNowPlaying = vm::dismissNowPlayingNotice
                )
            }
        }
        composable(ARTIST, arguments = artistArgs) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val library by vm.libraryState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val artistId = entry.arguments?.getString("id").orEmpty()
            LaunchedEffect(artistId) { vm.ensureArtist(artistId) }
            val artist = vm.artist(artistId)
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
                    onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                    onSaveTrack = { vm.toggleSaved(it) },
                    onEnsureSavedTrack = { vm.ensureTrackSaved(it.id) },
                    onFollow = { vm.toggleFollow(artist) },
                    onRadio = { nav.navigate(artistRadioRoute(artist.id)) },
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
            val vm = entry.spotifyViewModel(nav)
            val search by vm.searchState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            SpotifySearchScreen(
                search,
                saved,
                chrome,
                onBack = { nav.popBackStack() },
                onQuery = vm::setQuery,
                onClear = vm::clearQuery,
                onToggleScope = vm::toggleScope,
                onTrack = { track -> nav.openTrack(vm, track) { vm.playItem(track) } },
                onAddToQueue = vm::addToQueue,
                onArtist = { nav.navigate(artistRoute(it.id)) },
                onAlbum = { nav.navigate(collectionRoute(SpotifyCollectionType.Album, it.id)) },
                onPlaylist = { nav.navigate(collectionRoute(SpotifyCollectionType.Playlist, it.id)) },
                onShow = { nav.navigate(collectionRoute(SpotifyCollectionType.Show, it.id)) },
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
            val vm = entry.spotifyViewModel(nav)
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
            val vm = entry.spotifyViewModel(nav)
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            // The position is collected inside the seek composable, so the playback position flow
            // only recomposes the small seek bar, not the whole Now Playing screen.
            SpotifyNowPlayingScreen(
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
                    (playback.current as? SpotifyTrack)?.albumId?.spotifyIdOrNull()?.let { id ->
                        nav.navigate(collectionRoute(SpotifyCollectionType.Album, id))
                    }
                },
                onShow = {
                    (playback.current as? SpotifyEpisode)?.showId?.spotifyIdOrNull()?.let { id ->
                        nav.navigate(collectionRoute(SpotifyCollectionType.Show, id))
                    }
                },
                onArtist = {
                    playback.current?.artistId.spotifyIdOrNull()?.let { nav.navigate(artistRoute(it)) }
                },
                onSleepTimer = { nav.navigate(SLEEP_TIMER) },
                onEpisodeNotes = {
                    (playback.current as? SpotifyEpisode)?.let { episode ->
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
            val vm = entry.spotifyViewModel(nav)
            SpotifySleepTimerScreen(
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
            val vm = entry.spotifyViewModel(nav)
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
            val vm = entry.spotifyViewModel(nav)
            val library by vm.libraryState.collectAsStateWithLifecycle()
            val radioState by vm.radioState.collectAsStateWithLifecycle()
            val saved by vm.savedState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val artistId = entry.arguments?.getString("id").orEmpty()
            LaunchedEffect(artistId) { vm.ensureArtist(artistId) }
            val artist = vm.artist(artistId)
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
            val vm = entry.spotifyViewModel(nav)
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
            val vm = entry.spotifyViewModel(nav)
            val account by vm.accountState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            val playback by vm.playbackState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            LaunchedEffect(Unit) { vm.refreshCacheSize() }
            SpotifySettingsScreen(
                account,
                chrome,
                playback.recent.size,
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
                    nav.navigate(SIGN_IN) { launchSingleTop = true }
                },
                onDismissNotice = vm::dismissNotice,
                // A host-backed Sidekit owns appearance and keyboard settings. Passing null hides
                // those rows instead of offering edits the host would ignore.
                onAppearance = if (hostBacked) null else ({ nav.navigate(APPEARANCE) }),
                onKeyboard = if (hostBacked) null else ({ nav.navigate(KEYBOARD) }),
                onAbout = { nav.navigate(ABOUT) }
            )
        }
        composable(AUDIO_QUALITY) { entry ->
            val vm = entry.spotifyViewModel(nav)
            val account by vm.accountState.collectAsStateWithLifecycle()
            val chrome by vm.chromeState.collectAsStateWithLifecycle()
            SpotifyAudioQualityScreen(
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
            val vm = entry.spotifyViewModel(nav)
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
            SpotifySignInScreen(
                account,
                chrome,
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
    title: String = "Sidetrack",
    loading: Boolean = false,
    loadingTitle: String = "Loading…",
    error: String? = null,
    errorTitle: String = "Couldn't load this",
    missingTitle: String = "This collection isn't available",
    onRetry: (() -> Unit)? = null
) {
    SidekitScaffold(title = title, actions = spotifyBack("Back", onBack)) { padding ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            when {
                loading -> SpotifyLoadingScreen(title = loadingTitle)

                error != null -> SpotifyStatusScreen(
                    errorTitle,
                    hint = error,
                    actionLabel = "Try again",
                    onAction = onRetry
                )

                else -> SpotifyStatusScreen(
                    missingTitle,
                    hint = "Go back and choose another item"
                )
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
    val vm = viewModel<SpotifyViewModel>(
        parent,
        factory = remember(session, openBrowser, context) {
            viewModelFactory {
                initializer {
                    SpotifyViewModel(
                        catalog = session.catalog,
                        writer = session.writer,
                        queue = session.queue,
                        history = session.history,
                        episodeProgressRepository = session.episodeProgress,
                        episodeMetaRepository = session.episodeMeta,
                        session = session,
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
