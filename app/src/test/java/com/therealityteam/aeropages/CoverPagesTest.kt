package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CoverDesign
import com.therealityteam.aeropages.model.CoverPages
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.FloatFrame
import com.therealityteam.aeropages.model.FloatWrap
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextBoxBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Word's Insert ▸ Pages: cover pages (insert, replace, remove, docx's content control) and Blank Page. */
class CoverPagesTest {
    private val fields = CoverPages.Fields("[Document title]", "[Document subtitle]", "Ada Lovelace", "25 October 2026")

    private fun doc() = Document().apply {
        listOf("Introduction", "The body of the report starts here.", "It goes on.").forEach { blocks.add(ParagraphBlock(Paragraph.of(it))) }
    }

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    @Test
    fun aCoverIsInsertedReplacedAndRemovedInOneStepEach() {
        val e = DocumentEditor().apply { load(doc()) }
        e.setSelection(2, 4, 4)
        assertFalse(e.hasCoverPage)
        e.insertCoverPage(CoverDesign.SIMPLE, fields)
        assertTrue(e.hasCoverPage)
        val end = CoverPages.end(e.document)!!
        assertTrue("the cover ends in a page break", (e.document.blocks[end] as ParagraphBlock).paragraph.isPageBreak())
        assertEquals("the document's own text follows it", "Introduction", (e.document.blocks[end + 1] as ParagraphBlock).paragraph.plainText())
        val title = e.activeParagraph
        assertEquals(ParagraphStyle.TITLE, title.format.style)
        assertEquals("the title placeholder is chosen whole", 0 to "[Document title]".length, e.selStart to e.selEnd)
        assertNotNull("Simple: a rule under the title", title.format.borders?.bottom)
        assertTrue("the cover page carries no header or footer, as in Word", e.document.differentFirstPage)
        // Another design replaces it: still one cover, one page break before the text.
        e.insertCoverPage(CoverDesign.BAND, fields)
        val breaks = e.document.blocks.takeWhile { !(it is ParagraphBlock && it.paragraph.plainText() == "Introduction") }
            .count { it is ParagraphBlock && it.paragraph.isPageBreak() }
        assertEquals(1, breaks)
        val band = e.document.blocks.filterIsInstance<TextBoxBlock>().single()
        assertEquals("the band sits behind the text, placed on the page", FloatWrap.BEHIND, band.floating!!.wrap)
        assertEquals(FloatFrame.PAGE, band.floating!!.vFrom)
        e.undo()
        assertNotNull("undo brings the Simple cover back", (e.document.blocks.first() as ParagraphBlock).paragraph.format.borders)
        assertTrue(e.removeCoverPage())
        assertFalse(e.hasCoverPage)
        assertEquals("Introduction", (e.document.blocks.first() as ParagraphBlock).paragraph.plainText())
        assertFalse("nothing to remove", e.removeCoverPage())
        e.undo()
        assertTrue(e.hasCoverPage)
    }

    @Test
    fun theDesignsSetOutTheirParts() {
        val pg = Document().page
        for (d in CoverDesign.entries) {
            val blocks = CoverPages.blocks(d, pg, fields, 0xFF2F5496)
            val text = blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() }
            assertTrue(d.name, text.containsAll(listOf("[Document title]", "[Document subtitle]", "Ada Lovelace")))
            assertTrue("$d ends in a page break", (blocks.last() as ParagraphBlock).paragraph.isPageBreak())
        }
        // Sideline: the title and subtitle share a left border; Color Block: a rectangle as large as the page.
        val side = CoverPages.blocks(CoverDesign.SIDELINE, pg, fields, 0xFF2F5496).filterIsInstance<ParagraphBlock>()
        assertEquals(2, side.count { it.paragraph.format.borders?.left != null })
        val block = CoverPages.blocks(CoverDesign.BLOCK, pg, fields, 0xFF2F5496).filterIsInstance<TextBoxBlock>().single()
        assertEquals(pg.widthPt to pg.heightPt, block.widthPt to block.heightPt)
        assertTrue(CoverPages.isPlaceholder("[Author name]")); assertFalse(CoverPages.isPlaceholder("Ada"))
    }

    @Test
    fun blankPageIsAPageOfItsOwn() {
        val e = DocumentEditor().apply { load(doc()) }
        e.setSelection(1, 8, 8)
        e.insertBlankPage()
        val p = e.document.paragraphs().map { it.plainText() }
        assertEquals(listOf("Introduction", "The body", "\u000C", "", "\u000C", " of the report starts here.", "It goes on."), p)
        assertEquals("the caret on the blank page", "", e.activeParagraph.plainText())
        e.undo()
        assertEquals(3, e.document.paragraphs().size)
    }

    @Test
    fun docxKeepsTheCoverInWordsContentControl() {
        val e = DocumentEditor().apply { load(doc()) }
        e.insertCoverPage(CoverDesign.SIDELINE, fields)
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray()
        val xml = part(bytes, "word/document.xml")
        assertTrue(xml.contains("""<w:docPartGallery w:val="Cover Pages"/>"""))
        val sdtEnd = xml.indexOf("</w:sdtContent></w:sdt>")
        assertTrue("the content control closes after the page break and before the text", xml.indexOf("w:type=\"page\"") < sdtEnd && sdtEnd < xml.indexOf("Introduction"))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertTrue(back.coverPage)
        assertEquals("[Document title]", (back.blocks[CoverPages.end(back)!! - 3] as ParagraphBlock).paragraph.plainText())
        // A document without a cover has none.
        val plain = DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(doc(), it) }.toByteArray()))
        assertFalse(plain.coverPage); assertNull(CoverPages.end(plain))
    }

    /** With `-Daero.sampleDir`: each cover design before a short text, for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        for (d in CoverDesign.entries) {
            val e = DocumentEditor().apply { load(doc()) }
            e.insertCoverPage(d, CoverPages.Fields("Annual Report", "A year in review", "Ada Lovelace", "25 October 2026"))
            val name = "cover-${d.name.lowercase()}"
            File(dir, "$name.docx").outputStream().use { DocxWriter.write(e.document, it) }
            File(dir, "$name.odt").outputStream().use { OdtWriter.write(e.document, it) }
        }
    }
}
