package com.github.jvsena42.mandacaru.data.nostr

import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.domain.nostr.NostrEvent
import com.github.jvsena42.mandacaru.domain.nostr.NostrFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Exercises [NostrClientImpl]'s WebSocket-frame-level protocol (EVENT/REQ/CLOSE), connection
 * lifecycle, dedup-by-id, and Tor SOCKS wiring against a fake [WebSocket]/[WebSocketListener]
 * captured via the [NostrClientImpl.openWebSocket] seam - no real socket or relay is involved.
 */
class NostrClientImplTest {

    @Test
    fun `connect opens exactly one socket per relay url and is a no-op for an already-open relay`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)

        client.connect(listOf("wss://relay.one", "wss://relay.two"))
        assertEquals(2, factory.connections.size)
        // OkHttp rewrites ws(s):// to http(s):// internally in HttpUrl, so compare distinctness
        // rather than the literal "wss://" string - what matters is two *different* relays were
        // opened, not the exact scheme OkHttp's Request ends up storing it as.
        assertEquals(2, factory.connections.map { it.request.url.toString() }.distinct().size)

        client.connect(listOf("wss://relay.one"))
        assertEquals("reconnecting an already-open relay must not open a second socket", 2, factory.connections.size)
    }

    @Test
    fun `disconnect closes every open socket with the normal-closure code and clears state`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)
        client.connect(listOf("wss://relay.one", "wss://relay.two"))

        client.disconnect()

        factory.connections.forEach { connection ->
            assertEquals(1000, connection.socket.closeCode)
            assertEquals("client disconnect", connection.socket.closeReason)
        }
        val afterDisconnect = client.publish(sampleEvent())
        assertTrue("publish after disconnect should fail - no sockets remain", afterDisconnect.isFailure)
    }

    @Test
    fun `publish sends an EVENT frame to every connected relay`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)
        client.connect(listOf("wss://relay.one", "wss://relay.two"))
        val event = sampleEvent()

        val result = client.publish(event)

        assertTrue(result.isSuccess)
        factory.connections.forEach { connection ->
            assertEquals(1, connection.socket.sentMessages.size)
            val frame = JSONArray(connection.socket.sentMessages.single())
            assertEquals("EVENT", frame.getString(0))
            assertEquals(event.id, frame.getJSONObject(1).getString("id"))
        }
    }

    @Test
    fun `publish fails with no relay connections open`() = runBlocking {
        val client = NostrClientImpl(FakePreferencesDataSource(), FakeWebSocketFactory().opener)

        val result = client.publish(sampleEvent())

        assertTrue(result.isFailure)
        assertEquals("No relay connections open", result.exceptionOrNull()?.message)
    }

    @Test
    fun `subscribe sends a REQ frame immediately and re-issues it once the socket actually opens`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)
        client.connect(listOf("wss://relay.one"))
        val connection = factory.connections.single()
        val filter = NostrFilter(kinds = listOf(1), limit = 10)

        client.subscribe("sub-1", filter)
        assertEquals(1, connection.socket.sentMessages.size)

        // Simulate the handshake completing *after* subscribe() was already called - a realistic
        // race, since connect() returns before onOpen fires. The existing subscription must be
        // re-sent to the now-open socket, not silently lost.
        connection.listener.onOpen(connection.socket, fakeResponse(connection.request))

        assertEquals(2, connection.socket.sentMessages.size)
        val reissued = JSONArray(connection.socket.sentMessages[1])
        assertEquals("REQ", reissued.getString(0))
        assertEquals("sub-1", reissued.getString(1))
    }

    @Test
    fun `unsubscribe sends a CLOSE frame and stops future re-issuance on new opens`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)
        client.connect(listOf("wss://relay.one"))
        val connection = factory.connections.single()
        client.subscribe("sub-1", NostrFilter(kinds = listOf(1)))

        client.unsubscribe("sub-1")
        val closeFrame = JSONArray(connection.socket.sentMessages.last())
        assertEquals("CLOSE", closeFrame.getString(0))
        assertEquals("sub-1", closeFrame.getString(1))

        val sentCountBeforeReopen = connection.socket.sentMessages.size
        connection.listener.onOpen(connection.socket, fakeResponse(connection.request))
        assertEquals(
            "an unsubscribed filter must not be re-issued on a later onOpen",
            sentCountBeforeReopen,
            connection.socket.sentMessages.size,
        )
    }

    @Test
    fun `incoming EVENT frames are parsed and emitted, deduplicated by event id`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)
        client.connect(listOf("wss://relay.one"))
        val connection = factory.connections.single()
        val event = sampleEvent()
        val frame = JSONArray().apply {
            put("EVENT")
            put("sub-1")
            put(event.toJson())
        }.toString()

        val received = mutableListOf<NostrEvent>()
        val job = launch(Dispatchers.Unconfined) { client.incomingEvents.collect { received.add(it) } }

        connection.listener.onMessage(connection.socket, frame)
        connection.listener.onMessage(connection.socket, frame) // duplicate id - must not be re-emitted
        job.cancel()

        assertEquals(1, received.size)
        assertEquals(event.id, received.single().id)
    }

    @Test
    fun `a relay failure removes its socket so a later publish no longer targets it`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)
        client.connect(listOf("wss://relay.one", "wss://relay.two"))
        val (failing, healthy) = factory.connections

        failing.listener.onFailure(failing.socket, RuntimeException("boom"), response = null)
        client.publish(sampleEvent())

        assertTrue(failing.socket.sentMessages.isEmpty())
        assertEquals(1, healthy.socket.sentMessages.size)
    }

    @Test
    fun `connect applies no proxy when Tor is disabled`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val client = NostrClientImpl(FakePreferencesDataSource(), factory.opener)

        client.connect(listOf("wss://relay.one"))

        assertNull(factory.connections.single().client.proxy)
    }

    @Test
    fun `connect routes through the configured SOCKS proxy when Tor is enabled`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val prefs = FakePreferencesDataSource(
            booleans = mapOf(PreferenceKeys.TOR_ENABLED to true),
            strings = mapOf(
                PreferenceKeys.TOR_SOCKS_HOST to "127.0.0.1",
                PreferenceKeys.TOR_SOCKS_PORT to "9050",
            ),
        )
        val client = NostrClientImpl(prefs, factory.opener)

        client.connect(listOf("wss://relay.one"))

        val proxy = factory.connections.single().client.proxy
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        assertEquals(InetSocketAddress("127.0.0.1", 9050), proxy?.address())
    }

    @Test
    fun `connect refuses to open any socket when Tor is enabled with an invalid port`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val prefs = FakePreferencesDataSource(
            booleans = mapOf(PreferenceKeys.TOR_ENABLED to true),
            strings = mapOf(PreferenceKeys.TOR_SOCKS_PORT to "not-a-port"),
        )
        val client = NostrClientImpl(prefs, factory.opener)

        client.connect(listOf("wss://relay.one"))

        assertTrue("must fail closed rather than silently connect over clearnet", factory.connections.isEmpty())
    }

    @Test
    fun `enabling Tor mid-session forces every already-connected relay to reconnect, not just the ones in this call`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val prefs = FakePreferencesDataSource()
        val client = NostrClientImpl(prefs, factory.opener)

        // Mirrors CoinjoinViewModel's real usage: an initial connect() to every discovery relay,
        // then later, unrelated connect() calls (registerInput/createPool) that only pass one.
        client.connect(listOf("wss://relay.one", "wss://relay.two"))
        val original = factory.connections.toList()
        original.forEach { assertNull(it.client.proxy) }

        prefs.setBoolean(PreferenceKeys.TOR_ENABLED, true)
        prefs.setString(PreferenceKeys.TOR_SOCKS_HOST, "127.0.0.1")
        prefs.setString(PreferenceKeys.TOR_SOCKS_PORT, "9050")
        client.connect(listOf("wss://relay.one"))

        // The original sockets must be torn down - left connected over clearnet would defeat the
        // point of just having enabled Tor.
        original.forEach { connection ->
            assertEquals(1000, connection.socket.closeCode)
            assertEquals("proxy configuration changed", connection.socket.closeReason)
        }
        // Both relay.one *and* relay.two (never mentioned in this second connect() call) must be
        // reopened through the new SOCKS-proxied client - not just relay.one.
        val reconnected = factory.connections.drop(original.size)
        assertEquals(2, reconnected.size)
        assertEquals(2, reconnected.map { it.request.url.toString() }.distinct().size)
        reconnected.forEach { connection ->
            assertEquals(Proxy.Type.SOCKS, connection.client.proxy?.type())
            assertEquals(InetSocketAddress("127.0.0.1", 9050), connection.client.proxy?.address())
        }
    }

    @Test
    fun `an unchanged proxy configuration does not force a reconnect`() = runBlocking {
        val factory = FakeWebSocketFactory()
        val prefs = FakePreferencesDataSource()
        val client = NostrClientImpl(prefs, factory.opener)
        client.connect(listOf("wss://relay.one"))

        client.connect(listOf("wss://relay.one", "wss://relay.two"))

        assertEquals(2, factory.connections.size)
        assertNull(factory.connections.first().socket.closeCode)
    }

    private fun sampleEvent() = NostrEvent(
        id = "a".repeat(64),
        pubKey = "b".repeat(64),
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = emptyList(),
        content = "hello",
        sig = "c".repeat(128),
    )

    private fun fakeResponse(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    private class FakePreferencesDataSource(
        booleans: Map<PreferenceKeys, Boolean> = emptyMap(),
        strings: Map<PreferenceKeys, String> = emptyMap(),
    ) : PreferencesDataSource {
        private val booleans = booleans.toMutableMap()
        private val strings = strings.toMutableMap()
        override suspend fun setString(key: PreferenceKeys, value: String) {
            strings[key] = value
        }
        override suspend fun getString(key: PreferenceKeys, defaultValue: String): String =
            strings[key] ?: defaultValue
        override suspend fun setBoolean(key: PreferenceKeys, value: Boolean) {
            booleans[key] = value
        }
        override suspend fun getBoolean(key: PreferenceKeys, defaultValue: Boolean): Boolean =
            booleans[key] ?: defaultValue
    }

    /** Fake [WebSocket] that records every sent/closed call instead of touching a real connection. */
    private class FakeWebSocket(private val request: Request) : WebSocket {
        val sentMessages = mutableListOf<String>()
        var closeCode: Int? = null
        var closeReason: String? = null

        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            sentMessages.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            sentMessages.add(bytes.utf8())
            return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            closeReason = reason
            return true
        }
        override fun cancel() = Unit
    }

    /**
     * Substitutes for [OkHttpClient.newWebSocket] via [NostrClientImpl]'s `openWebSocket`
     * constructor parameter: records the [OkHttpClient] (so proxy wiring can be inspected), the
     * [Request], and the [WebSocketListener] (so tests can manually fire onOpen/onMessage/onFailure
     * to simulate relay behavior), returning a [FakeWebSocket] instead of opening a real socket.
     */
    private class FakeWebSocketFactory {
        data class Connection(val client: OkHttpClient, val request: Request, val listener: WebSocketListener, val socket: FakeWebSocket)

        val connections = mutableListOf<Connection>()

        val opener: (OkHttpClient, Request, WebSocketListener) -> WebSocket = { client, request, listener ->
            val socket = FakeWebSocket(request)
            connections.add(Connection(client, request, listener, socket))
            socket
        }
    }
}
