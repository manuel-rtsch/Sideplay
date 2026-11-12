package io.github.manuelrtsch.sideplay

import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.icons.SidekitIcon

/** The bar while a delete can still be taken back. */
fun undoBar(continueWith: SideAction, undo: SideAction? = null): List<SideAction> = if (undo == null) listOf(continueWith) else listOf(continueWith, undo)

fun SideAction.Companion.undo(
    key: String = "undo",
    label: String? = "Undo",
    contentDescription: String? = null,
    onClick: () -> Unit
) = SideAction(key, label, SidekitIcon.Undo, tone = ActionTone.Muted, contentDescription = contentDescription, onClick = onClick)

fun SideAction.Companion.delete(key: String, label: String, onClick: () -> Unit) = SideAction(key, label, SidekitIcon.DeleteForever, tone = ActionTone.Destructive, onClick = onClick)

fun SideAction.Companion.cancel(key: String, label: String, onClick: () -> Unit) = SideAction(key, label, tone = ActionTone.Muted, onClick = onClick)
