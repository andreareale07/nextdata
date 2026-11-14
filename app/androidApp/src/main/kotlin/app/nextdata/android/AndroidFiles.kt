package app.nextdata.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.nextdata.platform.DownloadTarget
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.StorageException
import app.nextdata.platform.StorageProblem
import app.nextdata.platform.UploadSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Storage Access Framework adapter.
 *
 * - Upload: picked documents/folders are copied into a private cache dir under their real display name
 *   (the engine derives the remote name from the local file name, so a bare `/proc/self/fd/N` would lose it);
 *   the cache is wiped when the transfer finishes.
 * - Download: the engine writes into a private staging dir; afterwards the result is copied through the
 *   ContentResolver into the folder the user picked, and the staging dir is deleted. The permission on the
 *   picked folder is persisted (takePersistableUriPermission) so the folder can be reused after a restart;
 *   a lost permission surfaces as [StorageProblem.PermissionLost] (the UI offers to choose the folder again),
 *   a full disk as [StorageProblem.NoSpace].
 */
class AndroidFiles(private val activity: ComponentActivity) : PlatformFiles {
    private val ctx: Context get() = activity.applicationContext
    private var pending: CompletableDeferred<Any?>? = null
    private val prefs by lazy { ctx.getSharedPreferences("nextdata_files", Context.MODE_PRIVATE) }

    private val openMultiple = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { pending?.complete(it) }
    private val openTree = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pending?.complete(it) }
    private val openOne = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { pending?.complete(it) }
    private val createDoc = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { pending?.complete(it) }

    private suspend fun <T> launch(block: () -> Unit): T? {
        val d = CompletableDeferred<Any?>()
        pending?.cancel()
        pending = d
        withContext(Dispatchers.Main) { block() }
        @Suppress("UNCHECKED_CAST")
        return d.await() as T?
    }

    override suspend fun pickUploadFiles(onPicked: () -> Unit): UploadSelection? {
        val uris: List<Uri> = launch { openMultiple.launch(arrayOf("*/*")) } ?: return null
        if (uris.isEmpty()) return null
        onPicked()
        return withContext(Dispatchers.IO) {
            val dir = newTemp("upload")
            val paths = uris.map { copyDocument(it, dir).absolutePath }
            UploadSelection(paths, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) { dir.deleteRecursively() }
        }
    }

    override suspend fun pickUploadFolder(onPicked: () -> Unit): UploadSelection? {
        val tree: Uri = launch { openTree.launch(null) } ?: return null
        onPicked()
        return withContext(Dispatchers.IO) {
            val dir = newTemp("upload")
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = queryName(rootDoc) ?: "folder"
            val target = File(dir, safeName(name)).also { it.mkdirs() }
            copyTree(tree, DocumentsContract.getTreeDocumentId(tree), target)
            UploadSelection(listOf(target.absolutePath), dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) { dir.deleteRecursively() }
        }
    }

    /** The last download folder if the app still holds write access to it. */
    private fun rememberedTree(): Uri? {
        val s = prefs.getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(s)
        val held = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (held) uri else null
    }

    override suspend fun chooseDownloadTarget(askEachTime: Boolean): DownloadTarget? {
        var tree: Uri? = if (askEachTime) null else rememberedTree()
        if (tree == null) {
            val initial = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
            tree = launch<Uri> { openTree.launch(initial) } ?: return null
            // keep access across restarts and reuse the folder next time
            try {
                ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                prefs.edit().putString(KEY_TREE, tree.toString()).apply()
            } catch (t: SecurityException) {
                android.util.Log.w("NextData", "cannot persist tree permission", t)
            }
        }
        val picked: Uri = tree
        val staging = withContext(Dispatchers.IO) { newTemp("download") }
        val label = queryName(DocumentsContract.buildDocumentUriUsingTree(picked, DocumentsContract.getTreeDocumentId(picked))) ?: picked.lastPathSegment.orEmpty()
        return DownloadTarget(
            destDir = staging.absolutePath,
            label = label,
            finish = { paths ->
                withContext(Dispatchers.IO) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(picked, DocumentsContract.getTreeDocumentId(picked))
                    try {
                        // A permission that expired between picking and finishing is reported as such, not as a generic file error.
                        if (ctx.checkUriPermission(picked, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            throw StorageException(StorageProblem.PermissionLost)
                        }
                        paths.forEach { exportToTree(File(it), parent) }
                    } catch (e: StorageException) {
                        throw e
                    } catch (e: SecurityException) {
                        android.util.Log.e("NextData", "export denied", e)
                        prefs.edit().remove(KEY_TREE).apply()
                        throw StorageException(StorageProblem.PermissionLost, e)
                    } catch (e: IOException) {
                        android.util.Log.e("NextData", "export failed", e)
                        val full = e.message?.contains("ENOSPC", true) == true || e.message?.contains("No space", true) == true
                        throw StorageException(if (full) StorageProblem.NoSpace else StorageProblem.Other, e)
                    } catch (e: Exception) {
                        android.util.Log.e("NextData", "export failed", e)
                        throw StorageException(StorageProblem.Other, e)
                    }
                }
            },
            discard = { staging.deleteRecursively() },
            location = picked.toString(),
        )
    }

    override suspend fun openLocation(location: String): Boolean = withContext(Dispatchers.Main) {
        val tree = Uri.parse(location)
        val doc = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull() ?: return@withContext false
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(view)
            true
        } catch (e: Exception) {
            // no app handles the directory view: open the system file browser instead
            try {
                activity.startActivity(Intent("android.provider.action.BROWSE_DOCUMENT_ROOT").setData(doc).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    override suspend fun saveBytes(suggestedName: String, bytes: ByteArray): Boolean {
        val uri: Uri = launch { createDoc.launch(suggestedName) } ?: return false
        withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
        return true
    }

    override suspend fun pickBytes(extensions: List<String>): ByteArray? {
        val uri: Uri = launch { openOne.launch(arrayOf("*/*")) } ?: return null
        return withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 16 * 1024 * 1024) return@withContext null
                }
                out.toByteArray()
            }
        }
    }

    // ---------------------------------------------------------------- opened documents (workspace copies)

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

    /** Down-sampled decode (never more than about [maxPixels] in memory) with the EXIF orientation applied. */
    override suspend fun decodeImage(path: String, maxPixels: Int): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
            val deg = when (android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                val m = android.graphics.Matrix().apply { postRotate(deg) }
                bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    private val pdfLock = Any()

    /** Framework PdfRenderer: no extra library. Password-protected or damaged files give null (opened elsewhere). */
    override suspend fun pdfPageCount(path: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { r -> r.pageCount.takeIf { it > 0 } }
            }
        }.getOrNull()
    }

    override suspend fun renderPdfPage(path: String, index: Int, widthPx: Int): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(pdfLock) {
                android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { r ->
                        r.openPage(index).use { page ->
                            val w = widthPx.coerceIn(100, 2400)
                            val h = (w.toLong() * page.height / page.width).toInt().coerceIn(1, 6000)
                            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp.asImageBitmap()
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /** The user picks the file another app exported; it is copied under its real name into a private import folder. */
    override suspend fun pickFileToImport(): String? {
        val uri: Uri = launch { openOne.launch(arrayOf("*/*")) } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                File(ctx.cacheDir, "import").deleteRecursively() // earlier imports were already taken over by the engine
                copyDocument(uri, newTemp("import")).absolutePath
            }.getOrNull()
        }
    }

    /**
     * Hands the workspace copy to another app through the FileProvider. Edit first tries ACTION_EDIT (few apps
     * handle it), then ACTION_VIEW with the same grants. Granting write access does NOT guarantee that the other
     * app writes back to this file: NextData watches the file and offers "Import modified version" otherwise.
     */
    override suspend fun openExternal(path: String, edit: Boolean): Boolean = withContext(Dispatchers.Main) {
        val file = File(path)
        if (!file.isFile) return@withContext false
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        } catch (e: IllegalArgumentException) {
            return@withContext false // outside the shared workspace folder
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (edit) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        fun intent(action: String) = Intent(action).setDataAndType(uri, mime).addFlags(flags).apply {
            clipData = android.content.ClipData.newRawUri(file.name, uri) // lets the grant reach the app picked in the chooser
        }
        val attempts = if (edit) listOf(Intent.ACTION_EDIT, Intent.ACTION_VIEW) else listOf(Intent.ACTION_VIEW)
        for (action in attempts) {
            val i = intent(action)
            // only offer the chooser when some app really handles it (an empty chooser would say "no apps" and stop us)
            val handlers = try { activity.packageManager.queryIntentActivities(i, 0) } catch (e: Exception) { emptyList() }
            if (handlers.isEmpty()) continue
            try {
                activity.startActivity(Intent.createChooser(i, file.name))
                return@withContext true
            } catch (e: Exception) {
                android.util.Log.w("NextData", "cannot start $action", e)
            }
        }
        false
    }

    // ---------------------------------------------------------------- helpers

    private fun newTemp(kind: String): File = File(ctx.cacheDir, "$kind/${UUID.randomUUID()}").also { it.mkdirs() }

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\u0000]"), "_").ifBlank { "file" }

    private fun queryName(docUri: Uri): String? =
        try {
            ctx.contentResolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: SecurityException) {
            null
        }

    private fun copyDocument(uri: Uri, dir: File): File {
        val name = safeName(queryName(uri) ?: uri.lastPathSegment ?: "file")
        var target = File(dir, name)
        var i = 1
        while (target.exists()) target = File(dir, "${name.substringBeforeLast('.', name)} (${i++})${name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }}")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun copyTree(tree: Uri, docId: String, into: File) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val entries = mutableListOf<Triple<String, String, String>>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) entries += Triple(c.getString(0), c.getString(1), c.getString(2))
        }
        for ((id, name, mime) in entries) {
            val safe = safeName(name)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                copyTree(tree, id, File(into, safe).also { it.mkdirs() })
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                ctx.contentResolver.openInputStream(uri)?.use { input -> File(into, safe).outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun exportToTree(src: File, parentDoc: Uri) {
        val cr = ctx.contentResolver
        if (src.isDirectory) {
            val dir = DocumentsContract.createDocument(cr, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, src.name) ?: throw IOException("cannot create folder")
            src.listFiles().orEmpty().forEach { exportToTree(it, dir) }
        } else {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.extension.lowercase()) ?: "application/octet-stream"
            val doc = DocumentsContract.createDocument(cr, parentDoc, mime, src.name) ?: throw IOException("cannot create file")
            cr.openOutputStream(doc)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        }
    }

    private companion object {
        const val KEY_TREE = "download_tree"
    }
}
