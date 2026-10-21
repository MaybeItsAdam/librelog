package uk.co.maybeitssoftware.librelog

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uk.co.maybeitssoftware.librelog.sync.InMemorySecretStore
import uk.co.maybeitssoftware.librelog.sync.SecretStore
import uk.co.maybeitssoftware.librelog.sync.Session
import uk.co.maybeitssoftware.librelog.sync.SupabaseClient
import uk.co.maybeitssoftware.librelog.sync.SyncEngine
import uk.co.maybeitssoftware.librelog.sync.SyncError
import uniffi.librelog_core.Block
import uniffi.librelog_core.BlockKind
import uniffi.librelog_core.Store

/**
 * The sync engine against a fake Supabase that behaves like the `librelog`
 * RPCs (supabase/README.md), with the real core doing the sealing and
 * merging. Runs on the JVM, loading the host build of the core through JNA.
 */
class SyncEngineTest {
    /** One account's worth of server state, as the database keeps it. */
    private class FakeServer : Dispatcher() {
        var keyCheck: String? = null
        val log = mutableListOf<Triple<Long, String, String>>() // seq, device, payload
        val batches = mutableSetOf<String>()
        var snapshot: Pair<Long, String>? = null
        /** Ops at or below this seq have been compacted away. */
        var compactedThrough = 0L
        /** RPCs that answer with something of the wrong shape, or fail outright. */
        val garbled = mutableSetOf<String>()
        val failing = mutableMapOf<String, Int>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val name = request.url.encodedPath.substringAfter("/rest/v1/rpc/")
            if (name in garbled) return json(200, buildJsonObject { put("not", "a list") })
            failing[name]?.let { return json(it, JsonPrimitive("broken"), message = true) }
            val args = SupabaseClient.json.parseToJsonElement(request.body!!.utf8()).jsonObject
            if (request.headers["Content-Profile"] != "librelog") return json(400, JsonPrimitive("wrong schema"))
            return synchronized(this) {
                when (name) {
                    "register_device" -> json(200, buildJsonArray {
                        add(buildJsonObject {
                            put("last_seq", log.lastOrNull()?.first ?: 0)
                            put("sync_until", "2027-10-03T00:00:00Z")
                            put("key_check", keyCheck?.let(::JsonPrimitive) ?: JsonNull)
                            put("quota_bytes", 524_288_000)
                            put("bytes_used", 0)
                        })
                    })
                    "set_key_check" -> {
                        if (keyCheck == null) keyCheck = args.str("p_key_check")
                        json(200, JsonPrimitive(keyCheck))
                    }
                    "push_ops" -> {
                        val batch = args.str("p_batch_id")
                        if (batches.add(batch)) {
                            for (p in args["p_payloads"]!!.jsonArray) {
                                log += Triple((log.lastOrNull()?.first ?: compactedThrough) + 1, args.str("p_device_id"), p.jsonPrimitive.content)
                            }
                        }
                        json(200, JsonPrimitive(log.last().first))
                    }
                    "pull_ops" -> {
                        val after = args["p_after"]!!.jsonPrimitive.long
                        if (after < compactedThrough) return@synchronized json(409, JsonPrimitive("cursor is behind compaction"), message = true)
                        val limit = args["p_limit"]!!.jsonPrimitive.int
                        json(200, JsonArray(log.filter { it.first > after }.take(limit).map { (seq, dev, payload) ->
                            buildJsonObject { put("seq", seq); put("device_id", dev); put("payload", payload) }
                        }))
                    }
                    "get_snapshot" -> json(200, JsonArray(listOfNotNull(snapshot?.let { (seq, payload) ->
                        buildJsonObject { put("seq", seq); put("payload", payload) }
                    })))
                    else -> json(404, JsonPrimitive("unknown rpc $name"), message = true)
                }
            }
        }

        private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.contentOrNull!!

        private fun json(code: Int, body: JsonElement, message: Boolean = false): MockResponse {
            val text = if (message) buildJsonObject { put("message", body) }.toString() else body.toString()
            return MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(text).build()
        }
    }

    private val server = MockWebServer()
    private val fake = FakeServer()
    private val dirs = mutableListOf<File>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private class Device(val store: Store, val sync: SyncEngine)

    /** A device already signed in to the shared account. */
    private fun device(name: String): Device {
        val dir = Files.createTempDirectory("librelog-$name").toFile().also { dirs += it }
        val store = Store.open(File(dir, "journal.sqlite").path)
        val secrets = InMemorySecretStore()
        val session = Session("token", "refresh", System.currentTimeMillis() + 3_600_000, "user-1", "me@example.com")
        secrets.write(SecretStore.Item.Session, SupabaseClient.json.encodeToString(Session.serializer(), session).encodeToByteArray())
        val client = SupabaseClient(server.url("/").toString().trimEnd('/'), "test-key", secrets)
        return Device(store, SyncEngine(store, client, secrets, name, scope))
    }

    private fun para(text: String) = listOf(Block(BlockKind.Paragraph, text, emptyList(), 0u))

    @Test
    fun firstDeviceCreatesTheKeyAndASecondJoinsWithTheRecoveryPhrase() = runBlocking {
        val phone = device("phone")
        phone.sync.start()
        assertEquals(SyncEngine.Phase.On, phone.sync.phase)
        val phrase = phone.sync.newRecoveryPhrase
        assertNotNull("first device shows the recovery phrase", phrase)
        assertEquals(24, phrase!!.split(' ').size)

        val entry = phone.store.createEntry(null, "2026-10-03", "08:30")
        phone.store.saveBody(entry.id, para("Coffee by the canal"))
        phone.sync.syncNow()
        assertEquals(0u, phone.store.pendingCount())
        assertTrue("the server only ever sees ciphertext", fake.log.none { it.third.contains("canal") })

        val tablet = device("tablet")
        tablet.sync.start()
        assertEquals("a second device needs the key", SyncEngine.Phase.NeedsKey, tablet.sync.phase)
        tablet.sync.useRecoveryPhrase(phrase)
        assertEquals(SyncEngine.Phase.On, tablet.sync.phase)
        assertEquals(para("Coffee by the canal"), tablet.store.entry(entry.id).body)

        // Edits flow back the other way.
        tablet.store.setRating(entry.id, 4.0)
        tablet.sync.syncNow()
        phone.sync.syncNow()
        assertEquals(4.0, phone.store.entry(entry.id).rating!!, 0.0)
    }

    @Test
    fun aWrongRecoveryPhraseIsRefused() = runBlocking {
        device("phone").sync.start()
        val other = device("other")
        other.sync.start()
        val wrong = other.store.recoveryPhraseFor()
        val failed = runCatching { other.sync.useRecoveryPhrase(wrong) }.isFailure
        assertTrue(failed)
        assertEquals(SyncEngine.Phase.NeedsKey, other.sync.phase)
    }

    @Test
    fun aDeviceBehindCompactionCatchesUpFromTheSnapshot() = runBlocking {
        val phone = device("phone")
        phone.sync.start()
        val phrase = phone.sync.newRecoveryPhrase!!
        val entry = phone.store.createEntry(null, "2026-10-03", null)
        phone.store.saveBody(entry.id, para("Before compaction"))
        phone.sync.syncNow()

        // The phone uploads a snapshot and the server trims the log under it.
        val snap = phone.store.snapshot()
        synchronized(fake) {
            fake.snapshot = snap.seq to snap.payload
            fake.compactedThrough = snap.seq
            fake.log.removeAll { it.first <= snap.seq }
        }

        val laptop = device("laptop")
        laptop.sync.start()
        laptop.sync.useRecoveryPhrase(phrase)
        assertEquals(SyncEngine.Phase.On, laptop.sync.phase)
        assertEquals(null, laptop.sync.problem)
        assertEquals(para("Before compaction"), laptop.store.entry(entry.id).body)
        assertFalse(laptop.store.pendingCount() > 1000u)
    }

    @Test
    fun aReplyOfTheWrongShapeIsAProblemNotACrash() = runBlocking {
        val phone = device("phone")
        phone.sync.start()
        val entry = phone.store.createEntry(null, "2026-10-03", null)
        phone.store.saveBody(entry.id, para("Still here"))
        val cursor = phone.store.syncCursor()

        synchronized(fake) { fake.garbled += "pull_ops" }
        phone.sync.syncNow()
        assertTrue(phone.sync.problem is SyncError.Unexpected)
        assertEquals(SyncEngine.Phase.On, phone.sync.phase)
        assertEquals("the cursor didn't move", cursor, phone.store.syncCursor())

        synchronized(fake) { fake.garbled.clear() }
        phone.sync.syncNow()
        assertEquals(null, phone.sync.problem)
    }

    @Test
    fun aSignInThatCantSetUpTheAccountSaysWhy() = runBlocking {
        synchronized(fake) { fake.failing["register_device"] = 500 }
        val phone = device("phone")
        phone.sync.start()
        assertEquals(SyncEngine.Phase.Off, phone.sync.phase)
        assertEquals(SyncError.Server(500, "broken"), phone.sync.problem)
    }
}

/** A valid phrase for a key that isn't the account's. */
private fun Store.recoveryPhraseFor(): String {
    val key = generateJournalKey()
    setJournalKey(key)
    return recoveryPhrase().also { forgetJournalKey() }
}
