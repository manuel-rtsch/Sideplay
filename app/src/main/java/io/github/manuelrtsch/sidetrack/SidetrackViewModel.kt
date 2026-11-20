package io.github.manuelrtsch.sidetrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.data.EpisodeMetaRepository
import io.github.manuelrtsch.sidetrack.data.EpisodeProgress
import io.github.manuelrtsch.sidetrack.data.EpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.HistoryEntry
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.cacheSizeBytes
import io.github.manuelrtsch.sidetrack.data.clearAudioCache
import io.github.manuelrtsch.sidetrack.data.nativeAvailable
import io.github.manuelrtsch.sidetrack.data.setAudioQuality
import io.github.manuelrtsch.sidetrack.data.setGapless
import io.github.manuelrtsch.sidetrack.data.setNormalization
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Sidetrack UI. Catalog reads, queue ordering and local history live in
 * their own domain objects; this class only maps them to the single immutable screen state and
 * implements the optimistic save/follow behaviour the action bars expect.
 */
class SidetrackViewModel(
    private val catalog: MediaCatalog,
    private val writer: MediaLibraryWriter? = null,
    private val queue: QueueRepository = QueueRepository(),
    private val history: HistoryRepository = HistoryRepository(),
    /** The entries the last Clear history removed, kept only while Undo is offered. */
    private var clearedHistory: List<HistoryEntry> = emptyList(),
    private val episodeProgressRepository: EpisodeProgressRepository = EpisodeProgressRepository(),
    private val episodeMetaRepository: EpisodeMetaRepository = EpisodeMetaRepository(),
    private val session: ServiceRuntime? = null,
    private val openBrowser: (suspend (String) -> Unit)? = null,
    private val clearArtworkCache: () -> Unit = {},
    private val simulatePlayback: Boolean = true
) : ViewModel() {
    /** The active service's identity; fixtures without a runtime use the local service. */
    private val service: ServiceId get() = session?.service ?: ServiceId.Local

    private val mediaKeys = MediaKeys(service)

    /** What the active service can do. The UI asks this instead of asking which service it is. */
    val capabilities: ServiceCapabilities get() = session?.capabilities ?: ServiceCapabilities.All

    /** The library categories this service can fill. The category row mutes the rest. */
    val supportedFilters: Set<LibraryFilter> get() = capabilities.availableLibraryFilters

    /** The search categories this service can answer. The scope list mutes the rest. */
    val supportedScopes: Set<SearchScope> get() = capabilities.availableSearchScopes

    /** What the signed-in account may change on one playlist, service limits included. */
    fun playlistActions(playlist: Playlist): PlaylistActions = capabilities.playlistActions(playlist)

    /** The same answer for an open playlist page. Other collection kinds report no actions. */
    fun playlistActions(collection: Collection): PlaylistActions = capabilities.playlistActions(collection)

    private val store = UiStateStore(
        SidetrackUiState(
            signedIn = session?.signedIn?.value ?: false,
            searchScopes = supportedScopes
        )
    )
    val state: StateFlow<SidetrackUiState> = store.state

    /**
     * Focused slices of [state]. A screen collects only the slices it reads, so a change to an
     * unrelated slice (a search keystroke, a save toggle, a cache-size refresh) does not
     * recompose it. Each slice is distinct-until-changed, so equal values do not emit.
     */
    val playbackState: StateFlow<PlaybackUiState> = state
        .map { it.playback }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.playback)

    val libraryState: StateFlow<LibraryUiState> = state
        .map { it.library }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.library)

    val searchState: StateFlow<SearchUiState> = state
        .map { it.search }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.search)

    val accountState: StateFlow<AccountUiState> = state
        .map { it.account }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.account)

    val savedState: StateFlow<SavedUiState> = state
        .map { it.saved }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.saved)

    val chromeState: StateFlow<ChromeUiState> = state
        .map { it.chrome }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.chrome)

    val radioState: StateFlow<RadioUiState> = state
        .map { it.radioSlice }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.radioSlice)

    /**
     * The seek bar's clock. Kept out of [state] so the position tick does not mutate the screen
     * state (which would invalidate and recompose every screen that collects it); only the Now
     * Playing seek bar subscribes to it.
     */
    private val notifier = Notifier(store)

    private val clock: PlaybackClock = PlaybackClock(
        snapshot = store::value,
        onPosition = { session?.playback?.updatePosition(it) },
        onPlaying = { session?.playback?.updateState(true, it) },
        onEpisodeProgress = { effects.recordEpisodeProgress() }
    )

    private val effects = PlaybackEffects(
        store = store,
        queue = queue,
        history = history,
        episodes = episodeProgressRepository,
        episodeMeta = episodeMetaRepository,
        clock = clock,
        playback = { session?.playback },
        resumePoint = { session?.resumePoint },
        scope = viewModelScope
    )

    private val driver = PlaybackDriver(
        store = store,
        clock = clock,
        effects = effects,
        notifier = notifier,
        queue = queue,
        session = session,
        catalog = catalog,
        mediaKeys = mediaKeys,
        ensureTrackSaved = { id -> saves.ensureTrackSaved(id) },
        scope = viewModelScope
    )

    val playbackPositionMs: StateFlow<Long> = clock.positionMs

    /**
     * True while a Sidetrack destination is started. The position tick only runs when this is set,
     * so a backgrounded app does not wake the process ten times a second for an invisible bar.
     */
    private val uiVisible = MutableStateFlow(false)

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
    }

    /** Per-episode podcast progress, kept out of [state] so a five-second write does not redraw menus. */
    val episodeProgress: StateFlow<Map<String, EpisodeProgress>> = episodeProgressRepository.progress

    /**
     * The sleep timer is owned by the process-wide player so it survives this ViewModel. The
     * fallback flow only exists for context-free fixtures without a session.
     */
    private val offlineSleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val sleepTimer: StateFlow<SleepTimerState> = session?.playback?.sleepTimer ?: offlineSleepTimer

    private var signInJob: Job? = null

    private val hints = SavedHints { writer }

    private val library = LibraryController(
        store = store,
        catalog = catalog,
        mediaKeys = mediaKeys,
        driver = driver,
        session = session,
        supportedFilters = { supportedFilters },
        online = { session?.isOnline != false },
        hints = hints,
        notify = { notifier.say(it) },
        scope = viewModelScope
    )

    private val radio = RadioController(
        store = store,
        catalog = catalog,
        mediaKeys = mediaKeys,
        driver = driver,
        queue = queue,
        effects = effects,
        hints = hints,
        session = session,
        online = { session?.isOnline != false },
        scope = viewModelScope
    )

    private val queueEditor = QueueController(
        store = store,
        queue = queue,
        history = history,
        effects = effects,
        catalog = catalog,
        library = library,
        mediaKeys = mediaKeys,
        online = { session?.isOnline != false },
        scope = viewModelScope
    )

    private val saves = SavesController(
        store = store,
        writer = writer,
        hints = hints,
        mediaKeys = mediaKeys,
        online = { session?.isOnline != false },
        scope = viewModelScope
    )

    private val account = AccountController(
        store = store,
        session = session,
        openBrowser = openBrowser,
        clearArtworkCache = clearArtworkCache,
        queue = queue,
        history = history,
        library = library,
        radio = radio,
        hints = hints,
        scope = viewModelScope
    )

    private val search = SearchController(
        store = store,
        catalog = catalog,
        supportedScopes = { supportedScopes },
        online = { session?.isOnline != false },
        coroutineScope = viewModelScope
    )

    private val nextHandler = { driver.next() }
    private val previousHandler = { driver.previous() }
    private val seekHandler = { position: Long -> driver.seekTo(position) }

    init {
        session?.let {
            viewModelScope.launch {
                it.signedIn.collect { signed ->
                    store.update { state -> state.copy(signedIn = signed) }
                    if (signed) account.load() else account.clear()
                }
            }
            viewModelScope.launch {
                it.playback.buffering.collect { buffering ->
                    if (!buffering) clock.anchorCurrent()
                    store.update { state -> state.copy(isBuffering = buffering) }
                }
            }
            viewModelScope.launch {
                it.settings.collect { settings ->
                    store.update { state ->
                        state.copy(
                            audioQuality = settings.audioQuality,
                            normalization = settings.normalization,
                            gapless = settings.gapless,
                            autoplay = settings.autoplay
                        )
                    }
                }
            }
            it.playback.onNext = nextHandler
            it.playback.onPrevious = previousHandler
            it.playback.onSeek = seekHandler
            // A play command from a headset or the notification can arrive before anything is
            // loaded: a cold process restores the queue but not the native player, so resuming
            // would only deliver silence. Restore already anchored the saved offset here.
            it.playback.onResumeWithoutSource = {
                val item = store.value.current
                if (item != null && session != null && item.service != ServiceId.Local) {
                    val resume = clock.value
                    store.update { state -> state.copy(isPlaying = true) }
                    driver.startRemote(item, resume, resuming = resume > 0L)
                }
            }
            // Playback upkeep (a stall that had to reconnect) has to reach the user: from the
            // outside it looked like the player was running while nothing was audible.
            viewModelScope.launch {
                it.playback.notice.collect { text ->
                    if (text != null) {
                        notice(text)
                        it.playback.emitNotice(null)
                    }
                }
            }
            // Starting offline leaves the library empty. Retry as soon as a network appears, so
            // the user does not have to restart the app or hunt for a refresh action.
            it.network?.let { network ->
                viewModelScope.launch {
                    var wasOnline = network.online.value
                    network.online.collect { online ->
                        if (online && !wasOnline && store.value.signedIn) {
                            account.load()
                            if (library.hasLoadedCategory()) library.load(store.value.filter)
                        }
                        wasOnline = online
                    }
                }
            }
        }
        driver.restore()
        refreshCacheSize()
        if (session == null || !session.nativeAvailable) {
            if (simulatePlayback) {
                // Local fixtures and builds without the native library still need a moving progress bar.
                viewModelScope.launch {
                    // Suspend on the playing flag instead of polling: a paused player costs no wakeups.
                    while (true) {
                        store.state.map { it.isPlaying }.first { it }
                        while (store.value.isPlaying) {
                            delay(1_000L)
                            driver.tick()
                        }
                    }
                }
            }
        } else {
            viewModelScope.launch {
                // Native events only matter while something is playing. Poll tight when active, back
                // off to a slow heartbeat when idle so this does not run forever on the main path.
                while (true) {
                    delay(if (store.value.isPlaying) 300L else 3_000L)
                    val event = withContext(Dispatchers.IO) { session.playback.pollEvent() } ?: continue
                    driver.handlePlaybackEvent(event)
                }
            }
            viewModelScope.launch {
                // The seek bar only needs to advance while it is on screen and playing. When the
                // app is backgrounded or paused this suspends on the flows instead of waking up.
                while (true) {
                    uiVisible.combine(store.state) { visible, s ->
                        visible && s.isPlaying && !s.isBuffering
                    }.first { it }
                    while (uiVisible.value && store.value.isPlaying && !store.value.isBuffering) {
                        delay(POSITION_TICK_MS)
                        clock.advanceTick()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        session?.playback?.let {
            if (it.onNext === nextHandler) it.onNext = null
            if (it.onPrevious === previousHandler) it.onPrevious = null
            if (it.onSeek === seekHandler) it.onSeek = null
        }
        super.onCleared()
    }

    fun setSleepTimer(minutes: Int) {
        session?.playback?.setSleepTimer(minutes)
    }

    fun setSleepTimerEndOfTrack() {
        session?.playback?.setSleepTimerEndOfTrack()
    }

    fun clearSleepTimer() {
        session?.playback?.clearSleepTimer()
    }

    fun dismissNotice() = notifier.dismiss()

    /** Shows a transient bar message; [kind] decides how long it stays. */
    fun notice(text: String, kind: NoticeKind = NoticeKind.Other) = notifier.say(text, kind)

    /** Dismisses the transient now-playing confirmation and returns the normal action bar. */
    fun dismissNowPlayingNotice() = effects.dismissNowPlayingNotice()

    /**
     * Creates a new empty playlist named [name] on the active service and refreshes the Library so
     * it is visible. Returns the created [Playlist] for the caller to open, or null when the
     * service cannot create playlists.
     */
    suspend fun createPlaylist(name: String): Playlist? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val playlist = session?.writer?.createPlaylist(trimmed) ?: return null
        refreshLibrary()
        return playlist
    }

    fun playNow(item: MediaItem) = driver.playNow(item)

    fun play(items: List<MediaItem>, index: Int) = driver.play(items, index)

    fun playItem(item: MediaItem) = driver.playItem(item)

    fun playEpisode(episode: Episode) = driver.playEpisode(episode)

    fun ensureRadio() = radio.ensureRadio()

    fun retryRadio() = radio.retryRadio()

    fun playRadio(index: Int) = radio.playRadio(index)

    fun ensureArtistRadio(artist: Artist) = radio.ensureArtistRadio(artist)

    fun retryArtistRadio(artist: Artist) = radio.retryArtistRadio(artist)

    fun playArtistRadio(index: Int) = radio.playArtistRadio(index)

    /**
     * Plays the artist's whole top-tracks list. Pages still behind "See more tracks" are fetched
     * first so the queue always matches the list the page shows, instead of whichever page the
     * user happened to have expanded when they pressed play.
     */

    fun playRecent(item: MediaItem) = driver.playRecent(item)

    fun skipBack(milliseconds: Long = PODCAST_SKIP_MS) = driver.skipBack(milliseconds)

    fun skipForward(milliseconds: Long = PODCAST_SKIP_MS) = driver.skipForward(milliseconds)

    fun togglePlayPause() = driver.togglePlayPause()

    fun next() = driver.next()

    fun previous() = driver.previous()

    fun seekTo(positionMs: Long) = driver.seekTo(positionMs)

    fun jumpTo(index: Int) = driver.jumpTo(index)

    internal fun handlePlaybackEvent(event: PlaybackEvent) = driver.handlePlaybackEvent(event)

    fun toggleSaveCurrent() = saves.toggleSaveCurrent()

    fun toggleSaved(track: Track) = saves.toggleSaved(track)

    fun toggleSaved(collection: Collection) = saves.toggleSaved(collection)

    fun savedCollection(collection: Collection): Boolean = saves.savedCollection(collection)

    fun followed(artist: Artist): Boolean = saves.followed(artist)

    fun toggleFollow(artist: Artist) = saves.toggleFollow(artist)

    fun ensureTrackSaved(id: String) = saves.ensureTrackSaved(id)

    fun removeUpcoming(index: Int): Int = queueEditor.removeUpcoming(index)

    fun restoreRemovedUpcoming(index: Int, item: MediaItem) = queueEditor.restoreRemovedUpcoming(index, item)

    fun moveUpcoming(from: Int, to: Int) = queueEditor.moveUpcoming(from, to)

    fun finishReorder() = queueEditor.finishReorder()

    fun removeHistory(item: MediaItem): (() -> Unit)? = queueEditor.removeHistory(item)

    fun addToQueue(item: MediaItem) = queueEditor.addToQueue(item)

    fun addToQueue(items: List<MediaItem>) = queueEditor.addToQueue(items)

    fun queueCollection(collection: Collection) = queueEditor.queueCollection(collection)

    fun queueAlbum(album: Album) = queueEditor.queueAlbum(album)

    fun queuePlaylist(playlist: Playlist) = queueEditor.queuePlaylist(playlist)

    fun queueLatestEpisode(show: Show) = queueEditor.queueLatestEpisode(show)

    fun clearUpcoming(): List<MediaItem> = queueEditor.clearUpcoming()

    fun restoreUpcoming(items: List<MediaItem>) = queueEditor.restoreUpcoming(items)

    fun signIn() = account.signIn()

    fun signIn(serverUrl: String, username: String, password: String) = account.signIn(serverUrl, username, password)

    fun signOut() {
        account.signOut()
        refreshCacheSize()
    }

    fun toggleShuffle() = effects.applyQueue(queue.toggleShuffle(), syncNowPlaying = false)

    fun cycleRepeat() = effects.applyQueue(queue.cycleRepeat(), syncNowPlaying = false)

    fun collection(type: CollectionType, id: String): Collection? = library.collection(type, id)

    fun collection(key: MediaKey): Collection? = library.collection(key)

    fun artist(id: String): Artist? = library.artist(id)

    fun albumsBy(artist: Artist): List<Album> = library.albumsBy(artist)

    fun topTracks(artist: Artist): List<Track> = library.topTracks(artist)

    fun ensureCollection(type: CollectionType, id: String) = library.ensureCollection(type, id)

    fun ensureCollection(key: MediaKey) = library.ensureCollection(key)

    fun retryCollection(type: CollectionType, id: String) = library.retryCollection(type, id)

    fun retryCollection(key: MediaKey) = library.retryCollection(key)

    fun loadMoreCollection(type: CollectionType, id: String) = library.loadMoreCollection(type, id)

    fun loadMoreCollection(key: MediaKey) = library.loadMoreCollection(key)

    fun loadMoreArtistAlbums(artist: Artist) = library.loadMoreArtistAlbums(artist)

    fun loadMoreArtistTracks(artist: Artist) = library.loadMoreArtistTracks(artist)

    fun ensureArtist(id: String) = library.ensureArtist(id)

    fun retryArtist(id: String) = library.retryArtist(id)

    fun playCollection(collection: Collection, index: Int) = library.playCollection(collection, index)

    fun playArtist(artist: Artist) = library.playArtist(artist)

    fun setFilter(filter: LibraryFilter) = library.setFilter(filter)

    fun loadMoreLibrary() = library.loadMoreLibrary()

    fun refreshLibrary() = library.refresh()

    fun retryLibrary() = library.retry()

    fun setQuery(query: String) = search.setQuery(query)

    fun clearQuery() = search.clearQuery()

    fun toggleScope(scope: SearchScope) = search.toggleScope(scope)

    fun retrySearch() = search.retrySearch()

    fun loadMoreSearch(scope: SearchScope) = search.loadMoreSearch(scope)

    fun setAudioQuality(quality: AudioQuality) {
        if (quality == store.value.audioQuality) return
        store.update { it.copy(audioQuality = quality) }
        session?.setAudioQuality(quality)
    }

    fun toggleNormalization() {
        val enabled = !store.value.normalization
        store.update { it.copy(normalization = enabled) }
        session?.setNormalization(enabled)
    }

    fun toggleGapless() {
        val enabled = !store.value.gapless
        store.update { it.copy(gapless = enabled) }
        session?.setGapless(enabled)
    }

    fun toggleAutoplay() {
        val enabled = !store.value.autoplay
        store.update { it.copy(autoplay = enabled) }
        session?.setAutoplay(enabled)
    }

    fun clearHistory(): Boolean {
        clearedHistory = history.clear()
        val snapshot = queue.clearHistory()
        effects.applyQueue(snapshot, syncNowPlaying = false)
        val cleared = clearedHistory.isNotEmpty()
        // A real clear is confirmed by the Undo offer and the dropping count; an empty list would
        // otherwise look like a dead key.
        store.update {
            if (cleared) it.copy(recent = emptyList()) else it.say("Nothing to clear")
        }
        return cleared
    }

    /** Puts the last cleared history back, timestamps included, so Undo restores the real order. */
    fun undoClearHistory() {
        if (clearedHistory.isEmpty()) return
        history.restoreAll(clearedHistory)
        clearedHistory = emptyList()
        store.update { it.copy(recent = history.items(), notice = null) }
    }

    fun clearCache() {
        clearArtworkCache()
        session?.clearAudioCache()
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        store.update { it.copy(cacheSizeBytes = session?.cacheSizeBytes() ?: 0L) }
    }

    /** Drag-and-drop reorder of the upcoming tail; the current item and history never move. */

    private fun playbackNotice(error: Exception): String = serviceMessage(error, session?.isOnline != false)
}
