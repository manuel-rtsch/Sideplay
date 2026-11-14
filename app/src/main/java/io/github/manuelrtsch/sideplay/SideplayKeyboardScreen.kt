package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.input.KeypadControl
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.input.T9Mode
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import hrln.interactive.sidekit.sdk.input.applyInputMode
import hrln.interactive.sidekit.sdk.settings.KeyCaptureScreen
import hrln.interactive.sidekit.sdk.settings.KeyTesterScreen
import hrln.interactive.sidekit.sdk.settings.KeypadGuide
import hrln.interactive.sidekit.sdk.settings.KeypadMappingScreen

private enum class KeyboardPage { Main, Mapping, Capture, Tester }

/** Sideplay's keypad choice, input mode, mapping and guide, separate from theme settings. */
@Composable
fun SideplayKeyboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val local = remember(context) { SideplayLocalSettings(context) }
    val keypadPrefs = remember(context) {
        SharedPreferencesKeypadPreferences(context, SideplayLocalSettings.KEYPAD_FILE)
    }
    val keypad = LocalKeypad.current
    var page by remember { mutableStateOf(KeyboardPage.Main) }
    var capture by remember { mutableStateOf<Pair<KeypadModule, KeypadControl>?>(null) }
    var inputMode by remember { mutableStateOf(local.inputMode) }

    when (page) {
        KeyboardPage.Main -> KeyboardSettings(
            module = keypad?.module ?: keypadPrefs.module() ?: KeypadModule.T9,
            custom = keypad?.mapper?.customModule,
            inputMode = inputMode,
            onBack = onBack,
            onModule = { next -> keypad?.useModule(next) ?: keypadPrefs.writeModule(next) },
            onInputMode = { next ->
                local.inputMode = next
                inputMode = next
                keypad?.applyInputMode(next)
            },
            onMapping = { page = KeyboardPage.Mapping }
        )

        KeyboardPage.Mapping -> KeypadMappingScreen(
            back = { page = KeyboardPage.Main },
            learn = { module, control ->
                capture = module to control
                page = KeyboardPage.Capture
            },
            tester = { page = KeyboardPage.Tester }
        )

        KeyboardPage.Capture -> {
            val (module, control) = capture ?: return
            KeyCaptureScreen(module, control, done = { page = KeyboardPage.Mapping })
        }

        KeyboardPage.Tester -> KeyTesterScreen(back = { page = KeyboardPage.Mapping })
    }
}

@Composable
private fun KeyboardSettings(
    module: KeypadModule,
    custom: KeypadModule?,
    inputMode: T9Mode,
    onBack: () -> Unit,
    onModule: (KeypadModule) -> Unit,
    onInputMode: (T9Mode) -> Unit,
    onMapping: () -> Unit
) {
    val list = rememberLazyListState()
    SidekitScaffold("Keyboard", actions = listOf(SideAction.back("back", "Settings", onBack))) { padding ->
        SidekitList(padding, state = list) {
            item {
                SideplayItem(
                    "Keypad",
                    secondary = custom?.let { "${it.label} · custom" } ?: module.label,
                    secondaryToEnd = true,
                    onClick = {
                        if (custom != null) {
                            onMapping()
                        } else {
                            onModule(KeypadModule.entries[(module.ordinal + 1) % KeypadModule.entries.size])
                        }
                    }
                )
            }
            item {
                SideplayItem(
                    "Input mode",
                    secondary = if (inputMode == T9Mode.Numeric) "Numbers" else "Letters",
                    secondaryToEnd = true,
                    onClick = { onInputMode(T9Mode.entries[(inputMode.ordinal + 1) % T9Mode.entries.size]) }
                )
            }
            item {
                SideplayItem("Keypad mapping", secondary = "Customise", showLinkArrow = true, onClick = onMapping)
            }
            item { KeypadGuide(module) }
        }
    }
}
