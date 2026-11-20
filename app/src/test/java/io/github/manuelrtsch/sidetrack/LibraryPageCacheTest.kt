package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached page set is the only copy of a category's rows, so the rules that fold server pages
 * and save toggles into the state have to keep every category coherent — not just the open one.
 */
class LibraryPageCacheTest {

    private val keys = MediaKeys(ServiceId.Spotify)
    private val playlist = MockSpotifyDataSource.playlists.first()
    private val albums = MockSpotifyDataSource.albums

    private fun state(vararg pages: Pair<LibraryFilter, LibraryPage>) = SidetrackUiState(
        signedIn = true,
        filter = LibraryFilter.Playlists,
        libraryPages = pages.toMap()
    )

    @Test
    fun `the open category is a projection of its own page`() {
        val opened = state(LibraryFilter.Playlists to LibraryPage(listOf(playlist), hasMore = true))

        assertEquals(listOf<Any>(playlist), opened.libraryItems)
        assertTrue(opened.libraryHasMore)
        assertNull(opened.libraryError)

        // Reading a different category must not show what the last fetch happened to bring in.
        val switched = opened.copy(filter = LibraryFilter.Albums)
        assertTrue(switched.libraryItems.isEmpty())
        assertFalse(switched.libraryHasMore)
    }

    @Test
    fun `a save patches its own category and leaves the others alone`() {
        val opened = state(
            LibraryFilter.Playlists to LibraryPage(emptyList(), hasMore = true),
            LibraryFilter.Albums to LibraryPage(albums, hasMore = false)
        )

        val saved = opened.withLibraryItemAdded(LibraryFilter.Playlists, playlist, keys)

        assertEquals(listOf<Any>(playlist), saved.libraryPages.getValue(LibraryFilter.Playlists).items)
        // Saves add rows to the end of the known list; they never reset it.
        assertTrue(saved.libraryPages.getValue(LibraryFilter.Playlists).hasMore)
        assertEquals(albums, saved.libraryPages.getValue(LibraryFilter.Albums).items)
    }

    @Test
    fun `a save into a category that was never opened does not invent its page`() {
        val saved = state().withLibraryItemAdded(LibraryFilter.Playlists, playlist, keys)

        assertTrue(saved.libraryPages.isEmpty())
    }

    @Test
    fun `an unsave removes the row and reopens the paging slot`() {
        val opened = state(LibraryFilter.Playlists to LibraryPage(listOf(playlist), hasMore = false))

        val removed = opened.withLibraryItemRemoved(LibraryFilter.Playlists, playlist.id, keys)

        assertTrue(removed.libraryItems.isEmpty())
        assertTrue(removed.libraryPages.getValue(LibraryFilter.Playlists).hasMore)
    }

    @Test
    fun `a list is expanded only once it reaches past its first page`() {
        val firstPage = List(CATALOG_PAGE_SIZE) { playlist }

        assertFalse(LibraryPage(firstPage).isExpanded)
        assertTrue(LibraryPage(firstPage + playlist).isExpanded)
    }
}
