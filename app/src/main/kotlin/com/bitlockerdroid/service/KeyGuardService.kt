package com.bitlockerdroid.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optionally protects a saved volume password with a Keystore-backed key,
 * so credentials at rest are encrypted rather than stored in plaintext.
 *
 * The user password is intentionally NOT stored by default; this is only used
 * when the user opts in to "remember this password" in settings.
 */
object KeyGuardService {

    private const val TAG = "KeyGuard"
    private const val ALIAS = "bitlockerdroid_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun keyStore(): KeyStore {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        return ks
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    /** Encrypts [plain] and returns Base64(iv:ct). */
    fun encrypt(plain: String): String? {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed", e)
            null
        }
    }

    /** Decrypts a Base64(iv:ct) blob. Returns null on failure. */
    fun decrypt(blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            // GCM standard requires 12-byte IV + 16-byte authentication tag = 28 bytes minimum
            if (raw.size < 28) return null
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            null
        }
    }

    fun deleteKey() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "delete key failed", e)
        }
    }
}
