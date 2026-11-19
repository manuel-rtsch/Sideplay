<p align="center">
  <img src="design/app-icon/sidetrack-icon-512.png" width="128" alt="Sidetrack icon">
</p>

<h1 align="center">Sidetrack</h1>

<p align="center"><strong>Spotify for your Sidephone, built for the keypad.</strong></p>

<p align="center">
  <a href="https://github.com/manuel-rtsch/Sidetrack/releases/latest"><img src="https://img.shields.io/github/v/release/manuel-rtsch/Sidetrack?label=release&color=1db954" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/manuel-rtsch/Sidetrack?color=1db954" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/platform-Android%2012%2B-3ddc84" alt="Platform: Android 12+">
  <a href="https://www.sidephone.com/"><img src="https://img.shields.io/badge/made%20for-Sidephone%20SP--01-1db954" alt="Made for Sidephone SP-01"></a>
</p>

Sidetrack is a Spotify player for [Sidekit](https://github.com/HarlenPostill/Sidekit), the
keypad-first launcher and app suite for the [Sidephone SP-01](https://www.sidephone.com/). It
follows Sidekit's theme, accent, input mode and key mappings when Sidekit is installed, and also
runs standalone on any Android 12+ phone with a touchscreen or a physical keypad.

> Sidetrack is an independent project. It is not made by or affiliated with Spotify.

## What you can do

- **Listen to your library** — playlists, albums, artists, shows and saved tracks.
- **Listen to podcasts properly** — show notes, episode numbers and dates, per-episode resume,
  ±30-second controls, and no song-style shuffle or repeat.
- **Search everything** — tracks, artists, albums, playlists and shows in one place.
- **Control playback** — play, pause, skip, seek, shuffle, repeat, and volume with the phone's
  own keys. Loudness is normalized and sequential tracks join without a gap.
- **Keep a queue** — add tracks or whole collections, reorder, and see what played before.
- **Start a song radio** — build a station from whatever is playing.
- **Save what you like** — like tracks, save albums and shows, follow artists, add playlists.
- **Use the keypad** — T9, mini QWERTY, Sundial and game controller are supported. On mini
  QWERTY, **T / D / B / J** move, **Enter** selects, **Back** goes back, and the top-right key
  opens Options.
- **See what is playing** — a full Now Playing screen, lock-screen controls, and a notification
  with artwork and transport buttons.

## Screenshots

| Home | Search | Now Playing | Queue |
| --- | --- | --- | --- |
| ![Home](app/screenshots/sidetrack_home.png) | ![Search](app/screenshots/sidetrack_search.png) | ![Now Playing](app/screenshots/sidetrack_now_playing.png) | ![Queue](app/screenshots/sidetrack_queue.png) |

| Album | Artist | Library | Show |
| --- | --- | --- | --- |
| ![Album](app/screenshots/sidetrack_album.png) | ![Artist](app/screenshots/sidetrack_artist.png) | ![Library](app/screenshots/sidetrack_library.png) | ![Show](app/screenshots/sidetrack_show.png) |

| Notes | Episode | Welcome | Keyboard |
| --- | --- | --- | --- |
| ![Show notes](app/screenshots/sidetrack_notes.png) | ![Now Playing episode](app/screenshots/sidetrack_now_playing_episode.png) | ![Welcome](app/screenshots/sidetrack_welcome.png) | ![Keyboard](app/screenshots/sidetrack_keyboard.png) |

| History | Sleep timer | Settings | Sign in |
| --- | --- | --- | --- |
| ![History](app/screenshots/sidetrack_history.png) | ![Sleep timer](app/screenshots/sidetrack_sleep_timer.png) | ![Settings](app/screenshots/sidetrack_settings.png) | ![Sign in](app/screenshots/sidetrack_sign_in.png) |

## Getting started

1. Install [Sidekit](https://github.com/HarlenPostill/Sidekit/releases/latest) on your Sidephone
   if you want the full launcher and app suite. Sidetrack works without it too.
2. Install the Sidetrack APK.
3. Open Sidetrack, choose **Spotify**, **Navidrome** or **Jellyfin**, and sign in. Jellyfin
   needs a 12.x server (the Kotlin SDK's minimum) with a music library. To try the app without an
   account or a server, choose **Navidrome** and leave the sign-in form empty: it signs into the
   shared demo at [demo.navidrome.org](https://demo.navidrome.org) (`demo` / `demo`).
4. On the one-time **Welcome** screen, learn the keypad habits: **Back** returns, **Options**
   opens the page's extra actions, holding **Options** jumps to the action bar, and on touch you
   drag a row left to reveal its menu. Open **Keyboard** to switch modules or enable the
   **Keypad Back key**, then press **Get started**.
5. Pick something from your library, or press **Search** and start typing.
6. Press **OK** / **Enter** to play. The now-playing confirmation in the action bar opens Now
   Playing; pressing **OK** / **Enter** on the current track does the same.

Lists show a few items at a time. When there is more, a **See more** row loads the next page.

## Building from source

You need JDK 21 and the Android SDK. For real playback through librespot, you also need the
Android NDK and a Rust toolchain.

```sh
./gradlew :app:assembleProfile -PsidetrackNative=true
```

The native bridge is required for library and search; without it the app falls back to the
public Spotify Web API, which currently rate-limits the desktop client ID.

## License

MIT. Sidetrack also links librespot; its license and notice ship with the native build.

Exception: `app/src/main/java/io/github/manuelrtsch/sidetrack/auth/` was written with the GPLv3
Sidespot auth path as a reference and is therefore a derivative of it. That package is pending a
clean-room rewrite or a GPLv3 notice.
