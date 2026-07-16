package com.github.jvsena42.mandacaru.domain.nostr

import kotlinx.coroutines.flow.Flow

/**
 * Nostr relay client (WebSocket over TLS). Routes through a local Tor SOCKS proxy (e.g. Orbot)
 * when enabled in Settings - see [com.github.jvsena42.mandacaru.data.network.resolveTorProxySettings].
 */
interface NostrClient {
    /** Every event received from any connected relay, deduplicated by id. */
    val incomingEvents: Flow<NostrEvent>

    /** Opens (or reuses) WebSocket connections to every relay in [relayUrls]. */
    suspend fun connect(relayUrls: List<String>)

    suspend fun publish(event: NostrEvent): Result<Unit>

    fun subscribe(subscriptionId: String, filter: NostrFilter)

    fun unsubscribe(subscriptionId: String)

    fun disconnect()
}
