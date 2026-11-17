package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind

/**
 * Focused views of [SpotifyUiState].
 *
 * The screen state used to be one large data class, so any change (a notice, a save toggle, a
 * search keystroke) produced a new instance and recomposed every screen that collected it. Each
 * slice below is a small, structurally comparable value: a screen collects only the slices it
 * reads, and a change to an unrelated slice no longer reaches it.
 *
 * The slices are derived from the single [SpotifyUiState] source of truth, so the ViewModel's
 * update sites stay unchanged.
 */
data class PlaybackUiState(
    val current: SpotifyMediaItem? = null,
    val upcoming: List<SpotifyMediaItem> = emptyList(),
    val history: List<SpotifyMediaItem> = emptyList(),
    val recent: List<SpotifyMediaItem> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off
)

data class LibraryUiState(
    val libraryItems: List<Any> = emptyList(),
    val librarySections: Map<LibraryFilter, List<Any>> = emptyMap(),
    val librarySectionHasMore: Set<LibraryFilter> = emptySet(),
    val libraryLoading: Boolean = false,
    val librarySectionsLoading: Boolean = false,
    val librarySectionsError: String? = null,
    val libraryError: String? = null,
    val libraryHasMore: Boolean = false,
    val libraryMorePending: Boolean = false,
    val filter: LibraryFilter = LibraryFilter.LikedSongs,
    val collections: Map<String, SpotifyCollection> = emptyMap(),
    val artists: Map<String, SpotifyArtist> = emptyMap(),
    val artistAlbums: Map<String, List<SpotifyAlbum>> = emptyMap(),
    val artistTracks: Map<String, List<SpotifyTrack>> = emptyMap(),
    val pendingCollections: Set<String> = emptySet(),
    val pendingArtists: Set<String> = emptySet(),
    val collectionErrors: Map<String, String> = emptyMap(),
    val artistErrors: Map<String, String> = emptyMap(),
    val collectionMorePending: Set<String> = emptySet(),
    val artistAlbumHasMore: Map<String, Boolean> = emptyMap(),
    val artistTrackHasMore: Map<String, Boolean> = emptyMap(),
    val artistMorePending: Set<String> = emptySet()
)

data class SearchUiState(
    val searchResults: SpotifySearchResults = SpotifySearchResults(),
    val searchPending: Boolean = false,
    val searchError: String? = null,
    val searchHasMore: Set<SearchScope> = emptySet(),
    val searchMorePending: Set<SearchScope> = emptySet(),
    val searchScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    val query: String = ""
)

data class AccountUiState(
    val signedIn: Boolean = false,
    val authPending: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.High,
    val normalization: Boolean = false,
    val gapless: Boolean = true,
    val autoplay: Boolean = true,
    val cacheSizeBytes: Long = 0L
)

data class SavedUiState(
    val savedTrackIds: Set<String> = emptySet(),
    val savedCollectionKeys: Set<String> = emptySet(),
    val followedArtistIds: Set<String> = emptySet()
)

/**
 * The transient action-bar state: a notice or the now-playing confirmation.
 *
 * [noticeKind] decides how long the notice owns the bar. It travels with [notice] rather than
 * living in the screen, so the bar's timer is the same on every screen and a later success cannot
 * inherit an earlier error's duration.
 */
data class ChromeUiState(
    val notice: String? = null,
    val noticeKind: NoticeKind = NoticeKind.Other,
    val nowPlayingNotice: SpotifyMediaItem? = null
)

data class RadioUiState(
    val radio: SpotifyCollection? = null,
    val radioSeedId: String? = null,
    val radioPending: Boolean = false,
    val radioError: String? = null,
    val artistRadio: SpotifyCollection? = null,
    val artistRadioArtistId: String? = null,
    val artistRadioPending: Boolean = false,
    val artistRadioError: String? = null
)

val SpotifyUiState.playback: PlaybackUiState
    get() = PlaybackUiState(current, upcoming, history, recent, isPlaying, isBuffering, shuffle, repeat)

val SpotifyUiState.library: LibraryUiState
    get() = LibraryUiState(
        libraryItems = libraryItems,
        librarySections = librarySections,
        librarySectionHasMore = librarySectionHasMore,
        libraryLoading = libraryLoading,
        librarySectionsLoading = librarySectionsLoading,
        librarySectionsError = librarySectionsError,
        libraryError = libraryError,
        libraryHasMore = libraryHasMore,
        libraryMorePending = libraryMorePending,
        filter = filter,
        collections = collections,
        artists = artists,
        artistAlbums = artistAlbums,
        artistTracks = artistTracks,
        pendingCollections = pendingCollections,
        pendingArtists = pendingArtists,
        collectionErrors = collectionErrors,
        artistErrors = artistErrors,
        collectionMorePending = collectionMorePending,
        artistAlbumHasMore = artistAlbumHasMore,
        artistTrackHasMore = artistTrackHasMore,
        artistMorePending = artistMorePending
    )

val SpotifyUiState.search: SearchUiState
    get() = SearchUiState(
        searchResults = searchResults,
        searchPending = searchPending,
        searchError = searchError,
        searchHasMore = searchHasMore,
        searchMorePending = searchMorePending,
        searchScopes = searchScopes,
        query = query
    )

val SpotifyUiState.account: AccountUiState
    get() = AccountUiState(
        signedIn = signedIn,
        authPending = authPending,
        audioQuality = audioQuality,
        normalization = normalization,
        gapless = gapless,
        autoplay = autoplay,
        cacheSizeBytes = cacheSizeBytes
    )

val SpotifyUiState.saved: SavedUiState
    get() = SavedUiState(savedTrackIds, savedCollectionKeys, followedArtistIds)

val SpotifyUiState.chrome: ChromeUiState
    get() = ChromeUiState(notice, noticeKind, nowPlayingNotice)

val SpotifyUiState.radioSlice: RadioUiState
    get() = RadioUiState(
        radio = radio,
        radioSeedId = radioSeedId,
        radioPending = radioPending,
        radioError = radioError,
        artistRadio = artistRadio,
        artistRadioArtistId = artistRadioArtistId,
        artistRadioPending = artistRadioPending,
        artistRadioError = artistRadioError
    )
