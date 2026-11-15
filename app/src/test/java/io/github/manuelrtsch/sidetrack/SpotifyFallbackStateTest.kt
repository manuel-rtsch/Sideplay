package io.github.manuelrtsch.sidetrack

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
