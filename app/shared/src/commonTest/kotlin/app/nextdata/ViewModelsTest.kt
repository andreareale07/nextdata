package app.nextdata

import app.nextdata.engine.FakeEngine
import app.nextdata.platform.UploadSelection
import app.nextdata.protocol.Collision
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.FolderStats
import app.nextdata.protocol.SpaceInfo
import app.nextdata.ui.SpaceLevel
import app.nextdata.ui.level
import app.nextdata.protocol.ExportOptions
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.Prompt
import app.nextdata.protocol.ProtocolId
import app.nextdata.protocol.SortKey
import app.nextdata.state.AppController
import app.nextdata.state.AppSettings
import app.nextdata.state.BootState
import app.nextdata.state.BrowserViewModel
import app.nextdata.state.ConnectionState
import app.nextdata.state.ExportOutcome
import app.nextdata.state.LanguageChoice
import app.nextdata.state.Outcome
import app.nextdata.state.Screen
import app.nextdata.state.TransferDirection
import app.nextdata.state.TransferItem
import app.nextdata.state.TransfersSummary
import app.nextdata.protocol.ProgressInfo
import app.nextdata.state.TransferStatus
import app.nextdata.state.UiMessage
import app.nextdata.state.errorOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    private class Rig(scope: TestScope, stepDelay: Long = 0) {
        val engine = FakeEngine(seedProfiles = true, transferStepDelayMs = stepDelay)
        val files = TestFiles()
        val app = AppController(testEnv(files), engine, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)))
    }

    private suspend fun TestScope.connected(rig: Rig, profileId: String = "p2"): BrowserViewModel {
        // p2 is WebDAV with a stored secret: no prompts at all.
        rig.app.boot()
        val p = rig.app.profiles.state.value.profiles.first { it.id == profileId }
        rig.app.connect(p)
        advanceUntilIdle()
        return assertNotNull(rig.app.browser.value)
    }

    // ------------------------------------------------------------------ boot / profiles

    @Test
    fun bootInitializesEngineAndLoadsProfiles() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        assertIs<BootState.Ready>(rig.app.boot.value)
        assertEquals(4, rig.app.profiles.state.value.profiles.size)
        val offered = rig.app.profiles.state.value.offeredProtocols().associateBy { it.id }
        assertTrue(offered.getValue(ProtocolId.SFTP).enabled)
        assertFalse(offered.getValue(ProtocolId.SMB).enabled)
        assertEquals(ErrorCodes.PROTOCOL_DISABLED, offered.getValue(ProtocolId.SMB).reasonCode)
    }

    @Test
    fun bootFailureIsReportedWithCode() = runTest {
        val rig = Rig(this)
        rig.engine.failNext = ErrInfo(ErrorCodes.NOT_INITIALIZED)
        rig.app.boot()
        val b = rig.app.boot.value
        assertIs<BootState.Failed>(b)
        assertEquals(ErrorCodes.NOT_INITIALIZED, b.error.code)
        // retry works
        rig.app.boot()
        assertIs<BootState.Ready>(rig.app.boot.value)
    }

    @Test
    fun saveAndDeleteProfileSecretsFlagged() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val vm = rig.app.profiles
        val r = vm.save(Profile(name = "New", protocol = ProtocolId.SFTP, host = "h", username = "u"), ProfileSecrets(password = "pw"))
        val saved = (r as Outcome.Success).value
        assertTrue(saved.id.isNotEmpty())
        assertTrue(saved.hasSecret)
        assertEquals(5, vm.state.value.profiles.size)
        // editing without secrets keeps the flag; the secret is never read back by the UI
        val edited = (vm.save(saved.copy(name = "Renamed"), null) as Outcome.Success).value
        assertTrue(edited.hasSecret)
        assertEquals("Renamed", vm.state.value.profiles.first { it.id == saved.id }.name)
        assertNull(vm.delete(saved.id))
        assertEquals(4, vm.state.value.profiles.size)
    }

    @Test
    fun saveInvalidProfileReturnsErrorCode() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val r = rig.app.profiles.save(Profile(name = "", protocol = ProtocolId.SFTP), null)
        assertEquals(ErrorCodes.INVALID_ARGUMENT, r.errorOrNull?.code)
    }

    // ------------------------------------------------------------------ connection + prompts

    @Test
    fun connectAsksToTrustNewHostAndOpensBrowser() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        rig.app.connect(rig.app.profiles.state.value.profiles.first { it.id == "p1" })
        advanceUntilIdle()
        assertIs<ConnectionState.Connecting>(rig.app.connection.value)
        val prompt = rig.app.prompts.queue.value.single()
        assertIs<Prompt.TrustHost>(prompt)
        assertNull(prompt.prior)
        rig.app.prompts.reply(accept = true)
        advanceUntilIdle()
        assertEquals(ConnectionState.Idle, rig.app.connection.value)
        assertNotNull(rig.app.browser.value)
        assertEquals(Screen.Browser, rig.app.currentScreen)
        assertTrue(rig.app.prompts.queue.value.isEmpty())
    }

    @Test
    fun rejectingTrustFailsWithHostKeyRejected() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        rig.app.connect(rig.app.profiles.state.value.profiles.first { it.id == "p1" })
        advanceUntilIdle()
        rig.app.prompts.reply(accept = false)
        advanceUntilIdle()
        val c = rig.app.connection.value
        assertIs<ConnectionState.Failed>(c)
        assertEquals(ErrorCodes.HOST_KEY_REJECTED, c.error.code)
        assertNull(rig.app.browser.value)
    }

    @Test
    fun changedHostKeyRequiresExplicitReplace() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val p4 = rig.app.profiles.state.value.profiles.first { it.id == "p4" }
        rig.app.connect(p4)
        advanceUntilIdle()
        val prompt = rig.app.prompts.queue.value.single() as Prompt.TrustHost
        assertNotNull(prompt.prior)
        // plain "accept" without replace is not enough
        rig.app.prompts.reply(accept = true, replace = false)
        advanceUntilIdle()
        assertEquals(ErrorCodes.HOST_KEY_CHANGED, (rig.app.connection.value as ConnectionState.Failed).error.code)
        // explicit replacement works
        rig.app.retryConnect()
        advanceUntilIdle()
        rig.app.prompts.reply(accept = true, replace = true)
        advanceUntilIdle()
        assertNotNull(rig.app.browser.value)
    }

    @Test
    fun missingSecretAsksForPassword() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val saved = (rig.app.profiles.save(Profile(name = "NoPw", protocol = ProtocolId.WEBDAV, host = "dav.example", tls = true), null) as Outcome.Success).value
        rig.app.connect(saved)
        advanceUntilIdle()
        val p = rig.app.prompts.queue.value.single()
        assertIs<Prompt.Secret>(p)
        assertEquals("password", p.kind)
        rig.app.prompts.reply(accept = true, value = "s3cret")
        advanceUntilIdle()
        assertNotNull(rig.app.browser.value)
    }

    @Test
    fun selfSignedCertificatePrompt() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val saved = (rig.app.profiles.save(
            Profile(name = "SS", protocol = ProtocolId.WEBDAV, host = "selfsigned.lan", tls = true, auth = "none"), null,
        ) as Outcome.Success).value
        rig.app.connect(saved)
        advanceUntilIdle()
        assertTrue(rig.app.prompts.queue.value.single() is Prompt.TrustCert)
        rig.app.prompts.reply(accept = false)
        advanceUntilIdle()
        assertEquals(ErrorCodes.TLS_UNTRUSTED, (rig.app.connection.value as ConnectionState.Failed).error.code)
    }

    @Test
    fun cancellingConnectSendsCancelToEngine() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        rig.app.connect(rig.app.profiles.state.value.profiles.first { it.id == "p1" })
        advanceUntilIdle()
        assertTrue(rig.app.prompts.queue.value.isNotEmpty())
        rig.app.cancelConnect()
        advanceUntilIdle()
        assertTrue("session.connect" in rig.engine.cancelledCommands)
        assertEquals(ConnectionState.Idle, rig.app.connection.value)
        assertTrue(rig.app.prompts.queue.value.isEmpty())
    }

    @Test
    fun authFailureAndNetworkErrorsSurfaceAsCodes() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val bad = (rig.app.profiles.save(Profile(name = "Bad", protocol = ProtocolId.WEBDAV, host = "fail.example", auth = "none"), null) as Outcome.Success).value
        rig.app.connect(bad)
        advanceUntilIdle()
        assertEquals(ErrorCodes.AUTH_FAILED, (rig.app.connection.value as ConnectionState.Failed).error.code)
        rig.app.dismissConnectionError()
        assertEquals(ConnectionState.Idle, rig.app.connection.value)
        val down = (rig.app.profiles.save(Profile(name = "Down", protocol = ProtocolId.WEBDAV, host = "down.example", auth = "none"), null) as Outcome.Success).value
        rig.app.connect(down)
        advanceUntilIdle()
        assertEquals(ErrorCodes.NETWORK, (rig.app.connection.value as ConnectionState.Failed).error.code)
    }

    @Test
    fun smbIsReportedAsDisabled() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val smb = (rig.app.profiles.save(Profile(name = "SMB", protocol = ProtocolId.SMB, host = "srv"), null) as Outcome.Success).value
        assertFalse(rig.app.isProtocolUsable(smb))
        rig.app.connect(smb)
        advanceUntilIdle()
        assertEquals(ErrorCodes.PROTOCOL_DISABLED, (rig.app.connection.value as ConnectionState.Failed).error.code)
    }

    // ------------------------------------------------------------------ browser

    @Test
    fun browserListsHomeAndNavigatesWithEngineRefsOnly() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        var s = b.state.value
        assertFalse(s.loading)
        assertNull(s.parentRef)
        assertEquals(1, s.breadcrumbs.size)
        assertEquals("Nextcloud", s.breadcrumbs.single().name)
        val docs = s.entries.first { it.name == "Documents" }
        assertEquals(EntryKind.DIR, docs.kind)
        b.open(docs.ref)
        advanceUntilIdle()
        s = b.state.value
        assertEquals(docs.ref, s.ref)
        assertEquals(2, s.breadcrumbs.size)
        assertTrue(s.canGoUp)
        assertTrue(s.entries.any { it.name == "notes.txt" })
        b.up()
        advanceUntilIdle()
        assertNull(b.state.value.parentRef)
        assertEquals(b.homeRef, b.state.value.ref)
    }

    @Test
    fun paginationLoadsMorePagesUntilToken_isNull() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        b.open(b.state.value.entries.first { it.name == "Pictures" }.ref)
        advanceUntilIdle()
        assertEquals(200, b.state.value.entries.size)
        assertNotNull(b.state.value.nextToken)
        b.loadMore(); advanceUntilIdle()
        assertEquals(400, b.state.value.entries.size)
        b.loadMore(); advanceUntilIdle()
        assertEquals(450, b.state.value.entries.size)
        assertNull(b.state.value.nextToken)
        b.loadMore(); advanceUntilIdle() // no-op without token
        assertEquals(450, b.state.value.entries.size)
    }

    @Test
    fun sortingTogglesDirectionAndReloadsFromFirstPage() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        b.open(b.state.value.entries.first { it.name == "Pictures" }.ref)
        advanceUntilIdle()
        b.loadMore(); advanceUntilIdle()
        b.setSort(SortKey.SIZE); advanceUntilIdle()
        var s = b.state.value
        assertEquals(SortKey.SIZE, s.sortKey)
        assertFalse(s.sortDesc)
        assertEquals(200, s.entries.size)
        assertEquals("IMG_0001.jpg", s.entries.first().name)
        b.setSort(SortKey.SIZE); advanceUntilIdle()
        s = b.state.value
        assertTrue(s.sortDesc)
        assertEquals("IMG_0450.jpg", s.entries.first().name)
    }

    @Test
    fun selectionOperations() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        val entries = b.state.value.entries
        b.toggleSelection(entries[0].ref)
        b.toggleSelection(entries[1].ref)
        assertTrue(b.state.value.selectionMode)
        assertEquals(2, b.selectedEntries().size)
        b.toggleSelection(entries[0].ref)
        assertEquals(setOf(entries[1].ref), b.state.value.selection)
        b.selectAll(entries)
        assertEquals(entries.size, b.state.value.selection.size)
        b.clearSelection()
        assertFalse(b.state.value.selectionMode)
        // refreshing the same folder keeps the selection of items that still exist
        b.selectOnly(entries[0].ref)
        b.refresh(); advanceUntilIdle()
        assertEquals(setOf(entries[0].ref), b.state.value.selection)
        // range / modifier selection
        b.clearSelection()
        b.click(entries, entries[1].ref, ctrl = false, shift = false)
        b.click(entries, entries[3].ref, ctrl = false, shift = true)
        assertEquals(entries.subList(1, 4).map { it.ref }.toSet(), b.state.value.selection)
        b.click(entries, entries[0].ref, ctrl = true, shift = false)
        assertEquals(4, b.state.value.selection.size)
        // keyboard cursor
        b.selectOnly(entries[0].ref)
        b.moveLead(entries, +2, extend = false)
        assertEquals(setOf(entries[2].ref), b.state.value.selection)
        b.moveLead(entries, +1, extend = true)
        assertEquals(setOf(entries[2].ref, entries[3].ref), b.state.value.selection)
        // navigating to another folder clears the selection and the filter
        b.setFilter("x")
        val dir = entries.first { it.kind == "dir" }
        b.open(dir.ref); advanceUntilIdle()
        assertTrue(b.state.value.selection.isEmpty())
        assertEquals("", b.state.value.filter)
    }

    @Test
    fun listingErrorKeepsPreviousListingAndRetryWorks() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        val before = b.state.value.entries
        val locked = before.first { it.name == "locked" }
        b.open(locked.ref); advanceUntilIdle()
        var s = b.state.value
        assertEquals(ErrorCodes.PERMISSION_DENIED, s.error?.code)
        assertEquals(locked.ref, s.errorRef)
        assertEquals(before, s.entries)
        assertFalse(s.loading)
        b.retry(); advanceUntilIdle()
        assertEquals(ErrorCodes.PERMISSION_DENIED, b.state.value.error?.code)
        b.dismissError()
        s = b.state.value
        assertNull(s.error)
        assertEquals(b.homeRef, s.ref)
    }

    @Test
    fun mkdirRenameDeleteReportErrorsAndRefresh() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        assertNull(b.mkdir("New folder"))
        advanceUntilIdle()
        assertTrue(b.state.value.entries.any { it.name == "New folder" })
        assertIs<UiMessage.FolderCreated>(b.state.value.message)
        assertEquals(ErrorCodes.ALREADY_EXISTS, b.mkdir("New folder")?.code)
        assertEquals(ErrorCodes.INVALID_ARGUMENT, b.mkdir("a/b")?.code)
        val created = b.state.value.entries.first { it.name == "New folder" }
        assertNull(b.rename(created, "Renamed"))
        advanceUntilIdle()
        assertTrue(b.state.value.entries.any { it.name == "Renamed" })
        assertEquals(ErrorCodes.ALREADY_EXISTS, b.rename(created, "Documents")?.code)
        val renamed = b.state.value.entries.first { it.name == "Renamed" }
        assertNull(b.delete(listOf(renamed), recursive = false))
        advanceUntilIdle()
        assertFalse(b.state.value.entries.any { it.name == "Renamed" })
        // non-empty folder without recursion is refused by the engine
        val docs = b.state.value.entries.first { it.name == "Documents" }
        assertEquals(ErrorCodes.INVALID_ARGUMENT, b.delete(listOf(docs), recursive = false)?.code)
        assertNull(b.delete(listOf(docs), recursive = true))
        advanceUntilIdle()
        assertFalse(b.state.value.entries.any { it.name == "Documents" })
    }

    @Test
    fun favoritesArePerProfileAndToggle() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        assertFalse(b.state.value.isFavoriteHere)
        b.toggleFavoriteHere(); advanceUntilIdle()
        assertTrue(b.state.value.isFavoriteHere)
        assertEquals(1, b.state.value.favorites.size)
        b.toggleFavoriteHere(); advanceUntilIdle()
        assertFalse(b.state.value.isFavoriteHere)
    }

    @Test
    fun leavingTheBrowserDoesNotCutARunningUpload() = runTest {
        val rig = Rig(this, stepDelay = 100)
        val b = connected(rig)
        rig.files.uploadFiles = UploadSelection(listOf("/cache/a.bin"), 1000)
        b.uploadFiles()
        advanceTimeBy(150) // the upload is running now
        assertTrue(rig.app.transfers.items.value.any { it.status.isActive })
        rig.app.disconnect()
        advanceTimeBy(50)
        assertFalse("session.close" in rig.engine.commandLog, "the session must stay open while the transfer runs")
        assertTrue(rig.app.transfers.items.value.none { it.status is TransferStatus.Cancelled || it.status is TransferStatus.Failed })
        advanceUntilIdle()
        assertTrue(rig.app.transfers.items.value.all { it.status is TransferStatus.Done }, "the transfer finishes")
        assertTrue("session.close" in rig.engine.commandLog, "then the session is closed")
    }

    @Test
    fun movingEntriesIntoAFolderKeepsNamesAndNeverReplaces() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        assertNull(b.mkdir("target"))
        advanceUntilIdle()
        assertNull(b.mkdir("other"))
        advanceUntilIdle()
        val target = b.state.value.entries.first { it.name == "target" }
        val other = b.state.value.entries.first { it.name == "other" }
        // an entry cannot go into itself, and a folder cannot go into its own subfolder
        assertNull(b.move(listOf(target), target))
        val before = b.state.value.entries.map { it.name }.toSet()
        assertNull(b.move(listOf(other), target))
        advanceUntilIdle()
        val after = b.state.value.entries.map { it.name }.toSet()
        assertEquals(before - "other", after, "the folder left this listing")
        assertIs<UiMessage.Moved>(b.state.value.message)
        // it really is in the target now
        b.open(target.ref)
        advanceUntilIdle()
        assertTrue(b.state.value.entries.any { it.name == "other" })
        // drag state is just state: begin/end
        b.beginDrag(listOf(other))
        assertEquals(listOf(other), b.dragging.value)
        b.endDrag()
        assertNull(b.dragging.value)
    }

    @Test
    fun transfersSummaryCombinesAllRunningTransfers() {
        fun item(id: Long, dir: TransferDirection, bytes: Long, total: Long?, done: Int, all: Int, speed: Long?) = TransferItem(
            id, dir, listOf("x"), "p", TransferStatus.Running, ProgressInfo(bytes, total, null, done, all), speedBps = speed,
        )
        assertNull(TransfersSummary.of(emptyList()))
        val s = TransfersSummary.of(listOf(
            item(1, TransferDirection.Upload, 50, 100, 1, 4, 10),
            item(2, TransferDirection.Upload, 150, 300, 2, 8, 20),
            TransferItem(3, TransferDirection.Upload, listOf("done"), "p", TransferStatus.Done),
        ))!!
        assertEquals(2, s.active)
        assertEquals(0.5f, s.fraction)
        assertEquals(3, s.itemsDone)
        assertEquals(12, s.itemsTotal)
        assertEquals(30L, s.speedBps)
        // an unknown total makes the bar indeterminate instead of lying
        assertNull(TransfersSummary.of(listOf(item(1, TransferDirection.Download, 5, null, 0, 0, null)))!!.fraction)
    }

    @Test
    fun disconnectClosesSessionAndReturnsToProfiles() = runTest {
        val rig = Rig(this)
        connected(rig)
        rig.app.disconnect()
        advanceUntilIdle()
        assertNull(rig.app.browser.value)
        assertEquals(Screen.Profiles, rig.app.currentScreen)
        assertTrue("session.close" in rig.engine.commandLog)
    }

    @Test
    fun backNavigationWalksUpThenDisconnects() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        b.open(b.state.value.entries.first { it.name == "Documents" }.ref); advanceUntilIdle()
        assertTrue(rig.app.back())
        advanceUntilIdle()
        assertEquals(b.homeRef, b.state.value.ref)
        assertEquals(Screen.Browser, rig.app.currentScreen)
        assertTrue(rig.app.back())
        advanceUntilIdle()
        assertEquals(Screen.Profiles, rig.app.currentScreen)
        assertFalse(rig.app.back())
        rig.app.navigate(Screen.Settings)
        assertTrue(rig.app.back())
        assertEquals(Screen.Profiles, rig.app.currentScreen)
    }

    // ------------------------------------------------------------------ transfers

    @Test
    fun downloadRunsWithProgressAndCompletes() = runTest {
        val rig = Rig(this, stepDelay = 100)
        val b = connected(rig)
        val docs = b.state.value.entries.first { it.name == "Documents" }
        b.download(listOf(docs))
        runCurrent()
        val item = rig.app.transfers.items.value.single()
        assertEquals(TransferDirection.Download, item.direction)
        assertEquals(listOf("Documents"), item.names)
        advanceTimeBy(150)
        assertEquals(TransferStatus.Running, rig.app.transfers.items.value.single().status)
        assertNotNull(rig.app.transfers.items.value.single().progress)
        advanceUntilIdle()
        assertEquals(TransferStatus.Done, rig.app.transfers.items.value.single().status)
        assertEquals(1.0f, rig.app.transfers.items.value.single().progress?.fraction)
        // finishing replaces the "queued" message with the completion message (with the destination to open)
        assertIs<UiMessage.DownloadDone>(b.state.value.message)
    }

    @Test
    fun cancellingATransferCancelsTheEngineOp() = runTest {
        val rig = Rig(this, stepDelay = 100)
        val b = connected(rig)
        b.download(listOf(b.state.value.entries.first { it.name == "archive.zip" }))
        runCurrent()
        advanceTimeBy(120)
        val id = rig.app.transfers.items.value.single().id
        rig.app.transfers.cancel(id)
        advanceUntilIdle()
        assertEquals(TransferStatus.Cancelled, rig.app.transfers.items.value.single().status)
        assertTrue("transfer.download" in rig.engine.cancelledCommands)
        rig.app.transfers.clearFinished()
        assertTrue(rig.app.transfers.items.value.isEmpty())
    }

    @Test
    fun failedTransferIsReportedWithLocalizableCode() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        var released = false
        rig.engine.failNext = null
        val entries = b.state.value.entries
        // make the next command fail: the download
        rig.engine.failNext = ErrInfo(ErrorCodes.DISK_FULL)
        rig.files.downloadTarget = app.nextdata.platform.DownloadTarget("C:/dl", "Downloads", discard = { released = true })
        b.download(listOf(entries.first { it.name == "readme.md" }))
        advanceUntilIdle()
        val st = rig.app.transfers.items.value.single().status
        assertIs<TransferStatus.Failed>(st)
        assertEquals(ErrorCodes.DISK_FULL, st.error.code)
        // temp resources are kept for a retry and released on removal
        assertFalse(released)
        rig.app.transfers.remove(rig.app.transfers.items.value.single().id)
        assertTrue(released)
    }

    @Test
    fun downloadTargetFinishRunsWithEngineResult() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        var finished: List<String>? = null
        rig.files.downloadTarget = app.nextdata.platform.DownloadTarget("C:/stage", "Phone", finish = { finished = it })
        b.download(listOf(b.state.value.entries.first { it.name == "readme.md" }))
        advanceUntilIdle()
        assertEquals(listOf("C:/stage/readme.md"), finished)
        assertEquals(TransferStatus.Done, rig.app.transfers.items.value.single().status)
    }

    @Test
    fun uploadUsesSettingsCollisionAndRefreshesListing() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        rig.app.settings.update { it.copy(collision = Collision.SKIP) }
        advanceUntilIdle()
        var released = false
        // readme.md already exists remotely; with "skip" it is not duplicated
        b.uploadPaths(listOf("C:\\Users\\me\\readme.md", "C:\\Users\\me\\fresh.txt"))
        advanceUntilIdle()
        assertTrue(rig.engine.commandLog.contains("transfer.upload"))
        val names = b.state.value.entries.map { it.name }
        assertTrue("fresh.txt" in names)
        assertEquals(1, names.count { it == "readme.md" })
        assertEquals(TransferDirection.Upload, rig.app.transfers.items.value.single().direction)
        assertEquals(listOf("readme.md", "fresh.txt"), rig.app.transfers.items.value.single().names)
        assertEquals(TransferStatus.Done, rig.app.transfers.items.value.single().status)
        released = true
        assertTrue(released)
    }

    @Test
    fun uploadWithFailCollisionReportsAlreadyExists() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        rig.app.settings.update { it.copy(collision = Collision.FAIL) }
        advanceUntilIdle()
        b.uploadPaths(listOf("/tmp/readme.md"))
        advanceUntilIdle()
        val st = rig.app.transfers.items.value.single().status
        assertIs<TransferStatus.Failed>(st)
        assertEquals(ErrorCodes.ALREADY_EXISTS, st.error.code)
    }

    @Test
    fun uploadSelectionIsReleasedAfterSuccess() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        var released = 0
        rig.files.uploadFiles = UploadSelection(listOf("/cache/a.bin"), release = { released++ })
        b.uploadFiles()
        advanceUntilIdle()
        assertEquals(1, released)
    }

    @Test
    fun uploadOnPhoneOpensTheMultiFilePickerAndUploadsOnce() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        var released = 0
        rig.files.uploadFiles = UploadSelection(listOf("/cache/a.bin", "/cache/b.bin"), 10, release = { released++ })
        b.upload()
        advanceUntilIdle()
        assertEquals(1, rig.app.transfers.items.value.size)
        assertEquals(1, released)
    }

    @Test
    fun uploadWithoutDirectoryReleasesSelection() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        // no browser: drop is ignored without error
        rig.app.uploadDropped(listOf("/x"))
        assertTrue(rig.app.transfers.items.value.isEmpty())
    }

    // ------------------------------------------------------------------ settings / export / import

    @Test
    fun settingsPersistThroughEngineAndKeepUnknownKeys() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        // an unknown key written by a newer engine
        rig.engine.execute("settings.set", buildJsonObject { put("settings", buildJsonObject { put("future", "x"); put("show_hidden", true) }) })
            .toList()
        rig.app.settings.load()
        assertTrue(rig.app.settings.state.value.showHidden)
        rig.app.settings.update { it.copy(language = LanguageChoice.Italian, collision = Collision.FAIL) }
        advanceUntilIdle()
        val raw = rig.app.client.settingsGet()
        assertEquals(JsonPrimitive("it"), raw["lang"])
        assertEquals(JsonPrimitive("fail"), raw["collision"])
        assertEquals(JsonPrimitive("x"), raw["future"])
        rig.app.settings.update { it.copy(language = LanguageChoice.System) }
        advanceUntilIdle()
        assertNull(rig.app.client.settingsGet()["lang"])
    }

    @Test
    fun appSettingsRoundTripAndDefaults() {
        val s = AppSettings.fromRaw(buildJsonObject { put("lang", "en"); put("collision", "bogus") })
        assertEquals(LanguageChoice.English, s.language)
        assertEquals(Collision.RENAME, s.collision)
        assertFalse(s.showHidden)
        assertEquals(LanguageChoice.System, AppSettings.fromRaw(buildJsonObject { }).language)
    }

    @Test
    fun exportThenImportPreviewShowsConflictsAndApplyRefreshes() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val svc = rig.app.profileTransfer
        val exp = svc.export("pass-phrase", ExportOptions(), "profiles.ndprofile")
        assertEquals(ExportOutcome.Saved, (exp as Outcome.Success).value)
        val (name, bytes) = assertNotNull(rig.files.saved)
        assertEquals("profiles.ndprofile", name)
        assertTrue(bytes.isNotEmpty())
        // wrong passphrase
        assertEquals(ErrorCodes.PASSPHRASE_INVALID, svc.preview(bytes, "nope").errorOrNull?.code)
        // garbage file
        assertEquals(ErrorCodes.IMPORT_INVALID, svc.preview(byteArrayOf(1, 2, 3), "pass-phrase").errorOrNull?.code)
        // delete one profile, preview: 4 profiles, 3 conflicts
        rig.app.profiles.delete("p3")
        val preview = (svc.preview(bytes, "pass-phrase") as Outcome.Success).value
        assertFalse(preview.applied)
        assertEquals(4, preview.profiles.size)
        assertEquals(3, preview.conflicts.size)
        assertEquals(3, rig.app.profiles.state.value.profiles.size)
        val applied = (svc.apply(bytes, "pass-phrase") as Outcome.Success).value
        assertTrue(applied.applied)
        assertEquals(4, rig.app.profiles.state.value.profiles.size)
    }

    // ------------------------------------------------------------------ copy, properties, space

    @Test
    fun copyingQueuesATransferKeepsTheOriginalsAndNeverReplaces() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        assertNull(b.mkdir("target"))
        advanceUntilIdle()
        val target = b.state.value.entries.first { it.name == "target" }
        val readme = b.state.value.entries.first { it.name == "readme.md" }
        val docs = b.state.value.entries.first { it.name == "Documents" }
        // one command copies files and folders together
        b.copyInto(listOf(readme, docs), target.ref, "target")
        advanceUntilIdle()
        val item = rig.app.transfers.items.value.single()
        assertEquals(TransferDirection.Copy, item.direction)
        assertEquals(TransferStatus.Done, item.status)
        assertEquals(listOf("readme.md", "Documents"), item.names)
        assertIs<UiMessage.Copied>(b.state.value.message)
        // the originals are still where they were
        assertTrue(b.state.value.entries.any { it.name == "readme.md" } && b.state.value.entries.any { it.name == "Documents" })
        // and the copies are really there, folder contents included
        val inTarget = (b.listFolders(target.ref) as Outcome.Success).value
        assertEquals(listOf("Documents"), inTarget.folders.map { it.name })
        val copiedDocs = inTarget.folders.single()
        assertEquals(4L, (b.folderStats(listOf(copiedDocs)) as Outcome.Success).value.files)
        // a second copy into the same folder never replaces: the names are numbered
        b.copyInto(listOf(readme), target.ref, "target")
        advanceUntilIdle()
        b.open(target.ref)
        advanceUntilIdle()
        assertEquals(setOf("Documents", "readme.md", "readme (1).md"), b.state.value.entries.map { it.name }.toSet())
    }

    @Test
    fun copyingAFolderIntoItselfFailsAsATransferWithTheEngineCode() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        val docs = b.state.value.entries.first { it.name == "Documents" }
        b.copyInto(listOf(docs), docs.ref, "Documents")
        advanceUntilIdle()
        val st = rig.app.transfers.items.value.single().status
        assertIs<TransferStatus.Failed>(st)
        assertEquals(ErrorCodes.INVALID_ARGUMENT, st.error.code)
    }

    @Test
    fun propertiesCountFilesFoldersAndBytesRecursively() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        val docs = b.state.value.entries.first { it.name == "Documents" }
        val readme = b.state.value.entries.first { it.name == "readme.md" }
        val one = (b.folderStats(listOf(docs)) as Outcome.Success).value
        assertEquals(FolderStats(files = 4, dirs = 0, bytes = 1_250_000L + 48_213 + 912 + 88_020, links = 0, truncated = false), one)
        // a mix of a folder and a file adds up; progress is reported while counting
        var seen = 0
        val both = (b.folderStats(listOf(docs, readme)) { seen = it.itemsDone } as Outcome.Success).value
        assertEquals(5L, both.files)
        assertEquals(one.bytes + 2_048, both.bytes)
        assertTrue(seen > 0)
        // links and special files are counted apart, never followed
        val root = (b.folderStats(b.state.value.entries.filter { it.name == "latest" || it.name == "device0" }) as Outcome.Success).value
        assertEquals(2L, root.links)
        assertEquals(0L, root.files)
        // a missing entry is a plain error
        val gone = docs.copy(ref = "n999999")
        assertEquals(ErrorCodes.NOT_FOUND, b.folderStats(listOf(gone)).errorOrNull?.code)
    }

    @Test
    fun theFolderListForThePickerHasOnlyFolders() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        val l = (b.listFolders(b.homeRef) as Outcome.Success).value
        assertNull(l.parentRef)
        assertTrue(l.folders.isNotEmpty() && l.folders.all { it.kind == EntryKind.DIR })
        assertTrue("Documents" in l.folders.map { it.name } && "readme.md" !in l.folders.map { it.name })
        // creating a folder from the picker works and is listed at once
        assertNull(b.createFolderIn(l.ref, "from-picker"))
        val again = (b.listFolders(l.ref) as Outcome.Success).value
        assertTrue("from-picker" in again.folders.map { it.name })
        assertEquals(ErrorCodes.ALREADY_EXISTS, b.createFolderIn(l.ref, "from-picker")?.code)
    }

    @Test
    fun spaceIsKnownAfterConnectingAndFollowsWhatIsCopied() = runTest {
        val rig = Rig(this)
        val b = connected(rig)
        val before = assertNotNull(b.state.value.space)
        assertEquals(16_000_000_000L, before.total)
        assertEquals(before.total!! - before.used!!, before.free)
        assertNull(b.mkdir("target"))
        advanceUntilIdle()
        val target = b.state.value.entries.first { it.name == "target" }
        val docs = b.state.value.entries.first { it.name == "Documents" }
        b.copyInto(listOf(docs), target.ref, "target")
        advanceUntilIdle()
        val after = assertNotNull(b.state.value.space)
        assertEquals(before.used!! + 1_387_145L, after.used, "a finished copy refreshes the space")
        // a server that does not say: no value, and a failing request keeps the last one
        rig.engine.failNext = ErrInfo(ErrorCodes.NETWORK)
        b.refreshSpace()
        advanceUntilIdle()
        assertEquals(after, b.state.value.space)
    }

    @Test
    fun usedShareAndWarningLevelsFollowTheNumbers() {
        assertEquals(0.5f, SpaceInfo(total = 100, used = 50).usedFraction)
        assertEquals(0.75f, SpaceInfo(total = 100, free = 25).usedFraction, "free is enough when the total is known")
        assertNull(SpaceInfo(free = 25).usedFraction, "no total, no bar")
        assertNull(SpaceInfo(total = 0, used = 0).usedFraction)
        assertEquals(SpaceLevel.Normal, SpaceInfo(total = 100, used = 84).level())
        assertEquals(SpaceLevel.Warning, SpaceInfo(total = 100, used = 85).level())
        assertEquals(SpaceLevel.Warning, SpaceInfo(total = 100, used = 94).level())
        assertEquals(SpaceLevel.Critical, SpaceInfo(total = 100, used = 95).level())
        assertEquals(SpaceLevel.Normal, SpaceInfo(free = 1).level(), "unknown share: no alarm")
    }
}
