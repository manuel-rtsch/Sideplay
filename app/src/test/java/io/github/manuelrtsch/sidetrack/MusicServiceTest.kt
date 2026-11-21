package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.KnownServices
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaLibraryWriter
import io.github.manuelrtsch.sidetrack.data.serviceChoice
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicServiceTest {
    @Test fun `the picker offers every service this build can provision`() {
        assertEquals(
            listOf(
                ServiceId.Jellyfin,
                ServiceId.Navidrome,
                ServiceId.NextcloudMusic,
                ServiceId.Plex,
                ServiceId.Spotify,
                ServiceId.Subsonic
            ),
            KnownServices.map { it.id }
        )
        // The label comes from the provider's own constant, so the picker cannot drift from it.
        assertEquals("Spotify", KnownServices.first { it.id == ServiceId.Spotify }.displayName)
        assertEquals("Navidrome", KnownServices.first { it.id == ServiceId.Navidrome }.displayName)
        assertEquals("Nextcloud Music", KnownServices.first { it.id == ServiceId.NextcloudMusic }.displayName)
        assertEquals("Subsonic", KnownServices.first { it.id == ServiceId.Subsonic }.displayName)
        assertEquals("Jellyfin", KnownServices.first { it.id == ServiceId.Jellyfin }.displayName)
        // The picker sorts by the label it renders, so the welcome list reads A–Z.
        assertEquals(
            KnownServices.map { it.displayName }.sortedBy { it.lowercase() },
            KnownServices.map { it.displayName }
        )
        assertNull(serviceChoice(ServiceId("unknown")))
    }

    @Test fun `the Subsonic family advertises the parity capabilities it can deliver`() {
        val caps = ServiceCapabilities.Subsonic
        assertTrue(caps.supportsRadio)
        assertTrue(caps.supportsNormalization)
        assertTrue(caps.supportsPlaylistEdit)
        assertTrue(caps.supportsPlaylistCreate)
        assertTrue(caps.supportsProgressReporting)
        assertTrue(caps.supportsScrobbling)
        // Not yet available for the Subsonic family: podcasts, artist-follow and gapless.
        assertTrue(!caps.supportsPodcasts)
        assertTrue(!caps.supportsFollowArtist)
        assertTrue(!caps.supportsGapless)
    }

    @Test fun `the Subsonic family mutes the categories its API cannot answer`() {
        val navidrome = ServiceCapabilities.Subsonic
        // Shows are missing because Navidrome answers every Subsonic podcast endpoint with 501.
        assertEquals(
            listOf(
                LibraryFilter.LikedSongs,
                LibraryFilter.Playlists,
                LibraryFilter.Albums,
                LibraryFilter.Artists
            ),
            navidrome.availableLibraryFilters.toList()
        )
        assertEquals(
            setOf(SearchScope.Tracks, SearchScope.Artists, SearchScope.Albums, SearchScope.Playlists),
            navidrome.availableSearchScopes
        )
        assertEquals(LibrarySection.Shows, LibraryFilter.Shows.section())
    }

    @Test fun `Jellyfin advertises only what its API can deliver`() {
        val caps = ServiceCapabilities.Jellyfin
        assertTrue(caps.supportsRadio)
        assertTrue(caps.supportsFollowArtist)
        assertTrue(caps.supportsPlaylistEdit)
        assertTrue(caps.supportsPlaylistCreate)
        assertTrue(caps.supportsProgressReporting)
        assertTrue(caps.supportsScrobbling)
        // Music libraries hold no podcast type (D3), there is no loudness endpoint, and gapless
        // is engine-level, so all three stay off.
        assertTrue(!caps.supportsPodcasts)
        assertTrue(!caps.supportsNormalization)
        assertTrue(!caps.supportsGapless)
    }

    @Test fun `Jellyfin mutes the categories it cannot answer`() {
        val jellyfin = ServiceCapabilities.Jellyfin
        assertEquals(
            listOf(
                LibraryFilter.LikedSongs,
                LibraryFilter.Playlists,
                LibraryFilter.Albums,
                LibraryFilter.Artists
            ),
            jellyfin.availableLibraryFilters.toList()
        )
        assertEquals(
            setOf(SearchScope.Tracks, SearchScope.Artists, SearchScope.Albums, SearchScope.Playlists),
            jellyfin.availableSearchScopes
        )
    }

    @Test fun `the picker offers Jellyfin once its runtime can sign in`() {
        val choice = serviceChoice(ServiceId.Jellyfin)
        assertEquals("Jellyfin", choice?.displayName)
        assertEquals(ServiceId.Jellyfin, choice?.id)
    }

    @Test fun `the picker offers Plex with a browser sign-in`() {
        val choice = serviceChoice(ServiceId.Plex)
        assertEquals("Plex", choice?.displayName)
        assertEquals(ServiceId.Plex, choice?.id)
    }

    @Test fun `Plex advertises only what its API can deliver`() {
        val caps = ServiceCapabilities.Plex
        assertTrue(caps.supportsPlaylistEdit)
        assertTrue(caps.supportsProgressReporting)
        assertTrue(caps.supportsScrobbling)
        // Plex has no radio endpoint on this server generation and no artist-follow flag.
        assertTrue(!caps.supportsRadio)
        assertTrue(!caps.supportsFollowArtist)
        assertTrue(!caps.supportsPlaylistCreate)
        assertTrue(!caps.supportsPodcasts)
        assertTrue(!caps.supportsNormalization)
        assertTrue(!caps.supportsGapless)
        assertEquals(
            listOf(
                LibraryFilter.LikedSongs,
                LibraryFilter.Playlists,
                LibraryFilter.Albums,
                LibraryFilter.Artists
            ),
            caps.availableLibraryFilters.toList()
        )
    }

    @Test fun `a fully featured service keeps every category selectable`() {
        assertEquals(LibraryFilter.entries.toSet(), ServiceCapabilities.All.availableLibraryFilters)
        assertEquals(SearchScope.entries.toSet(), ServiceCapabilities.All.availableSearchScopes)
    }

    @Test fun `Spotify does not yet advertise playlist creation until its transport lands`() {
        assertTrue(!ServiceCapabilities.All.supportsPlaylistCreate)
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
