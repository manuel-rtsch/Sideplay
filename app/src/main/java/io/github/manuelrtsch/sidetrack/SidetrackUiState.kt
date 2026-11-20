package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind

data class SidetrackUiState(
    val signedIn: Boolean = false,
    val current: MediaItem? = null,
    val upcoming: List<MediaItem> = emptyList(),
    val history: List<MediaItem> = emptyList(),
    val recent: List<MediaItem> = emptyList(),
    /** Every category opened this session, so switching back paints without a fetch. */
    val libraryPages: Map<LibraryFilter, LibraryPage> = emptyMap(),
    val librarySections: Map<LibraryFilter, List<Any>> = emptyMap(),
    val librarySectionHasMore: Set<LibraryFilter> = emptySet(),
    val searchResults: SearchResults = SearchResults(),
    val searchPending: Boolean = false,
    val authPending: Boolean = false,
    /** Categories whose first page is in flight with nothing cached to show meanwhile. */
    val libraryPendingPages: Set<LibraryFilter> = emptySet(),
    val librarySectionsLoading: Boolean = false,
    /** Set when the startup preload could not load any category. */
    val librarySectionsError: String? = null,
    /** Set when a search request failed and there are no results to keep on screen. */
    val searchError: String? = null,
    /** Failed collection loads, keyed by "type:id", so the screen can offer a retry. */
    val collectionErrors: Map<String, String> = emptyMap(),
    /** Failed artist loads, keyed by artist id, so the screen can offer a retry. */
    val artistErrors: Map<String, String> = emptyMap(),
    val libraryMorePendingPages: Set<LibraryFilter> = emptySet(),
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
    val artistRadioError: String? = null,
    /** The playlists the Add-to-playlist picker may offer: only ones this account may add to. */
    val playlistChoices: List<Playlist> = emptyList(),
    val playlistChoicesLoading: Boolean = false,
    val playlistChoicesLoaded: Boolean = false,
    val playlistChoicesHasMore: Boolean = false,
    val playlistChoicesError: String? = null
) {
    val savedCurrent: Boolean get() = current?.let { it.key.encode() in savedTrackIds } ?: false

    // The open category is a projection of [libraryPages]: the cache is the only copy of the rows,
    // so a page that arrives for a category the user has left is still worth keeping.
    val libraryItems: List<Any> get() = libraryPages[filter]?.items.orEmpty()
    val libraryHasMore: Boolean get() = libraryPages[filter]?.hasMore == true
    /** Set when the open category could not load and has no items to show. */
    val libraryError: String? get() = libraryPages[filter]?.error
    val libraryLoading: Boolean get() = filter in libraryPendingPages
    val libraryMorePending: Boolean get() = filter in libraryMorePendingPages
}

/** Sets the transient bar message together with the kind the bar renders it with. */
internal fun SidetrackUiState.say(text: String, kind: NoticeKind = NoticeKind.Other) = copy(notice = text, noticeKind = kind)
