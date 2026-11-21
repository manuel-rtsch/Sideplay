package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.libraryGraph(nav: NavHostController) {
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
            onPlay = { item -> nav.openTrack(vm, item) { vm.playNow(item) } },
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
            onPlay = { item -> nav.openTrack(vm, item) { vm.playNow(item) } },
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
        // Opening the Library paints from the cached category and only refetches when that
        // category is missing, failed, or older than the cache window. Coming back from a
        // playlist is therefore a repaint, not a round trip.
        LaunchedEffect(Unit) { vm.ensureLibrary() }
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
            supportedFilters = vm.supportedFilters,
            onArtistRadio = { nav.navigate(artistRadioRoute(it.key)) },
            canRadio = vm.capabilities.supportsRadio,
            onQueueAlbum = { vm.queueAlbum(it) },
            onQueuePlaylist = { vm.queuePlaylist(it) },
            onAddLatestShowToQueue = { vm.queueLatestEpisode(it) },
            onDismissNotice = vm::dismissNotice,
            onDismissNowPlaying = vm::dismissNowPlayingNotice,
            onAddToPlaylist = if (vm.capabilities.supportsPlaylistEdit) {
                { track -> nav.openAddToPlaylist(track) }
            } else {
                null
            }
        )
    }
    composable(CREATE_PLAYLIST) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        CreatePlaylistScreen(
            chrome,
            onBack = { nav.popBackStack() },
            onSubmit = { name ->
                scope.launch {
                    val playlist = vm.createPlaylist(name)
                    if (playlist != null) {
                        nav.navigate(collectionRoute(playlist.key)) {
                            launchSingleTop = true
                            popUpTo(CREATE_PLAYLIST) { inclusive = true }
                        }
                    }
                }
            },
            onDismissNotice = vm::dismissNotice,
            onDismissNowPlaying = vm::dismissNowPlayingNotice
        )
    }
    composable(ADD_TO_PLAYLIST, arguments = addToPlaylistArgs) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val playlists by vm.playlistState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        val track = SidetrackPlaylistAdd.get(entry.arguments?.getString("key"))
        LaunchedEffect(Unit) { vm.ensurePlaylistChoices() }
        AddToPlaylistScreen(
            track,
            playlists,
            chrome,
            onBack = { nav.popBackStack() },
            onLoadMore = vm::loadMorePlaylistChoices,
            onRetry = vm::retryPlaylistChoices,
            onAdd = { playlist -> track?.let { vm.addToPlaylist(playlist, it) } },
            onUndoAdd = { playlist, index ->
                track?.let { vm.undoAddToPlaylist(playlist, it, index) }
            },
            onDismissNotice = vm::dismissNotice,
            onDismissNowPlaying = vm::dismissNowPlayingNotice
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
                onDismissNowPlaying = vm::dismissNowPlayingNotice,
                onAddToPlaylist = if (vm.capabilities.supportsPlaylistEdit && collection.type != CollectionType.Playlist) {
                    { track -> nav.openAddToPlaylist(track) }
                } else {
                    null
                },
                onRemoveFromPlaylist = if (vm.playlistActions(collection).canRemoveItems) {
                    { item, index -> vm.removeFromPlaylist(collection, item, index) }
                } else {
                    null
                }
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
                onDismissNowPlaying = vm::dismissNowPlayingNotice,
                onAddToPlaylist = if (vm.capabilities.supportsPlaylistEdit) {
                    { track -> nav.openAddToPlaylist(track) }
                } else {
                    null
                }
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
            supportedScopes = vm.supportedScopes,
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
            onDismissNowPlaying = vm::dismissNowPlayingNotice,
            onAddToPlaylist = if (vm.capabilities.supportsPlaylistEdit) {
                { track -> nav.openAddToPlaylist(track) }
            } else {
                null
            },
            canRadio = vm.capabilities.supportsRadio,
            onArtistRadio = { nav.navigate(artistRadioRoute(it.key)) },
            onQueueAlbum = { vm.queueAlbum(it) },
            onQueuePlaylist = { vm.queuePlaylist(it) },
            onAddLatestShowToQueue = { vm.queueLatestEpisode(it) }
        )
    }
    composable(SEARCH_SCOPES) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val search by vm.searchState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        SearchScopesScreen(
            search,
            chrome,
            supportedScopes = vm.supportedScopes,
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
}
