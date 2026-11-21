package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Options menu is assembled in one place (`ActionBars.kt`). These tests pin the two rules the
 * audit in `docs/options-menu-audit.md` settled on, so a later screen cannot reintroduce the drift
 * it found: shape belongs to the entry (a destination is labelled, only a list-wide toggle and Now
 * Playing's playback shortcuts are icon-only squares) and colour belongs to what an option does
 * (accent for every live option, as Sidekit's own apps paint their Options bars; green for a toggle
 * that is on; red for destroy).
 */
class OptionsBuilderTest {

    @Test
    fun nowPlayingIsLastAndAccented() {
        val options = sidetrackOptions(sleepTimerOption(active = false, onClick = {}), onNowPlaying = {})

        assertEquals(listOf("sleepTimer", "nowPlaying"), options.map { it.key })
        assertEquals("Now playing", options.last().label)
        assertEquals(ActionTone.Primary, options.last().tone)
        assertFalse("Now playing is a destination, not a square", options.last().square)
    }

    @Test
    fun anEntryTheScreenCannotOfferLeavesNoGap() {
        val withoutPlaylist = sidetrackOptions(
            if (false) newPlaylistOption({}) else null,
            onNowPlaying = {}
        )
        val withPlaylist = sidetrackOptions(newPlaylistOption({}), onNowPlaying = {})

        assertEquals(listOf("nowPlaying"), withoutPlaylist.map { it.key })
        assertEquals(listOf("createPlaylist", "nowPlaying"), withPlaylist.map { it.key })
        assertTrue("Creating a playlist writes something, so it is the accent action", withPlaylist.first().tone == ActionTone.Primary)
    }

    @Test
    fun destinationsAreWideAndLabeled() {
        val destinations = listOf(
            nowPlayingOption {},
            settingsOption {},
            aboutOption {},
            newPlaylistOption {},
            addToQueueOption {},
            artistRadioOption {},
            sleepTimerOption(active = false) {}
        )

        destinations.forEach { option ->
            assertFalse("${option.key} must keep its label", option.square)
            assertTrue("${option.key} needs a label", option.label != null)
            assertTrue("${option.key} needs a glyph", option.icon != null)
        }
    }

    @Test
    fun sleepTimerSaysItsStateInWordsAndColorOnEveryScreen() {
        val idle = sleepTimerOption(active = false, onClick = {})
        val armed = sleepTimerOption(active = true, onClick = {})
        val queue = sidetrackOptions(
            openQueueOption({}),
            sleepTimerOption(active = true, onClick = {})
        )

        assertEquals("Sleep timer", idle.label)
        assertEquals("Sleep timer on", armed.label)
        assertEquals(ActionTone.Primary, idle.tone)
        assertEquals(ActionTone.Secondary, armed.tone)
        assertTrue("The queue's bar has room for the words", queue.last().label != null)
        assertEquals(armed.tone, queue.last().tone)
    }

    @Test
    fun nowPlayingSleepTimerIsASquareBecauseTheWordsAreOnTheScreen() {
        // Three playback squares plus Sidekit's close square already claim 430 of the bar's
        // 460 dp, so a labelled sleep timer there truncated to "Sle…". It is a square like the
        // shortcuts beside it; the countdown row in the body carries the words.
        val idle = sleepTimerShortcutOption(active = false, onClick = {})
        val armed = sleepTimerShortcutOption(active = true, onClick = {})
        val nowPlaying = sidetrackOptions(openQueueOption({}), songRadioOption({}), armed)

        assertTrue("Now Playing has no room for a label", nowPlaying.all { it.square })
        assertEquals("Sleep timer", idle.contentDescription)
        assertEquals("Sleep timer on", armed.contentDescription)
        assertEquals(ActionTone.Primary, idle.tone)
        assertEquals(ActionTone.Secondary, armed.tone)
        assertEquals("sleepTimer", armed.key)
    }

    @Test
    fun aToggleInTheBarIsSquareAndNamesItsState() {
        val on = toggleAction("shuffle", SidekitIcon.Shuffle, "Shuffle off", "Shuffle on", on = true, onClick = {})
        val off = toggleAction("shuffle", SidekitIcon.Shuffle, "Shuffle off", "Shuffle on", on = false, onClick = {})

        assertTrue("A bar toggle is icon-only", on.square)
        assertEquals(ActionTone.Secondary, on.tone)
        assertEquals(ActionTone.Primary, off.tone)
        assertEquals("Shuffle on", on.contentDescription)
        assertEquals("Shuffle off", off.contentDescription)
    }

    @Test
    fun saveAndShuffleAreTheSameControl() {
        val save = toggleAction(
            "save",
            SidekitIcon.BookmarkBorder,
            "Save",
            "Saved",
            true,
            onClick = {},
            onIcon = SidekitIcon.Bookmark
        )
        val shuffle = toggleAction("shuffle", SidekitIcon.Shuffle, "Shuffle off", "Shuffle on", on = true, onClick = {})

        assertEquals("A toggle in an action bar looks the same everywhere", save.square, shuffle.square)
        assertEquals(save.tone, shuffle.tone)
        assertEquals(SidekitIcon.Bookmark, save.icon)
        assertEquals(SidekitIcon.Shuffle, shuffle.icon)
    }

    @Test
    fun aDestructiveOptionIsAlwaysLast() {
        val options = sidetrackOptions(
            artistRadioOption {},
            sleepTimerOption(active = false) {},
            SideAction("clearUpcoming", "Clear", SidekitIcon.DeleteForever, tone = ActionTone.Destructive) {}
        )

        assertEquals("clearUpcoming", options.last().key)
        assertEquals(ActionTone.Destructive, options.last().tone)
    }

    @Test
    fun onlyPlaybackShortcutsAndTogglesAreSquares() {
        val nowPlaying = sidetrackOptions(
            openQueueOption({}),
            songRadioOption({}),
            sleepTimerShortcutOption(active = false, onClick = {})
        )
        val contentScreen = sidetrackOptions(artistRadioOption({}), onNowPlaying = {})

        assertEquals(listOf(true, true, true), nowPlaying.map { it.square })
        assertEquals(listOf(false, false), contentScreen.map { it.square })
    }

    @Test
    fun everyLiveOptionCarriesTheAccent() {
        // Sidekit's own apps paint their Options bars entirely in the theme accent, and so does
        // Sidetrack: an option is live wherever it leads, so tone says what it does, not how far it
        // travels. Grey is left to the close square, notices and undo.
        val live = listOf(
            nowPlayingOption {},
            settingsOption {},
            aboutOption {},
            newPlaylistOption {},
            addToQueueOption {},
            artistRadioOption {},
            openQueueOption {},
            songRadioOption {},
            episodeNotesOption {},
            sleepTimerOption(active = false) {},
            sleepTimerShortcutOption(active = false) {}
        )

        live.forEach { assertEquals("${it.key} is a live option", ActionTone.Primary, it.tone) }
        assertEquals(ActionTone.Secondary, sleepTimerOption(active = true) {}.tone)
    }

    @Test
    fun aShowNamesTheEpisodeItQueues() {
        assertEquals("Add to queue", addToQueueOption {}.label)
        assertEquals("Add latest to queue", addToQueueOption("Add latest to queue") {}.label)
    }
}
