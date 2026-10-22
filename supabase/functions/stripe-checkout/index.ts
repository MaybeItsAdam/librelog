// Web checkout (SPEC.md §15.4–15.5, docs/billing.md): starts a Stripe
// Checkout Session for the signed-in account, one payment for 1 or 3 years
// of sync, and returns the page to open in the browser. stripe-webhook
// records the payment when it's made.
//
//   POST /functions/v1/stripe-checkout
//   Authorization: Bearer <user's access token>
//   {"years": 1}   or   {"years": 3}
//
// 200 {url, id}; 400 not a plan; 401 not signed in; 502 Stripe couldn't be
// reached.
//
// Env: STRIPE_SECRET_KEY, STRIPE_PRICE_1Y, STRIPE_PRICE_3Y (one-off Price
// ids), STRIPE_SUCCESS_URL, STRIPE_CANCEL_URL (where the browser goes after).

import { stripeCheckout } from "../_shared/handlers.ts";
import { requireUser } from "../_shared/purchases.ts";
import { stripeCheckoutConfig, stripeCheckoutSessions } from "../_shared/stripe.ts";

const config = stripeCheckoutConfig();

Deno.serve(stripeCheckout({
  user: requireUser,
  config,
  createSession: stripeCheckoutSessions(config.secretKey),
}));
