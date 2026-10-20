#!/usr/bin/env bash
# Creates (or resets) the App Review account on the LibreLog Supabase project
# and turns sync on for it. See "App review account" in the README.
#
#   scripts/review-account.sh                        # new random password
#   REVIEW_PASSWORD='…' scripts/review-account.sh    # a password of your own
#
# Needs the Supabase CLI, logged in (`supabase login`). Running it again keeps
# the same account and sets the password again. The password is printed once
# and stored nowhere; put it in your password manager.
set -euo pipefail

REF="${LIBRELOG_PROJECT_REF:-wzkhuilypzapqkrjxeku}"
EMAIL="${REVIEW_EMAIL:-appreview@maybeitssoftware.co.uk}"
YEARS="${REVIEW_SYNC_YEARS:-10}"
URL="https://$REF.supabase.co"

PASSWORD="${REVIEW_PASSWORD:-$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-20)}"
if [ "${#PASSWORD}" -lt 8 ]; then echo "The password needs at least 8 characters." >&2; exit 1; fi

KEY=$(supabase projects api-keys --project-ref "$REF" -o json 2>/dev/null | python3 -c '
import json, sys
keys = json.load(sys.stdin)
print(next(k["api_key"] for k in keys if k.get("name") == "service_role"))
') || { echo "Couldn't read the project's service key. Run: supabase login" >&2; exit 1; }

auth=(-H "apikey: $KEY" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json")
body=$(EMAIL="$EMAIL" PASSWORD="$PASSWORD" python3 -c '
import json, os
print(json.dumps({"email": os.environ["EMAIL"], "password": os.environ["PASSWORD"], "email_confirm": True}))')

# Find the account by email, a page of users at a time.
id=""
for page in $(seq 1 50); do
  users=$(curl -fsS "${auth[@]}" "$URL/auth/v1/admin/users?page=$page&per_page=200")
  found=$(EMAIL="$EMAIL" python3 -c '
import json, os, sys
users = json.load(sys.stdin).get("users", [])
match = [u["id"] for u in users if (u.get("email") or "").lower() == os.environ["EMAIL"].lower()]
print(match[0] if match else ("" if users else "END"))' <<<"$users")
  [ "$found" = END ] && break
  [ -n "$found" ] && { id=$found; break; }
done

if [ -n "$id" ]; then
  curl -fsS -X PUT "${auth[@]}" "$URL/auth/v1/admin/users/$id" -d "$body" >/dev/null
  echo "Updated $EMAIL and set its password."
else
  curl -fsS -X POST "${auth[@]}" "$URL/auth/v1/admin/users" -d "$body" >/dev/null
  echo "Created $EMAIL (confirmed, so it never needs to receive mail)."
fi

until=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=365*$YEARS)).isoformat())")
curl -fsS -X POST "${auth[@]}" -H "Content-Profile: librelog" \
  "$URL/rest/v1/rpc/admin_set_sync_until" \
  -d "{\"p_email\": \"$EMAIL\", \"p_until\": \"$until\"}" >/dev/null
echo "Sync is on until ${until%%T*}."

cat <<EOF

  Email:    $EMAIL
  Password: $PASSWORD

Next: sign in once on any device (Settings → Sync → Use a password instead)
to create the account's journal key, and keep the recovery phrase it shows.
Then put the email, password and phrase in App Store Connect (App Review
Information) and Play Console (App content → App access).
EOF
