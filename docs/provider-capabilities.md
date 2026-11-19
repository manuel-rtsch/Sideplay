# Provider capabilities

Sidetrack speaks to one music service at a time through a provider-neutral domain model
(`MediaCatalog` / `MediaLibraryWriter` / `PlaybackController`). The UI never names a provider;
it asks a service's `ServiceCapabilities` which features to show. This document lists, for each
provider, what is currently **implemented** and what is **covered by tests**.

Sources of truth:

- Capability flags: `core/src/main/java/io/github/manuelrtsch/sidetrack/MediaModels.kt`
  (`ServiceCapabilities`, `ServiceId`), `provider-spotify/…/data/SpotifySession.kt` /
  `SpotifyAdapters.kt` (`ServiceCapabilities.All`), `provider-navidrome/…/data/NavidromeRuntime.kt`
  (`ServiceCapabilities.Navidrome`), and the Jellyfin set in `MediaModels.kt`.
- Which categories the UI offers: `ServiceCapabilities.availableLibraryFilters` and
  `availableSearchScopes` in the same file, fed to the Library row and the search screens.
- Tests: each module's own `src/test` (`app`, `core`, `provider-spotify`,
  `provider-navidrome`, `provider-jellyfin`).
- A third provider, Jellyfin, is **planned, not implemented**. Its identity and capability set
  exist (`ServiceId.Jellyfin`, `ServiceCapabilities.Jellyfin`, pinned by `MusicServiceTest`), but
  there is no auth, catalog, writer or runtime, and no picker entry — see
  [jellyfin-integration.md](jellyfin-integration.md) for the decisions and the build order.

Legend: ✅ supported & surfaced · ⚠️ implemented in the catalog but not surfaced (capability flag
off / returned as unsupported) · ❌ not supported.

A capability that is off is also visible in the UI: the Library's category row and the search
category row keep the button in place but mute it, drop its click and skip it under the keypad,
and screen readers get "not available" instead of "off". Keeping the slot means the row never
reflows between services; muting it means no button leads to an empty page.

---

## Overview

| Capability | Spotify | Navidrome |
| --- | :---: | :---: |
| Library sections | Liked, Playlists, Albums, Artists, Shows | Liked, Playlists, Albums, Artists |
| Search scopes | Tracks, Artists, Albums, Playlists, Shows | Tracks, Artists, Albums, Playlists |
| Podcasts / shows | ✅ | ❌ |
| Song radio | ✅ | ✅ |
| Follow artist | ✅ | ❌ |
| Playlist editing (add/remove items) | ✅ | ✅ |
| Playlist creation | ❌ (pending transport) | ✅ |
| Loudness normalization | ✅ | ✅ |
| Gapless playback | ✅ | ❌ |
| Quality selection | ✅ | ✅ |
| Progress reporting (resume) | ✅ | ✅ |
| Scrobbling | ✅ | ✅ |
| Sign-in | OAuth (PKCE) via browser | Username/password against a `ping` |

Planned, not built ([details](jellyfin-integration.md)):

| Capability | Jellyfin (proposed) |
| --- | :---: |
| Library sections | Liked, Playlists, Albums, Artists |
| Search scopes | Tracks, Artists, Albums, Playlists |
| Podcasts / shows | ❌ (music libraries carry none) |
| Song radio | ✅ (InstantMix) |
| Follow artist | ✅ (favorite artist) |
| Playlist editing / creation | ✅ |
| Loudness normalization | ❌ (no ReplayGain parameter) |
| Gapless playback | ❌ (engine-level) |
| Quality selection | ✅ (`maxStreamingBitrate`) |
| Progress reporting / scrobbling | ✅ (`reportPlaybackStart/Progress/Stopped`, `markPlayedItem`) |
| Sign-in | Username/password or Quick Connect (deferred) |

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
- **Saving**: like tracks, save albums and shows, follow artists, add/remove playlist items.
- **Progress & scrobbling**: resume points and scrobble reporting.

Coverage (unit + Robolectric, `app/src/test/...`) includes: auth/PKCE, session cache, web API
mapping, pagination, queue/history repositories, resume persistence, artist follow, playlist
edit, radio, episodes/metadata/progress, fallback state and view-model events. Real-playback
instrumentation lands under `app/src/androidTest` (`SpotifyPlaybackServiceInstrumentedTest`).

---

## Navidrome (Subsonic adapter)

Navidrome is implemented through the Subsonic API. It is **not** feature-complete; it covers
music library, search, library editing, quality/DSP settings and Media3 playback, with the
narrower capability set below.

### Implemented

- **Auth**: username/password against a `ping`, using the Subsonic token (MD5 salt + password)
  rather than sending the password; credentials persisted via `NavidromeCredentialStore`.
- **Library** (`NavidromeCatalog`):
  - Liked (starred) tracks, playlists, albums, artists.
  - Album and playlist collections with pagination.
  - Artist detail, artist albums and top tracks.
- **Search**: via `search3`, mapping each requested scope (tracks, artists, albums, playlists).
- **Playback**: Subsonic `stream` URL with authenticated headers, played through the shared
  `PlaybackController` over Media3/ExoPlayer, so the media session, notification and audio focus
  behave exactly as for Spotify.
- **Library editing** (`MediaLibraryWriter`): star/unstar (like/save), add to / remove from
  playlist.
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

- `NavidromeApiTest.kt` — unit tests: host normalization, ping/token auth, Subsonic error →
  `ServiceFailure` mapping, artist/album/playlist mapping with authenticated cover URLs, `search3`
  scope mapping, album pagination, stream/cover URL auth (no password), starred/saved state,
  catalog stream resolution + playlist pagination, `stream` `maxBitRate`/`replayGain` params,
  `createPlaylist` mapping,
  catalog applying quality/normalization from settings to the stream, and writer star/edit.
- `MusicServiceTest.kt` — asserts `ServiceCapabilities.Navidrome` advertises the delivered parity
  capabilities (radio, quality selection, normalization, playlist edit, progress, scrobbling) and
  keeps the not-yet-supported ones off (podcasts, follow-artist, gapless). Also pins the derived
  category sets: Navidrome offers four library filters and four search scopes, `Shows` included in
  neither, while `ServiceCapabilities.All` keeps every category selectable.
- `LibraryFilterRowTest.kt` / `SearchFilterRowTest.kt` — assert that a switched-off capability is
  surfaced honestly on Navidrome: the `Shows` tile keeps its slot, is announced as "not available",
  takes no click, is skipped by Left/Right, and cannot be turned back on from the *Search in*
  screen. `setFilter`/`toggleScope` refuse it as well, so no selection can reach an empty page.
- `NavidromeIntegrationTest.kt` — opt-in smoke tests against a real Navidrome/Subsonic server
  (env: `NAVIDROME_TEST_URL` / `_USER` / `_PASSWORD`; skipped when absent): reads artists/albums/
  tracks, resolves stream and artwork URLs, pages the library and writes stars, reads and edits a
  playlist.