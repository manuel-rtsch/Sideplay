package io.github.manuelrtsch.sidetrack

import kotlinx.coroutines.runBlocking

/**
 * The demo state the screenshots are rendered from: one current track, a seeded history and a
 * full library, all served by [MockSpotifyDataSource]'s fictional pack so no personal content is
 * ever drawn.
 */
internal fun screenshotState(): SidetrackUiState {
    val recent = MockSpotifyDataSource.recentlyPlayed
    val current = recent.first()
    return SidetrackUiState(
        current = current,
        upcoming = recent.drop(1),
        history = recent.drop(3),
        recent = recent,
        libraryItems = runBlocking { MockSpotifyDataSource.library(LibraryFilter.Albums).items },
        librarySections = runBlocking {
            LibraryFilter.entries.associateWith { MockSpotifyDataSource.library(it, 0, LIBRARY_SECTION_SIZE).items }
        },
        librarySectionHasMore = LibraryFilter.entries.toSet(),
        searchResults = runBlocking { MockSpotifyDataSource.search("night", SearchScope.entries.toSet()) },
        searchHasMore = SearchScope.entries.toSet(),
        isPlaying = true,
        savedTrackIds = setOf(current.id),
        filter = LibraryFilter.Albums
    )
}
