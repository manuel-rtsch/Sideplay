package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

internal fun NavGraphBuilder.playbackGraph(
    nav: NavHostController,
    onKeypadBackOverride: ((() -> Boolean)?) -> Unit
) {
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
                showSave = radio.saveable,
                onAddToPlaylist = if (vm.capabilities.supportsPlaylistEdit) {
                    { track -> nav.openAddToPlaylist(track) }
                } else {
                    null
                }
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
                    showSave = radio.saveable,
                    onAddToPlaylist = if (vm.capabilities.supportsPlaylistEdit) {
                        { track -> nav.openAddToPlaylist(track) }
                    } else {
                        null
                    }
                )
            }
        }
    }
    composable(QUEUE) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val playback by vm.playbackState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        // Collected here so the queue's Options entry can say the timer is armed, as Now Playing's
        // does. Only a timer change recomposes this route, not the whole list.
        val sleepTimer by vm.sleepTimer.collectAsStateWithLifecycle()
        QueueScreen(
            playback,
            chrome,
            sleepTimerActive = sleepTimer.active,
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
}
