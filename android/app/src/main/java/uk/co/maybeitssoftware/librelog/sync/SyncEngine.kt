package uk.co.maybeitssoftware.librelog.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import uniffi.librelog_core.CoreException
import uniffi.librelog_core.IncomingOps
import uniffi.librelog_core.PairingSession
import uniffi.librelog_core.Store
import uniffi.librelog_core.parsePairingQr

/**
 * Drives text sync (SPEC.md §12.5): sign-in, journal-key setup, and the
 * push → pull → snapshot loop. The core seals and merges; this only moves
 * sealed strings between it and Supabase. Mirrors
 * apple/App/Sync/SyncEngine.swift.
 *
 * State is Compose snapshot state, so screens recompose as it changes. All
 * calls are made from the main thread; core and network work hops to IO.
 */
class SyncEngine(
    private val store: Store,
    private val client: SupabaseClient,
    private val secrets: SecretStore,
    private val deviceName: String,
    private val scope: CoroutineScope,
    /** Opens the Realtime channel for a user; null in tests. */
    private val realtimeFor: ((userId: String, onPing: () -> Unit) -> RealtimeChannel)? = null,
) {
    enum class Phase {
        /** Not signed in. The journal works fully; nothing leaves the device. */
        Off,
        /** Signed in, but this device doesn't hold the account's journal key. */
        NeedsKey,
        /** Signed in with the key: syncing. */
        On,
    }

    var phase by mutableStateOf(Phase.Off)
        private set
    var email by mutableStateOf<String?>(null)
        private set
    var isSyncing by mutableStateOf(false)
        private set
    var lastSynced by mutableStateOf<Long?>(null)
        private set
    var problem by mutableStateOf<SyncError?>(null)
        private set
    var pending by mutableStateOf(0u)
        private set
    /**
     * Set once, right after the first device creates the key, so the recovery
     * phrase can be shown before the user moves on.
     */
    var newRecoveryPhrase by mutableStateOf<String?>(null)

    /** Called after a pull merged something, so screens reload. */
    var onChange: (() -> Unit)? = null

    private var realtime: RealtimeChannel? = null
    private var pushJob: Job? = null
    private val running = Mutex()
    private var syncAgain = false

    private var pulledSinceSnapshot: Int
        get() = secrets.read(SecretStore.Item.PulledSinceSnapshot)?.decodeToString()?.toIntOrNull() ?: 0
        set(value) = secrets.write(SecretStore.Item.PulledSinceSnapshot, value.toString().encodeToByteArray())

    // MARK: Lifecycle

    /**
     * Called at launch and on returning to the foreground, and by
     * [SyncWorker] in the background with [realtime] off: a background run
     * syncs once and has no one to tell, so it opens no channel.
     */
    suspend fun start(realtime: Boolean = true) {
        val session = client.session ?: run { phase = Phase.Off; return }
        email = session.email
        val key = secrets.read(SecretStore.Item.JournalKey)
        if (key != null) {
            try {
                io { store.setJournalKey(key) }
            } catch (e: CoreException) {
                problem = syncError(e)
                return
            }
            phase = Phase.On
            if (realtime) connectRealtime(session.userId)
            syncNow()
        } else {
            adoptAccount(realtime)
        }
    }

    fun schedulePush() {
        if (phase != Phase.On) return
        pushJob?.cancel()
        pushJob = scope.launch {
            pending = io { store.pendingCount() }
            delay(1_000)
            syncNow()
        }
    }

    suspend fun syncNow() {
        if (phase != Phase.On) return
        if (!running.tryLock()) {
            syncAgain = true
            return
        }
        isSyncing = true
        try {
            do {
                syncAgain = false
                try {
                    push()
                    val merged = pull()
                    problem = null
                    lastSynced = System.currentTimeMillis()
                    if (merged) onChange?.invoke()
                    snapshotIfDue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing is acknowledged to the core until it has
                    // succeeded, so a failure leaves the cursors where they
                    // were and the next sync picks up from there.
                    val error = syncError(e)
                    if (error == SyncError.SignedOut) signOut()
                    problem = error
                    return
                }
            } while (syncAgain)
            pending = io { store.pendingCount() }
        } finally {
            isSyncing = false
            running.unlock()
        }
    }

    // MARK: Sign-in

    suspend fun sendSignInEmail(address: String) {
        problem = null
        client.sendSignInEmail(address.trim())
        email = address.trim()
    }

    suspend fun signIn(address: String, password: String) {
        problem = null
        client.signIn(address.trim(), password)
        email = address.trim()
        adoptAccount()
    }

    suspend fun verify(code: String) {
        val address = email ?: throw SyncError.SignedOut
        problem = null
        client.verify(address, code.filter(Char::isDigit))
        adoptAccount()
    }

    /** Sign in with Google: the ID token from [GoogleSignIn] and its raw nonce. */
    suspend fun signInWithGoogle(idToken: String, nonce: String) {
        problem = null
        client.signInWithIdToken("google", idToken, nonce)
        adoptAccount()
    }

    /** The `librelog://auth…` link from the sign-in email. */
    suspend fun handle(uri: String) {
        if (!uri.startsWith("librelog://auth")) return
        problem = null
        try {
            client.handleRedirect(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = syncError(e)
            return
        }
        adoptAccount()
    }

    suspend fun signOut() {
        realtime?.close()
        realtime = null
        client.signOut()
        secrets.delete(SecretStore.Item.JournalKey)
        io { store.forgetJournalKey() }
        phase = Phase.Off
        email = null
        problem = null
    }

    /**
     * Deletes the account and everything synced to it, then forgets it here.
     * The journal on this device is untouched, and is queued to upload in
     * full if the user signs in to a new account later.
     */
    suspend fun deleteAccount() {
        client.rpc("delete_account", buildJsonObject {})
        signOut()
        io { store.resetSync() }
    }

    // MARK: Journal key

    /**
     * After sign-in: create the account's key if it has none, or use this
     * device's stored key if it matches; otherwise ask for it.
     *
     * Failures land in [problem], which the signed-out screen shows too, so
     * a sign-in that got a session but couldn't finish says why.
     */
    private suspend fun adoptAccount(realtime: Boolean = true) {
        try {
            val reg = register(nameEnc = "")
            email = client.session?.email ?: email

            val check = reg?.keyCheck
            if (check != null) {
                val key = secrets.read(SecretStore.Item.JournalKey)
                if (key != null && io { store.keyMatches(key, check) }) activate(key, realtime) else phase = Phase.NeedsKey
                return
            }

            // A brand-new account: this device makes the key.
            val key = io { store.generateJournalKey().also { store.setJournalKey(it) } }
            val ourCheck = io { store.keyCheck() }
            val stored = client.rpc("set_key_check", buildJsonObject { put("p_key_check", ourCheck) })
                .jsonPrimitive.contentOrNull ?: ""
            if (io { store.keyMatches(key, stored) }) {
                newRecoveryPhrase = io { store.recoveryPhrase() }
                activate(key, realtime)
            } else {
                // Another device set it first, a moment ago.
                io { store.forgetJournalKey() }
                phase = Phase.NeedsKey
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = syncError(e)
            problem = error
            if (error == SyncError.SignedOut) phase = Phase.Off
        }
    }

    /** Restores the key from the 24 words. Throws if they're not this account's. */
    suspend fun useRecoveryPhrase(phrase: String) {
        val key = io { store.keyFromRecoveryPhrase(phrase) }
        val check = register(nameEnc = "")?.keyCheck
        if (check == null || !io { store.keyMatches(key, check) }) throw WrongPhrase()
        activate(key)
    }

    class WrongPhrase : Exception("Those words don't match this account's journal key.")

    /** New device: shows a QR code and waits for an existing device to answer. */
    suspend fun receiveKeyByPairing(session: PairingSession) {
        client.rpc("pairing_open", buildJsonObject { put("p_id", session.id()) })
        repeat(120) { // ten minutes, like the server's expiry
            delay(5_000)
            val payload = client.rpc("pairing_take", buildJsonObject { put("p_id", session.id()) })
            if (payload !is JsonNull) {
                val text = payload.jsonPrimitive.contentOrNull
                if (text != null) {
                    activate(io { session.receive(text) })
                    return
                }
            }
        }
        throw SyncError.Server(408, "No device answered the pairing code.")
    }

    /** Existing device: answers a scanned QR code with the sealed key. */
    suspend fun approvePairing(qr: String) {
        val code = io { parsePairingQr(qr) }
        val payload = io { store.sealKeyForPairing(code.publicKey) }
        client.rpc("pairing_fulfil", buildJsonObject { put("p_id", code.id); put("p_payload", payload) })
    }

    private suspend fun activate(key: ByteArray, realtime: Boolean = true) {
        io { store.setJournalKey(key) }
        secrets.write(SecretStore.Item.JournalKey, key)
        // Register again, now with the device's name sealed.
        register(nameEnc = io { store.sealDeviceName(deviceName) })
        phase = Phase.On
        if (realtime) client.session?.userId?.let(::connectRealtime)
        syncNow()
    }

    private suspend fun register(nameEnc: String): Registration? {
        val result = client.rpc(
            "register_device",
            buildJsonObject { put("p_device_id", store.deviceId()); put("p_name_enc", nameEnc) },
        )
        return SupabaseClient.json.decodeFromJsonElement(ListSerializer(Registration.serializer()), result).firstOrNull()
    }

    // MARK: The loop

    private suspend fun push() {
        while (true) {
            val batch = io { store.outgoingBatch(BATCH.toUInt()) } ?: break
            client.rpc(
                "push_ops",
                buildJsonObject {
                    put("p_device_id", store.deviceId())
                    put("p_batch_id", batch.batchId)
                    put("p_payloads", buildJsonArray { add(JsonPrimitive(batch.payload)) })
                },
            )
            io { store.pushSucceeded(batch.batchId) }
            pending = io { store.pendingCount() }
        }
    }

    /** Returns true if anything merged. */
    private suspend fun pull(): Boolean {
        var merged = false
        while (true) {
            val rows = try {
                val result = client.rpc(
                    "pull_ops",
                    buildJsonObject {
                        put("p_device_id", store.deviceId())
                        put("p_after", io { store.syncCursor() })
                        put("p_limit", BATCH)
                    },
                )
                SupabaseClient.json.decodeFromJsonElement(ListSerializer(PulledRow.serializer()), result)
            } catch (e: SyncError.BehindCompaction) {
                loadSnapshot()
                merged = true
                continue
            }
            if (rows.isEmpty()) return merged
            val result = io { store.applyIncoming(rows.map { IncomingOps(it.seq, it.deviceId, it.payload) }) }
            pulledSinceSnapshot += rows.size
            merged = merged || result.changed > 0u
            if (rows.size < BATCH) return merged
        }
    }

    private suspend fun loadSnapshot() {
        val result = client.rpc("get_snapshot", buildJsonObject { put("p_device_id", store.deviceId()) })
        val snap = SupabaseClient.json.decodeFromJsonElement(ListSerializer(SnapshotRow.serializer()), result).firstOrNull()
            ?: throw SyncError.Server(404, "no snapshot to catch up from")
        io { store.applySnapshot(snap.seq, snap.payload) }
        pulledSinceSnapshot = 0
    }

    private suspend fun snapshotIfDue() {
        if (pulledSinceSnapshot < SNAPSHOT_EVERY) return
        val snap = io { store.snapshot() }
        client.rpc(
            "put_snapshot",
            buildJsonObject { put("p_device_id", store.deviceId()); put("p_seq", snap.seq); put("p_payload", snap.payload) },
        )
        pulledSinceSnapshot = 0
    }

    /** Main thread only, like the rest of the engine, so the check can't race. */
    private fun connectRealtime(userId: String) {
        if (realtime != null) return
        realtime = realtimeFor?.invoke(userId) { scope.launch { syncNow() } }?.also { it.connect() }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Anything a sync step threw, as a problem to show. */
    private fun syncError(e: Exception): SyncError = when (e) {
        is SyncError -> e
        // SerializationException is an IllegalArgumentException; so is
        // asking for jsonPrimitive on a reply of the wrong shape.
        is IllegalArgumentException -> SyncError.Unexpected("The server's reply didn't make sense: ${e.message.orEmpty()}".trimEnd(' ', ':'))
        is CoreException -> SyncError.Unexpected(e.message ?: "The journal couldn't apply what came from the server")
        else -> SyncError.Unexpected(e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        const val BATCH = 500
        const val SNAPSHOT_EVERY = 5_000
    }
}

// MARK: Wire types

@Serializable
private data class Registration(
    @SerialName("last_seq") val lastSeq: Long,
    @SerialName("sync_until") val syncUntil: String? = null,
    @SerialName("key_check") val keyCheck: String? = null,
    @SerialName("quota_bytes") val quotaBytes: Long,
    @SerialName("bytes_used") val bytesUsed: Long,
)

@Serializable
private data class PulledRow(
    val seq: Long,
    @SerialName("device_id") val deviceId: String,
    val payload: String,
)

@Serializable
private data class SnapshotRow(val seq: Long, val payload: String)
