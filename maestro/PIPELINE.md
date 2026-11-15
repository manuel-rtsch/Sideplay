# Pipeline assessment: maestro → recording → promo video

Where the time goes in the four suite flows, and what to change to get every recording under 45 s
and roughly the same length. **This document is analysis only — no flow, `record.sh` or build script
has been changed yet.**

All numbers below are measured, not estimated. They come from the runs that produced the checked-in
recordings:

```
~/.maestro/tests/2026-11-14_205703/Browse & Play/commands.json          -> 01-browse-play.mp4
~/.maestro/tests/2026-11-14_205826/Search & Library Writes/commands.json -> 02-search-library-writes.mp4
~/.maestro/tests/2026-11-14_210017/Queue & Transport/commands.json       -> 03-queue-transport.mp4
~/.maestro/tests/2026-11-14_210144/History & Settings/commands.json      -> 04-history-settings.mp4
```

Each `commands.json` entry carries `metadata.status`, `metadata.timestamp` and `metadata.duration`
(ms) per command, including nested `runFlow` children; the matching `maestro.log` adds the internal
phases (`Tap aimed via settled hierarchy`, `Something has changed in the UI`, `Scrolling … try
count`, `CommandSkipped`). Maestro here is **2.12.1**.

## 1. Current length and command mix

| Flow | Wall | Commands | Taps | `runFlow when` | Scroll | Asserts |
| --- | --- | --- | --- | --- | --- | --- |
| 01 Browse & Play | 68.4 s | 36 | 14 → **31.5 s** | 2 (7.3 s) + 1 skipped (7.1 s) | 3 → **20.4 s** | 15 (4.5 s) |
| 02 Search & Writes | 94.8 s | 45 | 21 → **47.8 s** | 7 (22.6 s) + 2 skipped (13.6 s) | 3 → **18.8 s** | 20 (5.1 s) |
| 03 Queue & Transport | 71.6 s | 41 | 19 → **44.3 s** | 9 (24.5 s) + 2 skipped (14.2 s) | 1 (0.3 s) | 23 (5.9 s) |
| 04 History & Settings | 51.2 s | 51 | 16 → **33.3 s** | 1 (2.2 s) | 3 (5.3 s) | 24 (6.7 s) |
| **Suite** | **286 s** | 195 | 70 → **157 s (55 %)** | 19 → **57 s (20 %)** | 10 → **45 s (16 %)** | 82 → **22 s (8 %)** |

The rest is `launchApp` (7 s), `pressKey` (5 s), `inputText`/`eraseText` (5 s), `swipe` (3 s) and
`waitForAnimationToEnd` (3 s).

## 2. Cost model

| Command | Cost | Why |
| --- | --- | --- |
| `tapOn` | **2.1 s** median (1.5–3.8 s) | **1.54 s** before the tap: `refreshElementUntilStable` re-dumps the hierarchy until the element's position stops moving (`Tap aimed via settled hierarchy`). **0.52 s** after: `hierarchyBasedTap` waits for a changed hierarchy (`Something has changed in the UI`). |
| `runFlow when:` — matches | ~2.2–2.5 s + children | condition check is a visibility probe, then the children run. |
| `runFlow when:` — does **not** match | **~7.0 s** | the probe waits out its default visibility timeout. Five guards never match on these fixtures: **34.9 s of the suite is spent proving nothing is there** (03: `if Previous` 7.21 s, `if Now playing` 7.01 s). |
| `scrollUntilVisible` | **~2.5 s per swipe attempt** | one swipe (601 ms at `speed: 40`) + hierarchy dump + settle, repeated. 01 spends **15.35 s / 6 attempts** finding `Artist #1`; 02 spends 9.57 s and 6.80 s. |
| `assertVisible` | **0.27 s** | cheap — assertions are *not* the problem. Cutting them buys little; an assert on a not-yet-present element blocks for its timeout, which is a different issue. |
| `waitForAnimationToEnd` | 0.9 s even when static | screenshot-diff loop, `timeout 15000`. |
| `eraseText: 40` | 1.6 s | one key event per character. |
| `launchApp` (default `stopApp: true`) | 1.4–1.8 s + splash | cold start. |

So the flows are not slow because of what they *do*: they are slow because Maestro pays ~2 s per tap,
~7 s per unmet guard and ~2.5 s per scroll attempt.

## 3. Technical changes, ranked by payoff

1. **Delete dead guards** — `record-prepare.yaml` already reseeds the device, so the "already
   current / followed / saved / playing" fallbacks are unnecessary for a recording. There is no knob
   to make a miss cheap: `Condition` in 2.12.1 only exposes `visible`, `notVisible`, `platform`,
   `scriptCondition`, `true` — no timeout. **−35 s.**
2. **Cap the pre-tap settle** with `waitToSettleTimeoutMs` (present on `tapOn`, `swipe` and
   `scrollUntilVisible` in 2.12.1, verified in the command models). Animations are already disabled,
   so ~200 ms is safe; ~1.2 s × 70 taps ≈ **−84 s**.
   ```yaml
   - tapOn: { text: "Library", waitToSettleTimeoutMs: 200 }
   ```
3. **Scroll in fewer, longer swipes.** `speed: 40` is a 601 ms swipe ≈ one screen. Use `speed: 80`
   with `visibilityPercentage: 60`, and prefer targets that are already on screen. Watch for
   overshoot: `scrollUntilVisible` only scrolls one direction, so a fling that skips the target ends
   in a timeout. 01: 15.4 s → ~4 s. **−25…30 s.**
   ```yaml
   - scrollUntilVisible:
       element: { text: "ALBUMS" }
       direction: DOWN
       speed: 80
       visibilityPercentage: 60
       waitToSettleTimeoutMs: 200
   ```
4. **Drop `waitForAnimationToEnd`** — no animations to wait for. **−3 s.**
5. **Keypad mode probe** (`02` and `record-prepare`): `inputText: "0"` → wait for `Abc|123` →
   maybe tap `123` → wait → `eraseText: 40` is ~6.4 s. Leave the keypad in Abc mode in the prep and
   `eraseText: 2`. **−7 s.**
6. **Warm launch** for recording runs: `- launchApp: { stopApp: false }` (the prep just started the
   app). **−1.5 s** and no splash frames.
7. **Suite-level**: one `maestro test` invocation with `--no-reinstall-driver` instead of four
   (driver reinstall is 5–10 s per invocation), and `--test-output-dir` so `commands.json` is kept.
   Then gate the build on flow length — it is a ten-line check over the timestamps above.
8. **`record.sh` start marker is brittle**: it greps for the literal
   `Assert that "Sideplay" is visible... COMPLETED`. Remove that assert and the recording only starts
   after the 30 s timeout, capturing the whole flow. Match the first real action instead
   (`Tap on "…" RUNNING`), or keep a deliberate first assert as the marker.

Projected with items 1–5 only (no content cuts): **01 ≈ 27 s, 02 ≈ 55 s, 03 ≈ 42 s, 04 ≈ 30 s**.

## 4. Behavioural changes to land every clip in 40–45 s

- **01 (68.4 s):** the 6-swipe hunt for `Artist #1` is 22 % of the clip — tap an artist that is
  already on screen. Drop §5 ("another top track", two `Back`s, a scroll ≈ 14 s) and end on Now
  Playing instead of walking back to Library → Home (≈ 6 s). Keep the album + first-track detour,
  it is the visual payoff.
- **02 (94.8 s):** remove the `Search in` detour (≈ 9 s of taps for a settings toggle); collapse
  follow and save to **one direction each** (each double-toggle block is ~7 s of taps plus a 6.8 s
  guard); tap `Done` once instead of three guarded `if Done` blocks (≈ 6 s); don't scroll back to the
  top for `ALBUMS` (9.6 s) — open the album before the artist.
- **03 (71.6 s):** delete §1 "make a track current" — the fixture guarantees a current track
  (scroll + tap + 7.2 s guard + 7.0 s guard ≈ 12 s). Cycle `Repeat` once instead of through all three
  labels (≈ 8 s), one play/pause toggle, and keep shuffle out of the recording.
- **04 (51.2 s):** step-count bound (51 steps). Keep one destructive-confirmation dialog instead of
  two (cache *or* history, ≈ 7 s), keep two of the three settings toggles (≈ 2 s each), and drop the
  Audio quality sub-screen (6.5 s) if the margin is needed.

Then pad every panel to exactly **45.0 s** in `design/promo/build-videos.sh`
(`tpad=stop_mode=clone:stop_duration=…`), which also removes the uneven freezes in the 16:9 grid:
all four panels would end together instead of two sitting still for 45 s.

## 5. Recommended shape

Keep `flows/suite/` authoritative for CI (guards and both-direction toggles are coverage), and add
recording-first variants — `flows/promo/*.yaml` — that contain only the intended path, the trimmed
narrative and the settle overrides. Gating inside one file is possible (`Condition` supports
`true:` and `scriptCondition:`, so `-e PROMO=1` works), but two small files are easier to read and
cannot accidentally weaken the suite.
