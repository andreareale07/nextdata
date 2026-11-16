package app.nextdata.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.state.BrowserViewModel
import kotlinx.coroutines.launch

/**
 * Desktop: starts a drag of [entries] from a row. Dropped inside the app on a folder it MOVES them; dropped on the file
 * manager it COPIES them to the PC. Null where the platform has no such drag (phone).
 */
fun interface EntryDragHost {
    @Composable
    fun source(vm: BrowserViewModel, entries: () -> List<Entry>): Modifier
}

val LocalEntryDrag = staticCompositionLocalOf<EntryDragHost?> { null }

/** The entries to drag from [row]: the whole selection when the row is part of it, otherwise just the row. */
fun dragSet(row: Entry, selection: Set<String>, visible: List<Entry>): List<Entry> =
    if (row.ref in selection) visible.filter { it.ref in selection } else listOf(row)

/** A folder that accepts what the user is dragging from this list (move). Highlights while the pointer is over it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.folderDropTarget(destRef: String, destName: String, isFolder: Boolean, vm: BrowserViewModel): Modifier {
    if (!isFolder) return this
    val scope = rememberCoroutineScope()
    var hover by remember { mutableStateOf(false) }
    val target = remember(destRef) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hover = vm.dragging.value?.none { it.ref == destRef } == true }
            override fun onExited(event: DragAndDropEvent) { hover = false }
            override fun onEnded(event: DragAndDropEvent) { hover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover = false
                val d = vm.dragging.value ?: return false
                if (d.any { it.ref == destRef }) return false
                vm.endDrag()
                scope.launch { vm.moveInto(d, destRef, destName) }
                return true
            }
        }
    }
    val highlighted = if (hover) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier
    return this.then(highlighted).dragAndDropTarget(shouldStartDragAndDrop = { vm.dragging.value != null }, target = target)
}
