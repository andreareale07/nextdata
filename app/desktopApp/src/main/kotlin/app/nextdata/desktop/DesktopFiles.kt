package app.nextdata.desktop

import app.nextdata.platform.DownloadTarget
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.UploadSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** Native-looking AWT/Swing dialogs; always shown on the EDT. */
class DesktopFiles(private val frame: () -> Frame?) : PlatformFiles {
    private var lastDir: File? = null

    private suspend fun <T> edt(block: () -> T): T = withContext(Dispatchers.Swing) { block() }

    private fun fileDialog(title: String, mode: Int): FileDialog = FileDialog(frame(), title, mode).apply {
        lastDir?.let { directory = it.absolutePath }
    }

    override suspend fun pickUploadFiles(onPicked: () -> Unit): UploadSelection? = edt {
        val d = fileDialog("", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
        val files = d.files.orEmpty()
        if (files.isEmpty()) null else {
            lastDir = files.first().parentFile
            UploadSelection(files.map { it.absolutePath }, files.sumOf { it.length() })
        }
    }

    private fun chooseDir(): File? {
        val c = JFileChooser(lastDir).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false }
        return if (c.showOpenDialog(frame()) == JFileChooser.APPROVE_OPTION) c.selectedFile?.also { lastDir = it } else null
    }

    override suspend fun pickUploadFolder(onPicked: () -> Unit): UploadSelection? = edt { chooseDir()?.let { UploadSelection(listOf(it.absolutePath), it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }) } }

    private var lastDownload: File? = null

    override suspend fun chooseDownloadTarget(askEachTime: Boolean): DownloadTarget? = edt {
        val reuse = lastDownload?.takeIf { !askEachTime && it.isDirectory }
        val dir = reuse ?: chooseDir()
        dir?.also { lastDownload = it }?.let { DownloadTarget(it.absolutePath, it.name.ifEmpty { it.absolutePath }, location = it.absolutePath) }
    }

    /** Opens the folder in Explorer. */
    override suspend fun openLocation(location: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(location)
        val dir = if (f.isDirectory) f else f.parentFile
        runCatching {
            if (dir != null && java.awt.Desktop.isDesktopSupported()) { java.awt.Desktop.getDesktop().open(dir); true } else false
        }.getOrDefault(false)
    }

    override suspend fun saveBytes(suggestedName: String, bytes: ByteArray): Boolean {
        val target = edt {
            val d = fileDialog("", FileDialog.SAVE).apply { file = suggestedName; isVisible = true }
            val name = d.file ?: return@edt null
            File(d.directory, if (name.contains('.')) name else "$name.${suggestedName.substringAfterLast('.')}")
        } ?: return false
        withContext(Dispatchers.IO) { target.writeBytes(bytes) }
        return true
    }

    override suspend fun pickBytes(extensions: List<String>): ByteArray? {
        val f = edt {
            val d = fileDialog("", FileDialog.LOAD).apply {
                if (extensions.isNotEmpty()) setFilenameFilter { _, n -> extensions.any { n.endsWith(".$it", ignoreCase = true) } }
                isVisible = true
            }
            d.file?.let { File(d.directory, it) }
        } ?: return null
        return withContext(Dispatchers.IO) { if (f.length() > 16L * 1024 * 1024) null else f.readBytes() }
    }
}
