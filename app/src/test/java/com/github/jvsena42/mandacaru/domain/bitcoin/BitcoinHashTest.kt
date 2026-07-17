package com.github.jvsena42.mandacaru.domain.bitcoin

import org.junit.Assert.assertEquals
import org.junit.Test

class BitcoinHashTest {

    @Test
    fun `sha256 of the empty string matches the well-known NIST vector`() {
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, TxPrimitives.bytesToHex(BitcoinHash.sha256(ByteArray(0))))
    }

    @Test
    fun `doubleSha256 hashes twice`() {
        val data = "hello".toByteArray()
        val expected = BitcoinHash.sha256(BitcoinHash.sha256(data))
        assertEquals(TxPrimitives.bytesToHex(expected), TxPrimitives.bytesToHex(BitcoinHash.doubleSha256(data)))
    }

    @Test
    fun `hash160 of the compressed pubkey for private key 1 matches the BIP173 test vector`() {
        // Same "privkey = 1" generator-point pubkey used by the project's own Bech32Test
        // (BIP173's "Valid checksum for segwit addresses" vector) - independently verified
        // against a Python secp256k1 (coincurve) reference while writing this test.
        val compressedPubKey = TxPrimitives.hexToBytes("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")

        val hash = BitcoinHash.hash160(compressedPubKey)

        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", TxPrimitives.bytesToHex(hash))
    }
}
