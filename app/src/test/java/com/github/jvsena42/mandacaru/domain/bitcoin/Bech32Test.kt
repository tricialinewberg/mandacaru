package com.github.jvsena42.mandacaru.domain.bitcoin

import org.junit.Assert.assertEquals
import org.junit.Test

/** Test vectors from BIP173 ("Valid checksum for segwit addresses"). */
class Bech32Test {

    @Test
    fun `encodes known BIP173 P2WPKH mainnet vector`() {
        val programHex = "751e76e8199196d454941c45d1b3a323f1433bd6"
        val expected = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

        val address = SegwitAddress.p2wpkh("bc", TxPrimitives.hexToBytes(programHex))

        assertEquals(expected, address)
    }

    @Test
    fun `decodes back to the same hrp and data as encoded`() {
        val programHex = "751e76e8199196d454941c45d1b3a323f1433bd6"
        val address = SegwitAddress.p2wpkh("bc", TxPrimitives.hexToBytes(programHex))

        val decoded = Bech32.decode(address)

        checkNotNull(decoded)
        val (hrp, data, encoding) = decoded
        assertEquals("bc", hrp)
        assertEquals(Bech32.Encoding.BECH32, encoding)
        val witnessVersion = data.first()
        val program = Bech32.convertBits(data.drop(1).toIntArray(), 5, 8, false)
        assertEquals(0, witnessVersion)
        assertEquals(programHex, TxPrimitives.bytesToHex(program!!.map { it.toByte() }.toByteArray()))
    }

    private fun List<Int>.toIntArray(): IntArray = IntArray(size) { this[it] }
}
