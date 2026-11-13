#!/usr/bin/env bash
#
# crash-watch.sh — watch a running Sideplay build on a device and capture a full
# forensic report the moment it crashes.
#
# It streams logcat (main/system/crash/events) to disk, watches for a fatal exception
# or a new ApplicationExitInfo crash record, waits for the process to die, then collects
# the crash buffer, exit info, DropBox entries and an R8-retraced stack trace into one
# timestamped directory.
#
# Usage:
#   tools/crash-watch.sh                      # stop after the first crash
#   tools/crash-watch.sh --count 0            # keep watching forever
#   tools/crash-watch.sh --count 3 --relaunch # capture 3 crashes, restarting in between
#   tools/crash-watch.sh --package io.github.manuelrtsch.sideplay.dev
#
# Useful options:
#   --package <pkg>   Package to watch (default $SIDEPLAY_PKG or io.github.manuelrtsch.sideplay)
#   --out <dir>       Report root (default ./crash-logs)
#   --count <n>       Number of crashes to capture, 0 = forever (default 1)
#   --relaunch        (Re)launch the app when it is not running, and after each crash
#   --no-clear        Keep the existing logcat buffers (default: clear at start)
#   --timeout <secs>  Give up after this many seconds (default: no timeout)
#   --mapping <file>  R8 mapping.txt override (default: newest app/build/outputs/mapping/*)
#   -h, --help        Show this help
#
# Exit codes: 0 crash captured, 2 usage/tooling problem, 3 watchdog timeout,
#             4 app was not running at start (and --relaunch not given).
#
set -uo pipefail

PKG="${SIDEPLAY_PKG:-io.github.manuelrtsch.sideplay}"
OUT_ROOT="${SIDEPLAY_CRASH_DIR:-$PWD/crash-logs}"
POLL_SECS="${SIDEPLAY_POLL_SECONDS:-2}"
COUNT=1
RELAUNCH=0
CLEAR=1
TIMEOUT=0
MAPPING="${SIDEPLAY_MAPPING:-}"

die() { printf 'crash-watch: %s\n' "$*" >&2; exit 2; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

usage() { sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    --package) PKG="${2:?}"; shift 2 ;;
    --out)     OUT_ROOT="${2:?}"; shift 2 ;;
    --count)   COUNT="${2:?}"; shift 2 ;;
    --relaunch) RELAUNCH=1; shift ;;
    --no-clear) CLEAR=0; shift ;;
    --timeout) TIMEOUT="${2:?}"; shift 2 ;;
    --mapping) MAPPING="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

command -v adb >/dev/null || die "adb not found in PATH"
SERIAL=""

probe_sdk() {
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"
  elif [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"
  elif [ -f "$PWD/local.properties" ]; then
    sed -n 's/^sdk\.dir=//p' "$PWD/local.properties" | head -1
  fi
}
SDK="$(probe_sdk)"

RETRACE=""
if [ -n "$SDK" ]; then
  for candidate in \
    "$SDK/cmdline-tools/latest/bin/retrace" \
    "$SDK/cmdline-tools/bin/retrace" \
    "$SDK/tools/bin/retrace"; do
    [ -x "$candidate" ] && { RETRACE="$candidate"; break; }
  done
fi

resolve_mapping() {
  [ -n "$MAPPING" ] && { echo "$MAPPING"; return; }
  local newest=""
  while IFS= read -r f; do
    [ -z "$newest" ] || [ "$f" -nt "$newest" ] && newest="$f"
  done < <(find app/build/outputs/mapping -name mapping.txt -type f 2>/dev/null)
  echo "$newest"
}

app_pid() {
  adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'
}

# Fingerprint of the newest ApplicationExitInfo block (stable across polls).
newest_exit_entry() {
  adb shell dumpsys activity exit-info "$PKG" 2>/dev/null | tr -d '\r' | awk '
    /ApplicationExitInfo #/ { inblk = 1; n = 0 }
    inblk { print; if (++n >= 16) exit }
  '
}

# Print FATAL EXCEPTION blocks from a logcat file that belong to $PKG.
fatal_block_for_package() {
  awk -v pkg="$PKG" '
    /FATAL EXCEPTION/ {
      if (blk != "" && ours) printf "%s\n", blk
      blk = $0 "\n"; ours = 0; n = 0; next
    }
    {
      if (n++ < 300) blk = blk $0 "\n"
      if (index($0, "Process: " pkg) > 0) ours = 1
    }
    END { if (blk != "" && ours) printf "%s", blk }
  ' "$1" 2>/dev/null
}

launch_activity() {
  local component
  component="$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null \
    | tr -d '\r' | grep '/' | head -1)"
  [ -n "$component" ] || { log "could not resolve launcher activity for $PKG"; return 1; }
  adb shell am start -W -n "$component" >/dev/null 2>&1 && log "launched $component"
}

# Wait (bounded) until the process is gone, so the crash record is fully written.
wait_for_process_death() {
  local max="${1:-20}" waited=0
  while [ "$waited" -lt "$max" ]; do
    [ -z "$(app_pid)" ] && return 0
    sleep 1
    waited=$(( waited + 1 ))
  done
  log "process still alive after ${max}s; collecting anyway"
  return 1
}

mkdir -p "$OUT_ROOT" || die "cannot create $OUT_ROOT"

captured=0
deadline=0
[ "$TIMEOUT" -gt 0 ] && deadline=$(( $(date +%s) + TIMEOUT ))

# Blocks until a device is attached (or the watchdog deadline passes) and sets SERIAL.
wait_for_device() {
  local announced=0
  while ! adb get-state >/dev/null 2>&1; do
    if [ "$deadline" -gt 0 ] && [ "$(date +%s)" -ge "$deadline" ]; then
      log "no device before the ${TIMEOUT}s timeout; giving up"
      exit 3
    fi
    if [ "$announced" -eq 0 ]; then
      log "no device attached; waiting for one (check the cable / 'adb devices')"
      announced=1
    fi
    sleep 3
  done
  SERIAL="$(adb get-serialno)"
  [ -n "$SERIAL" ] || die "could not determine device serial"
  [ "$announced" -eq 1 ] && log "device $SERIAL is back"
}

LAST_SEEN_FILE="$OUT_ROOT/.last-captured-exit"
LAST_CAPTURED_EXIT=""
[ -f "$LAST_SEEN_FILE" ] && LAST_CAPTURED_EXIT="$(cat "$LAST_SEEN_FILE")"

while :; do
  wait_for_device

  # ---- ensure the app is running ----------------------------------------
  PID="$(app_pid)"
  if [ -z "$PID" ]; then
    if [ "$RELAUNCH" -eq 1 ]; then
      launch_activity || exit 2
      for _ in $(seq 1 15); do sleep 1; PID="$(app_pid)"; [ -n "$PID" ] && break; done
    fi
    [ -n "$PID" ] || die "package $PKG is not running (start it, or pass --relaunch)"
  fi

  SESSION="$OUT_ROOT/$(date +%Y%m%d-%H%M%S)-pid$PID"
  mkdir -p "$SESSION"
  STREAM="$SESSION/logcat-stream.txt"

  if [ "$CLEAR" -eq 1 ]; then
    adb logcat -b all -c >/dev/null 2>&1
    log "cleared device log buffers"
  fi

  log "watching $PKG (pid $PID) on $SERIAL -> $SESSION"

  adb logcat -v threadtime -b main,system,crash,events >"$STREAM" 2>&1 &
  STREAM_PID=$!
  trap 'kill "${STREAM_PID:-}" 2>/dev/null; exit 130' INT TERM

  start_ts="$(date +%s)"
  alive_beats=1
  reason=""
  # The Sidekit launcher can relaunch a crashed app within ~2 s, so a dead PID is NOT a
  # reliable crash signal. Watch three independent sources instead:
  #   1. a FATAL EXCEPTION block for our package in the streaming crash buffer
  #   2. an am_crash / am_anr event in the events buffer
  #   3. a new ApplicationExitInfo entry (deduplicated by fingerprint)
  exit_baseline="$(newest_exit_entry)"
  restart_seen="$PID"

  # ---- poll until a crash / ANR is observed ------------------------------
  while :; do
    sleep "$POLL_SECS"
    now="$(date +%s)"

    if [ "$deadline" -gt 0 ] && [ "$now" -ge "$deadline" ]; then
      reason="timeout"
      break
    fi

    if ! kill -0 "$STREAM_PID" 2>/dev/null; then
      reason="stream-lost"
      break
    fi

    if [ -n "$(fatal_block_for_package "$STREAM")" ]; then
      reason="fatal-exception"
      break
    fi

    if grep -qE "am_anr.*${PKG}" "$STREAM" 2>/dev/null; then
      reason="anr"
      break
    fi

    if grep -qE "am_crash.*${PKG}" "$STREAM" 2>/dev/null; then
      reason="am-crash-event"
      break
    fi

    current_exit="$(newest_exit_entry)"
    if [ -n "$current_exit" ] && [ "$current_exit" != "$exit_baseline" ]; then
      if [ "$current_exit" = "$LAST_CAPTURED_EXIT" ]; then
        # the delayed record of a crash we already captured — ignore it
        exit_baseline="$current_exit"
      elif printf '%s' "$current_exit" | grep -q "APP CRASH"; then
        reason="exit-info"
        break
      else
        exit_baseline="$current_exit"
      fi
    fi

    PID_NOW="$(app_pid)"
    if [ -n "$PID_NOW" ] && [ "$PID_NOW" != "$restart_seen" ]; then
      log "app restarted (pid $restart_seen -> $PID_NOW), still watching"
      restart_seen="$PID_NOW"
    fi

    if [ $(( now - start_ts )) -ge $(( alive_beats * 30 )) ]; then
      alive_beats=$(( alive_beats + 1 ))
      log "still alive ($(( now - start_ts ))s, pid ${PID_NOW:-none})"
    fi
  done

  if [ "$reason" = "timeout" ]; then
    kill "$STREAM_PID" 2>/dev/null; wait "$STREAM_PID" 2>/dev/null; STREAM_PID=""
    log "no crash within ${TIMEOUT}s; logs kept in $SESSION"
    exit 3
  fi

  if [ "$reason" = "stream-lost" ]; then
    wait "$STREAM_PID" 2>/dev/null; STREAM_PID=""
    log "log stream ended (device unplugged or adb restarted); reconnecting"
    continue
  fi

  log "crash detected via $reason; waiting for the process to die"
  wait_for_process_death 20 || true
  sleep 1 # let logcat flush
  kill "$STREAM_PID" 2>/dev/null; wait "$STREAM_PID" 2>/dev/null; STREAM_PID=""

  # ---- collect ------------------------------------------------------------
  adb logcat -d -v threadtime -b all >"$SESSION/logcat-full.txt" 2>&1
  adb logcat -d -v threadtime -b crash >"$SESSION/logcat-crash.txt" 2>&1
  adb shell dumpsys activity exit-info "$PKG" >"$SESSION/exit-info.txt" 2>&1
  adb shell dumpsys activity lastanr >"$SESSION/lastanr.txt" 2>&1
  adb shell dumpsys dropbox --print data_app_crash >"$SESSION/dropbox-app-crash.txt" 2>&1
  adb shell dumpsys dropbox --print data_app_anr >"$SESSION/dropbox-app-anr.txt" 2>&1
  adb shell ls -l /data/tombstones >"$SESSION/tombstones.txt" 2>&1
  {
    echo "serial=$SERIAL"
    adb shell getprop ro.product.model
    adb shell getprop ro.build.version.release
    adb shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionName|versionCode|codePath" | head -5
    adb shell date
  } >"$SESSION/device.txt" 2>&1

  # ---- extract the crash block -------------------------------------------
  fatal_block_for_package "$SESSION/logcat-crash.txt" >"$SESSION/crash-stack.txt" 2>/dev/null || true
  if [ ! -s "$SESSION/crash-stack.txt" ]; then
    fatal_block_for_package "$SESSION/logcat-full.txt" >"$SESSION/crash-stack.txt" 2>/dev/null || true
  fi

  # ~80 log lines before the fatal block, for context
  if [ -s "$SESSION/crash-stack.txt" ]; then
    first_fatal_line="$(grep -n "FATAL EXCEPTION" "$SESSION/logcat-full.txt" | tail -1 | cut -d: -f1)"
    if [ -n "$first_fatal_line" ]; then
      start=$(( first_fatal_line > 80 ? first_fatal_line - 80 : 1 ))
      sed -n "${start},$(( start + 79 ))p" "$SESSION/logcat-full.txt" >"$SESSION/context-before-crash.txt"
    fi
  fi

  # ---- deobfuscate --------------------------------------------------------
  MAP="$(resolve_mapping)"
  if [ -n "$MAP" ] && [ -f "$MAP" ] && [ -s "$SESSION/crash-stack.txt" ]; then
    echo "mapping=$MAP" >>"$SESSION/device.txt"
    if [ -n "$RETRACE" ]; then
      # SKIP_JDK_VERSION_CHECK: the retrace wrapper mis-parses some JDK version strings (e.g. 27-ea)
      SKIP_JDK_VERSION_CHECK=1 "$RETRACE" "$MAP" "$SESSION/crash-stack.txt" \
        >"$SESSION/crash-stack-retraced.txt" 2>"$SESSION/retrace.log" \
        && log "retraced stack written" \
        || log "retrace failed (see $SESSION/retrace.log)"
    else
      echo "retrace tool not found; preprocess manually with <sdk>/cmdline-tools/latest/bin/retrace" \
        >>"$SESSION/device.txt"
    fi
  fi
  STACK_FOR_SUMMARY="$SESSION/crash-stack-retraced.txt"
  [ -s "$STACK_FOR_SUMMARY" ] || STACK_FOR_SUMMARY="$SESSION/crash-stack.txt"

  headline="$(grep -m1 -E "Exception|Error|OutOfMemory" "$STACK_FOR_SUMMARY" 2>/dev/null \
    | sed 's/^[[:space:]]*//' | cut -c1-200)"
  if [ -z "$headline" ]; then
    headline="$(grep -m1 -E "reason=" "$SESSION/exit-info.txt" 2>/dev/null \
      | sed 's/^[[:space:]]*//' | cut -c1-200)"
    [ -n "$headline" ] && headline="no Java stack in buffers; exit record: $headline"
  fi
  [ -n "$headline" ] || headline="(no exception line found; inspect the raw files)"

  {
    echo "# Sideplay crash report"
    echo
    echo "- captured: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "- device: $SERIAL"
    echo "- package: $PKG (was pid $PID)"
    echo "- detected by: $reason"
    echo
    echo '## Headline'
    echo
    echo '```'
    echo "$headline"
    echo '```'
    echo
    echo '## Stack trace (deobfuscated when a mapping was available)'
    echo
    echo '```'
    sed -n '1,80p' "$STACK_FOR_SUMMARY"
    echo '```'
    echo
    echo '## Files'
    echo
    echo "- $STACK_FOR_SUMMARY"
    echo "- $SESSION/crash-stack.txt (raw, minified)"
    echo "- $SESSION/logcat-crash.txt, $SESSION/logcat-full.txt"
    echo "- $SESSION/context-before-crash.txt"
    echo "- $SESSION/exit-info.txt (Android ApplicationExitInfo)"
    echo "- $SESSION/dropbox-app-crash.txt, $SESSION/dropbox-app-anr.txt"
  } >"$SESSION/report.md"

  printf '\n=== CRASH CAPTURED (%s) ===\n' "$reason"
  printf '%s\n' "$headline"
  printf 'Report: %s\n\n' "$SESSION/report.md"
  ln -sfn "$(basename "$SESSION")" "$OUT_ROOT/latest" 2>/dev/null

  # remember this crash so its delayed exit record is not captured a second time
  for _ in $(seq 1 10); do
    latest_exit="$(newest_exit_entry)"
    if [ -n "$latest_exit" ] && [ "$latest_exit" != "$exit_baseline" ] && [ "$latest_exit" != "$LAST_CAPTURED_EXIT" ]; then
      LAST_CAPTURED_EXIT="$latest_exit"
      printf '%s' "$LAST_CAPTURED_EXIT" >"$LAST_SEEN_FILE"
      break
    fi
    sleep 1
  done

  captured=$(( captured + 1 ))
  if [ "$COUNT" -eq 0 ]; then
    RELAUNCH=1
    continue
  fi
  [ "$captured" -lt "$COUNT" ] || break
  RELAUNCH=1
  CLEAR=1
done

# leave the app running if the user asked for relaunching
if [ "$RELAUNCH" -eq 1 ] && [ -z "$(app_pid)" ]; then
  launch_activity || true
fi

exit 0
