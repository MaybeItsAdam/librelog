#!/usr/bin/env python3
"""Gathers diary history from an Obsidian vault into one zip that Librelog
imports (Settings → Import & export → Import…). Standard library only.

    scripts/obsidian-to-librelog.py [--vault ~/Documents/Life] [--out file.zip]
                                    [--folders Log Journal] [--skip Entities Places]
                                    [--simplenote export.zip ...] [--dry-run]

Each note becomes a Markdown file with front matter the core's Markdown
importer reads (date, time, title, tags). A note's date comes from, in order:

  1. `date:` in its front matter (event notes: `2026-08-16T22:36`)
  2. a YYYY-MM-DD in its file name (daily notes)
  3. a month and year in its title ("Exeter Trip Mid May 2025")
  4. when the note was created in Simplenote, if it came from there
     (--simplenote takes the export zips or their source/notes.json)
  5. the file's creation time

Notes kept as a run of dated headings (`## 15/12`, `## 21/6/24 - The End`)
are split into one entry per heading, the year taken from the heading or the
note's title ("Winter 25", "Summer '24").

Simplenote notes tagged Journal, MOSTLL or Memoirs that never made it into
the vault are added too.

File names inside the zip are stable, so importing a newer zip again only adds
what's new: the core skips anything it has imported before.
"""

import argparse
import datetime as dt
import io
import json
import os
import re
import sys
import zipfile
from pathlib import Path

MONTHS = {m: i for i, m in enumerate(
    ["january", "february", "march", "april", "may", "june", "july",
     "august", "september", "october", "november", "december"], 1)}
MONTHS.update({k[:3]: v for k, v in list(MONTHS.items())})

DAY_HEADING = re.compile(r"^#{1,3}\s+(\d{1,2})/(\d{1,2})(?:/(\d{2}|\d{4}))?\b\s*[-–—:]?\s*(.*)$")
ISO_DATE = re.compile(r"(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?")


def front_matter(src):
    """Returns (fields, body) for `---\\nkey: value\\n---\\nbody`."""
    if not src.startswith("---\n"):
        return {}, src
    end = src.find("\n---", 4)
    if end < 0:
        return {}, src
    fields = {}
    for line in src[4:end].splitlines():
        if ":" in line and not line.startswith((" ", "-")):
            k, v = line.split(":", 1)
            fields[k.strip().lower()] = v.strip().strip("\"'")
    return fields, src[end + 4:].lstrip("\n")


def clean(body):
    """Obsidian-only syntax the importer would show literally."""
    body = re.sub(r"%%.*?%%", "", body, flags=re.S)          # comments
    body = re.sub(r"^\s*!\[\[[^\]]*\]\]\s*$", "", body, flags=re.M)  # embeds on their own line
    body = re.sub(r"!\[\[[^\]]*\]\]", "", body)
    body = re.sub(r"^> \[!\w+\][+-]?\s*", "> ", body, flags=re.M)    # callout markers
    return re.sub(r"\n{3,}", "\n\n", body).strip()


def year_hint(title):
    """'Winter 25' → 2025, "Summer '24" → 2024, 'Trip May 2025' → 2025."""
    m = re.search(r"\b(19|20)(\d{2})\b", title)
    if m:
        return int(m.group(1) + m.group(2))
    m = re.search(r"(?:'|\b)(\d{2})\b", title)
    return 2000 + int(m.group(1)) if m else None


def title_date(title, fallback_year):
    """A date in a title: 'Exeter Trip Mid May 2025' → 2025-05-15, '10th
    July' → that day in the year the note was made. Reviews and audits are
    written after the period they name, so they keep their creation date."""
    if re.search(r"review|audit", title, re.I):
        return None
    m = re.search(r"\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+)\b", title)
    if m and m.group(2).lower() in MONTHS:
        try:
            return dt.date(fallback_year, MONTHS[m.group(2).lower()], int(m.group(1)))
        except ValueError:
            pass
    m = re.search(r"\b(?:(mid|early|late)\s+)?([A-Za-z]+)\s+((?:19|20)\d{2})\b", title, re.I)
    if not m or m.group(2).lower() not in MONTHS:
        return None
    day = {"mid": 15, "late": 25}.get((m.group(1) or "").lower(), 1)
    return dt.date(int(m.group(3)), MONTHS[m.group(2).lower()], day)


def title_range(title):
    """'Aug 26-27 2026' / '26-29 August 2026' → (first day, last day)."""
    for pat, order in ((r"\b([A-Za-z]+)\s+(\d{1,2})\s*[-–]\s*(\d{1,2}),?\s+((?:19|20)\d{2})\b", "mdd"),
                       (r"\b(\d{1,2})\s*[-–]\s*(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+),?\s+((?:19|20)\d{2})\b", "ddm")):
        m = re.search(pat, title)
        if not m:
            continue
        mon, a, b = (m.group(1), m.group(2), m.group(3)) if order == "mdd" else (m.group(3), m.group(1), m.group(2))
        if mon.lower() not in MONTHS:
            continue
        try:
            y, mo = int(m.group(4)), MONTHS[mon.lower()]
            first, last = dt.date(y, mo, int(a)), dt.date(y, mo, int(b))
        except ValueError:
            continue
        if last > first:
            return first, last
    return None


def created(path):
    st = path.stat()
    return dt.datetime.fromtimestamp(getattr(st, "st_birthtime", st.st_mtime))


SIMPLENOTE_DIARY_TAGS = {"journal", "mostll", "memoirs"}


def norm_title(s):
    return re.sub(r"\W+", " ", s).strip().lower()


def simplenote_notes(paths):
    """Active notes from Simplenote exports as {normalised title: [(title,
    created, tags, body), ...]}, one version per export that differs."""
    notes = {}
    for p in paths:
        p = Path(p).expanduser()
        if p.suffix == ".zip":
            with zipfile.ZipFile(p) as z:
                data = json.loads(z.read("source/notes.json"))
        else:
            data = json.loads(p.read_text())
        for n in data.get("activeNotes", []):
            text = n["content"].replace("\r\n", "\n").replace("\r", "\n")
            first, _, body = text.partition("\n")
            title = first.lstrip("# ").strip()
            created = dt.datetime.fromisoformat(n["creationDate"].replace("Z", "+00:00")).astimezone()
            key = norm_title(title)
            version = (title, created, [t.lower() for t in n.get("tags") or []], body.strip())
            if all(v[3] != version[3] for v in notes.get(key, [])):
                notes.setdefault(key, []).append(version)
    return notes


def lines_of(e):
    """An entry's wording as a set of normalised lines, for spotting copies."""
    text = (e["title"] or "") + "\n" + e["body"]
    return {norm_title(l) for l in text.splitlines() if norm_title(l)}


def split_days(title, body):
    """Splits a note made of `## d/m` sections into (date, heading, text)
    triples, or returns None if it isn't one."""
    year = year_hint(title)
    lines = body.splitlines()
    heads = [(i, DAY_HEADING.match(l)) for i, l in enumerate(lines)]
    heads = [(i, m) for i, m in heads if m]
    if len(heads) < 2:
        return None
    out, prev = [], None
    for n, (i, m) in enumerate(heads):
        d, mo, y = int(m.group(1)), int(m.group(2)), m.group(3)
        if y:
            yr = int(y) + (2000 if len(y) == 2 else 0)
        elif year:
            yr = year
            if prev and prev.month >= 11 and mo <= 2:
                yr = prev.year + 1  # Winter 25 running into January
            year = yr
        else:
            return None
        try:
            date = dt.date(yr, mo, d)
        except ValueError:
            return None
        end = heads[n + 1][0] if n + 1 < len(heads) else len(lines)
        start_text = lines[i + 1:end]
        if n == 0:
            start_text = lines[:i] + start_text  # anything above the first day
        out.append((date, m.group(4).strip(), "\n".join(start_text).strip()))
        prev = date
    return out


def tag_for(rel):
    top = rel.parts[0]
    if top == "Log":
        if rel.parts[1] == "Events":
            return "Event"
        if rel.parts[1] == "Reviews" or "review" in rel.stem.lower():
            return "Review"
        return "Daily" if ISO_DATE.search(rel.stem) else "Note"
    return "Journal" if len(rel.parts) == 2 else rel.parts[1]


def yaml(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def entry_file(date, time, title, tags, body, end=None):
    fm = [f"date: {date.isoformat()}"]
    if end:
        fm.append(f"end_date: {end.isoformat()}")
    if time:
        fm.append(f'time: "{time}"')
    if title:
        fm.append(f"title: {yaml(title)}")
    if tags:
        fm.append("tags: [" + ", ".join(t.replace(",", " ") for t in tags) + "]")
    return "---\n" + "\n".join(fm) + "\n---\n\n" + body + "\n"


def gather(vault, folders, skip, simplenote, day_starts=5):
    entries, seen_titles = [], set()
    for folder in folders:
        for path in sorted((vault / folder).rglob("*.md")):
            rel = path.relative_to(vault)
            if any(p.startswith(".") for p in rel.parts) or any(s in rel.parts for s in skip):
                continue
            src = path.read_text(encoding="utf-8-sig", errors="replace").replace("\r\n", "\n")
            fields, body = front_matter(src)
            body = clean(body)
            if not body:
                continue
            title = path.stem
            seen_titles.add(norm_title(title))
            tag = tag_for(rel)

            days = split_days(title, body)
            if days:
                for date, heading, text in days:
                    if text or heading:
                        entries.append(dict(rel=rel, date=date, time=None,
                                            title=heading or None, tags=[tag, title], body=text, how="heading"))
                continue

            time, how, end = None, "front matter", None
            span = title_range(title)
            m = ISO_DATE.search(fields.get("end_date", "") or fields.get("end", ""))
            if m:
                end = dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
            m = ISO_DATE.search(fields.get("date", ""))
            if not m:
                m, how = ISO_DATE.search(path.stem), "file name"
            if m:
                date = dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
                time = f"{m.group(4)}:{m.group(5)}" if m.group(4) else None
                is_daily = ISO_DATE.fullmatch(path.stem) is not None
            else:
                is_daily = False
                c = created(path)
                date, how = title_date(title, c.year), "title"
                sn = simplenote.get(norm_title(title))
                if not date and sn:
                    first = min(v[1] for v in sn)
                    date, how, time = first.date(), "Simplenote", first.strftime("%H:%M")
                if not date:
                    date, how = c.date(), "created"
                    time = c.strftime("%H:%M")
            if span:
                # A range in the title beats a date written down afterwards.
                (date, end), time, how = span, None, "title range"
            entries.append(dict(rel=rel, date=date, time=time, title=None if is_daily else title,
                                tags=[tag], body=body, how=how, end=end if end and end > date else None))
    # The same note kept in two folders (a review in Journal and in
    # Log/Reviews): keep one copy unless the other has wording of its own.
    by_name, kept = {}, []
    for e in sorted(entries, key=lambda e: -len(e["body"])):
        key = (e["rel"].name, e["date"], e["title"])
        have = by_name.setdefault(key, set())
        ls = lines_of(e)
        if have and ls <= have:
            continue
        have |= ls
        kept.append(e)
    entries = kept

    # Simplenote versions of diary notes: keep any note, or any day of a
    # dated-heading note, with a line that isn't anywhere in the vault.
    vault_lines = set().union(*(lines_of(e) for e in entries)) if entries else set()
    for key, versions in simplenote.items():
        for title, when, tags, body in versions:
            if not SIMPLENOTE_DIARY_TAGS & set(tags) or not body:
                continue
            days = split_days(title, body)
            parts = ([dict(date=d, time=None, title=h or None, body=t) for d, h, t in days if t or h] if days else
                     [dict(date=when.date(), time=when.strftime("%H:%M"), title=title, body=body)])
            for part in parts:
                e = dict(rel=Path("Simplenote") / title, tags=["Simplenote", title] if days else ["Simplenote"],
                         how="Simplenote (not in vault)", **part)
                ls = lines_of(e)
                if ls - vault_lines:
                    vault_lines |= ls
                    entries.append(e)
    # A note timed by a clock (front matter, file creation, Simplenote)
    # between midnight and the day's start was written the night before:
    # it belongs to that day. It keeps the time it was written.
    for e in entries:
        if e["time"] and e["how"] != "heading" and int(e["time"][:2]) < day_starts:
            e["date"] -= dt.timedelta(days=1)
            if e.get("end") and e["end"] <= e["date"]:
                e["end"] = None
            e["how"] += f" (after midnight, filed under the day before)"

    # Notes copied into the vault together all share one creation day, which
    # says nothing about when they were written: tag them so they're easy to
    # find and re-date in the app.
    per_day = {}
    for e in entries:
        if e["how"].startswith("created"):
            per_day[e["date"]] = per_day.get(e["date"], 0) + 1
    for e in entries:
        if e["how"].startswith("created") and per_day[e["date"]] >= 5:
            e["tags"].append("Undated")
            e["time"] = None
            e["how"] = "created (bulk copy, tagged Undated)"
    return entries


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--vault", type=Path, default=Path.home() / "Documents" / "Life")
    ap.add_argument("--folders", nargs="+", default=["Log", "Journal"])
    ap.add_argument("--skip", nargs="*", default=["Entities", "Templates"],
                    help="folder names to leave out (reference pages, not diary)")
    ap.add_argument("--out", type=Path,
                    default=Path.home() / "Downloads" / f"Librelog import {dt.date.today().isoformat()}.zip")
    ap.add_argument("--simplenote", nargs="*", default=[],
                    help="Simplenote export zips (or notes.json) to date notes by and fill gaps from")
    ap.add_argument("--day-starts", type=int, default=5, metavar="HOUR",
                    help="notes timed before this hour belong to the day before (default 5)")
    ap.add_argument("--dry-run", action="store_true", help="list what would be written")
    a = ap.parse_args()

    entries = gather(a.vault.expanduser(), a.folders, set(a.skip), simplenote_notes(a.simplenote), a.day_starts)
    if not entries:
        sys.exit("Nothing found to import.")
    entries.sort(key=lambda e: (e["date"], e["time"] or "", str(e["rel"])))

    names, buf = set(), io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for e in entries:
            # The name is what makes re-importing idempotent, so it only
            # carries the date for notes split into days, whose dates are fixed:
            # correcting a guessed date later mustn't make a new entry.
            label = f"{e['date']} {e['rel'].with_suffix('')}" if e["how"] == "heading" else str(e["rel"].with_suffix(""))
            stem = re.sub(r"[^\w' &-]+", " ", label).strip()
            name = f"{stem}.md"
            # Split notes: one file per day, named after the note and the day.
            n = 2
            while name in names:
                name, n = f"{stem} ({n}).md", n + 1
            names.add(name)
            z.writestr(name, entry_file(e["date"], e["time"], e["title"], e["tags"], e["body"], e.get("end")))

    by_how = {}
    for e in entries:
        by_how.setdefault(e["how"], []).append(e)
    print(f"{len(entries)} entries, {entries[0]['date']} – {entries[-1]['date']}")
    for how, es in sorted(by_how.items()):
        print(f"  {len(es):4}  dated by {how}")
    guessed = [e for e in entries if e["how"].split(" (")[0] not in ("front matter", "file name", "heading", "Simplenote", "title range")]
    if guessed:
        print("\nDates guessed from the title or file creation time (fix in the app if wrong):")
        for e in guessed:
            print(f"  {e['date']}  {e['rel']}")
    if a.dry_run:
        return
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_bytes(buf.getvalue())
    print(f"\nWrote {a.out}")


if __name__ == "__main__":
    main()
