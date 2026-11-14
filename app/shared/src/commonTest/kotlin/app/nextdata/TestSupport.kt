package app.nextdata

import app.nextdata.engine.NativeTransport
import app.nextdata.platform.AppEnvironment
import app.nextdata.platform.DownloadTarget
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.PlatformKind
import app.nextdata.platform.SecretProtection
import app.nextdata.platform.SecretStore
import app.nextdata.platform.UploadSelection

/** Transport double: records what the engine layer sends and lets tests inject event lines. */
class FakeTransport : NativeTransport {
    val submitted = mutableListOf<String>()
    val cancelled = mutableListOf<Long>()
    private var listener: ((String) -> Unit)? = null

    override fun submit(commandJson: String) {
        submitted += commandJson
    }

    override fun setListener(listener: ((String) -> Unit)?) {
        this.listener = listener
    }

    override fun cancel(op: Long) {
        cancelled += op
    }

    /** Simulates an event line arriving from the engine thread. */
    fun emit(line: String) {
        listener?.invoke(line)
    }
}

class TestSecretStore : SecretStore {
    override suspend fun loadOrCreatePmk(): ByteArray = ByteArray(32) { it.toByte() }
}

class TestFiles : PlatformFiles {
    var downloadTarget: DownloadTarget? = DownloadTarget("C:/dl", "Downloads")
    var uploadFiles: UploadSelection? = null
    var saved: Pair<String, ByteArray>? = null
    var pickedBytes: ByteArray? = null

    override suspend fun pickUploadFiles(onPicked: () -> Unit) = uploadFiles
    override suspend fun pickUploadFolder(onPicked: () -> Unit) = uploadFiles
    override suspend fun chooseDownloadTarget(askEachTime: Boolean) = downloadTarget
    override suspend fun saveBytes(suggestedName: String, bytes: ByteArray): Boolean {
        saved = suggestedName to bytes
        return true
    }

    override suspend fun pickBytes(extensions: List<String>) = pickedBytes
}

fun testEnv(files: TestFiles = TestFiles()) =
    AppEnvironment("C:/data", TestSecretStore(), files, PlatformKind.Desktop, SecretProtection.Other)
