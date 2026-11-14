# Maestro

Reproducible UI flows for Sideplay, run against the connected Android device/emulator.

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

## Record a video

The checked-in recordings are one per suite flow:

| Recording | Flow |
| --- | --- |
| `recordings/01-browse-play.mp4` | `flows/suite/01-browse-play.yaml` |
| `recordings/02-search-library-writes.mp4` | `flows/suite/02-search-library-writes.yaml` |
| `recordings/03-queue-transport.mp4` | `flows/suite/03-queue-transport.yaml` |
| `recordings/04-history-settings.mp4` | `flows/suite/04-history-settings.yaml` |

Use the wrapper, which records with `adb screenrecord` and runs the flow. The suite needs the
seeded device fixture, so skip the recording prep that clears history:

```sh
cd maestro
for flow in 01-browse-play 02-search-library-writes 03-queue-transport 04-history-settings; do
  SIDEPLAY_SKIP_PREPARE=1 ./record.sh "flows/suite/$flow.yaml" "recordings/$flow.mp4"
done
```

The fixture must already satisfy the suite assumptions. Flow 4 removes a history entry, so reseed
before recording the suite again or before the next full suite run.

`record.sh` turns off the device's window, transition, and animator scales while it records
(restoring them afterwards). This removes most of Maestro's per-tap settle wait. The recording
starts only after the flow has launched the app, so Maestro's JVM startup is not in the video.

To keep the device's animations on, set `SIDEPLAY_KEEP_ANIMATIONS=1`:

```sh
SIDEPLAY_KEEP_ANIMATIONS=1 ./record.sh flows/suite/01-browse-play.yaml
```

For demo flows that should start from a clean history, omit `SIDEPLAY_SKIP_PREPARE`. Then
`record.sh` runs `flows/utility/record-prepare.yaml`, which launches Sideplay, clears
**Recently played**, starts **Africa** by TOTO, and returns Home with the song playing:

```sh
./record.sh flows/demo/hero-play-search-queue.yaml
```

Do not use that prep for suite recordings: flow 4 needs enough existing history to show
**See more history**.

`record.sh` picks the first online device; set `SIDEPLAY_DEVICE=<serial>` to choose one
explicitly. The recording begins when the flow reaches
`Assert that "Sideplay" is visible... COMPLETED`. Override that marker with
`SIDEPLAY_RECORD_START_MARKER`.

`maestro record --local` also works, but is currently very slow (several minutes for a
~1 minute flow) and leaves an unplayable file if interrupted. Prefer `record.sh`.

## Device fixture

The suite assumes a seeded device. The fixture is not created by the test flows, so seed it before
running the suite and restore it after any flow that removes history or clears the queue.

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
subtitle, and duration rather than by title. The Search flow pins the query **Die drei** because a
show result is required; see **Search input** below.

## Search input

On the Sidephone keypad, Maestro `inputText` arrives as individual key events, not as a text
assignment. The T9 engine only multi-taps digits while it is in **Abc** mode, so the Search flow:

1. taps the field and presses a harmless key so the keypad action bar appears;
2. switches out of **123** mode if that is the current mode;
3. erases the probe character and enters the query as T9 digit groups.

The pinned Search query is **Die drei**, entered as `3444330377733444`
(`d i e <space> d r e i`). The show flow uses the first result below the **SHOWS** heading and
does not assert its title.

## Flows

The planned four-flow suite, coverage map, fixture requirements, and implementation checklist are in
[`FLOW-SUITE-TODO.md`](FLOW-SUITE-TODO.md). Only `flows/suite/` is listed in `config.yaml`;
`flows/demo/` and `flows/utility/` are kept out of the automated suite.

- `flows/suite/01-browse-play.yaml` — Home → Library → second followed artist → third top track → Now Playing → first album → first album track → Now Playing → another top track → Now Playing → Home. Covers track, artist, and album media, and adds a history entry before flow 4 removes one.
- `flows/suite/02-search-library-writes.yaml` — Home → Search → `Search in` scopes → artist follow write → album save write → show episode playback → Now Playing. Covers the show media kind.
- `flows/suite/03-queue-transport.yaml` — Home → play a recent track (so an episode left current by an earlier flow cannot remove `Save`) → queue two recent tracks → remove/Undo → Clear/Undo → optional Shuffle → Now Playing transport (play/pause, Next, repeat, Save, seek). Covers queue and transport.
- `flows/suite/04-history-settings.yaml` — Home → See more history → focus-revealed Remove one entry → Home → Settings (Autoplay, Normalize volume, Gapless, Audio quality) → cancel Clear cache and Clear history → Home. Covers History removal and Settings confirmations.
- `flows/demo/home-library-home.yaml` — launches the app, opens the Library, and returns to Home.
- `flows/demo/home-library-artist-nowplaying-home.yaml` — Library → second followed artist → third top track → Now Playing → Home, without using Search.
- `flows/demo/queue-history-management.yaml` — queues two recent tracks, removes and undoes a queue item, toggles Autoplay, and cancels Clear history.
- `flows/demo/hero-play-search-queue.yaml` — Now Playing, Queue, keypad search, and playback.
- `flows/utility/record-prepare.yaml` — recording setup only: clear history, then start a song.
