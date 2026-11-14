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
)

/**
 * Makes the platform's default locale [languageTag] so that localized resources resolve in it
 * (in-app language switch). [systemLanguageTag] keeps returning the original system language.
 */
expect fun applyAppLocale(languageTag: String)
