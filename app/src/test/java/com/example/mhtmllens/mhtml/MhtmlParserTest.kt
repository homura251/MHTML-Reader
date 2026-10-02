package com.example.mhtmllens.mhtml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class MhtmlParserTest {

    @Test
    fun detectsBlinkMhtmlWithoutExtension() {
        val prefix = """
            From: <Saved by Blink>
            Snapshot-Content-Location: https://example.com/
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="boundary"
        """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)

        assertTrue(MhtmlParser.looksLikeMhtml(prefix))
    }

    @Test
    fun preservesUtf8ChineseAfterQuotedPrintableDecode() {
        val archive = """
            From: <Saved by Blink>
            Snapshot-Content-Location: https://example.com/
            MIME-Version: 1.0
            Content-Type: multipart/related; type="text/html"; boundary="boundary"

            --boundary
            Content-Type: text/html
            Content-Transfer-Encoding: quoted-printable
            Content-Location: https://example.com/

            <html><head><meta charset=3D"UTF-8"><title>Test</title></head><body>=E4=BD=A0=E5=A5=BD=EF=BC=81</body></html>
            --boundary--
        """.trimIndent().replace("\n", "\r\n").toByteArray(StandardCharsets.ISO_8859_1)

        val parsed = MhtmlParser.parse(archive)
        val html = MhtmlParser.decodePartText(parsed.root).first

        assertEquals("UTF-8", parsed.rootCharset.uppercase())
        assertTrue(html.contains("你好！"))
        assertFalse(html.contains("ä½"))
        assertFalse(html.contains("ï¼"))
    }
}
