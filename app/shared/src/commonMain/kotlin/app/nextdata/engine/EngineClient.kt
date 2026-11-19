package app.nextdata.engine

import app.nextdata.protocol.CacheStats
import app.nextdata.protocol.CheckResult
import app.nextdata.protocol.Codec
import app.nextdata.protocol.OpenDoc
import app.nextdata.protocol.OpenResult
import app.nextdata.protocol.RecoveredDoc
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import app.nextdata.protocol.EngineEvent
import app.nextdata.protocol.ConnectResult
import app.nextdata.protocol.Entry
import app.nextdata.protocol.ExportOptions
import app.nextdata.protocol.Favorite
import app.nextdata.protocol.ImportResult
import app.nextdata.protocol.InitResult
import app.nextdata.protocol.ListResult
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.FolderStats
import app.nextdata.protocol.ProgressInfo
import app.nextdata.protocol.SpaceInfo
import app.nextdata.protocol.Prompt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Typed facade over [Engine]: one method per protocol command. Contains no protocol logic beyond
 * argument/result (de)serialization; prompts raised during any command go to [promptHandler].
 */
class EngineClient(val engine: Engine) {
    /** Called (on the collecting coroutine) for every `prompt` event of any command. */
    var promptHandler: ((Prompt) -> Unit)? = null

    private suspend fun call(
        cmd: String,
        args: JsonObject = JsonObject(emptyMap()),
        onProgress: ((ProgressInfo) -> Unit)? = null,
    ): JsonObject = engine.request(cmd, args) { ev ->
        when (ev) {
            is EngineEvent.PromptRequest -> promptHandler?.invoke(ev.prompt)
            is EngineEvent.Progress -> onProgress?.invoke(ev.progress)
            else -> Unit
        }
    }

    suspend fun init(dataDir: String, pmkB64: String): InitResult = Codec.decode(
        call("init", buildJsonObject {
            put("data_dir", dataDir)
            put("pmk_b64", pmkB64)
        }),
    )

    suspend fun profilesList(): List<Profile> =
        Codec.json.decodeFromJsonElement(ListSerializer(Profile.serializer()), call("profiles.list")["profiles"] ?: JsonArray(emptyList()))

    suspend fun profilesSave(profile: Profile, secrets: ProfileSecrets?): Profile {
        val args = buildJsonObject {
            put("profile", Codec.json.encodeToJsonElement(Profile.serializer(), profile))
            if (secrets != null && !secrets.isEmpty) put("secrets", Codec.json.encodeToJsonElement(ProfileSecrets.serializer(), secrets))
        }
        return Codec.json.decodeFromJsonElement(Profile.serializer(), call("profiles.save", args)["profile"]!!)
    }

    suspend fun profilesDelete(id: String) {
        call("profiles.delete", buildJsonObject { put("id", id) })
    }

    suspend fun profilesExport(passphrase: String, options: ExportOptions): String = call(
        "profiles.export",
        buildJsonObject {
            put("passphrase", passphrase)
            put("options", Codec.json.encodeToJsonElement(ExportOptions.serializer(), options))
        },
    )["data_b64"]!!.jsonPrimitive.content

    suspend fun profilesImport(dataB64: String, passphrase: String, apply: Boolean): ImportResult = Codec.decode(
        call("profiles.import", buildJsonObject {
            put("data_b64", dataB64)
            put("passphrase", passphrase)
            put("apply", apply)
        }),
    )

    suspend fun connect(profileId: String): ConnectResult =
        Codec.decode(call("session.connect", buildJsonObject { put("profile_id", profileId) }))

    suspend fun list(
        session: String,
        ref: String,
        pageToken: String? = null,
        limit: Int? = null,
        sort: String? = null,
        desc: Boolean? = null,
    ): ListResult = Codec.decode(
        call("session.list", buildJsonObject {
            put("session", session)
            put("ref", ref)
            if (pageToken != null) put("page_token", pageToken)
            if (limit != null) put("limit", limit)
            if (sort != null) put("sort", sort)
            if (desc != null) put("desc", desc)
        }),
    )

    suspend fun mkdir(session: String, parentRef: String, name: String): Entry = Codec.json.decodeFromJsonElement(
        Entry.serializer(),
        call("session.mkdir", buildJsonObject {
            put("session", session)
            put("parent_ref", parentRef)
            put("name", name)
        })["entry"]!!,
    )

    suspend fun rename(session: String, ref: String, newName: String): Entry = Codec.json.decodeFromJsonElement(
        Entry.serializer(),
        call("session.rename", buildJsonObject {
            put("session", session)
            put("ref", ref)
            put("new_name", newName)
        })["entry"]!!,
    )

    /** Moves [refs] into the folder [destRef] (never replaces; the engine refuses a folder into itself). Returns how many moved. */
    suspend fun move(session: String, refs: List<String>, destRef: String): Int =
        call("session.move", buildJsonObject {
            put("session", session)
            put("refs", kotlinx.serialization.json.JsonArray(refs.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("dest_ref", destRef)
        })["moved"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() } ?: 0

    /** Copies [refs] (files and folders) into the folder [destRef] of the same session; nothing is replaced. Returns the copies made. */
    suspend fun copy(
        session: String,
        refs: List<String>,
        destRef: String,
        collision: String,
        onProgress: ((ProgressInfo) -> Unit)? = null,
    ): List<Entry> = Codec.json.decodeFromJsonElement(
        ListSerializer(Entry.serializer()),
        call(
            "session.copy",
            buildJsonObject {
                put("session", session)
                put("refs", stringArray(refs))
                put("dest_ref", destRef)
                put("collision", collision)
            },
            onProgress,
        )["entries"] ?: JsonArray(emptyList()),
    )

    /** Files, folders and bytes under [refs], counted recursively by the engine (cancel = cancel the calling coroutine). */
    suspend fun stats(session: String, refs: List<String>, onProgress: ((ProgressInfo) -> Unit)? = null): FolderStats = Codec.decode(
        call(
            "session.stats",
            buildJsonObject {
                put("session", session)
                put("refs", stringArray(refs))
            },
            onProgress,
        ),
    )

    /** Capacity of the volume behind the session as the server declares it; null when it does not (S3, many servers). */
    suspend fun space(session: String): SpaceInfo? {
        val s = call("session.space", buildJsonObject { put("session", session) })["space"]
        return if (s == null || s is JsonNull) null else Codec.json.decodeFromJsonElement(SpaceInfo.serializer(), s)
    }

    suspend fun delete(session: String, refs: List<String>, recursive: Boolean, onProgress: ((ProgressInfo) -> Unit)? = null): Int =
        call(
            "session.delete",
            buildJsonObject {
                put("session", session)
                put("refs", stringArray(refs))
                put("recursive", recursive)
            },
            onProgress,
        )["deleted"]?.jsonPrimitive?.int ?: 0

    suspend fun closeSession(session: String) {
        call("session.close", buildJsonObject { put("session", session) })
    }

    suspend fun download(
        session: String,
        refs: List<String>,
        destDir: String,
        collision: String,
        onProgress: ((ProgressInfo) -> Unit)? = null,
    ): List<String> = call(
        "transfer.download",
        buildJsonObject {
            put("session", session)
            put("refs", stringArray(refs))
            put("dest_dir", destDir)
            put("collision", collision)
        },
        onProgress,
    )["local_paths"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    suspend fun upload(
        session: String,
        localPaths: List<String>,
        parentRef: String,
        collision: String,
        onProgress: ((ProgressInfo) -> Unit)? = null,
    ): List<Entry> = Codec.json.decodeFromJsonElement(
        ListSerializer(Entry.serializer()),
        call(
            "transfer.upload",
            buildJsonObject {
                put("session", session)
                put("local_paths", stringArray(localPaths))
                put("parent_ref", parentRef)
                put("collision", collision)
            },
            onProgress,
        )["entries"] ?: JsonArray(emptyList()),
    )

    suspend fun promptReply(promptId: String, accept: Boolean, value: String? = null, replace: Boolean = false) {
        call("prompt.reply", Codec.promptReplyArgs(promptId, accept, value, replace))
    }

    suspend fun settingsGet(): JsonObject = call("settings.get")["settings"] as? JsonObject ?: JsonObject(emptyMap())

    suspend fun settingsSet(settings: JsonObject): JsonObject =
        call("settings.set", buildJsonObject { put("settings", settings) })["settings"] as? JsonObject ?: settings

    suspend fun favoritesList(profileId: String): List<Favorite> = favoritesResult(
        call("favorites.list", buildJsonObject { put("profile_id", profileId) }),
    )

    suspend fun favoritesAdd(profileId: String, ref: String, name: String): List<Favorite> = favoritesResult(
        call("favorites.add", buildJsonObject {
            put("profile_id", profileId)
            put("ref", ref)
            put("name", name)
        }),
    )

    suspend fun favoritesRemove(profileId: String, ref: String): List<Favorite> = favoritesResult(
        call("favorites.remove", buildJsonObject {
            put("profile_id", profileId)
            put("ref", ref)
        }),
    )

    // ------------------------------------------------------------------ open / edit (docs/open-edit.md)

    private fun doc(o: JsonObject): OpenDoc = Codec.json.decodeFromJsonElement(OpenDoc.serializer(), o)
    private fun JsonObject.flag(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false

    /** A pinned random-access reader of a remote file (docs/protocol.md `read.*`). */
    class ReadHandle(val handle: String, val size: Long?)

    suspend fun readOpen(session: String, ref: String): ReadHandle {
        val r = call("read.open", buildJsonObject { put("session", session); put("ref", ref) })
        return ReadHandle(r["handle"]!!.jsonPrimitive.content, (r["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull())
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun readRange(handle: String, offset: Long, length: Int): ByteArray {
        val r = call("read.range", buildJsonObject { put("handle", handle); put("offset", offset); put("length", length) })
        return kotlin.io.encoding.Base64.decode(r["data_b64"]!!.jsonPrimitive.content)
    }

    suspend fun readClose(handle: String) {
        call("read.close", buildJsonObject { put("handle", handle) })
    }

    suspend fun openPrepare(session: String, ref: String, cacheLimit: Long? = null, onProgress: ((ProgressInfo) -> Unit)? = null): OpenResult {
        val r = call(
            "open.prepare",
            buildJsonObject {
                put("session", session)
                put("ref", ref)
                if (cacheLimit != null) put("cache_limit", cacheLimit)
            },
            onProgress,
        )
        return OpenResult(doc(r["doc"]!!.jsonObject), r.flag("from_cache"), r.flag("remote_changed"), r.flag("over_quota"))
    }

    suspend fun openCheck(docId: String, stableMs: Long = 1000, acceptEmpty: Boolean = false): CheckResult {
        val r = call("open.check", buildJsonObject {
            put("doc_id", docId)
            put("stable_ms", stableMs)
            put("accept_empty", acceptEmpty)
        })
        return CheckResult(
            doc(r["doc"]!!.jsonObject), r.flag("changed"),
            (r["copies"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            r.flag("unstable"), r.flag("locked"), r.flag("missing"), (r["suspicious"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    suspend fun openSave(
        session: String,
        docId: String,
        resolution: String = "normal",
        confirmUnverified: Boolean = false,
        recoveryDir: String? = null,
        onProgress: ((ProgressInfo) -> Unit)? = null,
    ): OpenDoc = doc(
        call(
            "open.save",
            buildJsonObject {
                put("session", session)
                put("doc_id", docId)
                put("resolution", resolution)
                if (confirmUnverified) put("confirm_unverified", true)
                if (recoveryDir != null) put("recovery_dir", recoveryDir)
            },
            onProgress,
        )["doc"]!!.jsonObject,
    )

    suspend fun openImport(docId: String, localPath: String): OpenDoc =
        doc(call("open.import", buildJsonObject { put("doc_id", docId); put("local_path", localPath) })["doc"]!!.jsonObject)

    suspend fun openRecover(session: String): List<RecoveredDoc> =
        (call("open.recover", buildJsonObject { put("session", session) })["docs"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            RecoveredDoc(o["doc_id"]!!.jsonPrimitive.content, o["verdict"]!!.jsonPrimitive.content, o["state"]!!.jsonPrimitive.content)
        }

    suspend fun openList(profileId: String? = null): List<OpenDoc> =
        (call("open.list", buildJsonObject { if (profileId != null) put("profile_id", profileId) })["docs"] as? JsonArray).orEmpty().map { doc(it.jsonObject) }

    suspend fun openMarkOpen(docId: String): OpenDoc = doc(call("open.mark_open", buildJsonObject { put("doc_id", docId) })["doc"]!!.jsonObject)

    suspend fun openRelease(docId: String): OpenDoc = doc(call("open.release", buildJsonObject { put("doc_id", docId) })["doc"]!!.jsonObject)

    suspend fun openExport(docId: String, destDir: String): List<String> =
        (call("open.export", buildJsonObject { put("doc_id", docId); put("dest_dir", destDir) })["exported"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

    suspend fun openDiscard(docId: String) {
        call("open.discard", buildJsonObject { put("doc_id", docId); put("confirm", true) })
    }

    suspend fun cacheStats(): CacheStats = Codec.json.decodeFromJsonElement(CacheStats.serializer(), call("cache.stats"))

    suspend fun cachePurge(): Long = (call("cache.purge")["freed"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    private fun favoritesResult(data: JsonObject): List<Favorite> =
        Codec.json.decodeFromJsonElement(ListSerializer(Favorite.serializer()), data["favorites"] ?: JsonArray(emptyList()))

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
