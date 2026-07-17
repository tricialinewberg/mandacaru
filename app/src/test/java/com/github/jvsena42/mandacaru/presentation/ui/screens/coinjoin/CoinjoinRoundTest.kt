package com.github.jvsena42.mandacaru.presentation.ui.screens.coinjoin

import com.florestad.Network as FlorestaNetwork
import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.data.network.ProxyReachabilityChecker
import com.github.jvsena42.mandacaru.domain.coinjoin.CoinjoinEngine
import com.github.jvsena42.mandacaru.domain.coinjoin.PoolContent
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.GetTransactionResponse
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.GetTxOutResponse
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.ListUnspentResponse
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.ScriptPubKey
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.TransactionResult
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.TxOutResult
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.UnspentItem
import com.github.jvsena42.mandacaru.domain.wallet.WalletUtxo
import com.github.jvsena42.mandacaru.fakes.FakeFlorestaRpc
import com.github.jvsena42.mandacaru.fakes.FakeNostrClient
import com.github.jvsena42.mandacaru.fakes.FakeNostrRelay
import com.github.jvsena42.mandacaru.fakes.FakeWalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises a full three-peer round (the pool creator plus two joiners) end to end over a shared
 * in-memory Nostr relay, using real [com.github.jvsena42.mandacaru.domain.nostr.NostrCrypto] for
 * event signing/DM encryption - the same code path production peers use to coordinate. This is
 * the regression test for the round hanging or silently no-oping instead of completing: before
 * the fix, non-creator peers returned immediately without signing or replying, the creator never
 * signed its own contribution, and the DM-collection loops never terminated even when satisfied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoinjoinRoundTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a full round completes end-to-end - every peer signs and the creator broadcasts`() = runBlocking {
        val relay = FakeNostrRelay()
        val allTxids = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64))
        val creator = TestPeer(relay, DENOMINATION_SATS, txid = allTxids[0], allTxids = allTxids)
        val joinerA = TestPeer(relay, DENOMINATION_SATS, txid = allTxids[1], allTxids = allTxids)
        val joinerB = TestPeer(relay, DENOMINATION_SATS, txid = allTxids[2], allTxids = allTxids)

        creator.viewModel.onAction(CoinjoinAction.OnDenominationChanged(DENOMINATION_SATS.toString()))
        creator.viewModel.onAction(CoinjoinAction.OnConfirmCreatePool)

        val pool = awaitPool(joinerA.viewModel)
        joinerA.viewModel.onAction(CoinjoinAction.OnClickJoinPool(pool))
        joinerB.viewModel.onAction(CoinjoinAction.OnClickJoinPool(pool))

        awaitBroadcast(creator.viewModel)

        assertEquals(1, creator.rpc.sentRawTransactions.size)
        assertTrue(creator.viewModel.uiState.value.activePoolStatus.startsWith("Broadcast:"))
        assertEquals(SIGNED_STATUS, joinerA.viewModel.uiState.value.activePoolStatus)
        assertEquals(SIGNED_STATUS, joinerB.viewModel.uiState.value.activePoolStatus)
        assertEquals("", creator.viewModel.uiState.value.errorMessage)
        assertEquals("", joinerA.viewModel.uiState.value.errorMessage)
        assertEquals("", joinerB.viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `registration stage times out and fails the round when not enough peers ever join`() = runBlocking {
        val relay = FakeNostrRelay()
        // Pool timeout is 1 second - short enough for a quick test, but expressed through the
        // real per-pool config (PoolContent.timeoutSeconds), same value announced to peers.
        val creator = TestPeer(relay, DENOMINATION_SATS, txid = "a".repeat(64), poolTimeoutSeconds = 1L)

        creator.viewModel.onAction(CoinjoinAction.OnDenominationChanged(DENOMINATION_SATS.toString()))
        creator.viewModel.onAction(CoinjoinAction.OnConfirmCreatePool)

        // No joiners ever register - the pool creator should give up instead of hanging forever.
        awaitRoundFailure(creator.viewModel)

        assertTrue(creator.viewModel.uiState.value.activePoolStatus.contains("timed out", ignoreCase = true))
        assertTrue(creator.viewModel.uiState.value.errorMessage.contains("timed out", ignoreCase = true))
        assertNull(creator.viewModel.uiState.value.activePoolId)
        assertTrue(creator.rpc.sentRawTransactions.isEmpty())
    }

    @Test
    fun `round fails when a registered peer goes silent and never signs`() = runBlocking {
        val relay = FakeNostrRelay()
        val allTxids = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64))
        // Short stage timeout so the test doesn't wait on the (1 hour) production default.
        val creator = TestPeer(relay, DENOMINATION_SATS, txid = allTxids[0], allTxids = allTxids, roundStageTimeoutMillis = 300L)
        val joinerA = TestPeer(relay, DENOMINATION_SATS, txid = allTxids[1], allTxids = allTxids)
        val silentPeer = SilentPeer(relay, DENOMINATION_SATS, txid = allTxids[2])

        creator.viewModel.onAction(CoinjoinAction.OnDenominationChanged(DENOMINATION_SATS.toString()))
        creator.viewModel.onAction(CoinjoinAction.OnConfirmCreatePool)

        val pool = awaitPool(joinerA.viewModel)
        joinerA.viewModel.onAction(CoinjoinAction.OnClickJoinPool(pool))
        // Registers its input (so the pool fills and the round starts) but never listens for
        // final_outputs or signs - standing in for a peer that drops off the relay mid-round.
        silentPeer.register(pool)

        awaitRoundFailure(creator.viewModel)

        assertTrue(creator.viewModel.uiState.value.activePoolStatus.contains("timed out", ignoreCase = true))
        assertNull(creator.viewModel.uiState.value.activePoolId)
        assertTrue(creator.rpc.sentRawTransactions.isEmpty())
    }

    @Test
    fun `round rejects a peer's registration when the claimed UTXO doesn't match the chain`() = runBlocking {
        val relay = FakeNostrRelay()
        val allTxids = listOf("a".repeat(64), "b".repeat(64), "d".repeat(64))
        // Short pool timeout - the fraudulent registration is rejected and never replaced, so the
        // round should time out rather than hang for the (1 hour) production default.
        val creator = TestPeer(relay, DENOMINATION_SATS, txid = allTxids[0], allTxids = allTxids, poolTimeoutSeconds = 1L)
        val joinerA = TestPeer(relay, DENOMINATION_SATS, txid = allTxids[1], allTxids = allTxids)
        val fraudulentPeer = FraudulentPeer(relay, DENOMINATION_SATS, txid = allTxids[2])

        creator.viewModel.onAction(CoinjoinAction.OnDenominationChanged(DENOMINATION_SATS.toString()))
        creator.viewModel.onAction(CoinjoinAction.OnConfirmCreatePool)

        val pool = awaitPool(joinerA.viewModel)
        joinerA.viewModel.onAction(CoinjoinAction.OnClickJoinPool(pool))
        fraudulentPeer.registerWithMismatchedScript(pool)

        // The fraudulent registration never counts toward the pool's required peer count (it's
        // validated and rejected before being added), so the round times out instead of
        // broadcasting a transaction that includes an unvalidated/fabricated input.
        awaitRoundFailure(creator.viewModel)

        assertTrue(creator.viewModel.uiState.value.activePoolStatus.contains("timed out", ignoreCase = true))
        assertNull(creator.viewModel.uiState.value.activePoolId)
        assertTrue(creator.rpc.sentRawTransactions.isEmpty())
    }

    @Test
    fun `CoinJoin is blocked when Tor is enabled but the SOCKS proxy is unreachable`() = runBlocking {
        val relay = FakeNostrRelay()
        val prefs = FakePreferencesDataSource(initialBooleans = mapOf(PreferenceKeys.TOR_ENABLED to true))
        val creator = TestPeer(
            relay,
            DENOMINATION_SATS,
            txid = "a".repeat(64),
            preferencesDataSource = prefs,
            proxyReachabilityChecker = FakeProxyReachabilityChecker(reachable = false),
        )

        creator.viewModel.onAction(CoinjoinAction.OnDenominationChanged(DENOMINATION_SATS.toString()))
        creator.viewModel.onAction(CoinjoinAction.OnConfirmCreatePool)

        awaitError(creator.viewModel)

        assertTrue(creator.viewModel.uiState.value.errorMessage.contains("Tor", ignoreCase = true))
        assertNull(creator.viewModel.uiState.value.activePoolId)
        assertTrue(creator.rpc.sentRawTransactions.isEmpty())
    }

    @Test
    fun `CoinJoin proceeds normally when Tor is enabled and the SOCKS proxy is reachable`() = runBlocking {
        val relay = FakeNostrRelay()
        val prefs = FakePreferencesDataSource(initialBooleans = mapOf(PreferenceKeys.TOR_ENABLED to true))
        val creator = TestPeer(
            relay,
            DENOMINATION_SATS,
            txid = "a".repeat(64),
            preferencesDataSource = prefs,
            proxyReachabilityChecker = FakeProxyReachabilityChecker(reachable = true),
        )

        creator.viewModel.onAction(CoinjoinAction.OnDenominationChanged(DENOMINATION_SATS.toString()))
        creator.viewModel.onAction(CoinjoinAction.OnConfirmCreatePool)

        awaitActivePool(creator.viewModel)

        assertEquals("", creator.viewModel.uiState.value.errorMessage)
    }

    private suspend fun awaitPool(viewModel: CoinjoinViewModel): PoolContent = withTimeout(TIMEOUT_MS) {
        var pool: PoolContent? = null
        while (pool == null) {
            pool = viewModel.uiState.value.pools.firstOrNull()
            if (pool == null) delay(POLL_INTERVAL_MS)
        }
        pool
    }

    /**
     * Polls [CoinjoinViewModel.uiState] (a [kotlinx.coroutines.flow.StateFlow], so reads are
     * always up to date) rather than the underlying fake RPC's plain list, so this loop's memory
     * visibility of the round's outcome doesn't depend on unsynchronized state shared across the
     * IO-dispatched round coroutine and this polling thread.
     */
    private suspend fun awaitBroadcast(viewModel: CoinjoinViewModel) = withTimeout(TIMEOUT_MS) {
        while (!viewModel.uiState.value.activePoolStatus.startsWith("Broadcast:")) delay(POLL_INTERVAL_MS)
    }

    /** Polls until a round is reported failed (see [CoinjoinViewModel]'s private `failRound`): status text mentions "timed out". */
    private suspend fun awaitRoundFailure(viewModel: CoinjoinViewModel) = withTimeout(TIMEOUT_MS) {
        while (!viewModel.uiState.value.activePoolStatus.contains("timed out", ignoreCase = true)) delay(POLL_INTERVAL_MS)
    }

    /** Polls until any error is surfaced (e.g. the Tor-reachability gate rejecting the action). */
    private suspend fun awaitError(viewModel: CoinjoinViewModel) = withTimeout(TIMEOUT_MS) {
        while (viewModel.uiState.value.errorMessage.isEmpty()) delay(POLL_INTERVAL_MS)
    }

    /** Polls until a pool becomes this device's active round. */
    private suspend fun awaitActivePool(viewModel: CoinjoinViewModel) = withTimeout(TIMEOUT_MS) {
        while (viewModel.uiState.value.activePoolId == null) delay(POLL_INTERVAL_MS)
    }

    /** One simulated device: its own wallet/node/engine/viewmodel, sharing only the [relay]. */
    private class TestPeer(
        relay: FakeNostrRelay,
        denominationSats: Long,
        txid: String,
        /** Every txid this device's own node should recognize as a real, unspent coin - all
         * round participants' txids, since every peer validates every peer's claimed input
         * against its own node (see [CoinjoinEngine.validateRegisteredInput]), not just its own. */
        allTxids: List<String> = listOf(txid),
        poolTimeoutSeconds: Long = DEFAULT_POOL_TIMEOUT_SECONDS,
        roundStageTimeoutMillis: Long = DEFAULT_ROUND_STAGE_TIMEOUT_MILLIS,
        preferencesDataSource: PreferencesDataSource = FakePreferencesDataSource(),
        proxyReachabilityChecker: ProxyReachabilityChecker = FakeProxyReachabilityChecker(reachable = true),
    ) {
        val rpc = fakeRpcFor(denominationSats, txid, allTxids)
        private val engine = CoinjoinEngine(FakeNostrClient(relay), FakeWalletManager(), rpc)
        val viewModel = CoinjoinViewModel(
            engine = engine,
            florestaRpc = rpc,
            preferencesDataSource = preferencesDataSource,
            proxyReachabilityChecker = proxyReachabilityChecker,
            poolTimeoutSeconds = poolTimeoutSeconds,
            roundStageTimeoutMillis = roundStageTimeoutMillis,
        )
    }

    /**
     * A peer that registers an input directly through [CoinjoinEngine] - filling the pool so the
     * round starts - but never listens for `final_outputs` or signs afterward, standing in for a
     * peer whose relay connection drops mid-round.
     */
    private class SilentPeer(private val relay: FakeNostrRelay, private val denominationSats: Long, private val txid: String) {
        private val rpc = fakeRpcFor(denominationSats, txid)
        private val engine = CoinjoinEngine(FakeNostrClient(relay), FakeWalletManager(), rpc)

        suspend fun register(pool: PoolContent) {
            val prevTxHex = "00".repeat(50)
            val utxo = WalletUtxo(
                txid = txid,
                vout = 0,
                amountSats = denominationSats,
                scriptPubKeyHex = "0014" + "11".repeat(20),
                address = null,
                confirmations = 6,
            )
            engine.registerInput(FlorestaNetwork.BITCOIN, pool, utxo, prevTxHex).getOrThrow()
        }
    }

    /**
     * Registers a claim that doesn't match what's actually on-chain (per every honest peer's
     * shared fake RPC state, which agrees this peer's real coin has scriptPubKey
     * `"0014" + "11".repeat(20)"`) - standing in for a peer trying to sneak a fabricated or
     * mismatched UTXO into a round.
     */
    private class FraudulentPeer(private val relay: FakeNostrRelay, private val denominationSats: Long, private val txid: String) {
        private val engine = CoinjoinEngine(FakeNostrClient(relay), FakeWalletManager(), fakeRpcFor(denominationSats, txid))

        suspend fun registerWithMismatchedScript(pool: PoolContent) {
            val prevTxHex = "00".repeat(50)
            val utxo = WalletUtxo(
                txid = txid,
                vout = 0,
                amountSats = denominationSats,
                // Claims the pool denomination, but this script was never actually funded on-chain.
                scriptPubKeyHex = "0014" + "99".repeat(20),
                address = null,
                confirmations = 6,
            )
            engine.registerInput(FlorestaNetwork.BITCOIN, pool, utxo, prevTxHex).getOrThrow()
        }
    }

    private class FakePreferencesDataSource(
        initialBooleans: Map<PreferenceKeys, Boolean> = emptyMap(),
    ) : PreferencesDataSource {
        private val strings = mutableMapOf<PreferenceKeys, String>()
        private val booleans = mutableMapOf<PreferenceKeys, Boolean>().apply { putAll(initialBooleans) }
        override suspend fun setString(key: PreferenceKeys, value: String) {
            strings[key] = value
        }
        override suspend fun getString(key: PreferenceKeys, defaultValue: String): String =
            strings[key] ?: defaultValue
        override suspend fun setBoolean(key: PreferenceKeys, value: Boolean) {
            booleans[key] = value
        }
        override suspend fun getBoolean(key: PreferenceKeys, defaultValue: Boolean): Boolean =
            booleans[key] ?: defaultValue
    }

    /** Fake [ProxyReachabilityChecker] that always reports [reachable], regardless of host/port. */
    private class FakeProxyReachabilityChecker(private val reachable: Boolean) : ProxyReachabilityChecker {
        override suspend fun isReachable(host: String, port: Int): Boolean = reachable
    }

    private companion object {
        const val DENOMINATION_SATS = 100_000L
        const val SATS_PER_BTC = 100_000_000.0
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 20L
        const val SIGNED_STATUS = "Signed - waiting for the round to broadcast…"
        const val DEFAULT_POOL_TIMEOUT_SECONDS = 3600L
        const val DEFAULT_ROUND_STAGE_TIMEOUT_MILLIS = 60_000L

        /**
         * [allTxids] defaults to just [txid] for single-peer tests; multi-peer round tests pass
         * every participant's txid so each device's fake RPC agrees on the same on-chain state -
         * mirroring how every peer's real node would see the same shared ledger.
         */
        fun fakeRpcFor(denominationSats: Long, txid: String, allTxids: List<String> = listOf(txid)): FakeFlorestaRpc = FakeFlorestaRpc().apply {
            listUnspentResult = Result.success(
                ListUnspentResponse(
                    id = 1,
                    jsonrpc = "2.0",
                    result = listOf(
                        UnspentItem(
                            txid = txid,
                            vout = 0,
                            address = null,
                            scriptPubKey = "0014" + "11".repeat(20),
                            amount = denominationSats / SATS_PER_BTC,
                            confirmations = 6,
                        ),
                    ),
                ),
            )
            transactionResult = Result.success(
                GetTransactionResponse(
                    id = 1,
                    jsonrpc = "2.0",
                    result = TransactionResult(
                        blockhash = null,
                        blocktime = null,
                        confirmations = 6,
                        hash = null,
                        hex = "00".repeat(50),
                        inActiveChain = null,
                        locktime = null,
                        size = null,
                        time = null,
                        txid = txid,
                        version = null,
                        vin = null,
                        vout = null,
                        vsize = null,
                        weight = null,
                    ),
                ),
            )
            getTxOutResults = allTxids.associate { knownTxid ->
                "$knownTxid:0" to Result.success(
                    GetTxOutResponse(
                        id = 1,
                        jsonrpc = "2.0",
                        result = TxOutResult(
                            bestblock = null,
                            confirmations = 6,
                            value = denominationSats / SATS_PER_BTC,
                            scriptPubKey = ScriptPubKey(
                                asm = null,
                                hex = "0014" + "11".repeat(20),
                                reqSigs = null,
                                type = null,
                                addresses = null,
                            ),
                            coinbase = null,
                        ),
                    ),
                )
            }
        }
    }
}
