// App Store signed data (JWS with an x5c certificate chain): App Store Server
// Notifications v2 and StoreKit 2 transactions. Verified the way Apple's own
// App Store Server Library does it, offline:
//
//   1. the chain is exactly leaf → intermediate → Apple Root CA - G3, with the
//      root byte-for-byte the one pinned below;
//   2. each certificate is signed by the next and was valid when the payload
//      was signed;
//   3. the intermediate carries Apple's WWDR marker OID and the leaf the App
//      Store receipt-signing one;
//   4. the JWS itself is ES256-signed by the leaf.

import { type Certificate, equalBytes, fromBase64, importEcKey, parseCertificate, signedBy } from "./x509.ts";
import type { PurchaseState } from "./purchases.ts";

/** Apple Root CA - G3, from https://www.apple.com/certificateauthority/.
 *  SHA-256 63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79 */
export const APPLE_ROOT_CA_G3 = fromBase64(
  "MIICQzCCAcmgAwIBAgIILcX8iNLFS5UwCgYIKoZIzj0EAwMwZzEbMBkGA1UEAwwSQXBwbGUgUm9v" +
    "dCBDQSAtIEczMSYwJAYDVQQLDB1BcHBsZSBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eTETMBEGA1UE" +
    "CgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwHhcNMTQwNDMwMTgxOTA2WhcNMzkwNDMwMTgxOTA2" +
    "WjBnMRswGQYDVQQDDBJBcHBsZSBSb290IENBIC0gRzMxJjAkBgNVBAsMHUFwcGxlIENlcnRpZmlj" +
    "YXRpb24gQXV0aG9yaXR5MRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzB2MBAGByqG" +
    "SM49AgEGBSuBBAAiA2IABJjpLz1AcqTtkyJygRMc3RCV8cWjTnHcFBbZDuWmBSp3ZHtfTjjTuxxE" +
    "tX/1H7YyYl3J6YRbTzBPEVoA/VhYDKX1DyxNB0cTddqXl5dvMVztK517IDvYuVTZXpmkOlEKMaNC" +
    "MEAwHQYDVR0OBBYEFLuw3qFYM4iapIqZ3r6966/ayySrMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0P" +
    "AQH/BAQDAgEGMAoGCCqGSM49BAMDA2gAMGUCMQCD6cHEFl4aXTQY2e3v9GwOAEZLuN+yRhHFD/3m" +
    "eoyhpmvOwgPUnPWTxnS4at+qIxUCMG1mihDK1A3UT82NQz60imOlM27jbdoXt2QfyFMm+YhidDkL" +
    "F1vLUagM6BgD56KyKA==",
);

/** Marks Apple's WWDR intermediate. */
export const OID_WWDR_INTERMEDIATE = "1.2.840.113635.100.6.2.1";
/** Marks the App Store's receipt-signing leaf. */
export const OID_APP_STORE_SIGNING = "1.2.840.113635.100.6.11.1";

export class VerificationError extends Error {}

export interface VerifyOptions {
  /** Trusted roots; Apple Root CA - G3 unless a test swaps it. */
  roots?: Uint8Array[];
  /** When to check the certificates' validity. Defaults to the payload's
   *  `signedDate`, as Apple's library does without online checks, so a
   *  notification retried after a certificate rolled over still verifies. */
  at?: Date;
}

/** Verifies an App Store JWS and returns its payload. Throws
 *  VerificationError for anything that isn't Apple-signed. */
export async function verifyAppleJws<T = Record<string, unknown>>(
  jws: string,
  options: VerifyOptions = {},
): Promise<T> {
  const parts = jws.split(".");
  if (parts.length !== 3) throw new VerificationError("not a compact JWS");
  const [h, p, s] = parts;
  let header: { alg?: string; x5c?: string[] };
  let payload: Record<string, unknown>;
  try {
    header = JSON.parse(new TextDecoder().decode(fromBase64(h)));
    payload = JSON.parse(new TextDecoder().decode(fromBase64(p)));
  } catch {
    throw new VerificationError("malformed JWS");
  }
  if (header.alg !== "ES256") throw new VerificationError(`unexpected alg ${header.alg}`);
  if (!Array.isArray(header.x5c) || header.x5c.length !== 3) {
    throw new VerificationError("x5c must hold leaf, intermediate and root");
  }

  let chain: Certificate[];
  try {
    chain = header.x5c.map((c) => parseCertificate(fromBase64(c)));
  } catch (e) {
    throw new VerificationError(`unreadable certificate: ${(e as Error).message}`);
  }
  const [leaf, intermediate, root] = chain;

  const roots = options.roots ?? [APPLE_ROOT_CA_G3];
  if (!roots.some((r) => equalBytes(r, root.der))) {
    throw new VerificationError("chain does not end at a trusted root");
  }
  if (!intermediate.extensionOids.includes(OID_WWDR_INTERMEDIATE)) {
    throw new VerificationError("intermediate is not Apple WWDR");
  }
  if (!leaf.extensionOids.includes(OID_APP_STORE_SIGNING)) {
    throw new VerificationError("leaf is not an App Store signing certificate");
  }

  const signedMs = typeof payload.signedDate === "number" ? payload.signedDate : undefined;
  const at = options.at ?? (signedMs ? new Date(signedMs) : new Date());
  for (const c of chain) {
    if (at < c.notBefore || at > c.notAfter) {
      throw new VerificationError("certificate was not valid when the payload was signed");
    }
  }
  try {
    if (!(await signedBy(leaf, intermediate)) || !(await signedBy(intermediate, root))) {
      throw new VerificationError("certificate chain signature mismatch");
    }
  } catch (e) {
    if (e instanceof VerificationError) throw e;
    throw new VerificationError(`certificate chain: ${(e as Error).message}`);
  }

  // JWS ES256 signatures are already raw r‖s.
  const key = await importEcKey(leaf);
  const ok = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    fromBase64(s) as Uint8Array<ArrayBuffer>,
    new TextEncoder().encode(`${h}.${p}`),
  );
  if (!ok) throw new VerificationError("JWS signature mismatch");
  return payload as T;
}

// Payloads --------------------------------------------------------------------

/** JWSTransactionDecodedPayload, the fields used here. */
export interface AppleTransaction {
  originalTransactionId: string;
  transactionId: string;
  bundleId: string;
  productId: string;
  expiresDate?: number;
  revocationDate?: number;
  appAccountToken?: string;
  environment: string;
  signedDate: number;
  type?: string;
}

/** JWSRenewalInfoDecodedPayload, the fields used here. */
export interface AppleRenewalInfo {
  autoRenewStatus?: number;
  gracePeriodExpiresDate?: number;
  isInBillingRetryPeriod?: boolean;
  signedDate?: number;
}

/** responseBodyV2DecodedPayload. */
export interface AppleNotification {
  notificationType: string;
  subtype?: string;
  notificationUUID: string;
  signedDate: number;
  data?: {
    bundleId?: string;
    environment?: string;
    signedTransactionInfo?: string;
    signedRenewalInfo?: string;
    /** 1 active, 2 expired, 3 billing retry, 4 grace period, 5 revoked. */
    status?: number;
  };
}

export interface AppleConfig {
  bundleId: string;
  productIds: string[];
  allowSandbox: boolean;
}

export function appleConfig(env: (name: string) => string | undefined = Deno.env.get): AppleConfig {
  return {
    bundleId: env("APPLE_BUNDLE_ID") ?? "uk.co.maybeitssoftware.librelog",
    productIds: (env("APPLE_PRODUCT_IDS") ?? "uk.co.maybeitssoftware.librelog.sync.yearly")
      .split(",").map((s) => s.trim()).filter(Boolean),
    allowSandbox: (env("APPLE_ALLOW_SANDBOX") ?? "true") !== "false",
  };
}

/** Checks a transaction is for this app and product, from an environment
 *  this deployment accepts. */
export function checkTransaction(tx: AppleTransaction, config: AppleConfig): void {
  if (tx.bundleId !== config.bundleId) throw new VerificationError(`wrong bundle ${tx.bundleId}`);
  if (!config.productIds.includes(tx.productId)) throw new VerificationError(`unknown product ${tx.productId}`);
  if (tx.environment !== "Production" && !(tx.environment === "Sandbox" && config.allowSandbox)) {
    throw new VerificationError(`environment ${tx.environment} not accepted`);
  }
}

const STATUS_BY_CODE: Record<number, PurchaseState["status"]> = {
  1: "active",
  2: "expired",
  3: "billing_retry",
  4: "grace",
  5: "revoked",
};

/** What a verified transaction (and, from a notification, its renewal info
 *  and status) means for the purchase. */
export function appleState(
  tx: AppleTransaction,
  opts: {
    renewal?: AppleRenewalInfo;
    statusCode?: number;
    notificationType?: string;
    now?: Date;
  } = {},
): PurchaseState {
  const now = opts.now ?? new Date();
  let expires = tx.expiresDate ?? null;
  // In a billing grace period the subscriber keeps access until it ends.
  if (opts.renewal?.gracePeriodExpiresDate && (opts.statusCode === 4 || opts.statusCode === undefined)) {
    expires = Math.max(expires ?? 0, opts.renewal.gracePeriodExpiresDate);
  }

  let status: PurchaseState["status"];
  if (tx.revocationDate || opts.notificationType === "REFUND" || opts.notificationType === "REVOKE") {
    status = opts.notificationType === "REVOKE" ? "revoked" : "refunded";
  } else if (opts.statusCode && STATUS_BY_CODE[opts.statusCode]) {
    status = STATUS_BY_CODE[opts.statusCode];
  } else {
    status = expires !== null && expires > now.getTime() ? "active" : "expired";
  }

  return {
    store: "app_store",
    originalId: tx.originalTransactionId,
    userId: normaliseUuid(tx.appAccountToken),
    productId: tx.productId,
    environment: tx.environment === "Production" ? "production" : "sandbox",
    expiresAt: expires === null ? null : new Date(expires),
    status,
    autoRenew: opts.renewal ? opts.renewal.autoRenewStatus === 1 : status === "active",
  };
}

/** Notification types that only tell us something unrelated to access. */
export const IGNORED_NOTIFICATIONS = new Set([
  "TEST",
  "CONSUMPTION_REQUEST",
  "PRICE_INCREASE",
  "EXTERNAL_PURCHASE_TOKEN",
  "METADATA_UPDATE",
  "MIGRATION",
  "RENEWAL_EXTENSION", // the summary; each extended subscription gets RENEWAL_EXTENDED
]);

export function normaliseUuid(s: string | undefined | null): string | null {
  if (!s) return null;
  const v = s.trim().toLowerCase();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v) ? v : null;
}
