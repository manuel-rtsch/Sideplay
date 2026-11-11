package io.github.manuelrtsch.sideplay

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyViewModelEventTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<SpotifyViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun `stopped event clears the current item and queue`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first()
        vm.playItem(track)
        assertTrue(vm.state.value.current != null)

        vm.handlePlaybackEvent("""{"type":"stopped"}""")

        assertNull(vm.state.value.current)
        assertTrue(vm.state.value.upcoming.isEmpty())
        assertFalse(vm.state.value.isPlaying)
    }

    @Test fun `unavailable event pauses and explains`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first()
        vm.playItem(track)

        vm.handlePlaybackEvent("""{"type":"unavailable"}""")

        assertFalse(vm.state.value.isPlaying)
        assertEquals("This track isn't available", vm.state.value.notice)
    }

    @Test fun `shuffle and repeat events are adopted from the native player`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.handlePlaybackEvent("""{"type":"shuffle","shuffle":true}""")
        vm.handlePlaybackEvent("""{"type":"repeat","track":true}""")

        assertTrue(vm.state.value.shuffle)
        assertEquals(RepeatMode.Track, vm.state.value.repeat)
    }

    @Test fun `ended event advances to the next queued track`() = runTest(dispatcher) {
        val vm = viewModel()
        val items = MockSpotifyDataSource.recentlyPlayed.take(2)
        vm.play(items, 0)

        vm.handlePlaybackEvent("""{"type":"ended"}""")

        assertEquals(items[1].id, vm.state.value.current?.id)
    }

    @Test fun `event for another uri does not change the current item`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:ns1")
        vm.playItem(track)

        vm.handlePlaybackEvent("""{"type":"stopped","uri":"spotify:track:someone-else"}""")

        assertEquals(track.id, vm.state.value.current?.id)
    }

    private fun viewModel() = SpotifyViewModel(MockSpotifyDataSource, simulatePlayback = false).also { viewModels += it }
}
