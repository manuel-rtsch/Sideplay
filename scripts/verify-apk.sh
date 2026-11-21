#!/usr/bin/env bash
#
# Asserts that a built APK is fit to be published as a Sidetrack release.
#
# Gradle already refuses to package a release without the native bridge (verifySpotifyNative and
# verifyReleaseApk), but those tasks read the build's own output. This script reads the artefact
# that would actually be attached to the release: its ABI set, its signature certificate, the
# version it claims, and the symbols inside the shipped librespot bridge. It is the check the
# release workflow runs, and the same one can be run by hand before uploading anything.
#
# Usage: scripts/verify-apk.sh <apk> <expected-version> <abi[,abi…]>
#   e.g. scripts/verify-apk.sh build/release/Sidetrack-0.4.1.apk 0.4.1 arm64-v8a,x86_64

set -euo pipefail

apk=${1:?usage: verify-apk.sh <apk> <expected-version> <abi[,abi…]>}
version=${2:?usage: verify-apk.sh <apk> <expected-version> <abi[,abi…]>}
abis=${3:?usage: verify-apk.sh <apk> <expected-version> <abi[,abi…]>}

# The Sidetrack release certificate. Every published APK is signed with it, which is also what
# makes Sidekit trust the app as a host, so a release signed with any other key is a broken
# release even when it installs.
readonly RELEASE_CERT_SHA256="510311577a779a3da6efbbf5de5fa056b8bb2e7bf6924c57d9d783f4e4fd74ac"
readonly BRIDGE_SO="libsidetrack_spotify.so"
readonly BRIDGE_SYMBOL="Java_io_github_manuelrtsch_sidetrack_bridge_NativeBridge_nativeConnect"
readonly BRIDGE_SIZE_FLOOR=1000000

fail() {
  printf 'verify-apk: %s\n' "$*" >&2
  exit 1
}

[ -f "$apk" ] || fail "no such APK: $apk"

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
build_tools=$(ls -d "$sdk_root"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "$build_tools" ] || fail "no build-tools under ${sdk_root:-<unset ANDROID_HOME>}; set ANDROID_HOME"

aapt2="$build_tools/aapt2"
apksigner="$build_tools/apksigner"
[ -x "$aapt2" ] || fail "aapt2 not found in $build_tools"
[ -x "$apksigner" ] || fail "apksigner not found in $build_tools"

# --- identity -------------------------------------------------------------------------------
badging=$("$aapt2" dump badging "$apk")
apk_version=$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")
apk_code=$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<<"$badging")

[ "$apk_version" = "$version" ] ||
  fail "$(basename "$apk") claims versionName '$apk_version', expected '$version'"
[[ "$apk_code" =~ ^[0-9]+$ ]] || fail "$(basename "$apk") has no numeric versionCode ('$apk_code')"

package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")
[ "$package" = "io.github.manuelrtsch.sidetrack" ] ||
  fail "$(basename "$apk") declares package '$package'"

# --- signature ------------------------------------------------------------------------------
if ! certs=$("$apksigner" verify --print-certs "$apk" 2>&1); then
  fail "$(basename "$apk") is not signed: $certs"
fi
# apksigner labels the signer differently per scheme ("V2 Signer: certificate …",
# "Signer #1 certificate …"), so match the digest rather than the prefix.
cert=$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$certs" | head -1 | tr -d ':' | tr 'A-F' 'a-f')
[ -n "$cert" ] || fail "$(basename "$apk") has no signer certificate"
[ "$cert" = "$RELEASE_CERT_SHA256" ] ||
  fail "$(basename "$apk") is signed by $cert, not the Sidetrack release certificate $RELEASE_CERT_SHA256"

# --- native bridge --------------------------------------------------------------------------
entries=$(unzip -Z1 "$apk")

mapfile -t packaged_abis < <(
  grep -F "/$BRIDGE_SO" <<<"$entries" | sed -n "s#^lib/\([^/]*\)/$BRIDGE_SO\$#\1#p" | sort
)

expected=$(tr ',' '\n' <<<"$abis" | sort)
if [ "$(printf '%s\n' "${packaged_abis[@]}")" != "$expected" ]; then
  fail "$(basename "$apk") carries the bridge for [${packaged_abis[*]:-none}], expected [$abis]"
fi

# The app ships androidx's own `libandroidx.graphics.path.so` for a few more ABIs than the bridge
# covers (armeabi-v7a and x86 exist only for that shim), so the assertion is on the bridge's ABI
# set exactly, not on every lib/ directory in the archive.

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for abi in "${packaged_abis[@]}"; do
  unzip -qo "$apk" "lib/$abi/$BRIDGE_SO" -d "$tmp"
  so="$tmp/lib/$abi/$BRIDGE_SO"
  size=$(wc -c < "$so")
  [ "$size" -ge "$BRIDGE_SIZE_FLOOR" ] ||
    fail "lib/$abi/$BRIDGE_SO is only $size bytes; that is not a librespot build"
  grep -aqF "$BRIDGE_SYMBOL" "$so" ||
    fail "lib/$abi/$BRIDGE_SO does not export $BRIDGE_SYMBOL"
  # Linked Rust, not a stub: the bridge pulls in librespot itself.
  grep -aqF "librespot-core" "$so" ||
    fail "lib/$abi/$BRIDGE_SO has no librespot-core references"
done

printf 'verify-apk: %s ok — %s %s (%s), signed, bridge for %s\n' \
  "$(basename "$apk")" "$package" "$apk_version" "$apk_code" "${packaged_abis[*]}"
