# Sidetrack 0.4.0 — test APK

Pre-release build of the provider-agnostic 0.4.0 work (Spotify, Navidrome, Jellyfin), published for
the testing described in
[issue #2](https://github.com/manuel-rtsch/Sidetrack/issues/2).

| File | Commit | Built from |
| --- | --- | --- |
| `sidetrack-dev-0.4.0-47ee5df.apk` | `47ee5df` | `release/v0.4.0` (`dev` build type, `arm64-v8a`) |

## What this build is

- **Installable next to your existing Sidetrack.** Package id is
  `io.github.manuelrtsch.sidetrack.dev` and the launcher label is **Sidetrack Dev**, so your
  regular app and its data are untouched. It does *not* replace the release build.
- **Signed with the Android debug key.** That is why it co-installs rather than upgrades, and why
  Play Protect may show a prompt. It is a test artefact, not a Play Store build.
- **Minified/release-optimised** (the `dev` build type inherits `profile`, which inherits
  `release`), so performance and R8 behaviour are representative of the real release.
- **`arm64-v8a` only.** The two native bridges are ~4.5 MB each, so the APK packages the phone ABI
  and leaves the emulator's `x86_64` bridge out: 11.8 MB instead of 16 MB. Every arm64 phone is
  supported; Android emulators and 32-bit `armeabi-v7a` devices are not.
- **`versionName` reads `0.3.6-34-g47ee5df-dev`.** The version is derived from git tags and there
  is no `v0.4.0` tag yet; the branch, not the version string, identifies this build. It will be
  fixed for the actual release.
- **Built against Sidekit `1.9.0`** from the published Pages repository, so it carries the SDK as
  released, not an unreleased local checkout.

## Install

Over USB with [adb](https://developer.android.com/tools/adb):

```sh
adb install -r sidetrack-dev-0.4.0-47ee5df.apk
```

Or copy the APK to the device and open it, allowing installs from that source when prompted. If a
previous dev build is installed, `adb install -r` upgrades it in place — both are signed with the
debug key. Uninstall first only if that key changed:

```sh
adb uninstall io.github.manuelrtsch.sidetrack.dev
```

## Reporting

Please send findings to [issue #2](https://github.com/manuel-rtsch/Sidetrack/issues/2) — for each
provider, what worked and what did not, with the device/Android version and, where useful, a
logcat excerpt or screenshot.
