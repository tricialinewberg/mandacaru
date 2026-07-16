package com.github.jvsena42.mandacaru.presentation.utils

import java.net.URI

/**
 * Client-side validator for relay URLs entered in Settings. Requires a `wss://` scheme - Nostr
 * relays are WebSocket-over-TLS, and accepting plaintext `ws://` would give a false sense of
 * privacy (especially alongside the Tor setting, which only hides *who* you connect to, not
 * traffic already sent in the clear once it reaches the relay).
 */
object NostrRelayValidator {

    sealed interface Result {
        object Valid : Result
        object Empty : Result
        object InvalidScheme : Result
        object InvalidFormat : Result
    }

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Empty
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return Result.InvalidFormat
        // Checked before the host so a bare hostname like "relay.damus.io" (no scheme at all)
        // reports the specific, actionable "needs wss://" reason rather than a generic format error.
        if (!uri.scheme.equals("wss", ignoreCase = true)) return Result.InvalidScheme
        if (uri.host.isNullOrBlank()) return Result.InvalidFormat
        return Result.Valid
    }
}
