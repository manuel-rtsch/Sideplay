package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** Preview at the Sidephone's 480 x 640 px panel (mdpi, so 1dp == 1px == 1 Figma px). */
@Preview(name = "Sidephone 480x640", widthDp = 480, heightDp = 640, device = "spec:width=480px,height=640px,dpi=160", showBackground = true, backgroundColor = 0xFF121212)
annotation class SidephonePreview

/**
 * A composition shaped like the app shell. [keypad] stands in for the keypad Sidekit attaches in
 * `SidekitActivity`, which a screen can read through `LocalKeypad`.
 */
@Composable
fun SidekitPreviewFrame(keypad: Keypad? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInspectionMode provides true) {
        SidekitTheme(spacing = SidekitSpacing.Compact) { SidekitCanvas(keypad = keypad) { content() } }
    }
}
