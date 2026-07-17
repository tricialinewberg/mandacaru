package com.github.jvsena42.mandacaru.fakes

import com.github.jvsena42.mandacaru.data.wallet.WalletKeyStore

/**
 * In-memory fake of [WalletKeyStore]. The real implementation is backed by Android's
 * [androidx.security.crypto.EncryptedSharedPreferences] (Keystore-derived AES encryption), which
 * needs a real or Robolectric-simulated Android [android.content.Context] to construct - out of
 * reach for a plain JVM unit test. This fake lets [com.github.jvsena42.mandacaru.data.wallet.WalletManagerImpl]'s
 * own logic (derivation, signing, index bookkeeping) be tested without exercising that encryption
 * layer itself.
 */
class FakeWalletKeyStore : WalletKeyStore {
    private var mnemonic: String? = null
    private var nextExternalIndex: Int = 0

    override fun hasMnemonic(): Boolean = mnemonic != null

    override fun getMnemonic(): String? = mnemonic

    override fun saveMnemonic(mnemonic: String) {
        this.mnemonic = mnemonic
    }

    override fun clear() {
        mnemonic = null
        nextExternalIndex = 0
    }

    override fun getNextExternalIndex(): Int = nextExternalIndex

    override fun setNextExternalIndex(index: Int) {
        nextExternalIndex = index
    }
}
