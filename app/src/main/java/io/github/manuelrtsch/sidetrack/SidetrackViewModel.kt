package io.github.manuelrtsch.sidetrack

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.EpisodeMetaRepository
import io.github.manuelrtsch.sidetrack.data.EpisodeProgress
import io.github.manuelrtsch.sidetrack.data.EpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.HistoryEntry
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.cacheSizeBytes
import io.github.manuelrtsch.sidetrack.data.clearAudioCache
import io.github.manuelrtsch.sidetrack.data.consumeHaltStop
import io.github.manuelrtsch.sidetrack.data.halting
import io.github.manuelrtsch.sidetrack.data.nativeAvailable
import io.github.manuelrtsch.sidetrack.data.notePlayRequest
import io.github.manuelrtsch.sidetrack.data.setAudioQuality
import io.github.manuelrtsch.sidetrack.data.setGapless
import io.github.manuelrtsch.sidetrack.data.setNormalization
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long the now-playing confirmation owns the action bar before the normal actions return. */
const val NOW_PLAYING_NOTICE_MS = 4_000L

/** The podcast transport's rewind and fast-forward step. */
private const val PODCAST_SKIP_MS = 30_000L

/** How often a playing episode writes its local resume point. */
private const val EPISODE_PROGRESS_INTERVAL_MS = 5_000L

/**
 * How often the seek bar advances while it is on screen. The bar animates between samples, so
 * 200 ms looks the same as 100 ms while halving the wakeups.
 */
private const val POSITION_TICK_MS = 200L

/**
 * A native position event this far from the UI clock is treated as a real seek from another
 * client. Smaller differences are the decoder's normal lead over the speaker, and adopting them
 * would make the bar jump forward every time an event arrives.
 */
private const val POSITION_EVENT_JUMP_MS = 5_000L

/**
 * Upper bound on how long the seek bar waits for the native player to confirm a new item. The
 * hold normally ends with the loading/playing event; this only keeps a lost event from freezing
 * the bar forever.
 */
private const val PLAYBACK_START_TIMEOUT_MS = 10_000L

/** How long a resumed track ignores player events from the generation that was stopped. */
private const val RESUME_GRACE_MS = 8_000L

/** An event this far below the resumed offset is the old player state, not a real seek. */
private const val RESUME_EVENT_SLACK_MS = 2_000L

/** How long a local seek owns the bar while waiting for the player to report the new offset. */
private const val SEEK_CONFIRM_TIMEOUT_MS = 15_000L

data class SidetrackUiState(
    val signedIn: Boolean = false,
    val current: MediaItem? = null,
    val upcoming: List<MediaItem> = emptyList(),
    val history: List<MediaItem> = emptyList(),
    val recent: List<MediaItem> = emptyList(),
    val libraryItems: List<Any> = emptyList(),
    val librarySections: Map<LibraryFilter, List<Any>> = emptyMap(),
    val librarySectionHasMore: Set<LibraryFilter> = emptySet(),
    val searchResults: SearchResults = SearchResults(),
    val searchPending: Boolean = false,
    val authPending: Boolean = false,
    val libraryLoading: Boolean = false,
    val librarySectionsLoading: Boolean = false,
    /** Set when the startup preload could not load any category. */
    val librarySectionsError: String? = null,
    /** Set when the open category could not load and has no items to show. */
    val libraryError: String? = null,
    /** Set when a search request failed and there are no results to keep on screen. */
    val searchError: String? = null,
    /** Failed collection loads, keyed by "type:id", so the screen can offer a retry. */
    val collectionErrors: Map<String, String> = emptyMap(),
    /** Failed artist loads, keyed by artist id, so the screen can offer a retry. */
    val artistErrors: Map<String, String> = emptyMap(),
    val libraryHasMore: Boolean = false,
    val libraryMorePending: Boolean = false,
    val searchHasMore: Set<SearchScope> = emptySet(),
    val searchMorePending: Set<SearchScope> = emptySet(),
    val collectionMorePending: Set<String> = emptySet(),
    val artistAlbumHasMore: Map<String, Boolean> = emptyMap(),
    val artistTrackHasMore: Map<String, Boolean> = emptyMap(),
    val artistMorePending: Set<String> = emptySet(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val savedTrackIds: Set<String> = emptySet(),
    val savedCollectionKeys: Set<String> = emptySet(),
    val followedArtistIds: Set<String> = emptySet(),
    val collections: Map<String, Collection> = emptyMap(),
    val artists: Map<String, Artist> = emptyMap(),
    val artistAlbums: Map<String, List<Album>> = emptyMap(),
    val artistTracks: Map<String, List<Track>> = emptyMap(),
    val pendingCollections: Set<String> = emptySet(),
    val pendingArtists: Set<String> = emptySet(),
    val filter: LibraryFilter = LibraryFilter.LikedSongs,
    val searchScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    val query: String = "",
    val audioQuality: AudioQuality = AudioQuality.High,
    val normalization: Boolean = false,
    val gapless: Boolean = true,
    val autoplay: Boolean = true,
    val cacheSizeBytes: Long = 0L,
    val notice: String? = null,
    /** How long the bar keeps [notice]: a success is short, everything else gets time to be read. */
    val noticeKind: NoticeKind = NoticeKind.Other,
    /** The item that just started, shown briefly in the action bar as a now-playing confirmation. */
    val nowPlayingNotice: MediaItem? = null,
    /** The station built from the current track, shown by the Song radio screen. */
    val radio: Collection? = null,
    /** Which track seeded [radio], so revisiting the same song does not refetch it. */
    val radioSeedId: String? = null,
    val radioPending: Boolean = false,
    val radioError: String? = null,
    /** The station built from an artist, shown by the Artist radio screen. */
    val artistRadio: Collection? = null,
    /** Which artist seeded [artistRadio], so revisiting the same artist does not refetch it. */
    val artistRadioArtistId: String? = null,
    val artistRadioPending: Boolean = false,
    val artistRadioError: String? = null
) {
    val savedCurrent: Boolean get() = current?.let { it.key.encode() in savedTrackIds } ?: false
}

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

    /** What the active service can do. The UI asks this instead of asking which service it is. */
    val capabilities: ServiceCapabilities get() = session?.capabilities ?: ServiceCapabilities.All

    /** The library categories this service can fill. The category row mutes the rest. */
    val supportedFilters: Set<LibraryFilter> get() = capabilities.availableLibraryFilters

    /** The search categories this service can answer. The scope list mutes the rest. */
    val supportedScopes: Set<SearchScope> get() = capabilities.availableSearchScopes
    private val _state = MutableStateFlow(
        SidetrackUiState(
            signedIn = session?.signedIn?.value ?: false,
            searchScopes = supportedScopes
        )
    )
    val state: StateFlow<SidetrackUiState> = _state.asStateFlow()

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
     * Playback position for the seek bar. Kept on its own flow so the position tick does
     * not mutate the monolithic [state] (which would invalidate and recompose every screen that
     * collects it). Only the Now Playing seek bar subscribes to this.
     */
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

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

    private var searchJob: Job? = null
    private var signInJob: Job? = null
    private var autoplayJob: Job? = null
    private var radioJob: Job? = null
    private var artistRadioJob: Job? = null
    private var nowPlayingNoticeJob: Job? = null
    private var librarySectionsJob: Job? = null
    private var libraryJob: Job? = null
    private var libraryCategoryLoaded = false
    private val savedTrackTouched = mutableSetOf<String>()
    private val savedCollectionTouched = mutableSetOf<String>()
    private val followedArtistTouched = mutableSetOf<String>()
    private val nextHandler = { next() }
    private val previousHandler = { previous() }
    private val seekHandler = { position: Long -> seekTo(position) }

    /**
     * Smooth-position anchor. librespot emits position events on its own cadence (and they can
     * arrive late), so the UI interpolates from the last known position with the monotonic clock.
     * When an AudioTrack exists, its playback head is used instead: that is the real audio clock.
     */
    private var positionAnchorMs = 0L
    private var positionAnchorAt = 0L
    private var lastEpisodeProgressAt = 0L

    /**
     * Set while a newly started item has not been confirmed by the native player. The ticker
     * holds the bar at the requested position during that window: otherwise the UI clock runs
     * through fetch/buffer latency, and the first loading event (position 0) snaps it backwards.
     */
    private var awaitingPlaybackStart = false
    private var awaitingPlaybackStartAt = 0L

    /**
     * The target of the most recent local seek. Until the player reports a position near it, the
     * bar must ignore loading/position events from the previous offset: adopting them made a
     * large seek snap back and then crawl forward from the old position for minutes.
     */
    private data class SeekHold(val targetMs: Long, val atMs: Long)
    private var seekHold: SeekHold? = null

    init {
        session?.let {
            viewModelScope.launch {
                it.signedIn.collect { signed ->
                    _state.update { state -> state.copy(signedIn = signed) }
                    if (signed) loadAccount() else clearAccount()
                }
            }
            viewModelScope.launch {
                it.playback.buffering.collect { buffering ->
                    if (!buffering) anchorPosition(_playbackPositionMs.value)
                    _state.update { state -> state.copy(isBuffering = buffering) }
                }
            }
            viewModelScope.launch {
                it.settings.collect { settings ->
                    _state.update { state ->
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
                val item = _state.value.current
                if (item != null && session != null && item.service != ServiceId.Local) {
                    val resume = _playbackPositionMs.value
                    _state.update { state -> state.copy(isPlaying = true) }
                    startRemote(item, resume, resuming = resume > 0L)
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
                        if (online && !wasOnline && _state.value.signedIn) {
                            loadAccount()
                            if (libraryCategoryLoaded) loadLibrary(_state.value.filter)
                        }
                        wasOnline = online
                    }
                }
            }
        }
        restorePlaybackState()
        refreshCacheSize()
        if (session == null || !session.nativeAvailable) {
            if (simulatePlayback) {
                // Local fixtures and builds without the native library still need a moving progress bar.
                viewModelScope.launch {
                    // Suspend on the playing flag instead of polling: a paused player costs no wakeups.
                    while (true) {
                        _state.map { it.isPlaying }.first { it }
                        while (_state.value.isPlaying) {
                            delay(1_000L)
                            tick()
                        }
                    }
                }
            }
        } else {
            viewModelScope.launch {
                // Native events only matter while something is playing. Poll tight when active, back
                // off to a slow heartbeat when idle so this does not run forever on the main path.
                while (true) {
                    delay(if (_state.value.isPlaying) 300L else 3_000L)
                    val event = withContext(Dispatchers.IO) { session.playback.pollEvent() } ?: continue
                    handlePlaybackEvent(event)
                }
            }
            viewModelScope.launch {
                // The seek bar only needs to advance while it is on screen and playing. When the
                // app is backgrounded or paused this suspends on the flows instead of waking up.
                while (true) {
                    uiVisible.combine(_state) { visible, s ->
                        visible && s.isPlaying && !s.isBuffering
                    }.first { it }
                    while (uiVisible.value && _state.value.isPlaying && !_state.value.isBuffering) {
                        delay(POSITION_TICK_MS)
                        updatePositionTick()
                    }
                }
            }
        }
    }

    /** Adopts the process-wide player and queue after the Sidetrack screen was recreated. */
    private fun restorePlaybackState() {
        val session = session ?: return
        val playback = session.playback
        val snapshot = queue.snapshot
        val current = playback.current ?: snapshot.current
        if (current != null && snapshot.current?.id != current.id) {
            queue.restore(snapshot.copy(current = current))
        }
        val restored = queue.snapshot
        // A closed app leaves the item at the offset it reached; a live player owns its position.
        val resume = current?.let(::savedPositionFor) ?: 0L
        val position = if (playback.current != null) playback.position else resume
        anchorPosition(position)
        _playbackPositionMs.value = position
        _state.update {
            it.copy(
                current = current,
                upcoming = restored.upcoming,
                history = restored.history,
                shuffle = restored.shuffle,
                repeat = restored.repeat,
                isPlaying = playback.isActive,
                recent = history.items()
            )
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

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Shows a transient bar message; [kind] decides how long it stays. */
    fun notice(text: String, kind: NoticeKind = NoticeKind.Other) = _state.update { it.say(text, kind) }

    /** Dismisses the transient now-playing confirmation and returns the normal action bar. */
    fun dismissNowPlayingNotice() {
        nowPlayingNoticeJob?.cancel()
        nowPlayingNoticeJob = null
        _state.update { it.copy(nowPlayingNotice = null) }
    }

    /** Reloads the open Library category. Called whenever the Library screen is opened. */
    fun refreshLibrary() {
        if (!_state.value.signedIn) return
        loadLibrary(_state.value.filter)
    }

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

    /** Retries the open library category after a failed load. */
    fun retryLibrary() {
        if (!_state.value.signedIn) return
        loadLibrary(_state.value.filter)
    }

    fun collection(type: CollectionType, id: String): Collection? = collection(mediaKey(type, id))

    fun collection(key: MediaKey): Collection? = _state.value.collections[key.encode()]

    fun artist(id: String): Artist? = _state.value.artists[id]

    fun albumsBy(artist: Artist): List<Album> = _state.value.artistAlbums[artist.id].orEmpty()

    fun topTracks(artist: Artist): List<Track> = _state.value.artistTracks[artist.id].orEmpty()

    fun ensureCollection(type: CollectionType, id: String) = ensureCollection(mediaKey(type, id))

    fun ensureCollection(key: MediaKey) {
        val cacheKey = key.encode()
        val snapshot = _state.value
        if (cacheKey in snapshot.collections || cacheKey in snapshot.pendingCollections) return
        _state.update { it.copy(pendingCollections = it.pendingCollections + cacheKey) }
        viewModelScope.launch {
            try {
                val collection = catalog.collection(key, 0, CATALOG_PAGE_SIZE)
                val saved = if (collection == null) {
                    null
                } else {
                    runCatching { writer?.isSaved(key) }
                        .onFailure { SidetrackDebug.log("viewModel.ensureCollection key=$cacheKey saved lookup failed", it) }
                        .getOrNull()
                }
                _state.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    if (saved != null && cacheKey !in savedCollectionTouched) {
                        if (saved) keys.add(cacheKey) else keys.remove(cacheKey)
                    }
                    it.copy(
                        collections = if (collection == null) it.collections else it.collections + (cacheKey to collection),
                        savedCollectionKeys = keys,
                        pendingCollections = it.pendingCollections - cacheKey,
                        collectionErrors = it.collectionErrors - cacheKey
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SidetrackDebug.log("viewModel.ensureCollection key=$cacheKey failed", e)
                _state.update {
                    it.copy(
                        pendingCollections = it.pendingCollections - cacheKey,
                        collectionErrors = it.collectionErrors + (cacheKey to playbackNotice(e))
                    )
                }
            }
        }
    }

    /** Retries a collection that failed to load, keeping the screen on its fallback until it lands. */
    fun retryCollection(type: CollectionType, id: String) = retryCollection(mediaKey(type, id))

    fun retryCollection(key: MediaKey) {
        val cacheKey = key.encode()
        if (cacheKey in _state.value.pendingCollections) return
        _state.update { it.copy(collectionErrors = it.collectionErrors - cacheKey) }
        ensureCollection(key)
    }

    fun loadMoreCollection(type: CollectionType, id: String) = loadMoreCollection(mediaKey(type, id))

    fun loadMoreCollection(key: MediaKey) {
        val cacheKey = key.encode()
        val snapshot = _state.value
        val collection = snapshot.collections[cacheKey] ?: return
        if (!collection.hasMore || cacheKey in snapshot.collectionMorePending) return
        _state.update { it.copy(collectionMorePending = it.collectionMorePending + cacheKey) }
        viewModelScope.launch {
            try {
                val next = catalog.collection(key, collection.items.size, CATALOG_PAGE_SIZE)
                _state.update { state ->
                    val current = state.collections[cacheKey] ?: return@update state
                    if (next == null) {
                        state.copy(
                            collectionMorePending = state.collectionMorePending - cacheKey,
                            collections = state.collections + (cacheKey to current.copy(hasMore = false))
                        )
                    } else {
                        state.copy(
                            collectionMorePending = state.collectionMorePending - cacheKey,
                            collections = state.collections + (cacheKey to current.append(next))
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val current = it.collections[cacheKey]
                    it.copy(
                        collectionMorePending = it.collectionMorePending - cacheKey,
                        collections = if (current == null) it.collections else it.collections + (cacheKey to current.copy(hasMore = false))
                    ).say(playbackNotice(e))
                }
            }
        }
    }

    fun loadMoreArtistAlbums(artist: Artist) {
        val id = artist.id
        val snapshot = _state.value
        if (snapshot.artistAlbumHasMore[id] != true || id in snapshot.artistMorePending) return
        _state.update { it.copy(artistMorePending = it.artistMorePending + id) }
        viewModelScope.launch {
            try {
                val page = catalog.albums(artist.key, snapshot.artistAlbums[id].orEmpty().size, ARTIST_PAGE_SIZE)
                _state.update {
                    it.copy(
                        artistAlbums = it.artistAlbums + (id to (it.artistAlbums[id].orEmpty() + page.items).distinctBy { album -> album.id }),
                        artistAlbumHasMore = it.artistAlbumHasMore + (id to page.hasMore),
                        artistMorePending = it.artistMorePending - id
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        artistAlbumHasMore = it.artistAlbumHasMore + (id to false),
                        artistMorePending = it.artistMorePending - id
                    ).say(playbackNotice(e))
                }
            }
        }
    }

    fun loadMoreArtistTracks(artist: Artist) {
        val id = artist.id
        val snapshot = _state.value
        if (snapshot.artistTrackHasMore[id] != true || id in snapshot.artistMorePending) return
        _state.update { it.copy(artistMorePending = it.artistMorePending + id) }
        viewModelScope.launch {
            try {
                val page = catalog.topTracks(artist.key, snapshot.artistTracks[id].orEmpty().size, ARTIST_PAGE_SIZE)
                _state.update {
                    it.copy(
                        artistTracks = it.artistTracks + (id to (it.artistTracks[id].orEmpty() + page.items).distinctBy { track -> track.id }),
                        artistTrackHasMore = it.artistTrackHasMore + (id to page.hasMore),
                        artistMorePending = it.artistMorePending - id
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        artistTrackHasMore = it.artistTrackHasMore + (id to false),
                        artistMorePending = it.artistMorePending - id
                    ).say(playbackNotice(e))
                }
            }
        }
    }

    private suspend fun loadAllCollection(collection: Collection): Collection {
        var current = collection
        while (current.hasMore) {
            val next = catalog.collection(mediaKey(current.type, current.id), current.items.size, CATALOG_PAGE_SIZE) ?: break
            current = current.append(next)
            _state.update {
                it.copy(collections = it.collections + (collectionKey(current.type, current.id) to current))
            }
        }
        return current
    }

    private fun Collection.append(next: Collection): Collection = copy(
        items = items + next.items,
        totalItems = next.totalItems,
        hasMore = next.hasMore
    )

    fun ensureArtist(id: String) {
        val snapshot = _state.value
        if (id in snapshot.artists || id in snapshot.pendingArtists) return
        _state.update { it.copy(pendingArtists = it.pendingArtists + id) }
        viewModelScope.launch {
            try {
                val artist = catalog.artist(artistKey(id))
                if (artist == null) {
                    _state.update { it.copy(pendingArtists = it.pendingArtists - id) }
                    return@launch
                }
                val albums = catalog.albums(artist.key, 0, ARTIST_PAGE_SIZE)
                val tracks = catalog.topTracks(artist.key, 0, ARTIST_PAGE_SIZE)
                val followed = runCatching { writer?.isFollowed(artistKey(id)) }
                    .onFailure { SidetrackDebug.log("viewModel.ensureArtist id=$id followed lookup failed", it) }
                    .getOrNull()
                _state.update {
                    val ids = it.followedArtistIds.toMutableSet()
                    if (followed != null && id !in followedArtistTouched) {
                        if (followed) ids.add(id) else ids.remove(id)
                    }
                    it.copy(
                        artists = it.artists + (id to artist),
                        artistAlbums = it.artistAlbums + (id to albums.items.distinctBy { album -> album.id }),
                        artistTracks = it.artistTracks + (id to tracks.items.distinctBy { track -> track.id }),
                        artistAlbumHasMore = it.artistAlbumHasMore + (id to albums.hasMore),
                        artistTrackHasMore = it.artistTrackHasMore + (id to tracks.hasMore),
                        followedArtistIds = ids,
                        pendingArtists = it.pendingArtists - id,
                        artistErrors = it.artistErrors - id
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SidetrackDebug.log("viewModel.ensureArtist id=$id failed", e)
                _state.update {
                    it.copy(
                        pendingArtists = it.pendingArtists - id,
                        artistErrors = it.artistErrors + (id to playbackNotice(e))
                    )
                }
            }
        }
    }

    /** Retries an artist that failed to load. */
    fun retryArtist(id: String) {
        if (id in _state.value.pendingArtists) return
        _state.update { it.copy(artistErrors = it.artistErrors - id) }
        ensureArtist(id)
    }

    fun play(items: List<MediaItem>, index: Int) {
        if (index !in items.indices) return
        val item = items[index]
        if (!readyToPlay(item)) return
        if (_state.value.current?.id == item.id) {
            if (!_state.value.isPlaying) togglePlayPause()
            _state.update { it.copy(notice = null) }
            return
        }
        beginPlayback(queue.play(items, index), item)
    }

    fun playItem(item: MediaItem) = play(listOf(item), 0)

    /**
     * Plays one track picked from a list row (Library, Search) without discarding the queue.
     *
     * A row tap is a request to hear that track now, not a request to throw away the tail the
     * user built with Add to queue, so unlike [play] this keeps Up next exactly as it was and
     * only replaces the current item. Collection, album and artist pages still use [play] and
     * [playCollection], where the list is the context worth queueing.
     */
    fun playNow(item: MediaItem) {
        if (!readyToPlay(item)) return
        if (_state.value.current?.id == item.id) {
            if (!_state.value.isPlaying) togglePlayPause()
            _state.update { it.copy(notice = null) }
            return
        }
        beginPlayback(queue.playNow(item), item)
    }

    /** Guards every play entry point, so a tap that cannot start keeps the queue untouched. */
    private fun readyToPlay(item: MediaItem): Boolean {
        if (!item.playable) {
            notice("Not available on this account")
            return false
        }
        return true
    }

    /** Commits a queue snapshot to the screen and starts its current item on the service. */
    private fun beginPlayback(snapshot: QueueRepository.Snapshot, item: MediaItem) {
        if (item is Track) ensureTrackSaved(item.id)
        recorded(item)
        val resume = itemStartPosition(item)
        applyQueue(snapshot, positionMs = resume, playing = true)
        _state.update { it.copy(notice = null) }
        showNowPlayingNotice(item)
        startRemote(item, resume, resuming = resume > 0L)
    }

    /**
     * Plays one episode from a show page. A show is not a setlist: its list is newest-first, so
     * [playCollection] would queue the whole archive behind the tapped episode, autoplay backwards
     * through it, and fetch every page before the first second of audio. Lining up more is the
     * row's Add to queue square, so this stays a single-item queue.
     */
    fun playEpisode(episode: Episode) = playItem(episode)

    /**
     * Builds a station from the current track, queues it behind the still-playing seed, and makes
     * it available to the Song radio screen. Revisiting the same seed is a no-op.
     */
    fun ensureRadio() {
        val seed = _state.value.current as? Track
        if (seed == null) {
            _state.update {
                it.copy(radio = null, radioSeedId = null, radioPending = false, radioError = "Nothing playing")
            }
            return
        }
        if (radioJob?.isActive == true && _state.value.radioSeedId == seed.id) return
        if (_state.value.radioSeedId == seed.id && _state.value.radio != null) return
        loadRadio(seed)
    }

    fun retryRadio() {
        radioJob?.cancel()
        _state.update { it.copy(radio = null, radioSeedId = null, radioError = null) }
        ensureRadio()
    }

    fun playRadio(index: Int) {
        val radio = _state.value.radio ?: return
        play(radio.items, index)
    }

    /**
     * Builds the artist's station for the Artist radio screen. Revisiting the same artist is a
     * no-op, and unlike song radio the station never touches the queue on its own: the user starts
     * it from the station screen, so opening the screen cannot interrupt what is playing.
     */
    fun ensureArtistRadio(artist: Artist) {
        val id = artist.id
        val snapshot = _state.value
        if (artistRadioJob?.isActive == true && snapshot.artistRadioArtistId == id) return
        if (snapshot.artistRadioArtistId == id && snapshot.artistRadio != null) return
        loadArtistRadio(artist)
    }

    fun retryArtistRadio(artist: Artist) {
        artistRadioJob?.cancel()
        _state.update { it.copy(artistRadio = null, artistRadioArtistId = null, artistRadioError = null) }
        ensureArtistRadio(artist)
    }

    fun playArtistRadio(index: Int) {
        val station = _state.value.artistRadio ?: return
        play(station.items, index)
    }

    /**
     * Adopts the library state of a station's backing playlist, unless the user already toggled
     * the tile on this screen. Song radio and artist radio store their station in different
     * fields but resolve the saved state the same way.
     */
    private fun SidetrackUiState.withStationSaved(
        station: Collection,
        saved: Boolean?
    ): SidetrackUiState {
        if (saved == null) return this
        val key = collectionKey(CollectionType.Playlist, station.id)
        if (key in savedCollectionTouched) return this
        val keys = savedCollectionKeys.toMutableSet()
        if (saved) keys.add(key) else keys.remove(key)
        return copy(savedCollectionKeys = keys)
    }

    private fun loadArtistRadio(artist: Artist) {
        artistRadioJob?.cancel()
        val id = artist.id
        _state.update {
            it.copy(artistRadio = null, artistRadioArtistId = id, artistRadioPending = true, artistRadioError = null)
        }
        artistRadioJob = viewModelScope.launch {
            try {
                val result = catalog.radioStation(artistKey(id), CATALOG_PAGE_SIZE)
                val tracks = result.tracks
                    .filter { it.playable }
                    .distinctBy { it.id }
                // A station the user navigated away from must not overwrite the next artist's.
                if (_state.value.artistRadioArtistId != id) return@launch
                if (tracks.isEmpty()) {
                    _state.update {
                        it.copy(artistRadioPending = false, artistRadioError = "No similar tracks found")
                    }
                    return@launch
                }
                val station = Collection(
                    type = CollectionType.Playlist,
                    id = result.playlistId ?: "artistradio:$id",
                    title = "${artist.name} radio",
                    subtitle = "Based on ${artist.name} · ${tracks.size} tracks",
                    artRes = artist.artRes,
                    items = tracks,
                    artUrl = artist.artUrl,
                    totalItems = tracks.size,
                    service = service,
                    saveable = result.playlistId != null
                )
                val saved = result.playlistId?.let {
                    runCatching { writer?.isSaved(mediaKey(CollectionType.Playlist, it)) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        artistRadio = station,
                        artistRadioPending = false,
                        artistRadioError = null
                    ).withStationSaved(station, saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.artistRadioArtistId == id) {
                    _state.update {
                        it.copy(artistRadioPending = false, artistRadioError = playbackNotice(e))
                    }
                }
            }
        }
    }

    private fun loadRadio(seed: Track) {
        radioJob?.cancel()
        // The station request can take seconds. Remember the queue it was started from so a late
        // response cannot replace edits the user made while it loaded.
        val queueAtRequest = queue.snapshot
        _state.update {
            it.copy(radio = null, radioSeedId = seed.id, radioPending = true, radioError = null)
        }
        radioJob = viewModelScope.launch {
            try {
                val result = catalog.radioStation(trackKey(seed.id), CATALOG_PAGE_SIZE)
                val tracks = result.tracks
                    .filter { it.playable && it.id != seed.id }
                    .distinctBy { it.id }
                if (_state.value.current?.id != seed.id) {
                    _state.update {
                        it.copy(radioPending = false, radioError = "The track changed — try again")
                    }
                    return@launch
                }
                if (tracks.isEmpty()) {
                    _state.update { it.copy(radioPending = false, radioError = "No similar tracks found") }
                    return@launch
                }
                val station = Collection(
                    type = CollectionType.Playlist,
                    id = result.playlistId ?: "radio:${seed.id}",
                    title = "Song radio",
                    subtitle = "Based on ${seed.title} · ${tracks.size} tracks",
                    artRes = seed.artRes,
                    items = tracks,
                    artUrl = seed.artUrl,
                    totalItems = tracks.size,
                    service = service,
                    saveable = result.playlistId != null
                )
                val saved = result.playlistId?.let {
                    runCatching { writer?.isSaved(mediaKey(CollectionType.Playlist, it)) }.getOrNull()
                }
                _state.update {
                    it.copy(
                        radio = station,
                        radioSeedId = seed.id,
                        radioPending = false,
                        radioError = null
                    ).withStationSaved(station, saved)
                }
                // The seed keeps playing; the station becomes what follows it. Only do that if
                // the queue is still the one this request started from. A late response must not
                // overwrite a track the user added, removed, cleared or reordered meanwhile.
                val queueNow = queue.snapshot
                if (
                    queueNow.current?.id == seed.id &&
                    queueNow.upcoming == queueAtRequest.upcoming &&
                    queueNow.shuffle == queueAtRequest.shuffle
                ) {
                    applyQueue(queue.play(listOf(seed) + tracks, 0), syncNowPlaying = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.radioSeedId == seed.id) {
                    _state.update {
                        it.copy(
                            radioPending = false,
                            radioError = if (it.current?.id == seed.id) {
                                playbackNotice(e)
                            } else {
                                "The track changed — try again"
                            }
                        )
                    }
                }
            }
        }
    }

    fun playCollection(collection: Collection, index: Int) {
        if (!collection.hasMore) {
            play(collection.items, index)
            return
        }
        val key = collectionKey(collection.type, collection.id)
        _state.update { it.copy(collectionMorePending = it.collectionMorePending + key) }
        viewModelScope.launch {
            try {
                play(loadAllCollection(collection).items, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.say(playbackNotice(e)) }
            } finally {
                _state.update { it.copy(collectionMorePending = it.collectionMorePending - key) }
            }
        }
    }

    /**
     * Plays the artist's whole top-tracks list. Pages still behind "See more tracks" are fetched
     * first so the queue always matches the list the page shows, instead of whichever page the
     * user happened to have expanded when they pressed play.
     */
    fun playArtist(artist: Artist) {
        val id = artist.id
        if (id in _state.value.artistMorePending) return
        _state.update { it.copy(artistMorePending = it.artistMorePending + id) }
        viewModelScope.launch {
            try {
                val tracks = loadAllArtistTracks(artist)
                if (tracks.isEmpty()) {
                    notice("No top tracks for this artist")
                    return@launch
                }
                play(tracks, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.say(playbackNotice(e)) }
            } finally {
                _state.update { it.copy(artistMorePending = it.artistMorePending - id) }
            }
        }
    }

    private suspend fun loadAllArtistTracks(artist: Artist): List<Track> {
        val id = artist.id
        var tracks = topTracks(artist)
        var hasMore = _state.value.artistTrackHasMore[id] == true
        while (hasMore) {
            val page = catalog.topTracks(artist.key, tracks.size, ARTIST_PAGE_SIZE)
            tracks = (tracks + page.items).distinctBy { track -> track.id }
            hasMore = page.hasMore
            val updated = tracks
            val more = hasMore
            _state.update {
                it.copy(
                    artistTracks = it.artistTracks + (id to updated),
                    artistTrackHasMore = it.artistTrackHasMore + (id to more)
                )
            }
        }
        return tracks
    }

    /** Play a recent item in context, so the rest of the history follows it in the queue. */
    fun playRecent(item: MediaItem) {
        val recent = _state.value.recent
        val at = recent.indexOfFirst { it.id == item.id }
        if (at >= 0) play(recent, at) else playItem(item)
    }

    fun togglePlayPause() {
        val s = _state.value
        val item = s.current ?: return
        val play = !s.isPlaying
        // A paused seek has been delivered by now; let the next event realign the bar again.
        seekHold = null
        recordEpisodeProgress(force = true)
        anchorPosition(_playbackPositionMs.value)
        _state.update { it.copy(isPlaying = play) }
        if (session == null || item.service == ServiceId.Local) return
        viewModelScope.launch {
            try {
                if (play && session.playback.current == null) {
                    // A restored queue outlives the native player. Start the current item instead
                    // of resuming a source that no longer exists, from the offset it stopped at.
                    val resume = savedPositionFor(item)
                    startRemote(item, resume, resuming = resume > 0L)
                } else if (play) {
                    if (!session.resume()) {
                        _state.update { it.copy(isPlaying = false) }
                        return@launch
                    }
                } else {
                    session.pause()
                }
                session.playback.updateState(play, _playbackPositionMs.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isPlaying = false).say(playbackNotice(e)) }
            }
        }
    }

    fun next() {
        val before = _state.value.current
        val snapshot = queue.next(auto = false)
        if (snapshot.current !== before) {
            val next = snapshot.current
            val resume = next?.let(::itemStartPosition) ?: 0L
            next?.let(::recorded)
            applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, resuming = resume > 0L) }
        }
    }

    fun previous() {
        val s = _state.value
        if (s.current == null) return
        // A podcast's Previous means the previous episode. Rewinding belongs to the ±30 s
        // controls, so the track rule (restart after three seconds) does not apply here.
        if (s.current is Episode) {
            val snapshot = queue.previous(0L)
            if (snapshot.current !== s.current) {
                val previous = snapshot.current
                val resume = previous?.let(::itemStartPosition) ?: 0L
                previous?.let(::recorded)
                applyQueue(snapshot, positionMs = resume, playing = previous?.playable == true)
                previous?.let { startRemote(it, resume, resuming = resume > 0L) }
            } else {
                seekTo(0L)
            }
            return
        }
        if (_playbackPositionMs.value > 3_000L) {
            _playbackPositionMs.value = 0L
            if (s.current?.service != ServiceId.Local) {
                session?.let { session ->
                    viewModelScope.launch { runCatching { session.seek(0L) } }
                }
            }
            return
        }
        val snapshot = queue.previous(_playbackPositionMs.value)
        if (snapshot.current !== s.current) {
            val previous = snapshot.current
            val resume = previous?.let(::itemStartPosition) ?: 0L
            previous?.let(::recorded)
            applyQueue(snapshot, positionMs = resume, playing = previous?.playable == true)
            previous?.let { startRemote(it, resume, resuming = resume > 0L) }
        } else {
            _playbackPositionMs.value = 0L
            if (s.current?.service != ServiceId.Local) {
                session?.let { session ->
                    viewModelScope.launch { runCatching { session.seek(0L) } }
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val current = _state.value.current ?: return
        val target = positionMs.coerceIn(0L, current.durationMs)
        val now = SystemClock.elapsedRealtime()
        SidetrackDebug.log("viewModel.seekTo target=$target current=${_playbackPositionMs.value}")
        seekHold = SeekHold(target, now)
        anchorPosition(target)
        _playbackPositionMs.value = target
        recordEpisodeProgress(force = true)
        if (session != null && current.service != ServiceId.Local) {
            viewModelScope.launch { runCatching { session.seek(target) } }
        }
    }

    /** Podcast rewind. [seekTo] clamps to the item's duration and never goes below zero. */
    fun skipBack(milliseconds: Long = PODCAST_SKIP_MS) {
        seekTo(_playbackPositionMs.value - milliseconds)
    }

    /** Podcast fast-forward. [seekTo] clamps to the item's duration. */
    fun skipForward(milliseconds: Long = PODCAST_SKIP_MS) {
        seekTo(_playbackPositionMs.value + milliseconds)
    }

    fun toggleShuffle() = applyQueue(queue.toggleShuffle(), syncNowPlaying = false)

    fun cycleRepeat() = applyQueue(queue.cycleRepeat(), syncNowPlaying = false)

    fun toggleSaveCurrent() {
        val track = _state.value.current as? Track ?: return
        toggleTrackSaved(track.id, track)
    }

    fun toggleSaved(track: Track) = toggleTrackSaved(track.id, track)

    private fun toggleTrackSaved(
        id: String,
        track: Track?
    ) {
        val savedKey = trackKey(id).encode()
        val wasSaved = savedKey in _state.value.savedTrackIds
        val saved = !wasSaved
        savedTrackTouched += savedKey
        _state.update {
            val ids = it.savedTrackIds.toMutableSet()
            if (saved) ids.add(savedKey) else ids.remove(savedKey)
            var next = it.copy(savedTrackIds = ids)
            next = if (saved) {
                track?.let { item -> next.withLibraryItemAdded(LibraryFilter.LikedSongs, item) } ?: next
            } else {
                next.withLibraryItemRemoved(LibraryFilter.LikedSongs, id)
            }
            next
        }
        val writer = writer ?: return
        viewModelScope.launch {
            try {
                writer.setSaved(trackKey(id), saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val ids = it.savedTrackIds.toMutableSet()
                    if (wasSaved) ids.add(savedKey) else ids.remove(savedKey)
                    var next = it.copy(savedTrackIds = ids).say(playbackNotice(e))
                    next = if (wasSaved) {
                        track?.let { item -> next.withLibraryItemAdded(LibraryFilter.LikedSongs, item) } ?: next
                    } else {
                        next.withLibraryItemRemoved(LibraryFilter.LikedSongs, id)
                    }
                    next
                }
            }
        }
    }

    fun savedCollection(collection: Collection): Boolean = collectionKey(collection.type, collection.id) in _state.value.savedCollectionKeys

    fun toggleSaved(collection: Collection) = toggleSaved(collection.type, collection.id, collection)

    private fun toggleSaved(type: CollectionType, id: String, collection: Collection) {
        val key = collectionKey(type, id)
        val wasSaved = key in _state.value.savedCollectionKeys
        val saved = !wasSaved
        val addedItem = libraryItemFor(collection)
        savedCollectionTouched += key
        _state.update {
            val keys = it.savedCollectionKeys.toMutableSet()
            if (saved) keys.add(key) else keys.remove(key)
            val next = it.copy(savedCollectionKeys = keys)
            if (saved) {
                addedItem?.let { item -> next.withLibraryItemAdded(libraryFilterFor(type), item) } ?: next
            } else {
                next.withLibraryItemRemoved(libraryFilterFor(type), id)
            }
        }
        val writer = writer ?: return
        viewModelScope.launch {
            try {
                writer.setSaved(mediaKey(type, id), saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    if (wasSaved) keys.add(key) else keys.remove(key)
                    val next = it.copy(savedCollectionKeys = keys).say(playbackNotice(e))
                    if (wasSaved) {
                        addedItem?.let { item -> next.withLibraryItemAdded(libraryFilterFor(type), item) } ?: next
                    } else {
                        next.withLibraryItemRemoved(libraryFilterFor(type), id)
                    }
                }
            }
        }
    }

    fun followed(artist: Artist): Boolean = artistKey(artist.id).encode() in _state.value.followedArtistIds

    fun toggleFollow(artist: Artist) {
        val id = artist.id
        val followedKey = artistKey(id).encode()
        val wasFollowing = followedKey in _state.value.followedArtistIds
        val following = !wasFollowing
        followedArtistTouched += followedKey
        _state.update {
            val ids = it.followedArtistIds.toMutableSet()
            if (following) ids.add(followedKey) else ids.remove(followedKey)
            val next = it.copy(followedArtistIds = ids)
            if (following) {
                next.withLibraryItemAdded(LibraryFilter.Artists, artist)
            } else {
                next.withLibraryItemRemoved(LibraryFilter.Artists, id)
            }
        }
        val writer = writer ?: return
        viewModelScope.launch {
            try {
                writer.setFollowed(artistKey(id), following)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val ids = it.followedArtistIds.toMutableSet()
                    if (wasFollowing) ids.add(followedKey) else ids.remove(followedKey)
                    val next = it.copy(followedArtistIds = ids).say(playbackNotice(e))
                    if (wasFollowing) {
                        next.withLibraryItemAdded(LibraryFilter.Artists, artist)
                    } else {
                        next.withLibraryItemRemoved(LibraryFilter.Artists, id)
                    }
                }
            }
        }
    }

    /**
     * Reads a track's saved state when it first becomes relevant: starting it, or opening its
     * row's gutter. Idempotent, so a list can call it on every reveal.
     */
    fun ensureTrackSaved(id: String) {
        val writer = writer ?: return
        val savedKey = trackKey(id).encode()
        if (savedKey in _state.value.savedTrackIds || savedKey in savedTrackTouched) return
        viewModelScope.launch {
            val saved = runCatching { writer.isSaved(trackKey(id)) }.getOrNull() ?: return@launch
            if (saved && savedKey !in savedTrackTouched) {
                _state.update { it.copy(savedTrackIds = it.savedTrackIds + savedKey) }
            }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        // A category the service cannot fill is not a choice: the row disables it, and this guard
        // keeps a programmatic or restored selection from landing on an empty page.
        val supported = filter.takeIf { it in supportedFilters } ?: supportedFilters.firstOrNull() ?: LibraryFilter.LikedSongs
        _state.update {
            if (it.filter == supported) {
                it.copy(libraryLoading = true)
            } else {
                it.copy(
                    filter = supported,
                    libraryItems = emptyList(),
                    libraryHasMore = false,
                    libraryLoading = true
                )
            }
        }
        loadLibrary(supported)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        search(debounceMs = 250L)
    }

    fun clearQuery() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                query = "",
                searchResults = SearchResults(),
                searchPending = false,
                searchError = null,
                searchHasMore = emptySet(),
                searchMorePending = emptySet()
            )
        }
    }

    fun toggleScope(scope: SearchScope) {
        // A category the service cannot answer is not a choice either: the scope list disables
        // it, and this guard stops a stored or programmatic call from turning it back on.
        if (scope !in supportedScopes) return
        val current = _state.value.searchScopes
        val next = toggledSearchScopes(current, scope)
        if (next == current) return
        _state.update { it.copy(searchScopes = next) }
        search(debounceMs = 0L)
    }

    fun setAudioQuality(quality: AudioQuality) {
        if (quality == _state.value.audioQuality) return
        _state.update { it.copy(audioQuality = quality) }
        session?.setAudioQuality(quality)
    }

    fun toggleNormalization() {
        val enabled = !_state.value.normalization
        _state.update { it.copy(normalization = enabled) }
        session?.setNormalization(enabled)
    }

    fun toggleGapless() {
        val enabled = !_state.value.gapless
        _state.update { it.copy(gapless = enabled) }
        session?.setGapless(enabled)
    }

    fun toggleAutoplay() {
        val enabled = !_state.value.autoplay
        _state.update { it.copy(autoplay = enabled) }
        session?.setAutoplay(enabled)
    }

    fun clearHistory(): Boolean {
        clearedHistory = history.clear()
        val snapshot = queue.clearHistory()
        applyQueue(snapshot, syncNowPlaying = false)
        val cleared = clearedHistory.isNotEmpty()
        // A real clear is confirmed by the Undo offer and the dropping count; an empty list would
        // otherwise look like a dead key.
        _state.update {
            if (cleared) it.copy(recent = emptyList()) else it.say("Nothing to clear")
        }
        return cleared
    }

    /** Puts the last cleared history back, timestamps included, so Undo restores the real order. */
    fun undoClearHistory() {
        if (clearedHistory.isEmpty()) return
        history.restoreAll(clearedHistory)
        clearedHistory = emptyList()
        _state.update { it.copy(recent = history.items(), notice = null) }
    }

    fun clearCache() {
        clearArtworkCache()
        session?.clearAudioCache()
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        _state.update { it.copy(cacheSizeBytes = session?.cacheSizeBytes() ?: 0L) }
    }

    /**
     * Removes one upcoming occurrence by index and returns where it was, so the caller can offer
     * Undo. The index keeps duplicated tracks from taking each other with them.
     */
    fun removeUpcoming(index: Int): Int {
        val at = queue.removeUpcoming(index)
        if (at >= 0) applyQueue(queue.snapshot, syncNowPlaying = false)
        return at
    }

    fun restoreRemovedUpcoming(index: Int, item: MediaItem) = applyQueue(queue.restoreRemovedUpcoming(index, item), syncNowPlaying = false)

    /** Drag-and-drop reorder of the upcoming tail; the current item and history never move. */
    fun moveUpcoming(from: Int, to: Int) {
        applyQueue(queue.moveUpcoming(from, to), syncNowPlaying = false)
    }

    /** The user kept the hand-picked order: shuffle no longer describes it. */
    fun finishReorder() {
        applyQueue(queue.finishReorder(), syncNowPlaying = false)
    }

    /** Removes one history entry and returns the action that puts it back, if it was there. */
    fun removeHistory(item: MediaItem): (() -> Unit)? {
        val removed = history.removeEntry(item) ?: return null
        val queueIndex = queue.snapshot.history.indexOfFirst { it.id == item.id }
        applyQueue(queue.removeHistory(item), syncNowPlaying = false)
        _state.update { it.copy(recent = history.items()) }
        return {
            history.restore(removed)
            applyQueue(queue.restoreHistory(queueIndex, item), syncNowPlaying = false)
            _state.update { it.copy(recent = history.items()) }
        }
    }

    fun addToQueue(item: MediaItem) {
        applyQueue(queue.addToQueue(item), syncNowPlaying = false)
        // Queueing is silent in the player, so the bar is the only confirmation the action landed
        // — especially before anything is playing, when there is no Now Playing screen to check.
        _state.update { it.say("Added ${item.title} to queue", NoticeKind.Success) }
    }

    /**
     * Queues a whole collection. Paged collections are resolved first so the queue always matches
     * the list the page represents, and the items land as one batch instead of one commit and one
     * queue-file write per track. If a page cannot be loaded, nothing is queued rather than a
     * partial playlist.
     */
    fun queueCollection(collection: Collection) {
        if (!collection.hasMore) {
            addToQueue(collection.items)
            return
        }
        val key = collectionKey(collection.type, collection.id)
        if (key in _state.value.collectionMorePending) {
            _state.update { it.say("This collection is still loading — try again") }
            return
        }
        _state.update { it.copy(collectionMorePending = it.collectionMorePending + key) }
        viewModelScope.launch {
            try {
                val complete = loadAllCollection(collection)
                if (complete.hasMore) {
                    _state.update { it.say("Couldn't load the whole collection — nothing added") }
                    return@launch
                }
                addToQueue(complete.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.say(playbackNotice(e)) }
            } finally {
                _state.update { it.copy(collectionMorePending = it.collectionMorePending - key) }
            }
        }
    }

    /**
     * Queues an album straight from a library row. The album's tracks are not loaded until its page
     * opens, so the collection is fetched first and queued as one complete batch.
     */
    fun queueAlbum(album: Album) = queueLibraryCollection(album.key, "album")

    /** Playlist pages are paged too, so the same complete-collection rule applies. */
    fun queuePlaylist(playlist: Playlist) = queueLibraryCollection(playlist.key, "playlist")

    /** Queues the newest episode from a show row without opening the show page. */
    fun queueLatestEpisode(show: Show) {
        val latest = show.episodes.firstOrNull()
        if (latest == null) {
            _state.update { it.say("No episodes to queue yet") }
            return
        }
        addToQueue(latest)
    }

    private fun queueLibraryCollection(key: MediaKey, noun: String) {
        val cacheKey = key.encode()
        _state.value.collections[cacheKey]?.let { cached ->
            queueCollection(cached)
            return
        }
        if (cacheKey in _state.value.collectionMorePending) {
            _state.update { it.say("This $noun is still loading — try again") }
            return
        }
        _state.update { it.copy(collectionMorePending = it.collectionMorePending + cacheKey) }
        viewModelScope.launch {
            try {
                val first = catalog.collection(key, 0, CATALOG_PAGE_SIZE)
                if (first == null) {
                    _state.update { it.say("Nothing to queue") }
                    return@launch
                }
                val complete = loadAllCollection(first)
                if (complete.hasMore) {
                    _state.update { it.say("Couldn't load the whole $noun — nothing added") }
                    return@launch
                }
                addToQueue(complete.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.say(playbackNotice(e)) }
            } finally {
                _state.update { it.copy(collectionMorePending = it.collectionMorePending - cacheKey) }
            }
        }
    }

    /** Appends a batch with one queue commit; the collection screen uses this after paging. */
    fun addToQueue(items: List<MediaItem>) {
        if (items.isEmpty()) {
            _state.update { it.say("Nothing to queue") }
            return
        }
        applyQueue(queue.addToQueue(items), syncNowPlaying = false)
        _state.update {
            it.say(
                "Added ${items.size} ${if (items.size == 1) "track" else "tracks"} to queue",
                NoticeKind.Success
            )
        }
    }

    fun clearUpcoming(): List<MediaItem> {
        val removed = queue.clearUpcoming()
        applyQueue(queue.snapshot, syncNowPlaying = false)
        return removed
    }

    fun restoreUpcoming(items: List<MediaItem>) = applyQueue(queue.restoreUpcoming(items), syncNowPlaying = false)

    fun jumpTo(index: Int) {
        val s = _state.value
        val snapshot = queue.jumpTo(index)
        if (snapshot.current !== s.current) {
            val next = snapshot.current
            val resume = next?.let(::itemStartPosition) ?: 0L
            next?.let(::recorded)
            applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.takeIf { it.playable }?.let(::showNowPlayingNotice)
            next?.let { startRemote(it, resume, resuming = resume > 0L) }
        }
    }

    fun signOut() {
        session?.signOut()
        if (session == null) clearArtworkCache()
        queue.stop()
        _state.update { SidetrackUiState(signedIn = false, recent = history.items()) }
        refreshCacheSize()
    }

    fun signIn() {
        val openBrowser = openBrowser
        if (session == null || openBrowser == null) {
            _state.update { it.say("Sign-in is unavailable in this build") }
            return
        }
        startSignIn(AuthInput.Browser(openBrowser))
    }

    /** A self-hosted flow: server URL, username and password instead of a browser. */
    fun signIn(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank() || username.isBlank()) {
            _state.update { it.say("Enter the server and username") }
            return
        }
        // The password stays out of this check: a service can have accounts without one (the
        // Jellyfin demo does). The provider knows which fields it needs and names the missing one,
        // so the form does not refuse a valid sign-in the provider would accept.
        startSignIn(AuthInput.Password(serverUrl.trim(), username.trim(), password))
    }

    private fun startSignIn(input: AuthInput) {
        val session = session ?: run {
            _state.update { it.say("Sign-in is unavailable in this build") }
            return
        }
        // A previous attempt can still be waiting on the loopback listener after the user
        // closed the browser. Supersede it so every press opens a fresh sign-in page instead of
        // being ignored until the old listener times out.
        signInJob?.cancel()
        _state.update { it.copy(authPending = true, notice = null) }
        signInJob = viewModelScope.launch {
            try {
                session.signIn(input)
                _state.update { it.copy(signedIn = true, authPending = false) }
                loadAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CredentialPersistenceException) {
                // The session is live for this run; only keeping it for the next launch failed.
                _state.update { it.copy(signedIn = true, authPending = false).say(e.message ?: "Sign-in failed") }
                loadAccount()
            } catch (e: Throwable) {
                // Sign-in runs on the main thread and touches the keystore, the browser and the
                // network. Anything that escapes here used to kill the process; report it instead.
                Log.e("SidetrackSignIn", "sign-in threw ${e::class.java.name}", e)
                _state.update {
                    it.copy(authPending = false).say(e.message ?: "Sign-in failed")
                }
            }
        }
    }

    private fun loadAccount() {
        if (!_state.value.signedIn) return
        _state.update {
            // Recently played is local-only: no first-party history endpoint, no Web API quota.
            it.copy(recent = history.items())
        }
        loadLibrarySections()
    }

    /**
     * Loads a short preview of every library category after sign-in. The categories are
     * requested one after another so the native collection cache is filled once for the shared
     * sets instead of racing several identical full reads. The previews also seed saved-state
     * hints and let an offline app know what the account holds.
     */
    private fun loadLibrarySections() {
        if (!_state.value.signedIn) return
        librarySectionsJob?.cancel()
        _state.update { it.copy(librarySectionsLoading = true, librarySectionsError = null) }
        librarySectionsJob = viewModelScope.launch {
            try {
                val sections = LinkedHashMap<LibraryFilter, List<Any>>()
                val hasMore = mutableSetOf<LibraryFilter>()
                var notice: String? = null
                for (filter in supportedFilters) {
                    try {
                        val page = catalog.library(filter.section(), 0, LIBRARY_SECTION_SIZE + 1)
                        sections[filter] = page.items.take(LIBRARY_SECTION_SIZE)
                        // Prefer the category total over the delivered rows: a catalog may drop an
                        // entry whose metadata failed to load, and an item-based check would then
                        // hide "See more" for a category that still has more rows.
                        val count = page.total ?: page.items.size
                        if (page.hasMore || count > LIBRARY_SECTION_SIZE) hasMore += filter
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One failing category should not hide the rest of the library.
                        if (notice == null) notice = playbackNotice(e)
                    }
                }
                _state.update { current ->
                    val allFailed = sections.isEmpty()
                    var next = current.copy(
                        librarySections = sections,
                        librarySectionHasMore = hasMore,
                        librarySectionsLoading = false,
                        librarySectionsError = if (allFailed) notice else null
                    )
                    // A full failure is shown as an error page, not as a transient notice.
                    if (!allFailed && notice != null) next = next.say(notice)
                    for ((filter, items) in sections) {
                        next = next.withSectionSaved(filter, items)
                    }
                    next
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        librarySectionsLoading = false,
                        librarySectionsError = playbackNotice(e)
                    )
                }
            }
        }
    }

    private fun loadLibrary(filter: LibraryFilter) {
        if (!_state.value.signedIn) return
        libraryCategoryLoaded = true
        libraryJob?.cancel()
        _state.update { it.copy(libraryLoading = true, libraryMorePending = false, libraryError = null) }
        libraryJob = viewModelScope.launch {
            try {
                val page = catalog.library(filter.section(), 0, CATALOG_PAGE_SIZE)
                _state.update {
                    if (it.filter == filter) {
                        it.withLibrarySaved(filter, page.items).copy(
                            libraryItems = page.items,
                            libraryHasMore = page.hasMore,
                            libraryLoading = false,
                            libraryError = null
                        )
                    } else {
                        it
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val message = playbackNotice(e)
                    val empty = it.libraryItems.isEmpty()
                    val next = it.copy(
                        libraryLoading = false,
                        libraryError = if (empty) message else null
                    )
                    if (empty) next else next.say(message)
                }
            }
        }
    }

    fun loadMoreLibrary() {
        val snapshot = _state.value
        if (!snapshot.libraryHasMore || snapshot.libraryMorePending || snapshot.libraryLoading) return
        val filter = snapshot.filter
        val offset = snapshot.libraryItems.size
        _state.update { it.copy(libraryMorePending = true) }
        viewModelScope.launch {
            try {
                val page = catalog.library(filter.section(), offset, CATALOG_PAGE_SIZE)
                _state.update {
                    if (it.filter != filter) {
                        it.copy(libraryMorePending = false)
                    } else {
                        val merged = (it.libraryItems + page.items).distinctBy(::libraryItemKey)
                        it.withLibrarySaved(filter, merged).copy(
                            libraryItems = merged,
                            libraryHasMore = page.hasMore,
                            libraryMorePending = false
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(libraryMorePending = false, libraryHasMore = false).say(playbackNotice(e))
                }
            }
        }
    }

    private fun clearAccount() {
        queue.stop()
        history.clear()
        librarySectionsJob?.cancel()
        libraryJob?.cancel()
        radioJob?.cancel()
        libraryCategoryLoaded = false
        savedTrackTouched.clear()
        savedCollectionTouched.clear()
        followedArtistTouched.clear()
        _state.update {
            it.copy(
                recent = emptyList(),
                libraryItems = emptyList(),
                librarySections = emptyMap(),
                librarySectionHasMore = emptySet(),
                librarySectionsLoading = false,
                librarySectionsError = null,
                libraryError = null,
                searchError = null,
                collectionErrors = emptyMap(),
                artistErrors = emptyMap(),
                libraryHasMore = false,
                libraryMorePending = false,
                searchResults = SearchResults(),
                searchHasMore = emptySet(),
                searchMorePending = emptySet(),
                collections = emptyMap(),
                collectionMorePending = emptySet(),
                artistAlbumHasMore = emptyMap(),
                artistTrackHasMore = emptyMap(),
                artistMorePending = emptySet(),
                artists = emptyMap(),
                artistAlbums = emptyMap(),
                artistTracks = emptyMap(),
                savedTrackIds = emptySet(),
                savedCollectionKeys = emptySet(),
                followedArtistIds = emptySet(),
                nowPlayingNotice = null,
                radio = null,
                radioSeedId = null,
                radioPending = false,
                radioError = null
            )
        }
    }

    /** Adds preview items to the known saved state without discarding items from other pages. */
    private fun SidetrackUiState.withSectionSaved(filter: LibraryFilter, items: List<Any>): SidetrackUiState = when (filter) {
        LibraryFilter.LikedSongs -> copy(
            savedTrackIds = savedTrackIds + items.filterIsInstance<Track>().map { trackKey(it.id).encode() }
        )

        LibraryFilter.Albums -> copy(
            savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<Album>()
                .map { collectionKey(CollectionType.Album, it.id) }
        )

        LibraryFilter.Playlists -> copy(
            savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<Playlist>()
                .map { collectionKey(CollectionType.Playlist, it.id) }
        )

        LibraryFilter.Shows -> copy(
            savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<Show>()
                .map { collectionKey(CollectionType.Show, it.id) }
        )

        LibraryFilter.Artists -> copy(
            followedArtistIds = followedArtistIds + items.filterIsInstance<Artist>().map { artistKey(it.id).encode() }
        )
    }

    /** Server pages add to the known state; they never discard items learned from another page. */
    private fun SidetrackUiState.withLibrarySaved(filter: LibraryFilter, items: List<Any>): SidetrackUiState = withSectionSaved(filter, items)

    /** Prepends a just-saved item to its library section and the open category, if any. */
    private fun SidetrackUiState.withLibraryItemAdded(filter: LibraryFilter, item: Any): SidetrackUiState {
        val id = libraryItemId(item) ?: return this
        val section = listOf(item) + librarySections[filter].orEmpty().filterNot { libraryItemId(it) == id }
        val items = if (this.filter == filter) {
            listOf(item) + libraryItems.filterNot { libraryItemId(it) == id }
        } else {
            libraryItems
        }
        return copy(
            librarySections = librarySections + (filter to section.take(LIBRARY_SECTION_SIZE)),
            libraryItems = items
        )
    }

    /** Removes a just-unsaved item from its library section and the open category, if any. */
    private fun SidetrackUiState.withLibraryItemRemoved(filter: LibraryFilter, id: String): SidetrackUiState {
        fun matches(item: Any) = libraryItemId(item) == id

        val removedFromOpenCategory = this.filter == filter && libraryItems.any(::matches)
        return copy(
            librarySections = librarySections.mapValues { (section, items) ->
                if (section == filter) items.filterNot(::matches) else items
            },
            libraryItems = if (removedFromOpenCategory) libraryItems.filterNot(::matches) else libraryItems,
            // Removing a row makes space for the next server page, even when the list was full.
            libraryHasMore = if (removedFromOpenCategory) true else libraryHasMore
        )
    }

    private fun libraryItemId(item: Any): String? = when (item) {
        is Track -> item.id
        is Playlist -> item.id
        is Album -> item.id
        is Artist -> item.id
        is Show -> item.id
        else -> null
    }

    private fun libraryItemFor(collection: Collection): Any? = when (collection.type) {
        CollectionType.Album -> Album(
            id = collection.id,
            title = collection.title,
            artist = collection.subtitle.substringBefore(" · "),
            artistId = "",
            year = collection.subtitle.split(" · ").getOrNull(1)?.toIntOrNull() ?: 0,
            artRes = collection.artRes,
            tracks = collection.items.filterIsInstance<Track>(),
            artUrl = collection.artUrl,
            trackCount = collection.totalItems,
            service = collection.service
        )

        CollectionType.Playlist -> Playlist(
            id = collection.id,
            name = collection.title,
            owner = collection.subtitle.substringBefore(" · "),
            ownerId = collection.artistId,
            artRes = collection.artRes,
            tracks = collection.items.filterIsInstance<Track>(),
            artUrl = collection.artUrl,
            trackCount = collection.totalItems,
            service = collection.service
        )

        CollectionType.Show -> Show(
            id = collection.id,
            name = collection.title,
            publisher = collection.subtitle.substringBefore(" · "),
            artRes = collection.artRes,
            episodes = collection.items.filterIsInstance<Episode>(),
            artUrl = collection.artUrl,
            episodeCount = collection.totalItems,
            description = collection.description,
            service = collection.service
        )
    }

    private fun libraryFilterFor(type: CollectionType): LibraryFilter = when (type) {
        CollectionType.Album -> LibraryFilter.Albums
        CollectionType.Playlist -> LibraryFilter.Playlists
        CollectionType.Show -> LibraryFilter.Shows
    }

    private fun SidetrackUiState.trackById(id: String): Track? = (
        listOfNotNull(current as? Track) +
            upcoming.filterIsInstance<Track>() +
            recent.filterIsInstance<Track>() +
            libraryItems.filterIsInstance<Track>() +
            collections.values.flatMap { it.items }.filterIsInstance<Track>() +
            librarySections.values.flatten().filterIsInstance<Track>()
        ).firstOrNull { it.id == id }

    private fun applyQueue(
        snapshot: QueueRepository.Snapshot,
        positionMs: Long? = null,
        playing: Boolean? = null,
        syncNowPlaying: Boolean = true
    ) {
        // Leaving an episode is the last chance to save where it stopped, before the new item
        // resets the seek bar to its own offset.
        val previous = _state.value.current
        if (previous is Episode && (snapshot.current?.id != previous.id || positionMs != null)) {
            episodeProgressRepository.record(previous.id, _playbackPositionMs.value, previous.durationMs)
        }
        // Notes are not part of the queue file; keep them next to the episode that is playing so a
        // restored session can still offer them without another request.
        (snapshot.current as? Episode)?.let(episodeMetaRepository::remember)
        if (snapshot.current?.id != previous?.id) seekHold = null
        if (positionMs != null) {
            seekHold = null
            anchorPosition(positionMs)
            _playbackPositionMs.value = positionMs
        }
        _state.update {
            it.copy(
                current = snapshot.current,
                upcoming = snapshot.upcoming,
                history = snapshot.history,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat,
                isPlaying = playing ?: it.isPlaying
            )
        }
        if (syncNowPlaying) {
            session?.playback?.setNowPlaying(snapshot.current, positionMs ?: _playbackPositionMs.value)
        }
    }

    /** Episode offsets are saved every few seconds while playing, and immediately on pauses/seeks. */
    private fun recordEpisodeProgress(force: Boolean = false) {
        val current = _state.value.current as? Episode ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEpisodeProgressAt < EPISODE_PROGRESS_INTERVAL_MS) return
        lastEpisodeProgressAt = now
        episodeProgressRepository.record(current.id, _playbackPositionMs.value, current.durationMs)
    }

    /** The offset this item should start at when it becomes current. */
    private fun itemStartPosition(item: MediaItem): Long = (item as? Episode)?.let { episodeProgressRepository.resumePosition(it.id) } ?: 0L

    /** The offset a restored item was stopped at. Tracks still use the single-session resume point. */
    private fun savedPositionFor(item: MediaItem): Long = when (item) {
        is Episode -> episodeProgressRepository.resumePosition(item.id)
        else -> session?.resumePoint?.takeIf { it.key == item.key }?.positionMs ?: 0L
    }

    private fun markEpisodeFinished(item: MediaItem) {
        if (item !is Episode) return
        episodeProgressRepository.record(item.id, item.durationMs, item.durationMs, completed = true)
    }

    private fun recorded(item: MediaItem) {
        history.record(item, System.currentTimeMillis())
        _state.update { it.copy(recent = history.items()) }
    }

    /**
     * Shows the now-playing confirmation for a few seconds. The timer lives here rather than in
     * the action bar so the state is cleared even if the user navigates to a screen that does not
     * render the confirmation.
     */
    private fun showNowPlayingNotice(item: MediaItem) {
        nowPlayingNoticeJob?.cancel()
        _state.update { it.copy(nowPlayingNotice = item) }
        nowPlayingNoticeJob = viewModelScope.launch {
            delay(NOW_PLAYING_NOTICE_MS)
            _state.update {
                if (it.nowPlayingNotice?.id == item.id) it.copy(nowPlayingNotice = null) else it
            }
        }
    }

    private fun search(debounceMs: Long) {
        searchJob?.cancel()
        val query = _state.value.query
        if (query.trim().length < 2) {
            _state.update {
                it.copy(
                    searchResults = SearchResults(),
                    searchPending = false,
                    searchHasMore = emptySet(),
                    searchMorePending = emptySet()
                )
            }
            return
        }
        _state.update {
            it.copy(
                searchPending = true,
                searchError = null,
                searchHasMore = emptySet(),
                searchMorePending = emptySet()
            )
        }
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            val scopes = _state.value.searchScopes
            var failure: String? = null
            val results = try {
                if (_state.value.signedIn) catalog.search(query, scopes, 0, SEARCH_PAGE_SIZE) else SearchResults()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = playbackNotice(e)
                SearchResults()
            }
            _state.update {
                if (it.query == query && it.searchScopes == scopes) {
                    val empty = results.isEmpty
                    val next = it.copy(
                        searchResults = results,
                        searchPending = false,
                        searchError = if (empty) failure else null,
                        searchHasMore = scopes.filterTo(mutableSetOf()) { scope -> results.sizeFor(scope) >= SEARCH_PAGE_SIZE }
                    )
                    // A failed search with old results stays a notice; an empty one becomes the
                    // screen's fallback so the retry action is visible.
                    if (empty || failure == null) next else next.say(failure)
                } else {
                    it
                }
            }
        }
    }

    /** Retries the current query after a failed search. */
    fun retrySearch() {
        search(debounceMs = 0L)
    }

    fun loadMoreSearch(scope: SearchScope) {
        val snapshot = _state.value
        val query = snapshot.query
        if (scope !in snapshot.searchHasMore || scope in snapshot.searchMorePending) return
        val offset = snapshot.searchResults.sizeFor(scope)
        _state.update { it.copy(searchMorePending = it.searchMorePending + scope) }
        viewModelScope.launch {
            try {
                val more = catalog.search(query, setOf(scope), offset, SEARCH_PAGE_SIZE)
                _state.update {
                    if (it.query != query) {
                        it.copy(searchMorePending = it.searchMorePending - scope)
                    } else {
                        val hasMore = if (more.sizeFor(scope) >= SEARCH_PAGE_SIZE) {
                            it.searchHasMore + scope
                        } else {
                            it.searchHasMore - scope
                        }
                        it.copy(
                            searchResults = it.searchResults.merge(scope, more),
                            searchHasMore = hasMore,
                            searchMorePending = it.searchMorePending - scope
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(searchMorePending = it.searchMorePending - scope).say(playbackNotice(e))
                }
            }
        }
    }

    private fun SearchResults.sizeFor(scope: SearchScope): Int = when (scope) {
        SearchScope.Tracks -> tracks.size
        SearchScope.Artists -> artists.size
        SearchScope.Albums -> albums.size
        SearchScope.Playlists -> playlists.size
        SearchScope.Shows -> shows.size
    }

    private fun SearchResults.merge(scope: SearchScope, next: SearchResults): SearchResults = when (scope) {
        SearchScope.Tracks -> copy(tracks = (tracks + next.tracks).distinctBy { it.id })
        SearchScope.Artists -> copy(artists = (artists + next.artists).distinctBy { it.id })
        SearchScope.Albums -> copy(albums = (albums + next.albums).distinctBy { it.id })
        SearchScope.Playlists -> copy(playlists = (playlists + next.playlists).distinctBy { it.id })
        SearchScope.Shows -> copy(shows = (shows + next.shows).distinctBy { it.id })
    }

    /**
     * librespot is the source of truth while it is playing: its position events keep the seek bar
     * honest through buffering and seeks, and EndOfTrack advances the Sidekit queue exactly once.
     */
    internal fun handlePlaybackEvent(event: PlaybackEvent) {
        val position = when (event) {
            is PlaybackEvent.Loading -> event.positionMs
            is PlaybackEvent.Playing -> event.positionMs
            is PlaybackEvent.Paused -> event.positionMs
            is PlaybackEvent.Position -> event.positionMs
            is PlaybackEvent.Seeked -> event.positionMs
            is PlaybackEvent.PositionCorrection -> event.positionMs
            else -> null
        } ?: _playbackPositionMs.value
        session?.notePlayRequest(event.playRequestId, event.uri)
        SidetrackDebug.log(
            "event type=${event::class.simpleName} uri=${event.uri.orEmpty()} positionMs=$position"
        )
        when (event) {
            is PlaybackEvent.Loading -> if (matchesCurrent(event.uri) && !staleResumeEvent(position)) {
                // The player has accepted the new item: the hold can end and the clock may run
                // from the position the player will actually start at.
                awaitingPlaybackStart = false
                if (activeSeekHold() == null) {
                    anchorPosition(position)
                    session?.playback?.updatePosition(position)
                    _playbackPositionMs.value = position
                }
                // A load event is not a seek confirmation. While a local seek is pending it may
                // still report the old offset, so keep the bar at the target.
            }

            is PlaybackEvent.Playing -> if (matchesCurrent(event.uri)) {
                val playback = session?.playback
                // Ignore a playing event that belongs to a command the user already superseded
                // with a pause. Otherwise rapid taps leave the UI showing play with no sound.
                if (playback?.shouldApplyEvent(true) != false) {
                    awaitingPlaybackStart = false
                    // Keep the UI clock where it is. librespot's event position is the decoder
                    // position, which is ahead of the speaker by the PCM buffer; adopting it
                    // would make the bar jump forward on resume.
                    val current = _playbackPositionMs.value
                    anchorPosition(current)
                    playback?.updateState(true, current)
                    _state.update { it.copy(isPlaying = true) }
                }
            }

            is PlaybackEvent.Paused -> if (matchesCurrent(event.uri)) {
                val playback = session?.playback
                // Ignore a paused event that belongs to a command the user already superseded
                // with a resume. Otherwise the UI flickers back to paused after a resume.
                if (playback?.shouldApplyEvent(false) != false) {
                    awaitingPlaybackStart = false
                    val current = _playbackPositionMs.value
                    anchorPosition(current)
                    playback?.updateState(false, current)
                    _state.update { it.copy(isPlaying = false) }
                    recordEpisodeProgress(force = true)
                }
            }

            is PlaybackEvent.Seeked, is PlaybackEvent.PositionCorrection -> {
                // The player itself confirms where it landed. This is the only event that may
                // end a local seek hold; a regular position event can still describe the old
                // clock while the seek is queued behind a slow fetch.
                if (activeSeekHold() != null) {
                    adoptSeekPosition(position)
                } else if (!awaitingPlaybackStart && !staleResumeEvent(position)) {
                    anchorPosition(position)
                    session?.playback?.updatePosition(position)
                    _playbackPositionMs.value = position
                }
                recordEpisodeProgress(force = true)
            }

            is PlaybackEvent.Position -> {
                // Regular decoder updates must not end a pending seek: they can still be from
                // the generation before the seek, especially on a slow connection.
                if (activeSeekHold() != null) return
                // Position events carry no item identity. Ignore them while a newly started item
                // is still being loaded, otherwise the previous item's clock can rewind the bar.
                if (awaitingPlaybackStart) return
                // The ticker owns the seek bar. Only adopt an event position when it is far
                // enough away to be a real seek from another client, not the decoder's normal
                // lead over the speaker.
                val current = _playbackPositionMs.value
                if (!staleResumeEvent(position) && abs(position - current) > POSITION_EVENT_JUMP_MS) {
                    anchorPosition(position)
                    session?.playback?.updatePosition(position)
                    _playbackPositionMs.value = position
                    recordEpisodeProgress(force = true)
                }
            }

            // librespot 0.8 has no shuffle/repeat setters, but it emits these when another
            // client changes the player. Adopt them so the UI does not drift from the service.
            is PlaybackEvent.Shuffle ->
                applyQueue(queue.setShuffle(event.enabled), syncNowPlaying = false)

            is PlaybackEvent.Repeat -> {
                val mode = when {
                    event.track -> RepeatMode.Track
                    event.context -> RepeatMode.Context
                    else -> RepeatMode.Off
                }
                applyQueue(queue.setRepeat(mode), syncNowPlaying = false)
            }

            is PlaybackEvent.Ended -> if (matchesCurrent(event.uri)) {
                // A previous generation can report its end while the replacement is still
                // loading. The queue has already moved on, so this must not advance it again.
                if (!awaitingPlaybackStart) onPlaybackEnded()
            }

            is PlaybackEvent.Stopped -> if (matchesCurrent(event.uri)) {
                // Closing the app halts the audio and saves its offset; the player's "stopped"
                // echo can arrive later, after a recreated ViewModel has restored that offset.
                // Ignoring it keeps the restored position and leaves the queue untouched.
                if (session?.consumeHaltStop(event.uri, event.playRequestId) == true) return
                // The old generation's stop can arrive after the user already started the next
                // item (or the same item again). When the shared queue still has a current item,
                // the player is being replaced, not stopped: clearing here made Now Playing lose
                // the track while the new audio kept playing. A real notification stop clears the
                // queue first, so it still falls through.
                if (awaitingPlaybackStart && queue.snapshot.current != null) return
                // Stop from the notification or lock screen: clear both the UI and the queue so
                // reopening Sidetrack cannot restore the track that was stopped.
                awaitingPlaybackStart = false
                seekHold = null
                session?.playback?.updateState(false, 0L)
                val halting = session?.halting == true
                applyQueue(if (halting) queue.snapshot else queue.stop(), positionMs = 0L, playing = false)
            }

            is PlaybackEvent.Unavailable -> if (matchesCurrent(event.uri)) {
                awaitingPlaybackStart = false
                seekHold = null
                _state.update { it.copy(isPlaying = false).say("This track isn't available") }
            }

            is PlaybackEvent.Unknown -> Unit
        }
    }

    private fun matchesCurrent(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return true
        // HTTP services have no stable MediaItem.uri; their event URI is the resolved stream URL.
        val current = _state.value.current?.uri ?: session?.playback?.currentSource?.uri
        return current?.equals(uri, ignoreCase = true) ?: true
    }

    private fun onPlaybackEnded() {
        val snapshot = _state.value
        val current = snapshot.current ?: return
        markEpisodeFinished(current)
        // The sleep timer wins over repeat and autoplay: this is where the queue must stop.
        if (session?.playback?.consumeEndOfTrackTimer() == true) {
            _state.update { it.copy(isPlaying = false) }
            return
        }
        if (endOfEpisode(current)) return
        if (snapshot.repeat == RepeatMode.Track) {
            _playbackPositionMs.value = 0L
            _state.update { it.copy(isPlaying = true) }
            startRemote(current, 0L)
        } else {
            advance(auto = true, forceContextLoop = snapshot.repeat == RepeatMode.Context)
        }
    }

    /**
     * Ends a finished episode. Episodes carry no shuffle or repeat control, so a mode left over
     * from a song must not rewind the show or loop the episode forever: with nothing queued behind
     * it or added to the queue, the queue stops.
     */
    private fun endOfEpisode(current: MediaItem): Boolean {
        if (current !is Episode) return false
        advance(auto = true, forceContextLoop = false)
        return true
    }

    private data class ResumeAnchor(val trackId: String, val positionMs: Long, val atMs: Long)

    /** Which offset a restored track was started at, and when, so stale events can be spotted. */
    private var resumeAnchor: ResumeAnchor? = null

    private fun anchorPosition(positionMs: Long) {
        positionAnchorMs = positionMs
        positionAnchorAt = SystemClock.elapsedRealtime()
    }

    /**
     * Advances the seek bar from a monotonic clock anchored to the last known position. The
     * AudioTrack playback head is not stable across pause/resume on this device, so it is not
     * used for the UI; the system clock advances at the same 1.0x rate and never jumps.
     */
    private fun updatePositionTick() {
        val s = _state.value
        if (!s.isPlaying || s.isBuffering) return
        val current = s.current ?: return
        // The bar has been running from the seek target. Once the player had ample time to
        // confirm, stop suppressing unrelated position events.
        val hold = seekHold
        if (hold != null && SystemClock.elapsedRealtime() - hold.atMs > SEEK_CONFIRM_TIMEOUT_MS) {
            seekHold = null
        }
        if (awaitingPlaybackStart) {
            if (SystemClock.elapsedRealtime() - awaitingPlaybackStartAt < PLAYBACK_START_TIMEOUT_MS) return
            awaitingPlaybackStart = false
        }
        val next = positionAnchorMs + (SystemClock.elapsedRealtime() - positionAnchorAt)
        val clamped = next.coerceIn(0L, current.durationMs)
        if (clamped != _playbackPositionMs.value) {
            session?.playback?.updatePosition(clamped)
            _playbackPositionMs.value = clamped
            recordEpisodeProgress()
        }
    }

    private fun tick() {
        val s = _state.value
        val current = s.current ?: return
        if (!s.isPlaying) return
        val nextPosition = _playbackPositionMs.value + 1_000L
        if (nextPosition < current.durationMs) {
            _playbackPositionMs.value = nextPosition
            recordEpisodeProgress()
        } else if (!endOfEpisode(current)) {
            when (s.repeat) {
                RepeatMode.Track -> _playbackPositionMs.value = 0L
                RepeatMode.Context -> advance(auto = true, forceContextLoop = true)
                RepeatMode.Off -> advance(auto = true, forceContextLoop = false)
            }
        }
    }

    private fun advance(auto: Boolean, forceContextLoop: Boolean) {
        val s = _state.value
        val current = s.current ?: return
        if (auto) markEpisodeFinished(current)
        val hasNext = s.upcoming.isNotEmpty()
        if (!hasNext && forceContextLoop) {
            val snapshot = queue.next(auto = true)
            val next = snapshot.current
            val resume = next?.let(::itemStartPosition) ?: 0L
            next?.let(::recorded)
            applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, gapless = auto && _state.value.gapless, resuming = resume > 0L) }
            return
        }
        if (!hasNext && !auto) return
        if (!hasNext) {
            if (_state.value.autoplay) autoplaySimilar(current) else stopAtQueueEnd()
            return
        }
        val snapshot = queue.next(auto = true)
        if (snapshot.current !== current) {
            val next = snapshot.current
            val resume = next?.let(::itemStartPosition) ?: 0L
            next?.let(::recorded)
            applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, gapless = auto && _state.value.gapless, resuming = resume > 0L) }
        } else {
            _playbackPositionMs.value = 0L
            _state.update { it.copy(isPlaying = false) }
        }
    }

    /**
     * The queue ran out with Autoplay on. Ask the catalog for the current artist's top tracks,
     * drop anything already in the queue or history, and let the normal advance path start the
     * first one. The guard keeps a slow request from hijacking a track the user started meanwhile.
     */
    private fun autoplaySimilar(current: MediaItem) {
        if (autoplayJob?.isActive == true) return
        if (session?.isOnline == false) {
            stopAtQueueEnd("No internet connection — autoplay stopped")
            return
        }
        val track = current as? Track
        val artistId = track?.artistId?.mediaIdOrNull()
        if (track == null || artistId == null) {
            stopAtQueueEnd()
            return
        }
        autoplayJob = viewModelScope.launch {
            try {
                val artist = Artist(artistId, track.artist, 0, emptyList(), service = service)
                val page = catalog.topTracks(artist.key, 0, CATALOG_PAGE_SIZE)
                if (_state.value.current?.id != track.id || !_state.value.autoplay) return@launch
                val known = buildSet {
                    add(track.id)
                    addAll(_state.value.history.map { it.id })
                    addAll(_state.value.upcoming.map { it.id })
                }
                val similar = page.items.filter { it.id !in known && it.playable }
                if (similar.isEmpty()) {
                    stopAtQueueEnd()
                    return@launch
                }
                val tail = if (_state.value.shuffle) similar.shuffled() else similar
                applyQueue(queue.addToQueue(tail), syncNowPlaying = false)
                advance(auto = true, forceContextLoop = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.current?.id == track.id) {
                    val reason = when {
                        session?.isOnline == false -> "No internet connection"
                        (e as? ServiceException)?.failure == ServiceFailure.Network -> "Couldn't reach the service"
                        else -> playbackNotice(e)
                    }
                    stopAtQueueEnd("$reason — autoplay stopped")
                }
            }
        }
    }

    private fun stopAtQueueEnd(notice: String? = null) {
        _playbackPositionMs.value = 0L
        _state.update {
            val next = it.copy(isPlaying = false)
            if (notice == null) next else next.say(notice)
        }
    }

    /** Starts a real service item; local test fixtures have no URI and keep the local simulation. */
    private fun startRemote(
        item: MediaItem,
        positionMs: Long,
        gapless: Boolean = false,
        resuming: Boolean = false
    ) {
        val session = session ?: return
        if (item.service == ServiceId.Local) return
        SidetrackDebug.log("viewModel.startRemote key=${item.key.encode()} positionMs=$positionMs gapless=$gapless resuming=$resuming")
        seekHold = null
        anchorPosition(positionMs)
        awaitingPlaybackStart = true
        awaitingPlaybackStartAt = SystemClock.elapsedRealtime()
        if (resuming) {
            resumeAnchor = ResumeAnchor(item.id, positionMs, SystemClock.elapsedRealtime())
        } else {
            resumeAnchor = null
        }
        session.playback.setNowPlaying(item, positionMs)
        viewModelScope.launch {
            try {
                session.play(item, positionMs, gapless)
                SidetrackDebug.log("viewModel.startRemote returned")
                if (resuming) releaseResumeHold(item, positionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SidetrackDebug.log("viewModel.startRemote failed", e)
                awaitingPlaybackStart = false
                _state.update {
                    it.copy(isPlaying = false, nowPlayingNotice = null).say(playbackNotice(e))
                }
            }
        }
    }

    /**
     * The audio is running at [positionMs] once the start call returns, so let the seek bar run
     * from that offset instead of waiting for a player event. A reconnecting player can replay the
     * previous generation's state — typically position 0 — which left the bar stuck, then watched
     * it snap back to the start while the transport said it was playing.
     */
    private fun releaseResumeHold(item: MediaItem, positionMs: Long) {
        awaitingPlaybackStart = false
        anchorPosition(positionMs)
        _playbackPositionMs.value = positionMs
        session?.playback?.updateState(true, positionMs)
        _state.update { state ->
            if (state.current?.id == item.id) state.copy(isPlaying = true) else state
        }
    }

    /** The active local seek, or null once the player has had enough time to confirm it. */
    private fun activeSeekHold(): SeekHold? {
        val hold = seekHold ?: return null
        if (SystemClock.elapsedRealtime() - hold.atMs > SEEK_CONFIRM_TIMEOUT_MS) {
            seekHold = null
            return null
        }
        return hold
    }

    /** Adopts a confirmed seek position, keeping the UI and media session on the audio clock. */
    private fun adoptSeekPosition(position: Long) {
        seekHold = null
        anchorPosition(position)
        session?.playback?.updatePosition(position)
        _playbackPositionMs.value = position
    }

    /**
     * True while a player event still describes the generation that was stopped: it reports a
     * position well below the offset the user resumed from. Adopting it would move the bar back to
     * the start even though the audio is playing further in.
     */
    private fun staleResumeEvent(eventPositionMs: Long): Boolean {
        val anchor = resumeAnchor ?: return false
        if (SystemClock.elapsedRealtime() - anchor.atMs > RESUME_GRACE_MS) {
            resumeAnchor = null
            return false
        }
        return eventPositionMs + RESUME_EVENT_SLACK_MS < anchor.positionMs
    }

    private fun playbackNotice(error: Exception): String = when ((error as? ServiceException)?.failure) {
        ServiceFailure.Unavailable -> "Playback unavailable in this build"

        ServiceFailure.NotConnected -> "Playback isn't ready — try again"

        ServiceFailure.AuthenticationExpired -> "Session expired — sign in again"

        ServiceFailure.Network -> if (session?.isOnline == false) "No internet connection" else "Couldn't reach the service"

        ServiceFailure.RateLimited -> "The service is rate-limiting — try again"

        ServiceFailure.TrackUnavailable -> "This track isn't available"

        // Keep the status subtitle to one line; the full exception is already logged by callers.
        ServiceFailure.Unknown, null -> "Something went wrong — try again"
    }

    private fun collectionKey(type: CollectionType, id: String) = "${service.value}:${type.route}:$id"

    private fun mediaKey(type: CollectionType, id: String) = MediaKey(service, type.mediaType, id)

    private fun artistKey(id: String) = MediaKey(service, MediaType.Artist, id)

    private fun trackKey(id: String) = MediaKey(service, MediaType.Track, id)

    private fun libraryItemKey(item: Any): String = when (item) {
        is Track -> "t${item.id}"
        is Playlist -> "p${item.id}"
        is Album -> "a${item.id}"
        is Artist -> "r${item.id}"
        is Show -> "s${item.id}"
        else -> item.toString()
    }
}

/** Sets the transient bar message together with the time the bar keeps it. */
private fun SidetrackUiState.say(text: String, kind: NoticeKind = NoticeKind.Other) = copy(notice = text, noticeKind = kind)
