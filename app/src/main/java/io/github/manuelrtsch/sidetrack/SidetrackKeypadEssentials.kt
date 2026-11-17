package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.contentColorFor

/** One printed key and the one or two sentences a new listener needs to remember it. */
internal data class KeypadEssential(
    /** The label printed on the key. The gesture is described in [detail]. */
    val key: String,
    val detail: String
)

/**
 * The three keypad habits Sidetrack depends on, written for the attached module.
 *
 * T9 and QWERTY have dedicated Back and Options keys; the Sundial and GamePad turn gestures on
 * other keys into the same controls. Keeping the translation here means Welcome and Keyboard
 * cannot drift into giving different instructions for the same module.
 */
internal fun keypadEssentials(module: KeypadModule): List<KeypadEssential> {
    fun back(key: String, detail: String = "Go back a screen.") = KeypadEssential(key, detail)
    fun options(key: String, detail: String = "Tap for this page’s actions. Hold for the action bar.") = KeypadEssential(key, detail)
    val item = KeypadEssential("•••", "Press ••• for a focused row’s actions.")
    return when (module) {
        KeypadModule.T9, KeypadModule.Qwerty -> listOf(
            back("Back"),
            options("Options"),
            item
        )

        KeypadModule.Sundial -> listOf(
            back("Tab", "Hold Tab to go back a screen."),
            options("Enter"),
            item
        )

        KeypadModule.GamePad -> listOf(
            back("Back"),
            options("X"),
            item
        )
    }
}

/**
 * The compact key cheat sheet shared by Welcome and Keyboard. It is deliberately not a diagram:
 * the full [hrln.interactive.sidekit.sdk.settings.KeypadGuide] is a reference and lives behind
 * "Keypad guide", while these three rows are the actions a new listener has to know immediately.
 */
@Composable
internal fun KeypadEssentials(
    module: KeypadModule,
    modifier: Modifier = Modifier,
    customMapping: Boolean = false
) {
    val essentials = remember(module) { keypadEssentials(module) }
    val surface = SidekitTheme.colors.surfaceA3
    val contentColor = SidekitTheme.colors.contentColorFor(surface)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SidekitTheme.spacing.pageGap)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(SidekitTheme.shapes.item)
                .background(surface)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            essentials.forEach { essential ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Keycap(essential.key)
                    SidekitText(
                        essential.detail,
                        Modifier.weight(1f),
                        style = SidekitTheme.typography.small,
                        color = contentColor,
                        maxLines = 2
                    )
                }
            }
            // Touch screens have no round trigger to press, so the row menu is a swipe.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Keycap("← Drag")
                SidekitText(
                    "On touch, drag a row left to reveal its menu.",
                    Modifier.weight(1f),
                    style = SidekitTheme.typography.small,
                    color = contentColor,
                    maxLines = 2
                )
            }
        }
        if (customMapping) {
            SidekitText(
                "A custom mapping is active, so the printed labels may differ.",
                Modifier.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.tiny,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun Keycap(label: String) {
    val color = SidekitTheme.colors.surfaceA4
    Box(
        Modifier
            .height(38.dp)
            .widthIn(min = 76.dp)
            .clip(SidekitTheme.shapes.control)
            .background(color)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        SidekitText(
            label,
            style = SidekitTheme.typography.small,
            color = SidekitTheme.colors.contentColorFor(color),
            maxLines = 1
        )
    }
}
