// The billing endpoints, with their outside world passed in so the tests
// can run them against fakes. Each function's index.ts wires up the
// real one.

import {
  type AppleConfig,
  type AppleNotification,
  type AppleRenewalInfo,
  appleState,
  type AppleTransaction,
  checkTransaction,
  IGNORED_NOTIFICATIONS,
  normaliseUuid,
  VerificationError,
} from "./apple.ts";
import {
  type JwksSource,
  type PlayApi,
  type PlayConfig,
  playState,
  type PushAuth,
  SUBSCRIPTION_NOTIFICATION,
  verifyPushToken,
} from "./google.ts";
import {
  errorResponse,
  HttpError,
  json,
  type RecordPurchase,
  type RecordStripe,
  type StripePurchase,
} from "./purchases.ts";
import {
  checkoutParams,
  type CheckoutPlans,
  type CreateCheckoutSession,
  PLAN_YEARS,
  type PlanYears,
  SignatureError,
  verifyStripeSignature,
} from "./stripe.ts";
import { fromBase64 } from "./x509.ts";

type Verify = <T>(jws: string) => Promise<T>;
type Handler = (req: Request) => Promise<Response>;

async function verifyOr400<T>(verify: Verify, jws: unknown): Promise<T> {
  if (typeof jws !== "string") throw new HttpError(400, "missing signed data");
  try {
    return await verify<T>(jws);
  } catch (e) {
    if (e instanceof VerificationError) throw new HttpError(400, `not verified: ${e.message}`);
    throw e;
  }
}

/** Records a store event, treating "no such account" and "another account's
 *  purchase" as handled: the store retries anything but a 2xx, and retrying
 *  won't change either answer. */
async function recordNotification(
  record: RecordPurchase,
  ...args: Parameters<RecordPurchase>
): Promise<Response | undefined> {
  try {
    await record(...args);
  } catch (e) {
    if (e instanceof HttpError && (e.status === 404 || e.status === 409)) {
      console.warn("billing event not applied", args[1].event, args[0].store, e.message);
      return json(200, { ignored: e.message });
    }
    throw e;
  }
}

// App Store Server Notifications v2 -------------------------------------------------

export function appStoreNotifications(deps: {
  verify: Verify;
  record: RecordPurchase;
  config: AppleConfig;
}): Handler {
  return async (req) => {
    try {
      if (req.method !== "POST") throw new HttpError(405, "POST only");
      const body = await req.json().catch(() => ({}));
      const n = await verifyOr400<AppleNotification>(deps.verify, body.signedPayload);
      const type = n.subtype ? `${n.notificationType}.${n.subtype}` : n.notificationType;

      if (IGNORED_NOTIFICATIONS.has(n.notificationType) || !n.data?.signedTransactionInfo) {
        return json(200, { ignored: type });
      }
      if (n.data.bundleId && n.data.bundleId !== deps.config.bundleId) {
        return json(200, { ignored: `bundle ${n.data.bundleId}` });
      }

      const tx = await verifyOr400<AppleTransaction>(deps.verify, n.data.signedTransactionInfo);
      const renewal = n.data.signedRenewalInfo
        ? await verifyOr400<AppleRenewalInfo>(deps.verify, n.data.signedRenewalInfo)
        : undefined;
      try {
        checkTransaction(tx, deps.config);
      } catch (e) {
        if (e instanceof VerificationError) return json(200, { ignored: e.message });
        throw e;
      }

      const state = appleState(tx, { renewal, statusCode: n.data.status, notificationType: n.notificationType });
      const handled = await recordNotification(deps.record, state, {
        event: type,
        at: new Date(n.signedDate),
        id: n.notificationUUID,
      });
      return handled ?? json(200, { ok: true });
    } catch (e) {
      return errorResponse(e, "appstore-notifications");
    }
  };
}

// Google Play real-time developer notifications (Pub/Sub push) --------------------------

const PRODUCT_TYPE_SUBSCRIPTION = 1;

export function playRtdn(deps: {
  auth: PushAuth;
  jwks?: JwksSource;
  api: () => PlayApi;
  record: RecordPurchase;
  config: PlayConfig;
  now?: () => Date;
}): Handler {
  return async (req) => {
    try {
      if (req.method !== "POST") throw new HttpError(405, "POST only");
      await verifyPushToken(req.headers.get("Authorization"), deps.auth, deps.jwks);
      const body = await req.json().catch(() => ({}));
      const message = body?.message;
      if (typeof message?.data !== "string") throw new HttpError(400, "not a Pub/Sub push");
      // deno-lint-ignore no-explicit-any
      let note: Record<string, any>;
      try {
        note = JSON.parse(new TextDecoder().decode(fromBase64(message.data)));
      } catch {
        throw new HttpError(400, "unreadable notification");
      }
      if (note.packageName !== deps.config.packageName) return json(200, { ignored: `package ${note.packageName}` });

      let token: string;
      let event: string;
      let override: "refunded" | "revoked" | undefined;
      if (note.subscriptionNotification) {
        token = note.subscriptionNotification.purchaseToken;
        const kind = note.subscriptionNotification.notificationType;
        event = SUBSCRIPTION_NOTIFICATION[kind] ?? `TYPE_${kind}`;
        if (kind === 12) override = "revoked";
      } else if (note.voidedPurchaseNotification?.productType === PRODUCT_TYPE_SUBSCRIPTION) {
        token = note.voidedPurchaseNotification.purchaseToken;
        event = "VOIDED";
        override = "refunded";
      } else {
        // testNotification, one-time products.
        return json(200, { ignored: Object.keys(note).find((k) => k.endsWith("Notification")) ?? "unknown" });
      }
      if (typeof token !== "string" || !token) throw new HttpError(400, "no purchase token");

      // Play's current state, not the notification's, is what counts.
      const api = deps.api();
      const sub = await api.getSubscription(token);
      let state;
      try {
        state = playState(token, sub, deps.config, override, (deps.now?.() ?? new Date()).getTime());
      } catch (e) {
        if (e instanceof HttpError && e.status === 400) return json(200, { ignored: e.message });
        throw e;
      }
      const handled = await recordNotification(deps.record, state, {
        event,
        at: deps.now?.() ?? new Date(),
        id: typeof message.messageId === "string" ? `${message.messageId}` : undefined,
      });
      if (handled) return handled;
      // Unacknowledged purchases are refunded after three days. The app
      // usually reports it first; this catches the ones it didn't.
      if (state.needsAcknowledging) await api.acknowledge(state.productId, token);
      return json(200, { ok: true });
    } catch (e) {
      return errorResponse(e, "play-rtdn");
    }
  };
}

// The apps reporting a fresh purchase -----------------------------------------------------

export function verifyPurchase(deps: {
  user: (req: Request) => Promise<string>;
  verify: Verify;
  appleConfig: AppleConfig;
  playApi: () => PlayApi;
  playConfig: PlayConfig;
  record: RecordPurchase;
  now?: () => Date;
}): Handler {
  return async (req) => {
    try {
      if (req.method !== "POST") throw new HttpError(405, "POST only");
      const user = await deps.user(req);
      const body = await req.json().catch(() => ({}));

      if (body.store === "app_store") {
        const tx = await verifyOr400<AppleTransaction>(deps.verify, body.signed_transaction);
        try {
          checkTransaction(tx, deps.appleConfig);
        } catch (e) {
          if (e instanceof VerificationError) throw new HttpError(400, e.message);
          throw e;
        }
        const state = appleState(tx, { now: deps.now?.() });
        // Purchases from the app carry the account as appAccountToken.
        if (state.userId && state.userId !== user) {
          throw new HttpError(403, "this purchase was made for another account");
        }
        state.userId = user;
        const syncUntil = await deps.record(state, { event: "VERIFY", at: new Date(tx.signedDate) });
        return json(200, {
          sync_until: syncUntil,
          status: state.status,
          expires_at: state.expiresAt?.toISOString() ?? null,
          acknowledged: false,
        });
      }

      if (body.store === "play") {
        const token = body.purchase_token;
        if (typeof token !== "string" || !token) throw new HttpError(400, "missing purchase_token");
        const api = deps.playApi();
        const sub = await api.getSubscription(token);
        const now = deps.now?.() ?? new Date();
        const state = playState(token, sub, deps.playConfig, undefined, now.getTime());
        if (state.userId && normaliseUuid(state.userId) !== user) {
          throw new HttpError(403, "this purchase was made for another account");
        }
        state.userId = user;
        const syncUntil = await deps.record(state, { event: "VERIFY", at: now });
        let acknowledged = sub.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED";
        if (state.needsAcknowledging) {
          await api.acknowledge(state.productId, token);
          acknowledged = true;
        }
        return json(200, {
          sync_until: syncUntil,
          status: state.status,
          expires_at: state.expiresAt?.toISOString() ?? null,
          acknowledged,
        });
      }

      throw new HttpError(400, "store must be app_store or play");
    } catch (e) {
      return errorResponse(e, "verify-purchase");
    }
  };
}

// Web checkout through Stripe ----------------------------------------------------------

/** Starts a Checkout Session for the signed-in account and returns its URL
 *  for the browser. */
export function stripeCheckout(deps: {
  user: (req: Request) => Promise<string>;
  config: CheckoutPlans;
  createSession: CreateCheckoutSession;
}): Handler {
  return async (req) => {
    try {
      if (req.method !== "POST") throw new HttpError(405, "POST only");
      const user = await deps.user(req);
      const body = await req.json().catch(() => ({}));
      const years = body?.years;
      if (!PLAN_YEARS.includes(years)) throw new HttpError(400, `years must be ${PLAN_YEARS.join(" or ")}`);
      const session = await deps.createSession(checkoutParams(deps.config, user, years as PlanYears));
      return json(200, { url: session.url, id: session.id });
    } catch (e) {
      return errorResponse(e, "stripe-checkout");
    }
  };
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** An id field that Stripe sends as a string, or as the object when expanded. */
function stripeId(value: unknown): string | undefined {
  if (typeof value === "string" && value) return value;
  if (value && typeof value === "object" && typeof (value as { id?: unknown }).id === "string") {
    return (value as { id: string }).id;
  }
}

/** Stripe's webhook: a paid Checkout Session buys sync, a full refund or a
 *  dispute takes it back. Each event once (by its id), each payment once. */
export function stripeWebhook(deps: {
  secret: string;
  /** Whether this deployment runs on live keys; other-mode events are ignored. */
  livemode: boolean;
  record: RecordStripe;
  now?: () => Date;
}): Handler {
  return async (req) => {
    try {
      if (req.method !== "POST") throw new HttpError(405, "POST only");
      const payload = new Uint8Array(await req.arrayBuffer());
      try {
        await verifyStripeSignature(
          req.headers.get("Stripe-Signature"),
          payload,
          deps.secret,
          (deps.now?.() ?? new Date()).getTime(),
        );
      } catch (e) {
        if (e instanceof SignatureError) throw new HttpError(400, `not verified: ${e.message}`);
        throw e;
      }
      // deno-lint-ignore no-explicit-any
      let event: Record<string, any>;
      try {
        event = JSON.parse(new TextDecoder().decode(payload));
      } catch {
        throw new HttpError(400, "unreadable event");
      }
      if (typeof event?.id !== "string" || typeof event.type !== "string") throw new HttpError(400, "not an event");
      if (event.livemode !== deps.livemode) {
        return json(200, { ignored: `${event.livemode ? "live" : "test"}-mode event` });
      }

      const object = event.data?.object ?? {};
      const environment = deps.livemode ? "production" : "sandbox";
      let purchase: StripePurchase;
      switch (event.type) {
        case "checkout.session.completed":
        case "checkout.session.async_payment_succeeded": {
          if (object.mode !== "payment") return json(200, { ignored: `mode ${object.mode}` });
          // Bank debits and the like complete unpaid, then succeed later.
          if (object.payment_status !== "paid" && object.payment_status !== "no_payment_required") {
            return json(200, { ignored: `payment ${object.payment_status}` });
          }
          const user = object.client_reference_id ?? object.metadata?.user_id;
          const years = Number(object.metadata?.years);
          // Sessions not made by stripe-checkout (a payment link, say).
          if (typeof user !== "string" || !UUID.test(user)) return json(200, { ignored: "no LibreLog account" });
          if (!PLAN_YEARS.includes(years as PlanYears)) {
            return json(200, { ignored: `years ${object.metadata?.years}` });
          }
          purchase = {
            originalId: stripeId(object.payment_intent) ?? object.id,
            userId: user.toLowerCase(),
            productId: `sync_${years}y`,
            environment,
            years,
            status: "active",
          };
          break;
        }
        case "charge.refunded": {
          // A partial refund leaves the purchase standing.
          if (object.refunded !== true) return json(200, { ignored: "partial refund" });
          const id = stripeId(object.payment_intent);
          if (!id) return json(200, { ignored: "no payment" });
          purchase = { originalId: id, userId: null, productId: null, environment, years: null, status: "refunded" };
          break;
        }
        case "charge.dispute.created": {
          const id = stripeId(object.payment_intent);
          if (!id) return json(200, { ignored: "no payment" });
          purchase = { originalId: id, userId: null, productId: null, environment, years: null, status: "revoked" };
          break;
        }
        default:
          return json(200, { ignored: event.type });
      }

      try {
        const syncUntil = await deps.record(purchase, {
          event: event.type,
          at: typeof event.created === "number" ? new Date(event.created * 1000) : (deps.now?.() ?? new Date()),
          id: event.id,
        });
        return json(200, { ok: true, sync_until: syncUntil });
      } catch (e) {
        // An account deleted since, a payment that isn't ours: retrying
        // won't change the answer, and Stripe retries anything but a 2xx.
        if (e instanceof HttpError && (e.status === 404 || e.status === 409)) {
          console.warn("stripe event not applied", event.type, event.id, e.message);
          return json(200, { ignored: e.message });
        }
        throw e;
      }
    } catch (e) {
      return errorResponse(e, "stripe-webhook");
    }
  };
}
