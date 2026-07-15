package com.github.jvsena42.mandacaru.domain.coinjoin

import com.github.jvsena42.mandacaru.domain.wallet.CoinjoinOutput
import com.github.jvsena42.mandacaru.domain.wallet.SignedContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinjoinTxAssemblerTest {

    @Test
    fun `assembles a segwit tx with marker, flag, and one witness stack per input`() {
        val contribution = SignedContribution(
            txid = "a".repeat(64),
            vout = 0,
            sequence = 0xfffffffdL,
            witnessSignatureHex = "30".padEnd(142, '0'),
            witnessPubKeyHex = "02".padEnd(66, '1'),
        )
        val output = CoinjoinOutput(scriptPubKeyHex = "0014" + "b".repeat(40), amountSats = 100_000L)

        val rawHex = CoinjoinTxAssembler.assemble(listOf(contribution), listOf(output))

        // version(4) + marker(1) + flag(1)
        assertEquals("02000000" + "00" + "01", rawHex.substring(0, 12))
        assertTrue(rawHex.contains(output.scriptPubKeyHex))
        assertTrue(rawHex.endsWith("00000000")) // nLockTime = 0
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty contribution list`() {
        CoinjoinTxAssembler.assemble(emptyList(), listOf(CoinjoinOutput("00", 1)))
    }
}
