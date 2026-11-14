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
maestro test flows/home-library-home.yaml
```

## Record a video

Use the wrapper, which prepares the device, records with `adb screenrecord`, and runs the flow:

```sh
cd maestro
./record.sh flows/hero-play-search-queue.yaml
```

The recording is written to `recordings/hero-play-search-queue.mp4`.

Before the recording starts, `record.sh` runs `flows/record-prepare.yaml`, which:

1. launches Sideplay and opens **Options → Settings**,
2. clears **Recently played** and dismisses the confirmation,
3. starts **Africa** by TOTO, and
4. returns Home with the song already playing in the background.

`record.sh` also turns off the device's window, transition, and animator scales while it records
(restoring them afterwards). This removes most of Maestro's per-tap settle wait. The recording
starts only after the flow has launched the app, so Maestro's JVM startup is not in the video.

To keep the device's animations on, set `SIDEPLAY_KEEP_ANIMATIONS=1`:

```sh
SIDEPLAY_KEEP_ANIMATIONS=1 ./record.sh flows/hero-play-search-queue.yaml
```

To record without preparing the device, set `SIDEPLAY_SKIP_PREPARE=1`:

```sh
SIDEPLAY_SKIP_PREPARE=1 ./record.sh flows/home-library-home.yaml
```

`record.sh` picks the first online device; set `SIDEPLAY_DEVICE=<serial>` to choose one
explicitly. The recording begins when the flow reaches
`Assert that "Sideplay" is visible... COMPLETED`. Override that marker with
`SIDEPLAY_RECORD_START_MARKER`.

`maestro record --local` also works, but is currently very slow (several minutes for a
~1 minute flow) and leaves an unplayable file if interrupted. Prefer `record.sh`.

## Flows

- `flows/home-library-home.yaml` — launches the app, opens the Library, and returns to Home.
- `flows/hero-play-search-queue.yaml` — Now Playing, Queue, keypad search, and playback.
- `flows/record-prepare.yaml` — recording setup only: clear history, then start a song.
