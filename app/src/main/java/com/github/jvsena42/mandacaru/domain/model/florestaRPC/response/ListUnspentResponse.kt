package com.github.jvsena42.mandacaru.domain.model.florestaRPC.response

import com.google.gson.annotations.SerializedName

data class ListUnspentResponse(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("jsonrpc")
    val jsonrpc: String?,
    @SerializedName("result")
    val result: List<UnspentItem>?
)

data class UnspentItem(
    @SerializedName("txid")
    val txid: String,
    @SerializedName("vout")
    val vout: Int,
    @SerializedName("address")
    val address: String?,
    @SerializedName("scriptPubKey")
    val scriptPubKey: String?,
    @SerializedName("amount")
    val amount: Double,
    @SerializedName("confirmations")
    val confirmations: Int
)
