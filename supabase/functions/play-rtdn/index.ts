// Google Play real-time developer notifications, pushed by Pub/Sub (SPEC.md
// §12.5, docs/billing.md): checks the push's OIDC token, reads the
// subscription from the Play Developer API and sets the buyer's sync_until.
//
// Env: PLAY_RTDN_AUDIENCE (this function's URL, as set on the push
// subscription), PLAY_RTDN_SERVICE_ACCOUNT (the account Pub/Sub signs as),
// PLAY_SERVICE_ACCOUNT_JSON (key for the Play Developer API),
// PLAY_PACKAGE_NAME and PLAY_PRODUCT_IDS (defaults in _shared/google.ts).

import { playApi, playConfig } from "../_shared/google.ts";
import { playRtdn } from "../_shared/handlers.ts";
import { recordPurchase } from "../_shared/purchases.ts";

const config = playConfig();
const audience = Deno.env.get("PLAY_RTDN_AUDIENCE");
const serviceAccount = Deno.env.get("PLAY_RTDN_SERVICE_ACCOUNT");
if (!audience || !serviceAccount) {
  throw new Error("PLAY_RTDN_AUDIENCE and PLAY_RTDN_SERVICE_ACCOUNT must be set");
}

Deno.serve(playRtdn({
  auth: { audience, serviceAccount },
  api: () => playApi(config),
  record: recordPurchase,
  config,
}));
