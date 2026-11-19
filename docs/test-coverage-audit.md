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
