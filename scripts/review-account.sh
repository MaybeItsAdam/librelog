#!/usr/bin/env bash
# Creates (or resets) the App Review account on the LibreLog Supabase project
# and turns sync on for it. See "App review account" in the README.
#
#   scripts/review-account.sh                        # new random password
#   REVIEW_PASSWORD='…' scripts/review-account.sh    # a password of your own
#
# Needs the Supabase CLI, logged in (`supabase login`). Running it again keeps
# the same account and sets the password again, so without REVIEW_PASSWORD
# the password App Review has stops working. The password is printed once
# and stored nowhere; put it in your password manager.
#
# The service key and the password never go on a command line (where `ps`
# shows them): curl reads the headers from a private temp file and the
# bodies from stdin.
set -euo pipefail

REF="${LIBRELOG_PROJECT_REF:-wzkhuilypzapqkrjxeku}"
EMAIL="${REVIEW_EMAIL:-appreview@maybeitssoftware.co.uk}"
YEARS="${REVIEW_SYNC_YEARS:-10}"
URL="https://$REF.supabase.co"

if ! [[ "$YEARS" =~ ^[1-9][0-9]*$ ]]; then
  echo "REVIEW_SYNC_YEARS must be a whole number of years, not '$YEARS'." >&2; exit 1
fi

generated=""
if [ -n "${REVIEW_PASSWORD:-}" ]; then
  PASSWORD=$REVIEW_PASSWORD
else
  PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)
  generated=1
fi
if [ "${#PASSWORD}" -lt 8 ]; then echo "The password needs at least 8 characters." >&2; exit 1; fi
export EMAIL PASSWORD YEARS

# The service key, in full. Newer CLIs mask secret keys unless asked.
keys=$(supabase projects api-keys --project-ref "$REF" --reveal -o json 2>/dev/null \
  || supabase projects api-keys --project-ref "$REF" -o json 2>/dev/null) \
  || { echo "Couldn't list the project's API keys. Run: supabase login" >&2; exit 1; }
KEY=$(python3 -c '
import json, sys
keys = json.load(sys.stdin)
key = next((k.get("api_key") for k in keys if k.get("name") == "service_role"), None) \
   or next((k.get("api_key") for k in keys if k.get("type") == "secret"), None)
if not key:
    sys.exit("No service_role or secret key on the project.")
if not key.startswith(("eyJ", "sb_secret_")) or any(c in key for c in "·*•…"):
    sys.exit("The service key came back masked. Update the Supabase CLI: this needs `api-keys --reveal`.")
print(key)' <<<"$keys") || { echo "Couldn't read the project's service key (see above)." >&2; exit 1; }

# curl options holding the key, readable only by you, gone when this exits.
umask 077
secrets=$(mktemp -d)
trap 'rm -rf "$secrets"' EXIT
cat >"$secrets/curl" <<EOF
header = "apikey: $KEY"
header = "Authorization: Bearer $KEY"
header = "Content-Type: application/json"
silent
show-error
fail
EOF
api() { curl --config "$secrets/curl" "$@"; }

# Find the account by email, a page of users at a time.
id=""
for page in $(seq 1 50); do
  users=$(api "$URL/auth/v1/admin/users?page=$page&per_page=200")
  found=$(python3 -c '
import json, os, sys
users = json.load(sys.stdin).get("users", [])
match = [u["id"] for u in users if (u.get("email") or "").lower() == os.environ["EMAIL"].lower()]
print(match[0] if match else ("" if users else "END"))' <<<"$users")
  [ "$found" = END ] && break
  [ -n "$found" ] && { id=$found; break; }
done

# email_confirm: the account is confirmed, so it never needs to receive mail.
user_body() {
  python3 -c '
import json, os
print(json.dumps({"email": os.environ["EMAIL"], "password": os.environ["PASSWORD"], "email_confirm": True}))'
}

if [ -n "$id" ]; then
  user_body | api -X PUT "$URL/auth/v1/admin/users/$id" --data-binary @- >/dev/null
  echo "Updated $EMAIL and set its password."
else
  user_body | api -X POST "$URL/auth/v1/admin/users" --data-binary @- >/dev/null
  echo "Created $EMAIL (confirmed, so it never needs to receive mail)."
fi

until=$(python3 -c '
import datetime, os
years = int(os.environ["YEARS"])
print((datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=365 * years)).isoformat())')
UNTIL=$until python3 -c '
import json, os
print(json.dumps({"p_email": os.environ["EMAIL"], "p_until": os.environ["UNTIL"]}))' \
  | api -X POST -H "Content-Profile: librelog" "$URL/rest/v1/rpc/admin_set_sync_until" --data-binary @- >/dev/null
echo "Sync is on until ${until%%T*}."

cat <<EOF

  Email:    $EMAIL
  Password: $PASSWORD
EOF

if [ -n "$id" ] && [ -n "$generated" ]; then
  cat >&2 <<'EOF'

  !!! The account already existed, and its password is now the NEW one above.
  !!! The old password no longer works. Update App Store Connect and Play
  !!! Console now, or App Review will be locked out. (Next time, pass the
  !!! current password as REVIEW_PASSWORD to keep it.)
EOF
fi

cat <<EOF

Next: sign in once on any device (Settings → Sync → Use a password instead)
to create the account's journal key, and keep the recovery phrase it shows.
Then put the email, password and recovery phrase in App Store Connect (App
Review Information) and Play Console (App content → App access).
EOF
