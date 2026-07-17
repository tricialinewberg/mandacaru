package com.github.jvsena42.mandacaru.domain.coinjoin

import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.GetTxOutResponse
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.ScriptPubKey
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.TxOutResult
import com.github.jvsena42.mandacaru.fakes.FakeFlorestaRpc
import com.github.jvsena42.mandacaru.fakes.FakeNostrClient
import com.github.jvsena42.mandacaru.fakes.FakeNostrRelay
import com.github.jvsena42.mandacaru.fakes.FakeWalletManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-level coverage for [CoinjoinEngine.validateRegisteredInput] - the fix for the CoinJoin bug
 * where a peer's claimed UTXO was never checked against the blockchain, so a malicious peer could
 * register a fabricated input. Each test configures the fake node's `gettxout` response directly,
 * independent of the full multi-peer round machinery exercised in `CoinjoinRoundTest`.
 */
class CoinjoinEngineValidationTest {

    private fun newEngine(rpc: FakeFlorestaRpc) = CoinjoinEngine(FakeNostrClient(FakeNostrRelay()), FakeWalletManager(), rpc)

    @Test
    fun `validateRegisteredInput succeeds for a real, unspent, matching UTXO`() = runBlocking {
        val rpc = FakeFlorestaRpc().apply { getTxOutResults = mapOf(OUTPOINT to Result.success(honestTxOut())) }

        val result = newEngine(rpc).validateRegisteredInput(honestClaim())

        assertTrue(result.isSuccess)
        assertTrue(rpc.getTxOutCalls.contains(TXID to VOUT))
    }

    @Test
    fun `validateRegisteredInput fails when the outpoint is spent or doesn't exist`() = runBlocking {
        // No entry configured for this outpoint - mirrors gettxout's real "null result" answer.
        val rpc = FakeFlorestaRpc()

        val result = newEngine(rpc).validateRegisteredInput(honestClaim())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("spent or does not exist"))
    }

    @Test
    fun `validateRegisteredInput fails when the on-chain amount doesn't match the claim`() = runBlocking {
        val rpc = FakeFlorestaRpc().apply {
            getTxOutResults = mapOf(OUTPOINT to Result.success(honestTxOut(valueBtc = (AMOUNT_SATS - 1) / SATS_PER_BTC)))
        }

        val result = newEngine(rpc).validateRegisteredInput(honestClaim())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("doesn't match its actual amount"))
    }

    @Test
    fun `validateRegisteredInput fails when the on-chain scriptPubKey doesn't match the claim`() = runBlocking {
        val rpc = FakeFlorestaRpc().apply {
            getTxOutResults = mapOf(OUTPOINT to Result.success(honestTxOut(scriptPubKeyHex = "0014" + "ff".repeat(20))))
        }

        val result = newEngine(rpc).validateRegisteredInput(honestClaim())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("doesn't match its actual scriptPubKey"))
    }

    @Test
    fun `validateRegisteredInput fails when the claimed scriptPubKey is not P2WPKH-shaped`() = runBlocking {
        val rpc = FakeFlorestaRpc()
        // Right length, wrong prefix (P2SH-shaped: OP_HASH160 push20 ... OP_EQUAL) instead of P2WPKH.
        val nonP2wpkhScript = "a914" + "11".repeat(20)
        val claim = RegisteredInputClaim(TXID, VOUT, nonP2wpkhScript, AMOUNT_SATS)

        val result = newEngine(rpc).validateRegisteredInput(claim)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("not a P2WPKH script"))
        // The RPC-independent shape check runs first - no gettxout call needed to reject this.
        assertTrue(rpc.getTxOutCalls.isEmpty())
    }

    @Test
    fun `validateRegisteredInput fails when the outpoint has zero confirmations`() = runBlocking {
        val rpc = FakeFlorestaRpc().apply { getTxOutResults = mapOf(OUTPOINT to Result.success(honestTxOut(confirmations = 0))) }

        val result = newEngine(rpc).validateRegisteredInput(honestClaim())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("not confirmed on-chain"))
    }

    private fun honestClaim(): RegisteredInputClaim = RegisteredInputClaim(TXID, VOUT, HONEST_SCRIPT_PUB_KEY_HEX, AMOUNT_SATS)

    private fun honestTxOut(
        valueBtc: Double = AMOUNT_SATS / SATS_PER_BTC,
        scriptPubKeyHex: String = HONEST_SCRIPT_PUB_KEY_HEX,
        confirmations: Int = 6,
    ): GetTxOutResponse = GetTxOutResponse(
        id = 1,
        jsonrpc = "2.0",
        result = TxOutResult(
            bestblock = null,
            confirmations = confirmations,
            value = valueBtc,
            scriptPubKey = ScriptPubKey(asm = null, hex = scriptPubKeyHex, reqSigs = null, type = null, addresses = null),
            coinbase = null,
        ),
    )

    private companion object {
        const val VOUT = 0
        const val AMOUNT_SATS = 100_000L
        const val SATS_PER_BTC = 100_000_000.0
        val TXID = "aa".repeat(32)
        val OUTPOINT = "$TXID:$VOUT"
        val HONEST_SCRIPT_PUB_KEY_HEX = "0014" + "11".repeat(20)
    }
}
