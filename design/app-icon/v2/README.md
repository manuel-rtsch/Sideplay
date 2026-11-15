# Sidetrack app icon — v2

Refined version of the vinyl-record icon. v1 remains in the parent folder for comparison.

## Changes from v1
- Reduced five grooves to three, with thicker strokes so they survive at 48dp.
- Shrank the record to 62dp inside the 66dp adaptive-icon safe zone for breathing room.
- Added a dedicated monochrome asset with transparent groove/label cut-outs.
- Shortened and brightened the specular highlight so it reads as a shine, not a scratch.
- Added a play-triangle variant that replaces the spindle hole.

## Files
- `sidetrack-icon-v2.svg` / `-512.png` / `-1024.png` — standard icon
- `sidetrack-icon-v2-play.svg` / `-512.png` / `-1024.png` — play-triangle variant
- `sidetrack-foreground-v2.svg` / `-432.png` — adaptive foreground, transparent
- `sidetrack-foreground-v2-play.svg` / `-432.png` — adaptive foreground, play variant
- `sidetrack-monochrome-v2.svg` / `-432.png` — monochrome asset (transparent cut-outs)
- `sidetrack-monochrome-v2-play.svg` / `-432.png` — monochrome play variant
- `sidetrack-monochrome-v2-preview-432.png` — monochrome preview on #121212
- `sidetrack-monochrome-v2-play-preview-432.png` — monochrome play preview on #121212
- `comparison-v1-v2-v2play.png` — side-by-side comparison
- `small-size-48px-preview.png` — 48px legibility check (v1, v2, v2-play)

The monochrome SVGs use `fill-rule="evenodd"`; the transparent cut-outs are intentional.

## Decision
The standard v2 (spindle hole) is now the app icon. The play-triangle variant is kept as a secondary in-app mark.
