package com.github.jvsena42.mandacaru.presentation.utils

import com.github.jvsena42.mandacaru.presentation.utils.NostrRelayValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class NostrRelayValidatorTest {

    @Test
    fun `valid wss url`() {
        assertEquals(Result.Valid, NostrRelayValidator.validate("wss://relay.damus.io"))
    }

    @Test
    fun `valid wss url with port and path`() {
        assertEquals(Result.Valid, NostrRelayValidator.validate("wss://relay.example.com:4443/nostr"))
    }

    @Test
    fun `surrounding whitespace is trimmed before validating`() {
        assertEquals(Result.Valid, NostrRelayValidator.validate("  wss://relay.damus.io  "))
    }

    @Test
    fun `empty input is Empty not InvalidFormat`() {
        assertEquals(Result.Empty, NostrRelayValidator.validate(""))
        assertEquals(Result.Empty, NostrRelayValidator.validate("   "))
    }

    @Test
    fun `plaintext ws is rejected`() {
        assertEquals(Result.InvalidScheme, NostrRelayValidator.validate("ws://relay.damus.io"))
    }

    @Test
    fun `https is rejected`() {
        assertEquals(Result.InvalidScheme, NostrRelayValidator.validate("https://relay.damus.io"))
    }

    @Test
    fun `missing scheme is rejected`() {
        assertEquals(Result.InvalidScheme, NostrRelayValidator.validate("relay.damus.io"))
    }

    @Test
    fun `scheme with no host is invalid format`() {
        assertEquals(Result.InvalidFormat, NostrRelayValidator.validate("wss://"))
    }

    @Test
    fun `scheme match is case-insensitive`() {
        assertEquals(Result.Valid, NostrRelayValidator.validate("WSS://relay.damus.io"))
    }
}
