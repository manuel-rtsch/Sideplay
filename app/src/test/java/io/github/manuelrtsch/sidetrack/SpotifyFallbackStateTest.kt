package io.github.manuelrtsch.sidetrack

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaLibraryWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyFallbackStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<SidetrackViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun `a failed collection load is retryable`() = runTest(dispatcher) {
        val catalog = FlakyCatalog()
        val vm = viewModel(catalog)
        val type = CollectionType.Album
        val id = "night_signals"

        vm.ensureCollection(type, id)
        advanceUntilIdle()

        assertEquals("Couldn't reach the service", vm.state.value.collectionErrors["local:${type.route}:$id"])
        assertNull(vm.collection(type, id))

        catalog.failing = false
        vm.retryCollection(type, id)
        advanceUntilIdle()

        assertNull(vm.state.value.collectionErrors["local:${type.route}:$id"])
        assertNotNull(vm.collection(type, id))
    }

    @Test fun `a failed artist load is retryable`() = runTest(dispatcher) {
        val catalog = FlakyCatalog()
        val vm = viewModel(catalog)
        val id = MockSpotifyDataSource.artists.first().id

        vm.ensureArtist(id)
        advanceUntilIdle()

        assertEquals("Couldn't reach the service", vm.state.value.artistErrors[id])
        assertNull(vm.artist(id))

        catalog.failing = false
        vm.retryArtist(id)
        advanceUntilIdle()

        assertNull(vm.state.value.artistErrors[id])
        assertNotNull(vm.artist(id))
    }

    @Test fun `song radio queues its station behind the playing seed`() = runTest(dispatcher) {
        val seed = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
        val station = MockSpotifyDataSource.playlist("late_night")!!.tracks.drop(1).take(3)
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun songRadioStation(trackId: String, limit: Int): Station = Station(station)
        }
        val vm = viewModel(catalog)

        vm.playItem(seed)
        advanceUntilIdle()
        vm.ensureRadio()
        advanceUntilIdle()

        assertEquals(seed.id, vm.state.value.current?.id)
        assertEquals(station.map { it.id }, vm.state.value.upcoming.map { it.id })
        assertEquals(station.map { it.id }, vm.state.value.radio?.items?.map { it.id })
        assertNull(vm.state.value.radioError)
    }

    @Test fun `song radio saves its backing playlist`() = runTest(dispatcher) {
        val seed = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
        val station = MockSpotifyDataSource.playlist("late_night")!!.tracks.drop(1).take(3)
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun songRadioStation(trackId: String, limit: Int): Station = Station(station, playlistId = "37i9dQZF1DXcBWIGoYBM5M")
        }
        val writes = mutableListOf<Pair<String, Boolean>>()
        val writer = object : SpotifyLibraryWriter {
            override suspend fun isTrackSaved(id: String): Boolean? = null
            override suspend fun isSaved(type: CollectionType, id: String): Boolean? = false
            override suspend fun isArtistFollowed(id: String): Boolean? = null
            override suspend fun setTrackSaved(id: String, saved: Boolean) = Unit
            override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
                writes += id to saved
            }
            override suspend fun setArtistFollowed(id: String, followed: Boolean) = Unit
        }
        val vm = SidetrackViewModel(SpotifyMediaCatalog(catalog), SpotifyMediaLibraryWriter(writer), simulatePlayback = false).also { viewModels += it }

        vm.playItem(seed)
        advanceUntilIdle()
        vm.ensureRadio()
        advanceUntilIdle()

        val radio = vm.state.value.radio!!
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", radio.id)
        assertTrue(radio.saveable)

        vm.toggleSaved(radio)
        advanceUntilIdle()

        assertEquals(listOf("37i9dQZF1DXcBWIGoYBM5M" to true), writes)
    }

    @Test fun `adding to queue before playback keeps the item and confirms it`() = runTest(dispatcher) {
        val vm = viewModel(MockSpotifyDataSource)
        val track = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()

        vm.addToQueue(track)

        assertNull(vm.state.value.current)
        assertEquals(listOf(track.id), vm.state.value.upcoming.map { it.id })
        assertEquals("Added ${track.title} to queue", vm.state.value.notice)
    }

    @Test fun `queueing a collection appends every track as one batch`() = runTest(dispatcher) {
        val vm = viewModel(MockSpotifyDataSource)
        val playlist = MockSpotifyDataSource.playlist("late_night")!!
        val collection = Collection(
            type = CollectionType.Playlist,
            id = playlist.id,
            title = playlist.name,
            subtitle = "You",
            artRes = playlist.artRes,
            items = playlist.tracks
        )

        vm.queueCollection(collection)

        assertEquals(playlist.tracks.map { it.id }, vm.state.value.upcoming.map { it.id })
        assertEquals("Added ${playlist.tracks.size} tracks to queue", vm.state.value.notice)
    }

    @Test fun `queueing a paged collection loads every page first`() = runTest(dispatcher) {
        val base = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
        val all = (0 until 120).map { base.copy(id = "paged$it", title = "Paged $it") }
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun collection(
                type: CollectionType,
                id: String,
                offset: Int,
                limit: Int
            ): Collection {
                val items = all.drop(offset).take(limit)
                return Collection(
                    type = type,
                    id = id,
                    title = "Long playlist",
                    subtitle = "You · ${all.size} tracks",
                    artRes = base.artRes,
                    items = items,
                    totalItems = all.size,
                    hasMore = offset + items.size < all.size
                )
            }
        }
        val vm = viewModel(catalog)
        val type = CollectionType.Playlist
        val id = "long"
        vm.ensureCollection(type, id)
        advanceUntilIdle()
        val firstPage = vm.collection(type, id)!!
        assertEquals(CATALOG_PAGE_SIZE, firstPage.items.size)
        assertTrue(firstPage.hasMore)

        vm.queueCollection(firstPage)
        advanceUntilIdle()

        assertEquals(all.map { it.id }, vm.state.value.upcoming.map { it.id })
        assertFalse(vm.state.value.collectionMorePending.contains("spotify:${type.route}:$id"))
        assertEquals("Added ${all.size} tracks to queue", vm.state.value.notice)
    }

    @Test fun `a failed page load queues nothing`() = runTest(dispatcher) {
        val base = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
        val all = (0 until 120).map { base.copy(id = "paged$it", title = "Paged $it") }
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun collection(
                type: CollectionType,
                id: String,
                offset: Int,
                limit: Int
            ): Collection {
                if (offset > 0) throw SpotifyGatewayException(SpotifyFailure.Network, "offline")
                return Collection(
                    type = type,
                    id = id,
                    title = "Long playlist",
                    subtitle = "You · ${all.size} tracks",
                    artRes = base.artRes,
                    items = all.take(limit),
                    totalItems = all.size,
                    hasMore = true
                )
            }
        }
        val vm = viewModel(catalog)
        val type = CollectionType.Playlist
        val id = "long"
        vm.ensureCollection(type, id)
        advanceUntilIdle()

        vm.queueCollection(vm.collection(type, id)!!)
        advanceUntilIdle()

        assertTrue(vm.state.value.upcoming.isEmpty())
        assertEquals("Couldn't reach the service", vm.state.value.notice)
        assertFalse(vm.state.value.collectionMorePending.contains("spotify:${type.route}:$id"))
    }

    @Test fun `a late song radio response does not overwrite queue edits`() = runTest(dispatcher) {
        val seed = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
        val station = MockSpotifyDataSource.playlist("late_night")!!.tracks.drop(1).take(3)
        val added = MockSpotifyDataSource.playlist("late_night")!!.tracks.last()
        val gate = CompletableDeferred<Unit>()
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun songRadioStation(trackId: String, limit: Int): Station {
                gate.await()
                return Station(station)
            }
        }
        val vm = viewModel(catalog)

        vm.playItem(seed)
        advanceUntilIdle()
        vm.ensureRadio()
        runCurrent()
        vm.addToQueue(added)
        val edited = vm.state.value.upcoming.map { it.id }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(seed.id, vm.state.value.current?.id)
        assertEquals(edited, vm.state.value.upcoming.map { it.id })
        assertEquals(station.map { it.id }, vm.state.value.radio?.items?.map { it.id })
        assertNull(vm.state.value.radioError)
    }

    @Test fun `play artist queues every top track, not just the loaded page`() = runTest(dispatcher) {
        val artist = MockSpotifyDataSource.artists.first { it.id == "aurora" }
        val base = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
        val all = (0 until ARTIST_PAGE_SIZE * 2 + 5).map {
            base.copy(id = "top$it", title = "Top $it")
        }
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun topTracks(
                artist: Artist,
                offset: Int,
                limit: Int
            ): Page<Track> = Page.of(
                all.drop(offset).take(limit),
                offset,
                limit,
                all.size
            )
        }
        val vm = viewModel(catalog)

        vm.ensureArtist(artist.id)
        advanceUntilIdle()
        assertEquals(ARTIST_PAGE_SIZE, vm.topTracks(artist).size)
        assertTrue(vm.state.value.artistTrackHasMore.getValue(artist.id))

        vm.playArtist(artist)
        advanceUntilIdle()

        assertEquals(all.drop(1).map { it.id }, vm.state.value.upcoming.map { it.id })
        assertEquals(all.first().id, vm.state.value.current?.id)
        // The page keeps up too, so the list and the queue agree afterwards.
        assertEquals(all.map { it.id }, vm.topTracks(artist).map { it.id })
        assertFalse(vm.state.value.artistTrackHasMore.getValue(artist.id))
    }

    @Test fun `play artist without top tracks says so instead of doing nothing`() = runTest(dispatcher) {
        val artist = MockSpotifyDataSource.artists.first()
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun topTracks(
                artist: Artist,
                offset: Int,
                limit: Int
            ): Page<Track> = Page()
        }
        val vm = viewModel(catalog)

        vm.ensureArtist(artist.id)
        advanceUntilIdle()
        vm.playArtist(artist)
        advanceUntilIdle()

        assertNull(vm.state.value.current)
        assertEquals("No top tracks for this artist", vm.state.value.notice)
    }

    @Test fun `artist radio builds a station without touching the queue`() = runTest(dispatcher) {
        val artist = MockSpotifyDataSource.artists.first { it.id == "aurora" }
        val station = MockSpotifyDataSource.topTracks(artist, 4)
        val playing = MockSpotifyDataSource.albums.flatMap { it.tracks }.first { it.artistId != artist.id }
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun artistRadioStation(artistId: String, limit: Int): Station = Station(if (artistId == artist.id) station else emptyList())
        }
        val vm = viewModel(catalog)
        vm.playItem(playing)
        advanceUntilIdle()
        val before = vm.state.value.upcoming.map { it.id }

        vm.ensureArtistRadio(artist)
        advanceUntilIdle()

        assertEquals(station.map { it.id }, vm.state.value.artistRadio?.items?.map { it.id })
        assertNull(vm.state.value.artistRadioError)
        assertEquals(playing.id, vm.state.value.current?.id)
        assertEquals(before, vm.state.value.upcoming.map { it.id })

        vm.playArtistRadio(1)
        advanceUntilIdle()
        assertEquals(station[1].id, vm.state.value.current?.id)
        assertEquals(station.drop(2).map { it.id }, vm.state.value.upcoming.map { it.id })
    }

    @Test fun `a collection row tap plays the visible page instead of waiting for every page`() = runTest(dispatcher) {
        val vm = viewModel(MockSpotifyDataSource)
        val page = MockSpotifyDataSource.collection(CollectionType.Playlist, "late_night", 0, 3)!!
        val full = MockSpotifyDataSource.playlist("late_night")!!.tracks
        assertTrue(page.hasMore)

        vm.playCollection(page, 0)

        // The tap plays immediately: no dispatcher advance, no request in front of the first note.
        assertEquals(page.items.first().id, vm.state.value.current?.id)
        assertEquals(page.items.drop(1).map { it.id }, vm.state.value.upcoming.map { it.id })

        advanceUntilIdle()

        // The pages behind "See more" join the tail once they arrive, so the queue still matches
        // the whole playlist and repeat-context loops all of it.
        assertEquals(full.drop(1).map { it.id }, vm.state.value.upcoming.map { it.id })
    }

    @Test fun `a failed later page still plays the tapped track`() = runTest(dispatcher) {
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun collection(
                type: CollectionType,
                id: String,
                offset: Int,
                limit: Int
            ): Collection? = if (offset > 0) {
                throw SpotifyGatewayException(SpotifyFailure.Network, "offline")
            } else {
                MockSpotifyDataSource.collection(type, id, offset, limit)
            }
        }
        val vm = viewModel(catalog)
        val page = MockSpotifyDataSource.collection(CollectionType.Playlist, "late_night", 0, 3)!!

        vm.playCollection(page, 0)
        advanceUntilIdle()

        assertEquals(page.items.first().id, vm.state.value.current?.id)
        assertEquals(page.items.drop(1).map { it.id }, vm.state.value.upcoming.map { it.id })
        assertTrue(vm.state.value.notice.orEmpty().startsWith("Playing what's loaded"))
    }

    private fun viewModel(catalog: SpotifyCatalog) = SidetrackViewModel(SpotifyMediaCatalog(catalog), simulatePlayback = false).also { viewModels += it }

    private class FlakyCatalog : SpotifyCatalog by MockSpotifyDataSource {
        var failing = true

        override suspend fun collection(
            type: CollectionType,
            id: String,
            offset: Int,
            limit: Int
        ): Collection? {
            if (failing) throw SpotifyGatewayException(SpotifyFailure.Network, "offline")
            return MockSpotifyDataSource.collection(type, id, offset, limit)
        }

        override suspend fun artist(id: String): Artist? {
            if (failing) throw SpotifyGatewayException(SpotifyFailure.Network, "offline")
            return MockSpotifyDataSource.artist(id)
        }
    }
}
