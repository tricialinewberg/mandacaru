package com.github.jvsena42.mandacaru.presentation.ui.screens.coinjoin

import com.github.jvsena42.mandacaru.domain.coinjoin.PoolContent

data class CoinjoinUiState(
    val pools: List<PoolContent> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String = "",
    val statusMessage: String = "",
    val isCreateDialogVisible: Boolean = false,
    val denominationSatsInput: String = "",
    val activePoolId: String? = null,
    val activePoolStatus: String = "",
)

sealed interface CoinjoinAction {
    data object OnClickCreatePool : CoinjoinAction
    data class OnDenominationChanged(val value: String) : CoinjoinAction
    data object OnConfirmCreatePool : CoinjoinAction
    data object OnDismissCreateDialog : CoinjoinAction
    data class OnClickJoinPool(val pool: PoolContent) : CoinjoinAction
    data object ClearSnackBarMessage : CoinjoinAction
}
