package io.github.manuelrtsch.sideplay

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SideplayLinksTest {
    @After fun clearPendingNavigation() = SideplayLinks.reset()

    @Test fun `the playback notification action asks for now playing`() {
        assertTrue(SideplayLinks.handle(Intent(SideplayLinks.ACTION_OPEN_NOW_PLAYING)))
    }

    @Test fun `unrelated intents are ignored`() {
        assertFalse(SideplayLinks.handle(Intent(Intent.ACTION_VIEW)))
        assertFalse(SideplayLinks.handle(null))
    }
}
