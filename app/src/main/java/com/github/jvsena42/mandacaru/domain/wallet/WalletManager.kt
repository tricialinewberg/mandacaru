package com.github.jvsena42.mandacaru.domain.wallet

import com.florestad.Network

/** A wallet-tracked spendable output, as reported by the node's `listunspent`. */
data class WalletUtxo(
    val txid: String,
    val vout: Int,
    val amountSats: Long,
    val scriptPubKeyHex: String,
    val address: String?,
    val confirmations: Int,
)

/**
 * Local BIP84 signing wallet used for CoinJoin. Unlike the rest of the app
 * (watch-only, airgapped-signing), this wallet holds its own private keys
 * on-device, because Joinstr rounds require each peer to sign live during
 * the round - there's no way to do that from an external signer.
 *
 * UTXO/tx data still comes from the local Floresta node (via [WalletUtxo]
 * built from `listunspent`/`getrawtransaction`), not from a separate
 * blockchain client - this wallet is only responsible for key management,
 * address derivation, and signing.
 */
interface WalletManager {
    /** True once a mnemonic has been generated or restored. */
    suspend fun hasWallet(): Boolean

    /** Generates a fresh mnemonic and wallet if none exists yet; otherwise a no-op. */
    suspend fun ensureWallet(network: Network): Result<Unit>

    /** Replaces the current wallet with one restored from an existing mnemonic. */
    suspend fun restoreFromMnemonic(mnemonic: String, network: Network): Result<Unit>

    /** The public BIP84 descriptor pair (external + internal chain) to hand to the node via `loaddescriptor`. */
    suspend fun watchDescriptors(network: Network): Result<List<String>>

    /** Reveals the mnemonic for backup. Callers must gate this behind user confirmation. */
    suspend fun revealMnemonic(): Result<String>

    suspend fun getNewReceiveAddress(network: Network): Result<String>

    /**
     * Signs this peer's own input into a Joinstr round with
     * `SIGHASH_ALL | ANYONECANPAY`. Per BIP143, that hash type still commits
     * to *every* output of the joined transaction (only the other peers'
     * inputs are left uncommitted) - so the round's full, agreed-upon output
     * list must already be known before any peer signs. [allOutputs] must be
     * in final transaction order and identical for every peer signing this
     * round.
     */
    @Suppress("LongParameterList")
    suspend fun signCoinjoinContribution(
        network: Network,
        input: WalletUtxo,
        inputPrevTxHex: String,
        allOutputs: List<CoinjoinOutput>,
        lockTime: Long = 0,
    ): Result<SignedContribution>
}

/** One output of a joined coinjoin transaction. */
data class CoinjoinOutput(
    val scriptPubKeyHex: String,
    val amountSats: Long,
)

/** One peer's signed input, ready to be merged into the joined transaction. */
data class SignedContribution(
    val txid: String,
    val vout: Int,
    val sequence: Long,
    /** DER-encoded ECDSA signature with the `SIGHASH_ALL | ANYONECANPAY` type byte appended. */
    val witnessSignatureHex: String,
    val witnessPubKeyHex: String,
)
