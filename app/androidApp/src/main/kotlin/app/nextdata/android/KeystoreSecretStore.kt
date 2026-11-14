package app.nextdata.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.nextdata.platform.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Master key (PMK) protection: a non-exportable AES-256-GCM key in the Android Keystore wraps the
 * 32-byte PMK, which is kept in a private file (`iv || ciphertext`). If the file exists but cannot be
 * unwrapped it is never overwritten (that would orphan the engine's encrypted data).
 */
class KeystoreSecretStore(private val file: File, private val alias: String = "nextdata_pmk_wrap_v1") : SecretStore {
    private val lock = Mutex()

    override suspend fun loadOrCreatePmk(): ByteArray = lock.withLock {
        withContext(Dispatchers.IO) {
            val key = wrappingKey()
            if (file.isFile) {
                val raw = file.readBytes()
                require(raw.size > IV_LEN + 16) { "corrupt key file" }
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, IV_LEN))
                val pmk = c.doFinal(raw, IV_LEN, raw.size - IV_LEN)
                check(pmk.size == 32) { "unexpected key size" }
                pmk
            } else {
                val pmk = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.ENCRYPT_MODE, key)
                val ct = c.doFinal(pmk)
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(c.iv + ct)
                check(tmp.renameTo(file)) { "cannot persist key file" }
                pmk
            }
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
