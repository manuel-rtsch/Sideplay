package io.github.manuelrtsch.sidetrack

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyEpisodeProgress
import io.github.manuelrtsch.sidetrack.data.SpotifyEpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifyHistoryRepository
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import io.github.manuelrtsch.sidetrack.data.SpotifyQueueRepository
import io.github.manuelrtsch.sidetrack.data.SpotifySession
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** How long the now-playing confirmation owns the action bar before the normal actions return. */
const val NOW_PLAYING_NOTICE_MS = 4_000L

/** The podcast transport's rewind and fast-forward step. */
private const val PODCAST_SKIP_MS = 30_000L

/** How often a playing episode writes its local resume point. */
private const val EPISODE_PROGRESS_INTERVAL_MS = 5_000L

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

data class SpotifyUiState(
    val signedIn: Boolean = false,
    val current: SpotifyMediaItem? = null,
    val upcoming: List<SpotifyMediaItem> = emptyList(),
    val history: List<SpotifyMediaItem> = emptyList(),
    val recent: List<SpotifyMediaItem> = emptyList(),
    val libraryItems: List<Any> = emptyList(),
    val librarySections: Map<LibraryFilter, List<Any>> = emptyMap(),
    val librarySectionHasMore: Set<LibraryFilter> = emptySet(),
    val searchResults: SpotifySearchResults = SpotifySearchResults(),
    val searchPending: Boolean = false,
    val authPending: Boolean = false,
    val libraryLoading: Boolean = false,
    val librarySectionsLoading: Boolean = false,
    /** Set when the overview could not load at all, so the Library screen can offer a retry. */
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
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val savedTrackIds: Set<String> = emptySet(),
    val savedCollectionKeys: Set<String> = emptySet(),
    val followedArtistIds: Set<String> = emptySet(),
    val collections: Map<String, SpotifyCollection> = emptyMap(),
    val artists: Map<String, SpotifyArtist> = emptyMap(),
    val artistAlbums: Map<String, List<SpotifyAlbum>> = emptyMap(),
    val artistTracks: Map<String, List<SpotifyTrack>> = emptyMap(),
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
    /** The item that just started, shown briefly in the action bar as a now-playing confirmation. */
    val nowPlayingNotice: SpotifyMediaItem? = null,
    /** The station built from the current track, shown by the Song radio screen. */
    val radio: SpotifyCollection? = null,
    /** Which track seeded [radio], so revisiting the same song does not refetch it. */
    val radioSeedId: String? = null,
    val radioPending: Boolean = false,
    val radioError: String? = null,
    /** The station built from an artist, shown by the Artist radio screen. */
    val artistRadio: SpotifyCollection? = null,
    /** Which artist seeded [artistRadio], so revisiting the same artist does not refetch it. */
    val artistRadioArtistId: String? = null,
    val artistRadioPending: Boolean = false,
    val artistRadioError: String? = null
) {
    val savedCurrent: Boolean get() = current?.let { it.id in savedTrackIds } ?: false
}

/**
 * State holder for the Sidetrack UI. Catalog reads, queue ordering and local history live in
 * their own domain objects; this class only maps them to the single immutable screen state and
 * implements the optimistic save/follow behaviour the action bars expect.
 */
class SpotifyViewModel(
    private val catalog: SpotifyCatalog,
    private val writer: SpotifyLibraryWriter? = null,
    private val queue: SpotifyQueueRepository = SpotifyQueueRepository(),
    private val history: SpotifyHistoryRepository = SpotifyHistoryRepository(),
    private val episodeProgressRepository: SpotifyEpisodeProgressRepository = SpotifyEpisodeProgressRepository(),
    private val session: SpotifySession? = null,
    private val openBrowser: (suspend (String) -> Unit)? = null,
    private val clearArtworkCache: () -> Unit = {},
    private val simulatePlayback: Boolean = true
) : ViewModel() {
    private val _state = MutableStateFlow(SpotifyUiState(signedIn = session?.signedIn?.value ?: false))
    val state: StateFlow<SpotifyUiState> = _state.asStateFlow()

    /**
     * Playback position for the seek bar. Kept on its own flow so the 100 ms position tick does
     * not mutate the monolithic [state] (which would invalidate and recompose every screen that
     * collects it). Only the Now Playing seek bar subscribes to this.
     */
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    /** Per-episode podcast progress, kept out of [state] so a five-second write does not redraw menus. */
    val episodeProgress: StateFlow<Map<String, SpotifyEpisodeProgress>> = episodeProgressRepository.progress

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
    private var lastSeekAt = 0L
    private var lastEpisodeProgressAt = 0L

    /**
     * Set while a newly started item has not been confirmed by the native player. The ticker
     * holds the bar at the requested position during that window: otherwise the UI clock runs
     * through fetch/buffer latency, and the first loading event (position 0) snaps it backwards.
     */
    private var awaitingPlaybackStart = false
    private var awaitingPlaybackStartAt = 0L

    init {
        session?.let {
            viewModelScope.launch {
                it.signedIn.collect { signed ->
                    _state.update { state -> state.copy(signedIn = signed) }
                    if (signed) loadAccount() else clearAccount()
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
                if (item?.uri != null) {
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
                    // Back off when nothing is playing so the loop does not keep waking the main thread.
                    while (true) {
                        delay(if (_state.value.isPlaying) 1_000L else 2_000L)
                        tick()
                    }
                }
            }
        } else {
            viewModelScope.launch {
                // Native events only matter while something is playing. Poll tight when active, back
                // off to a slow heartbeat when idle so this does not run forever on the main path.
                while (true) {
                    delay(if (_state.value.isPlaying) 300L else 3_000L)
                    val raw = withContext(Dispatchers.IO) { session.pollPlaybackEvent() } ?: continue
                    handlePlaybackEvent(raw)
                }
            }
            viewModelScope.launch {
                while (true) {
                    delay(if (_state.value.isPlaying) 100L else 2_000L)
                    updatePositionTick()
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

    /** Dismisses the transient now-playing confirmation and returns the normal action bar. */
    fun dismissNowPlayingNotice() {
        nowPlayingNoticeJob?.cancel()
        nowPlayingNoticeJob = null
        _state.update { it.copy(nowPlayingNotice = null) }
    }

    /** Reloads the Library overview. Called whenever the Library screen is opened. */
    fun refreshLibrary() {
        if (!_state.value.signedIn) return
        loadLibrarySections()
    }

    /** Retries the open library category after a failed load. */
    fun retryLibrary() {
        if (!_state.value.signedIn) return
        loadLibrary(_state.value.filter)
    }

    fun notice(text: String) = _state.update { it.copy(notice = text) }

    fun collection(type: SpotifyCollectionType, id: String): SpotifyCollection? = _state.value.collections[collectionKey(type, id)]

    fun artist(id: String): SpotifyArtist? = _state.value.artists[id]

    fun albumsBy(artist: SpotifyArtist): List<SpotifyAlbum> = _state.value.artistAlbums[artist.id].orEmpty()

    fun topTracks(artist: SpotifyArtist): List<SpotifyTrack> = _state.value.artistTracks[artist.id].orEmpty()

    fun ensureCollection(type: SpotifyCollectionType, id: String) {
        val key = collectionKey(type, id)
        val snapshot = _state.value
        if (key in snapshot.collections || key in snapshot.pendingCollections) return
        _state.update { it.copy(pendingCollections = it.pendingCollections + key) }
        viewModelScope.launch {
            try {
                val collection = catalog.collection(type, id, 0, SPOTIFY_PAGE_SIZE)
                val saved = if (collection == null) {
                    null
                } else {
                    runCatching { writer?.isSaved(type, id) }
                        .onFailure { SidetrackDebug.log("viewModel.ensureCollection key=$key saved lookup failed", it) }
                        .getOrNull()
                }
                _state.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    if (saved != null && key !in savedCollectionTouched) {
                        if (saved) keys.add(key) else keys.remove(key)
                    }
                    it.copy(
                        collections = if (collection == null) it.collections else it.collections + (key to collection),
                        savedCollectionKeys = keys,
                        pendingCollections = it.pendingCollections - key,
                        collectionErrors = it.collectionErrors - key
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SidetrackDebug.log("viewModel.ensureCollection key=$key failed", e)
                _state.update {
                    it.copy(
                        pendingCollections = it.pendingCollections - key,
                        collectionErrors = it.collectionErrors + (key to playbackNotice(e))
                    )
                }
            }
        }
    }

    /** Retries a collection that failed to load, keeping the screen on its fallback until it lands. */
    fun retryCollection(type: SpotifyCollectionType, id: String) {
        val key = collectionKey(type, id)
        if (key in _state.value.pendingCollections) return
        _state.update { it.copy(collectionErrors = it.collectionErrors - key) }
        ensureCollection(type, id)
    }

    fun loadMoreCollection(type: SpotifyCollectionType, id: String) {
        val key = collectionKey(type, id)
        val snapshot = _state.value
        val collection = snapshot.collections[key] ?: return
        if (!collection.hasMore || key in snapshot.collectionMorePending) return
        _state.update { it.copy(collectionMorePending = it.collectionMorePending + key) }
        viewModelScope.launch {
            try {
                val next = catalog.collection(type, id, collection.items.size, SPOTIFY_PAGE_SIZE)
                _state.update { state ->
                    val current = state.collections[key] ?: return@update state
                    if (next == null) {
                        state.copy(
                            collectionMorePending = state.collectionMorePending - key,
                            collections = state.collections + (key to current.copy(hasMore = false))
                        )
                    } else {
                        state.copy(
                            collectionMorePending = state.collectionMorePending - key,
                            collections = state.collections + (key to current.append(next))
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val current = it.collections[key]
                    it.copy(
                        collectionMorePending = it.collectionMorePending - key,
                        collections = if (current == null) it.collections else it.collections + (key to current.copy(hasMore = false)),
                        notice = playbackNotice(e)
                    )
                }
            }
        }
    }

    fun loadMoreArtistAlbums(artist: SpotifyArtist) {
        val id = artist.id
        val snapshot = _state.value
        if (snapshot.artistAlbumHasMore[id] != true || id in snapshot.artistMorePending) return
        _state.update { it.copy(artistMorePending = it.artistMorePending + id) }
        viewModelScope.launch {
            try {
                val page = catalog.albumsBy(artist, snapshot.artistAlbums[id].orEmpty().size, SPOTIFY_ARTIST_PAGE_SIZE)
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
                        artistMorePending = it.artistMorePending - id,
                        notice = playbackNotice(e)
                    )
                }
            }
        }
    }

    fun loadMoreArtistTracks(artist: SpotifyArtist) {
        val id = artist.id
        val snapshot = _state.value
        if (snapshot.artistTrackHasMore[id] != true || id in snapshot.artistMorePending) return
        _state.update { it.copy(artistMorePending = it.artistMorePending + id) }
        viewModelScope.launch {
            try {
                val page = catalog.topTracks(artist, snapshot.artistTracks[id].orEmpty().size, SPOTIFY_ARTIST_PAGE_SIZE)
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
                        artistMorePending = it.artistMorePending - id,
                        notice = playbackNotice(e)
                    )
                }
            }
        }
    }

    private suspend fun loadAllCollection(collection: SpotifyCollection): SpotifyCollection {
        var current = collection
        while (current.hasMore) {
            val next = catalog.collection(current.type, current.id, current.items.size, SPOTIFY_PAGE_SIZE) ?: break
            current = current.append(next)
            _state.update {
                it.copy(collections = it.collections + (collectionKey(current.type, current.id) to current))
            }
        }
        return current
    }

    private fun SpotifyCollection.append(next: SpotifyCollection): SpotifyCollection = copy(
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
                val artist = catalog.artist(id)
                if (artist == null) {
                    _state.update { it.copy(pendingArtists = it.pendingArtists - id) }
                    return@launch
                }
                val albums = catalog.albumsBy(artist, 0, SPOTIFY_ARTIST_PAGE_SIZE)
                val tracks = catalog.topTracks(artist, 0, SPOTIFY_ARTIST_PAGE_SIZE)
                val followed = runCatching { writer?.isArtistFollowed(id) }
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

    fun play(items: List<SpotifyMediaItem>, index: Int) {
        if (index !in items.indices) return
        val item = items[index]
        if (!item.playable) {
            notice("Not available on this account")
            return
        }
        if (_state.value.current?.id == item.id) {
            if (!_state.value.isPlaying) togglePlayPause()
            _state.update { it.copy(notice = null) }
            return
        }
        val snapshot = queue.play(items, index)
        if (item is SpotifyTrack) ensureTrackSaved(item.id)
        recorded(item)
        val resume = itemStartPosition(item)
        applyQueue(snapshot, positionMs = resume, playing = true)
        _state.update { it.copy(notice = null) }
        showNowPlayingNotice(item)
        startRemote(item, resume, resuming = resume > 0L)
    }

    fun playItem(item: SpotifyMediaItem) = play(listOf(item), 0)

    /**
     * Builds a station from the current track, queues it behind the still-playing seed, and makes
     * it available to the Song radio screen. Revisiting the same seed is a no-op.
     */
    fun ensureRadio() {
        val seed = _state.value.current as? SpotifyTrack
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
    fun ensureArtistRadio(artist: SpotifyArtist) {
        val id = artist.id
        val snapshot = _state.value
        if (artistRadioJob?.isActive == true && snapshot.artistRadioArtistId == id) return
        if (snapshot.artistRadioArtistId == id && snapshot.artistRadio != null) return
        loadArtistRadio(artist)
    }

    fun retryArtistRadio(artist: SpotifyArtist) {
        artistRadioJob?.cancel()
        _state.update { it.copy(artistRadio = null, artistRadioArtistId = null, artistRadioError = null) }
        ensureArtistRadio(artist)
    }

    fun playArtistRadio(index: Int) {
        val station = _state.value.artistRadio ?: return
        play(station.items, index)
    }

    private fun loadArtistRadio(artist: SpotifyArtist) {
        artistRadioJob?.cancel()
        val id = artist.id
        _state.update {
            it.copy(artistRadio = null, artistRadioArtistId = id, artistRadioPending = true, artistRadioError = null)
        }
        artistRadioJob = viewModelScope.launch {
            try {
                val result = catalog.artistRadioStation(id, SPOTIFY_PAGE_SIZE)
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
                val station = SpotifyCollection(
                    type = SpotifyCollectionType.Playlist,
                    id = result.playlistId ?: "artistradio:$id",
                    title = "${artist.name} radio",
                    subtitle = "Based on ${artist.name} · ${tracks.size} tracks",
                    artRes = artist.artRes,
                    items = tracks,
                    artUrl = artist.artUrl,
                    totalItems = tracks.size,
                    saveable = result.playlistId != null
                )
                val saved = result.playlistId?.let {
                    runCatching { writer?.isSaved(SpotifyCollectionType.Playlist, it) }.getOrNull()
                }
                _state.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    val key = collectionKey(SpotifyCollectionType.Playlist, station.id)
                    if (saved != null && key !in savedCollectionTouched) {
                        if (saved) keys.add(key) else keys.remove(key)
                    }
                    it.copy(
                        artistRadio = station,
                        artistRadioPending = false,
                        artistRadioError = null,
                        savedCollectionKeys = keys
                    )
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

    private fun loadRadio(seed: SpotifyTrack) {
        radioJob?.cancel()
        // The station request can take seconds. Remember the queue it was started from so a late
        // response cannot replace edits the user made while it loaded.
        val queueAtRequest = queue.snapshot
        _state.update {
            it.copy(radio = null, radioSeedId = seed.id, radioPending = true, radioError = null)
        }
        radioJob = viewModelScope.launch {
            try {
                val result = catalog.songRadioStation(seed.id, SPOTIFY_PAGE_SIZE)
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
                val station = SpotifyCollection(
                    type = SpotifyCollectionType.Playlist,
                    id = result.playlistId ?: "radio:${seed.id}",
                    title = "Song radio",
                    subtitle = "Based on ${seed.title} · ${tracks.size} tracks",
                    artRes = seed.artRes,
                    items = tracks,
                    artUrl = seed.artUrl,
                    totalItems = tracks.size,
                    saveable = result.playlistId != null
                )
                val saved = result.playlistId?.let {
                    runCatching { writer?.isSaved(SpotifyCollectionType.Playlist, it) }.getOrNull()
                }
                _state.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    val key = collectionKey(SpotifyCollectionType.Playlist, station.id)
                    if (saved != null && key !in savedCollectionTouched) {
                        if (saved) keys.add(key) else keys.remove(key)
                    }
                    it.copy(
                        radio = station,
                        radioSeedId = seed.id,
                        radioPending = false,
                        radioError = null,
                        savedCollectionKeys = keys
                    )
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

    fun playCollection(collection: SpotifyCollection, index: Int) {
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
                _state.update { it.copy(notice = playbackNotice(e)) }
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
    fun playArtist(artist: SpotifyArtist) {
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
                _state.update { it.copy(notice = playbackNotice(e)) }
            } finally {
                _state.update { it.copy(artistMorePending = it.artistMorePending - id) }
            }
        }
    }

    private suspend fun loadAllArtistTracks(artist: SpotifyArtist): List<SpotifyTrack> {
        val id = artist.id
        var tracks = topTracks(artist)
        var hasMore = _state.value.artistTrackHasMore[id] == true
        while (hasMore) {
            val page = catalog.topTracks(artist, tracks.size, SPOTIFY_ARTIST_PAGE_SIZE)
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
    fun playRecent(item: SpotifyMediaItem) {
        val recent = _state.value.recent
        val at = recent.indexOfFirst { it.id == item.id }
        if (at >= 0) play(recent, at) else playItem(item)
    }

    fun togglePlayPause() {
        val s = _state.value
        val item = s.current ?: return
        val play = !s.isPlaying
        recordEpisodeProgress(force = true)
        anchorPosition(_playbackPositionMs.value)
        _state.update { it.copy(isPlaying = play) }
        if (item.uri == null || session == null) return
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
                _state.update { it.copy(isPlaying = false, notice = playbackNotice(e)) }
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
        if (s.current is SpotifyEpisode) {
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
            s.current?.uri?.let { uri ->
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
            s.current?.uri?.let { uri ->
                session?.let { session ->
                    viewModelScope.launch { runCatching { session.seek(0L) } }
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val current = _state.value.current ?: return
        val target = positionMs.coerceIn(0L, current.durationMs)
        lastSeekAt = SystemClock.elapsedRealtime()
        anchorPosition(target)
        _playbackPositionMs.value = target
        recordEpisodeProgress(force = true)
        if (current.uri != null && session != null) {
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
        val track = _state.value.current as? SpotifyTrack ?: return
        toggleTrackSaved(track.id, track)
    }

    fun toggleSaved(track: SpotifyTrack) = toggleTrackSaved(track.id, track)

    private fun toggleTrackSaved(
        id: String,
        track: SpotifyTrack?
    ) {
        val wasSaved = id in _state.value.savedTrackIds
        val saved = !wasSaved
        savedTrackTouched += id
        _state.update {
            val ids = it.savedTrackIds.toMutableSet()
            if (saved) ids.add(id) else ids.remove(id)
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
                writer.setTrackSaved(id, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val ids = it.savedTrackIds.toMutableSet()
                    if (wasSaved) ids.add(id) else ids.remove(id)
                    var next = it.copy(savedTrackIds = ids, notice = playbackNotice(e))
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

    fun savedCollection(collection: SpotifyCollection): Boolean = collectionKey(collection.type, collection.id) in _state.value.savedCollectionKeys

    fun toggleSaved(collection: SpotifyCollection) = toggleSaved(collection.type, collection.id, collection)

    private fun toggleSaved(
        type: SpotifyCollectionType,
        id: String,
        collection: SpotifyCollection? = null
    ) {
        val key = collectionKey(type, id)
        val wasSaved = key in _state.value.savedCollectionKeys
        val saved = !wasSaved
        savedCollectionTouched += key
        _state.update {
            val keys = it.savedCollectionKeys.toMutableSet()
            if (saved) keys.add(key) else keys.remove(key)
            val next = it.copy(savedCollectionKeys = keys)
            if (saved) {
                collection?.let { item -> libraryItemFor(item) }?.let { item ->
                    next.withLibraryItemAdded(libraryFilterFor(type), item)
                } ?: next
            } else {
                next.withLibraryItemRemoved(libraryFilterFor(type), id)
            }
        }
        val writer = writer ?: return
        viewModelScope.launch {
            try {
                writer.setSaved(type, id, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    if (wasSaved) keys.add(key) else keys.remove(key)
                    val next = it.copy(savedCollectionKeys = keys, notice = playbackNotice(e))
                    if (wasSaved) {
                        collection?.let { item -> libraryItemFor(item) }?.let { item ->
                            next.withLibraryItemAdded(libraryFilterFor(type), item)
                        } ?: next
                    } else {
                        next.withLibraryItemRemoved(libraryFilterFor(type), id)
                    }
                }
            }
        }
    }

    fun followed(artist: SpotifyArtist): Boolean = artist.id in _state.value.followedArtistIds

    fun toggleFollow(artist: SpotifyArtist) {
        val id = artist.id
        val wasFollowing = id in _state.value.followedArtistIds
        val following = !wasFollowing
        followedArtistTouched += id
        _state.update {
            val ids = it.followedArtistIds.toMutableSet()
            if (following) ids.add(id) else ids.remove(id)
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
                writer.setArtistFollowed(id, following)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    val ids = it.followedArtistIds.toMutableSet()
                    if (wasFollowing) ids.add(id) else ids.remove(id)
                    val next = it.copy(followedArtistIds = ids, notice = playbackNotice(e))
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
        if (id in _state.value.savedTrackIds || id in savedTrackTouched) return
        viewModelScope.launch {
            val saved = runCatching { writer.isTrackSaved(id) }.getOrNull() ?: return@launch
            if (saved && id !in savedTrackTouched) {
                _state.update { it.copy(savedTrackIds = it.savedTrackIds + id) }
            }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        _state.update {
            if (it.filter == filter) {
                it.copy(libraryLoading = true)
            } else {
                it.copy(
                    filter = filter,
                    libraryItems = emptyList(),
                    libraryHasMore = false,
                    libraryLoading = true
                )
            }
        }
        loadLibrary(filter)
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
                searchResults = SpotifySearchResults(),
                searchPending = false,
                searchError = null,
                searchHasMore = emptySet(),
                searchMorePending = emptySet()
            )
        }
    }

    fun toggleScope(scope: SearchScope) {
        _state.update {
            val scopes = if (scope in it.searchScopes) it.searchScopes - scope else it.searchScopes + scope
            it.copy(searchScopes = scopes)
        }
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

    fun clearHistory() {
        history.clear()
        val snapshot = queue.clearHistory()
        applyQueue(snapshot, syncNowPlaying = false)
        _state.update { it.copy(recent = emptyList(), notice = "Listening history cleared") }
    }

    fun clearCache() {
        clearArtworkCache()
        session?.clearAudioCache()
        refreshCacheSize()
        _state.update { it.copy(notice = "Cache cleared") }
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

    fun restoreRemovedUpcoming(index: Int, item: SpotifyMediaItem) = applyQueue(queue.restoreRemovedUpcoming(index, item), syncNowPlaying = false)

    /** Drag-and-drop reorder of the upcoming tail; the current item and history never move. */
    fun moveUpcoming(from: Int, to: Int) {
        applyQueue(queue.moveUpcoming(from, to), syncNowPlaying = false)
    }

    /** The user kept the hand-picked order: shuffle no longer describes it. */
    fun finishReorder() {
        applyQueue(queue.finishReorder(), syncNowPlaying = false)
    }

    fun removeHistory(item: SpotifyMediaItem) {
        history.remove(item)
        val snapshot = queue.removeHistory(item)
        applyQueue(snapshot, syncNowPlaying = false)
        _state.update {
            it.copy(
                recent = history.items(),
                notice = "${item.title} removed from history"
            )
        }
    }

    fun addToQueue(item: SpotifyMediaItem) {
        applyQueue(queue.addToQueue(item), syncNowPlaying = false)
        // Queueing is silent in the player, so the bar is the only confirmation the action landed
        // — especially before anything is playing, when there is no Now Playing screen to check.
        _state.update { it.copy(notice = "Added ${item.title} to queue") }
    }

    fun clearUpcoming(): List<SpotifyMediaItem> {
        val removed = queue.clearUpcoming()
        applyQueue(queue.snapshot, syncNowPlaying = false)
        return removed
    }

    fun restoreUpcoming(items: List<SpotifyMediaItem>) = applyQueue(queue.restoreUpcoming(items), syncNowPlaying = false)

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
        _state.update { SpotifyUiState(signedIn = false, recent = history.items()) }
        refreshCacheSize()
    }

    fun signIn() {
        val session = session
        val openBrowser = openBrowser
        if (session == null || openBrowser == null) {
            _state.update { it.copy(notice = "Sign-in is unavailable in this build") }
            return
        }
        // A previous attempt can still be waiting on the loopback listener after the user
        // closed the browser. Supersede it so every press opens a fresh sign-in page instead of
        // being ignored until the old listener times out.
        signInJob?.cancel()
        _state.update { it.copy(authPending = true, notice = null) }
        signInJob = viewModelScope.launch {
            try {
                session.signIn(openBrowser)
                _state.update { it.copy(signedIn = true, authPending = false) }
                loadAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(authPending = false, notice = e.message ?: "Sign-in failed")
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
     * Loads a short preview of every library category for the Library overview. The categories are
     * requested one after another so the native collection cache is filled once for the shared
     * sets instead of racing several identical full reads.
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
                for (filter in LibraryFilter.entries) {
                    try {
                        val page = catalog.library(filter, 0, LIBRARY_SECTION_SIZE + 1)
                        sections[filter] = page.items.take(LIBRARY_SECTION_SIZE)
                        // Prefer the category total over the delivered rows: a catalog may drop an
                        // entry whose metadata failed to load, and an item-based check would then
                        // hide "See more" for a category that still has more rows.
                        val count = page.total ?: page.items.size
                        if (count > LIBRARY_SECTION_SIZE) hasMore += filter
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
                        librarySectionsError = if (allFailed) notice else null,
                        // A full failure is shown as an error page, not as a transient notice.
                        notice = if (allFailed) current.notice else notice ?: current.notice
                    )
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
                val page = catalog.library(filter, 0, SPOTIFY_PAGE_SIZE)
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
                    it.copy(
                        libraryLoading = false,
                        libraryError = if (empty) message else null,
                        notice = if (empty) it.notice else message
                    )
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
                val page = catalog.library(filter, offset, SPOTIFY_PAGE_SIZE)
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
                    it.copy(libraryMorePending = false, libraryHasMore = false, notice = playbackNotice(e))
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
                searchResults = SpotifySearchResults(),
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
    private fun SpotifyUiState.withSectionSaved(filter: LibraryFilter, items: List<Any>): SpotifyUiState = when (filter) {
        LibraryFilter.LikedSongs -> copy(
            savedTrackIds = savedTrackIds + items.filterIsInstance<SpotifyTrack>().map { it.id }
        )

        LibraryFilter.Albums -> copy(
            savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<SpotifyAlbum>()
                .map { collectionKey(SpotifyCollectionType.Album, it.id) }
        )

        LibraryFilter.Playlists -> copy(
            savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<SpotifyPlaylist>()
                .map { collectionKey(SpotifyCollectionType.Playlist, it.id) }
        )

        LibraryFilter.Shows -> copy(
            savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<SpotifyShow>()
                .map { collectionKey(SpotifyCollectionType.Show, it.id) }
        )

        LibraryFilter.Artists -> copy(
            followedArtistIds = followedArtistIds + items.filterIsInstance<SpotifyArtist>().map { it.id }
        )
    }

    /** Server pages add to the known state; they never discard items learned from another page. */
    private fun SpotifyUiState.withLibrarySaved(filter: LibraryFilter, items: List<Any>): SpotifyUiState = withSectionSaved(filter, items)

    /** Prepends a just-saved item to its library section and the open category, if any. */
    private fun SpotifyUiState.withLibraryItemAdded(filter: LibraryFilter, item: Any): SpotifyUiState {
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
    private fun SpotifyUiState.withLibraryItemRemoved(filter: LibraryFilter, id: String): SpotifyUiState {
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
        is SpotifyTrack -> item.id
        is SpotifyPlaylist -> item.id
        is SpotifyAlbum -> item.id
        is SpotifyArtist -> item.id
        is SpotifyShow -> item.id
        else -> null
    }

    private fun libraryItemFor(collection: SpotifyCollection): Any? = when (collection.type) {
        SpotifyCollectionType.Album -> SpotifyAlbum(
            id = collection.id,
            title = collection.title,
            artist = collection.subtitle.substringBefore(" · "),
            artistId = "",
            year = collection.subtitle.split(" · ").getOrNull(1)?.toIntOrNull() ?: 0,
            artRes = collection.artRes,
            tracks = collection.items.filterIsInstance<SpotifyTrack>(),
            artUrl = collection.artUrl,
            trackCount = collection.totalItems
        )

        SpotifyCollectionType.Playlist -> SpotifyPlaylist(
            id = collection.id,
            name = collection.title,
            owner = collection.subtitle.substringBefore(" · "),
            ownerId = collection.artistId,
            artRes = collection.artRes,
            tracks = collection.items.filterIsInstance<SpotifyTrack>(),
            artUrl = collection.artUrl,
            trackCount = collection.totalItems
        )

        SpotifyCollectionType.Show -> SpotifyShow(
            id = collection.id,
            name = collection.title,
            publisher = collection.subtitle.substringBefore(" · "),
            artRes = collection.artRes,
            episodes = collection.items.filterIsInstance<SpotifyEpisode>(),
            artUrl = collection.artUrl,
            episodeCount = collection.totalItems,
            description = collection.description
        )
    }

    private fun libraryFilterFor(type: SpotifyCollectionType): LibraryFilter = when (type) {
        SpotifyCollectionType.Album -> LibraryFilter.Albums
        SpotifyCollectionType.Playlist -> LibraryFilter.Playlists
        SpotifyCollectionType.Show -> LibraryFilter.Shows
    }

    private fun SpotifyUiState.trackById(id: String): SpotifyTrack? = (
        listOfNotNull(current as? SpotifyTrack) +
            upcoming.filterIsInstance<SpotifyTrack>() +
            recent.filterIsInstance<SpotifyTrack>() +
            libraryItems.filterIsInstance<SpotifyTrack>() +
            collections.values.flatMap { it.items }.filterIsInstance<SpotifyTrack>() +
            librarySections.values.flatten().filterIsInstance<SpotifyTrack>()
        ).firstOrNull { it.id == id }

    private fun applyQueue(
        snapshot: SpotifyQueueRepository.Snapshot,
        positionMs: Long? = null,
        playing: Boolean? = null,
        syncNowPlaying: Boolean = true
    ) {
        // Leaving an episode is the last chance to save where it stopped, before the new item
        // resets the seek bar to its own offset.
        val previous = _state.value.current
        if (previous is SpotifyEpisode && (snapshot.current?.id != previous.id || positionMs != null)) {
            episodeProgressRepository.record(previous.id, _playbackPositionMs.value, previous.durationMs)
        }
        if (positionMs != null) {
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
        val current = _state.value.current as? SpotifyEpisode ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEpisodeProgressAt < EPISODE_PROGRESS_INTERVAL_MS) return
        lastEpisodeProgressAt = now
        episodeProgressRepository.record(current.id, _playbackPositionMs.value, current.durationMs)
    }

    /** The offset this item should start at when it becomes current. */
    private fun itemStartPosition(item: SpotifyMediaItem): Long = (item as? SpotifyEpisode)?.let { episodeProgressRepository.resumePosition(it.id) } ?: 0L

    /** The offset a restored item was stopped at. Tracks still use the single-session resume point. */
    private fun savedPositionFor(item: SpotifyMediaItem): Long = when (item) {
        is SpotifyEpisode -> episodeProgressRepository.resumePosition(item.id)
        else -> session?.resumePoint?.takeIf { it.trackId == item.id }?.positionMs ?: 0L
    }

    private fun markEpisodeFinished(item: SpotifyMediaItem) {
        if (item !is SpotifyEpisode) return
        episodeProgressRepository.record(item.id, item.durationMs, item.durationMs, completed = true)
    }

    private fun recorded(item: SpotifyMediaItem) {
        history.record(item, System.currentTimeMillis())
        _state.update { it.copy(recent = history.items()) }
    }

    /**
     * Shows the now-playing confirmation for a few seconds. The timer lives here rather than in
     * the action bar so the state is cleared even if the user navigates to a screen that does not
     * render the confirmation.
     */
    private fun showNowPlayingNotice(item: SpotifyMediaItem) {
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
                    searchResults = SpotifySearchResults(),
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
                if (_state.value.signedIn) catalog.search(query, scopes, 0, SPOTIFY_SEARCH_PAGE_SIZE) else SpotifySearchResults()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = playbackNotice(e)
                SpotifySearchResults()
            }
            _state.update {
                if (it.query == query && it.searchScopes == scopes) {
                    val empty = results.isEmpty
                    it.copy(
                        searchResults = results,
                        searchPending = false,
                        searchError = if (empty) failure else null,
                        // A failed search with old results stays a notice; an empty one becomes
                        // the screen's fallback so the retry action is visible.
                        notice = if (empty) it.notice else failure ?: it.notice,
                        searchHasMore = scopes.filterTo(mutableSetOf()) { scope -> results.sizeFor(scope) >= SPOTIFY_SEARCH_PAGE_SIZE }
                    )
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
                val more = catalog.search(query, setOf(scope), offset, SPOTIFY_SEARCH_PAGE_SIZE)
                _state.update {
                    if (it.query != query) {
                        it.copy(searchMorePending = it.searchMorePending - scope)
                    } else {
                        val hasMore = if (more.sizeFor(scope) >= SPOTIFY_SEARCH_PAGE_SIZE) {
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
                    it.copy(searchMorePending = it.searchMorePending - scope, notice = playbackNotice(e))
                }
            }
        }
    }

    private fun SpotifySearchResults.sizeFor(scope: SearchScope): Int = when (scope) {
        SearchScope.Tracks -> tracks.size
        SearchScope.Artists -> artists.size
        SearchScope.Albums -> albums.size
        SearchScope.Playlists -> playlists.size
        SearchScope.Shows -> shows.size
    }

    private fun SpotifySearchResults.merge(scope: SearchScope, next: SpotifySearchResults): SpotifySearchResults = when (scope) {
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
    internal fun handlePlaybackEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val position = event.optLong("positionMs", _playbackPositionMs.value)
        val playRequestId = event.optLong("playRequestId", -1L)
        val eventUri = event.opt("uri")?.toString()
        session?.notePlayRequest(playRequestId, eventUri)
        val type = event.opt("type")?.toString().orEmpty()
        SidetrackDebug.log("event type=$type uri=${event.opt("uri")?.toString().orEmpty()} positionMs=$position")
        when (type) {
            "loading" -> if (matchesCurrent(event) && !staleResumeEvent(position)) {
                // The player has accepted the new item: the hold can end and the clock may run
                // from the position the player will actually start at.
                awaitingPlaybackStart = false
                anchorPosition(position)
                session?.playback?.updatePosition(position)
                _playbackPositionMs.value = position
            }

            "playing" -> if (matchesCurrent(event)) {
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

            "paused" -> if (matchesCurrent(event)) {
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

            "position" -> {
                // The 100 ms ticker owns the seek bar. Only adopt an event position when it is
                // far enough away to be a real seek from another client, not the decoder's
                // normal lead over the speaker.
                val current = _playbackPositionMs.value
                // Ignore the decoder's stale position events while a local seek is settling.
                val settling = SystemClock.elapsedRealtime() - lastSeekAt < 1_500L
                if (!settling && !staleResumeEvent(position) && abs(position - current) > POSITION_EVENT_JUMP_MS) {
                    anchorPosition(position)
                    session?.playback?.updatePosition(position)
                    _playbackPositionMs.value = position
                    recordEpisodeProgress(force = true)
                }
            }

            // librespot 0.8 has no shuffle/repeat setters, but it emits these when another
            // client changes the player. Adopt them so the UI does not drift from the service.
            "shuffle" -> applyQueue(queue.setShuffle(event.optBoolean("shuffle")), syncNowPlaying = false)

            "repeat" -> {
                val mode = when {
                    event.optBoolean("track") -> RepeatMode.Track
                    event.optBoolean("context") -> RepeatMode.Context
                    else -> RepeatMode.Off
                }
                applyQueue(queue.setRepeat(mode), syncNowPlaying = false)
            }

            "ended" -> if (matchesCurrent(event)) onPlaybackEnded()

            "stopped" -> if (matchesCurrent(event)) {
                // Closing the app halts the audio and saves its offset; the player's "stopped"
                // echo can arrive later, after a recreated ViewModel has restored that offset.
                // Ignoring it keeps the restored position and leaves the queue untouched.
                if (session?.consumeHaltStop(eventUri, playRequestId) == true) return
                // Stop from the notification or lock screen: clear both the UI and the queue so
                // reopening Sidetrack cannot restore the track that was stopped.
                awaitingPlaybackStart = false
                session?.playback?.updateState(false, 0L)
                val halting = session?.halting == true
                applyQueue(if (halting) queue.snapshot else queue.stop(), positionMs = 0L, playing = false)
            }

            "unavailable" -> if (matchesCurrent(event)) {
                awaitingPlaybackStart = false
                _state.update { it.copy(isPlaying = false, notice = "This track isn't available") }
            }
        }
    }

    private fun matchesCurrent(event: JSONObject): Boolean {
        val uri = event.opt("uri")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: return true
        return _state.value.current?.uri?.equals(uri, ignoreCase = true) ?: true
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
        if (snapshot.repeat == RepeatMode.Track) {
            _playbackPositionMs.value = 0L
            _state.update { it.copy(isPlaying = true) }
            startRemote(current, 0L)
        } else {
            advance(auto = true, forceContextLoop = snapshot.repeat == RepeatMode.Context)
        }
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
        if (!s.isPlaying) return
        val current = s.current ?: return
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
        } else {
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
    private fun autoplaySimilar(current: SpotifyMediaItem) {
        if (autoplayJob?.isActive == true) return
        if (session?.isOnline == false) {
            stopAtQueueEnd("No internet connection — autoplay stopped")
            return
        }
        val track = current as? SpotifyTrack
        val artistId = track?.artistId?.spotifyIdOrNull()
        if (track == null || artistId == null) {
            stopAtQueueEnd()
            return
        }
        autoplayJob = viewModelScope.launch {
            try {
                val artist = SpotifyArtist(artistId, track.artist, 0, emptyList())
                val page = catalog.topTracks(artist, 0, SPOTIFY_PAGE_SIZE)
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
                        (e as? SpotifyGatewayException)?.failure == SpotifyFailure.Network -> "Couldn't reach Spotify"
                        else -> playbackNotice(e)
                    }
                    stopAtQueueEnd("$reason — autoplay stopped")
                }
            }
        }
    }

    private fun stopAtQueueEnd(notice: String? = null) {
        _playbackPositionMs.value = 0L
        _state.update { it.copy(isPlaying = false, notice = notice ?: it.notice) }
    }

    /** Starts a real Spotify item; local test fixtures have no URI and keep the local simulation. */
    private fun startRemote(
        item: SpotifyMediaItem,
        positionMs: Long,
        gapless: Boolean = false,
        resuming: Boolean = false
    ) {
        val uri = item.uri ?: return
        val session = session ?: return
        SidetrackDebug.log("viewModel.startRemote uri=$uri positionMs=$positionMs gapless=$gapless resuming=$resuming")
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
                session.play(uri, positionMs, gapless)
                SidetrackDebug.log("viewModel.startRemote returned")
                if (resuming) releaseResumeHold(item, positionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SidetrackDebug.log("viewModel.startRemote failed", e)
                awaitingPlaybackStart = false
                _state.update {
                    it.copy(isPlaying = false, notice = playbackNotice(e), nowPlayingNotice = null)
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
    private fun releaseResumeHold(item: SpotifyMediaItem, positionMs: Long) {
        awaitingPlaybackStart = false
        anchorPosition(positionMs)
        _playbackPositionMs.value = positionMs
        session?.playback?.updateState(true, positionMs)
        _state.update { state ->
            if (state.current?.id == item.id) state.copy(isPlaying = true) else state
        }
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

    private fun playbackNotice(error: Exception): String = when ((error as? SpotifyGatewayException)?.failure) {
        SpotifyFailure.Unavailable -> "Playback unavailable in this build"

        SpotifyFailure.NotConnected -> "Playback isn't ready — try again"

        SpotifyFailure.AuthenticationExpired -> "Session expired — sign in again"

        SpotifyFailure.Network -> if (session?.isOnline == false) "No internet connection" else "Couldn't reach Spotify"

        SpotifyFailure.RateLimited -> "Spotify is rate-limiting — try again"

        SpotifyFailure.TrackUnavailable -> "This track isn't available"

        // Keep the status subtitle to one line; the full exception is already logged by callers.
        SpotifyFailure.Unknown, null -> "Something went wrong — try again"
    }

    private fun collectionKey(type: SpotifyCollectionType, id: String) = "${type.route}:$id"

    private fun libraryItemKey(item: Any): String = when (item) {
        is SpotifyTrack -> "t${item.id}"
        is SpotifyPlaylist -> "p${item.id}"
        is SpotifyAlbum -> "a${item.id}"
        is SpotifyArtist -> "r${item.id}"
        is SpotifyShow -> "s${item.id}"
        else -> item.toString()
    }
}
