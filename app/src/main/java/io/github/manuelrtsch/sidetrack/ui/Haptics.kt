package io.github.manuelrtsch.sidetrack.ui

import android.view.HapticFeedbackConstants
import android.view.View

/*
 * The app's whole haptic vocabulary, and the rule behind it.
 *
 * **Haptics belong to touch only.** A finger has no focus ring, so a hold that registered has to
 * be confirmed by the phone itself; the keypad already has the focus ring, and a D-pad held
 * through a list would buzz once per row it passes. Every action both hands can reach therefore
 * ticks on its touch route and stays silent on its keypad route — see [GutterRow]'s long-press
 * reveal against its focus settle, and [rowLongPress].
 *
 * Nothing here is called from a focus event. If a new gesture needs a tick, add it here rather
 * than reaching for `performHapticFeedback` at the call site, so this rule has one home.
 */

/** A hold has been recognised — the standard platform acknowledgement for a long press. */
fun View.longPressHaptic() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/** A swipe has crossed the point where releasing runs the action rather than leaving it open. */
fun View.armedHaptic() {
    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

/** An action square was pressed. A destructive action warns; everything else acknowledges. */
fun View.actionHaptic(destructive: Boolean) {
    performHapticFeedback(
        if (destructive) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONTEXT_CLICK
    )
}
