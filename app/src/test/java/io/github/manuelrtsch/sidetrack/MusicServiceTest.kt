package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import io.github.manuelrtsch.sidetrack.data.MusicServiceProvider
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceAuth
import io.github.manuelrtsch.sidetrack.data.ServiceRegistry
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaLibraryWriter
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicServiceTest {
    @Test fun `registry starts on Spotify and switches only to installed services`() {
        val spotify = FakeProvider(ServiceId.Spotify, "Spotify")
        val navidrome = FakeProvider(ServiceId.Navidrome, "Navidrome")
        val registry = ServiceRegistry(listOf(spotify, navidrome))

        assertEquals(ServiceId.Spotify, registry.active.value)
        assertSame(spotify, registry.provider())

        assertTrue(registry.switchTo(ServiceId.Navidrome))
        assertEquals(ServiceId.Navidrome, registry.active.value)
        assertSame(navidrome, registry.provider())

        assertFalse(registry.switchTo(ServiceId("unknown")))
        assertEquals(ServiceId.Navidrome, registry.active.value)
    }

    @Test fun `Spotify catalog adapter maps neutral sections and keys`() = runTest {
        val catalog = SpotifyMediaCatalog(MockSpotifyDataSource)

        val liked = catalog.library(LibrarySection.Liked, 0, 5)
        assertTrue(liked.items.isNotEmpty())
        assertTrue(liked.items.all { it is Track })

        val album = catalog.collection(MediaKey.spotify(MediaType.Album, "night_signals"), 0, 5)!!
        assertEquals(MediaType.Album, album.kind)
        assertEquals("Night Signals", album.title)

        val artist = catalog.artist(MediaKey.spotify(MediaType.Artist, "aurora"))!!
        assertEquals("Aurora Vale", artist.name)

        val albums = catalog.albums(artist.key, 0, 5)
        assertTrue(albums.items.any { it.id == "night_signals" })

        val search = catalog.search("aurora", setOf(SearchScope.Tracks), 0, 5)
        assertTrue(search.tracks.isNotEmpty())
    }

    @Test fun `Spotify writer adapter maps neutral saves and playlist edits`() = runTest {
        val writer = RecordingWriter()
        val adapter = SpotifyMediaLibraryWriter(writer)
        val track = Track(
            id = "t1",
            title = "Track",
            artist = "Artist",
            artistId = "a1",
            albumId = "al1",
            artRes = 0,
            durationMs = 1_000L,
            uri = "spotify:track:t1"
        )

        adapter.setSaved(track.key, true)
        adapter.setSaved(MediaKey.spotify(MediaType.Album, "al1"), true)
        adapter.setFollowed(MediaKey.spotify(MediaType.Artist, "a1"), true)
        adapter.addToPlaylist(MediaKey.spotify(MediaType.Playlist, "p1"), track)
        adapter.removeFromPlaylist(MediaKey.spotify(MediaType.Playlist, "p1"), track)

        assertEquals(listOf("t1" to true), writer.savedTracks)
        assertEquals(listOf(CollectionType.Album to ("al1" to true)), writer.savedCollections)
        assertEquals(listOf("a1" to true), writer.followedArtists)
        assertEquals(listOf("p1" to "spotify:track:t1"), writer.added)
        assertEquals(listOf("p1" to "spotify:track:t1"), writer.removed)
    }

    private class FakeProvider(
        override val id: ServiceId,
        override val displayName: String
    ) : MusicServiceProvider {
        override val capabilities: ServiceCapabilities = ServiceCapabilities.Spotify

        override fun createAuth(): ServiceAuth = error("not used")
        override fun createCatalog(account: ServiceAccount): MediaCatalog = error("not used")
        override fun createWriter(account: ServiceAccount): MediaLibraryWriter? = null
        override fun createPlaybackEngine(account: ServiceAccount): PlaybackEngine = error("not used")
        override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource = error("not used")
    }

    private class RecordingWriter : SpotifyLibraryWriter {
        val savedTracks = mutableListOf<Pair<String, Boolean>>()
        val savedCollections = mutableListOf<Pair<CollectionType, Pair<String, Boolean>>>()
        val followedArtists = mutableListOf<Pair<String, Boolean>>()
        val added = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<Pair<String, String>>()

        override suspend fun isTrackSaved(id: String): Boolean? = null
        override suspend fun isSaved(type: CollectionType, id: String): Boolean? = null
        override suspend fun isArtistFollowed(id: String): Boolean? = null

        override suspend fun setTrackSaved(id: String, saved: Boolean) {
            savedTracks += id to saved
        }

        override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
            savedCollections += type to (id to saved)
        }

        override suspend fun setArtistFollowed(id: String, followed: Boolean) {
            followedArtists += id to followed
        }

        override suspend fun addToPlaylist(playlistId: String, uri: String): Int? {
            added += playlistId to uri
            return 0
        }

        override suspend fun removeFromPlaylist(playlistId: String, uri: String) {
            removed += playlistId to uri
        }
    }
}
