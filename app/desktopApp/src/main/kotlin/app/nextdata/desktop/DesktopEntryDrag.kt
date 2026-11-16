package app.nextdata.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import app.nextdata.protocol.Entry
import app.nextdata.state.BrowserViewModel
import app.nextdata.ui.EntryDragHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Drag of list entries. Inside the app a drop on a folder is a MOVE (the app handles it from [BrowserViewModel.dragging],
 * the data below is never asked for). Dropped on the file manager, the system asks for the files at that moment and only
 * then they are copied from the server into a temporary folder: a drag that stays in the app downloads nothing.
 */
class DesktopEntryDrag : EntryDragHost {
    @OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    override fun source(vm: BrowserViewModel, entries: () -> List<Entry>): Modifier {
        val current by rememberUpdatedState(entries)
        return Modifier.dragAndDropSource { _ ->
            val list = current()
            if (list.isEmpty()) return@dragAndDropSource null
            vm.beginDrag(list)
            DragAndDropTransferData(
                transferable = DragAndDropTransferable(FilesOnDemand { stage(vm, list) }),
                supportedActions = listOf(DragAndDropTransferAction.Copy, DragAndDropTransferAction.Move),
                onTransferCompleted = { vm.endDrag() },
            )
        }
    }

    companion object {
        private const val PREFIX = "nextdata-drag-"

        private fun stage(vm: BrowserViewModel, list: List<Entry>): List<File>? {
            purgeOld()
            val dir = Files.createTempDirectory(PREFIX).toFile()
            val ok = runBlocking(Dispatchers.IO) { vm.stageForDrag(list, dir.absolutePath) }
            return if (ok) dir.listFiles()?.toList()?.takeIf { it.isNotEmpty() } else null
        }

        /** Staging folders of earlier drags (a day old or more) are removed. */
        private fun purgeOld() {
            runCatching {
                val tmp = File(System.getProperty("java.io.tmpdir"))
                val limit = System.currentTimeMillis() - 24L * 3600 * 1000
                tmp.listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) && f.lastModified() < limit }?.forEach { it.deleteRecursively() }
            }
        }
    }
}

/** Offers a list of files that is produced only when the drop target asks for it. */
internal class FilesOnDemand(private val produce: () -> List<File>?) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return produce() ?: throw IOException("the files could not be prepared")
    }
}
