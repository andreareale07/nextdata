package app.nextdata

import app.nextdata.engine.FakeEngine
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.ProtocolId
import app.nextdata.state.AppController
import app.nextdata.state.AppSettings
import app.nextdata.state.Density
import app.nextdata.state.Outcome
import app.nextdata.state.RecentFolder
import app.nextdata.state.ThemeChoice
import app.nextdata.state.ViewMode
import app.nextdata.state.isConnectionLost
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

/** State-level tests for the UX features: settings persistence, last folder, recents, test connection, reconnect. */
@OptIn(ExperimentalCoroutinesApi::class)
class UxStateTest {
    private class Rig(scope: TestScope) {
        val engine = FakeEngine(seedProfiles = true)
        val app = AppController(testEnv(), engine, CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)))
    }

    @Test
    fun settingsRoundTripKeepsUnknownKeysAndNewFields() {
        val s = AppSettings(
            density = Density.Compact, viewMode = ViewMode.Grid, theme = ThemeChoice.Dark, onboarded = true,
            askDownloadLocation = false, navWidthDp = 333, detailsOpen = true,
            lastRefs = mapOf("p1" to "/x"), recents = listOf(RecentFolder("p1", "/x", "x")),
        )
        val raw = s.toRaw()
        val back = AppSettings.fromRaw(raw)
        assertEquals(Density.Compact, back.density)
        assertEquals(ViewMode.Grid, back.viewMode)
        assertEquals(ThemeChoice.Dark, back.theme)
        assertTrue(back.onboarded && back.detailsOpen && !back.askDownloadLocation)
        assertEquals(333, back.navWidthDp)
        assertEquals("/x", back.lastRefs["p1"])
        assertEquals(listOf(RecentFolder("p1", "/x", "x")), back.recents)
        // defaults are used for a settings object written by an older version
        val old = AppSettings.fromRaw(kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(null, old.density)
        assertEquals(ThemeChoice.System, old.theme)
        assertTrue(old.askDownloadLocation)
    }

    @Test
    fun lastFolderAndRecentsAreRememberedAndRestored() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        // a profile without a start folder: its last visited folder is restored
        val p = rig.app.profiles.state.value.profiles.first { it.id == "p2" }.copy(basePath = "")
        rig.app.connect(p)
        advanceUntilIdle()
        val vm = assertNotNull(rig.app.browser.value)
        val dir = vm.state.value.entries.first { it.kind == "dir" }
        vm.open(dir.ref)
        advanceUntilIdle()
        assertEquals(dir.ref, rig.app.settings.state.value.lastRefs["p2"])
        assertEquals(dir.ref, rig.app.settings.state.value.recents.first().ref)
        // reconnecting lands in the same folder
        rig.app.disconnect()
        rig.app.connect(p)
        advanceUntilIdle()
        assertEquals(dir.ref, rig.app.browser.value!!.state.value.ref)
    }

    @Test
    fun reconnectingKeepsTheFolderBeingBrowsedEvenWithAStartFolder() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        // e.g. coming back from the file picker after "Upload": the session is re-opened, the user must stay where they were
        val p = rig.app.profiles.state.value.profiles.first { it.id == "p2" }
        assertTrue(p.basePath.isNotBlank())
        rig.app.connect(p)
        advanceUntilIdle()
        val vm = assertNotNull(rig.app.browser.value)
        val dir = vm.state.value.entries.first { it.kind == "dir" }
        vm.open(dir.ref)
        advanceUntilIdle()
        rig.app.reconnect()
        advanceUntilIdle()
        assertEquals(dir.ref, rig.app.browser.value!!.state.value.ref)
    }

    @Test
    fun configuredStartFolderWinsOverLastVisitedFolder() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        // p2 has a start folder: remembering another folder must not override it on the next connection
        val p = rig.app.profiles.state.value.profiles.first { it.id == "p2" }
        assertTrue(p.basePath.isNotBlank())
        rig.app.connect(p)
        advanceUntilIdle()
        val vm = assertNotNull(rig.app.browser.value)
        val home = vm.homeRef
        val dir = vm.state.value.entries.first { it.kind == "dir" }
        vm.open(dir.ref)
        advanceUntilIdle()
        assertEquals(dir.ref, rig.app.settings.state.value.lastRefs["p2"])
        rig.app.disconnect()
        rig.app.connect(p)
        advanceUntilIdle()
        assertEquals(home, rig.app.browser.value!!.state.value.ref)
    }

    @Test
    fun testConnectionSavesProfileOnceAndKeepsIdOnFailure() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        val draft = Profile(name = "T", protocol = ProtocolId.WEBDAV, host = "h", username = "u")
        val ok = rig.app.testProfile(draft, ProfileSecrets(password = "pw"))
        if (ok is Outcome.Failure) kotlin.test.fail("save failed: " + ok.error.code + " " + ok.error.params)
        val first = (assertIs<Outcome.Success<*>>(ok).value as app.nextdata.state.ProfileTestResult)
        assertNull(first.error)
        assertTrue(first.profile.id.isNotEmpty())
        // testing again with the returned profile updates it instead of creating a duplicate
        val again = rig.app.testProfile(first.profile, null)
        val second = (assertIs<Outcome.Success<*>>(again).value as app.nextdata.state.ProfileTestResult)
        assertEquals(first.profile.id, second.profile.id)
        assertEquals(1, rig.app.profiles.state.value.profiles.count { it.name == "T" })
    }

    @Test
    fun connectionLostClassification() {
        assertTrue(ErrInfo(ErrorCodes.SESSION_CLOSED).isConnectionLost())
        assertTrue(ErrInfo(ErrorCodes.NETWORK).isConnectionLost())
        assertTrue(ErrInfo.of(ErrorCodes.IO, "detail" to "session closed").isConnectionLost())
        assertFalse(ErrInfo(ErrorCodes.IO).isConnectionLost())
        assertFalse(ErrInfo(ErrorCodes.PERMISSION_DENIED).isConnectionLost())
    }

    @Test
    fun filterDropsHiddenItemsFromSelection() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        rig.app.connect(rig.app.profiles.state.value.profiles.first { it.id == "p2" })
        advanceUntilIdle()
        val vm = assertNotNull(rig.app.browser.value)
        val all = vm.state.value.entries
        vm.selectAll(all)
        val keep = all.first()
        vm.setFilter(keep.name.take(3))
        assertTrue(vm.state.value.selection.all { ref -> all.first { it.ref == ref }.name.contains(keep.name.take(3), ignoreCase = true) })
        assertTrue(keep.ref in vm.state.value.selection)
    }

    @Test
    fun backLeavesAFailingListingInsteadOfTrappingTheUser() = runTest {
        val rig = Rig(this)
        rig.app.boot()
        rig.app.connect(rig.app.profiles.state.value.profiles.first { it.id == "p2" })
        advanceUntilIdle()
        val vm = assertNotNull(rig.app.browser.value)
        val dir = vm.state.value.entries.first { it.kind == "dir" }
        vm.open(dir.ref)
        advanceUntilIdle()
        rig.engine.failNext = ErrInfo(ErrorCodes.SESSION_CLOSED)
        vm.refresh()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        rig.app.back()
        assertNull(rig.app.browser.value)
    }
}
