package io.github.manuelrtsch.sideplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpotifyPaginationTest {
    @Test fun `collection pages continue without gaps`() = runTest {
        val full = MockSpotifyDataSource.playlist("late_night")!!.tracks
        val first = MockSpotifyDataSource.collection(SpotifyCollectionType.Playlist, "late_night", 0, 3)!!
        val second = MockSpotifyDataSource.collection(SpotifyCollectionType.Playlist, "late_night", 3, 3)!!

        assertEquals(full.take(3), first.items)
        assertEquals(full.drop(3).take(3), second.items)
        assertEquals(full.size, first.totalItems)
        assertTrue(first.hasMore)
    }

    @Test fun `last collection page reports no more`() = runTest {
        val full = MockSpotifyDataSource.playlist("late_night")!!.tracks
        val last = MockSpotifyDataSource.collection(
            SpotifyCollectionType.Playlist,
            "late_night",
            full.size - 2,
            3
        )!!

        assertEquals(full.takeLast(2), last.items)
        assertFalse(last.hasMore)
    }

    @Test fun `library page reports total and more`() = runTest {
        val page = MockSpotifyDataSource.library(LibraryFilter.LikedSongs, 0, 4)

        assertEquals(4, page.items.size)
        assertEquals(MockSpotifyDataSource.liked.size, page.total)
        assertTrue(page.hasMore)
    }

    @Test fun `search offset advances each section`() = runTest {
        val first = MockSpotifyDataSource.search("night", SearchScope.entries.toSet(), 0, 2)
        val second = MockSpotifyDataSource.search("night", SearchScope.entries.toSet(), 2, 2)

        assertTrue(first.tracks.isNotEmpty())
        assertTrue((first.tracks + second.tracks).distinctBy { it.id }.size >= first.tracks.size)
    }
}
