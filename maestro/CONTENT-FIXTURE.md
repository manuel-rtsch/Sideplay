# Demo content fixture: what the flows need, and where it should come from

The four promo recordings and the four suite flows were made against one personal Spotify
account. This document is the inventory of what the flows actually consume, the constraints each
resource has, the content that is still hardcoded, and the options for a **neutral** base.

Everything below is derived from the flows in `flows/` and the app source
(`SpotifyViewModel`, `SpotifyLibraryScreens`, `SpotifySearchScreen`, `SpotifyQueueRepository`,
`SpotifyHistoryRepository`). Re-check the counts after a flow change.

- [1. Where the content comes from](#1-where-the-content-comes-from)
- [2. Resource manifest](#2-resource-manifest)
- [3. Per-flow dependencies](#3-per-flow-dependencies)
- [4. Non-obvious constraints](#4-non-obvious-constraints)
- [5. Hardcoded content today](#5-hardcoded-content-today)
- [6. Gaps in the current recipe](#6-gaps-in-the-current-recipe)
- [7. Options](#7-options)
- [8. Recommendation](#8-recommendation)
- [9. What was built](#9-what-was-built)
- [10. Recording with the demo build](#10-recording-with-the-demo-build)
- [11. Verification checklist](#11-verification-checklist)

## 1. Where the content comes from

Three different stores feed the screens, and only the first one is Spotify-side:

| Source | What it feeds | Where it lives | Seeded how |
| --- | --- | --- | --- |
| Spotify catalog (`SpotifyCatalog` → librespot / Web API) | Library sections, artist page, collections, search, artwork | Spotify account | save, follow, create, upload |
| Local queue (`sidetracks-queue.bin`) | Home **NOW PLAYING**, Queue screen | device | play, Add to queue |
| Local history (`sidetracks-history.bin`) | Home **RECENTLY PLAYED**, History screen | device | play |

Two consequences matter for a neutral base:

- **Recently played is not Spotify's history.** The Home preview and the History screen come from
  the local file only. Nothing an account "has listened to" on Spotify shows up.
- **The queue is not Spotify's queue either.** It is local, is rebuilt every time `play()` hands
  over a list (artist top tracks, album track list, show episodes), and is extended only by
  Add to queue.

So an account only has to supply the *library and search* content. History and queue come from
what the flows themselves play, which is why the flows are order-dependent (see §6).

The `demo` build (§9) replaces the first row entirely: library, collections, artist pages and
search come from `app/src/demo/`, and the account is only used for authentication and playback.
The other two rows stay local and are rewritten at every process start.

## 2. Resource manifest

"Min" is the smallest set that passes the checked-in flows. "Recommended" is what makes the promo
look complete (five rows per Library category before the **See more** row, five recent rows before
**See more history**) and survives repeated runs without a reseed.

| # | Resource | Min | Recommended | Consumed by | Constraints |
| --- | --- | --- | --- | --- | --- |
| 1 | Spotify account | 1 | 1 | every flow | signed in, online. Real playback (librespot) needs **Premium**; a free account loads no audio and the transport flows fail |
| 2 | Liked songs (saved tracks) | **1** | 10–12 | `01`, `03` | `01` scrolls up to the **LIKED SONGS** heading as its reset anchor; the heading is omitted when the section is empty, so zero liked songs breaks `01` even if everything else is right |
| 3 | Followed artists | **2** | 6 | `01` | the second `Artist` row is opened. Every followed artist must have **≥ 3 top tracks** and **≥ 1 album with ≥ 1 track**, because the order is Spotify's `added_at` (newest first) and can put any of them second |
| 4 | Artist discography | 1 album / artist | 2–3 albums, 8–10 tracks | `01` | the album row is matched by `[0-9]{4}( · [0-9]+ tracks)?`, so the subtitle must contain the 4-digit year; the first album track is then played |
| 5 | Artist top tracks | **3** | 8+ | `01`, autoplay | `01` taps duration index `2`; only 5 are fetched per page (`SPOTIFY_ARTIST_PAGE_SIZE`) |
| 6 | Saved albums | 0 | 6 | Library visuals only | no flow opens the Library Albums category; 6+ makes **See more albums** appear |
| 7 | Saved playlists | 0 | 6 | Library visuals only | no flow opens a playlist collection; 6+ makes **See more playlists** appear |
| 8 | Saved shows | 0 | 6 | Library visuals only | 6+ makes **See more shows** appear |
| 9 | Search results for the pinned query | 1 track, 1 artist, 1 album, 1 show, 1 show episode | 2–5 per section | `02`, `setQuery` | each section renders **only when non-empty** and the flows tap the first result below each heading. Playlists are optional (no flow needs them). Only 5 results per section are fetched per page |
| 10 | Show episodes | 1 playable, ≥ 1 h | 3–5 per show | `02` promo | the first episode is played. Episodes have no Save/Seek, so the transport flow needs a *track* current, never an episode. Duration must render `h:mm:ss` (≥ 1 h) or the track-only `m:ss` selectors can pick an episode row |
| 11 | Local history | **6 distinct** | 8–9 | `04`, `03`, `01` | **See more history** needs `recent.size > 5`; History dedupes by item id; `04` removes one entry per run |
| 12 | Upcoming queue | **2** | 3 | `03` | `03` adds two recent rows, then swipes the row at index 1; `Clear` needs a non-empty upcoming list |
| 13 | Current item | 1 | 1 | all | must be a **track**: `03`/Now Playing need Save, Seek and Repeat, which an episode does not have |
| 14 | Distinct track ids | 6 | 10–12 | history seeding | history dedupes by id, so six *different* items are needed for **See more history** |
| 15 | Artwork | 1 per album, playlist, artist, show | distinct covers | every screen | official covers bundled in `app/src/demo/res/drawable-nodpi/` (6 albums); the generated `app/src/debug/res` covers remain for playlists and shows |
| 16 | Accent colour | 1 per recording | Lime, Orange, Blue, Pink | `set-accent.yaml` | Sidekit host setting, not Spotify |
| 17 | UI language / keypad mode | English, `Abc` | — | all | flows match English labels; T9 multi-tap needs `Abc`, not `123` |

Minimum viable pack: **2 followed artists (3+ top tracks, 1+ album each), 1 liked song, 1 artist
+ 1 album + 1 track + 1 show (with a ≥ 1 h episode) in search, 6 distinct playable tracks, 2
upcoming queue items.**

## 3. Per-flow dependencies

Suite order (`config.yaml`) is `01 → 02 → 03 → 04` on the same device, from the documented fixture.

| Flow | Catalog needed | Local state needed | Adds | Ends on |
| --- | --- | --- | --- | --- |
| `01-browse-play` | Liked Songs ≥ 1, followed artists ≥ 2, #2 has ≥ 3 top tracks and an album with a track | current track | 2 history entries, replaces the queue with album tracks | Now Playing |
| `02-search-library-writes` | search hits: track, artist (Follow state), album (Save state), show (Save state) | signed in, on Home | 1 history entry (episode), queue = show episodes | Search results |
| `03-queue-transport` | none | ≥ 2 recent **track** rows, a **track** current, ≥ 2 upcoming after two adds | 0–2 distinct history entries, transport toggles save state | Now Playing |
| `04-history-settings` | none | ≥ 6 distinct history entries | −1 history entry | Home |

Promo recording chain (`record.sh`, per `README.md`): `01` with prep, then `02`, `03`, `04` with
`SIDEPLAY_SKIP_PREPARE=1`. The prep clears history and starts one known track, so the chain must
accumulate six distinct entries *before* `04` records. It currently does not (see §6).

## 4. Non-obvious constraints

- **Ordering is server-side.** Liked songs, saved albums, artists and shows are ordered by
  Spotify's `added_at`, newest first; playlists use the rootlist order; search results use
  Spotify's ranking. Only search *input* (the query) is pinned. "The second followed artist" is
  therefore "the artist followed second-most-recently", not a title.
- **Every followed artist is a candidate** for "second followed artist". A pack with one rich
  artist and five thin ones is not a valid fixture.
- **Headings are conditional.** Library category headings, search section headings and
  **See more** rows exist only when the data behind them is non-empty / larger than a page.
  Anchoring a scroll on a heading silently depends on that section having content.
- **Duration format splits tracks from episodes.** `formatDuration` renders `m:ss` under an hour
  and `h:mm:ss` above it. `03` uses the `m:ss`-only selector to skip episodes on Home. Episodes in
  the fixture must be ≥ 1 h for that assumption to hold.
- **Search input is T9 multi-tap, not text.** Maestro's `inputText` becomes key events, so the
  query is a digit string that the keypad turns back into letters. Adjacent letters on the same
  key are ambiguous (that is why `africa` is avoided: `c`+`a` = `2222`). Pick query words whose
  digit runs never repeat a key across a letter boundary:
  `bowie` → `22666944433`, `toto` → `86668666`, `aurora` → `2887776667772`,
  `amber` → `262233777`.
- **Search result sections are independent.** A query can hit tracks but no show, and then the
  `SHOWS` heading never appears and `02` scrolls until it times out.
- **Writes are real.** In account mode, `02` follows the first artist result and toggles Save on
  the first album and the first show. With a personal account every demo run edits the real
  library with whatever search happened to return first (both are toggled back on the next run,
  but the account is touched).
- **Save/follow state starts unknown.** The flows read the current label and flip it, so either
  state is acceptable — but the write must succeed for the label to change.
- **Now-playing notices are transient** (4 s confirmation, 5 s queue Undo); flows avoid racing
  them by using Options instead of the notice where possible.

## 5. Hardcoded content today

| File | Content | Replacement |
| --- | --- | --- |
| `flows/utility/record-prepare.yaml` | query `toto` (`86668666`), tap and assert **Africa** (TOTO) | account mode only; the demo build uses `record-prepare-demo.yaml`, which starts no track |
| `flows/demo/hero-play-search-queue.yaml` | same `toto` / **Africa** sequence | account mode only; not part of the four promo videos |
| `flows/suite/02-…` and `flows/promo/02-…` | query `bowie` (`22666944433`), first artist / first album / first show | **kept**, and both demo packs match it: the real pack has `David Bowie` and the show `Bowie: A Life`, the backup pack `Bowie Vale` and `Bowie Vale Sessions` |
| `flows/promo/01-…`, `flows/promo/03-…` | comments mention **Africa** only | comments only |

Everything else is content-agnostic: duration regexes, `index`, generated labels
(`Add .* to queue`, `Remove .* from history`, `Playing .* Open Now Playing`), and chrome labels.

## 6. Gaps in the current recipe

Items 1–4 and 6 are what the demo build in §9 removes. Item 5 only exists for account-based
recordings.

1. **The promoted history count does not add up.** The chain can add at most **5** distinct
   entries: 1 from the prep, 2 from `01`, 1 episode from `02`, and 1 more from `03`'s `Next`.
   `04` needs **> 5**, so the checked-in `04` recording must have relied on history left over from
   an earlier session. `README.md` says "Play six distinct tracks from Liked Songs", describing a
   step that is no longer in the checked-in `03`.
2. **`03` also depends on what `02` left playing.** `02` ends with the queue set to the show's
   episodes and an episode current. The promo `03` dropped the suite's "make a track current"
   step, but still asserts `Sav(e|ed)` after `Next` — which only holds if a *track* context was
   seeded between `02` and `03`. The seed step is therefore needed before `03`, not just before
   `04`.
3. **`record-prepare.yaml` pins a copyrighted track** and its artist, so the prep cannot be reused
   with neutral content as-is.
4. **The pinned query returns whatever Spotify ranks first**, which may be unrelated to the rest
   of the library in the video. The demo packs remove this entirely.
5. **`02` writes to the account** (see §4). This is harmless on a throwaway account and
   inappropriate on a personal one.
6. **There is no `flows/utility/seed-fixture.yaml`.** `FLOW-SUITE-TODO.md` still lists it as a
   TODO; the fixture is manual and documented only. One explicit seed flow — play N distinct
   fixture tracks from Liked Songs, add two to the queue, return Home — would fix items 1 and 2
   and make the whole recording chain independent of leftover state.

## 7. Options

| | A. Seed the personal profile | B. Dedicated demo account | C. Demo fixture in the app |
| --- | --- | --- | --- |
| Code changes | none | none | **one catalog + writer implementation, one wiring seam**, kept out of release |
| Account | existing Premium | **second Premium subscription** (or sharing) | existing account for auth only, or none with simulated playback |
| On-screen content | curated, but search shows real Spotify ranking | curated, same search problem | **fully controlled**, fictional, matches the screenshot suite |
| Search determinism | low (ranking, index, availability) | low | **high** |
| Account mutation by `02` | real library gets follow/save writes | throwaway account, harmless | none |
| Drift | Spotify edits/removes items | same | only when the fixture changes |
| Neutrality of names/art | real artists, real brands | real artists, real brands | invented names, generated covers |
| Setup effort | ~30 min | ~30 min + subscription | ~1–2 days, one-time |
| Recurring effort | reseed + re-pick results | reseed | none; rebuild the demo APK |

The choice is really **"curate real Spotify items" vs "curate a fixture that the app serves"**.
Both need the same editorial work (what the video should show). Option C additionally buys
deterministic search results and zero account state, at the cost of one confined code change.

Note that the app was built for this: `SpotifyCatalog`, `SpotifyLibraryWriter` and `SpotifyGateway`
are transport-free interfaces "so a fake can drive UI/playback tests", and
`app/src/test/.../MockSpotifyDataSource.kt` already contains a complete, neutral pack
(5 artists, 6 albums, 4 playlists, 2 shows, 10 liked songs, 9 recently played) with 17 bundled
covers in the debug source set; the real pack now bundles the six official album covers next to it.

## 8. Recommendation

**Option C, in a `demo`-only build, on top of the existing mock pack — not a long-lived branch.**
Reasons:

1. It is the only option that makes **search deterministic**. The flows tap the *first* result
   below each heading; with a real account that result is Spotify's choice and changes over time.
   With the fixture, the query maps onto the pack.
2. It keeps **personal data and personal writes** out of the recording entirely, without a second
   Premium subscription.
3. It is **not a big change**: no screen, ViewModel, queue, history or playback change is needed
   for the visual result — only a `SpotifyCatalog` + `SpotifyLibraryWriter` pair and a seam in
   `SpotifySessions.get()`. The UI and all flows stay identical to release.
4. A branch would rot; a **product flavour** (`real` / `fixture`, or a `demo` build type whose
   sources live in `src/demo/`) keeps the fixture compiling with every refactor and cannot leak
   into `realRelease`. `FLOW-SUITE-TODO.md` already anticipated this ("consider a debug-only
   fixture intent in the app; do not add production behavior for tests").

Two ways to keep playback honest:

- **C1 — real URIs (built).** Give fixture items the `uri` of a real Spotify item, so playback is
  the real librespot path: seek/position/next behave exactly as in release with no playback code
  changes. The video is muted, so a single stock track serves every item (see §9) — the donors
  need no particular licence, and the visible titles and covers come from the pack.
- **C2 — simulated playback.** Leave `uri` null and let the ViewModel's existing local simulation
  drive the seek bar. Zero Spotify content, works offline, cannot rot. Cost: the demo no longer
  exercises librespot, and the `session == null || !nativeAvailable` gate in
  `SpotifyViewModel.init` must learn about demo mode (~5 lines).

C1 is the smaller code change; C2 is the smaller content change. The recordings are silent
(`ffmpeg … -an` in `PROMO-VIDEO.md`), so C2 loses nothing visible.

**Do not seed the personal profile.** If zero code is a hard requirement, use **B** (a throwaway
Premium account), but accept the search drift and the fact that the account gets follow/save
writes on every run.

## 9. What was built

A `demo` build type (C1). Release, debug, profile and dev builds are untouched: the fixture lives
only in `app/src/demo/`, and `assembleRelease` neither compiles it nor ships it.

| File | Role |
| --- | --- |
| `app/build.gradle.kts` | `demo` build type, `BuildConfig.DEMO_CONTENT`/`DEMO_PACK`, `src/debug/res` added to the demo source set for the generated covers |
| `app/proguard-rules.pro` | keeps `io.github.manuelrtsch.sideplay.demo.**` (release optimisation would strip a class only referenced by name) |
| `app/src/main/java/.../DemoContent.kt` | `DemoContent` interface + reflective lookup, `null` in every normal build |
| `app/src/main/java/.../data/SpotifySession.kt` | `catalog` returns the demo pack when present; `init` seeds history and queue |
| `app/src/demo/java/.../demo/RealPack.kt` | the default content: real songs, catalog + in-memory `SpotifyLibraryWriter` + seed |
| `app/src/demo/java/.../demo/FictionalPack.kt` | the original fully invented pack, kept as the backup content set |
| `maestro/flows/utility/record-prepare-demo.yaml` | a cold launch; fixture state is rewritten by the app itself |

Decisions worth knowing:

- **Same application id and signing as release.** Sidekit host theming needs the release
  certificate, and the Maestro flows pin the production id. Installing the demo APK therefore
  *replaces* the release app; the Spotify credentials survive because package and signature match.
- **Only the catalog is fake.** Auth and playback stay real, so a device must be signed in once.
- **The default pack shows real songs**, not invented ones: David Bowie (*Heroes*, *Low*), Nina
  Simone (*I Put a Spell on You*), Massive Attack (*Mezzanine*), Fela Kuti (*Zombie*) and Buena
  Vista Social Club. Recognisable and culturally relevant, deliberately not chart material. The
  pinned query is `bowie`, which the pack satisfies in all four search sections.
- **One stock track is the audio for every item** (`spotify:track:2GrbfjJz9M1kbplDKslSor`, the one
  that used to back *Afterglow*). Nothing is downloaded: the recordings are muted, so the audio is
  irrelevant, and one URI keeps playback on the real librespot path without a per-item catalogue of
  donors. Takes are shorter than the track, so it never ends mid-recording.
- **The records carry their official covers.** Six 512×512 JPEGs live in `app/src/demo/res/`
  (`album_heroes`, `album_low`, `album_spell`, `album_mezzanine`, `album_zombie`, `album_bvsc`),
  so artwork renders instantly and offline and the promo never shows a placeholder. The demo source
  set is the only place they exist; the release APK has none of them. Album art doubles as the
  artist image. Playlists and shows are still invented, so they keep the generated
  `src/debug/res` covers.
- **Durations are the songs' real lengths**, so timestamps in the video look right. Episodes keep
  their own durations and display `h:mm:ss`, which is what the flow selectors rely on.
- **The invented pack is still built**, selectable with `-PdemoPack=fictional`. Both packs satisfy
  the same flows, so the backup can be swapped in without touching a flow.
- **History and queue are rewritten at every process start.** They are local playback state, not
  account data; a take must always begin with the same rows even if the app was used normally
  before. Nine recent tracks (newest first) and a queue of three tracks cover the "≥ 6" and
  "current item is a track" requirements without any seeding flow.
- **Flows that depend on prepped state were fixed, not worked around.** Promo `03` now starts a
  track from a recent row (episodes have no Save/Seek, and `02` leaves an episode current), and
  promo `02` probes the keypad mode instead of assuming the prep left it in `Abc`.
- `record.sh` selects `record-prepare-demo.yaml` automatically when the installed `versionName`
  ends in `-demo`; `SIDEPLAY_PREP_FLOW` still wins.

## 10. Recording with the demo build

```sh
# Once per device: install the demo APK. This replaces the release app; sign in if the
# credentials are gone, then the fixture is served on every launch.
./gradlew :app:assembleDemo
adb install -r app/build/outputs/apk/demo/app-demo.apk

# Per take: the cold launch is the whole preparation (record.sh picks the demo prep).
SIDEPLAY_ACCENT=Lime ./record.sh flows/promo/01-browse-play.yaml "recordings/01-browse-play.mp4"
SIDEPLAY_ACCENT=Orange ./record.sh flows/promo/02-search-library-writes.yaml "recordings/02-search-library-writes.mp4"
SIDEPLAY_ACCENT=Blue ./record.sh flows/promo/03-queue-transport.yaml "recordings/03-queue-transport.mp4"
SIDEPLAY_ACCENT=Pink ./record.sh flows/promo/04-history-settings.yaml "recordings/04-history-settings.mp4"
```

Do **not** set `SIDEPLAY_SKIP_PREPARE=1` with the demo build: every take needs the cold launch so
the previous take's Home screen state and queue are discarded.

To go back to the real account: `adb install -r app/build/outputs/apk/release/app-release.apk`.

## 11. Verification checklist

Verified against the demo build on the Sidephone: the full suite passes for both packs
(`-PdemoPack=real` default and `-PdemoPack=fictional`), and each of the four promo flows passes
with `record-prepare-demo.yaml` between takes.

- [x] signed in and online (playback is real);
- [x] Library shows **LIKED SONGS** and **FOLLOWED ARTISTS**, with ≥ 2 followed artists;
- [x] the second followed artist has **TOP TRACKS** with ≥ 3 rows and an **ALBUMS** section whose
      first row has a year in its subtitle;
- [x] every followed artist satisfies the same rule;
- [x] the pinned query shows **TRACKS**, **ARTISTS**, **ALBUMS** and **SHOWS**, and the first
      show has ≥ 1 playable episode ≥ 1 h;
- [x] Home shows **RECENTLY PLAYED** with ≥ 6 entries and **See more history**;
- [x] the queue has ≥ 2 upcoming items and the current item is a **track**;
- [ ] accent colour set for the take; keypad in `Abc` mode;
- [x] `./run-suite.sh` passes (4/4 in ~2m 30s on both demo packs; the build reseeds itself between flows).
