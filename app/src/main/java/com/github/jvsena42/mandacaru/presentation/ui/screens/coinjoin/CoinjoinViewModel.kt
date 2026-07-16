package com.github.jvsena42.mandacaru.presentation.ui.screens.coinjoin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.florestad.Network as FlorestaNetwork
import com.github.jvsena42.mandacaru.data.FlorestaRpc
import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.data.floresta.toFlorestaNetwork
import com.github.jvsena42.mandacaru.domain.bitcoin.TxPrimitives
import com.github.jvsena42.mandacaru.domain.coinjoin.CoinjoinEngine
import com.github.jvsena42.mandacaru.domain.coinjoin.PoolContent
import com.github.jvsena42.mandacaru.domain.coinjoin.RegisteredInput
import com.github.jvsena42.mandacaru.domain.nostr.NostrCrypto
import com.github.jvsena42.mandacaru.domain.wallet.CoinjoinOutput
import com.github.jvsena42.mandacaru.domain.wallet.SignedContribution
import com.github.jvsena42.mandacaru.domain.wallet.WalletUtxo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Default public relays; user-editable list lands in Settings. */
val DEFAULT_NOSTR_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.snort.social")

class CoinjoinViewModel(
    private val engine: CoinjoinEngine,
    private val florestaRpc: FlorestaRpc,
    private val preferencesDataSource: PreferencesDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoinjoinUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            engine.connectAndDiscover(DEFAULT_NOSTR_RELAYS)
            engine.poolAnnouncements().collect { pool ->
                _uiState.update { state ->
                    if (state.pools.any { it.id == pool.id }) state else state.copy(pools = state.pools + pool, isLoading = false)
                }
            }
        }
    }

    fun onAction(action: CoinjoinAction) {
        when (action) {
            CoinjoinAction.OnClickCreatePool -> _uiState.update { it.copy(isCreateDialogVisible = true) }
            is CoinjoinAction.OnDenominationChanged -> _uiState.update { it.copy(denominationSatsInput = action.value) }
            CoinjoinAction.OnDismissCreateDialog -> _uiState.update { it.copy(isCreateDialogVisible = false) }
            CoinjoinAction.OnConfirmCreatePool -> confirmCreatePool()
            is CoinjoinAction.OnClickJoinPool -> joinPool(action.pool)
            CoinjoinAction.ClearSnackBarMessage -> _uiState.update { it.copy(errorMessage = "") }
        }
    }

    private fun confirmCreatePool() {
        val denomination = _uiState.value.denominationSatsInput.toLongOrNull()
        if (denomination == null || denomination <= 0) {
            showError("Enter a valid denomination in sats")
            return
        }
        _uiState.update { it.copy(isCreateDialogVisible = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val network = currentNetwork()
            val utxo = findEligibleUtxo(denomination) ?: run {
                showError("No spendable coin of exactly $denomination sats. Prepare one first.")
                return@launch
            }
            val prevTxHex = fetchPrevTxHex(utxo.txid)

            engine.createPool(
                denominationSats = denomination,
                peers = MIN_POOL_PEERS,
                timeoutSeconds = DEFAULT_TIMEOUT_SECONDS,
                relay = DEFAULT_NOSTR_RELAYS.first(),
                feeRateSatVb = DEFAULT_FEE_RATE,
            ).onSuccess { local ->
                _uiState.update { it.copy(activePoolId = local.pool.id, activePoolStatus = "Waiting for peers to join…") }
                val registration = engine.registerInput(network, local.pool, utxo, prevTxHex).getOrNull()
                if (registration != null) {
                    runRound(
                        network = network,
                        pool = local.pool,
                        ownRegistration = registration.registeredInputs.first(),
                        ownRegistrationPrivateKeyHex = registration.ephemeralPrivateKeyHex,
                        isCreator = true,
                        creatorPrivateKeyHex = local.ephemeralPrivateKeyHex,
                    )
                }
            }.onFailure { showError(it.message ?: "Failed to create pool") }
        }
    }

    private fun joinPool(pool: PoolContent) {
        viewModelScope.launch(Dispatchers.IO) {
            val network = currentNetwork()
            val utxo = findEligibleUtxo(pool.denominationSats) ?: run {
                showError("No spendable coin of exactly ${pool.denominationSats} sats. Prepare one first.")
                return@launch
            }
            val prevTxHex = fetchPrevTxHex(utxo.txid)
            engine.registerInput(network, pool, utxo, prevTxHex)
                .onSuccess { local ->
                    _uiState.update { it.copy(activePoolId = pool.id, activePoolStatus = "Registered - waiting for the round to fill…") }
                    runRound(
                        network = network,
                        pool = pool,
                        ownRegistration = local.registeredInputs.first(),
                        ownRegistrationPrivateKeyHex = local.ephemeralPrivateKeyHex,
                        isCreator = false,
                    )
                }
                .onFailure { showError(it.message ?: "Failed to join pool") }
        }
    }

    /**
     * Drives this device's side of one round after registration.
     *
     * The creator waits for every other peer's registration DM, fans out the
     * agreed final output list, then - like every other peer - signs its own
     * input and waits for everyone's signed-input DM to merge and broadcast.
     * Non-creator peers just wait for the creator's final output list, sign
     * their own input, and send it back; the creator does the merging.
     * Kept intentionally simple (no timeout/retry handling yet) for this
     * first port - see the PR notes.
     */
    @Suppress("LongParameterList")
    private suspend fun runRound(
        network: FlorestaNetwork,
        pool: PoolContent,
        ownRegistration: RegisteredInput,
        ownRegistrationPrivateKeyHex: String,
        isCreator: Boolean,
        creatorPrivateKeyHex: String = ownRegistrationPrivateKeyHex,
    ) {
        if (!isCreator) {
            val outputs = mutableListOf<CoinjoinOutput>()
            collectDirectMessages(ownRegistrationPrivateKeyHex) { _, payload ->
                if (payload.optString("type") != "final_outputs") return@collectDirectMessages false
                outputs.addAll(parseFinalOutputs(payload))
                true
            }
            if (outputs.isEmpty()) {
                showError("Never received this round's final output list")
                return
            }
            engine.signAndSubmit(
                network = network,
                myPrivateKeyHex = ownRegistrationPrivateKeyHex,
                creatorPublicKeyHex = pool.publicKey,
                myRegistration = ownRegistration,
                allOutputs = outputs,
            ).onSuccess {
                _uiState.update { it.copy(activePoolStatus = "Signed - waiting for the round to broadcast…") }
            }.onFailure { showError(it.message ?: "Failed to sign this round's input") }
            return
        }

        val registrations = mutableListOf<RegisteredInput>()
        collectDirectMessages(creatorPrivateKeyHex) { senderPubKeyHex, payload ->
            if (payload.optString("type") == "register" && registrations.size < pool.peers) {
                registrations.add(RegisteredInput.fromJson(peerPublicKey = senderPubKeyHex, json = payload))
            }
            registrations.size >= pool.peers
        }

        val outputs = engine.broadcastFinalOutputs(pool, creatorPrivateKeyHex, registrations).getOrElse {
            showError(it.message ?: "Failed to finalize round outputs")
            return
        }

        engine.signAndSubmit(
            network = network,
            myPrivateKeyHex = ownRegistrationPrivateKeyHex,
            creatorPublicKeyHex = pool.publicKey,
            myRegistration = ownRegistration,
            allOutputs = outputs,
        ).onFailure {
            showError(it.message ?: "Failed to sign this round's own input")
            return
        }

        val contributions = mutableListOf<SignedContribution>()
        collectDirectMessages(creatorPrivateKeyHex) { _, payload ->
            if (payload.optString("type") == "signed_input" && contributions.size < registrations.size) {
                contributions.add(
                    SignedContribution(
                        txid = payload.getString("txid"),
                        vout = payload.getInt("vout"),
                        sequence = payload.getLong("sequence"),
                        witnessSignatureHex = payload.getString("signature"),
                        witnessPubKeyHex = payload.getString("pubkey"),
                    ),
                )
            }
            contributions.size >= registrations.size
        }

        engine.finalizeAndBroadcast(contributions, outputs)
            .onSuccess { txid -> _uiState.update { it.copy(activePoolStatus = "Broadcast: $txid") } }
            .onFailure { showError(it.message ?: "Failed to broadcast the joined transaction") }
    }

    /** Parses the `final_outputs` DM payload's `"scriptPubKeyHex:amount"` entries back into outputs. */
    private fun parseFinalOutputs(payload: JSONObject): List<CoinjoinOutput> {
        val array = payload.getJSONArray("outputs")
        return List(array.length()) { index ->
            val (scriptPubKeyHex, amount) = array.getString(index).split(":", limit = 2)
            CoinjoinOutput(scriptPubKeyHex, amount.toLong())
        }
    }

    /**
     * Collects decrypted DM payloads addressed to this ephemeral key until [onPayload] returns
     * true. Runs the underlying flow collection in a child coroutine so it can be cancelled as
     * soon as [onPayload] is satisfied, rather than collecting from the (never-completing) relay
     * event stream forever.
     */
    private suspend fun collectDirectMessages(myPrivateKeyHex: String, onPayload: (String, JSONObject) -> Boolean) = coroutineScope {
        val privateKey = TxPrimitives.hexToBytes(myPrivateKeyHex)
        val job = launch {
            engine.directMessages().collect { event ->
                val shared = NostrCrypto.sharedSecret(privateKey, event.pubKey)
                val payload = runCatching { JSONObject(NostrCrypto.decryptDirectMessage(event.content, shared)) }.getOrNull()
                    ?: return@collect
                if (onPayload(event.pubKey, payload)) cancel()
            }
        }
        job.join()
    }

    private suspend fun findEligibleUtxo(denominationSats: Long): WalletUtxo? {
        var found: WalletUtxo? = null
        florestaRpc.listUnspent().collect { result ->
            result.onSuccess { response ->
                found = response.result.orEmpty().firstOrNull { (it.amount * SATS_PER_BTC).toLong() == denominationSats }
                    ?.let { item ->
                        WalletUtxo(
                            txid = item.txid,
                            vout = item.vout,
                            amountSats = (item.amount * SATS_PER_BTC).toLong(),
                            scriptPubKeyHex = item.scriptPubKey.orEmpty(),
                            address = item.address,
                            confirmations = item.confirmations,
                        )
                    }
            }
        }
        return found
    }

    private suspend fun fetchPrevTxHex(txid: String): String {
        var hex = ""
        florestaRpc.getTransaction(txid).collect { result ->
            result.onSuccess { hex = it.result?.hex.orEmpty() }
        }
        return hex
    }

    private suspend fun currentNetwork(): FlorestaNetwork =
        preferencesDataSource.getString(
            PreferenceKeys.CURRENT_NETWORK,
            FlorestaNetwork.BITCOIN.name,
        ).toFlorestaNetwork()

    private fun showError(message: String) {
        Log.w(TAG, message)
        _uiState.update { it.copy(errorMessage = message, isLoading = false) }
    }

    private companion object {
        const val TAG = "CoinjoinViewModel"
        const val SATS_PER_BTC = 100_000_000.0
        const val MIN_POOL_PEERS = 3
        const val DEFAULT_TIMEOUT_SECONDS = 3600L
        const val DEFAULT_FEE_RATE = 1.0
    }
}
