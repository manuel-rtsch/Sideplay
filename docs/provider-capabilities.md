# Provider capabilities

Sidetrack speaks to one music service at a time through a provider-neutral domain model
(`MediaCatalog` / `MediaLibraryWriter` / `PlaybackController`). The UI never names a provider;
it asks a service's `ServiceCapabilities` which features to show. This document lists, for each
provider, what is currently **implemented** and what is **covered by tests**.

Sources of truth:

- Capability flags: `core/src/main/java/io/github/manuelrtsch/sidetrack/MediaModels.kt`
  (`ServiceCapabilities`, `ServiceId`), `provider-spotify/…/data/SpotifySession.kt` /
  `SpotifyAdapters.kt` (`ServiceCapabilities.All`), `provider-subsonic/…/data/SubsonicRuntime.kt`
  (`ServiceCapabilities.Subsonic`), and the Jellyfin set in `MediaModels.kt`.
- Which categories the UI offers: `ServiceCapabilities.availableLibraryFilters` and
  `availableSearchScopes` in the same file, fed to the Library row and the search screens.
- What the user may change on one playlist: `PlaylistActions` in the same file, carried by
  `Playlist.actions` and `Collection.playlistActions`. `ServiceCapabilities.playlistActions(...)`
  combines the service-level flag with the row's own answer.
- Tests: each module's own `src/test` (`app`, `core`, `provider-spotify`,
  `provider-subsonic`, `provider-jellyfin`).
- Jellyfin is implemented through the official Kotlin SDK (music libraries only, no pre-12.x
  servers). It has auth, catalog, writer, runtime and a picker entry — see
  [jellyfin-integration.md](jellyfin-integration.md) for the decisions and the build order.

Legend: ✅ supported & surfaced · ⚠️ implemented in the catalog but not surfaced (capability flag
off / returned as unsupported) · ❌ not supported.

A capability that is off is also visible in the UI: the Library's category row and the search
category row keep the button in place but mute it, drop its click and skip it under the keypad,
and screen readers get "not available" instead of "off". Keeping the slot means the row never
reflows between services; muting it means no button leads to an empty page.

## Playlist actions are per playlist, not just per service

`ServiceCapabilities.supportsPlaylistEdit` says a service has an item-edit transport. It does not
say whether the signed-in account may change a particular playlist. That answer is
`PlaylistActions`:

| Field | Meaning |
| --- | --- |
| `canAddItems` | Appending to this playlist is offered. |
| `canRemoveItems` | Removing an entry from this playlist is offered. |
| `unconfirmed` | The service did not state permissions; the action is offered and a server refusal is reported. |

Add and remove stay separate because a service can grant one without the other. `Playlist.editable`
remains as a compatibility view (`canAddItems || canRemoveItems`), but new UI reads the pair.
`Collection.playlistActions` is the same answer once a playlist page is open, so the row list and
the page cannot disagree.

Deleting a playlist is **not** modelled. No provider has a delete transport; Spotify has no
delete endpoint at all, only "remove from library" (unfollow), which is the existing Save toggle.

| | Spotify | Subsonic family | Jellyfin |
| --- | --- | --- | --- |
| Source of truth | playlist capabilities + owner | playlist `owner` vs signed-in username | no reliable server answer |
| Owned playlist | add + remove | add + remove | offered unconfirmed |
| Collaborative / foreign | `can_edit_items` grants add + remove | read-only | offered unconfirmed |
| Followed, non-collaborative | read-only | read-only | offered unconfirmed |
| Cannot report | read-only (`editable=false`) | offered unconfirmed | offered unconfirmed |
| Read-only fallback | Web API transport → `supportsPlaylistEdit=false` | — | — |

### Playlist item edits

`PlaylistActions` is computed for every playlist row and page, and the writer is now reachable
from the UI on every service that supports editing. The row bar stays inside the shared option
budget: two labelled actions, never a third square.

- **Create** — *New playlist* in the Library → Playlists options menu opens a name-only form.
  On success the new, empty playlist opens. `supportsPlaylistCreate` gates the entry point. A
  refused or failed create stays on the form and reports the service message in the bar; it never
  fails silently.
- **Add** — a track row outside a playlist (liked songs, search, artist, album, radio) offers
  `Add to queue | Add to playlist`. The picker lists only playlists whose `canAddItems` is true,
  so a read-only target never becomes a button that only ends in a server refusal. Adding appends;
  the writer returns the index, and the bar offers Undo that removes exactly that copy. Episodes
  are excluded because playlists are track-only.
- **Remove** — a track row inside an open playlist offers `Add to queue | Remove from playlist`
  when the collection's `canRemoveItems` is true. Removal is destructive and cannot restore the
  original slot, so it uses a two-step bar confirmation (`Cancel | Remove`) rather than Undo.
  The row page drops the confirmed occurrence and keeps the paging offset aligned with the rows
  still loaded.
- **Rejected** — Now Playing is already at its documented three-option cap; a permanent third
  square on playlist pages would make one of the two above actions unreachable. Rename, delete
  and reorder have no transport in any provider and therefore no UI.

The remaining model gap: `MediaLibraryWriter.addToPlaylist` has no optional index
(`MusicService.kt:128`), so a remove cannot be undone back into its original position. Spotify
(playlist4 insert) and Navidrome (`songIndexToAdd`) could honour one later; Jellyfin can only
append. That is why remove confirms instead of offering Undo.

---

## Overview

| Capability | Spotify | Subsonic family | Jellyfin |
| --- | :---: | :---: | :---: |
| Library sections | Liked, Playlists, Albums, Artists, Shows | Liked, Playlists, Albums, Artists | Liked, Playlists, Albums, Artists |
| Search scopes | Tracks, Artists, Albums, Playlists, Shows | Tracks, Artists, Albums, Playlists | Tracks, Artists, Albums, Playlists |
| Podcasts / shows | ✅ | ❌ | ❌ (music libraries carry none) |
| Song radio | ✅ | ✅ | ✅ (InstantMix) |
| Follow artist | ✅ | ❌ | ✅ (favorite artist) |
| Playlist editing (add/remove items) | ✅ per playlist | ✅ owned playlists | ✅ offered unconfirmed |
| Playlist creation | ❌ (pending transport) | ✅ | ✅ |
| Playlist deletion | ❌ (unfollow only) | ❌ | ❌ |
| Loudness normalization | ✅ | ✅ | ❌ (no ReplayGain parameter) |
| Gapless playback | ✅ | ❌ | ❌ (engine-level) |
| Quality selection | ✅ | ✅ | ✅ (`maxStreamingBitrate`) |
| Progress reporting (resume) | ✅ | ✅ | ✅ (`reportPlaybackStart/Progress/Stopped`) |
| Scrobbling | ✅ | ✅ | ✅ (`markPlayedItem` on a real end, D12) |
| Sign-in | OAuth (PKCE) via browser | Username/password against a `ping` | Username/password against the SDK; Quick Connect deferred |

---

## Spotify — feature complete

Spotify reports `ServiceCapabilities.All`: every capability this build knows about.
Implemented and tested:

- **Library**: liked/saved tracks, playlists, albums, artists and shows.
- **Search**: across tracks, artists, albums, playlists and shows in one place.
- **Podcasts**: show notes, episode numbers and dates, per-episode resume, ±30-second controls,
  no song-style shuffle/repeat.
- **Playback**: play/pause/skip/seek/shuffle/repeat/volume, queue (reorder, history),
  normalization, gapless, quality selection.
- **Radio**: song radio / station from any seed.
- **Saving**: like tracks, save albums and shows, follow artists, create playlists, and add or
  remove playlist items. Item edits are offered only on a playlist the account owns or
  collaborates on. The native bridge reports
  `canAddItems`/`canRemoveItems` from the playlist's capabilities, and a refused write is surfaced
  as `ServiceFailure.NotAllowed`, not as a network error.
- **Progress & scrobbling**: resume points and scrobble reporting.

Coverage (unit + Robolectric, `app/src/test/...`) includes: auth/PKCE, session cache, web API
mapping, pagination, queue/history repositories, resume persistence, artist follow, playlist
edit, radio, episodes/metadata/progress, fallback state and view-model events. Real-playback
instrumentation lands under `app/src/androidTest` (`SpotifyPlaybackServiceInstrumentedTest`).

---

## Subsonic family (Navidrome, Nextcloud Music, Subsonic)

The family is implemented through the Subsonic/OpenSubsonic API. It is **not** feature-complete;
it covers music library, search, library editing, quality/DSP settings and Media3 playback, with
the narrower capability set below.

`provider-subsonic` holds one adapter. `SubsonicFamily` (in `data/SubsonicFamily.kt`) is the only
thing that varies between the three picker entries: the `ServiceId`, the display name, the optional
public demo, and the derived credential/settings/queue/history store names. `AppSession.build`
routes every unknown-to-`when` service id through `SubsonicFamily.of(id)`, so adding another
Subsonic-compatible server is one enum entry, not a new client. Any entry connects to any
Subsonic server: the picker names the product, it does not constrain the credentials, and servers
without their own row use the generic **Subsonic** entry. The id still matters, because it is what
keeps two servers on the same device apart in `MediaKey`, the encrypted credential store and the
local queue/history files.

Only the Navidrome entry offers the public demo (`demo.navidrome.org`); the other entries leave the
password form literal.

### Implemented

- **Auth**: username/password against a `ping`, using the Subsonic token (MD5 salt + password)
  rather than sending the password; credentials persisted via `SubsonicCredentialStore`.
- **Library** (`SubsonicCatalog`):
  - Liked (starred) tracks, playlists, albums, artists.
  - Album and playlist collections with pagination.
  - Artist detail, artist albums and top tracks.
- **Search**: via `search3`, mapping each requested scope (tracks, artists, albums, playlists).
- **Playback**: Subsonic `stream` URL with authenticated headers, played through the shared
  `PlaybackController` over Media3/ExoPlayer, so the media session, notification and audio focus
  behave exactly as for Spotify.
- **Library editing** (`MediaLibraryWriter`): star/unstar (like/save), create a playlist, and add
  to / remove from playlists from the row bar. Removal takes the row the caller acted on
  (`index: Int? = null`): Subsonic removes by
  position, so with the same track twice the named row decides. Without one the last match goes,
  which is the copy an append leaves at the end. A row that no longer holds the item is ignored,
  not trusted.
- **Playlist item permissions**: Subsonic has no per-playlist permission flag, so the adapter
  compares the playlist's `owner` with the signed-in username. A foreign playlist reports
  `PlaylistActions.None` and the writer refuses before sending `updatePlaylist`. A playlist with
  no owner is offered as unconfirmed, because the server did not describe it; a playlist the
  account just created is always editable.
- **Radio**: `similarSongs` with a `randomSongs` fallback for servers without Last.fm, surfaced
  because `supportsRadio` is now true.
- **Create playlist**: a *New playlist* action in the Library → Playlists options menu opens a
  name-entry screen and, on success, opens the new playlist. On the wire it is Subsonic
  `createPlaylist`; `supportsPlaylistCreate` gates the entry point. Spotify's create transport
  (the native bridge has no endpoint) is still undecided, so `ServiceCapabilities.All` leaves the
  flag off for it.
- **Audio quality / bitrate**: the selected `AudioQuality` maps to Subsonic `maxBitRate` on the
  `stream` URL. `High` (the default) leaves the server free to pick its best bitrate so FLAC
  isn't silently capped; `Normal` → 96 kbit/s and `Max` → 320 kbit/s force a transcode ceiling.
  The ceiling applies to the next track, not the one already playing.
- **Normalization**: Subsonic `stream` gets `replayGain=true` when the *Normalize volume* toggle
  is on.
- **Progress & scrobbling**: resume points and scrobble reporting flags are on.
- **Isolation**: separate queue, history, resume and settings stores, so switching services never
  mixes two accounts.

### Not supported (not yet)

- **Podcasts / shows**: no `Shows` library section and no `Shows` search scope; `supportsPodcasts`
  is false. This is upstream, not a Sidetrack omission: Navidrome registers every Subsonic
  podcast endpoint (`getPodcasts`, `getNewestPodcasts`, `refreshPodcasts`, the channel and episode
  calls) as an HTTP 501 stub, because mainline Navidrome has no podcast library at all. Podcast
  support lives only in unmerged upstream work (navidrome/navidrome#3150, #5949) and in forks, so
  there is nothing for a client to read. Adding it here would mean the two calls plus a *Show*
  collection and episode mapping, gated on a runtime probe rather than on the provider name, since
  a fork or a future release would answer them.
- **Follow artist** ❌: `isFollowed` returns `null` and `setFollowed` is a no-op;
  `supportsFollowArtist` is false.
- **Gapless** ❌: `supportsGapless` stays false until the Media3 engine applies per-track
  seamless handoff. The preference is already persisted (`setGapless`) so it is ready to flip on.

The missing features surface as muted, unreachable categories and hidden settings rows, not as
buttons that open an empty page: the Library category row and the *Search in* screen read
`availableLibraryFilters` / `availableSearchScopes`, `setFilter`/`toggleScope` ignore a category
the service cannot answer, and the quality/normalization/gapless rows are gated by their flags.

### Coverage

- `SubsonicApiTest.kt` — unit tests: host normalization, ping/token auth, Subsonic error →
  `ServiceFailure` mapping, artist/album/playlist mapping with authenticated cover URLs, `search3`
  scope mapping, album pagination, stream/cover URL auth (no password), starred/saved state,
  catalog stream resolution + playlist pagination, `stream` `maxBitRate`/`replayGain` params,
  `createPlaylist` mapping,
  catalog applying quality/normalization from settings to the stream, and writer star/edit.
- `MusicServiceTest.kt` — asserts `ServiceCapabilities.Subsonic` advertises the delivered parity
  capabilities (radio, quality selection, normalization, playlist edit, progress, scrobbling) and
  keeps the not-yet-supported ones off (podcasts, follow-artist, gapless). Also pins the derived
  category sets: the family offers four library filters and four search scopes, `Shows` included
  in neither, while `ServiceCapabilities.All` keeps every category selectable.
- `LibraryFilterRowTest.kt` / `SearchFilterRowTest.kt` — assert that a switched-off capability is
  surfaced honestly on the Subsonic family: the `Shows` tile keeps its slot, is announced as "not available",
  takes no click, is skipped by Left/Right, and cannot be turned back on from the *Search in*
  screen. `setFilter`/`toggleScope` refuse it as well, so no selection can reach an empty page.
- `SubsonicIntegrationTest.kt` — opt-in smoke tests against a real Navidrome/Subsonic server
  (env: `NAVIDROME_TEST_URL` / `_USER` / `_PASSWORD`; skipped when absent): reads artists/albums/
  tracks, resolves stream and artwork URLs, pages the library and writes stars, reads and edits a
  playlist. The playlist edit appends a track the playlist does not already hold and asserts the
  ordered track ids before, after the append and after the removal — a size check would hide a
  rotation. `removing a duplicated track leaves the other entries in place` builds the duplicate
  case against the server (append two spare tracks, duplicate one, remove it) and restores the
  playlist exactly, so the defect recorded in
  [release-readiness-jellyfin-navidrome.md](release-readiness-jellyfin-navidrome.md) §1.4 stays
  covered. It and `removing a duplicated track removes the occurrence asked for` in
  `SubsonicApiTest` are the two halves of that fix: one against a server, one offline.
---

## Jellyfin (official Kotlin SDK)

Jellyfin is implemented through `org.jellyfin.sdk:jellyfin-core` 1.9.4 (LGPL-3.0), which speaks
Jellyfin 12.x. 10.x is out of scope **and refused**: `SdkJellyfinAuthenticator` reads
`/System/Info/Public` before it sends the password and rejects a server older than 12.0 by version,
because 10.9 accepts the sign-in and then fails every read (`UserItemDataDto` has no `ItemId`,
which the 12.x SDK requires). See
[release-readiness-jellyfin-navidrome.md](release-readiness-jellyfin-navidrome.md) §2.1.1. The
adapter covers the music library only.

### Implemented

- **Auth** (`JellyfinAuth`): username/password against `/Users/AuthenticateByName`, a session
  token in `JellyfinCredentialStore`, and the SDK's own `AuthorizationHeaderBuilder` so the
  device id matches the one the API client sends. `restore` reads the token without a round trip;
  a revoked token becomes `AuthenticationExpired` on the first request.
- **API seam**: `JellyfinApi` reduces the generated DTOs to provider records; `SdkJellyfinApi`
  is the only class that touches the SDK. A hand-written client could replace it without moving
  the catalog.
- **Library** (`JellyfinCatalog`): liked (favourite) tracks, playlists, albums and favourite
  artists; album and playlist collections with pagination; artist detail, artist albums and top
  tracks; artwork URLs minted per call.
- **Search**: `getSearchHints` split into tracks, artists, albums and playlists; artwork for a
  hint uses the image item id it points at, not the id of the hit.
- **Playback**: `getPostedPlaybackInfo` with a music `DeviceProfile` decides direct play vs
  transcode (D11); the resolved URL and its headers go through the shared `PlaybackController`
  over Media3/ExoPlayer. Quality maps to `maxStreamingBitrate` (D4), `High` leaving the ceiling to
  the server. Artwork and audio carry `Authorization` via `ArtworkAccess` / `PlaybackSource`.
- **Library editing** (`MediaLibraryWriter`): favourite/unfavourite tracks, albums and artists;
  create a playlist; add to and remove from playlists from the row bar. Jellyfin has no separate
  "saved" flag, so favourite is used for both like and save.
- **Playlist item permissions**: Jellyfin does not report them reliably (12.4 answers a creator's
  own fresh playlist with `OpenAccess:false, Shares:[]`), so every playlist reports
  `PlaylistActions.ServerDecides` (`unconfirmed = true`). The action is offered, and the server
  still rejects a write the account may not make.
- **Radio**: `getInstantMixFromItem` from any seed.
- **Progress reporting** (`JellyfinPlaybackReporter`): `reportPlaybackStart` / `Progress` /
  `Stopped`, throttled to 10 seconds, and `markPlayedItem` only when the engine emits its
  end-of-track signal (D12). It subscribes to a separate engine flow, so the ViewModel's event
  queue is untouched. Skipping reports a stop but never a play.
- **Resume**: the account's own `getResumeItems` seeds the resume point at sign-in (D13);
  `postCapabilities` registers the session.
- **Isolation**: separate queue, history, resume and settings stores per service.

### Not supported

- **Podcasts / shows** ❌: a music library has no podcast type, so `Shows` is neither a library
  filter nor a search scope and `supportsPodcasts` is false (D3).
- **Loudness normalization** ❌: Jellyfin exposes no ReplayGain/loudness parameter on the stream
  URL. The preference is persisted but `supportsNormalization` is false, so the row is hidden.
- **Gapless** ❌: engine-level, like Navidrome.
- **Quick Connect** ❌ (deferred): v1 is password sign-in only (D5).
- **Jellyfin 10.x** ❌: SDK 1.9.x requires 12.0.0 or newer (D7).

### Coverage

- `JellyfinApiTest.kt` — SDK DTO → provider mapping (artist/album ids, artwork tag, ticks to
  milliseconds, favourite/played state, search-hint artwork item), the music `DeviceProfile`, and
  the quality → bitrate mapping.
- `JellyfinCatalogTest.kt` — four library sections and `hasMore`, album/playlist collections,
  artist albums/top tracks, search partitioning, InstantMix offset, playback URL/headers and the
  quality ceiling, writer favourites/add/remove/create (the removal passes the row it was given),
  saved state for playlists being absent, and every playlist — from the library, from search, or
  newly created — being offered as editable, because the server never says who may edit one.
- `JellyfinApiTest.kt` — the SDK-to-neutral mapping, the music profile and quality ceiling, and
  `resolvePlaylistEntry`: an entry past the first pages is still found (1,200 entries scanned
  page by page), a named row decides which duplicate is removed, an unnamed removal takes the
  last match, and a stale row or a missing item sends nothing.
- `JellyfinPlaybackReporterTest.kt` — start/progress/stopped ordering, 10-second throttling, a
  single pause report, end-of-track marking played, and a skip stopping without a play.
- `MusicServiceTest.kt` — the alphabetical picker list (`Jellyfin`, `Navidrome`, `Nextcloud
  Music`, `Spotify`, `Subsonic`) and the existing Jellyfin capability/category pins.
- `JellyfinPlaybackUrlTest.kt` / `JellyfinPlaybackChoiceTest.kt` — offline, no server and (for
  the choice test) no SDK: the direct play is `/Audio/{id}/stream?static=true` and never
  `/universal`; no or blank `transcodingUrl` keeps that URL, an absolute one is used as is, a
  relative one is joined to the server root, and a `transcodingUrl` wins over a source that also
  claims `supportsDirectPlay`. This is the branch the 0.4.0 playback regression lived in.
- `JellyfinIntegrationTest.kt` — opt-in smoke tests against a real server
  (`JELLYFIN_TEST_URL` / `_USER` / `_PASSWORD`; skipped when absent): album/track/search reads,
  a resolved stream URL that returns audio bytes with the auth header, and artwork URL minting.
  The dev-container checks named in D14 were run on `jellyfin/jellyfin:12.4`: empty-id
  `createPlaylist` and direct FLAC hold; `editable` derived from `openAccess`/`shares` does not
  (see [release-readiness-jellyfin-navidrome.md](release-readiness-jellyfin-navidrome.md) §2.1).
