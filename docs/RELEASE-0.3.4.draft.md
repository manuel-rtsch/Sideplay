# Sidetrack 0.3.4

A row-actions release. Every list row now opens one contextual action bar instead of growing
squares, the queue gained real editing, and podcasts stopped queueing the whole archive.

## Row actions, rebuilt

- **One trigger per row.** A focused row reveals a single actions trigger; pressing it publishes
  that row's actions to the screen's contextual bar. The row keeps its title, and the bar keeps
  the verbs.
- **Swipe is the touch equivalent.** A left swipe reveals the same trigger. Focus and swipe share
  one right edge, so two triggers are never visible at once.
- **Save and Queue stay the track verbs.** Save/Unsave keeps its bookmark tile and Undo; Queue
  still works before anything is playing.
- **Haptics are touch-only.** A swipe ticks, a focus settle does not — otherwise a held D-pad
  would buzz on every row it passed. All haptics now come from one place.

## Queue

- **Reorder from the row.** The contextual bar carries Reorder alongside Delete, in addition to
  the existing list mode in Options.
- **Delete with Undo.** Removing an upcoming track offers Undo, and clearing the tail asks for
  confirmation instead of wiping on the first press.
- **Shuffle stays a list mode** and keeps its state in the header.

## Now Playing

- The transport scrim is full-bleed, and the art backdrop layout is refined.

## Podcasts

- **An episode plays alone.** The play tile and every episode row queue that one episode, not the
  show's newest-first archive. Lining up more is the row's Add to queue or Options' **Add latest
  to queue**.
- A finished episode stops even when Repeat was left on from a song.
- Previous returns to the item played before, instead of the track restart rule.

## Playback and performance

- librespot keeps its stored credential when refreshing tokens, so a token refresh no longer
  forces a re-login.
- Screen state is split into focused slices, background position ticks and lazy debug logging are
  gone, and the UI no longer does work while nothing is on screen.

## Under the hood

- The native catalog can now **add and remove playlist items** through the same `playlist4`
  protocol it already used for the rootlist: revision-conflict retry, `added_by` attribution,
  cache invalidation, and an `editable` flag on playlists so a picker can hide playlists the
  service would reject. **There is no UI for this yet** — it is the write path the add-to-playlist
  picker will use.

## Notes

- Verified on the SP-01 with the release build; see `docs/DEVICE-CHECKLIST.md` for the manual pass.
- The `Move` action in the queue uses the existing up-arrow icon; Sidekit 1.7.1 has no swap-vert
  glyph.
