# Contributing to LibreLog

Thanks for helping. [SPEC.md](SPEC.md) is the design; read the section you're
touching before changing behaviour, and update it if the behaviour changes.

## Build and test

Run the checks for every part you touch:

```bash
# Rust core — shared by every app
cd core && cargo test

# iPhone / iPad / Mac
scripts/build-apple.sh
cd apple && xcodegen && open Librelog.xcodeproj

# Android
scripts/build-android.sh
cd android && ./gradlew test installDebug

# Windows shared layer (any OS); the WinUI app itself builds on Windows only
cargo build --release --features capi --target-dir core/target/capi --manifest-path core/Cargo.toml
dotnet test windows/tests/Librelog.Shared.Tests

# Sync backend
supabase/tests/run.sh          # needs Homebrew postgresql@17
```

A change to the core usually needs its counterpart in each app, so the
platforms stay in step. Performance budgets (SPEC.md §17) are release-blocking.

## Commits

One coherent change per commit. The subject is a short imperative sentence
saying what changed for the user, capitalised, without a trailing full stop —
`Show multi-day entries on Android`, `File after-midnight notes under the day
before`. Where it helps, a body explains why. `git log` is the reference.

## Licence

LibreLog is licensed under the [GNU Affero General Public License v3.0 or
later](LICENSE). By submitting a contribution you agree that it is licensed
under the same terms (inbound = outbound). There is no contributor licence
agreement, and you keep the copyright in your work.

Dependencies must be compatible with AGPL-3.0 (MIT, BSD, Apache-2.0,
MPL-2.0, LGPL, GPL-3.0 and the like; not GPL-2.0-only). Raise any proprietary
dependency in an issue first — the few already used are listed, with the
reasons, in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). When you add or
remove a dependency, update that file. Bundled assets such as
fonts must ship with their licence text.
