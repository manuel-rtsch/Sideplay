#!/usr/bin/env bash
# Run the whole Maestro suite in one invocation and report per-flow length.
#
# One `maestro test` run instead of four: the driver is installed once (it is also
# asked not to reinstall at all, since a warm device already has it), and every
# flow's commands.json lands in one output directory so the timing report sees
# all four flows.
#
# System animations are turned off for the run and restored afterwards, matching
# what record.sh does. Maestro otherwise pays for an animated window on every
# tap: ~3.3 s per tap instead of ~2.0 s on this device.
#
# Usage:
#   ./run-suite.sh                       # run + report
#   FLOW_MAX_SECONDS=45 ./run-suite.sh   # also fail if a flow is longer
#   MAESTRO_OUT_DIR=/tmp/run ./run-suite.sh
#
# Extra arguments are passed to `maestro test`, e.g. `-e FOO=bar` or
# `--include-tags nightly`.
set -euo pipefail

cd "$(dirname "$0")"

OUT="${MAESTRO_OUT_DIR:-build/maestro-suite}"
MAX_SECONDS="${FLOW_MAX_SECONDS:-}"
ANIMATION_KEYS=(window_animation_scale transition_animation_scale animator_duration_scale)
ANIMATION_VALUES=()
ANIMATIONS_DISABLED=0

export PATH="$PATH:$HOME/.maestro/bin"
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

DEVICE="${SIDEPLAY_DEVICE:-}"
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

if [[ "${SIDEPLAY_KEEP_ANIMATIONS:-0}" != "1" ]]; then
    for key in "${ANIMATION_KEYS[@]}"; do
        ANIMATION_VALUES+=("$("${ADB[@]}" shell settings get global "$key" | tr -d '\r')")
        "${ADB[@]}" shell settings put global "$key" 0
    done
    ANIMATIONS_DISABLED=1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

echo "Running suite on $DEVICE (artifacts: $OUT)"
MAESTRO_STATUS=0
maestro test \
    --udid "$DEVICE" \
    --no-reinstall-driver \
    --test-output-dir "$OUT" \
    "$@" \
    . || MAESTRO_STATUS=$?

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
