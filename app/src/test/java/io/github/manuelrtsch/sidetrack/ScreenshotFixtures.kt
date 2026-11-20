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
        libraryPages = mapOf(
            LibraryFilter.Albums to LibraryPage(
                runBlocking { MockSpotifyDataSource.library(LibraryFilter.Albums).items }
            )
        ),
        librarySections = runBlocking {
            LibraryFilter.entries.associateWith { MockSpotifyDataSource.library(it, 0, LIBRARY_SECTION_SIZE).items }
        },
        librarySectionHasMore = LibraryFilter.entries.toSet(),
        searchPages = mapOf(
            "night" to SearchScope.entries.associateWith { scope ->
                val items = runBlocking { MockSpotifyDataSource.search("night", setOf(scope)) }.itemsFor(scope)
                LibraryPage(items = items, hasMore = items.size >= SEARCH_PAGE_SIZE)
            }
        ),
        isPlaying = true,
        savedTrackIds = setOf(current.id),
        filter = LibraryFilter.Albums
    )
}
