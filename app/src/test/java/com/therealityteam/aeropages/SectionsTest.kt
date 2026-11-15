package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Section breaks: a document can hold several sections, each with its own page size, orientation,
 * margins and column count (Word's 分节符, OOXML `w:pPr/w:sectPr`).
 */
class SectionsTest {

    private fun editorWith(vararg texts: String): DocumentEditor {
        val doc = Document()
        texts.forEach { doc.blocks.add(ParagraphBlock(Paragraph.of(it))) }
        return DocumentEditor().also { it.load(doc) }
    }

    @Test
    fun insertingABreakSplitsTheDocumentIntoSections() {
        val e = editorWith("intro", "body", "appendix")
        assertEquals(1, e.document.sections().size)
        assertEquals(1 to 1, e.sectionPosition())

        e.focusParagraph(0)
        e.insertSectionBreak()

        assertTrue(e.document.hasSections)
        val sections = e.document.sections()
        assertEquals(2, sections.size)
        assertEquals(listOf("intro"), sections[0].first.map { (it as ParagraphBlock).paragraph.plainText() })
        assertEquals(3, sections[1].first.size)          // the new empty paragraph + body + appendix
        // The caret moved into the new section.
        assertEquals(2 to 2, e.sectionPosition())
    }

    @Test
    fun pageSetupAppliesToTheSectionBeingEdited() {
        val e = editorWith("portrait part", "landscape part")
        e.focusParagraph(0)
        e.insertSectionBreak()
        // Caret is in section 2 now: turning it landscape must not touch section 1.
        e.setOrientationLandscape(true)
        e.setColumns(2)

        val sections = e.document.sections()
        assertEquals(2, sections.size)
        assertTrue("first section stays portrait", sections[0].second.heightPt > sections[0].second.widthPt)
        assertEquals(1, sections[0].second.columns)
        assertTrue("second section is landscape", sections[1].second.widthPt > sections[1].second.heightPt)
        assertEquals(2, sections[1].second.columns)

        // Editing the first section again only changes that one.
        e.focusParagraph(0)
        e.setMargins(36f, 36f, 36f, 36f)
        assertEquals(36f, e.document.sections()[0].second.marginTopPt, 0.1f)
        assertEquals(72f, e.document.sections()[1].second.marginTopPt, 0.1f)
    }

    @Test
    fun undoRestoresTheSingleSection() {
        val e = editorWith("one", "two")
        e.focusParagraph(0)
        e.insertSectionBreak()
        assertEquals(2, e.document.sections().size)
        e.undo()
        assertEquals(1, e.document.sections().size)
        assertNull(e.document.paragraphs()[0].format.sectionBreak)
        e.redo()
        assertEquals(2, e.document.sections().size)
    }

    @Test
    fun sectionsRoundTripThroughDocx() {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph.of("portrait section").also {
            it.format.sectionBreak = PageSetup(widthPt = 595f, heightPt = 842f, marginTopPt = 72f, marginBottomPt = 72f, marginLeftPt = 72f, marginRightPt = 72f)
        }))
        doc.blocks.add(ParagraphBlock(Paragraph.of("landscape section")))
        doc.page.widthPt = 842f
        doc.page.heightPt = 595f
        doc.page.columns = 2
        doc.page.marginLeftPt = 36f

        val bytes = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/document.xml"]!!, Charsets.UTF_8)
        // The break rides inside the paragraph's properties, the last section closes the body.
        assertTrue("paragraph section break", xml.contains("<w:sectPr>") && xml.indexOf("<w:sectPr>") < xml.indexOf("</w:pPr>"))
        assertTrue("two sectPr elements", Regex("<w:sectPr>").findAll(xml).count() == 2)

        val back = DocxReader.read(bytes.inputStream())
        val first = back.paragraphs()[0].format.sectionBreak
        assertNotNull("break survived", first)
        assertEquals(595f, first!!.widthPt, 1f)
        assertEquals(842f, first.heightPt, 1f)
        assertEquals(842f, back.page.widthPt, 1f)
        assertEquals(2, back.page.columns)
        assertEquals(36f, back.page.marginLeftPt, 1f)
        assertEquals(2, back.sections().size)
    }

    @Test
    fun setupAtWalksForwardToTheNextBreak() {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph.of("a")))
        doc.blocks.add(ParagraphBlock(Paragraph.of("b").also { it.format.sectionBreak = PageSetup(widthPt = 400f) }))
        doc.blocks.add(ParagraphBlock(Paragraph.of("c")))
        assertEquals(400f, doc.setupAt(0).widthPt, 0.1f)   // before the break: the break's setup
        assertEquals(400f, doc.setupAt(1).widthPt, 0.1f)
        assertEquals(doc.page.widthPt, doc.setupAt(2).widthPt, 0.1f)  // after it: the final section
    }
}
