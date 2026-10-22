package uk.co.maybeitssoftware.librelog.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import uk.co.maybeitssoftware.librelog.AppModel
import uk.co.maybeitssoftware.librelog.billing.SubscriptionSummary
import uk.co.maybeitssoftware.librelog.billing.SyncPlan
import uk.co.maybeitssoftware.librelog.billing.summarise
import uk.co.maybeitssoftware.librelog.ui.PrimaryButton
import uk.co.maybeitssoftware.librelog.ui.SecondaryButton
import uk.co.maybeitssoftware.librelog.ui.StatusBanner
import uk.co.maybeitssoftware.librelog.ui.Typeface
import uk.co.maybeitssoftware.librelog.ui.tokens

/**
 * Settings → Subscription, while signed in: what the account is paid up to,
 * and buying, restoring or managing the yearly sync subscription on Google
 * Play (SPEC.md §15, docs/billing.md). The price always comes from Play.
 */
@Composable
internal fun SubscriptionSection(app: AppModel) {
    val t = tokens
    val sync = app.sync
    val billing = app.billing
    val context = LocalContext.current
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG) }

    LaunchedEffect(Unit) {
        sync.refreshSubscription()
        billing.loadProduct()
    }

    val info = sync.subscription
    val summary = summarise(info, formatter = formatter)
    val active = info?.isActive() == true

    Section("Subscription") {
        Labeled("Sync") {
            when {
                billing.busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.primary)
                billing.paymentPending && !active -> StatusTag("Payment pending", t.warning)
                else -> StatusTag(
                    summary.tag,
                    when (summary.tone) {
                        SubscriptionSummary.Tone.Good -> t.success
                        SubscriptionSummary.Tone.Warning -> t.warning
                        SubscriptionSummary.Tone.Bad -> t.danger
                    },
                )
            }
        }
        Text(
            if (billing.paymentPending && !active) {
                "Google Play is waiting for your payment to clear. Sync switches on as soon as it does."
            } else {
                summary.detail
            },
            style = Typeface.small,
            color = t.muted,
        )

        if (!active) {
            val price = billing.price
            PrimaryButton(
                if (price != null) "Subscribe for $price a year" else "Subscribe",
                onClick = { context.activity()?.let(billing::purchase) },
                enabled = !billing.busy && !billing.paymentPending,
            )
        }
        SecondaryButton("Restore purchases", { billing.restore() })
        // A subscription bought on the App Store is managed there; Play's
        // page would show nothing.
        if (info?.store == null || info.store == "play") {
            SecondaryButton("Manage subscription", {
                val url = SyncPlan.manageUrl(context.packageName)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            })
        }
        billing.message?.let { StatusBanner(it, t.danger) }
        if (!active) {
            Text(
                SyncPlan.disclosure(billing.price),
                style = Typeface.small.copy(fontSize = 12.sp),
                color = t.muted,
            )
        }
    }
}
