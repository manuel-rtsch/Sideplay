package io.github.manuelrtsch.sidetrack

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SidetrackLinksTest {
    @After fun clearPendingNavigation() = SidetrackLinks.reset()

    @Test fun `the playback notification action asks for now playing`() {
        assertTrue(SidetrackLinks.handle(Intent(SidetrackLinks.ACTION_OPEN_NOW_PLAYING)))
    }

    @Test fun `unrelated intents are ignored`() {
        assertFalse(SidetrackLinks.handle(Intent(Intent.ACTION_VIEW)))
        assertFalse(SidetrackLinks.handle(null))
    }
}
