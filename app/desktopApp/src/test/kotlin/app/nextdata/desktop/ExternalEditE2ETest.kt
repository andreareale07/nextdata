package app.nextdata.desktop

import app.nextdata.bridge.JniEngine
import app.nextdata.platform.AppEnvironment
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.PlatformKind
import app.nextdata.platform.SecretProtection
import app.nextdata.platform.SecretStore
import app.nextdata.protocol.Collision
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProtocolId
import app.nextdata.state.AppController
import app.nextdata.state.OpenMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The patterns real desktop apps use to save, replayed on the REAL file system against the REAL engine DLL and the
 * REAL watcher (DesktopFiles.listLocal + OpenManager). The "other app" is simulated by plain file operations; no
 * program is launched. Enabled with ND_ENGINE_E2E=1.
 */
class ExternalEditE2ETest {
    /** Real files, but "open with another app" only records the path instead of launching anything. */
    private class RecordingFiles(private val real: DesktopFiles) : PlatformFiles by real {
        val opened = mutableListOf<String>()
        override suspend fun openExternal(path: String, edit: Boolean): Boolean {
            opened += path
            return true
        }
    }

    private class Env(val app: AppController, val files: RecordingFiles, val scope: CoroutineScope, val work: File) {
        lateinit var local: File
        lateinit var remoteRef: String
        lateinit var session: String

        suspend fun until(timeoutMs: Long = 15_000, what: String, cond: () -> Boolean) {
            try {
                withTimeout(timeoutMs) { while (!cond()) delay(100) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                error("timed out waiting for: $what (message=${app.opener.message.value})")
            }
        }

        /** What the server holds, read back through the ordinary download path. */
        suspend fun remoteText(): String {
            val dl = Files.createTempDirectory(work.toPath(), "dl").toFile()
            val p = app.client.download(session, listOf(remoteRef), dl.absolutePath, Collision.RENAME)
            return File(p.single()).readText()
        }
    }

    private fun env(content: String): Env? {
        if (System.getenv("ND_ENGINE_E2E") != "1") {
            println("ExternalEditE2ETest skipped (set ND_ENGINE_E2E=1)")
            return null
        }
        val dll = System.getenv("ND_ENGINE_DLL")?.let(::File)
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, "engine/dist/windows-x64/nd_engine.dll") }.first { it.isFile }
        System.load(dll.absolutePath)
        val work = Files.createTempDirectory("ndext").toFile()
        val files = RecordingFiles(DesktopFiles { null })
        val store = object : SecretStore {
            override suspend fun loadOrCreatePmk() = ByteArray(32) { (it * 3 + 1).toByte() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val env = AppEnvironment(File(work, "data").absolutePath, store, files, PlatformKind.Desktop, SecretProtection.Other)
        val app = AppController(env, JniEngine(), scope)
        val e = Env(app, files, scope, work)
        runBlocking {
            app.boot()
            val p = app.client.profilesSave(Profile(name = "E2E", protocol = ProtocolId.FAKE, host = "demo", auth = "none"), null)
            app.profiles.refresh()
            app.connect(p)
            e.until(what = "connected") { app.browser.value != null }
            val vm = app.browser.value!!
            e.session = vm.sessionId
            val src = File(work, "report.zip").apply { writeText(content) } // a type handled by other apps
            val up = app.client.upload(vm.sessionId, listOf(src.absolutePath), vm.homeRef, Collision.RENAME).single()
            e.remoteRef = up.ref
            vm.refresh()
            e.until(what = "listing") { vm.state.value.entries.any { it.name == "report.zip" } }
            vm.openFile(vm.state.value.entries.first { it.name == "report.zip" })
            e.until(what = "handed to the other app") { files.opened.isNotEmpty() }
            e.local = File(files.opened.single())
            assertEquals("report.zip", e.local.name)
            assertEquals(content, e.local.readText())
        }
        return e
    }

    private fun Env.finish() {
        app.opener.unwatchAll()
        scope.cancel()
    }

    @Test
    fun directWriteIsUploadedAndVerified() {
        val e = env("version 1") ?: return
        try {
            runBlocking {
                e.local.writeText("version 2, written in place")
                e.until(what = "uploaded") { e.app.opener.message.value is OpenMessage.ExternalSaved }
                assertEquals("version 2, written in place", e.remoteText())
            }
        } finally { e.finish() }
    }

    @Test
    fun tempFileThenRenameOverTheDocumentIsUploaded() {
        val e = env("version 1") ?: return
        try {
            runBlocking {
                // how Office/LibreOffice/Notepad++ save: write a temp file next to it, then replace the original
                val tmp = File(e.local.parentFile, "~wrl0001.tmp").apply { writeText("version 2 via temp+rename") }
                Files.move(tmp.toPath(), e.local.toPath(), StandardCopyOption.REPLACE_EXISTING)
                e.until(what = "uploaded") { e.app.opener.message.value is OpenMessage.ExternalSaved }
                assertEquals("version 2 via temp+rename", e.remoteText())
                assertFalse(File(e.local.parentFile, "~wrl0001.tmp").exists())
            }
        } finally { e.finish() }
    }

    @Test
    fun copyMadeByTheAppIsOfferedButNeverUploadedByItself() {
        val e = env("version 1") ?: return
        try {
            runBlocking {
                File(e.local.parentFile, "report (1).zip").writeText("a different file the app made")
                e.until(what = "copy detected") { e.app.opener.message.value is OpenMessage.CopyDetected }
                val m = e.app.opener.message.value as OpenMessage.CopyDetected
                assertEquals(listOf("report (1).zip"), m.names)
                delay(3_000)
                assertEquals("version 1", e.remoteText(), "a copy must not replace the document")
                // the user decides to use it
                e.app.opener.useCopy(m.sessionId, m.docId, m.names.first())
                e.until(what = "copy uploaded as the new version") { runBlocking { e.remoteText() } == "a different file the app made" }
            }
        } finally { e.finish() }
    }

    @Test
    fun truncatedFileIsNotUploadedUntilItHasContentAgain() {
        val e = env("precious content that must never be replaced by nothing") ?: return
        try {
            runBlocking {
                e.local.writeText("") // apps that save with "truncate, then write"
                delay(4_500)
                assertEquals("precious content that must never be replaced by nothing", e.remoteText(), "an empty file is never a save")
                e.local.writeText("the real new content")
                e.until(what = "uploaded after the app finished writing") { e.app.opener.message.value is OpenMessage.ExternalSaved }
                assertEquals("the real new content", e.remoteText())
            }
        } finally { e.finish() }
    }

    @Test
    fun returningWithoutChangesPointsToImportingTheVersionSavedElsewhere() {
        val e = env("version 1") ?: return
        try {
            runBlocking {
                e.app.opener.onForeground()
                e.until(what = "no-change hint") { e.app.opener.message.value is OpenMessage.NoChangeDetected }
                val m = e.app.opener.message.value as OpenMessage.NoChangeDetected
                // the app exported its result to another place: the user imports that file
                val exported = File(e.work, "exported-by-app.zip").apply { writeText("saved somewhere else") }
                e.app.opener.importVersion(e.session, m.docId, exported.absolutePath)
                e.until(what = "imported version uploaded") { runBlocking { e.remoteText() } == "saved somewhere else" }
                assertNotNull(e.app.opener.doc)
                assertTrue(e.local.exists())
            }
        } finally { e.finish() }
    }
}
