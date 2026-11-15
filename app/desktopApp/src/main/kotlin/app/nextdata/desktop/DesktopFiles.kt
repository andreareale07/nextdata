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
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.swing.JFileChooser

private val NEVER_LAUNCH = setOf(
    "exe", "com", "bat", "cmd", "ps1", "psm1", "msi", "msp", "lnk", "scr", "js", "jse", "vbs", "vbe", "wsf", "wsh", "hta", "jar",
    "reg", "dll", "cpl", "pif", "gadget", "inf", "url", "appx", "msix", "application", "chm",
)

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

    override val supportsMixedPicker: Boolean get() = true

    /** One dialog for any number of files and folders (a folder is uploaded with everything inside it). */
    override suspend fun pickUploadMixed(onPicked: () -> Unit): UploadSelection? {
        val chosen = edt {
            val c = JFileChooser(lastDir).apply { fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES; isMultiSelectionEnabled = true }
            if (c.showOpenDialog(frame()) == JFileChooser.APPROVE_OPTION) c.selectedFiles.toList().also { l -> l.firstOrNull()?.parentFile?.let { lastDir = it } } else emptyList()
        }
        if (chosen.isEmpty()) return null
        onPicked()
        val total = withContext(Dispatchers.IO) { chosen.sumOf { f -> if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
        return UploadSelection(chosen.map { it.absolutePath }, total)
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

    // ---- opened documents (workspace copies)

    override suspend fun readFile(path: String, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.isFile || f.length() > maxBytes) null else runCatching { f.readBytes() }.getOrNull()
    }

    override suspend fun writeFile(path: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(path).writeBytes(bytes) }.isSuccess
    }

    override suspend fun listLocal(dir: String): List<app.nextdata.platform.LocalStat>? = withContext(Dispatchers.IO) {
        File(dir).listFiles()?.filter { it.isFile }?.map { app.nextdata.platform.LocalStat(it.name, it.length(), it.lastModified()) }?.sortedBy { it.name }
    }

    /** Skia decodes JPEG, PNG, WebP, GIF, BMP, ICO...; big images are scaled down to about [maxPixels]. */
    override suspend fun decodeImage(path: String, maxPixels: Int): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val img = org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes())
            val px = img.width.toLong() * img.height
            if (px <= maxPixels) return@runCatching img.toComposeImageBitmap()
            val k = Math.sqrt(maxPixels.toDouble() / px)
            val w = (img.width * k).toInt().coerceAtLeast(1)
            val h = (img.height * k).toInt().coerceAtLeast(1)
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
            surface.makeImageSnapshot().toComposeImageBitmap()
        }.getOrNull()
    }

    override suspend fun pickFileToImport(): String? = edt {
        val d = fileDialog("", FileDialog.LOAD).apply { isVisible = true }
        d.file?.let { File(d.directory, it).absolutePath }
    }

    /**
     * Opens the workspace copy with the default app. Programs and scripts are never launched this way (the user can
     * still download them): a file from a server must not run just because it was opened.
     */
    override suspend fun openExternal(path: String, edit: Boolean): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.isFile || f.extension.lowercase() in NEVER_LAUNCH) return@withContext false
        runCatching {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(f)
                true
            } else false
        }.getOrDefault(false)
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
