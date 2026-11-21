# Releasing

A release is a tag. Everything after that is automated, and the one thing that is not allowed to
happen silently is shipping an APK without the native Spotify bridge.

## Why the bridge is mandatory

`assembleRelease` can legitimately be built without `libsidetrack_spotify.so`: the Gradle task that
builds it is off by default because it needs a Rust toolchain and the NDK, and local iteration
should not. Such a build does not crash. Spotify falls back to the public Web API, which
rate-limits the desktop client ID, so the app looks fine and then stalls — the shape of bug that
cost a release round to diagnose. 0.4.1 was built with the bridge on a developer machine; CI had
no idea whether that was true.

Three gates now stand in the way:

| Gate | Where | What it proves |
| --- | --- | --- |
| `verifySpotifyNative` | Gradle, before packaging | the generated jni libs hold a real `libsidetrack_spotify.so` for every required ABI, it is above the size floor, and it exports `NativeBridge_nativeConnect` |
| `verifyReleaseApk` | Gradle, after packaging | the packaged APK contains that `.so` for every ABI it is supposed to ship |
| `scripts/verify-apk.sh` | release workflow / by hand | the artefact is signed with the Sidetrack release certificate, declares the expected package and version, and carries the bridge for exactly the advertised ABIs |

`verifySpotifyNative` and `verifyReleaseApk` hang off `assembleRelease`, so a native-less release
build fails on the machine that runs it, not in review. Debug, dev, profile and demo builds stay
native-optional.

The About screen's version card reports which engine the installed APK carries ("Native Spotify
engine" or "Spotify Web API, no native bridge"), so a tester report states it instead of requiring
a guess.

## Cutting a release

1. Merge to `main` and wait for CI. The `Native Spotify bridge` job builds the bridge and packages a
   release APK, so a broken `native/` or a regression in the packaging fails there.
2. Write the notes to `docs/releases/<version>.md`. This file becomes the release body verbatim.
   The workflow refuses to publish without it.
3. Tag and push:

   ```sh
   git tag -a v0.4.2 -m "Sidetrack 0.4.2"
   git push origin v0.4.2
   ```

4. The `Release` workflow checks the tag is on `main`, builds both APKs with
   `-PsidetrackNative=true`, verifies and publishes. Re-running it is safe: an existing release has
   its assets and notes replaced.

`workflow_dispatch` runs the same job for an existing tag, which is how a release is rebuilt after
a failure without force-pushing the tag.

## Repository secrets

| Secret | Contents |
| --- | --- |
| `SIDETRACK_KEYSTORE_BASE64` | the release `.jks`, `base64 -w0` |
| `SIDETRACK_STORE_PASSWORD` | `storePassword` |
| `SIDETRACK_KEY_ALIAS` | `keyAlias` |
| `SIDETRACK_KEY_PASSWORD` | `keyPassword` |

The workflow writes `keystore.properties` (gitignored) from them, which is the same file
`app/build.gradle.kts` reads locally. The certificate is pinned in `scripts/verify-apk.sh`, so an
APK signed with a different key fails the release rather than shipping — Sidekit trusts the app as
a host only by that certificate.

## Building by hand

The workflow has no exclusive logic: it calls `scripts/release.sh`, and so can you.

```sh
scripts/release.sh 0.4.1
```

It requires `cargo`, `cargo-ndk`, the NDK and `ANDROID_HOME`, a clean tree, and `HEAD` to describe
exactly as `v<version>` (`versionName` comes from `git describe`). The output lands in
`build/release/` with `SHA256SUMS.txt`, and each APK has already been through
`scripts/verify-apk.sh`. Publishing by hand is then:

```sh
gh release create v0.4.1 \
  build/release/Sidetrack-0.4.1.apk \
  build/release/Sidetrack-0.4.1-arm64-v8a.apk \
  build/release/SHA256SUMS.txt \
  --notes-file docs/releases/0.4.1.md
```

## Local iteration

Nothing changed here: `./gradlew :app:assembleDebug` (or `installDev`) builds without Rust or the
NDK, which is what makes the loop fast. Add `-PsidetrackNative=true` when the change touches
`native/` or the Spotify bridge.
