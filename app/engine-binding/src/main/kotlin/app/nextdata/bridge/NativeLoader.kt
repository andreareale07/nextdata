package app.nextdata.bridge

import java.io.File
import java.util.zip.CRC32

/**
 * Loads `nd_engine` once.
 *
 * - Android: `System.loadLibrary("nd_engine")` (the .so is packaged under jniLibs).
 * - Desktop (Windows): in order
 *   1. the path in env `ND_ENGINE_DLL` (development / tests),
 *   2. the DLL bundled as classpath resource `/native/windows-x64/nd_engine.dll`, extracted to
 *      [extractDir] (a per-user folder such as `%LOCALAPPDATA%\NextData\native`) and loaded with `System.load`,
 *   3. `System.loadLibrary("nd_engine")` (java.library.path).
 */
object NativeLoader {
    const val LIB_NAME = "nd_engine"
    const val RESOURCE = "/native/windows-x64/nd_engine.dll"

    @Volatile
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    val isAndroid: Boolean
        get() = System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            System.getProperty("java.specification.vendor").orEmpty().contains("Android", ignoreCase = true)

    /** True if a bundled DLL resource is on the classpath (desktop builds made with the engine present). */
    fun hasBundledLibrary(): Boolean = NativeLoader::class.java.getResource(RESOURCE) != null

    /** @throws UnsatisfiedLinkError if no source provides the library. */
    @Synchronized
    fun ensureLoaded(extractDir: File? = null) {
        if (loaded) return
        if (isAndroid) {
            System.loadLibrary(LIB_NAME)
            loaded = true
            return
        }
        val override = System.getenv("ND_ENGINE_DLL")
        when {
            !override.isNullOrBlank() -> System.load(File(override).absolutePath)
            hasBundledLibrary() && extractDir != null -> System.load(extractBundled(extractDir).absolutePath)
            else -> System.loadLibrary(LIB_NAME)
        }
        loaded = true
    }

    /** Copies the bundled DLL to `<dir>/<crc>/nd_engine.dll` (content-addressed, so updates never collide with a loaded copy). */
    internal fun extractBundled(dir: File): File {
        val bytes = NativeLoader::class.java.getResourceAsStream(RESOURCE)!!.use { it.readBytes() }
        val crc = CRC32().also { it.update(bytes) }.value.toString(16)
        val target = File(File(dir, crc), "nd_engine.dll")
        if (target.isFile && target.length() == bytes.size.toLong()) return target
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, "nd_engine.dll.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            if (!(target.isFile && target.length() == bytes.size.toLong())) error("cannot extract engine library to $target")
        }
        return target
    }
}
