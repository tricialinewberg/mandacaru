package com.github.jvsena42.mandacaru.data.network

import com.github.jvsena42.mandacaru.common.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Probes a SOCKS proxy with a raw TCP connect. Orbot's SOCKS port starts accepting connections
 * only once Tor has bootstrapped, so a successful connect is a reasonable "Tor is up" signal
 * without needing a full SOCKS handshake.
 */
class SocketProxyReachabilityChecker : ProxyReachabilityChecker {
    override suspend fun isReachable(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runSuspendCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        }.isSuccess
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3000
    }
}
