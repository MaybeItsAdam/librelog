// Stripe, over its REST API with fetch (no SDK): creating Checkout Sessions
// for the web plans, and checking the signature on Stripe's webhooks.
// docs/billing.md has the Stripe setup.

import { HttpError } from "./purchases.ts";

/** The web plans: years of sync for one payment (SPEC.md §15.4). */
export const PLAN_YEARS = [1, 3] as const;
export type PlanYears = typeof PLAN_YEARS[number];

export interface CheckoutPlans {
  /** Price ids, one-off, from the Stripe dashboard. */
  prices: Record<PlanYears, string>;
  successUrl: string;
  cancelUrl: string;
}

export interface StripeCheckoutConfig extends CheckoutPlans {
  secretKey: string;
}

function required(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`${name} must be set`);
  return value;
}

/** stripe-checkout's settings, from STRIPE_SECRET_KEY, STRIPE_PRICE_1Y,
 *  STRIPE_PRICE_3Y, STRIPE_SUCCESS_URL and STRIPE_CANCEL_URL. */
export function stripeCheckoutConfig(): StripeCheckoutConfig {
  return {
    secretKey: required("STRIPE_SECRET_KEY"),
    prices: { 1: required("STRIPE_PRICE_1Y"), 3: required("STRIPE_PRICE_3Y") },
    successUrl: required("STRIPE_SUCCESS_URL"),
    cancelUrl: required("STRIPE_CANCEL_URL"),
  };
}

/** Whether a secret or restricted key is a live-mode one. Webhook events
 *  from the other mode are ignored, so test purchases never grant sync on a
 *  live deployment, and the other way round. */
export function isLiveKey(key: string): boolean {
  return /^(sk|rk)_live_/.test(key);
}

/** The form body for POST /v1/checkout/sessions: one payment (not a
 *  subscription) for one plan, tied to the signed-in account, with Stripe
 *  Tax working out VAT from the buyer's address. */
export function checkoutParams(config: CheckoutPlans, userId: string, years: PlanYears): URLSearchParams {
  return new URLSearchParams({
    "mode": "payment",
    "line_items[0][price]": config.prices[years],
    "line_items[0][quantity]": "1",
    "client_reference_id": userId,
    "metadata[user_id]": userId,
    "metadata[years]": String(years),
    // On the payment too, so a refund in the dashboard shows whose it was.
    "payment_intent_data[metadata][user_id]": userId,
    "payment_intent_data[metadata][years]": String(years),
    "automatic_tax[enabled]": "true",
    "success_url": config.successUrl,
    "cancel_url": config.cancelUrl,
  });
}

export interface CheckoutSession {
  id: string;
  url: string;
}

export type CreateCheckoutSession = (params: URLSearchParams) => Promise<CheckoutSession>;

/** Creates a Checkout Session with Stripe's API. */
export function stripeCheckoutSessions(secretKey: string, fetcher: typeof fetch = fetch): CreateCheckoutSession {
  return async (params) => {
    let res: Response;
    try {
      res = await fetcher("https://api.stripe.com/v1/checkout/sessions", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${secretKey}`,
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: params.toString(),
      });
    } catch (e) {
      throw new HttpError(502, `Stripe unreachable: ${(e as Error).message}`);
    }
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new HttpError(502, `Stripe ${res.status}: ${body?.error?.message ?? "no message"}`);
    if (typeof body.id !== "string" || typeof body.url !== "string") {
      throw new HttpError(502, "Stripe returned a session without a URL");
    }
    return { id: body.id, url: body.url };
  };
}

// Webhook signatures ---------------------------------------------------------------

export class SignatureError extends Error {}

/** How far a webhook's timestamp may be from now, as Stripe's libraries
 *  allow by default. */
export const SIGNATURE_TOLERANCE_SECONDS = 300;

const encoder = new TextEncoder();

function hex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}

function equalInConstantTime(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Checks a `Stripe-Signature` header (`t=<unix time>,v1=<hex>,…`) against
 *  the raw request body: an HMAC-SHA256 of `<t>.<body>` keyed with the
 *  endpoint's signing secret (`whsec_…`), within the tolerance of now. Any
 *  v1 entry may match (Stripe sends one per secret while one is being
 *  rolled). Throws SignatureError otherwise. */
export async function verifyStripeSignature(
  header: string | null,
  payload: Uint8Array,
  secret: string,
  now = Date.now(),
  toleranceSeconds = SIGNATURE_TOLERANCE_SECONDS,
): Promise<void> {
  if (!header) throw new SignatureError("no Stripe-Signature header");
  let timestamp: number | undefined;
  const signatures: string[] = [];
  for (const part of header.split(",")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    const key = part.slice(0, eq).trim(), value = part.slice(eq + 1).trim();
    if (key === "t" && /^\d+$/.test(value)) timestamp = Number(value);
    else if (key === "v1") signatures.push(value.toLowerCase());
  }
  if (timestamp === undefined) throw new SignatureError("no timestamp in the signature");
  if (signatures.length === 0) throw new SignatureError("no v1 signature");
  if (Math.abs(now / 1000 - timestamp) > toleranceSeconds) {
    throw new SignatureError("signature timestamp outside the tolerance");
  }

  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const prefix = encoder.encode(`${timestamp}.`);
  const signed = new Uint8Array(prefix.length + payload.length);
  signed.set(prefix);
  signed.set(payload, prefix.length);
  const expected = hex(await crypto.subtle.sign("HMAC", key, signed));
  if (!signatures.some((s) => equalInConstantTime(s, expected))) throw new SignatureError("signature doesn't match");
}

/** Signs a payload as Stripe does, for tests and local tools. */
export async function signStripePayload(payload: string, secret: string, timestamp: number): Promise<string> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  return `t=${timestamp},v1=${hex(await crypto.subtle.sign("HMAC", key, encoder.encode(`${timestamp}.${payload}`)))}`;
}
