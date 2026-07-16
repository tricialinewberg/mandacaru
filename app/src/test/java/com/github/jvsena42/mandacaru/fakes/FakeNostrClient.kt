package com.github.jvsena42.mandacaru.fakes

import com.github.jvsena42.mandacaru.domain.nostr.NostrClient
import com.github.jvsena42.mandacaru.domain.nostr.NostrEvent
import com.github.jvsena42.mandacaru.domain.nostr.NostrFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A single in-memory "relay": every [FakeNostrClient] built on top of the same instance sees
 * every event any of them publishes, standing in for a real relay's fan-out to every connected
 * client. Replay is large enough to cover one CoinJoin round's handful of events regardless of
 * which peer's collector subscribes first - matching how a real relay would resend backlog to a
 * subscription instead of silently dropping events published before it connected.
 */
class FakeNostrRelay {
    val events = MutableSharedFlow<NostrEvent>(replay = REPLAY_CAPACITY, extraBufferCapacity = REPLAY_CAPACITY)

    private companion object {
        const val REPLAY_CAPACITY = 200
    }
}

/** Fake [NostrClient] that publishes to, and reads from, a shared [FakeNostrRelay] - subscription filters are ignored, matching how CoinjoinEngine already filters client-side. */
class FakeNostrClient(private val relay: FakeNostrRelay) : NostrClient {
    override val incomingEvents: Flow<NostrEvent> = relay.events

    override suspend fun connect(relayUrls: List<String>) = Unit

    override suspend fun publish(event: NostrEvent): Result<Unit> {
        relay.events.emit(event)
        return Result.success(Unit)
    }

    override fun subscribe(subscriptionId: String, filter: NostrFilter) = Unit

    override fun unsubscribe(subscriptionId: String) = Unit

    override fun disconnect() = Unit
}
