package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.key.key
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.data.ServiceChoice

/**
 * The one place a service is chosen: first run, and again after signing out or going back from
 * Sign in.
 *
 * Selection is deliberately not reachable while signed in. The app runs one service at a time,
 * so a picker in Settings would promise a switch that would have to tear down a live session.
 *
 * The screen is always the same screen: no row is pre-selected, and the bar holds the two things
 * a first-run user needs before committing to a service — what this app is, and how to drive it.
 * About sits on the left, Help on the right. No back action: this is the step behind Sign in, not
 * a step in front of something else, and the keypad Back key still leaves.
 */
@Composable
fun ServicePickerScreen(
    services: List<ServiceChoice>,
    onSelect: (ServiceId) -> Boolean,
    onAbout: () -> Unit = {},
    onHelp: () -> Unit = {}
) {
    // A service that cannot be opened stays selected nowhere and says so on its own row, so the
    // tap is never silently lost.
    var unavailable by remember { mutableStateOf<ServiceId?>(null) }
    val actions = remember(onAbout, onHelp) {
        listOf(
            // Both wear the theme colour: on the first screen the app shows, the two ways to find
            // out what it is are the main actions, not a grey afterthought.
            SideAction("about", "About", motion = ActionMotion.Up, onClick = onAbout),
            SideAction("help", "Help", motion = ActionMotion.Up, onClick = onHelp)
        )
    }
    // "Welcome", not "Welcome to Sidetrack": the shared header is one line at title size, and the
    // longer string truncates on the wheel ("Welcome to Sid…"). The box carries the greeting's
    // second half by telling the user what to do.
    SidekitScaffold(title = "Welcome", actions = actions) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            item(key = "explain") {
                SidekitCard(Modifier.listFade(listState, 0).fillMaxWidth()) {
                    SidekitText(
                        "Select an instance to get started",
                        style = SidekitTheme.typography.caption,
                        color = SidekitTheme.colors.surfaceA6,
                        maxLines = DESCRIPTION_MAX_LINES
                    )
                }
            }
            services.forEachIndexed { index, provider ->
                item(key = provider.id.value) {
                    SidetrackItem(
                        provider.displayName,
                        Modifier.listFade(listState, index + 1).then(
                            if (index == 0) Modifier.focusRequester(first) else Modifier
                        ),
                        secondary = if (unavailable == provider.id) "Unavailable" else null,
                        secondaryToEnd = true,
                        onClick = {
                            if (onSelect(provider.id)) unavailable = null else unavailable = provider.id
                        }
                    )
                }
            }
        }
    }
}
