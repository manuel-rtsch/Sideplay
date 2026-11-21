package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Appearance is one screen with two shapes. The rows a host owns have to disappear when the host is
 * installed — offering an edit that a host-backed theme store ignores would be a lie — but the rows
 * no host holds have to survive it, which is the case that used to fail: them being bundled with
 * the host's rows hid them along with the whole screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidetrackAppearanceScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val settings by lazy { SidetrackLocalSettings(context) }

    @Before
    fun clearSettings() {
        context.getSharedPreferences(SidetrackLocalSettings.FILE, 0).edit().clear().commit()
    }

    private fun show(hostBacked: Boolean) {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackAppearanceScreen(hostBacked = hostBacked, onBack = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `standalone shows every appearance row`() {
        show(hostBacked = false)

        compose.onNodeWithText("Compact layout").assertExists()
        compose.onNodeWithText("Compact rows").assertExists()
        compose.onNodeWithText("Backdrop").assertExists()
        compose.onNodeWithText("Match your Sidephone case").assertExists()
    }

    @Test
    fun `a host keeps the rows it owns off the screen`() {
        show(hostBacked = true)

        compose.onNodeWithText("Compact layout").assertDoesNotExist()
        compose.onNodeWithText("Match your Sidephone case").assertDoesNotExist()
    }

    @Test
    fun `a host still leaves Sidetrack's own appearance rows editable`() {
        show(hostBacked = true)

        compose.onNodeWithText("Compact rows").assertExists()
        compose.onNodeWithText("Backdrop").assertExists()

        compose.onNodeWithText("Compact rows").performClick()
        compose.waitForIdle()

        assertTrue(settings.compactRows)
    }

    @Test
    fun `the backdrop row toggles the now playing setting`() {
        show(hostBacked = true)

        compose.onNodeWithText("Backdrop").performClick()
        compose.waitForIdle()

        assertTrue(settings.nowPlayingBackdrop)

        compose.onNodeWithText("Backdrop").performClick()
        compose.waitForIdle()

        assertFalse(settings.nowPlayingBackdrop)
    }

    @Test
    fun `a host-backed screen does not seed the standalone theme default`() {
        show(hostBacked = true)

        assertFalse(
            context.getSharedPreferences(SidetrackLocalSettings.FILE, 0)
                .contains("compact_layout")
        )
    }
}
