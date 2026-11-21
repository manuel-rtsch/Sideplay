package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The password form is three fields that behave like a form: the server takes a URL, the secret is
 * a real secret to the platform, the caps hold, and the keyboard's action key moves down the form
 * and submits at the end.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInFieldTest {
    @get:Rule val compose = createComposeRule()

    private fun showSignIn(onPasswordSignIn: (String, String, String) -> Unit = { _, _, _ -> }) {
        compose.setContent {
            SidekitTheme {
                SidekitCanvas {
                    SignInScreen(
                        ChromeUiState(),
                        onBack = {},
                        onSignIn = {},
                        onDismissNotice = {},
                        serviceName = "Navidrome",
                        passwordAuth = true,
                        defaults = PasswordSignInDefaults("demo.example:4533", "demo", "demo"),
                        onPasswordSignIn = onPasswordSignIn
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun field(index: Int) = compose.onAllNodes(hasSetTextAction())[index]

    private fun isPasswordField() = SemanticsMatcher("is a password field") { node ->
        node.config.getOrNull(SemanticsProperties.Password) != null
    }

    @Test
    fun `the password field is a secret to the platform`() {
        showSignIn()
        field(2).assert(isPasswordField())
        field(0).assert(!isPasswordField())
        field(1).assert(!isPasswordField())
    }

    @Test
    fun `a pasted server or user name is cut at the field's cap`() {
        showSignIn()
        field(0).performTextInput("a".repeat(SERVER_MAX + 20))
        field(0).assertTextEquals("a".repeat(SERVER_MAX))
        field(1).performTextInput("b".repeat(USERNAME_MAX + 20))
        field(1).assertTextEquals("b".repeat(USERNAME_MAX))
    }

    @Test
    fun `next hands typing from the server to the user name`() {
        showSignIn()
        field(0).performClick()
        field(0).assertIsFocused()
        field(0).performImeAction()
        field(1).assertIsFocused()
    }

    @Test
    fun `done on the password submits the form`() {
        var submitted: Triple<String, String, String>? = null
        showSignIn { server, user, secret -> submitted = Triple(server, user, secret) }
        field(1).performTextInput("manuel")
        field(2).performTextInput("hunter2")
        field(2).performClick()
        field(2).performImeAction()
        compose.waitForIdle()
        // Nothing was typed into the server, so it goes in blank rather than borrowing the demo.
        assertEquals(Triple("", "manuel", "hunter2"), submitted)
    }
}
