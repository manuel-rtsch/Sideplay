package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sideplay.ui.SidekitSwipeRow
import io.github.manuelrtsch.sideplay.ui.SwipeAction
import io.github.manuelrtsch.sideplay.ui.SwipeRevealGroup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeRevealFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun focusMovesFromRowIntoRevealedActionWithoutClosingIt() {
        compose.setContent {
            val group = remember { SwipeRevealGroup() }
            val rowFocus = remember { FocusRequester() }
            SidekitPreviewFrame {
                SidekitSwipeRow(
                    key = "row",
                    group = group,
                    modifier = Modifier.width(320.dp),
                    action = SwipeAction(
                        label = "Add to queue",
                        icon = SidekitIcon.QueueMusic,
                        color = SidekitTheme.colors.successA1,
                        onClick = {}
                    )
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .focusRequester(rowFocus)
                            .testTag("row")
                            .clickable {}
                    )
                }
            }
        }

        compose.onNodeWithTag("row").requestFocus()
        compose.waitForIdle()
        // The action is revealed after the focus-settle delay.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add to queue").assertExists()

        compose.onNodeWithTag("row").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add to queue").assertIsFocused()
    }
}
