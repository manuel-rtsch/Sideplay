package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import hrln.interactive.sidekit.sdk.settings.KeypadGuide

/**
 * The one-time welcome shown after sign-in. It names the attached keypad, lets a standalone
 * install switch modules, and points out the Options key before the listener reaches Home.
 *
 * A Sidekit host owns the keypad choice, so with a host installed the row is informational and
 * the module and input mode keep coming from Sidekit.
 */
@Composable
fun SidetrackOnboardingScreen(
    hostBacked: Boolean,
    onAbout: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val local = remember(context) { SidetrackLocalSettings(context) }
    val keypad = LocalKeypad.current
    val keypadPrefs = remember(context) {
        SharedPreferencesKeypadPreferences(context, SidetrackLocalSettings.KEYPAD_FILE)
    }
    val custom = keypad?.mapper?.customModule ?: keypadPrefs.customModule()
    var module by remember(keypad) {
        mutableStateOf(keypad?.module ?: custom ?: keypadPrefs.module() ?: KeypadModule.T9)
    }
    val canSwitch = !hostBacked && custom == null
    val keyboardLabel = when {
        hostBacked -> "${module.label} · Sidekit"
        custom != null -> "${custom.label} · custom"
        else -> module.label
    }
    val keyboardHint = when {
        hostBacked -> "Sidekit manages the keyboard and input mode."
        custom != null -> "A custom key mapping is active. Change it in Settings › Keyboard."
        else -> "Press Keyboard to switch modules."
    }

    SidetrackOnboardingContent(
        module = module,
        keyboardLabel = keyboardLabel,
        keyboardHint = keyboardHint,
        canSwitchKeyboard = canSwitch,
        onSwitchKeyboard = {
            val next = KeypadModule.entries[(module.ordinal + 1) % KeypadModule.entries.size]
            keypad?.useModule(next) ?: keypadPrefs.writeModule(next)
            module = next
        },
        onAbout = onAbout,
        onDone = {
            local.markOnboardingSeen()
            onDone()
        }
    )
}

/**
 * Presentational body, kept separate so the screenshot suite can draw a deterministic keypad
 * without a real Sidekit host.
 */
@Composable
internal fun SidetrackOnboardingContent(
    module: KeypadModule,
    keyboardLabel: String,
    keyboardHint: String,
    canSwitchKeyboard: Boolean,
    onSwitchKeyboard: () -> Unit,
    onAbout: () -> Unit,
    onDone: () -> Unit
) {
    val list = rememberLazyListState()
    val keyboardFocus = remember { FocusRequester() }
    LaunchedEffect(canSwitchKeyboard) {
        if (canSwitchKeyboard) runCatching { keyboardFocus.requestFocus() }
    }

    SidekitScaffold(
        title = "Welcome",
        actions = listOf(
            SideAction.forward("getStarted", "Get started", onDone)
        ),
        // A real extra action, so pressing Options here demonstrates what the card explains.
        options = listOf(
            SideAction("about", "About Sidetrack", SidekitIcon.BrandAwareness, onClick = onAbout)
        )
    ) { padding ->
        SidekitList(padding, state = list) {
            item(key = "intro") {
                SidekitText(
                    "Sidetrack is made for the keypad: arrows move, OK or Enter chooses, Back returns.",
                    Modifier.padding(horizontal = 18.dp),
                    style = SidekitTheme.typography.caption,
                    maxLines = Int.MAX_VALUE
                )
            }
            item(key = "keyboard") {
                SidetrackItem(
                    "Keyboard",
                    if (canSwitchKeyboard) Modifier.focusRequester(keyboardFocus) else Modifier,
                    secondary = keyboardLabel,
                    secondaryToEnd = true,
                    onClick = if (canSwitchKeyboard) onSwitchKeyboard else null
                )
            }
            item(key = "keyboardHint") {
                SidekitText(
                    keyboardHint,
                    Modifier.padding(horizontal = 18.dp),
                    style = SidekitTheme.typography.small,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 2
                )
            }
            item(key = "options") {
                SidekitCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SidekitText(
                            "Options opens more",
                            style = SidekitTheme.typography.label,
                            maxLines = Int.MAX_VALUE
                        )
                        SidekitText(
                            optionsHint(module),
                            style = SidekitTheme.typography.caption,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
            item(key = "guide") {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionLabel("Keypad guide")
                    KeypadGuide(module)
                }
            }
        }
    }
}

/** The Options key sits differently on each module, so the copy follows the attached one. */
internal fun optionsHint(module: KeypadModule): String = when (module) {
    KeypadModule.T9, KeypadModule.Qwerty ->
        "Press Options (top right) for this screen's extra actions."

    KeypadModule.Sundial ->
        "Hold Enter (or ←) for this screen's extra actions."

    KeypadModule.GamePad ->
        "Press the Options button for this screen's extra actions."
}
