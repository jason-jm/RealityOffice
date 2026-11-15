package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.model.DocFormat
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Legacy .doc: text, paragraphs and the basic formatting decoded from the CHPX / PAPX pages of a hand-built Word 97 file. */
class DocReaderTest {

    private fun ByteArray.put16(off: Int, v: Int) { this[off] = (v and 0xFF).toByte(); this[off + 1] = ((v ushr 8) and 0xFF).toByte() }
    private fun ByteArray.put32(off: Int, v: Int) { for (k in 0 until 4) this[off + k] = ((v ushr (8 * k)) and 0xFF).toByte() }

    /** Three paragraphs: a centred Heading 1 "Title"; "Hello bold world" with "bold" in bold red 14 pt Arial; a right-aligned "Right". */
    private fun buildDoc(papx2: ByteArray = byteArrayOf(), papx3: ByteArray = byteArrayOf(0x03, 0x24, 2)): ByteArray {
        val text = "Title\rHello bold world\rRight\r"
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val wd = ByteArray(4608)
        wd.put16(0, 0xA5EC); wd.put16(2, 0x00C1)
        val fcMin = 1024; val fcMac = fcMin + textBytes.size
        wd.put32(0x18, fcMin); wd.put32(0x1C, fcMac)
        wd.put16(0x20, 14); wd.put16(0x3E, 22); wd.put32(0x40 + 12, text.length); wd.put16(0x98, 93)
        System.arraycopy(textBytes, 0, wd, fcMin, textBytes.size)
        // CHPX FKP (page 4): plain | bold red 14pt Arial on "bold" | plain
        val chpxPage = 2048
        val boldFc = fcMin + text.indexOf("bold"); val boldEnd = boldFc + 4
        intArrayOf(fcMin, boldFc, boldEnd, fcMac).forEachIndexed { i, fc -> wd.put32(chpxPage + i * 4, fc) }
        val chpx = byteArrayOf(0x35, 0x08, 1, 0x43, 0x4A, 28, 0, 0x42, 0x2A, 6, 0x4F, 0x4A, 0, 0)
        val chpxOff = chpxPage + 400; wd[chpxOff] = chpx.size.toByte(); System.arraycopy(chpx, 0, wd, chpxOff + 1, chpx.size)
        val rgb = chpxPage + 16; wd[rgb] = 0; wd[rgb + 1] = 200.toByte(); wd[rgb + 2] = 0
        wd[chpxPage + 511] = 3
        // PAPX FKP (page 5): heading 1 + centre | normal | right
        val papxPage = 2560
        val p1End = fcMin + text.indexOf('\r') + 1; val p2End = fcMin + text.indexOf('\r', p1End - fcMin) + 1
        intArrayOf(fcMin, p1End, p2End, fcMac).forEachIndexed { i, fc -> wd.put32(papxPage + i * 4, fc) }
        fun papx(at: Int, istd: Int, grpprl: ByteArray): Int {
            val words = (2 + grpprl.size + 1) / 2
            wd[at] = 0; wd[at + 1] = words.toByte(); wd.put16(at + 2, istd); System.arraycopy(grpprl, 0, wd, at + 4, grpprl.size)
            return (at - papxPage) / 2
        }
        val o1 = papx(papxPage + 300, 1, byteArrayOf(0x03, 0x24, 1)); val o2 = papx(papxPage + 320, 0, papx2); val o3 = papx(papxPage + 340, 0, papx3)
        val rgbx = papxPage + 16; wd[rgbx] = o1.toByte(); wd[rgbx + 13] = o2.toByte(); wd[rgbx + 26] = o3.toByte()
        wd[papxPage + 511] = 3
        // 0Table: CLX (one compressed piece), the two bin tables, the font table
        val tbl = ByteArray(4608)
        var pos = 0
        tbl[pos] = 2; tbl.put32(pos + 1, 16); pos += 5
        tbl.put32(pos, 0); tbl.put32(pos + 4, text.length); pos += 8
        tbl.put16(pos, 0); tbl.put32(pos + 2, (fcMin * 2) or 0x40000000); tbl.put16(pos + 6, 0); pos += 8
        val clxLen = pos
        val bteChpx = pos; tbl.put32(pos, fcMin); tbl.put32(pos + 4, fcMac); tbl.put32(pos + 8, 4); pos += 12
        val btePapx = pos; tbl.put32(pos, fcMin); tbl.put32(pos + 4, fcMac); tbl.put32(pos + 8, 5); pos += 12
        val ffn = pos; tbl.put16(pos, 0xFFFF); tbl.put16(pos + 2, 1); tbl.put16(pos + 4, 0); pos += 6
        val name = "Arial"; val body = 39 + (name.length + 1) * 2
        tbl[pos] = body.toByte(); name.forEachIndexed { k, ch -> tbl.put16(pos + 40 + k * 2, ch.code) }; pos += 1 + body
        val ffnLen = pos - ffn
        wd.put32(0x1A2, 0); wd.put32(0x1A6, clxLen)
        wd.put32(0xFA, bteChpx); wd.put32(0xFE, 12); wd.put32(0x102, btePapx); wd.put32(0x106, 12); wd.put32(0x112, ffn); wd.put32(0x116, ffnLen)
        return TestCfb.build(linkedMapOf("WordDocument" to wd, "0Table" to tbl))
    }

    /** A Word 97 file of [text] alone: one compressed piece, no formatting tables. */
    private fun plainDoc(text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val wd = ByteArray(4608)
        wd.put16(0, 0xA5EC); wd.put16(2, 0x00C1)
        val fcMin = 1024; val fcMac = fcMin + textBytes.size
        wd.put32(0x18, fcMin); wd.put32(0x1C, fcMac)
        wd.put16(0x20, 14); wd.put16(0x3E, 22); wd.put32(0x40 + 12, text.length); wd.put16(0x98, 93)
        System.arraycopy(textBytes, 0, wd, fcMin, textBytes.size)
        val tbl = ByteArray(512)
        var pos = 0
        tbl[pos] = 2; tbl.put32(pos + 1, 16); pos += 5
        tbl.put32(pos, 0); tbl.put32(pos + 4, text.length); pos += 8
        tbl.put16(pos, 0); tbl.put32(pos + 2, (fcMin * 2) or 0x40000000); tbl.put16(pos + 6, 0); pos += 8
        wd.put32(0x1A2, 0); wd.put32(0x1A6, pos)
        return TestCfb.build(linkedMapOf("WordDocument" to wd, "0Table" to tbl))
    }

    @Test
    fun aFieldShowsItsResultNotItsCodeAndAHyperlinkLinks() {
        val f = "\u0013"; val sep = "\u0014"; val end = "\u0015"
        val text = "Date: $f CREATEDATE \\@ \"MMMM D, YYYY\" ${sep}April 23, 2007$end\r" +
            "See $f HYPERLINK \"https://example.com/\" ${sep}our site$end now\r" +
            "Nested $f IF $f MERGEFIELD x ${sep}y$end = 1 \"a\" \"b\" ${sep}a$end end\r" +
            "No result: $f PAGE $end.\r" +
            "To: $f MACROBUTTON NoMacro [Click to enter the recipient's name] $end\r"
        val paras = DocReader.read(ByteArrayInputStream(plainDoc(text))).blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        assertEquals(listOf("Date: April 23, 2007", "See our site now", "Nested a end", "No result: .", "To: [Click to enter the recipient's name]"), paras.map { it.plainText() })
        assertEquals(listOf(null, "https://example.com/", null), paras[1].runs.map { it.format.link })
        assertEquals(listOf("See ", "our site", " now"), paras[1].runs.map { it.text })
    }

    @Test
    fun textParagraphsAndBasicFormattingAreRecovered() {
        val doc = DocReader.read(ByteArrayInputStream(buildDoc()))
        assertEquals(DocFormat.DOC, doc.sourceFormat)
        val paras = doc.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        assertEquals(listOf("Title", "Hello bold world", "Right"), paras.map { it.plainText() })
        val title = paras[0]
        assertEquals(ParagraphStyle.HEADING1, title.format.style); assertEquals(TextAlign.CENTER, title.format.align); assertTrue(title.runs[0].format.bold)
        val hello = paras[1]
        assertEquals(listOf("Hello ", "bold", " world"), hello.runs.map { it.text })
        assertFalse(hello.runs[0].format.bold); assertNull(hello.runs[0].format.fontSizePt)
        val bold = hello.runs[1].format
        assertTrue(bold.bold); assertEquals(14f, bold.fontSizePt); assertEquals(0xFFFF0000, bold.color); assertEquals("Arial", bold.fontFamily)
        assertFalse(hello.runs[2].format.bold)
        assertEquals(TextAlign.LEFT, hello.format.align); assertEquals(ParagraphStyle.NORMAL, hello.format.style)
        assertEquals(TextAlign.RIGHT, paras[2].format.align)
    }

    @Test
    fun indentsInTwipsAndInCharacters() {
        fun sprm(code: Int, v: Int) = byteArrayOf((code and 0xFF).toByte(), (code ushr 8).toByte(), (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        // Paragraph 2: left 720, hanging 360, right 360 twips; paragraph 3: right-aligned, first line two characters.
        val doc = DocReader.read(ByteArrayInputStream(buildDoc(
            papx2 = sprm(0x845E, 720) + sprm(0x8460, -360) + sprm(0x845D, 360),
            papx3 = byteArrayOf(0x03, 0x24, 2) + sprm(0x4457, 200),
        )))
        val paras = doc.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        val hello = paras[1].format
        assertEquals(36f, hello.indentLeftPt); assertEquals(-18f, hello.firstLineIndentPt); assertEquals(18f, hello.indentRightPt)
        assertEquals(0f, paras[0].format.indentLeftPt)
        val right = paras[2].format
        assertEquals(TextAlign.RIGHT, right.align)
        assertEquals("two characters of its text", 2 * ParagraphStyle.NORMAL.sizePt, right.firstLineIndentPt)
    }

    @Test
    fun tabStopsFromThePapx() {
        // sprmPChgTabsPapx: no deletions, one stop added at 1440 twips — right-aligned (jc 2) with a dot leader (tlc 1).
        val tabs = byteArrayOf(0x0D, 0xC6.toByte(), 5, 0, 1, 0xA0.toByte(), 0x05, 0x0A)
        val doc = DocReader.read(ByteArrayInputStream(buildDoc(papx2 = tabs)))
        val hello = doc.blocks.filterIsInstance<ParagraphBlock>()[1].paragraph
        assertEquals(listOf(com.therealityteam.aeropages.model.TabStop(72f, com.therealityteam.aeropages.model.TabAlign.RIGHT, com.therealityteam.aeropages.model.TabLeader.DOT)), hello.format.tabStops)
    }
}
