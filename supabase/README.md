# Librelog sync backend

Supabase project `librelog` (ref `wzkhuilypzapqkrjxeku`, `eu-west-2`, Free
plan in the `maybeitsadam` org). There is no server of our own: the apps call
Postgres functions through the Data API. SPEC.md §12.5 is the design; this
file is the contract the Rust core's sync client codes against.

## Layout

| Path | What |
| --- | --- |
| `migrations/20261003170000_sync_schema.sql` | Tables, RLS, every client function, the nightly `librelog.maintenance()` at 03:17 UTC, and the Realtime policy that lets a device receive on its own channel |
| `migrations/20261022120000_billing.sql` | Store purchases, `record_purchase()` (service role only) and `sync_status()` |
| `functions/` | The billing Edge Functions: `verify-purchase`, `appstore-notifications`, `play-rtdn` ([docs/billing.md](../docs/billing.md)); `deno task test` in there |
| `templates/sign_in_code.html` | Sign-in code email, enabled once custom SMTP is set up |
| `tests/` | `run.sh` tests the migrations on a throwaway local Postgres 17 |
| `config.toml` | Pushed with `supabase config push`; changes here do nothing to the live project until then |

## Calling it

Every call is `POST https://wzkhuilypzapqkrjxeku.supabase.co/rest/v1/rpc/<name>`
with:

```
apikey: <publishable key>
Authorization: Bearer <user's access token>
Content-Profile: librelog
Content-Type: application/json
```

All binary values (ciphertext, device names, key check) travel as base64
strings without line breaks.

| Function | Arguments | Returns |
| --- | --- | --- |
| `register_device` | `p_device_id uuid, p_name_enc b64` | `[{last_seq, sync_until, key_check, quota_bytes, bytes_used}]` — first call after sign-in; creates the account |
| `set_key_check` | `p_key_check b64` | the stored key check (first writer wins) |
| `push_ops` | `p_device_id, p_batch_id uuid, p_payloads b64[]` (≤ 500) | new `last_seq`; same `p_batch_id` again is a no-op |
| `pull_ops` | `p_device_id, p_after bigint, p_limit int = 500` (≤ 1000) | `[{seq, device_id, payload}]` oldest first; also acks `p_after` |
| `put_snapshot` | `p_device_id, p_seq bigint, p_payload b64` | — keeps the newest two |
| `get_snapshot` | `p_device_id` | `[{seq, payload}]` or `[]` |
| `pairing_open` | `p_id uuid` | expiry (10 min) |
| `pairing_fulfil` | `p_id, p_payload b64` | — |
| `pairing_take` | `p_id` | payload, or `null` while waiting; deletes the pairing once taken |
| `revoke_device` | `p_device_id` | — |
| `delete_account` | — | — deletes the auth user and every row |
| `sync_status` | — | `[{sync_until, store, product_id, status, expires_at, auto_renew}]` — the subscription that pays for sync, if any |

Plain `GET /rest/v1/purchases` reads the user's own store purchases.

## Reporting a purchase

`POST https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/verify-purchase`
with the same `apikey` and `Authorization` headers (no `Content-Profile`),
right after a purchase, a restore, or on launch for one not yet confirmed:

```json
{"store": "app_store", "signed_transaction": "<StoreKit 2 jwsRepresentation>"}
{"store": "play", "product_id": "sync_yearly", "purchase_token": "<token>"}
```

It answers `{sync_until, status, expires_at, acknowledged}`. Calling it again
with the same purchase changes nothing. 400 means the store data didn't
verify, 403 that the purchase was made for another account, 409 that it
already pays for another account, 502 that the store couldn't be reached.
On Play, acknowledge the purchase only after a 200, and only if
`acknowledged` is false. docs/billing.md has the rest.

Plain `GET /rest/v1/accounts` and `/rest/v1/devices` (same `Accept-Profile:
librelog` header) read the user's own rows for the settings screen.

## Errors

| HTTP | Meaning | Client does |
| --- | --- | --- |
| 401 | Not signed in / token expired | refresh the session, retry |
| 402 | Sync not paid for (pushes), or lapsed > 30 days (pulls) | show the subscription screen; keep working locally |
| 404 | Device revoked/unknown, or pairing expired | re-register, or restart pairing |
| 409 | Cursor is behind compaction | `get_snapshot`, apply it, then `pull_ops` from its `seq` |
| 413 | Over quota, or more than 500 ops in one push | split the batch; or surface the quota |
| 400 | `device belongs to another account` from `register_device` | the device id was registered by an account this device was signed in to before: reset sync state and register under a new id |
| 400 | Other bad input | bug — log it |

## Sessions

Sign-in is by emailed code, Apple or Google, or a password for accounts an
admin gave one (`scripts/review-account.sh`). Email confirmation is on
(`config.toml`), so a password sign-up can't claim an address its owner
hasn't confirmed.

- Sign out with `POST /auth/v1/logout?scope=local`. The default scope is
  global and signs the user out on every device.
- A refresh answered with 400 (`invalid_grant`, "Refresh Token Not Found")
  or 401 means the session is over: drop it and show sign-in. Keep the
  journal key, so signing in to the same account again needs nothing else.
- Device ids belong to the account that registered them. Signing in to a
  different account means a fresh cursor, a full re-upload and a new id.

## Realtime

Subscribe to the **private** broadcast channel `user:<auth uid>`, event
`ops`, payload `{seq, device_id}`. When `seq` is higher than the local
cursor and `device_id` isn't this device, pull.

The channel is joined with the user's access token and closed by the server
when that token expires. Send each refreshed token before then as an
`access_token` event on the channel topic (payload `{access_token}`), and
rejoin on `phx_close` or `phx_error`.

## Granting sync by hand

Besides the store subscriptions (docs/billing.md), in the SQL editor:

```sql
select librelog.admin_set_sync_until('you@example.com', now() + interval '1 year');
```

The user must have signed in once first.

## Changing the schema

```bash
supabase/tests/run.sh                 # local test, needs Homebrew postgresql@17
supabase migration new <name>         # write the SQL, add tests to tests/sync_test.sql
supabase db push                      # asks for the DB password; it's in ../.env.local
```
