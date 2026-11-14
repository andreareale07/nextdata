package app.nextdata.desktop

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DpapiSecretStoreTest {
    private val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)

    private fun tmp() = Files.createTempDirectory("ndpmk").toFile()

    @Test
    fun createsThenReloadsSameKeyAndFileIsNotPlaintext() = runTest {
        if (!windows) return@runTest
        val dir = tmp()
        val store = DpapiSecretStore(File(dir, "pmk.dpapi"))
        val a = store.loadOrCreatePmk()
        assertEquals(32, a.size)
        val raw = File(dir, "pmk.dpapi").readBytes()
        assertTrue(raw.size > 32)
        assertTrue(raw.toList().windowed(32).none { it == a.toList() }, "key must not appear in clear on disk")
        val b = DpapiSecretStore(File(dir, "pmk.dpapi")).loadOrCreatePmk()
        assertContentEquals(a, b)
    }

    @Test
    fun corruptFileIsNeverOverwritten() = runTest {
        if (!windows) return@runTest
        val dir = tmp()
        val f = File(dir, "pmk.dpapi").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertFailsWith<PmkUnavailableException> { DpapiSecretStore(f).loadOrCreatePmk() }
        assertContentEquals(byteArrayOf(1, 2, 3, 4), f.readBytes())
    }

    @Test
    fun differentKeysPerInstallation() = runTest {
        if (!windows) return@runTest
        val a = DpapiSecretStore(File(tmp(), "p")).loadOrCreatePmk()
        val b = DpapiSecretStore(File(tmp(), "p")).loadOrCreatePmk()
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun fileUriConversion() {
        if (!windows) return
        assertEquals("C:/Users/a b/x.txt", fileUriToPath("file:///C:/Users/a%20b/x.txt")?.replace(java.io.File.separatorChar, (47).toChar()))
    }
}
