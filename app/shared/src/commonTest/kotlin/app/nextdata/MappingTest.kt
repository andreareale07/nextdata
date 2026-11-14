package app.nextdata

import app.nextdata.protocol.EntryKind
import app.nextdata.protocol.ProgressInfo
import app.nextdata.state.FileCategory
import app.nextdata.state.FileKinds
import app.nextdata.state.Formatters
import app.nextdata.state.LanguageChoice
import app.nextdata.state.Languages
import app.nextdata.state.ProfileVisuals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Name mapping" tests: file names -> categories, profile colours/initials, language and size formatting. */
class MappingTest {
    @Test
    fun entryKindsMapToCategories() {
        assertEquals(FileCategory.Folder, FileKinds.categoryOf("x.jpg", EntryKind.DIR))
        assertEquals(FileCategory.Link, FileKinds.categoryOf("latest", EntryKind.SYMLINK))
        assertEquals(FileCategory.Special, FileKinds.categoryOf("dev0", EntryKind.OTHER))
    }

    @Test
    fun extensionsMapToCategoriesCaseInsensitively() {
        val cases = mapOf(
            "photo.JPG" to FileCategory.Image, "a.png" to FileCategory.Image, "song.flac" to FileCategory.Audio,
            "movie.MKV" to FileCategory.Video, "backup.tar.gz" to FileCategory.Archive, "disk.iso" to FileCategory.Archive,
            "doc.pdf" to FileCategory.Pdf, "letter.docx" to FileCategory.Document, "sheet.xlsx" to FileCategory.Spreadsheet,
            "deck.pptx" to FileCategory.Presentation, "main.rs" to FileCategory.Code, "notes.txt" to FileCategory.Text,
            "README" to FileCategory.Generic, "weird.zzz" to FileCategory.Generic,
        )
        cases.forEach { (name, cat) -> assertEquals(cat, FileKinds.categoryOf(name, EntryKind.FILE), name) }
    }

    @Test
    fun extensionEdgeCases() {
        assertEquals("", FileKinds.extensionOf(".bashrc"))
        assertEquals("", FileKinds.extensionOf("trailingdot."))
        assertEquals("gz", FileKinds.extensionOf("a.b.GZ"))
        assertEquals(FileCategory.Generic, FileKinds.categoryOf(".gitignore", EntryKind.FILE))
    }

    @Test
    fun hiddenFilesAreDotfiles() {
        assertTrue(FileKinds.isHidden(".config"))
        assertFalse(FileKinds.isHidden("config"))
        assertFalse(FileKinds.isHidden(".."))
        assertFalse(FileKinds.isHidden("."))
    }

    @Test
    fun colorParsing() {
        assertEquals(0xFF0F766EL, ProfileVisuals.parseColor("#0F766E"))
        assertEquals(0x800F766EL, ProfileVisuals.parseColor("#800F766E"))
        assertEquals(0xFF0F766EL, ProfileVisuals.parseColor("0f766e"))
        assertNull(ProfileVisuals.parseColor("#12"))
        assertNull(ProfileVisuals.parseColor("teal"))
        assertNull(ProfileVisuals.parseColor(null))
        assertTrue(ProfileVisuals.palette.all { ProfileVisuals.parseColor(it) != null })
    }

    @Test
    fun defaultColorWraps() {
        assertEquals(ProfileVisuals.palette[0], ProfileVisuals.defaultColorFor(ProfileVisuals.palette.size))
        assertEquals(ProfileVisuals.palette.last(), ProfileVisuals.defaultColorFor(-1))
    }

    @Test
    fun initials() {
        assertEquals("HN", ProfileVisuals.initials("Home NAS"))
        assertEquals("NE", ProfileVisuals.initials("nextcloud"))
        assertEquals("?", ProfileVisuals.initials("   "))
    }

    @Test
    fun languageResolution() {
        assertEquals("it", Languages.effectiveTag(LanguageChoice.System, "it-IT"))
        assertEquals("en", Languages.effectiveTag(LanguageChoice.System, "de-DE"))
        assertEquals("en", Languages.effectiveTag(LanguageChoice.English, "it-IT"))
        assertEquals("it", Languages.effectiveTag(LanguageChoice.Italian, "en-US"))
        assertEquals(LanguageChoice.System, LanguageChoice.fromId("zz"))
        assertEquals(LanguageChoice.Italian, LanguageChoice.fromId("it"))
    }

    @Test
    fun sizeFormattingUsesLocaleSeparators() {
        assertEquals("0 B", Formatters.size(0, "en"))
        assertEquals("1023 B", Formatters.size(1023, "en"))
        assertEquals("1 KB", Formatters.size(1024, "en"))
        assertEquals("1.5 MB", Formatters.size(1_572_864, "en"))
        assertEquals("1,5 MB", Formatters.size(1_572_864, "it"))
        assertEquals("70 MB", Formatters.size(73_400_320, "en"))
        assertEquals("4.4 GB", Formatters.size(4_700_000_000, "en"))
    }

    @Test
    fun dateFormattingIsLocalized() {
        val it = Formatters.dateTime(1_700_000_000_000, "it")
        val en = Formatters.dateTime(1_700_000_000_000, "en")
        assertTrue(it.isNotBlank() && en.isNotBlank())
        assertTrue(it != en, "it and en formats differ: $it vs $en")
    }

    @Test
    fun progressFraction() {
        assertEquals(0.5f, ProgressInfo(50, 100).fraction)
        assertEquals(1f, ProgressInfo(500, 100).fraction)
        assertNull(ProgressInfo(5, 0).fraction)
        assertNull(ProgressInfo(5, null).fraction)
    }
}
