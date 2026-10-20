# Third-party notices

LibreLog is licensed under the GNU Affero General Public License, version 3
or (at your option) any later version — see [LICENSE](LICENSE). It is built
on the work of others, listed here with their licences. Everything below is
compatible with AGPL-3.0-or-later, except the proprietary Google and
Microsoft components called out in their own sections, which are discussed
under [Proprietary components](#proprietary-components).

Audited 2026-10-20. Re-run the commands under each heading when dependencies
change.

## Fonts

Bundled in every app. Both are under the SIL Open Font License 1.1, which
lets them be bundled with software under any licence provided the licence
text travels with them; each app ships it beside the font files.

| Font | Copyright | Licence | Licence text shipped at |
| --- | --- | --- | --- |
| Arvo | © 2010–2013 Anton Koovit, Reserved Font Name "Arvo" | OFL-1.1 | `apple/Fonts/Arvo-OFL.txt`, `android/app/src/main/assets/licenses/Arvo-OFL.txt`, `windows/src/Librelog.App/Assets/Fonts/Arvo-OFL.txt` |
| Geist Mono | © 2024 The Geist Project Authors | OFL-1.1 | `apple/Fonts/GeistMono-OFL.txt`, `android/app/src/main/assets/licenses/GeistMono-OFL.txt`, `windows/src/Librelog.App/Assets/Fonts/GeistMono-OFL.txt` |

The OFL forbids selling the fonts on their own and using a Reserved Font
Name for a modified font; neither applies to shipping them inside the app.

## Brand marks

The Google "G" on the Sign in with Google buttons is Google's trademark,
used as Google's [Sign-In branding
guidelines](https://developers.google.com/identity/branding-guidelines)
allow; it is not covered by LibreLog's licence. It is taken from the
guidelines' downloadable kit (`signin-assets.zip`) and converted, outline,
colours and highlights unchanged, into each platform's vector format by
`scripts/google-g-logo.py`: `apple/App/Assets.xcassets/GoogleG.imageset/`,
`android/app/src/main/res/drawable/ic_google_g.xml` and
`windows/src/Librelog.App/Assets/GoogleG.svg`. Sign in with Apple uses
Apple's own system button, so no Apple mark is bundled.

## Rust core (`core/`)

Statically linked into every app. Normal dependencies across all targets,
from `cargo tree -e normal --target all --prefix none -f '{p}|{l}'` in
`core/`; versions are those pinned by `core/Cargo.lock`.

| Licence (SPDX) | Count | Crates |
| --- | --- | --- |
| MIT OR Apache-2.0 | 91 | aead 0.6.1, anyhow 1.0.104, argon2 0.5.3, arrayvec 0.7.8, base64 0.23.1, base64ct 1.8.3, bitflags 2.13.2, blake2 0.10.6, block-buffer 0.10.4, block-buffer 0.12.1, bumpalo 3.20.3, camino 1.2.6, cargo-platform 0.3.3, cfg-if 1.0.5, chacha20 0.10.2, chacha20poly1305 0.11.0, cipher 0.5.2, cmov 0.5.4, cpufeatures 0.2.17, cpufeatures 0.3.1, crc32fast 1.5.2, crypto-common 0.1.7, crypto-common 0.2.2, ctutils 0.4.2, curve25519-dalek-derive 0.1.1, deranged 0.5.8, digest 0.10.7, equivalent 1.0.2, errno 0.3.14, fallible-iterator 0.3.0, fallible-streaming-iterator 0.1.9, fastrand 2.5.0, flate2 1.1.10, fs-err 3.3.1, getrandom 0.3.4, getrandom 0.4.3, hashbrown 0.16.1, hashbrown 0.17.1, hashlink 0.12.2, heck 0.5.0, hybrid-array 0.4.15, indexmap 2.14.2, inout 0.2.2, itoa 1.0.18, js-sys 0.3.106, libc 0.2.190, log 0.4.34, num-conv 0.2.2, once_cell 1.21.4, password-hash 0.5.0, poly1305 0.9.1, portable-atomic 1.15.0, portable-atomic-util 0.2.8, powerfmt 0.2.0, proc-macro2 1.0.107, quote 1.0.47, rand_core 0.10.1, rand_core 0.6.4, semver 1.0.28, serde 1.0.229, serde_core 1.0.229, serde_derive 1.0.229, serde_json 1.0.151, serde_spanned 1.1.1, siphasher 1.0.4, smallvec 1.16.2, static_assertions 1.1.0, syn 2.0.119, syn 3.0.6, tempfile 3.27.0, thiserror 2.0.21, thiserror-impl 2.0.21, time 0.3.55, time-core 0.1.9, time-macros 0.2.32, toml 1.1.6+spec-1.1.0, toml_datetime 1.1.1+spec-1.1.0, toml_parser 1.1.3+spec-1.1.0, toml_writer 1.1.2+spec-1.1.0, typed-path 0.12.3, typenum 1.20.1, unicode-normalization 0.1.25, universal-hash 0.6.1, uuid 1.27.0, wasm-bindgen 0.2.129, wasm-bindgen-macro 0.2.129, wasm-bindgen-macro-support 0.2.129, wasm-bindgen-shared 0.2.129, windows-link 0.2.1, windows-sys 0.61.2, zeroize 1.9.0 |
| MIT | 12 | bytes 1.12.1, cargo_metadata 0.23.1, generic-array 0.14.7, libsqlite3-sys 0.38.2, rsqlite-vfs 0.1.1, rusqlite 0.40.2, simd-adler32 0.3.10, sqlite-wasm-rs 0.5.5, thumbhash 0.1.0, winnow 1.0.4, zip 8.6.0, zmij 1.0.23 |
| MIT OR Unlicense | 8 | csv 1.4.0, csv-core 0.1.13, jiff 0.2.37, jiff-core 0.1.1, jiff-static 0.2.37, jiff-tzdb 0.1.8, jiff-tzdb-platform 0.1.3, memchr 2.8.3 |
| MPL-2.0 | 6 | uniffi 0.32.2, uniffi_core 0.32.2, uniffi_internal_macros 0.32.2, uniffi_macros 0.32.2, uniffi_meta 0.32.2, uniffi_pipeline 0.32.2 |
| Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | 4 | linux-raw-sys 0.12.1, rustix 1.1.5, wasip2 1.0.4+wasi-0.2.12, wit-bindgen 0.57.1 |
| BSD-3-Clause | 3 | curve25519-dalek 5.0.0, subtle 2.6.1, x25519-dalek 3.0.0 |
| CC0-1.0 | 3 | bip39 3.0.0, bitcoin_hashes 0.14.101, hex-conservative 0.2.3 |
| MIT OR Apache-2.0 OR LGPL-2.1-or-later | 2 | r-efi 5.3.0, r-efi 6.0.0 |
| Zlib | 2 | foldhash 0.2.0, zlib-rs 0.6.8 |
| (MIT OR Apache-2.0) AND Unicode-3.0 | 1 | unicode-ident 1.0.26 |
| Apache-2.0 | 1 | zopfli 0.8.3 |
| Apache-2.0 OR BSL-1.0 | 1 | ryu 1.0.23 |
| Apache-2.0 OR CC0-1.0 OR MIT-0 | 1 | constant_time_eq 0.4.2 |
| CC0-1.0 OR Apache-2.0 OR Apache-2.0 WITH LLVM-exception | 1 | blake3 1.8.7 |
| MIT OR Apache-2.0 OR BSD-1-Clause | 1 | fiat-crypto 0.3.0 |
| MIT OR Apache-2.0 OR Zlib | 1 | tinyvec 1.13.3 |

Where a crate offers a choice (`OR`), LibreLog takes a GPL-compatible
option — MIT or Apache-2.0 — so the LGPL option of `r-efi` (a UEFI-only
crate never built for any LibreLog target) is not relied on.

- **SQLite**, compiled in by `libsqlite3-sys` (`rusqlite`'s `bundled`
  feature), is in the public domain.
- **UniFFI** (MPL-2.0) is a file-level copyleft licence that is explicitly
  compatible with the GPL family (MPL-2.0 §3.3); its files stay MPL-2.0
  inside the larger AGPL work. The Swift and Kotlin bindings generated by
  `uniffi-bindgen` (`apple/Sources/LibrelogCore/` and
  `android/app/src/main/java/uniffi/librelog_core/`) contain UniFFI
  template code under MPL-2.0.
- Build-only tooling behind the `bindgen` feature (`uniffi_bindgen`,
  `uniffi_udl`, `askama`, `clap`, `goblin`, `weedle2`, `textwrap` and their
  dependencies, all MIT, Apache-2.0 or MPL-2.0) and build scripts (`cc`,
  `pkg-config`, `vcpkg`, `autocfg`) are not shipped in any app. `tempfile`
  (MIT OR Apache-2.0) is a test-only dependency.

## Apple app (`apple/`)

No third-party Swift packages. Beyond the Rust core and the fonts above, it
uses only Apple's system frameworks (SwiftUI, UIKit/AppKit, Core Image,
AuthenticationServices, Security, …), which are system libraries for the
purposes of the GPL (AGPL-3.0 §1).

## Android app (`android/`)

From `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`.
Licences from each artifact's published POM.

| Component | Licence |
| --- | --- |
| AndroidX: Compose (BOM 2026.09.00: ui, foundation, material3, material-icons-extended, ui-tooling), activity-compose, lifecycle, core-ktx, work-runtime, security-crypto, credentials, exifinterface | Apache-2.0 |
| Google Tink (via `androidx.security:security-crypto`) | Apache-2.0 |
| Kotlin standard library, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 |
| OkHttp, Okio (Square) | Apache-2.0 |
| JNA (`net.java.dev.jna:jna`) | LGPL-2.1-or-later OR Apache-2.0 — used under Apache-2.0 |
| ZXing core (`com.google.zxing:core`), draws the pairing QR code | Apache-2.0 |
| `androidx.credentials:credentials-play-services-auth` | Apache-2.0 itself, but pulls in `play-services-auth` below |
| **Google code scanner** (`com.google.android.gms:play-services-code-scanner`), scans the pairing QR code | **Proprietary** — ML Kit Terms of Service |
| **Google Play services** (`play-services-auth`, `play-services-basement`, … pulled in by the two above) | **Proprietary** — Android Software Development Kit License |
| **Sign in with Google** (`com.google.android.libraries.identity.googleid:googleid`) | **Proprietary** — Android Software Development Kit License |

Test-only (not shipped): JUnit 4 (EPL-1.0), Robolectric (MIT), AndroidX
Test (Apache-2.0), kotlinx-coroutines-test and OkHttp MockWebServer
(Apache-2.0).

Every Apache-2.0 component above is compatible with AGPL-3.0 (Apache-2.0 is
compatible with GPLv3 one way: the combination is distributed under the
AGPL).

## Windows app (`windows/`)

From the `.csproj` files.

| Component | Licence |
| --- | --- |
| QRCoder | MIT |
| System.Security.Cryptography.ProtectedData, .NET runtime (Native AOT) | MIT |
| **Windows App SDK** (`Microsoft.WindowsAppSDK`, WinUI 3, and WebView2 which it brings in) | **Proprietary** — Microsoft Software License Terms (redistributable runtime) |
| Microsoft.Windows.SDK.BuildTools | Microsoft Software License Terms — build tool only, not shipped |

Test-only (not shipped): xUnit (Apache-2.0), Microsoft.NET.Test.Sdk (MIT).

## Sync backend (`supabase/`)

Only SQL migrations of our own, run on Supabase (Postgres — PostgreSQL
Licence; Supabase's services — Apache-2.0; `pg_cron` — PostgreSQL Licence).
Nothing from those projects is copied into this repository.

## Proprietary components

Four of the components above are not free software: Google's code scanner,
Google Play services and the Sign in with Google library on Android, and the
Windows App SDK on Windows. The AGPL forbids nobody from *running* LibreLog
with them, but it does matter for anyone who conveys a build:

- **Windows App SDK.** Microsoft's UI runtime, shipped as a framework
  package that the OS installs and services, and the only way to build a
  WinUI 3 app. It is generally treated as part of the platform, in the way
  the GPL's "System Libraries" exception (AGPL-3.0 §1) intends.
- **Google Play services, the code scanner and `googleid`.** These are
  linked into the APK rather than provided by the operating system, so the
  System Libraries exception does not clearly cover them. The copyright
  holder can ship its own Play builds regardless (a licensor is not bound by
  its own licence), but a third party redistributing a modified build, and
  any outside contribution merged into such a build, is on less certain
  ground.

The two usual remedies, which can be combined:

1. **An additional permission under AGPL-3.0 §7**, published with the
   licence, that lets the program be combined with these named proprietary
   libraries (and, where needed, distributed through app stores whose terms
   add restrictions). This is the approach of many GPL Android apps. It has
   to be in place before outside contributions arrive, because it can only
   be granted by the people who hold copyright in the code.
2. **A Play-free build flavour** (as F-Droid requires): scan the pairing QR
   code with CameraX and ZXing (already a dependency, Apache-2.0) instead of
   the Google code scanner, and leave out Sign in with Google (email code
   sign-in and recovery-phrase pairing still work).

Neither is in place yet. Until one is, builds that include these libraries
are distributed by the copyright holder only.
