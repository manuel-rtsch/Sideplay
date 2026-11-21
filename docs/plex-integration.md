# Plex integration

Sidetrack's Plex adapter is a thin translation between Plex Media Server's HTTP API and the
neutral `MediaCatalog` / `MediaLibraryWriter` / `ServiceRuntime` contracts. This note records the
endpoint decisions that are not obvious from the API surface, so the next change does not have to
rediscover them.

## Identity and sign-in

- **Sign-in is a PIN on plex.tv.** `POST https://plex.tv/api/v2/pins?strong=true` returns an
  `id` and a `code`; the browser opens
  `https://app.plex.tv/auth#?clientID=<clientIdentifier>&code=<code>` and the app polls
  `GET /api/v2/pins/<id>` until `authToken` appears. Sidetrack never sees the password.
- **The server token comes from the resource, not the pin.** Once the account token exists,
  `GET /api/v2/resources?includeHttps=1&includeRelay=1` lists the account's servers; the
  `accessToken` on the chosen `PlexResource` is what the server accepts. The pin's account token
  is only for plex.tv.
- **The client identifier is derived from `ANDROID_ID`** (`plexClientIdentifier(context)`), so a
  reinstall keeps the same Plex device row instead of creating a new one.
- **Discovery prefers plain HTTP.** `plex.direct` certificates are not trusted by Android's
  system store, so the adapter tries `http://<address>:<port>` candidates before the advertised
  `https://…plex.direct` URIs. A server is only accepted when `/identity` reports `claimed="1"`.
- **Credentials** (`server_url`, `access_token`, `client_identifier`, `user_name`,
  `machine_identifier`) live in `GuardedEncryptedStore`. The token is also installed into
  `ArtworkAccess`, because Compose's image loader cannot attach per-request headers.

## Library

- `GET /library/sections` returns `Directory[]`; **music sections have `type="artist"`**. The
  adapter reads the first one and ignores movie/show sections.
- Section items use `X-Plex-Container-Start` / `X-Plex-Container-Size`; `totalSize` drives
  `Page.hasMore`. Item types are `8` artist, `9` album, `10` track (the JSON names are
  `artist`/`album`/`track`).
- **Liked tracks** are `userRating >= 8`; Plex has no separate favourite flag. The parameter name
  carries the operator: `userRating%3E=8`. A fully encoded `%3D` is rejected.
- **Saving** writes a five-star rating: `PUT /:/rate?key=<ratingKey>&identifier=com.plexapp.plugins.library&rating=10`
  (or `rating=0` to unsave).
- Detail: `GET /library/metadata/{ratingKey}`, children at `…/children`, all artist tracks at
  `…/allLeaves`.
- **Search** is `GET /hubs/search?query=…&limit=…`; the returned `Hub[]` are typed
  `track`/`artist`/`album`/`playlist`. Hubs are not paginated, so one request fills every scope.

## Playback

- The playable file is `Media[].Part[].key`, e.g. `/library/parts/1/…/file.mp3`. It answers `200`
  with `audio/mpeg` and `Accept-Ranges: bytes`, and requires the `X-Plex-Token` header.
  `/library/metadata/{id}/file.mp3` is **not** a stream endpoint.
- Plex's `Part.key` is already a direct file URL, so v1 does not transcode and the quality
  setting stays off. A future transcode path would use
  `/music/:/transcode/universal/start?path=…&maxAudioBitrate=…`.
- `/library/metadata/{id}/similar` and `/mix` returned empty/404 on the tested server, so the
  adapter reports no radio rather than showing an empty station.

## Artwork

- Track rows carry no cover of their own; the album's `thumb`/`parentThumb` is used. Album and
  artist rows use their own `thumb`.
- Thumb paths are server-relative and need the token header. For a resized image, Plex offers
  `/photo/:/transcode?url=<encoded thumb>&width=<w>&height=<h>`; `artUrl` currently returns the
  original path and lets the image loader scale it.

## Playlists

- `GET /playlists?playlistType=audio` lists audio playlists; item type is `playlist`.
- Items are at `GET /playlists/{key}/items`; each item carries a **`playlistItemID`**, which is
  what removal addresses. Removing an item is
  `DELETE /playlists/{key}/items/{playlistItemID}` (not a track id).
- Adding is `PUT /playlists/{key}/items?uri=server://<machineIdentifier>/com.plexapp.plugins.library/library/metadata/<ratingKey>`
  (URL-encoded). `POST` returns 404.
- **Creation requires a non-empty `uri`**:
  `POST /playlists?title=…&type=audio&smart=0&uri=server://<machine>/…/library/metadata/<seed>`.
  Without a `uri` the server answers `400`. A name-only form has no seed yet, so the adapter
  exposes no create button.
- **Smart playlists are read-only.** The `smart` flag maps to `PlaylistActions.None`; other
  playlists allow add and remove.

## Progress and scrobbling

- `POST /:/timeline?ratingKey=<id>&state=<playing|paused|stopped>&time=<ms>&duration=<ms>` keeps
  the Plex dashboard current. The reporter sends start, pause, resume, every 10 seconds while
  playing, and one `stopped` when the track changes or the player stops.
- `POST /:/scrobble?key=<id>&identifier=com.plexapp.plugins.library` increments `viewCount` and
  is sent **only** on the engine's real end-of-track signal. A skip sends `stopped` but never a
  scrobble.
- Both calls are fire-and-forget on an IO scope; a slow server cannot stall playback.

## Demo container

The local demo is a `linuxserver/plex` container with a read-only music mount at `/music` and a
claimed server. The library was created with a **singular `location=`** query parameter:

```text
POST /library/sections?X-Plex-Token=…&name=Music&type=artist&agent=tv.plex.agents.music&scanner=Plex%20Music&language=en-US&location=/music
```

`locations=` (plural) creates nothing. `customConnections` can be set through `/:/prefs` so
plex.tv advertises a LAN address other devices can reach.

## Tests

`provider-plex` runs offline unit tests by default. The integration suite is opt-in:

```bash
PLEX_TEST_URL=http://127.0.0.1:32400 \
PLEX_TEST_TOKEN=<server token> \
./gradlew :provider-plex:testDebugUnitTest -PsidetrackIntegration --tests '*PlexIntegrationTest'
```

It skips when the variables are absent. The token is the server token from the account resource
(or `Preferences.xml` on the demo container), not the plex.tv account token.
