package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Bookmarks
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TocBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Word's Insert Table of Figures: a live list of a caption label's captions, kept through docx and ODT. */
class TableOfFiguresTest {

    /** "Results", two figures and a table, each captioned, and an appendix. */
    private fun captioned(): DocumentEditor {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Results").also { it.format.style = ParagraphStyle.HEADING1 })
        doc.addParagraph(Paragraph.of("The first chart."))
        doc.addParagraph(Paragraph.of("The second chart."))
        doc.addParagraph(Paragraph.of("The totals."))
        doc.addParagraph(Paragraph.of("Closing words."))
        doc.addParagraph(Paragraph.of("Appendix").also { it.format.style = ParagraphStyle.HEADING1 })
        val e = DocumentEditor().apply { load(doc) }
        e.setSelection(1, 0, 0); e.insertCaption("Figure"); e.typeOver("Sales")
        e.setSelection(3, 0, 0); e.insertCaption("Figure"); e.typeOver("Costs")
        e.setSelection(5, 0, 0); e.insertCaption("Table"); e.typeOver("Totals")
        return e
    }

    private fun tocs(doc: Document) = doc.blocks.filterIsInstance<TocBlock>()

    @Test
    fun itListsTheCaptionsOfOneLabel() {
        val e = captioned()
        val last = e.document.locations().lastIndex
        e.setSelection(last, 0, 0)
        e.insertTableOfFigures("Figure")
        val tof = tocs(e.document).single()
        assertEquals("Figure", tof.caption)
        assertEquals(listOf("Figure 1 Sales", "Figure 2 Costs"), e.document.tocEntries(tof).map { it.text.trim() })
        assertTrue(e.document.tocEntries(tof).all { it.figure })
        // Placed before the caret's paragraph; inserting it again moves it rather than adding another.
        assertTrue(e.document.blocks.indexOf(tof) < e.document.blocks.size - 1)
        e.setSelection(0, 0, 0)
        e.insertTableOfFigures("figure")
        assertEquals(1, tocs(e.document).size)
        assertEquals(0, e.document.blocks.indexOfFirst { it is TocBlock })
        // A table of contents is another block; Remove takes only it.
        e.insertToc()
        assertEquals(2, tocs(e.document).size)
        assertTrue(e.hasToc)
        e.removeToc()
        assertFalse(e.hasToc)
        assertEquals("Figure", tocs(e.document).single().caption)
        e.undo()
        assertTrue(e.hasToc)
        // Its entries go through _Toc bookmarks round the captions, as Word's do.
        val caption = e.document.tocEntries(tocs(e.document).first { it.caption != null }).first().paragraph
        assertTrue(Bookmarks.tocMarks(e.document)[caption.id]!!.first.startsWith("_Toc"))
    }

    @Test
    fun wordKeepsIt() {
        val e = captioned()
        e.setSelection(0, 0, 0); e.insertToc()
        e.setSelection(e.document.locations().lastIndex, 0, 0); e.insertTableOfFigures("Figure")
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/document.xml"]!!)
        assertTrue(xml, xml.contains("""<w:docPartGallery w:val="Table of Figures"/>"""))
        assertTrue(xml.contains("""<w:instrText xml:space="preserve"> TOC \h \z \c "Figure" </w:instrText>"""))
        assertTrue(xml.contains("""<w:pStyle w:val="TableofFigures"/>"""))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf(null, "Figure"), tocs(back).map { it.caption })
        assertEquals(listOf("Figure 1 Sales", "Figure 2 Costs"), back.tocEntries(tocs(back)[1]).map { it.text.trim() })
        // The cached entries are not read in as text.
        assertEquals(2, back.paragraphs().count { it.plainText().startsWith("Figure") })
    }

    @Test
    fun odfKeepsItAsAnIllustrationOrTableIndex() {
        val e = captioned()
        e.setSelection(e.document.locations().lastIndex, 0, 0)
        e.insertTableOfFigures("Figure"); e.insertTableOfFigures("Table")
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(e.document, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue(content, content.contains("""<text:illustration-index-source text:caption-sequence-name="Figure" text:caption-sequence-format="text">"""))
        assertTrue(content.contains("""<text:table-index-source text:caption-sequence-name="Table" text:caption-sequence-format="text">"""))
        val back = OdtReader.read(bytes)
        assertEquals(setOf("Figure", "Table"), tocs(back).mapTo(HashSet()) { it.caption })
        assertEquals(listOf("Table 1 Totals"), back.tocEntries(tocs(back).first { it.caption == "Table" }).map { it.text.trim() })
    }

    @Test
    fun aSavedTableShowsThePagesLastLaidOut() {
        val e = captioned()
        e.setSelection(0, 0, 0); e.insertToc()
        e.setSelection(e.document.locations().lastIndex, 0, 0); e.insertTableOfFigures("Figure")
        // As the canvas leaves them: each paragraph's page as its section numbers it.
        val doc = e.document
        doc.pageCache = doc.paragraphs().associate { it.id to (if (it.plainText().startsWith("Appendix")) "iv" else "ii") }
        val docx = String(OfficeXml.unzip(ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray())["word/document.xml"]!!)
        assertTrue(docx, docx.contains("""Appendix</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>iv</w:t></w:r>"""))
        assertTrue(docx.contains("""Figure 1 Sales</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>ii</w:t></w:r>"""))
        val odtBytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        val odt = String(OfficeXml.unzip(odtBytes)["content.xml"]!!)
        assertTrue(odt, odt.contains("Appendix<text:tab/>iv"))
        // Copies (the save's snapshot) carry them; read back, the entries are the live table's again, not text.
        assertEquals(doc.pageCache, doc.copyOf().pageCache)
        assertEquals(2, DocxReader.read(ByteArrayInputStream(docx.let { ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray() })).blocks.count { it is TocBlock })
        assertEquals(0, OdtReader.read(odtBytes).paragraphs().count { it.plainText().endsWith("iv") })
    }

    /** With `-Daero.sampleDir`: captions, a table of contents and a table of figures, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = captioned()
        e.setSelection(0, 0, 0); e.insertToc()
        e.setSelection(e.document.locations().lastIndex, 0, 0); e.insertTableOfFigures("Figure")
        File(dir, "figures.docx").outputStream().use { DocxWriter.write(e.document, it) }
        File(dir, "figures.odt").outputStream().use { OdtWriter.write(e.document, it) }
    }
}
