package io.github.manuelrtsch.sidetrack

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.drawFocusRing
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import hrln.interactive.sidekit.sdk.ui.rememberUndoWindow
import io.github.manuelrtsch.sidetrack.data.ServiceChoice

private enum class SettingsConfirmation { SignOut }

@Composable
fun SettingsScreen(
    account: AccountUiState,
    chrome: ChromeUiState,
    recentCount: Int,
    serviceName: String = "",
    capabilities: ServiceCapabilities = ServiceCapabilities.All,
    onBack: () -> Unit,
    onAudioQuality: () -> Unit,
    onNormalization: () -> Unit,
    onGapless: () -> Unit,
    onAutoplay: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Boolean,
    onUndoClearHistory: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
    onAppearance: (() -> Unit)? = null,
    onKeyboard: (() -> Unit)? = null,
    onAbout: () -> Unit = {}
) {
    var confirmation by remember { mutableStateOf<SettingsConfirmation?>(null) }
    // Clearing a list is immediate and reversible, so it offers Undo instead of a second press.
    var historyCleared by remember { mutableStateOf(false) }
    // The Undo offer is measured in visible time: leaving Settings commits the clear instead of
    // burning the window in the background.
    val undoWindow = rememberUndoWindow { historyCleared = false }
    UndoWindowLifecycle(undoWindow) { historyCleared = false }
    val backdrop = rememberArtworkBackdropSetting()
    val context = LocalContext.current
    val back = remember { sidetrackBack("Back", onBack).first() }
    val baseActions = remember(back) { listOf(back, SideAction.forward("about", "About", onClick = onAbout)) }
    val normal = withNotice(chrome.notice, onDismissNotice, baseActions, chrome.noticeKind)
    val undo = if (historyCleared) {
        SideAction.undo("undoClearHistory", "Undo", "Undo clear history") {
            undoWindow.cancel()
            historyCleared = false
            onUndoClearHistory()
        }
    } else {
        null
    }
    val actions = when {
        confirmation == SettingsConfirmation.SignOut -> listOf(
            SideAction.cancel("cancelSignOut", "Cancel") { confirmation = null },
            SideAction.delete("confirmSignOut", "Sign out", onClick = {
                confirmation = null
                onSignOut()
            })
        )

        undo != null -> undoBar(back, undo)

        else -> normal
    }
    SidekitScaffold(
        title = "Settings",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            // listFade keys off the row's real index in the list. Conditional rows (quality,
            // normalization, gapless, appearance, keyboard) shift every index after them, so
            // advance a counter as each row is registered instead of hard-coding slots.
            var rowIndex = 0
            fun LazyListScope.row(key: String, content: @Composable (Modifier) -> Unit) {
                val index = rowIndex++
                item(key = key) { content(Modifier.listFade(listState, index)) }
            }

            row("account") { modifier ->
                // One row for the whole account: which service this is, and the sign-out it leads
                // to. A service is chosen once, before sign-in, so there is nothing to switch here;
                // naming it on the account row says which one signing out would leave.
                SidetrackItem(
                    "Account",
                    modifier.focusRequester(first),
                    secondary = if (account.signedIn) {
                        serviceName.ifEmpty { "Signed in" }
                    } else {
                        "Not signed in"
                    },
                    secondaryToEnd = true,
                    onClick = if (account.signedIn) {
                        { confirmation = SettingsConfirmation.SignOut }
                    } else {
                        null
                    }
                )
            }
            if (capabilities.supportsQualitySelection) {
                row("quality") { modifier ->
                    SidetrackItem(
                        "Audio quality",
                        modifier,
                        secondary = account.audioQuality.label,
                        secondaryToEnd = true,
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onAudioQuality
                    )
                }
            }
            row("autoplay") { modifier ->
                SidetrackItem(
                    "Autoplay",
                    modifier,
                    secondary = if (account.autoplay) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = account.autoplay,
                    onClick = onAutoplay
                )
            }
            if (capabilities.supportsNormalization) {
                row("normalization") { modifier ->
                    SidetrackItem(
                        "Normalize volume",
                        modifier,
                        secondary = if (account.normalization) "On" else "Off",
                        secondaryToEnd = true,
                        toggleable = account.normalization,
                        onClick = onNormalization
                    )
                }
            }
            if (capabilities.supportsGapless) {
                row("gapless") { modifier ->
                    SidetrackItem(
                        "Gapless playback",
                        modifier,
                        secondary = if (account.gapless) "On" else "Off",
                        secondaryToEnd = true,
                        toggleable = account.gapless,
                        onClick = onGapless
                    )
                }
            }
            row("cache") { modifier ->
                SidetrackItem(
                    "Clear cache",
                    modifier,
                    secondary = Formatter.formatShortFileSize(context, account.cacheSizeBytes),
                    secondaryToEnd = true,
                    onClick = onClearCache
                )
            }
            row("history") { modifier ->
                SidetrackItem(
                    "Clear history",
                    modifier,
                    secondary = recentCount.toString(),
                    secondaryToEnd = true,
                    onClick = {
                        historyCleared = onClearHistory()
                        if (historyCleared) undoWindow.start()
                    }
                )
            }
            row("artworkBackdrop") { modifier ->
                SidetrackItem(
                    "Art backdrop",
                    modifier,
                    secondary = if (backdrop.enabled) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = backdrop.enabled,
                    onClick = backdrop::toggle
                )
            }
            if (onAppearance != null) {
                row("appearance") { modifier ->
                    SidetrackItem(
                        "Appearance",
                        modifier,
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onAppearance
                    )
                }
            }
            if (onKeyboard != null) {
                row("keyboard") { modifier ->
                    SidetrackItem(
                        "Keyboard",
                        modifier,
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onKeyboard
                    )
                }
            }
        }
    }
}

/**
 * The one place a service is chosen: first run, and again after signing out or going back from
 * Sign in.
 *
 * Selection is deliberately not reachable while signed in. The app runs one service at a time,
 * so a picker in Settings would promise a switch that would have to tear down a live session.
 *
 * The screen is always the same screen: no row is pre-selected, and the bar holds the two things
 * a first-run user needs before committing to a service — what this app is, and how to drive it.
 * About sits on the left, Help on the right. No back action: this is the step behind Sign in, not
 * a step in front of something else, and the keypad Back key still leaves.
 */
@Composable
fun ServicePickerScreen(
    services: List<ServiceChoice>,
    onSelect: (ServiceId) -> Boolean,
    onAbout: () -> Unit = {},
    onHelp: () -> Unit = {}
) {
    // A service that cannot be opened stays selected nowhere and says so on its own row, so the
    // tap is never silently lost.
    var unavailable by remember { mutableStateOf<ServiceId?>(null) }
    val actions = remember(onAbout, onHelp) {
        listOf(
            // Both wear the theme colour: on the first screen the app shows, the two ways to find
            // out what it is are the main actions, not a grey afterthought.
            SideAction("about", "About", motion = ActionMotion.Up, onClick = onAbout),
            SideAction("help", "Help", motion = ActionMotion.Up, onClick = onHelp)
        )
    }
    // "Welcome", not "Welcome to Sidetrack": the shared header is one line at title size, and the
    // longer string truncates on the wheel ("Welcome to Sid…"). The box carries the greeting's
    // second half by telling the user what to do.
    SidekitScaffold(title = "Welcome", actions = actions) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            item(key = "explain") {
                SidekitCard(Modifier.listFade(listState, 0).fillMaxWidth()) {
                    SidekitText(
                        "Select an instance to get started",
                        style = SidekitTheme.typography.caption,
                        color = SidekitTheme.colors.surfaceA6,
                        maxLines = DESCRIPTION_MAX_LINES
                    )
                }
            }
            services.forEachIndexed { index, provider ->
                item(key = provider.id.value) {
                    SidetrackItem(
                        provider.displayName,
                        Modifier.listFade(listState, index + 1).then(
                            if (index == 0) Modifier.focusRequester(first) else Modifier
                        ),
                        secondary = if (unavailable == provider.id) "Unavailable" else null,
                        secondaryToEnd = true,
                        onClick = {
                            if (onSelect(provider.id)) unavailable = null else unavailable = provider.id
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AudioQualityScreen(
    account: AccountUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onSelect: (AudioQuality) -> Unit,
    onDismissNotice: () -> Unit
) {
    val baseActions = remember { listOf(SideAction.back("back", "Settings", onBack)) }
    val actions = withNotice(chrome.notice, onDismissNotice, baseActions, chrome.noticeKind)
    SidekitScaffold(
        title = "Audio quality",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, modifier = Modifier.selectableGroup(), state = listState) {
            AudioQuality.entries.forEachIndexed { index, quality ->
                val selected = quality == account.audioQuality
                item(key = quality.name) {
                    SidetrackItem(
                        quality.label,
                        Modifier.listFade(listState, index).then(
                            if (index == 0) Modifier.focusRequester(first) else Modifier
                        ),
                        secondary = "${quality.bitrateKbps} kbps",
                        secondaryToEnd = true,
                        secondaryNoEllipsis = true,
                        icon = if (selected) SidekitIcon.Check else null,
                        containerColor = if (selected) SidekitTheme.colors.successA1 else null,
                        secondaryColor = if (selected) SidekitTheme.colors.light else null,
                        selected = selected,
                        selectionRole = Role.RadioButton,
                        onClick = { onSelect(quality) }
                    )
                }
            }
        }
    }
}

/**
 * How many lines a screen's explanatory card may take. Three is the wheel's practical limit for a
 * hint the user reads once and scrolls past; copy that needs more is copy that needs rewriting.
 */
internal const val DESCRIPTION_MAX_LINES = 3

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
