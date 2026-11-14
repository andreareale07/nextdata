package app.nextdata.protocol

import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Events engine -> UI, one JSON line each. */
sealed interface EngineEvent {
    val op: Long

    data class Ok(override val op: Long, val data: JsonObject) : EngineEvent
    data class Err(override val op: Long, val error: ErrInfo) : EngineEvent
    data class Cancelled(override val op: Long) : EngineEvent
    data class Progress(override val op: Long, val progress: ProgressInfo) : EngineEvent
    data class PromptRequest(override val op: Long, val prompt: Prompt) : EngineEvent

    val isTerminal: Boolean get() = this is Ok || this is Err || this is Cancelled
}

/** JSON codec for the whole bridge protocol (docs/protocol.md). Pure, no I/O. */
object Codec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeCommand(op: Long, cmd: String, args: JsonObject = JsonObject(emptyMap())): String {
        require(op > 0) { "op must be > 0" }
        val obj = buildJsonObject {
            put("op", op)
            put("cmd", cmd)
            put("args", args)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** Returns null for lines that are not valid protocol events (they are ignored by the engine layer). */
    fun decodeEvent(line: String): EngineEvent? {
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (_: Exception) {
            return null
        }
        val op = (obj["op"] as? JsonPrimitive)?.longOrNull ?: return null
        val ev = (obj["ev"] as? JsonPrimitive)?.contentOrNull ?: return null
        return try {
            when (ev) {
                "ok" -> EngineEvent.Ok(op, obj["data"] as? JsonObject ?: JsonObject(emptyMap()))
                "err" -> EngineEvent.Err(op, decodeErr(obj["err"] as? JsonObject))
                "cancelled" -> EngineEvent.Cancelled(op)
                "progress" -> EngineEvent.Progress(
                    op,
                    json.decodeFromJsonElement(ProgressInfo.serializer(), obj["data"] ?: JsonObject(emptyMap())),
                )
                "prompt" -> EngineEvent.PromptRequest(op, decodePrompt(obj["data"] as? JsonObject ?: return null))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeErr(obj: JsonObject?): ErrInfo {
        if (obj == null) return ErrInfo(ErrorCodes.INTERNAL)
        val code = (obj["code"] as? JsonPrimitive)?.contentOrNull ?: ErrorCodes.INTERNAL
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        val retryable = (obj["retryable"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ErrInfo(code, params, retryable)
    }

    fun decodePrompt(d: JsonObject): Prompt {
        val id = (d["prompt_id"] as? JsonPrimitive)?.contentOrNull ?: ""
        fun s(k: String) = (d[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: ""
        return when (val kind = s("kind")) {
            PromptKind.TRUST_HOST -> Prompt.TrustHost(
                promptId = id,
                host = s("host"),
                port = (d["port"] as? JsonPrimitive)?.intOrNull ?: 0,
                algo = s("algo"),
                fingerprint = s("fingerprint"),
                prior = (d["prior"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
            )
            PromptKind.TRUST_CERT -> Prompt.TrustCert(
                promptId = id,
                host = s("host"),
                port = (d["port"] as? JsonPrimitive)?.intOrNull ?: 0,
                spkiSha256 = s("spki_sha256"),
                subject = s("subject"),
                selfSigned = (d["self_signed"] as? JsonPrimitive)?.booleanOrNull ?: false,
                reason = s("reason"),
            )
            PromptKind.PASSWORD, PromptKind.PASSPHRASE -> Prompt.Secret(id, kind, s("profile_name"))
            else -> Prompt.Unknown(id, kind)
        }
    }

    inline fun <reified T> decode(data: JsonElement): T = json.decodeFromJsonElement(data)

    inline fun <reified T> encodeToElement(value: T): JsonElement = json.encodeToJsonElement(value)

    fun promptReplyArgs(promptId: String, accept: Boolean, value: String? = null, replace: Boolean = false): JsonObject =
        buildJsonObject {
            put("prompt_id", promptId)
            put("accept", accept)
            if (value != null) put("value", value)
            if (replace) put("replace", true)
        }
}
