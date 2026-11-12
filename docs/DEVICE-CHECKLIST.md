# Sideplay device checklist

Run this on a physical Sidephone before tagging a release. Record the build, device, and result.
Items marked **manual** need a second Spotify client, a waiting period, or hardware that cannot be
driven from `adb`.

**Device:** `SP01GE260600886` (480 × 640, Android 12)  
**Last run:** 2026-11-11, Sideplay 0.1.1 (`versionCode=8`)

## Install and shell

- [x] Install the profile APK over the previous build.
- [x] Launch `SideplayActivity`; the app resumes without a crash.
- [x] **Manual:** with Sidekit installed, confirm Sideplay follows the host theme and keypad.
- [x] **Manual:** repeat with Sidekit absent and confirm the standalone Appearance screen.

## Account and catalog

- [x] Sign in with an existing Spotify account.
- [x] Library loads real albums, playlists, artists, and shows.
- [x] Search returns tracks, artists, albums, playlists, and shows.
- [x] Artist and album pages open without the old collection-contains failure.
- [ ] **Manual:** leave the app signed in for more than one hour, then play a track and confirm
      the rotated token is pushed into the live librespot session.
- [ ] **Manual:** like/unlike a track, save/remove an album and show, follow/unfollow an artist,
      and add/remove a playlist. Confirm each change in another Spotify client and after restart.

## Playback and queue

- [x] Play, pause, next, previous, seek, shuffle, and repeat work.
- [x] The queue advances exactly once at the end of a track.
- [x] The notification shows artwork, title, artist, and previous/play-pause/next.
- [x] The media session reports the current item and playing state.
- [ ] **Manual:** tapping the notification opens Sideplay at Now Playing.
- [ ] **Manual:** lock the screen and confirm transport controls still work.
- [ ] **Manual:** unplug headphones and confirm playback pauses.
- [ ] **Manual:** start playback in another media app and confirm Sideplay releases audio focus.

## Keypad

- [ ] **Manual:** T9 typing and suggestions.
- [ ] **Manual:** mini QWERTY navigation with T / D / B / J, Enter, Back, and Options.
- [ ] **Manual:** switch T9 → mini QWERTY while Sideplay is backgrounded, return, and confirm the
      navigation cross is active without restarting Sideplay.
- [ ] **Manual:** Sundial and game-controller modules.

## Lists and focus

- [ ] **Manual:** search and artist sections show five items and a pressable **See more** row.
- [ ] **Manual:** pressing **See more** loads the next page; it does not preload.
- [ ] **Manual:** long playlists and shows scroll without losing focus.
- [ ] **Manual:** Library shows a short section for liked songs, playlists, albums, artists and
      shows, and each **See more** opens that category with focus on the first row.
- [ ] **Manual:** playlists inside Spotify folders show the folder path above the owner.
- [ ] **Manual:** Search opens **Search in** and toggling a category changes results without
      opening Options.
