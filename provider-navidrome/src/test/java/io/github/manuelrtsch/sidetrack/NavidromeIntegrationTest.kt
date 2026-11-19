package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.NavidromeApi
import io.github.manuelrtsch.sidetrack.data.NavidromeCatalog
import io.github.manuelrtsch.sidetrack.data.NavidromeCredentials
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Opt-in smoke test against a real Navidrome/Subsonic server.
 *
 * Point it at a disposable instance:
 *
 * `NAVIDROME_TEST_URL=http://127.0.0.1:4534 NAVIDROME_TEST_USER=... NAVIDROME_TEST_PASSWORD=...`
 *
 * It skips when those variables are absent so CI and normal local runs stay offline.
 */
@RunWith(RobolectricTestRunner::class)
class NavidromeIntegrationTest {
    private val baseUrl = System.getenv("NAVIDROME_TEST_URL").orEmpty()
    private val username = System.getenv("NAVIDROME_TEST_USER").orEmpty()
    private val password = System.getenv("NAVIDROME_TEST_PASSWORD").orEmpty()

    private fun api() = NavidromeApi(NavidromeCredentials(baseUrl, username, password))

    private fun requireServer() {
        assumeTrue(
            "Set NAVIDROME_TEST_URL/USER/PASSWORD to run this test",
            baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        )
    }

    @Test fun `reads artists albums and tracks from a real server`() = runTest {
        requireServer()
        val api = api()
        api.ping()

        val artists = api.artists()
        assertTrue(artists.isNotEmpty())

        val artist = api.artist(artists.first().id)!!
        assertTrue(artist.albumIds.isNotEmpty())

        val album = api.album(artist.albumIds.first())!!
        assertTrue(album.tracks.isNotEmpty())
        assertTrue(album.tracks.first().title.isNotBlank())
        assertTrue(album.tracks.first().artist.isNotBlank())

        val page = api.albumList(0, 2)
        assertEquals(2, page.items.size)
        assertTrue(page.hasMore)

        val search = api.search("love", setOf(SearchScope.Tracks, SearchScope.Albums), 0, 5)
        assertTrue(search.tracks.isNotEmpty() || search.albums.isNotEmpty())
    }

    @Test fun `resolves stream and artwork URLs that the server serves`() = runTest {
        requireServer()
        val api = api()
        val artist = api.artists().first()
        val album = api.album(api.artist(artist.id)!!.albumIds.first())!!
        val track = album.tracks.first()

        assertTrue(api.streamUrl(track.id).contains("/rest/stream"))
        val artUrl = track.artUrl!!
        assertTrue(artUrl.contains("/rest/getCoverArt"))

        // The token is part of the URL, so a plain open must return bytes, not an XML error.
        val bytes = java.net.URL(artUrl).openStream().use { it.readBytes() }
        assertTrue(bytes.size > 1_000)
    }

    @Test fun `catalog pages the library and writes stars`() = runTest {
        requireServer()
        val api = api()
        val catalog = NavidromeCatalog(api = { api })

        val artists = catalog.library(LibrarySection.Artists, 0, 5)
        assertTrue(artists.items.isNotEmpty())

        val albums = catalog.library(LibrarySection.Albums, 0, 2)
        assertTrue(albums.items.isNotEmpty())

        val artist = artists.items.filterIsInstance<Artist>().first()
        val top = catalog.topTracks(artist.key, 0, 5)
        assertTrue(top.items.isNotEmpty())
        val track = top.items.first()

        val before = catalog.isSaved(track.key) ?: false
        catalog.setSaved(track.key, !before)
        assertEquals(!before, catalog.isSaved(track.key))
        catalog.setSaved(track.key, before)
        assertEquals(before, catalog.isSaved(track.key))

        val source = catalog.resolvePlaybackSource(track)
        assertTrue(source.uri.contains("/rest/stream"))
        assertTrue(source.uri.contains("t="))
        assertFalse(source.uri.contains(password))
    }

    private suspend fun playlistUnderTest(catalog: NavidromeCatalog): Playlist {
        val playlists = catalog.library(LibrarySection.Playlists, 0, 20).items.filterIsInstance<Playlist>()
        val playlist = playlists.firstOrNull { it.name.startsWith("Sidetrack Test") }
        assumeTrue("Create a playlist named 'Sidetrack Test…' first", playlist != null)
        return playlist!!
    }

    /** The playlist's tracks, in order — the assertion unit. A size alone hides a rotation. */
    private suspend fun tracksOf(catalog: NavidromeCatalog, playlist: Playlist): List<Track> = catalog.collection(playlist.key, 0, 200)!!.items.filterIsInstance<Track>()

    /** Tracks the playlist does not already hold, so an edit appends a distinct id. */
    private suspend fun tracksNotIn(api: NavidromeApi, playlist: List<Track>, count: Int): List<Track> {
        val ids = playlist.map { it.id }.toSet()
        val found = LinkedHashMap<String, Track>()
        for (album in api.albumList(0, 10).items) {
            for (track in api.album(album.id)?.tracks.orEmpty()) {
                if (track.id !in ids) found[track.id] = track
                if (found.size >= count) return found.values.toList()
            }
        }
        return found.values.toList()
    }

    private suspend fun trackNotIn(api: NavidromeApi, playlist: List<Track>): Track? = tracksNotIn(api, playlist, count = 1).firstOrNull()

    @Test fun `catalog reads and edits a playlist`() = runTest {
        requireServer()
        val api = api()
        val catalog = NavidromeCatalog(api = { api })

        val playlist = playlistUnderTest(catalog)
        val before = tracksOf(catalog, playlist)
        assertTrue(before.isNotEmpty())

        val song = trackNotIn(api, before)
        assumeTrue("Needs a track that is not already in the playlist", song != null)
        song!!

        val index = catalog.addToPlaylist(playlist.key, song)
        assertEquals(before.size, index)
        // Contents, not size: an append that rotated the entries would keep the size and fail here.
        assertEquals(before.map { it.id } + song.id, tracksOf(catalog, playlist).map { it.id })

        catalog.removeFromPlaylist(playlist.key, song)
        assertEquals(before.map { it.id }, tracksOf(catalog, playlist).map { it.id })
    }

    /**
     * The defect recorded in `docs/release-readiness-jellyfin-navidrome.md` §1.4: removal resolved
     * the row with `indexOfFirst { it.id == songId }`, so with `[A, B, C, A]` removing the last `A`
     * deleted index 0. Size is preserved, contents rotate — which is why the test above asserts
     * contents. `removeFromPlaylist` now takes the last match when no row is named, so this passes;
     * the case with a named row is pinned offline in `NavidromeApiTest`.
     */
    @Test fun `removing a duplicated track leaves the other entries in place`() = runTest {
        requireServer()
        val api = api()
        val catalog = NavidromeCatalog(api = { api })

        val playlist = playlistUnderTest(catalog)
        val before = tracksOf(catalog, playlist)

        // Build the case instead of assuming it: any playlist with two spare tracks in the library
        // can run this, and the fixture is put back exactly as it was found.
        val additions = tracksNotIn(api, before, count = 2)
        assumeTrue("The library needs two tracks outside the playlist", additions.size == 2)
        val (first, second) = additions
        catalog.addToPlaylist(playlist.key, first)
        catalog.addToPlaylist(playlist.key, second)
        val withTwo = before.map { it.id } + first.id + second.id
        assertEquals(withTwo, tracksOf(catalog, playlist).map { it.id })

        // Append a copy of `first` and remove it again without naming a row: the copy at the end
        // must go, not the one that was already there.
        catalog.addToPlaylist(playlist.key, first)
        assertEquals(withTwo + first.id, tracksOf(catalog, playlist).map { it.id })
        catalog.removeFromPlaylist(playlist.key, first)
        assertEquals(withTwo, tracksOf(catalog, playlist).map { it.id })

        catalog.removeFromPlaylist(playlist.key, second)
        catalog.removeFromPlaylist(playlist.key, first)
        assertEquals(before.map { it.id }, tracksOf(catalog, playlist).map { it.id })
    }
}
