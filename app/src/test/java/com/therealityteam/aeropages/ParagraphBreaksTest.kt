package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.PageFlow
import com.therealityteam.aeropages.editor.PageFlow.Geometry
import com.therealityteam.aeropages.editor.PageFlow.Lines
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Word's Paragraph ▸ Line and Page Breaks: keep with next, keep lines together, page break before, widow/orphan control. */
class ParagraphBreaksTest {

    private fun lines(n: Int, together: Boolean = false, widow: Boolean = true, breakBefore: Boolean = false) =
        Lines(0f, FloatArray(n) { (it + 1) * 10f }, 0f, keepTogether = together, widowControl = widow, breakBefore = breakBefore)

    private fun shape(pages: List<PageFlow.Page>) = pages.map { p -> p.pieces.map { Triple(it.item, it.from, it.to) } }

    // --- the page flow ------------------------------------------------------------------

    @Test
    fun aParagraphKeptTogetherMovesWholeInsteadOfBreaking() {
        // 5 of 8 lines would fit after the first paragraph; kept together, all 8 go to the next page.
        val pages = PageFlow.paginate(listOf(lines(5), lines(8, together = true)), Geometry(100f))
        assertEquals(listOf(listOf(Triple(0, 0, 5)), listOf(Triple(1, 0, 8))), shape(pages))
        // Taller than a page, it breaks after all.
        val tall = PageFlow.paginate(listOf(lines(5), lines(15, together = true)), Geometry(100f))
        assertEquals(listOf(Triple(1, 0, 10)), shape(tall)[1])
    }

    @Test
    fun pageBreakBeforeStartsANewPageUnlessThePageIsEmpty() {
        val pages = PageFlow.paginate(listOf(lines(2), lines(2, breakBefore = true)), Geometry(100f))
        assertEquals(listOf(listOf(Triple(0, 0, 2)), listOf(Triple(1, 0, 2))), shape(pages))
        // First on its page already: no blank page in front of it.
        assertEquals(1, PageFlow.paginate(listOf(lines(2, breakBefore = true)), Geometry(100f)).size)
    }

    @Test
    fun withoutWidowControlALoneLineMayStayAtThePageFoot() {
        // Room for one line: with control the paragraph moves whole, without it one line stays.
        val kept = PageFlow.paginate(listOf(lines(9), lines(5)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 9)), shape(kept)[0])
        val loose = PageFlow.paginate(listOf(lines(9), lines(5, widow = false)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 9), Triple(1, 0, 1)), shape(loose)[0])
    }

    @Test
    fun headingsKeepWithNextByDefaultAndAParagraphCanSayOtherwise() {
        val heading = Paragraph.of("H", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING2))
        assertTrue(PageMetrics.keepsWithNext(heading))
        heading.format.keepWithNext = false
        assertFalse(PageMetrics.keepsWithNext(heading))
        val body = Paragraph.of("b", pFormat = ParagraphFormat(keepWithNext = true))
        assertTrue(PageMetrics.keepsWithNext(body))
        assertFalse(PageMetrics.keepsWithNext(Paragraph.of("title", pFormat = ParagraphFormat(style = ParagraphStyle.TITLE))))
    }

    // --- the editor ------------------------------------------------------------------------

    @Test
    fun theToggleCommandsSetTheActiveParagraphAndUndo() {
        val d = Document()
        d.addParagraph(Paragraph.of("Chapter", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING1)))
        d.addParagraph(Paragraph.of("body"))
        val e = DocumentEditor().apply { load(d) }
        e.focusParagraph(0)
        assertTrue(e.keepsWithNext)
        e.toggleKeepWithNext()
        assertEquals(false, e.activeParagraph.format.keepWithNext)
        e.togglePageBreakBefore(); e.toggleKeepTogether(); e.toggleWidowControl()
        val f = e.activeParagraph.format
        assertTrue(f.pageBreakBefore); assertTrue(f.keepTogether); assertFalse(f.widowControl)
        repeat(4) { e.undo() }
        val g = e.document.locations()[0].paragraph.format
        assertNull(g.keepWithNext); assertFalse(g.pageBreakBefore); assertFalse(g.keepTogether); assertTrue(g.widowControl)
    }

    // --- files -----------------------------------------------------------------------------

    private fun sample(): Document {
        val d = Document()
        d.addParagraph(Paragraph.of("Chapter", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING1)))
        d.addParagraph(Paragraph.of("caption above a table", pFormat = ParagraphFormat(keepWithNext = true)))
        d.addParagraph(Paragraph.of("a quotation", pFormat = ParagraphFormat(keepTogether = true)))
        d.addParagraph(Paragraph.of("new chapter page", pFormat = ParagraphFormat(pageBreakBefore = true)))
        d.addParagraph(Paragraph.of("loose", pFormat = ParagraphFormat(widowControl = false)))
        d.addParagraph(Paragraph.of("heading not kept", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING2, keepWithNext = false)))
        return d
    }

    private fun formats(d: Document) = d.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.format }

    private fun assertBreaks(back: Document) {
        val f = formats(back)
        assertTrue("a heading keeps with next by its style", PageMetrics.keepsWithNext(back.locations()[0].paragraph))
        assertEquals(true, f[1].keepWithNext)
        assertTrue(f[2].keepTogether)
        assertTrue(f[3].pageBreakBefore)
        assertFalse(f[4].widowControl)
        assertEquals(false, f[5].keepWithNext)
        // Nothing set where nothing was asked for.
        assertTrue(f[1].widowControl); assertFalse(f[1].pageBreakBefore); assertFalse(f[1].keepTogether)
    }

    @Test
    fun wordKeepsTheBreakOptions() {
        val bos = ByteArrayOutputStream()
        DocxWriter.write(sample(), bos)
        val xml = String(java.util.zip.ZipInputStream(ByteArrayInputStream(bos.toByteArray())).let { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "word/document.xml" }
            zip.readBytes()
        })
        assertTrue(xml.contains("<w:keepNext/>")); assertTrue(xml.contains("<w:keepLines/>"))
        assertTrue(xml.contains("<w:pageBreakBefore/>")); assertTrue(xml.contains("""<w:widowControl w:val="0"/>"""))
        assertTrue(xml.contains("""<w:keepNext w:val="0"/>"""))
        assertBreaks(DocxReader.read(ByteArrayInputStream(bos.toByteArray())))
    }

    @Test
    fun openDocumentKeepsTheBreakOptions() {
        val back = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(sample(), it) }.toByteArray())
        assertBreaks(back)
        // The heading's own keep-with-next comes from its style and is not repeated on the paragraph.
        assertNull(formats(back)[0].keepWithNext)
    }
}
