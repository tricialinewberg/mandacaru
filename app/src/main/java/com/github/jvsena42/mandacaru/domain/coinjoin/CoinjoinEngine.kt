package com.github.jvsena42.mandacaru.domain.coinjoin

import com.florestad.Network
import com.github.jvsena42.mandacaru.common.runSuspendCatching
import com.github.jvsena42.mandacaru.data.FlorestaRpc
import com.github.jvsena42.mandacaru.domain.bitcoin.SegwitAddress
import com.github.jvsena42.mandacaru.domain.bitcoin.TxPrimitives
import com.github.jvsena42.mandacaru.domain.nostr.NostrClient
import com.github.jvsena42.mandacaru.domain.nostr.NostrCrypto
import com.github.jvsena42.mandacaru.domain.nostr.NostrEvent
import com.github.jvsena42.mandacaru.domain.nostr.NostrFilter
import com.github.jvsena42.mandacaru.domain.nostr.NostrKind
import com.github.jvsena42.mandacaru.domain.nostr.toHex
import com.github.jvsena42.mandacaru.domain.wallet.CoinjoinOutput
import com.github.jvsena42.mandacaru.domain.wallet.SignedContribution
import com.github.jvsena42.mandacaru.domain.wallet.WalletManager
import com.github.jvsena42.mandacaru.domain.wallet.WalletUtxo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import org.json.JSONObject

/**
 * Coordinator-less Joinstr round logic. There is no trusted party: a pool
 * "creator" is just the peer who happened to announce it and collects
 * everyone else's registrations, and whichever peer ends up with every
 * signed contribution can merge and broadcast the identical, valid
 * transaction - the [WalletManager]-produced signatures don't depend on who
 * assembles them.
 *
 * This class exposes the round as small, composable steps rather than one
 * end-to-end orchestration function, since each step is driven by a
 * different Nostr message arriving at a different time; the ViewModel layer
 * drives the state machine and calls into these as messages arrive.
 */
class CoinjoinEngine(
    private val nostrClient: NostrClient,
    private val walletManager: WalletManager,
    private val florestaRpc: FlorestaRpc,
) {

    /** Announcements from every connected relay, decoded from kind 2022 events. */
    fun poolAnnouncements(): Flow<PoolContent> =
        nostrClient.incomingEvents
            .filter { it.kind == NostrKind.POOL_ANNOUNCEMENT }
            .mapNotNull { event -> runCatching { PoolContent.fromJson(JSONObject(event.content)) }.getOrNull() }

    /** Raw encrypted DM events (kind 4) from every connected relay - callers decrypt with their own key. */
    fun directMessages(): Flow<NostrEvent> =
        nostrClient.incomingEvents.filter { it.kind == NostrKind.ENCRYPTED_DM }

    suspend fun connectAndDiscover(relays: List<String>) {
        nostrClient.connect(relays)
        nostrClient.subscribe(
            subscriptionId = "pools",
            filter = NostrFilter(kinds = listOf(NostrKind.POOL_ANNOUNCEMENT), limit = POOL_HISTORY_LIMIT),
        )
    }

    @Suppress("LongParameterList")
    suspend fun createPool(
        denominationSats: Long,
        peers: Int,
        timeoutSeconds: Long,
        relay: String,
        feeRateSatVb: Double,
    ): Result<LocalPoolContent> = runSuspendCatching {
        nostrClient.connect(listOf(relay))
        val privateKey = NostrCrypto.generatePrivateKey()
        val publicKeyHex = NostrCrypto.xOnlyPublicKey(privateKey).toHex()
        val pool = PoolContent(
            id = publicKeyHex.take(POOL_ID_LENGTH),
            publicKey = publicKeyHex,
            denominationSats = denominationSats,
            peers = peers,
            timeoutSeconds = timeoutSeconds,
            relay = relay,
            feeRateSatVb = feeRateSatVb,
        )
        val event = buildEvent(privateKey, NostrKind.POOL_ANNOUNCEMENT, tags = emptyList(), content = pool.toJson().toString())
        nostrClient.publish(event).getOrThrow()
        nostrClient.subscribe(
            subscriptionId = "reg-$publicKeyHex",
            filter = NostrFilter(kinds = listOf(NostrKind.ENCRYPTED_DM), pTags = listOf(publicKeyHex)),
        )
        LocalPoolContent(pool = pool, ephemeralPrivateKeyHex = privateKey.toHex())
    }

    /** Registers [utxo] (which must exactly match the pool's denomination) into [pool]. */
    suspend fun registerInput(
        network: Network,
        pool: PoolContent,
        utxo: WalletUtxo,
        prevTxHex: String,
    ): Result<LocalPoolContent> = runSuspendCatching {
        require(utxo.amountSats == pool.denominationSats) {
            "Selected UTXO (${utxo.amountSats} sats) must exactly match the pool denomination (${pool.denominationSats} sats)"
        }
        nostrClient.connect(listOf(pool.relay))
        val privateKey = NostrCrypto.generatePrivateKey()
        val publicKeyHex = NostrCrypto.xOnlyPublicKey(privateKey).toHex()
        val outputAddress = walletManager.getNewReceiveAddress(network).getOrThrow()
        val outputScriptPubKeyHex = scriptPubKeyHexOf(outputAddress)

        val registration = RegisteredInput(
            peerPublicKey = publicKeyHex,
            utxo = utxo,
            prevTxHex = prevTxHex,
            outputScriptPubKeyHex = outputScriptPubKeyHex,
            outputAmountSats = pool.denominationSats,
        )
        sendEncryptedDm(privateKey, toPubKeyHex = pool.publicKey, payload = registration.toJson())

        nostrClient.subscribe(
            subscriptionId = "round-$publicKeyHex",
            filter = NostrFilter(kinds = listOf(NostrKind.ENCRYPTED_DM), pTags = listOf(publicKeyHex)),
        )
        LocalPoolContent(
            pool = pool,
            ephemeralPrivateKeyHex = privateKey.toHex(),
            registeredOutputAddress = outputAddress,
            registeredInputs = listOf(registration),
        )
    }

    /** Called by the pool creator once [pool.peers] registrations have arrived, to fan the agreed output list back out. */
    suspend fun broadcastFinalOutputs(
        pool: PoolContent,
        creatorPrivateKeyHex: String,
        registrations: List<RegisteredInput>,
    ): Result<List<CoinjoinOutput>> = runSuspendCatching {
        check(registrations.size == pool.peers) { "Expected ${pool.peers} registrations, got ${registrations.size}" }
        // Outputs are sorted by script bytes (not registration order) so the final
        // output ordering carries no information about who registered when.
        val outputs = registrations
            .map { CoinjoinOutput(it.outputScriptPubKeyHex, it.outputAmountSats) }
            .sortedBy { it.scriptPubKeyHex }
        val privateKey = TxPrimitives.hexToBytes(creatorPrivateKeyHex)
        val payload = JSONObject().apply {
            put("type", "final_outputs")
            put("pool_id", pool.id)
            put("outputs", org.json.JSONArray(outputs.map { it.scriptPubKeyHex + ":" + it.amountSats }))
        }
        registrations.forEach { registration ->
            sendEncryptedDm(privateKey, toPubKeyHex = registration.peerPublicKey, payload = payload)
        }
        outputs
    }

    /** Called by each peer (including the creator, for its own input) once the final output list is known. */
    suspend fun signAndSubmit(
        network: Network,
        myPrivateKeyHex: String,
        creatorPublicKeyHex: String,
        myRegistration: RegisteredInput,
        allOutputs: List<CoinjoinOutput>,
    ): Result<SignedContribution> = runSuspendCatching {
        val contribution = walletManager.signCoinjoinContribution(
            network = network,
            input = myRegistration.utxo,
            inputPrevTxHex = myRegistration.prevTxHex,
            allOutputs = allOutputs,
        ).getOrThrow()

        val privateKey = TxPrimitives.hexToBytes(myPrivateKeyHex)
        val payload = JSONObject().apply {
            put("type", "signed_input")
            put("txid", contribution.txid)
            put("vout", contribution.vout)
            put("sequence", contribution.sequence)
            put("signature", contribution.witnessSignatureHex)
            put("pubkey", contribution.witnessPubKeyHex)
        }
        sendEncryptedDm(privateKey, toPubKeyHex = creatorPublicKeyHex, payload = payload)
        contribution
    }

    /** Called by whichever peer collects every [SignedContribution], to merge and broadcast the round. Returns the broadcast txid. */
    suspend fun finalizeAndBroadcast(
        contributions: List<SignedContribution>,
        outputs: List<CoinjoinOutput>,
    ): Result<String> {
        val rawTxHex = CoinjoinTxAssembler.assemble(contributions, outputs)
        var result: Result<String> = Result.failure(IllegalStateException("Broadcast never completed"))
        florestaRpc.sendRawTransaction(rawTxHex).collect { rpcResult ->
            result = rpcResult.map { it.result }
        }
        return result
    }

    private suspend fun sendEncryptedDm(fromPrivateKey: ByteArray, toPubKeyHex: String, payload: JSONObject) {
        val shared = NostrCrypto.sharedSecret(fromPrivateKey, toPubKeyHex)
        val ciphertext = NostrCrypto.encryptDirectMessage(payload.toString(), shared)
        val event = buildEvent(
            fromPrivateKey,
            kind = NostrKind.ENCRYPTED_DM,
            tags = listOf(listOf("p", toPubKeyHex)),
            content = ciphertext,
        )
        nostrClient.publish(event).getOrThrow()
    }

    private fun buildEvent(privateKey: ByteArray, kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val pubKeyHex = NostrCrypto.xOnlyPublicKey(privateKey).toHex()
        val createdAt = System.currentTimeMillis() / MILLIS_PER_SECOND
        val tagsJson = NostrEvent.tagsToJson(tags).toString()
        val idHash = NostrCrypto.eventId(pubKeyHex, createdAt, kind, tagsJson, content)
        val signature = NostrCrypto.signEvent(idHash, privateKey)
        return NostrEvent(
            id = idHash.toHex(),
            pubKey = pubKeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = signature.toHex(),
        )
    }

    private fun scriptPubKeyHexOf(address: String): String {
        val program = SegwitAddress.decodeProgram(address) ?: error("Failed to decode wallet address")
        return TxPrimitives.bytesToHex(TxPrimitives.p2wpkhScriptPubKey(program))
    }

    private companion object {
        const val POOL_ID_LENGTH = 16
        const val POOL_HISTORY_LIMIT = 50
        const val MILLIS_PER_SECOND = 1000L
    }
}
