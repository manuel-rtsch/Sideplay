# Test Suite and Coverage Audit

Status: Phase 2 complete. **Phase 3 (rebalance) is what remains.**
Branch: `test/coverage-audit`. Measured on the debug variant, all modules merged.

The original audit's 391-test / 57% baseline is obsolete; see git history if you need it.
This document is now a work list, not a history.

---

## 1. What remains

### 1.1 Phase 3 — rebalance (highest value)

The suite is broad enough; the next gain is removing work that does not buy risk reduction.

1. **Delete the `:app` Spotify tests that duplicate `:provider-spotify`.**
   About 62 tests / 900 lines still exercise session, queue, fallback and persistence
   behaviour the provider module owns:

   | `:app` test | Tests | Also covered by |
   |---|---:|---|
   | `SpotifyViewModelEventTest` | 32 | `NativeSpotifyGatewayTest`, `SpotifyQueueRepositoryTest` |
   | `SpotifyFallbackStateTest` | 12 | `SpotifySessionCacheTest`, `NativeSpotifyCatalogTest` |
   | `SpotifyPersistenceTest` | 14 | `SpotifyHistoryRepositoryTest`, `SpotifyEpisodeProgressTest` |
   | `SpotifyPaginationTest` | 4 | `NativeSpotifyCatalogTest` |

   Keep the view-model-only assertions; move or delete the rest. Re-measure afterwards:
   product coverage should barely move, and the default suite should get materially faster.

2. **Cap the screenshot suite.** `SidetrackScreenshotTest` is 26 tests and 27 PNGs, and it
   carries ~12 points of line coverage. Keep one baseline per screen state that semantics
   tests cannot express; drop variants that only re-render a covered state. It stays
   opt-in (`-PsidetrackScreenshots`), never part of the default loop.

3. **Create a shared test-fixtures source set/module.**
   `MediaKeyFixtures`, `FakeHttpStream`, `RecordingServiceRuntime`, `Previews` and the
   Spotify fakes are still app-local or copied. Android `testFixtures` on `:core` is the
   smallest option. This unblocks moving the remaining `:app` playback tests to `:core`
   and removes the direct `MediaKey(ServiceId.Spotify, …)` workaround in the moved tests.

4. **Re-measure and ratchet.** After the deletions, measured coverage may dip a little
   because the screenshot suite shrinks. Re-baseline the floors once, then raise them
   again; do not lower a floor to absorb duplicate tests.

### 1.2 `app` gaps

* `AppSession`'s remaining ~45 lines are mostly the `GuardedEncryptedStore`-backed
  `ActiveServiceStore` and `AppSessions.create`/`build` failure branches. The store cannot
  be read back under Robolectric (the guard swallows the write). Either add a factory seam
  for the store/runtime constructors or accept the gap; the persistence boundary is already
  pinned by `CredentialStoreGuardTest` and `StoreFailureTest`.
* `SidetrackGraph` needs a composed nav host. That is Roborazzi or instrumented work, not
  more unit tests.
* `QueueReorder`'s slot arithmetic (`firstOnScreen`/`lastOnScreen`, auto-scroll) needs a
  laid-out list. Same category: instrumented/Roborazzi.
* `PlaybackService` is unit-tested at 0% → high coverage through `onStartCommand`; an
  instrumented test is still worth adding for the real foreground-service lifecycle.

### 1.3 `core` — accepted gaps and one decision

* `ExoPlayerMedia3Player` (0%) is **accepted**. It builds a real `ExoPlayer`; Robolectric
  cannot buffer audio, so a unit test would only assert listener translation against a
  mock. Cover it with an instrumented test, or add a `Media3Player` factory seam only when
  a slice actually needs it.
* `GuardedEncryptedStore` (0%) is **accepted** for the same reason as the app store: it
  constructs `EncryptedFileStore` in its initializer. The same contract is pinned through
  the injectable `JsonStore`.
* **Decision needed:** `PlaybackController.resume()` activates the media-session owner
  before asking the engine whether audio started. If the engine reports failure, `playing`
  is cleared but `registry.active` still points at the controller. Slice 6 pinned the
  audible state and deliberately did not encode either answer for the registry. Decide
  whether this is a bug; if yes, fix it and assert the registry is cleared.
* `ServiceRuntime`'s optional-interface extensions are shallow coverage. They are fine as
  neutral-default regression insurance; the real runtimes are covered.

### 1.4 `provider-spotify` gaps

* `recoverPlayback` (the stall/reconnect path) is the remaining native branch in
  `SpotifySession`. It needs a fake native that can report a dead drain and a session that
  can reconnect.
* `applyAudioSettings`' deferred rebuild is now pinned in
  `LibrespotPlaybackEngine.reconfigurePending`; no session-level test is needed.
* `SpotifySession` is still below the provider average. Add only native-lifecycle cases
  that the engine tests cannot express.

### 1.5 `provider-jellyfin` / `provider-navidrome`

Both are at or near the module target (91.18% and 68.36% line). No planned work beyond
ratcheting the floors if a future change moves them.

---

## 2. Current state

| Gate | Value |
|---|---|
| Default suite (`./gradlew testDebugUnitTest`) | **599 tests** |
| Full suite (`-PsidetrackScreenshots`) | **625 tests** |
| Product line / branch, default | **59.51% / 40.17%** |
| Product line / branch, full | **71.87% / 46.31%** |
| Phase-2 target (full suite) | **70% line / 45% branch — met** |

Module coverage (own tests, unless noted):

| Module | Line | Branch |
|---|---:|---:|
| `:core` | 64.02% | 50.69% |
| `:provider-spotify` | 77.74% | 61.24% |
| `:provider-jellyfin` | 91.18% | 60.55% |
| `:provider-navidrome` | 68.36% | 41.61% |
| `:app` / product merged, default | 59.51% | 40.17% |
| `:app` / product merged, full | 71.87% | 46.31% |

Floors are a ratchet and track the suite that ran:

| Gate | Line | Branch |
|---|---:|---:|
| `:app` product, default | 58 | 39 |
| `:app` product, full | 71 | 45 |
| `:core` | 63 | 49 |
| `:provider-spotify` | 76 | 60 |
| `:provider-jellyfin` | 91 | 60 |
| `:provider-navidrome` | 68 | 41 |

The full suite's extra coverage comes almost entirely from the screenshot tests; that is
why the default and full product floors differ. The floor is the gate, not the target.

---

## 3. What is already done (compressed)

Phase 1 (infrastructure):

* Kover on the debug variant, `:app` as the merging module, `./gradlew coverage` as the
  entry point; generated code filtered.
* `:core` has a test source set; pure logic moved out of Robolectric.
* Default vs screenshot vs live-integration suites split by opt-in flags.
* Screenshot tests verify by default; recording is explicit.
* Per-module and product-wide floors added.

Phase 2 (coverage, slices 1–6):

* `:core`: audio focus, the runtime seams, queue reorder, and the `PlaybackController`
  session contract (new `PlaybackControllerSessionTest`, 32 tests, fake engine).
* `:app`: `PlaybackService`, `AppSession`, the reachable `SidetrackGraph` route table and
  notification intent, `QueueReorder`.
* Providers: `JellyfinRuntime`, `NavidromeRuntime`, token client, audio sink, `NativeBridge`,
  `SpotifyWebApi`, `SpotifySession`, the Jellyfin SDK seam, and the native playback paths.
* Slice 6 added six `LibrespotPlaybackEngine` tests for deferred player rebuilds, moved
  `PlaybackControllerTest` and `LibrespotPlaybackEngineTest` to `:provider-spotify`, and
  added the core controller session suite.
* Production-neutral seams added along the way: `SpotifySession(native, playbackContext)`,
  `SpotifyTokenClient(tokenUrl)`, `SpotifyWebApi(apiBase)`, `PlaybackController` clock and
  notification hooks.

Known test-shape issues already recorded here and still open in Phase 3: the duplicated
`:app` Spotify suites, the screenshot suite size, and the unconsolidated fixtures.

---

## 4. Definition of done for Phase 3

* No provider-owned behaviour is tested only through `:app`.
* Screenshot baselines cover states, not screen variants.
* Shared fixtures live in one place; no copied `FakeHttpStream`/`MediaKey` helpers.
* Default suite is faster and smaller without losing product coverage.
* Floors are re-measured and raised; `coverage` and `coverage -PsidetrackScreenshots` pass.
* Accepted gaps (`ExoPlayerMedia3Player`, the encrypted stores) are either covered by
  instrumented tests or explicitly left out of scope.

After that, stop adding unit tests unless a real defect creates a new risk area. The
remaining value is in instrumented coverage of the playback service and the store/keystore
boundary, not in more line coverage.

---

## 5. Measurement notes

* Coverage is measured with Kover (see `docs/testing.md`); `./gradlew coverage` runs the
  default gate, `./gradlew coverage -PsidetrackScreenshots` the full gate.
* Reports land in `app/build/reports/kover/` and per module under
  `<module>/build/reports/kover/`.
* Only `src/main` product code is counted. Generated code, `BuildConfig`, vendored Rust,
  Maestro flows and instrumentation tests are out of scope.
* The old JaCoCo offline-instrumentation recipe was removed with this rewrite; Kover is the
  source of truth now.
