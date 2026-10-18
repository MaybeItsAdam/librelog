# Self-hosting LibreLog sync

LibreLog's sync backend is a set of SQL migrations in [`supabase/`](../supabase/)
running on [Supabase](https://supabase.com): Auth for sign-in, Postgres
functions for the encrypted op log, Realtime Broadcast for "new data" pings,
and `pg_cron` for nightly housekeeping. There is no other server. Anyone can
run their own copy, on Supabase's hosted platform or on a self-hosted
Supabase, and use it instead of the paid hosted sync.

The server only ever holds ciphertext (SPEC.md §12.4), so whoever runs it
cannot read the journals that sync through it — but they do hold the
accounts, and with them email addresses and device counts.

[`supabase/README.md`](../supabase/README.md) is the client contract: every
function, its arguments and error codes.

## What you need

- A Supabase project — hosted (the free plan is enough for a household), or
  [self-hosted Supabase](https://supabase.com/docs/guides/self-hosting) with
  Auth, the Data API (PostgREST) and Realtime running, on **Postgres 17**.
- The extension `pg_cron` available (it is on hosted Supabase and in the
  Supabase Docker images).
- The [Supabase CLI](https://supabase.com/docs/guides/cli).
- HTTPS. The apps talk to `https://<host>` and open Realtime on
  `wss://<host>`, so a self-hosted Supabase must sit behind TLS (a reverse
  proxy such as Caddy, or a tunnel). `supabase start` on localhost serves
  plain HTTP, which is fine for testing the migrations but not for the apps.
- Your own builds of the apps (see [Pointing the apps at your
  server](#pointing-the-apps-at-your-server)).

## 1. Create the database

From a clone of this repository:

```bash
supabase login
supabase link --project-ref <your-project-ref>   # asks for the database password
supabase db push                                 # applies supabase/migrations/ in order
```

For a self-hosted Supabase, use `supabase db push --db-url
postgresql://postgres:<password>@<host>:5432/postgres` instead of `link`.

The migration builds the `librelog` schema. It creates:

- the tables, row-level security and every client function;
- the Realtime policy that lets a signed-in device receive on its own
  private channel `user:<uid>` and nobody else's;
- the nightly `librelog-maintenance` job (03:17 UTC), which drops expired
  pairings, compacts each account's op log below its newest snapshot, and
  deletes accounts whose sync lapsed more than 90 days ago.

Check the job is there:

```sql
select jobname, schedule from cron.job;   -- librelog-maintenance | 17 3 * * *
```

To test the migrations without any project, `supabase/tests/run.sh` runs them
and the test suite against a throwaway local Postgres 17.

## 2. Expose the schema to the Data API

The apps call `https://<host>/rest/v1/rpc/<function>` with the header
`Content-Profile: librelog`, so `librelog` has to be an exposed schema.

- Hosted: Dashboard → Project Settings → Data API → Exposed schemas → add
  `librelog`.
- Self-hosted: add it to `PGRST_DB_SCHEMAS` (e.g.
  `public,graphql_public,librelog`) and restart PostgREST.

`supabase/config.toml` already lists it under `[api] schemas` for local
development. Be careful with `supabase config push` against your own project:
it pushes the whole file, including this project's auth settings and Sign in
with Apple client id. Edit it for your setup first, or make the changes in the
dashboard instead.

## 3. Configure Auth

LibreLog signs in with an emailed one-time code, Sign in with Apple, or
Sign in with Google. Accounts exist only to sync.

- **Redirect URLs.** The apps handle `librelog://auth`. Set the Site URL to
  `librelog://auth` and add `librelog://auth` and `librelog://auth/callback`
  to the allowed redirect URLs (Dashboard → Authentication → URL
  Configuration; `GOTRUE_SITE_URL` and `GOTRUE_URI_ALLOW_LIST` when
  self-hosting).
- **Email.** Enable the email provider. The apps ask for a 6-digit code
  and also accept the default email's link (it lands on `librelog://auth`).
  To send just the code, use `supabase/templates/sign_in_code.html`
  (`{{ .Token }}`) for both the "Magic Link" and "Confirm signup" emails;
  `supabase/config.toml` shows the settings. Supabase's built-in mailer is
  heavily rate-limited and, on hosted projects, templates can only be
  changed once custom SMTP is set up, so configure your own SMTP server for
  anything beyond a test.
- **Sign in with Apple** (optional). The Apple apps request an identity
  token for their bundle id, so add your build's bundle id as the Apple
  provider's client id. Without this, the email code still works.
- **Sign in with Google** (optional). Needs your own Google Cloud OAuth
  client and the Google provider enabled; see
  [google-sign-in.md](google-sign-in.md).
- **Who may sign up.** Anyone who can reach your Auth endpoint can create an
  account. On a private server, turn off new sign-ups (Authentication →
  Sign In / Providers → "Allow new users to sign up", or
  `GOTRUE_DISABLE_SIGNUP=true`) and invite the people you want instead.

## 4. Realtime

Nothing to configure beyond having Realtime running: `push_ops` calls
`realtime.send()` on the pusher's private channel, and the migration's policy
on `realtime.messages` restricts each user to their own channel. If Realtime
is missing, `push_ops` skips the ping and devices still pull when they
launch or return to the foreground, just not within a second of an edit.

## 5. Let accounts sync

A new account starts with `sync_until` empty, and `push_ops` refuses to
store anything (HTTP 402) until it is in the future. This is the hosted
service's paywall; on your own server you choose.

**Grant it per person**, in the SQL editor (they must have signed in once):

```sql
select librelog.admin_set_sync_until('someone@example.com', 'infinity');
```

**Or make every account active** — the usual choice for a private server,
together with turning off open sign-ups (step 3). Add it as a migration of
your own so it is applied with the rest:

```bash
supabase migration new self_hosted_sync_for_everyone
```

```sql
-- Every account may sync, now and in future.
alter table librelog.accounts alter column sync_until set default 'infinity';
update librelog.accounts set sync_until = 'infinity';
```

```bash
supabase db push
```

`'infinity'` passes every check the functions make: pushes and pulls are
allowed, and the nightly job never treats the account as lapsed.

Each account may also hold at most 500 MB of ciphertext (HTTP 413 beyond it).
Text sync rarely comes near that, but you can raise it the same way:

```sql
alter table librelog.accounts alter column quota_bytes set default 5368709120; -- 5 GB
update librelog.accounts set quota_bytes = 5368709120;
```

## Pointing the apps at your server

**The apps have no setting for a custom server yet.** The Supabase URL and
publishable key are compiled in, identically, on each platform:

| App | File | Constants |
| --- | --- | --- |
| iPhone, iPad, Mac | `apple/App/AppModel.swift` | `Backend.url`, `Backend.publishableKey` |
| Android | `android/app/src/main/java/uk/co/maybeitssoftware/librelog/AppModel.kt` | `Backend.URL`, `Backend.PUBLISHABLE_KEY` |
| Windows | `windows/src/Librelog.Shared/Sync/SupabaseClient.cs` | `Backend.Url`, `Backend.PublishableKey` |

To use your server, change those to your project's URL and publishable key
(Dashboard → Project Settings → API Keys; a legacy `anon` key works too), then
build and install the apps yourself as described in the
[README](../README.md#build-and-run). The publishable key is public by
design: it identifies the project, and row-level security plus the
functions' own checks decide what each user can do. A build from the App
Store, Google Play or the Microsoft Store always talks to the hosted service.

If you also change the app id (for example to publish your own fork), update
the Apple provider's client id and your Google OAuth client to match. The
`librelog://auth` URL scheme is fixed in each app's manifest
(`apple/project.yml`, `android/app/src/main/AndroidManifest.xml`,
`windows/src/Librelog.App/Package.appxmanifest`); keep it, or change it there
and in the redirect URLs together.

Switching a device between servers is not a migration: sign out of sync on
the device, change the build, then sign in to the new server and either
start sync fresh (the device uploads its journal) or pair it with a device
already on that server. Journals stay on the device throughout.

### What a server setting would take

A "Use a different server" option in Sync settings would need, on each
platform: a stored URL and publishable key (falling back to the built-in
ones) read by `SupabaseClient` and `RealtimeChannel` instead of the
`Backend` constants; the setting allowed only while signed out, so a session
token is never sent to a different server; and the Sign in with Apple and
Google buttons hidden for servers whose providers do not match the build.
The end-to-end encryption needs no change, since the journal key never
leaves the devices.

## Your obligations under the AGPL

LibreLog, including the SQL in `supabase/`, is licensed under the GNU
Affero General Public License v3 or later. Running it unmodified for
yourself or others asks nothing of you. If you **modify** it — the apps or
the backend's SQL — and let other people use your modified version,
including just by syncing with your server, you must offer those people the
corresponding source of your version under the same licence (AGPL-3.0 §13).
A public fork with a link to it in your app's settings or sign-in screen is
the usual way.
