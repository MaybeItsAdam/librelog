package uk.co.maybeitssoftware.librelog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.maybeitssoftware.librelog.sync.InMemorySecretStore
import uk.co.maybeitssoftware.librelog.sync.SecretStore
import uk.co.maybeitssoftware.librelog.sync.Session
import uk.co.maybeitssoftware.librelog.sync.SupabaseClient
import uk.co.maybeitssoftware.librelog.sync.SyncError

/** Sessions: refreshing, a refused refresh, and a stray 401. */
class SupabaseClientTest {
    /** Auth and one RPC, `ping`, scripted per test. */
    private class FakeAuth : Dispatcher() {
        var refreshStatus = 200
        var refreshBody = """{"error":"invalid_grant","error_description":"Invalid Refresh Token: Refresh Token Not Found"}"""
        /** Statuses `ping` answers with, in turn; 200 once they run out. */
        val pingStatuses = ArrayDeque<Int>()
        var issued = 0
        val seen = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
            seen += "${request.url.encodedPath}${request.url.encodedQuery?.let { "?$it" } ?: ""} ${request.headers["Authorization"] ?: ""}".trim()
            when (request.url.encodedPath) {
                "/auth/v1/token" -> if (refreshStatus == 200) {
                    issued += 1
                    reply(200, """{"access_token":"fresh-$issued","refresh_token":"r-$issued","expires_in":3600,"user":{"id":"user-1","email":"me@example.com"}}""")
                } else {
                    reply(refreshStatus, refreshBody)
                }
                "/auth/v1/logout" -> reply(204, "")
                "/rest/v1/rpc/ping" -> {
                    val status = pingStatuses.removeFirstOrNull() ?: 200
                    if (status == 200) reply(200, "\"pong\"") else reply(status, """{"message":"JWT expired"}""")
                }
                else -> reply(404, "{}")
            }
        }

        private fun reply(code: Int, body: String) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }

    private val server = MockWebServer()
    private val fake = FakeAuth()
    private val secrets = InMemorySecretStore()

    @Before fun setUp() {
        server.dispatcher = fake
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun client(expiresIn: Long = 3_600_000): SupabaseClient {
        val session = Session("token", "refresh", System.currentTimeMillis() + expiresIn, "user-1", "me@example.com")
        secrets.write(SecretStore.Item.Session, SupabaseClient.json.encodeToString(Session.serializer(), session).encodeToByteArray())
        return SupabaseClient(server.url("/").toString().trimEnd('/'), "test-key", secrets)
    }

    private suspend fun SupabaseClient.ping() = rpc("ping", buildJsonObject {}).jsonPrimitive.content

    @Test fun anExpiringSessionRefreshesFirst() = runBlocking {
        fake.refreshStatus = 200
        val client = client(expiresIn = 0)
        assertEquals("pong", client.ping())
        assertEquals("fresh-1", client.session?.accessToken)
        assertTrue(fake.seen.last().endsWith("Bearer fresh-1"))
    }

    @Test fun aRefusedRefreshIsSignedOutNotAProblem() = runBlocking {
        fake.refreshStatus = 400
        val client = client(expiresIn = 0)
        val error = runCatching { client.ping() }.exceptionOrNull()
        assertEquals(SyncError.SignedOut, error)
        assertNull("the dead session is forgotten", client.session)
        assertNull(secrets.read(SecretStore.Item.Session))
    }

    @Test fun aRefreshThatFailsOnTheServerSideKeepsTheSession() = runBlocking {
        fake.refreshStatus = 503
        fake.refreshBody = """{"msg":"unavailable"}"""
        val client = client(expiresIn = 0)
        val error = runCatching { client.ping() }.exceptionOrNull()
        assertTrue(error is SyncError.Server)
        assertNotNull(client.session)
    }

    @Test fun aStray401RefreshesAndRetries() = runBlocking {
        fake.refreshStatus = 200
        fake.pingStatuses += 401
        val client = client()
        assertEquals("pong", client.ping())
        assertEquals("fresh-1", client.session?.accessToken)
        assertEquals(
            listOf("/rest/v1/rpc/ping Bearer token", "/auth/v1/token?grant_type=refresh_token", "/rest/v1/rpc/ping Bearer fresh-1"),
            fake.seen,
        )
    }

    @Test fun a401AfterRefreshingIsSignedOut() = runBlocking {
        fake.refreshStatus = 200
        fake.pingStatuses += listOf(401, 401)
        val client = client()
        assertEquals(SyncError.SignedOut, runCatching { client.ping() }.exceptionOrNull())
        assertEquals("only one refresh", 1, fake.issued)
    }
}
