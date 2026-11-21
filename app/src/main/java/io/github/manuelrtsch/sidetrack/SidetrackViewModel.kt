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
    private val episodeProgressRepository: EpisodeProgressRepository = EpisodeProgressRepository(),
    private val episodeMetaRepository: EpisodeMetaRepository = EpisodeMetaRepository(),
    private val session: ServiceRuntime? = null,
    private val openBrowser: (suspend (String) -> Unit)? = null,
    private val clearArtworkCache: () -> Unit = {},
    private val simulatePlayback: Boolean = true,
    private val loadSearchHistory: () -> List<String> = { emptyList() },
    private val saveSearchHistory: (List<String>) -> Unit = {}
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
            searchScopes = supportedScopes,
            searchHistory = loadSearchHistory()
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

    val playlistState: StateFlow<PlaylistUiState> = state
        .map { it.playlistSlice }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.playlistSlice)

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

    private val settings = SettingsController(
        store = store,
        session = session,
        history = history,
        queue = queue,
        effects = effects,
        clearArtworkCache = clearArtworkCache
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

    private val playlists = PlaylistController(
        store = store,
        catalog = catalog,
        writer = writer,
        capabilities = { capabilities },
        onCreated = { library.reload(LibraryFilter.Playlists) },
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
        saveSearchHistory = saveSearchHistory,
        coroutineScope = viewModelScope
    )

    private val sessionWiring = session?.let {
        SidetrackSessionWiring(viewModelScope, it, store, clock, driver, account, library) { text -> notice(text) }
    }

    init {
        sessionWiring?.install()
        driver.restore()
        refreshCacheSize()
        PlaybackTicker(viewModelScope, session, store, clock, driver, uiVisible, simulatePlayback).start()
    }

    override fun onCleared() {
        sessionWiring?.detach()
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
     * Creates a new empty playlist named [name] on the active service and reloads the Playlists
     * category so it is visible. Returns the created [Playlist] for the caller to open, or null
     * when the service cannot create playlists.
     */
    suspend fun createPlaylist(name: String): Playlist? = playlists.create(name)

    fun ensurePlaylistChoices() = playlists.ensureChoices()

    fun retryPlaylistChoices() = playlists.retryChoices()

    fun loadMorePlaylistChoices() = playlists.loadMoreChoices()

    suspend fun addToPlaylist(playlist: Playlist, item: MediaItem): Int? = playlists.add(playlist, item)

    suspend fun undoAddToPlaylist(playlist: Playlist, item: MediaItem, index: Int) {
        playlists.undoAdd(playlist, item, index)
    }

    fun removeFromPlaylist(collection: Collection, item: MediaItem, index: Int) {
        playlists.remove(collection, item, index)
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

    /** Cache-first: opening the Library only refetches a category that is missing or ageing. */
    fun ensureLibrary() = library.ensure()

    fun retryLibrary() = library.retry()

    /** Session-only: whether the search field was put away, so the history stays readable. */
    val searchFocusDismissed = search.searchFocusDismissed

    fun dismissSearchFocus() = search.dismissSearchFocus()

    fun restoreSearchFocus() = search.restoreSearchFocus()

    fun setSearchDraft(query: String) = search.setDraftQuery(query)

    fun submitSearch(query: String) = search.submitSearch(query)

    fun removeSearchHistory(query: String) = search.removeSearchHistory(query)

    fun clearSearchHistory() = search.clearSearchHistory()

    fun restoreSearchHistory(history: List<String>) = search.restoreSearchHistory(history)

    fun clearQuery() = search.clearQuery()

    fun toggleScope(scope: SearchScope) = search.toggleScope(scope)

    fun retrySearch() = search.retrySearch()

    fun loadMoreSearch(scope: SearchScope) = search.loadMoreSearch(scope)

    fun setAudioQuality(quality: AudioQuality) = settings.setAudioQuality(quality)

    fun toggleNormalization() = settings.toggleNormalization()

    fun toggleGapless() = settings.toggleGapless()

    fun toggleAutoplay() = settings.toggleAutoplay()

    fun clearHistory(): Boolean = settings.clearHistory()

    fun undoClearHistory() = settings.undoClearHistory()

    fun clearCache() = settings.clearCache()

    fun refreshCacheSize() = settings.refreshCacheSize()

    /** Drag-and-drop reorder of the upcoming tail; the current item and history never move. */

    private fun playbackNotice(error: Exception): String = serviceMessage(error, session?.isOnline != false)
}
