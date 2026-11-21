# Provider decoupling — assessment and plan

Status: **done 2026-11-19.** The module split landed in stages A–F; the deviations from the plan are
listed in §8. This document records how far the three providers are detached from the core, the
target module graph, and the staged plan that got there without changing behaviour.

Related: [provider-capabilities.md](provider-capabilities.md), [jellyfin-integration.md](jellyfin-integration.md).

---

## 1. Findings

### 1.1 Logical separation is strong

The provider contracts already exist and are used consistently:

- `data/MusicService.kt` — `MediaCatalog`, `MediaLibraryWriter`, `ServiceAuth`,
  `MusicServiceProvider`, `AuthInput`, `ServiceAccount`, `ServiceFailure`.
- `data/ServiceRuntime.kt` — the single contract the UI sees, plus optional mix-ins
  (`NativePlaybackRuntime`, `QualitySettingsRuntime`, `CacheRuntime`) reached through `as?`
  extensions, so a provider without a feature stubs nothing.
- `MediaModels.kt` — `ServiceId`, `MediaKey`, `ServiceCapabilities`; the UI gates categories and
  search scopes on capabilities (`availableLibraryFilters`, `availableSearchScopes`) rather than on
  a provider id.
- Shared playback — `playback/PlaybackController.kt`, `Media3PlaybackEngine.kt`,
  `ExoPlayerMedia3Player.kt` (already takes per-request headers) serve every non-Spotify service.
- Isolated persistence — per-provider store names (`sidetracks_settings_navidrome`,
  `sidetracks-jellyfin-queue.bin`, …), one encrypted store per provider.
- No shared file imports a provider internal: `SpotifySession`, `SubsonicApi`,
  `LibrespotPlaybackEngine`, `NativeBridge` are referenced only from `data/AppSession.kt` and their
  own adapters.

### 1.2 Physical separation is absent

`settings.gradle.kts` includes exactly one module: `:app`. Everything compiles into one source set,
so nothing stops the UI, the ViewModel or another provider from importing a provider internal. The
only enforced boundary in the repository is the Rust artifact `libsidetrack_spotify.so`.

Size, `app/src/main` (20,817 Kotlin lines):

| Area | Lines |
| --- | --- |
| Spotify (runtime, API, catalogs, auth, sink, librespot engine, bridge) | ~2,900 |
| Navidrome (API, runtime, auth, demo) | ~851 |
| Jellyfin (auth only so far) | 264 |
| Provider code total | ~4,560 (22%) |

### 1.3 Concrete leaks

| # | Leak | Where | Why it hurts |
| --- | --- | --- | --- |
| L1 | Provider identity decides the sign-in form | `SidetrackGraph.kt:876` — `appSession.provider?.id == ServiceId.Navidrome` | Jellyfin also signs in with username/password, so this becomes a second id branch; the UI is asking "which service?" instead of "what do you need?" |
| L2 | Provider demo data inside a shared screen | `SidetrackGraph.kt:885–891` injects `NavidromeDemo.SERVER_URL/USERNAME/PASSWORD` | Navidrome constants in provider-neutral UI |
| L3 | Registry by `when` | `data/AppSession.kt:169–191`, `KnownServices` list | Expected, but every provider adds an edit in shared code; acceptable after modules, since `:app` is the only place allowed to know all providers |
| L4 | Demo layer is Spotify-typed | `DemoContent.catalog(): SpotifyCatalog`; `SpotifySession` reads `DemoContentSource` | Demo builds only; wrong shape once another provider wants demo content |
| L5 | Naming debt | `SpotifySession` is the Spotify *runtime*; `Track.uri` is a generic stream ref; `SpotifyCatalog` is a provider contract used by neutral adapters | Makes the seam look worse than it is; renaming is optional and out of scope here |

Everything else that mentions a provider is either a `ServiceId` constant, a capability set, a
comment, or the About screen's licence list.

---

## 2. Target module graph

```
:app                  UI, navigation, ViewModel, picker, AppSession registry
 ├── :core            domain model, provider contracts, shared repositories, playback engines
 ├── :provider-spotify   ─┐
 ├── :provider-subsonic ─┼─► :core   (never the reverse)
 └── :provider-jellyfin  ─┘
```

Rules to enforce:

1. `:core` must not depend on, reference or name any provider except the `ServiceId` constants and
   the `ServiceCapabilities` sets, which are neutral identity, not implementation.
2. A provider module depends only on `:core` (and third-party libraries it needs).
3. Only `:app` knows every provider: `KnownServices`, `AppSession.build`'s `when`, and the demo
   build's content source.
4. Package names stay as they are — a module is a build boundary, not a package rename. Files move,
   imports do not change, so review stays readable.

---

## 3. File mapping

| Module | Moves in |
| --- | --- |
| `:core` | `MediaModels.kt`, `MediaItems.kt`, `DesignCompat.kt` (if UI-free), `data/{MusicService, ServiceRuntime, QueueRepository, HistoryRepository, PlaybackSettings, StateStores, ConflatedSaver, HaltStopGuard, NetworkMonitor, ArtworkAccess, EpisodeMeta, EpisodeProgress}.kt`, `storage/`, `playback/{PlaybackController, PlaybackEngine, Media3PlaybackEngine, ExoPlayerMedia3Player, MediaFormat, DrainPolicy, SleepTimer}.kt`, `auth/CredentialPersistenceException.kt`, `ui/` helpers that core types need |
| `:provider-spotify` | `data/{SpotifySession, SpotifyCatalog, SpotifyWebApi, SpotifyWebCatalog, NativeSpotifyCatalog, NativeSpotifyGateway, SpotifyGateway, SpotifyAdapters, DemoContent}.kt`, `auth/{SpotifyAuth, SpotifyCredentials, SpotifyPkce, SpotifyTokenClient, EncryptedSpotifyCredentialStore}.kt`, `playback/{LibrespotPlaybackEngine, SpotifyAudioSink}.kt`, `bridge/` |
| `:provider-subsonic` | `data/{SubsonicApi, SubsonicRuntime, NavidromeDemo}.kt`, `auth/SubsonicAuth.kt` |
| `:provider-jellyfin` | `auth/JellyfinAuth.kt`, then `data/{JellyfinApi, JellyfinCatalog, JellyfinPlaybackReporter, JellyfinRuntime}.kt` (the adapter landed after the split) |
| `:app` (stays) | every `Sidetrack*.kt` screen/activity/viewmodel, `data/AppSession.kt`, `Components.kt`, `TrackRow.kt`, `LibraryScreens.kt`, `NowPlaying*.kt`, `SearchScreen.kt`, `SettingsScreens.kt`, `Podcast*.kt`, `SidetrackViewModel.kt`, `MediaSessions.kt`/`PlaybackService.kt`/`AudioFocus.kt` if they need app-level Android components |

Tests move with their subject: `SubsonicApiTest`, `SubsonicIntegrationTest` →
`:provider-subsonic`; `Spotify*Test`, `NativeSpotify*Test`, `MockSpotifyDataSource` →
`:provider-spotify`; `JellyfinAuthTest` → `:provider-jellyfin`; shared/core tests stay in `:app`
until `:core` has its own test source, whichever is less churn per file.

Packaging detail: the `.so` files stay packaged by `:app` (the `buildSpotifyNative` task and the
`jniLibs` source directory do **not** move).

---

## 4. Sign-in form capability (do first, smallest and highest value)

Replace L1/L2 with a capability:

```kotlin
/** What the sign-in screen must offer for this service. */
sealed interface SignInKind {
    /** OAuth/custom tab, no fields. */
    data object Browser : SignInKind

    /** Server address, username and password. [defaults] pre-fills a public demo, if any. */
    data class Password(val defaults: PasswordDefaults? = null) : SignInKind
}

data class PasswordDefaults(val serverUrl: String, val username: String, val password: String)
```

- `MusicServiceProvider` gains `val signInKind: SignInKind`.
- `SpotifyProvider` → `SignInKind.Browser`.
- `SubsonicProvider` → `SignInKind.Password(PasswordDefaults(NavidromeDemo.SERVER_URL, …))`, so the
  demo constants leave the UI.
- `SidetrackGraph` asks `appSession.provider?.signInKind` and passes `passwordAuth`/`defaults` from
  it; no provider id remains in the screen.
- `PasswordSignInDefaults`/`passwordSignInValues` either consume `PasswordDefaults` or are replaced
  by it — keep the existing `PasswordSignInValuesTest` behaviour intact.

Acceptance: `grep -rn "ServiceId\.\(Spotify\|Navidrome\|Jellyfin\)" app/src/main` returns only the
`AppSession` registry (and provider-internal guards); Navidrome still shows the prefilled form;
Spotify still opens the browser path.

---

## 5. Staged execution

Each stage ends with a green `./gradlew :app:testDebugUnitTest :app:assembleDebug` (and
`spotlessCheck`) plus one commit. If a stage fails twice, revert it and report instead of thrashing.

| Stage | Work |
| --- | --- |
| A | Sign-in capability (§4). Commit. |
| B | Create `:core` (`build.gradle.kts`, `settings.gradle.kts`, AGP library plugin), move shared files, make the core's public surface explicit (fix `internal` visibility across the new boundary), `:app` depends on `:core`. Commit. |
| C | Create `:provider-subsonic`, move its files and tests. Commit. |
| D | Create `:provider-jellyfin`, move `JellyfinAuth` and its test. Commit. |
| E | Create `:provider-spotify`, move its files/tests plus `bridge/`. Commit. |
| F | Boundary check and docs: `rg "Spotify\|Navidrome\|Jellyfin" core/src/main` must show only `ServiceId`/capability references; update the paths in `docs/provider-capabilities.md` and `docs/jellyfin-integration.md` §5; final build. Commit. |

Notes for each stage:

- Keep `ServiceCapabilities` values, copy and behaviour identical; this is a move, not a redesign.
- `DemoContent` is looked up reflectively by package name — if it moves, its `PACKAGE` constant and
  the app's `demo` source set must agree, or the demo build silently loses its content. Verify with
  `assembleDemo`.
- `internal` members used across the new boundary become `public` with a short KDoc note, or the
  consumer is moved into the same module. Prefer the latter.
- Jellyfin still builds against Sidekit `1.9.0` from `~/.m2` (see `gradle/libs.versions.toml`); a
  clean machine needs that publication first.
- Do not touch the Sidekit repository, and do not rename packages.

---

## 6. Acceptance criteria

1. `settings.gradle.kts` includes `:app`, `:core`, `:provider-spotify`, `:provider-subsonic`,
   `:provider-jellyfin`; the provider graph is `app → providers → core`.
2. `:core` has no dependency on a provider module and no provider implementation reference in
   source, only `ServiceId`/`ServiceCapabilities`/`SignInKind`.
3. No provider id comparison exists outside provider code and the `AppSession` registry.
4. Navidrome and Spotify behave exactly as before: same sign-in screens, same capability rows, same
   playback.
5. `./gradlew :app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDemo` and `spotlessCheck`
   are green; unit-test count does not drop (369 before the split, plus any moved tests).
6. Docs list the new layout; `docs/jellyfin-integration.md` §5 file paths point at
   `:provider-jellyfin` for `JellyfinAuth`.

## 7. Risks

- **Visibility churn** is the real cost: `internal` types (`SidetrackDebug`, demo lookup,
  test fixtures) must either become public API or move with their consumers.
- **Compose/Android resources**: core UI helpers that use Sidekit composables keep their
  dependencies; if a helper drags the whole UI into `:core`, leave it in `:app` instead — the goal is
  that `:core` cannot see providers, not that `:core` is tiny.
- **Native packaging** must stay in `:app`, or the Spotify build silently ships without
  `libsidetrack_spotify.so`.
- **Demo build**: reflective `DemoContent` lookup is easy to break by moving the fixture package.
- **Test placement**: a test that needs both a provider and `KnownServices` belongs in `:app`.


---

## 8. What actually landed

Graph is now `:app → :core`, `:provider-spotify`, `:provider-subsonic`, `:provider-jellyfin`, with
every provider depending only on `:core`.

Commits: `6032e03` (sign-in capability), `f00f5bd` (`:core`), `f18665a` (Navidrome),
`8fb8a44` (Jellyfin), `133a932` (Spotify), plus this documentation pass.

Deviations from the plan, all forced by the module boundary and recorded here so the next reader
does not "fix" them back:

- **`DemoContent` split.** The interface moved to `:provider-spotify`; the reflective
  `DemoContentSource` (which reads `BuildConfig.DEMO_CONTENT`/`DEMO_PACK`) stayed in `:app` and is
  injected into `SpotifySession(demo = …)`. A library module cannot read the app's build-type
  fields, and a provider should not reach for the app's classes.
- **`SpotifyAuth` takes the redirect scheme.** `BuildConfig.AUTH_SCHEME` is a build-type value
  (`sidetrack` vs `sidetrack-demo`), so it is a constructor argument the app supplies.
- **Three Spotify tests stayed in `:app`:** `SpotifyViewModelEventTest` and
  `SpotifyFallbackStateTest` drive the ViewModel/`AppSessions`, and `SpotifyPaginationTest` uses
  `MockSpotifyDataSource`, which depends on app resources (`R`).
- **`SpotifyIdNavigationTest` lost one assertion.** `NavType.StringType.parseValue` is AndroidX
  behaviour; the JSON-null mapping it also covered stays tested in the provider module.
- Native packaging (`buildSpotifyNative`, `jniLibs`, `src/demo` fixtures) never moved, as planned.

Verification at the time of the split: `:app:testDebugUnitTest` (231), `:provider-spotify` (107),
`:provider-subsonic` (18), `:provider-jellyfin` (12) — 368 total, 0 failures;
`:app:assembleDebug`, `:app:assembleDemo` and `spotlessCheck` green.

The Jellyfin adapter was completed after this split: catalog, writer, runtime, playback reporting
and picker registration now live in `:provider-jellyfin`, and `KnownServices` lists all three
providers. See [provider-capabilities.md](provider-capabilities.md) and
[jellyfin-integration.md](jellyfin-integration.md).
