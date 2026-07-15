package com.github.jvsena42.mandacaru.domain.coinjoin

import com.github.jvsena42.mandacaru.domain.bitcoin.TxPrimitives
import com.github.jvsena42.mandacaru.domain.wallet.CoinjoinOutput
import com.github.jvsena42.mandacaru.domain.wallet.SignedContribution
import java.io.ByteArrayOutputStream

/**
 * Merges every peer's independently-signed [SignedContribution] plus the
 * round's agreed [CoinjoinOutput] list into one final, broadcastable segwit
 * transaction (BIP144). This is the "any peer can merge and finalize" step
 * of the coordinator-less protocol - there is no coordinator server, so
 * whichever peer collects all N contributions can produce and broadcast the
 * same transaction; the SIGHASH_ALL|ANYONECANPAY signatures each peer
 * produced remain valid regardless of input order or which peer merges.
 */
object CoinjoinTxAssembler {

    fun assemble(
        contributions: List<SignedContribution>,
        outputs: List<CoinjoinOutput>,
        lockTime: Long = 0,
    ): String {
        require(contributions.isNotEmpty()) { "A coinjoin round needs at least one input" }
        require(outputs.isNotEmpty()) { "A coinjoin round needs at least one output" }

        val tx = ByteArrayOutputStream()
        tx.write(TxPrimitives.le(TxPrimitives.TX_VERSION, 4)) // nVersion
        tx.write(SEGWIT_MARKER)
        tx.write(SEGWIT_FLAG)

        TxPrimitives.writeVarInt(contributions.size.toLong(), tx)
        contributions.forEach { c ->
            tx.write(TxPrimitives.reversed(TxPrimitives.hexToBytes(c.txid)))
            tx.write(TxPrimitives.le(c.vout.toLong(), 4))
            TxPrimitives.writeVarInt(0, tx) // empty scriptSig - segwit input
            tx.write(TxPrimitives.le(c.sequence, 4))
        }

        TxPrimitives.writeVarInt(outputs.size.toLong(), tx)
        outputs.forEach { o ->
            TxPrimitives.serializeOutput(TxPrimitives.hexToBytes(o.scriptPubKeyHex), o.amountSats, tx)
        }

        contributions.forEach { c ->
            val signature = TxPrimitives.hexToBytes(c.witnessSignatureHex)
            val pubKey = TxPrimitives.hexToBytes(c.witnessPubKeyHex)
            TxPrimitives.writeVarInt(2, tx) // witness stack: [signature, pubkey]
            TxPrimitives.writeVarInt(signature.size.toLong(), tx)
            tx.write(signature)
            TxPrimitives.writeVarInt(pubKey.size.toLong(), tx)
            tx.write(pubKey)
        }

        tx.write(TxPrimitives.le(lockTime, 4))
        return TxPrimitives.bytesToHex(tx.toByteArray())
    }

    private val SEGWIT_MARKER = byteArrayOf(0x00)
    private val SEGWIT_FLAG = byteArrayOf(0x01)
}
