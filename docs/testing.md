# Testing

Sidetrack's unit tests run on the JVM through Robolectric and Roborazzi. The suites are split by
cost and by what they need, so the default loop stays fast and hermetic and the slow or
environment-dependent suites are explicit opt-ins.

## Running the suites

| Command | What runs | Needs |
|---|---|---|
| `./gradlew testDebugUnitTest` | the default gate: every unit test except screenshots and live-server integration | nothing |
| `./gradlew coverage` | the default gate plus every coverage floor | nothing |
| `./gradlew testDebugUnitTest -PsidetrackScreenshots` | adds the 26 Roborazzi baseline **verifications** | nothing |
| `./gradlew testDebugUnitTest -PsidetrackScreenshotsRecord` | rewrites the baseline PNGs instead of verifying | nothing |
| `./gradlew testDebugUnitTest -PsidetrackIntegration` | adds the live-server tests (`*IntegrationTest`) | reachable Navidrome/Jellyfin |

Screenshot tests verify by default. Recording is opt-in (`-PsidetrackScreenshotsRecord`) because a
suite that records on every run can never fail, which is how `*ScreenshotTest` behaved before this
was made explicit.

`*IntegrationTest` classes used to `Assume`-skip themselves, so they reported green while asserting
nothing. They are now filtered out of the default run and only execute when `-PsidetrackIntegration`
is passed.

## Where tests live

| Module | Scope | Notable suites |
|---|---|---|
| `:core` | service-agnostic models, persistence, queue/history/progress repositories, playback controller, sleep timer, drain policy | `QueueRepositoryTest`, `HistoryRepositoryTest`, `StorePersistenceTest`, `DrainPolicyTest`, `SleepTimerTest`, `PlaybackControllerSessionTest` |
| `:app` | Compose screens, view model, navigation, service glue | `SidetrackViewModel` suites, row/screen semantics, `Media3PlaybackEngineTest`, `SidetrackScreenshotTest` |
| `:provider-spotify` | Spotify auth, Web API mapping, native catalog/gateway, librespot engine and the controller it builds | `NativeSpotifyCatalogTest`, `SpotifyAuthTest`, `LibrespotPlaybackEngineTest`, `PlaybackControllerTest` |
| `:provider-jellyfin` | Jellyfin auth, API mapping, catalog, playback reporting | `JellyfinCatalogTest`, `JellyfinAuthTest` |
| `:provider-subsonic` | Subsonic auth, API mapping, family entries | `SubsonicApiTest`, `SubsonicRuntimeTest` |

Pure logic lives in `:core` and is tested there: no Compose rule, no Robolectric where avoidable, so
those tests run in seconds instead of minutes.

## Coverage

Coverage is measured with [Kover](https://kotlin.github.io/kotlinx-kover/gradle-plugin/) on the
`debug` variant. `:app` is the merging module, so `:app:koverVerifyDebug` measures **all** modules
together; the other modules verify their own tests against their own code.

```bash
./gradlew coverage                      # fast-suite gate
./gradlew coverage -PsidetrackScreenshots   # full-suite gate, higher floors
```

Reports land in `app/build/reports/kover/` (`report.xml`, `html/index.html`) and per module under
`<module>/build/reports/kover/`.

Floors are a **ratchet**: the values in the build files are the levels measured when Kover was
introduced (see `docs/test-coverage-audit.md`), and they only ever move up. The bound changes with
the suite that ran, because the screenshot tests contribute roughly twelve points of line coverage:

| Suite | Line floor | Branch floor |
|---|---|---|
| default (no screenshots) | 58 | 39 |
| full (`-PsidetrackScreenshots`) | 71 | 45 |

Measured after the controller/engine slice: 59.51% line / 40.17% branch on the default suite, 71.87% /
46.31% on the full suite — the phase-2 targets (70/45) are met with the screenshots included.

`:provider-spotify`'s own floor is 76/60: the module measures 77.74% line / 61.24% branch after the
controller and engine suites moved next to the engine. `:core` measures 64.02% / 50.69% and its
floor is 63/49. The other provider floors are unchanged.

Phase 2 targets are **70% line / 45% branch** with the full suite.

## Adding a test

* Pure logic goes in `:core` — it is the cheapest place to run it.
* A test that needs `ApplicationProvider`, `Context` or Compose belongs in the module that owns the
  code, not in `:app`, unless it exercises the app's own screens or view model.
* A test that needs a live server must be named `*IntegrationTest` and assume-guard itself.
* A test that captures pixels must be named `*ScreenshotTest`, so it lands in the opt-in suite.
