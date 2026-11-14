package app.nextdata.state

/**
 * Decoding/encoding of text files for the internal editor. Never guesses: anything that is not clearly
 * UTF-8 / UTF-8 with BOM / UTF-16 with BOM, or that mixes line endings, is reported as not editable so that
 * saving can never corrupt it.
 */
object TextCodec {
    enum class Encoding { Utf8, Utf8Bom, Utf16Le, Utf16Be }

    enum class Why { Binary, UnknownEncoding, MixedLineEndings, Odd16 }

    sealed interface Result
    /** [text] always uses "\n"; [crlf] says how to write it back. */
    data class Text(val text: String, val encoding: Encoding, val crlf: Boolean) : Result
    data class NotEditable(val why: Why, val preview: String? = null) : Result

    fun decode(b: ByteArray): Result {
        val (enc, off) = when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Encoding.Utf8Bom to 3
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Encoding.Utf16Le to 2
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Encoding.Utf16Be to 2
            else -> Encoding.Utf8 to 0
        }
        val raw: String = when (enc) {
            Encoding.Utf8, Encoding.Utf8Bom -> {
                if (enc == Encoding.Utf8 && b.contains(0)) return NotEditable(Why.Binary)
                try {
                    b.decodeToString(off, b.size, throwOnInvalidSequence = true)
                } catch (_: Exception) {
                    return NotEditable(Why.UnknownEncoding, lossy(b))
                }
            }
            Encoding.Utf16Le, Encoding.Utf16Be -> {
                if ((b.size - off) % 2 != 0) return NotEditable(Why.Odd16)
                val chars = CharArray((b.size - off) / 2) { i ->
                    val x = b[off + 2 * i].toInt() and 0xFF
                    val y = b[off + 2 * i + 1].toInt() and 0xFF
                    (if (enc == Encoding.Utf16Le) (y shl 8) or x else (x shl 8) or y).toChar()
                }
                chars.concatToString()
            }
        }
        val crlfCount = raw.windowed(2).count { it == "\r\n" }
        val lfOnly = raw.count { it == '\n' } - crlfCount
        val loneCr = raw.count { it == '\r' } - crlfCount
        if (loneCr > 0 || (crlfCount > 0 && lfOnly > 0)) return NotEditable(Why.MixedLineEndings, raw.replace("\r\n", "\n"))
        val crlf = crlfCount > 0
        return Text(if (crlf) raw.replace("\r\n", "\n") else raw, enc, crlf)
    }

    fun encode(text: String, encoding: Encoding, crlf: Boolean): ByteArray {
        val t = if (crlf) text.replace("\n", "\r\n") else text
        return when (encoding) {
            Encoding.Utf8 -> t.encodeToByteArray()
            Encoding.Utf8Bom -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + t.encodeToByteArray()
            Encoding.Utf16Le -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + ByteArray(t.length * 2).also { o ->
                t.forEachIndexed { i, c -> o[2 * i] = c.code.toByte(); o[2 * i + 1] = (c.code shr 8).toByte() }
            }
            Encoding.Utf16Be -> byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + ByteArray(t.length * 2).also { o ->
                t.forEachIndexed { i, c -> o[2 * i] = (c.code shr 8).toByte(); o[2 * i + 1] = c.code.toByte() }
            }
        }
    }

    /** Read-only preview of text in an unknown single-byte encoding (never written back). */
    private fun lossy(b: ByteArray): String = b.decodeToString(0, minOf(b.size, 200_000))
}
