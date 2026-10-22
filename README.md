# Librelog

A private, local-first journal for iPhone, iPad, Mac, Android and Windows.
Everything works offline and for free; the only paid part is optional
end-to-end encrypted text sync. [SPEC.md](SPEC.md) is the full design.

| Path | What |
| --- | --- |
| `core/` | Rust: database, search, merge, encryption. Shared by every app |
| `apple/` | SwiftUI app for iPhone, iPad and Mac |
| `android/` | Kotlin / Compose app |
| `windows/` | WinUI 3 app, plus a UI-free shared layer with tests |
| `supabase/` | Sync backend: schema, functions, tests ([README](supabase/README.md)) |
| `scripts/` | Build the core for each platform |

App id everywhere: `uk.co.maybeitssoftware.librelog`.

## Build and run

```bash
# Core
cd core && cargo test

# iPhone / iPad / Mac
scripts/build-apple.sh
cd apple && xcodegen && open Librelog.xcodeproj
# Run on a device from Xcode while signed in to your Apple account; automatic
# signing registers the app id and Sign in with Apple on first run.

# Android
scripts/build-android.sh
cd android && ./gradlew test installDebug

# Windows (shared layer and tests run on any OS)
cargo build --release --features capi --target-dir core/target/capi --manifest-path core/Cargo.toml
dotnet test windows/tests/Librelog.Shared.Tests
# The WinUI app builds on Windows only: scripts/build-windows.ps1, or the
# `windows` GitHub workflow, which uploads x64 and ARM64 packages per run.

# Sync backend
supabase/tests/run.sh          # local, needs Homebrew postgresql@17
supabase db push               # apply migrations to the linked project
```

## Sync access

Sync is a yearly subscription bought in the iPhone, Mac or Android app
(Settings → Sync → Subscription); [docs/billing.md](docs/billing.md) has
the store and server setup. It can also be granted by hand in the Supabase
SQL editor (the user must exist; sign in once first):

```sql
select librelog.admin_set_sync_until('someone@example.com', now() + interval '1 year');
```

### App review account

Reviewers can't receive a sign-in code, so Settings → Sync also offers "Use a
password instead". It works for any account with a password, but the apps
never set one, so in practice that's accounts given one by an admin (and
email confirmation is on, so nobody can sign up someone else's address with
a password of their own). `scripts/review-account.sh` makes the one for App Review
(`appreview@maybeitssoftware.co.uk`, confirmed, sync on) and prints its
password. Sign in to it once on any device to create its journal key, then put
the email, password and recovery phrase in App Store Connect (App Review
Information) and Play Console (App content → App access).

## Bringing in an Obsidian vault

Settings → Import & export reads Librelog backups, Day One, Daylio and
Markdown directly. For an Obsidian vault, gather it into one zip first:

```sh
scripts/obsidian-to-librelog.py --vault ~/Documents/Life   # writes ~/Downloads/Librelog import <date>.zip
scripts/obsidian-to-librelog.py --dry-run                  # just list the guessed dates
scripts/obsidian-to-librelog.py --simplenote export.zip    # date notes by when they were made in Simplenote
```

Daily notes are dated by file name, event notes by their `date:` front matter,
and notes kept as `## 15/12` sections are split into one entry per day. Notes
with no date anywhere are tagged `Undated`. Running it again later and
importing the new zip only adds what's new.

To try an import on a scratch database: `cargo run --example import --
/tmp/test.sqlite <file>` in `core/`.

Photos kept on a day: [docs/photos.md](docs/photos.md). Theme, accent, font
and text size: [docs/appearance.md](docs/appearance.md). Password-locked notes
on a day: [docs/locked-notes.md](docs/locked-notes.md).

## Not built yet

Payments (Stripe, store subscriptions), branded sign-in emails (needs SMTP),
and a signed Windows package for distribution (CI packages are test-signed).
Sign in with Google is built on every platform but needs its Google Cloud
clients and Supabase provider set up first: [docs/google-sign-in.md](docs/google-sign-in.md).

## Releasing to internal testing

Fastlane lanes in `fastlane/Fastfile`; credentials come from the environment
(the shared maybeitssoftware set: `ASC_KEY_ID`, `ASC_ISSUER_ID`,
`ASC_KEY_CONTENT`, `MATCH_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`) and
`android/key.properties` (the shared upload key, git-ignored).

```sh
# Play internal track, as a draft. The code is the UTC date and a two-digit
# number above any upload that day, Actions runs included. Local uploads take
# 90 and up so they stay clear of the workflow's 01, 02, …; after one, give
# any later Actions run that day an explicit version_code above it.
bundle exec fastlane android internal version_code:$(date -u +%Y%m%d)90 version_name:0.1.1
bundle exec fastlane ios internal   # TestFlight (next build number)
```

Or from GitHub: Actions → **Android · Play internal** → Run workflow, with a
versionName. Leave versionCode blank and it uses the same scheme, numbering
that day's runs 01, 02, …; Android version codes must always go up, and the
ones on Play already are date-based (the last was 2026102002), so never use a
small number like `2`. It uploads a draft; roll it out to testers in Play
Console. Choosing `completed` instead fails until the app has been published
once. It needs `PLAY_STORE_SERVICE_ACCOUNT_JSON`, the same Play key as the
Takt repo.

## Open source and hosted sync

LibreLog is free and open source: every app, the Rust core and the sync
backend are in this repository under the AGPL, and every feature works
offline without an account. The one thing that costs money is the **hosted**
sync service, priced each year at what it costs to run (SPEC.md §15) — you
pay for the servers, not for the software.

You don't have to use it. The backend is a set of Supabase migrations in
`supabase/`, and anyone can run their own copy and sync through it for free:
[docs/self-hosting.md](docs/self-hosting.md) covers setting it up and
building the apps to point at it. Journals are end-to-end encrypted either
way, so the server only ever holds ciphertext.

## Licence

Copyright © 2026 MaybeItsSoftware.

LibreLog is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version. It is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

If you run a modified version — including a modified sync backend — for other
people, the AGPL asks you to offer them its source. Third-party components
and their licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md);
[CONTRIBUTING.md](CONTRIBUTING.md) explains how to contribute.
