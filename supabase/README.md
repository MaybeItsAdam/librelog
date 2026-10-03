# Librelog sync backend

Supabase project `librelog` (ref `wzkhuilypzapqkrjxeku`, `eu-west-2`, Free
plan in the `maybeitsadam` org). There is no server of our own: the apps call
Postgres functions through the Data API. SPEC.md §12.5 is the design; this
file is the contract the Rust core's sync client codes against.

## Layout

| Path | What |
| --- | --- |
| `migrations/20261003150000_sync_schema.sql` | Tables, RLS, every client function |
| `migrations/20261003150100_maintenance_cron.sql` | Nightly `librelog.maintenance()` at 03:17 UTC |
| `migrations/20261003150200_realtime_auth.sql` | Lets a device receive on its own Realtime channel |
| `migrations/20261003170000_rename_to_librelog.sql` | Rebuilds the three above under the `librelog` schema (the app was Dailylog) and drops `dailylog` |
| `templates/sign_in_code.html` | Sign-in code email, enabled once custom SMTP is set up |
| `tests/` | `run.sh` tests the migrations on a throwaway local Postgres 17 |
| `config.toml` | Pushed with `supabase config push` |

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
| 400 | Bad input | bug — log it |

## Realtime

Subscribe to the **private** broadcast channel `user:<auth uid>`, event
`ops`, payload `{seq, device_id}`. When `seq` is higher than the local
cursor and `device_id` isn't this device, pull.

## Granting sync by hand

Until checkout exists, in the SQL editor:

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
