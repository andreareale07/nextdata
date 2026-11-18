package app.nextdata.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import app.nextdata.bridge.NativeBridge
import app.nextdata.protocol.Entry
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.state.BrowserViewModel
import app.nextdata.ui.EntryDragHost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drag of list entries.
 *
 * The drag is a real Windows drag started by the engine (OLE, virtual files): the entries are offered as files that
 * Explorer reads from the server while IT copies them, with its own cursor, progress window and cancel button. The app
 * copies nothing in advance and stays free for the whole copy, whatever the size.
 *
 * Inside the app a drop on a folder is a MOVE: while the drag runs [BrowserViewModel.dragging] is set, the folders of the
 * list accept it and handle the move themselves (the data of the drag is never asked for in that case).
 */
class DesktopEntryDrag : EntryDragHost {
    // Only the drag GESTURE is exclusive: once dropped, Explorer's copy goes on by itself and a new drag, or an upload,
    // can start at once (several copies may run at the same time).
    private val running = AtomicBoolean(false)
    private val tokens = java.util.concurrent.atomic.AtomicLong()


    @Composable
    override fun source(vm: BrowserViewModel, entries: () -> List<Entry>): Modifier {
        val current by rememberUpdatedState(entries)
        return Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { _ -> start(vm, current()) },
                onDrag = { _, _ -> },
            )
        }
    }

    private fun start(vm: BrowserViewModel, list: List<Entry>) {
        if (list.isEmpty() || !running.compareAndSet(false, true)) return
        vm.beginDrag(list)
        val token = tokens.incrementAndGet()
        // this drag gives the gesture back once, however many times it is told (its own copy may outlive a newer drag)
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) { traceDrag("drag gesture released"); vm.endDrag(); running.set(false) } }
        val refs = JsonArray(list.map { JsonPrimitive(it.ref) }).toString()
        Thread({
            try {
                val code = vm.runDragOut(
                    list.map { it.name }, { NativeBridge.dragOutProgress(token) }, { NativeBridge.dragOutCancel(token) }, ::failure,
                    onDropped = release,
                ) { session -> NativeBridge.dragOut(session, refs, token) }
                traceDrag("drag to the file manager ended: $code")
                failure(code)?.let(vm::dragOutFailed)
            } catch (t: Throwable) {
                traceDrag("drag to the file manager failed: $t")
                vm.dragOutFailed(ErrInfo.of(ErrorCodes.IO))
            } finally {
                release()
            }
        }, "nextdata-dragout").apply { isDaemon = true; start() }
    }

    internal companion object {
        /** What the user is told for a negative result of the native drag (zero and above are drop effects). */
        fun failure(code: Int): ErrInfo? = when (code) {
            -2 -> ErrInfo.of(ErrorCodes.SESSION_CLOSED)
            -3, -1 -> ErrInfo.of(ErrorCodes.IO)
            -4 -> ErrInfo.of(ErrorCodes.LIMIT_EXCEEDED)
            else -> null
        }
    }
}

/** Diagnostic trail (no names, no paths of the server) in the temp folder: `nextdata-drag.log`. */
internal fun traceDrag(msg: String) {
    runCatching { File(System.getProperty("java.io.tmpdir"), "nextdata-drag.log").appendText("${java.time.LocalTime.now()} $msg\n") }
}
