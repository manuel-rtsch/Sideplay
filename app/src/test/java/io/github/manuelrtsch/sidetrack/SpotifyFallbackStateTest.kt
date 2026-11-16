package io.github.manuelrtsch.sidetrack

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyFallbackStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<SpotifyViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun `a failed collection load is retryable`() = runTest(dispatcher) {
        val catalog = FlakyCatalog()
        val vm = viewModel(catalog)
        val type = SpotifyCollectionType.Album
        val id = "night_signals"

        vm.ensureCollection(type, id)
        advanceUntilIdle()

        assertEquals("Couldn't reach Spotify", vm.state.value.collectionErrors["${type.route}:$id"])
        assertNull(vm.collection(type, id))

        catalog.failing = false
        vm.retryCollection(type, id)
        advanceUntilIdle()

        assertNull(vm.state.value.collectionErrors["${type.route}:$id"])
        assertNotNull(vm.collection(type, id))
    }

    @Test fun `a failed artist load is retryable`() = runTest(dispatcher) {
        val catalog = FlakyCatalog()
        val vm = viewModel(catalog)
        val id = MockSpotifyDataSource.artists.first().id

        vm.ensureArtist(id)
        advanceUntilIdle()

        assertEquals("Couldn't reach Spotify", vm.state.value.artistErrors[id])
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
            override suspend fun songRadio(trackId: String, limit: Int): List<SpotifyTrack> = station
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

    @Test fun `a late song radio response does not overwrite queue edits`() = runTest(dispatcher) {
        val seed = MockSpotifyDataSource.playlist("late_night")!!.tracks.first()
        val station = MockSpotifyDataSource.playlist("late_night")!!.tracks.drop(1).take(3)
        val added = MockSpotifyDataSource.playlist("late_night")!!.tracks.last()
        val gate = CompletableDeferred<Unit>()
        val catalog = object : SpotifyCatalog by MockSpotifyDataSource {
            override suspend fun songRadio(trackId: String, limit: Int): List<SpotifyTrack> {
                gate.await()
                return station
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
        val all = MockSpotifyDataSource.topTracks(artist, Int.MAX_VALUE)
        val vm = viewModel(MockSpotifyDataSource)

        vm.ensureArtist(artist.id)
        advanceUntilIdle()
        assertEquals(SPOTIFY_ARTIST_PAGE_SIZE, vm.topTracks(artist).size)

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
                artist: SpotifyArtist,
                offset: Int,
                limit: Int
            ): SpotifyPage<SpotifyTrack> = SpotifyPage()
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
            override suspend fun artistRadio(artistId: String, limit: Int): List<SpotifyTrack> =
                if (artistId == artist.id) station else emptyList()
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

    private fun viewModel(catalog: SpotifyCatalog) = SpotifyViewModel(catalog, simulatePlayback = false).also { viewModels += it }

    private class FlakyCatalog : SpotifyCatalog by MockSpotifyDataSource {
        var failing = true

        override suspend fun collection(
            type: SpotifyCollectionType,
            id: String,
            offset: Int,
            limit: Int
        ): SpotifyCollection? {
            if (failing) throw SpotifyGatewayException(SpotifyFailure.Network, "offline")
            return MockSpotifyDataSource.collection(type, id, offset, limit)
        }

        override suspend fun artist(id: String): SpotifyArtist? {
            if (failing) throw SpotifyGatewayException(SpotifyFailure.Network, "offline")
            return MockSpotifyDataSource.artist(id)
        }
    }
}
