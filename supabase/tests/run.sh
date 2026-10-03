#!/usr/bin/env bash
# Tests the sync migration on a throwaway local Postgres 17 (no Docker needed):
#   supabase/tests/run.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
pgbin=${PGBIN:-/opt/homebrew/opt/postgresql@17/bin}
dir=$(mktemp -d)
port=54339

cleanup() { "$pgbin/pg_ctl" -D "$dir/data" stop -m immediate >/dev/null 2>&1 || true; rm -rf "$dir"; }
trap cleanup EXIT

"$pgbin/initdb" -D "$dir/data" -U postgres --auth=trust >/dev/null
"$pgbin/pg_ctl" -D "$dir/data" -o "-p $port -k $dir" -l "$dir/log" start >/dev/null

psql=("$pgbin/psql" -h "$dir" -p "$port" -U postgres -d postgres -q -v ON_ERROR_STOP=1)
"${psql[@]}" -f "$here/stubs.sql"
# The cron migration needs pg_cron, which a plain Postgres doesn't have;
# maintenance() itself is exercised directly by the tests.
# Every migration, in order, as `supabase db push` applies them. The stubs
# stand in for pg_cron, so `create extension pg_cron` is skipped.
for m in "$here"/../migrations/*.sql; do
  sed 's/^create extension if not exists pg_cron;/-- (pg_cron stubbed)/' "$m" | "${psql[@]}" -f -
done
"${psql[@]}" -f "$here/sync_test.sql"
