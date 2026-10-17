# Sign in with Google

An account only gates ciphertext sync (SPEC.md §12.3), and Google is one of
the ways to have one, beside an emailed code and Sign in with Apple. Every
app ends up with an ordinary Supabase Auth session; they get there two ways:

- **Android** signs in natively, through Credential Manager
  (`android/app/src/main/java/uk/co/maybeitssoftware/librelog/sync/GoogleSignIn.kt`).
  Google returns an ID token issued to LibreLog's **Web** OAuth client, asked
  for with the SHA-256 of a random nonce. The app hands the token and the raw
  nonce to Supabase's `id_token` grant, and Supabase checks the token's
  audience against the client ids on its Google provider.
- **iPhone, iPad, Mac and Windows** use Supabase's web flow with PKCE. The
  app opens `/auth/v1/authorize?provider=google` (Apple: in an
  `ASWebAuthenticationSession` sheet, `apple/App/Sync/WebSignIn.swift`;
  Windows: in the default browser), Google comes back to Supabase's callback,
  Supabase redirects to `librelog://auth/callback?code=…`, and the app trades
  the code and its verifier for a session (`grant_type=pkce`). That flow
  needs the same Web client and its secret.

The Android button is hidden until the build has a Web client id. The Apple
and Windows buttons always show, and Supabase answers with an error page
until its Google provider is enabled.

Getting it working takes one Google Cloud project with a Web client and
three Android clients, the Supabase provider, and one build value.

## 1. Google Cloud project and consent screen

1. [Google Cloud console](https://console.cloud.google.com) → project picker
   → **New project**. Name it `LibreLog`.
2. **APIs & Services → OAuth consent screen** (now *Google Auth Platform →
   Branding / Audience / Data access*):
   - App name **LibreLog**, a support email, the developer contact email.
   - App domain: the privacy policy URL from the store listing (and the home
     page if there is one). Add `supabase.co` under **Authorised domains** so
     the callback below is accepted.
   - Scopes: `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`.
     These are non-sensitive, so no verification review is needed.
   - Audience: **External**, then **Publish app**. A consent screen left in
     Testing only lets the test users you list sign in.

## 2. The Web application client

Its id and secret go to Supabase; its id also goes into the Android build.

1. **Credentials → Create credentials → OAuth client ID → Web application.**
   Name it `LibreLog web (Supabase)`.
2. **Authorised redirect URIs**: add Supabase's callback,
   `https://wzkhuilypzapqkrjxeku.supabase.co/auth/v1/callback`.
   (A self-hosted server uses its own `https://<host>/auth/v1/callback`; for
   `supabase start` locally, `http://127.0.0.1:54321/auth/v1/callback`.)
3. Save, and copy the **Client ID** (`….apps.googleusercontent.com`) and the
   **Client secret**.

## 3. The Android clients

Google accepts a native sign-in only from an app whose package name and
signing certificate match an **Android** OAuth client in the same project.
Their ids go nowhere; they only need to exist. A client takes one SHA-1, so
make one per signing key, each with package `uk.co.maybeitssoftware.librelog`
(**Credentials → Create credentials → OAuth client ID → Android**):

| Client | SHA-1 | Signs |
| --- | --- | --- |
| `LibreLog Android (Play)` | from Play Console, see below | every install from the Play Store |
| `LibreLog Android (upload)` | `98:79:B3:1C:CF:A8:6D:93:8D:B0:F2:7C:B7:2B:CB:73:F5:0D:F4:EF` | local release builds (`android/key.properties`, the shared maybeitssoftware upload key) |
| `LibreLog Android (debug)` | `00:69:90:78:37:7B:B1:17:F0:10:9A:2A:53:28:99:92:5C:77:DC:6C` | `./gradlew installDebug` on this Mac (`~/.android/debug.keystore`) |

The debug SHA-1 is per machine; another Mac needs its own client.

**Play app signing SHA-1:** Play Console → LibreLog → **Test and release →
Setup → App signing** → *App signing key certificate* → SHA-1 (not the
SHA-256). It exists once Play App Signing is on, which it is after the first
bundle upload.

To recompute the other two:

```sh
cd android
prop() { grep -m1 "^$1=" key.properties | cut -d= -f2-; }
keytool -list -v -keystore "$(prop storeFile)" -alias "$(prop keyAlias)" \
  -storepass "$(prop storePassword)" | grep -E '^\s*SHA1:'

keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
  -storepass android | grep -E '^\s*SHA1:'
```

## 4. Supabase: the Google provider

Dashboard → project `wzkhuilypzapqkrjxeku` → **Authentication → Sign In /
Providers → Google**:

- **Enable Sign in with Google**: on.
- **Client IDs**: the Web client id from step 2. (Comma-separated if more
  audiences are added later, Web client first.)
- **Client Secret (for OAuth)**: the Web client's secret.
- **Skip nonce checks**: leave **off**. Android sends a nonce.
- **Allow users without an email**: off.

Then **Authentication → URL Configuration → Redirect URLs** must list both
`librelog://auth` (email links) and `librelog://auth/callback` (Google on
Apple and Windows). `supabase/config.toml` lists them for local dev, but the
hosted project's list is set in the dashboard.

For `supabase start` locally, `config.toml` enables the provider from the
environment:

```sh
export SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID=….apps.googleusercontent.com
export SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET=…
```

## 5. The Web client id in the Android build

The build reads the **Web** client id (never an Android client id) from the
first of these that is set:

1. Gradle property: `./gradlew -PlibrelogGoogleWebClientId=… …`, or
   `librelogGoogleWebClientId=…` in `~/.gradle/gradle.properties`.
2. Environment variable `LIBRELOG_GOOGLE_WEB_CLIENT_ID`. The Tramline Android
   workflow passes the repository secret of that name into the bundle build.
3. `librelogGoogleWebClientId=…` in `android/local.properties` (git-ignored).

The id isn't secret (it ships in the app); it stays out of the repo so
self-hosted builds don't inherit it. To check a build picked it up:

```sh
cd android
./gradlew -q :app:generateReleaseBuildConfig
grep GOOGLE_WEB_CLIENT_ID app/build/generated/source/buildConfig/release/uk/co/maybeitssoftware/librelog/BuildConfig.java
```

`fastlane android internal` runs Gradle from `android/`, so it picks up any
of the three.

## When it fails

- **No Google button on Android**: the build had no Web client id.
- **"No credentials available", or the sheet closes at once (Android)**: no
  Android client matches this install's package and SHA-1. Play installs need
  the Play signing client, sideloaded release builds the upload client, debug
  builds the debug client. New clients can take a few minutes.
- **`Unacceptable audience` / `invalid id token`**: the id in the app isn't in
  the provider's Client IDs, or is an Android client id rather than the Web one.
- **`redirect_uri_mismatch` from Google**: the Web client lacks
  `https://wzkhuilypzapqkrjxeku.supabase.co/auth/v1/callback`.
- **Supabase error page, or the browser lands on the site URL instead of the
  app**: the provider is off, or `librelog://auth/callback` is missing from
  the hosted Redirect URLs.
