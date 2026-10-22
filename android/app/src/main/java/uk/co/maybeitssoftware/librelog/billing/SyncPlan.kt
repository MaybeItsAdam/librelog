package uk.co.maybeitssoftware.librelog.billing

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The sync subscription as sold on Google Play (SPEC.md §15, docs/billing.md):
 * one auto-renewing subscription with a yearly base plan. The ids here must
 * match Play Console exactly. The pure parts (offer choice, server replies,
 * what the settings screen says) live here so they can be tested on the JVM.
 */
object SyncPlan {
    const val PRODUCT_ID = "sync_yearly"
    const val BASE_PLAN_ID = "yearly"

    /** Play's subscription centre, open at this subscription. */
    fun manageUrl(packageName: String) =
        "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=$packageName"

    /** One way to buy the product, as Play lists it (ProductDetails.SubscriptionOfferDetails). */
    data class Offer(val basePlanId: String, val offerId: String?, val offerToken: String, val formattedPrice: String?)

    /**
     * The offer to buy: the yearly base plan itself (no offer id) if Play
     * lists it, else any offer on that base plan, else whatever there is.
     */
    fun pickOffer(offers: List<Offer>): Offer? =
        offers.firstOrNull { it.basePlanId == BASE_PLAN_ID && it.offerId == null }
            ?: offers.firstOrNull { it.basePlanId == BASE_PLAN_ID }
            ?: offers.firstOrNull()

    /**
     * The disclosure Play's subscription policy asks for next to the button.
     * Sync is the only thing it pays for; the journal itself stays free.
     */
    fun disclosure(price: String?): String {
        val each = price?.let { "at $it " } ?: ""
        return "LibreLog Sync renews automatically every year ${each}until you cancel. Payment is charged to your " +
            "Google account. Cancel any time in Google Play before the renewal date; sync carries on until the " +
            "end of the year you've paid for. Everything else in LibreLog is free and works without it."
    }

    /** Text for a verify-purchase error status, or null to show the server's own. */
    fun verifyErrorText(status: Int): String? = when (status) {
        400 -> "Google Play couldn't confirm this purchase. If you were charged, try Restore purchases later."
        403 -> "This purchase was made while signed in to a different LibreLog account."
        409 -> "This subscription is already syncing another LibreLog account."
        502, 503, 504 -> "Couldn't reach Google Play to confirm the purchase. It'll be checked again next time."
        else -> null
    }
}

/** verify-purchase's reply: the account's paid-through date and the purchase's state. */
@Serializable
data class VerifyResult(
    @SerialName("sync_until") val syncUntil: String? = null,
    val status: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** The server acknowledged the purchase with Play, so the app needn't. */
    val acknowledged: Boolean = false,
)

/** A row of `librelog.sync_status()`. */
@Serializable
data class SyncStatusRow(
    @SerialName("sync_until") val syncUntil: String? = null,
    val store: String? = null,
    @SerialName("product_id") val productId: String? = null,
    val status: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("auto_renew") val autoRenew: Boolean? = null,
)

/** What the settings screen knows about the account's subscription. */
data class SubscriptionInfo(
    /** Epoch milliseconds; null if sync was never paid for. */
    val syncUntil: Long?,
    val store: String? = null,
    val status: String? = null,
    val expiresAt: Long? = null,
    val autoRenew: Boolean = false,
) {
    fun isActive(now: Long = System.currentTimeMillis()) = syncUntil != null && syncUntil > now

    companion object {
        fun from(row: SyncStatusRow) = SubscriptionInfo(
            syncUntil = parseTime(row.syncUntil),
            store = row.store,
            status = row.status,
            expiresAt = parseTime(row.expiresAt),
            autoRenew = row.autoRenew ?: false,
        )
    }
}

/** How the subscription reads in Settings: a short tag plus a sentence. */
data class SubscriptionSummary(val tag: String, val tone: Tone, val detail: String) {
    enum class Tone { Good, Warning, Bad }
}

/** Postgres and JavaScript timestamps (`…+00:00`, `…Z`) as epoch milliseconds. */
fun parseTime(text: String?): Long? =
    text?.let { runCatching { OffsetDateTime.parse(it.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull() }

/** Which store a subscription is with, as a phrase. */
fun storeName(store: String?): String = when (store) {
    "app_store" -> "the App Store"
    "play" -> "Google Play"
    "stripe" -> "the web"
    else -> "your store"
}

fun summarise(
    info: SubscriptionInfo?,
    now: Long = System.currentTimeMillis(),
    formatter: DateTimeFormatter,
    zone: ZoneId = ZoneId.systemDefault(),
): SubscriptionSummary {
    val until = info?.syncUntil
    fun date(ms: Long) = formatter.format(Instant.ofEpochMilli(ms).atZone(zone))
    return when {
        until == null -> SubscriptionSummary(
            "Not active", SubscriptionSummary.Tone.Bad,
            "Subscribe to sync this journal between your devices. Everything stays on this device either way.",
        )
        until <= now -> SubscriptionSummary(
            "Ended", SubscriptionSummary.Tone.Bad,
            "Sync ended on ${date(until)}. Changes here stay on this device until you subscribe again; " +
                "your other devices can still download for 30 days after.",
        )
        info.status == "grace" || info.status == "billing_retry" -> SubscriptionSummary(
            "Payment problem", SubscriptionSummary.Tone.Warning,
            "${storeName(info.store).replaceFirstChar(Char::uppercase)} couldn't renew it. Update your payment " +
                "method there to keep syncing after ${date(until)}.",
        )
        info.autoRenew && info.status == "active" -> SubscriptionSummary(
            "Active", SubscriptionSummary.Tone.Good,
            "Renews on ${date(info.expiresAt ?: until)} through ${storeName(info.store)}.",
        )
        info.store != null && info.status == "active" -> SubscriptionSummary(
            "Active", SubscriptionSummary.Tone.Good,
            "Won't renew. Sync carries on until ${date(until)}.",
        )
        else -> SubscriptionSummary("Active", SubscriptionSummary.Tone.Good, "Paid until ${date(until)}.")
    }
}
