package com.kkgosu.learnharness.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.kkgosu.learnharness.api.ProviderFailure
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Call on an IO dispatcher. Only ciphertext lives on disk; the AES key never leaves Keystore. */
class CredentialStore(context: Context, name: String = "openai", private val maxBytes: Int = 512) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.credential"))
    private val alias = "reasoned.$name.v1"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun save(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size in 16..maxBytes)
        val key = keyStore().getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            "AES",
            "AndroidKeyStore"
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build()
            )
            generateKey()
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(encrypted); file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream); throw ProviderFailure("credential_storage")
        }
    }

    @Synchronized
    fun read(): String? {
        if (!file.baseFile.exists()) return null
        return try {
            require(file.baseFile.length() in 28..(maxBytes + 28).toLong())
            val data = file.readFully()
            require(data.size in 28..maxBytes + 28)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: throw IllegalStateException()
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
                String(doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            throw ProviderFailure("credential_unavailable")
        }
    }

    @Synchronized
    fun forget() {
        file.delete()
        keyStore().deleteEntry(alias)
    }

    fun hasSavedKey() = file.baseFile.exists()
}
