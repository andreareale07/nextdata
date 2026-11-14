package app.nextdata.state

import app.nextdata.engine.EngineCancelledException
import app.nextdata.engine.EngineClient
import app.nextdata.engine.EngineException
import app.nextdata.protocol.Collision
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.Prompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** Result of an engine call, as consumed by the view models. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: ErrInfo, val cause: Throwable? = null) : Outcome<Nothing>
}

/** The connection itself is gone (not just this request): the UI offers "Reconnect" instead of a plain retry. */
fun ErrInfo.isConnectionLost(): Boolean =
    code == ErrorCodes.SESSION_CLOSED || code == ErrorCodes.NETWORK || code == ErrorCodes.TIMEOUT ||
        (code == ErrorCodes.IO && param("detail")?.contains("closed", ignoreCase = true) == true)

val <T> Outcome<T>.errorOrNull: ErrInfo? get() = (this as? Outcome.Failure)?.error

fun Throwable.toErrInfo(): ErrInfo = when (this) {
    is EngineException -> error
    is EngineCancelledException -> ErrInfo(ErrorCodes.INTERNAL)
    else -> ErrInfo(ErrorCodes.INTERNAL)
}

/** Runs [block], mapping engine failures to [Outcome.Failure]; coroutine cancellation is never swallowed. */
suspend inline fun <T> outcome(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Outcome.Failure(e.toErrInfo(), e)
}

/** List density: comfortable (touch) or compact (pointer). `null` in settings = platform default. */
enum class Density(val id: String) {
    Comfortable("comfortable"), Compact("compact");

    companion object {
        fun fromId(id: String?): Density? = entries.firstOrNull { it.id == id }
    }
}

/** Colour theme: follow the system or force one. */
enum class ThemeChoice(val id: String) {
    System("system"), Light("light"), Dark("dark");

    companion object {
        fun fromId(id: String?): ThemeChoice = entries.firstOrNull { it.id == id } ?: System
    }
}

/** How a folder is rendered. */
enum class ViewMode(val id: String) {
    List("list"), Grid("grid");

    companion object {
        fun fromId(id: String?): ViewMode = entries.firstOrNull { it.id == id } ?: List
    }
}

/** A recently opened folder (display name + opaque ref) of a profile. */
data class RecentFolder(val profileId: String, val ref: String, val name: String)

/** UI-facing settings (persisted through the engine's `settings.*`; unknown keys are preserved). */
data class AppSettings(
    val language: LanguageChoice = LanguageChoice.System,
    val showHidden: Boolean = false,
    val collision: String = Collision.RENAME,
    val density: Density? = null,
    val viewMode: ViewMode = ViewMode.List,
    val theme: ThemeChoice = ThemeChoice.System,
    val onboarded: Boolean = false,
    /** Ask where to save every download (otherwise the last chosen folder is reused). */
    val askDownloadLocation: Boolean = true,
    val navWidthDp: Int = 300,
    val detailsOpen: Boolean = false,
    /** Upload changes made by other apps as soon as they are saved (otherwise ask first). */
    val autoSaveExternal: Boolean = true,
    /** profile id -> last visited folder ref (opaque). */
    val lastRefs: Map<String, String> = emptyMap(),
    val recents: List<RecentFolder> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    fun toRaw(): JsonObject {
        val m = raw.toMutableMap()
        if (language == LanguageChoice.System) m.remove(KEY_LANG) else m[KEY_LANG] = JsonPrimitive(language.id)
        m[KEY_HIDDEN] = JsonPrimitive(showHidden)
        m[KEY_COLLISION] = JsonPrimitive(collision)
        if (density == null) m.remove(KEY_DENSITY) else m[KEY_DENSITY] = JsonPrimitive(density.id)
        m[KEY_VIEW] = JsonPrimitive(viewMode.id)
        if (theme == ThemeChoice.System) m.remove(KEY_THEME) else m[KEY_THEME] = JsonPrimitive(theme.id)
        m[KEY_ONBOARDED] = JsonPrimitive(onboarded)
        m[KEY_ASK_DL] = JsonPrimitive(askDownloadLocation)
        m[KEY_NAV_W] = JsonPrimitive(navWidthDp)
        m[KEY_DETAILS] = JsonPrimitive(detailsOpen)
        m[KEY_AUTOSAVE] = JsonPrimitive(autoSaveExternal)
        m[KEY_LAST_REFS] = JsonObject(lastRefs.mapValues { JsonPrimitive(it.value) })
        m[KEY_RECENTS] = JsonArray(recents.map {
            JsonObject(mapOf("p" to JsonPrimitive(it.profileId), "r" to JsonPrimitive(it.ref), "n" to JsonPrimitive(it.name)))
        })
        return JsonObject(m)
    }

    companion object {
        const val KEY_LANG = "lang"
        const val KEY_HIDDEN = "show_hidden"
        const val KEY_COLLISION = "collision"
        const val KEY_DENSITY = "density"
        const val KEY_VIEW = "view_mode"
        const val KEY_THEME = "theme"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_ASK_DL = "ask_download_location"
        const val KEY_NAV_W = "nav_width_dp"
        const val KEY_DETAILS = "details_open"
        const val KEY_AUTOSAVE = "auto_save_external"
        const val KEY_LAST_REFS = "last_refs"
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 8

        fun fromRaw(raw: JsonObject): AppSettings {
            fun s(k: String) = (raw[k] as? JsonPrimitive)?.content
            val last = (raw[KEY_LAST_REFS] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
            val recents = (raw[KEY_RECENTS] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun f(k: String) = (o[k] as? JsonPrimitive)?.content
                val p = f("p"); val r = f("r"); val n = f("n")
                if (p == null || r == null || n == null) null else RecentFolder(p, r, n)
            }.orEmpty()
            return AppSettings(
                language = LanguageChoice.fromId(s(KEY_LANG)),
                showHidden = s(KEY_HIDDEN)?.toBooleanStrictOrNull() ?: false,
                collision = s(KEY_COLLISION)?.takeIf { it in Collision.all } ?: Collision.RENAME,
                density = Density.fromId(s(KEY_DENSITY)),
                viewMode = ViewMode.fromId(s(KEY_VIEW)),
                theme = ThemeChoice.fromId(s(KEY_THEME)),
                onboarded = s(KEY_ONBOARDED)?.toBooleanStrictOrNull() ?: false,
                askDownloadLocation = s(KEY_ASK_DL)?.toBooleanStrictOrNull() ?: true,
                navWidthDp = s(KEY_NAV_W)?.toIntOrNull()?.coerceIn(200, 480) ?: 300,
                detailsOpen = s(KEY_DETAILS)?.toBooleanStrictOrNull() ?: false,
                autoSaveExternal = s(KEY_AUTOSAVE)?.toBooleanStrictOrNull() ?: true,
                lastRefs = last,
                recents = recents,
                raw = raw,
            )
        }
    }
}

/** Holds [AppSettings] and persists every change through the engine. */
class SettingsStore(private val client: EngineClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** Last persistence failure (shown once as a message), null if none. */
    private val _error = MutableStateFlow<ErrInfo?>(null)
    val error: StateFlow<ErrInfo?> = _error.asStateFlow()

    suspend fun load() {
        when (val r = outcome { client.settingsGet() }) {
            is Outcome.Success -> _state.value = AppSettings.fromRaw(r.value)
            is Outcome.Failure -> _error.value = r.error
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        scope.launch {
            when (val r = outcome { client.settingsSet(next.toRaw()) }) {
                is Outcome.Success -> _state.update { it.copy(raw = r.value) }
                is Outcome.Failure -> _error.value = r.error
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }
}

/**
 * Serializes the prompts raised by the engine (trust host/cert, secrets) so the UI shows one dialog
 * at a time, and sends the user's answer with `prompt.reply`.
 */
class PromptCoordinator(private val scope: CoroutineScope, private val client: EngineClient) {
    private val _queue = MutableStateFlow<List<Prompt>>(emptyList())
    val queue: StateFlow<List<Prompt>> = _queue.asStateFlow()

    init {
        client.promptHandler = ::offer
    }

    fun offer(prompt: Prompt) {
        if (prompt is Prompt.Unknown) {
            // Unknown prompt kinds are refused: never trust something the UI cannot explain.
            scope.launch { outcome { client.promptReply(prompt.promptId, accept = false) } }
            return
        }
        if (prompt is Prompt.TrustHost || prompt is Prompt.TrustCert) trustPromptSeen = true
        _queue.update { it + prompt }
    }

    /** The last trust prompt the user accepted (host key / certificate): shown as connection security detail. */
    var lastAccepted: Prompt? = null
        private set

    /** A trust prompt (host key / certificate) was shown since the last [resetLastAccepted]. */
    var trustPromptSeen: Boolean = false
        private set

    fun resetLastAccepted() {
        lastAccepted = null
        trustPromptSeen = false
    }

    fun reply(accept: Boolean, value: String? = null, replace: Boolean = false) {
        val head = _queue.value.firstOrNull() ?: return
        if (accept && (head is Prompt.TrustHost || head is Prompt.TrustCert)) lastAccepted = head
        _queue.update { it.drop(1) }
        scope.launch { outcome { client.promptReply(head.promptId, accept, value, replace) } }
    }

    /** Drops pending prompts (e.g. the operation that raised them was cancelled). */
    fun clear() {
        _queue.value = emptyList()
    }
}
