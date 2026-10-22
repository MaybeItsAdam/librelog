#!/usr/bin/env python3
"""A fictional journal for store screenshots, never anyone's real one.

Renders a few landscape "photos" procedurally (no real photos are used),
then fills a fresh journal database with a few weeks of made-up entries,
photos, kept calendar events and a locked note through the core's own API
(core/examples/store_demo.rs):

    python3 scripts/store-demo.py /tmp/librelog-demo [today YYYY-MM-DD]

Writes <dir>/Librelog/journal.sqlite with media/ and thumbs/ beside it,
ready to copy into a simulator's or emulator's app container:

    iOS:      <data container>/Library/Application Support/Librelog/
    Android:  /data/data/uk.co.maybeitssoftware.librelog/files/Librelog/

The locked note's password is "harbour".
Needs Pillow (pip install pillow) and cargo.
"""
import datetime
import json
import os
import random
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W, H = 2400, 1800


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def sky(img, stops):
    """A vertical gradient through (position, colour) stops."""
    d = ImageDraw.Draw(img)
    for y in range(img.height):
        t = y / (img.height - 1)
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if p0 <= t <= p1:
                d.line([(0, y), (img.width, y)], fill=lerp(c0, c1, (t - p0) / (p1 - p0 or 1)))
                break


def ridge(rng, base, rough, n=9):
    """A ridge line by midpoint displacement: y for each x across the frame."""
    pts = [base + rng.uniform(-rough, rough), base + rng.uniform(-rough, rough)]
    amp = rough
    for _ in range(n):
        nxt = []
        for a, b in zip(pts, pts[1:]):
            nxt += [a, (a + b) / 2 + rng.uniform(-amp, amp)]
        pts = nxt + [pts[-1]]
        amp *= 0.55
    step = W / (len(pts) - 1)
    return [(i * step, y) for i, y in enumerate(pts)]


def hill(img, rng, base, rough, colour, blur=0):
    layer = Image.new("RGBA", img.size)
    line = ridge(rng, base, rough)
    ImageDraw.Draw(layer).polygon(line + [(W, H), (0, H)], fill=colour + (255,))
    if blur:
        layer = layer.filter(ImageFilter.GaussianBlur(blur))
    img.alpha_composite(layer)


def sun(img, x, y, r, colour, glow):
    layer = Image.new("RGBA", img.size)
    d = ImageDraw.Draw(layer)
    for i in range(12, 0, -1):
        rr = r + i * r * 0.45
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=glow + (int(14 * (13 - i) / 12),))
    d.ellipse([x - r, y - r, x + r, y + r], fill=colour + (255,))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(r * 0.15)))


def grain(img, amount=10):
    noise = Image.effect_noise(img.size, 40).convert("RGB")
    return Image.blend(img.convert("RGB"), noise, amount / 255)


def coast(rng):
    """Dusk over the sea, a headland on the left."""
    img = Image.new("RGBA", (W, H))
    sky(img, [(0, (52, 62, 112)), (0.35, (196, 120, 128)), (0.55, (250, 186, 120)), (1, (250, 186, 120))])
    sun(img, 1500, 930, 70, (255, 226, 170), (255, 200, 140))
    sea = Image.new("RGBA", img.size)
    d = ImageDraw.Draw(sea)
    for y in range(990, H):
        t = (y - 990) / (H - 990)
        d.line([(0, y), (W, y)], fill=lerp((214, 140, 118), (40, 52, 92), t ** 0.6) + (255,))
    for _ in range(260):
        y = rng.uniform(1000, H)
        x = rng.gauss(1500, 120 + (y - 990) * 0.4)
        L = rng.uniform(20, 90) * (1 + (y - 990) / 400)
        d.line([(x - L, y), (x + L, y)], fill=(255, 214, 160, rng.randint(60, 160)), width=rng.choice((2, 3, 4)))
    img.alpha_composite(sea)
    head = Image.new("RGBA", img.size)
    hd = ImageDraw.Draw(head)
    line = [(x, y + 0.0) for x, y in ridge(rng, 0, 30)]
    pts = [(0, 760)] + [(x * 0.42, 760 + (x / W) ** 1.8 * 260 + y) for x, y in line] + [(1010, 1020), (0, 1060)]
    hd.polygon(pts, fill=(38, 34, 56, 255))
    img.alpha_composite(head)
    return grain(img)


def hills(rng):
    """Autumn hills in afternoon light."""
    img = Image.new("RGBA", (W, H))
    sky(img, [(0, (120, 168, 214)), (0.6, (226, 230, 226)), (1, (226, 230, 226))])
    cloud = Image.new("RGBA", img.size)
    cd = ImageDraw.Draw(cloud)
    for _ in range(40):
        x, y = rng.uniform(0, W), rng.uniform(80, 520)
        rx, ry = rng.uniform(120, 340), rng.uniform(30, 70)
        cd.ellipse([x - rx, y - ry, x + rx, y + ry], fill=(255, 255, 255, 90))
    img.alpha_composite(cloud.filter(ImageFilter.GaussianBlur(30)))
    hill(img, rng, 820, 90, (150, 160, 170), blur=3)
    hill(img, rng, 980, 120, (150, 128, 84), blur=1)
    hill(img, rng, 1150, 110, (176, 112, 52))
    hill(img, rng, 1330, 90, (120, 108, 46))
    # A hedgerow and a few trees along the near field.
    trees = Image.new("RGBA", img.size)
    td = ImageDraw.Draw(trees)
    for _ in range(9):
        x, base = rng.uniform(100, W - 100), rng.uniform(1360, 1460)
        td.rectangle([x - 8, base - 140, x + 8, base], fill=(60, 44, 34, 255))
        for _ in range(18):
            ox, oy, r = rng.gauss(0, 60), rng.gauss(-200, 50), rng.uniform(40, 80)
            td.ellipse([x + ox - r, base + oy - r, x + ox + r, base + oy + r],
                       fill=rng.choice([(196, 98, 40), (214, 140, 52), (150, 70, 36)]) + (255,))
    img.alpha_composite(trees)
    hill(img, rng, 1560, 50, (92, 104, 52))
    return grain(img)


def mist(rng):
    """Early mist over layered ridges."""
    img = Image.new("RGBA", (W, H))
    sky(img, [(0, (206, 214, 226)), (1, (240, 236, 232))])
    sun(img, 640, 520, 60, (255, 246, 228), (255, 240, 220))
    for i, (base, c) in enumerate([(700, (176, 184, 200)), (860, (146, 154, 176)), (1020, (114, 122, 150)),
                                   (1200, (84, 90, 120)), (1400, (56, 60, 86))]):
        hill(img, rng, base, 110 - i * 8, c, blur=max(0, 6 - i * 2))
        haze = Image.new("RGBA", img.size, (236, 234, 236, 40))
        img.alpha_composite(haze)
    return grain(img, 8)


def field(rng):
    """A stubble field and a line of trees at golden hour."""
    img = Image.new("RGBA", (W, H))
    sky(img, [(0, (98, 142, 196)), (0.45, (238, 206, 150)), (0.62, (250, 214, 150)), (1, (250, 214, 150))])
    sun(img, 1820, 1000, 55, (255, 236, 190), (255, 210, 150))
    trees = Image.new("RGBA", img.size)
    td = ImageDraw.Draw(trees)
    x = -40
    while x < W + 40:
        r = rng.uniform(50, 110)
        td.ellipse([x - r, 1080 - r * 1.6, x + r, 1080 + r * 0.2], fill=(52, 58, 44, 255))
        x += rng.uniform(40, 90)
    td.rectangle([0, 1070, W, 1110], fill=(52, 58, 44, 255))
    img.alpha_composite(trees.filter(ImageFilter.GaussianBlur(1.5)))
    ground = Image.new("RGBA", img.size)
    gd = ImageDraw.Draw(ground)
    for y in range(1100, H):
        t = (y - 1100) / (H - 1100)
        gd.line([(0, y), (W, y)], fill=lerp((206, 168, 98), (150, 112, 56), t) + (255,))
    for i in range(70):
        # Rows of stubble converging on a far point.
        x0 = -2400 + i * 105
        gd.line([(1200 + (x0 - 1200) * 0.08, 1100), (x0, H)], fill=(128, 94, 46, 120), width=4)
    img.alpha_composite(ground)
    return grain(img)


def harbour(rng):
    """Boats in a small harbour at low tide, flat midday light."""
    img = Image.new("RGBA", (W, H))
    sky(img, [(0, (140, 182, 220)), (0.5, (222, 232, 236)), (1, (222, 232, 236))])
    hill(img, rng, 700, 60, (130, 150, 120), blur=2)
    town = Image.new("RGBA", img.size)
    d = ImageDraw.Draw(town)
    x = 0
    while x < W:
        w = rng.uniform(90, 170)
        h = rng.uniform(150, 260)
        c = rng.choice([(236, 230, 214), (214, 204, 186), (196, 206, 214), (232, 216, 196), (240, 238, 232)])
        d.rectangle([x, 900 - h, x + w, 900], fill=c + (255,))
        d.polygon([(x - 6, 900 - h), (x + w / 2, 900 - h - 60), (x + w + 6, 900 - h)], fill=(96, 92, 100, 255))
        for wy in range(int(900 - h + 30), 880, 60):
            for wx in range(int(x + 20), int(x + w - 20), 45):
                d.rectangle([wx, wy, wx + 18, wy + 26], fill=(70, 76, 96, 255))
        x += w + rng.uniform(2, 14)
    img.alpha_composite(town)
    sand = Image.new("RGBA", img.size)
    sd = ImageDraw.Draw(sand)
    for y in range(900, H):
        t = (y - 900) / (H - 900)
        sd.line([(0, y), (W, y)], fill=lerp((206, 190, 160), (184, 164, 128), t) + (255,))
    sd.polygon([(0, 1060), (W, 980), (W, 1140), (0, 1240)], fill=(120, 160, 170, 255))
    img.alpha_composite(sand)
    boats = Image.new("RGBA", img.size)
    bd = ImageDraw.Draw(boats)
    for _ in range(6):
        bx, by = rng.uniform(150, W - 400), rng.uniform(1280, 1620)
        L = rng.uniform(220, 360)
        hull = rng.choice([(196, 52, 64), (32, 96, 164), (240, 236, 228), (40, 120, 92)])
        bd.polygon([(bx, by), (bx + L, by), (bx + L * 0.85, by + L * 0.22), (bx + L * 0.12, by + L * 0.22)], fill=hull + (255,))
        bd.rectangle([bx + L * 0.45, by - L * 0.9, bx + L * 0.45 + 6, by], fill=(80, 70, 60, 255))
        bd.line([(bx + L * 0.45, by + L * 0.2), (bx + L * 0.45 + 30, by + L * 0.5)], fill=(90, 80, 70, 200), width=3)
    img.alpha_composite(boats)
    return grain(img)


SCENES = {"coast": coast, "hills": hills, "mist": mist, "field": field, "harbour": harbour}


def render(out):
    """Writes each scene as an original JPEG, a 1024 px preview and the RGBA
    the core hashes, as the apps would hand them over."""
    os.makedirs(out, exist_ok=True)
    made = {}
    for i, (name, draw) in enumerate(SCENES.items()):
        img = draw(random.Random(1000 + i))
        orig = os.path.join(out, f"{name}.jpg")
        img.save(orig, quality=88)
        prev = img.copy()
        prev.thumbnail((1024, 1024), Image.LANCZOS)
        prev.save(os.path.join(out, f"{name}-1024.jpg"), quality=85)
        small = img.copy()
        small.thumbnail((100, 100), Image.LANCZOS)
        rgba = small.convert("RGBA")
        with open(os.path.join(out, f"{name}.rgba"), "wb") as f:
            f.write(rgba.tobytes())
        made[name] = {"width": img.width, "height": img.height, "hash_width": rgba.width, "hash_height": rgba.height}
    with open(os.path.join(out, "photos.json"), "w") as f:
        json.dump(made, f)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out = os.path.abspath(sys.argv[1])
    today = sys.argv[2] if len(sys.argv) > 2 else datetime.date.today().isoformat()
    photos = os.path.join(out, "photos")
    journal = os.path.join(out, "Librelog")
    shutil.rmtree(journal, ignore_errors=True)
    os.makedirs(journal)
    render(photos)
    subprocess.run(["cargo", "run", "-q", "--example", "store_demo", "--",
                    os.path.join(journal, "journal.sqlite"), today, photos],
                   cwd=os.path.join(ROOT, "core"), check=True)


if __name__ == "__main__":
    main()
