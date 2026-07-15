package com.github.jvsena42.mandacaru.data.wallet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the wallet's BIP39 mnemonic. Backed by Android Keystore-derived AES
 * keys (via [EncryptedSharedPreferences]), kept fully separate from the
 * plain-text DataStore used for regular preferences - this is the one place
 * in the app that holds key material.
 */
interface WalletKeyStore {
    fun hasMnemonic(): Boolean
    fun getMnemonic(): String?
    fun saveMnemonic(mnemonic: String)
    fun clear()

    /** Index of the next unused external (receive) address, for gap-free address display. */
    fun getNextExternalIndex(): Int
    fun setNextExternalIndex(index: Int)
}

class WalletKeyStoreImpl(context: Context) : WalletKeyStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun hasMnemonic(): Boolean = prefs.contains(KEY_MNEMONIC)

    override fun getMnemonic(): String? = prefs.getString(KEY_MNEMONIC, null)

    override fun saveMnemonic(mnemonic: String) {
        prefs.edit().putString(KEY_MNEMONIC, mnemonic).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_MNEMONIC).remove(KEY_NEXT_EXTERNAL_INDEX).apply()
    }

    override fun getNextExternalIndex(): Int = prefs.getInt(KEY_NEXT_EXTERNAL_INDEX, 0)

    override fun setNextExternalIndex(index: Int) {
        prefs.edit().putInt(KEY_NEXT_EXTERNAL_INDEX, index).apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "wallet_secure_prefs"
        const val KEY_MNEMONIC = "mnemonic"
        const val KEY_NEXT_EXTERNAL_INDEX = "next_external_index"
    }
}
