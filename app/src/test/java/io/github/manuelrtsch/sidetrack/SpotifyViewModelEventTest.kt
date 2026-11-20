package io.github.manuelrtsch.sidetrack

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.data.EpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.data.SpotifyMediaCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifySession
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    private val viewModels = mutableListOf<SidetrackViewModel>()

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

        vm.handlePlaybackEvent(event("""{"type":"stopped"}"""))

        assertNull(vm.state.value.current)
        assertTrue(vm.state.value.upcoming.isEmpty())
        assertFalse(vm.state.value.isPlaying)
    }

    @Test fun `unavailable event pauses and explains`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first()
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"unavailable"}"""))

        assertFalse(vm.state.value.isPlaying)
        assertEquals("This track isn't available", vm.state.value.notice)
    }

    @Test fun `an episode resumes at its saved local offset`() = runTest(dispatcher) {
        val progress = EpisodeProgressRepository(now = { 1_000L })
        progress.record("lw5", 1_200_000L, 2_529_000L)
        val vm = SidetrackViewModel(
            SpotifyMediaCatalog(MockSpotifyDataSource),
            episodeProgressRepository = progress,
            simulatePlayback = false
        ).also { viewModels += it }
        val episode = MockSpotifyDataSource.shows.first().episodes.last { it.id == "lw5" }

        vm.playItem(episode)

        assertEquals(1_200_000L, vm.playbackPositionMs.value)
    }

    @Test fun `seeking an episode saves the new local offset`() = runTest(dispatcher) {
        val progress = EpisodeProgressRepository(now = { 1_000L })
        val vm = SidetrackViewModel(
            SpotifyMediaCatalog(MockSpotifyDataSource),
            episodeProgressRepository = progress,
            simulatePlayback = false
        ).also { viewModels += it }
        val episode = MockSpotifyDataSource.shows.first().episodes.last { it.id == "lw5" }
        vm.playItem(episode)

        vm.seekTo(900_000L)

        assertEquals(900_000L, progress.resumePosition("lw5"))
    }

    @Test fun `shuffle and repeat events are adopted from the native player`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.handlePlaybackEvent(event("""{"type":"shuffle","shuffle":true}"""))
        vm.handlePlaybackEvent(event("""{"type":"repeat","track":true}"""))

        assertTrue(vm.state.value.shuffle)
        assertEquals(RepeatMode.Track, vm.state.value.repeat)
    }

    @Test fun `ended event advances to the next queued track`() = runTest(dispatcher) {
        val vm = viewModel()
        val items = MockSpotifyDataSource.recentlyPlayed.take(2)
        vm.play(items, 0)

        vm.handlePlaybackEvent(event("""{"type":"ended"}"""))

        assertEquals(items[1].id, vm.state.value.current?.id)
    }

    @Test fun `end of track timer stops before advancing`() = runTest(dispatcher) {
        val session = SpotifySession()
        val vm = SidetrackViewModel(
            SpotifyMediaCatalog(MockSpotifyDataSource),
            session = session,
            simulatePlayback = false
        ).also { viewModels += it }
        val items = MockSpotifyDataSource.recentlyPlayed.take(2)
        vm.play(items, 0)
        vm.setSleepTimerEndOfTrack()

        vm.handlePlaybackEvent(event("""{"type":"ended"}"""))

        assertEquals(items[0].id, vm.state.value.current?.id)
        assertFalse(vm.state.value.isPlaying)
        assertEquals(SleepTimerState.Finished, vm.sleepTimer.value)
    }

    @Test fun `starting a new item shows the now-playing notice`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first()

        vm.playItem(track)

        assertEquals(track.id, vm.state.value.nowPlayingNotice?.id)
    }

    @Test fun `dismissing the now-playing notice clears it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.playItem(MockSpotifyDataSource.recentlyPlayed.first())

        vm.dismissNowPlayingNotice()

        assertNull(vm.state.value.nowPlayingNotice)
    }

    @Test fun `resuming the current item does not show the now-playing notice`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first()
        vm.playItem(track)
        vm.dismissNowPlayingNotice()
        vm.togglePlayPause()

        vm.playItem(track)

        assertNull(vm.state.value.nowPlayingNotice)
    }

    @Test fun `the now-playing notice clears after its timeout`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.playItem(MockSpotifyDataSource.recentlyPlayed.first())

        advanceTimeBy(NOW_PLAYING_NOTICE_MS + 1)
        runCurrent()

        assertNull(vm.state.value.nowPlayingNotice)
    }

    @Test fun `playing an episode adds it to recently played`() = runTest(dispatcher) {
        val vm = viewModel()
        val episode = MockSpotifyDataSource.shows.first().episodes.first()

        vm.playItem(episode)

        assertEquals(episode.id, vm.state.value.recent.firstOrNull()?.id)
    }

    @Test fun `a show episode plays alone and ends on itself`() = runTest(dispatcher) {
        val vm = viewModel()
        val episode = MockSpotifyDataSource.shows.first().episodes.first()

        vm.playEpisode(episode)

        assertEquals(episode.id, vm.state.value.current?.id)
        // A show's list is newest-first, so the row after this one is an older episode: queueing the
        // list would autoplay backwards through the archive. Lining up more is Add to queue.
        assertTrue(vm.state.value.upcoming.isEmpty())

        vm.handlePlaybackEvent(event("""{"type":"ended"}"""))

        // Autoplay only extends a track's queue, so nothing else is pulled in behind an episode.
        assertEquals(episode.id, vm.state.value.current?.id)
        assertTrue(vm.state.value.upcoming.isEmpty())
        assertFalse(vm.state.value.isPlaying)
    }

    @Test fun `a repeat mode left over from songs does not loop a finished episode`() = runTest(dispatcher) {
        val vm = viewModel()
        val episode = MockSpotifyDataSource.shows.first().episodes.first()
        vm.playEpisode(episode)
        vm.cycleRepeat()
        assertEquals(RepeatMode.Context, vm.state.value.repeat)

        vm.handlePlaybackEvent(event("""{"type":"ended"}"""))

        // Episodes have no repeat control, so the leftover mode must not replay it forever.
        assertFalse(vm.state.value.isPlaying)
        assertEquals(episode.id, vm.state.value.current?.id)
    }

    @Test fun `a stale loading event after a seek does not move the bar back`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:seek1")
        vm.playItem(track)
        vm.seekTo(120_000L)

        vm.handlePlaybackEvent(event("""{"type":"loading","uri":"spotify:track:seek1","positionMs":10000}"""))

        assertEquals(120_000L, vm.playbackPositionMs.value)
        assertEquals(track.id, vm.state.value.current?.id)
    }

    @Test fun `a seeked event confirms and adopts the new offset`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:seek2")
        vm.playItem(track)
        vm.seekTo(120_000L)

        vm.handlePlaybackEvent(event("""{"type":"seeked","positionMs":120000}"""))

        assertEquals(120_000L, vm.playbackPositionMs.value)
    }

    @Test fun `a regular position event cannot cancel a pending seek`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:seek4")
        vm.playItem(track)
        vm.seekTo(120_000L)

        vm.handlePlaybackEvent(event("""{"type":"position","positionMs":121500}"""))

        assertEquals(120_000L, vm.playbackPositionMs.value)
    }

    @Test fun `a position event from the old clock is ignored after a seek`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:seek3")
        vm.playItem(track)
        vm.seekTo(120_000L)

        vm.handlePlaybackEvent(event("""{"type":"position","positionMs":10000}"""))

        assertEquals(120_000L, vm.playbackPositionMs.value)
    }

    @Test fun `event for another uri does not change the current item`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:ns1")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"stopped","uri":"spotify:track:someone-else"}"""))

        assertEquals(track.id, vm.state.value.current?.id)
    }

    @Test fun `a restored queue resumes the matching track at its saved offset`() = runTest(dispatcher) {
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(
            id = "resume-a",
            uri = "spotify:track:resume-a"
        )
        val session = SpotifySession()
        SpotifySession::class.java.getDeclaredField("resumePoint").apply {
            isAccessible = true
            set(session, ResumePoint(track.key, 42_000L))
        }
        val queue = QueueRepository(load = { QueueRepository.Snapshot(current = track) })

        val vm = SidetrackViewModel(
            SpotifyMediaCatalog(MockSpotifyDataSource),
            queue = queue,
            session = session,
            simulatePlayback = false
        ).also { viewModels += it }

        assertEquals(track.id, vm.state.value.current?.id)
        assertEquals(42_000L, vm.playbackPositionMs.value)
    }

    @Test fun `a resume point for another track does not move the restored item`() = runTest(dispatcher) {
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(
            id = "resume-b",
            uri = "spotify:track:resume-b"
        )
        val session = SpotifySession()
        SpotifySession::class.java.getDeclaredField("resumePoint").apply {
            isAccessible = true
            set(session, ResumePoint(MediaKey(ServiceId.Spotify, MediaType.Track, "someone-else"), 42_000L))
        }
        val queue = QueueRepository(load = { QueueRepository.Snapshot(current = track) })

        val vm = SidetrackViewModel(
            SpotifyMediaCatalog(MockSpotifyDataSource),
            queue = queue,
            session = session,
            simulatePlayback = false
        ).also { viewModels += it }

        assertEquals(track.id, vm.state.value.current?.id)
        assertEquals(0L, vm.playbackPositionMs.value)
    }

    @Test fun `loading event adopts the position the player will start at`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:load1")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"loading","uri":"spotify:track:load1","positionMs":5000}"""))

        assertEquals(5_000L, vm.playbackPositionMs.value)
        assertTrue(vm.state.value.isPlaying)
    }

    @Test fun `loading event for another item is ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:load2")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"loading","uri":"spotify:track:other","positionMs":5000}"""))

        assertEquals(0L, vm.playbackPositionMs.value)
    }

    @Test fun `playing event keeps the UI clock instead of the decoder lead`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:play1")
        vm.playItem(track)
        vm.seekTo(120_000L)

        vm.handlePlaybackEvent(event("""{"type":"playing","uri":"spotify:track:play1","positionMs":150000}"""))

        assertEquals(120_000L, vm.playbackPositionMs.value)
        assertTrue(vm.state.value.isPlaying)
    }

    @Test fun `paused event stops the transport`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:pause1")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"paused","uri":"spotify:track:pause1"}"""))

        assertFalse(vm.state.value.isPlaying)
    }

    @Test fun `positionCorrection adopts the confirmed position`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:correction1")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"positionCorrection","positionMs":42000}"""))

        assertEquals(42_000L, vm.playbackPositionMs.value)
    }

    @Test fun `a distant position event is adopted as a seek from another client`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:jump1")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"position","positionMs":90000}"""))

        assertEquals(90_000L, vm.playbackPositionMs.value)
    }

    @Test fun `a nearby position event does not move the bar`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:jump2")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"position","positionMs":2000}"""))

        assertEquals(0L, vm.playbackPositionMs.value)
    }

    @Test fun `repeat events adopt context and off`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.handlePlaybackEvent(event("""{"type":"repeat","context":true}"""))
        assertEquals(RepeatMode.Context, vm.state.value.repeat)

        vm.handlePlaybackEvent(event("""{"type":"repeat"}"""))
        assertEquals(RepeatMode.Off, vm.state.value.repeat)
    }

    @Test fun `shuffle off event turns shuffle off`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleShuffle()
        assertTrue(vm.state.value.shuffle)

        vm.handlePlaybackEvent(event("""{"type":"shuffle","shuffle":false}"""))

        assertFalse(vm.state.value.shuffle)
    }

    @Test fun `ended event for another item does not advance the queue`() = runTest(dispatcher) {
        val vm = viewModel()
        val items = MockSpotifyDataSource.recentlyPlayed.take(2)
            .mapIndexed { index, item -> item.copy(uri = "spotify:track:ended$index") }
        vm.play(items, 0)

        vm.handlePlaybackEvent(event("""{"type":"ended","uri":"spotify:track:someone-else"}"""))

        assertEquals(items[0].id, vm.state.value.current?.id)
    }

    @Test fun `unavailable event for another item does not pause`() = runTest(dispatcher) {
        val vm = viewModel()
        val track = MockSpotifyDataSource.recentlyPlayed.first().copy(uri = "spotify:track:unavailable1")
        vm.playItem(track)

        vm.handlePlaybackEvent(event("""{"type":"unavailable","uri":"spotify:track:someone-else"}"""))

        assertTrue(vm.state.value.isPlaying)
        assertNull(vm.state.value.notice)
    }

    private fun event(raw: String): PlaybackEvent = PlaybackEvent.fromJson(raw)

    private fun viewModel() = SidetrackViewModel(SpotifyMediaCatalog(MockSpotifyDataSource), simulatePlayback = false).also { viewModels += it }
}
