package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice

/**
 * The screen's action bar, with the transient now-playing confirmation taking precedence over the
 * normal actions. A real notice (an error or confirmation) wins over the now-playing confirmation.
 */
fun sidetrackActions(
    chrome: ChromeUiState,
    onDismissNotice: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    actions: List<SideAction>,
    showNowPlayingNotice: Boolean = true
): List<SideAction> = when {
    chrome.notice != null ->
        withNotice(chrome.notice, onDismissNotice, actions, chrome.noticeKind)

    showNowPlayingNotice && chrome.nowPlayingNotice != null ->
        nowPlayingNoticeActions(chrome.nowPlayingNotice, onOpenNowPlaying, onDismissNowPlaying)

    else -> actions
}

fun sidetrackBack(label: String, onBack: () -> Unit) = listOf(SideAction.back("back", label, onBack))

/** The action bar for screens that can change saved state, plus any important notice. */
fun sidetrackBar(
    chrome: ChromeUiState,
    onDismissNotice: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    continueWith: SideAction,
    extra: List<SideAction> = emptyList(),
    showNowPlayingNotice: Boolean = true
): List<SideAction> = sidetrackActions(
    chrome,
    onDismissNotice,
    onOpenNowPlaying,
    onDismissNowPlaying,
    listOf(continueWith) + extra,
    showNowPlayingNotice
)
