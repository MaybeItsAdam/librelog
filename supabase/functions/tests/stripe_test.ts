// deno test supabase/functions/tests
//
// Stripe webhook signatures, the Checkout Session request, and the two
// Stripe endpoints against a faked Stripe and database.

import { stripeCheckout, stripeWebhook } from "../_shared/handlers.ts";
import { HttpError, type RecordEvent, type StripePurchase } from "../_shared/purchases.ts";
import {
  checkoutParams,
  isLiveKey,
  signStripePayload,
  stripeCheckoutSessions,
  verifyStripeSignature,
} from "../_shared/stripe.ts";

function assert(cond: unknown, message = "assertion failed"): asserts cond {
  if (!cond) throw new Error(message);
}
function assertEquals<T>(actual: T, expected: T, message = "") {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a !== e) throw new Error(`${message} expected ${e}, got ${a}`);
}
async function assertRejects(fn: () => Promise<unknown>, match?: RegExp) {
  try {
    await fn();
  } catch (e) {
    if (match && !match.test((e as Error).message)) throw new Error(`wrong error: ${(e as Error).message}`);
    return;
  }
  throw new Error("expected a rejection");
}

const SECRET = "whsec_test_secret";
const USER = "8a1b2c3d-0000-4000-8000-000000000001";
const NOW = Date.UTC(2026, 9, 22, 12, 0, 0);
const T = Math.floor(NOW / 1000);
const bytes = (s: string) => new TextEncoder().encode(s);

// Signatures -----------------------------------------------------------------------

Deno.test("a Stripe signature over the raw body verifies", async () => {
  const body = '{"id":"evt_1","object":"event"}';
  const header = await signStripePayload(body, SECRET, T);
  await verifyStripeSignature(header, bytes(body), SECRET, NOW);
  // Within the tolerance either side.
  await verifyStripeSignature(header, bytes(body), SECRET, NOW + 299_000);
  await verifyStripeSignature(header, bytes(body), SECRET, NOW - 299_000);
});

Deno.test("any v1 entry may match, and other schemes are skipped", async () => {
  // Computed independently: HMAC-SHA256("whsec_test_secret", "1700000000.{}").
  const expected = Array.from(
    new Uint8Array(
      await crypto.subtle.sign(
        "HMAC",
        await crypto.subtle.importKey("raw", bytes(SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]),
        bytes("1700000000.{}"),
      ),
    ),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
  // Extra schemes and a v1 for a rolled secret are skipped over.
  const header = `t=1700000000,v0=deadbeef,v1=${"0".repeat(64)},v1=${expected}`;
  await verifyStripeSignature(header, bytes("{}"), SECRET, 1700000000_000);
});

Deno.test("a bad, missing or tampered signature is refused", async () => {
  const body = '{"id":"evt_1"}';
  const header = await signStripePayload(body, SECRET, T);
  await assertRejects(() => verifyStripeSignature(header, bytes(body), "whsec_other", NOW), /doesn't match/);
  await assertRejects(() => verifyStripeSignature(header, bytes('{"id":"evt_2"}'), SECRET, NOW), /doesn't match/);
  await assertRejects(() => verifyStripeSignature(null, bytes(body), SECRET, NOW), /no Stripe-Signature/);
  await assertRejects(() => verifyStripeSignature(`t=${T}`, bytes(body), SECRET, NOW), /no v1/);
  await assertRejects(() => verifyStripeSignature("v1=abc", bytes(body), SECRET, NOW), /no timestamp/);
  // The signature binds the timestamp too.
  const moved = header.replace(`t=${T}`, `t=${T + 1}`);
  await assertRejects(() => verifyStripeSignature(moved, bytes(body), SECRET, NOW), /doesn't match/);
});

Deno.test("an expired or future signature is refused", async () => {
  const body = "{}";
  const header = await signStripePayload(body, SECRET, T);
  await assertRejects(() => verifyStripeSignature(header, bytes(body), SECRET, NOW + 301_000), /tolerance/);
  await assertRejects(() => verifyStripeSignature(header, bytes(body), SECRET, NOW - 301_000), /tolerance/);
});

// Checkout ---------------------------------------------------------------------------

const plans = {
  prices: { 1: "price_1y", 3: "price_3y" } as const,
  successUrl: "https://example.test/thanks",
  cancelUrl: "https://example.test/cancelled",
};

Deno.test("the Checkout Session is one tax-calculated payment tied to the account", () => {
  const p = checkoutParams(plans, USER, 3);
  assertEquals(Object.fromEntries(p), {
    "mode": "payment",
    "line_items[0][price]": "price_3y",
    "line_items[0][quantity]": "1",
    "client_reference_id": USER,
    "metadata[user_id]": USER,
    "metadata[years]": "3",
    "payment_intent_data[metadata][user_id]": USER,
    "payment_intent_data[metadata][years]": "3",
    "automatic_tax[enabled]": "true",
    "success_url": "https://example.test/thanks",
    "cancel_url": "https://example.test/cancelled",
  });
  assertEquals(checkoutParams(plans, USER, 1).get("line_items[0][price]"), "price_1y");
});

Deno.test("Checkout Sessions are created with the secret key, form-encoded", async () => {
  const seen: Request[] = [];
  const fake = (input: string | URL | Request, init?: RequestInit) => {
    seen.push(new Request(input, init));
    return Promise.resolve(Response.json({ id: "cs_1", url: "https://checkout.stripe.com/c/pay/cs_1" }));
  };
  const create = stripeCheckoutSessions("sk_test_abc", fake as typeof fetch);
  assertEquals(await create(checkoutParams(plans, USER, 1)), {
    id: "cs_1",
    url: "https://checkout.stripe.com/c/pay/cs_1",
  });
  assertEquals(seen[0].url, "https://api.stripe.com/v1/checkout/sessions");
  assertEquals(seen[0].headers.get("Authorization"), "Bearer sk_test_abc");
  assertEquals(seen[0].headers.get("Content-Type"), "application/x-www-form-urlencoded");
  assert((await seen[0].text()).includes("automatic_tax%5Benabled%5D=true"));

  const failing = stripeCheckoutSessions(
    "sk_test_abc",
    (() => Promise.resolve(Response.json({ error: { message: "No such price" } }, { status: 400 }))) as typeof fetch,
  );
  await assertRejects(() => failing(new URLSearchParams()), /Stripe 400: No such price/);
});

Deno.test("stripe-checkout returns the session URL for a signed-in plan", async () => {
  const made: URLSearchParams[] = [];
  const handler = stripeCheckout({
    user: () => Promise.resolve(USER),
    config: plans,
    createSession: (params) => {
      made.push(params);
      return Promise.resolve({ id: "cs_1", url: "https://checkout.stripe.com/c/pay/cs_1" });
    },
  });
  const post = (body: unknown) => new Request("https://example.test/", { method: "POST", body: JSON.stringify(body) });

  const res = await handler(post({ years: 3 }));
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { url: "https://checkout.stripe.com/c/pay/cs_1", id: "cs_1" });
  assertEquals(made[0].get("client_reference_id"), USER);
  assertEquals(made[0].get("line_items[0][price]"), "price_3y");

  assertEquals((await handler(post({ years: 2 }))).status, 400);
  assertEquals((await handler(post({ years: "1" }))).status, 400);
  assertEquals((await handler(new Request("https://example.test/"))).status, 405);
  assertEquals(made.length, 1);

  const signedOut = stripeCheckout({
    user: () => Promise.reject(new HttpError(401, "not signed in")),
    config: plans,
    createSession: () => Promise.reject(new Error("must not be called")),
  });
  assertEquals((await signedOut(post({ years: 1 }))).status, 401);
});

Deno.test("only live keys are live", () => {
  assert(isLiveKey("sk_live_abc") && isLiveKey("rk_live_abc"));
  assert(!isLiveKey("sk_test_abc") && !isLiveKey("rk_test_abc") && !isLiveKey(""));
});

// Webhook ----------------------------------------------------------------------------

/** A stand-in for record_stripe_purchase: applies each event id once and
 *  each payment once, and stacks years on the account as the SQL does. */
function fakeDatabase() {
  const events = new Set<string>();
  const purchases = new Map<string, StripePurchase>();
  const calls: { purchase: StripePurchase; event: RecordEvent }[] = [];
  let syncUntil = 0;
  const record = (p: StripePurchase, e: RecordEvent) => {
    calls.push({ purchase: p, event: e });
    if (e.id && events.has(e.id)) return Promise.resolve(new Date(syncUntil).toISOString());
    if (e.id) events.add(e.id);
    const existing = purchases.get(p.originalId);
    if (p.status === "active") {
      if (!existing) {
        purchases.set(p.originalId, p);
        syncUntil = new Date(Math.max(NOW, syncUntil)).setUTCFullYear(
          new Date(Math.max(NOW, syncUntil)).getUTCFullYear() + p.years!,
        );
      }
    } else {
      if (!existing) return Promise.reject(new HttpError(404, "no such purchase"));
      if (existing.status === "active") {
        existing.status = p.status;
        syncUntil = NOW;
      }
    }
    return Promise.resolve(new Date(syncUntil).toISOString());
  };
  return { calls, record, purchases, until: () => new Date(syncUntil).toISOString() };
}

function event(type: string, object: Record<string, unknown>, over: Record<string, unknown> = {}) {
  return { id: `evt_${type}`, object: "event", type, created: T, livemode: false, data: { object }, ...over };
}

const session = (over: Record<string, unknown> = {}) => ({
  id: "cs_test_1",
  object: "checkout.session",
  mode: "payment",
  payment_status: "paid",
  payment_intent: "pi_1",
  client_reference_id: USER,
  metadata: { user_id: USER, years: "1" },
  ...over,
});

async function deliver(handler: (req: Request) => Promise<Response>, body: unknown, opts: { secret?: string } = {}) {
  const raw = JSON.stringify(body);
  const res = await handler(
    new Request("https://example.test/", {
      method: "POST",
      body: raw,
      headers: { "Stripe-Signature": await signStripePayload(raw, opts.secret ?? SECRET, T) },
    }),
  );
  return { status: res.status, body: await res.json() };
}

function webhook(db = fakeDatabase(), livemode = false) {
  return { db, handler: stripeWebhook({ secret: SECRET, livemode, record: db.record, now: () => new Date(NOW) }) };
}

Deno.test("stripe-webhook buys a year for a paid session, once", async () => {
  const { db, handler } = webhook();
  const paid = event("checkout.session.completed", session());
  const first = await deliver(handler, paid);
  assertEquals(first.status, 200);
  assertEquals(db.calls[0].purchase, {
    originalId: "pi_1",
    userId: USER,
    productId: "sync_1y",
    environment: "sandbox",
    years: 1,
    status: "active",
  });
  assertEquals(db.calls[0].event, { event: "checkout.session.completed", at: new Date(T * 1000), id: paid.id });
  assertEquals(first.body.sync_until, "2027-10-22T12:00:00.000Z");

  // Redelivered, and the same payment under another event: nothing more.
  assertEquals((await deliver(handler, paid)).body.sync_until, "2027-10-22T12:00:00.000Z");
  const again = event("checkout.session.async_payment_succeeded", session());
  assertEquals((await deliver(handler, again)).body.sync_until, "2027-10-22T12:00:00.000Z");

  // A second purchase stacks three years on.
  const more = event("checkout.session.completed", session({ payment_intent: "pi_2", metadata: { years: "3" } }), {
    id: "evt_more",
  });
  assertEquals((await deliver(handler, more)).body.sync_until, "2030-10-22T12:00:00.000Z");
  assertEquals(db.calls.at(-1)!.purchase.productId, "sync_3y");
});

Deno.test("stripe-webhook waits for delayed payments and ignores what isn't a plan", async () => {
  const { db, handler } = webhook();
  const ignored = async (body: unknown) => {
    const r = await deliver(handler, body);
    assertEquals(r.status, 200);
    assert(r.body.ignored, `expected ignored: ${JSON.stringify(r.body)}`);
  };
  await ignored(event("checkout.session.completed", session({ payment_status: "unpaid" })));
  await ignored(event("checkout.session.completed", session({ mode: "subscription" })));
  await ignored(event("checkout.session.completed", session({ client_reference_id: null, metadata: {} })));
  await ignored(event("checkout.session.completed", session({ metadata: { years: "2" } })));
  await ignored(event("checkout.session.completed", session(), { livemode: true }));
  await ignored(event("customer.created", {}));
  await ignored(event("charge.refunded", { payment_intent: "pi_1", refunded: false, amount_refunded: 10 }));
  assertEquals(db.calls.length, 0);

  // Once the delayed payment clears it counts, with an expanded payment
  // intent and no PaymentIntent at all (a 100% discount) both handled.
  await deliver(
    handler,
    event("checkout.session.async_payment_succeeded", session({ payment_intent: { id: "pi_9" } })),
  );
  assertEquals(db.calls[0].purchase.originalId, "pi_9");
  await deliver(
    handler,
    event("checkout.session.completed", session({ payment_status: "no_payment_required", payment_intent: null }), {
      id: "evt_free",
    }),
  );
  assertEquals(db.calls[1].purchase.originalId, "cs_test_1");
});

Deno.test("stripe-webhook takes a purchase back on a full refund or a dispute", async () => {
  const { db, handler } = webhook();
  await deliver(handler, event("checkout.session.completed", session()));

  const refund = await deliver(
    handler,
    event("charge.refunded", { id: "ch_1", payment_intent: "pi_1", refunded: true }),
  );
  assertEquals(refund.status, 200);
  assertEquals(db.calls[1].purchase, {
    originalId: "pi_1",
    userId: null,
    productId: null,
    environment: "sandbox",
    years: null,
    status: "refunded",
  });
  assertEquals(refund.body.sync_until, new Date(NOW).toISOString());

  await deliver(handler, event("charge.dispute.created", { id: "dp_1", charge: "ch_1", payment_intent: "pi_1" }));
  assertEquals(db.calls[2].purchase.status, "revoked");

  // A refund for a payment that isn't a LibreLog purchase is answered 200 so
  // Stripe stops retrying.
  const other = await deliver(
    handler,
    event("charge.refunded", { payment_intent: "pi_other", refunded: true }, { id: "evt_other" }),
  );
  assertEquals([other.status, other.body.ignored], [200, "no such purchase"]);
});

Deno.test("stripe-webhook refuses unsigned or wrongly signed events", async () => {
  const { db, handler } = webhook();
  const body = event("checkout.session.completed", session());
  assertEquals((await deliver(handler, body, { secret: "whsec_wrong" })).status, 400);
  const unsigned = await handler(new Request("https://example.test/", { method: "POST", body: JSON.stringify(body) }));
  assertEquals(unsigned.status, 400);
  assertEquals(db.calls.length, 0);

  // A database failure is a 500, so Stripe retries.
  const broken = stripeWebhook({
    secret: SECRET,
    livemode: false,
    record: () => Promise.reject(new Error("connection reset")),
    now: () => new Date(NOW),
  });
  assertEquals((await deliver(broken, body)).status, 500);
});

Deno.test("a live deployment takes live events only", async () => {
  const { db, handler } = webhook(fakeDatabase(), true);
  const live = await deliver(handler, event("checkout.session.completed", session(), { livemode: true }));
  assertEquals(live.status, 200);
  assertEquals(db.calls[0].purchase.environment, "production");
  const test = await deliver(handler, event("checkout.session.completed", session(), { id: "evt_t" }));
  assertEquals(test.body.ignored, "test-mode event");
});
