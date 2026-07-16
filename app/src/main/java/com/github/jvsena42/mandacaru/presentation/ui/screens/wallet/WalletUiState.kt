package com.github.jvsena42.mandacaru.presentation.ui.screens.wallet

data class WalletUiState(
    val hasWallet: Boolean = false,
    val balanceSats: Long = 0,
    val receiveAddress: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String = "",
    val isSeedDialogVisible: Boolean = false,
    val seedPhrase: String = "",
)

sealed interface WalletAction {
    data object OnClickNewAddress : WalletAction
    data object OnClickRevealSeed : WalletAction
    data object OnDismissSeedDialog : WalletAction
    data object ClearSnackBarMessage : WalletAction
}
