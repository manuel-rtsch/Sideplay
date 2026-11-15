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
   so ~200 ms is safe; ~1.2 s × 70 taps ≈ **−84 s**. *Measured: the Android driver always burns a
   fixed 750 ms window-update loop plus a fallback settle, so the cap only saves ~0.9 s/tap when
   animations are on and almost nothing when they are off. See §6.*
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

## 6. Implementation log

Baseline committed as `3bc06b7` before any flow changed.

Landed so far:

| Change | Result |
| --- | --- |
| `waitToSettleTimeoutMs: 200` on taps, swipes and scrolls | ~3.3 s → ~2.4 s/tap with animations on; no measurable gain with animations off |
| `speed: 80` + `visibilityPercentage: 60` on `scrollUntilVisible` | flow 01: 20.4 s → 11.3 s; `speed: 100` overshoots/undershoots and times out |
| `waitForAnimationToEnd` removed | ~0.9 s per removed command |
| `tapOn: "Back"` → `pressKey: Back` | ~2.1 s → ~0.7 s per navigation, 5–6 per flow |
| keypad probe: `eraseText: 2`, prep leaves Abc mode | probe/correction path shortened; prep no longer pays 40 key events |
| `flows/promo/01-browse-play.yaml` | recording variant, warm `launchApp`; fixture-dead guards removed, state-dependent guards kept |
| `record.sh` marker | `Launch app .*COMPLETED` regex: matches cold and warm launches, no flow-specific assert |
| `run-suite.sh` + `scripts/flow-times.py` | one `maestro test --no-reinstall-driver --test-output-dir` run, animations off, per-flow wall time |
| `FLOW_MAX_SECONDS` | opt-in length gate on the timing report |

Correction to §3 item 2: in Maestro 2.12.1 `AndroidDriver.waitForWindowToSettle` loops for the fixed
`WINDOW_UPDATE_TIMEOUT_MS` (750 ms) and then falls back to `ScreenshotUtils.waitForAppToSettle`,
which needs two matching hierarchy dumps. `waitToSettleTimeoutMs` only bounds the inner settle, so
it cannot bring a tap near the modelled 0.2 s. The real per-tap floor is ~2.0 s on this device; the
lever that remains is doing fewer taps, which is the §4 work.

Flow 01 after the technical changes:

| Run | Wall | Notes |
| --- | --- | --- |
| Suite baseline | 68.4 s | |
| Suite, technical changes + guards | 42.8 s | animations off, `pressKey: Back` |
| Promo variant, warm launch, no guards | 40.5 s | `commands.json` span |
| Promo recording (`record.sh`) | 39.4 s | `/tmp/01-browse-play-promo.mp4` |

### Log review after the first recording

The full-suite logs showed no deadlock and no Maestro-level tap retries (`retryIfNoChange` is false,
one `Tapping at` per command). Three things looked like a stuck or double-pressing bot:

| Symptom | Cause | Fix |
| --- | --- | --- |
| Long freezes after a track tap | six `runFlow when` guards never matched; each costs its full ~7 s visibility probe (42.5 s across the suite) | keep only fixture-dead guards out of the promo; guards whose branch depends on the selected row stay |
| Sensitivity to scroll position | `scrollUntilVisible` stops as soon as a label is reported visible, and a label clipped to 6 px at the bottom edge reports 100 % visible; the tap then lands on that sliver | scroll to the section heading, then a fixed swipe so two rows are fully visible; no `centerElement` (it overshoots and loses the element at `speed: 80`) |
| Different artist each run | `index` counts matches in the current viewport; the Library also remembers its scroll position between runs | reset to `LIKED SONGS` (UP), then `FOLLOWED ARTISTS` (DOWN), then the fixed swipe; `index: 1` is now the second followed artist |

The promo variant also had a latent failure: when a track tap opens Now Playing directly (the track was
already current), the following `Remote Menu` → `Now playing` step has nowhere to go. The suite hit
that case in §5. Match-case guards are cheap (~0.3–0.5 s when they match); only a miss costs 7 s.

§4 cuts applied to 01: remove §5 (another top track, two `Back`s, a scroll) and §6 (artist →
Library → Home), and end on Now Playing. Flow 01 now:

| Run | Wall | Notes |
| --- | --- | --- |
| Promo variant, warm launch, state guards kept | **29.9 s** | 28 commands, no skipped commands |
| Promo recording (`record.sh`) | 30.4 s | `/tmp/patch5.mp4` |
| Suite flow 01, cold launch | **31.7 s** | 28 commands, both guards matched |

Next: add `flows/promo/02…04` with the §4 content cuts, record all four, then enable
`FLOW_MAX_SECONDS=45` and pad every panel to 45.0 s in `design/promo/build-videos.sh`.
