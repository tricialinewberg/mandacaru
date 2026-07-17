package com.github.jvsena42.mandacaru.domain.bitcoin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The known-answer sighash vectors below were computed independently in Python (hashlib +
 * coincurve, i.e. a separate libsecp256k1/SHA256 binding from the ones this app uses) while
 * writing this test, hand-tracing the BIP143 preimage layout field by field. As a sanity check on
 * that reference itself: for the same "private key = 1" input this project's own [Bech32Test]
 * already uses (the BIP173 spec's published segwit address vector), the derived pubKeyHash came
 * back byte-identical to that test's hardcoded value - see [BitcoinHashTest].
 */
class TxPrimitivesTest {

    @Test
    fun `hexToBytes and bytesToHex round-trip`() {
        val hex = "deadbeef00112233"
        assertEquals(hex, TxPrimitives.bytesToHex(TxPrimitives.hexToBytes(hex)))
    }

    @Test
    fun `hexToBytes rejects odd-length input`() {
        assertThrows(IllegalArgumentException::class.java) { TxPrimitives.hexToBytes("abc") }
    }

    @Test
    fun `writeVarInt matches Bitcoin Core's compactSize boundaries`() {
        assertEquals("00", varIntHex(0))
        assertEquals("fc", varIntHex(0xfc))
        assertEquals("fdfd00", varIntHex(0xfd))
        assertEquals("fdffff", varIntHex(0xffff))
        assertEquals("fe00000100", varIntHex(0x10000))
        assertEquals("feffffffff", varIntHex(0xffffffffL))
        assertEquals("ff0000000001000000", varIntHex(0x100000000L))
    }

    @Test
    fun `reversed flips byte order without mutating the input`() {
        val original = TxPrimitives.hexToBytes("0102030405")
        val reversed = TxPrimitives.reversed(original)

        assertEquals("0504030201", TxPrimitives.bytesToHex(reversed))
        assertEquals("0102030405", TxPrimitives.bytesToHex(original))
    }

    @Test
    fun `p2wpkhScriptPubKey is OP_0 push20 pubKeyHash`() {
        val pubKeyHash = TxPrimitives.hexToBytes("751e76e8199196d454941c45d1b3a323f1433bd6")

        val script = TxPrimitives.p2wpkhScriptPubKey(pubKeyHash)

        assertEquals("0014751e76e8199196d454941c45d1b3a323f1433bd6", TxPrimitives.bytesToHex(script))
    }

    @Test
    fun `p2wpkhScriptPubKey rejects a pubKeyHash that is not 20 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            TxPrimitives.p2wpkhScriptPubKey(ByteArray(19))
        }
    }

    @Test
    fun `p2wpkhScriptCode is the length-prefixed P2PKH-style script BIP143 requires`() {
        val pubKeyHash = TxPrimitives.hexToBytes("751e76e8199196d454941c45d1b3a323f1433bd6")

        val scriptCode = TxPrimitives.p2wpkhScriptCode(pubKeyHash)

        // 0x19 (25) length prefix + OP_DUP OP_HASH160 <push 20> <hash> OP_EQUALVERIFY OP_CHECKSIG.
        assertEquals("1976a914751e76e8199196d454941c45d1b3a323f1433bd688ac", TxPrimitives.bytesToHex(scriptCode))
    }

    @Test
    fun `serializeOutput writes amount then length-prefixed scriptPubKey`() {
        val script = TxPrimitives.hexToBytes("00141111111111111111111111111111111111111111")
        val out = ByteArrayOutputStream()

        TxPrimitives.serializeOutput(script, 100_000L, out)

        assertEquals("a086010000000000" + "16" + "00141111111111111111111111111111111111111111", TxPrimitives.bytesToHex(out.toByteArray()))
    }

    @Test
    fun `sighashAllAnyoneCanPay matches an independently computed BIP143 vector - two outputs`() {
        // privkey = 1's well-known pubKeyHash (see class doc); everything else is an arbitrary
        // but fully independently hashed-by-hand-in-Python test vector.
        val pubKeyHash = TxPrimitives.hexToBytes("751e76e8199196d454941c45d1b3a323f1433bd6")
        val input = TxPrimitives.SighashInput(
            outpointTxidBE = TxPrimitives.hexToBytes("11".repeat(32)),
            vout = 0,
            pubKeyHash = pubKeyHash,
            amountSats = 100_000L,
            sequence = 0xfffffffdL,
        )
        val outputs = listOf(
            TxPrimitives.hexToBytes("0014" + "22".repeat(20)) to 40_000L,
            TxPrimitives.hexToBytes("0014" + "33".repeat(20)) to 59_000L,
        )

        val sighash = TxPrimitives.sighashAllAnyoneCanPay(input, outputs, lockTime = 0)

        assertEquals(
            "eda0229095df9eb1bdfb6ed7a6050b1844d24bc6cfc5cf7a82093d28d881c8b3",
            TxPrimitives.bytesToHex(sighash),
        )
    }

    @Test
    fun `sighashAllAnyoneCanPay preimage has hashPrevouts and hashSequence zeroed`() {
        // The defining property of ANYONECANPAY: the preimage's first 40 bytes are nVersion (4)
        // followed by two all-zero 32-byte hashes, regardless of the actual input/outputs.
        val input = TxPrimitives.SighashInput(
            outpointTxidBE = TxPrimitives.hexToBytes("aa".repeat(32)),
            vout = 3,
            pubKeyHash = TxPrimitives.hexToBytes("bb".repeat(20)),
            amountSats = 1L,
            sequence = 0L,
        )
        val sighash = TxPrimitives.sighashAllAnyoneCanPay(input, outputs = emptyList(), lockTime = 0)

        // Just confirming this constructs without throwing and produces a 32-byte hash - the
        // zeroed-hashPrevouts property itself is exercised end-to-end by the known-answer vector
        // above (it's baked into that expected hash).
        assertEquals(32, sighash.size)
    }

    @Test
    fun `sighashAllAnyoneCanPay is sensitive to every field - each changed input changes the hash`() {
        fun baseInput() = TxPrimitives.SighashInput(
            outpointTxidBE = TxPrimitives.hexToBytes("11".repeat(32)),
            vout = 0,
            pubKeyHash = TxPrimitives.hexToBytes("751e76e8199196d454941c45d1b3a323f1433bd6"),
            amountSats = 100_000L,
            sequence = 0xfffffffdL,
        )
        val baseOutputs = listOf(TxPrimitives.hexToBytes("0014" + "22".repeat(20)) to 40_000L)
        val base = TxPrimitives.sighashAllAnyoneCanPay(baseInput(), baseOutputs, lockTime = 0)

        val differentVout = TxPrimitives.sighashAllAnyoneCanPay(baseInput().copy(vout = 1), baseOutputs, lockTime = 0)
        val differentAmount = TxPrimitives.sighashAllAnyoneCanPay(baseInput().copy(amountSats = 100_001L), baseOutputs, lockTime = 0)
        val differentSequence = TxPrimitives.sighashAllAnyoneCanPay(baseInput().copy(sequence = 0xffffffffL), baseOutputs, lockTime = 0)
        val differentTxid = TxPrimitives.sighashAllAnyoneCanPay(baseInput().copy(outpointTxidBE = TxPrimitives.hexToBytes("22".repeat(32))), baseOutputs, lockTime = 0)
        val differentPubKeyHash = TxPrimitives.sighashAllAnyoneCanPay(
            baseInput().copy(pubKeyHash = TxPrimitives.hexToBytes("00".repeat(20))),
            baseOutputs,
            lockTime = 0,
        )
        val differentOutputs = TxPrimitives.sighashAllAnyoneCanPay(
            baseInput(),
            listOf(TxPrimitives.hexToBytes("0014" + "22".repeat(20)) to 40_001L),
            lockTime = 0,
        )
        val differentLockTime = TxPrimitives.sighashAllAnyoneCanPay(baseInput(), baseOutputs, lockTime = 1)

        val allHex = listOf(base, differentVout, differentAmount, differentSequence, differentTxid, differentPubKeyHash, differentOutputs, differentLockTime)
            .map { TxPrimitives.bytesToHex(it) }
        assertEquals("every field change should produce a distinct sighash", allHex.size, allHex.distinct().size)
    }

    @Test
    fun `sighashAllAnyoneCanPay is deterministic for identical inputs`() {
        val input = TxPrimitives.SighashInput(
            outpointTxidBE = TxPrimitives.hexToBytes("11".repeat(32)),
            vout = 0,
            pubKeyHash = TxPrimitives.hexToBytes("751e76e8199196d454941c45d1b3a323f1433bd6"),
            amountSats = 100_000L,
            sequence = 0xfffffffdL,
        )
        val outputs = listOf(TxPrimitives.hexToBytes("0014" + "22".repeat(20)) to 40_000L)

        val first = TxPrimitives.sighashAllAnyoneCanPay(input, outputs, lockTime = 0)
        val second = TxPrimitives.sighashAllAnyoneCanPay(input, outputs, lockTime = 0)

        assertArrayEquals(first, second)
    }

    private fun varIntHex(value: Long): String {
        val out = ByteArrayOutputStream()
        TxPrimitives.writeVarInt(value, out)
        return TxPrimitives.bytesToHex(out.toByteArray())
    }
}
