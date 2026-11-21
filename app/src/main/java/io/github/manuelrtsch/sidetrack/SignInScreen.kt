package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * The values a password form shows as placeholders, and submits when the user presses Sign in
 * without typing anything. Used by Navidrome and Jellyfin to offer their public demos. Null
 * leaves the form literal: empty fields stay empty and fail validation as before.
 */
data class PasswordSignInDefaults(
    val serverUrl: String,
    val username: String,
    val password: String
)

/**
 * The credentials a password form submits: [defaults] when every field is blank (the placeholders
 * are a fallback, not examples), otherwise exactly what was typed. A partly filled form is never
 * completed from the defaults, so a server address cannot be paired with someone else's account.
 */

internal fun passwordSignInValues(
    serverUrl: String,
    username: String,
    password: String,
    defaults: PasswordSignInDefaults?
): Triple<String, String, String> {
    val untouched = serverUrl.isBlank() && username.isBlank() && password.isBlank()
    if (!untouched || defaults == null) return Triple(serverUrl, username, password)
    return Triple(defaults.serverUrl, defaults.username, defaults.password)
}

@Composable
fun SignInScreen(
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onDismissNotice: () -> Unit,
    serviceName: String = "your music service",
    passwordAuth: Boolean = false,
    defaults: PasswordSignInDefaults? = null,
    onPasswordSignIn: (serverUrl: String, username: String, password: String) -> Unit = { _, _, _ -> }
) {
    val colors = SidekitTheme.colors
    val focusManager = LocalFocusManager.current
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Typing on the keypad with nothing focused starts in the first blank field, so the form is
    // filled in order. Every field is blank before the demo values count, and then the server
    // takes the typing; a user who later clears the username moves the fallback there.
    val keypadTarget = when {
        serverUrl.isBlank() -> 0
        username.isBlank() -> 1
        password.isBlank() -> 2
        else -> -1
    }
    val submit = if (passwordAuth) {
        {
            val (server, user, secret) = passwordSignInValues(serverUrl, username, password, defaults)
            onPasswordSignIn(server, user, secret)
        }
    } else {
        onSignIn
    }
    val baseActions = remember(passwordAuth, onBack) {
        listOf(
            // Back, not About: the picker is the step behind this screen, so the left action is the
            // way to a different service and the bar needs no second, overlapping affordance.
            SideAction.back("back", "Back", onBack),
            SideAction.forward("signIn", "Sign in", submit)
        )
    }
    val actions = withNotice(chrome.notice, onDismissNotice, baseActions, chrome.noticeKind)
    SidekitScaffold(
        title = "Sign in",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            fun LazyListScope.row(key: String, content: @Composable (Modifier) -> Unit) {
                val index = rowIndex++
                item(key = key) { content(Modifier.listFade(listState, index)) }
            }

            row("explain") { modifier ->
                SidekitCard(modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SidekitText(
                            "Connect your $serviceName account",
                            style = SidekitTheme.typography.bodySmall,
                            maxLines = DESCRIPTION_MAX_LINES
                        )
                        // One sentence per case: what the app does with the secret, nothing else.
                        // A demo needs no mention here — its values sit in the placeholders.
                        SidekitText(
                            if (passwordAuth) {
                                "The password is stored encrypted on this device."
                            } else {
                                "Sidetrack never sees your password."
                            },
                            style = SidekitTheme.typography.caption,
                            color = colors.surfaceA6,
                            maxLines = DESCRIPTION_MAX_LINES
                        )
                    }
                }
            }
            if (passwordAuth) {
                row("server") { modifier ->
                    FormField(
                        serverUrl,
                        { serverUrl = it },
                        label = "Server",
                        placeholder = defaults?.serverUrl ?: "host:port",
                        modifier = modifier,
                        focusOnKeypad = keypadTarget == 0,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        maxLength = SERVER_MAX
                    )
                }
                row("username") { modifier ->
                    FormField(
                        username,
                        { username = it },
                        label = "Username",
                        placeholder = defaults?.username,
                        modifier = modifier,
                        focusOnKeypad = keypadTarget == 1,
                        contentType = ContentType.Username,
                        imeAction = ImeAction.Next,
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        maxLength = USERNAME_MAX
                    )
                }
                row("password") { modifier ->
                    FormField(
                        password,
                        { password = it },
                        label = "Password",
                        placeholder = defaults?.password,
                        modifier = modifier,
                        focusOnKeypad = keypadTarget == 2,
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        masked = true,
                        contentType = ContentType.Password,
                        imeAction = ImeAction.Done,
                        onDone = submit
                    )
                }
            }
        }
    }
}

/** The caps that keep a pasted blob from filling the field: a URL and a user name have limits. */
internal const val SERVER_MAX = 255
internal const val USERNAME_MAX = 64
