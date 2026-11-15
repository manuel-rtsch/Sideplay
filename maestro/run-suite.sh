#!/usr/bin/env bash
# Run the whole Maestro suite, setting a different accent colour before each flow,
# and report per-flow length.
#
# The flows run in config.yaml order. Before each one, set-accent.yaml changes
# Sidekit's host-backed case colour and restarts Sidetrack so the flow records with
# that accent. The accent steps write to a separate directory, so the timing
# report only sees the suite flows.
#
# System animations are turned off for the whole run and restored afterwards,
# matching what record.sh does. Maestro otherwise pays for an animated window on
# every tap: ~3.3 s per tap instead of ~2.0 s on this device.
#
# Usage:
#   ./run-suite.sh                       # run + report
#   FLOW_MAX_SECONDS=45 ./run-suite.sh   # also fail if a flow is longer
#   MAESTRO_OUT_DIR=/tmp/run ./run-suite.sh
#   SIDETRACK_ACCENTS="Lime Orange Blue Pink" ./run-suite.sh
#
# Extra arguments are passed to each `maestro test` flow invocation, e.g.
# `-e FOO=bar` or `--include-tags nightly`.
set -euo pipefail

cd "$(dirname "$0")"

OUT="${MAESTRO_OUT_DIR:-build/maestro-suite}"
ACCENT_OUT="${MAESTRO_ACCENT_OUT_DIR:-build/maestro-accent}"
MAX_SECONDS="${FLOW_MAX_SECONDS:-}"
# The order used when the accent rotation was verified on device.
read -r -a ACCENTS <<< "${SIDETRACK_ACCENTS:-Lime Orange Blue Pink}"
mapfile -t FLOWS < <(sed -n 's/^[[:space:]]*-[[:space:]]*"\(.*\)"[[:space:]]*$/\1/p' config.yaml)
ANIMATION_KEYS=(window_animation_scale transition_animation_scale animator_duration_scale)
ANIMATION_VALUES=()
ANIMATIONS_DISABLED=0

export PATH="$PATH:$HOME/.maestro/bin"
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

DEVICE="${SIDETRACK_DEVICE:-}"
if [[ -z "$DEVICE" ]]; then
    DEVICE="$(adb devices | awk '$2 == "device" { print $1; exit }')"
fi
if [[ -z "$DEVICE" ]]; then
    echo "No connected Android device." >&2
    exit 1
fi
ADB=(adb -s "$DEVICE")
export MAESTRO_DEVICE_UDID="$DEVICE"

restore_animations() {
    if (( ANIMATIONS_DISABLED )); then
        for i in "${!ANIMATION_KEYS[@]}"; do
            local value="${ANIMATION_VALUES[$i]}"
            if [[ -z "$value" || "$value" == "null" ]]; then
                "${ADB[@]}" shell settings delete global "${ANIMATION_KEYS[$i]}" || true
            else
                "${ADB[@]}" shell settings put global "${ANIMATION_KEYS[$i]}" "$value" || true
            fi
        done
        ANIMATIONS_DISABLED=0
    fi
}
trap restore_animations EXIT

if [[ "${SIDETRACK_KEEP_ANIMATIONS:-0}" != "1" ]]; then
    for key in "${ANIMATION_KEYS[@]}"; do
        ANIMATION_VALUES+=("$("${ADB[@]}" shell settings get global "$key" | tr -d '\r')")
        "${ADB[@]}" shell settings put global "$key" 0
    done
    ANIMATIONS_DISABLED=1
fi

if (( ${#FLOWS[@]} == 0 )); then
    echo "No flows found in config.yaml." >&2
    exit 1
fi
if (( ${#ACCENTS[@]} == 0 )); then
    echo "SIDETRACK_ACCENTS is empty." >&2
    exit 1
fi

rm -rf "$OUT" "$ACCENT_OUT"
mkdir -p "$OUT" "$ACCENT_OUT"

echo "Running suite on $DEVICE (artifacts: $OUT)"
MAESTRO_STATUS=0
for i in "${!FLOWS[@]}"; do
    FLOW="${FLOWS[$i]}"
    ACCENT="${ACCENTS[$((i % ${#ACCENTS[@]}))]}"
    echo
    echo "[$((i + 1))/${#FLOWS[@]}] Accent: $ACCENT"
    if ! maestro test \
        --udid "$DEVICE" \
        --no-reinstall-driver \
        --test-output-dir "$ACCENT_OUT/$((i + 1))-$ACCENT" \
        -e ACCENT="$ACCENT" \
        flows/utility/set-accent.yaml >"$ACCENT_OUT/$((i + 1))-$ACCENT.log" 2>&1; then
        echo "Setting accent $ACCENT failed; see $ACCENT_OUT/$((i + 1))-$ACCENT.log" >&2
        cat "$ACCENT_OUT/$((i + 1))-$ACCENT.log" >&2
        MAESTRO_STATUS=1
        break
    fi
    echo "[$((i + 1))/${#FLOWS[@]}] Flow: $FLOW"
    maestro test \
        --udid "$DEVICE" \
        --no-reinstall-driver \
        --test-output-dir "$OUT" \
        "$@" \
        "$FLOW" || { MAESTRO_STATUS=$?; break; }
done

# Always report timings, even when a flow failed: the failed flow's
# commands.json is exactly what explains how far it got.
echo
REPORT_STATUS=0
if [[ -n "$MAX_SECONDS" ]]; then
    scripts/flow-times.py "$OUT" --max-seconds "$MAX_SECONDS" || REPORT_STATUS=$?
else
    scripts/flow-times.py "$OUT" || REPORT_STATUS=$?
    echo
    echo "Set FLOW_MAX_SECONDS=<seconds> to fail the run when a flow is too long."
fi

if (( MAESTRO_STATUS != 0 )); then
    exit "$MAESTRO_STATUS"
fi
exit "$REPORT_STATUS"
