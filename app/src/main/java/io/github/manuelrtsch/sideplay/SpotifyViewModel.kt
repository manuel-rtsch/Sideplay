package io.github.manuelrtsch.sideplay

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hrln.interactive.sidekit.sdk.ui.UndoWindow
import io.github.manuelrtsch.sideplay.data.SpotifyCatalog
import io.github.manuelrtsch.sideplay.data.SpotifyFailure
import io.github.manuelrtsch.sideplay.data.SpotifyGatewayException
import io.github.manuelrtsch.sideplay.data.SpotifyHistoryRepository
import io.github.manuelrtsch.sideplay.data.SpotifyLibraryWriter
import io.github.manuelrtsch.sideplay.data.SpotifyQueueRepository
import io.github.manuelrtsch.sideplay.data.SpotifySession
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

/** The last save/follow change, offered back by the action bar for a few seconds. */
sealed interface SpotifyUndo {
    val contentDescription: String

    data class Track(val id: String, val label: String?) : SpotifyUndo {
        override val contentDescription: String = if (label == null) "Undo like change" else "Undo save to $label"
    }

    data class Collection(val type: SpotifyCollectionType, val id: String) : SpotifyUndo {
        override val contentDescription: String = "Undo library change"
    }

    data class Artist(val id: String, val name: String) : SpotifyUndo {
        override val contentDescription: String = "Undo follow change"
    }
}

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
    val libraryHasMore: Boolean = false,
    val libraryMorePending: Boolean = false,
    val searchHasMore: Set<SearchScope> = emptySet(),
    val searchMorePending: Set<SearchScope> = emptySet(),
    val collectionMorePending: Set<String> = emptySet(),
    val artistAlbumHasMore: Map<String, Boolean> = emptyMap(),
    val artistTrackHasMore: Map<String, Boolean> = emptyMap(),
    val artistMorePending: Set<String> = emptySet(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
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
    val autoplay: Boolean = true,
    val cacheSizeBytes: Long = 0L,
    val notice: String? = null,
    val undo: SpotifyUndo? = null
) {
    val savedCurrent: Boolean get() = current?.let { it.id in savedTrackIds } ?: false
}

/**
 * State holder for the Sideplay UI. Catalog reads, queue ordering and local history live in
 * their own domain objects; this class only maps them to the single immutable screen state and
 * implements the optimistic save/follow behaviour the action bars expect.
 */
class SpotifyViewModel(
    private val catalog: SpotifyCatalog,
    private val writer: SpotifyLibraryWriter? = null,
    private val queue: SpotifyQueueRepository = SpotifyQueueRepository(),
    private val history: SpotifyHistoryRepository = SpotifyHistoryRepository(),
    private val session: SpotifySession? = null,
    private val openBrowser: (suspend (String) -> Unit)? = null,
    private val clearArtworkCache: () -> Unit = {},
    private val simulatePlayback: Boolean = true
) : ViewModel() {
    private val _state = MutableStateFlow(SpotifyUiState(signedIn = session?.signedIn?.value ?: false))
    val state: StateFlow<SpotifyUiState> = _state.asStateFlow()

    /** The five-second offer; leaving the screen commits the change instead of carrying Undo away. */
    val undoWindow = UndoWindow(viewModelScope) { _state.update { it.copy(undo = null) } }

    private var searchJob: Job? = null
    private var autoplayJob: Job? = null
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
                        state.copy(audioQuality = settings.audioQuality, autoplay = settings.autoplay)
                    }
                }
            }
            it.playback.onNext = nextHandler
            it.playback.onPrevious = previousHandler
            it.playback.onSeek = seekHandler
        }
        restorePlaybackState()
        refreshCacheSize()
        if (session == null || !session.nativeAvailable) {
            if (simulatePlayback) {
                // Local fixtures and builds without the native library still need a moving progress bar.
                viewModelScope.launch {
                    while (true) {
                        delay(1_000L)
                        tick()
                    }
                }
            }
        } else {
            viewModelScope.launch {
                while (true) {
                    delay(300L)
                    val raw = withContext(Dispatchers.IO) { session.pollPlaybackEvent() } ?: continue
                    handlePlaybackEvent(raw)
                }
            }
            viewModelScope.launch {
                while (true) {
                    delay(100L)
                    updatePositionTick()
                }
            }
        }
    }

    /** Adopts the process-wide player and queue after the Sideplay screen was recreated. */
    private fun restorePlaybackState() {
        val session = session ?: return
        val playback = session.playback
        val snapshot = queue.snapshot
        val current = playback.current ?: snapshot.current
        if (current != null && snapshot.current?.id != current.id) {
            queue.restore(snapshot.copy(current = current))
        }
        val restored = queue.snapshot
        anchorPosition(playback.position)
        _state.update {
            it.copy(
                current = current,
                upcoming = restored.upcoming,
                history = restored.history,
                shuffle = restored.shuffle,
                repeat = restored.repeat,
                isPlaying = playback.isActive,
                positionMs = playback.position,
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

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun refresh() = loadAccount()

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
                        .onFailure { SideplayDebug.log("viewModel.ensureCollection key=$key saved lookup failed", it) }
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
                        pendingCollections = it.pendingCollections - key
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SideplayDebug.log("viewModel.ensureCollection key=$key failed", e)
                _state.update {
                    it.copy(pendingCollections = it.pendingCollections - key, notice = playbackNotice(e))
                }
            }
        }
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
                    .onFailure { SideplayDebug.log("viewModel.ensureArtist id=$id followed lookup failed", it) }
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
                        pendingArtists = it.pendingArtists - id
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SideplayDebug.log("viewModel.ensureArtist id=$id failed", e)
                _state.update {
                    it.copy(pendingArtists = it.pendingArtists - id, notice = playbackNotice(e))
                }
            }
        }
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
        applyQueue(snapshot, positionMs = 0L, playing = true)
        _state.update { it.copy(notice = null) }
        startRemote(item, 0L)
    }

    fun playItem(item: SpotifyMediaItem) = play(listOf(item), 0)

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

    fun playArtist(artist: SpotifyArtist) = play(topTracks(artist), 0)

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
        anchorPosition(s.positionMs)
        _state.update { it.copy(isPlaying = play) }
        if (item.uri == null || session == null) return
        viewModelScope.launch {
            try {
                if (play && session.playback.current == null) {
                    // A restored queue outlives the native player. Start the current item instead
                    // of resuming a source that no longer exists.
                    startRemote(item, 0L)
                } else if (play) {
                    if (!session.resume()) {
                        _state.update { it.copy(isPlaying = false) }
                        return@launch
                    }
                } else {
                    session.pause()
                }
                session.playback.updateState(play, if (play) _state.value.positionMs else s.positionMs)
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
            snapshot.current?.let(::recorded)
            applyQueue(snapshot, positionMs = 0L, playing = snapshot.current?.playable == true)
            snapshot.current?.let { startRemote(it, 0L) }
        }
    }

    fun previous() {
        val s = _state.value
        if (s.current == null) return
        if (s.positionMs > 3_000L) {
            _state.update { it.copy(positionMs = 0L) }
            s.current?.uri?.let { uri ->
                session?.let { session ->
                    viewModelScope.launch { runCatching { session.seek(0L) } }
                }
            }
            return
        }
        val snapshot = queue.previous(s.positionMs)
        if (snapshot.current !== s.current) {
            snapshot.current?.let(::recorded)
            applyQueue(snapshot, positionMs = 0L, playing = snapshot.current?.playable == true)
            snapshot.current?.let { startRemote(it, 0L) }
        } else {
            _state.update { it.copy(positionMs = 0L) }
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
        _state.update { it.copy(positionMs = target) }
        if (current.uri != null && session != null) {
            viewModelScope.launch { runCatching { session.seek(target) } }
        }
    }

    fun toggleShuffle() = applyQueue(queue.toggleShuffle(), syncNowPlaying = false)

    fun cycleRepeat() = applyQueue(queue.cycleRepeat(), syncNowPlaying = false)

    fun toggleSaveCurrent() {
        val track = _state.value.current as? SpotifyTrack ?: return
        // A like is a simple toggle: no notice and no Undo offer.
        toggleTrackSaved(track.id, track, null, offerUndo = false)
    }

    fun toggleSaved(track: SpotifyTrack) = toggleTrackSaved(track.id, track, null)

    private fun toggleTrackSaved(
        id: String,
        track: SpotifyTrack?,
        label: String?,
        offerUndo: Boolean = true
    ) {
        val wasSaved = id in _state.value.savedTrackIds
        val saved = !wasSaved
        val action = SpotifyUndo.Track(id, label)
        savedTrackTouched += id
        _state.update {
            val ids = it.savedTrackIds.toMutableSet()
            if (saved) ids.add(id) else ids.remove(id)
            var next = it.copy(
                savedTrackIds = ids,
                notice = label?.let { name -> if (saved) "Saved to $name" else "Removed from $name" },
                undo = if (offerUndo) action else it.undo
            )
            next = if (saved) {
                track?.let { item -> next.withLibraryItemAdded(LibraryFilter.LikedSongs, item) } ?: next
            } else {
                next.withLibraryItemRemoved(LibraryFilter.LikedSongs, id)
            }
            next
        }
        if (offerUndo) undoWindow.start()
        val writer = writer ?: return
        viewModelScope.launch {
            try {
                writer.setTrackSaved(id, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearUndo(action)
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
        collection: SpotifyCollection? = null,
        offerUndo: Boolean = true
    ) {
        val key = collectionKey(type, id)
        val wasSaved = key in _state.value.savedCollectionKeys
        val saved = !wasSaved
        val action = SpotifyUndo.Collection(type, id)
        savedCollectionTouched += key
        _state.update {
            val keys = it.savedCollectionKeys.toMutableSet()
            if (saved) keys.add(key) else keys.remove(key)
            val next = it.copy(
                savedCollectionKeys = keys,
                notice = if (saved) "Saved to Library" else "Removed from Library",
                undo = if (offerUndo) action else it.undo
            )
            if (saved) {
                collection?.let { item -> libraryItemFor(item) }?.let { item ->
                    next.withLibraryItemAdded(libraryFilterFor(type), item)
                } ?: next
            } else {
                next.withLibraryItemRemoved(libraryFilterFor(type), id)
            }
        }
        if (offerUndo) undoWindow.start()
        val writer = writer ?: return
        viewModelScope.launch {
            try {
                writer.setSaved(type, id, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearUndo(action)
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

    fun toggleFollow(artist: SpotifyArtist) = toggleFollow(artist.id, artist.name)

    private fun toggleFollow(id: String, name: String, offerUndo: Boolean = true) {
        val wasFollowing = id in _state.value.followedArtistIds
        val following = !wasFollowing
        val action = SpotifyUndo.Artist(id, name)
        followedArtistTouched += id
        _state.update {
            val ids = it.followedArtistIds.toMutableSet()
            if (following) ids.add(id) else ids.remove(id)
            val next = it.copy(
                followedArtistIds = ids,
                notice = if (following) "Following $name" else "Unfollowed $name",
                undo = if (offerUndo) action else it.undo
            )
            if (following) {
                next.withLibraryItemAdded(
                    LibraryFilter.Artists,
                    SpotifyArtist(id = id, name = name, artRes = 0, albumIds = emptyList())
                )
            } else {
                next.withLibraryItemRemoved(LibraryFilter.Artists, id)
            }
        }
        if (offerUndo) undoWindow.start()
        val writer = writer ?: return
        viewModelScope.launch {
            try {
                writer.setArtistFollowed(id, following)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearUndo(action)
                _state.update {
                    val ids = it.followedArtistIds.toMutableSet()
                    if (wasFollowing) ids.add(id) else ids.remove(id)
                    val next = it.copy(followedArtistIds = ids, notice = playbackNotice(e))
                    if (wasFollowing) {
                        next.withLibraryItemAdded(
                            LibraryFilter.Artists,
                            SpotifyArtist(id = id, name = name, artRes = 0, albumIds = emptyList())
                        )
                    } else {
                        next.withLibraryItemRemoved(LibraryFilter.Artists, id)
                    }
                }
            }
        }
    }

    fun undo() {
        val action = _state.value.undo ?: return
        undoWindow.cancel()
        _state.update { it.copy(undo = null) }
        when (action) {
            is SpotifyUndo.Track -> toggleTrackSaved(
                action.id,
                _state.value.trackById(action.id),
                action.label,
                offerUndo = false
            )

            is SpotifyUndo.Collection -> toggleSaved(
                action.type,
                action.id,
                _state.value.collections[collectionKey(action.type, action.id)],
                offerUndo = false
            )

            is SpotifyUndo.Artist -> toggleFollow(action.id, action.name, offerUndo = false)
        }
    }

    /** Leaving the screen finalises the change instead of offering Undo when you come back. */
    fun commitUndo() {
        undoWindow.cancel()
        _state.update { it.copy(undo = null) }
    }

    private fun clearUndo(action: SpotifyUndo) {
        if (_state.value.undo == action) {
            undoWindow.cancel()
            _state.update { it.copy(undo = null) }
        }
    }

    private fun ensureTrackSaved(id: String) {
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

    fun cycleAudioQuality() {
        val next = _state.value.audioQuality.next()
        _state.update { it.copy(audioQuality = next) }
        session?.setAudioQuality(next)
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

    fun moveToNext(item: SpotifyMediaItem) {
        applyQueue(queue.playNext(item), syncNowPlaying = false)
    }

    fun removeUpcoming(item: SpotifyMediaItem) {
        applyQueue(queue.removeUpcoming(item), syncNowPlaying = false)
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
    }

    fun clearUpcoming(): List<SpotifyMediaItem> {
        val removed = queue.clearUpcoming()
        applyQueue(queue.snapshot, syncNowPlaying = false)
        return removed
    }

    fun restoreUpcoming(items: List<SpotifyMediaItem>) = applyQueue(queue.restoreUpcoming(items), syncNowPlaying = false)

    fun jumpTo(id: String) {
        val s = _state.value
        if (s.current?.id == id) {
            _state.update { it.copy(positionMs = 0L, isPlaying = true) }
            return
        }
        val snapshot = queue.jumpTo(id)
        if (snapshot.current !== s.current) {
            snapshot.current?.let(::recorded)
            applyQueue(snapshot, positionMs = 0L, playing = snapshot.current?.playable == true)
            snapshot.current?.let { startRemote(it, 0L) }
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
        if (_state.value.authPending) return
        _state.update { it.copy(authPending = true, notice = null) }
        viewModelScope.launch {
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
        _state.update { it.copy(librarySectionsLoading = true) }
        viewModelScope.launch {
            try {
                val sections = LinkedHashMap<LibraryFilter, List<Any>>()
                val hasMore = mutableSetOf<LibraryFilter>()
                var notice: String? = null
                for (filter in LibraryFilter.entries) {
                    try {
                        val page = catalog.library(filter, 0, LIBRARY_SECTION_SIZE + 1)
                        sections[filter] = page.items.take(LIBRARY_SECTION_SIZE)
                        if (page.items.size > LIBRARY_SECTION_SIZE) hasMore += filter
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One failing category should not hide the rest of the library.
                        if (notice == null) notice = playbackNotice(e)
                    }
                }
                _state.update { current ->
                    var next = current.copy(
                        librarySections = sections,
                        librarySectionHasMore = hasMore,
                        librarySectionsLoading = false,
                        notice = notice ?: current.notice
                    )
                    for ((filter, items) in sections) {
                        next = next.withSectionSaved(filter, items)
                    }
                    next
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(librarySectionsLoading = false, notice = playbackNotice(e)) }
            }
        }
    }

    private fun loadLibrary(filter: LibraryFilter) {
        if (!_state.value.signedIn) return
        _state.update { it.copy(libraryLoading = true, libraryMorePending = false) }
        viewModelScope.launch {
            try {
                val page = catalog.library(filter, 0, SPOTIFY_PAGE_SIZE)
                _state.update {
                    if (it.filter == filter) {
                        it.withLibrarySaved(filter, page.items).copy(
                            libraryItems = page.items,
                            libraryHasMore = page.hasMore,
                            libraryLoading = false
                        )
                    } else {
                        it
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(libraryLoading = false, notice = playbackNotice(e)) }
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
        undoWindow.cancel()
        queue.stop()
        history.clear()
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
                followedArtistIds = emptySet()
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
            episodeCount = collection.totalItems
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
        if (positionMs != null) anchorPosition(positionMs)
        _state.update {
            it.copy(
                current = snapshot.current,
                upcoming = snapshot.upcoming,
                history = snapshot.history,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat,
                positionMs = positionMs ?: it.positionMs,
                isPlaying = playing ?: it.isPlaying
            )
        }
        if (syncNowPlaying) {
            session?.playback?.setNowPlaying(snapshot.current, positionMs ?: _state.value.positionMs)
        }
    }

    private fun recorded(item: SpotifyMediaItem) {
        history.record(item, System.currentTimeMillis())
        _state.update { it.copy(recent = history.items()) }
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
        _state.update { it.copy(searchPending = true, searchHasMore = emptySet(), searchMorePending = emptySet()) }
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            val scopes = _state.value.searchScopes
            val results = try {
                if (_state.value.signedIn) catalog.search(query, scopes, 0, SPOTIFY_SEARCH_PAGE_SIZE) else SpotifySearchResults()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = playbackNotice(e)) }
                SpotifySearchResults()
            }
            _state.update {
                if (it.query == query && it.searchScopes == scopes) {
                    it.copy(
                        searchResults = results,
                        searchPending = false,
                        searchHasMore = scopes.filterTo(mutableSetOf()) { scope -> results.sizeFor(scope) >= SPOTIFY_SEARCH_PAGE_SIZE }
                    )
                } else {
                    it
                }
            }
        }
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
        val position = event.optLong("positionMs", _state.value.positionMs)
        val type = event.opt("type")?.toString().orEmpty()
        SideplayDebug.log("event type=$type uri=${event.opt("uri")?.toString().orEmpty()} positionMs=$position")
        when (type) {
            "loading" -> if (matchesCurrent(event)) {
                anchorPosition(position)
                session?.playback?.updatePosition(position)
                _state.update { it.copy(positionMs = position) }
            }

            "playing" -> if (matchesCurrent(event)) {
                val playback = session?.playback
                // Ignore a playing event that belongs to a command the user already superseded
                // with a pause. Otherwise rapid taps leave the UI showing play with no sound.
                if (playback?.shouldApplyEvent(true) != false) {
                    // Keep the UI clock where it is. librespot's event position is the decoder
                    // position, which is ahead of the speaker by the PCM buffer; adopting it
                    // would make the bar jump forward on resume.
                    val current = _state.value.positionMs
                    anchorPosition(current)
                    playback?.updateState(true, current)
                    _state.update { it.copy(isPlaying = true, positionMs = current) }
                }
            }

            "paused" -> if (matchesCurrent(event)) {
                val playback = session?.playback
                // Ignore a paused event that belongs to a command the user already superseded
                // with a resume. Otherwise the UI flickers back to paused after a resume.
                if (playback?.shouldApplyEvent(false) != false) {
                    val current = _state.value.positionMs
                    anchorPosition(current)
                    playback?.updateState(false, current)
                    _state.update { it.copy(isPlaying = false, positionMs = current) }
                }
            }

            "position" -> {
                // The 100 ms ticker owns the seek bar. Only adopt an event position when it is
                // far enough away to be a real seek from another client, not the decoder's
                // normal lead over the speaker.
                val current = _state.value.positionMs
                // Ignore the decoder's stale position events while a local seek is settling.
                val settling = SystemClock.elapsedRealtime() - lastSeekAt < 1_500L
                if (!settling && abs(position - current) > 2_000L) {
                    anchorPosition(position)
                    session?.playback?.updatePosition(position)
                    _state.update { it.copy(positionMs = position) }
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
                // Stop from the notification or lock screen: clear both the UI and the queue so
                // reopening Sideplay cannot restore the track that was stopped.
                session?.playback?.updateState(false, 0L)
                applyQueue(queue.stop(), positionMs = 0L, playing = false)
            }

            "unavailable" -> if (matchesCurrent(event)) {
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
        if (snapshot.repeat == RepeatMode.Track) {
            _state.update { it.copy(positionMs = 0L, isPlaying = true) }
            startRemote(current, 0L)
        } else {
            advance(auto = true, forceContextLoop = snapshot.repeat == RepeatMode.Context)
        }
    }

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
        val next = positionAnchorMs + (SystemClock.elapsedRealtime() - positionAnchorAt)
        val clamped = next.coerceIn(0L, current.durationMs)
        if (clamped != s.positionMs) {
            session?.playback?.updatePosition(clamped)
            _state.update { it.copy(positionMs = clamped) }
        }
    }

    private fun tick() {
        val s = _state.value
        val current = s.current ?: return
        if (!s.isPlaying) return
        val nextPosition = s.positionMs + 1_000L
        if (nextPosition < current.durationMs) {
            _state.update { it.copy(positionMs = nextPosition) }
        } else {
            when (s.repeat) {
                RepeatMode.Track -> _state.update { it.copy(positionMs = 0L) }
                RepeatMode.Context -> advance(auto = true, forceContextLoop = true)
                RepeatMode.Off -> advance(auto = true, forceContextLoop = false)
            }
        }
    }

    private fun advance(auto: Boolean, forceContextLoop: Boolean) {
        val s = _state.value
        val current = s.current ?: return
        val hasNext = s.upcoming.isNotEmpty()
        if (!hasNext && forceContextLoop) {
            val snapshot = queue.next(auto = true)
            snapshot.current?.let(::recorded)
            applyQueue(snapshot, positionMs = 0L, playing = snapshot.current?.playable == true)
            snapshot.current?.let { startRemote(it, 0L) }
            return
        }
        if (!hasNext && !auto) return
        if (!hasNext) {
            if (_state.value.autoplay) autoplaySimilar(current) else stopAtQueueEnd()
            return
        }
        val snapshot = queue.next(auto = true)
        if (snapshot.current !== current) {
            snapshot.current?.let(::recorded)
            applyQueue(snapshot, positionMs = 0L, playing = snapshot.current?.playable == true)
            snapshot.current?.let { startRemote(it, 0L) }
        } else {
            _state.update { it.copy(isPlaying = false, positionMs = 0L) }
        }
    }

    /**
     * The queue ran out with Autoplay on. Ask the catalog for the current artist's top tracks,
     * drop anything already in the queue or history, and let the normal advance path start the
     * first one. The guard keeps a slow request from hijacking a track the user started meanwhile.
     */
    private fun autoplaySimilar(current: SpotifyMediaItem) {
        if (autoplayJob?.isActive == true) return
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
                if (_state.value.current?.id == track.id) stopAtQueueEnd()
            }
        }
    }

    private fun stopAtQueueEnd() {
        _state.update { it.copy(isPlaying = false, positionMs = 0L) }
    }

    /** Starts a real Spotify item; local test fixtures have no URI and keep the local simulation. */
    private fun startRemote(item: SpotifyMediaItem, positionMs: Long) {
        val uri = item.uri ?: return
        val session = session ?: return
        SideplayDebug.log("viewModel.startRemote uri=$uri positionMs=$positionMs")
        anchorPosition(positionMs)
        session.playback.setNowPlaying(item, positionMs)
        viewModelScope.launch {
            try {
                session.play(uri, positionMs)
                SideplayDebug.log("viewModel.startRemote returned")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SideplayDebug.log("viewModel.startRemote failed", e)
                _state.update { it.copy(isPlaying = false, notice = playbackNotice(e)) }
            }
        }
    }

    private fun playbackNotice(error: Exception): String = when ((error as? SpotifyGatewayException)?.failure) {
        SpotifyFailure.Unavailable -> "Spotify playback is unavailable in this build"
        SpotifyFailure.NotConnected -> "Playback is not ready — try again"
        SpotifyFailure.AuthenticationExpired -> "Spotify session expired — sign in again"
        SpotifyFailure.Network -> "Couldn't reach Spotify"
        SpotifyFailure.RateLimited -> "Spotify is rate-limiting requests — try again shortly"
        SpotifyFailure.TrackUnavailable -> "This track isn't available"
        SpotifyFailure.Unknown, null -> error.message ?: "Playback failed"
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
