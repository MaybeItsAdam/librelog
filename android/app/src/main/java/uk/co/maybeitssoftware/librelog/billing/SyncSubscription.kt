package uk.co.maybeitssoftware.librelog.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.co.maybeitssoftware.librelog.sync.SyncEngine
import uk.co.maybeitssoftware.librelog.sync.SyncError

/**
 * Buying sync on Google Play with the Play Billing Library (docs/billing.md).
 *
 * The purchase carries the Supabase user id as its obfuscated account id, so
 * the server (and Play's notifications) can tell whose it is. A completed
 * purchase goes to the server first; it is acknowledged with Play only once
 * the server has confirmed it, so a purchase the server never heard of is
 * refunded by Play after three days rather than kept. State is Compose
 * snapshot state; call from the main thread.
 */
class SyncSubscription(
    context: Context,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
) {
    /** The yearly price as Play formats it for this user's country, once loaded. */
    var price by mutableStateOf<String?>(null)
        private set
    /** Play holds a purchase whose payment hasn't cleared (cash, bank transfer). */
    var paymentPending by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /** The last thing worth telling the user: an error, or that restoring found nothing. */
    var message by mutableStateOf<String?>(null)
        private set
    /** Play Billing isn't available here (no Play Store, or an unsupported device). */
    var unavailable by mutableStateOf(false)
        private set

    private var product: ProductDetails? = null
    private var offerToken: String? = null
    private val handling = Mutex()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Connects if need be. With auto-reconnection on, later calls reconnect by themselves. */
    private suspend fun connected(): Boolean {
        if (client.isReady) return true
        val result = suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() {}
            })
        }
        val ok = result.responseCode == BillingClient.BillingResponseCode.OK
        unavailable = !ok && result.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
        return ok
    }

    /** Loads the price, for Settings. */
    suspend fun loadProduct() {
        if (product != null || !connected()) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SyncPlan.PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull() ?: return
        val offers = details.subscriptionOfferDetails.orEmpty().map { o ->
            SyncPlan.Offer(
                basePlanId = o.basePlanId,
                offerId = o.offerId,
                offerToken = o.offerToken,
                formattedPrice = o.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice,
            )
        }
        val offer = SyncPlan.pickOffer(offers) ?: return
        product = details
        offerToken = offer.offerToken
        price = offer.formattedPrice
    }

    /** Opens Play's purchase sheet. The result arrives in [onPurchasesUpdated]. */
    fun purchase(activity: Activity) {
        val user = sync.userId
        if (user == null) {
            message = "Sign in first, so the subscription knows which account to sync."
            return
        }
        scope.launch {
            message = null
            loadProduct()
            val details = product
            val token = offerToken
            if (details == null || token == null) {
                message = if (unavailable) "Google Play billing isn't available on this device."
                else "Couldn't load the subscription from Google Play. Try again in a moment."
                return@launch
            }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .setOfferToken(token)
                            .build(),
                    ),
                )
                .setObfuscatedAccountId(user)
                .build()
            val result = client.launchBillingFlow(activity, params)
            if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) restore()
            else if (result.responseCode != BillingClient.BillingResponseCode.OK) message = playError(result)
        }
    }

    /** Sends every subscription Play has for this Google account to the server. */
    fun restore() {
        scope.launch {
            message = null
            val found = checkPurchases(all = true)
            if (found == 0 && message == null) message = "Google Play has no LibreLog subscription for this Google account."
        }
    }

    /**
     * At launch and on returning to the app: finishes purchases that
     * completed while the app wasn't looking (a pending payment that cleared,
     * a purchase interrupted before the server heard of it).
     */
    fun checkOnLaunch() {
        if (sync.userId == null) return
        scope.launch { checkPurchases(all = false) }
    }

    /** Returns how many of the product's purchases Play has. */
    private suspend fun checkPurchases(all: Boolean): Int {
        if (!connected()) return 0
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            if (all) message = playError(result.billingResult)
            return 0
        }
        val ours = result.purchasesList.filter { SyncPlan.PRODUCT_ID in it.products }
        paymentPending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        for (p in ours) if (all || !p.isAcknowledged) handle(p)
        return ours.size
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch {
                purchases.orEmpty().filter { SyncPlan.PRODUCT_ID in it.products }.forEach { handle(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> message = playError(result)
        }
    }

    /** Server first, then acknowledge: see the class comment. One at a time. */
    private suspend fun handle(purchase: Purchase) = handling.withLock {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                paymentPending = true
                return@withLock
            }
            Purchase.PurchaseState.PURCHASED -> paymentPending = false
            else -> return@withLock
        }
        busy = true
        try {
            val verified = sync.verifyPurchase("play", SyncPlan.PRODUCT_ID, purchase.purchaseToken)
            if (!verified.acknowledged && !purchase.isAcknowledged) {
                val ack = client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                )
                if (ack.responseCode != BillingClient.BillingResponseCode.OK) {
                    // Not fatal: the next launch tries again, and the server
                    // acknowledges on each Play notification too.
                    Log.w(TAG, "acknowledge failed: ${ack.responseCode} ${ack.debugMessage}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncError.Server) {
            message = SyncPlan.verifyErrorText(e.code) ?: e.message
        } catch (e: SyncError.Offline) {
            message = "You're offline. The purchase will be confirmed when you're back online."
        } catch (e: Exception) {
            message = e.message ?: "Couldn't confirm the purchase."
        } finally {
            busy = false
        }
    }

    private fun playError(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play billing isn't available on this device."
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "Couldn't reach Google Play. Check your connection and try again."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "The subscription isn't available in your country yet."
        else -> "Google Play said no (${result.responseCode}). ${result.debugMessage}".trim()
    }

    private companion object {
        const val TAG = "SyncSubscription"
    }
}
