package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.design.theme.drawFocusRing

/**
 * The list rows' focus feedback for a field card: a white ring and a slight lift while the field
 * inside has focus, and D-pad Up/Down moving between fields instead of staying inside the text.
 * Left/Right are left alone: inside a field they move the caret.
 */
@Composable
private fun Modifier.fieldFocus(): Modifier {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(FOCUS_RING_MS),
        label = "fieldFocusRing"
    )
    return this
        .onFocusChanged { focused = it.hasFocus }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                when (event.key) {
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    else -> false
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (ring > 0f) drawFocusRing(ring)
        }
}

private const val FOCUS_RING_MS = 120

/**
 * A labelled one-line Sidekit field in an item card, the boxed form used across Sidekit's apps.
 * The label sits inside the card so the placeholder can carry an example (the demo credentials)
 * without having to say what the field is for.
 */
@Composable
internal fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    modifier: Modifier = Modifier,
    fieldDescription: String? = null,
    focusOnKeypad: Boolean = false,
    autoFocus: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    autoCorrectEnabled: Boolean? = null,
    masked: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    contentType: ContentType? = null,
    maxLength: Int? = null
) {
    SidekitCard(
        modifier.fillMaxWidth()
            .heightIn(min = SidekitTheme.spacing.itemHeight)
            .fieldFocus()
    ) {
        Column(Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
            SidekitText(
                label,
                style = SidekitTheme.typography.caption,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 1
            )
            SidekitTextField(
                value,
                { typed -> onValueChange(if (maxLength != null) typed.take(maxLength) else typed) },
                Modifier.fillMaxWidth()
                    .then(if (fieldDescription != null) Modifier.semantics { contentDescription = fieldDescription } else Modifier),
                placeholder = placeholder,
                focusOnKeypad = focusOnKeypad,
                singleLine = true,
                autoFocus = autoFocus,
                keyboardType = keyboardType,
                autoCorrectEnabled = autoCorrectEnabled,
                masked = masked,
                imeAction = imeAction,
                onNext = onNext,
                onDone = onDone,
                contentType = contentType
            )
        }
    }
}
