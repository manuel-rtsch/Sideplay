package io.github.manuelrtsch.sidetrack

import android.view.ViewGroup
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowViewGroup

/**
 * Records every haptic a view asks for.
 *
 * [ShadowViewGroup.lastHapticFeedbackPerformed] cannot answer the question this suite needs: it is an
 * `int` that starts at `0`, and `HapticFeedbackConstants.LONG_PRESS` is also `0`, so "never
 * vibrated" and "vibrated with the long-press tick" are the same value. A list of the constants,
 * empty until something asks, distinguishes silence from the tick.
 */
@Implements(ViewGroup::class)
class RecordingHapticShadow : ShadowViewGroup() {
    override fun performHapticFeedback(feedbackConstant: Int): Boolean {
        performed += feedbackConstant
        return super.performHapticFeedback(feedbackConstant)
    }

    companion object {
        /** Every constant asked for since the last [reset]. */
        val performed = mutableListOf<Int>()

        fun reset() = performed.clear()
    }
}
