package uk.co.maybeitssoftware.librelog

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.maybeitssoftware.librelog.billing.SubscriptionInfo
import uk.co.maybeitssoftware.librelog.billing.SubscriptionSummary.Tone
import uk.co.maybeitssoftware.librelog.billing.SyncPlan
import uk.co.maybeitssoftware.librelog.billing.SyncStatusRow
import uk.co.maybeitssoftware.librelog.billing.VerifyResult
import uk.co.maybeitssoftware.librelog.billing.parseTime
import uk.co.maybeitssoftware.librelog.billing.summarise
import uk.co.maybeitssoftware.librelog.sync.InMemorySecretStore
import uk.co.maybeitssoftware.librelog.sync.SecretStore
import uk.co.maybeitssoftware.librelog.sync.Session
import uk.co.maybeitssoftware.librelog.sync.SupabaseClient
import uk.co.maybeitssoftware.librelog.sync.SyncError

/** The sync subscription's pure parts: offer choice, server replies, and what Settings says. */
class SyncPlanTest {
    private val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val now = parseTime("2026-10-22T12:00:00Z")!!

    private fun summary(info: SubscriptionInfo?) = summarise(info, now, formatter, ZoneOffset.UTC)

    @Test fun picksTheYearlyBasePlanOverOffers() {
        val offers = listOf(
            SyncPlan.Offer("yearly", "intro", "t-intro", "£0.99"),
            SyncPlan.Offer("monthly", null, "t-month", "£0.29"),
            SyncPlan.Offer("yearly", null, "t-base", "£1.99"),
        )
        assertEquals("t-base", SyncPlan.pickOffer(offers)?.offerToken)
        assertEquals("t-intro", SyncPlan.pickOffer(offers.filter { it.offerId != null || it.basePlanId != "yearly" })?.offerToken)
        assertEquals("t-month", SyncPlan.pickOffer(listOf(offers[1]))?.offerToken)
        assertNull(SyncPlan.pickOffer(emptyList()))
    }

    @Test fun parsesPostgresAndJavaScriptTimes() {
        assertEquals(parseTime("2027-10-22T12:00:00Z"), parseTime("2027-10-22T12:00:00+00:00"))
        assertEquals(parseTime("2027-10-22T12:00:00.5Z"), parseTime("2027-10-22 12:00:00.5+00"))
        assertNull(parseTime("soon"))
        assertNull(parseTime(null))
    }

    @Test fun decodesTheVerifyReply() {
        val r = SupabaseClient.json.decodeFromString(
            VerifyResult.serializer(),
            """{"sync_until":"2027-10-22T12:00:00+00:00","status":"active","expires_at":"2027-10-22T12:00:00Z","acknowledged":true}""",
        )
        assertEquals("active", r.status)
        assertTrue(r.acknowledged)
        assertEquals(parseTime("2027-10-22T12:00:00Z"), parseTime(r.syncUntil))
        val pending = SupabaseClient.json.decodeFromString(VerifyResult.serializer(), """{"sync_until":null,"status":"pending"}""")
        assertFalse(pending.acknowledged)
        assertNull(pending.syncUntil)
    }

    @Test fun describesEachState() {
        assertEquals("Not active", summary(null).tag)
        assertEquals("Not active", summary(SubscriptionInfo(null)).tag)

        val lapsed = summary(SubscriptionInfo(parseTime("2026-10-01T00:00:00Z")))
        assertEquals("Ended", lapsed.tag)
        assertEquals(Tone.Bad, lapsed.tone)
        assertTrue(lapsed.detail, lapsed.detail.contains("1 Oct 2026"))

        val renewing = summary(
            SubscriptionInfo.from(
                SyncStatusRow("2027-10-22T00:00:00+00:00", "play", "sync_yearly", "active", "2027-10-22T00:00:00+00:00", true),
            ),
        )
        assertEquals(Tone.Good, renewing.tone)
        assertEquals("Renews on 22 Oct 2027 through Google Play.", renewing.detail)

        val cancelled = summary(SubscriptionInfo(parseTime("2027-01-05T00:00:00Z"), "app_store", "active", null, autoRenew = false))
        assertEquals("Won't renew. Sync carries on until 5 Jan 2027.", cancelled.detail)

        val grace = summary(SubscriptionInfo(parseTime("2026-11-01T00:00:00Z"), "play", "grace", null, autoRenew = true))
        assertEquals(Tone.Warning, grace.tone)
        assertTrue(grace.detail, grace.detail.startsWith("Google Play couldn't renew it"))

        // Given by hand, no purchase behind it.
        assertEquals("Paid until 1 Jan 2030.", summary(SubscriptionInfo(parseTime("2030-01-01T00:00:00Z"))).detail)
    }

    @Test fun errorsAndLinks() {
        assertTrue(SyncPlan.verifyErrorText(409)!!.contains("another LibreLog account"))
        assertNull(SyncPlan.verifyErrorText(500))
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=sync_yearly&package=uk.co.maybeitssoftware.librelog",
            SyncPlan.manageUrl("uk.co.maybeitssoftware.librelog"),
        )
        assertTrue(SyncPlan.disclosure("£1.99").contains("every year at £1.99 until you cancel"))
    }

    // The Edge Function call: its own path, no PostgREST profile, and its
    // statuses kept rather than read as database errors (409 isn't compaction).

    private val server = MockWebServer()
    private val seen = mutableListOf<RecordedRequest>()

    @After fun tearDown() = server.close()

    private fun functionClient(status: Int, body: String): SupabaseClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        val secrets = InMemorySecretStore()
        val session = Session("token", "refresh", System.currentTimeMillis() + 3_600_000, "user-1")
        secrets.write(SecretStore.Item.Session, SupabaseClient.json.encodeToString(Session.serializer(), session).encodeToByteArray())
        return SupabaseClient(server.url("/").toString().trimEnd('/'), "test-key", secrets)
    }

    @Test fun callsVerifyPurchaseAsTheUser() = runBlocking {
        val client = functionClient(200, """{"sync_until":"2027-10-22T12:00:00Z","status":"active","acknowledged":true}""")
        val reply = client.function("verify-purchase", buildJsonObject { put("store", "play"); put("purchase_token", "tok") })
        assertEquals("active", SupabaseClient.json.decodeFromJsonElement(VerifyResult.serializer(), reply).status)
        val request = seen.single()
        assertEquals("/functions/v1/verify-purchase", request.url.encodedPath)
        assertEquals("Bearer token", request.headers["Authorization"])
        assertEquals("test-key", request.headers["apikey"])
        assertNull(request.headers["Content-Profile"])
        assertTrue(request.body!!.utf8().contains("\"purchase_token\":\"tok\""))
    }

    @Test fun keepsTheFunctionsStatus() = runBlocking {
        val client = functionClient(409, """{"error":"purchase belongs to another account"}""")
        val error = runCatching { client.function("verify-purchase", buildJsonObject {}) }.exceptionOrNull()
        assertEquals(SyncError.Server(409, "purchase belongs to another account"), error)
    }
}
