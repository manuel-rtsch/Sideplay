import math
from pathlib import Path

OUT = Path(__file__).parent / "v2"
OUT.mkdir(exist_ok=True)

# Shared proportions (relative to record radius R)
GROOVE_FRACTIONS = (0.60, 0.74, 0.88)
LABEL_FRACTION = 0.32
HOLE_FRACTION = 0.055
MONO_HOLE_FRACTION = 0.07
HIGHLIGHT_FRACTION = 0.86
HIGHLIGHT_START = -55.0
HIGHLIGHT_SWEEP = 45.0

# Colours: v1 brightened further for contrast
BG = "#121212"
BODY = "#303030"
OUTER_RING = "#737373"
GROOVE = "#505050"
LABEL = "#2FBE8B"
LABEL_RING = "#C4F4E2"
HIGHLIGHT = "#FFFFFF"

def circle_path(cx, cy, r):
    return f"M {cx-r:.3f} {cy:.3f} a {r:.3f} {r:.3f} 0 1 0 {2*r:.3f} 0 a {r:.3f} {r:.3f} 0 1 0 {-2*r:.3f} 0 Z"

def arc_endpoints(cx, cy, r, start, sweep):
    a0 = math.radians(start)
    a1 = math.radians(start + sweep)
    x0, y0 = cx + r*math.cos(a0), cy + r*math.sin(a0)
    x1, y1 = cx + r*math.cos(a1), cy + r*math.sin(a1)
    return x0, y0, x1, y1

def play_triangle(cx, cy, R, scale=1.0):
    # Right-pointing triangle, visually centred on the label.
    w = 0.12 * R * scale
    h = 0.14 * R * scale
    # Shift right by w/6 so the centroid sits on the centre.
    x0 = cx - w/2 + w/6
    x1 = cx + w/2 + w/6
    return f"M {x0:.3f} {cy-h/2:.3f} L {x1:.3f} {cy:.3f} L {x0:.3f} {cy+h/2:.3f} Z"

def color_svg(size, R, play=False, background=True):
    cx = cy = size/2
    stroke = size/108.0  # 1dp at 108 viewport
    highlight_stroke = 1.4 * size/108.0
    label_r = LABEL_FRACTION * R
    hole_r = HOLE_FRACTION * R
    hx0, hy0, hx1, hy1 = arc_endpoints(cx, cy, HIGHLIGHT_FRACTION*R, HIGHLIGHT_START, HIGHLIGHT_SWEEP)
    parts = []
    parts.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}">')
    if background:
        parts.append(f'  <rect width="{size}" height="{size}" fill="{BG}"/>')
    parts.append(f'  <circle cx="{cx}" cy="{cy}" r="{R}" fill="{BODY}"/>')
    parts.append(f'  <circle cx="{cx}" cy="{cy}" r="{R}" fill="none" stroke="{OUTER_RING}" stroke-width="{stroke:.3f}"/>')
    parts.append(f'  <g fill="none" stroke="{GROOVE}" stroke-width="{stroke:.3f}">')
    for f in GROOVE_FRACTIONS:
        parts.append(f'    <circle cx="{cx}" cy="{cy}" r="{f*R:.3f}"/>')
    parts.append('  </g>')
    parts.append(f'  <circle cx="{cx}" cy="{cy}" r="{label_r:.3f}" fill="{LABEL}"/>')
    parts.append(f'  <circle cx="{cx}" cy="{cy}" r="{label_r:.3f}" fill="none" stroke="{LABEL_RING}" stroke-width="{stroke:.3f}"/>')
    if play:
        parts.append(f'  <path d="{play_triangle(cx, cy, R)}" fill="{BG}" stroke="{BG}" stroke-width="{stroke*0.8:.3f}" stroke-linejoin="round"/>')
    else:
        parts.append(f'  <circle cx="{cx}" cy="{cy}" r="{hole_r:.3f}" fill="{BG}"/>')
    parts.append(f'  <path d="M {hx0:.3f} {hy0:.3f} A {HIGHLIGHT_FRACTION*R:.3f} {HIGHLIGHT_FRACTION*R:.3f} 0 0 1 {hx1:.3f} {hy1:.3f}" fill="none" stroke="{HIGHLIGHT}" stroke-opacity="0.32" stroke-width="{highlight_stroke:.3f}" stroke-linecap="round"/>')
    parts.append('</svg>')
    return "\n".join(parts) + "\n"

def mono_svg(size, R, play=False):
    cx = cy = size/2
    stroke = 1.2 * size/108.0
    label_r = LABEL_FRACTION * R
    hole_r = MONO_HOLE_FRACTION * R
    subpaths = [circle_path(cx, cy, R)]
    for f in GROOVE_FRACTIONS:
        r = f * R
        subpaths.append(circle_path(cx, cy, r - stroke/2))
        subpaths.append(circle_path(cx, cy, r + stroke/2))
    # Transparent gap just outside the label, so the label reads as a separate disc.
    subpaths.append(circle_path(cx, cy, label_r))
    subpaths.append(circle_path(cx, cy, label_r + stroke))
    if play:
        subpaths.append(play_triangle(cx, cy, R))
    else:
        subpaths.append(circle_path(cx, cy, hole_r))
    d = " ".join(subpaths)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}">
  <path fill="#FFFFFF" fill-rule="evenodd" d="{d}"/>
</svg>
'''

# 512 master icon: record diameter 62/108 of the canvas, matching the adaptive safe-zone margin.
R512 = 512 * (62/108) / 2
# 108 adaptive foreground: 62dp record inside the 66dp safe zone.
R108 = 31.0

(OUT / "sideplay-icon-v2.svg").write_text(color_svg(512, R512, play=False, background=True))
(OUT / "sideplay-icon-v2-play.svg").write_text(color_svg(512, R512, play=True, background=True))
(OUT / "sideplay-foreground-v2.svg").write_text(color_svg(108, R108, play=False, background=False))
(OUT / "sideplay-foreground-v2-play.svg").write_text(color_svg(108, R108, play=True, background=False))
(OUT / "sideplay-monochrome-v2.svg").write_text(mono_svg(108, R108, play=False))
(OUT / "sideplay-monochrome-v2-play.svg").write_text(mono_svg(108, R108, play=True))

print("R512", R512, "R108", R108)
