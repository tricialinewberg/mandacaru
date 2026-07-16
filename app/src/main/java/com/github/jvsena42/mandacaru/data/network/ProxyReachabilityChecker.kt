package com.github.jvsena42.mandacaru.data.network

/** Checks whether a local SOCKS proxy (e.g. Orbot) is up before routing traffic through it. */
interface ProxyReachabilityChecker {
    suspend fun isReachable(host: String, port: Int): Boolean
}
