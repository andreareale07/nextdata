package app.nextdata

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Architecture guard: the shared UI module does no networking, crypto or file-system access of its own
 * (all of that lives in the Rust engine and in the platform adapters). The test fails if any source
 * file of `shared` (every main source set) or `engine-binding` references those packages.
 */
class ArchitectureTest {
    private val forbidden = listOf("java.net", "javax.crypto", "java.security", "java.nio.file")

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
    }

    private fun mainSources(module: String): List<File> {
        val src = File(projectRoot(), "$module/src")
        if (!src.isDirectory) return emptyList()
        return src.listFiles { f -> f.isDirectory && (f.name.endsWith("Main") || f.name == "main") }.orEmpty()
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "java") }.toList() }
    }

    private fun violations(module: String): List<String> = mainSources(module).flatMap { file ->
        file.readLines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//")
            forbidden.firstOrNull { code.contains(Regex("""(^|[^\w.])${Regex.escape(it)}\b""")) }
                ?.let { "${file.relativeTo(projectRoot())}:${i + 1} uses $it" }
        }
    }

    @Test
    fun sharedModuleHasNoNetworkCryptoOrFileSystemImports() {
        val sources = mainSources("shared")
        assertTrue(sources.size > 10, "expected to scan the shared sources, found ${sources.size}")
        val v = violations("shared")
        if (v.isNotEmpty()) fail("shared must not touch network/crypto/fs:\n" + v.joinToString("\n"))
    }

    @Test
    fun engineBindingHasNoNetworkCryptoOrFileSystemImports() {
        val v = violations("engine-binding")
        if (v.isNotEmpty()) fail("engine-binding must not touch network/crypto/fs:\n" + v.joinToString("\n"))
    }

    @Test
    fun detectorFlagsForbiddenReferences() {
        // self-check of the regex so the guard cannot silently rot
        val r = Regex("""(^|[^\w.])${Regex.escape("java.net")}\b""")
        assertTrue(r.containsMatchIn("import java.net.URI"))
        assertTrue(r.containsMatchIn("val x = java.net.URI(\"a\")"))
        assertTrue(!r.containsMatchIn("import kotlinx.java.net2"))
    }
}
