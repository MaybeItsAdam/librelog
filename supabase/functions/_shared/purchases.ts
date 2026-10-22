// Talking to the database and to callers: record_purchase() and
// record_stripe_purchase() through PostgREST with the service role, the
// signed-in user behind a request, and JSON responses.

export type PurchaseStatus =
  | "active"
  | "grace"
  | "billing_retry"
  | "paused"
  | "pending"
  | "expired"
  | "refunded"
  | "revoked";

/** One verified store state for a purchase, as record_purchase() takes it. */
export interface PurchaseState {
  store: "app_store" | "play";
  /** App Store originalTransactionId, or the Play purchase token. */
  originalId: string;
  /** Who the store says bought it: appAccountToken / obfuscatedExternalAccountId. */
  userId: string | null;
  productId: string;
  environment: "production" | "sandbox";
  expiresAt: Date | null;
  status: PurchaseStatus;
  autoRenew: boolean;
}

export interface RecordEvent {
  /** Store notification type, or VERIFY when an app reports a purchase. */
  event: string;
  /** When the store signed this state; older than what's on file is ignored. */
  at: Date;
  /** A notification's own id, so a redelivery is a no-op. */
  id?: string;
}

export type RecordPurchase = (state: PurchaseState, event: RecordEvent) => Promise<string | null>;

/** An HTTP error with the status the caller should see. */
export class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function serviceKey(): string {
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!key) throw new Error("SUPABASE_SERVICE_ROLE_KEY is not set");
  return key;
}

function supabaseUrl(): string {
  const url = Deno.env.get("SUPABASE_URL");
  if (!url) throw new Error("SUPABASE_URL is not set");
  return url.replace(/\/$/, "");
}

/** Calls a librelog function with the service role and returns its result.
 *  PT404 / PT409 from the function come through as 404 / 409. */
async function serviceRpc<T>(name: string, args: Record<string, unknown>): Promise<T> {
  const key = serviceKey();
  const res = await fetch(`${supabaseUrl()}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: {
      apikey: key,
      ...(key.startsWith("eyJ") ? { Authorization: `Bearer ${key}` } : {}),
      "Content-Type": "application/json",
      "Content-Profile": "librelog",
    },
    body: JSON.stringify(args),
  });
  const body = await res.text();
  if (!res.ok) {
    let message = body;
    try {
      message = JSON.parse(body).message ?? body;
    } catch { /* not JSON */ }
    throw new HttpError(res.status === 404 || res.status === 409 ? res.status : 500, message);
  }
  return JSON.parse(body) as T;
}

/** Calls librelog.record_purchase and returns the account's sync_until. */
export const recordPurchase: RecordPurchase = (state, event) =>
  serviceRpc<string | null>("record_purchase", {
    p_store: state.store,
    p_original_id: state.originalId,
    p_user: state.userId,
    p_product_id: state.productId,
    p_environment: state.environment,
    p_expires_at: state.expiresAt?.toISOString() ?? null,
    p_status: state.status,
    p_auto_renew: state.autoRenew,
    p_event: event.event,
    p_event_at: event.at.toISOString(),
    p_event_id: event.id ?? null,
  });

/** A web purchase, or the taking back of one, as record_stripe_purchase()
 *  takes it. A purchase carries no expiry of its own: it buys `years` from
 *  the later of now and the account's current sync_until. */
export interface StripePurchase {
  /** The PaymentIntent id, or the Checkout Session id if nothing was paid. */
  originalId: string;
  /** From the session's client_reference_id; null for a refund or dispute. */
  userId: string | null;
  /** sync_1y, sync_3y; null for a refund or dispute. */
  productId: string | null;
  environment: "production" | "sandbox";
  years: number | null;
  status: "active" | "refunded" | "revoked";
}

export type RecordStripe = (purchase: StripePurchase, event: RecordEvent) => Promise<string | null>;

/** Calls librelog.record_stripe_purchase and returns the account's sync_until. */
export const recordStripePurchase: RecordStripe = (p, event) =>
  serviceRpc<string | null>("record_stripe_purchase", {
    p_original_id: p.originalId,
    p_user: p.userId,
    p_product_id: p.productId,
    p_environment: p.environment,
    p_years: p.years,
    p_status: p.status,
    p_event: event.event,
    p_event_at: event.at.toISOString(),
    p_event_id: event.id ?? null,
  });

/** The signed-in user's id, from the request's bearer token. */
export async function requireUser(req: Request): Promise<string> {
  const auth = req.headers.get("Authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!token) throw new HttpError(401, "not signed in");
  const res = await fetch(`${supabaseUrl()}/auth/v1/user`, {
    headers: { apikey: Deno.env.get("SUPABASE_ANON_KEY") ?? serviceKey(), Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new HttpError(401, "not signed in");
  const user = await res.json();
  if (typeof user?.id !== "string") throw new HttpError(401, "not signed in");
  return user.id.toLowerCase();
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** Turns a thrown error into a response, logging the unexpected ones. */
export function errorResponse(e: unknown, context: string): Response {
  if (e instanceof HttpError) {
    if (e.status >= 500) console.error(context, e.message);
    return json(e.status, { error: e.message });
  }
  console.error(context, e);
  return json(500, { error: "internal error" });
}
