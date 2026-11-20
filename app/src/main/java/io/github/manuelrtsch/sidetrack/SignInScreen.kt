package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.drawFocusRing

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
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
                        focusOnKeypad = true
                    )
                }
                row("username") { modifier ->
                    FormField(
                        username,
                        { username = it },
                        label = "Username",
                        placeholder = defaults?.username,
                        modifier = modifier
                    )
                }
                row("password") { modifier ->
                    PasswordField(
                        password,
                        { password = it },
                        label = "Password",
                        placeholder = defaults?.password,
                        modifier = modifier
                    )
                }
            }
        }
    }
}

/**
 * The list rows' focus feedback for a field card: a white ring and a slight lift while the field
 * inside has focus, and D-pad Up/Down moving between fields instead of staying inside the text.
 * Left/Right are left alone: inside a field they move the caret.
 */

@Composable
private fun Modifier.fieldFocus(): Modifier {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(FOCUS_RING_MS),
        label = "fieldFocusRing"
    )
    return this
        .onFocusChanged { focused = it.hasFocus }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                when (event.key) {
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    else -> false
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (ring > 0f) drawFocusRing(ring)
        }
}

private const val FOCUS_RING_MS = 120

/**
 * A labelled one-line Sidekit field in an item card, the boxed form used across Sidekit's apps.
 * The label sits inside the card so the placeholder can carry an example (the demo credentials)
 * without having to say what the field is for.
 */
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    modifier: Modifier = Modifier,
    focusOnKeypad: Boolean = false
) {
    SidekitCard(
        modifier.fillMaxWidth()
            .heightIn(min = SidekitTheme.spacing.itemHeight)
            .fieldFocus()
    ) {
        Column(Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
            SidekitText(
                label,
                style = SidekitTheme.typography.caption,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 1
            )
            SidekitTextField(
                value,
                onValueChange,
                Modifier.fillMaxWidth(),
                placeholder = placeholder,
                focusOnKeypad = focusOnKeypad,
                singleLine = true
            )
        }
    }
}

/**
 * A masked password field in the same card as [FormField]. SidekitTextField has no visual
 * transformation, and the keypad's T9 bar is for search, not secrets.
 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    modifier: Modifier = Modifier
) {
    val colors = SidekitTheme.colors
    SidekitCard(
        modifier.fillMaxWidth()
            .heightIn(min = SidekitTheme.spacing.itemHeight)
            .fieldFocus()
    ) {
        Column(Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
            SidekitText(
                label,
                style = SidekitTheme.typography.caption,
                color = colors.surfaceA6,
                maxLines = 1
            )
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty() && placeholder != null) {
                    SidekitText(
                        placeholder,
                        style = SidekitTheme.typography.body,
                        color = colors.surfaceA6
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = SidekitTheme.typography.body.copy(color = colors.light),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.light),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )
            }
        }
    }
}
