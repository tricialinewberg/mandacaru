package com.github.jvsena42.mandacaru.data.wallet

import com.florestad.Network
import com.github.jvsena42.mandacaru.domain.bitcoin.SegwitAddress
import com.github.jvsena42.mandacaru.domain.bitcoin.TxPrimitives
import com.github.jvsena42.mandacaru.domain.wallet.CoinjoinOutput
import com.github.jvsena42.mandacaru.domain.wallet.WalletUtxo
import com.github.jvsena42.mandacaru.fakes.FakeWalletKeyStore
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [WalletManagerImpl] through the public [com.github.jvsena42.mandacaru.domain.wallet.WalletManager]
 * interface - real BDK (via the `bdk-jvm` desktop-native test artifact, same version/API as
 * production's `bdk-android`) and real secp256k1 (via `secp256k1-kmp-jni-jvm`, already used
 * elsewhere in this test suite for [com.github.jvsena42.mandacaru.domain.nostr.NostrCrypto]) do the
 * actual derivation and signing - only the Keystore-backed storage layer is faked (see
 * [FakeWalletKeyStore]).
 *
 * The BIP84 addresses/keys below are BIP84's own officially published test vectors for the
 * standard "abandon abandon ... about" test mnemonic - independently cross-checked (while writing
 * this test) against a Python reference (`embit`) that reproduced the identical, spec-recognized
 * addresses. The exact-signature test below was computed independently in Python (`coincurve`, a
 * different libsecp256k1 binding) from that same derived private key.
 */
class WalletManagerImplTest {

    private fun newManager() = WalletManagerImpl(FakeWalletKeyStore())

    @Test
    fun `hasWallet is false until ensureWallet or restoreFromMnemonic is called`() = runBlocking {
        val manager = newManager()

        assertFalse(manager.hasWallet())
        // .getOrThrow() (not a bare call) so a native-binding failure surfaces here as the test's
        // own failure cause, instead of cascading into a generic "no wallet" error from a later call.
        manager.ensureWallet(Network.BITCOIN).getOrThrow()

        assertTrue(manager.hasWallet())
    }

    @Test
    fun `ensureWallet generates a fresh 12-word mnemonic when none exists`() = runBlocking {
        val manager = newManager()

        manager.ensureWallet(Network.BITCOIN).getOrThrow()

        val mnemonic = manager.revealMnemonic().getOrThrow()
        assertEquals(12, mnemonic.trim().split(" ").size)
    }

    @Test
    fun `ensureWallet does not overwrite an existing mnemonic`() = runBlocking {
        val manager = newManager()
        manager.ensureWallet(Network.BITCOIN).getOrThrow()
        val original = manager.revealMnemonic().getOrThrow()

        manager.ensureWallet(Network.BITCOIN).getOrThrow()

        assertEquals(original, manager.revealMnemonic().getOrThrow())
    }

    @Test
    fun `restoreFromMnemonic accepts a valid phrase and reveals it back verbatim`() = runBlocking {
        val manager = newManager()

        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.BITCOIN).getOrThrow()

        assertEquals(TEST_MNEMONIC, manager.revealMnemonic().getOrThrow())
    }

    @Test
    fun `restoreFromMnemonic rejects a mnemonic with an invalid checksum`() = runBlocking {
        val manager = newManager()
        // Same 12 words as TEST_MNEMONIC, but the last word is swapped so the BIP39 checksum
        // no longer validates ("zoo" is a real wordlist entry, just the wrong one here).
        val badChecksum = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zoo"

        val result = manager.restoreFromMnemonic(badChecksum, Network.BITCOIN)

        assertTrue(result.isFailure)
    }

    @Test
    fun `revealMnemonic fails before any wallet exists`() = runBlocking {
        val manager = newManager()

        val result = manager.revealMnemonic()

        assertTrue(result.isFailure)
    }

    @Test
    fun `watchDescriptors returns two distinct public-only descriptors`() = runBlocking {
        val manager = newManager()
        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.BITCOIN).getOrThrow()

        val descriptors = manager.watchDescriptors(Network.BITCOIN).getOrThrow()

        assertEquals(2, descriptors.size)
        assertNotEquals(descriptors[0], descriptors[1])
        descriptors.forEach { descriptor ->
            assertTrue("descriptor should be a wpkh() descriptor: $descriptor", descriptor.startsWith("wpkh("))
            assertFalse(
                "descriptor must not leak private key material: $descriptor",
                descriptor.contains("prv", ignoreCase = true),
            )
        }
    }

    @Test
    fun `getNewReceiveAddress matches BIP84's own published test vector and increments gap-free`() = runBlocking {
        val manager = newManager()
        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.BITCOIN).getOrThrow()

        val first = manager.getNewReceiveAddress(Network.BITCOIN).getOrThrow()
        val second = manager.getNewReceiveAddress(Network.BITCOIN).getOrThrow()

        // BIP84 spec test vectors for this exact mnemonic: account 0, first two receiving addresses.
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", first)
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", second)
    }

    @Test
    fun `getNewReceiveAddress uses the testnet hrp and coin type for non-mainnet networks`() = runBlocking {
        val manager = newManager()
        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.TESTNET).getOrThrow()

        val address = manager.getNewReceiveAddress(Network.TESTNET).getOrThrow()

        assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", address)
    }

    @Test
    fun `signCoinjoinContribution fails when the wallet does not control the input's scriptPubKey`() = runBlocking {
        val manager = newManager()
        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.BITCOIN).getOrThrow()
        val foreignUtxo = WalletUtxo(
            txid = "aa".repeat(32),
            vout = 0,
            amountSats = 100_000L,
            scriptPubKeyHex = "0014" + "ff".repeat(20),
            address = null,
            confirmations = 6,
        )

        val result = manager.signCoinjoinContribution(
            network = Network.BITCOIN,
            input = foreignUtxo,
            inputPrevTxHex = "00",
            allOutputs = listOf(CoinjoinOutput("0014" + "44".repeat(20), 99_000L)),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `signCoinjoinContribution produces the exact expected DER signature for a known key and inputs`() = runBlocking {
        val manager = newManager()
        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.BITCOIN).getOrThrow()
        // Forces derivation of m/84h/0h/0h/0/0 - the same BIP84 test-vector key used above,
        // whose private key (and therefore expected signature) was computed independently.
        val ownedAddress = manager.getNewReceiveAddress(Network.BITCOIN).getOrThrow()
        val ownedScriptPubKeyHex = TxPrimitives.bytesToHex(
            TxPrimitives.p2wpkhScriptPubKey(SegwitAddress.decodeProgram(ownedAddress)!!),
        )
        val utxo = WalletUtxo(
            txid = "aa".repeat(32),
            vout = 1,
            amountSats = 100_000L,
            scriptPubKeyHex = ownedScriptPubKeyHex,
            address = ownedAddress,
            confirmations = 6,
        )
        val outputs = listOf(
            CoinjoinOutput("0014" + "44".repeat(20), 49_700L),
            CoinjoinOutput("0014" + "55".repeat(20), 50_000L),
        )

        val contribution = manager.signCoinjoinContribution(
            network = Network.BITCOIN,
            input = utxo,
            inputPrevTxHex = "00",
            allOutputs = outputs,
        ).getOrThrow()

        assertEquals("aa".repeat(32), contribution.txid)
        assertEquals(1, contribution.vout)
        assertEquals(0xfffffffdL, contribution.sequence)
        assertEquals("0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c", contribution.witnessPubKeyHex)
        assertEquals(
            "3045022100fa7171a9d92ac05240e477cb6bb54f7a644e86924b6547809ccc314ea27c4b4302201f4d4cc278140cba7b6d7ac1badc218365ee4a69cf0e0ffc2e4754f7cd2dee6d81",
            contribution.witnessSignatureHex,
        )
    }

    @Test
    fun `signCoinjoinContribution's signature independently verifies against the recomputed sighash`() = runBlocking {
        val manager = newManager()
        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.BITCOIN).getOrThrow()
        val ownedAddress = manager.getNewReceiveAddress(Network.BITCOIN).getOrThrow()
        val pubKeyHash = SegwitAddress.decodeProgram(ownedAddress)!!
        val utxo = WalletUtxo(
            txid = "bb".repeat(32),
            vout = 2,
            amountSats = 250_000L,
            scriptPubKeyHex = TxPrimitives.bytesToHex(TxPrimitives.p2wpkhScriptPubKey(pubKeyHash)),
            address = ownedAddress,
            confirmations = 6,
        )
        val outputs = listOf(CoinjoinOutput("0014" + "66".repeat(20), 249_000L))

        val contribution = manager.signCoinjoinContribution(
            network = Network.BITCOIN,
            input = utxo,
            inputPrevTxHex = "00",
            allOutputs = outputs,
        ).getOrThrow()

        val expectedSighash = TxPrimitives.sighashAllAnyoneCanPay(
            input = TxPrimitives.SighashInput(
                outpointTxidBE = TxPrimitives.hexToBytes(utxo.txid),
                vout = utxo.vout,
                pubKeyHash = pubKeyHash,
                amountSats = utxo.amountSats,
                sequence = contribution.sequence,
            ),
            outputs = outputs.map { TxPrimitives.hexToBytes(it.scriptPubKeyHex) to it.amountSats },
            lockTime = 0,
        )
        val sigDerOnly = TxPrimitives.hexToBytes(contribution.witnessSignatureHex).dropLast(1).toByteArray()
        val pubKey = TxPrimitives.hexToBytes(contribution.witnessPubKeyHex)

        // Secp256k1.verify() accepts either compact (64-byte) or DER signatures, so it alone
        // can't catch a regression back to the compact format the SegWit witness rejects -
        // assert the DER SEQUENCE tag explicitly (a compact r||s signature never starts with 0x30).
        assertEquals(0x30, sigDerOnly[0].toInt() and 0xff)
        assertTrue(Secp256k1.verify(sigDerOnly, expectedSighash, pubKey))
    }

    @Test
    fun `getNewReceiveAddress is safe under concurrent callers - no two ever get the same index or address`() = runBlocking {
        val keyStore = SlowFakeWalletKeyStore()
        val manager = WalletManagerImpl(keyStore)
        manager.restoreFromMnemonic(TEST_MNEMONIC, Network.BITCOIN).getOrThrow()

        // Dispatchers.Default is a real multi-threaded pool - this is genuine concurrency, not
        // just interleaved suspension points on a single thread.
        val addresses = coroutineScope {
            (1..CONCURRENT_CALLERS)
                .map { async(Dispatchers.Default) { manager.getNewReceiveAddress(Network.BITCOIN).getOrThrow() } }
                .awaitAll()
        }

        assertEquals(
            "every concurrent caller must get a distinct address - a duplicate means the index " +
                "counter's read-increment-write raced and the same key was derived (and would be " +
                "reused on-chain) twice",
            CONCURRENT_CALLERS,
            addresses.toSet().size,
        )
        assertEquals(CONCURRENT_CALLERS, keyStore.getNextExternalIndex())
    }

    /**
     * Wraps [FakeWalletKeyStore] with a deliberate delay between reading and the caller's
     * subsequent write of the next-external-index counter, to reliably widen the race window for
     * the concurrency test above instead of depending on thread-scheduling luck.
     */
    private class SlowFakeWalletKeyStore(
        private val delegate: WalletKeyStore = FakeWalletKeyStore(),
    ) : WalletKeyStore by delegate {
        override fun getNextExternalIndex(): Int {
            val index = delegate.getNextExternalIndex()
            Thread.sleep(READ_TO_WRITE_DELAY_MS)
            return index
        }
    }

    private companion object {
        const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val CONCURRENT_CALLERS = 20
        const val READ_TO_WRITE_DELAY_MS = 5L
    }
}
