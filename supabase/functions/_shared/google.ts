// Google Play: authenticating Pub/Sub's push (an OIDC token Google signs),
// calling the Play Developer API as the service account, and turning a
// SubscriptionPurchaseV2 into a purchase state.

import { fromBase64, toBase64Url } from "./x509.ts";
import { HttpError, type PurchaseState } from "./purchases.ts";

// Pub/Sub push authentication --------------------------------------------------

export interface Jwk {
  kid: string;
  kty: string;
  n: string;
  e: string;
  alg?: string;
}

export type JwksSource = () => Promise<Jwk[]>;

const GOOGLE_ISSUERS = ["https://accounts.google.com", "accounts.google.com"];

let cachedJwks: { keys: Jwk[]; until: number } | undefined;

/** Google's OAuth signing keys, cached for as long as Google says. */
export const googleJwks: JwksSource = async () => {
  if (cachedJwks && cachedJwks.until > Date.now()) return cachedJwks.keys;
  const res = await fetch("https://www.googleapis.com/oauth2/v3/certs");
  if (!res.ok) throw new HttpError(502, `Google certs: ${res.status}`);
  const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get("Cache-Control") ?? "")?.[1] ?? 3600);
  const keys = (await res.json()).keys as Jwk[];
  cachedJwks = { keys, until: Date.now() + maxAge * 1000 };
  return keys;
};

export interface PushAuth {
  /** The push endpoint URL configured as the audience on the subscription. */
  audience: string;
  /** The service account Pub/Sub signs the token as. */
  serviceAccount: string;
}

/** Checks a Pub/Sub push request's `Authorization: Bearer <OIDC token>`.
 *  Throws HttpError 401 unless Google signed it for this audience as the
 *  expected service account. */
export async function verifyPushToken(
  authorization: string | null,
  expected: PushAuth,
  jwks: JwksSource = googleJwks,
  now = Date.now(),
): Promise<void> {
  const token = authorization?.startsWith("Bearer ") ? authorization.slice(7) : "";
  const parts = token.split(".");
  if (parts.length !== 3) throw new HttpError(401, "missing push token");
  let header: { alg?: string; kid?: string };
  let claims: Record<string, unknown>;
  try {
    header = JSON.parse(new TextDecoder().decode(fromBase64(parts[0])));
    claims = JSON.parse(new TextDecoder().decode(fromBase64(parts[1])));
  } catch {
    throw new HttpError(401, "malformed push token");
  }
  if (header.alg !== "RS256") throw new HttpError(401, "unexpected token algorithm");
  const jwk = (await jwks()).find((k) => k.kid === header.kid);
  if (!jwk) throw new HttpError(401, "unknown signing key");
  const key = await crypto.subtle.importKey(
    "jwk",
    { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    fromBase64(parts[2]) as Uint8Array<ArrayBuffer>,
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!ok) throw new HttpError(401, "bad push token signature");

  const skew = 60_000;
  if (!GOOGLE_ISSUERS.includes(claims.iss as string)) throw new HttpError(401, "wrong issuer");
  if (claims.aud !== expected.audience) throw new HttpError(401, "wrong audience");
  if (typeof claims.exp !== "number" || claims.exp * 1000 < now - skew) throw new HttpError(401, "token expired");
  if (typeof claims.iat === "number" && claims.iat * 1000 > now + skew) {
    throw new HttpError(401, "token from the future");
  }
  if (claims.email !== expected.serviceAccount || claims.email_verified !== true) {
    throw new HttpError(401, "wrong service account");
  }
}

// Play Developer API --------------------------------------------------------------

export interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

let cachedToken: { token: string; until: number } | undefined;

async function rsaSign(pem: string, input: string): Promise<Uint8Array> {
  const der = fromBase64(pem.replace(/-----[^-]+-----/g, ""));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der as Uint8Array<ArrayBuffer>,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(input)));
}

/** An OAuth access token for the Play Developer API, as the service account. */
export async function playAccessToken(account: ServiceAccount): Promise<string> {
  if (cachedToken && cachedToken.until > Date.now()) return cachedToken.token;
  const tokenUri = account.token_uri ?? "https://oauth2.googleapis.com/token";
  const now = Math.floor(Date.now() / 1000);
  const enc = (o: unknown) => toBase64Url(new TextEncoder().encode(JSON.stringify(o)));
  const input = `${enc({ alg: "RS256", typ: "JWT" })}.${
    enc({
      iss: account.client_email,
      scope: "https://www.googleapis.com/auth/androidpublisher",
      aud: tokenUri,
      iat: now,
      exp: now + 3600,
    })
  }`;
  const assertion = `${input}.${toBase64Url(await rsaSign(account.private_key, input))}`;
  const res = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!res.ok) throw new HttpError(502, `Google token: ${res.status} ${await res.text()}`);
  const body = await res.json();
  cachedToken = { token: body.access_token, until: Date.now() + (body.expires_in - 120) * 1000 };
  return body.access_token;
}

export interface PlayApi {
  getSubscription(token: string): Promise<SubscriptionPurchaseV2>;
  acknowledge(productId: string, token: string): Promise<void>;
}

export interface PlayConfig {
  packageName: string;
  productIds: string[];
}

export function playConfig(env: (name: string) => string | undefined = Deno.env.get): PlayConfig {
  return {
    packageName: env("PLAY_PACKAGE_NAME") ?? "uk.co.maybeitssoftware.librelog",
    productIds: (env("PLAY_PRODUCT_IDS") ?? "sync_yearly").split(",").map((s) => s.trim()).filter(Boolean),
  };
}

/** The real API, as the service account in PLAY_SERVICE_ACCOUNT_JSON. */
export function playApi(config: PlayConfig): PlayApi {
  const raw = Deno.env.get("PLAY_SERVICE_ACCOUNT_JSON");
  if (!raw) throw new Error("PLAY_SERVICE_ACCOUNT_JSON is not set");
  const account = JSON.parse(raw) as ServiceAccount;
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${
    encodeURIComponent(config.packageName)
  }/purchases`;
  const call = async (method: string, url: string) => {
    const res = await fetch(url, {
      method,
      headers: {
        Authorization: `Bearer ${await playAccessToken(account)}`,
        ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      },
      body: method === "POST" ? "{}" : undefined,
    });
    const text = await res.text();
    if (res.status === 404 || res.status === 410 || res.status === 400) {
      throw new HttpError(400, `Play doesn't know that purchase (${res.status})`);
    }
    if (!res.ok) throw new HttpError(502, `Play API ${res.status}: ${text}`);
    return text ? JSON.parse(text) : {};
  };
  return {
    getSubscription: (token) => call("GET", `${base}/subscriptionsv2/tokens/${encodeURIComponent(token)}`),
    acknowledge: async (productId, token) => {
      await call(
        "POST",
        `${base}/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(token)}:acknowledge`,
      );
    },
  };
}

// Subscription state ------------------------------------------------------------------

/** SubscriptionPurchaseV2, the fields used here. */
export interface SubscriptionPurchaseV2 {
  subscriptionState?: string;
  acknowledgementState?: string;
  linkedPurchaseToken?: string;
  testPurchase?: Record<string, never>;
  externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string };
  lineItems?: {
    productId: string;
    expiryTime?: string;
    autoRenewingPlan?: { autoRenewEnabled?: boolean };
  }[];
}

/** RTDN SubscriptionNotification types. */
export const SUBSCRIPTION_NOTIFICATION: Record<number, string> = {
  1: "RECOVERED",
  2: "RENEWED",
  3: "CANCELED",
  4: "PURCHASED",
  5: "ON_HOLD",
  6: "IN_GRACE_PERIOD",
  7: "RESTARTED",
  8: "PRICE_CHANGE_CONFIRMED",
  9: "DEFERRED",
  10: "PAUSED",
  11: "PAUSE_SCHEDULE_CHANGED",
  12: "REVOKED",
  13: "EXPIRED",
  19: "PRICE_CHANGE_UPDATED",
  20: "PENDING_PURCHASE_CANCELED",
};

/** What Play's current state for a purchase token means for the purchase.
 *  `override` is for a revocation or refund we were told about, which Play
 *  otherwise reports as plain EXPIRED. */
export function playState(
  token: string,
  sub: SubscriptionPurchaseV2,
  config: PlayConfig,
  override?: "refunded" | "revoked",
  now = Date.now(),
): PurchaseState & { needsAcknowledging: boolean } {
  const items = (sub.lineItems ?? []).filter((i) => config.productIds.includes(i.productId));
  if (items.length === 0) throw new HttpError(400, "not a sync subscription");
  const latest = items.reduce((a, b) => (Date.parse(b.expiryTime ?? "") > Date.parse(a.expiryTime ?? "") ? b : a));
  const expiry = latest.expiryTime ? new Date(latest.expiryTime) : null;

  let status: PurchaseState["status"];
  switch (sub.subscriptionState) {
    case "SUBSCRIPTION_STATE_ACTIVE":
    case "SUBSCRIPTION_STATE_CANCELED": // auto-renew off, paid to the end of the period
      status = expiry && expiry.getTime() > now ? "active" : "expired";
      break;
    case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD":
      status = "grace";
      break;
    case "SUBSCRIPTION_STATE_ON_HOLD":
      status = "billing_retry";
      break;
    case "SUBSCRIPTION_STATE_PAUSED":
      status = "paused";
      break;
    case "SUBSCRIPTION_STATE_PENDING":
      status = "pending";
      break;
    default: // EXPIRED, PENDING_PURCHASE_CANCELED, UNSPECIFIED
      status = "expired";
  }
  if (override) status = override;

  // Only states that pay for access carry an expiry; on hold, paused and
  // pending ones mustn't extend sync.
  const grants = status === "active" || status === "grace" || status === "expired" || override;
  return {
    store: "play",
    originalId: token,
    userId: sub.externalAccountIdentifiers?.obfuscatedExternalAccountId?.toLowerCase() ?? null,
    productId: latest.productId,
    environment: sub.testPurchase ? "sandbox" : "production",
    expiresAt: grants ? expiry : null,
    status,
    autoRenew: latest.autoRenewingPlan?.autoRenewEnabled === true,
    needsAcknowledging: sub.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING" &&
      (status === "active" || status === "grace"),
  };
}
