# Options menu audit

Sidetrack has one menu primitive: the **Options bar**. A screen publishes a `List<SideAction>`
through `SidekitScaffold(options = …)`; Sidekit's `ChromeState` swaps the action bar to that list
when the user presses the keypad's Options key (or holds it to focus the bar). There are no
Material menus, dropdowns or sheets in the app.

This document inventories every Options menu as it exists today — the buttons, their shape and
their colour — and lists the inconsistencies that keep the same menu from feeling like one
control. It is a snapshot, not a spec: it should be rewritten (or deleted) once the unification in
the last section is done.

Sidekit version audited: **1.9.1** (`gradle/libs.versions.toml`), rendering in
`SideAction.kt:409-433` of `sidekit-design`.

## How a menu is rendered

Every published option is re-projected into a bar button by `ChromePublisher.optionsBar()`:

| Property | Effect on the button |
| --- | --- |
| `label` + `icon` | wide button, icon at the start, text label |
| `square = true` | fixed width equal to the bar height, **icon-only** |
| `dense` | smaller type; **forced on automatically when the menu has more than 2 items** (`o.dense \|\| opts.size > 2`) |
| `tone` | `Primary` = theme accent (`primaryA1`), `Secondary` = green (`successA1`), `Destructive` = red (`dangerA1`), `Muted` = grey (`surfaceA3`) |
| implicit | a trailing **icon-only grey Close square** is always appended |
| implicit | an empty list renders a single grey wide button, "No options here", which closes on tap and has no Close square |
| implicit | every option closes the bar after it runs unless it sets `keepOptionsOpen` |

Because the bar morphs in place, a one-button menu is a full-width button that replaces the
screen's normal actions, and a three-button menu is three narrow squares.

## Every Options menu

| # | Screen (file:line) | Buttons, in order | Shape | Colour / tone |
| --- | --- | --- | --- | --- |
| 1 | Home "Sidetrack" (`HomeScreen.kt:61`) | Settings (⚙ Settings) | wide, text + icon | Primary (accent) |
| 2 | History (`HistoryScreen.kt:42`) | Now playing (♪) | wide, text + icon | Primary |
| 3 | Search (`SearchScreen.kt:56`) | Now playing (♪) | wide, text + icon | Primary |
| 4 | Search results (`SearchScreen.kt:217`) | Search in → Now playing (♪) | wide ×1–2, text + icon | Primary, Primary |
| 5 | Search in — scope list (`SearchScreen.kt:516`) | Now playing (♪) | wide, text + icon | Primary |
| 6 | Library (`LibraryScreen.kt:92`) | New playlist (only on the Playlists category, and only where the service can create playlists) → Now playing | wide ×1–2, text + icon | Primary, Primary |
| 7 | Album / Playlist (`CollectionScreen.kt:88`) | Add to queue (when the collection has items) → Now playing | wide ×1–2, text + icon | Primary, Primary |
| 8 | Artist (`ArtistScreen.kt:74`) | Artist radio (when the service supports radio) → Now playing | wide ×1–2, text + icon | Primary, Primary |
| 9 | Podcast show (`PodcastScreens.kt:91`) | Add latest to queue (when the show has episodes) → Now playing | wide ×1–2, text + icon | Primary, Primary |
| 10 | **Now Playing** (`NowPlayingScreen.kt:114`) | Open queue → Song radio **or** Episode notes → Sleep timer / "Sleep timer on" | **icon-only squares ×3** | **all Primary (accent), Secondary while the timer is armed** |
| 11 | **Queue** (`QueueScreen.kt:323`) | Sleep timer → Clear upcoming (when there is an upcoming track) | wide, text + icon ×1–2 | Primary; **Destructive** |
| 12 | Welcome — keyboard onboarding (`SidetrackOnboardingScreen.kt:116`) | About Sidetrack (ⓘ) | wide, text + icon | Primary |

Twelve menus, twenty-odd buttons, and only #10 and #11 ever use an icon-only button.

## Screens where the Options key does nothing

Pressing Options on these screens shows the grey "No options here" button for 1.2 s and then
restores the normal bar.

| Screen | Note |
| --- | --- |
| Settings (`SettingsScreen.kt:82`) | the densest destination list in the app, yet no options |
| Audio quality (`AudioQualityScreen.kt:31`) | — |
| Appearance (`SidetrackAppearanceScreen.kt:29`) | — |
| Sleep timer (`SleepTimerScreen.kt:37`) | reachable *as an option* from other screens, has none itself |
| About (`SidetrackAboutScreen.kt:92`) | puts "Source code" in the normal bar as a grey wide button instead of in Options |
| Keyboard (`SidetrackKeyboardScreen.kt:117`) | — |
| Sign in (`SignInScreen.kt:106`) | — |
| Service picker / Welcome (`ServicePickerScreen.kt:58`) | same title, "Welcome", as #11 in the table above, but opposite Options behaviour |
| New playlist (`CreatePlaylistScreen.kt:51`) | — |
| Add to playlist (`AddToPlaylistScreen.kt:56`) | — |
| Notes (`PodcastScreens.kt:439`) | — |
| Artwork (`SidetrackArtworkScreen.kt:76`) | — |
| Status / loading wrapper (`SidetrackGraph.kt:57`) | — |
| Queue, while reordering (`QueueScreen.kt:329`) | options deliberately emptied |
| Now Playing, with nothing playing (`NowPlayingScreen.kt:115`) | options deliberately emptied |

The two deliberate empty cases are defensible; the other thirteen are not.

## The other menu that owns the same bar

Row gutters publish `RowAction`s into the same action bar (`RowActionBar.kt`). They are built by
`rowActions(...)` from one factory per entry, exactly like the Options menu, and share its colour
rule — accent for every action, `Destructive` for a removal, grey only for Cancel — plus two rules
of their own: **destructive entries sort last**, and **shape follows the count** (three or more
actions are icon-only squares, one or two keep their label), because a contextual bar earns its
space from the gesture that opened it.

| Menu | Buttons | Shape | Colour / tone |
| --- | --- | --- | --- |
| Track row (`TrackRow.kt:137-165`) | Add to queue, Add to playlist, Remove from playlist → confirmation (Cancel, Remove) | ≥3 ⇒ icon-only squares; ≤2 ⇒ wide text | Primary; Primary; Destructive; Muted (Cancel) |
| Artist row (`GutterRows.kt:57`) | Artist radio | 1 ⇒ wide text | Primary |
| Album row (`GutterRows.kt:129`) | Add to queue | 1 ⇒ wide text | Primary |
| Playlist row (`GutterRows.kt:161`) | Add to queue | 1 ⇒ wide text | Primary |
| Show row (`GutterRows.kt:193`) | Add latest to queue | 1 ⇒ wide text | Primary |
| History row (`GutterRows.kt:327`) | Add to queue, Remove | 2 ⇒ wide text | Primary, Destructive |
| Queue row (`QueueScreen.kt:246`) | Move (when the queue can reorder), Remove | 2 ⇒ wide text | Primary, Destructive |
| Episode row (`PodcastScreens.kt:373`) | Add to queue, Notes | 2 ⇒ wide text | Primary, Primary |
| always | Close | icon-only square | Muted |

## Anti-patterns

1. **Partly fixed — two shape rules remain.** Wording, glyph, colour and order are now shared by
   both menus (`sidetrackOptions` / `rowActions`), but shape still differs: Options choose it per
   entry (`square`), row actions per menu (count ≥ 3). The same conceptual action can therefore
   still change shape depending on where it is offered. Deliberate for now, documented in
   `RowActionBar.kt`.
2. **Partly fixed — "Sleep timer" has two shapes, on purpose.** It is a wide accent text button on
   Queue (`QueueScreen.kt:345`) and an icon-only square on Now Playing
   (`NowPlayingScreen.kt:130-136`), because Now Playing's bar is three playback squares beside
   Sidekit's close square: on 460 dp that leaves nothing for a label, and the label truncated to
   "Sle…". The words are not lost — the body's countdown row says "Sleep timer · 23:00", and the
   glyph turns green while armed. The state is no longer a colour-only signal, which is what this
   anti-pattern was really about.
3. **Fixed — options mixed three tones for one job.** Now Playing painted every entry `Primary`,
   every other screen painted navigation `Primary` and toggles `Muted`/`Secondary`, so the same
   entry changed colour between screens. The bar now uses one scheme everywhere: accent for every
   live option (what Sidekit's own apps do — Calculator, Calendar, Banking, Files), green for a
   toggle that is on, red for destroy, and grey only for the close square, notices and undo.
4. **Fixed — toggles reported their state inconsistently.** Queue's Shuffle changes tone
   (`Secondary` on / `Muted` off) *and* stays open. Now Playing's "Sleep timer on" keeps
   `Primary` and only changes its content description, which is invisible in a square button: an
   armed timer looks identical to an idle one.
5. **One-button menus cost a whole bar swap.** Menus 1–4 and 11 exist to hold a single wide
   button. Menus 3 and 4 publish the *identical* Now playing shortcut on two pages of one flow.
6. **"Now playing" is undeclared navigation.** It appears as an option on six screens, is absent
   from Home (which offers Settings instead), and is absent from every Settings-like screen. There
   is no written rule for what belongs in Options.
7. **A dead Options key on thirteen screens.** Users learn the key is unreliable, and the
   onboarding card explicitly teaches "press Options — About Sidetrack is there"
   (`SidetrackOnboardingScreen.kt:161`), which then fails on the Service picker that shares the
   same "Welcome" title.
8. **Fixed — same idea, different label.** "Add to queue" (Album/Playlist option) vs "Add latest to
   queue" (Show option) vs "Add latest to queue" (Show row action); "Song radio" (square, Now
   Playing) vs "Artist radio" (wide, Artist page and Artist row).
9. **Fixed — an Options menu no longer holds a toggle.** The only stateful entry (Queue Shuffle)
   moved into the screen's own bar beside Back, where Now Playing's Save already lived, so both are
   now one `toggleAction` and `keepOptionsOpen` has no user left in the app.
10. **Playlist vocabulary drifts**: "New playlist" (option), "Add to playlist" (row action,
    Muted), "Add to playlist" (screen title), "Add latest to queue".

## Unification target

| Rule | Target |
| --- | --- |
| Shape | A destination is a **wide button with its label and glyph**. An icon-only square is either a list-wide toggle (`shuffleOption`) or one of Now Playing's playback shortcuts, where the bar is already read as transport beside the five tiles. Sleep timer is a shortcut there and a labelled destination on Queue, where the bar has room for the words. |
| Coverage | Every screen with a destination list or a list-wide control publishes options. The "No options here" path only remains reachable for transient states (nothing playing, reordering queue). |
| Tone | `Primary` for **every** option, the way Sidekit's own apps paint their Options bars: an option is live wherever it leads, so tone says what it does, never how far it travels. `Secondary` for a toggle that is on, `Destructive` for destroy. Grey is left to the close square, notices and undo, which are not options. |
| Toggle state | Shuffle and Sleep timer express state the same way: tone change **and** label/content description. Shuffle keeps the menu open; the sleep timer is a destination, so it closes. |
| Shared entries | One `sidetrackOptions(...)` builder beside `sidetrackBar()` in `ActionBars.kt`, with one entry factory per shared option, so label, icon, shape and tone cannot drift per screen. |
| Order | Destinations first, the list-wide control last, Now playing always at the end. |

## Status

**Landed.** `ActionBars.kt` now owns `sidetrackOptions(...)` plus one factory per entry
(`nowPlayingOption`, `settingsOption`, `aboutOption`, `newPlaylistOption`, `addToQueueOption`,
`artistRadioOption`, `openQueueOption`, `songRadioOption`, `episodeNotesOption`,
`sleepTimerOption`, `shuffleOption`), and all eleven menus above are built from it.
`OptionsBuilderTest` pins the two rules.

What changed in behaviour, and nothing else:

- **Sleep timer says its state in words, and its shape follows the room it has.** On Queue it is
  the wide, labelled button that reads "Sleep timer on" while armed. On Now Playing it is a square
  like the shortcuts beside it (`sleepTimerShortcutOption`), because three squares and the close
  square leave no width for a label; the armed state is the green glyph plus the body's own
  "Sleep timer · 23:00" row, so it is never colour-only (anti-patterns 2 and 4).
- **Tones follow the role, and there is only one role for a live option.** Every entry is
  `Primary` — navigation and actions alike — matching Sidekit's own apps
  (`calculator/CalculatorScreen.kt:116`, `calendar/CalendarScreen.kt:50`,
  `banking/BankingGraph.kt:54`, and `files/FilesScreen.kt:203`, where only a *selection* splits
  accent from grey). `Secondary` marks the on-state of Sleep timer and Shuffle; grey is left to the
  Close square, notices and undo (anti-patterns 3 and 4).
- **Queue reads the timer state.** `QueueScreen` takes `sleepTimerActive`, collected in the
  playback graph, so its entry can say the timer is armed.
- **Labels, icons and order are now identical for a given entry** on every screen (anti-pattern 8).
- **The row action menus are built the same way.** `RowActionBar.kt` owns `rowActions(...)` plus one
  factory per entry (`addToQueueRowAction`, `addToPlaylistRowAction`, `removeFromPlaylistRowAction`,
  `removeFromQueueRowAction`, `removeFromHistoryRowAction`, `moveRowAction`,
  `artistRadioRowAction`, `episodeNotesRowAction`, `cancelRowAction`,
  `confirmRemoveFromPlaylistRowAction`). `Add to playlist` is no longer Muted, the history row's
  add-to-queue uses the same glyph as everywhere else, destructive entries sort last, and the
  show's row action shares the `addToQueue` key with the option that does the same thing.
- **The queue's bar and menu swapped their one shared control.** Shuffle is a toggle, so it sits in
  the screen's own bar beside Back — the same place Now Playing's Save lives, now both from one
  `toggleAction`. Clear upcoming is the only list-wide destructive action and therefore the first
  Destructive entry in an Options menu, where Sidekit's own apps also keep them (anti-pattern 9).

Still open: anti-patterns 1 (row actions keep their count-based shape rule, by design), 5, 6, 7
(thirteen screens still publish no options) and 10.

