package uk.co.maybeitssoftware.librelog.sync

import android.util.Base64
import java.io.IOException
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A signed-in Supabase Auth session. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch milliseconds. */
    val expiresAt: Long,
    val userId: String,
    val email: String? = null,
) {
    fun needsRefresh(now: Long = System.currentTimeMillis()) = expiresAt - now < 60_000
}

/**
 * What a sync RPC can fail with. The database raises `PTxxx` codes, which
 * PostgREST returns as HTTP status `xxx` (supabase/README.md).
 */
sealed class SyncError(message: String) : Exception(message) {
    data object SignedOut : SyncError("Signed out")
    data object NotActive : SyncError("Sync isn't active for this account")
    data object UnknownDevice : SyncError("This device was removed from sync")
    data object BehindCompaction : SyncError("Catching up from a snapshot")
    data object OverQuota : SyncError("Sync storage is full")
    data object Offline : SyncError("Offline")
    data class Server(val code: Int, val detail: String) : SyncError("$code: $detail")
}

/**
 * Supabase Auth and the `librelog` RPCs over OkHttp. No SDK: the surface is a
 * handful of POSTs. Mirrors apple/App/Sync/SupabaseClient.swift.
 */
class SupabaseClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val secrets: SecretStore,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val refreshLock = Mutex()

    var session: Session? = secrets.read(SecretStore.Item.Session)?.let {
        runCatching { json.decodeFromString<Session>(it.decodeToString()) }.getOrNull()
    }
        private set

    // MARK: Auth

    /**
     * Emails a sign-in message. With the default template it's a link back
     * into the app; with the custom template it's a 6-digit code.
     */
    suspend fun sendSignInEmail(email: String) {
        val url = "$baseUrl/auth/v1/otp".toHttpUrl().newBuilder().addQueryParameter("redirect_to", "librelog://auth").build()
        post(url.toString(), buildJsonObject { put("email", email); put("create_user", true) }, auth = false)
    }

    suspend fun verify(email: String, code: String) {
        val body = buildJsonObject { put("type", "email"); put("email", email); put("token", code) }
        storeSession(post("$baseUrl/auth/v1/verify", body, auth = false))
    }

    /**
     * Email and password, for accounts made with one in the dashboard (the
     * App Review account). Everyone else signs in with a code or a provider.
     */
    suspend fun signIn(email: String, password: String) {
        val url = "$baseUrl/auth/v1/token".toHttpUrl().newBuilder().addQueryParameter("grant_type", "password").build()
        val body = buildJsonObject { put("email", email); put("password", password) }
        storeSession(post(url.toString(), body, auth = false))
    }

    /**
     * Native sign-in with a provider's OpenID token (Google's, from Credential
     * Manager): Supabase checks the token's audience and that it carries the
     * SHA-256 of [nonce], then answers with a session.
     */
    suspend fun signInWithIdToken(provider: String, idToken: String, nonce: String) {
        val url = "$baseUrl/auth/v1/token".toHttpUrl().newBuilder().addQueryParameter("grant_type", "id_token").build()
        val body = buildJsonObject { put("provider", provider); put("id_token", idToken); put("nonce", nonce) }
        storeSession(post(url.toString(), body, auth = false))
    }

    /** The `librelog://auth#access_token=…` link from the sign-in email. */
    fun handleRedirect(uri: String) {
        val tail = uri.substringAfter('#', missingDelimiterValue = "").ifEmpty { uri.substringAfter('?', "") }
        if (tail.isEmpty()) throw SyncError.SignedOut
        val params = tail.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        params["error_description"]?.let { throw SyncError.Server(400, it) }
        val access = params["access_token"] ?: throw SyncError.SignedOut
        val refresh = params["refresh_token"] ?: throw SyncError.SignedOut
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600
        val claims = jwtClaims(access)
        save(
            Session(
                accessToken = access,
                refreshToken = refresh,
                expiresAt = System.currentTimeMillis() + expiresIn * 1000,
                userId = claims["sub"]?.jsonPrimitive?.contentOrNull ?: "",
                email = claims["email"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }

    suspend fun signOut() {
        session?.let { s ->
            runCatching {
                withContext(Dispatchers.IO) {
                    http.newCall(
                        Request.Builder().url("$baseUrl/auth/v1/logout")
                            .header("apikey", apiKey)
                            .header("Authorization", "Bearer ${s.accessToken}")
                            .post(ByteArray(0).toRequestBody())
                            .build(),
                    ).execute().close()
                }
            }
        }
        forget()
    }

    /** A current access token, refreshing first if it's about to expire. */
    suspend fun accessToken(): String = refreshLock.withLock {
        val s = session ?: throw SyncError.SignedOut
        if (s.needsRefresh()) refresh(s)
        session?.accessToken ?: throw SyncError.SignedOut
    }

    /**
     * After the server refused [rejected]: refreshes, unless another call
     * already has since that token was handed out.
     */
    private suspend fun refreshAfterRejection(rejected: String) = refreshLock.withLock {
        val s = session ?: throw SyncError.SignedOut
        if (s.accessToken == rejected) refresh(s)
    }

    /**
     * Swaps the refresh token for a new session. If Auth refuses it (revoked,
     * already used, the user deleted: a 4xx such as `invalid_grant` or
     * "Refresh Token Not Found"), the session is over and this is signed out;
     * that never fixes itself by retrying. Offline and 5xx stay as they are.
     */
    private suspend fun refresh(s: Session) {
        val url = "$baseUrl/auth/v1/token".toHttpUrl().newBuilder().addQueryParameter("grant_type", "refresh_token").build()
        try {
            storeSession(post(url.toString(), buildJsonObject { put("refresh_token", s.refreshToken) }, auth = false))
        } catch (e: SyncError.Server) {
            if (!refreshRefused(e.code)) throw e
            forget()
            throw SyncError.SignedOut
        }
    }

    // MARK: RPC

    /** Calls `librelog.<name>` and returns its JSON result. */
    suspend fun rpc(name: String, args: JsonObject): JsonElement {
        val text = post("$baseUrl/rest/v1/rpc/$name", args, auth = true)
        return if (text.isBlank()) JsonPrimitive(null as String?) else json.parseToJsonElement(text)
    }

    // MARK: Plumbing

    private suspend fun post(url: String, body: JsonObject, auth: Boolean, retried: Boolean = false): String {
        val bearer = if (auth) accessToken() else null
        val request = Request.Builder().url(url)
            .header("apikey", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .apply {
                if (bearer != null) {
                    header("Authorization", "Bearer $bearer")
                    header("Content-Profile", "librelog")
                }
            }
            .build()
        val (status, text) = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response -> response.code to response.body.string() }
            } catch (e: IOException) {
                throw SyncError.Offline
            }
        }
        if (status in 200..299) return text
        // A 401 can be a token revoked or expired early (clock skew, a
        // refresh elsewhere): refresh once and retry before calling it signed
        // out, since signing out forgets the journal key.
        if (status == 401 && bearer != null && !retried) {
            refreshAfterRejection(bearer)
            return post(url, body, auth, retried = true)
        }
        throw errorFor(status, text, auth)
    }

    private fun errorFor(status: Int, text: String, auth: Boolean): SyncError {
        val message = runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            (o["message"] ?: o["msg"] ?: o["error_description"])?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: text
        return when {
            status == 401 && auth -> SyncError.SignedOut
            status == 402 -> SyncError.NotActive
            status == 404 && message.contains("unknown device") -> SyncError.UnknownDevice
            status == 409 -> SyncError.BehindCompaction
            status == 413 -> SyncError.OverQuota
            else -> SyncError.Server(status, message)
        }
    }

    private fun storeSession(text: String) {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw SyncError.Server(500, "unexpected auth response")
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull ?: throw SyncError.Server(500, "unexpected auth response")
        val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: throw SyncError.Server(500, "unexpected auth response")
        val user = o["user"] as? JsonObject
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        save(
            Session(
                accessToken = access,
                refreshToken = refresh,
                expiresAt = System.currentTimeMillis() + expiresIn * 1000,
                userId = user?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: jwtClaims(access)["sub"]?.jsonPrimitive?.contentOrNull ?: "",
                email = user?.get("email")?.jsonPrimitive?.contentOrNull,
            ),
        )
    }

    private fun forget() {
        session = null
        secrets.delete(SecretStore.Item.Session)
    }

    private fun save(s: Session) {
        session = s
        secrets.write(SecretStore.Item.Session, json.encodeToString(Session.serializer(), s).encodeToByteArray())
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val JSON = "application/json".toMediaType()

        /** Whether Auth refusing a refresh with [status] means the session is gone. */
        fun refreshRefused(status: Int) = status in 400..499 && status != 408 && status != 429

        fun jwtClaims(jwt: String): JsonObject {
            val parts = jwt.split('.')
            if (parts.size < 2) return JsonObject(emptyMap())
            return runCatching {
                val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                json.parseToJsonElement(bytes.decodeToString()).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
        }
    }
}
