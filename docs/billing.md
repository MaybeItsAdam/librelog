# Billing: paying for sync

Everything in LibreLog is free except hosted sync, which is paid for by the
year at what the service costs to run (SPEC.md §15). The phone, tablet and
Mac apps sell it as a yearly subscription through the App Store and Google
Play; Windows sells it on the web through Stripe Checkout, as one payment for
1 or 3 years (the Microsoft Store takes no cut of that, SPEC.md §15.5).

| Store | Product | Bought with |
| --- | --- | --- |
| App Store | subscription group **LibreLog Sync**, product id `uk.co.maybeitssoftware.librelog.sync.yearly`, 1 year, auto-renewing | `appAccountToken` = the Supabase user id |
| Google Play | subscription `sync_yearly`, base plan `yearly` (auto-renewing, 1 year) | `obfuscatedAccountId` = the Supabase user id |
| Stripe (web, from Windows) | two one-off Prices: **€1** for 1 year, **€3** for 3 years; recorded as `sync_1y` / `sync_3y` | `client_reference_id` = the Supabase user id |

The apps on Apple and Google platforms don't link to the web checkout: the
store rules don't allow it outside a few storefronts (SPEC.md §15.5), and it
isn't worth the separate builds yet.

## How it fits together

There is still no server of our own. Supabase Edge Functions verify what
the stores and Stripe say and call `librelog.record_purchase()` with the
service role, which sets `accounts.sync_until`, the date `push_ops` and
`pull_ops` already check.

| Function | Called by | Does |
| --- | --- | --- |
| `verify-purchase` | the apps, signed in, right after a purchase or restore | App Store: checks the StoreKit 2 transaction's JWS. Play: reads the purchase token from the Play Developer API and acknowledges it. Then records it against the signed-in account and returns the new `sync_until`. Idempotent. |
| `appstore-notifications` | App Store Server Notifications v2 | checks the notification's JWS and the transaction and renewal info inside it; handles SUBSCRIBED, DID_RENEW, DID_FAIL_TO_RENEW (grace period), EXPIRED, GRACE_PERIOD_EXPIRED, REFUND, REVOKE, REFUND_REVERSED and the renewal-status changes |
| `play-rtdn` | Google Cloud Pub/Sub push of Play real-time developer notifications | checks the push's OIDC token (issuer, audience, service account), reads the subscription with `purchases.subscriptionsv2.get`, takes `lineItems[].expiryTime`, acknowledges, and handles refunds (`voidedPurchaseNotification`) and revocations |
| `stripe-checkout` | Windows, signed in, when *1 year* or *3 years* is clicked | checks the user's session, creates a Checkout Session in `payment` mode (one-off) for that plan's Price with `automatic_tax` on, `client_reference_id` and `metadata` = {user_id, years}, and returns its URL for the browser |
| `stripe-webhook` | Stripe webhook events | checks the `Stripe-Signature` (HMAC-SHA256 over `t.payload` with the endpoint's secret, 5 minutes' tolerance); a paid `checkout.session.completed` (or `async_payment_succeeded`) buys its years, a full `charge.refunded` or a `charge.dispute.created` takes them back; events from the other mode (test or live, by `STRIPE_SECRET_KEY`) are ignored |

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

Web purchases go through `librelog.record_stripe_purchase()`
(`supabase/migrations/20261022150000_stripe.sql`), which works out the expiry
and then calls `record_purchase`:

- A payment is keyed by its PaymentIntent id (the Checkout Session id if
  there was nothing to pay, after a 100% coupon). It buys its years from
  the later of now and the account's current `sync_until`, so buying early
  adds to what's left rather than overlapping it. Each payment is granted
  once, whichever of its events arrives and however often; each Stripe event
  id is applied once.
- A full refund or a dispute takes the purchase back. Web purchases stacked
  after it move back by the time it still had to run, then the usual refund
  rule falls back to what's left. A partial refund changes nothing; a dispute
  you win doesn't restore the time by itself (grant it by hand).
- Time bought on the web on top of an App Store or Play subscription that
  renews by itself starts after its current period, and the next renewal
  then overlaps it. Windows doesn't offer the web plans to such an account.

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
| `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/stripe-webhook` | Stripe → Developers → Webhooks, the endpoint URL |
| `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/stripe-checkout` | called by the Windows app; nothing to set up |

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
| `STRIPE_SECRET_KEY` | yes | a restricted key (`rk_live_…`) with **Checkout Sessions: write** and nothing else, or the secret key (`sk_live_…`); `sk_test_…`/`rk_test_…` in test mode. Its mode is also the mode `stripe-webhook` accepts events from |
| `STRIPE_PRICE_1Y` | yes | the 1-year Price id, `price_…` |
| `STRIPE_PRICE_3Y` | yes | the 3-year Price id, `price_…` |
| `STRIPE_SUCCESS_URL` | yes | where the browser goes after paying, e.g. `https://maybeitssoftware.co.uk/librelog/thanks` |
| `STRIPE_CANCEL_URL` | yes | where it goes if they back out, e.g. `https://maybeitssoftware.co.uk/librelog/sync` |
| `STRIPE_WEBHOOK_SECRET` | yes | the webhook endpoint's signing secret, `whsec_…` |

`play-rtdn`, `stripe-checkout` and `stripe-webhook` won't start without their
required secrets, so set them before deploying.

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
   button. Give review a second account without a grant too, so they can
   buy in the sandbox (see *Review accounts* below):
   > Sync is an optional yearly subscription (Settings → Sync →
   > Subscription). appreview@maybeitssoftware.co.uk already has sync
   > switched on. To see the purchase, sign in with
   > appreview-buy@maybeitssoftware.co.uk instead.

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

### 3. Stripe

1. **Account.** Sign up at <https://dashboard.stripe.com/register> as Maybe
   It's Software, then **Settings → Business → Account details** and
   **Activate payments**: business type, your details, the bank account for
   payouts. Under **Settings → Business → Public details** set the statement
   descriptor (e.g. `LIBRELOG SYNC`) and a support email; Checkout shows
   them. Everything below can be done in test mode first (the *Test mode*
   toggle); test and live mode each have their own Products, Prices,
   webhooks and keys, so repeat it in live mode.
2. **Stripe Tax.** **Settings → Tax**: set the origin address, the default
   tax behaviour **Inclusive** (EU consumer prices include VAT) and the
   default product tax code **Software as a service (SaaS) – personal use**
   (`txcd_10103000`). Then **Registrations → Add registration**: the UK if
   you're VAT-registered there, and the EU One Stop Shop (for a UK business,
   the **non-Union scheme**). Stripe only *calculates* and collects the tax:
   you register for EU VAT OSS yourself, with the tax authority of the one
   EU member state you choose, file the quarterly OSS return from Stripe's
   tax reports, and pay it. Stripe Tax has its own per-transaction fee (see
   Stripe's pricing page).
3. **Product and Prices.** **Product catalogue → Add product**: name
   `LibreLog Sync`, description `End-to-end encrypted sync across your
   devices, at cost.`, tax code as above. Add two prices, both **One-off**,
   EUR, tax behaviour **Inclusive**:
   - **€1.00**, description `1 year` → its id is `STRIPE_PRICE_1Y`.
   - **€3.00**, description `3 years` → its id is `STRIPE_PRICE_3Y`.

   The Windows buttons say €1 and €3 (`windows/…/SubscriptionStatus.cs`,
   `SyncPage.xaml`); change them there too if the prices change.
4. **Checkout.** **Settings → Branding**: icon, the paper `#faf8f4`
   background and the azure `#007fff` accent. **Settings → Customer
   emails**: turn on **Successful payments** (receipts) and **Refunds**.
5. **Keys.** **Developers → API keys → Create restricted key**: name
   `librelog-checkout`, **Checkout Sessions: Write**, everything else None.
   That's `STRIPE_SECRET_KEY`. (If `stripe-checkout` logs a permissions
   error from Stripe, the secret key `sk_live_…` works too.)
6. **Webhook.** **Developers → Webhooks → Add endpoint**:
   - Endpoint URL
     `https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/stripe-webhook`.
   - Events: `checkout.session.completed`,
     `checkout.session.async_payment_succeeded`, `charge.refunded`,
     `charge.dispute.created`.
   - After saving, **Signing secret → Reveal**: that's
     `STRIPE_WEBHOOK_SECRET`.
7. **Pages.** Publish the two pages `STRIPE_SUCCESS_URL` and
   `STRIPE_CANCEL_URL` point at (a line saying sync switches on within a
   minute and to go back to the app is enough), and the privacy policy
   should mention Stripe as the payment processor.
8. **Microsoft Store.** Partner Center's certification notes: say that sync
   is sold through the developer's own web payments, which the Microsoft
   Store Policies allow for non-game apps.

### 4. Supabase

Run from the repo root, signed in (`supabase login`) and linked
(`supabase link --project-ref wzkhuilypzapqkrjxeku`).

```bash
# Database: purchases, record_purchase(), sync_status(), record_stripe_purchase().
supabase/tests/run.sh
supabase db push

# Secrets. The JSON key goes through a private env file, not the command line.
umask 077
{
  printf 'PLAY_SERVICE_ACCOUNT_JSON=%s\n' "$(jq -c . ~/play-api.json)"
  printf 'PLAY_RTDN_AUDIENCE=%s\n' https://wzkhuilypzapqkrjxeku.supabase.co/functions/v1/play-rtdn
  printf 'PLAY_RTDN_SERVICE_ACCOUNT=%s\n' librelog-rtdn-push@<project>.iam.gserviceaccount.com
  printf 'STRIPE_SECRET_KEY=%s\n' rk_live_…
  printf 'STRIPE_PRICE_1Y=%s\n' price_…
  printf 'STRIPE_PRICE_3Y=%s\n' price_…
  printf 'STRIPE_SUCCESS_URL=%s\n' https://maybeitssoftware.co.uk/librelog/thanks
  printf 'STRIPE_CANCEL_URL=%s\n' https://maybeitssoftware.co.uk/librelog/sync
  printf 'STRIPE_WEBHOOK_SECRET=%s\n' whsec_…
} > /tmp/billing.env
supabase secrets set --env-file /tmp/billing.env --project-ref wzkhuilypzapqkrjxeku
rm /tmp/billing.env ~/play-api.json   # keep the key in your password manager instead

# Functions. verify_jwt = false comes from config.toml: each function checks
# its own caller (Apple's signature, Google's OIDC token, Stripe's signature,
# the user's session).
(cd supabase/functions && deno task test && deno task check)
supabase functions deploy appstore-notifications play-rtdn verify-purchase \
  stripe-checkout stripe-webhook --project-ref wzkhuilypzapqkrjxeku
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
- **Stripe, locally.** With the Stripe CLI (`brew install stripe/stripe-cli/stripe`,
  then `stripe login`) and test-mode values in `supabase/functions/.env.local`
  (`STRIPE_SECRET_KEY=sk_test_…`, the test Price ids, any success and cancel
  URLs, and the `whsec_…` that `stripe listen` prints):

  ```bash
  supabase start && supabase functions serve --env-file supabase/functions/.env.local
  stripe listen --forward-to http://127.0.0.1:54321/functions/v1/stripe-webhook \
    --events checkout.session.completed,checkout.session.async_payment_succeeded,charge.refunded,charge.dispute.created
  stripe trigger checkout.session.completed \
    --add checkout_session:client_reference_id=<a local user id> \
    --add checkout_session:metadata.user_id=<the same id> \
    --add checkout_session:metadata.years=1
  stripe trigger charge.refunded   # a refund of a payment that isn't ours: answered 200, ignored
  ```

  A bare `stripe trigger checkout.session.completed` arrives without an
  account and is answered 200 and ignored, which shows the signature check
  passed. `.env.local` is git-ignored; keep the keys there, not in `.env`.
- **Stripe, end to end.** In test mode, set the test-mode secrets on the
  project (`STRIPE_SECRET_KEY=sk_test_…` and the test webhook's
  `whsec_…`), sign in on Windows, click a plan and pay with card
  `4242 4242 4242 4242`, any future date and CVC; `3D Secure` with
  `4000 0027 6000 3184`. Refund it from the payment's page in the dashboard
  and the time goes. Switch the secrets back to live ones afterwards: a
  deployment only accepts events from its key's mode.
- **Google Play.** Play Console → Settings → **License testing**: add the
  testers' Google accounts, and add them to the internal testing track. Install
  from that track's opt-in link (sideloaded debug builds can't buy). Testers
  pay with test cards; a test year renews every 30 minutes, and refunds or
  revocations can be made from **Order management**.
- After each step, check `librelog.purchases` and the account's
  `sync_until` in the SQL editor, and the function logs in the dashboard.

## Windows and the web

The Microsoft Store lets non-game apps take their own payments with no
commission (SPEC.md §15.5), so Windows sells sync through Stripe Checkout.
Settings → Sync → Subscription shows the account's status and, unless a
store subscription already renews it, **3 years · €3** and **1 year · €1**:
each asks `stripe-checkout` for a Checkout Session and opens it in the
browser. When the LibreLog window comes back to the front, it asks for the
status again (twice, a few seconds apart, as the webhook can land just after
the browser's thank-you page). Sync bought in the iPhone, iPad, Mac or
Android app shows here too.

## Review accounts

`scripts/review-account.sh` makes both App Review accounts; neither is run
for you. It needs the Supabase CLI, logged in.

```bash
# 1. Sync on (granted by hand for 10 years): appreview@maybeitssoftware.co.uk.
scripts/review-account.sh
# 2. Sync off, to buy it in the sandbox: appreview-buy@maybeitssoftware.co.uk.
REVIEW_SYNC_YEARS=0 scripts/review-account.sh
```

Each prints its password once. Pass the current one as `REVIEW_PASSWORD`
when running it again, or the password App Review has stops working. Running
the second one again switches its sync off once more (clearing whatever it
bought), so each review starts from scratch. Sign in to each once on any
device to make its journal key, then give App Store Connect and Play Console
the email, password and recovery phrase of both.

Purchases made in the sandbox are recorded with `environment = 'sandbox'`,
and a sandbox year lasts an hour, so the second account lapses again by
itself soon after review.

## Granting sync by hand

Still works, and survives refunds of store purchases:

```sql
select librelog.admin_set_sync_until('someone@example.com', now() + interval '1 year');
```
