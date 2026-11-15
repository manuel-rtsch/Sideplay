# Maestro Flow Suite — Implementation TODO

Status: flows 1–4 implemented and passing.  
Audience: the next agent finishing the Maestro suite.  
Read before starting:

- `maestro/README.md`
- `maestro/config.yaml`
- `maestro/flows/suite/*.yaml`, `maestro/flows/demo/*.yaml`, `maestro/flows/utility/*.yaml`
- `docs/SIDEPLAY.md` — behavior and guardrails
- `docs/DEVICE-CHECKLIST.md` — what is intentionally manual-only
- `app/src/main/java/io/github/manuelrtsch/sideplay/SpotifyGraph.kt` — routes and back labels
- `SpotifyLibraryScreens.kt`, `SpotifySearchScreen.kt`, `SpotifyNowPlayingScreens.kt`,
  `SpotifyHistoryScreen.kt`, `SpotifySettingsScreens.kt` — screen copy and controls

---

## 1. Current state

Status: **Flows 1–4 are implemented and passing** on `SP0100260901847`. `config.yaml` lists all
four suite flows.

### Layout

| Path | Purpose |
|---|---|
| `flows/suite/01-browse-play.yaml` | Implemented. Home → Library → artist → top track → album track → Home. Covers track, artist, album. |
| `flows/suite/02-search-library-writes.yaml` | Implemented. Search → scopes → follow write → save write → show episode → Now Playing. Covers show. |
| `flows/suite/03-queue-transport.yaml` | Implemented. Queue and Now Playing transport. |
| `flows/suite/04-history-settings.yaml` | Implemented. History removal and Settings confirmations. |
| `flows/demo/*.yaml` | Manual demos and older smoke/artist/queue flows. Not in `config.yaml`. |
| `flows/utility/record-prepare.yaml` | Recording setup only. Not in `config.yaml`. |
| `recordings/` | One MP4 per suite flow. Regenerate from the `demo` build (see `README.md`). |

### Implemented coverage

**01 — Browse & Play** covers app launch, Home, Library, followed artists, position-based artist
selection, artist Top tracks, starting playback, Now Playing, the artist's Albums section, an album
collection, playing an album track, and the full back stack to Home. It does not use Search.

**02 — Search & Library Writes** covers Search focus, T9 keypad input, `Search in` categories, an
artist result and follow/unfollow write, an album result and save/unsave write, a show result, first
episode playback, and Now Playing for an episode. It ends on Now Playing.

**03 — Queue & Transport** covers adding two recent tracks to the queue, removing an upcoming item
and Undo, Clear/Undo, optional Shuffle, and Now Playing transport: play/pause, Next, repeat cycling,
Save, and Seek. It ends on Now Playing.

**04 — History & Settings** covers Home → See more history, focusing a history row so its
gutter opens, removing one entry, dismissing the resulting notice, toggling Autoplay,
Normalize volume and Gapless playback, selecting an Audio quality option, and cancelling both
Clear cache and Clear history confirmations. It ends on Home.

### Not yet covered

- Pagination and empty/offline/error states

### Implementation notes

- Open Now Playing through **Options → Now playing** instead of racing the four-second now-playing
  notice. The older demo artist flow still uses the notice and can fail if Maestro's tap lands after
  it expires.
- Search input must be entered as T9 digit groups. The keypad mode is process-wide, so switch out of
  `123` mode first. The pinned query is documented in `README.md`.
- The last `Search in` row can sit behind the bottom action bar; toggle a category higher in the
  list, or scroll with centering.
- Episode durations are `h:mm:ss`, so show flows use `[0-9]+:[0-9]{2}(:[0-9]{2})?`.
- The old `queue-history-management.yaml` still mixes Queue and Settings. It is now a demo until
  flows 3 and 4 split it.

---

## 2. Target suite

Use **four flows**. Every flow starts at Home. Not every flow must return Home.

| # | Flow | File | Ends at |
|---|---|---|---|
| 1 | Browse & Play | `flows/suite/01-browse-play.yaml` | Home |
| 2 | Search & Library Writes | `flows/suite/02-search-library-writes.yaml` | Now Playing |
| 3 | Queue & Transport | `flows/suite/03-queue-transport.yaml` | Now Playing or Queue |
| 4 | History & Settings | `flows/suite/04-history-settings.yaml` | Home |

### Media coverage requirement

All four media kinds must be encountered at least once across the suite.

| Media kind | Required in | How |
|---|---|---|
| Music / track | Flow 1 and Flow 3 | Play a track; queue tracks |
| Artist | Flow 1 | Open a followed artist and assert the Artist page |
| Album | Flow 1 | Open an album from the artist’s Albums section and play a track |
| Show | Flow 2 | Open a show from Search and play/queue an episode |

Flow 2 may also touch artist/album results, but Flow 1 is responsible for guaranteeing artist and album coverage.

---

## 3. Shared fixture / preconditions

The current suite depends on whatever is on the device. Make that explicit.

- [x] Add a non-test fixture flow or document a device fixture in `maestro/README.md`.
- [x] Keep the fixture out of `config.yaml`, like `flows/utility/record-prepare.yaml`.
- [x] Fixture must leave the app on **Home** with:
  - signed-in account,
  - one current track,
  - at least 6 distinct history entries (needed for `See more history`),
  - at least 2 upcoming queue items.
- [x] Prefer a fixture flow (`flows/utility/seed-fixture.yaml`) over relying on flow order.
  No seed flow is needed: the `demo` build writes history and the queue itself at process start
  (`app/src/demo/.../DemoPack.kt`), and `flows/utility/record-prepare-demo.yaml` is a cold launch.
- [x] If seeding through Search is too slow, consider a debug-only fixture intent in the app; do not add production behavior for tests.
  Built as a `demo` build type, not an intent: no production screen or ViewModel changed, and
  `assembleRelease` contains none of it. See `CONTENT-FIXTURE.md` §9.
- [x] Document any content-specific fixture choices (query, show name, artist) in the flow header.

Without this fixture, Flows 3 and 4 remain order-dependent and cannot guarantee that `See more history` or a second artist exists.

---

## 4. Authoring conventions

- [x] Every flow starts with `launchApp` and `assertVisible: "Sideplay"`.
- [x] Do not hardcode artist/track/show titles unless the fixture explicitly guarantees them.
- [x] Use stable chrome labels: `Library`, `TOP TRACKS`, `Albums`, `Save`, `Saved`, `Follow`,
      `Following`, `Queue`, `History`, `Settings`, `Back`, `Sideplay`.
- [x] Use duration regex `[0-9]+:[0-9]{2}(:[0-9]{2})?` plus `index` to target track/episode rows.
- [x] Use `index` on `Artist` / `Album` / `Show` row subtitles rather than titles.
- [x] Use generated action labels:
  - `Add .* to queue`
  - `Remove .* from queue`
  - `Remove .* from history`
  - `Playing .* Open Now Playing`
- [x] Swipe syntax that works in this project:

```yaml
- swipe:
    from:
      text: "[0-9]+:[0-9]{2}"
      index: 1
    direction: LEFT
```

- [x] Timing: the now-playing notice lasts 4 seconds and queue Undo lasts 5 seconds.
      Do not put `waitForAnimationToEnd` between remove and Undo.
- [ ] Destructive confirmations: prefer `Cancel` in automated flows. Only confirm Clear history in
      a dedicated test that is meant to wipe state.
- [ ] If text/index selectors become brittle, add Compose test tags as a separate app change and
      expose them with `testTagsAsResourceId = true`. Do not silently replace content-agnostic
      selectors with screen coordinates.

---

## 5. Flow 1 — Browse & Play

File: `flows/suite/01-browse-play.yaml`  
Status: implemented and passing.  
Start: Home. End: Home.  
Media: music, artist, album.

### Route

- [x] Launch app, assert Home.
- [x] Tap `Library`, assert `Library`.
- [x] Scroll until `Artist` index `1` is visible, tap it.
- [x] Assert `TOP TRACKS`.
- [x] Tap duration regex index `2` to play the third top track.
- [x] Open Now Playing via **Options → Now playing**. If the track was already current, the track
      tap opens Now Playing directly and the Options step is skipped (`TOP TRACKS` still visible).
- [x] Assert Now Playing: `Sav(e|ed)`, `Previous`.
- [x] Tap `Back` to the artist.
- [x] Scroll to the `Albums` section and open the first album row.
- [x] Assert the collection page (album): `Sav(e|ed)` tile and track rows.
- [x] Tap duration regex index `0` to play the album's first track.
- [x] Open Now Playing again, with the same already-current guard.
- [x] Assert Now Playing.
- [x] Back to the artist and play another top track, then open Now Playing with the same guard,
      so the suite adds a history entry before flow 4 removes one.
- [x] Navigate back: Now Playing → artist → Library → Home.
      Artist back uses `Back`; Library → Home uses `Sideplay`.
- [x] Assert Home.

### Notes

- Built from `flows/demo/home-library-artist-nowplaying-home.yaml`, with album coverage added.
- The four-second now-playing notice is not used: Maestro's tap can land after it expires and hit
  `Back` instead. **Options → Now playing** is stable.
- Assumptions: at least two followed artists, and the chosen artist has at least three top tracks
  and one album with tracks.
- Do not use Search.

---

## 6. Flow 2 — Search & Library Writes

File: `flows/suite/02-search-library-writes.yaml`  
Status: implemented and passing.  
Start: Home. End: Now Playing.  
Media: show (required), plus artist and album results.

### Route

- [x] Launch app, assert Home.
- [x] Tap `Search` and focus `Search Spotify`.
- [x] Press a harmless key so the keypad action bar appears.
- [x] If the mode button reads `123`, tap it to return to multi-tap mode.
- [x] `eraseText`, then enter the pinned query as T9 digit groups.
- [x] `pressKey: Enter`, close the keypad with `Done`, `waitForAnimationToEnd`.
- [x] Assert a result section is visible (`TRACKS`).
- [x] Test scopes:
  - [x] Tap `Search in`.
  - [x] Assert `Search in` screen and `Categories`.
  - [x] Toggle `Artists` off and on again.
  - [x] Tap `Search` to return.
- [x] Open an artist result:
  - [x] Scroll to an `Artist` row and tap index `0`.
  - [x] Assert Artist page (`TOP TRACKS`, `Follow`/`Following`).
  - [x] Toggle follow and assert the opposite state.
  - [x] Toggle it back and assert the original state.
  - [x] Back to Search and close the keypad if it reopened.
- [x] Open an album result:
  - [x] Scroll the `ALBUMS` heading to the centre, tap the row below it.
  - [x] Assert collection page (`Sav(e|ed)` and track rows).
  - [x] Toggle save and assert the opposite state.
  - [x] Toggle it back and assert the original state.
  - [x] Back to Search.
- [x] Open a show result:
  - [x] Scroll to `SHOWS` and tap the row below it.
  - [x] Assert Show collection page (`Sav(e|ed)` and episode rows).
  - [x] Tap duration regex index `0` to play the first episode.
  - [x] If still on the show page, open Now Playing via **Options → Now playing**.
  - [x] Assert Now Playing (`Previous`, `Pause`/`Play`).

### Notes

- Pinned query: **Die drei**, entered as T9 digits `3444330377733444`
  (`d i e <space> d r e i`). The show section uses the first result below `SHOWS`; its title is not
  asserted.
- The keypad mode is process-wide. The flow probes it after the first key press and switches out of
  `123` mode when needed.
- The last `Search in` row can be overlapped by the bottom action bar; the flow toggles `Artists`
  instead of `Shows`.
- Episode durations are `h:mm:ss`, so the show selector is `[0-9]+:[0-9]{2}(:[0-9]{2})?`.
- Episodes have no saved-track endpoint, so Now Playing for an episode has no `Save` action. Assert
  `Previous` and `Pause`/`Play` instead.

---

## 7. Flow 3 — Queue & Transport

File: `flows/suite/03-queue-transport.yaml`  
Status: implemented and passing.  
Start: Home. End: Now Playing.  
Media: music / track.

### Route

- [x] Launch app, assert Home and `RECENTLY PLAYED`.
- [x] Play a recent track first, using the m:ss-only duration regex so an episode left
      current by an earlier flow cannot make Now Playing lack `Save`.
- [x] Add two recent tracks to the queue:
  - [x] Swipe left from m:ss duration regex index `0`, tap `Add .* to queue`.
  - [x] Swipe left from m:ss duration regex index `1`, tap `Add .* to queue`.
- [x] Tap `Queue`, assert `Queue`.
- [x] Remove the first upcoming item:
  - [x] Swipe left from the full duration regex index `1` (index `0` is the current row;
        the queue may contain `h:mm:ss` episodes).
  - [x] Tap `Remove .* from queue`.
  - [x] Tap `Undo` immediately, without a settle wait.
  - [x] `assertNotVisible: "Undo"`.
- [x] Clear/Undo the upcoming queue:
  - [x] Tap `Clear`.
  - [x] Tap `Undo` immediately.
  - [x] `assertNotVisible: "Undo"`.
- [x] Optional shuffle:
  - [x] `pressKey: Remote Menu`
  - [x] Tap `Shuffle off` / `Shuffle on`
  - [x] Leave the options bar open and tap `Now playing` from it.
- [x] Open Now Playing via Options → `Now playing`.
- [x] Assert Now Playing controls: `Previous`, `Pause`/`Play`, `Next`, `Save`,
      `Seek`, `Repeat: ...`.
- [x] Toggle play/pause both ways; assert the opposite control is visible.
- [x] Tap `Next`; assert Now Playing remains.
- [x] Cycle `Repeat: ...` through all labels and back to off.
- [x] Toggle `Save` and leave the current track saved.
- [x] End on Now Playing.

### Optional / manual-only

These are listed in `docs/DEVICE-CHECKLIST.md` as manual and may be too brittle for Maestro:

- Long-press drag reorder
- Keypad lift / step / drop / cancel
- Play next with focus on an upcoming row

Do not block the flow on those unless a stable key sequence is proven on device.

---

## 8. Flow 4 — History & Settings

File: `flows/suite/04-history-settings.yaml`  
Status: implemented and passing.  
Start: Home. End: Home.  
Media: whatever the fixture’s history contains; no additional requirement.

### Route

- [x] Launch app, assert Home.
- [x] Scroll until `See more history` is visible, tap it.
- [x] Assert `History`.
- [x] Remove one history entry:
  - [x] Capture the first row title with `copyTextFrom` if practical.
  - [x] Focus the first history row with the keypad so its gutter opens; the last commit
        moved `Remove .* from history` into a focus-revealed gutter, so it is not a direct tap.
  - [x] Tap `Remove .* from history` index `0`.
  - [x] Assert the history screen remains and the entry is gone (or count/list changed).
  - [x] Do not use the app’s Clear history confirmation for this step.
- [x] Tap `Sideplay` to return Home.
- [x] Open `pressKey: Remote Menu` → `Settings`; assert `Settings`.
- [x] Toggle `Autoplay` once.
- [x] Toggle `Normalize volume` once.
- [x] Toggle `Gapless playback` once.
- [x] Open `Audio quality`; assert the three options (`Normal`, `High`, `Max`); select one
      (prefer restoring the original choice if you can read it); back to Settings.
- [x] Scroll to `Clear cache`; tap it; assert `Cancel`/`Clear`; tap `Cancel`.
- [x] Scroll to `Clear history`; tap it; assert `Cancel`/`Clear`; tap `Cancel`.
- [x] Tap `Back`; assert Home.

### Notes

- Do not confirm Clear history here: it destroys the fixture for other flows.
- Settings toggles are stateful. Repeated suite runs should still pass because each flow toggles
  once and only asserts the row is still present/actionable.
- If asserting the actual On/Off change, capture the secondary text before tapping and assert the
  opposite after; do not hardcode the initial state.

---

## 9. Implementation order

- [x] Decide/implement the fixture (Section 3). Implemented as the `demo` build type; account
  mode still uses the manual device fixture.
- [x] Create `flows/suite/01-browse-play.yaml` from the existing artist flow; add album coverage.
- [x] Create `flows/suite/02-search-library-writes.yaml`.
- [x] Create `flows/suite/03-queue-transport.yaml` from the queue half of the demo flow.
- [x] Create `flows/suite/04-history-settings.yaml` from the settings half, plus the History screen path.
- [x] Move the structured flows into `flows/suite/` and number them.
- [x] Move demos into `flows/demo/` and the recording utility into `flows/utility/`.
- [x] Update `config.yaml` for the implemented suite flows; add 03/04 when written.
- [x] Update `README.md`: flow list, fixture/preconditions, media coverage, search input.
- [x] Remove or demote `home-library-home.yaml` (moved to `flows/demo/`).
- [x] Retire or re-scope `hero-play-search-queue.yaml` (moved to `flows/demo/`).
- [x] Keep `record-prepare.yaml` as a utility, not a test (`flows/utility/`).
- [x] Run flows 1–3 individually with `--udid`; all pass.
- [x] Run the complete suite from `maestro/` once flow 4 exists.

---

## 10. Acceptance criteria

- [x] All four flows start at Home.
- [x] At least one flow encounters a track/music item.
- [x] At least one flow opens an Artist page.
- [x] At least one flow opens an Album and plays a track from it.
- [x] At least one flow opens a Show and plays or queues an episode.
- [x] Queue flow covers add, remove, Undo, Clear/Undo, and Now Playing transport.
- [x] History flow opens the History screen, removes one entry, and cancels both destructive
      Settings confirmations.
- [x] Search flow covers keypad input, `Search in` scopes, a result page, and a save/follow write.
- [x] Flows 1 and 2 depend only on the documented fixture, not on each other.
- [x] No new hardcoded media titles except the pinned Search query documented in `README.md`.
- [x] `maestro test .` passes on the target device.

---

## 11. Known risks

- **Distinct history entries:** history deduplicates by item id. To reach `See more history`, the
  fixture needs at least 6 distinct items.
- **History removal is destructive:** Flow 4 removes one entry per run. Flows 1–3 normally add
  entries back, but if they only replay existing items, reseed the fixture before rerunning.
- **Transient actions:** now-playing notice is 4 s; queue Undo is 5 s. Maestro command overhead can
  exceed these if an extra wait is inserted.
- **Search content drift:** Spotify search results change. The suite pins **Die drei** and only
  relies on the first result below each section heading.
- **Keypad mode:** T9 mode is process-wide. Search input must switch out of `123` mode after the
  keypad action bar appears, or `inputText` writes literal digits.
- **Action-bar overlap:** the last `Search in` row can sit behind the bottom bar. Toggle a category
  higher in the list or scroll with centering.
- **Show availability:** the fixture account must have at least one searchable show with episodes.
- **Save/follow initial state:** may already be saved/followed. The Search flow toggles each state
  both ways and restores the original state.
- **Localized labels:** current flows assume English UI copy. If localization is in scope, move to
  test tags or accessibility ids.
