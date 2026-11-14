package app.nextdata

import app.nextdata.engine.FakeEngine
import app.nextdata.platform.AppEnvironment
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.PlatformKind
import app.nextdata.platform.SecretProtection
import app.nextdata.protocol.DocState
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.SaveResolution
import app.nextdata.state.AppController
import app.nextdata.state.BrowserViewModel
import app.nextdata.state.OpenManager
import app.nextdata.state.OpenMessage
import app.nextdata.state.ViewerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class OpenFiles : PlatformFiles {
    val base = TestFiles()
    var externalOk = true
    var pdfPages: Int? = null
    val opened = mutableListOf<Pair<String, Boolean>>()
    override suspend fun pickUploadFiles(onPicked: () -> Unit) = base.pickUploadFiles(onPicked)
    override suspend fun pickUploadFolder(onPicked: () -> Unit) = base.pickUploadFolder(onPicked)
    override suspend fun chooseDownloadTarget(askEachTime: Boolean) = base.chooseDownloadTarget(askEachTime)
    override suspend fun saveBytes(suggestedName: String, bytes: ByteArray) = base.saveBytes(suggestedName, bytes)
    override suspend fun pickBytes(extensions: List<String>) = base.pickBytes(extensions)
    override suspend fun pdfPageCount(path: String): Int? = pdfPages
    override suspend fun openExternal(path: String, edit: Boolean): Boolean {
        opened += path to edit
        return externalOk
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpenManagerTest {
    private class Rig(scope: TestScope) {
        val engine = FakeEngine(seedProfiles = true)
        val files = OpenFiles()
        val env = AppEnvironment("C:/data", TestSecretStore(), files, PlatformKind.Desktop, SecretProtection.Other)
        val app = AppController(env, engine, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)))
    }

    private suspend fun TestScope.connected(rig: Rig): BrowserViewModel {
        rig.app.boot()
        rig.app.connect(rig.app.profiles.state.value.profiles.first { it.id == "p2" })
        advanceUntilIdle()
        return assertNotNull(rig.app.browser.value)
    }

    private fun e(name: String, size: Long = 100, kind: String = EntryKind.FILE) = Entry(name, "r-$name", kind, size, null, name)

    @Test
    fun routingByTypeAndSize() {
        assertEquals(ViewerKind.Text, OpenManager.classify(e("notes.txt")))
        assertEquals(ViewerKind.Text, OpenManager.classify(e("data.csv")))
        assertEquals(ViewerKind.Text, OpenManager.classify(e("drawing.svg")))
        assertEquals(ViewerKind.Text, OpenManager.classify(e("Main.kt")))
        assertEquals(ViewerKind.External, OpenManager.classify(e("huge.log", size = OpenManager.TEXT_EDIT_MAX + 1)))
        assertEquals(ViewerKind.Image, OpenManager.classify(e("a.JPG")))
        assertEquals(ViewerKind.External, OpenManager.classify(e("a.jpg", size = OpenManager.IMAGE_MAX + 1)))
        assertEquals(ViewerKind.Pdf, OpenManager.classify(e("report.pdf")))
        assertEquals(ViewerKind.Media, OpenManager.classify(e("clip.mp4")))
        assertEquals(ViewerKind.Media, OpenManager.classify(e("song.FLAC")))
        assertEquals(ViewerKind.External, OpenManager.classify(e("unknown.xyz")))
        assertEquals(ViewerKind.Unsupported, OpenManager.classify(e("folder", kind = EntryKind.DIR)))
    }

    @Test
    fun tappingATextFileOpensItWithoutDownloading() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        vm.openFile(vm.state.value.entries.first { it.name == "readme.md" })
        advanceUntilIdle()
        val v = assertNotNull(rig.app.opener.viewer.value)
        assertEquals(ViewerKind.Text, v.kind)
        assertEquals(DocState.CLEAN, v.doc.state)
        assertTrue("open.prepare" in rig.engine.commandLog)
        assertFalse("transfer.download" in rig.engine.commandLog, "opening must not go through a user download")
    }

    @Test
    fun unsupportedTypesGoToAnotherAppAndAreMarkedOpen() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        vm.openFile(vm.state.value.entries.first { it.name == "archive.zip" })
        advanceUntilIdle()
        assertEquals(1, rig.files.opened.size)
        assertTrue(rig.files.opened.single().second, "external open is for editing")
        assertTrue("open.mark_open" in rig.engine.commandLog, "an app may now modify it: the cache must keep it")
        assertNull(rig.app.opener.viewer.value)
    }

    @Test
    fun hugeFilesAskBeforeBeingCopiedAndNothingIsDownloadedMeanwhile() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        val iso = vm.state.value.entries.first { it.name == "ubuntu.iso" }
        vm.openFile(iso)
        advanceUntilIdle()
        val p = assertNotNull(rig.app.opener.large.value)
        assertEquals("ubuntu.iso", p.entry.name)
        assertFalse("open.prepare" in rig.engine.commandLog, "no copy before the user agrees")
        rig.app.opener.dismissLarge()
        assertNull(rig.app.opener.large.value)
        assertFalse("open.prepare" in rig.engine.commandLog)
        vm.openFile(iso)
        advanceUntilIdle()
        rig.app.opener.confirmLarge()
        advanceUntilIdle()
        assertTrue("open.prepare" in rig.engine.commandLog)
        assertEquals(1, rig.files.opened.size)
    }

    @Test
    fun pdfIsShownInAppWhenTheDeviceCanRenderItOtherwiseHandedOver() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        val docs = vm.state.value.entries.first { it.name == "Documents" }
        vm.open(docs.ref)
        advanceUntilIdle()
        val pdf = vm.state.value.entries.first { it.name.endsWith(".pdf") }
        rig.files.pdfPages = 3
        vm.openFile(pdf)
        advanceUntilIdle()
        val v = assertNotNull(rig.app.opener.viewer.value)
        assertEquals(ViewerKind.Pdf, v.kind)
        assertEquals(3, v.pages)
        assertTrue(rig.files.opened.isEmpty())
        rig.app.opener.close()
        advanceUntilIdle()
        // password-protected / unsupported: another app
        rig.files.pdfPages = null
        vm.openFile(pdf)
        advanceUntilIdle()
        assertNull(rig.app.opener.viewer.value)
        assertEquals(1, rig.files.opened.size)
    }

    @Test
    fun mediaWithoutAnInAppPlayerBecomesALocalCopyInAnotherApp() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        vm.open(vm.state.value.entries.first { it.name == "Music" }.ref)
        advanceUntilIdle()
        vm.openFile(vm.state.value.entries.first { it.name == "track01.mp3" })
        advanceUntilIdle()
        assertNull(rig.app.opener.media.value)
        assertEquals(1, rig.files.opened.size)
    }

    @Test
    fun noAppAvailableFallsBackToDownloadOffer() = runTest {
        val rig = Rig(this)
        rig.files.externalOk = false
        val vm = connected(rig)
        vm.openFile(vm.state.value.entries.first { it.name == "archive.zip" })
        advanceUntilIdle()
        assertEquals(ViewerKind.Unsupported, rig.app.opener.viewer.value?.kind)
        assertEquals(OpenMessage.NoExternalApp, rig.app.opener.message.value)
    }

    @Test
    fun saveIsRefusedWhenNothingChangedAndDoesNotTouchTheServer() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        vm.openFile(vm.state.value.entries.first { it.name == "readme.md" })
        advanceUntilIdle()
        rig.app.opener.save()
        advanceUntilIdle()
        assertEquals(OpenMessage.NothingToSave, rig.app.opener.message.value)
        assertFalse("open.save" in rig.engine.commandLog)
    }

    @Test
    fun editedDocumentIsSavedAndReportedVerified() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        vm.openFile(vm.state.value.entries.first { it.name == "readme.md" })
        advanceUntilIdle()
        rig.engine.localEdits += rig.app.opener.viewer.value!!.doc.id
        rig.app.opener.save()
        advanceUntilIdle()
        assertEquals(OpenMessage.Saved(content = true), rig.app.opener.message.value)
        assertEquals(DocState.CLEAN, rig.app.opener.viewer.value?.doc?.state)
        assertEquals(listOf(SaveResolution.NORMAL), rig.engine.savedResolutions)
        assertFalse(rig.app.opener.saving.value)
    }

    @Test
    fun conflictIsNeverResolvedSilentlyAndHasThreeWaysOut() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        vm.openFile(vm.state.value.entries.first { it.name == "readme.md" })
        advanceUntilIdle()
        val opener = rig.app.opener
        rig.engine.localEdits += opener.viewer.value!!.doc.id
        rig.engine.saveFailure = ErrInfo(ErrorCodes.CONFLICT)
        opener.save()
        advanceUntilIdle()
        assertNotNull(opener.conflict.value, "the user must decide")
        assertEquals(DocState.CONFLICT, opener.doc.value?.state)
        assertTrue(rig.engine.savedResolutions.isEmpty(), "nothing was uploaded")
        opener.dismissConflict() // keep my version: nothing lost
        assertNull(opener.conflict.value)
        opener.save(SaveResolution.COPY)
        advanceUntilIdle()
        assertEquals(listOf(SaveResolution.COPY), rig.engine.savedResolutions)
        assertEquals(OpenMessage.SavedAsCopy, opener.message.value)
    }

    @Test
    fun uncertainAndFailedSavesAreReportedAndKeepTheDocument() = runTest {
        for (code in listOf(ErrorCodes.SAVE_UNCERTAIN, ErrorCodes.NETWORK)) {
            val rig = Rig(this)
            val vm = connected(rig)
            vm.openFile(vm.state.value.entries.first { it.name == "readme.md" })
            advanceUntilIdle()
            rig.engine.localEdits += rig.app.opener.viewer.value!!.doc.id
            rig.engine.saveFailure = ErrInfo(code)
            rig.app.opener.save()
            advanceUntilIdle()
            val m = assertIs<OpenMessage.Error>(rig.app.opener.message.value)
            assertEquals(code, m.error.code)
            assertNotNull(rig.app.opener.viewer.value, "the viewer (and the local copy) stays")
            assertTrue(rig.app.opener.doc.value!!.state in setOf(DocState.UNCERTAIN, DocState.SAVE_FAILED))
        }
    }

    @Test
    fun imagesOfTheFolderCanBeSwipedAndThePreviousOneIsReleased() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        vm.open(vm.state.value.entries.first { it.name == "Pictures" }.ref)
        advanceUntilIdle()
        val imgs = vm.state.value.entries.filter { it.name.endsWith(".jpg") }
        vm.openFile(imgs[0])
        advanceUntilIdle()
        val first = assertNotNull(rig.app.opener.viewer.value)
        assertEquals(ViewerKind.Image, first.kind)
        assertTrue(first.images.size >= 2)
        rig.app.opener.showImage(1)
        advanceUntilIdle()
        val second = assertNotNull(rig.app.opener.viewer.value)
        assertEquals(1, second.index)
        assertTrue(second.doc.id != first.doc.id)
        assertTrue("open.release" in rig.engine.commandLog)
        rig.engine.commandLog.clear()
        rig.app.opener.close()
        advanceUntilIdle()
        assertNull(rig.app.opener.viewer.value)
        assertTrue("open.release" in rig.engine.commandLog)
    }

    @Test
    fun unsavedWorkIsListedAndCanOnlyBeDroppedOnPurpose() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        assertTrue("open.recover" in rig.engine.commandLog, "connecting settles interrupted saves")
        vm.openFile(vm.state.value.entries.first { it.name == "readme.md" })
        advanceUntilIdle()
        val opener = rig.app.opener
        val id = opener.viewer.value!!.doc.id
        opener.refreshPending()
        assertTrue(opener.pending.value.isEmpty(), "a clean document is not pending")
        rig.engine.localEdits += id
        rig.engine.saveFailure = ErrInfo(ErrorCodes.NETWORK)
        opener.save()
        advanceUntilIdle()
        opener.refreshPending()
        assertEquals(listOf(id), opener.pending.value.map { it.id })
        // saving it later from the list works once connected
        opener.savePending(opener.viewer.value!!.sessionId, id)
        advanceUntilIdle()
        assertTrue(opener.pending.value.isEmpty())
        // dropping needs the explicit call (the UI asks for confirmation first)
        rig.engine.localEdits += id
        rig.engine.saveFailure = ErrInfo(ErrorCodes.NETWORK)
        opener.save()
        advanceUntilIdle()
        opener.refreshPending()
        assertEquals(1, opener.pending.value.size)
        opener.discardPending(id)
        advanceUntilIdle()
        assertTrue(opener.pending.value.isEmpty())
    }

    @Test
    fun failedOpenShowsTheErrorAndNoViewer() = runTest {
        val rig = Rig(this)
        val vm = connected(rig)
        rig.engine.failNext = ErrInfo(ErrorCodes.NETWORK)
        vm.openFile(vm.state.value.entries.first { it.name == "readme.md" })
        advanceUntilIdle()
        assertEquals(ErrorCodes.NETWORK, assertIs<OpenMessage.Error>(rig.app.opener.message.value).error.code)
        assertNull(rig.app.opener.viewer.value)
        assertNull(rig.app.opener.progress.value)
    }
}
