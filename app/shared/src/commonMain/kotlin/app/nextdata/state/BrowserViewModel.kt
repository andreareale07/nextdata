package app.nextdata.state

import app.nextdata.engine.EngineClient
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.UploadSelection
import app.nextdata.protocol.Breadcrumb
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.Favorite
import app.nextdata.protocol.Profile
import app.nextdata.protocol.SortKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transient, non-blocking feedback shown as a snackbar. */
sealed interface UiMessage {
    data class Error(val error: ErrInfo) : UiMessage
    data class FolderCreated(val name: String) : UiMessage
    data class Renamed(val name: String) : UiMessage
    data class Deleted(val count: Int) : UiMessage
    data class DownloadQueued(val count: Int) : UiMessage
    data class UploadQueued(val count: Int) : UiMessage
    data class FavoriteChanged(val added: Boolean) : UiMessage
    /** A download finished; [location] (platform specific) lets the user open the destination. */
    data class DownloadDone(val count: Int, val label: String?, val location: String?) : UiMessage
    data class UploadDone(val count: Int) : UiMessage
    /** Path copied to the clipboard. */
    data object PathCopied : UiMessage
    /** The folder restored from the last session no longer exists: back at the start folder. */
    data object RestoreFailed : UiMessage
}

/** Pure helpers for what the list shows (also used by the tests). */
object BrowserListing {
    fun visible(entries: List<Entry>, showHidden: Boolean, filter: String): List<Entry> {
        val f = filter.trim()
        return entries.filter { e ->
            (showHidden || !FileKinds.isHidden(e.name)) && (f.isEmpty() || e.name.contains(f, ignoreCase = true))
        }
    }

    /** Total size of the selected *files* (folders have no size without a recursive listing). */
    fun selectionBytes(entries: List<Entry>, selection: Set<String>): Long =
        entries.filter { it.ref in selection && it.kind == EntryKind.FILE }.sumOf { it.size ?: 0L }
}

data class BrowserState(
    /** Reference of the directory currently listed (null before the first listing). */
    val ref: String? = null,
    val parentRef: String? = null,
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val nextToken: String? = null,
    val totalHint: Int? = null,
    /** Listing in progress (first page / refresh). */
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** Failure of the last listing request; [errorRef] is what to retry. */
    val error: ErrInfo? = null,
    val errorRef: String? = null,
    val sortKey: String = SortKey.NAME,
    val sortDesc: Boolean = false,
    val selection: Set<String> = emptySet(),
    /** Anchor of range selection (shift+click) and the item the keyboard cursor is on. */
    val anchor: String? = null,
    val lead: String? = null,
    /** Quick filter typed by the user (case-insensitive substring of the name). */
    val filter: String = "",
    val favorites: List<Favorite> = emptyList(),
    /** A mutation (mkdir/rename/delete) is running. */
    val busy: Boolean = false,
    /** Picked files are being copied/prepared before the upload is queued. */
    val preparing: Boolean = false,
    val message: UiMessage? = null,
) {
    val isFavoriteHere: Boolean get() = ref != null && favorites.any { it.ref == ref }
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val canGoUp: Boolean get() = parentRef != null
}

/**
 * State holder for one open session (one profile). All paths/refs come from the engine
 * (`ref`, `parent_ref`, `breadcrumbs`): this class never builds or parses a path.
 */
class BrowserViewModel(
    val profile: Profile,
    val sessionId: String,
    val homeRef: String,
    private val client: EngineClient,
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
    private val transfers: TransfersViewModel,
    private val files: PlatformFiles,
    /** Folder to restore (last session); falls back to [homeRef] if it cannot be listed. */
    private val initialRef: String? = null,
    private val askDownloadLocation: () -> Boolean = { true },
    /** Reports every folder successfully listed (persist last folder / recents). */
    private val onLocation: (ref: String, name: String) -> Unit = { _, _ -> },
    /** Shows files without downloading them (docs/open-edit.md); null = taps fall back to downloading. */
    private val opener: OpenManager? = null,
) {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var restoring = false
    private var closed = false

    init {
        scope.launch {
            // Refresh the listing when an upload to the directory we are looking at finishes.
            transfers.completions
                .filter { it.sessionId == sessionId && it.parentRef != null }
                .collect { c -> if (c.parentRef == _state.value.ref) refresh() }
        }
        scope.launch {
            transfers.completions.filter { it.sessionId == sessionId }.collect { c ->
                val msg = when (c.direction) {
                    TransferDirection.Download -> UiMessage.DownloadDone(c.count, c.destinationLabel, c.location)
                    TransferDirection.Upload -> UiMessage.UploadDone(c.count)
                }
                _state.update { it.copy(message = msg) }
            }
        }
        scope.launch {
            val r = outcome { client.favoritesList(profile.id) }
            if (r is Outcome.Success) _state.update { it.copy(favorites = r.value) }
        }
        if (initialRef != null && initialRef != homeRef) {
            restoring = true
            open(initialRef)
        } else open(homeRef)
    }


    // ------------------------------------------------------------------ navigation / listing

    fun open(ref: String) = load(ref, pageToken = null)

    fun up() {
        _state.value.parentRef?.let { open(it) }
    }

    /**
     * Cheap liveness check of the open session (lists one entry of the current folder). Returns the error, or
     * null when the connection works. Used when the app returns from the background, where mobile OSes may
     * have cut the TCP connection.
     */
    suspend fun probe(): ErrInfo? {
        val s = _state.value
        val ref = s.ref ?: return null
        return outcome { client.list(sessionId, ref, null, 1, s.sortKey, s.sortDesc) }.errorOrNull
    }

    fun refresh() {
        val s = _state.value
        (s.ref ?: s.errorRef)?.let { open(it) }
    }

    fun retry() {
        val s = _state.value
        (s.errorRef ?: s.ref)?.let { open(it) }
    }

    /** Dismisses a listing error and keeps showing the last good listing. */
    fun dismissError() = _state.update { it.copy(error = null, errorRef = null, loading = false) }

    fun loadMore() {
        val s = _state.value
        val token = s.nextToken ?: return
        val ref = s.ref ?: return
        if (s.loadingMore || s.loading) return
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            val r = outcome { client.list(sessionId, ref, token, null, s.sortKey, s.sortDesc) }
            when (r) {
                is Outcome.Success -> _state.update {
                    if (it.ref != ref) it else it.copy(entries = it.entries + r.value.entries, nextToken = r.value.nextToken, loadingMore = false)
                }
                is Outcome.Failure -> _state.update { it.copy(loadingMore = false, message = UiMessage.Error(r.error)) }
            }
        }
    }

    private fun load(ref: String, pageToken: String?) {
        loadJob?.cancel()
        val s = _state.value
        _state.update { it.copy(loading = true, error = null, errorRef = null, loadingMore = false) }
        loadJob = scope.launch {
            val r = outcome { client.list(sessionId, ref, pageToken, null, s.sortKey, s.sortDesc) }
            when (r) {
                is Outcome.Success -> {
                    val l = r.value
                    restoring = false
                    onLocation(l.ref, if (l.parentRef == null) profile.name else (l.breadcrumbs.lastOrNull()?.name ?: profile.name))
                    _state.update {
                        it.copy(
                            ref = l.ref,
                            parentRef = l.parentRef,
                            breadcrumbs = l.breadcrumbs,
                            entries = l.entries,
                            nextToken = l.nextToken,
                            totalHint = l.totalHint,
                            loading = false,
                            selection = if (it.ref == l.ref) it.selection.filter { r -> l.entries.any { e -> e.ref == r } }.toSet() else emptySet(),
                            anchor = if (it.ref == l.ref) it.anchor else null,
                            lead = if (it.ref == l.ref) it.lead else null,
                            filter = if (it.ref == l.ref) it.filter else "",
                        )
                    }
                }
                is Outcome.Failure -> {
                    if (restoring && ref == initialRef) {
                        // The remembered folder is gone/unreachable: silently start from home.
                        restoring = false
                        _state.update { it.copy(message = UiMessage.RestoreFailed) }
                        open(homeRef)
                    } else {
                        _state.update { it.copy(loading = false, error = r.error, errorRef = ref) }
                    }
                }
            }
        }
    }

    fun setSort(key: String) {
        val s = _state.value
        val desc = if (s.sortKey == key) !s.sortDesc else false
        _state.update { it.copy(sortKey = key, sortDesc = desc) }
        s.ref?.let { open(it) }
    }

    // ------------------------------------------------------------------ selection

    fun toggleSelection(ref: String) = _state.update {
        it.copy(selection = if (ref in it.selection) it.selection - ref else it.selection + ref, anchor = ref, lead = ref)
    }

    fun selectOnly(ref: String) = _state.update { it.copy(selection = setOf(ref), anchor = ref, lead = ref) }

    fun selectAll(visible: List<Entry>) = _state.update { it.copy(selection = visible.map { e -> e.ref }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet(), anchor = null) }

    /** Click with modifiers: plain = only this, ctrl = toggle, shift = range from the anchor. */
    fun click(visible: List<Entry>, ref: String, ctrl: Boolean, shift: Boolean) {
        when {
            shift -> selectRange(visible, ref, additive = ctrl)
            ctrl -> toggleSelection(ref)
            else -> selectOnly(ref)
        }
    }

    fun selectRange(visible: List<Entry>, toRef: String, additive: Boolean = false) = _state.update { st ->
        val b = visible.indexOfFirst { it.ref == toRef }
        val a = visible.indexOfFirst { it.ref == (st.anchor ?: toRef) }.takeIf { it >= 0 } ?: b
        if (a < 0 || b < 0) st else {
            val range = visible.subList(minOf(a, b), maxOf(a, b) + 1).map { it.ref }.toSet()
            st.copy(selection = if (additive) st.selection + range else range, anchor = st.anchor ?: toRef, lead = toRef)
        }
    }

    /** Keyboard cursor: moves by [delta] rows (or to the start/end with [absolute]); [extend] = shift. */
    fun moveLead(visible: List<Entry>, delta: Int, extend: Boolean, absolute: Boolean = false) {
        if (visible.isEmpty()) return
        val st = _state.value
        val cur = visible.indexOfFirst { it.ref == st.lead }
        val target = when {
            absolute -> if (delta < 0) 0 else visible.lastIndex
            cur < 0 -> if (delta > 0) 0 else visible.lastIndex
            else -> (cur + delta).coerceIn(0, visible.lastIndex)
        }
        val ref = visible[target].ref
        if (extend) selectRange(visible, ref) else selectOnly(ref)
    }

    /** Sets the quick filter; selected items that the filter hides are dropped from the selection. */
    fun setFilter(text: String) = _state.update { st ->
        val f = text.trim()
        val keep = if (f.isEmpty()) st.selection else st.entries.filter { it.ref in st.selection && it.name.contains(f, ignoreCase = true) }.map { it.ref }.toSet()
        st.copy(filter = text, selection = keep, anchor = if (st.anchor in keep) st.anchor else null, lead = if (st.lead in keep) st.lead else null)
    }

    fun selectedEntries(): List<Entry> {
        val s = _state.value
        return s.entries.filter { it.ref in s.selection }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ mutations (return the error for inline display)

    suspend fun mkdir(name: String): ErrInfo? {
        val parent = _state.value.ref ?: return null
        _state.update { it.copy(busy = true) }
        val r = outcome { client.mkdir(sessionId, parent, name) }
        _state.update { it.copy(busy = false) }
        if (r is Outcome.Success) {
            _state.update { it.copy(message = UiMessage.FolderCreated(r.value.name)) }
            refresh()
        }
        return r.errorOrNull
    }

    suspend fun rename(entry: Entry, newName: String): ErrInfo? {
        _state.update { it.copy(busy = true) }
        val r = outcome { client.rename(sessionId, entry.ref, newName) }
        _state.update { it.copy(busy = false) }
        if (r is Outcome.Success) {
            _state.update { it.copy(message = UiMessage.Renamed(r.value.name)) }
            refresh()
        }
        return r.errorOrNull
    }

    suspend fun delete(entries: List<Entry>, recursive: Boolean): ErrInfo? {
        if (entries.isEmpty()) return null
        _state.update { it.copy(busy = true) }
        val r = outcome { client.delete(sessionId, entries.map { it.ref }, recursive) }
        _state.update { it.copy(busy = false) }
        when (r) {
            is Outcome.Success -> _state.update { it.copy(message = UiMessage.Deleted(r.value)) }
            is Outcome.Failure -> _state.update { it.copy(message = UiMessage.Error(r.error)) }
        }
        refresh()
        return r.errorOrNull
    }

    // ------------------------------------------------------------------ transfers

    /** Asks the platform for a destination, then queues the download of [entries]. */
    fun download(entries: List<Entry>) {
        if (entries.isEmpty()) return
        scope.launch {
            val target = files.chooseDownloadTarget(askDownloadLocation()) ?: return@launch
            transfers.enqueueDownload(
                sessionId, profile.name, entries.map { it.ref }, entries.map { it.name }, target, settings.value.collision,
            )
            _state.update { it.copy(message = UiMessage.DownloadQueued(entries.size)) }
        }
    }

    /** Opens a file in place (viewer / other app) instead of downloading it. */
    fun openFile(entry: Entry) {
        val o = opener
        if (o == null) download(listOf(entry)) else o.open(sessionId, entry, _state.value.entries)
    }

    fun uploadFiles() {
        scope.launch {
            try {
                files.pickUploadFiles(onPicked = { _state.update { it.copy(preparing = true) } })?.let { queueUpload(it) }
            } finally {
                _state.update { it.copy(preparing = false) }
            }
        }
    }

    fun uploadFolder() {
        scope.launch {
            try {
                files.pickUploadFolder(onPicked = { _state.update { it.copy(preparing = true) } })?.let { queueUpload(it) }
            } finally {
                _state.update { it.copy(preparing = false) }
            }
        }
    }

    /** Upload of already-known local paths (drag & drop). */
    fun uploadPaths(paths: List<String>, totalBytes: Long? = null) {
        if (paths.isNotEmpty()) queueUpload(UploadSelection(paths, totalBytes))
    }

    private fun queueUpload(sel: UploadSelection) {
        val parent = _state.value.ref
        if (parent == null || sel.paths.isEmpty()) {
            sel.release()
            return
        }
        val names = sel.paths.map { displayName(it) }
        transfers.enqueueUpload(sessionId, profile.name, sel, names, parent, settings.value.collision)
        _state.update { it.copy(message = UiMessage.UploadQueued(sel.paths.size)) }
    }

    /** Last segment of a *local* path, for display only. */
    private fun displayName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

    /** Opens the destination of a finished download (file manager). */
    fun openLocation(location: String) {
        scope.launch { files.openLocation(location) }
    }

    fun notifyPathCopied() = _state.update { it.copy(message = UiMessage.PathCopied) }

    // ------------------------------------------------------------------ favorites

    fun toggleFavoriteHere() {
        val s = _state.value
        val ref = s.ref ?: return
        val name = if (s.parentRef == null) profile.name else (s.breadcrumbs.lastOrNull()?.name ?: profile.name)
        toggleFavorite(ref, name)
    }

    fun toggleFavorite(ref: String, name: String) {
        val isFav = _state.value.favorites.any { it.ref == ref }
        scope.launch {
            val r = outcome { if (isFav) client.favoritesRemove(profile.id, ref) else client.favoritesAdd(profile.id, ref, name) }
            when (r) {
                is Outcome.Success -> _state.update { it.copy(favorites = r.value, message = UiMessage.FavoriteChanged(!isFav)) }
                is Outcome.Failure -> _state.update { it.copy(message = UiMessage.Error(r.error)) }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun close() {
        if (closed) return
        closed = true
        loadJob?.cancel()
        scope.launch {
            withContext(NonCancellable) { outcome { client.closeSession(sessionId) } }
        }
    }
}
