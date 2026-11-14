package app.nextdata.engine

import app.nextdata.protocol.Codec
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
import app.nextdata.protocol.ProgressInfo
import app.nextdata.protocol.Prompt
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
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

    private fun favoritesResult(data: JsonObject): List<Favorite> =
        Codec.json.decodeFromJsonElement(ListSerializer(Favorite.serializer()), data["favorites"] ?: JsonArray(emptyList()))

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
