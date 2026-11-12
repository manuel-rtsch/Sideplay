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
import hrln.interactive.sidekit.sdk.settings.KeypadMappingScreen
import hrln.interactive.sidekit.sdk.theme.SharedPreferencesSidekitThemeStore
import hrln.interactive.sidekit.sdk.theme.caseColourChoices
import hrln.interactive.sidekit.sdk.theme.rememberSidekitThemeConfig

private enum class AppearancePage { Main, Mapping, Capture, Tester }

/**
 * Sideplay's own theme and keypad settings. Only reachable when no compatible Sidekit host is
 * installed; with a host, Sidekit owns these settings and Sideplay reads them instead.
 */
@Composable
fun SideplayAppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SharedPreferencesSidekitThemeStore(context, SideplayLocalSettings.FILE) }
    val theme = rememberSidekitThemeConfig(store)
    val local = remember(context) { SideplayLocalSettings(context) }
    val keypadPrefs = remember(context) { SharedPreferencesKeypadPreferences(context, SideplayLocalSettings.KEYPAD_FILE) }
    val keypad = LocalKeypad.current
    var page by remember { mutableStateOf(AppearancePage.Main) }
    var capture by remember { mutableStateOf<Pair<KeypadModule, KeypadControl>?>(null) }
    var inputMode by remember { mutableStateOf(local.inputMode) }

    when (page) {
        AppearancePage.Main -> AppearanceSettings(
            compact = theme.compact,
            selected = theme.caseColour,
            module = keypad?.module ?: keypadPrefs.module() ?: KeypadModule.T9,
            custom = keypad?.mapper?.customModule,
            inputMode = inputMode,
            onBack = onBack,
            onColour = { store.writeCaseColour(it) },
            onCompact = { store.writeCompact(!theme.compact) },
            onModule = { next -> keypad?.useModule(next) ?: keypadPrefs.writeModule(next) },
            onInputMode = { next ->
                local.inputMode = next
                inputMode = next
                keypad?.applyInputMode(next)
            },
            onMapping = { page = AppearancePage.Mapping }
        )

        AppearancePage.Mapping -> KeypadMappingScreen(
            back = { page = AppearancePage.Main },
            learn = { module, control ->
                capture = module to control
                page = AppearancePage.Capture
            },
            tester = { page = AppearancePage.Tester }
        )

        AppearancePage.Capture -> {
            val (module, control) = capture ?: return
            KeyCaptureScreen(module, control, done = { page = AppearancePage.Mapping })
        }

        AppearancePage.Tester -> KeyTesterScreen(back = { page = AppearancePage.Mapping })
    }
}

@Composable
private fun AppearanceSettings(
    compact: Boolean,
    selected: hrln.interactive.sidekit.design.theme.CaseColour?,
    module: KeypadModule,
    custom: KeypadModule?,
    inputMode: T9Mode,
    onBack: () -> Unit,
    onColour: (hrln.interactive.sidekit.design.theme.CaseColour?) -> Unit,
    onCompact: () -> Unit,
    onModule: (KeypadModule) -> Unit,
    onInputMode: (T9Mode) -> Unit,
    onMapping: () -> Unit
) {
    val list = rememberLazyListState()
    SidekitScaffold("Appearance", actions = listOf(SideAction.back("back", "Settings", onBack))) { padding ->
        SidekitList(padding, state = list) {
            caseColourChoices(selected, onColour)
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
                SideplayItem(
                    "Compact layout",
                    secondary = if (compact) "On" else "Off",
                    showLinkArrow = true,
                    onClick = onCompact
                )
            }
            item {
                SideplayItem("Keypad mapping", secondary = "Customise", showLinkArrow = true, onClick = onMapping)
            }
        }
    }
}
