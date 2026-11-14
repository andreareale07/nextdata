package app.nextdata.desktop

import app.nextdata.bridge.JniEngine
import app.nextdata.bridge.NativeLoader
import app.nextdata.engine.EngineClient
import app.nextdata.platform.systemLanguageTag
import app.nextdata.protocol.Collision
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProtocolId
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test against the REAL engine DLL and its in-memory `fake` provider.
 * Enabled with ND_ENGINE_E2E=1 (DLL taken from ND_ENGINE_DLL or ../engine/dist/windows-x64).
 */
@OptIn(ExperimentalEncodingApi::class)
class EngineE2ETest {
    @Test
    fun listMkdirRenameUploadDownloadDelete() {
        if (System.getenv("ND_ENGINE_E2E") != "1") {
            println("EngineE2ETest skipped (set ND_ENGINE_E2E=1)")
            return
        }
        val dll = System.getenv("ND_ENGINE_DLL")?.let(::File)
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, "engine/dist/windows-x64/nd_engine.dll") }.first { it.isFile }
        System.setProperty("nd.dll", dll.absolutePath)
        // load by absolute path through the production loader
        val loaderEnv = dll
        System.load(loaderEnv.absolutePath)
        val client = EngineClient(JniEngine())
        val data = Files.createTempDirectory("nde2e").toFile()
        val work = Files.createTempDirectory("nde2ework").toFile()

        runBlocking {
            val init = client.init(File(data, "data").absolutePath, Base64.encode(ByteArray(32) { (it * 7).toByte() }))
            println("engine version=${init.version} protocols=${init.protocols}")
            assertTrue(init.protocols.any { it.id == ProtocolId.FAKE && it.enabled }, "fake provider must be enabled")

            val p = client.profilesSave(Profile(name = "E2E", protocol = ProtocolId.FAKE, host = "demo", auth = "none"), null)
            assertTrue(p.id.isNotEmpty())
            assertTrue(client.profilesList().any { it.id == p.id })

            val s = client.connect(p.id)
            val home = client.list(s.session, s.homeRef)
            println("home entries=" + home.entries.map { it.name + ":" + it.kind })
            assertTrue(home.entries.isNotEmpty(), "demo data expected")
            assertTrue(home.breadcrumbs.isNotEmpty())

            val folder = client.mkdir(s.session, s.homeRef, "E2E Folder")
            assertEquals(EntryKind.DIR, folder.kind)
            val renamed = client.rename(s.session, folder.ref, "E2E Renamed")
            assertEquals("E2E Renamed", renamed.name)
            assertTrue(client.list(s.session, s.homeRef).entries.any { it.name == "E2E Renamed" })

            val src = File(work, "hello.txt").apply { writeText("hello nextdata") }
            val up = client.upload(s.session, listOf(src.absolutePath), renamed.ref, Collision.RENAME)
            assertEquals(1, up.size)
            val inside = client.list(s.session, renamed.ref)
            assertEquals(listOf("hello.txt"), inside.entries.map { it.name })
            assertEquals(renamed.ref, inside.ref)
            assertTrue(inside.parentRef != null)

            val dest = File(work, "dl").apply { mkdirs() }
            val progress = mutableListOf<Long>()
            val paths = client.download(s.session, listOf(inside.entries[0].ref), dest.absolutePath, Collision.RENAME) { progress += it.bytes }
            println("downloaded=$paths progressEvents=${progress.size}")
            assertEquals(1, paths.size)
            assertEquals("hello nextdata", File(paths[0]).readText())

            assertTrue(client.delete(s.session, listOf(renamed.ref), recursive = true) >= 1)
            assertTrue(client.list(s.session, s.homeRef).entries.none { it.name == "E2E Renamed" })
            client.closeSession(s.session)
            client.profilesDelete(p.id)
        }
    }

    /**
     * Real SFTP server (infra/test-env/sftp_server.py). Enabled with ND_ENGINE_E2E_SFTP="host|port|user|pass";
     * covers connect with trust-on-first-use prompt, upload of a multi-chunk file and listing.
     */
    @Test
    fun sftpUploadListDownload() {
        val cfg = System.getenv("ND_ENGINE_E2E_SFTP")?.split("|") ?: run { println("sftp E2E skipped"); return }
        if (System.getenv("ND_ENGINE_E2E") != "1") return
        val dll = System.getenv("ND_ENGINE_DLL")?.let(::File)
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, "engine/dist/windows-x64/nd_engine.dll") }.first { it.isFile }
        System.load(dll.absolutePath)
        val client = EngineClient(JniEngine())
        val data = Files.createTempDirectory("nde2e").toFile()
        val work = Files.createTempDirectory("nde2ework").toFile()
        client.promptHandler = { p ->
            runBlocking { client.promptReply(p.promptId, accept = true) }
        }
        runBlocking {
            client.init(File(data, "data").absolutePath, Base64.encode(ByteArray(32) { (it * 5).toByte() }))
            val prof = client.profilesSave(
                Profile(name = "SFTP", protocol = ProtocolId.SFTP, host = cfg[0], port = cfg[1].toInt(), username = cfg[2], auth = "password"),
                app.nextdata.protocol.ProfileSecrets(password = cfg[3]),
            )
            val s = client.connect(prof.id)
            val folder = client.mkdir(s.session, s.homeRef, "e2e-" + System.currentTimeMillis())
            for (size in listOf(10, 20_000, 700_000)) {
                val src = File(work, "f$size.bin").apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
                val up = client.upload(s.session, listOf(src.absolutePath), folder.ref, Collision.RENAME)
                assertEquals(1, up.size, "upload of $size bytes")
            }
            val inside = client.list(s.session, folder.ref)
            assertEquals(3, inside.entries.size)
            val dest = File(work, "dl").apply { mkdirs() }
            val got = client.download(s.session, inside.entries.map { it.ref }, dest.absolutePath, Collision.RENAME)
            assertEquals(3, got.size)
            assertEquals(700_000L, File(dest, "f700000.bin").length())
            client.delete(s.session, listOf(folder.ref), recursive = true)
            client.closeSession(s.session)
        }
    }
}
