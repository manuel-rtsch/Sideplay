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
 * Sidetrack's appearance, in both of the shapes it can take.
 *
 * With no compatible Sidekit host installed it is the whole screen: the case colour, the compact
 * layout and Sidetrack's own rows. With a host installed Sidekit owns the theme — it reads
 * `sidekit_settings` and a host-backed theme store ignores writes — so those rows disappear and
 * what remains is what no host can hold: the media-row density and the Now Playing backdrop.
 *
 * The two variants are one screen on purpose. They differ only in which rows exist, and the shell
 * they share (the scaffold, the back action, the list and its focus) is the part a second screen
 * would duplicate and then let drift. It is a duplicated screen, not a shared one, that made a
 * Sidetrack-only setting vanish behind the host in the first place: the row was bundled with rows
 * the host really does own.
 */
@Composable
fun SidetrackAppearanceScreen(hostBacked: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val compactRows = rememberCompactRows()
    val backdrop = rememberNowPlayingBackdropSetting()
    val list = rememberLazyListState()
    // The store is Sidetrack's local copy either way; only a standalone install edits it, and only
    // a standalone install seeds it with the compact-layout default. A host-backed install must
    // not write that seed, or a later uninstall would look like a choice the user never made.
    val store = remember(context, hostBacked) {
        if (!hostBacked) SidetrackLocalSettings(context).applyStandaloneThemeDefaults()
        SharedPreferencesSidekitThemeStore(context, SidetrackLocalSettings.FILE)
    }
    // An idle reader when hosted: the rows that would show the theme are not composed, and this
    // keeps the write path (`store.writeCompact`) off the screen entirely in that case.
    val theme = rememberSidekitThemeConfig(store)

    SidekitScaffold("Appearance", actions = listOf(SideAction.back("back", "Settings", onBack))) { padding ->
        SidekitList(padding, state = list) {
            if (!hostBacked) {
                item {
                    SidetrackItem(
                        "Compact layout",
                        secondary = if (theme.compact) "On" else "Off",
                        secondaryToEnd = true,
                        toggleable = theme.compact,
                        onClick = { store.writeCompact(!theme.compact) }
                    )
                }
            }
            item {
                SidetrackItem(
                    "Compact rows",
                    secondary = if (compactRows) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = compactRows,
                    onClick = { SidetrackLocalSettings(context).compactRows = !compactRows }
                )
            }
            item {
                SidetrackItem(
                    // Short on purpose: the Sidephone's row has room for a label and its On/Off,
                    // and "Now Playing backdrop" pushed the value off the edge.
                    "Backdrop",
                    secondary = if (backdrop.enabled) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = backdrop.enabled,
                    onClick = backdrop::toggle
                )
            }
            if (!hostBacked) {
                caseColourChoices(theme.caseColour) { store.writeCaseColour(it) }
            }
        }
    }
}
