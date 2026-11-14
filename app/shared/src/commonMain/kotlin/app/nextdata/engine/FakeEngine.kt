package app.nextdata.engine

import app.nextdata.protocol.Codec
import app.nextdata.protocol.EngineEvent
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.Entry
import app.nextdata.protocol.Breadcrumb
import app.nextdata.protocol.Favorite
import app.nextdata.protocol.ImportConflict
import app.nextdata.protocol.ImportResult
import app.nextdata.protocol.InitResult
import app.nextdata.protocol.ListResult
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.ProgressInfo
import app.nextdata.protocol.Prompt
import app.nextdata.protocol.PromptKind
import app.nextdata.protocol.ProtocolId
import app.nextdata.protocol.ProtocolInfo
import app.nextdata.protocol.SortKey
import app.nextdata.protocol.Collision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * In-memory implementation of the protocol, used for UI/ViewModel tests and `--fake` runs.
 * It speaks the same JSON protocol as the real engine (args and results go through [Codec]),
 * including trust prompts, progress events and simulated errors.
 *
 * Scenario hooks (by profile host name): contains "fail" -> AUTH_FAILED, "down" -> NETWORK,
 * "changed" -> host key changed prompt, "selfsigned" -> TLS certificate prompt.
 * Folders: "locked" -> PERMISSION_DENIED on list; "Pictures" has 450 entries (pagination).
 */
@OptIn(ExperimentalEncodingApi::class)
class FakeEngine(
    /** Artificial latency for list/mutations. Tests pass 0. */
    var latencyMs: Long = 0,
    /** Delay between simulated progress steps. */
    var transferStepDelayMs: Long = 0,
    var transferSteps: Int = 4,
    seedProfiles: Boolean = false,
) : Engine {

    private class Node(
        val id: Int,
        var name: String,
        val kind: String,
        var size: Long? = null,
        var mtime: Long? = null,
        var parent: Node? = null,
        val children: MutableList<Node> = mutableListOf(),
    ) {
        val ref get() = "n$id"
    }

    private class FakeError(val info: ErrInfo) : Exception(info.code)

    private var opCounter = 0L
    private var idCounter = 0
    private var profileCounter = 0
    private var sessionCounter = 0

    private val profiles = mutableListOf<Profile>()
    private val secretProfiles = mutableSetOf<String>()
    private val settings = mutableMapOf<String, JsonElement>("show_hidden" to JsonPrimitive(false))
    private val favorites = mutableMapOf<String, MutableList<Favorite>>()
    private val sessions = mutableMapOf<String, String>() // session -> profile id
    private val roots = mutableMapOf<String, Node>() // profile id -> fs root
    private val nodes = mutableMapOf<String, Node>() // ref -> node
    private val trustedHosts = mutableSetOf<String>()
    private val trustedCerts = mutableSetOf<String>()
    private val pendingPrompts = mutableMapOf<String, CompletableDeferred<PromptAnswer>>()
    private var promptCounter = 0

    private class PromptAnswer(val accept: Boolean, val value: String?, val replace: Boolean)

    /** Commands that were cancelled by the collector (for tests). */
    val cancelledCommands = mutableListOf<String>()

    /** Log of every command executed (for tests). */
    val commandLog = mutableListOf<String>()

    /** If set, the next command fails with this error (then it resets). */
    var failNext: ErrInfo? = null

    init {
        if (seedProfiles) {
            profiles += Profile("p1", "Home NAS", ProtocolId.SFTP, "nas.local", 22, "andrea", "password", "", false, null, true, "#0F766E")
            profiles += Profile("p2", "Nextcloud", ProtocolId.WEBDAV, "cloud.example.org", 0, "andrea", "password", "/remote.php/dav/files/andrea", true, null, true, "#2563EB")
            profiles += Profile("p3", "Backups (S3)", ProtocolId.S3, "s3.eu-west-1.amazonaws.com", 0, "", "none", "", true,
                app.nextdata.protocol.S3Config("backups", "eu-west-1", "AKIAFAKEKEY", false), true, "#B45309")
            profiles += Profile("p4", "Changed host", ProtocolId.SFTP, "changed.example.net", 22, "root", "password", "", false, null, true, "#BE123C")
            secretProfiles += listOf("p1", "p2", "p3", "p4")
            profileCounter = 4
        }
    }

    // ------------------------------------------------------------------ Engine

    override fun execute(cmd: String, args: JsonObject): Flow<EngineEvent> = flow {
        val op = ++opCounter
        commandLog += cmd
        var terminal = false
        val out = this
        try {
            val forced = failNext
            if (forced != null) {
                failNext = null
                terminal = true
                out.emit(EngineEvent.Err(op, forced))
                return@flow
            }
            val data = try {
                dispatch(op, cmd, args, out)
            } catch (e: FakeError) {
                terminal = true
                out.emit(EngineEvent.Err(op, e.info))
                return@flow
            }
            terminal = true
            out.emit(EngineEvent.Ok(op, data))
        } finally {
            if (!terminal) cancelledCommands += cmd
        }
    }

    private fun err(code: String, vararg params: Pair<String, String>): Nothing = throw FakeError(ErrInfo.of(code, *params))

    private suspend fun dispatch(op: Long, cmd: String, a: JsonObject, out: FlowCollector<EngineEvent>): JsonObject {
        fun str(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull ?: err(ErrorCodes.INVALID_ARGUMENT, "name" to k)
        fun strOrNull(k: String) = (a[k] as? JsonPrimitive)?.takeIf { it.content != "null" }?.contentOrNull
        fun strList(k: String) = (a[k] as? JsonArray)?.map { it.jsonPrimitive.content } ?: err(ErrorCodes.INVALID_ARGUMENT, "name" to k)
        suspend fun progress(p: ProgressInfo) = out.emit(EngineEvent.Progress(op, p))

        return when (cmd) {
            "init" -> {
                str("data_dir"); str("pmk_b64")
                Codec.json.encodeToJsonElement(
                    InitResult(
                        1, "fake-0.1",
                        listOf(
                            ProtocolInfo(ProtocolId.SFTP, true), ProtocolInfo(ProtocolId.WEBDAV, true), ProtocolInfo(ProtocolId.S3, true),
                            ProtocolInfo(ProtocolId.SMB, false, ErrorCodes.PROTOCOL_DISABLED), ProtocolInfo(ProtocolId.FAKE, true),
                        ),
                    ),
                ) as JsonObject
            }
            "profiles.list" -> buildJsonObject { put("profiles", profilesJson(profiles)) }
            "profiles.save" -> {
                val p = Codec.json.decodeFromJsonElement(Profile.serializer(), a["profile"] ?: err(ErrorCodes.INVALID_ARGUMENT))
                if (p.name.isBlank()) err(ErrorCodes.INVALID_ARGUMENT, "name" to "name")
                val secrets = (a["secrets"] as? JsonObject)?.let { Codec.json.decodeFromJsonElement(ProfileSecrets.serializer(), it) }
                val id = p.id.ifEmpty { "p${++profileCounter}" }
                val hadSecret = id in secretProfiles
                val hasSecret = hadSecret || (secrets != null && !secrets.isEmpty)
                if (hasSecret) secretProfiles += id
                val saved = p.copy(id = id, hasSecret = hasSecret)
                val idx = profiles.indexOfFirst { it.id == id }
                if (idx >= 0) profiles[idx] = saved else profiles += saved
                buildJsonObject { put("profile", Codec.json.encodeToJsonElement(Profile.serializer(), saved)) }
            }
            "profiles.delete" -> {
                val id = str("id")
                profiles.removeAll { it.id == id }
                secretProfiles -= id
                favorites -= id
                JsonObject(emptyMap())
            }
            "profiles.export" -> {
                val pass = str("passphrase")
                if (pass.isEmpty()) err(ErrorCodes.INVALID_ARGUMENT, "name" to "passphrase")
                val payload = buildJsonObject {
                    put("magic", "NDFAKE1")
                    put("pass", pass)
                    put("profiles", profilesJson(profiles.map { it.copy(hasSecret = false) }))
                }
                buildJsonObject { put("data_b64", Base64.encode(payload.toString().encodeToByteArray())) }
            }
            "profiles.import" -> {
                val payload = try {
                    Codec.json.parseToJsonElement(Base64.decode(str("data_b64")).decodeToString()).jsonObject
                } catch (_: Exception) {
                    err(ErrorCodes.IMPORT_INVALID)
                }
                if (payload["magic"]?.jsonPrimitive?.contentOrNull != "NDFAKE1") err(ErrorCodes.IMPORT_INVALID)
                if (payload["pass"]?.jsonPrimitive?.contentOrNull != str("passphrase")) err(ErrorCodes.PASSPHRASE_INVALID)
                val imported = Codec.json.decodeFromJsonElement(ListSerializer(Profile.serializer()), payload["profiles"]!!)
                val conflicts = imported.filter { i -> profiles.any { it.id == i.id } }.map { ImportConflict(it.id, it.name) }
                val apply = (a["apply"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (apply) {
                    imported.forEach { i ->
                        val idx = profiles.indexOfFirst { it.id == i.id }
                        if (idx >= 0) profiles[idx] = i else profiles += i
                    }
                }
                Codec.json.encodeToJsonElement(ImportResult.serializer(), ImportResult(imported, conflicts, apply)) as JsonObject
            }
            "session.connect" -> connect(str("profile_id"), out, op)
            "prompt.reply" -> {
                val answer = pendingPrompts.remove(str("prompt_id"))
                answer?.complete(
                    PromptAnswer(
                        (a["accept"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        strOrNull("value"),
                        (a["replace"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    ),
                )
                JsonObject(emptyMap())
            }
            "session.list" -> {
                val profileId = sessionProfile(str("session"))
                if (latencyMs > 0) delay(latencyMs)
                val node = nodes[str("ref")] ?: err(ErrorCodes.NOT_FOUND, "path" to str("ref"))
                if (node.kind != EntryKind.DIR && node.kind != EntryKind.SYMLINK) err(ErrorCodes.INVALID_ARGUMENT)
                if (node.name == "locked") err(ErrorCodes.PERMISSION_DENIED, "name" to node.name)
                val limit = (a["limit"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 500) ?: 200
                val sort = strOrNull("sort") ?: SortKey.NAME
                val desc = (a["desc"] as? JsonPrimitive)?.booleanOrNull ?: false
                val offset = strOrNull("page_token")?.removePrefix("o:")?.toIntOrNull() ?: 0
                val sorted = sortChildren(node.children, sort, desc)
                val page = sorted.drop(offset).take(limit)
                val next = if (offset + limit < sorted.size) "o:${offset + limit}" else null
                val crumbs = generateSequence(node) { it.parent }.toList().asReversed()
                    .map { Breadcrumb(if (it.parent == null) profiles.firstOrNull { p -> p.id == profileId }?.name ?: "/" else it.name, it.ref) }
                Codec.json.encodeToJsonElement(
                    ListResult.serializer(),
                    ListResult(node.ref, node.parent?.ref, crumbs, page.map { entryOf(it) }, next, sorted.size),
                ) as JsonObject
            }
            "session.mkdir" -> {
                sessionProfile(str("session"))
                if (latencyMs > 0) delay(latencyMs)
                val parent = nodes[str("parent_ref")] ?: err(ErrorCodes.NOT_FOUND)
                val name = validName(str("name"))
                if (parent.children.any { it.name == name }) err(ErrorCodes.ALREADY_EXISTS, "name" to name)
                val n = newNode(name, EntryKind.DIR, null, parent)
                buildJsonObject { put("entry", Codec.json.encodeToJsonElement(Entry.serializer(), entryOf(n))) }
            }
            "session.rename" -> {
                sessionProfile(str("session"))
                if (latencyMs > 0) delay(latencyMs)
                val node = nodes[str("ref")] ?: err(ErrorCodes.NOT_FOUND)
                val name = validName(str("new_name"))
                if (node.parent?.children?.any { it !== node && it.name == name } == true) err(ErrorCodes.ALREADY_EXISTS, "name" to name)
                node.name = name
                buildJsonObject { put("entry", Codec.json.encodeToJsonElement(Entry.serializer(), entryOf(node))) }
            }
            "session.delete" -> {
                sessionProfile(str("session"))
                val refs = strList("refs")
                val recursive = (a["recursive"] as? JsonPrimitive)?.booleanOrNull ?: false
                var deleted = 0
                refs.forEachIndexed { i, ref ->
                    val node = nodes[ref] ?: err(ErrorCodes.NOT_FOUND, "path" to ref)
                    if (node.kind == EntryKind.DIR && node.children.isNotEmpty() && !recursive) err(ErrorCodes.INVALID_ARGUMENT, "name" to node.name)
                    if (latencyMs > 0) delay(latencyMs)
                    progress(ProgressInfo(i.toLong(), null, node.name, i, refs.size))
                    node.parent?.children?.remove(node)
                    forget(node)
                    deleted++
                }
                buildJsonObject { put("deleted", deleted) }
            }
            "session.close" -> {
                sessions.remove(str("session"))
                JsonObject(emptyMap())
            }
            "transfer.download" -> {
                sessionProfile(str("session"))
                val refs = strList("refs")
                val dest = str("dest_dir")
                val picked = refs.map { nodes[it] ?: err(ErrorCodes.NOT_FOUND, "path" to it) }
                val total = picked.sumOf { totalSize(it) }.coerceAtLeast(1)
                simulate(::progress, total, picked.map { it.name })
                buildJsonObject {
                    put("local_paths", buildJsonArray { picked.forEach { add(JsonPrimitive(dest.trimEnd('/', '\\') + "/" + it.name)) } })
                }
            }
            "transfer.upload" -> {
                sessionProfile(str("session"))
                val paths = strList("local_paths")
                val parent = nodes[str("parent_ref")] ?: err(ErrorCodes.NOT_FOUND)
                val collision = strOrNull("collision") ?: Collision.RENAME
                val names = paths.map { it.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') }
                simulate(::progress, names.size * 100_000L, names)
                val created = mutableListOf<Entry>()
                names.forEach { n ->
                    val existing = parent.children.firstOrNull { it.name == n }
                    when {
                        existing == null -> created += entryOf(newNode(n, EntryKind.FILE, 100_000, parent))
                        collision == Collision.SKIP -> Unit
                        collision == Collision.FAIL -> err(ErrorCodes.ALREADY_EXISTS, "name" to n)
                        else -> created += entryOf(newNode(uniqueName(parent, n), EntryKind.FILE, 100_000, parent))
                    }
                }
                buildJsonObject { put("entries", Codec.json.encodeToJsonElement(ListSerializer(Entry.serializer()), created)) }
            }
            "settings.get" -> buildJsonObject { put("settings", JsonObject(settings.toMap())) }
            "settings.set" -> {
                val s = a["settings"] as? JsonObject ?: err(ErrorCodes.INVALID_ARGUMENT)
                settings.clear(); settings.putAll(s)
                buildJsonObject { put("settings", JsonObject(settings.toMap())) }
            }
            "favorites.list" -> favoritesJson(str("profile_id"))
            "favorites.add" -> {
                val list = favorites.getOrPut(str("profile_id")) { mutableListOf() }
                if (list.none { it.ref == str("ref") }) list += Favorite(str("ref"), str("name"))
                favoritesJson(str("profile_id"))
            }
            "favorites.remove" -> {
                favorites[str("profile_id")]?.removeAll { it.ref == str("ref") }
                favoritesJson(str("profile_id"))
            }
            else -> err(ErrorCodes.INVALID_ARGUMENT, "name" to cmd)
        }
    }

    // ------------------------------------------------------------------ connect + prompts

    private suspend fun connect(profileId: String, out: FlowCollector<EngineEvent>, op: Long): JsonObject {
        val p = profiles.firstOrNull { it.id == profileId } ?: err(ErrorCodes.NOT_FOUND, "name" to profileId)
        if (p.protocol == ProtocolId.SMB) err(ErrorCodes.PROTOCOL_DISABLED)
        if (latencyMs > 0) delay(latencyMs)
        if (p.host.contains("down")) err(ErrorCodes.NETWORK)
        if (p.protocol == ProtocolId.SFTP && p.host !in trustedHosts) {
            val prior = if (p.host.contains("changed")) "SHA256:1ldF1ngerpr1ntQ3tnVwx2B8Zf0aYz9Kc7mUo4Hs6Tw" else null
            val ans = ask(out, op, Prompt.TrustHost("", p.host, if (p.port == 0) 22 else p.port, "ssh-ed25519", "SHA256:Zk3oQe9r0n8mB1c5Hh7LpV2xYw4TtUa6JdSgNfC0iMk", prior))
            if (!ans.accept) err(if (prior != null) ErrorCodes.HOST_KEY_CHANGED else ErrorCodes.HOST_KEY_REJECTED)
            if (prior != null && !ans.replace) err(ErrorCodes.HOST_KEY_CHANGED)
            trustedHosts += p.host
        }
        if (p.tls && (p.protocol == ProtocolId.WEBDAV || p.protocol == ProtocolId.S3) && p.host.contains("selfsigned") && p.host !in trustedCerts) {
            val ans = ask(out, op, Prompt.TrustCert("", p.host, 443, "9f2c0b7a5d1e43c8a6f0b2d4e6a8c0e2f4a6b8d0c2e4f6a8b0d2e4f6a8c0e2f4", "CN=${p.host}", true, "self_signed"))
            if (!ans.accept) err(ErrorCodes.TLS_UNTRUSTED)
            trustedCerts += p.host
        }
        if (p.host.contains("fail")) err(ErrorCodes.AUTH_FAILED)
        if (p.auth != "none" && p.id !in secretProfiles) {
            val kind = if (p.auth == "key") PromptKind.PASSPHRASE else PromptKind.PASSWORD
            val ans = ask(out, op, Prompt.Secret("", kind, p.name))
            if (!ans.accept || ans.value.isNullOrEmpty()) err(ErrorCodes.AUTH_FAILED)
        }
        val sid = "s${++sessionCounter}"
        sessions[sid] = p.id
        val root = roots.getOrPut(p.id) { seedFs() }
        return buildJsonObject {
            put("session", sid)
            put("home_ref", root.ref)
        }
    }

    private suspend fun ask(out: FlowCollector<EngineEvent>, op: Long, template: Prompt): PromptAnswer {
        val id = "p${++promptCounter}"
        val prompt = when (template) {
            is Prompt.TrustHost -> template.copy(promptId = id)
            is Prompt.TrustCert -> template.copy(promptId = id)
            is Prompt.Secret -> template.copy(promptId = id)
            is Prompt.Unknown -> template.copy(promptId = id)
        }
        val d = CompletableDeferred<PromptAnswer>()
        pendingPrompts[id] = d
        try {
            out.emit(EngineEvent.PromptRequest(op, prompt))
            return d.await()
        } finally {
            pendingPrompts.remove(id)
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun simulate(progress: suspend (ProgressInfo) -> Unit, total: Long, names: List<String>) {
        val steps = transferSteps.coerceAtLeast(1)
        for (i in 1..steps) {
            if (transferStepDelayMs > 0) delay(transferStepDelayMs)
            val item = names[((i - 1) * names.size / steps).coerceIn(0, names.size - 1)]
            progress(ProgressInfo(total * i / steps, total, item, (i * names.size / steps).coerceAtMost(names.size), names.size))
        }
    }

    private fun sessionProfile(session: String): String = sessions[session] ?: err(ErrorCodes.SESSION_CLOSED)

    private fun validName(raw: String): String {
        val n = raw.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\')) err(ErrorCodes.INVALID_ARGUMENT, "name" to raw)
        return n
    }

    private fun uniqueName(parent: Node, name: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val base = if (dot != null) name.substring(0, dot) else name
        val ext = if (dot != null) name.substring(dot) else ""
        var i = 1
        while (parent.children.any { it.name == "$base ($i)$ext" }) i++
        return "$base ($i)$ext"
    }

    private fun newNode(name: String, kind: String, size: Long?, parent: Node?): Node {
        val n = Node(++idCounter, name, kind, size, 1_700_000_000_000L + idCounter * 3_600_000L, parent)
        nodes[n.ref] = n
        parent?.children?.add(n)
        return n
    }

    private fun forget(n: Node) {
        nodes.remove(n.ref)
        n.children.forEach { forget(it) }
    }

    private fun totalSize(n: Node): Long = if (n.kind == EntryKind.DIR) n.children.sumOf { totalSize(it) } else n.size ?: 0

    private fun entryOf(n: Node) = Entry(n.name, n.ref, n.kind, n.size, n.mtime, n.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))

    private fun sortChildren(list: List<Node>, key: String, desc: Boolean): List<Node> {
        val cmp: Comparator<Node> = when (key) {
            SortKey.SIZE -> compareBy({ it.size ?: -1L }, { it.name.lowercase() })
            SortKey.MTIME -> compareBy({ it.mtime ?: 0L }, { it.name.lowercase() })
            else -> compareBy { it.name.lowercase() }
        }
        val dirs = list.filter { it.kind == EntryKind.DIR }.sortedWith(if (desc) cmp.reversed() else cmp)
        val rest = list.filter { it.kind != EntryKind.DIR }.sortedWith(if (desc) cmp.reversed() else cmp)
        return dirs + rest
    }

    private fun profilesJson(list: List<Profile>): JsonArray =
        Codec.json.encodeToJsonElement(ListSerializer(Profile.serializer()), list) as JsonArray

    private fun favoritesJson(profileId: String): JsonObject = buildJsonObject {
        put("favorites", Codec.json.encodeToJsonElement(ListSerializer(Favorite.serializer()), favorites[profileId] ?: emptyList()))
    }

    private fun seedFs(): Node {
        val root = newNode("/", EntryKind.DIR, null, null)
        val docs = newNode("Documents", EntryKind.DIR, null, root)
        newNode("Report 2026.pdf", EntryKind.FILE, 1_250_000, docs)
        newNode("Budget.xlsx", EntryKind.FILE, 48_213, docs)
        newNode("notes.txt", EntryKind.FILE, 912, docs)
        newNode("Contract.docx", EntryKind.FILE, 88_020, docs)
        val pics = newNode("Pictures", EntryKind.DIR, null, root)
        for (i in 1..450) newNode("IMG_" + i.toString().padStart(4, '0') + ".jpg", EntryKind.FILE, 1_500_000L + i * 1000, pics)
        val music = newNode("Music", EntryKind.DIR, null, root)
        newNode("track01.mp3", EntryKind.FILE, 4_300_000, music)
        newNode("track02.flac", EntryKind.FILE, 28_900_000, music)
        newNode("Projects", EntryKind.DIR, null, root)
        newNode("empty", EntryKind.DIR, null, root)
        newNode("locked", EntryKind.DIR, null, root)
        newNode(".config", EntryKind.DIR, null, root)
        newNode(".bashrc", EntryKind.FILE, 220, root)
        newNode("readme.md", EntryKind.FILE, 2_048, root)
        newNode("archive.zip", EntryKind.FILE, 73_400_320, root)
        newNode("ubuntu.iso", EntryKind.FILE, 4_700_000_000, root)
        newNode("latest", EntryKind.SYMLINK, null, root)
        newNode("device0", EntryKind.OTHER, null, root)
        return root
    }
}
