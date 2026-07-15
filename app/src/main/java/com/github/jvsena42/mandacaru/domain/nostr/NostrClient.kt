package com.github.jvsena42.mandacaru.domain.nostr

import kotlinx.coroutines.flow.Flow

/**
 * Clearnet Nostr relay client (WebSocket over TLS). Tor is intentionally out
 * of scope for now - see the CoinJoin settings section for the relay list.
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
