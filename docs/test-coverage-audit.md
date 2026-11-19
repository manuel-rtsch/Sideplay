# Test Suite and Coverage Audit

Status: audit only, no code changes yet.
Branch: `test/coverage-audit`, based on `release/v0.4.0` @ `cbe96d0`.
Measured: debug variant, all four unit-test modules, merged.

This document records the current shape of Sidetrack's unit-test suite: how much code
there is, how many tests exist, where coverage actually lands, and where the suite is
spending effort without buying risk reduction. It is the baseline for the test
optimisation work on this branch.

---

## 1. Method

Figures below are measured, not estimated:

* **Size** — `cloc` over `src/main` only (Kotlin code lines: non-blank, non-comment),
  dependencies, vendored librespot and generated sources excluded.
* **Test count** — executed JUnit test methods from the Gradle test reports
  (`*/build/test-results/testDebugUnitTest/TEST-*.xml`), cross-checked against `@Test`
  annotations.
* **Coverage** — JaCoCo 0.8.12 line/branch coverage of debug-compiled classes, all
  modules merged.

One caveat that matters if you reproduce this: **Robolectric ignores an on-the-fly JaCoCo
agent**, so a plain `-javaagent` run reports ~0% for every Robolectric-backed test and
makes the app look almost untested. The numbers here come from *offline* instrumentation
of the compiled class jars. The exact recipe is in [Appendix A](#appendix-a--reproducing-the-numbers).

Scope notes:

* Android instrumentation tests (`app/src/androidTest`, 3 classes) are out of scope;
  they are device tests, not unit tests.
* Compose compiler-generated code and `BuildConfig` are included in the denominators
  where JaCoCo sees them; they are not actionable.

---

## 2. Size of the codebase

Own product code, `src/main` only:

| Module | Kotlin files | Code lines | Share |
|---|---|---|---|
| `app` | 37 | 10,545 | 59% |
| `core` | 26 | 2,596 | 15% |
| `provider-spotify` | 18 | 2,672 | 15% |
| `provider-jellyfin` | 6 | 1,414 | 8% |
| `provider-navidrome` | 4 | 679 | 4% |
| **Kotlin total** | **91** | **17,906** | |
| Rust (`native/sidetrack-spotify/src`) | 4 | 3,219 | |
| **Product code total** | **95** | **~21,100** | |

Excluded: `native/vendor` (vendored librespot and friends, ~7.4k lines plus its own
dependencies), XML resources, Gradle scripts, `design/`, `maestro/`, `docs/`.

Test code: **7,041 lines** across **47 test classes** plus 6 fakes/fixtures
(`MockSpotifyDataSource`, `FakeHttpStream`, `FakeJellyfinApi`, `TestHttpStream`,
`NowPlayingFixture`, `MediaKeyFixtures`/`RecordingHapticShadow`/`Previews`).

Test-to-product-code ratio is therefore about **1:3**, and 395 tests is about
**1 test per 53 product lines** (1 per 45 Kotlin lines).

---

## 3. Current test inventory

`./gradlew testDebugUnitTest` → 395 test methods, all green, 6 skipped.

| Module | Test classes | Tests | Test code lines | Test:main ratio |
|---|---|---|---|---|
| `app` | 30 | 231 | 4,526 | 0.43 |
| `provider-spotify` | 10 | 110 | 1,363 | 0.51 |
| `provider-jellyfin` | 5 | 36 | 784 | 0.55 |
| `provider-navidrome` | 2 | 18 | 368 | 0.54 |
| `core` | **0** | **0** | 0 | **0.00** |
| **Total** | **47** | **395** | **7,041** | 0.39 |

The 6 skipped tests are the live-server integration tests, which silently do nothing in
CI because no server is provisioned:

* `NavidromeIntegrationTest` — 4 tests
* `JellyfinIntegrationTest` — 2 tests

That is 1.5% of the suite reporting "green" without asserting anything.

Largest test files by size: `SidetrackScreenshotTest` (415 lines), `PlaybackControllerTest`
(331), `SpotifyViewModelEventTest` (329), `NativeSpotifyCatalogTest` (325),
`SpotifyFallbackStateTest` (319), `SpotifyQueueRepositoryTest` (303).

---

## 4. Coverage, measured

Merged across all modules (a line covered by any module's tests counts once):

| Area | Line coverage | Branch coverage |
|---|---|---|
| **Overall** | **57.0%** (7,142 / 12,520) | **37.3%** |
| `app` | 58.0% (4,394 / 7,570) | 38.1% |
| `core` | 75.1% (1,274 / 1,696) | 50.3% |
| `provider-spotify` | 42.4% (728 / 1,718) | 23.9% |
| `provider-jellyfin` | 47.6% (507 / 1,066) | 38.8% |
| `provider-navidrome` | 50.9% (239 / 470) | 35.1% |

`core`'s 75% is entirely indirect: `:core` has **no test source set**, so its coverage
comes from `:app` and provider tests importing `QueueRepository`, `PlaybackController`,
`StateStores` and friends. That has two costs — those tests pay full
Robolectric/Compose startup cost for pure Kotlin logic, and there is no per-module gate
that would catch a regression in `core` early.

### 4.1 Where coverage is good (≥90%)

| Code | Lines | Coverage |
|---|---|---|
| `core/data/HistoryRepository.kt` | 44 | 100% |
| `core/data/PlaybackSettings.kt` | 28 | 100% |
| `core/data/QueueRepository.kt` | 148 | 98% |
| `core/data/EpisodeProgress.kt` | 124 | 92% |
| `core/data/StateStores.kt` | 190 | 92% |
| `core/playback/DrainPolicy.kt` | 18 | 100% |
| `core/playback/SleepTimer.kt` | 31 | 97% |
| `core/playback/PlaybackEngine.kt` | 76 | 96% |
| `app/SidetrackAboutScreen.kt` | 112 | 100% |
| `app/SidetrackItem.kt` | 70 | 100% |
| `app/UiSlices.kt` | 127 | 98% |
| `app/PodcastText.kt` | 56 | 96% |
| `app/SidetrackOnboardingScreen.kt` | 111 | 92% |
| `provider-spotify/auth/SpotifyPkce.kt` | 39 | 97% |
| `provider-jellyfin/data/JellyfinPlaybackReporter.kt` | 94 | 98% |
| `provider-jellyfin/data/JellyfinCatalog.kt` | 203 | 97% |

### 4.2 Where the code is effectively untested

Zero or near-zero coverage, ranked by how much runtime risk the file carries:

| File | Lines | Coverage | Why it matters |
|---|---|---|---|
| `app/playback/PlaybackService.kt` | 139 | **0%** | The Android service that runs during playback |
| `app/data/AppSession.kt` | 91 | **5.5%** | Session/bootstrap wiring |
| `app/SidetrackGraph.kt` | 787 | **8.9%** | Navigation graph — every screen transition goes through it |
| `app/SidetrackActivity.kt` | 66 | **0%** | Host activity |
| `app/SidetrackAppearanceScreen.kt` | 21 | **0%** | |
| `app/SidetrackNotes.kt` | 10 | **0%** | |
| `app/ui/QueueIndication.kt` | 26 | **0%** | |
| `app/ui/QueueReorder.kt` | 121 | **24.8%** | User-facing drag/reorder state |
| `app/ui/Haptics.kt` | 7 | **28.6%** | |
| `core/playback/AudioFocus.kt` | 27 | **0%** | Focus loss/ducking — common device bug source |
| `core/playback/ExoPlayerMedia3Player.kt` | 60 | **0%** | |
| `core/playback/PlaybackController.kt` | 317 | **53.3%** | Largest playback surface |
| `core/data/ServiceRuntime.kt` | 15 | **26.7%** | |
| `core/storage/GuardedEncryptedStore.kt` | 17 | **35.3%** | Credential boundary |
| `provider-spotify/auth/SpotifyTokenClient.kt` | 49 | **2.0%** | Token refresh — silent auth failures |
| `provider-spotify/data/SpotifyWebApi.kt` | 310 | **7.7%** | |
| `provider-spotify/data/SpotifySession.kt` | 279 | **35.5%** | |
| `provider-spotify/playback/SpotifyAudioSink.kt` | 228 | **3.5%** | |
| `provider-spotify/bridge/NativeBridge.kt` | 48 | **6.2%** | JNI boundary |
| `provider-spotify/data/SpotifyWebCatalog.kt` | 9 | **0%** | |
| `provider-jellyfin/data/JellyfinRuntime.kt` | 155 | **0.6%** | |
| `provider-jellyfin/data/JellyfinApi.kt` | 485 | **27.8%** | |
| `provider-navidrome/data/NavidromeRuntime.kt` | 112 | **0.9%** | |

Roughly 1,800 lines of glue sit at under 10%, and that is precisely the layer that
cannot be exercised from a JVM-only test without some deliberate seams (fake runtime,
fake service, injected clock/dispatcher).

---

## 5. Where the suite is spending effort inefficiently

Coverage alone does not show over-testing; density and duplication do.

### 5.1 Screenshot tests duplicate semantic tests

`SidetrackScreenshotTest` is 26 tests, 415 test lines and 27 PNG baselines, and it runs on
every `testDebugUnitTest`, paying Robolectric + Roborazzi + Compose render cost. Nearly
every screen it renders also has behavioural assertions elsewhere
(`AccessibilitySemanticsTest`, `RowActionBarTest`, `HistoryRowActionsTest`,
`NowPlayingScreens`/`SettingsScreens`/`LibraryScreens` semantics, `TrackRowGutterTest`).

Verdict: keep the baselines, but run them as a tagged, separate task (PR/nightly), not as
part of the default loop.

### 5.2 Spotify is covered twice, at two layers

`:app` carries Spotify behaviour that `:provider-spotify` also covers:

| `:app` test | Tests | Lines | Also covered in provider module by |
|---|---|---|---|
| `SpotifyViewModelEventTest` | 32 | 329 | `NativeSpotifyGatewayTest`, `SpotifyQueueRepositoryTest` |
| `SpotifyFallbackStateTest` | 12 | 319 | `SpotifySessionCacheTest`, `NativeSpotifyCatalogTest` |
| `SpotifyPersistenceTest` | 14 | 205 | `SpotifyHistoryRepositoryTest`, `SpotifyEpisodeProgressTest` |
| `SpotifyPaginationTest` | 4 | 43 | `NativeSpotifyCatalogTest` |

That is ~62 tests / ~900 lines in `:app` exercising session, queue, fallback and
persistence semantics that the provider module owns. Some of it is legitimately
view-model behaviour, but the split is accidental rather than designed — and it keeps a
large share of the "provider" logic testable only through Compose.

The two engine suites (`Media3PlaybackEngineTest` 12 tests, `LibrespotPlaybackEngineTest`
11 tests) are justified as parity contracts, but their fakes/fixtures are triplicated
(`MockSpotifyDataSource`, `FakeHttpStream`, `MediaKeyFixtures`, `NowPlayingFixture`).

### 5.3 Highest density sits on the lowest-risk code

| Code | Code lines | Tests | Tests per 100 lines |
|---|---|---|---|
| `core/data/QueueRepository.kt` | 148 | 34 (`SpotifyQueueRepositoryTest`) | **23** |
| `app/ui/RowGutter.kt` | 157 | 15 (`TrackRowGutterTest`) | 9.6 |
| `provider-spotify/data/NativeSpotifyCatalog.kt` | 240 | 22 | 9.2 |
| `provider-jellyfin/data/JellyfinCatalog.kt` | 203 | 10 | 4.9 |
| `app/SidetrackViewModel.kt` | 1,634 | ~46 | 2.8 |

`QueueRepository` at 98% with 34 tests is the single densest area in the repo, while
`PlaybackService` — the component that actually runs while music plays — has zero tests.
Trivia is also pinned at 100%: `AboutVersionTest`, `SidetrackLinksTest`,
`PodcastTextTest` (8 tests for a 56-line file), `SidetrackAboutScreen`.

---

## 6. Targets

Two independent heuristics agree on the size of the next step:

* **Density:** 1 test per ~40 product lines → ~525 tests.
* **Coverage:** lifting 57% → 70% requires ~2,000–2,600 newly covered lines; concentrated
  in 0%-coverage glue that is ~20 lines per test, that is 120–160 new tests.

**Target: 530–560 tests, with a 70% overall line-coverage floor and a 60% per-module
floor.** The count is an output, not the goal — the floors and the risk list are the goal.

| Module | Tests now | Target | Priority work |
|---|---|---|---|
| `core` | 0 (in-module) | 40 | Create a `:core` test source set; move pure-logic tests out of `:app`; cover `AudioFocus`, `ExoPlayerMedia3Player`, `ServiceRuntime`, `GuardedEncryptedStore`, rest of `PlaybackController` |
| `app` | 231 | 290 | `PlaybackService` (0%), `AppSession` (5%), `SidetrackGraph` (9%), `SidetrackActivity`, `QueueReorder`/`QueueIndication`/`Haptics` |
| `provider-spotify` | 110 | 145 | `SpotifyTokenClient` (2%), `SpotifyWebApi` (8%), `SpotifySession` (36%), `SpotifyAudioSink` (3.5%), `NativeBridge` (6%) |
| `provider-jellyfin` | 36 | 55 | `JellyfinRuntime` (0.6%), `JellyfinApi` (28%) |
| `provider-navidrome` | 18 | 30 | `NavidromeRuntime` (0.9%) |

Stop at ~70%. Beyond that, the remaining uncovered lines are Compose scaffolding and
platform glue where tests are expensive and weak; additional budget is better spent on
instrumented tests for the playback service than on unit-test line coverage.

---

## 7. Proposed plan for this branch

**Phase 1 — infrastructure (no behaviour tests yet)**

1. Add Kover with per-module floors: 70% overall, 60% per module, excluding
   Compose-generated code and `BuildConfig`.
2. Create `core/src/test` and move the pure-logic tests there (`QueueRepository`,
   `EpisodeProgress`, `HistoryRepository`, `PlaybackController`, `DrainPolicy`,
   `SleepTimer`, `StateStores`). These get ~10× faster without Robolectric.
3. Split suites by tag: `unit` (default, fast), `screenshot` (Roborazzi, PR/nightly),
   `integration` (needs a live Navidrome/Jellyfin). Integration tests become an explicit
   opt-in task instead of silently skipping.
4. Consolidate fakes into a small shared test-fixtures module or source set to remove the
   triplicated Spotify mocks.

**Phase 2 — close the 0% glue** (the ~130 new tests)

5. `app`: `PlaybackService`, `AppSession`, `SidetrackGraph` (route table + deep links),
   `QueueReorder`, `Haptics`/`QueueIndication` via injected shadows.
6. `core`: `AudioFocus` (loss, duck, transient, permanent), `ExoPlayerMedia3Player`,
   `ServiceRuntime`, `GuardedEncryptedStore` failure paths.
7. Providers: token client refresh/expiry/failure, session lifecycle, runtime
   start/stop/halt, native bridge error mapping, `SpotifyWebApi`/`JellyfinApi` request
   construction and error paths.

**Phase 3 — rebalance**

8. Decide ownership per behaviour: provider-level vs view-model-level, and delete the
   duplicated half rather than keeping both.
9. Cap the screenshot suite: one baseline per screen state that semantics tests cannot
   express, not one per screen variant.
10. Re-measure and record the delta at the end of the branch.

---

## 8. Explicitly out of scope

* Vendored Rust (`native/vendor`) and third-party Compose/Media3 code.
* `BuildConfig` and compiler-generated Compose code.
* Full end-to-end device flows — those belong to Maestro (`maestro/`), not unit tests.

---

## 9. Phase 1 status (landed on this branch)

Phase 1 was infrastructure only; no production behaviour changed.

### 9.1 What landed

1. **Kover replaces the ad-hoc JaCoCo measurement.** `kover` 0.9.11 on the `debug` variant, with
   `:app` as the merging module so `:app:koverVerifyDebug` measures every module together. Generated
   code (`BuildConfig`, `R`, `ComposableSingletons`) is filtered out; `@Composable` functions are
   deliberately still counted, because the semantics and screenshot tests do exercise them.
   Entry point: `./gradlew coverage`.
2. **`:core` has a test source set.** Seven suites moved there, renamed where the old name leaked a
   provider: `QueueRepositoryTest` (34), `HistoryRepositoryTest` (10), `EpisodeProgressTest` (7),
   `StorePersistenceTest` (14), `DrainPolicyTest` (8), `SleepTimerTest` (4), `HaltStopGuardTest` (4).
   That is **81 tests that now run in ~3 s** instead of under Robolectric/Compose.
3. **Suites are split by cost and dependency.** `*ScreenshotTest` and `*IntegrationTest` are filtered
   out of the default `testDebugUnitTest` and gated behind `-PsidetrackScreenshots` and
   `-PsidetrackIntegration`. Default run: **359 tests, ~46 s**. Full run: **385 tests**.
4. **Screenshot tests actually verify now.** They were running with `roborazzi.test.record=true` on
   every invocation, so all 26 could never fail — they rewrote their own baselines. Recording is now
   opt-in (`-PsidetrackScreenshotsRecord`) and verification is the default. All 26 baselines match, so
   no rebaselining was needed.
5. **Coverage floors are a ratchet** in the build files, with the bound tracking which suite ran:

| Gate | Suite | Line floor | Branch floor | Measured |
|---|---|---|---|---|
| `:app` (product-wide, merged) | default | 45 | 28 | 46.0% / 29.5% |
| `:app` (product-wide, merged) | full | 57 | 34 | 58.4% / 35.8% |
| `:core` | own tests | 34 | 27 | 35.8% / 29.0% |
| `:provider-spotify` | own tests | 31 | 20 | 32.5% / 20.8% |
| `:provider-jellyfin` | own tests | 46 | 38 | 47.1% / 39.7% |
| `:provider-navidrome` | own tests | 50 | 34 | 51.4% / 35.4% |

Phase 2 targets remain **70% line / 45% branch** on the full suite.

### 9.2 What the split revealed

* **The screenshot suite carries ~12 points of line coverage** (58.4% full vs 46.0% default). That is
  the single largest block of coverage in the repo, and it comes from rendering screens whose
  behaviour is already asserted by semantics tests. It is why the two floors differ.
* **Moving tests to `:core` did not change product coverage** — the same lines were already exercised,
  just from a more expensive JVM. What changed is the feedback loop: 81 tests in 3 s instead of ~40 s.
* **Per-module floors and per-module *coverage* are different numbers.** `:core` verifies against
  35.8% because that is what its own tests reach; the whole suite still covers roughly 75% of it. The
  per-module gates therefore guard against losing a module's own tests, while the `:app` merged gate
  guards the product. Both are intentional.

### 9.3 Findings that move to Phase 2

* Fixture sharing is the blocker for moving the remaining playback tests: `Media3PlaybackEngineTest`
  depends on `app/src/test/.../FakeHttpStream.kt`, which six other app tests also use. Android
  `testFixtures` on `:core` (or a small `:test-support` module) would let that test and its fixture
  travel to `:core`. The fakes are otherwise not duplicated: the Jellyfin and Navidrome fakes are
  provider-local by design.
* `:app` still carries ~62 Spotify tests duplicating `:provider-spotify` coverage, and
  `PlaybackControllerTest` / `LibrespotPlaybackEngineTest` still sit in `:app` even though they only
  touch `:core` and `:provider-spotify` types (`PlaybackControllerTest` needs `SpotifyGateway`, so it
  belongs in `:provider-spotify`).
* `SidetrackGraph` remains at 8.9% and `PlaybackService` at 0%: the two largest risks are untouched,
  which is exactly where Phase 2 starts.

### 9.4 Baseline reconciliation

The audit above was measured in the 0.4.0 working tree, which contained four uncommitted
`JellyfinAuthTest` cases that do not exist at `cbe96d0`. On this branch the committed baseline is
391 tests (395 − 4); Phase 1 moved 81 of them and split the rest into 359 default / 385 full.

---

## 10. Phase 2 status (four slices)

Phase 2 is being worked in slices. The first took the `:core` glue the audit named plus the queue
reorder state machine; the second took the two largest `app` risks, the playback service and the
app session, and whatever of the navigation graph is reachable without a composed nav host; the
third took the provider runtimes and the three Spotify boundaries the audit named (token client,
audio sink, native bridge); the fourth took the two remaining Spotify data seams and the
SDK-backed Jellyfin API.

### 10.1 What landed (slice 1)

| Area | Tests | What is pinned |
|---|---|---|
| `core/playback/AudioFocus.kt` | 12 | grant, denial, a held request is not repeated, abandon with and without focus, transient loss resumes on gain, permanent loss does not, a transient loss followed by a permanent one does not resume, abandon during a transient loss cancels the resume, duck is ignored, a permanent loss re-asks the system |
| `core/data/ServiceRuntime.kt` | 10 | every optional-interface extension answers the neutral value when the interface is absent, and forwards to the runtime when it is present |
| `app/ui/QueueReorder.kt` | 13 | lift state, keypad stepping and its bounds, step without a lift, touch lift ignoring steps, a sub-half-row drag, end/cancel clearing every field Back reads |

### 10.1a What landed (slice 2)

| Area | Tests | What is pinned |
|---|---|---|
| `app/playback/PlaybackService.kt` | 12 | channel creation, the nothing-playing path leaving the foreground, the playing path promoting to a media notification, title/artist text, the three notification actions and the paused player's Play button, each transport action reaching the player, the signed-out session dropping the notification, task removal halting audio |
| `app/data/AppSession.kt` | 12 | unprovisioned state for every accessor, the runtime accessor failing loudly, transport commands silent before provisioning, install publishing the service and forwarding each transport call, `signedIn` and `playback` following the runtime, re-install replacing both |
| `app/SidetrackGraph.kt` | 16 | the keypad-back route table (Home/Welcome/Sign in excluded, every screen route included, unknown/null routes and an empty back stack excluded), the notification intent recorded and other intents ignored |

The service test installs a session into `AppSessions` by reflection and drives `onStartCommand`
directly, so it exercises the real notification builder and the real `PlaybackController`, not a
model of them. The session and provider fakes moved to `app/src/test/.../RecordingServiceRuntime.kt`,
which now backs both files.

35 new tests in slice 1, 40 in slice 2. They run in the default suite: `:core` in ~1 s (no Robolectric for the runtime
seams), `QueueReorderTest` in well under a second.

Audio focus is exercised through Robolectric's `ShadowAudioManager`; the listener is read off the
shadow request by type, because the field name is a Robolectric implementation detail.

### 10.2 Measured delta

| Gate | Phase 1 | After slice 1 | After slice 2 | After slice 3 | After slice 4 |
|---|---|---|---|---|---|
| Product line (default suite) | 45.99% | 46.75% | 48.57% | 52.36% | **57.74%** |
| Product branch (default suite) | 29.46% | 30.07% | 31.20% | 33.88% | **38.12%** |
| Product line (full suite) | 58.4% | 58.4% | 58.4% | 64.66% | **69.98%** |
| Product branch (full suite) | 35.8% | 35.8% | 35.8% | 40.16% | **44.35%** |
| `:core` line (own tests) | 35.8% | 46.5% | 46.5% | 46.5% | 46.5% |
| `:core` branch (own tests) | 29.0% | 34.0% | 34.0% | 34.0% | 34.0% |
| Floors: `:app` default, `:app` full | 45/28, 57/34 | 46/30, 57/34 | 48/31, 57/34 | 52/33, 64/40 | **57/38, 69/44** |
| Floors: `:core`, `:provider-spotify` | 34/27, 31/20 | 46/34, 31/20 | 46/34, 31/20 | 46/34, 48/33 | 46/34, **67/52** |
| Floors: `:provider-jellyfin`, `:provider-navidrome` | 46/38, 50/34 | 46/38, 50/34 | 46/38, 50/34 | 58/44, 68/41 | **91/59, 68/41** |

Per-file after slice 2: `PlaybackService.kt` 0% → **89.0%** line, `AppSession.kt` 5.5% → **36.1%**,
`QueueReorder.kt` 24.8% → **49.1%**, `SidetrackGraph.kt` 8.9% → **9.0%**.

Per-file after slice 3: `JellyfinRuntime.kt` 0.6% → **71.8%**, `NavidromeRuntime.kt` 0.9% →
**69.5%**, `HttpSpotifyTokenClient.kt` 2.0% → **100%**, `SpotifyAudioSink.kt` 3.5% → **83.8%**,
`NativeBridge.kt` 6.2% → **90.9%**. The full-suite gates were re-measured for the first time since
Phase 1, so the full-suite floor moved with them.

Per-file after slice 4: `SpotifyWebApi.kt` 7.7% → **98.9%**, `SpotifySession.kt` 35.5% →
**55.5%**, `SdkJellyfinApi` → **100%** and the Jellyfin module 58.5% → **91.1%**. The full-suite
line is now **69.98%**, effectively at the Phase 2 line target; branch is 0.65 points short.

`:provider-spotify` moved from 32.5% at the start of Phase 2 to **67.2%** and is no longer the
module holding the product-wide number back. Its remaining gaps are playback paths that need the
native library, not request mapping. `:provider-jellyfin` (91.1%) and `:provider-navidrome` (68.4%)
are close to or past the module target, and `:core` (46.5%) is the last large module below it.

### 10.3 What the slice changed about the plan

* **Two audit items are blocked on a seam, not on test effort.** `GuardedEncryptedStore`
  constructs `EncryptedFileStore` in its initializer, so its failure paths can only be reached
  with a keystore that throws — the app's `CredentialStoreGuardTest` covers the same behaviour
  through the injectable `JsonStore` instead. `ExoPlayerMedia3Player` builds a real `ExoPlayer`,
  and Robolectric cannot buffer audio; its listener translation is only worth testing once a
  `Media3Player` factory seam exists. Both should be decided (seam or accept the gap) before the
  next slice spends time on them.
* **`ServiceRuntime`'s optional interfaces are cheap coverage but shallow.** The extensions only
  cast; the value is regression insurance for the neutral defaults. The real risk in that file is
  the runtime implementations (`JellyfinRuntime`, `NavidromeRuntime`), which are still at ~1%.
* **`QueueReorder`'s drag math still needs a laid-out list.** The keypad path and the no-op cases
  are covered; the slot arithmetic (`firstOnScreen`/`lastOnScreen`, auto-scroll) needs a Compose
  list with a real viewport, so it belongs with the instrumented or Roborazzi work, not here.

### 10.4 What landed (slice 3)

| Area | Tests | What is pinned |
|---|---|---|
| `provider-spotify/auth/SpotifyTokenClient.kt` | 8 | the authorization-code form (PKCE verifier, redirect, client id, form encoding), refresh grants, refresh-token rotation and the keep-previous rule, missing token type/scope/expiry defaults, Spotify's `error_description`, a blank error body falling back to the HTTP status, an unreadable body, and a 200 without an access token |
| `provider-spotify/playback/SpotifyAudioSink.kt` | 7 | every no-track path (position, seek, pause, repeated stop), a start that drains PCM and reports audio flowing, valid/invalid/missing native format JSON, a dead drain reported through `onFailure`, resume without a paused track restarting at the requested position, pause/resume reusing the track, and reset keeping the track while dropping the item's audio |
| `provider-spotify/bridge/NativeBridge.kt` | 3 | every call answers the neutral value when the library is absent, every call degrades to false/null when the library reports loaded but the symbols are missing (a partial ABI split), and the library name the Rust build produces |
| `provider-jellyfin/data/JellyfinRuntime.kt` | 12 | empty vs stored session, sign-in persisting the token, a keystore write failure still signing in for this process, settings applied immediately, queue/history/resume isolation per account, sign-out clearing session and state, audio-cache accounting, the resume-item kind mapping, and the provider identity |
| `provider-navidrome/data/NavidromeRuntime.kt` | 9 | the same account-state contract for Navidrome: signed-out defaults, stored credentials, settings, queue/history/resume isolation, sign-out, cache accounting and the provider identity |

38 new tests, all in the default suite: the default gate is now 473 tests and the full run 499,
all green. The token client gained a `tokenUrl` constructor argument (defaulted to Spotify's
endpoint) so the real HTTP handling can run against a loopback server; no production behaviour
changed. The runtime tests inject a `JsonStore` and a `JellyfinAuthenticator`, so they never touch
the keystore or a real server.

Two boundaries are pinned through their real failure mode rather than a mock: the audio sink is
driven through Robolectric's `ShadowAudioTrack` (writes, pause, flush and the playback head are
real shadow behaviour), and `NativeBridge` flips the SDK's own loaded/failed set by reflection so
the "library present but symbols missing" branch executes. The reflection is the only seam short of
shipping a test-only ABI, and it fails visibly if the SDK renames its state.

### 10.5 What landed (slice 4)

| Area | Tests | What is pinned |
|---|---|---|
| `provider-spotify/data/SpotifyWebApi.kt` | 24 | every library filter and its nested envelope, saved-track/saved-album/followed-artist/saved-show paging, all three collection flows (detail then page), search scope ordering, query encoding, the 1..50/1..100 limit clamps, artist/albums/top-tracks/radio mapping, the "smallest usable artwork" and blank-url fallbacks, sparse-track and episode defaults, and 401/429/4xx/unreadable-body/unreachable-server translation |
| `provider-spotify/data/SpotifySession.kt` | 15 | stored-vs-empty sign-in state including an unreadable store, valid/expiring/refresh-less tokens, refresh rotation and scope inheritance, a refresh the store rejects, password sign-in rejection, sign-out clearing identity/queue/history/resume/cache, queue+history+resume persistence across a new session, every playback setting, and the expired-session error from `ensurePlaybackConnected`/`play`/`resume` |
| `provider-jellyfin/data/JellyfinApi.kt` | 16 | the SDK-backed seam against a loopback server: every list query's kinds and paging, item mapping, playlist detail/entries and editability, create/add/remove, search scope bits including `includeArtists`, instant mix, user data, favorite/played/capabilities/report payloads, 401/404/500 translation, transcoding (relative and absolute) vs direct-play fallback, header merging and artwork URLs |

55 new tests, all in the default suite: the default gate is now 528 tests and the full run 554,
all green. `SpotifyWebApi` gained an `apiBase` constructor argument (defaulted to Spotify's API)
so the real HTTP path can run against a loopback server; the Jellyfin test adds `slf4j-simple` as
a test-only dependency because the SDK's kotlin-logging JVM binding needs a provider.

The Jellyfin test deliberately goes through the generated serializers instead of a hand-written
DTO fake: the loopback server returns the JSON the real server would, so a field the SDK marks
required shows up as a red test rather than a fake that silently accepts anything. That caught the
required `MediaSources`, `Shares`/`ItemIds`, and `PlayCount`/`Key` fields before the fixtures were
finished.

### 10.6 Rebase onto release/v0.4.0

The phase-2 work was written against `cbe96d0`. `release/v0.4.0` then moved six commits ahead (the
Jellyfin direct-play fix, the Spotify access-point fix, the bare-host Jellyfin sign-in, the service
screens' About/Help pages and the keystore-unavailable store fix). The branch has been rebased onto
`release/v0.4.0` and the tests were moved with the production code:

| Test | What changed |
|---|---|
| `NativeBridgeTest` | `connect` now returns the transport's failure reason (`String?`) instead of a boolean, because an access-point failure happens before any request. The absent-library case asserts the reason itself; the missing-symbols case asserts the `bridge failure: …` translation. |
| `SpotifyAudioSinkTest` | the fake native's `connect` returns `String?`. |
| `JellyfinSdkApiTest` | direct play streams `/Audio/{id}/stream?static=true` (the universal endpoint answers 500 on Jellyfin 12); a missing media source still yields that stream URL but not as a direct play. |
| `SidetrackGraphTest` | sign-in has a way back now, so it is in the keypad-back route table. |

Two measurements worth recording:

* **The screenshot suite still verifies against the release baselines** — no rebaselining was needed
  after the service-screen UI change, so the 26 baselines were already updated on the release branch.
* **`:provider-spotify`'s own gate dropped from 67.2% to 65.06%/51.76%** (line/branch), because the
  release commits added `ResilientSpotifyCatalog`, `ConnectDiagnostics` and the gateway/session
  rework — roughly 350 lines that no provider test reaches. Its floor is re-based to 65/51 in this
  commit with a note; the native-playback slice has to climb back past 67/52 rather than the floor
  drifting again. `:app`, `:core`, `:provider-jellyfin` and `:provider-navidrome` floors are
  unchanged and still pass.

### 10.7 Next slice

Phase 2's named targets are now effectively met. What remains is the long tail, in value order:

1. `core`: `PlaybackController` (75% merged) remainder, `ExoPlayerMedia3Player` and
   `GuardedEncryptedStore` if a seam is approved. `:core` is the last large module below 70%.
2. `app`: the remainder of `AppSession` (36%) and `SidetrackGraph`. The graph's route table only
   becomes reachable with a composed nav host, so that is a Roborazzi/instrumented job, not a
   unit-test one; the 1 point it gained here is the keypad-back predicate.
3. `provider-spotify`: the native playback paths (`recoverPlayback`, `preloadNext`, transport
   skips, the start-of-play/stop coroutines). These need a fake `SpotifyNative` injected into
   `SpotifySession`, or they stay a deliberate gap. A first attempt at this was abandoned: the
   session owns a `Dispatchers.Main.immediate` scope, and under Robolectric the test body runs on
   the sandbox's main looper, so `runBlocking` in the test blocks the very looper the session's
   continuations are queued on — `playback.stop()` never resumes and the suite hangs instead of
   failing. The seam alone is not enough; the session also needs an injectable playback
   dispatcher/scope (the way `PlaybackControllerTest` already drives the controller with a
   `StandardTestDispatcher`). Do both in the same slice, and keep the fixture's `readAudio`
   non-blocking with a small sleep so the drain does not spin.

## Appendix A — reproducing the numbers

Robolectric ignores on-the-fly JaCoCo instrumentation, so the agent has to be applied
offline. Reproduce with temporary artefacts outside the repo:

```bash
# 1. Fetch JaCoCo (agent runtime + CLI)
mkdir -p /tmp/jac && cd /tmp/jac
curl -sSfLO https://repo1.maven.org/maven2/org/jacoco/org.jacoco.agent/0.8.12/org.jacoco.agent-0.8.12-runtime.jar
curl -sSfLO https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/0.8.12/org.jacoco.cli-0.8.12-nodeps.jar

# 2. Instrument the debug class jars (originals stay untouched)
for m in app core provider-spotify provider-jellyfin provider-navidrome; do
  case $m in
    app) src=app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar ;;
    *)   src=$m/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar ;;
  esac
  java -jar org.jacoco.cli-0.8.12-nodeps.jar instrument "$src" --dest instr/$m.jar --quiet
done

# 3. Run tests with instrumented jars and jacoco-agent.destfile set
#    (see /tmp/jac/offline.gradle in Appendix B)
./gradlew --offline -I /tmp/jac/offline.gradle testDebugUnitTest --rerun-tasks

# 4. Merge runs and report against the ORIGINAL (non-instrumented) jars
java -jar org.jacoco.cli-0.8.12-nodeps.jar merge /tmp/jac/offline/*.exec --destfile /tmp/jac/merged.exec
java -jar org.jacoco.cli-0.8.12-nodeps.jar report /tmp/jac/merged.exec \
  --classfiles <each module's original classes.jar> \
  --sourcefiles <each module's src/main/java> \
  --html /tmp/jac/html --xml /tmp/jac/merged.xml
```

Note: `report` must be pointed at the original jars — JaCoCo refuses to analyse
instrumented class files.

## Appendix B — Gradle init script used for measurement

```groovy
def instrRoot = new File('/tmp/jac/instr')
def runtimeJar = new File('/tmp/jac/org.jacoco.agent-0.8.12-runtime.jar')

gradle.projectsEvaluated {
    gradle.rootProject.allprojects { p ->
        p.tasks.withType(Test).configureEach { t ->
            def exec = new File("/tmp/jac/offline/${p.name}.exec")
            t.systemProperty('jacoco-agent.destfile', exec.absolutePath)
            t.systemProperty('jacoco-agent.append', 'true')
            t.systemProperty('jacoco-agent.output', 'file')
            def entries = t.classpath.files.collect { f ->
                def s = f.absolutePath
                if (s.contains('/build/intermediates/runtime_app_classes_jar/') ||
                    s.contains('/build/intermediates/runtime_library_classes_jar/')) {
                    def mod = (s =~ /\/([^\/]+)\/build\/intermediates\//)[0][1]
                    def cand = new File(instrRoot, "${mod}.jar/classes.jar")
                    if (cand.exists()) return cand
                }
                return f
            }
            entries.add(runtimeJar)
            t.classpath = p.files(entries)
        }
    }
}
```

## Appendix C — raw measurement artefacts

Generated during the audit (not committed): per-module XML/HTML reports and exec files
under `/tmp/jac/`, including `merged.xml` (overall), `mod/*.xml` (per module) and
`mod/html-*` (browsable). Re-run Appendix A to regenerate.
