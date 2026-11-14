package io.github.manuelrtsch.sideplay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import hrln.interactive.sidekit.design.theme.drawFocusRing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The SideKit focus ring, but an unfocused row drops its ring immediately instead of fading it
 * out. In the queue the focus border moves on every key press; a fading old ring overlaps the new
 * row and reads as a blink.
 */
object QueueIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)

    override fun equals(other: Any?) = other === this

    override fun hashCode() = 0

    private class Node(private val source: InteractionSource) :
        Modifier.Node(),
        DrawModifierNode {
        private var pressed = false
        private val focus = Animatable(0f)
        private var focusJob: Job? = null

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressed = true

                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false

                        is FocusInteraction.Focus -> {
                            focusJob?.cancel()
                            focusJob = launch { focus.animateTo(1f, tween(FOCUS_MS)) { invalidateDraw() } }
                        }

                        is FocusInteraction.Unfocus -> {
                            // Snap, don't fade: the old row must be clean before the new one lights up.
                            focusJob?.cancel()
                            focusJob = null
                            focus.snapTo(0f)
                        }
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            val f = focus.value
            if (f > 0f) drawFocusRing(f)
            if (pressed) drawRect(Color.White.copy(alpha = 0.12f))
        }
    }

    private const val FOCUS_MS = 120
}
