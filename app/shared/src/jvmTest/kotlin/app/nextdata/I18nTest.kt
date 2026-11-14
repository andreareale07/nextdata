package app.nextdata

import app.nextdata.i18n.certReasonRes
import app.nextdata.i18n.errorStringRes
import app.nextdata.i18n.namedErrorStringRes
import app.nextdata.protocol.ErrorCodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** i18n guards: every protocol error code and every string exists in Italian AND English, same placeholders. */
class I18nTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("project root not found")
    }

    private fun res(locale: String) = File(root(), "shared/src/commonMain/composeResources/$locale/strings.xml").also {
        assertTrue(it.exists(), "missing $it")
    }

    /** name -> raw text (for plurals: concatenated items), by resource kind. */
    private fun parse(file: File): Map<String, String> {
        val text = file.readText()
        val out = linkedMapOf<String, String>()
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach {
            out["string:" + it.groupValues[1]] = it.groupValues[2]
        }
        Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach {
            out["plurals:" + it.groupValues[1]] = it.groupValues[2]
        }
        return out
    }

    private val en by lazy { parse(res("values")) }
    private val itl by lazy { parse(res("values-it")) }

    @Test
    fun everyProtocolErrorCodeHasAStringInItalianAndEnglish() {
        assertEquals(23, ErrorCodes.all.size, "protocol.md defines 23 error codes")
        ErrorCodes.all.forEach { code ->
            assertTrue("string:err_$code" in en, "EN missing err_$code")
            assertTrue("string:err_$code" in itl, "IT missing err_$code")
            assertTrue(en.getValue("string:err_$code").isNotBlank())
            assertTrue(itl.getValue("string:err_$code").isNotBlank())
        }
        assertTrue("string:err_UNKNOWN" in en && "string:err_UNKNOWN" in itl)
    }

    @Test
    fun errorCodeMappingPointsAtTheMatchingResource() {
        ErrorCodes.all.forEach { code ->
            assertEquals("err_$code", errorStringRes(code).key, "mapping for $code")
        }
        assertEquals("err_UNKNOWN", errorStringRes("SOMETHING_NEW").key)
        // named variants exist where mapped
        ErrorCodes.all.mapNotNull { c -> namedErrorStringRes(c)?.let { c to it } }.forEach { (c, r) ->
            assertEquals("err_${c}_named", r.key)
            assertTrue("string:err_${c}_named" in en && "string:err_${c}_named" in itl)
        }
        assertNotEquals(errorStringRes(ErrorCodes.NETWORK).key, errorStringRes(ErrorCodes.TIMEOUT).key)
    }

    @Test
    fun certificateReasonsAreMapped() {
        listOf("self_signed", "expired", "hostname_mismatch", "unknown_issuer", "other").forEach {
            val key = certReasonRes(it).key
            assertTrue("string:$key" in en && "string:$key" in itl, key)
        }
    }

    @Test
    fun italianAndEnglishDefineExactlyTheSameResources() {
        val onlyEn = en.keys - itl.keys
        val onlyIt = itl.keys - en.keys
        assertTrue(onlyEn.isEmpty(), "missing in IT: $onlyEn")
        assertTrue(onlyIt.isEmpty(), "missing in EN: $onlyIt")
        assertTrue(en.size > 200, "expected a full string table, got ${en.size}")
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        val ph = Regex("""%\d+\$[sd]""")
        en.forEach { (k, v) ->
            val a = ph.findAll(v).map { it.value }.toSortedSet()
            val b = ph.findAll(itl.getValue(k)).map { m -> m.value }.toSortedSet()
            assertEquals(a, b, "placeholders differ for $k")
        }
    }

    @Test
    fun noEmptyTranslationsAndNoUnescapedApostrophes() {
        listOf("EN" to en, "IT" to itl).forEach { (lang, table) ->
            table.forEach { (k, v) ->
                assertTrue(v.isNotBlank(), "$lang $k is blank")
                assertTrue(!Regex("""(?<!\\)'""").containsMatchIn(v), "$lang $k has an unescaped apostrophe")
            }
        }
    }

    @Test
    fun pluralsDefineOneAndOther() {
        listOf("EN" to en, "IT" to itl).forEach { (lang, table) ->
            table.filterKeys { it.startsWith("plurals:") }.forEach { (k, v) ->
                assertTrue("quantity=\"one\"" in v && "quantity=\"other\"" in v, "$lang $k needs one+other")
            }
        }
    }

    @Test
    fun uiSourcesHaveNoHardcodedUserVisibleLiterals() {
        val dir = File(root(), "shared/src/commonMain/kotlin/app/nextdata/ui")
        val bad = Regex("""(Text\(\s*"[^"$]*[A-Za-z]{2,}[^"]*"|contentDescription\s*=\s*"[^"]*[A-Za-z]{2,})""")
        val offenders = dir.walkTopDown().filter { f -> f.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (bad.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }.toList()
        assertTrue(offenders.isEmpty(), "hardcoded UI strings:\n" + offenders.joinToString("\n"))
    }
}
