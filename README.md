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

Until checkout exists, grant sync by hand in the Supabase SQL editor (the
user must exist; sign in once first):

```sql
select librelog.admin_set_sync_until('someone@example.com', now() + interval '1 year');
```

## Not built yet

Payments (Stripe, store subscriptions), branded sign-in emails (needs SMTP),
Google sign-in on Android (needs an OAuth client id), and a signed Windows
package for distribution (CI packages are test-signed).
