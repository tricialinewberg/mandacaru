package com.github.jvsena42.mandacaru.domain.coinjoin

import com.github.jvsena42.mandacaru.domain.wallet.WalletUtxo
import org.json.JSONObject

/** A CoinJoin pool as announced on Nostr (kind 2022). Peers agree on a fixed denomination. */
data class PoolContent(
    val id: String,
    val publicKey: String,
    val denominationSats: Long,
    val peers: Int,
    val timeoutSeconds: Long,
    val relay: String,
    val feeRateSatVb: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "pool")
        put("id", id)
        put("public_key", publicKey)
        put("denomination", denominationSats)
        put("peers", peers)
        put("timeout", timeoutSeconds)
        put("relay", relay)
        put("fee_rate", feeRateSatVb)
    }

    companion object {
        fun fromJson(json: JSONObject): PoolContent = PoolContent(
            id = json.getString("id"),
            publicKey = json.getString("public_key"),
            denominationSats = json.getLong("denomination"),
            peers = json.getInt("peers"),
            timeoutSeconds = json.getLong("timeout"),
            relay = json.getString("relay"),
            feeRateSatVb = json.optDouble("fee_rate", 1.0),
        )
    }
}

/** This device's own copy of a pool it created or joined, including its private state. */
data class LocalPoolContent(
    val pool: PoolContent,
    /** Ephemeral Nostr key used only for this round - never the wallet's own identity. */
    val ephemeralPrivateKeyHex: String,
    val registeredOutputAddress: String? = null,
    val registeredInputs: List<RegisteredInput> = emptyList(),
)

/** One peer's registered input + destination output for a round. */
data class RegisteredInput(
    val peerPublicKey: String,
    val utxo: WalletUtxo,
    val prevTxHex: String,
    val outputScriptPubKeyHex: String,
    val outputAmountSats: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "register")
        put("txid", utxo.txid)
        put("vout", utxo.vout)
        put("prev_tx", prevTxHex)
        put("script_pub_key", utxo.scriptPubKeyHex)
        put("amount", utxo.amountSats)
        put("output_script_pub_key", outputScriptPubKeyHex)
        put("output_amount", outputAmountSats)
    }

    companion object {
        fun fromJson(peerPublicKey: String, json: JSONObject): RegisteredInput = RegisteredInput(
            peerPublicKey = peerPublicKey,
            utxo = WalletUtxo(
                txid = json.getString("txid"),
                vout = json.getInt("vout"),
                amountSats = json.getLong("amount"),
                scriptPubKeyHex = json.getString("script_pub_key"),
                address = null,
                confirmations = 0,
            ),
            prevTxHex = json.getString("prev_tx"),
            outputScriptPubKeyHex = json.getString("output_script_pub_key"),
            outputAmountSats = json.getLong("output_amount"),
        )
    }
}

/** A completed mix, kept locally for the CoinJoin history list. */
data class CoinJoinHistory(
    val relay: String,
    val poolId: String,
    val amountSats: Long,
    val txid: String,
    val timestampSeconds: Long,
)
