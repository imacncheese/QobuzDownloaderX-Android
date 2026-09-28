#!/usr/bin/env python3
"""Generate the launcher icon.

The mark is a geometric "Q": a ring with a diagonal tail, in the app's own
purple-to-cyan, on a near-black gradient. It is deliberately an original mark
rather than Qobuz's logo — the README is explicit that this app is not
affiliated with, endorsed by, or approved by Qobuz, so their actual logo has no
business here.

Outputs (all under app/src/main/res):
  drawable/ic_launcher_background.xml    gradient + glow, adaptive background
  drawable/ic_launcher_foreground.xml    the Q, adaptive foreground
  drawable/ic_launcher_monochrome.xml    the Q, single colour, for themed icons
  drawable/ic_launcher.xml               background + foreground, one vector
  mipmap-anydpi-v26/ic_launcher.xml      adaptive icon
  mipmap-anydpi-v26/ic_launcher_round.xml
  mipmap-*/ic_launcher{,_round}.png      legacy rasters

minSdk is 26, so every supported device uses the adaptive vectors; the PNGs are
generated for completeness (older tooling and store listings read them).

Stdlib only: no Pillow, no ImageMagick. Shapes are analytic, so coverage comes
from supersampling rather than an SVG rasteriser.

Usage:  python3 tools/gen_icon.py [repo-root] [--preview out.png]
"""
import math
import os
import struct
import sys
import zlib

# ------------------------------------------------------------------ palette
BG_TOP = (0x1B, 0x12, 0x33)
BG_BOTTOM = (0x0C, 0x09, 0x12)
GLOW = (0x6C, 0x4C, 0xF1)
PURPLE = (0x8B, 0x6B, 0xFF)
CYAN = (0x3D, 0xD6, 0xD0)

PURPLE_HEX = "#FF8B6BFF"
CYAN_HEX = "#FF3DD6D0"

# --------------------------------------------------------------- geometry
# Design space is the adaptive-icon 108x108 grid.
CX = CY = 54.0
R_RING = 22.0            # ring centreline radius
STROKE = 8.6             # ring and tail thickness
TAIL_IN = 15.0           # tail starts just inside the ring's inner edge (17.7)
TAIL_OUT = 28.5          # tip reaches 28.5 + 4.3 = 32.8, inside the 33 safe radius
ANGLE = 45.0

SS = 4                   # supersampling factor per axis

LEGACY_SCALE = 1.30      # legacy rasters fill the canvas; adaptive keeps margins
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def lerp(c1, c2, t):
    return tuple(c1[i] + (c2[i] - c1[i]) * t for i in range(3))


def sd_segment(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    L2 = vx * vx + vy * vy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L2))
    return math.hypot(wx - vx * t, wy - vy * t)


def tail_points(k=1.0):
    a = math.radians(ANGLE)
    dx, dy = math.cos(a), math.sin(a)
    return (CX + dx * TAIL_IN * k, CY + dy * TAIL_IN * k,
            CX + dx * TAIL_OUT * k, CY + dy * TAIL_OUT * k)


def render(size, k=1.0, round_mask=False):
    R = R_RING * k
    half = STROKE * k / 2.0
    ax, ay, bx, by = tail_points(k)
    inv = 108.0 / size
    rows = []

    for y in range(size):
        row = bytearray()
        for x in range(size):
            ar = ag = ab = aa = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    px = (x + (sx + 0.5) / SS) * inv
                    py = (y + (sy + 0.5) / SS) * inv

                    g = max(0.0, min(1.0, (px + py) / 216.0))
                    br, bg_, bb = lerp(BG_TOP, BG_BOTTOM, g)

                    d = math.hypot(px - CX, py - CY)
                    glow = max(0.0, 1.0 - d / 46.0) ** 2 * 0.30
                    br += (GLOW[0] - br) * glow
                    bg_ += (GLOW[1] - bg_) * glow
                    bb += (GLOW[2] - bb) * glow

                    if abs(d - R) <= half or sd_segment(px, py, ax, ay, bx, by) <= half:
                        t = max(0.0, min(1.0, ((px + py) / 2.0 - 10.0) / 88.0))
                        br, bg_, bb = lerp(PURPLE, CYAN, t)

                    ar += br
                    ag += bg_
                    ab += bb
                    aa += 255.0

            n = SS * SS
            a = int(aa / n)
            if round_mask:
                edge = math.hypot((x + 0.5) * inv - 54.0, (y + 0.5) * inv - 54.0) - 54.0
                if edge > 0.5:
                    a = 0
                elif edge > -0.5:
                    a = int(a * (0.5 - edge))
            row += bytes((max(0, min(255, int(ar / n))),
                          max(0, min(255, int(ag / n))),
                          max(0, min(255, int(ab / n))), a))
        rows.append(row)
    return rows


def write_png(path, size, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n")
        fh.write(chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)))
        fh.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        fh.write(chunk(b"IEND", b""))


# ------------------------------------------------------------------- vectors
def fmt(v):
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    return s if s else "0"


def background_xml():
    stops = [(0.0, 0x4D), (0.25, 0x2B), (0.5, 0x13), (0.75, 0x05), (1.0, 0x00)]
    glow_items = "\n".join(
        '            <item android:offset="%s" android:color="#%02X%02X%02X%02X"/>'
        % (fmt(o), a, GLOW[0], GLOW[1], GLOW[2]) for o, a in stops)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  Generated by tools/gen_icon.py. Do not edit by hand.

  Adaptive-icon background: a diagonal near-black gradient with a soft purple
  glow behind the mark, which keeps the icon from reading as a flat black tile.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FF{BG_TOP[0]:02X}{BG_TOP[1]:02X}{BG_TOP[2]:02X}"/>
                <item android:offset="1" android:color="#FF{BG_BOTTOM[0]:02X}{BG_BOTTOM[1]:02X}{BG_BOTTOM[2]:02X}"/>
            </gradient>
        </aapt:attr>
    </path>

    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54" android:centerY="54"
                android:gradientRadius="46">
{glow_items}
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


Q_COMMENT = """  The mark is a ring with a diagonal tail that starts just inside the ring's
  inner edge, so it reads as one stroke rather than a blob stuck to the side.
  Everything stays inside the 33dp safe radius, so no launcher mask clips it.
"""


def q_paths():
    ax, ay, bx, by = tail_points()
    ring = (f"M{fmt(CX - R_RING)},{fmt(CY)} "
            f"a{fmt(R_RING)},{fmt(R_RING)} 0 1,0 {fmt(R_RING * 2)},0 "
            f"a{fmt(R_RING)},{fmt(R_RING)} 0 1,0 {fmt(-R_RING * 2)},0")
    tail = f"M{fmt(ax)},{fmt(ay)} L{fmt(bx)},{fmt(by)}"
    return ring, tail


def gradient_attr(indent):
    return f'''{indent}<aapt:attr name="android:strokeColor">
{indent}    <gradient
{indent}        android:type="linear"
{indent}        android:startX="10" android:startY="10"
{indent}        android:endX="98" android:endY="98">
{indent}        <item android:offset="0" android:color="{PURPLE_HEX}"/>
{indent}        <item android:offset="1" android:color="{CYAN_HEX}"/>
{indent}    </gradient>
{indent}</aapt:attr>'''


def foreground_xml():
    ring, tail = q_paths()
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  Generated by tools/gen_icon.py. Do not edit by hand.

{Q_COMMENT}-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:pathData="{ring}"
        android:strokeWidth="{fmt(STROKE)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round">
{gradient_attr("        ")}
    </path>

    <path
        android:pathData="{tail}"
        android:strokeWidth="{fmt(STROKE)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round">
{gradient_attr("        ")}
    </path>
</vector>
'''


def monochrome_xml():
    ring, tail = q_paths()
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  Generated by tools/gen_icon.py. Do not edit by hand.

  Android 13+ themed icons. The system recolours this, so it is drawn flat in a
  single colour; the gradient above would be thrown away.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:pathData="{ring}"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{fmt(STROKE)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"/>

    <path
        android:pathData="{tail}"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{fmt(STROKE)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"/>
</vector>
'''


def composed_xml():
    """Background and foreground in one vector, for anything wanting a single drawable."""
    ring, tail = q_paths()
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  Generated by tools/gen_icon.py. Do not edit by hand.

  Background and foreground composited into a single drawable, for callers that
  want one flat icon. Devices on API 26+ use the adaptive icon in
  mipmap-anydpi-v26 instead.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FF{BG_TOP[0]:02X}{BG_TOP[1]:02X}{BG_TOP[2]:02X}"/>
                <item android:offset="1" android:color="#FF{BG_BOTTOM[0]:02X}{BG_BOTTOM[1]:02X}{BG_BOTTOM[2]:02X}"/>
            </gradient>
        </aapt:attr>
    </path>

    <path
        android:pathData="{ring}"
        android:strokeWidth="{fmt(STROKE)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round">
{gradient_attr("        ")}
    </path>

    <path
        android:pathData="{tail}"
        android:strokeWidth="{fmt(STROKE)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round">
{gradient_attr("        ")}
    </path>
</vector>
'''


def adaptive_xml():
    return '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/gen_icon.py. Do not edit by hand. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


def main():
    args = [a for a in sys.argv[1:]]
    preview = None
    if "--preview" in args:
        i = args.index("--preview")
        preview = args[i + 1]
        del args[i:i + 2]
    repo = args[0] if args else "."

    res = os.path.join(repo, "app", "src", "main", "res")

    def put(rel, text):
        path = os.path.join(res, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        print("  wrote", rel)

    put("drawable/ic_launcher_background.xml", background_xml())
    put("drawable/ic_launcher_foreground.xml", foreground_xml())
    put("drawable/ic_launcher_monochrome.xml", monochrome_xml())
    put("drawable/ic_launcher.xml", composed_xml())
    put("mipmap-anydpi-v26/ic_launcher.xml", adaptive_xml())
    put("mipmap-anydpi-v26/ic_launcher_round.xml", adaptive_xml())

    for name, size in DENSITIES.items():
        d = os.path.join(res, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), size,
                  render(size, k=LEGACY_SCALE, round_mask=False))
        write_png(os.path.join(d, "ic_launcher_round.png"), size,
                  render(size, k=LEGACY_SCALE, round_mask=True))
        print("  wrote mipmap-%s/ic_launcher{,_round}.png (%dpx)" % (name, size))

    if preview:
        os.makedirs(os.path.dirname(preview) or ".", exist_ok=True)
        s = 432
        write_png(preview, s, render(s, k=1.0))
        print("  wrote preview", preview)


if __name__ == "__main__":
    main()
