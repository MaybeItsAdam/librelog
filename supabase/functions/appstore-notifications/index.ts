// App Store Server Notifications v2 (SPEC.md §12.5, docs/billing.md):
// verifies the signed payload and sets the buyer's sync_until.
//
// Env: APPLE_BUNDLE_ID, APPLE_PRODUCT_IDS, APPLE_ALLOW_SANDBOX (defaults in
// _shared/apple.ts), plus SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY, which
// Supabase provides.

import { appleConfig, verifyAppleJws } from "../_shared/apple.ts";
import { appStoreNotifications } from "../_shared/handlers.ts";
import { recordPurchase } from "../_shared/purchases.ts";

Deno.serve(appStoreNotifications({
  verify: (jws) => verifyAppleJws(jws),
  record: recordPurchase,
  config: appleConfig(),
}));
