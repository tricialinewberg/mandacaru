package com.github.jvsena42.mandacaru.data.network

import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.domain.model.Constants

/** This device's configured Tor SOCKS proxy. [port] is `null` when the stored value isn't a valid port number. */
data class TorProxySettings(
    val enabled: Boolean,
    val host: String,
    val port: Int?,
)

/**
 * Reads the Tor toggle and SOCKS host/port from Settings, falling back to Orbot's default
 * address (127.0.0.1:9050) whenever the user hasn't entered one - the Settings fields only show
 * that address as a placeholder, they don't pre-fill it as a storable value.
 */
suspend fun PreferencesDataSource.resolveTorProxySettings(): TorProxySettings {
    val enabled = getBoolean(PreferenceKeys.TOR_ENABLED, false)
    val host = getString(PreferenceKeys.TOR_SOCKS_HOST, "").ifBlank { Constants.TOR_SOCKS_HOST_DEFAULT }
    val port = getString(PreferenceKeys.TOR_SOCKS_PORT, "").ifBlank { Constants.TOR_SOCKS_PORT_DEFAULT }.toIntOrNull()
    return TorProxySettings(enabled = enabled, host = host, port = port)
}
