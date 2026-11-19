# Release readiness — Jellyfin and Navidrome for private testers

Question asked: can 0.4.0 go to private testers with the new Jellyfin and Navidrome integrations,
where (1) there must be **no chance of a bulk overwrite or deletion of server data** — testers
will not have backups — and (2) sign-in, library discovery, search and playback must be
trustworthy on their devices.

Method: complete read of both providers' write paths and the UI entry points that reach them, the
shared playback path, and the existing test suite; the app and both provider suites executed
(`287 tests, 0 failures`); and, on 2026-11-19, **both integration suites executed against local
throwaway containers** — `jellyfin/jellyfin:12.4` on :8096 and `navidrome/navidrome:0.64.2` on
:4533, each with a small real library. The device was also used end to end (tap → FLAC decode →
`AudioTrack start`).

## Verdict

| Area | Status | Basis |
| --- | --- | --- |
| **Bulk/destructive writes** | **PASS — not reachable by construction** | No delete endpoint is implemented for either provider; no write takes a collection; no replace-style playlist call; login/logout write nothing to the server. See §1. |
| Sign-in (both) | PASS, verified against real servers | 16 Jellyfin auth tests + a real 12.4 sign-in; Navidrome ping/token tests + a real 0.64.2 sign-in. |
| Library discovery | PASS, verified against real servers | section/page mapping pinned per provider, plus the integration suites reading a real library. |
| Search | PASS, verified against real servers | scope mapping pinned per provider; both integration suites ran a real search. |
| Playback | PASS — fix verified, on device and against the server | Jellyfin direct play streams (`/Audio/{id}/stream?static=true`, 206 `audio/flac`); 12 shared-engine tests + on-device `AudioTrack start`. |
| Real-server verification | **DONE for this revision** | `provider-jellyfin` 2/2 and `provider-navidrome` 4/4 integration tests ran green against the containers (§2.1). Both suites were previously unrunnable — see §2.2. |

Short version: **the data-safety requirement is met with margin, and the four core features are
now verified on real servers of the versions testers are likely to run.** The one thing that
remains a judgement call is the Jellyfin 12.x floor (§3.2).

---

## 1. Server-data safety

### 1.1 The complete write surface

`MediaLibraryWriter` (`core/.../data/MusicService.kt:118`) is the *only* way the UI changes a
library, and it has five operations. Their implementations, verbatim in scope:

| Operation | Navidrome | Jellyfin | Scope per call |
| --- | --- | --- | --- |
| `setSaved` (favourite/star) | `star`/`unstar` with one `id`/`albumId`/`artistId` (`NavidromeApi.kt:148`) | `markFavoriteItem`/`unmarkFavoriteItem` (`JellyfinApi.kt:405`) | **1 item** |
| `setFollowed` (artist) | no-op (`NavidromeApi.kt:497`) | `markFavoriteItem` on the artist (`JellyfinApi.kt:411`) | **1 artist** |
| `addToPlaylist` | `updatePlaylist` with `songIdToAdd=<one id>` (`NavidromeApi.kt:166`) | `addItemToPlaylist(ids = listOf(one))` (`JellyfinApi.kt:434`) | **1 item appended** |
| `removeFromPlaylist` | `updatePlaylist` with `songIndexToRemove=<one index>` (`NavidromeApi.kt:172`) | `removeItemFromPlaylist(entryIds = listOf(one))` (`JellyfinApi.kt:447`) | **1 entry** |
| `createPlaylist` | `createPlaylist` with `name` only — no song ids, no `playlistId` (`NavidromeApi.kt:159`) | `createPlaylist(ids = emptyList())` (`JellyfinApi.kt:419`) | **1 new playlist** |

Everything else that leaves the device is either a read, a playback progress report, or
`markPlayedItem` for the item that just finished (`JellyfinPlaybackReporter.kt:106`).

### 1.2 Why a bulk overwrite cannot happen

- **No delete code exists.** `grep -rni delete` over both providers' `src/main` returns nothing.
  Neither `deletePlaylist` (Subsonic) nor `DELETE /Items` (Jellyfin) is implemented, so no UI
  action can call one.
- **No write takes a collection.** Each call above sends exactly one id, one index or one name;
  there is no loop over items anywhere in either write path (the only loops in either provider are
  read-side: `NavidromeApi.kt:441` album fan-out, `JellyfinApi.kt` playlist entry scan).
- **The Subsonic "replace the playlist" form is never emitted.** `updatePlaylist` can be
  destructive if called with `songId` (no suffix) or with `playlistId` inside `createPlaylist`;
  the code only ever sends `songIdToAdd`, `songIndexToRemove` or `name`. `createPlaylist` is called
  with `name` alone, so it always creates and never replaces.
- **Sign-in, restore and sign-out write nothing.** Jellyfin's post-login seeding is
  `announceCapabilities` + `resumeItems` (read) + a local resume point
  (`JellyfinRuntime.kt:240`); Navidrome's sign-in is a `ping` (`NavidromeRuntime.kt:92`). Both
  `signOut` implementations clear local state only — no `DELETE /Sessions`/`/Devices`, no server
  cleanup.
- **Queue, history and resume state are local files** (`QueueRepository`, `HistoryRepository`,
  `positionStore`, written through `ConflatedSaver`); nothing mirrors them back into the library.
- **Reachability is narrower than the interface.** In 0.4.0 the *only* writer calls the UI makes
  are the two favourite/follow toggles (`SidetrackViewModel.kt:1212, 1259, 1300`) and
  *New playlist* (`SidetrackViewModel.kt:491`). Add-to-playlist and remove-from-playlist have no
  entry point yet (deferred to 0.4.1, see `provider-capabilities.md`), so the two index-based
  playlist writes are dead code for a tester.

### 1.3 Guards already in place

- `setSaved` returns early for `Playlist`/`Show` on both providers
  (`NavidromeApi.kt:493`, `JellyfinCatalog.kt:155`), so a favourite tap can never be aimed at a
  playlist id through the song endpoint.
- The Navidrome transport logs only `url.substringBefore('?')` on failure
  (`NavidromeApi.kt:264`), so the Subsonic salt/token/password never reaches logcat.
- Toggles are optimistic with an explicit revert on failure (`SidetrackViewModel.kt:1195-1225`):
  a failed write re-reads nothing and retries nothing.
- `JellyfinCatalog.addToPlaylist` reads the playlist size *before* appending only to report an
  Undo index — the read cannot become a write.

### 1.4 Bounded defects found (wrong-entry risk, never bulk)

1. **Duplicate track ids in a playlist remove the wrong occurrence (Navidrome).**
   `removeFromPlaylist` resolves the row with `indexOfFirst { it.id == songId }`
   (`NavidromeApi.kt:173`). With `[A, B, C, A]`, removing the last `A` deletes index 0. Size is
   preserved, contents are not. Not reachable from the 0.4.0 UI; fix before 0.4.1 ships
   add/remove. **The suite now catches it:** `catalog reads and edits a playlist` asserts the
   ordered track ids before, after the append and after the removal, and appends a track the
   playlist does not already hold — so it stays green on the correct path. `removing a duplicated
   track leaves the other entries in place` pins the defect itself and is `@Ignore`d until the
   resolution becomes "the occurrence I appended"; un-ignore it with that fix.
2. **Jellyfin entry-id fallback (Jellyfin).** If the entry is not found within the first 500
   playlist items, `removeFromPlaylist` falls back to passing the *media item id* as an entry id
   (`JellyfinApi.kt:447-455`), which removes nothing (or, improbably, the wrong entry). Again one
   entry, and unreachable from the UI today.
3. **`createPlaylist(ids = emptyList())` on Jellyfin is unverified.** Documented as an open
   question in D14. The reachable failure mode is a visible error on *New playlist*, not a write.
   Worth 10 minutes against a scratch 12.x instance.

### 1.5 Hazard in the test harness itself

`NavidromeIntegrationTest` (5 tests, 1 `@Ignore`d) **stars a real track and edits a real playlist** named
`Sidetrack Test…`. `JellyfinIntegrationTest` is read-only. These run only when
`NAVIDROME_TEST_URL`/`JELLYFIN_TEST_URL` are exported — never point them at a tester's instance,
only at a throwaway container. This is the one place where "the app can damage a library" is
literally true; it is the test, not the app.

---

## 2. Core-feature coverage

Fast, offline tests unless marked **(integration)** — those need `NAVIDROME_TEST_*` /
`JELLYFIN_TEST_*` and skip when absent; they were run against the containers for this report (§2.1).

| Feature | Navidrome | Jellyfin |
| --- | --- | --- |
| Host normalisation / bad input | `NavidromeApiTest`: bare host → `http`, slashes trimmed | `JellyfinAuthTest`: scheme/slash/port rules, unusable address rejected before a request |
| Sign-in | `auth signs in against a ping and stores the credentials`; token-not-password in the URL | 16 tests: empty password, token stored, artwork header installed, restore, sign-out, persistence failure |
| Sign-in failures | subsonic error → `ServiceFailure` (40/41/50), HTTP status mapping | 401/403, 404, 5xx, timeout, certificate, SDK version → typed failures |
| Library discovery | artists/albums/tracks mapping, `albumList` paging, starred section, library paging **(integration: ran)** | section paging, album collection, playlist collection + editability, artist pages **(integration: ran)** |
| Search | `search3` scope mapping **(integration: ran)** | hint split into tracks/artists/albums/playlists, image item id **(integration: ran)** |
| Playback source | `streamUrl` auth/bitrate/replay-gain; catalog applies quality + normalisation **(integration: ran)** | `JellyfinPlaybackUrlTest` (new): direct play is `/Audio/{id}/stream?static=true`, never `/universal`; quality → bitrate ceiling; real server served audio bytes **(integration: ran)** |
| Playback engine (shared) | 12 tests: prepare/headers/range requests, buffering, seek, ended, player error, reconfigure, fake HTTP source | same |
| Progress reporting | — | start/progress/stopped reach the server once, mark-played only on finish |
| Device confirmation | — | **ran**: FLAC decoded, `AudioTrack start`, no extractor error |

Suite status (default, no env vars): `provider-navidrome 19 tests (5 skipped)`,
`provider-jellyfin 43 (2 skipped)`, `app 235`, `provider-spotify 115`, `core 4`, **0 failures**.

### 2.1 With the containers running

`NAVIDROME_TEST_URL=http://localhost:4533 …` — **4 ran, 1 `@Ignore`d, 0 failures** against Navidrome
0.64.2: artist/album/track reads, `albumList` paging, search, stream + artwork URLs fetched, star
toggled and restored, and playlist read/append/remove asserted on the ordered track ids (the
playlist was left with its original single entry).

`JELLYFIN_TEST_URL=http://localhost:8096 …` — **2/2 ran, 0 failures** against Jellyfin 12.4:
real sign-in, albums/tracks/search, and a stream URL opened with the `Authorization` header that
answers with real audio bytes (this is the assertion that fails when the direct-play URL regresses
to `/universal`).

Also confirmed directly on 12.4, since they were D14's open questions:

| Claim | Result |
| --- | --- |
| `createPlaylist(ids = emptyList())` | **Works** — `POST /Playlists` → 200 with a new id, playlist empty. *New playlist* is safe. |
| Direct FLAC via the music profile | **Works** — `…/Audio/{id}/stream?static=true` → 206, `audio/flac`, `Accept-Ranges: bytes`, `content-range: bytes 0-1023/17494001`. |
| `editable` from `openAccess`/`shares` | **Does not hold for a fresh playlist** — a playlist created by `demo` comes back `OpenAccess:false, Shares:[]`, so `editable` is `false` even for its creator, and `createPlaylist` returns `editable = false` (`JellyfinCatalog.kt:173` + default at `:252`). Harmless in 0.4.0 (no add/remove entry point) but must be fixed before 0.4.1 surfaces them. |
| Entry id vs item id | A freshly added entry's `PlaylistItemId` equals the media item id, so the 500-item scan fallback (§1.4) is usually harmless in practice. Add (`POST /Playlists/{id}/Items`) and remove (`DELETE …?entryIds=`) both returned 204. |

### 2.1.1 What a 10.9 server actually does — the floor, settled

Run 2026-11-19 against a throwaway `jellyfin/jellyfin:10.9.11` on :8097
(`docker run -d --name jellyfin109 -p 8097:8096 -v ~/jf109b-cfg:/config -v ~/jf109b-cache:/cache -v ~/Music/Auto:/media:ro jellyfin/jellyfin:10.9.11`;
the container is stopped again, its config at `~/jf109b-cfg`), finished through the
startup API (`GET /Startup/Configuration` first — that is what creates the initial administrator;
`POST /Startup/User` alone answers 500 `Sequence contains no elements`) and loaded with the same
`/media` music folder.

| Step | 10.9.11 |
| --- | --- |
| `POST /Users/AuthenticateByName` | **Succeeds** — a token, a user id, no warning. |
| `GET /Items?includeItemTypes=MusicAlbum` (curl) | **200** with albums. |
| The same read through the 12.x SDK | **`InvalidContentException: Deserialization failed`** — `MissingFieldException: Field 'ItemId' is required for type 'UserItemDataDto'`. 10.9's user data carries no `ItemId`. |
| What the user sees without the fix | Sign-in appears to work, then every screen says *“Could not talk to that Jellyfin server. Sidetrack needs Jellyfin 12.0 or newer.”* |
| `POST /Items/{id}/PlaybackInfo` with the music profile | curl → 500 (10.9 rejects part of the 12.x profile / route shape; not investigated further, since the reads above already decide the floor). |

So 10.9 “mostly works” in exactly the way this report warned about: the failure lands *after* a
successful sign-in, on the first read, as a deserialization error the user cannot act on. The
floor is therefore enforced, not just documented:

- `jellyfinMajorVersion` / `requireJellyfinVersion` (`auth/JellyfinAuth.kt`). `SdkJellyfinAuthenticator`
  asks `/System/Info/Public` — anonymous, one small request, **before** the password is sent — and
  refuses a server older than 12 by name: *“That server runs Jellyfin 10.9.11. Sidetrack needs
  Jellyfin 12.0 or newer.”* An unreadable version string (a fork, a proxy) is let through, and its
  reads still fail honestly.
- Verified both ways: the same suite against `jellyfin/jellyfin:12.4` is 2/2 green, and against
  10.9.11 it now fails on the sign-in line with that message instead of inside `GET /Items`.
- `JellyfinAuthTest` pins the parsing (`12.4.0`, `12`, `12.0.0-beta5`, `unstable`, null), the exact
  refusal text, and that 12.x and unreadable versions pass.

### 2.2 The harness was broken, not just skipped

The Jellyfin integration suite could not run even with the env vars set: `createJellyfin` threw
`NullPointerException` at `AndroidDeviceKt.androidDevice` because Robolectric leaves
`Settings.Secure.ANDROID_ID` null, and the JVM test classpath had no slf4j binding
(`NoClassDefFoundError`). Both are test-only fixes, applied with this report: the suite now states
a device name and id, and `provider-jellyfin` declares `testRuntimeOnly("org.slf4j:slf4j-simple")`.
This is the reason the 0.4.0 playback regression reached the device despite an integration test
that would have caught it.

### ~~The one structural gap~~ — closed

`SdkJellyfinApi.playback()` — the function that posts `PlaybackInfo`, merges
`requiredHttpHeaders`, and chooses `transcodingUrl` vs direct stream — had **no offline test**.
`FakeJellyfinApi` stubs `playback()` wholesale (`FakeJellyfinApi.kt:91`), so the branch the 0.4.0
bug lived in was exercised only by the integration test.

The choice is now `internal fun playbackStreamUrl(transcodingUrl, baseUrl, directUrl)` in
`JellyfinApi.kt`, and `JellyfinPlaybackChoiceTest` (5 tests, no SDK, no server) pins it: no url and
blank url keep the static direct-play stream, an absolute `transcodingUrl` is used as is, a
relative one is joined to the server root, and a `transcodingUrl` wins over a source that also
claims `supportsDirectPlay`.

---

## 3. Remaining work before handing out

1. ~~Run both integration suites against throwaway containers.~~ **Done** — see §2.1.
2. ~~Decide the Jellyfin floor out loud.~~ **Decided: 12.0, and now enforced at sign-in.**
   What 10.9.11 does was measured — it signs in and then fails every read in deserialization — so
   the app asks `/System/Info/Public` first and refuses an older server by version. See §2.1.1.
   The tester brief still says 12+, now with the app agreeing instead of contradicting itself.
3. ~~Close the `playback()` branch gap (§2) so the 0.4.0 regression cannot come back silently.~~
   **Done** — see §2, “The one structural gap”.
4. Optional, before 0.4.1: ~~make `NavidromeIntegrationTest` assert playlist *contents* rather than
   size so it can catch them~~ (**done**, plus the `@Ignore`d duplicate case — see §1.4), fix the
   duplicate-index removal and the 500-entry fallback, and stop reporting a creator's own fresh
   Jellyfin playlist as non-editable.

## 4. What to tell testers

- Navidrome and Jellyfin connections are read-first: the app can favourite/unfavourite tracks,
  albums and artists, follow artists, create empty playlists and report playback progress. It
  cannot delete anything and cannot rewrite an existing playlist.
- Jellyfin needs **12.0 or newer**. An older server is refused on the sign-in form and named
  ("That server runs Jellyfin 10.9.11…"), instead of signing in and then failing every screen.
- Audio settings affect only how the app asks for a stream; nothing is written back to the
  library.
- If a screen says "unavailable"/"network", the app has not written anything — retry is safe.
