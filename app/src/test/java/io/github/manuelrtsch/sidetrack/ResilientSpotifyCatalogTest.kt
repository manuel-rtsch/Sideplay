package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ResilientSpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientSpotifyCatalogTest {
    @Test fun `an unreachable access point moves reads to the web`() = runTest {
        val native = FakeCatalog(failWith = SpotifyGatewayException(SpotifyFailure.Network, "Couldn't reach Spotify"))
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
        val native = FakeCatalog(failWith = SpotifyGatewayException(SpotifyFailure.RateLimited, "slow down"))
        val web = FakeCatalog()
        val catalog = ResilientSpotifyCatalog(native, web)

        val error = runCatching { catalog.library(LibraryFilter.LikedSongs, 0, 20) }.exceptionOrNull()

        assertEquals(SpotifyFailure.RateLimited, (error as SpotifyGatewayException).failure)
        assertFalse(catalog.degraded)
        assertEquals(0, web.libraryCalls)
    }

    @Test fun `a degraded catalog refuses to change the library`() = runTest {
        // The Web API transport in this app is read-only, so a write cannot silently go through it.
        val native = FakeCatalog(failWith = SpotifyGatewayException(SpotifyFailure.Network, "no access point"))
        val catalog = ResilientSpotifyCatalog(native, FakeCatalog())

        catalog.library(LibraryFilter.LikedSongs, 0, 20)
        val error = runCatching { catalog.setTrackSaved("id", true) }.exceptionOrNull()

        assertEquals(SpotifyFailure.Unavailable, (error as SpotifyGatewayException).failure)
        assertEquals(0, native.writes)
    }

    private class FakeCatalog(
        private val failWith: SpotifyGatewayException? = null
    ) : SpotifyCatalog,
        SpotifyLibraryWriter {
        var libraryCalls = 0
        var writes = 0

        override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): SpotifyPage<Any> {
            libraryCalls++
            failWith?.let { throw it }
            return SpotifyPage.of(emptyList(), offset, limit)
        }

        override suspend fun collection(
            type: SpotifyCollectionType,
            id: String,
            offset: Int,
            limit: Int
        ): SpotifyCollection? = throw UnsupportedOperationException()

        override suspend fun artist(id: String): SpotifyArtist? = throw UnsupportedOperationException()

        override suspend fun albumsBy(
            artist: SpotifyArtist,
            offset: Int,
            limit: Int
        ): SpotifyPage<SpotifyAlbum> = throw UnsupportedOperationException()

        override suspend fun topTracks(
            artist: SpotifyArtist,
            offset: Int,
            limit: Int
        ): SpotifyPage<SpotifyTrack> = throw UnsupportedOperationException()

        override suspend fun search(
            query: String,
            scopes: Set<SearchScope>,
            offset: Int,
            limit: Int
        ): SpotifySearchResults = throw UnsupportedOperationException()

        override suspend fun isTrackSaved(id: String): Boolean? = null
        override suspend fun isSaved(type: SpotifyCollectionType, id: String): Boolean? = null
        override suspend fun isArtistFollowed(id: String): Boolean? = null

        override suspend fun setTrackSaved(id: String, saved: Boolean) {
            writes++
        }

        override suspend fun setSaved(type: SpotifyCollectionType, id: String, saved: Boolean) {
            writes++
        }

        override suspend fun setArtistFollowed(id: String, followed: Boolean) {
            writes++
        }
    }
}
