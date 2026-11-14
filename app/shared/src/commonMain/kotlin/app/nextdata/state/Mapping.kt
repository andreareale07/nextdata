package app.nextdata.state

import app.nextdata.platform.formatDateTimeMs
import app.nextdata.platform.formatDecimal
import app.nextdata.protocol.Entry
import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ProtocolId

/** Visual category of a directory entry; maps to an icon in the UI layer. */
enum class FileCategory { Folder, Link, Special, Image, Audio, Video, Archive, Pdf, Document, Spreadsheet, Presentation, Code, Text, Generic }

object FileKinds {
    private val images = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "tif", "tiff", "raw", "ico", "avif")
    private val audio = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "aiff")
    private val video = setOf("mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "m4v", "mpg", "mpeg")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "zst", "dmg", "apk", "jar")
    private val documents = setOf("doc", "docx", "odt", "rtf", "pages")
    private val spreadsheets = setOf("xls", "xlsx", "ods", "csv", "numbers", "tsv")
    private val presentations = setOf("ppt", "pptx", "odp", "key")
    private val code = setOf("kt", "kts", "java", "rs", "py", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "sh", "bat", "ps1", "json", "xml", "yml", "yaml", "toml", "html", "css", "sql", "rb", "php", "swift")
    private val text = setOf("txt", "md", "log", "ini", "cfg", "conf", "tex", "rst")

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    fun categoryOf(name: String, kind: String): FileCategory {
        when (kind) {
            EntryKind.DIR -> return FileCategory.Folder
            EntryKind.SYMLINK -> return FileCategory.Link
            EntryKind.OTHER -> return FileCategory.Special
        }
        val ext = extensionOf(name)
        return when {
            ext in images -> FileCategory.Image
            ext in audio -> FileCategory.Audio
            ext in video -> FileCategory.Video
            ext in archives -> FileCategory.Archive
            ext == "pdf" -> FileCategory.Pdf
            ext in documents -> FileCategory.Document
            ext in spreadsheets -> FileCategory.Spreadsheet
            ext in presentations -> FileCategory.Presentation
            ext in code -> FileCategory.Code
            ext in text -> FileCategory.Text
            else -> FileCategory.Generic
        }
    }

    fun categoryOf(entry: Entry) = categoryOf(entry.name, entry.kind)

    /** Hidden = dotfile; the engine owns the `show_hidden` setting, this is only the display filter. */
    fun isHidden(name: String) = name.startsWith(".") && name != "." && name != ".."
}

/** Profile visuals: colour palette and protocol icon key. */
object ProfileVisuals {
    /** Default palette (ARGB hex strings, stored in `Profile.color`). */
    val palette = listOf("#0F766E", "#2563EB", "#7C3AED", "#BE123C", "#B45309", "#15803D", "#475569", "#0E7490")

    fun defaultColorFor(index: Int) = palette[((index % palette.size) + palette.size) % palette.size]

    /** Parses `#RRGGBB` or `#AARRGGBB` into an ARGB long; null if malformed. */
    fun parseColor(hex: String?): Long? {
        if (hex.isNullOrBlank()) return null
        val h = hex.trim().removePrefix("#")
        if (h.length != 6 && h.length != 8) return null
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) 0xFF000000L or v else v
    }

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    /** Is [protocol] one of those with a TLS switch (https)? */
    fun hasTlsSwitch(protocol: String) = protocol == ProtocolId.WEBDAV || protocol == ProtocolId.S3

    fun defaultPort(protocol: String): Int = when (protocol) {
        ProtocolId.SFTP -> 22
        ProtocolId.SMB -> 445
        else -> 0
    }
}

/** Language selection (in-app, switchable at runtime). */
enum class LanguageChoice(val id: String, val tag: String?) {
    System("system", null),
    Italian("it", "it"),
    English("en", "en");

    companion object {
        fun fromId(id: String?): LanguageChoice = entries.firstOrNull { it.id == id } ?: System
    }
}

object Languages {
    /** Resolves to a language we actually ship ("it" or "en"). */
    fun effectiveTag(choice: LanguageChoice, systemTag: String): String {
        val raw = choice.tag ?: systemTag
        return if (raw.lowercase().startsWith("it")) "it" else "en"
    }
}

object Formatters {
    private val units = listOf("B", "KB", "MB", "GB", "TB", "PB")

    /** 1024-based, one decimal at most, locale decimal separator. */
    fun size(bytes: Long, languageTag: String): String {
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return formatDecimal(v, if (v < 10) 1 else 0, languageTag) + " " + units[i]
    }

    fun dateTime(epochMs: Long, languageTag: String): String = formatDateTimeMs(epochMs, languageTag)

    /** Throughput, e.g. "5.2 MB/s". */
    fun speed(bytesPerSecond: Long, languageTag: String): String = size(bytesPerSecond, languageTag) + "/s"

    /** Compact remaining time: "38 s", "4 min 10 s", "1 h 05 min" (unit symbols are language-neutral). */
    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s"
        else -> "${seconds / 3600} h ${((seconds % 3600) / 60).toString().padStart(2, '0')} min"
    }
}
