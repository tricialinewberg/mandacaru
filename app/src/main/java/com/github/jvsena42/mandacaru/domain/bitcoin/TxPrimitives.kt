package com.github.jvsena42.mandacaru.domain.bitcoin

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Low-level Bitcoin transaction serialization helpers (BIP143/BIP144). */
object TxPrimitives {

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Bitcoin core's compactSize varint. */
    fun writeVarInt(value: Long, out: ByteArrayOutputStream) {
        when {
            value < 0xfd -> out.write(value.toInt())
            value <= 0xffff -> {
                out.write(0xfd)
                out.write(le(value, 2))
            }
            value <= 0xffffffffL -> {
                out.write(0xfe)
                out.write(le(value, 4))
            }
            else -> {
                out.write(0xff)
                out.write(le(value, 8))
            }
        }
    }

    fun le(value: Long, bytes: Int): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
        return buffer.array().copyOfRange(0, bytes)
    }

    /** Reverses byte order - txids are displayed big-endian but serialized little-endian. */
    fun reversed(bytes: ByteArray): ByteArray = bytes.reversedArray()

    /** `OP_DUP OP_HASH160 <20-byte hash> OP_EQUALVERIFY OP_CHECKSIG`, length-prefixed. */
    fun p2wpkhScriptCode(pubKeyHash: ByteArray): ByteArray {
        require(pubKeyHash.size == 20) { "pubKeyHash must be 20 bytes" }
        val script = ByteArrayOutputStream().apply {
            write(0x76) // OP_DUP
            write(0xa9) // OP_HASH160
            write(0x14) // push 20 bytes
            write(pubKeyHash)
            write(0x88) // OP_EQUALVERIFY
            write(0xac) // OP_CHECKSIG
        }.toByteArray()
        val out = ByteArrayOutputStream()
        writeVarInt(script.size.toLong(), out)
        out.write(script)
        return out.toByteArray()
    }

    /** `OP_0 <20-byte hash>` - the scriptPubKey of a P2WPKH output. */
    fun p2wpkhScriptPubKey(pubKeyHash: ByteArray): ByteArray {
        require(pubKeyHash.size == 20) { "pubKeyHash must be 20 bytes" }
        return byteArrayOf(0x00, 0x14) + pubKeyHash
    }

    fun serializeOutput(scriptPubKey: ByteArray, amountSats: Long, out: ByteArrayOutputStream) {
        out.write(le(amountSats, 8))
        writeVarInt(scriptPubKey.size.toLong(), out)
        out.write(scriptPubKey)
    }

    /**
     * BIP143 sighash preimage for a `SIGHASH_ALL | ANYONECANPAY` (0x81) P2WPKH input.
     * Under ANYONECANPAY, `hashPrevouts`/`hashSequence` are zeroed - the signer commits
     * only to their own outpoint plus every output of the round, not to any other
     * peer's input. This is exactly what lets a coordinator-less CoinJoin round be
     * assembled incrementally: each peer signs before the other inputs exist.
     */
    fun sighashAllAnyoneCanPay(
        outpointTxidBE: ByteArray,
        vout: Int,
        pubKeyHash: ByteArray,
        amountSats: Long,
        sequence: Long,
        outputs: List<Pair<ByteArray, Long>>,
        lockTime: Long,
    ): ByteArray {
        val preimage = ByteArrayOutputStream()
        preimage.write(le(TX_VERSION, 4)) // nVersion
        preimage.write(ZERO_HASH) // hashPrevouts (ANYONECANPAY)
        preimage.write(ZERO_HASH) // hashSequence (ANYONECANPAY)
        preimage.write(reversed(outpointTxidBE)) // outpoint txid (internal order)
        preimage.write(le(vout.toLong(), 4)) // outpoint index
        preimage.write(p2wpkhScriptCode(pubKeyHash)) // scriptCode
        preimage.write(le(amountSats, 8)) // amount
        preimage.write(le(sequence, 4)) // nSequence
        preimage.write(hashOutputs(outputs)) // hashOutputs (SIGHASH_ALL commits to all outputs)
        preimage.write(le(lockTime, 4)) // nLocktime
        preimage.write(le(SIGHASH_ALL_ANYONECANPAY, 4)) // nHashType
        return BitcoinHash.doubleSha256(preimage.toByteArray())
    }

    private fun hashOutputs(outputs: List<Pair<ByteArray, Long>>): ByteArray {
        val out = ByteArrayOutputStream()
        outputs.forEach { (scriptPubKey, amountSats) -> serializeOutput(scriptPubKey, amountSats, out) }
        return BitcoinHash.doubleSha256(out.toByteArray())
    }

    const val TX_VERSION = 2L
    const val SIGHASH_ALL_ANYONECANPAY = 0x81L
    val ZERO_HASH: ByteArray = ByteArray(32)
}
