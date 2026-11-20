package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerDurations
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.StateFlow

/**
 * The Sleep timer picker. Every row is a one-shot action: selecting it arms the timer and returns
 * to Now Playing, where the status line appears immediately.
 */
@Composable
fun SleepTimerScreen(
    sleepTimer: StateFlow<SleepTimerState>,
    onBack: () -> Unit,
    onOff: () -> Unit,
    onDuration: (Int) -> Unit,
    onEndOfTrack: () -> Unit
) {
    val timer by sleepTimer.collectAsStateWithLifecycle()
    SidekitScaffold(
        title = "Sleep timer",
        actions = listOf(SideAction.back("back", "Now playing", onBack))
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, modifier = Modifier.selectableGroup(), state = listState) {
            item(key = "off") {
                // Finished is not an armed timer either, so the Off row owns that state.
                val selected = !timer.active
                SidetrackItem(
                    "Off",
                    Modifier.listFade(listState, 0).then(Modifier.focusRequester(first)),
                    icon = if (selected) SidekitIcon.Check else null,
                    containerColor = if (selected) SidekitTheme.colors.successA1 else null,
                    selected = selected,
                    selectionRole = Role.RadioButton,
                    onClick = onOff
                )
            }
            SleepTimerDurations.forEachIndexed { index, minutes ->
                item(key = "minutes-$minutes") {
                    val active = (timer as? SleepTimerState.Countdown)
                        ?.takeIf { it.minutes == minutes }
                    val selected = active != null
                    SidetrackItem(
                        "$minutes minutes",
                        Modifier.listFade(listState, index + 1),
                        secondary = active?.let { "${it.remainingMinutes} min" },
                        secondaryToEnd = true,
                        secondaryNoEllipsis = true,
                        icon = if (selected) SidekitIcon.Check else null,
                        containerColor = if (selected) SidekitTheme.colors.successA1 else null,
                        secondaryColor = if (selected) SidekitTheme.colors.light else null,
                        selected = selected,
                        selectionRole = Role.RadioButton,
                        onClick = { onDuration(minutes) }
                    )
                }
            }
            item(key = "end-of-track") {
                val selected = timer == SleepTimerState.EndOfTrack
                SidetrackItem(
                    "End of track",
                    Modifier.listFade(listState, SleepTimerDurations.size + 1),
                    icon = if (selected) SidekitIcon.Check else null,
                    containerColor = if (selected) SidekitTheme.colors.successA1 else null,
                    selected = selected,
                    selectionRole = Role.RadioButton,
                    onClick = onEndOfTrack
                )
            }
        }
    }
}
