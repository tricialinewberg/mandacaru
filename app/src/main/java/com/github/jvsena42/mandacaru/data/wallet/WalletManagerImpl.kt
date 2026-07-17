package com.github.jvsena42.mandacaru.data.wallet

import com.florestad.Network as FlorestaNetwork
import com.github.jvsena42.mandacaru.common.runSuspendCatching
import com.github.jvsena42.mandacaru.domain.bitcoin.BitcoinHash
import com.github.jvsena42.mandacaru.domain.bitcoin.SegwitAddress
import com.github.jvsena42.mandacaru.domain.bitcoin.TxPrimitives
import com.github.jvsena42.mandacaru.domain.wallet.CoinjoinOutput
import com.github.jvsena42.mandacaru.domain.wallet.SignedContribution
import com.github.jvsena42.mandacaru.domain.wallet.WalletManager
import com.github.jvsena42.mandacaru.domain.wallet.WalletUtxo
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bitcoindevkit.DerivationPath
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.WordCount
import org.bitcoindevkit.Network as BdkNetwork

/**
 * BDK is used only for BIP39/BIP32 key material (`Mnemonic`, `DescriptorSecretKey`)
 * - the well-established, stable slice of its API. Address derivation, PSBT-less
 * signing, and sighash computation are done by hand with [SegwitAddress]/[BitcoinHash]/
 * [TxPrimitives] + [Secp256k1] rather than BDK's `Wallet`/`TxBuilder`, since those
 * assume BDK owns UTXO/chain sync - here the local Floresta node is the source of
 * truth for UTXOs, fed in externally via `listunspent`.
 */
class WalletManagerImpl(
    private val keyStore: WalletKeyStore,
) : WalletManager {

    // Guards the getNextExternalIndex -> setNextExternalIndex read-increment-write in
    // getNewReceiveAddress: EncryptedSharedPreferences has no atomic increment, so without this,
    // concurrent calls can read the same index and hand out (and persist) a duplicate address.
    private val nextExternalIndexMutex = Mutex()

    override suspend fun hasWallet(): Boolean = withContext(Dispatchers.Default) {
        keyStore.hasMnemonic()
    }

    override suspend fun ensureWallet(network: FlorestaNetwork): Result<Unit> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (!keyStore.hasMnemonic()) {
                    val mnemonic = Mnemonic(WordCount.WORDS12)
                    keyStore.saveMnemonic(mnemonic.toString())
                    keyStore.setNextExternalIndex(0)
                }
            }
        }

    override suspend fun restoreFromMnemonic(mnemonic: String, network: FlorestaNetwork): Result<Unit> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                // Validates the checksum/wordlist - throws on a malformed phrase.
                Mnemonic.fromString(mnemonic)
                keyStore.saveMnemonic(mnemonic)
                keyStore.setNextExternalIndex(0)
            }
        }

    override suspend fun revealMnemonic(): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            keyStore.getMnemonic() ?: error("No wallet has been created yet")
        }
    }

    override suspend fun watchDescriptors(network: FlorestaNetwork): Result<List<String>> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val mnemonic = requireMnemonic()
                val bdkNetwork = network.toBdkNetwork()
                listOf(KeychainKind.EXTERNAL, KeychainKind.INTERNAL).map { keychain ->
                    accountXpubDescriptor(mnemonic, bdkNetwork, keychain)
                }
            }
        }

    override suspend fun getNewReceiveAddress(network: FlorestaNetwork): Result<String> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val mnemonic = requireMnemonic()
                val pubKeyHash = nextExternalIndexMutex.withLock {
                    val index = keyStore.getNextExternalIndex()
                    val (_, pubKeyHash) = deriveKeyPair(mnemonic, network, EXTERNAL_CHAIN, index)
                    keyStore.setNextExternalIndex(index + 1)
                    pubKeyHash
                }
                SegwitAddress.p2wpkh(hrpFor(network), pubKeyHash)
            }
        }

    @Suppress("LongParameterList")
    override suspend fun signCoinjoinContribution(
        network: FlorestaNetwork,
        input: WalletUtxo,
        inputPrevTxHex: String,
        allOutputs: List<CoinjoinOutput>,
        lockTime: Long,
    ): Result<SignedContribution> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val mnemonic = requireMnemonic()
            val (privateKey, pubKeyHash) = findSigningKeyForScript(
                mnemonic = mnemonic,
                network = network,
                scriptPubKeyHex = input.scriptPubKeyHex,
            ) ?: error("This wallet doesn't control the input's scriptPubKey")

            val txidBytes = TxPrimitives.hexToBytes(input.txid)
            val sequence = OPT_IN_RBF_SEQUENCE
            val outputs = allOutputs.map { TxPrimitives.hexToBytes(it.scriptPubKeyHex) to it.amountSats }

            val sighash = TxPrimitives.sighashAllAnyoneCanPay(
                input = TxPrimitives.SighashInput(
                    outpointTxidBE = txidBytes,
                    vout = input.vout,
                    pubKeyHash = pubKeyHash,
                    amountSats = input.amountSats,
                    sequence = sequence,
                ),
                outputs = outputs,
                lockTime = lockTime,
            )

            // Secp256k1.sign() returns a compact (64-byte r||s) signature, not DER - the SegWit
            // witness requires strict DER encoding (BIP66), so it must be converted before use.
            val compactSignature = Secp256k1.sign(sighash, privateKey)
            val derSignature = Secp256k1.compact2der(compactSignature)
            val sigWithHashType = derSignature + TxPrimitives.SIGHASH_ALL_ANYONECANPAY.toByte()
            val compressedPubKey = compressedPublicKey(privateKey)

            SignedContribution(
                txid = input.txid,
                vout = input.vout,
                sequence = sequence,
                witnessSignatureHex = TxPrimitives.bytesToHex(sigWithHashType),
                witnessPubKeyHex = TxPrimitives.bytesToHex(compressedPubKey),
            )
        }
    }

    private fun requireMnemonic(): String =
        keyStore.getMnemonic() ?: error("No wallet has been created yet")

    private fun accountXpubDescriptor(
        mnemonicStr: String,
        bdkNetwork: BdkNetwork,
        keychain: KeychainKind,
    ): String {
        val mnemonic = Mnemonic.fromString(mnemonicStr)
        val master = DescriptorSecretKey(bdkNetwork, mnemonic, null)
        // newBip84's default Display/toString renders the public form only - the app
        // never hands the node a private descriptor, matching the existing manual-
        // descriptor flow in Settings, which rejects any descriptor containing one.
        return Descriptor.newBip84(master, keychain, bdkNetwork).toString()
    }

    /** Scans the BIP84 external/internal chains for the key whose P2WPKH script matches. */
    private fun findSigningKeyForScript(
        mnemonic: String,
        network: FlorestaNetwork,
        scriptPubKeyHex: String,
    ): Pair<ByteArray, ByteArray>? {
        val targetScript = TxPrimitives.hexToBytes(scriptPubKeyHex)
        for (chain in listOf(EXTERNAL_CHAIN, INTERNAL_CHAIN)) {
            for (index in 0 until GAP_LIMIT) {
                val (privateKey, pubKeyHash) = deriveKeyPair(mnemonic, network, chain, index)
                val candidateScript = TxPrimitives.p2wpkhScriptPubKey(pubKeyHash)
                if (candidateScript.contentEquals(targetScript)) return privateKey to pubKeyHash
            }
        }
        return null
    }

    private fun deriveKeyPair(
        mnemonicStr: String,
        network: FlorestaNetwork,
        chain: Int,
        index: Int,
    ): Pair<ByteArray, ByteArray> {
        val mnemonic = Mnemonic.fromString(mnemonicStr)
        val master = DescriptorSecretKey(network.toBdkNetwork(), mnemonic, null)
        val path = "m/84h/${coinType(network)}h/0h/$chain/$index"
        val leaf = master.derive(DerivationPath(path))
        val privateKey = leaf.secretBytes()
        val pubKeyHash = BitcoinHash.hash160(compressedPublicKey(privateKey))
        return privateKey to pubKeyHash
    }

    private fun compressedPublicKey(privateKey: ByteArray): ByteArray {
        val uncompressed = Secp256k1.pubkeyCreate(privateKey)
        return Secp256k1.pubKeyCompress(uncompressed)
    }

    private fun coinType(network: FlorestaNetwork): Int =
        if (network == FlorestaNetwork.BITCOIN) 0 else 1

    private fun hrpFor(network: FlorestaNetwork): String = when (network) {
        FlorestaNetwork.BITCOIN -> "bc"
        FlorestaNetwork.REGTEST -> "bcrt"
        else -> "tb"
    }

    private fun FlorestaNetwork.toBdkNetwork(): BdkNetwork = when (this) {
        FlorestaNetwork.BITCOIN -> BdkNetwork.BITCOIN
        FlorestaNetwork.SIGNET -> BdkNetwork.SIGNET
        FlorestaNetwork.TESTNET -> BdkNetwork.TESTNET
        FlorestaNetwork.REGTEST -> BdkNetwork.REGTEST
        // BDK versions vary on whether TESTNET4 is a distinct enum entry; falls back
        // to TESTNET (only affects address/xpub version-byte prefixes, not signing).
        FlorestaNetwork.TESTNET4 -> runCatching { BdkNetwork.valueOf("TESTNET4") }
            .getOrDefault(BdkNetwork.TESTNET)
    }

    private companion object {
        const val EXTERNAL_CHAIN = 0
        const val INTERNAL_CHAIN = 1
        const val GAP_LIMIT = 500
        const val OPT_IN_RBF_SEQUENCE = 0xfffffffdL
    }
}
