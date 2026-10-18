#!/usr/bin/env python3
"""Regenerates the Google "G" on the Sign in with Google buttons from Google's
own asset, for every platform:

    curl -LO https://developers.google.com/static/identity/images/signin-assets.zip
    unzip signin-assets.zip -d signin-assets
    scripts/google-g-logo.py "signin-assets/Android + Web/SVG/Light/Theme=Light, Show text=No, Shape=Square, Platform=Android+Web.svg"

writes apple/App/Assets.xcassets/GoogleG.imageset/google-g.pdf (needs
rsvg-convert: `brew install librsvg`), android/app/src/main/res/drawable/
ic_google_g.xml and windows/src/Librelog.App/Assets/GoogleG.svg. The kit's
SVGs come from https://developers.google.com/identity/branding-guidelines.

The official SVG is a Figma export: the G outline masks a CSS conic-gradient
inside <foreignObject> plus six Gaussian-blurred ellipses. No native renderer
(CoreSVG, VectorDrawable, Direct2D SVG) supports foreignObject, masks or
filters, so this keeps the official outline, gradient stops, transform and
ellipse geometry verbatim and re-expresses only the effects:
  * conic gradient -> sweep gradient (Android) / overlapping solid wedges (SVG)
  * blurred ellipse -> ellipse filled with a radial gradient whose falloff is
    the Gaussian-blurred edge profile.
"""
import math
import os
import re
import subprocess
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = open(sys.argv[1]).read()

OFF = 10.0  # the G's box in the 40x40 icon is (10,10)-(30,30)

g_path = re.search(r'<mask[^>]*>\s*<path d="([^"]+)"', src).group(1)


def shift_path(d, dx, dy):
    # Absolute commands only in this file (M, H, V, C, L, Z); shift coordinates.
    out, cmd, idx = [], None, 0
    for tok in re.findall(r'[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?', d):
        if tok.isalpha():
            cmd, idx = tok, 0
            out.append(tok)
            continue
        v = float(tok)
        if cmd == 'H':
            v += dx
        elif cmd == 'V':
            v += dy
        else:
            v += dx if idx % 2 == 0 else dy
        idx += 1
        out.append(f'{v:.4f}'.rstrip('0').rstrip('.'))
    s = ''
    for t in out:
        s += t if t.isalpha() else (' ' + t if s and not s[-1].isalpha() else t)
    return s


G = shift_path(g_path, -OFF, -OFF)

# Conic gradient: "from 90deg", transformed by matrix(a b c d e f).
m = re.search(r'matrix\(([^)]+)\)', src).group(1).split()
a, b, c, d, e, f = map(float, m)
rot = math.degrees(math.atan2(b, a))  # local +x direction in global coords
cx, cy = e - OFF, f - OFF
stops = [(float(p), tuple(int(x) for x in rgb.split(','))) for rgb, p in
         re.findall(r'rgba\((\d+, \d+, \d+), 1\) ([\d.]+)deg', src)]


def colour_at(local_deg):
    t = local_deg % 360
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if p0 <= t <= p1:
            k = 0 if p1 == p0 else (t - p0) / (p1 - p0)
            return tuple(round(c0[i] + (c1[i] - c0[i]) * k) for i in range(3))
    return stops[-1][1]


def hexc(rgb, alpha=None):
    s = '#%02X%02X%02X' % rgb
    return s if alpha is None else '#%02X%02X%02X%02X' % ((round(alpha * 255),) + rgb)


# Blurred ellipses: (cx, cy, rx, ry, rotation, colour), all stdDeviation 1.
ellipses = []
for el in re.finditer(r'<ellipse ([^>]+)/>', src):
    at = dict(re.findall(r'(\w+)="([^"]+)"', el.group(1)))
    r = re.search(r'rotate\(([-\d.]+)', at.get('transform', ''))
    ellipses.append((float(at['cx']) - OFF, float(at['cy']) - OFF, float(at['rx']), float(at['ry']),
                     float(r.group(1)) if r else 0.0, at['fill']))

SD = 1.0
EXT = 3.0 * SD


def phi(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def falloff(rx, ry, n=9):
    """Stops (offset, opacity) for a radial gradient over an ellipse grown by
    EXT, averaging the blurred-edge profile of both axes."""
    out = []
    for i in range(n + 1):
        t = i / n
        o = 0.5 * (phi((rx - t * (rx + EXT)) / SD) + phi((ry - t * (ry + EXT)) / SD))
        out.append((t, o))
    return out


def rgb_of(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# ---- Plain SVG (CoreSVG / cairo / Direct2D friendly) -------------------------
STEP, OVER, R = 1.0, 0.75, 16.0
wedges = []
n = int(360 / STEP)
for i in range(n):
    g0 = i * STEP
    g1 = g0 + STEP + OVER
    col = colour_at(g0 + STEP / 2 - rot)  # global angle -> local angle
    p = []
    for ang in (g0, g1):
        r_ = math.radians(ang)
        p.append((cx + R * math.cos(r_), cy + R * math.sin(r_)))
    wedges.append(f'<path d="M{cx:.3f} {cy:.3f}L{p[0][0]:.3f} {p[0][1]:.3f}L{p[1][0]:.3f} {p[1][1]:.3f}Z" fill="{hexc(col)}"/>')

defs, blobs = [], []
for k, (ex, ey, rx, ry, er, fill) in enumerate(ellipses):
    gid = f'b{k}'
    st = ''.join(f'<stop offset="{t:.3f}" stop-color="{fill}" stop-opacity="{o:.3f}"/>' for t, o in falloff(rx, ry))
    defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="0" cy="0" r="1">{st}</radialGradient>')
    blobs.append(f'<g transform="translate({ex:.4f} {ey:.4f}) rotate({er}) scale({rx + EXT:.4f} {ry + EXT:.4f})">'
                 f'<circle r="1" fill="url(#{gid})"/></g>')

svg = (
    '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20">\n'
    '<!-- Google "G" logo, from Google\'s Sign-In branding kit (signin-assets.zip);\n'
    '     the official outline, gradient stops and highlights, with the Figma-only\n'
    '     conic gradient and blurs re-expressed for native SVG renderers. -->\n'
    f'<defs><clipPath id="g"><path d="{G}"/></clipPath>{"".join(defs)}</defs>\n'
    f'<g clip-path="url(#g)">\n{chr(10).join(wedges)}\n{chr(10).join(blobs)}\n</g>\n</svg>\n'
)
windows_svg = os.path.join(root, 'windows/src/Librelog.App/Assets/GoogleG.svg')
open(windows_svg, 'w').write(svg)
# 72 dpi so the PDF is 20pt square, the size the button draws it at.
subprocess.run(['rsvg-convert', '-f', 'pdf', '-d', '72', '-p', '72', windows_svg,
                '-o', os.path.join(root, 'apple/App/Assets.xcassets/GoogleG.imageset/google-g.pdf')], check=True)

# ---- Android VectorDrawable --------------------------------------------------
# SweepGradient starts at 3 o'clock and runs clockwise, as the global angle does.
pts = []
for i in range(0, 361, 3):
    pts.append((i / 360, colour_at(i - rot)))
items = '\n'.join(f'                    <item android:offset="{o:.4f}" android:color="{hexc(cl)}" />' for o, cl in pts)
vd_blobs = []
for ex, ey, rx, ry, er, fill in ellipses:
    st = '\n'.join(f'                        <item android:offset="{t:.3f}" android:color="{hexc(rgb_of(fill), o)}" />'
                   for t, o in falloff(rx, ry))
    vd_blobs.append(f'''        <group
            android:translateX="{ex:.4f}"
            android:translateY="{ey:.4f}"
            android:rotation="{er}"
            android:scaleX="{rx + EXT:.4f}"
            android:scaleY="{ry + EXT:.4f}">
            <path android:pathData="M-1,0a1,1 0,1 0,2 0a1,1 0,1 0,-2 0z">
                <aapt:attr name="android:fillColor">
                    <gradient android:type="radial" android:centerX="0" android:centerY="0" android:gradientRadius="1">
{st}
                    </gradient>
                </aapt:attr>
            </path>
        </group>''')
vd = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Google "G" logo, from Google's Sign-In branding kit (signin-assets.zip):
     the official outline, gradient stops and highlights, with the Figma-only
     conic gradient and blurs re-expressed for VectorDrawable. Don't recolour
     or resize it within the button: Google's branding guidelines fix both. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="20"
    android:viewportHeight="20">
    <group>
        <clip-path android:pathData="{G}" />
        <path android:pathData="M0,0h20v20h-20z">
            <aapt:attr name="android:fillColor">
                <gradient android:type="sweep" android:centerX="{cx:.4f}" android:centerY="{cy:.4f}">
{items}
                </gradient>
            </aapt:attr>
        </path>
{chr(10).join(vd_blobs)}
    </group>
</vector>
'''
open(os.path.join(root, 'android/app/src/main/res/drawable/ic_google_g.xml'), 'w').write(vd)
