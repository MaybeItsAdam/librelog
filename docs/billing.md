# Billing: the yearly sync subscription

Everything in LibreLog is free except hosted sync, which is a yearly
subscription priced at what the service costs to run (SPEC.md §15). The apps
sell it through the App Store and Google Play; Windows shows the account's
status and points to the phone apps until web checkout exists.

| Store | Product | Bought with |
| --- | --- | --- |
| App Store | subscription group **LibreLog Sync**, product id `uk.co.maybeitssoftware.librelog.sync.yearly`, 1 year, auto-renewing | `appAccountToken` = the Supabase user id |
| Google Play | subscription `sync_yearly`, base plan `yearly` (auto-renewing, 1 year) | `obfuscatedAccountId` = the Supabase user id |

## How it fits together

There is still no server of our own. Three Supabase Edge Functions verify
what the stores say and call `librelog.record_purchase()` with the service
role, which sets `accounts.sync_until`, the date `push_ops` and `pull_ops`
already check.

| Function | Called by | Does |
| --- | --- | --- |
| `verify-purchase` | the apps, signed in, right after a purchase or restore | App Store: checks the StoreKit 2 transaction's JWS. Play: reads the purchase token from the Play Developer API and acknowledges it. Then records it against the signed-in account and returns the new `sync_until`. Idempotent. |
| `appstore-notifications` | App Store Server Notifications v2 | checks the notification's JWS and the transaction and renewal info inside it; handles SUBSCRIBED, DID_RENEW, DID_FAIL_TO_RENEW (grace period), EXPIRED, GRACE_PERIOD_EXPIRED, REFUND, REVOKE, REFUND_REVERSED and the renewal-status changes |
| `play-rtdn` | Google Cloud Pub/Sub push of Play real-time developer notifications | checks the push's OIDC token (issuer, audience, service account), reads the subscription with `purchases.subscriptionsv2.get`, takes `lineItems[].expiryTime`, acknowledges, and handles refunds (`voidedPurchaseNotification`) and revocations |

Rules `record_purchase` keeps (`supabase/migrations/20261022120000_billing.sql`):

- A purchase is one row in `librelog.purchases`, keyed by store and the
  store's id for the subscription (App Store `originalTransactionId`, Play
  purchase token), so every renewal and refund lands on the same account.
- `sync_until` only moves later, except on a refund or revocation, which
  takes back that purchase and falls back to the account's other purchases,
  any hand grant (`admin_set_sync_until`, kept in `granted_until`), or now
  (so the usual 30 days of pulls remain).
- A notification redelivered with the same id (App Store `notificationUUID`,
  Pub/Sub `messageId`) is a no-op, and so is one older than the state on
  file.
- One subscription pays for one LibreLog account: a purchase already linked
  to another account is refused (409), and so is a transaction bought for
  another account (403).
- Pending Play purchases are recorded but don't switch sync on.

Apple's JWS chain is checked offline, as Apple's App Store Server Library
does: exactly three certificates, the root byte-for-byte Apple Root CA - G3
(pinned in `supabase/functions/_shared/apple.ts`), Apple's intermediate and
leaf marker OIDs, each certificate signed by the next and valid when the
payload was signed, then the ES256 signature. Sandbox transactions are
accepted (App Review and TestFlight buy in the sandbox, where a year lasts an
hour) unless `APPLE_ALLOW_SANDBOX=false`.

The apps read their status with `rpc/sync_status` (paid-through date, store,
state, auto-renew).

## Endpoints and secrets

Project `wzkhuilypzapqkrjxeku`:

| URL | Goes in |
| --- | --- |
| `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/appstore-notifications` | App Store Connect, production **and** sandbox server URL |
| `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/play-rtdn` | the Pub/Sub push subscription's endpoint and token audience |
| `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/verify-purchase` | called by the apps; nothing to set up |

Edge Function secrets (`supabase secrets set`). Supabase provides
`SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` and `SUPABASE_ANON_KEY` itself.

| Secret | Required | Value |
| --- | --- | --- |
| `PLAY_SERVICE_ACCOUNT_JSON` | yes | the Play Developer API service account's JSON key, on one line |
| `PLAY_RTDN_AUDIENCE` | yes | `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/play-rtdn` |
| `PLAY_RTDN_SERVICE_ACCOUNT` | yes | the email of the service account Pub/Sub pushes as, e.g. `librelog-rtdn-push@<gcp-project>.iam.gserviceaccount.com` |
| `PLAY_PACKAGE_NAME` | no | default `uk.co.maybeitssoftware.librelog` |
| `PLAY_PRODUCT_IDS` | no | default `sync_yearly` (comma-separated) |
| `APPLE_BUNDLE_ID` | no | default `uk.co.maybeitssoftware.librelog` |
| `APPLE_PRODUCT_IDS` | no | default `uk.co.maybeitssoftware.librelog.sync.yearly` (comma-separated) |
| `APPLE_ALLOW_SANDBOX` | no | default `true`; `false` refuses sandbox purchases |

`play-rtdn` won't start without its two required secrets, so set them before
deploying it.

## Checklist

### 1. App Store Connect

1. **Business → Agreements**: accept the **Paid Applications** agreement.
   Under it, fill in **Tax forms** (the UK tax and the US W-8BEN) and **Bank
   accounts**. Nothing can be sold, even in the sandbox for review, until
   it shows *Active*.
2. Enrol in the **App Store Small Business Program** (developer account →
   Programs), so the cut is 15% rather than 30%. SPEC.md §15.5 assumes 15%.
3. **Apps → LibreLog → Monetisation → Subscriptions → Create** a subscription
   group:
   - Reference name `LibreLog Sync`; localisation display name `LibreLog Sync`.
4. In the group, **Create** a subscription:
   - Reference name `Sync, 1 year`.
   - Product ID **`uk.co.maybeitssoftware.librelog.sync.yearly`** (exact;
     it can never be reused or renamed).
   - Duration **1 year**. Family Sharing off (sync is per account).
   - **Subscription prices**: the base country United Kingdom, at the price
     you decide (see *Decisions* below), then let Apple fill in the other
     storefronts.
   - **Localisation** (English (U.K.), add English (U.S.)): display name
     `Sync, 1 year`; description `End-to-end encrypted sync across your
     devices for a year, at cost.`
   - **Review information**: a screenshot of Settings → Sync on an iPhone
     showing the Subscription section (status, price button, terms), and the
     note below.
   - **Tax category**: Software as a service.
5. **App Information → App Store Server Notifications**: Production Server
   URL and Sandbox Server URL both
   `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/appstore-notifications`,
   **Version 2**. Then **Send Test Notification** (the function answers 200 and
   ignores it; see its logs in the Supabase dashboard).
6. **App Information**: a **Privacy Policy URL**, and the licence agreement
   (the standard Apple EULA is fine; the app links to it). The app links its
   privacy policy at `https://maybeitssoftware.co.uk/librelog/privacy`
   (`apple/App/Sync/Subscription.swift`, `Legal`): publish it there, or
   change the link.
7. The first subscription is reviewed with an app version: on the version
   page, under **In-App Purchases and Subscriptions**, add the subscription
   before **Submit for Review**.
8. **App Review notes**. The review account has sync granted by hand
   (`scripts/review-account.sh`), so it shows *Active* and no Subscribe
   button. Either say so, or give review a second account without a grant
   so they can buy in the sandbox:
   > Sync is an optional yearly subscription (Settings → Sync →
   > Subscription). The review account already has sync switched on. To
   > see the purchase, sign in with <second account> instead.

### 2. Google Play Console

1. **Settings → Payments profile**: create or link a payments profile
   (merchant account), with the bank account and tax details. Subscriptions
   can't be created without it.
2. Upload a build that includes the Play Billing Library (any track,
   internal testing is fine); Play won't offer the subscription screens
   until it has seen one.
3. **Monetise → Products → Subscriptions → Create subscription**:
   - Product ID **`sync_yearly`**, name `LibreLog Sync`.
   - Benefits: `Your journal, end-to-end encrypted, on all your devices`.
   - **Add a base plan**: ID **`yearly`**, type **Auto-renewing**, billing
     period **1 year**, grace period as Play suggests (the server honours
     it), resubscribe allowed. **Set prices** (see *Decisions*), then
     **Activate**.
4. **Google Cloud** (the project linked to the Play Console, or a new one):

   ```bash
   PROJECT=librelog-billing   # your Google Cloud project id
   URL=https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/play-rtdn

   gcloud services enable androidpublisher.googleapis.com pubsub.googleapis.com --project=$PROJECT

   # The topic Play publishes to.
   gcloud pubsub topics create play-rtdn --project=$PROJECT
   gcloud pubsub topics add-iam-policy-binding play-rtdn --project=$PROJECT \
     --member=serviceAccount:google-play-developer-notifications@system.gserviceaccount.com \
     --role=roles/pubsub.publisher

   # The identity Pub/Sub signs its pushes as (PLAY_RTDN_SERVICE_ACCOUNT).
   gcloud iam service-accounts create librelog-rtdn-push --project=$PROJECT
   gcloud pubsub subscriptions create play-rtdn-push --project=$PROJECT \
     --topic=play-rtdn \
     --push-endpoint=$URL \
     --push-auth-service-account=librelog-rtdn-push@$PROJECT.iam.gserviceaccount.com \
     --push-auth-token-audience=$URL \
     --ack-deadline=60

   # The identity the functions call the Play Developer API as
   # (PLAY_SERVICE_ACCOUNT_JSON). Keep the key out of the repo.
   gcloud iam service-accounts create librelog-play-api --project=$PROJECT
   gcloud iam service-accounts keys create ~/play-api.json --project=$PROJECT \
     --iam-account=librelog-play-api@$PROJECT.iam.gserviceaccount.com
   ```

   Projects made before April 2021 also need Pub/Sub's own service agent
   allowed to mint tokens:
   `gcloud projects add-iam-policy-binding $PROJECT --member=serviceAccount:service-<project-number>@gcp-sa-pubsub.iam.gserviceaccount.com --role=roles/iam.serviceAccountTokenCreator`.
5. **Play Console → Users and permissions → Invite new users**:
   `librelog-play-api@<project>.iam.gserviceaccount.com`, app permission on
   LibreLog with **View financial data** and **Manage orders and
   subscriptions**. It can take up to a day to start working.
6. **Monetise → Monetisation setup → Real-time developer notifications**:
   topic `projects/<project>/topics/play-rtdn`, notifications for
   **Subscriptions, voided purchases and all one-time products**. **Send test
   notification** (the function answers 200 and ignores it).

### 3. Supabase

Run from the repo root, signed in (`supabase login`) and linked
(`supabase link --project-ref wzkhuilypzapqkrjxeku`).

```bash
# Database: purchases, record_purchase(), sync_status().
supabase/tests/run.sh
supabase db push

# Secrets. The JSON key goes through a private env file, not the command line.
umask 077
{
  printf 'PLAY_SERVICE_ACCOUNT_JSON=%s\n' "$(jq -c . ~/play-api.json)"
  printf 'PLAY_RTDN_AUDIENCE=%s\n' https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/play-rtdn
  printf 'PLAY_RTDN_SERVICE_ACCOUNT=%s\n' librelog-rtdn-push@<project>.iam.gserviceaccount.com
} > /tmp/billing.env
supabase secrets set --env-file /tmp/billing.env --project-ref wzkhuilypzapqkrjxeku
rm /tmp/billing.env ~/play-api.json   # keep the key in your password manager instead

# Functions. verify_jwt = false comes from config.toml: each function checks
# its own caller (Apple's signature, Google's OIDC token, the user's session).
(cd supabase/functions && deno task test && deno task check)
supabase functions deploy appstore-notifications play-rtdn verify-purchase \
  --project-ref wzkhuilypzapqkrjxeku
```

## Testing

- **Locally, no stores.** `supabase/tests/run.sh` (SQL) and
  `cd supabase/functions && deno task test` (signature checks against a
  stand-in chain in `tests/fixtures`, made by `tests/make-fixtures.sh`; the
  store and database calls are faked).
- **iPhone/Mac in Xcode.** The Librelog scheme runs with
  `apple/Librelog.storekit`, so Subscribe, renewals (Debug → StoreKit →
  Manage Transactions), refunds and Restore work without App Store Connect.
  Those transactions are signed by Xcode, not Apple, so `verify-purchase`
  refuses them and the app says it couldn't confirm the purchase: that's
  expected. To go end to end, turn the StoreKit configuration off (Edit
  Scheme → Run → Options → StoreKit Configuration: None) and use a sandbox
  account.
- **App Store sandbox.** App Store Connect → Users and Access → Sandbox →
  add a tester with an unused email. On the device, Settings → Developer →
  Sandbox Apple Account (or the purchase sheet prompts). TestFlight builds
  use the sandbox automatically. A sandbox year lasts an hour; refunds can be
  tested from the tester's purchase history.
- **Google Play.** Play Console → Settings → **License testing**: add the
  testers' Google accounts, and add them to the internal testing track. Install
  from that track's opt-in link (sideloaded debug builds can't buy). Testers
  pay with test cards; a test year renews every 30 minutes, and refunds or
  revocations can be made from **Order management**.
- After each step, check `librelog.purchases` and the account's
  `sync_until` in the SQL editor, and the function logs in the dashboard.

## Windows and the web

The Microsoft Store lets non-game apps take their own payments with no
commission (SPEC.md §15.5), so Windows (and a web page) would sell sync
through Stripe Checkout and a `stripe-webhook` function writing
`store = 'stripe'` rows through the same `record_purchase`. That isn't
built: Windows shows the account's status and says to subscribe from the
iPhone or Android app.

## Granting sync by hand

Still works, and survives refunds of store purchases:

```sql
select librelog.admin_set_sync_until('someone@example.com', now() + interval '1 year');
```
