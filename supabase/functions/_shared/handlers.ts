// The three billing endpoints, with their outside world passed in so the
// tests can run them against fakes. Each function's index.ts wires up the
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
import { errorResponse, HttpError, json, type RecordPurchase } from "./purchases.ts";
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
