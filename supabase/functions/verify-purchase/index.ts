// The apps report a purchase the moment it completes, so sync switches on
// without waiting for the store's notification. Same verification as the
// notification endpoints; reporting the same purchase again changes nothing.
//
//   POST /functions/v1/verify-purchase
//   Authorization: Bearer <user's access token>
//   {"store": "app_store", "signed_transaction": "<StoreKit 2 JWS>"}
//   {"store": "play", "product_id": "sync_yearly", "purchase_token": "…"}
//
// 200 {sync_until, status, expires_at, acknowledged}; 400 not verifiable;
// 401 not signed in; 403 bought for another account; 409 already syncing
// another account; 502 the store couldn't be reached (try again later).
//
// Env: as appstore-notifications and play-rtdn (except the RTDN pair).

import { appleConfig, verifyAppleJws } from "../_shared/apple.ts";
import { playApi, playConfig } from "../_shared/google.ts";
import { verifyPurchase } from "../_shared/handlers.ts";
import { recordPurchase, requireUser } from "../_shared/purchases.ts";

const play = playConfig();

Deno.serve(verifyPurchase({
  user: requireUser,
  verify: (jws) => verifyAppleJws(jws),
  appleConfig: appleConfig(),
  playApi: () => playApi(play),
  playConfig: play,
  record: recordPurchase,
}));
