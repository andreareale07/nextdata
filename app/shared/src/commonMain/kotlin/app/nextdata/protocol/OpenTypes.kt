package app.nextdata.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** States of an opened remote file (docs/open-edit.md). */
object DocState {
    const val DOWNLOADING = "downloading"
    const val CLEAN = "clean"
    const val MODIFIED = "modified"
    const val SAVING = "saving"
    const val SAVE_FAILED = "save_failed"
    const val UNCERTAIN = "uncertain"
    const val CONFLICT = "conflict"
    const val ORPHANED = "orphaned"
    const val IMPORTED = "imported"
}

/** How a conflicting save is resolved (`open.save` `resolution`). */
object SaveResolution {
    const val NORMAL = "normal"
    const val OVERWRITE = "overwrite"
    const val COPY = "copy"
}

@Serializable
data class OpenDoc(
    val id: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("remote_ref") val remoteRef: String,
    val name: String,
    val state: String,
    val size: Long = 0,
    @SerialName("local_path") val localPath: String = "",
    @SerialName("needs_attention") val needsAttention: Boolean = false,
    @SerialName("ext_open") val extOpen: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    val snaps: List<String> = emptyList(),
)

data class OpenResult(val doc: OpenDoc, val fromCache: Boolean, val remoteChanged: Boolean, val overQuota: Boolean)

/** Outcome of `open.check`: [changed] = local differs from the saved base; the flags explain why nothing was captured. */
data class CheckResult(
    val doc: OpenDoc,
    val changed: Boolean,
    val copies: List<String> = emptyList(),
    val unstable: Boolean = false,
    val locked: Boolean = false,
    val missing: Boolean = false,
    /** "empty" when the file was truncated to 0 bytes (never treated as a save). */
    val suspicious: String? = null,
)

@Serializable
data class CacheStats(val used: Long = 0, val evictable: Long = 0, val protected: Long = 0, val docs: Int = 0, val limit: Long = 0)

/** One document settled by `open.recover`. */
data class RecoveredDoc(val docId: String, val verdict: String, val state: String)

internal fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
