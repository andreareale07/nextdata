package app.nextdata.state

import app.nextdata.engine.EngineClient
import app.nextdata.platform.PlatformFiles
import app.nextdata.protocol.DocState
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.OpenDoc
import app.nextdata.protocol.SaveResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which internal viewer shows a file ([External] = another app; [Unsupported] = offer download). */
enum class ViewerKind { Image, Text, Pdf, Media, External, Unsupported }

/** The document on screen. [images] are the sibling images of the folder (for swiping), [index] the current one. */
data class ViewerState(
    val doc: OpenDoc,
    val kind: ViewerKind,
    val sessionId: String,
    val entry: Entry,
    val images: List<Entry> = emptyList(),
    val index: Int = 0,
    /** PDF: number of pages. */
    val pages: Int = 0,
)

/** Opening in progress (download into the workspace). [fraction] null = unknown. */
data class OpenProgress(val name: String, val fraction: Float?)

sealed interface OpenMessage {
    data class Error(val error: ErrInfo) : OpenMessage
    /** Saved on the server and verified; [content] = verified on the content (not only the size). */
    data class Saved(val content: Boolean) : OpenMessage
    data object SavedAsCopy : OpenMessage
    /** The remote changed since the cached copy; local work was kept. */
    data object RemoteChangedKept : OpenMessage
    /** Nothing to save (the content equals what is on the server). */
    data object NothingToSave : OpenMessage
    /** The local file is empty/locked/changing: not uploaded. */
    data object LocalNotReady : OpenMessage
    /** No app can open it. */
    data object NoExternalApp : OpenMessage

    /** Another app saved [name] and NextData uploaded it (verified). */
    data class ExternalSaved(val name: String) : OpenMessage
    /** Another app saved [name]; "ask first" mode: the user decides ([docId] to save). */
    data class ExternalChanged(val docId: String, val sessionId: String, val name: String) : OpenMessage
    /** An app made copies next to [docId]'s file: never uploaded on their own, the user may use one. */
    data class CopyDetected(val docId: String, val sessionId: String, val names: List<String>) : OpenMessage
    /** [count] documents have changes that were never saved on the server (found at startup). */
    data class PendingFound(val count: Int) : OpenMessage

    /** Back from another app and nothing changed: the user may have saved elsewhere (explains "Import"). */
    data class NoChangeDetected(val docId: String, val name: String) : OpenMessage
}

/** Audio/video played straight from the server in ranges (nothing is downloaded or cached). */
data class MediaState(val sessionId: String, val entry: Entry)

/** A big file that would be copied into the temporary space of the device: asks before doing it. */
data class LargeFilePrompt(val sessionId: String, val entry: Entry)

/** A save that needs the user's decision. */
data class ConflictPrompt(val docId: String, val sessionId: String)

/**
 * Opening, viewing, editing and saving remote files without manual download/upload. All data-safety logic lives in
 * the engine (workspace + journal); this class orchestrates UI state, platform access and the change watching of
 * documents handed to other apps. A platform signal never decides anything: the engine compares content hashes.
 */
class OpenManager(
    private val client: EngineClient,
    private val scope: CoroutineScope,
    private val files: PlatformFiles,
    private val cacheLimit: () -> Long? = { null },
    private val autoSaveExternal: () -> Boolean = { true },
    /** True when the platform has an in-app player fed by range reads. */
    private val mediaAvailable: () -> Boolean = { false },
) {
    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    private val _progress = MutableStateFlow<OpenProgress?>(null)
    val progress: StateFlow<OpenProgress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<OpenMessage?>(null)
    val message: StateFlow<OpenMessage?> = _message.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _conflict = MutableStateFlow<ConflictPrompt?>(null)
    val conflict: StateFlow<ConflictPrompt?> = _conflict.asStateFlow()

    /** State of the document on screen as last reported by the engine. */
    private val _doc = MutableStateFlow<OpenDoc?>(null)
    val doc: StateFlow<OpenDoc?> = _doc.asStateFlow()

    private val _media = MutableStateFlow<MediaState?>(null)
    val media: StateFlow<MediaState?> = _media.asStateFlow()

    private val _large = MutableStateFlow<LargeFilePrompt?>(null)
    val large: StateFlow<LargeFilePrompt?> = _large.asStateFlow()

    /** Documents whose local work is not on the server (modified, failed, uncertain, conflicting, imported, orphaned). */
    private val _pending = MutableStateFlow<List<OpenDoc>>(emptyList())
    val pending: StateFlow<List<OpenDoc>> = _pending.asStateFlow()

    private val _pendingOpen = MutableStateFlow(false)
    val pendingOpen: StateFlow<Boolean> = _pendingOpen.asStateFlow()

    fun showPending(visible: Boolean) {
        _pendingOpen.value = visible
        if (visible) scope.launch { refreshPending() }
    }

    /** Reads the workspace list; [announce] = tell the user once if something is waiting (startup). */
    suspend fun refreshPending(announce: Boolean = false) {
        val r = outcome { client.openList() }
        if (r is Outcome.Success) {
            _pending.value = r.value.filter { it.needsAttention || it.state != DocState.CLEAN }.filter { it.state != DocState.DOWNLOADING }
            if (announce && _pending.value.isNotEmpty() && _message.value == null) _message.value = OpenMessage.PendingFound(_pending.value.size)
        }
    }

    /** After (re)connecting: settle interrupted saves by looking at the server, then refresh the list. */
    fun recover(sessionId: String) {
        scope.launch {
            outcome { client.openRecover(sessionId) }
            refreshPending()
        }
    }

    /** Save a pending document now (its profile must be connected: [sessionId]). */
    fun savePending(sessionId: String, docId: String, resolution: String = SaveResolution.NORMAL) {
        scope.launch {
            saveDoc(sessionId, docId, resolution)
            refreshPending()
        }
    }

    /** Copy the local work of a document to a folder the user picks (the way out for orphaned work). */
    fun exportPending(docId: String) {
        scope.launch {
            val target = files.chooseDownloadTarget(true) ?: return@launch
            when (val r = outcome { client.openExport(docId, target.destDir) }) {
                is Outcome.Failure -> { target.discard(); _message.value = OpenMessage.Error(r.error) }
                is Outcome.Success -> runCatching { target.finish(r.value) }
                    .onFailure { _message.value = OpenMessage.Error(ErrInfo.of(ErrorCodes.IO)) }
            }
        }
    }

    /** Explicit user decision: the local work is deleted. */
    fun discardPending(docId: String) {
        scope.launch {
            outcome { client.openDiscard(docId) }
            refreshPending()
        }
    }

    private var openJob: Job? = null
    private val watches = HashMap<String, Job>()
    private val forced = HashSet<String>()
    private val foreground = HashSet<String>()
    private val hintOnce = HashSet<String>()
    private val sessionOf = HashMap<String, String>()

    fun consumeMessage() = _message.update { null }

    companion object {
        /** Largest text file the internal editor loads (bigger ones are not edited in memory). */
        const val TEXT_EDIT_MAX = 5L * 1024 * 1024
        const val IMAGE_MAX = 100L * 1024 * 1024
        /** Above this size a copy into the device's temporary space needs the user's yes. */
        const val LARGE_COPY = 256L * 1024 * 1024
        const val POLL_MS = 1000L
        const val SETTLE_MS = 1500L
        /** Minimum time between two automatic uploads of the same document (apps with autosave). */
        const val AUTOSAVE_COOLDOWN_MS = 10_000L
        /** A watched document nobody touched for this long stops being watched (the journal/list still keep it). */
        const val WATCH_IDLE_MS = 8L * 3600 * 1000

        private val textLike = setOf("csv", "tsv", "svg", "json", "xml", "yml", "yaml", "toml", "md", "log", "txt", "ini", "cfg", "conf", "html", "css", "js", "sql", "tex", "rst")

        /** Pure routing rule (tested): which viewer opens [entry]. */
        fun classify(entry: Entry): ViewerKind {
            if (entry.kind != EntryKind.FILE) return ViewerKind.Unsupported
            val size = entry.size ?: 0L
            val ext = FileKinds.extensionOf(entry.name)
            return when {
                ext in textLike || FileKinds.categoryOf(entry) in setOf(FileCategory.Text, FileCategory.Code) ->
                    if (size <= TEXT_EDIT_MAX) ViewerKind.Text else ViewerKind.External
                FileKinds.categoryOf(entry) == FileCategory.Image -> if (size <= IMAGE_MAX) ViewerKind.Image else ViewerKind.External
                ext == "pdf" -> ViewerKind.Pdf
                FileKinds.categoryOf(entry) in setOf(FileCategory.Audio, FileCategory.Video) -> ViewerKind.Media
                else -> ViewerKind.External
            }
        }

        fun parentDir(path: String): String = path.substring(0, path.lastIndexOfAny(charArrayOf('/', '\\')).coerceAtLeast(0))
    }

    /** Taps a file: prepares it in the workspace and shows it (or hands it to another app). */
    fun open(sessionId: String, entry: Entry, siblings: List<Entry> = emptyList(), confirmedLarge: Boolean = false, forceExternal: Boolean = false) {
        openJob?.cancel()
        var kind = classify(entry)
        if (forceExternal) kind = ViewerKind.External
        if (kind == ViewerKind.Media) {
            if (mediaAvailable()) {
                _media.value = MediaState(sessionId, entry) // played from the server in ranges: no copy at all
                return
            }
            kind = ViewerKind.External
        }
        // anything that ends up copied locally and is big asks first (never a silent multi-GB download)
        if (!confirmedLarge && (entry.size ?: 0L) > LARGE_COPY && kind != ViewerKind.Image) {
            _large.value = LargeFilePrompt(sessionId, entry)
            return
        }
        _progress.value = OpenProgress(entry.name, null)
        openJob = scope.launch {
            try {
                val r = outcome { client.openPrepare(sessionId, entry.ref, cacheLimit()) { p -> _progress.value = OpenProgress(entry.name, p.fraction) } }
                when (r) {
                    is Outcome.Failure -> _message.value = OpenMessage.Error(r.error)
                    is Outcome.Success -> {
                        val res = r.value
                        if (res.remoteChanged && res.doc.state != DocState.CLEAN) _message.value = OpenMessage.RemoteChangedKept
                        _doc.value = res.doc
                        val pages = if (kind == ViewerKind.Pdf) files.pdfPageCount(res.doc.localPath) else null
                        if (kind == ViewerKind.Pdf && pages == null) kind = ViewerKind.External // cannot be shown here: another app
                        if (kind == ViewerKind.External) {
                            openExternally(sessionId, entry, res.doc, hintIfUnchanged = false)
                        } else if (kind == ViewerKind.Pdf) {
                            _viewer.value = ViewerState(res.doc, kind, sessionId, entry, pages = pages ?: 0)
                        } else {
                            val imgs = if (kind == ViewerKind.Image) siblings.filter { classify(it) == ViewerKind.Image } else emptyList()
                            _viewer.value = ViewerState(res.doc, kind, sessionId, entry, imgs, imgs.indexOfFirst { it.ref == entry.ref }.coerceAtLeast(0))
                        }
                    }
                }
            } finally {
                _progress.value = null
            }
        }
    }

    fun confirmLarge() {
        val p = _large.value ?: return
        _large.value = null
        open(p.sessionId, p.entry, confirmedLarge = true)
    }

    fun dismissLarge() {
        _large.value = null
    }

    /** Range reader for the in-app player: one pinned engine handle, opened on first use. */
    fun mediaSource(m: MediaState): app.nextdata.platform.RangeSource = object : app.nextdata.platform.RangeSource {
        private var handle: String? = null
        override suspend fun read(offset: Long, length: Int): ByteArray {
            val h = handle ?: client.readOpen(m.sessionId, m.entry.ref).handle.also { handle = it }
            return client.readRange(h, offset, length.coerceIn(1, 256 * 1024))
        }
        override suspend fun close() {
            val h = handle ?: return
            handle = null
            withContext(NonCancellable) { outcome { client.readClose(h) } }
        }
    }

    fun closeMedia() {
        _media.value = null
    }

    /** Playing from the server failed or the format is not supported: fall back to a local copy in another app. */
    fun mediaToExternal() {
        val m = _media.value ?: return
        _media.value = null
        open(m.sessionId, m.entry.copy(), confirmedLarge = false, forceExternal = true)
    }

    fun cancelOpening() {
        openJob?.cancel()
        _progress.value = null
    }

    /**
     * Opens the workspace copy in another app, marks it "may be modified" (the cache never drops it) and starts
     * watching it. Whether the other app writes back to this file is up to that app: that is why the watcher also
     * reports copies, and why [NoChangeDetected] points to "Import modified version".
     */
    suspend fun openExternally(sessionId: String, entry: Entry, doc: OpenDoc, edit: Boolean = true, hintIfUnchanged: Boolean = true) {
        val ok = files.openExternal(doc.localPath, edit)
        if (!ok) {
            _message.value = OpenMessage.NoExternalApp
            // from an internal viewer the user keeps it; otherwise offer download
            if (_viewer.value == null) _viewer.value = ViewerState(doc, ViewerKind.Unsupported, sessionId, entry)
            return
        }
        outcome { client.openMarkOpen(doc.id) }
        // The "nothing changed, import a version?" hint is for people who edit. A plain tap on a list item is mostly
        // just looking at the file: no question then (a change is still noticed and saved silently).
        if (hintIfUnchanged) hintOnce += doc.id else hintOnce -= doc.id
        if (edit) watch(sessionId, doc, entry.name)
    }

    // ------------------------------------------------------------------ watching documents handed to other apps

    /** Starts (once per document) the loop that notices external saves. No-op where the platform cannot list files. */
    fun watch(sessionId: String, doc: OpenDoc, name: String) {
        if (watches[doc.id]?.isActive == true) return
        sessionOf[doc.id] = sessionId
        watches[doc.id] = scope.launch { watchLoop(sessionId, doc, name) }
    }

    fun unwatch(docId: String) {
        watches.remove(docId)?.cancel()
    }

    fun unwatchAll() {
        watches.values.forEach { it.cancel() }
        watches.clear()
    }

    /** The app came back to the foreground: look at every watched document now. */
    fun onForeground() {
        val ids = watches.filterValues { it.isActive }.keys
        forced += ids
        foreground += ids
    }

    private suspend fun safeList(dir: String) = try { files.listLocal(dir) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    private suspend fun watchLoop(sessionId: String, doc: OpenDoc, name: String) {
        val dir = parentDir(doc.localPath)
        var last = safeList(dir) ?: return
        val knownCopies = HashSet<String>()
        var idle = 0L
        while (scope.isActive) {
            delay(POLL_MS)
            idle += POLL_MS
            if (idle >= WATCH_IDLE_MS) return
            val now = safeList(dir) ?: continue
            val force = forced.remove(doc.id)
            val fromForeground = foreground.remove(doc.id)
            if (now == last && !force) continue
            // the writer may still be busy: wait until two snapshots in a row agree
            var cur = now
            if (now != last) {
                var tries = 0
                while (tries++ < 20) {
                    delay(SETTLE_MS)
                    val again = safeList(dir) ?: break
                    if (again == cur) break
                    cur = again
                }
            }
            last = cur
            idle = 0
            val chk = when (val r = outcome { client.openCheck(doc.id, stableMs = 0) }) {
                is Outcome.Failure -> { _message.value = OpenMessage.Error(r.error); continue }
                is Outcome.Success -> r.value
            }
            if (chk.locked) { forced += doc.id; continue } // the app holds the file: look again shortly
            val fresh = chk.copies.filter { knownCopies.add(it) }
            if (fresh.isNotEmpty()) _message.value = OpenMessage.CopyDetected(doc.id, sessionId, fresh)
            if (chk.suspicious != null || !chk.changed) {
                // asked at most once per opening, and only when the user chose to edit
                if (fromForeground && fresh.isEmpty() && chk.suspicious == null && hintOnce.remove(doc.id)) _message.value = OpenMessage.NoChangeDetected(doc.id, name)
                continue
            }
            if (autoSaveExternal()) {
                val ok = saveDoc(sessionId, doc.id, SaveResolution.NORMAL, background = true)
                if (ok) _message.value = OpenMessage.ExternalSaved(name)
                delay(AUTOSAVE_COOLDOWN_MS) // apps with autosave must not cause a burst of uploads
            } else {
                _message.value = OpenMessage.ExternalChanged(doc.id, sessionId, name)
            }
        }
    }

    /** "Use this copy as the new version": imports it as the pending version and saves it (conflicts still ask). */
    fun useCopy(sessionId: String, docId: String, copyName: String) {
        val dir = (_doc.value?.takeIf { it.id == docId }?.localPath ?: _viewer.value?.doc?.localPath) ?: return
        scope.launch {
            val path = parentDir(dir) + "/" + copyName
            when (val r = outcome { client.openImport(docId, path) }) {
                is Outcome.Failure -> _message.value = OpenMessage.Error(r.error)
                is Outcome.Success -> saveDoc(sessionId, docId, SaveResolution.NORMAL)
            }
        }
    }

    /** The user chose a file exported by another app ("Save as", "Share") as the modified version. */
    fun importVersion(sessionId: String, docId: String, path: String) {
        scope.launch {
            when (val r = outcome { client.openImport(docId, path) }) {
                is Outcome.Failure -> _message.value = OpenMessage.Error(r.error)
                is Outcome.Success -> saveDoc(sessionId, docId, SaveResolution.NORMAL)
            }
        }
    }

    /** Lets the user pick the file another app exported ("Save as", "Share") and uses it as the modified version. */
    fun pickAndImportFor(docId: String) {
        val s = sessionOf[docId] ?: return
        scope.launch {
            val path = files.pickFileToImport() ?: return@launch
            importVersion(s, docId, path)
        }
    }

    /** Saves a document that is being watched/asked about (not the one on screen). */
    fun saveAsked(sessionId: String, docId: String) {
        scope.launch { saveDoc(sessionId, docId, SaveResolution.NORMAL) }
    }

    // ------------------------------------------------------------------ viewer

    /** Swipe to another image of the folder. */
    fun showImage(index: Int) {
        val v = _viewer.value ?: return
        if (v.kind != ViewerKind.Image || index !in v.images.indices || index == v.index) return
        val entry = v.images[index]
        openJob?.cancel()
        openJob = scope.launch {
            _progress.value = OpenProgress(entry.name, null)
            try {
                val r = outcome { client.openPrepare(v.sessionId, entry.ref, cacheLimit()) { p -> _progress.value = OpenProgress(entry.name, p.fraction) } }
                when (r) {
                    is Outcome.Failure -> _message.value = OpenMessage.Error(r.error)
                    is Outcome.Success -> {
                        val old = v.doc
                        _doc.value = r.value.doc
                        _viewer.value = v.copy(doc = r.value.doc, entry = entry, index = index)
                        withContext(NonCancellable) { outcome { client.openRelease(old.id) } }
                    }
                }
            } finally {
                _progress.value = null
            }
        }
    }

    fun close() {
        val v = _viewer.value
        _viewer.value = null
        _conflict.value = null
        _doc.value = null
        if (v != null) scope.launch { withContext(NonCancellable) { outcome { client.openRelease(v.doc.id) } } }
    }

    /**
     * Saves the document on screen: the engine looks at the local file, uploads only if it really changed, verifies
     * the remote and only then reports success. Conflicts go to [conflict]; nothing is ever overwritten silently.
     */
    fun save(resolution: String = SaveResolution.NORMAL, recoveryDir: String? = null) {
        val v = _viewer.value ?: return
        scope.launch { saveDoc(v.sessionId, v.doc.id, resolution, recoveryDir) }
    }

    /** Resolution chosen in the conflict dialog (works for background documents too). */
    fun resolveConflict(resolution: String) {
        val c = _conflict.value ?: return
        _conflict.value = null
        scope.launch { saveDoc(c.sessionId, c.docId, resolution) }
    }

    /** Returns true when the document ended up saved and verified. [background] = no viewer involved. */
    private suspend fun saveDoc(sessionId: String, docId: String, resolution: String, recoveryDir: String? = null, background: Boolean = false): Boolean {
        if (_saving.value) return false
        _saving.value = true
        _conflict.value = null
        try {
            val chk = outcome { client.openCheck(docId, stableMs = 0) }
            if (chk is Outcome.Success) {
                if (_viewer.value?.doc?.id == docId) _doc.value = chk.value.doc
                if (chk.value.suspicious != null || chk.value.locked || chk.value.missing) {
                    if (!background) _message.value = OpenMessage.LocalNotReady
                    return false
                }
                if (!chk.value.changed && chk.value.doc.state == DocState.CLEAN) {
                    if (!background) _message.value = OpenMessage.NothingToSave
                    return false
                }
            }
            return when (val r = outcome { client.openSave(sessionId, docId, resolution, recoveryDir = recoveryDir) }) {
                is Outcome.Success -> {
                    if (_viewer.value?.doc?.id == docId) {
                        _doc.value = r.value
                        _viewer.update { it?.copy(doc = r.value) }
                    }
                    if (!background) _message.value = if (resolution == SaveResolution.COPY) OpenMessage.SavedAsCopy else OpenMessage.Saved(content = true)
                    true
                }
                is Outcome.Failure -> {
                    refreshDoc(docId)
                    if (r.error.code == ErrorCodes.CONFLICT) _conflict.value = ConflictPrompt(docId, sessionId) else _message.value = OpenMessage.Error(r.error)
                    false
                }
            }
        } finally {
            _saving.value = false
        }
    }

    fun dismissConflict() {
        _conflict.value = null
    }

    private suspend fun refreshDoc(id: String) {
        val l = outcome { client.openList() }
        if (l is Outcome.Success) l.value.firstOrNull { it.id == id }?.let { d ->
            if (_viewer.value?.doc?.id == id) {
                _doc.value = d
                _viewer.update { it?.copy(doc = d) }
            }
        }
    }

    /** Writes edited text into the workspace copy (draft, never uploaded by itself). */
    suspend fun writeLocal(bytes: ByteArray): Boolean {
        val v = _viewer.value ?: return false
        return files.writeFile(v.doc.localPath, bytes)
    }
}
