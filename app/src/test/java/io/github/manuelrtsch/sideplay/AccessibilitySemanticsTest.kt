package io.github.manuelrtsch.sideplay

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun toggleRowExposesSwitchState() {
        compose.setContent {
            SidekitPreviewFrame {
                SideplayItem("Autoplay", secondary = "On", toggleable = true, onClick = {})
            }
        }

        compose.onNodeWithText("Autoplay").assertIsToggleable().assertIsOn()
    }

    @Test
    fun selectedRowExposesRadioState() {
        compose.setContent {
            SidekitPreviewFrame {
                SideplayItem(
                    "Very high",
                    selected = true,
                    selectionRole = Role.RadioButton,
                    onClick = {}
                )
            }
        }

        compose.onNodeWithText("Very high").assertIsSelectable().assertIsSelected()
    }
}
