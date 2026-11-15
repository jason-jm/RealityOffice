package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Odd/even and first-page headers and footers: page resolution, editor toggles and the docx round trip. */
class HeaderVariantsTest {

    @Test
    fun pagesResolveTheirVariant() {
        val d = Document.blank()
        d.header = Paragraph.of("odd"); d.evenHeader = Paragraph.of("even"); d.firstHeader = Paragraph.of("first")
        assertEquals("odd", d.headerFor(1)?.plainText()); assertEquals("odd", d.headerFor(2)?.plainText())
        d.differentOddEven = true
        assertEquals("odd", d.headerFor(1)?.plainText()); assertEquals("even", d.headerFor(2)?.plainText()); assertEquals("odd", d.headerFor(3)?.plainText())
        d.differentFirstPage = true
        assertEquals("first", d.headerFor(1)?.plainText()); assertEquals("even", d.headerFor(2)?.plainText()); assertEquals("odd", d.headerFor(3)?.plainText())
        assertNull(d.footerFor(1)); assertTrue(d.hasAnyHeader)
    }

    @Test
    fun editorTogglesSeedEvenHeadersAndUndo() {
        val ed = DocumentEditor(); ed.load(Document.blank()); ed.setSelection(0, 0, 0)
        ed.setHeaderText("Report")
        ed.toggleOddEvenHeaders()
        assertTrue(ed.document.differentOddEven)
        assertEquals("even pages start from the default header", "Report", ed.document.evenHeader?.plainText())
        assertEquals(DocumentEditor.HeaderKind.EVEN, ed.headerKindFor(2)); assertEquals(DocumentEditor.HeaderKind.DEFAULT, ed.headerKindFor(3))
        ed.setHeaderText("Report — even", DocumentEditor.HeaderKind.EVEN)
        assertEquals("Report", ed.document.header?.plainText()); assertEquals("Report — even", ed.document.evenHeader?.plainText())
        ed.toggleFirstPageHeader()
        assertEquals(DocumentEditor.HeaderKind.FIRST, ed.headerKindFor(1))
        ed.addFooter(DocumentEditor.HeaderKind.FIRST)
        assertTrue(ed.document.firstFooter != null)
        ed.undo(); assertNull(ed.document.firstFooter)
        ed.undo(); assertFalse(ed.document.differentFirstPage)
        ed.removeHeader(DocumentEditor.HeaderKind.EVEN)
        assertNull(ed.document.evenHeader); assertEquals("Report", ed.document.header?.plainText())
    }

    @Test
    fun docxRoundTripKeepsAllVariants() {
        val d = Document.blank()
        d.header = Paragraph.of("Default header"); d.footer = Paragraph.of("Page {PAGE}")
        d.evenHeader = Paragraph.of("Even header"); d.evenFooter = Paragraph.of("Even footer")
        d.firstHeader = Paragraph.of("Cover header")
        d.differentOddEven = true; d.differentFirstPage = true
        val bos = ByteArrayOutputStream()
        DocxWriter.write(d, bos)
        val parts = OfficeXml.unzip(bos.toByteArray())
        val docXml = String(parts["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(docXml.contains("""<w:headerReference w:type="default" r:id="""")); assertTrue(docXml.contains("""<w:headerReference w:type="even" r:id="""")); assertTrue(docXml.contains("""<w:headerReference w:type="first" r:id=""""))
        assertTrue(docXml.contains("""<w:footerReference w:type="even" r:id="""")); assertFalse(docXml.contains("""<w:footerReference w:type="first""""))
        assertTrue(docXml.contains("<w:titlePg/></w:sectPr>"))
        assertTrue(String(parts["word/settings.xml"]!!, Charsets.UTF_8).contains("<w:evenAndOddHeaders/>"))
        assertTrue(String(parts["word/header2.xml"]!!, Charsets.UTF_8).contains("Even header")); assertTrue(String(parts["word/header3.xml"]!!, Charsets.UTF_8).contains("Cover header"))
        val ct = String(parts["[Content_Types].xml"]!!, Charsets.UTF_8)
        assertTrue(ct.contains("/word/header3.xml")); assertTrue(ct.contains("/word/footer2.xml")); assertTrue(ct.contains("/word/settings.xml"))
        val rels = String(parts["word/_rels/document.xml.rels"]!!, Charsets.UTF_8)
        assertTrue(rels.contains("relationships/settings")); assertTrue(rels.contains("Target=\"header2.xml\""))
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals("Default header", back.header?.plainText()); assertEquals("Even header", back.evenHeader?.plainText()); assertEquals("Cover header", back.firstHeader?.plainText())
        assertEquals("Page {PAGE}", back.footer?.plainText()); assertEquals("Even footer", back.evenFooter?.plainText()); assertNull(back.firstFooter)
        assertTrue(back.differentOddEven); assertTrue(back.differentFirstPage)
        assertEquals("Cover header", back.headerFor(1)?.plainText()); assertEquals("Even header", back.headerFor(2)?.plainText()); assertEquals("Default header", back.headerFor(3)?.plainText())
        // Flags off → the variants are not written and a plain document has neither part.
        val plain = Document.blank(); plain.header = Paragraph.of("H")
        val bos2 = ByteArrayOutputStream(); DocxWriter.write(plain, bos2)
        val parts2 = OfficeXml.unzip(bos2.toByteArray())
        assertTrue(!String(parts2["word/settings.xml"]!!, Charsets.UTF_8).contains("evenAndOddHeaders")); assertNull(parts2["word/header2.xml"]); assertTrue(parts2.containsKey("word/header1.xml"))
    }
}
