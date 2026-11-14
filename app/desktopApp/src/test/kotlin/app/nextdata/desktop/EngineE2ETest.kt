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

    /** Open in place, edit the workspace copy, save: through the real engine DLL (docs/open-edit.md). */
    @Test
    fun openEditSaveRoundTrip() {
        if (System.getenv("ND_ENGINE_E2E") != "1") {
            println("EngineE2ETest.openEditSaveRoundTrip skipped (set ND_ENGINE_E2E=1)")
            return
        }
        val dll = System.getenv("ND_ENGINE_DLL")?.let(::File)
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, "engine/dist/windows-x64/nd_engine.dll") }.first { it.isFile }
        System.load(dll.absolutePath)
        val client = EngineClient(JniEngine())
        val data = Files.createTempDirectory("nde2e-open").toFile()
        val work = Files.createTempDirectory("nde2e-openwork").toFile()
        runBlocking {
            client.init(File(data, "data").absolutePath, Base64.encode(ByteArray(32) { (it * 5).toByte() }))
            val p = client.profilesSave(Profile(name = "E2E", protocol = ProtocolId.FAKE, host = "demo", auth = "none"), null)
            val s = client.connect(p.id)
            val src = File(work, "note.txt").apply { writeText("first version") }
            val remote = client.upload(s.session, listOf(src.absolutePath), s.homeRef, Collision.RENAME).single()

            val opened = client.openPrepare(s.session, remote.ref)
            assertEquals("clean", opened.doc.state)
            val local = File(opened.doc.localPath)
            assertEquals("first version", local.readText())
            // untouched: nothing to upload
            assertEquals(false, client.openCheck(opened.doc.id, stableMs = 0).changed)

            // "another app" saves the file
            local.writeText("second version, edited outside")
            val chk = client.openCheck(opened.doc.id, stableMs = 0)
            assertEquals(true, chk.changed)
            assertEquals("modified", chk.doc.state)
            val saved = client.openSave(s.session, opened.doc.id)
            assertEquals("clean", saved.state)

            // the server really has it (downloaded through the ordinary path)
            val dl = File(work, "dl").apply { mkdirs() }
            val got = client.download(s.session, listOf(remote.ref), dl.absolutePath, Collision.RENAME)
            assertEquals("second version, edited outside", File(got.single()).readText())
            // reopening is served from the cache, still clean
            val again = client.openPrepare(s.session, remote.ref)
            assertEquals(true, again.fromCache)
            assertEquals(opened.doc.id, again.doc.id)
            client.openRelease(opened.doc.id)
            client.openDiscard(opened.doc.id)
        }
    }

    /**
     * Spike P6: cost of the bridge itself for ranged reads (JSON + base64 + JNI) with no network involved (fake
     * provider). Reads a 24 MiB file in 256 KiB ranges, checks every byte and prints the throughput.
     */
    @Test
    fun rangeReadThroughputThroughTheBridge() {
        if (System.getenv("ND_ENGINE_E2E") != "1") {
            println("EngineE2ETest.rangeReadThroughputThroughTheBridge skipped (set ND_ENGINE_E2E=1)")
            return
        }
        val dll = System.getenv("ND_ENGINE_DLL")?.let(::File)
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, "engine/dist/windows-x64/nd_engine.dll") }.first { it.isFile }
        System.load(dll.absolutePath)
        val client = EngineClient(JniEngine())
        val data = Files.createTempDirectory("nde2e-rr").toFile()
        val work = Files.createTempDirectory("nde2e-rrwork").toFile()
        runBlocking {
            client.init(File(data, "data").absolutePath, Base64.encode(ByteArray(32) { (it * 11).toByte() }))
            val p = client.profilesSave(Profile(name = "E2E", protocol = ProtocolId.FAKE, host = "demo", auth = "none"), null)
            val s = client.connect(p.id)
            val size = 24 * 1024 * 1024
            val bytes = ByteArray(size) { ((it * 31) xor (it ushr 9)).toByte() }
            val src = File(work, "big.bin").apply { writeBytes(bytes) }
            val remote = client.upload(s.session, listOf(src.absolutePath), s.homeRef, Collision.RENAME).single()
            val h = client.readOpen(s.session, remote.ref)
            assertEquals(size.toLong(), h.size)
            val t0 = System.nanoTime()
            var off = 0L
            var ok = true
            while (off < size) {
                val chunk = client.readRange(h.handle, off, 256 * 1024)
                if (chunk.isEmpty()) break
                if (!chunk.contentEquals(bytes.copyOfRange(off.toInt(), off.toInt() + chunk.size))) ok = false
                off += chunk.size
            }
            val secs = (System.nanoTime() - t0) / 1e9
            client.readClose(h.handle)
            val mbps = size / 1024.0 / 1024.0 / secs
            println("P6 range-read throughput through the bridge: %.1f MiB/s (%d MiB in %.2f s, 256 KiB ranges)".format(mbps, size / 1024 / 1024, secs))
            assertTrue(ok, "content mismatch")
            assertEquals(size.toLong(), off)
            // a 4K video is about 3 MiB/s: the bridge alone must never be the bottleneck
            assertTrue(mbps > 20.0, "bridge too slow: $mbps MiB/s")
        }
    }
}
