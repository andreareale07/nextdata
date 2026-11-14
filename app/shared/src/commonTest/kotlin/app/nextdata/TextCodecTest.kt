package app.nextdata

import app.nextdata.state.TextCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TextCodecTest {
    private fun text(b: ByteArray) = assertIs<TextCodec.Text>(TextCodec.decode(b))

    @Test
    fun utf8RoundTripKeepsEveryByte() {
        val src = "caffè ☕ 日本\nsecond line\n".encodeToByteArray()
        val t = text(src)
        assertEquals(TextCodec.Encoding.Utf8, t.encoding)
        assertContentEquals(src, TextCodec.encode(t.text, t.encoding, t.crlf))
    }

    @Test
    fun bomAndCrlfArePreserved() {
        val src = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\r\nb\r\n".encodeToByteArray()
        val t = text(src)
        assertEquals(TextCodec.Encoding.Utf8Bom, t.encoding)
        assertEquals(true, t.crlf)
        assertEquals("a\nb\n", t.text) // the editor always works with \n
        assertContentEquals(src, TextCodec.encode(t.text, t.encoding, t.crlf))
    }

    @Test
    fun utf16BothByteOrdersRoundTrip() {
        for (enc in listOf(TextCodec.Encoding.Utf16Le, TextCodec.Encoding.Utf16Be)) {
            val bytes = TextCodec.encode("héllo\nwörld", enc, crlf = true)
            val t = text(bytes)
            assertEquals(enc, t.encoding)
            assertEquals("héllo\nwörld", t.text)
            assertContentEquals(bytes, TextCodec.encode(t.text, t.encoding, t.crlf))
        }
    }

    @Test
    fun nothingUnclearIsEditable() {
        assertEquals(TextCodec.Why.Binary, assertIs<TextCodec.NotEditable>(TextCodec.decode(byteArrayOf(1, 2, 0, 3))).why)
        // Latin-1 "è" alone is invalid UTF-8: shown read-only, never rewritten as something else
        assertEquals(TextCodec.Why.UnknownEncoding, assertIs<TextCodec.NotEditable>(TextCodec.decode(byteArrayOf(0x63, 0xE8.toByte()))).why)
        // mixed line endings could not be written back identically
        assertEquals(TextCodec.Why.MixedLineEndings, assertIs<TextCodec.NotEditable>(TextCodec.decode("a\r\nb\nc".encodeToByteArray())).why)
        assertEquals(TextCodec.Why.MixedLineEndings, assertIs<TextCodec.NotEditable>(TextCodec.decode("a\rb".encodeToByteArray())).why)
        // truncated UTF-16
        assertEquals(TextCodec.Why.Odd16, assertIs<TextCodec.NotEditable>(TextCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61))).why)
    }

    @Test
    fun emptyFileIsEditableText() {
        val t = text(ByteArray(0))
        assertEquals("", t.text)
        assertContentEquals(ByteArray(0), TextCodec.encode(t.text, t.encoding, t.crlf))
    }
}
