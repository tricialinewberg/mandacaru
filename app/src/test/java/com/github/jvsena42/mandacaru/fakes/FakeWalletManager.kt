package com.github.jvsena42.mandacaru.fakes

import com.florestad.Network
import com.github.jvsena42.mandacaru.domain.bitcoin.SegwitAddress
import com.github.jvsena42.mandacaru.domain.wallet.CoinjoinOutput
import com.github.jvsena42.mandacaru.domain.wallet.SignedContribution
import com.github.jvsena42.mandacaru.domain.wallet.WalletManager
import com.github.jvsena42.mandacaru.domain.wallet.WalletUtxo

/**
 * Hand-rolled fake of [WalletManager] for CoinJoin round tests. Real signing (BDK + native
 * secp256k1 PSBT-less ECDSA) needs actual key material and isn't worth exercising here - what
 * matters for round-completion tests is that every peer produces *some* deterministic witness
 * data the assembler can merge, addressed to a fresh, decodable receive address each time.
 */
class FakeWalletManager : WalletManager {

    private var nextAddressIndex = 0

    override suspend fun hasWallet(): Boolean = true

    override suspend fun ensureWallet(network: Network): Result<Unit> = Result.success(Unit)

    override suspend fun restoreFromMnemonic(mnemonic: String, network: Network): Result<Unit> = Result.success(Unit)

    override suspend fun watchDescriptors(network: Network): Result<List<String>> = Result.success(emptyList())

    override suspend fun revealMnemonic(): Result<String> = Result.success("")

    override suspend fun getNewReceiveAddress(network: Network): Result<String> {
        val hash = ByteArray(HASH160_SIZE).also { it[0] = (++nextAddressIndex).toByte() }
        return Result.success(SegwitAddress.p2wpkh("bcrt", hash))
    }

    override suspend fun signCoinjoinContribution(
        network: Network,
        input: WalletUtxo,
        inputPrevTxHex: String,
        allOutputs: List<CoinjoinOutput>,
        lockTime: Long,
    ): Result<SignedContribution> = Result.success(
        SignedContribution(
            txid = input.txid,
            vout = input.vout,
            sequence = OPT_IN_RBF_SEQUENCE,
            witnessSignatureHex = DUMMY_SIGNATURE_HEX,
            witnessPubKeyHex = DUMMY_PUBKEY_HEX,
        ),
    )

    private companion object {
        const val HASH160_SIZE = 20
        const val OPT_IN_RBF_SEQUENCE = 0xfffffffdL
        val DUMMY_SIGNATURE_HEX = "30".padEnd(142, '0')
        val DUMMY_PUBKEY_HEX = "02".padEnd(66, '1')
    }
}
