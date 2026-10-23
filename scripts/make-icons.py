#!/usr/bin/env python3
"""The app icon, drawn once and rendered for every platform.

The mark is a calendar leaf: a grape header band with two binding tabs, the
day marked in azure, and a written entry beneath. Everything is drawn here in
a 1024 square; this script writes the source SVG and renders it with
rsvg-convert (Homebrew: `brew install librsvg`):

    python3 scripts/make-icons.py

Writes:
  design/icon/librelog-icon.svg            the full-bleed source, 1024
  design/icon/play-store-512.png           Play Console's hi-res icon
  apple/App/Assets.xcassets/AppIcon.appiconset/icon-1024.png   iOS
  apple/App/Assets.xcassets/AppIcon.appiconset/mac-*.png        macOS
  android/app/src/main/res/drawable/ic_launcher_foreground.xml
  android/app/src/main/res/drawable/ic_launcher_monochrome.xml
  windows/src/Librelog.App/Assets/*.png    tiles, store logo, splash
"""
import os
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PAPER = "#faf8f4"
RAISED = "#ffffff"
INK = "#444054"
AZURE = "#007fff"

# The leaf, in the 1024 square. Its box is x 240–784, y 180–808.
BOX = (240, 180, 784, 808)
# The day's mark, drawn a little heavier than the lines so it holds at 60px.
LINES = [(476, 440, 712, 36), (476, 504, 640, 36), (312, 608, 712, 36), (312, 672, 712, 36)]


def mark(raised=RAISED, ink=INK, azure=AZURE):
    """The leaf's shapes as SVG, in 1024 units."""
    s = f'<rect x="252" y="236" width="520" height="560" rx="40" fill="{raised}" stroke="{ink}" stroke-width="24"/>\n'
    s += f'<path d="M240 368 V264 a40 40 0 0 1 40 -40 H744 a40 40 0 0 1 40 40 V368 Z" fill="{ink}"/>\n'
    for x in (364, 620):
        s += f'<rect x="{x}" y="180" width="40" height="112" rx="20" fill="{ink}"/>\n'
    s += f'<rect x="312" y="428" width="120" height="120" rx="16" fill="{azure}"/>\n'
    for x1, y, x2, h in LINES:
        s += f'<rect x="{x1}" y="{y}" width="{x2 - x1}" height="{h}" fill="{ink}"/>\n'
    return s


def svg(width, height, scale, background=PAPER):
    """The mark centred on a `width`×`height` canvas, `scale` times its 1024 size."""
    cx, cy = (BOX[0] + BOX[2]) / 2, (BOX[1] + BOX[3]) / 2
    tx, ty = width / 2 - cx * scale, height / 2 - cy * scale
    bg = f'<rect width="{width}" height="{height}" fill="{background}"/>\n' if background else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">\n'
        f"{bg}<g transform=\"translate({tx:.3f} {ty:.3f}) scale({scale:.5f})\">\n{mark()}</g>\n</svg>\n"
    )


# The Mac draws no mask of its own, so its icon carries its own shape: the
# rounded square of Apple's icon grid, 824 in the 1024 canvas with a 100
# margin, and a hairline in place of the shadow the house style leaves out.
MAC_BODY, MAC_MARGIN, MAC_RADIUS = 824, 100, 185
MAC_HAIRLINE = "#d8d5dd"


def mac_svg():
    """The leaf on the Mac's rounded paper square, transparent around it."""
    leaf = BOX[3] - BOX[1]
    scale = MAC_BODY * 0.66 / leaf
    cx, cy = (BOX[0] + BOX[2]) / 2, (BOX[1] + BOX[3]) / 2
    tx, ty = 512 - cx * scale, 512 - cy * scale
    m, b, r = MAC_MARGIN, MAC_BODY, MAC_RADIUS
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">\n'
        f'<rect x="{m + 2}" y="{m + 2}" width="{b - 4}" height="{b - 4}" rx="{r}" fill="{PAPER}" '
        f'stroke="{MAC_HAIRLINE}" stroke-width="4"/>\n'
        f'<g transform="translate({tx:.3f} {ty:.3f}) scale({scale:.5f})">\n{mark()}</g>\n</svg>\n'
    )


# Every size the Mac asks an app icon for, in points, at 1x and 2x.
MAC_SIZES = [16, 32, 128, 256, 512]


def render(source, path, width, height):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(source)
    try:
        subprocess.run(["rsvg-convert", "-w", str(width), "-h", str(height), f.name, "-o", path], check=True)
    finally:
        os.unlink(f.name)


def android_vector(monochrome):
    """The adaptive icon's foreground (or monochrome) layer: 108dp, with the
    leaf inside the 66dp circle every launcher mask keeps."""
    # Half the leaf's diagonal, less its rounded corners, fits a 33dp radius.
    scale = 0.082
    cx, cy = (BOX[0] + BOX[2]) / 2, (BOX[1] + BOX[3]) / 2
    tx, ty = 54 - cx * scale, 54 - cy * scale
    ink = "#FFFFFFFF" if monochrome else INK.upper()
    azure = "#FFFFFFFF" if monochrome else AZURE.upper()
    paths = []
    if not monochrome:
        # The page's white; left out of the monochrome layer, where only
        # shape counts and a filled page would hide the lines.
        paths.append(f'<path android:fillColor="{RAISED.upper()}" android:pathData="M292,236 H732 a40,40 0 0 1 40,40 V756 a40,40 0 0 1 -40,40 H292 a40,40 0 0 1 -40,-40 V276 a40,40 0 0 1 40,-40 Z" />')
    paths.append(f'<path android:strokeColor="{ink}" android:strokeWidth="24" android:pathData="M292,236 H732 a40,40 0 0 1 40,40 V756 a40,40 0 0 1 -40,40 H292 a40,40 0 0 1 -40,-40 V276 a40,40 0 0 1 40,-40 Z" />')
    paths.append(f'<path android:fillColor="{ink}" android:pathData="M240,368 V264 a40,40 0 0 1 40,-40 H744 a40,40 0 0 1 40,40 V368 Z" />')
    for x in (364, 620):
        paths.append(f'<path android:fillColor="{ink}" android:pathData="M{x + 20},180 a20,20 0 0 1 20,20 V272 a20,20 0 0 1 -40,0 V200 a20,20 0 0 1 20,-20 Z" />')
    paths.append(f'<path android:fillColor="{azure}" android:pathData="M328,428 H416 a16,16 0 0 1 16,16 V532 a16,16 0 0 1 -16,16 H328 a16,16 0 0 1 -16,-16 V444 a16,16 0 0 1 16,-16 Z" />')
    for x1, y, x2, h in LINES:
        paths.append(f'<path android:fillColor="{ink}" android:pathData="M{x1},{y} H{x2} V{y + h} H{x1} Z" />')
    body = "\n        ".join(paths)
    what = "monochrome layer (themed icons)" if monochrome else "foreground layer"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/make-icons.py: the icon's {what}. Edit the script, not this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" android:scaleX="{scale}" android:scaleY="{scale}">
        {body}
    </group>
</vector>
'''


def main():
    full = svg(1024, 1024, 1.0)
    os.makedirs(f"{ROOT}/design/icon", exist_ok=True)
    with open(f"{ROOT}/design/icon/librelog-icon.svg", "w") as f:
        f.write(full)
    render(full, f"{ROOT}/design/icon/play-store-512.png", 512, 512)
    icons = f"{ROOT}/apple/App/Assets.xcassets/AppIcon.appiconset"
    render(full, f"{icons}/icon-1024.png", 1024, 1024)
    mac = mac_svg()
    with open(f"{ROOT}/design/icon/librelog-icon-mac.svg", "w") as f:
        f.write(mac)
    images = [{"filename": "icon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}]
    for points in MAC_SIZES:
        for scale in (1, 2):
            name = f"mac-{points}@{scale}x.png"
            render(mac, f"{icons}/{name}", points * scale, points * scale)
            images.append({"filename": name, "idiom": "mac", "scale": f"{scale}x", "size": f"{points}x{points}"})
    import json
    with open(f"{icons}/Contents.json", "w") as f:
        json.dump({"images": images, "info": {"author": "xcode", "version": 1}}, f, indent=2)
        f.write("\n")

    res = f"{ROOT}/android/app/src/main/res/drawable"
    with open(f"{res}/ic_launcher_foreground.xml", "w") as f:
        f.write(android_vector(monochrome=False))
    with open(f"{res}/ic_launcher_monochrome.xml", "w") as f:
        f.write(android_vector(monochrome=True))

    # Windows: square tiles fill most of the square; the wide tile and the
    # splash keep the leaf small in the middle of the paper.
    assets = f"{ROOT}/windows/src/Librelog.App/Assets"
    leaf = BOX[3] - BOX[1]
    for name, size, fill in [("Square44x44Logo", 44, 0.86), ("Square150x150Logo", 150, 0.62),
                             ("StoreLogo", 50, 0.86), ("LockScreenLogo", 24, 0.92)]:
        render(svg(size, size, size * fill / leaf), f"{assets}/{name}.png", size, size)
    render(svg(310, 150, 150 * 0.62 / leaf), f"{assets}/Wide310x150Logo.png", 310, 150)
    render(svg(620, 300, 300 * 0.5 / leaf), f"{assets}/SplashScreen.png", 620, 300)


if __name__ == "__main__":
    main()
