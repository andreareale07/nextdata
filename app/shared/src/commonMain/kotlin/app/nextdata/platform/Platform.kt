package app.nextdata.platform

/** Number formatting with the grouping/decimal separators of [languageTag] (BCP-47, e.g. "it"). */
expect fun formatDecimal(value: Double, fractionDigits: Int, languageTag: String): String

/** Date + time (short style) in the local time zone, localized. */
expect fun formatDateTimeMs(epochMs: Long, languageTag: String): String

/** Language of the operating system (BCP-47). */
expect fun systemLanguageTag(): String

/** Where the master key is protected; only used to describe it in the privacy panel. */
enum class SecretProtection { WindowsDpapi, AndroidKeystore, Other }

enum class PlatformKind { Desktop, Android }

/**
 * Protects the 32-byte profile master key (PMK) with an OS facility. The UI/shared code never
 * does crypto: it only asks for the opaque bytes to hand to the engine at `init`.
 */
interface SecretStore {
    /** Returns the PMK, creating (and persisting, protected) a new random one on first use. */
    suspend fun loadOrCreatePmk(): ByteArray
}

/** A set of local paths ready for `transfer.upload`; [release] frees temporary copies / descriptors. */
/** Cheap description of a local file, used to notice that another app wrote it (never its content). */
data class LocalStat(val name: String, val size: Long, val mtimeMs: Long)

class UploadSelection(val paths: List<String>, val totalBytes: Long? = null, val release: () -> Unit = {})

/**
 * Where a download goes. [destDir] is passed to `transfer.download`; [finish] runs after the engine
 * finished (Android: copy from the private staging dir to the folder the user picked).
 */
class DownloadTarget(
    val destDir: String,
    val label: String,
    val finish: suspend (localPaths: List<String>) -> Unit = {},
    val discard: () -> Unit = {},
    /** Opaque platform location that [PlatformFiles.openLocation] can open (folder path / tree URI). */
    val location: String? = null,
)

/** Why a platform storage operation failed (distinct from engine errors: no engine code fits). */
enum class StorageProblem {
    /** The permission on the chosen folder is gone or was never granted: choose the folder again. */
    PermissionLost,
    NoSpace,
    Other,
}

class StorageException(val problem: StorageProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** File access adapter implemented by each platform app (SAF on Android, AWT dialogs on desktop). */
interface PlatformFiles {
    /** [onPicked] runs once the user has chosen (before any slow copy), so the UI can show "preparing…". */
    suspend fun pickUploadFiles(onPicked: () -> Unit = {}): UploadSelection?
    suspend fun pickUploadFolder(onPicked: () -> Unit = {}): UploadSelection?
    /**
     * Destination for a download. [askEachTime] = false reuses the last chosen folder when the platform
     * still holds access to it (otherwise the picker is shown).
     */
    suspend fun chooseDownloadTarget(askEachTime: Boolean = true): DownloadTarget?

    /** Opens [location] (from [DownloadTarget.location]) in the system file manager; false if impossible. */
    suspend fun openLocation(location: String): Boolean = false

    /** Saves [bytes] where the user chooses; returns false if cancelled. */
    suspend fun saveBytes(suggestedName: String, bytes: ByteArray): Boolean

    /** Lets the user pick a file and returns its bytes (null if cancelled). */
    suspend fun pickBytes(extensions: List<String>): ByteArray?

    // ---- local files of opened documents (docs/open-edit.md); the paths come from the engine's workspace

    /** Whole file, or null if missing / larger than [maxBytes]. */
    suspend fun readFile(path: String, maxBytes: Long): ByteArray? = null

    /** Replaces the file content (the workspace copy being edited); false on failure. */
    suspend fun writeFile(path: String, bytes: ByteArray): Boolean = false

    /** Decodes an image file, downsampled to about [maxPixels]; null if the format is not supported or the file is damaged. */
    suspend fun decodeImage(path: String, maxPixels: Int = 16_000_000): androidx.compose.ui.graphics.ImageBitmap? = null

    /** Files directly inside [dir] (name, size, mtime); null if the folder cannot be read. Used to notice external edits. */
    suspend fun listLocal(dir: String): List<LocalStat>? = null

    /** Number of pages of a PDF, or null if it cannot be shown here (no renderer, password-protected, damaged). */
    suspend fun pdfPageCount(path: String): Int? = null

    /** One PDF page as an image about [widthPx] wide; null on failure. */
    suspend fun renderPdfPage(path: String, index: Int, widthPx: Int): androidx.compose.ui.graphics.ImageBitmap? = null

    /** The user picks a file to use as the modified version of an opened document; a local path the engine can read. */
    suspend fun pickFileToImport(): String? = null

    /** Hands the file to another app (edit = may change it). False if no app can handle it. */
    suspend fun openExternal(path: String, edit: Boolean): Boolean = false
}

/** Everything platform-specific the shared app needs. */
class AppEnvironment(
    val dataDir: String,
    val secretStore: SecretStore,
    val files: PlatformFiles,
    val kind: PlatformKind,
    val secretProtection: SecretProtection,
    val appVersion: String = "1.0.0",
    /** True when the user asked the OS to remove animations (Android: animator scale 0). */
    val reduceMotion: () -> Boolean = { false },
    /**
     * In-app audio/video player fed by range reads (Android). Null where there is none (desktop opens a local copy in
     * the system player instead). Receives the player request and must dispose everything it opens.
     */
    val mediaPlayer: (@androidx.compose.runtime.Composable (MediaRequest, androidx.compose.ui.Modifier) -> Unit)? = null,
)

/** What the in-app media player plays: a remote file read in ranges through [source]. */
class MediaRequest(val name: String, val size: Long?, val source: RangeSource, val video: Boolean)

/** Random-access reader of a remote file (backed by the engine's `read.*`); calls may block the caller's thread. */
interface RangeSource {
    /** Up to [length] bytes at [offset]; empty at/after the end; throws on failure. */
    suspend fun read(offset: Long, length: Int): ByteArray
    suspend fun close()
}

/**
 * Makes the platform's default locale [languageTag] so that localized resources resolve in it
 * (in-app language switch). [systemLanguageTag] keeps returning the original system language.
 */
expect fun applyAppLocale(languageTag: String)
