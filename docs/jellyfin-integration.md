# Jellyfin integration — plan and SDK assessment

Status: **researched, not implemented.** Nothing here is in the build yet: no dependency, no
`ServiceId`, no capability set, no code. This document records what the official Kotlin SDK gives
us, what it does not, how Jellyfin maps onto Sidetrack's provider-neutral model, and the order in
which the adapter should be built.

Sources: the [Kotlin SDK guide](https://kotlin-sdk.jellyfin.org/guide/getting-started.html), the
SDK sources at [jellyfin/jellyfin-sdk-kotlin](https://github.com/jellyfin/jellyfin-sdk-kotlin)
(`master`, release 1.9.4), and the Jellyfin OpenAPI spec the SDK is generated from.

---

## 1. Short version

- The Kotlin SDK is a **generated API client only**: request groups, models, URL builders,
  WebSockets, server discovery. There is no player, no queue, no cache, no media session.
- One dependency (`org.jellyfin.sdk:jellyfin-core`), LGPL-3.0, OkHttp + kotlinx-serialization
  underneath, needs an Android `Context` when constructed.
- It is generated against Jellyfin **12.4** and declares **12.0.0** as its minimum supported
  server. Jellyfin 10.x servers are out of scope for SDK 1.9.x.
- Most of Sidetrack's `MediaCatalog` maps 1:1. Three capabilities are genuinely absent
  (podcasts/shows, loudness normalization, gapless), and two need a semantic decision
  (follow-artist becomes "favorite artist", quality becomes a bitrate ceiling).
- The significant risk is **playback**, not the catalog: the SDK's audio endpoints return
  `ByteArray` (whole-body download), so a streaming adapter must use its URL builders or
  `getPostedPlaybackInfo` and hand the URL to Media3 — exactly how the Navidrome adapter already
  builds a Subsonic `stream` URL.

---

## 2. What the Kotlin SDK gives

### 2.1 Coordinates and platform

| Item | Value |
| --- | --- |
| Artifact | `org.jellyfin.sdk:jellyfin-core` (Maven Central) |
| Latest release | `1.9.4` |
| Licence | LGPL-3.0 |
| Generated against | Jellyfin API `12.4.0` |
| Minimum server | `12.0.0` (`Jellyfin.minimumVersion`) |
| Platforms | JVM and Android (SDK `minSdk` 21; Sidetrack is 31, so no constraint) |
| Kotlin | built with Kotlin 2.4.20 / JvmTarget 1.8 (Sidetrack is on 2.4.21) |
| Transitive deps | OkHttp 5.5.1, kotlinx-coroutines 1.11, kotlinx-serialization-json 1.11, kotlin-logging |

`jellyfin-core` re-exports `jellyfin-api`, `jellyfin-api-okhttp` and `jellyfin-model`, so one
`implementation` line is enough. No serialization Gradle plugin is needed on our side; we only read
the generated models.

The SDK needs an Android `Context` at construction. On Android it derives `DeviceInfo` from
`Settings.Secure.ANDROID_ID` (device id) and `Settings.Global.DEVICE_NAME`/`Build.MODEL`
(`androidDevice(context)`); the id must be stable because Jellyfin keys a session on it.

### 2.2 Wiring

```kotlin
val jellyfin = createJellyfin {
    context = appContext
    clientInfo = ClientInfo(name = "Sidetrack", version = BuildConfig.VERSION_NAME)
}

// Address handling: normalize a typed host, then score the candidates.
val candidates = jellyfin.discovery.getAddressCandidates("music.example.org:8096")
val server = jellyfin.discovery.getRecommendedServers(candidates, RecommendedServerInfoScore.GOOD)

val api = jellyfin.createApi(baseUrl = server.address, accessToken = storedToken)
api.update(accessToken = newToken) // later, after sign-in or on refresh
```

### 2.3 Authentication

Everything lives in `authenticationApi` (`POST /Users/AuthenticateByName` and friends) — note the
guide's `userApi.authenticateUserByName` is stale for 1.9.4; the extension property is
`authenticationApi`.

| Flow | Call | Fit for Sidetrack |
| --- | --- | --- |
| Username + password | `authenticationApi.authenticateUserByName(AuthenticateUserByName(username, pw))` | `AuthInput.Password(serverUrl, username, password)` already exists and the sign-in screen is already provider-agnostic; no new UI |
| Passwordless user | same call, empty `pw` | works |
| Quick Connect | `getQuickConnectEnabled`, `initiateQuickConnect`, `getQuickConnectState(secret)` (poll ~5 s), `authenticateWithQuickConnect(QuickConnectDto(secret))` | would need a new `AuthInput` variant and a code-display screen; nice-to-have, not first |
| API key / token | `createApi(accessToken = ...)` or `api.update(accessToken = ...)` | used for `restore()` after a stored token |
| Wrong password | `InvalidStatusException` with `status == 401` | map to `ServiceFailure.AuthenticationExpired`, the same bucket Navidrome uses for Subsonic 40/41 |

`AuthenticationResult` carries `accessToken`, `user: UserDto` (`id`, `name`), `serverId`,
`sessionInfo`. `UserApi.getCurrentUser()` is the cheapest token validation for `restore()`.

The password is needed **once**, unlike Navidrome's per-request Subsonic token, so the store keeps
the access token, not the password.

### 2.4 Server discovery

`jellyfin.discovery` gives `discoverLocalServers(): Flow`, `getAddressCandidates(input)` and
`getRecommendedServers(candidates, score)`. This is a UX upgrade over Navidrome's string
normalization: it fixes scheme/port/path typing and reports a score per address, so a wrong host
can be explained instead of surfacing as a network error. Optional for a first cut.

### 2.5 Operation groups

The SDK exposes 45 generated groups as extension properties on `ApiClient`
(`api.<name>Api`). The ones Sidetrack needs:

| Need | Group → call |
| --- | --- |
| `library(Liked)` | `libraryApi.getItems(includeItemTypes = [AUDIO], filters = [IS_FAVORITE], recursive = true)` |
| `library(Playlists)` | `libraryApi.getItems(includeItemTypes = [PLAYLIST], recursive = true)` (there is **no** playlist-list endpoint in 1.9.4) |
| `library(Albums)` | `libraryApi.getItems(includeItemTypes = [MUSIC_ALBUM], recursive = true, sortBy = [SORT_NAME])` |
| `library(Artists)` | `artistApi.getArtists(sortBy = [SORT_NAME], isFavorite = …)` |
| `collection(Album)` | `libraryApi.getItem(albumId)` + `libraryApi.getItems(parentId = albumId, includeItemTypes = [AUDIO], sortBy = [PARENT_INDEX_NUMBER])` |
| `collection(Playlist)` | `libraryApi.getItem(id)` (name/art/permissions) + `playlistApi.getPlaylistItems(id)` |
| `artist(key)` | `libraryApi.getItem(artistId)` |
| `albums(artist)` | `libraryApi.getItems(includeItemTypes = [MUSIC_ALBUM], albumArtistIds = [artistId])` |
| `topTracks(artist)` | `libraryApi.getItems(artistIds = [artistId], includeItemTypes = [AUDIO], sortBy = [PLAY_COUNT], sortOrder = [DESCENDING])` — Jellyfin has no top-songs endpoint |
| `search` | `searchApi.getSearchHints(searchTerm = q, limit = n, includeItemTypes = mapped)` |
| `radio` | `instantMixApi.getInstantMixFromSong/FromAlbum/FromArtists/FromPlaylist` |
| `resolvePlaybackSource` | `audioApi.getUniversalAudioStreamUrl(...)` or `mediaInfoApi.getPostedPlaybackInfo(...)` |
| `isSaved` | `userDataApi.getItemUserData(itemId).isFavorite` |
| `setSaved` | `userDataApi.markFavoriteItem` / `unmarkFavoriteItem` |
| `isFollowed` / `setFollowed` | same two calls on a `MusicArtist` id |
| `addToPlaylist` | `playlistApi.addItemToPlaylist` |
| `removeFromPlaylist` | `playlistApi.removeItemFromPlaylist` |
| `createPlaylist` | `playlistApi.createPlaylist(CreatePlaylistDto(name = …, ids = …, mediaType = AUDIO))` |
| Progress | `sessionApi.postFullCapabilities` once, then `reportPlaybackStart/Progress/Stopped` |
| Resume list | `libraryApi.getResumeItems` |
| Server identity | `systemApi.getPublicSystemInfo` / `getSystemInfo` / `getPingSystem` |
| Live updates | `api.webSocket.subscribe<T>()` |

Groups we would not use but that ship in the jar: backup, branding, channel, collection, device,
display preference, environment, filter, genre, item lookup/update, library structure, live TV,
localization, lyric, media segment, movie, music genre, person, plugin, remote image, scheduled
task, startup, studio, subtitle, suggestion, sync play, trailer, trick play, video, year.

### 2.6 Models and enums

Useful generated types: `BaseItemDto`, `BaseItemDtoQueryResult` (`items`, `totalRecordCount`,
`startIndex` — **no `hasMore`**), `UserItemDataDto` (`isFavorite`, `playbackPositionTicks`,
`playCount`, `played`), `SearchHint`/`SearchHintResult`, `MediaSourceInfo`, `PlaylistDto`
(`openAccess`, `shares`, `itemIds`), `AuthenticationResult`, `PlaybackInfoResponse`,
`PlaybackStartInfo`/`PlaybackProgressInfo`/`PlaybackStopInfo`, `ClientCapabilitiesDto`,
`DeviceProfile`.

Enums worth knowing: `BaseItemKind` (`AUDIO`, `MUSIC_ALBUM`, `MUSIC_ARTIST`, `PLAYLIST`, `EPISODE`,
`SERIES`, …), `ItemFields`, `ItemSortBy` (incl. `PLAY_COUNT`, `PARENT_INDEX_NUMBER`,
`DATE_CREATED`), `ItemFilter` (`IS_FAVORITE`, `IS_PLAYED`, `IS_RESUMABLE`, …), `MediaType`
(`AUDIO`, …), `ImageType` (`PRIMARY`, `ART`, …), `SortOrder`, `PlayMethod`, `RepeatMode`,
`PlaybackOrder`.

Ids are `org.jellyfin.sdk.model.UUID`, not `String`; `MediaKey` keeps `item.id.toString()` and
parses back with `UUID.fromString`.

`runTimeTicks` and `positionTicks` are 100-nanosecond ticks — divide by 10 000 for `durationMs`.

### 2.7 URL builders

The SDK exposes URL builders next to every byte-returning endpoint:

- `imageApi.getItemImageUrl(itemId, ImageType.PRIMARY, maxHeight = …, tag = item.imageTags[PRIMARY])`
  → artwork URL that maps directly onto `Track.artUrl`/`Album.artUrl` (Sidetrack already loads
  remote artwork).
- `audioApi.getUniversalAudioStreamUrl(itemId, maxStreamingBitrate = …, transcodingContainer = …,
  deviceId = …, userId = …)`.
- `pluginApi.getPluginImageUrl`, `trickPlayApi.getTrickplayTileImageUrl`, etc.

**`createUrl` does not append the token.** Either add `?ApiKey=<token>` yourself or send the
`Authorization` header. This differs from Navidrome, which bakes its Subsonic token into every URL
(`u`/`t`/`s` query params), so Sidetrack has never needed headers for artwork. `RemoteArtwork`
(Sidekit) fetches with a plain GET and no custom headers, so **image URLs must carry `ApiKey`**;
the audio stream can use either the query parameter or `PlaybackSource.headers`, which already
exists for Media3.

The authorization header format the SDK itself uses is
`MediaBrowser Client="Sidetrack", DeviceId="…", Device="…", Version="…", Token="…"`
(`AuthorizationHeaderBuilder`).

### 2.8 WebSockets

`api.webSocket.subscribeAll()` / `subscribe<T>()` / `subscribeGeneralCommand(...)` /
`subscribePlayStateCommands(...)` / `subscribeSyncPlayCommands(...)` return flows. The SDK only
receives; sending is limited to keepalive and subscription management. Connections open and close
with subscriptions and reconnect on credential changes.

Useful for later: `UserDataChangedMessage` (invalidate saved-state cache), `LibraryChangedMessage`
(refresh library sections), `PlayStateMessage` (remote control, like the SDK's own TV client).
Nothing here is required for a first version.

### 2.9 Errors and HTTP tuning

`ApiClientException` subclasses: `InvalidStatusException` (`status`), `TimeoutException`,
`SecureConnectionException`, `InvalidContentException`, `MissingBaseUrlException`,
`MissingPathVariableException`. Map to Sidetrack's `ServiceFailure`:

| SDK | `ServiceFailure` |
| --- | --- |
| `InvalidStatusException` 401 | `AuthenticationExpired` |
| `InvalidStatusException` 404 | `TrackUnavailable` |
| `InvalidStatusException` 5xx | `Unavailable` |
| `TimeoutException`, `IOException`-style failures | `Network` |
| `SecureConnectionException` | `Network` (with a certificate hint) |
| anything else | `Unknown` |

`HttpClientOptions(followRedirects = true, requestTimeout = 30s, connectTimeout = 6s,
socketTimeout = 30s, socketReconnectPolicy = …)` is per `createApi` call and worth tightening:
30 s is long for a search on a self-hosted box.

### 2.10 What the SDK does **not** do

1. **No playback.** No player, decoder, media session, audio focus, queue, or gapless handoff.
   Sidetrack's existing `Media3PlaybackEngine` remains the player; the adapter only resolves URLs.
2. **No streaming reads.** `getAudioStream`, `getUniversalAudioStream`, `getItemImage` and friends
   return `ByteArray` — the whole body. Never call them for playback or artwork; use the URL
   builders or `getPostedPlaybackInfo`.
3. **No transcoding policy.** You either build `/Audio/{id}/universal` and let the server decide,
   or send a `DeviceProfile` in `PlaybackInfoDto` and play `MediaSourceInfo.transcodingUrl`
   (with `requiredHttpHeaders` passed as request headers).
4. **No capability announcement.** `sessionApi.postCapabilities` (individual flags) or
   `postFullCapabilities(id, ClientCapabilitiesDto)` must be called by the app, or the server's
   session list and some playback decisions stay ignorant.
5. **No scrobble endpoint.** "Played" is `userDataApi.markPlayedItem` plus the playback reports.
6. **No loudness normalization / ReplayGain** parameter on any endpoint.
7. **No `/Playlists` list call** in 1.9.4 (only get/create/update/items). Playlist lists come from
   `getItems(includeItemTypes = [PLAYLIST])`.
8. **No grouped/letter-indexed artists.** Subsonic's `getArtists` returns index groups; Jellyfin's
   returns one flat, paged list, so the UI's alphabetical browsing has no server-side grouping.
9. **No toast for `hasMore`.** `BaseItemDtoQueryResult` gives `totalRecordCount` and `startIndex`;
   compute `hasMore = startIndex + items.size < totalRecordCount`.
10. **No session/token refresh.** A revoked token surfaces as a 401 and the app must sign out.
11. **No podcasts.** Music libraries contain `MusicAlbum`/`MusicArtist`/`Audio`; `Series`/`Episode`
    exist only in video libraries.

### 2.11 Sharp edges to remember

- `PlaylistDto` has no name or child count — take those from `libraryApi.getItem(playlistId)`;
  `editable` comes from `openAccess` and `shares.first { it.userId == user }.canEdit`.
- `createPlaylist` with an empty `ids` list is the only way to make the "New playlist" flow match
  Navidrome's (create empty, then add); confirm against a real server during implementation.
- `SearchHint` is rich enough (id, name, `album`, `albumId`, `albumArtist`/`artists`,
  `runTimeTicks`, `primaryImageTag`, `type`) to build a `Track` without a per-hit `getItem`.
- `getArtists` paging plus `sortBy = SORT_NAME` is the only way to page the Artists section.

---

## 3. Model mapping

| Sidetrack | Jellyfin | Notes |
| --- | --- | --- |
| `MediaType.Track` ↔ `AUDIO` | `BaseItemDto` | `Track.artist` ← `albumArtist` (fallback `artists.first()`), `artistId` ← `albumArtists.first().id`, `albumId` ← `albumId` |
| `MediaType.Album` ↔ `MUSIC_ALBUM` | `BaseItemDto` | `Album.trackCount` ← `childCount` (fallback `recursiveItemCount`) |
| `MediaType.Artist` ↔ `MUSIC_ARTIST` | `BaseItemDto` | — |
| `MediaType.Playlist` ↔ `PLAYLIST` | `BaseItemDto` + `PlaylistDto` | see sharp edge above |
| `LibrarySection.Liked` ↔ favorite `AUDIO` | `getItems(filters = IS_FAVORITE)` | "Liked songs" label stays honest |
| `LibrarySection.Artists` ↔ favorite `MUSIC_ARTIST` | `getArtists(isFavorite = true)` | only if follow-artist maps to favorites |
| `LibrarySection.Shows` | — | proposed: off |
| `SearchScope.*` | `getSearchHints(includeItemTypes)` | Tracks→`AUDIO`, Artists→`MUSIC_ARTIST`, Albums→`MUSIC_ALBUM`, Playlists→`PLAYLIST` |
| `Page(hasMore)` | `totalRecordCount`/`startIndex` | — |
| `Track.durationMs` | `runTimeTicks / 10_000` | — |
| `EpisodeProgress`, `ResumePoint` | `userData.playbackPositionTicks` (optional import) | Sidetrack already keeps local resume state; server state can seed it |

There is no `Show`/`Episode` mapping proposed: everything Sidetrack does with a show (episode
numbers, notes, dates, ±30 s controls, no shuffle) exists for podcasts, and a Jellyfin music
library has none. A video library's `Series`/`Episode` would fit the shape but not the product.

---

## 4. Proposed capability set

```kotlin
/** Jellyfin has albums, playlists, artists and instant mixes; no podcasts, no DSP settings. */
val Jellyfin = ServiceCapabilities(
    librarySections = setOf(
        LibrarySection.Liked,
        LibrarySection.Playlists,
        LibrarySection.Albums,
        LibrarySection.Artists
    ),
    searchScopes = setOf(
        SearchScope.Tracks,
        SearchScope.Artists,
        SearchScope.Albums,
        SearchScope.Playlists
    ),
    supportsPodcasts = false,          // music libraries hold no podcast type
    supportsRadio = true,              // InstantMix
    supportsFollowArtist = true,       // favorite MusicArtist (see decision D2)
    supportsPlaylistEdit = true,       // add / remove / move
    supportsPlaylistCreate = true,     // PlaylistApi.createPlaylist
    supportsNormalization = false,     // no ReplayGain or loudness parameter
    supportsGapless = false,           // engine-level, same reason as Navidrome
    supportsQualitySelection = true,   // maxStreamingBitrate on the stream URL
    supportsProgressReporting = true,  // reportPlaybackStart/Progress/Stopped
    supportsScrobbling = true          // reportPlaybackStopped + markPlayedItem
)
```

The category rows and the "Search in" screen need no Jellyfin-specific work: they already mute
whatever the capability set leaves out (see `docs/provider-capabilities.md`).

---

## 5. Runtime shape

Mirror the Navidrome adapter — one provider file set, isolated stores, shared Media3 playback:

| Piece | File | Responsibility |
| --- | --- | --- |
| Identity | `MediaModels.kt` | `ServiceId.Jellyfin = ServiceId("jellyfin")` (persisted, ship once) |
| Capabilities | `MediaModels.kt` | `ServiceCapabilities.Jellyfin` |
| API seam | `data/JellyfinApi.kt` | thin wrapper over the SDK groups so tests can fake it (the Navidrome `NavidromeTransport` pattern) |
| Catalog | `data/JellyfinCatalog.kt` | `MediaCatalog` + `MediaLibraryWriter` over the seam |
| Auth | `auth/JellyfinAuth.kt` | `ServiceAuth` over `authenticationApi`, token-only store |
| Store | `auth/JellyfinCredentialStore.kt` | `GuardedEncryptedStore(context, "sidetracks_jellyfin")` holding server, token, user id, user name |
| Runtime | `data/JellyfinRuntime.kt` | `ServiceRuntime` + `QualitySettingsRuntime` + `CacheRuntime`; `sidetracks_settings_jellyfin`, `sidetracks-jellyfin-queue.bin`, `-history.bin`, `-resume.bin` |
| Registration | `data/AppSession.kt`, `data/JellyfinRuntime.kt` | `JellyfinService` constants, `JellyfinProvider`, `KnownServices`, `AppSessions.build` |
| SDK lifecycle | `data/JellyfinRuntime.kt` | one `createJellyfin(context)` per runtime; one `createApi` per signed-in account; `api.update(token)` on restore |

`AppSessions.build` gains a `ServiceId.Jellyfin ->` branch; the picker, the sign-in form and the
settings screens pick it up from `KnownServices` and the capability set with no per-provider copy.

---

## 6. Playback plan

1. **Preferred**: `mediaInfoApi.getPostedPlaybackInfo(itemId, PlaybackInfoDto(
   userId = …, maxStreamingBitrate = quality.kbps * 1000, deviceProfile = musicProfile))`.
   Take `playSessionId` (needed for progress reporting) and the first `mediaSources` entry.
   Play `transcodingUrl` (absolute path, so prefix `api.baseUrl`), passing
   `requiredHttpHeaders` plus the `Authorization` header.
2. **Simple fallback**: `audioApi.getUniversalAudioStreamUrl(itemId, maxStreamingBitrate = …,
   userId = …, deviceId = …)` with the token as a header.
3. Feed it to the existing `PlaybackController`:
   `PlaybackSource(id = item.id, uri = url, headers = mapOf("Authorization" to header))`.
4. `AudioQuality` → `maxStreamingBitrate`: `Normal` 96 kbit/s, `VeryHigh` (labelled *Max*)
   320 kbit/s, `High` = no cap (server decides), matching the Navidrome mapping so the settings
   screen reads the same. Note Jellyfin's own default ceiling is low; a `DeviceProfile` should
   state `maxStreamingBitrate`/`maxStaticMusicBitrate` explicitly for the `High` case too.
5. **Direct play vs transcode matters here.** Without a `DeviceProfile` the server may transcode to
   MP3 and silently downgrade FLAC. The profile should advertise direct play for the containers
   Media3/ExoPlayer handles (flac, mp3, aac/m4a, ogg/opus, wav) and one audio-only transcoding
   profile as a fallback.
6. No normalization and no gapless, so both settings rows stay hidden by capability.

---

## 7. Progress, "played" and session hygiene

1. After sign-in: `sessionApi.postFullCapabilities(id = sessionId, data = ClientCapabilitiesDto(
   playableMediaTypes = listOf(MediaType.AUDIO), supportsMediaControl = true,
   supportsPersistentIdentifier = true, deviceProfile = musicProfile))` so the server knows the
   client and its device profile.
2. On start: `reportPlaybackStart(PlaybackStartInfo(itemId, mediaSourceId, playSessionId,
   positionTicks, canSeek = true, isPaused = false, isMuted = false, playMethod = DIRECT_PLAY |
   TRANSCODE, repeatMode, playbackOrder))`.
3. Every ~10 s while playing and on pause/seek: `reportPlaybackProgress(...)` with the same ids and
   the current `positionTicks`.
4. On stop/finish: `reportPlaybackStopped(PlaybackStopInfo(...))`, then `markPlayedItem(itemId)`
   when the item actually finished (Jellyfin has no scrobble rule of its own).
5. Optionally seed `ResumePoint` from `libraryApi.getResumeItems` on sign-in, so resume survives a
   reinstall; local state stays authoritative otherwise.

---

## 8. Open decisions

| # | Question | Recommendation |
| --- | --- | --- |
| D1 | **SDK or hand-rolled HTTP like Navidrome?** | SDK. Jellyfin's API surface (45 groups, many enums) is far larger than Subsonic's ~20 endpoints; hand-rolling would mean reimplementing the mapping the SDK already ships. Accept LGPL-3.0, +OkHttp, +kotlinx-serialization. |
| D2 | **Follow artist = favorite artist?** | Yes, `supportsFollowArtist = true`. It is the only artist-level "keep this" state Jellyfin has, and Sidetrack's follow is exactly "pin this artist". Copy stays "Follow"/"Following"; internal naming can note the mapping. |
| D3 | **Shows?** | Off. Music libraries have no podcasts; mapping video `Series` would need podcast-only UI semantics rethought. Revisit only if Jellyfin ships podcast libraries. |
| D4 | **Quality selection?** | On, as a bitrate ceiling via `maxStreamingBitrate` + `DeviceProfile`. Without a profile, servers default to low-bitrate transcodes. |
| D5 | **Quick Connect?** | Defer. Password auth reuses the existing form and store; Quick Connect needs a code screen and polling, and adds a second auth path to test. |
| D6 | **WebSockets now?** | Defer. Useful for saved-state invalidation and remote control, not required for a correct first version. |
| D7 | **Server version floor** | Document "Jellyfin 12.0 or newer". SDK 1.9.x will not talk to 10.10.x servers. |
| D8 | **Second app id vs. in-app provider?** | In-app, like Navidrome. It is already a multi-provider app; one more picker entry is the smaller change and keeps one code base. |

---

## 9. Implementation order

1. **Dependency and licence**: `jellyfin = "1.9.4"` in `gradle/libs.versions.toml`, `jellyfin-core`
   in `app/build.gradle.kts`, LGPL-3.0 notice in the About licences list.
2. **Identity and capabilities**: `ServiceId.Jellyfin`, `ServiceCapabilities.Jellyfin`, a
   `MusicServiceTest` case pinning the flag set and the muted categories.
3. **Auth**: `JellyfinCredentialStore` + `JellyfinAuth` (`signIn`, `restore`, `signOut`), token
   validation via `getCurrentUser`, `ServiceFailure` mapping unit tests with a fake transport.
4. **API seam and catalog**: `JellyfinApi` + `JellyfinCatalog` for the four sections, album and
   playlist collections, artist/albums/top-tracks, search, artwork URLs; pagination via
   `totalRecordCount`, `hasMore` computed; unit tests with recorded JSON shaped like
   `BaseItemDtoQueryResult`.
5. **Writer**: favorites (track/album/artist), playlist add/remove/create.
6. **Runtime and playback**: `JellyfinRuntime`, `PlaybackSource` resolution (universal URL first,
   `getPostedPlaybackInfo` next), quality ceiling, isolated stores.
7. **Registration**: `KnownServices`, `JellyfinProvider`, `AppSessions.build`; the picker and the
   password sign-in form then work unchanged.
8. **Progress reporting**: `postCapabilities`, start/progress/stopped, `markPlayedItem`.
9. **Optional**: `getResumeItems` seeding, WebSocket invalidation, discovery UI, Quick Connect.
10. **Integration test**: `JellyfinIntegrationTest` gated on `JELLYFIN_TEST_URL` / `_USER` /
    `_PASSWORD`, matching Navidrome's opt-in pattern (skipped when absent).

Steps 2–4 keep the build green on their own; nothing needs a feature flag because the picker only
offers what `KnownServices` lists.

---

## 10. Risks and compliance

- **LGPL-3.0**: acceptable for a proprietary app when the library is dynamically linked and its
  licence/notice is preserved. Add the SDK to the About screen's licence list and do not modify or
  statically bundle SDK sources. Replacing the SDK with a hand-rolled client later must stay
  possible (no SDK types in the domain model — keep them inside `data/`/`auth/`).
- **Binary size**: one artifact plus OkHttp and kotlinx-serialization already partly present via
  other deps; measure with `assembleRelease` before and after.
- **Token leakage**: never log `Authorization` headers or `ApiKey` query parameters; keep tokens in
  the encrypted store only.
- **Request volume**: search is one call (hints), but library sections page through `getItems`;
  the artist screen costs three calls. Match Navidrome's `LIBRARY_SECTION_SIZE`/`CATALOG_PAGE_SIZE`
  budget.
- **Server matrix**: test against Jellyfin 12.x (Linux, at least one with a library that has FLAC
  and MP3), and document that 10.x servers are rejected by the SDK's minimum version.
