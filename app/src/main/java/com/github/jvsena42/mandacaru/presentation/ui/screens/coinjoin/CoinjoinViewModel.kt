package com.github.jvsena42.mandacaru.presentation.ui.screens.coinjoin

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.florestad.Network as FlorestaNetwork
import com.github.jvsena42.mandacaru.data.FlorestaRpc
import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.data.floresta.toFlorestaNetwork
import com.github.jvsena42.mandacaru.data.network.ProxyReachabilityChecker
import com.github.jvsena42.mandacaru.data.network.resolveNostrRelays
import com.github.jvsena42.mandacaru.data.network.resolveTorProxySettings
import com.github.jvsena42.mandacaru.domain.bitcoin.TxPrimitives
import com.github.jvsena42.mandacaru.domain.coinjoin.CoinjoinEngine
import com.github.jvsena42.mandacaru.domain.coinjoin.PoolContent
import com.github.jvsena42.mandacaru.domain.coinjoin.RegisteredInput
import com.github.jvsena42.mandacaru.domain.coinjoin.RegisteredInputClaim
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class CoinjoinViewModel(
    private val engine: CoinjoinEngine,
    private val florestaRpc: FlorestaRpc,
    private val preferencesDataSource: PreferencesDataSource,
    private val proxyReachabilityChecker: ProxyReachabilityChecker,
    /** How long a pool stays open waiting for [MIN_POOL_PEERS] registrations; also announced to peers as [PoolContent.timeoutSeconds]. */
    private val poolTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    /** How long each post-registration DM stage (final_outputs, signed_input) waits before the round is declared failed. */
    private val roundStageTimeoutMillis: Long = DEFAULT_ROUND_STAGE_TIMEOUT_MILLIS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoinjoinUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            engine.connectAndDiscover(preferencesDataSource.resolveNostrRelays())
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
            if (!ensureTorReachableIfEnabled()) return@launch
            val network = currentNetwork()
            val utxo = findEligibleUtxo(denomination) ?: run {
                showError("No spendable coin of exactly $denomination sats. Prepare one first.")
                return@launch
            }
            val prevTxHex = fetchPrevTxHex(utxo.txid)

            engine.createPool(
                denominationSats = denomination,
                peers = MIN_POOL_PEERS,
                timeoutSeconds = poolTimeoutSeconds,
                relay = preferencesDataSource.resolveNostrRelays().first(),
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
            if (!ensureTorReachableIfEnabled()) return@launch
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
     *
     * Each DM-collection stage (registration, final_outputs, signed_input) is
     * bounded by a timeout so a dropped peer fails the round instead of
     * hanging forever. There is no in-round retry: a timed-out round is left
     * failed and reported via [failRound], and the user starts a fresh round
     * (new ephemeral keys, new registration) rather than resuming a stale one.
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
            signAsNonCreator(network, pool, ownRegistration, ownRegistrationPrivateKeyHex)
            return
        }

        val registrations = collectValidRegistrations(pool, creatorPrivateKeyHex) ?: run {
            failRound("Round timed out - not enough peers registered in time")
            return
        }

        val outputs = engine.broadcastFinalOutputs(pool, creatorPrivateKeyHex, registrations).getOrElse {
            showError(it.message ?: "Failed to finalize round outputs")
            return
        }

        // Re-validate the full input set (including this creator's own) right before signing -
        // not because Point A above was skipped, but so every peer, this one included, only ever
        // signs against a set it has independently confirmed itself, in case a future change ever
        // lets a doctored set reach this point some other way.
        validateAllRegisteredInputs(registrations.map { RegisteredInputClaim.of(it) })?.let { reason ->
            failRound(reason)
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

        val contributions = collectSignedContributions(creatorPrivateKeyHex, registrations.size) ?: run {
            failRound("Round timed out - a peer never sent back its signed input")
            return
        }

        engine.finalizeAndBroadcast(contributions, outputs)
            .onSuccess { txid -> _uiState.update { it.copy(activePoolStatus = "Broadcast: $txid") } }
            .onFailure { showError(it.message ?: "Failed to broadcast the joined transaction") }
    }

    /**
     * Collects `register` DMs until [pool.peers][PoolContent.peers] valid registrations have
     * arrived, or `null` on timeout. Each registration is validated against this node's own chain
     * state before being counted - a rejected registration doesn't fill a slot, it just keeps the
     * round waiting (up to the existing registration timeout) for a legitimate replacement.
     */
    private suspend fun collectValidRegistrations(pool: PoolContent, creatorPrivateKeyHex: String): List<RegisteredInput>? {
        val registrations = mutableListOf<RegisteredInput>()
        val registered = collectDirectMessages(
            myPrivateKeyHex = creatorPrivateKeyHex,
            timeoutMillis = pool.timeoutSeconds * MILLIS_PER_SECOND,
        ) { senderPubKeyHex, payload ->
            if (payload.optString("type") == "register" && registrations.size < pool.peers) {
                val candidate = RegisteredInput.fromJson(peerPublicKey = senderPubKeyHex, json = payload)
                engine.validateRegisteredInput(RegisteredInputClaim.of(candidate)).fold(
                    onSuccess = { registrations.add(candidate) },
                    onFailure = { reason -> rejectRegistration(senderPubKeyHex, reason) },
                )
            }
            registrations.size >= pool.peers
        }
        return if (registered) registrations else null
    }

    private fun rejectRegistration(senderPubKeyHex: String, reason: Throwable) {
        Log.w(TAG, "Rejected registration from $senderPubKeyHex: ${reason.message}")
        _uiState.update {
            it.copy(activePoolStatus = "Rejected an invalid registration from a peer - waiting for others…")
        }
    }

    /** Non-creator leg: wait for the creator's final output list, sign this peer's own input, and send it back. */
    private suspend fun signAsNonCreator(
        network: FlorestaNetwork,
        pool: PoolContent,
        ownRegistration: RegisteredInput,
        ownRegistrationPrivateKeyHex: String,
    ) {
        val outputs = mutableListOf<CoinjoinOutput>()
        var inputClaims: List<RegisteredInputClaim> = emptyList()
        val received = collectDirectMessages(
            myPrivateKeyHex = ownRegistrationPrivateKeyHex,
            timeoutMillis = roundStageTimeoutMillis,
        ) { _, payload ->
            if (payload.optString("type") != "final_outputs") return@collectDirectMessages false
            outputs.addAll(parseFinalOutputs(payload))
            inputClaims = parseRegisteredInputClaims(payload)
            true
        }
        if (!received) {
            failRound("Round timed out - never received this round's final output list")
            return
        }
        // Non-creator peers never see other peers' registrations until this point - validate
        // every one of them against this node's own chain state before signing, exactly like the
        // creator does, so this peer never contributes a signature to a round it hasn't itself
        // confirmed is backed by real, unspent, correctly-shaped coins.
        validateAllRegisteredInputs(inputClaims)?.let { reason ->
            failRound(reason)
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
    }

    /** Collects [expectedCount] `signed_input` DMs addressed to the creator's identity, or `null` on timeout. */
    private suspend fun collectSignedContributions(creatorPrivateKeyHex: String, expectedCount: Int): List<SignedContribution>? {
        val contributions = mutableListOf<SignedContribution>()
        val completed = collectDirectMessages(
            myPrivateKeyHex = creatorPrivateKeyHex,
            timeoutMillis = roundStageTimeoutMillis,
        ) { _, payload ->
            if (payload.optString("type") == "signed_input" && contributions.size < expectedCount) {
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
            contributions.size >= expectedCount
        }
        return if (completed) contributions else null
    }

    /** Parses the `final_outputs` DM payload's `"scriptPubKeyHex:amount"` entries back into outputs. */
    private fun parseFinalOutputs(payload: JSONObject): List<CoinjoinOutput> {
        val array = payload.getJSONArray("outputs")
        return List(array.length()) { index ->
            val (scriptPubKeyHex, amount) = array.getString(index).split(":", limit = 2)
            CoinjoinOutput(scriptPubKeyHex, amount.toLong())
        }
    }

    /** Parses the `final_outputs` DM payload's `"inputs"` entries every peer validates before signing. */
    private fun parseRegisteredInputClaims(payload: JSONObject): List<RegisteredInputClaim> {
        val array = payload.optJSONArray("inputs") ?: return emptyList()
        return List(array.length()) { index -> RegisteredInputClaim.fromJson(array.getJSONObject(index)) }
    }

    /**
     * Confirms every registered input in the round against this device's own node before signing
     * - run by every peer, including the creator, so a malicious or dishonest peer (or creator)
     * can't sneak a fabricated UTXO into the round undetected. Returns a failure message naming
     * the first invalid claim, or `null` if every input checks out.
     */
    private suspend fun validateAllRegisteredInputs(claims: List<RegisteredInputClaim>): String? {
        claims.forEach { claim ->
            engine.validateRegisteredInput(claim).onFailure {
                return "Round aborted - a peer's registered input failed validation: ${it.message}"
            }
        }
        return null
    }

    /**
     * Collects decrypted DM payloads addressed to this ephemeral key until [onPayload] returns
     * true, or [timeoutMillis] elapses - returning `false` in that case so a stalled wait (e.g. a
     * peer that dropped off the relay mid-round) fails instead of hanging forever. Runs the
     * underlying flow collection in a child coroutine so it can be cancelled as soon as
     * [onPayload] is satisfied, rather than collecting from the (never-completing) relay event
     * stream forever.
     */
    private suspend fun collectDirectMessages(
        myPrivateKeyHex: String,
        timeoutMillis: Long,
        onPayload: suspend (String, JSONObject) -> Boolean,
    ): Boolean {
        val privateKey = TxPrimitives.hexToBytes(myPrivateKeyHex)
        val completed = withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
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
        }
        return completed != null
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

    /**
     * When the user has opted into Tor, refuses to start a round rather than silently falling
     * back to clearnet: CoinJoin's whole point is hiding pool registration/coordination traffic,
     * so a quiet clearnet fallback would deanonymize a user who believes they're protected.
     */
    private suspend fun ensureTorReachableIfEnabled(): Boolean {
        val settings = preferencesDataSource.resolveTorProxySettings()
        if (!settings.enabled) return true
        val port = settings.port
        if (port == null) {
            showError("Tor is enabled but the configured SOCKS port is invalid. Check Settings.")
            return false
        }
        if (!proxyReachabilityChecker.isReachable(settings.host, port)) {
            showError("Tor is enabled but unreachable at ${settings.host}:$port. Is Orbot running?")
            return false
        }
        return true
    }

    private fun showError(message: String) {
        Log.w(TAG, message)
        _uiState.update { it.copy(errorMessage = message, isLoading = false) }
    }

    /**
     * Reports a timed-out round as failed: same snackbar as [showError], plus a persistent
     * status and a cleared active pool so the user can start over.
     */
    private fun failRound(message: String) {
        Log.w(TAG, message)
        _uiState.update { it.copy(activePoolStatus = message, errorMessage = message, activePoolId = null, isLoading = false) }
    }

    private companion object {
        const val TAG = "CoinjoinViewModel"
        const val SATS_PER_BTC = 100_000_000.0
        const val MIN_POOL_PEERS = 3
        const val DEFAULT_TIMEOUT_SECONDS = 3600L
        const val DEFAULT_FEE_RATE = 1.0
        const val MILLIS_PER_SECOND = 1000L
        const val DEFAULT_ROUND_STAGE_TIMEOUT_MILLIS = 60_000L
    }
}
