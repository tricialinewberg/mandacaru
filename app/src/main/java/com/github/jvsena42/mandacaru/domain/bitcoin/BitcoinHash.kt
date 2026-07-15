package com.github.jvsena42.mandacaru.domain.bitcoin

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import java.security.MessageDigest

object BitcoinHash {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))

    /** RIPEMD160(SHA256(data)) - used for P2WPKH/P2PKH pubkey hashes. */
    fun hash160(data: ByteArray): ByteArray {
        val sha = sha256(data)
        val digest = RIPEMD160Digest()
        digest.update(sha, 0, sha.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }
}
