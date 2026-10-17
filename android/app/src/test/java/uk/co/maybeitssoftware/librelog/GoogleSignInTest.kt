package uk.co.maybeitssoftware.librelog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import uk.co.maybeitssoftware.librelog.sync.GoogleSignIn
import uk.co.maybeitssoftware.librelog.sync.InMemorySecretStore
import uk.co.maybeitssoftware.librelog.sync.SupabaseClient

/** Google's half is the device's; this checks what reaches Supabase. */
class GoogleSignInTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun theNonceGoogleSeesIsTheSha256OfTheOneSupabaseGets() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleSignIn.sha256Hex("abc"),
        )
        val nonce = GoogleSignIn.newNonce()
        assertEquals(64, nonce.length)
        assertNotEquals(nonce, GoogleSignIn.newNonce())
    }

    @Test
    fun anIdTokenIsExchangedForASession() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
                """{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"user-1","email":"me@example.com"}}""",
            ).build(),
        )
        val secrets = InMemorySecretStore()
        val client = SupabaseClient(server.url("/").toString().trimEnd('/'), "test-key", secrets)

        client.signInWithIdToken("google", "id-token", "raw-nonce")

        val request = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=id_token", request.url.encodedPath + "?" + request.url.encodedQuery)
        assertEquals("test-key", request.headers["apikey"])
        val body = SupabaseClient.json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("google", body["provider"]?.jsonPrimitive?.contentOrNull)
        assertEquals("id-token", body["id_token"]?.jsonPrimitive?.contentOrNull)
        assertEquals("raw-nonce", body["nonce"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user-1", client.session?.userId)
        assertEquals("me@example.com", client.session?.email)
    }
}
