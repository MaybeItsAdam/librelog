package uk.co.maybeitsadam.dailylog.sync

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The private Realtime Broadcast channel `user:<uid>` (SPEC.md §12.5).
 * `push_ops` sends an `ops` event on it, and every other online device pulls
 * straight away. It carries no journal data, only "something new", so a
 * dropped connection only delays sync until the next foreground pull.
 *
 * Speaks the Phoenix channel protocol Supabase Realtime uses: join with the
 * user's access token, heartbeat every 25 s, rejoin after a drop. Mirrors
 * apple/App/Sync/RealtimeChannel.swift.
 */
class RealtimeChannel(
    private val baseUrl: String,
    private val apiKey: String,
    private val topic: String,
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
    private val onPing: () -> Unit,
) {
    private val http = OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS).build()
    private val ref = AtomicInteger(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private var heartbeat: Job? = null
    private var retryDelayMs = 1_000L

    fun connect() {
        scope.launch {
            val token = runCatching { client.accessToken() }.getOrNull() ?: return@launch
            open(token)
        }
    }

    fun close() {
        closed = true
        heartbeat?.cancel()
        socket?.close(1001, null)
        socket = null
    }

    private fun open(token: String) {
        if (closed) return
        val url = baseUrl.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryDelayMs = 1_000
                send(
                    "realtime:$topic", "phx_join",
                    buildJsonObject {
                        putJsonObject("config") {
                            putJsonObject("broadcast") { put("self", false); put("ack", false) }
                            putJsonObject("presence") { put("key", "") }
                            put("postgres_changes", buildJsonArray { })
                            put("private", true)
                        }
                        put("access_token", token)
                    },
                )
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { SupabaseClient.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (msg["event"]?.jsonPrimitive?.contentOrNull != "broadcast") return
                val event = (msg["payload"] as? JsonObject)?.get("event")?.jsonPrimitive?.contentOrNull
                if (event == "ops") onPing()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnect()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect()
        })
    }

    private fun reconnect() {
        heartbeat?.cancel()
        if (closed) return
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000)
        scope.launch {
            delay(wait)
            // Fetch a fresh token: the old one may have expired while offline.
            connect()
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(25_000)
                send("phoenix", "heartbeat", JsonObject(emptyMap()))
            }
        }
    }

    private fun send(topic: String, event: String, payload: JsonObject) {
        val msg = buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", ref.incrementAndGet().toString())
        }
        socket?.send(msg.toString())
    }
}
