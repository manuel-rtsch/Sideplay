package io.github.manuelrtsch.sidetrack

// The pure half of the library: how server pages, save toggles and follow toggles fold into
// SidetrackUiState.
//
// These are extension functions rather than methods on the ViewModel because they are pure state
// transforms. They read no service, no coroutines and no Android types: the keys they need are
// passed in, which makes every branch here testable on its own.

internal fun SidetrackUiState.withSectionSaved(
    filter: LibraryFilter,
    items: List<Any>,
    keys: MediaKeys
): SidetrackUiState = when (filter) {
    LibraryFilter.LikedSongs -> copy(
        savedTrackIds = savedTrackIds + items.filterIsInstance<Track>().map { keys.track(it.id).encode() }
    )

    LibraryFilter.Albums -> copy(
        savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<Album>()
            .map { keys.collection(CollectionType.Album, it.id) }
    )

    LibraryFilter.Playlists -> copy(
        savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<Playlist>()
            .map { keys.collection(CollectionType.Playlist, it.id) }
    )

    LibraryFilter.Shows -> copy(
        savedCollectionKeys = savedCollectionKeys + items.filterIsInstance<Show>()
            .map { keys.collection(CollectionType.Show, it.id) }
    )

    LibraryFilter.Artists -> copy(
        followedArtistIds = followedArtistIds + items.filterIsInstance<Artist>().map { keys.artist(it.id).encode() }
    )
}

/**
 * Server pages add to the known state; they never discard items learned from another page.
 * Only the saved hints are adopted, which is why this delegates to [withSectionSaved].
 */
internal fun SidetrackUiState.withLibrarySaved(
    filter: LibraryFilter,
    items: List<Any>,
    keys: MediaKeys
): SidetrackUiState = withSectionSaved(filter, items, keys)

/**
 * Prepends a just-saved item to its library section and to that category's cached page set.
 *
 * The cached page is patched rather than dropped so the save shows up the moment the user walks
 * back to that category. A category that was never opened has no page to patch: its first fetch
 * will bring the saved item anyway.
 */
internal fun SidetrackUiState.withLibraryItemAdded(
    filter: LibraryFilter,
    item: Any,
    keys: MediaKeys
): SidetrackUiState {
    val id = libraryItemId(item) ?: return this
    val section = listOf(item) + librarySections[filter].orEmpty().filterNot { libraryItemId(it) == id }
    val page = libraryPages[filter]
    return copy(
        librarySections = librarySections + (filter to section.take(LIBRARY_SECTION_SIZE)),
        libraryPages = if (page == null) {
            libraryPages
        } else {
            libraryPages + (filter to page.copy(items = listOf(item) + page.items.filterNot { libraryItemId(it) == id }))
        }
    )
}

/** Removes a just-unsaved item from its library section and from that category's cached page set. */
internal fun SidetrackUiState.withLibraryItemRemoved(
    filter: LibraryFilter,
    id: String,
    keys: MediaKeys
): SidetrackUiState {
    fun matches(item: Any) = libraryItemId(item) == id

    val page = libraryPages[filter]
    val removedFromPage = page?.items?.any(::matches) == true
    return copy(
        librarySections = librarySections.mapValues { (section, items) ->
            if (section == filter) items.filterNot(::matches) else items
        },
        libraryPages = if (page == null) {
            libraryPages
        } else {
            libraryPages + (filter to page.copy(
                items = if (removedFromPage) page.items.filterNot(::matches) else page.items,
                // Removing a row makes space for the next server page, even when the list was full.
                hasMore = if (removedFromPage) true else page.hasMore
            ))
        }
    )
}

fun libraryItemFor(collection: Collection): Any? = when (collection.type) {
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
        actions = collection.playlistActions ?: PlaylistActions.None,
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

fun libraryFilterFor(type: CollectionType): LibraryFilter = when (type) {
    CollectionType.Album -> LibraryFilter.Albums
    CollectionType.Playlist -> LibraryFilter.Playlists
    CollectionType.Show -> LibraryFilter.Shows
}
