package com.github.jvsena42.mandacaru.data.network

import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.domain.model.Constants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies [resolveNostrRelays] falls back to [Constants.DEFAULT_NOSTR_RELAYS] whenever nothing
 * has been customized, and that the read path stays defensive (never empty, never past the
 * configured max) even if [saveNostrRelays]'s own guardrails were somehow bypassed - e.g. a
 * preference value edited outside the app, or a future caller that forgets to validate first.
 */
class NostrRelaySettingsTest {

    @Test
    fun `defaults are used when nothing has ever been set`() = runBlocking {
        val relays = FakePreferencesDataSource().resolveNostrRelays()

        assertEquals(Constants.DEFAULT_NOSTR_RELAYS, relays)
    }

    @Test
    fun `a blank stored value falls back to the defaults`() = runBlocking {
        val prefs = FakePreferencesDataSource()
        prefs.setString(PreferenceKeys.NOSTR_RELAYS, "")

        assertEquals(Constants.DEFAULT_NOSTR_RELAYS, prefs.resolveNostrRelays())
    }

    @Test
    fun `a custom list round-trips through save and resolve`() = runBlocking {
        val prefs = FakePreferencesDataSource()
        val custom = listOf("wss://relay.one.example", "wss://relay.two.example")

        prefs.saveNostrRelays(custom)

        assertEquals(custom, prefs.resolveNostrRelays())
    }

    @Test
    fun `blank lines and surrounding whitespace are trimmed out on read`() = runBlocking {
        val prefs = FakePreferencesDataSource()
        prefs.setString(PreferenceKeys.NOSTR_RELAYS, "  wss://relay.one.example \n\n wss://relay.two.example  \n")

        assertEquals(listOf("wss://relay.one.example", "wss://relay.two.example"), prefs.resolveNostrRelays())
    }

    @Test
    fun `a stored list beyond the max is clamped on read`() = runBlocking {
        val prefs = FakePreferencesDataSource()
        val tooMany = (1..Constants.MAX_NOSTR_RELAYS + 5).map { "wss://relay-$it.example" }
        prefs.setString(PreferenceKeys.NOSTR_RELAYS, tooMany.joinToString("\n"))

        val relays = prefs.resolveNostrRelays()

        assertEquals(Constants.MAX_NOSTR_RELAYS, relays.size)
        assertEquals(tooMany.take(Constants.MAX_NOSTR_RELAYS), relays)
    }

    private class FakePreferencesDataSource : PreferencesDataSource {
        private val strings = mutableMapOf<PreferenceKeys, String>()
        private val booleans = mutableMapOf<PreferenceKeys, Boolean>()
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
}
