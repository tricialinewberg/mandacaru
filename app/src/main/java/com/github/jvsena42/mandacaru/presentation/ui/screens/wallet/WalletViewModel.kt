package com.github.jvsena42.mandacaru.presentation.ui.screens.wallet

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.mandacaru.data.FlorestaRpc
import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.data.floresta.toFlorestaNetwork
import com.github.jvsena42.mandacaru.domain.wallet.WalletManager
import com.florestad.Network as FlorestaNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WalletViewModel(
    private val walletManager: WalletManager,
    private val florestaRpc: FlorestaRpc,
    private val preferencesDataSource: PreferencesDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState = _uiState.asStateFlow()

    init {
        setUpWallet()
    }

    fun onAction(action: WalletAction) {
        when (action) {
            WalletAction.OnClickNewAddress -> generateNewAddress()
            WalletAction.OnClickRevealSeed -> revealSeed()
            WalletAction.OnDismissSeedDialog -> _uiState.update { it.copy(isSeedDialogVisible = false) }
            WalletAction.ClearSnackBarMessage -> _uiState.update { it.copy(errorMessage = "") }
        }
    }

    private fun setUpWallet() {
        viewModelScope.launch(Dispatchers.IO) {
            val network = currentNetwork()
            walletManager.ensureWallet(network)
                .onFailure { showError(it.message ?: "Failed to create wallet") }

            walletManager.watchDescriptors(network)
                .onSuccess { descriptors -> descriptors.forEach { loadDescriptorIntoNode(it) } }
                .onFailure { showError(it.message ?: "Failed to derive wallet descriptors") }

            val hasWallet = walletManager.hasWallet()
            _uiState.update { it.copy(hasWallet = hasWallet, isLoading = false) }
            refreshBalance()
        }
    }

    private suspend fun loadDescriptorIntoNode(descriptor: String) {
        florestaRpc.loadDescriptor(descriptor).collect { result ->
            result.onFailure { Log.w(TAG, "loadDescriptor failed for $descriptor: ${it.message}") }
        }
    }

    private fun refreshBalance() {
        viewModelScope.launch(Dispatchers.IO) {
            florestaRpc.listUnspent().collect { result ->
                result.onSuccess { response ->
                    val totalSats = response.result.orEmpty().sumOf { (it.amount * SATS_PER_BTC).toLong() }
                    _uiState.update { it.copy(balanceSats = totalSats) }
                }.onFailure { Log.w(TAG, "listUnspent failed: ${it.message}") }
            }
        }
    }

    private fun generateNewAddress() {
        viewModelScope.launch(Dispatchers.IO) {
            walletManager.getNewReceiveAddress(currentNetwork())
                .onSuccess { address -> _uiState.update { it.copy(receiveAddress = address) } }
                .onFailure { showError(it.message ?: "Failed to derive a new address") }
        }
    }

    private fun revealSeed() {
        viewModelScope.launch(Dispatchers.IO) {
            walletManager.revealMnemonic()
                .onSuccess { seed -> _uiState.update { it.copy(seedPhrase = seed, isSeedDialogVisible = true) } }
                .onFailure { showError(it.message ?: "Failed to reveal the seed phrase") }
        }
    }

    private suspend fun currentNetwork(): FlorestaNetwork =
        preferencesDataSource.getString(
            PreferenceKeys.CURRENT_NETWORK,
            FlorestaNetwork.BITCOIN.name,
        ).toFlorestaNetwork()

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message, isLoading = false) }
    }

    private companion object {
        const val TAG = "WalletViewModel"
        const val SATS_PER_BTC = 100_000_000.0
    }
}
