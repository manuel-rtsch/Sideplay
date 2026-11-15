# Promo videos: phone mockup + recordings

How the promo videos in `design/promo/` are built: a static Sidephone render with a Maestro screen
recording played inside its display, then cropped to 4:9 and tiled 4-up into 16:9.

Rebuild everything:

```sh
design/promo/build-videos.sh
```

## 1. The source image is not a WebP

`design/promo/sidephone.webp` is an **AVIF** file (`ftypavif`) that was saved with a `.webp`
extension. It is a 1000×1000 image whose transparency is stored as a **second AV1 stream**
(the alpha plane), not as an embedded alpha channel:

```sh
file design/promo/sidephone.webp        # ISO Media, AVIF Image
ffprobe design/promo/sidephone.webp     # stream 0: av1 1000x1000 yuv444p
                                        # stream 1: av1 1000x1000 gray  <- alpha
```

ImageMagick on this machine has no AVIF decode delegate, and PIL/freeimage refuses it because the
extension does not match the content. ffmpeg can decode it with libdav1d, but it treats the two
streams as independent videos, so a plain `ffmpeg -i img -frames:v 1 out.png` writes the color
plane only and every transparent pixel comes out **black**. Merge the planes with `alphamerge`:

```sh
ffmpeg -i design/promo/sidephone.webp \
  -filter_complex "[0:v:0]format=rgb24[c];[0:v:1]format=gray[a];[c][a]alphamerge" \
  -frames:v 1 -update 1 design/promo/sidephone.png
```

`design/promo/sidephone.png` (1000×1000, RGBA, ~90 KB) is the canonical still. PNG is the right
target here: lossless, keeps the transparent background, and no generation loss for the encodes
that follow.

## 2. Geometry inside the 1000×1000 canvas

Measured from `sidephone.png`:

| | |
| --- | --- |
| phone bounds (alpha > 0, includes the soft shadow) | x 315…687, y 69…931 |
| display (dark glass) | x ≈ 339…666, y ≈ 78…551 |
| video overlay, as requested | 304.58×406.10 at (347.71, 116.60) |
| video overlay, as rendered | 305×406 at (348, 117) |
| 4:9 crop | 432×972 at (284, 14) |

The overlay is the 480×640 recording (3:4) scaled to 305×406, i.e. rounded to whole pixels — at most
0.5 px off the requested rect, and the background stays bit-identical to the PNG. The video sits
**inset** in the glass (~5 px at the sides, ~38 px above, ~28 px below).

## 3. Why the first render took 6 minutes

The first version composited on a 2000×2000 canvas — the 1000×1000 still upscaled 2× and the frame
scaled back down — with `scale=…:flags=lanczos` and x264 on the CPU. Per-frame cost was dominated by
the two full-canvas resamples, not by the encoder; 6 min for 68 s of video.

Two changes bring the whole thing down to **seconds**:

1. **Composite at 1×.** Only the 480×640 recording is resampled (to 305×406); the still is never
   touched, so the alpha stays exact and the pixel grid is 1:1.
2. **Encode on the GPU** with `h264_nvenc` (RTX 4060 here) instead of x264:
   `-preset p6 -tune hq -rc vbr -cq 19 -b:v 0 -pix_fmt yuv420p`.

Timings on this machine: 1000×1000 / 68.5 s → ~5 s; four 432×972 panels → ~20 s; the 1728×972 grid
→ ~13 s. The 2× supersampling trick is only worth it if you need sub-pixel overlays; a 0.4 px
rounding is invisible here.

## 4. Composing one video

The still is looped at the output frame rate and the recording is overlaid; `shortest=1` ends the
composite when the recording ends. The recordings are **variable frame rate** (`adb screenrecord`,
~13 fps average, 90 kHz timebase), so `fps=30` normalises them to constant 30 fps.

```sh
flat=/tmp/sidephone-flat.png
magick design/promo/sidephone.png -background white -flatten -alpha off "$flat"

ffmpeg -loop 1 -framerate 30 -i "$flat" -i maestro/recordings/01-browse-play.mp4 \
  -filter_complex "\
    [0:v]format=yuv420p[bg];\
    [1:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[fg];\
    [bg][fg]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[v]" \
  -map "[v]" -an \
  -c:v h264_nvenc -preset p6 -tune hq -rc vbr -cq 19 -b:v 0 -maxrate 40M \
  -pix_fmt yuv420p -r 30 -movflags +faststart \
  design/promo/4x9/01-browse-play.mp4
```

`-an` because the recordings carry a `data` stream (touch events) and no audio; ffmpeg warns that it
discards it.

### Filter-graph pitfalls hit along the way

- `overlay` takes its timing from its **first** input (the bottom layer). Using the looped still as
  the main input and the recording as the overlay silently produces a static video, because the
  still has only one frame. Either loop the still (`-loop 1 -framerate 30`) and rely on
  `shortest=1`, or put the recording first and use `eof_action=repeat` for the still.
- `trim` does not reset timestamps. `trim=start_frame=N` followed by an overlay yields nothing
  until the trimmed stream's PTS is reached; add `setpts=PTS-STARTPTS`.
- `scale`'s `accurate_rnd` is a **flag**, not an option: `flags=lanczos+accurate_rnd`.
- White background: the MP4 is opaque, so the still is flattened onto white first. Keep the RGBA
  PNG if you later need a transparent WebM (VP9 + `yuva420p`).

## 5. 4:9 panels and the 16:9 grid

The 4:9 crop removes the white space around the phone while keeping it fully visible:

- phone bounds are 373×863 inside the 1000×1000 canvas;
- 432×972 is exactly 4:9 (`972 × 4/9 = 432`) and leaves 31/29 px at the sides, 55/55 px vertically;
- four of them side by side are exactly 16:9: `4 × 432 = 1728`, `1728 / 972 = 16/9`.

So the grid needs no padding — a plain `hstack` of the four 4:9 panels:

```sh
R=maestro/recordings
ffmpeg -loop 1 -framerate 30 -i "$flat" \
  -i $R/01-browse-play.mp4 -i $R/02-search-library-writes.mp4 \
  -i $R/03-queue-transport.mp4 -i $R/04-history-settings.mp4 \
  -filter_complex "\
    [0:v]format=yuv420p,split=4[b0][b1][b2][b3];\
    [1:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f1];\
    [2:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f2];\
    [3:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f3];\
    [4:v]scale=305:406:flags=lanczos+accurate_rnd,setsar=1,format=yuv420p[f4];\
    [b0][f1]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c1];\
    [b1][f2]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c2];\
    [b2][f3]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c3];\
    [b3][f4]overlay=348:117:shortest=1,crop=432:972:284:14,fps=30,format=yuv420p[c4];\
    [c1][c2][c3][c4]hstack=inputs=4,format=yuv420p[v]" \
  -map "[v]" -an \
  -c:v h264_nvenc -preset p6 -tune hq -rc vbr -cq 19 -b:v 0 -maxrate 60M \
  -pix_fmt yuv420p -r 30 -movflags +faststart \
  design/promo/sidetrack-suite-16x9.mp4
```

The four recordings have different lengths (36.0 s, 38.8 s, 39.7 s, 39.1 s), so every panel is
padded from its last presented frame to `CLIP_SECONDS = 40` with `tpad=stop_mode=clone` before the
`hstack`. The grid is therefore exactly 40 s and all four phones end together — no panel freezes
for tens of seconds, and nothing restarts mid-take.

## 6. Gallery GIF

Reddit's gallery takes a GIF but re-encodes video, so the 16:9 grid is sampled straight from the
already composited `sidetrack-suite-16x9.mp4` rather than from the four recordings again. The step
lives at the end of `build-videos.sh`, so the GIF is regenerated with everything else:

```sh
ffmpeg -i design/promo/sidetrack-suite-16x9.mp4 \
  -vf "fps=15,palettegen=max_colors=256:stats_mode=diff" /tmp/palette.png
ffmpeg -i design/promo/sidetrack-suite-16x9.mp4 -i /tmp/palette.png \
  -lavfi "fps=15,paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
  -loop 0 design/promo/sidetrack-suite-16x9.gif
```

- **15 fps** is the smallest rate at which focus rings and the seek row still read as motion;
  every frame is a full frame, so the rate is what decides the file size.
- **1728×972** is the native grid size: no panel is resampled twice, and the GIF is 16:9 as-is.
- **Two passes** because one pass either spends the 256-colour table on the gradients or bands the
dark surfaces. `stats_mode=diff` spends the table on what changes between frames, and
  `dither=bayer:bayer_scale=5:diff_mode=rectangle` dithers only the rectangles that changed, which
  keeps the flat black chrome flat.
- The script also writes a single mid-GIF frame (20 s in) to a temp path and prints it, so the
  banding review is one `open` away and leaves no file in the repo.

## 7. Outputs

| File | Notes |
| --- | --- |
| `design/promo/sidephone.png` | 1000×1000 RGBA still, decoded from the AVIF |
| `design/promo/4x9/01…04-*.mp4` | 432×972 4:9 panels, 30 fps, 40 s each |
| `design/promo/sidetrack-suite-16x9.mp4` | 1728×972 16:9 grid, 30 fps, 40 s |
| `design/promo/sidetrack-suite-1920x1080.mp4` | 1920×1080 lanczos upscale of the grid, 30 fps, 40 s |
| `design/promo/sidetrack-suite-16x9.gif` | 1728×972, 15 fps, 40 s palette GIF |

All encodes are H.264 High, `yuv420p`, `+faststart`, CRF/cq 19 — web-safe and quick to scrub.
