package com.github.jvsena42.mandacaru.domain.nostr

import com.github.jvsena42.mandacaru.domain.bitcoin.Bech32
import com.github.jvsena42.mandacaru.domain.bitcoin.BitcoinHash
import com.github.jvsena42.mandacaru.domain.bitcoin.TxPrimitives
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-01 event signing (BIP340 Schnorr) and NIP-04 direct-message encryption,
 * built on the same `secp256k1-kmp` used for coinjoin's ECDSA signing.
 */
object NostrCrypto {

    private val random = SecureRandom()

    fun generatePrivateKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** BIP340 x-only public key: the x-coordinate of privateKey * G. */
    fun xOnlyPublicKey(privateKey: ByteArray): ByteArray {
        val compressed = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(privateKey))
        return compressed.copyOfRange(1, compressed.size)
    }

    /** Canonical NIP-01 event id: sha256 of `[0,pubkey,created_at,kind,tags,content]`. */
    fun eventId(
        pubKeyXOnlyHex: String,
        createdAt: Long,
        kind: Int,
        tags: String,
        content: String,
    ): ByteArray {
        val json = buildString {
            append('[')
            append("0,")
            append('"').append(pubKeyXOnlyHex).append("\",")
            append(createdAt).append(',')
            append(kind).append(',')
            append(tags).append(',')
            append(jsonEscape(content))
            append(']')
        }
        return BitcoinHash.sha256(json.toByteArray(Charsets.UTF_8))
    }

    fun signEvent(eventIdHash: ByteArray, privateKey: ByteArray): ByteArray {
        val auxRand = ByteArray(32).also { random.nextBytes(it) }
        return Secp256k1.signSchnorr(eventIdHash, privateKey, auxRand)
    }

    fun verifyEvent(eventIdHash: ByteArray, signature: ByteArray, pubKeyXOnly: ByteArray): Boolean =
        Secp256k1.verifySchnorr(signature, eventIdHash, pubKeyXOnly)

    /** ECDH shared secret (NIP-04): the x-coordinate of privateKey * theirPubKey. */
    fun sharedSecret(privateKey: ByteArray, theirPubKeyXOnlyHex: String): ByteArray {
        val theirCompressed = byteArrayOf(0x02) + TxPrimitives.hexToBytes(theirPubKeyXOnlyHex)
        // pubKeyTweakMul already returns the uncompressed (65-byte) point.
        val point = Secp256k1.pubKeyTweakMul(theirCompressed, privateKey)
        return point.copyOfRange(1, 33)
    }

    /** NIP-04: AES-256-CBC, content = base64(ciphertext) + "?iv=" + base64(iv). */
    fun encryptDirectMessage(plaintext: String, sharedSecret: ByteArray): String {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getEncoder()
        return "${b64.encodeToString(ciphertext)}?iv=${b64.encodeToString(iv)}"
    }

    fun decryptDirectMessage(payload: String, sharedSecret: ByteArray): String {
        val parts = payload.split("?iv=")
        require(parts.size == 2) { "Malformed NIP-04 payload" }
        val b64 = java.util.Base64.getDecoder()
        val ciphertext = b64.decode(parts[0])
        val iv = b64.decode(parts[1])
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun npub(pubKeyXOnly: ByteArray): String = bech32Encode("npub", pubKeyXOnly)

    fun nsec(privateKey: ByteArray): String = bech32Encode("nsec", privateKey)

    private fun bech32Encode(hrp: String, data: ByteArray): String {
        val words = Bech32.convertBits(data.map { it.toInt() and 0xff }.toIntArray(), 8, 5, true)
            ?: error("Invalid key bytes")
        return Bech32.encode(hrp, words, Bech32.Encoding.BECH32)
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder("\"")
        value.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

fun ByteArray.toHex(): String = TxPrimitives.bytesToHex(this)
