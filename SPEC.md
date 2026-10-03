# Dailylog — Product & Technical Specification

> A private, local-first journal for phone, tablet and desktop, modelled on the
> feature set of Diarium (diariumapp.com). This is a functional clone spec: it
> reproduces *capabilities*, not Diarium's name, branding, copy, icons or
> assets. Every user-facing string and visual is our own.

Status: draft v3 · 2026-10-03

**v2 changes:** native apps over a shared Rust core for performance; the app
is completely free with no Pro tier; the only paid item is optional hosted
sync, sold yearly at what its hosting actually costs.
**v3:** text-only sync on Supabase, with no server of our own (§12.2, §12.5, §15).

---

## 1. Product summary

Dailylog is a journal you can open and write in within a minute — no account,
no login, no ads, no paywalled features. Entries live on the device and every
feature works offline, for free, forever.

The one thing that costs money is **hosted text sync**: end-to-end
encrypted sync of everything except media bytes, on Supabase, priced each
year at what the hosting actually costs (§15). The database only ever
stores ciphertext.

Each day can hold several timestamped entries with rich text, photos, video,
audio and files, a rating, tags, people, a place and custom tracker values.
Integrations pull in context automatically — the day's weather, photos taken,
calendar events, completed tasks, steps and sleep, music and TV — so a sparse
entry still becomes a full record of the day.

### Principles

1. **Performance first.** Native UI on every platform over a shared Rust core.
   Hard budgets in §17; a regression against them blocks a release.
2. **Zero-friction start.** First launch → writing in under 60 seconds. No
   sign-up, no onboarding wall.
3. **Everything free, hosting at cost.** No feature gates. Sync is priced
   from a published cost sheet, recalculated yearly.
4. **Zero-knowledge server.** End-to-end encryption is mandatory for sync, not
   optional. We cannot read journals, so a server breach exposes nothing
   readable.
5. **Leave anytime.** Full export in open formats with attachments.
6. **Calm, precise UI.** Follows the house design language (§14).

### Non-goals (v1)

- Social sharing feeds, public journals, shared journals.
- Server-side features that need plaintext (server search, AI, web reader).
- Third-party cloud sync (iCloud/Drive/Dropbox) — see §19.
- Syncing photos, video, audio and files (text-only sync — §12.2).

---

## 2. Platforms

| Platform | UI toolkit | Distribution |
|---|---|---|
| iOS / iPadOS | SwiftUI + UIKit (TextKit 2 editor) | App Store |
| macOS | SwiftUI + AppKit (native, not Catalyst) | Mac App Store + direct download |
| Android | Kotlin + Jetpack Compose (View-based editor) | Google Play |
| Windows | WinUI 3 / Windows App SDK, C# with NativeAOT | Microsoft Store + direct download |

Platform-only features are marked **[iOS]**, **[Android]**, **[Win]**, **[mac]**.

---

## 3. Architecture

### 3.1 Shared Rust core

Everything that isn't UI lives in one Rust crate, compiled per platform and
exposed through **UniFFI** (Swift and Kotlin bindings; C# via
`uniffi-bindgen-cs`, falling back to a hand-written C ABI if that binding
proves unreliable).

```
core/
  db/          rusqlite (bundled SQLite, WAL, FTS5), schema, forward-only migrations
  model/       entries, tags, trackers, people, places, templates, journals
  doc/         rich-text block model + serialisers (HTML, Markdown, DOCX, TXT, PDF)
  search/      query parser (tag:, rating:>=, before:…), FTS5 ranking, snippets
  stats/       aggregations, streaks, tracker correlations
  sync/        HLC, op log, merge, encryption, HTTP/WebSocket client
  crypto/      XChaCha20-Poly1305, Argon2id, X25519, BLAKE3 (RustCrypto crates)
  media/       thumbnailing, EXIF/GPS extraction, content hashing
  integrations/ network integrations (weather, Last.fm, Trakt, GitHub, …)
  import/      Day One, Apple Journal, Journey, Daylio, Markdown, CSV
  export/      JSON, Markdown, HTML site, DOCX, PDF, backup archive
supabase/      migrations (schema, RLS, RPCs, pg_cron) + Edge Functions (§12.5)
```

OS-bound integrations (HealthKit, Health Connect, PhotoKit, EventKit, Apple
Reminders, MediaStore) are implemented natively per platform and hand
normalised data to the core.

### 3.2 Performance rules

- The UI thread never touches SQLite or the network. Core calls are async and
  return small, paged view models — no "load all entries".
- Lists (Timeline, Gallery) are virtualised and backed by keyset pagination
  on `(date, time, id)`.
- Thumbnails are generated once at import (256 px and 1024 px, HEIC/AVIF where
  supported, WebP otherwise) and cached on disk; full-size images decode only
  in the viewer.
- Stats are computed incrementally into summary tables on write, not by
  scanning entries on open.
- FTS index is updated in the same transaction as the entry write.
- App launch opens the DB and renders Today from one query; integrations
  load after first frame.

---

## 4. Domain model

All ids are UUIDv7. Every syncable row carries `created_at`, `updated_at`,
`deleted_at` (tombstone) and `hlc` (hybrid logical clock).

```
Journal          id, name, color, icon, sort, is_default, is_locked

Entry            id, journal_id, date (local YYYY-MM-DD), time (HH:mm, nullable),
                 tz (IANA), title, body (block document, §6.2), body_text (plain,
                 for FTS), word_count, rating (0–5, half-steps optional),
                 place_id, template_id, is_favorite, weather_snapshot (JSON)

Attachment       id, entry_id, kind (photo|video|audio|file|drawing),
                 content_hash (keyed BLAKE3), mime, bytes, width, height,
                 duration_ms, taken_at, lat, lng, caption, sort,
                 thumbhash, origin_device_id, local_state (present|elsewhere)

Tag              id, name, color, group, is_tracker,
                 tracker_type (count|number|duration|scale|boolean|text),
                 unit, min, max, goal
EntryTag         entry_id, tag_id, value

Person           id, name, avatar_attachment_id, contact_ref
EntryPerson      entry_id, person_id

Place            id, name, address, lat, lng, radius_m, category

Template         id, name, journal_id, body, default_tags[], default_rating,
                 prompt_questions[], schedule (RRULE)

IntegrationCache id, provider, date, payload, fetched_at   — device-local, never synced
DayMeta          date, journal_id, kept_integration_items  — synced
Revision         id, entry_id, body, hlc, device_id, is_conflict
Setting          key, value, scope (device|synced)
```

**Multiple entries per day** is first-class: a "day" is all entries sharing
`date` + `journal_id`, ordered by `time`, shown as one scroll with timestamp
dividers.

---

## 5. Information architecture

Bottom tab bar on phones; sidebar on tablet/desktop.

1. **Today** (start screen)
2. **Calendar**
3. **Timeline**
4. **Explore** — Map · Gallery · Attachments · On This Day · Places · People
5. **Stats**
6. **Search** (also `⌘/Ctrl+K`)
7. **Settings**

Journal switcher at the top of the sidebar / Today header, including an
"All journals" view.

---

## 6. Screens

### 6.1 Today / start screen

- Large date header; swipe (or `←`/`→`) between days.
- Reorderable, hideable cards:
  - *Write* — the day's entries, or "Start writing" with a template picker.
  - *On this day* — same date in previous years.
  - *Rating & trackers* — quick-entry row (§8).
  - *Integrations* — weather, photos, events, tasks, health, music… (§11).
  - *Streak & word goal*.
  - *Prompt of the day* — user-editable rotating list.
- "+" button → new entry at current time.

### 6.2 Entry editor

- Full screen on phones, right pane on tablet/desktop. Autosave after 1 s idle
  and on blur; no Save button.
- **Header:** date & time (back-dating is normal), optional title, journal,
  rating, place, tags, people, weather (auto for today, historical for past).
- **Body:** headings H1–H3, bold, italic, underline, strikethrough, highlight,
  palette-limited text colour, bulleted / numbered / checklist, quote, code,
  rule, links (web and **internal links to other entries** via `[[`), inline
  images, tables (tablet/desktop).
- **Storage format:** a flat block list (`paragraph | heading | list_item |
  quote | code | image | rule | table`) with inline marks as ranges. Each
  platform's native text engine (TextKit 2, Android `Spannable`, WinUI
  `RichEditBox`) maps to and from it; the core owns the canonical form and all
  serialisers, so rendering is identical everywhere.
- **Markdown shortcuts** while typing (`# `, `- `, `1. `, `> `, `**x**`).
- **Attachments:** camera, library, drag-and-drop, paste; in-app audio recorder
  with waveform (unlimited length); any file type; sketches.
- **Dictation** via the platform speech API.
- Live word count in the footer.
- **Entry menu:** duplicate, move journal, change date, favourite, share (text,
  PDF, image), print **[desktop]**, version history, delete (Trash for 30 days).

### 6.3 Calendar

- Month grid with entry dots tinted by rating (or by tag / tracker).
- Week strip and year heat-map variants.
- Tap → day preview sheet; long-press/double-click → editor.

### 6.4 Timeline

- Reverse-chronological virtualised list: date eyebrow, time, title/first
  line, two-line excerpt, thumbnail, tags, rating.
- Filters: journal, tags (any/all), people, rating range, has-media,
  favourites, date range. Sticky month headers and a scrubber.

### 6.5 Map

- Clustered entries and geotagged photos; filter by date and tag.
- Apple: MapKit. Android & Windows: MapLibre with OpenFreeMap vector tiles
  (free, no API key). Self-host tiles only if that service becomes
  unreliable.

### 6.6 Gallery · 6.7 Attachments · 6.8 On This Day · 6.9 Places & People

- **Gallery:** every photo/video grouped by month; full-screen viewer.
- **Attachments:** audio/files/drawings in a sortable list; inline playback.
- **On This Day:** same calendar date across all prior years.
- **Places / People:** counts, last seen, related entries, merge duplicates.

### 6.10 Stats

- Totals: entries, words, days written, photos, current & longest streak.
- Charts: entries/words per month, average rating over time, rating
  distribution, tag frequency, top people/places, time-of-day histogram.
- Tracker charts with goal lines, plus simple correlations ("rating is 0.6
  higher on days tagged *Run*").

### 6.11 Search

- FTS5 over title, body, captions, places, people; diacritic-insensitive,
  prefix matching, highlighted snippets.
- Operators: `tag:run`, `person:anna`, `rating:>=4`, `in:work`,
  `before:2025-01-01`, `after:`, `has:photo|audio|file|location`, `"phrase"`,
  `-exclude`. Saved searches pin to the sidebar.

---

## 7. Tags & people

- Free-form tags created inline; autocomplete by frequency; optional colour
  and group.
- People have their own field, avatar and optional system-contact link;
  `@name` in the body adds the person.
- Bulk rename / merge / delete in Settings.

## 8. Rating & trackers

- **Rating:** 0–5 stars per entry; day rating = latest or average (setting);
  custom labels per value.
- **Trackers:** any tag can become a tracker — *count*, *number* with unit,
  *duration*, *scale* 1–10, *boolean*, *text*. Shown in the Today quick-entry
  row; optional goal and reminder.
- Integrations can fill trackers automatically (steps, sleep), marked as
  auto-filled and editable.

## 9. Templates

- Body, default tags, default rating, prompt questions; optional schedule
  (e.g. Weekly review on Sundays) and per-journal default.
- Built-ins: Daily reflection, Gratitude, Morning pages, Weekly review, Travel
  day, Dream log, Workout log.
- Variables: `{{date}}`, `{{weekday}}`, `{{weather}}`, `{{yesterday_link}}`.

## 10. Journals

Unlimited journals with name, colour, icon; each can be locked separately,
hidden from "All journals", or excluded from sync.

---

## 11. Integrations

Interface (core side; native integrations implement the same shape in
Swift/Kotlin/C# and pass results down):

```rust
trait Integration {
    fn id(&self) -> &'static str;
    fn platforms(&self) -> &[Platform];
    fn auth(&self) -> Auth; // None | OsPermission | OAuth | ApiKey | FileImport
    async fn fetch_day(&self, date: LocalDate, ctx: &Ctx) -> Result<Vec<Item>>;
    fn tracker_mappings(&self) -> &[(Metric, &'static str)];
}
```

Items show on the day card and in the editor sidebar. They are not saved into
the entry unless the user taps **Keep** / **Insert**, or enables "Keep
integration data automatically" (frozen at day end, since many APIs only
expose recent history).

| Integration | Data | Source |
|---|---|---|
| Photos of the day | photos/videos taken that date | PhotoKit, MediaStore, chosen folder on desktop |
| Calendar | events | EventKit, CalendarContract, Microsoft Graph, Google Calendar |
| Weather | current + historical | WeatherKit REST (Apple dev programme quota) or MET Norway for current; historical via self-hosted Open-Meteo or by freezing at entry time — §19 |
| Sun & moon | sunrise/sunset, moon phase | computed locally |
| Word count goal | words vs goal | local |
| Holidays & special days | holidays, birthdays, anniversaries | bundled holiday rules + contacts + user-defined |
| Health | steps, sleep, workouts, heart rate, weight, mindful minutes | HealthKit **[iOS]**, Health Connect **[Android]** |
| Location history | places visited | on-device significant-location tracking (opt-in); Google Timeline import |
| Tasks completed | tasks done | Microsoft To Do, Todoist, Apple Reminders, Google Tasks |
| Music | scrobbles | Last.fm |
| TV & films | watched | Trakt |
| Check-ins | beer check-ins | Untappd |
| Code activity | commits | GitHub |
| Social | own posts | Mastodon, Bluesky; others via data-export import |
| Working hours | hours worked | Toggl, Clockify, CSV |

Calls go **directly from the device** to each provider — never via our
servers. OAuth tokens live in the OS keychain per device and are never synced.

---

## 12. Storage, sync & security

### 12.1 Local storage

```
<app-data>/
  dailylog.sqlite        WAL mode
  media/<hash>           originals, content-addressed
  thumbs/<hash>-256|1024
  backups/               rolling local backups (7 daily, 4 weekly)
```

### 12.2 What syncs

**Text-only sync.** Everything except media bytes syncs: entries, rich-text
bodies, titles, ratings, tags, tracker values, people, places, templates,
journals, kept integration data, synced settings, and attachment
**metadata** (kind, size, dimensions, caption, date, location, and a
~25-byte ThumbHash for a blurred preview).

Photos, video, audio, files and drawings stay on the device they were added
on. Other devices show the attachment as a blurred ThumbHash tile labelled
"On iPhone" (the origin device's name). Media moves between devices only via
export/backup (§16). Media sync is a possible later add-on (§19).

Why: text is ≈ 1–5 MB per user per year even encrypted, so hosting cost
per user is effectively zero, and the backend can be a plain database with
no object storage and no server of our own.

### 12.3 Accounts (sync only)

- No account is needed to use the app. An account exists only to sync.
- Supabase Auth: email one-time code, plus Sign in with Apple and Google.
  (Apple's review rules require Sign in with Apple when other third-party
  sign-ins are offered.)
- The account gives access to ciphertext only; it never unlocks the journal.

### 12.4 End-to-end encryption

- On first sync setup the device generates a random 256-bit **journal key**
  and shows a 24-word **recovery phrase** once.
- **Adding a device:** sign in on the new device, which shows a QR code
  containing an ephemeral X25519 public key. An existing device scans it and
  writes the journal key, encrypted to that key, into a short-lived
  `pairings` row. Or type the recovery phrase instead.
- Every op and snapshot is encrypted with XChaCha20-Poly1305 under keys
  derived from the journal key, and padded to size buckets so lengths leak
  little.
- Lost every device *and* the recovery phrase = cloud copy unrecoverable.
  This is stated plainly during setup.

### 12.5 Sync backend (Supabase)

There is **no sync server of our own**. Clients talk directly to Supabase:
Auth for sign-in, Postgres (via PostgREST RPC) for the op log, Realtime
Broadcast for "new data" pings, Edge Functions for payment webhooks, and
`pg_cron` for housekeeping. The Rust core does no networking itself: it
produces sealed batches and snapshots, and merges what the apps hand back
(`outgoing_batch`, `push_succeeded`, `apply_incoming`, `snapshot`,
`apply_snapshot`). Each app makes the HTTPS calls with its platform's own
client (URLSession, OkHttp, HttpClient), which keeps TLS, proxies and
background transfers native and the core trivially testable.

**Schema** (own `dailylog` schema, exposed to the Data API):

```sql
create schema dailylog;

create table dailylog.accounts (
  user_id     uuid primary key references auth.users on delete cascade,
  last_seq    bigint not null default 0,
  bytes_used  bigint not null default 0,
  sync_until  timestamptz,               -- paid through; null = never paid
  key_check   bytea                      -- encrypted constant, verifies the journal key
);

create table dailylog.devices (
  id          uuid primary key,
  user_id     uuid not null references dailylog.accounts on delete cascade,
  name_enc    bytea not null,
  acked_seq   bigint not null default 0,
  last_seen   timestamptz not null default now()
);

create table dailylog.ops (
  user_id     uuid not null references dailylog.accounts on delete cascade,
  seq         bigint not null,
  device_id   uuid not null,
  payload     bytea not null,            -- encrypted batch of row changes
  primary key (user_id, seq)
);

create table dailylog.snapshots (
  user_id     uuid not null references dailylog.accounts on delete cascade,
  seq         bigint not null,           -- snapshot covers ops <= seq
  payload     bytea not null,
  primary key (user_id, seq)
);

create table dailylog.pairings (
  id          uuid primary key,
  user_id     uuid not null references dailylog.accounts on delete cascade,
  payload     bytea,                     -- journal key encrypted to the new device
  expires_at  timestamptz not null
);
```

RLS: every table allows `select` only where `user_id = auth.uid()`. There
are **no direct insert/update policies** on `ops`, `snapshots` or
`accounts` — writes go through `security definer` functions.

**Protocol:**

- Every local write produces an op `{table, id, fields, hlc, device_id}`.
  Ops are encrypted and pushed in batches (debounced 1 s while online) via
  `rpc/push_ops(device_id, batch_id, payloads[])`; a retried `batch_id` is
  a no-op. That function:
  1. checks `sync_until > now()` and the quota (default 500 MB);
  2. runs `update accounts set last_seq = last_seq + n … returning`, which
     row-locks the account so two devices pushing at once get **gap-free,
     correctly ordered** `seq` numbers (a global sequence would let a
     reader skip rows committed out of order);
  3. inserts the ops and calls `realtime.send()` on the private channel
     `user:<uid>` with `{seq}`.
- Online devices subscribed to that channel pull immediately: target ≤ 1 s
  edit-to-visible. Devices also pull on launch, on foreground, and on
  background fetch (iOS BGTaskScheduler, Android WorkManager). Silent push
  notifications are deferred until needed.
- Pull: `rpc/pull_ops(device_id, after, limit)` returns ops base64-encoded
  (PostgREST would otherwise hex-encode `bytea`, doubling the payload) and
  records `after` as the device's `acked_seq`. The full function list and
  error codes are in `supabase/README.md`.
- All merging happens on the client: per-field last-writer-wins by HLC.
  Concurrent edits of an entry's `body` keep the losing version as a
  conflict `Revision` with a non-blocking "Edited on two devices — review"
  banner and side-by-side diff.
- **Compaction:** past 5,000 ops a client uploads an encrypted snapshot via
  `rpc/put_snapshot`; a daily `pg_cron` job deletes ops at or below the
  newest snapshot once every device seen in the last 90 days has
  `acked_seq` past it. A new device loads the newest snapshot, then the ops
  after it.

**Edge Functions** (secrets in Supabase Edge Function secrets):

| Function | Trigger | Does |
|---|---|---|
| `stripe-webhook` | Stripe events | sets `sync_until` |
| `appstore-notifications` | App Store Server Notifications v2 | verifies JWS, sets `sync_until` |
| `play-rtdn` | Google Play real-time notifications (Pub/Sub push) | verifies via Play Developer API, sets `sync_until` |

**Housekeeping (`pg_cron`, daily):** compaction GC; delete expired
`pairings`; delete data for accounts lapsed > 90 days.

**Lifecycle:**

- Subscription lapses → pushes rejected, pulls still allowed for 30 days;
  data kept 90 days with reminders, then deleted. Local data is never
  affected.
- Account deletion is immediate and complete (`on delete cascade` from
  `auth.users`), leaving backups on their 7-day rotation.

**Project:** a dedicated `dailylog` project from day one, created on the
**Free plan** in a personal org while you're the only user ($0). Free
projects pause after a week with no API requests (daily syncing prevents
that) and have no automatic backups (acceptable: every device holds a full
copy). Before the first external users, transfer the project into the Pro
org so it gets daily backups and no pausing; transfer keeps the same
project, keys and URL, so the apps need no change. Migrations live in
`supabase/migrations/`.

### 12.6 Device security

- App lock: PIN/password plus Face ID / Touch ID / Android BiometricPrompt /
  Windows Hello; timeout immediate / 1 / 5 / 15 min; blurred app-switcher
  snapshot.
- Per-journal lock with a separate password.
- Optional SQLCipher full-database encryption at rest (on top of OS file
  protection).
- No content analytics. Opt-in anonymous crash reports only (self-hosted
  collector).

---

## 13. Reminders, notifications, widgets

- Daily reminder(s) at chosen times; skip if already written today.
- "On this day" notification (opt-in); per-tracker reminders; streak nudge
  (opt-in, off by default).
- **Widgets:** iOS/Android home & lock screen — *Write now*, *On this day*,
  *Streak*, *Tracker quick-log*; Windows Widgets board; macOS widget +
  menu-bar quick entry.
- **Quick capture:** App Intents / Shortcuts, Android share target and
  quick-settings tile, desktop global hotkey.
- **Share into app** for text, URLs, photos, files → appends to today.

---

## 14. Design & settings

House design language:

- **Surfaces:** paper `#faf8f4`, raised cards `#ffffff`, ink `#444054`;
  dark `#1c1a23` / `#25232f`. 1px hairlines, **no shadows**, no gradients on
  chrome.
- **Accents:** azure `#007fff` (actions, focus), emerald `#4cc38e`,
  raspberry `#d62246` (delete), amber `#ffbf00` (stars, warnings); purple /
  pink / orange only for categorical colour (journals, tags).
- **Radius:** 8px cards/sheets, 6px buttons/inputs/chips (no pill chips),
  9999px for avatars/dots only.
- **Type:** Arvo for display and journal body; Geist Mono for timestamps, word
  counts, stats; 10px uppercase micro-labels with 0.15em tracking.
- **Touch:** 44px targets, 16px mobile inputs, bottom sheets on phones,
  reduced motion honoured.
- Tokens are defined once (JSON) and generated into Swift, Kotlin and XAML
  resources at build time.

**Settings:** theme (light/dark/system), accent colour, editor font and size,
first day of week, date/time format, day boundary (e.g. before 4 am counts as
yesterday), units, language, start-screen card order, default journal and
template, rating style, integrations, sync & devices, security, reminders,
backup & export, subscription, about.

Localisation via Fluent (shared in the core); launch: en, de, fr, es, it, nl,
pt-BR, ja.

---

## 15. Pricing: everything free, sync at cost

### 15.1 What's free

Everything in this spec, on every platform, with no limits: unlimited
journals, entries, attachments, trackers, templates, all views, all
integrations, app lock, all import/export formats, local backups, widgets.

### 15.2 What's paid

**Hosted text sync**, billed yearly. The price is the running cost of the
service per user, recalculated once a year from a public cost sheet.

```
price_per_user = ( hosting_costs / paying_users ) + payment_fees + VAT
```

`hosting_costs` = the Supabase share, domain and transactional email.
Operator time and store developer fees (needed to ship the app at all, sync
or not) are not charged.

### 15.3 Hosting costs on Supabase (estimate — verify current prices)

Text sync is tiny: ≈ 1–5 MB of encrypted ops per user per year, and less
after compaction. As I understand the Pro plan ($25/month per organisation),
it includes 8 GB of database disk, 100k monthly active auth users, 250 GB
egress, and Realtime and Edge Function quotas far above this load. Its
compute credit covers one Micro project; each extra project adds ≈ $10/month.

| Setup | Marginal hosting cost | Covers |
|---|---|---|
| Schema in your existing project (now, solo) | **$0** | you + testers |
| Dedicated project (public launch) | ≈ $10/month ≈ $120/year | thousands of users before the 8 GB disk fills (then $0.125/GB-month) |
| Domain + transactional email | ≈ $2/month | |

- Per-user hosting share on a dedicated project: ≈ €1.25 at 100 users,
  €0.12 at 1,000, €0.01 at 10,000.
- Nothing grows with photos, because photos don't sync.

### 15.4 Price plan

With hosting near zero, card fees are the biggest real cost. Card processing
charges a fixed ≈ €0.25 per transaction, so a €1 charge loses ~25% to it.

| Plan | Web price | Fee share |
|---|---|---|
| Sync, 1 year | €1 | ~27% |
| Sync, 3 years | **€3** (recommended default) | ~10% |

App Store / Play prices sit at the lowest store tier that covers the 15% cut.

### 15.5 Payment channels

- **Web checkout (cheapest):** Stripe + Stripe Tax; you register for EU VAT
  OSS yourself. A merchant of record (Paddle, Lemon Squeezy) handles VAT for
  you but costs ~5% + €0.50, which would take most of a €1 sale.
- **App Store / Google Play:** in-app subscription at the lowest tier that
  covers the store's 15% cut, so the in-app price is higher than the web
  price. Where the rules allow it (US storefront, EU under the DMA), link to
  web checkout as well.
- **Microsoft Store:** non-game apps may use their own payments with no store
  commission → web checkout.
- Receipts and store notifications are verified by Supabase Edge Functions
  (§12.5); no third-party subscription SaaS.

### 15.6 Transparency

A public `/costs` page lists last year's actual invoices by category, the
number of paying users, and how the next year's price was derived.

---

## 16. Import & export

**Export** (any range / journal / tag): JSON (canonical, lossless,
documented schema), Markdown with YAML front-matter (Obsidian-compatible),
self-contained HTML site, DOCX, PDF book, plain text, and a full
`.dailylog-backup` (encrypted archive), with scheduled backups to any folder.

**Import:** Day One, Apple Journal, Journey, Daylio (rating + trackers),
Markdown folders with front-matter, Google Timeline, generic CSV with column
mapping, own JSON/backup. Idempotent (`import_ref`), dry-run summary first.

---

## 17. Performance budgets (release-blocking)

Reference devices: iPhone 12, Pixel 6a, M1 MacBook Air, Windows laptop with a
4-core 11th-gen i5.

| Metric | Budget |
|---|---|
| Cold start → editable Today | ≤ 400 ms (phones), ≤ 300 ms (desktop) |
| Warm start | ≤ 150 ms |
| Keystroke-to-glyph in a 20,000-word entry | 1 frame (≤ 8 ms at 120 Hz) |
| Open entry from Timeline | ≤ 50 ms |
| Timeline scroll | 0 dropped frames at 120 Hz with thumbnails |
| Full-text search, 10,000 entries | ≤ 30 ms to first results |
| Stats screen, 10 years of data | ≤ 100 ms |
| Sync: edit visible on second online device | ≤ 1 s |
| Initial sync, 10,000 entries (text) | ≤ 10 s on 50 Mbit |
| Memory, Today screen idle | ≤ 80 MB (phones) |
| App download size | ≤ 25 MB (phones) |
| `push_ops` / `pull_ops` database time | p99 ≤ 10 ms (network round-trip excluded) |

Enforced with CI benchmarks (criterion for the core, XCTest metrics, Android
Macrobenchmark, WinAppDriver timing) on every merge to main.

Other requirements: works fully offline; forward-only migrations with an
automatic pre-migration backup; VoiceOver/TalkBack/Narrator, Dynamic Type,
keyboard-only desktop use, WCAG AA contrast; ≥ 99.8% crash-free sessions;
property-based tests proving merge convergence across 3 simulated devices.

---

## 18. Milestones

| # | Scope | Exit criteria |
|---|---|---|
| M1 Core | Rust core (DB, model, block doc, search), UniFFI bindings, iOS app: Today, editor, Calendar, Timeline, Search, photos, tags, rating, app lock, JSON export | Daily-drivable on iPhone; §17 budgets met on iOS |
| M2 Android + macOS | Compose app, AppKit/SwiftUI Mac app at M1 parity | Budgets met on both |
| M3 Sync | Supabase schema + RPCs, E2E encryption, device pairing, conflict UI, ThumbHash placeholders, web checkout + Edge Functions | 3-device convergence soak test passes 7 days |
| M4 Windows | WinUI 3 app at parity | Budgets met |
| M5 Richness | Trackers, people, places, map, gallery, audio, templates, multiple journals, stats | |
| M6 Integrations & launch | All integrations, importers, export formats, widgets, reminders, store subscriptions, localisation, `/costs` page | Store review passed on all four stores |

---

## 19. Open decisions

1. **Free or €1?** Hosting is ≈ $0 while solo and ≈ $120/year on a
   dedicated project, so a €1/year (€3 per 3 years) price mostly pays card
   fees. *Recommendation:* keep the €3 / 3-year plan. It covers the dedicated
   project from ~40 subscribers and keeps sync a deliberate choice, which
   limits abuse of free accounts.
2. **Bring-your-own cloud sync** (iCloud/Drive/Dropbox/WebDAV) for free?
   It costs nothing to host but adds five provider adapters and support load.
   *Recommendation:* not in v1. Free encrypted backup-to-folder covers people
   who refuse a subscription.
3. **Historical weather.** The free Open-Meteo API is non-commercial only, and
   this service is commercial. *Recommendation:* freeze weather at entry time
   (current-conditions APIs are free), and add self-hosted Open-Meteo for past
   dates later only if users ask for it.
4. **Windows UI.** WinUI 3 + NativeAOT (recommended) vs a Rust-native UI
   (Slint/egui — faster to start up but less native).
5. **Media sync add-on (later).** If users ask for photos on every device:
   Supabase Storage with the same E2E encryption, priced per GB on top of
   text sync. *Recommendation:* ship text-only, and add it only on demand.
6. **When to leave the shared project.** *Recommendation:* create the
   dedicated `dailylog` project before the first external beta, so its
   sign-in emails and user pool are its own from day one.
7. **Product name & icon:** "Dailylog" is a working title; check trademark
   and store availability before M6.
