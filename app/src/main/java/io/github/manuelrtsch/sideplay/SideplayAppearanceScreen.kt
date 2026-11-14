package io.github.manuelrtsch.sideplay

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
 * Sideplay's own theme settings. With a compatible Sidekit host, Sidekit still owns the appearance
 * used by the host; this screen keeps Sideplay's local fallback settings editable.
 */
@Composable
fun SideplayAppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) {
        SideplayLocalSettings(context).applyStandaloneThemeDefaults()
        SharedPreferencesSidekitThemeStore(context, SideplayLocalSettings.FILE)
    }
    val theme = rememberSidekitThemeConfig(store)
    val list = rememberLazyListState()

    SidekitScaffold("Appearance", actions = listOf(SideAction.back("back", "Settings", onBack))) { padding ->
        SidekitList(padding, state = list) {
            caseColourChoices(theme.caseColour) { store.writeCaseColour(it) }
            item {
                SideplayItem(
                    "Compact layout",
                    secondary = if (theme.compact) "On" else "Off",
                    secondaryToEnd = true,
                    onClick = { store.writeCompact(!theme.compact) }
                )
            }
        }
    }
}
