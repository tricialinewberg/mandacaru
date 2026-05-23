package com.github.jvsena42.mandacaru.presentation.ui.screens.transaction

import androidx.compose.runtime.Stable
import com.github.jvsena42.mandacaru.domain.model.florestaRPC.response.GetTransactionResponse
import com.github.jvsena42.mandacaru.domain.scan.DecodedTransaction

@Stable
data class TransactionUiState(
    val transactionId: String = "",
    val searchResult: GetTransactionResponse? = null,
    val isSearchLoading: Boolean = false,
    val rawTxHex: String = "",
    val broadcastResult: String = "",
    val isBroadcasting: Boolean = false,
    val errorMessage: String = "",
    val isScannerVisible: Boolean = false,
    val scanProgress: Float = 0f,
    val scanError: String = "",
    val isDecoding: Boolean = false,
    val decodedTx: DecodedTransaction? = null,
)
