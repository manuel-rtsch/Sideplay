package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidetrackOnboardingTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearLocalSettings() {
        context.getSharedPreferences(SidetrackLocalSettings.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(SidetrackLocalSettings.KEYPAD_FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun finishingTheWelcomeRemembersIt() {
        var done = false
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(hostBacked = false, onAbout = {}, onDone = { done = true })
            }
        }

        compose.onNodeWithText("Get started").performClick()

        compose.runOnIdle {
            assertTrue(done)
            assertTrue(SidetrackLocalSettings(context).hasSeenOnboarding())
        }
    }

    @Test
    fun aStandaloneInstallSwitchesTheModuleFromTheKeyboardRow() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(hostBacked = false, onAbout = {}, onDone = {})
            }
        }

        compose.onNodeWithText("Keyboard").performClick()

        compose.onNodeWithText("Mini QWERTY").assertExists()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test
    fun aHostBackedInstallDoesNotOfferTheSwitch() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(hostBacked = true, onAbout = {}, onDone = {})
            }
        }

        compose.onNodeWithText("Keyboard").performClick()

        compose.onNodeWithText("T9 · Sidekit").assertExists()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }
}
