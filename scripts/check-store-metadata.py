#!/usr/bin/env python3
"""Checks fastlane/metadata against the App Store and Google Play length
limits and prints each file's character count.

    scripts/check-store-metadata.py
"""
import pathlib, sys

root = pathlib.Path(__file__).resolve().parent.parent / "fastlane" / "metadata"
APPLE = {"name.txt": 30, "subtitle.txt": 30, "promotional_text.txt": 170,
         "description.txt": 4000, "keywords.txt": 100, "release_notes.txt": 4000}
PLAY = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000,
        "changelogs/default.txt": 500}

failed = False

def check(path: pathlib.Path, limit: int):
    global failed
    text = path.read_text(encoding="utf-8").rstrip("\n")
    n = len(text)
    note = ""
    if path.name == "keywords.txt":
        if ", " in text or " ," in text:
            note = "  spaces around commas waste characters"
            failed = True
        words = [w.lower() for w in text.split(",")]
        if len(set(words)) != len(words):
            note += "  repeated keyword"
            failed = True
    ok = n <= limit
    failed |= not ok
    print(f"{'ok ' if ok else 'OVER'} {n:5d} / {limit:<5d} {path.relative_to(root)}{note}")

for locale in ("en-GB", "en-US"):
    for name, limit in APPLE.items():
        check(root / locale / name, limit)
for name, limit in PLAY.items():
    check(root / "android" / "en-GB" / name, limit)
sys.exit(1 if failed else 0)
