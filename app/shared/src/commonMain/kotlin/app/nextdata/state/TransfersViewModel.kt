package app.nextdata.state

import app.nextdata.engine.EngineClient
import app.nextdata.platform.DownloadTarget
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.StorageException
import app.nextdata.platform.StorageProblem
import app.nextdata.platform.UploadSelection
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.ProgressInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

enum class TransferDirection { Download, Upload }

sealed interface TransferStatus {
    data object Queued : TransferStatus
    data object Running : TransferStatus
    data object Done : TransferStatus
    /** [storage] is set when the failure was on the device side (destination folder), not the engine's. */
    data class Failed(val error: ErrInfo, val storage: StorageProblem? = null) : TransferStatus
    data object Cancelled : TransferStatus

    val isActive: Boolean get() = this is Queued || this is Running
}

data class TransferItem(
    val id: Long,
    val direction: TransferDirection,
    /** Names of the top-level items (as displayed to the user). */
    val names: List<String>,
    val profileName: String,
    val status: TransferStatus = TransferStatus.Queued,
    val progress: ProgressInfo? = null,
    /** Download: where it went (platform label). Upload: unused. */
    val destinationLabel: String? = null,
    /** Smoothed throughput of a running transfer, bytes/second (null until measurable). */
    val speedBps: Long? = null,
    /** Estimated seconds left (null when the total or the speed is unknown). */
    val etaSec: Long? = null,
)

/**
 * Smoothed transfer speed and ETA from progress samples. Pure and clock-agnostic: callers pass the elapsed
 * milliseconds, so it is unit-testable.
 */
class SpeedMeter(private val alpha: Double = 0.3) {
    private var lastMs = -1L
    private var lastBytes = 0L
    private var ema = 0.0

    /** Feeds a sample; returns (bytes/s, eta seconds) or nulls while there is not enough data. */
    fun sample(nowMs: Long, bytes: Long, total: Long?): Pair<Long?, Long?> {
        if (lastMs < 0 || bytes < lastBytes) {
            lastMs = nowMs; lastBytes = bytes; ema = 0.0
            return null to null
        }
        val dt = nowMs - lastMs
        if (dt >= 250) {
            val inst = (bytes - lastBytes) * 1000.0 / dt
            ema = if (ema == 0.0) inst else alpha * inst + (1 - alpha) * ema
            lastMs = nowMs; lastBytes = bytes
        }
        if (ema <= 0.0) return null to null
        val eta = total?.takeIf { it > bytes }?.let { ((it - bytes) / ema).toLong() }
        return ema.toLong() to eta
    }
}

/** Emitted when a transfer finished OK, so the browser of that session can refresh. */
data class TransferCompletion(
    val sessionId: String,
    val direction: TransferDirection,
    val parentRef: String?,
    val count: Int = 1,
    val destinationLabel: String? = null,
    val location: String? = null,
)

private sealed interface Spec {
    var sessionId: String
    val profileName: String

    /** Frees temporary resources (staging dirs, temp copies, descriptors). */
    fun release()

    class Download(
        override var sessionId: String,
        override val profileName: String,
        val refs: List<String>,
        val names: List<String>,
        var target: DownloadTarget,
        val collision: String,
    ) : Spec {
        override fun release() = target.discard()
    }

    class Upload(
        override var sessionId: String,
        override val profileName: String,
        val selection: UploadSelection,
        val parentRef: String,
        val collision: String,
    ) : Spec {
        override fun release() = selection.release()
    }
}

/**
 * Transfer queue: each job is one `transfer.download`/`transfer.upload` command (the engine handles
 * folders recursively and reports aggregated progress). Cancelling a job cancels its coroutine, which
 * makes the engine layer send `cancel(op)`.
 */
class TransfersViewModel(
    private val client: EngineClient,
    private val scope: CoroutineScope,
    maxParallel: Int = 2,
    private val files: PlatformFiles? = null,
) {
    private val _paused = MutableStateFlow(false)

    /** While paused, queued transfers wait (running ones finish): the engine has no pause primitive. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setPaused(value: Boolean) {
        _paused.value = value
    }

    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items.asStateFlow()

    private val _completions = MutableSharedFlow<TransferCompletion>(extraBufferCapacity = 16)
    val completions: SharedFlow<TransferCompletion> = _completions.asSharedFlow()

    private val permits = Semaphore(maxParallel)
    private val jobs = HashMap<Long, Job>()
    private val specs = HashMap<Long, Spec>()
    private var nextId = 1L

    val activeCount: Int get() = _items.value.count { it.status.isActive }

    /**
     * Returns once no queued/running transfer uses [sessionId]. Leaving the browser must never cut a transfer that
     * was started: the session is closed only after this returns.
     */
    suspend fun awaitIdle(sessionId: String) {
        _items.first { list -> list.none { it.status.isActive && specs[it.id]?.sessionId == sessionId } }
    }

    fun enqueueDownload(
        sessionId: String,
        profileName: String,
        refs: List<String>,
        names: List<String>,
        target: DownloadTarget,
        collision: String,
    ): Long {
        val id = nextId++
        val spec = Spec.Download(sessionId, profileName, refs, names, target, collision)
        specs[id] = spec
        _items.update { it + TransferItem(id, TransferDirection.Download, names, profileName, destinationLabel = target.label) }
        launchJob(id, spec)
        return id
    }

    // ---- copies done by the system (drag to the file manager): no engine job, only what the list shows

    private val externalMeters = HashMap<Long, Pair<SpeedMeter, kotlin.time.TimeMark>>()

    /** Adds a running item for a copy the file manager performs; [onCancel] runs when the user cancels it here. */
    fun beginExternal(profileName: String, names: List<String>, onCancel: () -> Unit): Long {
        val id = nextId++
        _items.update { it + TransferItem(id, TransferDirection.Download, names, profileName, status = TransferStatus.Running) }
        externalMeters[id] = SpeedMeter() to TimeSource.Monotonic.markNow()
        val job = Job()
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                onCancel()
                update(id) { if (it.status.isActive) it.copy(status = TransferStatus.Cancelled, speedBps = null, etaSec = null) else it }
            }
        }
        jobs[id] = job
        return id
    }

    fun reportExternal(id: Long, bytes: Long, total: Long?) {
        val (meter, start) = externalMeters[id] ?: return
        val (bps, eta) = meter.sample(start.elapsedNow().inWholeMilliseconds, bytes, total)
        update(id) { if (it.status.isActive) it.copy(progress = ProgressInfo(bytes = bytes, total = total), speedBps = bps ?: it.speedBps, etaSec = eta) else it }
    }

    /** The copy is over: [error] null = done (or [cancelled]). */
    fun endExternal(id: Long, error: ErrInfo?, cancelled: Boolean) {
        externalMeters.remove(id)
        (jobs[id] as? kotlinx.coroutines.CompletableJob)?.complete()
        update(id) {
            if (it.status != TransferStatus.Running) it
            else when {
                error != null -> it.copy(status = TransferStatus.Failed(error), speedBps = null, etaSec = null)
                cancelled -> it.copy(status = TransferStatus.Cancelled, speedBps = null, etaSec = null)
                else -> it.copy(status = TransferStatus.Done, speedBps = null, etaSec = null, progress = it.progress?.copy(bytes = it.progress.total ?: it.progress.bytes))
            }
        }
    }

    fun enqueueUpload(
        sessionId: String,
        profileName: String,
        selection: UploadSelection,
        displayNames: List<String>,
        parentRef: String,
        collision: String,
    ): Long {
        val id = nextId++
        val spec = Spec.Upload(sessionId, profileName, selection, parentRef, collision)
        specs[id] = spec
        _items.update { it + TransferItem(id, TransferDirection.Upload, displayNames, profileName) }
        launchJob(id, spec)
        return id
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    fun cancelAll() {
        jobs.values.toList().forEach { it.cancel() }
    }

    /** Re-runs a failed/cancelled job. Upload selections whose temp copies were released cannot be retried. */
    fun retry(id: Long) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status.isActive) return
        val spec = specs[id] ?: return
        val failure = item.status as? TransferStatus.Failed
        if (spec is Spec.Download && failure?.storage == StorageProblem.PermissionLost && files != null) {
            // Access to the destination folder was lost: ask for it again, then re-run.
            scope.launch {
                val fresh = files.chooseDownloadTarget(true) ?: return@launch
                spec.target = fresh
                update(id) { it.copy(status = TransferStatus.Queued, progress = null, destinationLabel = fresh.label, speedBps = null, etaSec = null) }
                launchJob(id, spec)
            }
            return
        }
        update(id) { it.copy(status = TransferStatus.Queued, progress = null, speedBps = null, etaSec = null) }
        launchJob(id, spec)
    }

    /**
     * The connection was re-established under a new session id: queued/failed transfers of the old session are
     * moved to the new one, and those that failed because the connection was lost are started again.
     */
    fun remapSession(old: String, new: String) {
        val moved = specs.filterValues { it.sessionId == old }.keys
        moved.forEach { specs[it]?.sessionId = new }
        _items.value.filter { it.id in moved }.forEach { item ->
            val st = item.status
            if (st is TransferStatus.Failed && st.error.isConnectionLost()) retry(item.id)
        }
    }

    fun clearFinished() {
        val finished = _items.value.filter { !it.status.isActive }.map { it.id }.toSet()
        finished.forEach { specs.remove(it)?.release(); jobs.remove(it) }
        _items.update { list -> list.filterNot { it.id in finished } }
    }

    fun remove(id: Long) {
        if (_items.value.firstOrNull { it.id == id }?.status?.isActive == true) return
        specs.remove(id)?.release()
        jobs.remove(id)
        _items.update { list -> list.filterNot { it.id == id } }
    }

    private fun update(id: Long, f: (TransferItem) -> TransferItem) =
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun launchJob(id: Long, spec: Spec) {
        jobs[id] = scope.launch {
            try {
                _paused.first { !it }
                permits.withPermit {
                    update(id) { it.copy(status = TransferStatus.Running) }
                    val meter = SpeedMeter()
                    val clock = TimeSource.Monotonic.markNow()
                    val onProgress: (ProgressInfo) -> Unit = { p ->
                        val (bps, eta) = meter.sample(clock.elapsedNow().inWholeMilliseconds, p.bytes, p.total)
                        update(id) { it.copy(progress = p, speedBps = bps ?: it.speedBps, etaSec = eta) }
                    }
                    when (spec) {
                        is Spec.Download -> runDownload(id, spec, onProgress)
                        is Spec.Upload -> runUpload(id, spec, onProgress)
                    }
                }
            } catch (e: CancellationException) {
                update(id) { it.copy(status = TransferStatus.Cancelled) }
                throw e
            }
        }
    }

    private suspend fun runDownload(id: Long, spec: Spec.Download, onProgress: (ProgressInfo) -> Unit) {
        val r = outcome { client.download(spec.sessionId, spec.refs, spec.target.destDir, spec.collision, onProgress) }
        when (r) {
            is Outcome.Failure -> update(id) { it.copy(status = TransferStatus.Failed(r.error), speedBps = null, etaSec = null) }
            is Outcome.Success -> {
                val fin = outcome { spec.target.finish(r.value) }
                if (fin is Outcome.Failure) {
                    val problem = (fin.cause as? StorageException)?.problem ?: StorageProblem.Other
                    val code = if (problem == StorageProblem.NoSpace) ErrorCodes.DISK_FULL else ErrorCodes.IO
                    update(id) { it.copy(status = TransferStatus.Failed(ErrInfo(code), storage = problem), speedBps = null, etaSec = null) }
                } else {
                    val target = spec.target
                    spec.release()
                    update(id) { it.copy(status = TransferStatus.Done, speedBps = null, etaSec = null, progress = it.progress?.copy(bytes = it.progress.total ?: it.progress.bytes)) }
                    _completions.tryEmit(TransferCompletion(spec.sessionId, TransferDirection.Download, null, spec.refs.size, target.label, target.location))
                }
            }
        }
    }

    private suspend fun runUpload(id: Long, spec: Spec.Upload, onProgress: (ProgressInfo) -> Unit) {
        // the engine does not know the total of an upload: use the size computed by the platform adapter (progress bar + ETA)
        val known = spec.selection.totalBytes
        val withTotal: (ProgressInfo) -> Unit = { p -> onProgress(if (p.total == null && known != null && known > 0) p.copy(total = known) else p) }
        val r = outcome { client.upload(spec.sessionId, spec.selection.paths, spec.parentRef, spec.collision, withTotal) }
        when (r) {
            is Outcome.Failure -> update(id) { it.copy(status = TransferStatus.Failed(r.error)) }
            is Outcome.Success -> {
                spec.release()
                update(id) { it.copy(status = TransferStatus.Done, speedBps = null, etaSec = null) }
                _completions.tryEmit(TransferCompletion(spec.sessionId, TransferDirection.Upload, spec.parentRef, spec.selection.paths.size))
            }
        }
    }
}

/** What the user sees about all running transfers together (one bar, one line). Pure, so it is unit-tested. */
data class TransfersSummary(
    val active: Int,
    val uploads: Int,
    val downloads: Int,
    /** 0..1 over the bytes of all active transfers; null while any of them has no known total. */
    val fraction: Float?,
    val itemsDone: Int,
    val itemsTotal: Int,
    val speedBps: Long?,
) {
    companion object {
        fun of(items: List<TransferItem>): TransfersSummary? {
            val act = items.filter { it.status.isActive }
            if (act.isEmpty()) return null
            val totals = act.map { it.progress?.total }
            val fraction = if (totals.all { it != null && it > 0 }) {
                val total = totals.sumOf { it!! }.toDouble()
                val done = act.sumOf { (it.progress?.bytes ?: 0L).coerceAtMost(it.progress?.total ?: 0L) }.toDouble()
                (done / total).toFloat().coerceIn(0f, 1f)
            } else null
            val speeds = act.mapNotNull { it.speedBps }
            return TransfersSummary(
                active = act.size,
                uploads = act.count { it.direction == TransferDirection.Upload },
                downloads = act.count { it.direction == TransferDirection.Download },
                fraction = fraction,
                itemsDone = act.sumOf { (it.progress?.itemsDone ?: 0).coerceAtMost(it.progress?.itemsTotal ?: 0) },
                itemsTotal = act.sumOf { it.progress?.itemsTotal ?: 0 },
                speedBps = speeds.takeIf { it.isNotEmpty() }?.sum(),
            )
        }
    }
}
