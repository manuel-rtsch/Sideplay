package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.sdk.theme.SharedPreferencesSidekitThemeStore
import hrln.interactive.sidekit.sdk.theme.caseColourChoices
import hrln.interactive.sidekit.sdk.theme.rememberSidekitThemeConfig

/**
 * Sidetrack's own theme settings. With a compatible Sidekit host, Sidekit still owns the appearance
 * used by the host; this screen keeps Sidetrack's local fallback settings editable.
 */
@Composable
fun SidetrackAppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) {
        SidetrackLocalSettings(context).applyStandaloneThemeDefaults()
        SharedPreferencesSidekitThemeStore(context, SidetrackLocalSettings.FILE)
    }
    val theme = rememberSidekitThemeConfig(store)
    val list = rememberLazyListState()

    SidekitScaffold("Appearance", actions = listOf(SideAction.back("back", "Settings", onBack))) { padding ->
        SidekitList(padding, state = list) {
            item {
                SidetrackItem(
                    "Compact layout",
                    secondary = if (theme.compact) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = theme.compact,
                    onClick = { store.writeCompact(!theme.compact) }
                )
            }
            caseColourChoices(theme.caseColour) { store.writeCaseColour(it) }
        }
    }
}
