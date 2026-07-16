package com.github.jvsena42.mandacaru.data.network

import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.domain.model.Constants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies [resolveTorProxySettings] falls back to Orbot's default SOCKS address whenever the
 * user hasn't entered one - the Settings fields only show "127.0.0.1"/"9050" as a placeholder,
 * they never pre-fill the stored value, so this resolution has to happen at read time.
 */
class TorProxySettingsTest {

    @Test
    fun `Tor is disabled and defaults are used when nothing has ever been set`() = runBlocking {
        val settings = FakePreferencesDataSource().resolveTorProxySettings()

        assertFalse(settings.enabled)
        assertEquals(Constants.TOR_SOCKS_HOST_DEFAULT, settings.host)
        assertEquals(Constants.TOR_SOCKS_PORT_DEFAULT.toInt(), settings.port)
    }

    @Test
    fun `blank host and port stored explicitly still resolve to the Orbot defaults`() = runBlocking {
        val prefs = FakePreferencesDataSource()
        prefs.setBoolean(PreferenceKeys.TOR_ENABLED, true)
        prefs.setString(PreferenceKeys.TOR_SOCKS_HOST, "")
        prefs.setString(PreferenceKeys.TOR_SOCKS_PORT, "")

        val settings = prefs.resolveTorProxySettings()

        assertEquals(Constants.TOR_SOCKS_HOST_DEFAULT, settings.host)
        assertEquals(Constants.TOR_SOCKS_PORT_DEFAULT.toInt(), settings.port)
    }

    @Test
    fun `explicit host and port override the Orbot defaults`() = runBlocking {
        val prefs = FakePreferencesDataSource()
        prefs.setBoolean(PreferenceKeys.TOR_ENABLED, true)
        prefs.setString(PreferenceKeys.TOR_SOCKS_HOST, "10.0.2.2")
        prefs.setString(PreferenceKeys.TOR_SOCKS_PORT, "9150")

        val settings = prefs.resolveTorProxySettings()

        assertEquals(true, settings.enabled)
        assertEquals("10.0.2.2", settings.host)
        assertEquals(9150, settings.port)
    }

    @Test
    fun `a non-numeric port resolves to null instead of throwing`() = runBlocking {
        val prefs = FakePreferencesDataSource()
        prefs.setBoolean(PreferenceKeys.TOR_ENABLED, true)
        prefs.setString(PreferenceKeys.TOR_SOCKS_PORT, "not-a-port")

        val settings = prefs.resolveTorProxySettings()

        assertNull(settings.port)
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
