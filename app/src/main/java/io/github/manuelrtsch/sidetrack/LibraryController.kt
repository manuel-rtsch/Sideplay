package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Paging for everything the library shows: the five category previews, the open category, a
 * collection page and an artist page.
 *
 * All of them follow the same rules — the first page replaces, later pages append, a response for
 * a category the user left is dropped, and one failing category must not erase the others. Those
 * rules used to be spread over the ViewModel between unrelated save and playback code.
 */
internal class LibraryController(
    private val store: UiStateStore,
    private val catalog: MediaCatalog,
    private val mediaKeys: MediaKeys,
    private val driver: PlaybackDriver,
    private val session: ServiceRuntime?,
    private val supportedFilters: () -> Set<LibraryFilter>,
    private val online: () -> Boolean,
    private val hints: SavedHints,
    private val notify: (String) -> Unit,
    private val scope: CoroutineScope
) {

    private var librarySectionsJob: Job? = null
    private var libraryJob: Job? = null
    private var libraryCategoryLoaded = false

    private fun playbackNotice(error: Exception): String = serviceMessage(error, online())

    /** True once the open category has been fetched, so a reconnect knows to retry it. */
    fun hasLoadedCategory(): Boolean = libraryCategoryLoaded

    /** Drops the account's pages. Called when the user signs out. */
    fun reset() {
        librarySectionsJob?.cancel()
        libraryJob?.cancel()
        libraryCategoryLoaded = false
    }

    fun collection(type: CollectionType, id: String): Collection? = collection(mediaKeys.media(type, id))

    fun collection(key: MediaKey): Collection? = store.value.collections[key.encode()]

    fun artist(id: String): Artist? = store.value.artists[id]

    fun albumsBy(artist: Artist): List<Album> = store.value.artistAlbums[artist.id].orEmpty()

    fun topTracks(artist: Artist): List<Track> = store.value.artistTracks[artist.id].orEmpty()

    fun ensureCollection(type: CollectionType, id: String) = ensureCollection(mediaKeys.media(type, id))

    fun retryCollection(type: CollectionType, id: String) = retryCollection(mediaKeys.media(type, id))

    fun loadMoreCollection(type: CollectionType, id: String) = loadMoreCollection(mediaKeys.media(type, id))

    fun ensureCollection(key: MediaKey) {
        val cacheKey = key.encode()
        val snapshot = store.value
        if (cacheKey in snapshot.collections || cacheKey in snapshot.pendingCollections) return
        store.update { it.copy(pendingCollections = it.pendingCollections + cacheKey) }
        scope.launch {
            try {
                val collection = catalog.collection(key, 0, CATALOG_PAGE_SIZE)
                val saved = if (collection == null) null else hints.savedCollection(key, cacheKey)
                store.update {
                    val keys = it.savedCollectionKeys.toMutableSet()
                    if (saved != null) {
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
                store.update {
                    it.copy(
                        pendingCollections = it.pendingCollections - cacheKey,
                        collectionErrors = it.collectionErrors + (cacheKey to playbackNotice(e))
                    )
                }
            }
        }
    }

    fun retryCollection(key: MediaKey) {
        val cacheKey = key.encode()
        if (cacheKey in store.value.pendingCollections) return
        store.update { it.copy(collectionErrors = it.collectionErrors - cacheKey) }
        ensureCollection(key)
    }

    fun loadMoreCollection(key: MediaKey) {
        val cacheKey = key.encode()
        val snapshot = store.value
        val collection = snapshot.collections[cacheKey] ?: return
        if (!collection.hasMore || cacheKey in snapshot.collectionMorePending) return
        store.update { it.copy(collectionMorePending = it.collectionMorePending + cacheKey) }
        scope.launch {
            try {
                val next = catalog.collection(key, collection.items.size, CATALOG_PAGE_SIZE)
                store.update { state ->
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
                store.update {
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
        val snapshot = store.value
        if (snapshot.artistAlbumHasMore[id] != true || id in snapshot.artistMorePending) return
        store.update { it.copy(artistMorePending = it.artistMorePending + id) }
        scope.launch {
            try {
                val page = catalog.albums(artist.key, snapshot.artistAlbums[id].orEmpty().size, ARTIST_PAGE_SIZE)
                store.update {
                    it.copy(
                        artistAlbums = it.artistAlbums + (id to (it.artistAlbums[id].orEmpty() + page.items).distinctBy { album -> album.id }),
                        artistAlbumHasMore = it.artistAlbumHasMore + (id to page.hasMore),
                        artistMorePending = it.artistMorePending - id
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
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
        val snapshot = store.value
        if (snapshot.artistTrackHasMore[id] != true || id in snapshot.artistMorePending) return
        store.update { it.copy(artistMorePending = it.artistMorePending + id) }
        scope.launch {
            try {
                val page = catalog.topTracks(artist.key, snapshot.artistTracks[id].orEmpty().size, ARTIST_PAGE_SIZE)
                store.update {
                    it.copy(
                        artistTracks = it.artistTracks + (id to (it.artistTracks[id].orEmpty() + page.items).distinctBy { track -> track.id }),
                        artistTrackHasMore = it.artistTrackHasMore + (id to page.hasMore),
                        artistMorePending = it.artistMorePending - id
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
                    it.copy(
                        artistTrackHasMore = it.artistTrackHasMore + (id to false),
                        artistMorePending = it.artistMorePending - id
                    ).say(playbackNotice(e))
                }
            }
        }
    }

    suspend fun loadAll(collection: Collection): Collection {
        var current = collection
        while (current.hasMore) {
            val next = catalog.collection(mediaKeys.media(current.type, current.id), current.items.size, CATALOG_PAGE_SIZE) ?: break
            current = current.append(next)
            store.update {
                it.copy(collections = it.collections + (mediaKeys.collection(current.type, current.id) to current))
            }
        }
        return current
    }

    fun ensureArtist(id: String) {
        val snapshot = store.value
        if (id in snapshot.artists || id in snapshot.pendingArtists) return
        store.update { it.copy(pendingArtists = it.pendingArtists + id) }
        scope.launch {
            try {
                val artist = catalog.artist(mediaKeys.artist(id))
                if (artist == null) {
                    store.update { it.copy(pendingArtists = it.pendingArtists - id) }
                    return@launch
                }
                val albums = catalog.albums(artist.key, 0, ARTIST_PAGE_SIZE)
                val tracks = catalog.topTracks(artist.key, 0, ARTIST_PAGE_SIZE)
                val followed = hints.followedArtist(mediaKeys.artist(id), id)
                store.update {
                    val ids = it.followedArtistIds.toMutableSet()
                    if (followed != null) {
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
                store.update {
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
        if (id in store.value.pendingArtists) return
        store.update { it.copy(artistErrors = it.artistErrors - id) }
        ensureArtist(id)
    }

    fun playCollection(collection: Collection, index: Int) {
        if (!collection.hasMore) {
            driver.play(collection.items, index)
            return
        }
        val key = mediaKeys.collection(collection.type, collection.id)
        store.update { it.copy(collectionMorePending = it.collectionMorePending + key) }
        scope.launch {
            try {
                driver.play(loadAll(collection).items, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { it.say(playbackNotice(e)) }
            } finally {
                store.update { it.copy(collectionMorePending = it.collectionMorePending - key) }
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
        if (id in store.value.artistMorePending) return
        store.update { it.copy(artistMorePending = it.artistMorePending + id) }
        scope.launch {
            try {
                val tracks = loadAllArtistTracks(artist)
                if (tracks.isEmpty()) {
                    notify("No top tracks for this artist")
                    return@launch
                }
                driver.play(tracks, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { it.say(playbackNotice(e)) }
            } finally {
                store.update { it.copy(artistMorePending = it.artistMorePending - id) }
            }
        }
    }

    suspend fun loadAllArtistTracks(artist: Artist): List<Track> {
        val id = artist.id
        var tracks = topTracks(artist)
        var hasMore = store.value.artistTrackHasMore[id] == true
        while (hasMore) {
            val page = catalog.topTracks(artist.key, tracks.size, ARTIST_PAGE_SIZE)
            tracks = (tracks + page.items).distinctBy { track -> track.id }
            hasMore = page.hasMore
            val updated = tracks
            val more = hasMore
            store.update {
                it.copy(
                    artistTracks = it.artistTracks + (id to updated),
                    artistTrackHasMore = it.artistTrackHasMore + (id to more)
                )
            }
        }
        return tracks
    }

    fun setFilter(filter: LibraryFilter) {
        // A category the service cannot fill is not a choice: the row disables it, and this guard
        // keeps a programmatic or restored selection from landing on an empty page.
        val supported = filter.takeIf { it in supportedFilters() } ?: supportedFilters().firstOrNull() ?: LibraryFilter.LikedSongs
        store.update {
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
        load(supported)
    }

    /**
     * Loads a short preview of every library category after sign-in. The categories are
     * requested one after another so the native collection cache is filled once for the shared
     * sets instead of racing several identical full reads. The previews also seed saved-state
     * hints and let an offline app know what the account holds.
     */
    fun loadSections() {
        if (!store.value.signedIn) return
        librarySectionsJob?.cancel()
        store.update { it.copy(librarySectionsLoading = true, librarySectionsError = null) }
        librarySectionsJob = scope.launch {
            try {
                val sections = LinkedHashMap<LibraryFilter, List<Any>>()
                val hasMore = mutableSetOf<LibraryFilter>()
                var notice: String? = null
                for (filter in supportedFilters()) {
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
                store.update { current ->
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
                        next = next.withSectionSaved(filter, items, mediaKeys)
                    }
                    next
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
                    it.copy(
                        librarySectionsLoading = false,
                        librarySectionsError = playbackNotice(e)
                    )
                }
            }
        }
    }

    fun load(filter: LibraryFilter) {
        if (!store.value.signedIn) return
        libraryCategoryLoaded = true
        libraryJob?.cancel()
        store.update { it.copy(libraryLoading = true, libraryMorePending = false, libraryError = null) }
        libraryJob = scope.launch {
            try {
                val page = catalog.library(filter.section(), 0, CATALOG_PAGE_SIZE)
                store.update {
                    if (it.filter == filter) {
                        it.withLibrarySaved(filter, page.items, mediaKeys).copy(
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
                store.update {
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
        val snapshot = store.value
        if (!snapshot.libraryHasMore || snapshot.libraryMorePending || snapshot.libraryLoading) return
        val filter = snapshot.filter
        val offset = snapshot.libraryItems.size
        store.update { it.copy(libraryMorePending = true) }
        scope.launch {
            try {
                val page = catalog.library(filter.section(), offset, CATALOG_PAGE_SIZE)
                store.update {
                    if (it.filter != filter) {
                        it.copy(libraryMorePending = false)
                    } else {
                        val merged = (it.libraryItems + page.items).distinctBy(::libraryItemKey)
                        it.withLibrarySaved(filter, merged, mediaKeys).copy(
                            libraryItems = merged,
                            libraryHasMore = page.hasMore,
                            libraryMorePending = false
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update {
                    it.copy(libraryMorePending = false, libraryHasMore = false).say(playbackNotice(e))
                }
            }
        }
    }

    fun refresh() {
        if (!store.value.signedIn) return
        load(store.value.filter)
    }

    fun retry() {
        if (!store.value.signedIn) return
        load(store.value.filter)
    }

    private fun Collection.append(next: Collection): Collection = copy(
        items = items + next.items,
        totalItems = next.totalItems,
        hasMore = next.hasMore
    )
}
