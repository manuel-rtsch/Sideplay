package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice
import hrln.interactive.sidekit.design.icons.SidekitIcon

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

/**
 * The screen's Options menu, built in one place.
 *
 * Options are the screen's destinations and its list-wide controls. Every menu is assembled here so
 * a shared entry cannot drift in wording, glyph, shape or colour from one screen to the next; the
 * audit in `docs/options-menu-audit.md` found the same Sleep timer, Now playing and queue entries
 * all doing exactly that.
 *
 * Two rules, and neither is decided by the screen:
 *
 * - **Shape.** A destination is a wide button with its label beside its glyph, so pressing Options
 *   never asks the user to read a row of icons. Only Now Playing's playback shortcuts are icon-only
 *   squares, where the bar is already read as a row of transport controls. A stateful control is
 *   not an option at all: [toggleAction] puts it in the screen's own bar beside Back.
 * - **Colour.** [ActionTone.Primary] for every option, the way Sidekit's own apps paint their
 *   Options bars: an option is live, wherever it leads. [ActionTone.Secondary] marks a toggle that
 *   is *on*, [ActionTone.Destructive] destroys something. Grey is left to the close button the bar
 *   appends, and to notices and undo, which are not options at all.
 *
 * Order is destinations first, list-wide controls last, and Now playing always at the end, except
 * that a destructive entry is always last of all. Pass `null` for an entry the current state or the
 * connected service cannot offer; it disappears without leaving a gap.
 */
fun sidetrackOptions(
    vararg entries: SideAction?,
    onNowPlaying: (() -> Unit)? = null
): List<SideAction> = buildList {
    entries.forEach { entry -> if (entry != null) add(entry) }
    if (onNowPlaying != null) add(nowPlayingOption(onNowPlaying))
    // Destructive last, whatever order the caller listed them in: the bar must never open on the
    // button that destroys something.
    sortBy { if (it.tone == ActionTone.Destructive) 1 else 0 }
}

/**
 * A destination on this screen. Wide and labelled, in the theme accent like every other option: the
 * bar holds only live choices, so tone marks what an option *does* to the world (nothing here),
 * never how far it travels.
 */
fun nowPlayingOption(onClick: () -> Unit) = SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onClick)

fun settingsOption(onClick: () -> Unit) = SideAction("settings", "Settings", SidekitIcon.Settings, onClick = onClick)

fun aboutOption(onClick: () -> Unit) = SideAction("about", "About Sidetrack", SidekitIcon.BrandAwareness, onClick = onClick)

/** Creating a playlist writes something new, so it is stated (and coloured) like the other actions. */
fun newPlaylistOption(onClick: () -> Unit) = SideAction("createPlaylist", "New playlist", SidekitIcon.PlaylistAdd, tone = ActionTone.Primary, onClick = onClick)

/**
 * Queue what this page holds. [label] lets a show say "Add latest to queue" for the one episode it
 * holds, without inventing a second entry.
 */
fun addToQueueOption(label: String = "Add to queue", onClick: () -> Unit) = SideAction("addToQueue", label, SidekitIcon.PlaylistAdd, tone = ActionTone.Primary, onClick = onClick)

fun artistRadioOption(onClick: () -> Unit) = SideAction("artistRadio", "Artist radio", SidekitIcon.Explore, tone = ActionTone.Primary, onClick = onClick)

/**
 * Now Playing's options are the bar's one icon-only set: the screen has no room for labels and the
 * bar reads as transport beside the five tiles below it. They are accents like every other option —
 * only their shape differs, and their content description carries the name a label would.
 */
fun openQueueOption(onClick: () -> Unit) = SideAction.square("queue", SidekitIcon.QueueMusic, "Open queue", onClick)

fun songRadioOption(onClick: () -> Unit) = SideAction.square("songRadio", SidekitIcon.Explore, "Song radio", onClick)

fun episodeNotesOption(onClick: () -> Unit) = SideAction.square("notes", SidekitIcon.Docs, "Episode notes", onClick)

/**
 * The sleep timer is a destination with a state, so it keeps its label: the armed state is written
 * into the words as well as the colour, and both screens that offer it show it the same way. Idle it
 * is an ordinary option; armed it commits to stopping playback, so it turns green.
 */
fun sleepTimerOption(active: Boolean, onClick: () -> Unit) = SideAction(
    key = "sleepTimer",
    label = if (active) "Sleep timer on" else "Sleep timer",
    icon = SidekitIcon.Bedtime,
    tone = if (active) ActionTone.Secondary else ActionTone.Primary,
    onClick = onClick
)

/**
 * A stateful control in the screen's own action bar, beside its Back: Now Playing's Save and the
 * queue's Shuffle. Square and icon-only like the other bar controls, green while on, accent while
 * off, and the announcement names the state so colour is never the only signal.
 */
fun toggleAction(
    key: String,
    icon: SidekitIcon,
    offLabel: String,
    onLabel: String,
    on: Boolean,
    onClick: () -> Unit,
    onIcon: SidekitIcon = icon
) = SideAction.square(
    key = key,
    icon = if (on) onIcon else icon,
    contentDescription = if (on) onLabel else offLabel,
    onClick = onClick,
    tone = if (on) ActionTone.Secondary else ActionTone.Primary
)
