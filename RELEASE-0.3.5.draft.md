# Sidetrack 0.3.5

An onboarding and queue release. Welcome now teaches the keypad before Home, whole collections
can be queued in one go, and the seek bar follows the audio clock instead of the decoder.

## Onboarding

- **Welcome opens after sign-in.** A fresh install, or an upgrade from a version older than
  0.3.4, lands on Welcome instead of Home and no longer needs a restart to see it.
- **The keypad is taught up front.** Welcome shows the three habits a listener needs: Back
  returns, Options opens the page's extra actions, and a focused row's round trigger opens that
  item's actions. On touch, a row is dragged left to reveal the same menu.
- **The keypad map is inline.** The diagram follows the attached module (T9/QWERTY, Sundial or
  GamePad), so the printed keys can be found without leaving the screen.
- **Keyboard has its own page.** The module, Back-key status and the full keypad guide moved
  there; the guide starts collapsed.

## Queue

- **Add a whole collection.** Albums, playlists, artists and shows can be queued in one action
  instead of row by row.
- **Two queue verbs, two vocabularies.** Opening the queue is a destination: the QueueMusic
  glyph, the noun **Queue**, and the `queue` key. Adding an item is a verb: the PlaylistAdd
  glyph, **Add to queue**, and the `addToQueue` key. Row gutters and item Options say **Add to
  queue**; the chrome keeps **Queue**.

## Now Playing

- **Song radio stays reachable.** The art backdrop layout no longer swaps the middle Options
  square for Open album. Tapping the artwork opens the album, so Song radio keeps its square in
  both layouts and the bar stays at three buttons.
- **The seek bar follows the audio clock.** A landed seek is no longer overwritten by a stale
  decoder position, so the bar does not snap back after a slow-network seek. Keypad seeks are
  debounced, and the position freezes at **Buffering** while the drain has no PCM instead of
  advancing through silence. A dead drain recovers without losing the current item.

## Feedback and undo

- **One notice clock.** Every action-bar notice goes through Sidekit's `withNotice`: successes
  last two seconds, warnings, errors and playback upkeep last five. Tapping dismisses early, and
  a notice no longer inherits the previous message's duration or strands itself on the next
  screen.
- **No duplicate confirmations.** Clearing the cache, clearing the history and the now-playing
  card on Queue no longer announce what the screen already shows. Clearing an empty history says
  **Nothing to clear**.
- **Undo uses one window.** Row, queue and Settings undo offers share Sidekit's `UndoWindow`:
  five seconds of visible time, paused while the app is in the background, committed when the
  screen goes away.

## Fixes

- **Row actions keep focus.** The action bar waits for its new buttons to compose before
  requesting focus, and the row no longer keeps focus when its bar opens.
- **Active picker rows are readable.** The sleep timer and audio quality pickers paint the
  selected row in the same success green as the landing page's album tile, with the icon, title
  and trailing value in white.

## Notes

- Built against Sidekit 1.7.2.
- Verified on the SP-01 with the release build.
