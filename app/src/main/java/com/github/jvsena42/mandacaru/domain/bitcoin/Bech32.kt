package com.github.jvsena42.mandacaru.domain.bitcoin

/**
 * Bech32 (BIP173) / Bech32m (BIP350) encoding, plus segwit address encoding.
 * Hand-rolled instead of pulling in a dependency: the algorithm is small,
 * fully specified, and shared here by both P2WPKH address derivation
 * ([SegwitAddress]) and Nostr's npub/nsec encoding.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    enum class Encoding(val constant: Int) {
        BECH32(1),
        BECH32M(0x2bc830a3.toInt()),
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor v
            for (i in 0 until 5) {
                if ((b ushr i) and 1 != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val lower = hrp.map { it.code shr 5 }
        val upper = hrp.map { it.code and 31 }
        return (lower + listOf(0) + upper).toIntArray()
    }

    private fun createChecksum(hrp: String, data: IntArray, encoding: Encoding): IntArray {
        val values = hrpExpand(hrp) + data + IntArray(6)
        val mod = polymod(values) xor encoding.constant
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    fun encode(hrp: String, data: IntArray, encoding: Encoding): String {
        val combined = data + createChecksum(hrp, data, encoding)
        val sb = StringBuilder(hrp).append('1')
        combined.forEach { sb.append(CHARSET[it]) }
        return sb.toString()
    }

    /** Returns (hrp, 5-bit data words, encoding) or null if [bech] is malformed. */
    fun decode(bech: String): Triple<String, IntArray, Encoding>? {
        if (bech.lowercase() != bech && bech.uppercase() != bech) return null
        val str = bech.lowercase()
        val pos = str.lastIndexOf('1')
        if (pos < 1 || pos + 7 > str.length) return null
        val hrp = str.substring(0, pos)
        val dataPart = str.substring(pos + 1)
        val data = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val d = CHARSET.indexOf(dataPart[i])
            if (d == -1) return null
            data[i] = d
        }
        val check = polymod(hrpExpand(hrp) + data)
        val encoding = when (check) {
            Encoding.BECH32.constant -> Encoding.BECH32
            Encoding.BECH32M.constant -> Encoding.BECH32M
            else -> return null
        }
        return Triple(hrp, data.copyOfRange(0, data.size - 6), encoding)
    }

    /** Regroups bits between [fromBits] and [toBits] sized words (e.g. bytes <-> 5-bit words). */
    fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxV = (1 shl toBits) - 1
        for (value in data) {
            if (value < 0 || (value ushr fromBits) != 0) return null
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc ushr bits) and maxV)
            }
        }
        return when {
            pad && bits > 0 -> (result + ((acc shl (toBits - bits)) and maxV)).toIntArray()
            !pad && (bits >= fromBits || ((acc shl (toBits - bits)) and maxV) != 0) -> null
            else -> result.toIntArray()
        }
    }
}

/** Segwit v0 (P2WPKH/P2WSH) address encode/decode, per BIP173. */
object SegwitAddress {
    fun encode(hrp: String, witnessVersion: Int, program: ByteArray): String {
        val words = Bech32.convertBits(program.map { it.toInt() and 0xff }.toIntArray(), 8, 5, true)
            ?: throw IllegalArgumentException("Invalid witness program")
        val encoding = if (witnessVersion == 0) Bech32.Encoding.BECH32 else Bech32.Encoding.BECH32M
        return Bech32.encode(hrp, intArrayOf(witnessVersion) + words, encoding)
    }

    /** Builds a P2WPKH address for a 20-byte pubkey hash. */
    fun p2wpkh(hrp: String, pubKeyHash: ByteArray): String {
        require(pubKeyHash.size == HASH160_SIZE) { "P2WPKH program must be 20 bytes" }
        return encode(hrp, witnessVersion = 0, program = pubKeyHash)
    }

    private const val HASH160_SIZE = 20
}
