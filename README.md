# Sideplay

Sideplay is a keypad-first Spotify app for Sidekit-native phones. It is built as a standalone
APK on top of the shared `sidekit-design` and `sidekit-sdk` artifacts, and appears in the Sidekit
launcher through the native app descriptor.

This repository is the new home for Sideplay. The app code is being moved here from the Sidekit
repository as part of phase 6 of
[`docs/third-party-apps.md`](https://github.com/manuel-rtsch/Sidekit/blob/feat/third-party-apps/docs/third-party-apps.md).
The current `main` branch is the initial project scaffold.

## Package

| Build | Application ID |
| --- | --- |
| Release / debug | `io.github.manuel_rtsch.sideplay` |
| Dev (installs alongside) | `io.github.manuel_rtsch.sideplay.dev` |

GitHub usernames may contain hyphens, but Android application IDs may not. The hyphen is
replaced with an underscore.

## Requirements

- JDK 21
- Android SDK 37
- Android NDK 27.1.12297006 and a Rust toolchain for the librespot bridge (optional; playback
  falls back to the HTTP catalog when the native library is absent)

## Build

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleProfile
./gradlew :app:assembleDev
```

`profile` is an R8 build signed with the local debug key, for judging real-device performance.
`dev` derives from it and installs as `io.github.manuel_rtsch.sideplay.dev`.

## Sidekit artifacts

The release app consumes `hrln.interactive.sidekit:sidekit-design` and
`hrln.interactive.sidekit:sidekit-sdk` from GitHub Packages:

```kotlin
implementation(libs.sidekit.sdk)
```

Local development can resolve unpublished SDK changes from Maven Local after running
`./gradlew publishToMavenLocal` in the Sidekit checkout.

GitHub Packages requires authentication even for public packages. Provide
`sidekitPackagesUser`/`sidekitPackagesToken` in `~/.gradle/gradle.properties`, or let CI use
`GITHUB_ACTOR`/`GITHUB_TOKEN`.

## License

MIT. Sideplay also links librespot; its license and notice ship with the native build.
