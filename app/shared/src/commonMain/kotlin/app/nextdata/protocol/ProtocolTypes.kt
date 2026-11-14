package app.nextdata.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Protocol ids as spoken by the engine (docs/protocol.md). */
object ProtocolId {
    const val SFTP = "sftp"
    const val WEBDAV = "webdav"
    const val S3 = "s3"
    const val SMB = "smb"
    const val FAKE = "fake"

    /** Display order of the protocols the UI knows about. */
    val known = listOf(SFTP, WEBDAV, S3, SMB, FAKE)
}

object AuthKind {
    const val PASSWORD = "password"
    const val KEY = "key"
    const val NONE = "none"
}

/** SMB security mode of a profile (docs/adr/0001): strict = signed and encrypted, relaxed = signed only. */
object SmbMode {
    const val STRICT = "strict"
    const val RELAXED = "relaxed"
}

object EntryKind {
    const val FILE = "file"
    const val DIR = "dir"
    const val SYMLINK = "symlink"
    const val OTHER = "other"
}

object Collision {
    const val RENAME = "rename"
    const val SKIP = "skip"
    const val FAIL = "fail"
    val all = listOf(RENAME, SKIP, FAIL)
}

object SortKey {
    const val NAME = "name"
    const val SIZE = "size"
    const val MTIME = "mtime"
    val all = listOf(NAME, SIZE, MTIME)
}

@Serializable
data class S3Config(
    val bucket: String = "",
    val region: String = "",
    @SerialName("access_key") val accessKey: String = "",
    @SerialName("path_style") val pathStyle: Boolean = false,
)

@Serializable
data class Profile(
    val id: String = "",
    val name: String = "",
    val protocol: String = ProtocolId.SFTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val auth: String = AuthKind.PASSWORD,
    @SerialName("base_path") val basePath: String = "",
    val tls: Boolean = false,
    val s3: S3Config? = null,
    @SerialName("has_secret") val hasSecret: Boolean = false,
    val color: String? = null,
    /** SMB share name. */
    val share: String = "",
    @SerialName("smb_mode") val smbMode: String = "",
)

/** Secrets travel to the engine only inside `profiles.save` and are never read back. */
@Serializable
data class ProfileSecrets(
    val password: String? = null,
    @SerialName("private_key") val privateKey: String? = null,
    val passphrase: String? = null,
    @SerialName("secret_key") val secretKey: String? = null,
) {
    val isEmpty: Boolean
        get() = password.isNullOrEmpty() && privateKey.isNullOrEmpty() && passphrase.isNullOrEmpty() && secretKey.isNullOrEmpty()
}

@Serializable
data class Entry(
    val name: String,
    val ref: String,
    val kind: String = EntryKind.FILE,
    val size: Long? = null,
    @SerialName("mtime_ms") val mtimeMs: Long? = null,
    @SerialName("safe_local_name") val safeLocalName: String = name,
) {
    val isDir: Boolean get() = kind == EntryKind.DIR
}

@Serializable
data class Breadcrumb(val name: String, val ref: String)

@Serializable
data class ProtocolInfo(
    val id: String,
    val enabled: Boolean = true,
    @SerialName("reason_code") val reasonCode: String? = null,
)

@Serializable
data class InitResult(
    @SerialName("api_version") val apiVersion: Int = 1,
    val version: String = "",
    val protocols: List<ProtocolInfo> = emptyList(),
)

@Serializable
data class ConnectResult(
    val session: String,
    @SerialName("home_ref") val homeRef: String,
)

@Serializable
data class ListResult(
    val ref: String,
    @SerialName("parent_ref") val parentRef: String? = null,
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val entries: List<Entry> = emptyList(),
    @SerialName("next_token") val nextToken: String? = null,
    @SerialName("total_hint") val totalHint: Int? = null,
)

@Serializable
data class ImportConflict(val id: String, val name: String)

@Serializable
data class ImportResult(
    val profiles: List<Profile> = emptyList(),
    val conflicts: List<ImportConflict> = emptyList(),
    val applied: Boolean = false,
)

@Serializable
data class Favorite(val ref: String, val name: String)

@Serializable
data class ExportOptions(
    val favorites: Boolean = false,
    val settings: Boolean = false,
    val pins: Boolean = false,
)

@Serializable
data class ProgressInfo(
    val bytes: Long = 0,
    val total: Long? = null,
    val item: String? = null,
    @SerialName("items_done") val itemsDone: Int = 0,
    @SerialName("items_total") val itemsTotal: Int = 0,
) {
    /** 0f..1f or null when the total is unknown. */
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

/** Error as emitted by the engine: only `code` + `params` are shown (localized), never free text. */
data class ErrInfo(
    val code: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val retryable: Boolean = false,
) {
    fun param(key: String): String? = (params[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        fun of(code: String, vararg params: Pair<String, String>) =
            ErrInfo(code, JsonObject(params.associate { it.first to (JsonPrimitive(it.second) as JsonElement) }))
    }
}

/** Prompts requested by the engine during a command (trust, secrets). */
sealed interface Prompt {
    val promptId: String

    data class TrustHost(
        override val promptId: String,
        val host: String,
        val port: Int,
        val algo: String,
        val fingerprint: String,
        /** non-null means the host key CHANGED. */
        val prior: String?,
    ) : Prompt

    data class TrustCert(
        override val promptId: String,
        val host: String,
        val port: Int,
        val spkiSha256: String,
        val subject: String,
        val selfSigned: Boolean,
        val reason: String,
    ) : Prompt

    data class Secret(
        override val promptId: String,
        /** "password" or "passphrase". */
        val kind: String,
        val profileName: String,
    ) : Prompt

    data class Unknown(override val promptId: String, val kind: String) : Prompt
}

object PromptKind {
    const val TRUST_HOST = "trust_host"
    const val TRUST_CERT = "trust_cert"
    const val PASSWORD = "password"
    const val PASSPHRASE = "passphrase"
}
