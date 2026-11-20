package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search caches the five result lists per (query, category), so toggling a scope must not drop the
 * pages of the categories it leaves untouched. These rules keep that cache coherent, mirroring the
 * library's per-category page set.
 */
class SearchPageCacheTest {

    private val track = MockSpotifyDataSource.recentlyPlayed.first()
    private val track2 = MockSpotifyDataSource.recentlyPlayed.drop(1).first()
    private val album = MockSpotifyDataSource.albums.first()
    private val artist = MockSpotifyDataSource.artists.first()
    private val playlist = MockSpotifyDataSource.playlists.first()
    private val show = MockSpotifyDataSource.shows.first()

    private fun state(vararg pages: Pair<SearchScope, LibraryPage>, query: String = "night") = SidetrackUiState(
        query = query,
        searchPages = mapOf(query to pages.toMap())
    )

    @Test
    fun `the open result set is a projection of its own query's pages`() {
        val opened = state(
            SearchScope.Tracks to LibraryPage(listOf(track), hasMore = true),
            SearchScope.Albums to LibraryPage(listOf(album)),
            query = "night"
        )

        assertEquals(listOf<Any>(track), opened.searchResults.tracks)
        assertEquals(listOf<Any>(album), opened.searchResults.albums)
        assertTrue(SearchScope.Tracks in opened.searchHasMore)
        assertFalse(SearchScope.Albums in opened.searchHasMore)

        // A different query is a different browse: the previous query's pages never leak into it.
        val other = opened.copy(query = "day")
        assertTrue(other.searchResults.isEmpty)
        assertFalse(SearchScope.Tracks in other.searchHasMore)
    }

    @Test
    fun `a first page replaces a list still on its first page`() {
        val opened = state(SearchScope.Tracks to LibraryPage(listOf(track), hasMore = true))

        val replaced = opened.withSearchFirstPage(SearchScope.Tracks, listOf(track2), hasMore = false)

        assertEquals(listOf<Any>(track2), replaced.searchResults.tracks)
        assertFalse(SearchScope.Tracks in replaced.searchHasMore)
    }

    @Test
    fun `a first page does not collapse a list already paged deeper`() {
        val deep = List(CATALOG_PAGE_SIZE + 1) { track }
        val opened = state(SearchScope.Tracks to LibraryPage(deep, hasMore = true))

        val refreshed = opened.withSearchFirstPage(SearchScope.Tracks, listOf(track), hasMore = false)

        assertEquals(CATALOG_PAGE_SIZE + 1, refreshed.searchResults.tracks.size)
        assertTrue(SearchScope.Tracks in refreshed.searchHasMore)
    }

    @Test
    fun `a first page for an untouched category leaves it alone`() {
        val opened = state(
            SearchScope.Tracks to LibraryPage(listOf(track)),
            SearchScope.Albums to LibraryPage(listOf(album))
        )

        val refreshed = opened.withSearchFirstPage(SearchScope.Albums, emptyList(), hasMore = false)

        assertEquals(listOf<Any>(track), refreshed.searchResults.tracks)
    }

    @Test
    fun `a load more extends only its own category`() {
        val opened = state(
            SearchScope.Tracks to LibraryPage(listOf(track)),
            SearchScope.Albums to LibraryPage(listOf(album))
        )

        val merged = opened.withSearchMorePage(SearchScope.Tracks, listOf(track2), hasMore = true)

        assertEquals(2, merged.searchResults.tracks.size)
        assertEquals(listOf<Any>(album), merged.searchResults.albums)
        assertTrue(SearchScope.Tracks in merged.searchHasMore)
    }

    @Test
    fun `a load more never duplicates a row already known`() {
        val opened = state(SearchScope.Tracks to LibraryPage(listOf(track)))

        val merged = opened.withSearchMorePage(SearchScope.Tracks, listOf(track), hasMore = true)

        assertEquals(1, merged.searchResults.tracks.size)
    }

    @Test
    fun `a load more for a page that was not fetched does not invent one`() {
        val opened = state(SearchScope.Tracks to LibraryPage(listOf(track)))

        val merged = opened.withSearchMorePage(SearchScope.Artists, listOf(artist), hasMore = true)

        assertFalse(SearchScope.Artists in merged.searchPagesForQuery)
        assertEquals(listOf<Any>(track), merged.searchResults.tracks)
    }
}
