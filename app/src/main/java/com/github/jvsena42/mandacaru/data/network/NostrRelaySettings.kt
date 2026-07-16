package com.github.jvsena42.mandacaru.data.network

import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.domain.model.Constants

/** Relay URLs are newline-joined in a single preference value - simple to store/parse, and relay URLs never contain newlines. */
private const val RELAY_DELIMITER = "\n"

/**
 * Reads the user's configured Nostr relay list, falling back to [Constants.DEFAULT_NOSTR_RELAYS]
 * whenever nothing has been customized. Also defends the read path against ever returning an
 * empty or oversized list - even though [saveNostrRelays] callers are expected to enforce those
 * guardrails before persisting, a stale or externally-edited preference value shouldn't be able
 * to leave CoinJoin with zero relays to connect to.
 */
suspend fun PreferencesDataSource.resolveNostrRelays(): List<String> {
    val stored = getString(PreferenceKeys.NOSTR_RELAYS, "")
    val relays = stored.split(RELAY_DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
    return relays.ifEmpty { Constants.DEFAULT_NOSTR_RELAYS }.take(Constants.MAX_NOSTR_RELAYS)
}

suspend fun PreferencesDataSource.saveNostrRelays(relays: List<String>) {
    setString(PreferenceKeys.NOSTR_RELAYS, relays.joinToString(RELAY_DELIMITER))
}
