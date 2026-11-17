package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The Options bar and the contextual row bar both own the action bar. They must dismiss each
 * other instead of stacking, and a row selection must not survive hidden behind Options.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowActionBarTest {
    @get:Rule val compose = createComposeRule()

    private val selection = RowActionSelection(
        key = "row",
        title = "Row",
        actions = listOf(
            RowAction(
                key = "queue",
                label = "Queue",
                icon = SidekitIcon.QueueMusic,
                onClick = {}
            )
        )
    )

    private fun showHost(chrome: ChromeState, onState: (RowActionBarState) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SidekitTheme(spacing = SidekitSpacing.Compact) {
                    SidekitCanvas(chrome = chrome) {
                        Host(onState)
                    }
                }
            }
        }
    }

    @Test
    fun openingRowActionsClosesOptions() {
        val chrome = ChromeState()
        lateinit var state: RowActionBarState
        showHost(chrome) { state = it }

        compose.runOnIdle { chrome.toggleOptions() }
        compose.waitForIdle()
        assertTrue(chrome.optionsOpen)

        compose.runOnIdle { state.show(selection) }
        compose.waitForIdle()

        assertFalse(chrome.optionsOpen)
        assertEquals("row", state.selection?.key)
    }

    @Test
    fun undoIsOfferedInTheBar() {
        val chrome = ChromeState()
        lateinit var state: RowActionBarState
        showHost(chrome) { state = it }

        compose.runOnIdle {
            state.showUndo(
                RowActionUndo(
                    contentDescription = "Undo remove Row from Liked Songs",
                    onClick = {}
                )
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Undo remove Row from Liked Songs").assertExists()
    }

    @Test
    fun openingOptionsClosesRowActions() {
        val chrome = ChromeState()
        lateinit var state: RowActionBarState
        showHost(chrome) { state = it }

        compose.runOnIdle { state.show(selection) }
        compose.waitForIdle()
        assertEquals("row", state.selection?.key)

        compose.runOnIdle { chrome.toggleOptions() }
        compose.waitForIdle()

        assertTrue(chrome.optionsOpen)
        assertNull(state.selection)
    }
}

@Composable
private fun Host(onState: (RowActionBarState) -> Unit) {
    val state = rememberRowActionBarState()
    SideEffect { onState(state) }
    SidekitScaffold(
        title = "Test",
        actions = rowActionBarActions(
            state,
            listOf(SideAction("normal", "Normal", onClick = {}))
        ),
        options = listOf(SideAction("option", "Option", onClick = {}))
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}
