package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.NoticeKind

/**
 * The transient bar message: the one line at the bottom of the screen that reports a failed save,
 * an unavailable track or a finished autoplay.
 *
 * It is its own collaborator because almost every feature reports something, and none of them
 * should need the ViewModel to say it.
 */
internal class Notifier(private val store: UiStateStore) {

    fun say(text: String, kind: NoticeKind = NoticeKind.Other) = store.update { it.copy(notice = text, noticeKind = kind) }

    fun dismiss() = store.update { it.copy(notice = null) }
}
