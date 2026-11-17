package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.SpotifyHistoryEntry
import io.github.manuelrtsch.sidetrack.data.SpotifyHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ClearHistoryTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `clear history can be undone`() = runTest(dispatcher) {
        val track = MockSpotifyDataSource.recentlyPlayed.first()
        val history = SpotifyHistoryRepository(load = { listOf(SpotifyHistoryEntry(track, 1)) })
        val vm = SpotifyViewModel(MockSpotifyDataSource, history = history, simulatePlayback = false)

        assertTrue(vm.clearHistory())
        assertEquals(0, vm.state.value.recent.size)

        vm.undoClearHistory()
        assertEquals(1, vm.state.value.recent.size)
        assertEquals(track.id, vm.state.value.recent.first().id)
    }
}
