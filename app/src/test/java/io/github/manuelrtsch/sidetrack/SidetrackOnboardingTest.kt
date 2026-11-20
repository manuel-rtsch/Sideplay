package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.T9Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /**
     * The selector prints only the module's own name, so it is found by its title and the name is
     * checked on the page - which also catches the "· Sidekit" and "· tap to switch" suffixes
     * that used to explain the row.
     */
    private fun assertSelectedKeyboard(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Select keyboard"))
        compose.onNodeWithText("Select keyboard").assertExists()
        compose.onNodeWithText(label).assertExists()
    }

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
    fun theKeyboardSettingsRowOpensTheKeyboardScreen() {
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

        // Welcome grew a keypad guide, so the Keyboard rows can sit below the first viewport.
        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Keyboard settings"))
        compose.onNodeWithText("Keyboard settings").performClick()

        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithText("Input, mapping, guide · Back off ›").assertExists()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test
    fun tappingTheKeyboardRowTeachesTheNextModule() {
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

        // T9 is the standalone default, so the first step is the mini QWERTY.
        assertSelectedKeyboard("T9")
        compose.onNodeWithText("Select keyboard").performClick()
        assertSelectedKeyboard("Mini QWERTY")
        // And on to the Sundial, whose rows are the ones a new listener has to read.
        compose.onNodeWithText("Select keyboard").performClick()
        assertSelectedKeyboard("Sundial")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Centre"))
        compose.onNodeWithText("Tap to play or pause. Hold to confirm.").assertExists()
    }

    @Test
    fun aHostBackedWelcomeCanStillSwitchTheModule() {
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

        compose.onNodeWithText("Select keyboard").performClick()

        // A host owns the choice, but the page it owns the instructions on can still correct it.
        assertSelectedKeyboard("Mini QWERTY")
        // There is no Keyboard screen to open on a host-backed install.
        compose.onNodeWithText("Keyboard settings").assertDoesNotExist()
    }

    @Test
    fun thePageFollowsAKeypadSidekitDetectsWhileItIsOpen() {
        val keypad = Keypad.forTesting(
            CoroutineScope(Dispatchers.Unconfined),
            words = T9Words.parse(""),
            module = KeypadModule.T9
        )
        compose.setContent {
            SidekitPreviewFrame(keypad = keypad) {
                SidetrackOnboardingScreen(
                    hostBacked = true,
                    onAbout = {},
                    onKeyboard = {},
                    onDone = {}
                )
            }
        }

        compose.onNodeWithText("Tap for this page’s actions. Hold for the action bar.").assertExists()

        // What Sidekit's detection does when the game controller's buttons arrive.
        compose.runOnIdle { keypad.useModule(KeypadModule.GamePad) }

        assertSelectedKeyboard("Game controller")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("X"))
        compose.onNodeWithText("Go back a screen.").assertExists()

        // A manual cycle is a preview; the attached module wins as soon as it changes.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Select keyboard"))
        compose.onNodeWithText("Select keyboard").performClick()
        assertSelectedKeyboard("T9")
        compose.runOnIdle { keypad.useModule(KeypadModule.Sundial) }
        assertSelectedKeyboard("Sundial")
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

        assertSelectedKeyboard("T9")
        compose.onNodeWithText("Keypad Back key").assertDoesNotExist()
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }
}
