#!/usr/bin/env bash
# Rebuild the Sideplay promo videos from the static phone render + Maestro recordings.
# See maestro/PROMO-VIDEO.md for the reasoning behind every number here.
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")/../.."

STILL=design/promo/sidephone.png          # 1000x1000 RGBA, decoded from sidephone.webp (AVIF)
OUT=design/promo
PANELS="$OUT/4x9"
FLAT="$(mktemp -d)/sidephone-flat.png"    # opaque background for the MP4s

# Video rect inside the 1000x1000 canvas (requested 304.58x406.10 @ 347.71,116.60).
OV_W=305 OV_H=406 OV_X=348 OV_Y=117
# 4:9 panel crop: 432x972 == 4:9, keeps the 373x863 phone with a small margin.
C_W=432 C_H=972 C_X=284 C_Y=14

# Normalise the recordings (VFR from `adb screenrecord`) to constant 30 fps first,
# then hand off to NVENC. x264 here is ~100x slower for the same visual result.
REC=(01-browse-play 02-search-library-writes 03-queue-transport 04-history-settings)
# Every panel is padded to the same length so the four-up grid ends together.
CLIP_SECONDS=40
NVENC=(-c:v h264_nvenc -preset p6 -tune hq -rc vbr -cq 19 -b:v 0 -pix_fmt yuv420p -r 30 -movflags +faststart)

command -v ffmpeg >/dev/null || { echo "ffmpeg not found" >&2; exit 1; }
ENCODERS=$(ffmpeg -hide_banner -encoders 2>/dev/null || true)
case "$ENCODERS" in
  *h264_nvenc*) ;;
  *)
    echo "h264_nvenc unavailable; use: -c:v libx264 -preset slow -crf 18 without -rc/-cq" >&2
    exit 1
    ;;
esac

[ -f "$STILL" ] || {
  echo "missing $STILL — decode it from the AVIF first:" >&2
  echo "  ffmpeg -i design/promo/sidephone.webp -filter_complex '[0:v:0]format=rgb24[c];[0:v:1]format=gray[a];[c][a]alphamerge' -frames:v 1 -update 1 $STILL" >&2
  exit 1
}

# Flatten the alpha onto white so the H.264 output has no phantom black.
magick "$STILL" -background white -flatten -alpha off "$FLAT"
mkdir -p "$PANELS"

PADS=()
for rec in "${REC[@]}"; do
  # Pad from the last presented frame, not the container duration: VFR screen
  # recordings can carry a long final frame duration that tpad does not count.
  LAST=$(ffprobe -v error -select_streams v:0 -show_entries frame=pts_time -of csv=p=0 "maestro/recordings/$rec.mp4" | tail -1)
  PADS+=("$(awk -v d="$LAST" -v c="$CLIP_SECONDS" 'BEGIN { p=c+0.5-d; if (p<0) p=0; printf "%.3f", p }')")
done

echo "== 4:9 panels"
for i in "${!REC[@]}"; do
  rec=${REC[$i]}
  pad=${PADS[$i]}
  echo "   $rec (+${pad}s)"
  ffmpeg -y -hide_banner -loglevel error -loop 1 -framerate 30 -i "$FLAT" \
    -i "maestro/recordings/$rec.mp4" \
    -filter_complex "[0:v]format=yuv420p[bg];\
[1:v]scale=${OV_W}:${OV_H}:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[fg];\
[bg][fg]overlay=${OV_X}:${OV_Y}:shortest=1,crop=${C_W}:${C_H}:${C_X}:${C_Y},fps=30,setpts=N/30/TB,tpad=stop_mode=clone:stop_duration=${pad},format=yuv420p[v]" \
    -map "[v]" -an "${NVENC[@]}" -maxrate 40M -t "$CLIP_SECONDS" "$PANELS/$rec.mp4"
done

echo "== 16:9 grid (4 x 4:9 = 1728x972)"
ffmpeg -y -hide_banner -loglevel error -loop 1 -framerate 30 -i "$FLAT" \
  -i "maestro/recordings/${REC[0]}.mp4" \
  -i "maestro/recordings/${REC[1]}.mp4" \
  -i "maestro/recordings/${REC[2]}.mp4" \
  -i "maestro/recordings/${REC[3]}.mp4" \
  -filter_complex "[0:v]format=yuv420p,split=4[b0][b1][b2][b3];\
[1:v]scale=${OV_W}:${OV_H}:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p,fps=30,setpts=N/30/TB,tpad=stop_mode=clone:stop_duration=${PADS[0]}[f1];\
[2:v]scale=${OV_W}:${OV_H}:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p,fps=30,setpts=N/30/TB,tpad=stop_mode=clone:stop_duration=${PADS[1]}[f2];\
[3:v]scale=${OV_W}:${OV_H}:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p,fps=30,setpts=N/30/TB,tpad=stop_mode=clone:stop_duration=${PADS[2]}[f3];\
[4:v]scale=${OV_W}:${OV_H}:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p,fps=30,setpts=N/30/TB,tpad=stop_mode=clone:stop_duration=${PADS[3]}[f4];\
[b0][f1]overlay=${OV_X}:${OV_Y}:shortest=1,crop=${C_W}:${C_H}:${C_X}:${C_Y},fps=30,format=yuv420p[c1];\
[b1][f2]overlay=${OV_X}:${OV_Y}:shortest=1,crop=${C_W}:${C_H}:${C_X}:${C_Y},fps=30,format=yuv420p[c2];\
[b2][f3]overlay=${OV_X}:${OV_Y}:shortest=1,crop=${C_W}:${C_H}:${C_X}:${C_Y},fps=30,format=yuv420p[c3];\
[b3][f4]overlay=${OV_X}:${OV_Y}:shortest=1,crop=${C_W}:${C_H}:${C_X}:${C_Y},fps=30,format=yuv420p[c4];\
[c1][c2][c3][c4]hstack=inputs=4,format=yuv420p[v]" \
  -map "[v]" -an "${NVENC[@]}" -maxrate 60M -t "$CLIP_SECONDS" "$OUT/sideplay-suite-16x9.mp4"

# Full-HD master. The grid is 1728x972 (four 4:9 panels), so 1920x1080 is a 1.11x
# upscale of an already composited frame: one lanczos pass on the finished grid, and
# the panel seams stay put because both sizes are exactly 16:9.
HD_W=1920
HD_H=1080
echo "== 1080p master"
ffmpeg -y -hide_banner -loglevel error -i "$OUT/sideplay-suite-16x9.mp4" \
  -vf "scale=${HD_W}:${HD_H}:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p" \
  -map 0:v -an "${NVENC[@]}" -maxrate 60M -t "$CLIP_SECONDS" "$OUT/sideplay-suite-1920x1080.mp4"

echo "== done"
ls -lh "$PANELS"/*.mp4 "$OUT/sideplay-suite-16x9.mp4" "$OUT/sideplay-suite-1920x1080.mp4"
