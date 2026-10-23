package dev.otmgram.core.td

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The key TDLib encrypts its database and login with: 32 random bytes, stored encrypted by
 * a key in the Android Keystore, which never leaves the device. A copy of the app's files
 * is useless anywhere else.
 */
@Singleton
class DatabaseKey @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(context.noBackupFilesDir, "database.key")

    /** The stored key, or null if there is none yet (or it can't be read any more). */
    fun load(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val stored = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES))
            cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    /** Makes and stores a new random key. */
    fun create(): ByteArray {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        file.writeBytes(cipher.iv + cipher.doFinal(key))
        return key
    }

    fun delete() {
        file.delete()
    }

    private fun keystoreKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "otmgram_database"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
    }
}
