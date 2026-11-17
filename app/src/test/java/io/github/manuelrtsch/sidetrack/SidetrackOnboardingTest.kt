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
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = { done = true }
                )
            }
        }

        compose.onNodeWithText("Get started").performClick()

        compose.runOnIdle {
            assertTrue(done)
            assertTrue(SidetrackLocalSettings(context).hasSeenOnboarding())
        }
    }

    @Test
    fun theWelcomeTeachesTheThreeEssentials() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Go back a screen.").assertExists()
        compose.onNodeWithText("Tap for this page’s actions. Hold for the action bar.").assertExists()
        compose.onNodeWithText("Press ••• for a focused row’s actions.").assertExists()
        compose.onNodeWithText("On touch, drag a row left to reveal its menu.").assertExists()
        // The diagram now sits on Welcome so a new listener can find the printed keys.
        compose.onNodeWithText("Where those keys are").assertExists()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test
    fun aStandaloneInstallOpensKeyboardFromTheKeyboardRow() {
        var opened = false
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = false,
                    onAbout = {},
                    onKeyboard = { opened = true },
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Keyboard").performClick()

        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithText("T9 · Back off ›").assertExists()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test
    fun theKeyboardGuideStaysClosedUntilAsked() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackKeyboardScreen(onBack = {})
            }
        }

        compose.onNodeWithText("Keypad guide").assertExists()
        compose.onNodeWithText("Hide").assertDoesNotExist()

        compose.onNodeWithText("Keypad guide").performClick()

        compose.onNodeWithText("Hide").assertExists()
    }

    @Test
    fun aHostBackedInstallDoesNotOfferTheKeyboardPage() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackOnboardingScreen(
                    hostBacked = true,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("T9 · Sidekit").assertExists()
        compose.onNodeWithText("Keypad Back key").assertDoesNotExist()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }
}
