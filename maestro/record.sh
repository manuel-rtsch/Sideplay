#!/usr/bin/env bash
# Record a Maestro flow with adb screenrecord.
#
# `maestro record --local` is currently very slow (several minutes for a ~1 minute
# flow) and leaves an unplayable file if interrupted. screenrecord is fast and
# produces a normal MP4.
#
# Before recording, `flows/utility/record-prepare.yaml` clears the Recently played
# history and starts a song so it is already playing in the background. The
# recording itself starts once the flow's launchApp command completes, so the MP4
# does not begin with Maestro's JVM startup time. Set SIDEPLAY_SKIP_PREPARE=1 to record
# the flow as-is instead.
#
# Set SIDEPLAY_ACCENT to change Sidekit's host-backed case colour before recording:
#   SIDEPLAY_ACCENT=Lime ./record.sh flows/promo/01-browse-play.yaml
#
# Usage: ./record.sh [flow] [output]
set -euo pipefail

cd "$(dirname "$0")"

FLOW="${1:-flows/demo/hero-play-search-queue.yaml}"
OUT="${2:-recordings/hero-play-search-queue.mp4}"
PREP_FLOW="${SIDEPLAY_PREP_FLOW:-flows/utility/record-prepare.yaml}"
ACCENT_FLOW="${SIDEPLAY_ACCENT_FLOW:-flows/utility/set-accent.yaml}"
ACCENT="${SIDEPLAY_ACCENT:-}"
DEVICE_FILE="/sdcard/maestro-recording.mp4"
# Start at the end of the flow's launchApp command: it is the first command every
# flow runs, so the marker cannot drift when the flow's first action or first
# assertion changes, and it matches both cold and warm launches. The JVM startup
# stays out of the video.
START_MARKER="${SIDEPLAY_RECORD_START_MARKER:-Launch app .*COMPLETED}"
START_TIMEOUT="${SIDEPLAY_RECORD_START_TIMEOUT:-30}"
REC_PID=""
MAESTRO_PID=""
MAESTRO_LOG=""
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

stop_recording() {
    if [[ -n "$MAESTRO_PID" ]] && kill -0 "$MAESTRO_PID" 2>/dev/null; then
        kill "$MAESTRO_PID" 2>/dev/null || true
    fi
    if [[ -n "$REC_PID" ]]; then
        "${ADB[@]}" shell pkill -INT screenrecord || true
        wait "$REC_PID" 2>/dev/null || true
        sleep 1
        "${ADB[@]}" pull "$DEVICE_FILE" "$OUT" || true
        "${ADB[@]}" shell rm -f "$DEVICE_FILE" || true
        REC_PID=""
    fi
    if [[ -n "$MAESTRO_LOG" ]]; then
        rm -f "$MAESTRO_LOG" || true
        MAESTRO_LOG=""
    fi
    restore_animations
}

start_recording() {
    "${ADB[@]}" shell screenrecord --size 480x640 --bit-rate 4000000 "$DEVICE_FILE" &
    REC_PID=$!
}

trap stop_recording EXIT

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
"${ADB[@]}" shell rm -f "$DEVICE_FILE"

# System animations make every Maestro tap wait for the screen to settle. Turn
# them off for the recording and restore the device's values on exit.
if [[ "${SIDEPLAY_KEEP_ANIMATIONS:-0}" != "1" ]]; then
    for key in "${ANIMATION_KEYS[@]}"; do
        ANIMATION_VALUES+=("$("${ADB[@]}" shell settings get global "$key" | tr -d '\r')")
        "${ADB[@]}" shell settings put global "$key" 0
    done
    ANIMATIONS_DISABLED=1
fi

if [[ -n "$ACCENT" ]]; then
    echo "Setting accent colour to $ACCENT..."
    maestro test --udid "$DEVICE" -e ACCENT="$ACCENT" "$ACCENT_FLOW"
fi

if [[ "${SIDEPLAY_SKIP_PREPARE:-0}" != "1" ]]; then
    echo "Preparing device for recording..."
    maestro test --udid "$DEVICE" "$PREP_FLOW"
fi

# Start Maestro first, then begin screen recording once the app is up. This keeps
# the JVM startup and app launch out of the video while still catching the first
# real interaction.
MAESTRO_LOG="$(mktemp "${TMPDIR:-/tmp}/sideplay-maestro.XXXXXX.log")"
maestro test --udid "$DEVICE" "$FLOW" >"$MAESTRO_LOG" 2>&1 &
MAESTRO_PID=$!

deadline=$((SECONDS + START_TIMEOUT))
while (( SECONDS < deadline )); do
    if grep -Eq "$START_MARKER" "$MAESTRO_LOG" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$MAESTRO_PID" 2>/dev/null; then
        break
    fi
    sleep 0.1
done

if ! grep -Eq "$START_MARKER" "$MAESTRO_LOG" 2>/dev/null; then
    echo "Warning: did not reach recording start marker; recording the whole flow." >&2
fi
start_recording

MAESTRO_STATUS=0
wait "$MAESTRO_PID" || MAESTRO_STATUS=$?
MAESTRO_PID=""
cat "$MAESTRO_LOG"
rm -f "$MAESTRO_LOG"
MAESTRO_LOG=""

stop_recording
trap - EXIT

if (( MAESTRO_STATUS != 0 )); then
    exit "$MAESTRO_STATUS"
fi
echo "Wrote $OUT"
