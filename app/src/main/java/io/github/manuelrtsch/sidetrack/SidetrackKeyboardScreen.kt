package io.github.manuelrtsch.sidetrack

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCompactItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.input.KeypadControl
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.input.T9Mode
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import hrln.interactive.sidekit.sdk.input.applyInputMode
import hrln.interactive.sidekit.sdk.settings.KeyCaptureScreen
import hrln.interactive.sidekit.sdk.settings.KeyTesterScreen
import hrln.interactive.sidekit.sdk.settings.KeypadGuide
import hrln.interactive.sidekit.sdk.settings.KeypadMappingScreen

private enum class KeyboardPage { Main, Mapping, Capture, Tester }

/** Sidetrack's keypad choice, input mode, mapping and guide, separate from theme settings. */
@Composable
fun SidetrackKeyboardScreen(
    onBack: () -> Unit,
    backLabel: String = "Settings",
    keypadAccessAvailable: Boolean = true
) {
    val context = LocalContext.current.applicationContext
    val local = remember(context) { SidetrackLocalSettings(context) }
    val keypadPrefs = remember(context) {
        SharedPreferencesKeypadPreferences(context, SidetrackLocalSettings.KEYPAD_FILE)
    }
    val keypad = LocalKeypad.current
    var page by remember { mutableStateOf(KeyboardPage.Main) }
    var capture by remember { mutableStateOf<Pair<KeypadModule, KeypadControl>?>(null) }
    var inputMode by remember { mutableStateOf(local.inputMode) }
    var accessRevision by remember { mutableIntStateOf(0) }
    val keypadBackEnabled = remember(accessRevision) { SidetrackKeypadAccess.isEnabled(context) }
    val accessSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        accessRevision++
    }
    LifecycleResumeEffect(Unit) {
        accessRevision++
        onPauseOrDispose {}
    }

    when (page) {
        KeyboardPage.Main -> KeyboardSettings(
            module = keypad?.module ?: keypadPrefs.module() ?: KeypadModule.T9,
            custom = keypad?.mapper?.customModule,
            inputMode = inputMode,
            onBack = onBack,
            backLabel = backLabel,
            keypadAccessAvailable = keypadAccessAvailable,
            keypadBackEnabled = keypadBackEnabled,
            onEnableKeypadBack = {
                runCatching { accessSettings.launch(SidetrackKeypadAccess.settingsIntent()) }
            },
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
    backLabel: String,
    keypadAccessAvailable: Boolean,
    keypadBackEnabled: Boolean,
    onEnableKeypadBack: () -> Unit,
    onModule: (KeypadModule) -> Unit,
    onInputMode: (T9Mode) -> Unit,
    onMapping: () -> Unit
) {
    val list = rememberLazyListState()
    var showGuide by remember { mutableStateOf(false) }
    SidekitScaffold("Keyboard", actions = listOf(SideAction.back("back", backLabel, onBack))) { padding ->
        SidekitList(padding, state = list) {
            if (keypadAccessAvailable) {
                item(key = "keypadBack") {
                    SidekitCompactItem(
                        "Keypad Back key",
                        secondary = if (keypadBackEnabled) "On" else "Off · Enable",
                        onClick = onEnableKeypadBack
                    )
                }
            }
            item(key = "keypad") {
                SidekitCompactItem(
                    "Keypad",
                    secondary = custom?.let { "${it.label} · custom" } ?: module.label,
                    onClick = {
                        if (custom != null) {
                            onMapping()
                        } else {
                            onModule(KeypadModule.entries[(module.ordinal + 1) % KeypadModule.entries.size])
                        }
                    }
                )
            }
            item(key = "inputMode") {
                SidekitCompactItem(
                    "Input mode",
                    secondary = if (inputMode == T9Mode.Numeric) "Numbers" else "Letters",
                    onClick = { onInputMode(T9Mode.entries[(inputMode.ordinal + 1) % T9Mode.entries.size]) }
                )
            }
            item(key = "mapping") {
                SidekitCompactItem(
                    "Keypad mapping",
                    secondary = "Customise",
                    onClick = onMapping
                )
            }
            item(key = "guideRow") {
                SidekitCompactItem(
                    "Keypad guide",
                    secondary = if (showGuide) "Hide" else "Show",
                    onClick = { showGuide = !showGuide }
                )
            }
            if (showGuide) {
                if (custom != null) {
                    item(key = "guideCustom") {
                        SidekitText(
                            "A custom mapping is active; the guide shows the module’s default labels.",
                            Modifier.padding(horizontal = 18.dp),
                            style = SidekitTheme.typography.small,
                            color = SidekitTheme.colors.surfaceA6,
                            maxLines = 2
                        )
                    }
                }
                item(key = "guide") { KeypadGuide(module) }
            }
        }
    }
}
