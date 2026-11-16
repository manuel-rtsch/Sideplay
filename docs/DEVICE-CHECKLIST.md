# Sidetrack device checklist

Run this on a physical Sidephone before tagging a release. Record the build, device, and result.
Items marked **manual** need a second Spotify client, a waiting period, or hardware that cannot be
driven from `adb`.

**Device:** `SP0100260901847` (SP01_FE_US, 480 × 640, Android 12)  
**Last run:** 2026-11-16, Sidetrack 0.3.1 (`versionCode=89`)

## Install and shell

- [x] Install the profile APK over the previous build.
- [ ] Confirm the installed APK contains the native bridge before testing: `adb shell pm path
      io.github.manuelrtsch.sidetrack`, then check the APK for `lib/*/libsidetrack_spotify.so`.
      A build without `-PsidetrackNative=true` installs and signs in fine but silently runs the
      Web API fallback, so the catalog is empty (see README, "It signs in but nothing loads").
- [x] Launch `SidetrackActivity`; the app resumes without a crash.
- [x] **Manual:** with Sidekit installed, confirm Sidetrack follows the host theme and keypad.
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
- [ ] **Manual:** long-press an upcoming track, drag it above and below its neighbours, and
      release. Confirm the order sticks after reopening the queue, that a drag past the bottom
      edge auto-scrolls, and that swipe-to-remove still works on a row that was not picked up.
- [ ] **Manual:** focus an upcoming track and confirm Enter plays it now. Focus the current row
      and confirm Enter opens Now Playing.
- [ ] **Manual:** on a focused upcoming row, confirm one full-height red Remove square appears
      after the focus settles. Press it and confirm that track is removed, the bar offers Undo,
      and Undo puts it back at the same index.
- [ ] **Manual:** swipe a focused row open, then press Down. Confirm the swipe closes as the
      focus moves and that only the next row's gutter can appear, never both at once.
- [ ] **Manual:** open Options, choose **Reorder**, then Up/Down to step the lifted track. Confirm
      it stays in view, Confirm drops it, and Cancel returns it to where it started without leaving
      the queue. Confirm the bar shows Cancel + Done while reordering.
- [ ] **Manual:** start Reorder while Shuffled, step once, then Cancel. Confirm both the order and
      the **Shuffled** state return exactly. Then start Reorder, let the track end, and confirm the
      mode exits instead of moving a different row.
- [ ] **Manual:** add the same track to the queue twice, remove one copy, and confirm the other
      copy remains and Undo restores only the removed copy.
- [ ] **Manual:** press Clear, confirm Cancel leaves the queue alone, then confirm Clear wipes the
      tail and Undo restores it.
- [ ] **Manual:** toggle shuffle from Options and confirm the **Up next** header says
      **Shuffled**. Manually reorder the tail and confirm shuffle turns off and the header drops
      the label.
- [ ] **Manual:** start Song radio on a slow connection, add or remove a queue track while it
      loads, and confirm the late station does not overwrite that edit. Reopen Song radio and
      confirm it is ready to play.
- [ ] **Manual:** close Sidetrack mid-track, reopen it, and confirm the position and queue are
      preserved. Then resume the same track and press stop from the notification within a few
      seconds; confirm the UI and queue clear normally instead of the stop being swallowed.
- [x] The notification shows artwork, title, artist, and previous/play-pause/next.
- [x] The media session reports the current item and playing state.
- [ ] **Manual:** tapping the notification opens Sidetrack at Now Playing.
- [ ] **Manual:** lock the screen and confirm transport controls still work.
- [ ] **Manual:** unplug headphones and confirm playback pauses.
- [ ] **Manual:** start playback in another media app and confirm Sidetrack releases audio focus.

## Keypad

- [ ] **Manual:** after signing in, Welcome shows the selected keypad, cycles modules on a
      standalone install, and opens About from Options. **Get started** goes Home and a relaunch
      does not show Welcome again.
- [ ] **Manual:** upgrading a signed-in install shows Welcome once. With Sidekit installed, the
      keyboard row shows the host module without offering a switch.
- [ ] **Manual:** T9 typing and suggestions.
- [ ] **Manual:** mini QWERTY navigation with T / D / B / J, Enter, Back, and Options.
- [ ] **Manual:** switch T9 → mini QWERTY while Sidetrack is backgrounded, return, and confirm the
      navigation cross is active without restarting Sidetrack.
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
- [ ] **Manual:** Home shows five recently played items and **See more history** when more exist;
      the Options menu's **History** opens the full list, a focused row reveals Add to queue and
      Remove squares, and left swipe still offers Add to queue.
- [ ] **Manual:** on Now Playing, album, playlist, show, and artist screens, confirm the play
      control and every save/bookmark or follow control use the Library success fill, never the
      case/primary colour outside the action bar.
- [ ] **Manual:** confirm save controls show a hollow bookmark when unsaved and a filled bookmark
      when saved, and that follow/unfollow keeps the same success fill in both states.
- [ ] **Manual:** play a show episode, let it advance from the notification, and confirm it appears
      in Recently played / History with the show name.
- [ ] **Manual:** search results and History fade toward the bottom action bar, and the focused row
      never fades.
