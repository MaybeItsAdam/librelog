// deno test supabase/functions/tests
//
// The App Store chain checks run against the stand-in chain in fixtures/
// (make-fixtures.sh); the real Apple root is only parsed. Everything that
// would talk to Apple, Google or the database is faked.

import {
  APPLE_ROOT_CA_G3,
  appleState,
  type AppleTransaction,
  checkTransaction,
  verifyAppleJws,
} from "../_shared/apple.ts";
import { type Jwk, playState, type SubscriptionPurchaseV2, verifyPushToken } from "../_shared/google.ts";
import { appStoreNotifications, playRtdn, verifyPurchase } from "../_shared/handlers.ts";
import { HttpError, type PurchaseState, type RecordEvent } from "../_shared/purchases.ts";
import { fromBase64, parseCertificate, signedBy, toBase64Url } from "../_shared/x509.ts";

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

const fixtures = new URL("./fixtures/", import.meta.url);
const pemDer = (name: string) =>
  fromBase64(Deno.readTextFileSync(new URL(name, fixtures)).replace(/-----[^-]+-----/g, ""));
const b64 = (d: Uint8Array) => btoa(String.fromCharCode(...d));

const ROOT = pemDer("root.pem");
const INTERMEDIATE = pemDer("intermediate.pem");
const LEAF = pemDer("leaf.pem");
const PLAIN_LEAF = pemDer("plainleaf.pem");

async function signJws(payload: unknown, opts: { leaf?: "leaf" | "plainleaf"; x5c?: Uint8Array[] } = {}) {
  const which = opts.leaf ?? "leaf";
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemDer(`${which}.key`) as Uint8Array<ArrayBuffer>,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const x5c = opts.x5c ?? [which === "leaf" ? LEAF : PLAIN_LEAF, INTERMEDIATE, ROOT];
  const enc = (o: unknown) => toBase64Url(new TextEncoder().encode(JSON.stringify(o)));
  const input = `${enc({ alg: "ES256", x5c: x5c.map(b64) })}.${enc(payload)}`;
  const sig = new Uint8Array(
    await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(input)),
  );
  return `${input}.${toBase64Url(sig)}`;
}

const testVerify = <T>(jws: string) => verifyAppleJws<T>(jws, { roots: [ROOT] });

const USER = "8a1b2c3d-0000-4000-8000-000000000001";
const OTHER = "8a1b2c3d-0000-4000-8000-000000000002";
// A day into the stand-in certificates' validity, whenever they were made.
const NOW = parseCertificate(LEAF).notBefore.getTime() + 86_400_000;
const YEAR = 365 * 24 * 3600 * 1000;

const appleConfig = {
  bundleId: "uk.co.maybeitssoftware.librelog",
  productIds: ["uk.co.maybeitssoftware.librelog.sync.yearly"],
  allowSandbox: true,
};

function transaction(over: Partial<AppleTransaction> = {}): AppleTransaction {
  return {
    originalTransactionId: "2000000000000001",
    transactionId: "2000000000000009",
    bundleId: appleConfig.bundleId,
    productId: appleConfig.productIds[0],
    expiresDate: NOW + YEAR,
    appAccountToken: USER.toUpperCase(),
    environment: "Production",
    signedDate: NOW,
    type: "Auto-Renewable Subscription",
    ...over,
  };
}

// Certificate chain ---------------------------------------------------------------

Deno.test("the pinned Apple root parses and is self-signed", async () => {
  const root = parseCertificate(APPLE_ROOT_CA_G3);
  assertEquals(root.curve, "P-384");
  assert(await signedBy(root, root));
  assert(root.notAfter.getUTCFullYear() === 2039);
});

Deno.test("a JWS signed through a trusted chain verifies", async () => {
  const payload = await testVerify<AppleTransaction>(await signJws(transaction()));
  assertEquals(payload.originalTransactionId, "2000000000000001");
});

Deno.test("a chain not ending at Apple's root is refused by default", async () => {
  await assertRejects(async () => verifyAppleJws(await signJws(transaction())), /trusted root/);
});

Deno.test("a leaf without the App Store signing OID is refused", async () => {
  await assertRejects(
    async () => testVerify(await signJws(transaction(), { leaf: "plainleaf" })),
    /signing certificate/,
  );
});

Deno.test("an intermediate without the WWDR OID is refused", async () => {
  await assertRejects(
    async () => testVerify(await signJws(transaction(), { x5c: [LEAF, LEAF, ROOT] })),
    /WWDR/,
  );
});

Deno.test("a tampered payload or a short chain is refused", async () => {
  const jws = await signJws(transaction());
  const [h, , s] = jws.split(".");
  const forged = toBase64Url(new TextEncoder().encode(JSON.stringify(transaction({ expiresDate: NOW + 99 * YEAR }))));
  await assertRejects(() => testVerify(`${h}.${forged}.${s}`), /signature/);
  await assertRejects(async () => testVerify(await signJws(transaction(), { x5c: [LEAF, ROOT] })), /x5c/);
});

Deno.test("certificates must have been valid when the payload was signed", async () => {
  await assertRejects(
    async () => testVerify(await signJws(transaction({ signedDate: Date.UTC(2090, 0, 1) }))),
    /valid/,
  );
});

// App Store state ---------------------------------------------------------------------

Deno.test("appleState maps transactions and notifications", () => {
  const now = new Date(NOW);
  const active = appleState(transaction(), { now });
  assertEquals(active.status, "active");
  assertEquals(active.userId, USER, "appAccountToken is lowercased");
  assertEquals(active.expiresAt?.getTime(), NOW + YEAR);

  const grace = appleState(transaction({ expiresDate: NOW - 1000 }), {
    now,
    statusCode: 4,
    renewal: { autoRenewStatus: 1, gracePeriodExpiresDate: NOW + 16 * 86_400_000 },
  });
  assertEquals(grace.status, "grace");
  assertEquals(grace.expiresAt?.getTime(), NOW + 16 * 86_400_000, "grace period extends access");
  assertEquals(grace.autoRenew, true);

  assertEquals(appleState(transaction({ expiresDate: NOW - 1 }), { now }).status, "expired");
  assertEquals(appleState(transaction({ revocationDate: NOW }), { now }).status, "refunded");
  assertEquals(appleState(transaction({ revocationDate: NOW }), { now, notificationType: "REVOKE" }).status, "revoked");
  assertEquals(appleState(transaction({ appAccountToken: "not-a-uuid" }), { now }).userId, null);
  assertEquals(appleState(transaction({ environment: "Sandbox" }), { now }).environment, "sandbox");
});

Deno.test("checkTransaction holds to this app, product and environment", () => {
  checkTransaction(transaction(), appleConfig);
  let threw = 0;
  for (
    const tx of [
      transaction({ bundleId: "com.example" }),
      transaction({ productId: "other" }),
      transaction({ environment: "Xcode" }),
    ]
  ) {
    try {
      checkTransaction(tx, appleConfig);
    } catch {
      threw++;
    }
  }
  assertEquals(threw, 3);
  try {
    checkTransaction(transaction({ environment: "Sandbox" }), { ...appleConfig, allowSandbox: false });
    throw new Error("sandbox should be refused");
  } catch (e) {
    assert(/environment/.test((e as Error).message));
  }
});

// Play state ------------------------------------------------------------------------

const playConfig = { packageName: "uk.co.maybeitssoftware.librelog", productIds: ["sync_yearly"] };

function sub(over: Partial<SubscriptionPurchaseV2> = {}): SubscriptionPurchaseV2 {
  return {
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
    externalAccountIdentifiers: { obfuscatedExternalAccountId: USER },
    lineItems: [{
      productId: "sync_yearly",
      expiryTime: new Date(NOW + YEAR).toISOString(),
      autoRenewingPlan: { autoRenewEnabled: true },
    }],
    ...over,
  };
}

Deno.test("playState maps subscription states", () => {
  const active = playState("tok", sub(), playConfig, undefined, NOW);
  assertEquals([active.status, active.userId, active.autoRenew, active.needsAcknowledging], [
    "active",
    USER,
    true,
    true,
  ]);
  assertEquals(active.expiresAt?.getTime(), NOW + YEAR);

  const canceled = playState(
    "tok",
    sub({ subscriptionState: "SUBSCRIPTION_STATE_CANCELED" }),
    playConfig,
    undefined,
    NOW,
  );
  assertEquals(canceled.status, "active", "cancelled but paid up still syncs");

  const onHold = playState("tok", sub({ subscriptionState: "SUBSCRIPTION_STATE_ON_HOLD" }), playConfig, undefined, NOW);
  assertEquals([onHold.status, onHold.expiresAt, onHold.needsAcknowledging], ["billing_retry", null, false]);

  const pending = playState(
    "tok",
    sub({ subscriptionState: "SUBSCRIPTION_STATE_PENDING" }),
    playConfig,
    undefined,
    NOW,
  );
  assertEquals([pending.status, pending.expiresAt], ["pending", null]);

  const revoked = playState(
    "tok",
    sub({ subscriptionState: "SUBSCRIPTION_STATE_EXPIRED" }),
    playConfig,
    "revoked",
    NOW,
  );
  assertEquals(revoked.status, "revoked");

  assertEquals(playState("tok", sub({ testPurchase: {} }), playConfig, undefined, NOW).environment, "sandbox");

  try {
    playState("tok", sub({ lineItems: [{ productId: "something_else" }] }), playConfig, undefined, NOW);
    throw new Error("other products should be refused");
  } catch (e) {
    assert(e instanceof HttpError && e.status === 400);
  }
});

// Pub/Sub push tokens ------------------------------------------------------------------

const rsa = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);
const publicJwk = await crypto.subtle.exportKey("jwk", rsa.publicKey);
const jwks = () => Promise.resolve([{ ...publicJwk, kid: "k1" } as Jwk]);
const pushAuth = {
  audience: "https://example.supabase.co/functions/v1/play-rtdn",
  serviceAccount: "rtdn-push@example.iam.gserviceaccount.com",
};

async function pushToken(over: Record<string, unknown> = {}, kid = "k1") {
  const enc = (o: unknown) => toBase64Url(new TextEncoder().encode(JSON.stringify(o)));
  const input = `${enc({ alg: "RS256", kid, typ: "JWT" })}.${
    enc({
      iss: "https://accounts.google.com",
      aud: pushAuth.audience,
      email: pushAuth.serviceAccount,
      email_verified: true,
      iat: Math.floor(NOW / 1000),
      exp: Math.floor(NOW / 1000) + 3600,
      ...over,
    })
  }`;
  const sig = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", rsa.privateKey, new TextEncoder().encode(input)),
  );
  return `Bearer ${input}.${toBase64Url(sig)}`;
}

Deno.test("Pub/Sub push tokens are checked for signature, audience, account and time", async () => {
  await verifyPushToken(await pushToken(), pushAuth, jwks, NOW);
  await assertRejects(
    async () => verifyPushToken(await pushToken({ aud: "https://elsewhere" }), pushAuth, jwks, NOW),
    /audience/,
  );
  await assertRejects(
    async () => verifyPushToken(await pushToken({ email: "x@y" }), pushAuth, jwks, NOW),
    /service account/,
  );
  await assertRejects(
    async () => verifyPushToken(await pushToken({ email_verified: false }), pushAuth, jwks, NOW),
    /service account/,
  );
  await assertRejects(
    async () => verifyPushToken(await pushToken({ exp: Math.floor(NOW / 1000) - 600 }), pushAuth, jwks, NOW),
    /expired/,
  );
  await assertRejects(
    async () => verifyPushToken(await pushToken({ iss: "https://evil" }), pushAuth, jwks, NOW),
    /issuer/,
  );
  await assertRejects(async () => verifyPushToken(await pushToken({}, "k2"), pushAuth, jwks, NOW), /signing key/);
  await assertRejects(() => verifyPushToken(null, pushAuth, jwks, NOW), /missing/);
  const forged = (await pushToken()).replace(/\.[^.]+$/, ".AAAA");
  await assertRejects(() => verifyPushToken(forged, pushAuth, jwks, NOW), /signature/);
});

// Handlers --------------------------------------------------------------------------

function recorder(result: string | null | HttpError = "2027-10-22T00:00:00+00:00") {
  const calls: { state: PurchaseState; event: RecordEvent }[] = [];
  const record = (state: PurchaseState, event: RecordEvent) => {
    calls.push({ state, event });
    return result instanceof HttpError ? Promise.reject(result) : Promise.resolve(result);
  };
  return { calls, record };
}

const post = (body: unknown, headers: Record<string, string> = {}) =>
  new Request("https://example.test/", { method: "POST", body: JSON.stringify(body), headers });

Deno.test("appstore-notifications applies a renewal once per notification", async () => {
  const { calls, record } = recorder();
  const handler = appStoreNotifications({ verify: testVerify, record, config: appleConfig });
  const signedPayload = await signJws({
    notificationType: "DID_RENEW",
    notificationUUID: "uuid-1",
    signedDate: NOW + 5,
    data: {
      bundleId: appleConfig.bundleId,
      environment: "Production",
      status: 1,
      signedTransactionInfo: await signJws(transaction({ appAccountToken: undefined })),
      signedRenewalInfo: await signJws({ autoRenewStatus: 1, signedDate: NOW }),
    },
  });
  const res = await handler(post({ signedPayload }));
  assertEquals(res.status, 200);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].event.id, "uuid-1");
  assertEquals(calls[0].event.at.getTime(), NOW + 5);
  assertEquals([calls[0].state.status, calls[0].state.userId, calls[0].state.autoRenew], ["active", null, true]);
});

Deno.test("appstore-notifications ignores tests, refuses forgeries, and accepts unknown buyers", async () => {
  const { calls, record } = recorder(new HttpError(404, "purchase is not linked to an account"));
  const handler = appStoreNotifications({ verify: testVerify, record, config: appleConfig });
  const test = await signJws({ notificationType: "TEST", notificationUUID: "t", signedDate: NOW });
  assertEquals((await handler(post({ signedPayload: test }))).status, 200);
  assertEquals(calls.length, 0);

  const forged = await signJws({ notificationType: "SUBSCRIBED", notificationUUID: "f", signedDate: NOW }, {
    leaf: "plainleaf",
  });
  assertEquals((await handler(post({ signedPayload: forged }))).status, 400);

  const refund = await signJws({
    notificationType: "REFUND",
    notificationUUID: "r",
    signedDate: NOW,
    data: {
      bundleId: appleConfig.bundleId,
      signedTransactionInfo: await signJws(transaction({ revocationDate: NOW })),
    },
  });
  const res = await handler(post({ signedPayload: refund }));
  assertEquals(res.status, 200, "an unknown purchase must not make Apple retry forever");
  assertEquals(calls[0].state.status, "refunded");
});

function fakePlay(state: SubscriptionPurchaseV2) {
  const acks: string[] = [];
  return {
    acks,
    api: () => ({
      getSubscription: () => Promise.resolve(state),
      acknowledge: (productId: string, token: string) => {
        acks.push(`${productId}/${token}`);
        return Promise.resolve();
      },
    }),
  };
}

function verifyHandler(record: ReturnType<typeof recorder>["record"], play = fakePlay(sub())) {
  return verifyPurchase({
    user: () => Promise.resolve(USER),
    verify: testVerify,
    appleConfig,
    playApi: play.api,
    playConfig,
    record,
    now: () => new Date(NOW),
  });
}

Deno.test("verify-purchase links an App Store purchase to the signed-in account", async () => {
  const { calls, record } = recorder();
  const res = await verifyHandler(record)(
    post({ store: "app_store", signed_transaction: await signJws(transaction()) }),
  );
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(body.sync_until, "2027-10-22T00:00:00+00:00");
  assertEquals(body.status, "active");
  assertEquals(calls[0].state.userId, USER);
  assertEquals(calls[0].event.event, "VERIFY");

  const other = await verifyHandler(record)(
    post({ store: "app_store", signed_transaction: await signJws(transaction({ appAccountToken: OTHER })) }),
  );
  assertEquals(other.status, 403);
  const bad = await verifyHandler(record)(post({ store: "app_store", signed_transaction: "a.b.c" }));
  assertEquals(bad.status, 400);
});

Deno.test("verify-purchase checks a Play token with Google and acknowledges it", async () => {
  const { calls, record } = recorder();
  const play = fakePlay(sub());
  const res = await verifyHandler(record, play)(
    post({ store: "play", product_id: "sync_yearly", purchase_token: "tok-1" }),
  );
  assertEquals(res.status, 200);
  assertEquals((await res.json()).acknowledged, true);
  assertEquals(play.acks, ["sync_yearly/tok-1"]);
  assertEquals(calls[0].state.originalId, "tok-1");

  const elsewhere = fakePlay(sub({ externalAccountIdentifiers: { obfuscatedExternalAccountId: OTHER } }));
  assertEquals((await verifyHandler(record, elsewhere)(post({ store: "play", purchase_token: "t" }))).status, 403);

  const pending = fakePlay(sub({ subscriptionState: "SUBSCRIPTION_STATE_PENDING" }));
  const p = await verifyHandler(record, pending)(post({ store: "play", purchase_token: "t" }));
  const body = await p.json();
  assertEquals([body.status, body.acknowledged, pending.acks.length], ["pending", false, 0]);

  const taken = recorder(new HttpError(409, "purchase belongs to another account"));
  assertEquals((await verifyHandler(taken.record)(post({ store: "play", purchase_token: "t" }))).status, 409);
});

Deno.test("play-rtdn authenticates the push and applies a refund", async () => {
  const { calls, record } = recorder();
  const play = fakePlay(
    sub({
      subscriptionState: "SUBSCRIPTION_STATE_EXPIRED",
      acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    }),
  );
  const handler = playRtdn({
    auth: pushAuth,
    jwks,
    api: play.api,
    record,
    config: playConfig,
    now: () => new Date(NOW),
  });
  const data = btoa(JSON.stringify({
    packageName: playConfig.packageName,
    voidedPurchaseNotification: { purchaseToken: "tok-1", orderId: "GPA.1", productType: 1, refundType: 1 },
  }));
  const message = { message: { data, messageId: "m-1" }, subscription: "projects/p/subscriptions/s" };

  assertEquals((await handler(post(message))).status, 401, "no token");
  // verifyPushToken checks time against the real clock here, so mint a current token.
  const fresh = await pushToken({ iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 600 });
  const res = await handler(post(message, { Authorization: fresh }));
  assertEquals(res.status, 200);
  assertEquals([calls[0].state.status, calls[0].event.id, calls[0].event.event], ["refunded", "m-1", "VOIDED"]);
  assertEquals(play.acks.length, 0);

  const renewed = btoa(JSON.stringify({
    packageName: playConfig.packageName,
    subscriptionNotification: {
      version: "1.0",
      notificationType: 4,
      purchaseToken: "tok-2",
      subscriptionId: "sync_yearly",
    },
  }));
  const play2 = fakePlay(sub());
  const handler2 = playRtdn({
    auth: pushAuth,
    jwks,
    api: play2.api,
    record,
    config: playConfig,
    now: () => new Date(NOW),
  });
  assertEquals(
    (await handler2(post({ message: { data: renewed, messageId: "m-2" } }, { Authorization: fresh }))).status,
    200,
  );
  assertEquals(calls[1].event.event, "PURCHASED");
  assertEquals(play2.acks, ["sync_yearly/tok-2"], "unacknowledged purchases are acknowledged");

  const test = btoa(JSON.stringify({ packageName: playConfig.packageName, testNotification: { version: "1.0" } }));
  assertEquals(
    (await handler2(post({ message: { data: test, messageId: "m-3" } }, { Authorization: fresh }))).status,
    200,
  );
  assertEquals(calls.length, 2);
});
