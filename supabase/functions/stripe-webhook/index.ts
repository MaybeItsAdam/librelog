// Stripe's webhook (SPEC.md §12.5, docs/billing.md): checks the
// Stripe-Signature header, then a paid Checkout Session buys its years of
// sync (from the later of now and the account's current sync_until), and a
// full refund or a dispute takes them back. Redelivered events and a
// payment's second "paid" event change nothing.
//
// Env: STRIPE_WEBHOOK_SECRET (the endpoint's signing secret, whsec_…),
// STRIPE_SECRET_KEY (only its mode is used: events from the other mode,
// test or live, are ignored).

import { stripeWebhook } from "../_shared/handlers.ts";
import { recordStripePurchase } from "../_shared/purchases.ts";
import { isLiveKey } from "../_shared/stripe.ts";

const secret = Deno.env.get("STRIPE_WEBHOOK_SECRET");
const key = Deno.env.get("STRIPE_SECRET_KEY");
if (!secret || !key) throw new Error("STRIPE_WEBHOOK_SECRET and STRIPE_SECRET_KEY must be set");

Deno.serve(stripeWebhook({
  secret,
  livemode: isLiveKey(key),
  record: recordStripePurchase,
}));
