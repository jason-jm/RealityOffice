package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.model.HeaderFooters
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextBoxBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A .doc's other stories — headers and footers, footnotes and endnotes, text boxes — and its section's
 * page, read from a file LibreOffice wrote (`resources/doc/stories.doc`, converted from a docx).
 */
class DocStoriesTest {
    private val doc = DocReader.read(javaClass.getResourceAsStream("/doc/stories.doc")!!)

    @Test
    fun headersAndFootersWithTheirPageFields() {
        assertEquals("Quarterly report – page ${HeaderFooters.PAGE} of ${HeaderFooters.PAGES}", doc.header!!.plainText())
        assertEquals(TextAlign.RIGHT, doc.header!!.format.align)
        assertEquals("Confidential — AeroDesk", doc.footer!!.plainText())
        assertEquals(TextAlign.CENTER, doc.footer!!.format.align)
    }

    @Test
    fun notesAndTheirMarkers() {
        assertEquals(listOf("Revenue counts every region."), doc.footnotes.map { it.text })
        assertEquals(listOf("Figures are unaudited."), doc.endnotes.map { it.text })
        val first = (doc.blocks[0] as ParagraphBlock).paragraph
        assertEquals("Revenue grew in every region[1] and costs fell[i].", first.plainText())
        assertTrue("markers in superscript", first.runs.filter { it.text == "[1]" || it.text == "[i]" }.all { it.format.superscript })
    }

    @Test
    fun textBoxesAfterTheirAnchorAndTheSectionsPage() {
        val box = doc.blocks[2] as TextBoxBlock
        assertEquals(listOf("Boxed note: see the appendix."), box.paragraphs.map { it.plainText() })
        // 200 × 40 pt with Word's insets (7.2 pt at the sides, 3.6 pt above and below): as wide and tall as
        // the app's own 8 pt inset leaves the text that room.
        assertEquals(200f + 16f - 14.4f, box.widthPt!!, 0.5f); assertEquals(40f + 16f - 7.2f, box.heightPt!!, 0.5f)
        // LibreOffice writes the box's outline as a shape of its own beside the text's see-through one.
        assertEquals(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, box.shape?.type)
        assertEquals("The next paragraph anchors a text box.", (doc.blocks[1] as ParagraphBlock).paragraph.plainText())
        assertEquals("Curly “quotes” – dashes — and an ellipsis…", (doc.blocks[3] as ParagraphBlock).paragraph.plainText())
        // A4, Word's 1.25-inch side margins, the header 1.5 cm and the footer 1.75 cm from the edges.
        assertEquals(595.3f, doc.page.widthPt, 0.1f); assertEquals(841.9f, doc.page.heightPt, 0.1f)
        assertEquals(90f, doc.page.marginLeftPt); assertEquals(72f, doc.page.marginTopPt)
        assertEquals(42.55f, doc.page.headerPt, 0.01f); assertEquals(49.6f, doc.page.footerPt, 0.01f)
    }

    @Test
    fun outlinesKeepTheirDashes() {
        val look = com.therealityteam.aeropages.format.ppt.OfficeArtLook()
        assertEquals(com.therealityteam.aeropages.model.LineDash.SOLID, look.dashOf(emptyMap()))
        assertEquals(com.therealityteam.aeropages.model.LineDash.SYS_DASH, look.dashOf(mapOf(0x01CE to 1)))
        assertEquals(com.therealityteam.aeropages.model.LineDash.DASH, look.dashOf(mapOf(0x01CE to 6)))
        assertEquals(com.therealityteam.aeropages.model.LineDash.LONG_DASH_DOT_DOT, look.dashOf(mapOf(0x01CE to 10)))
    }
}
