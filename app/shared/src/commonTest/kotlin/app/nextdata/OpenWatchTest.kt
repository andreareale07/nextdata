package app.nextdata

import app.nextdata.engine.FakeEngine
import app.nextdata.platform.AppEnvironment
import app.nextdata.platform.LocalStat
import app.nextdata.platform.PlatformFiles
import app.nextdata.platform.PlatformKind
import app.nextdata.platform.SecretProtection
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.SaveResolution
import app.nextdata.state.AppController
import app.nextdata.state.BrowserViewModel
import app.nextdata.state.OpenManager
import app.nextdata.state.OpenMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Files double whose folder listing is driven by the test (an app "saving" = the listing changes). */
private class WatchFiles : PlatformFiles {
    val base = TestFiles()
    var listing: List<LocalStat> = listOf(LocalStat("readme.md", 100, 1))
    val opened = mutableListOf<String>()
    var picked: String? = null
    override suspend fun pickUploadFiles(onPicked: () -> Unit) = base.pickUploadFiles(onPicked)
    override suspend fun pickUploadFolder(onPicked: () -> Unit) = base.pickUploadFolder(onPicked)
    override suspend fun chooseDownloadTarget(askEachTime: Boolean) = base.chooseDownloadTarget(askEachTime)
    override suspend fun saveBytes(suggestedName: String, bytes: ByteArray) = base.saveBytes(suggestedName, bytes)
    override suspend fun pickBytes(extensions: List<String>) = base.pickBytes(extensions)
    override suspend fun listLocal(dir: String) = listing
    override suspend fun openExternal(path: String, edit: Boolean): Boolean {
        opened += path
        return true
    }
    override suspend fun pickFileToImport() = picked
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpenWatchTest {
    private class Rig(scope: TestScope) {
        val engine = FakeEngine(seedProfiles = true)
        val files = WatchFiles()
        val env = AppEnvironment("C:/data", TestSecretStore(), files, PlatformKind.Desktop, SecretProtection.Other)
        val app = AppController(env, engine, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)))
    }

    private suspend fun TestScope.opened(rig: Rig): Pair<BrowserViewModel, String> {
        rig.app.boot()
        rig.app.connect(rig.app.profiles.state.value.profiles.first { it.id == "p2" })
        advanceUntilIdle()
        val vm = assertNotNull(rig.app.browser.value)
        // an unsupported type is handed to another app: that is what starts the watching
        vm.openFile(vm.state.value.entries.first { it.name == "archive.zip" })
        runCurrent()
        val id = rig.engine.localDocIds().single()
        return vm to id
    }

    /** Lets the polling loop notice a change and settle (poll + two settle rounds), without running forever. */
    private fun TestScope.letItSettle() {
        advanceTimeBy(OpenManager.POLL_MS + OpenManager.SETTLE_MS * 3)
        runCurrent()
    }

    private fun Rig.appSaves() {
        files.listing = files.listing.map { it.copy(size = it.size + 20, mtimeMs = it.mtimeMs + 1) }
    }

    @Test
    fun anotherAppsSaveIsUploadedAutomaticallyAndVerified() = runTest {
        val rig = Rig(this)
        val (_, id) = opened(rig)
        rig.engine.localEdits += id
        rig.appSaves()
        letItSettle()
        assertEquals(listOf(SaveResolution.NORMAL), rig.engine.savedResolutions)
        assertIs<OpenMessage.ExternalSaved>(rig.app.opener.message.value)
        rig.app.opener.unwatchAll()
    }

    @Test
    fun nothingHappensUntilTheFolderReallyChanges() = runTest {
        val rig = Rig(this)
        val (_, id) = opened(rig)
        rig.engine.localEdits += id // edited, but no write has been observed yet
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(rig.engine.savedResolutions.isEmpty())
        assertFalse("open.check" in rig.engine.commandLog, "no change signal, no work")
        rig.app.opener.unwatchAll()
    }

    @Test
    fun askModeDoesNotUploadUntilTheUserSaysSo() = runTest {
        val rig = Rig(this)
        val (_, id) = opened(rig)
        rig.app.settings.update { it.copy(autoSaveExternal = false) }
        rig.engine.localEdits += id
        rig.appSaves()
        letItSettle()
        val m = assertIs<OpenMessage.ExternalChanged>(rig.app.opener.message.value)
        assertTrue(rig.engine.savedResolutions.isEmpty())
        rig.app.opener.saveAsked(m.sessionId, m.docId)
        runCurrent()
        assertEquals(listOf(SaveResolution.NORMAL), rig.engine.savedResolutions)
        rig.app.opener.unwatchAll()
    }

    @Test
    fun copiesMadeByAnAppAreOfferedNeverUploadedOnTheirOwn() = runTest {
        val rig = Rig(this)
        val (_, id) = opened(rig)
        rig.engine.checkCopies += "readme (1).md"
        rig.files.listing = rig.files.listing + LocalStat("readme (1).md", 100, 5)
        letItSettle()
        val m = assertIs<OpenMessage.CopyDetected>(rig.app.opener.message.value)
        assertEquals(listOf("readme (1).md"), m.names)
        assertEquals(id, m.docId)
        assertTrue(rig.engine.savedResolutions.isEmpty())
        assertFalse("open.import" in rig.engine.commandLog)
        // the user chooses to use it
        rig.app.opener.useCopy(m.sessionId, m.docId, m.names.first())
        runCurrent()
        assertTrue("open.import" in rig.engine.commandLog)
        rig.app.opener.unwatchAll()
    }

    @Test
    fun backFromTheOtherAppWithoutChangesExplainsHowToImport() = runTest {
        val rig = Rig(this)
        opened(rig)
        rig.app.opener.onForeground()
        advanceTimeBy(OpenManager.POLL_MS + 10)
        runCurrent()
        val m = assertIs<OpenMessage.NoChangeDetected>(rig.app.opener.message.value)
        assertTrue(rig.engine.savedResolutions.isEmpty())
        // the user imports the version the app exported somewhere else
        rig.files.picked = "C:/Users/x/Downloads/readme-edited.md"
        rig.app.opener.pickAndImportFor(m.docId)
        runCurrent()
        assertTrue("open.import" in rig.engine.commandLog)
        assertEquals(listOf(SaveResolution.NORMAL), rig.engine.savedResolutions)
        rig.app.opener.unwatchAll()
    }

    @Test
    fun conflictFoundInTheBackgroundAsksTheUser() = runTest {
        val rig = Rig(this)
        val (_, id) = opened(rig)
        rig.engine.localEdits += id
        rig.engine.saveFailure = ErrInfo(ErrorCodes.CONFLICT)
        rig.appSaves()
        letItSettle()
        val c = assertNotNull(rig.app.opener.conflict.value)
        assertEquals(id, c.docId)
        assertTrue(rig.engine.savedResolutions.isEmpty(), "never overwritten silently")
        rig.app.opener.resolveConflict(SaveResolution.COPY)
        runCurrent()
        assertEquals(listOf(SaveResolution.COPY), rig.engine.savedResolutions)
        rig.app.opener.unwatchAll()
    }

    @Test
    fun saveFailureInTheBackgroundIsReportedAndTheDocumentStaysPending() = runTest {
        val rig = Rig(this)
        val (_, id) = opened(rig)
        rig.engine.localEdits += id
        rig.engine.saveFailure = ErrInfo(ErrorCodes.NETWORK)
        rig.appSaves()
        letItSettle()
        assertEquals(ErrorCodes.NETWORK, assertIs<OpenMessage.Error>(rig.app.opener.message.value).error.code)
        assertTrue(rig.engine.savedResolutions.isEmpty())
        rig.app.opener.unwatchAll()
    }
}
