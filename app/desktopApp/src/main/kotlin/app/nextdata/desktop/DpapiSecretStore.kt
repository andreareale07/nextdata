package app.nextdata.desktop

import app.nextdata.platform.SecretStore
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** Wraps/unwraps bytes with an OS facility. */
interface Protector {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(blob: ByteArray): ByteArray
}

/** Windows DPAPI (CryptProtectData, current-user scope) through JNA: no child process, no secret on a command line. */
object WindowsDpapi : Protector {
    private val entropy = "NextData.PMK.v1".encodeToByteArray()
    override fun protect(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain, entropy, 0, "NextData PMK", null)
    override fun unprotect(blob: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(blob, entropy, 0, null)
}

class PmkUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Keeps the 32-byte profile master key in [file] (under %LOCALAPPDATA%\NextData, never Roaming),
 * protected by [protector]. An existing but unreadable file is never overwritten: that would
 * silently orphan every secret the engine encrypted with the old key.
 */
class DpapiSecretStore(private val file: File, private val protector: Protector = WindowsDpapi) : SecretStore {
    private val lock = Mutex()

    override suspend fun loadOrCreatePmk(): ByteArray = lock.withLock {
        withContext(Dispatchers.IO) {
            if (file.isFile) {
                val pmk = try {
                    protector.unprotect(file.readBytes())
                } catch (e: Exception) {
                    throw PmkUnavailableException("cannot unprotect ${file.name}", e)
                }
                if (pmk.size != 32) throw PmkUnavailableException("unexpected key size")
                pmk
            } else {
                val pmk = ByteArray(32).also { SecureRandom().nextBytes(it) }
                file.parentFile.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(protector.protect(pmk))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                pmk
            }
        }
    }
}
