package com.kzkt.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kzkt.app.util.KLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption backed by the Android Keystore. Used to encrypt API keys
 * at rest so they never sit in plaintext in the DataStore (which would otherwise
 * be carried into cloud backups, since allowBackup is on). The Keystore key is
 * hardware-backed where available and cannot be extracted by other apps.
 *
 * Values are stored as "kzkt_v1:<base64(iv|ciphertext)>". Anything without the
 * prefix is treated as legacy plaintext and returned unchanged, so old installs
 * keep working and are re-encrypted on the next save ([migrateLegacyApiKeys]).
 */
object KeyCipher {
    const val PREFIX = "kzkt_v1:"

    private const val KEYSTORE_ALIAS = "kzkt_api_key_cipher"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_LENGTH = 12

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Encrypt [plain]; blank input stays blank. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + encrypted
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Keystore unavailable (unlikely on API 26+): fall back to plaintext so
            // keys are never lost — at-rest protection degrades gracefully.
            KLog.w("KZKT", "KeyCipher: encrypt failed — storing API key in plaintext: ${e.message}")
            plain
        }
    }

    /**
     * Decrypt a value produced by [encrypt]. Legacy plaintext (no prefix) and
     * undecryptable values (e.g. ciphertext from another device's backup) are
     * returned unchanged instead of crashing the settings flow.
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        return try {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            KLog.w("KZKT", "KeyCipher: decrypt failed — returning value as-is (may be an API key that no longer works): ${e.message}")
            stored
        }
    }
}
