package com.github.jvsena42.mandacaru.domain.model.florestaRPC.response

import com.google.gson.annotations.SerializedName

data class GetTxOutResponse(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("jsonrpc")
    val jsonrpc: String?,
    // `null` means the outpoint is spent or doesn't exist - not an RPC error, so callers must
    // treat a null result as a normal (if negative) answer rather than a failure to parse.
    @SerializedName("result")
    val result: TxOutResult?,
)

data class TxOutResult(
    @SerializedName("bestblock")
    val bestblock: String?,
    @SerializedName("confirmations")
    val confirmations: Int?,
    @SerializedName("value")
    val value: Double?,
    @SerializedName("scriptPubKey")
    val scriptPubKey: ScriptPubKey?,
    @SerializedName("coinbase")
    val coinbase: Boolean?,
)
