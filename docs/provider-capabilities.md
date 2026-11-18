# Provider capabilities

Sidetrack speaks to one music service at a time through a provider-neutral domain model
(`MediaCatalog` / `MediaLibraryWriter` / `PlaybackController`). The UI never names a provider;
it asks a service's `ServiceCapabilities` which features to show. This document lists, for each
provider, what is currently **implemented** and what is **covered by tests**.

Sources of truth:

- Capability flags: `app/src/main/java/io/github/manuelrtsch/sidetrack/MediaModels.kt`
  (`ServiceCapabilities`), `SpotifySession.kt` / `SpotifyAdapters.kt` (`ServiceCapabilities.All`),
  `NavidromeRuntime.kt` (`ServiceCapabilities.Navidrome`).
- Tests: `app/src/test/java/io/github/manuelrtsch/sidetrack/`.

Legend: ✅ supported & surfaced · ⚠️ implemented in the catalog but not surfaced (capability flag
off / returned as unsupported) · ❌ not supported.

---

## Overview

| Capability | Spotify | Navidrome |
| --- | :---: | :---: |
| Library sections | Liked, Playlists, Albums, Artists, Shows | Liked, Playlists, Albums, Artists |
| Search scopes | Tracks, Artists, Albums, Playlists, Shows | Tracks, Artists, Albums, Playlists |
| Podcasts / shows | ✅ | ❌ |
| Song radio | ✅ | ⚠️ |
| Follow artist | ✅ | ❌ |
| Playlist editing (add/remove items) | ✅ | ✅ |
| Loudness normalization | ✅ | ❌ |
| Gapless playback | ✅ | ❌ |
| Quality selection | ✅ | ❌ |
| Progress reporting (resume) | ✅ | ✅ |
| Scrobbling | ✅ | ✅ |
| Sign-in | OAuth (PKCE) via browser | Username/password against a `ping` |

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
music library, search, library editing and Media3 playback, with the narrower capability set
below.

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
- **Progress & scrobbling**: resume points and scrobble reporting flags are on.
- **Isolation**: separate queue, history, resume and settings stores, so switching services never
  mixes two accounts.

### Present in the catalog but not surfaced

- **Radio** ⚠️: `NavidromeCatalog.radio` exists, built on `similarSongs` with a `randomSongs`
  fallback for servers without Last.fm, but `supportsRadio` is false in `ServiceCapabilities.
  Navidrome`, so the UI hides the radio action.

### Not supported

- **Podcasts / shows**: no `Shows` library section and no `Shows` search scope; `supportsPodcasts`
  is false.
- **Follow artist** ❌: `isFollowed` returns `null` and `setFollowed` is a no-op;
  `supportsFollowArtist` is false.
- **DSP settings** ❌: `supportsNormalization`, `supportsGapless` and `supportsQualitySelection`
  are all false.

### Coverage

- `NavidromeApiTest.kt` — unit tests: host normalization, ping/token auth, Subsonic error →
  `ServiceFailure` mapping, artist/album/playlist mapping with authenticated cover URLs, `search3`
  scope mapping, album pagination, stream/cover URL auth (no password), starred/saved state,
  catalog stream resolution + playlist pagination, and writer star/edit.
- `NavidromeIntegrationTest.kt` — opt-in smoke tests against a real Navidrome/Subsonic server
  (env: `NAVIDROME_TEST_URL` / `_USER` / `_PASSWORD`; skipped when absent): reads artists/albums/
  tracks, resolves stream and artwork URLs, pages the library and writes stars, reads and edits a
  playlist.