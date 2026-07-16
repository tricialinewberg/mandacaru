package com.github.jvsena42.mandacaru.presentation.ui.screens.coinjoin

import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.domain.coinjoin.CoinjoinEngine
import com.github.jvsena42.mandacaru.domain.coinjoin.PoolContent
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.GetTransactionResponse
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.ListUnspentResponse
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.TransactionResult
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.UnspentItem
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
        val creator = TestPeer(relay, DENOMINATION_SATS, txid = "a".repeat(64))
        val joinerA = TestPeer(relay, DENOMINATION_SATS, txid = "b".repeat(64))
        val joinerB = TestPeer(relay, DENOMINATION_SATS, txid = "c".repeat(64))

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

    /** One simulated device: its own wallet/node/engine/viewmodel, sharing only the [relay]. */
    private class TestPeer(relay: FakeNostrRelay, denominationSats: Long, txid: String) {
        val rpc = FakeFlorestaRpc().apply {
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
        }

        private val engine = CoinjoinEngine(FakeNostrClient(relay), FakeWalletManager(), rpc)
        val viewModel = CoinjoinViewModel(engine, rpc, FakePreferencesDataSource())
    }

    private class FakePreferencesDataSource : PreferencesDataSource {
        private val strings = mutableMapOf<PreferenceKeys, String>()
        private val booleans = mutableMapOf<PreferenceKeys, Boolean>()
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

    private companion object {
        const val DENOMINATION_SATS = 100_000L
        const val SATS_PER_BTC = 100_000_000.0
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 20L
        const val SIGNED_STATUS = "Signed - waiting for the round to broadcast…"
    }
}
