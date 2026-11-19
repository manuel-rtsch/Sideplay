package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ResilientSpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientSpotifyCatalogTest {
    @Test fun `an unreachable access point moves reads to the web`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "Couldn't reach Spotify"))
        val web = FakeCatalog()
        val catalog = ResilientSpotifyCatalog(native, web)

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        catalog.library(LibraryFilter.Albums, 0, 20)

        // The native transport is tried once, then never again for this process; "one connect per
        // library row" is exactly the hammering this avoids.
        assertEquals(1, native.libraryCalls)
        assertEquals(2, web.libraryCalls)
        assertTrue(catalog.degraded)
    }

    @Test fun `a failure that is not the transport's is not degraded`() = runTest {
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.RateLimited, "slow down"))
        val web = FakeCatalog()
        val catalog = ResilientSpotifyCatalog(native, web)

        val error = runCatching { catalog.library(LibraryFilter.LikedSongs, 0, 20) }.exceptionOrNull()

        assertEquals(ServiceFailure.RateLimited, (error as ServiceException).failure)
        assertFalse(catalog.degraded)
        assertEquals(0, web.libraryCalls)
    }

    @Test fun `a degraded catalog refuses to change the library`() = runTest {
        // The Web API transport in this app is read-only, so a write cannot silently go through it.
        val native = FakeCatalog(failWith = ServiceException(ServiceFailure.Network, "no access point"))
        val catalog = ResilientSpotifyCatalog(native, FakeCatalog())

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        val error = runCatching { catalog.setTrackSaved("id", true) }.exceptionOrNull()

        assertEquals(ServiceFailure.Unavailable, (error as ServiceException).failure)
        assertEquals(0, native.writes)
    }

    private class FakeCatalog(
        private val failWith: ServiceException? = null
    ) : SpotifyCatalog,
        SpotifyLibraryWriter {
        var libraryCalls = 0
        var writes = 0

        override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): Page<LibraryEntry> {
            libraryCalls++
            failWith?.let { throw it }
            return Page.of(emptyList(), offset, limit)
        }

        override suspend fun collection(
            type: CollectionType,
            id: String,
            offset: Int,
            limit: Int
        ): Collection? = throw UnsupportedOperationException()

        override suspend fun artist(id: String): Artist? = throw UnsupportedOperationException()

        override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> = throw UnsupportedOperationException()

        override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> = throw UnsupportedOperationException()

        override suspend fun search(
            query: String,
            scopes: Set<SearchScope>,
            offset: Int,
            limit: Int
        ): SearchResults = throw UnsupportedOperationException()

        override suspend fun isTrackSaved(id: String): Boolean? = null
        override suspend fun isSaved(type: CollectionType, id: String): Boolean? = null
        override suspend fun isArtistFollowed(id: String): Boolean? = null

        override suspend fun setTrackSaved(id: String, saved: Boolean) {
            writes++
        }

        override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
            writes++
        }

        override suspend fun setArtistFollowed(id: String, followed: Boolean) {
            writes++
        }
    }
}
