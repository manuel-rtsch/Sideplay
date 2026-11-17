# Maestro

Reproducible UI flows for Sidetrack, run against the connected Android device/emulator.

## Setup

Maestro is installed at `~/.maestro/bin/maestro`. Add it to the current shell:

```sh
export PATH="$PATH:$HOME/.maestro/bin"
```

## Run

```sh
cd maestro
maestro test .
```

Run a single flow:

```sh
maestro test flows/suite/01-browse-play.yaml
```

Run the whole suite with device animations off, a different accent colour before each flow, and a
per-flow timing report:

```sh
./run-suite.sh
FLOW_MAX_SECONDS=45 ./run-suite.sh   # fail when a flow exceeds the limit
SIDETRACK_ACCENTS="Lime Orange Blue Pink" ./run-suite.sh   # override the rotation
```

The flows run in `config.yaml` order. Suite artifacts (`commands.json`, logs, screenshots) are
kept in `build/maestro-suite/`; the accent steps and their logs are kept separately in
`build/maestro-accent/`, so they do not appear in the timing report. Re-run the report alone with
`./scripts/flow-times.py build/maestro-suite --verbose`.

## Record a video

The checked-in recordings come from the recording-first variants in `flows/promo/`:

| Recording | Flow | Accent | Length |
| --- | --- | --- | --- |
| `recordings/01-browse-play.mp4` | `flows/promo/01-browse-play.yaml` | Lime | 35.4 s |
| `recordings/02-search-library-writes.mp4` | `flows/promo/02-search-library-writes.yaml` | Orange | 38.4 s |
| `recordings/03-queue-transport.mp4` | `flows/promo/03-queue-transport.yaml` | Blue | 39.3 s |
| `recordings/04-history-settings.mp4` | `flows/promo/04-history-settings.yaml` | Pink | 38.8 s |

The promo variants use a warm launch and omit guards and content that can never match or do not fit
the recording budget. State-dependent guards stay: a track tap that is already current opens Now
Playing, so the following Options → Now playing step needs the branch.

The four takes are meant to tell four different stories, not to cover one checklist:

| Take | Story | Owns |
| --- | --- | --- |
| 01 Browse & Play | Home hero, a recent row, then the Library tour: liked songs, the full liked list, first playlist, first album, first show, back Home, hero again | Home, the Library categories, album pages, playback started from three places |
| 02 Search & Library Writes | One query, then the writer's tour: follow the first artist, open the first show, play its first episode | Search, Follow, artist pages, shows, episodes |
| 03 Queue & Transport | Queue editing plus transport: add, remove and undo, clear and undo, shuffle, repeat, save, sleep timer | Queue, swipe actions, transport, sleep timer |
| 04 History & Settings | History, playback settings, audio quality, Appearance, Keyboard, Clear cache/history | History, settings, device screens |

Flow 01 no longer opens an artist page or writes anything, so it cannot drift back into being a
copy of 02. Keep it that way when editing: the artist page, Follow and episodes belong to 02. The
album page belongs to 01 — 02 used to open one too, which duplicated 01 and pushed the take 3.5 s
past the clip.

### Working on the emulator

`SIDETRACK_DEVICE=emulator-5554 ./record.sh …` records against an emulator, which is enough to
iterate on flows that do not type: the device is 480×640 like the Sidephone, so the layouts match.
Two caveats:

- **Timing is ~1.3× faster than the phone.** Measured on flows 03 and 04 (suite): 38.0 s and 33.3 s
  here against 50.2 s and 45.2 s on the device. Budget a phone take at `emulator × 1.3` against the
  40 s clip — the promo flows are now paced for the emulator, so a phone take of 02 or 03 would
  overrun it.
- **Hide the soft keyboard.** `record.sh` sets `show_ime_with_hard_keyboard=0` for the take and
  restores it afterwards (`SIDETRACK_KEEP_IME=1` opts out). With the IME hidden the app falls back to
  its own Abc/Done bar and the T9 digits work exactly as on the sidephone; with it visible the
  keyboard is drawn in the video and the query is typed as text instead.
- **The accent comes from Sidetrack itself.** The emulator has only the Sidekit SDK sample, not the
  host, so `record.sh` picks `flows/utility/set-accent-standalone.yaml` (Settings → Appearance)
  instead of the host flow. It detects this by looking for the host package; `SIDETRACK_ACCENT_FLOW`
  overrides the choice.
- **Takes are shorter than on the phone** (see the ratio above), so a phone re-record would overrun
  the clip instead of underrunning it. The emulator takes now fill the 40 s panel to within 1–5 s.

### Demo build

The recordings are made against a `demo` build that serves a fixed catalog instead of the
signed-in account's library, so the videos never show personal data and search results never
change with Spotify's ranking. The default pack shows real, widely known songs (Bowie, Nina
Simone, Massive Attack, Fela Kuti, Buena Vista Social Club) with their official covers bundled in
`app/src/demo/res`; every item plays one stock track, because the videos are muted. Only the catalog is
fake: playback still uses the signed-in account. `-PdemoPack=fictional` builds the original fully
invented pack instead. See `CONTENT-FIXTURE.md` for what the pack contains and why.

```sh
./gradlew :app:assembleDemo
adb install -r app/build/outputs/apk/demo/app-demo.apk   # replaces the release app
# Sign in once if the stored credentials are gone; then, per take:
SIDETRACK_ACCENT=Lime ./record.sh flows/promo/01-browse-play.yaml "recordings/01-browse-play.mp4"
SIDETRACK_ACCENT=Orange ./record.sh flows/promo/02-search-library-writes.yaml "recordings/02-search-library-writes.mp4"
SIDETRACK_ACCENT=Blue ./record.sh flows/promo/03-queue-transport.yaml "recordings/03-queue-transport.mp4"
SIDETRACK_ACCENT=Pink ./record.sh flows/promo/04-history-settings.yaml "recordings/04-history-settings.mp4"
```

Every take needs its own cold launch: the demo build rewrites history and the queue at process
start, which is what makes each video start from the same rows. `record.sh` detects the `-demo`
version name and runs `flows/utility/record-prepare-demo.yaml` instead of the account prep, so
never set `SIDETRACK_SKIP_PREPARE=1` here. That also means flow `02`'s follow/save writes never
reach the account.

To go back to the real account, reinstall the release APK.

### Account mode

The flows still work against a real account, which is useful when the demo build is not
installed. Seed the profile first (see `CONTENT-FIXTURE.md` §6), then:

```sh
SIDETRACK_ACCENT=Lime ./record.sh flows/promo/01-browse-play.yaml "recordings/01-browse-play.mp4"
# Keep the history from flow 01 for the later history recording.
SIDETRACK_SKIP_PREPARE=1 SIDETRACK_ACCENT=Orange ./record.sh flows/promo/02-search-library-writes.yaml "recordings/02-search-library-writes.mp4"
# Seed first: play several distinct tracks so history reaches six entries and a
# track (not the episode from 02) is current. See CONTENT-FIXTURE.md §6.
SIDETRACK_SKIP_PREPARE=1 SIDETRACK_ACCENT=Blue ./record.sh flows/promo/03-queue-transport.yaml "recordings/03-queue-transport.mp4"
SIDETRACK_SKIP_PREPARE=1 SIDETRACK_ACCENT=Pink ./record.sh flows/promo/04-history-settings.yaml "recordings/04-history-settings.mp4"
```

`SIDETRACK_ACCENT` runs `flows/utility/set-accent.yaml` before the prep and the recording, so the
video picks up Sidekit's host-backed case colour. The checked-in set uses Lime, Orange, Blue, and
Pink in flow order. Assemble the four panels and the combined 16:9 grid with
`design/promo/build-videos.sh`.

In account mode the profile must already satisfy the suite assumptions. Flow 4 removes a history
entry, so reseed before recording the suite again or before the next full suite run. Neither
applies to the demo build, which reseeds itself.

`record.sh` turns off the device's window, transition, and animator scales while it records
(restoring them afterwards). This removes most of Maestro's per-tap settle wait. The recording
starts when the flow's `launchApp` command completes (`Launch app ... COMPLETED` in the Maestro
log), so Maestro's JVM startup is not in the video and the marker does not depend on the flow's
first action or first assertion.

To keep the device's animations on, set `SIDETRACK_KEEP_ANIMATIONS=1`:

```sh
SIDETRACK_KEEP_ANIMATIONS=1 ./record.sh flows/promo/01-browse-play.yaml
```

For demo and promo flows that should start from a clean history in account mode, omit
`SIDETRACK_SKIP_PREPARE`. Then `record.sh` runs `flows/utility/record-prepare.yaml`, which launches
Sidetrack, clears **Recently played**, starts **Africa** by TOTO, leaves the keypad in Abc mode, and
returns Home with the song playing:

```sh
./record.sh flows/demo/hero-play-search-queue.yaml
```

Do not use that prep for suite recordings: flow 4 needs enough existing history to show
**See more history**.

`record.sh` picks the first online device; set `SIDETRACK_DEVICE=<serial>` to choose one
explicitly. The recording begins when the flow reaches `Launch app ... COMPLETED`. Override that
marker with `SIDETRACK_RECORD_START_MARKER` (matched as an extended regex).

`maestro record --local` also works, but is currently very slow (several minutes for a
~1 minute flow) and leaves an unplayable file if interrupted. Prefer `record.sh`.

## Accent colour between runs

Sidetrack is host-backed when Sidekit is installed: it reads the case colour from Sidekit's
configuration and ignores writes to Sidetrack's own local Appearance screen. Change the host
setting, then restart Sidetrack:

```sh
maestro test flows/utility/set-accent.yaml -e ACCENT=Lime
maestro test flows/utility/set-accent.yaml -e ACCENT=Orange
maestro test flows/utility/set-accent.yaml -e ACCENT=Blue
maestro test flows/utility/set-accent.yaml -e ACCENT=Pink
```

The utility launches Sidekit, opens **Settings → Case colour** from the launcher's Options menu,
selects the named swatch, asserts the new Active colour, and relaunches Sidetrack. It force-stops
both apps, so run it between flows rather than inside one.

## Device fixture

The suite assumes a seeded device. The fixture is not created by the test flows, so seed it before
running the suite and restore it after any flow that removes history or clears the queue.

The full inventory of what the flows consume — counts, ordering, duration and T9 constraints, the
content that is still hardcoded, the options for a neutral (non-personal) content base and the
recommended one — is in [`CONTENT-FIXTURE.md`](CONTENT-FIXTURE.md).

Required state, left on **Home**:

- signed in;
- one current track;
- at least **6 distinct** recently played entries, so Home shows **See more history**;
- at least **2 upcoming** queue items.

To seed manually: play six different tracks once each (history deduplicates by item id), add two
recent tracks to the queue, and return Home. Do not use **Clear history** afterwards.

A dedicated `flows/utility/seed-fixture.yaml` remains a TODO. It is not listed in `config.yaml`
until it is repeatable on-device; the flows must not depend on one another beyond this documented
fixture.

Known content-specific choices: the Browse flow selects artists, albums and tracks by position,
subtitle, and duration rather than by title. The Search flow pins the query **bowie** because a
show result is required; see **Search input** below. Both demo packs satisfy it.

## Search input

On the Sidephone keypad, Maestro `inputText` arrives as individual key events, not as a text
assignment. The T9 engine only multi-taps digits while it is in **Abc** mode, so the Search flow:

1. taps the field and presses a harmless key so the keypad action bar appears;
2. switches out of **123** mode if that is the current mode;
3. erases the probe character (`eraseText: 2`) and enters the query as T9 digit groups.

The pinned Search query is **bowie**, entered as `22666944433` (`b 22, o 666, w 9, i 444,
e 33`). The flow taps the first result below the **SHOWS** heading and does not assert its title.

## Flows

Only `flows/suite/` is listed in `config.yaml`; `flows/demo/` and `flows/utility/` are kept out
of the automated suite.

- `flows/suite/01-browse-play.yaml` — Home → Library → second followed artist → third top track → Now Playing → first album → first album track → Now Playing → another top track → Now Playing → Home. Covers track, artist, and album media, and adds a history entry before flow 4 removes one.
- `flows/suite/02-search-library-writes.yaml` — Home → Search → `Search in` scopes → artist follow write → album save write → show episode playback → Now Playing. Covers the show media kind.
- `flows/suite/03-queue-transport.yaml` — Home → play a recent track (so an episode left current by an earlier flow cannot remove `Save`) → queue two recent tracks → remove/Undo → Clear/Undo → optional Shuffle → Now Playing transport (play/pause, Next, repeat, Save, seek). Covers queue and transport.
- `flows/suite/04-history-settings.yaml` — Home → See more history → focus-revealed Remove one entry → Home → Settings (Autoplay, Normalize volume, Gapless, Audio quality) → cancel Clear cache and Clear history → Home. Covers History removal and Settings confirmations.
- `flows/promo/01-browse-play.yaml` — recording-first Browse & Play: same path as the suite flow without guards, warm launch, and the settle/scroll overrides. Used for the promo video.
- `flows/demo/home-library-home.yaml` — launches the app, opens the Library, and returns to Home.
- `flows/demo/home-library-artist-nowplaying-home.yaml` — Library → second followed artist → third top track → Now Playing → Home, without using Search.
- `flows/demo/queue-history-management.yaml` — queues two recent tracks, removes and undoes a queue item, toggles Autoplay, and cancels Clear history.
- `flows/demo/hero-play-search-queue.yaml` — Now Playing, Queue, keypad search, and playback.
- `flows/utility/record-prepare.yaml` — recording setup only: clear history, then start a song.
- `flows/utility/set-accent.yaml` — sets Sidekit's host-backed case colour through **Settings → Case colour**, then restarts Sidetrack so the theme takes effect. Use `-e ACCENT=<colour>` with `Lime`, `Orange`, `Blue`, `Pink`, `Sage`, or `Lavender`; it starts and ends on Sidetrack Home.

## Promo videos

The recordings are also composited into the Sidephone promo videos in `design/promo/` (4:9 panels
and a 4-up 16:9 grid). Decoding, geometry, crop values and the ffmpeg commands are documented in
[`PROMO-VIDEO.md`](PROMO-VIDEO.md); rebuild with `design/promo/build-videos.sh`.

Flow length and per-command timing are analysed in [`PIPELINE.md`](PIPELINE.md): where the ~2 s per
tap and ~7 s per unmet `runFlow when` guard come from, and the changes that bring recordings under
45 s. The technical changes are implemented for flow 01; see §6 of that document for measured
results and the remaining work on flows 02–04.
