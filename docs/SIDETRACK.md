# Sidetrack

**Updated:** 2026-11-15
**Repository:** `manuel-rtsch/Sidetrack` (`main`)
**Phase:** standalone APK; manual device checks are tracked in `docs/DEVICE-CHECKLIST.md`

Sidetrack is a standalone Spotify app for Sidekit-native phones: one account's library, search,
playback, and queue, drawn with Sidekit's chrome and operated keypad-first on a 480 × 640 phone.
It is not a Spotify client reproduction and not a web wrapper.

The implementation has moved past the porting/mockup stage. This document is the single source
of truth for what exists and what must not regress. Older clean-room specs and status logs were
folded into it; the full pre-consolidation text remains in Git history.

---

## 1. Guardrails

These are constraints, not preferences. A change that violates them is wrong even if it matches
Spotify.

- **Chrome belongs to the shell.** The SDK's `SidekitActivity` and `SidekitScaffold` draw title,
  body, and action bar. No app bottom bar, FAB, tabs, mini-player, sheets, dialogs, toasts, or
  snackbars.
- **Action bar grammar.** Left = continuation (primary action, else Back), right = next/undo.
  Secondary actions live in keypad Options. Destructive actions swap the bar for confirmation.
- **The body is the control surface.** Play, save, and transport are tiles/rows; the bar navigates.
- **One column, explicit pagination.** No carousels, infinite scroll, or algorithmic shelves.
  Long lists end with a "Load more" row.
- **Keypad first.** Every interactive row is focusable, has a visible focus ring, and a 48 dp
  minimum target. Do not fade the focused element.
- **Content never wears the theme colour.** Play, selection, progress, save/unsave, and
  follow/unfollow use the Library success fill; the case colour belongs to action-bar actions and
  the vinyl status record only. Save controls are always bookmark tiles: hollow (`BookmarkBorder`)
  when unsaved, filled (`Bookmark`) when saved.
- **No new dependencies** for images, DI, persistence, or media. Reuse the SDK's
  `RemoteArtwork`, `EncryptedFileStore`, `UndoWindow`, and `NativeLibraries`; keep app-local
  media coordination, plain repositories, and manual wiring.
- **No new icons** unless design decides; v1 needed none.
- **Explicit non-goal:** Spotify feature parity. Sidekit parity is the bar.

Rejected on purpose: algorithmic home feed, horizontal shelves, persistent mini-player, bottom
navigation, full-screen drag-dismiss player, queue bottom sheet, Spotify Connect device switching,
lyrics/Canvas/video, social features, account management, and crossfade/EQ.

---

## 2. Shipped

**Architecture**

- Playback: `SpotifySession` → `NativeSpotifyGateway` → `native/sidetrack-spotify` (librespot 0.8.0)
  → `SpotifyAudioSink` (streaming `AudioTrack`).
- Catalog: `NativeSpotifyCatalog` reads through the authenticated librespot session
  (SpClient/Pathfinder). `SpotifyWebCatalog` remains a fallback when native is unavailable.
- UI: `SpotifyViewModel` maps catalog responses, queue/history repositories, and native player
  events into one immutable `SpotifyUiState`.
- Screens: Welcome, Home, History, Library overview, Library category, Collection
  (album/playlist/show), Artist, Search, Search scopes, Now Playing, Queue, Sleep timer, Settings,
  Appearance (only without a Sidekit host), Sign in. Mock data is confined to the
  screenshot-test source set.
- Shell: `SidetrackActivity` reads Sidekit's theme and keypad configuration when a compatible host
  is installed; otherwise it uses local stores and shows Appearance settings in-app. Host support
  is best-effort: an absent, older, or unreadable Sidekit falls back to standalone mode instead of
  blocking startup.
- Packages: application `io.github.manuelrtsch.sidetrack`, native bridge
  `bridge/NativeBridge.kt`, player `playback/SpotifyPlayback.kt`.

**Behaviour**

- Welcome is the one-time onboarding after sign-in. It names the attached keypad, cycles the
  module on a standalone install, explains that Options reveals each screen's extra actions, and
  shows the keypad guide. Finishing it writes `onboarding_seen` to the local settings file; an
  existing signed-in install without that key sees it once after updating. A Sidekit host keeps
  ownership of the keyboard choice, so Welcome shows the module without offering a switch.
- Library, collection, artist, and grouped search reads use first-party endpoints, avoiding the
  public Web API quota and the old HTTP 429 failures.
- Native events (`loading`, `playing`, `paused`, `position`, `ended`, `unavailable`) drive Now
  Playing and advance the local queue exactly once. The one-second simulation runs only when
  native is absent.
- Library write-back is optimistic with rollback: like/unlike, save/remove album and show,
  follow/unfollow artist, add/remove playlist. Saves and follows offer Undo for five visible
  seconds; queue actions are silent. The open Library section is patched in place, so a save or
  follow appears immediately and rolls back if the write fails. Save/bookmark and follow states
  keep the same success fill in both directions, matching the Home Library tile.
- Saved songs, albums, artists and shows are ordered by Spotify's `added_at`, newest first. The
  collection page is read once, filtered by URI prefix, and cached for two minutes; writes
  invalidate that cache.
- Home previews five recently played items and ends the section with a compact **See more
  history** row when more exist. A **History** option in Home's Options menu opens the same full
  list directly. History is the full local list: tracks and show episodes are both recorded,
  including when playback advances from the notification. A focused row opens a gutter with Add to
  queue and Remove squares; left swipe still offers Add to queue.
- The Library overview shows five rows per category with a compact **See more** row. Opening a
  category gives the full paged list. Playlists use the rootlist order and show their folder
  path above the owner. Startup pre-caches only six items per category (five shown plus one to
  detect **See more**), and opening the Library refreshes that overview so a load that started
  offline recovers without restarting the app.
- Every list row gets Sidekit's `listFade` at its actual LazyColumn index, so labels, See more
  rows, results, and History all recede correctly toward the action bar without fading the
  focused row.
- Playback is process-wide. Queue and local history live on `SpotifySession`; a recreated
  ViewModel adopts the live item, position, and playing state. Starting a new item releases the
  previous `AudioTrack`.
- The sleep timer is owned by `SpotifyPlayback`, not the ViewModel, so it survives leaving
  Sidetrack and activity recreation. Now Playing's Options offer a Bedtime square that opens a
  one-shot picker (Off, 5/15/30/45/60/90 minutes, End of track); selecting a row arms the timer and
  returns. A slim, non-interactive status line appears between the artist and seek row, and the
  Home tile gains a Bedtime icon with the remaining minutes. Expiry pauses playback and clears
  the timer without touching system volume. `End of track` stops before repeat/advance and
  retargets naturally when the user skips. The timer is not persisted and is cleared on stop,
  disconnect, and sign-out.
- Song radio is seeded from the current track. Now Playing's Options bar carries three square,
  primary-coloured icon buttons — Queue, Song radio and Sleep timer — with no text labels. The
  station is read through librespot's first-party `get_radio_for_track` endpoint (the same
  authenticated session the catalog uses), resolved to normal track rows, and shown with the
  collection screen's play/artwork header and track list. Opening the station queues it behind
  the still-playing seed, so playback continues without a restart; the Queue screen carries the
  same queue, subject to the current shuffle and repeat modes. Episodes have no song radio, so
  the button is omitted for them. The Web API fallback uses `/recommendations` when the native
  bridge is absent.
- Starting a new item shows a transient now-playing confirmation in the action bar: artwork, an
  animated playing indicator, the title, and a right arrow to Now Playing. It replaces the normal
  actions for four seconds, can be dismissed immediately with its X, and never auto-navigates.
  Selecting the already-playing item still opens Now Playing directly. The timer lives in
  `SpotifyViewModel`, so the confirmation clears even if the user navigates to a screen that does
  not render it.
- Network loss is reported honestly. `NetworkMonitor` tracks the active network, and playback or
  catalog failures while offline map to **No internet connection** instead of an expired Spotify
  session. When Autoplay reaches the end of the queue with no connection, it stops instead of
  retrying and shows **No internet connection — autoplay stopped**. When the network returns,
  the ViewModel retries the library overview (and the open category, if one was loaded).
- Queue, queue history, shuffle order, repeat mode, and recently played persist across process
  death in atomic binary files (`sidetracks-queue.bin`, `sidetracks-history.bin`; legacy names
  kept so existing dev installs keep their data). Playback
  itself does not resume on launch.
- Foreground `mediaPlayback` service and platform `MediaSession` keep librespot alive with the
  screen off. Notification shows artwork, title, artist, and previous/play-pause/next; while a
  sleep timer is active, the artist line gains `· sleep 23m` (or `· ends with this track`) and a
  minute tick rebuilds only the notification. When the
  ViewModel is gone, next/previous fall back to advancing the process-wide queue directly.
  Tapping the notification opens Sidetrack directly at Now Playing rather than the launcher.
  A native `stopped` event clears Now Playing and the queue so notification/lock-screen stop
  cannot leave stale UI or restore the stopped track.
- Sidetrack owns its media session and audio focus (`MediaSessions`, `AudioFocus`), taking and
  abandoning `AUDIOFOCUS_GAIN`, including transient loss and headphone changes. A standalone
  install has no local Music process to coordinate with.
- OAuth tokens rotate into an existing native session. Every play and resume revalidates that
  session: librespot marks it invalid when the access-point connection or its keep-alive dies,
  so an invalid session is rebuilt from the stored token and the current item reloads at its
  position. A connection attempt is bounded (20 s native, 25 s Kotlin) so a stalled handshake
  reports an error instead of leaving the play button hanging. Tearing down the old session runs
  off the native state lock, because `Session::shutdown` can block behind a stalled network task;
  a shutdown that cannot complete leaks one thread instead of wedging playback. Sign-out stops
  the decoder synchronously, releases the media session, removes the notification, then clears
  credentials.
- Audio quality, loudness normalization and gapless playback persist encrypted across process
  death and map to librespot's `PlayerConfig` (96/160/320 kbps, `normalisation`, `gapless`).
  Changing any of them rebuilds the native player and reloads the current item at its position
  without changing the pause/play state. The next queue item is preloaded, and an automatic
  end-of-track transition keeps the `AudioTrack` open when gapless is on, so pre-buffered audio
  joins without a sink restart. Playback volume is the device output volume; there is no
  separate attenuation.
- Artwork is memory-safe on device: 300 px URL preference, `RGB_565` + `inSampleSize`, 1024 px
  cap, `OutOfMemoryError` treated as a cache miss. List thumbnails use Sidekit's 24dp artwork
  shape, which matches the item's 36:104 corner ratio at 68dp without cropping the cover into a
  circle.
- Home's action bar opens the queue; track rows across the app expose Add to queue on left swipe.
  Upcoming rows in the queue swipe left to remove; the queue shows only the current item and what
  is next, while the full listening history lives on the History screen. Library uses the standard
  single-line header; Settings has one sign-out action in the bar.
- The queue has one primary action per row. Enter or a tap on an upcoming track plays it now; the
  current row opens Now Playing. Remove is the single focused-row square and the left swipe; the
  keypad square appears after the focus settles, so holding Up/Down does not flash a button on
  every row. A touch swipe and the keypad gutter share one right edge: an open swipe suppresses
  every gutter, and a keypad move away from the swiped row closes the swipe, so two Remove buttons
  are never visible together. There is no edit pair and no Play next: reorder-to-top covers Play
  next, and the row body never turns into a menu. A non-focusable **Up next · N tracks**
  section label separates the current item from the tail and carries the shuffle state. It uses the
  same uppercase, small, muted `SectionLabel` as Home, Library, Search and album/playlist sections.
  Section labels are structural, so they do not take `listFade`: every label keeps the same full
  `surfaceA6` alpha, while the content rows below still step down toward the action bar.
- Reorder is a list mode from Options, not a per-row menu. Choosing **Reorder** lifts the focused
  upcoming track (or the first one when the current row has focus), shows the two bare arrows in a
  narrow sliver, and lets Up/Down step it, Confirm drop it, and Back cancel back to where it
  started; Left/Right are swallowed so focus cannot strand the lift. The bar becomes Cancel + Done.
  Finishing or cancelling a reorder leaves no row focused, and neither does Remove or Clear: the
  keypad user picks the next row explicitly instead of the list choosing a neighbour. A touch
  long-press still lifts and drags directly. The current item never moves. Rows are keyed by
  track identity, so a stepped track keeps its node and focus and each step scrolls it into view in
  the same measure pass rather than by Sidekit's animated focus scroll, which a held key outruns.
  The stepped track is parked one place below the top so the track it is about to pass stays
  visible. The queue reorders live as the finger crosses each row, so a long drag keeps its slot
  on screen, and auto-scroll carries it past the edges. The header's own top padding separates it
  from the now-playing row; no extra spacer is needed.
- **Clear** in the bar asks for confirmation before wiping the tail; a confirmed clear still offers
  Undo, and removing one track offers Undo at the index it left. Remove and undo work by row index,
  so duplicated tracks are distinct and one copy never takes another with it. Shuffle is a
  one-time reorder of the existing tail, shown in the header as **Shuffled**; new tracks append in
  order. A manual reorder turns shuffle off, so the mode always describes the order on screen.
- Library category and collection lists page in 50-row chunks and load the next page as their
  footer becomes visible. Search and artist surfaces start with five items per section and show
  a pressable **See more** row; the next page is fetched only when that row is pressed.
- Album and show collections use a position number in the row's left column; playlist rows use
  per-track artwork instead, since playlist tracks usually come from different albums.
- Search categories live behind the **Search in** row on the Search screen, not in Options.
  Toggling a category immediately changes the result sections and the empty-state hint. The
  selected categories sit at the right edge of that row in the high-contrast item colour.
- First loads of an artist or collection show a centred, rotating vinyl record with the loading
  label and a "Fetching from Spotify" hint instead of plain text. Library, Search, artist and
  collection failures use the same record, held still, with a short explanation and a full-width
  secondary **Try again** action, centred because Sidekit's line button left-aligns its label.
  The action bar always keeps **Back**, so a failed connection never traps the user on the
  fallback screen. Home's empty recently-played list and an empty queue use the same still record
  without an action; History has no empty state and simply shows nothing when it has no entries.
- `SidetrackActivity` re-applies the host's input mode and keypad module on every resume, so a
  T9 → mini QWERTY switch made while Sidetrack was in the background takes effect immediately.
- Settings shows the actual artwork + audio cache size. Sign-out clears artwork, audio, and
  credentials; the Settings action clears artwork and audio without touching the account.
- Focus review: collection rows and Library category rows are focusable, and the first row
  receives focus after opening a category. Long collections use the same lazy list as the rest
  of the app.
- APK-size review: the profile APK is about 12.2 MB, dominated by the two native librespot
  libraries. No action until the vendored librespot patch can be dropped.

**Verified**

- `spotlessCheck` and lint for debug, dev, profile, and release report no issues;
  `:app:testDebugUnitTest` and `:app:assembleDebug` pass.
- APK contains `arm64-v8a` and `x86_64` `libsidetrack_spotify.so`.
- On `SP01GE260600886` (480 × 640): libraries load real data, search returns Pathfinder tracks,
  playback advances/pauses correctly, show episodes format dates, and artwork no longer crashes.
- On the `Sidephone_SP-01` emulator (480 × 640, Android 12): Liked songs loads from the complete
  collection, the Library overview and compact **See more** rows render, **Search in** changes
  scopes without opening Options, Home previews five recent items with **See more history**, and
  History reveals Add to queue and Remove squares on focus while search results fade toward the
  action bar.
- On `SP01GE260600886`: instrumented tests cover the foreground-service notification and actions,
  media-session metadata/state, media-owner hand-off, and the headphone-unplug pause path.
- On `SP01GE260600886`: dropping the network long enough to close the librespot access point
  leaves libraries browsable (HTTP catalog) but makes playback fail until the session is rebuilt.
  After the fix, the next play reconnects (`Connecting to AP` / `Authenticated as …`) and loads
  the track instead of reporting every item as unavailable.
- JVM tests cover catalog page offsets, collection totals, page boundaries, catalog error
  mapping (auth, network, malformed response), and the playback event state machine, including
  `stopped`.

---

## 3. Known limitations

1. **Recently played is local-only.** There is no safe first-party history endpoint, and the
   Web API quota is not worth spending on a non-critical list. Home shows Sidetrack's local
   history; it is not cross-device.
2. **Sidetrack owns queue, shuffle, and repeat.** librespot 0.8 plays one item at a time and has
   no shuffle/repeat setters. `SpotifyQueueRepository` is authoritative; native shuffle/repeat
   events are adopted when another client changes them. Queue, queue history, and recently
   played persist; playback does not resume automatically after process death.
3. **Device builds must include the native bridge.** `libsidetrack_spotify.so` is opt-in via
   `-PsidetrackNative=true`. Without it, `SpotifySession` falls back to `SpotifyWebCatalog`, and
   Spotify now answers those requests with HTTP 429 because the OAuth token uses the desktop
   client ID. Symptom: sign-in succeeds, but the library and search are empty. The 0.2.4 release
   asset was rebuilt with the bridge after this was found on the emulator.
4. **Playlist owner names.** Your own playlists show **You**; other users' playlists show their
   Spotify username. Display-name lookup is not worth a per-owner Web API call.
5. **The Options key does not dismiss the now-playing confirmation.** Sidekit's
   `ChromeState.toggleOptions()` owns the Options key and opens the screen's options instead. A
   later improvement is to make Options dismiss an auto-dismiss action before opening options;
   that belongs in Sidekit and would benefit every app, not just Sidetrack. Until then the
   confirmation is dismissed by its X, by opening Now Playing, or by its four-second timer.

---

## 4. Native build, provenance, and licensing

**Native bridge.** `native/sidetrack-spotify` links librespot 0.8.0 and exposes the JNI surface
declared in `bridge/NativeBridge.kt`.

- Toolchain: Rust stable, `cargo-ndk` 4.1.2, NDK 27.1.12297006, targets
  `aarch64-linux-android` and `x86_64-linux-android`.
- Gradle builds it with `:app:buildSpotifyNative` when `-PsidetrackNative=true` (or
  `SIDETRACK_NATIVE=true`) is set. Native playback is deliberately opt-in so CI without Rust can
  still build. If Rust or the NDK is missing, the task is skipped, the APK has no library, and
  `NativeLibraries.load("sidetrack_spotify")` returns false. Device and release builds must opt in.
- `Cargo.lock` pins the full graph. `vergen` is held at 9.0.6; do not update it without rebuilding
  the Android targets.
- Playback temp files use Android `cacheDir` (`NativeBridge.setTmpDir`).

**librespot patch.** One minimal in-tree patch is applied via
`native/vendor/librespot-core` and `[patch.crates-io]`:

- `src/config.rs`: `OS = "linux"` instead of `std::env::consts::OS`.
- Reason: the stored OAuth credential comes from Spotify's desktop/keymaster client id. Login5
  and client-token requests bind to the platform-implied client id; on Android librespot
  otherwise reports `INVALID_CREDENTIALS`/`BAD_REQUEST`.
- Scope: one constant. No protocol, playback, audio, or auth logic changes.
- Removal: delete the patch once librespot supports overriding platform/client identity, or once
  Sidetrack performs its own Android-client OAuth.
- Verify with `diff -rq ~/.cargo/registry/src/index.crates.io-*/librespot-core-0.8.0
  native/vendor/librespot-core`; only `src/config.rs` (plus registry bookkeeping) should differ.

**Licensing.** Sidekit and librespot are MIT; librespot's notice and `Cargo.lock` are retained.
Sidespot is GPLv3 and remains reference-only at the untracked `.sidespot/` path: never committed,
linked, vendored, or used as a build dependency. No Sidespot source, identifiers, comments,
assets, or documentation enter Sidekit.

**Clean-room boundary.** The implementation was written from Sidekit's design system, public
Spotify/librespot documentation, and observable behaviour. Before merge, run the similarity
review: no `sidespot` identifiers in committed code, no copied strings/assets/symbols, and no
structure that only matches the reference implementation. Record the reviewer and date here.
