package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

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
