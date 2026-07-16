package com.github.jvsena42.mandacaru.data.nostr

import android.util.Log
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.data.network.resolveTorProxySettings
import com.github.jvsena42.mandacaru.domain.nostr.NostrClient
import com.github.jvsena42.mandacaru.domain.nostr.NostrEvent
import com.github.jvsena42.mandacaru.domain.nostr.NostrFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NostrClientImpl(private val preferencesDataSource: PreferencesDataSource) : NostrClient {

    @Volatile
    private var client: OkHttpClient = buildClient(proxy = null)

    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val activeSubscriptions = ConcurrentHashMap<String, NostrFilter>()
    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()

    private val _incomingEvents = MutableSharedFlow<NostrEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val incomingEvents = _incomingEvents.asSharedFlow()

    override suspend fun connect(relayUrls: List<String>) {
        val settings = preferencesDataSource.resolveTorProxySettings()
        if (settings.enabled && settings.port == null) {
            Log.w(TAG, "Tor is enabled but the configured SOCKS port is invalid - refusing to connect over clearnet")
            return
        }
        client = buildClient(
            proxy = settings.port?.takeIf { settings.enabled }
                ?.let { port -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(settings.host, port)) },
        )
        relayUrls.forEach { url -> connectToRelay(url) }
    }

    private fun buildClient(proxy: Proxy?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket, no read timeout
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .apply { proxy?.let { proxy(it) } }
        .build()

    private fun connectToRelay(url: String) {
        if (sockets.containsKey(url)) return
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, RelayListener(url))
        sockets[url] = socket
    }

    override suspend fun publish(event: NostrEvent): Result<Unit> = runCatching {
        val message = JSONArray().apply {
            put("EVENT")
            put(event.toJson())
        }.toString()
        if (sockets.isEmpty()) error("No relay connections open")
        sockets.values.forEach { it.send(message) }
    }

    override fun subscribe(subscriptionId: String, filter: NostrFilter) {
        activeSubscriptions[subscriptionId] = filter
        val message = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            put(filter.toJson())
        }.toString()
        sockets.values.forEach { it.send(message) }
    }

    override fun unsubscribe(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId)
        val message = JSONArray().apply {
            put("CLOSE")
            put(subscriptionId)
        }.toString()
        sockets.values.forEach { it.send(message) }
    }

    override fun disconnect() {
        sockets.values.forEach { it.close(NORMAL_CLOSURE, "client disconnect") }
        sockets.clear()
        activeSubscriptions.clear()
    }

    private inner class RelayListener(private val relayUrl: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.d(TAG, "connected: $relayUrl")
            // Re-issue subscriptions that were created before this relay finished connecting.
            activeSubscriptions.forEach { (id, filter) ->
                val message = JSONArray().apply {
                    put("REQ")
                    put(id)
                    put(filter.toJson())
                }.toString()
                webSocket.send(message)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.w(TAG, "relay failure: $relayUrl", t)
            sockets.remove(relayUrl)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sockets.remove(relayUrl)
        }

        private fun handleMessage(text: String) {
            runCatching {
                val array = JSONArray(text)
                when (array.optString(0, "")) {
                    "EVENT" -> {
                        val eventJson = array.getJSONObject(2)
                        val event = NostrEvent.fromJson(eventJson)
                        if (seenEventIds.add(event.id)) {
                            _incomingEvents.tryEmit(event)
                        }
                    }
                    "OK" -> Log.d(TAG, "OK: $text")
                    "NOTICE" -> Log.d(TAG, "NOTICE: $text")
                    "EOSE" -> Unit
                    else -> Unit
                }
            }.onFailure { e -> Log.w(TAG, "failed to parse relay message: $text", e) }
        }
    }

    private companion object {
        const val TAG = "NostrClientImpl"
        const val EVENT_BUFFER = 64
        const val NORMAL_CLOSURE = 1000
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val PING_INTERVAL_SECONDS = 30L
    }
}
