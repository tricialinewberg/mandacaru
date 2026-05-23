package com.github.jvsena42.mandacaru.domain.scan

sealed interface ScanState {
    data object Idle : ScanState

    data class InProgress(val progress: Float) : ScanState

    class Complete(val payload: ByteArray, val transport: ScanTransport) : ScanState

    data class Error(val reason: String) : ScanState
}
