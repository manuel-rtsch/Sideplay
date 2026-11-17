# libsidetrack_spotify

The Sidetrack native bridge. It links librespot 0.8.0 and exposes the JNI surface declared in
`bridge/NativeBridge.kt`.

## Toolchain

- Rust stable. The first working build used 1.99.1.
- Android targets: `aarch64-linux-android`, `x86_64-linux-android`.
- `cargo-ndk` 4.1.2.
- Android NDK 27.1.12297006 (the version pinned by the app module).

## Build

Gradle builds it when native playback is enabled with `-PsidetrackNative=true`:

```sh
./gradlew :app:buildSpotifyNative -PsidetrackNative=true
```

To run Cargo directly:

```sh
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.1.12297006"
cargo ndk -t arm64-v8a -t x86_64 \
  -o ../../app/build/generated/spotifyJniLibs build --release
```

The output directory is a `jniLibs` source set. Without `-PsidetrackNative=true`, Cargo, or the NDK, Gradle skips
the task and the APK has no library; `NativeLibraries.load("sidetrack_spotify")` then returns false.

## Dependency pins

`Cargo.lock` pins librespot and the full graph. `vergen` is held at 9.0.6 because
`vergen-gitcl` 1.0.8 depends on `vergen-lib` 0.1.6 while `vergen` 9.1.0 pulled a second
incompatible `vergen-lib`. Do not update that crate without rebuilding Android targets.

One minimal librespot-core patch is applied through `native/vendor/librespot-core` (platform
constant set to `"linux"` so Login5/client-token requests match the desktop client id).
Upstream MIT licence: `LICENSE-librespot`.
