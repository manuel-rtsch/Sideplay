package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.input.KeypadControl
import hrln.interactive.sidekit.design.input.KeypadModule
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
    val store = remember(context) { SharedPreferencesSidekitThemeStore(context, LOCAL_SETTINGS) }
    val theme = rememberSidekitThemeConfig(store)
    var page by remember { mutableStateOf(AppearancePage.Main) }
    var capture by remember { mutableStateOf<Pair<KeypadModule, KeypadControl>?>(null) }

    when (page) {
        AppearancePage.Main -> AppearanceSettings(
            compact = theme.compact,
            selected = theme.caseColour,
            onBack = onBack,
            onColour = { store.writeCaseColour(it) },
            onCompact = { store.writeCompact(!theme.compact) },
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
    onBack: () -> Unit,
    onColour: (hrln.interactive.sidekit.design.theme.CaseColour?) -> Unit,
    onCompact: () -> Unit,
    onMapping: () -> Unit
) {
    val list = rememberLazyListState()
    SidekitScaffold("Appearance", actions = listOf(SideAction.back("back", "Settings", onBack))) { padding ->
        SidekitList(padding, state = list) {
            caseColourChoices(selected, onColour)
            item {
                SidekitItem(
                    "Compact layout",
                    secondary = if (compact) "On" else "Off",
                    showLinkArrow = true,
                    onClick = onCompact
                )
            }
            item {
                SidekitItem("Keypad mapping", secondary = "Customise", showLinkArrow = true, onClick = onMapping)
            }
        }
    }
}

private const val LOCAL_SETTINGS = "sideplay_settings"
