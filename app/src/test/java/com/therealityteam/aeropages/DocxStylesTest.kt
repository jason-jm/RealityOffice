package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Paragraph styles: styles.xml drives formatting on read, survives a round trip, and can be modified in the editor. */
class DocxStylesTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="$w">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="24"/></w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="a"><w:name w:val="Normal"/><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="1"><w:name w:val="heading 1"/><w:basedOn w:val="a"/><w:pPr><w:spacing w:before="240"/><w:jc w:val="center"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="2E74B5"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Note"><w:name w:val="Note"/><w:basedOn w:val="1"/><w:rPr><w:i/><w:spacing w:val="20"/><w:sz w:val="20"/></w:rPr></w:style>
<w:style w:type="character" w:styleId="Strong"><w:name w:val="Strong"/><w:rPr><w:b/></w:rPr></w:style>
</w:styles>"""

    private val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$w"><w:body>
<w:p><w:pPr><w:pStyle w:val="1"/></w:pPr><w:r><w:t>Head</w:t></w:r><w:r><w:rPr><w:b w:val="0"/><w:sz w:val="20"/></w:rPr><w:t>small</w:t></w:r></w:p>
<w:p><w:r><w:t>Body</w:t></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="Note"/><w:jc w:val="right"/></w:pPr><w:r><w:t>note</w:t></w:r></w:p>
<w:sectPr/></w:body></w:document>"""

    private fun docx(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in listOf("word/document.xml" to documentXml, "word/styles.xml" to stylesXml)) {
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun paras(d: Document) = d.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }

    @Test
    fun stylesXmlDrivesFormattingOnRead() {
        val doc = DocxReader.read(ByteArrayInputStream(docx()))
        val (head, body, note) = paras(doc)
        assertEquals(ParagraphStyle.HEADING1, head.format.style); assertEquals("1", head.format.rawStyleId)
        val h0 = head.runs[0].format
        assertTrue(h0.bold); assertEquals(16f, h0.fontSizePt); assertEquals("Arial", h0.fontFamily); assertEquals(0xFF2E74B5, h0.color)
        val h1 = head.runs[1].format
        assertFalse("explicit w:b val=0 wins over the bold style", h1.bold); assertEquals(10f, h1.fontSizePt); assertEquals(0xFF2E74B5, h1.color)
        assertEquals(TextAlign.CENTER, head.format.align); assertEquals(12f, head.format.spaceBeforePt); assertEquals(6f, head.format.spaceAfterPt)
        assertEquals(1.15f, head.format.lineSpacing, 0.001f)
        assertEquals(ParagraphStyle.NORMAL, body.format.style); assertNull(body.format.rawStyleId)
        val b0 = body.runs[0].format
        assertFalse(b0.bold); assertEquals(12f, b0.fontSizePt); assertEquals("Arial", b0.fontFamily); assertNull(b0.color)
        assertEquals(6f, body.format.spaceAfterPt); assertEquals(TextAlign.LEFT, body.format.align)
        // A foreign style based on heading 1 inherits its traits; explicit paragraph props still win.
        assertEquals(ParagraphStyle.NORMAL, note.format.style); assertEquals("Note", note.format.rawStyleId)
        val n0 = note.runs[0].format
        assertTrue(n0.italic); assertTrue(n0.bold); assertEquals(10f, n0.fontSizePt); assertEquals(0xFF2E74B5, n0.color)
        assertEquals(TextAlign.RIGHT, note.format.align); assertEquals(12f, note.format.spaceBeforePt)
        // The sheet itself.
        assertEquals("1", doc.styles.idOf(ParagraphStyle.HEADING1)); assertEquals("a", doc.styles.idOf(ParagraphStyle.NORMAL))
        assertEquals("Heading2", doc.styles.idOf(ParagraphStyle.HEADING2))
        assertNull("character styles are not paragraph styles", doc.styles["Strong"])
        assertEquals(12f, doc.styles.defaultSizePt); assertEquals("Arial", doc.styles.defaultFontFamily)
        assertEquals(0, doc.styles["1"]!!.outlineLevel)
        assertEquals(16f, doc.styles.resolve(ParagraphStyle.HEADING1).sizePt)
        assertEquals(16f, doc.styles.resolve(ParagraphStyle.HEADING2).sizePt) // built-in default, not in the file
    }

    @Test
    fun sheetRoundTripsThroughStylesXml() {
        val doc = DocxReader.read(ByteArrayInputStream(docx()))
        val ed = DocumentEditor(); ed.load(doc)
        ed.modifyStyle(ParagraphStyle.HEADING1) { it.fontFamily = "Georgia" }
        val bos = ByteArrayOutputStream()
        DocxWriter.write(ed.document, bos)
        val bytes = bos.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/styles.xml"]!!, Charsets.UTF_8)
        assertTrue(xml, xml.contains("""<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="24"/>"""))
        assertTrue(xml, xml.contains("""<w:style w:type="paragraph" w:default="1" w:styleId="a"><w:name w:val="Normal"/>"""))
        assertTrue(xml, xml.contains("""w:styleId="1"><w:name w:val="heading 1"/><w:basedOn w:val="a"/><w:qFormat/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="240"/><w:jc w:val="center"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:rFonts w:ascii="Georgia" w:hAnsi="Georgia" w:eastAsia="Georgia"/><w:b/><w:bCs/><w:color w:val="2E74B5"/><w:sz w:val="32"/>"""))
        assertTrue(xml, xml.contains("""w:styleId="Note"><w:name w:val="Note"/><w:basedOn w:val="1"/>"""))
        assertTrue("built-ins the file lacked are written with their defaults", xml.contains("""w:styleId="Heading2"><w:name w:val="heading 2"/>"""))
        assertFalse("no second Normal", xml.contains("""w:styleId="Normal""""))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        val (head, body, note) = paras(back)
        assertEquals("1", head.format.rawStyleId); assertEquals(ParagraphStyle.HEADING1, head.format.style)
        assertEquals("Georgia", head.runs[0].format.fontFamily); assertEquals(16f, head.runs[0].format.fontSizePt); assertTrue(head.runs[0].format.bold)
        assertFalse(head.runs[1].format.bold); assertEquals(10f, head.runs[1].format.fontSizePt)
        assertEquals("Arial", body.runs[0].format.fontFamily); assertEquals(12f, body.runs[0].format.fontSizePt)
        assertEquals("Note", note.format.rawStyleId); assertEquals("1", back.styles["Note"]!!.basedOn); assertTrue(note.runs[0].format.italic)
        assertEquals("Georgia", back.styles.resolve(ParagraphStyle.HEADING1).fontFamily)
        assertEquals("a", back.styles.idOf(ParagraphStyle.NORMAL))
    }

    @Test
    fun modifyingAStyleRebakesMatchingRunsAndUndoes() {
        val ed = DocumentEditor()
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph.of("H1 a", CharacterFormat(bold = true, fontSizePt = 20f), ParagraphFormat(style = ParagraphStyle.HEADING1))))
        val custom = Paragraph(mutableListOf(TextRun("H1 ", CharacterFormat(bold = true, fontSizePt = 20f)), TextRun("red", CharacterFormat(bold = true, fontSizePt = 20f, color = 0xFFCC0000))), ParagraphFormat(style = ParagraphStyle.HEADING1))
        doc.blocks.add(ParagraphBlock(custom))
        val body = Paragraph.of("body", CharacterFormat(fontSizePt = 11f, italic = true, fontFamily = "Georgia"))
        doc.blocks.add(ParagraphBlock(body))
        ed.load(doc)
        fun h1() = paras(ed.document)[0].runs[0].format
        ed.modifyStyle(ParagraphStyle.HEADING1) { it.sizePt = 24f; it.color = 0xFF2E74B5 }
        assertEquals(24f, h1().fontSizePt); assertEquals(0xFF2E74B5, h1().color)
        assertEquals("direct colour kept", 0xFFCC0000, custom.runs[1].format.color); assertEquals(24f, custom.runs[1].format.fontSizePt)
        assertEquals(11f, body.runs[0].format.fontSizePt)
        assertEquals(24f, ed.resolvedStyle(ParagraphStyle.HEADING1).sizePt)
        ed.undo()
        assertEquals(20f, h1().fontSizePt); assertNull(h1().color); assertEquals(20f, ed.resolvedStyle(ParagraphStyle.HEADING1).sizePt)
        ed.redo()
        assertEquals(24f, h1().fontSizePt); assertEquals(24f, ed.resolvedStyle(ParagraphStyle.HEADING1).sizePt)
        // Normal is the base of Heading 1: updating it from the body paragraph flows into the headings.
        ed.setSelection(2, 0, 0)
        ed.updateStyleFromSelection()
        assertTrue(ed.resolvedStyle(ParagraphStyle.NORMAL).italic); assertEquals("Georgia", ed.resolvedStyle(ParagraphStyle.HEADING1).fontFamily)
        assertTrue(h1().italic); assertEquals("Georgia", h1().fontFamily); assertEquals(24f, h1().fontSizePt)
        assertEquals(11f, ed.resolvedStyle(ParagraphStyle.NORMAL).sizePt)
        ed.resetStyle(ParagraphStyle.NORMAL)
        assertFalse(h1().italic); assertNull(h1().fontFamily); assertTrue(h1().bold)
        assertFalse("after 'update from selection' the body paragraph's italic belongs to the style, so the reset removes it", body.runs[0].format.italic)
    }

    @Test
    fun applyingAStyleUsesTheDocumentDefinition() {
        val ed = DocumentEditor(); ed.load(Document.blank()); ed.setSelection(0, 0, 0); ed.replaceText(0, "", "Section")
        ed.modifyStyle(ParagraphStyle.HEADING2) { it.fontFamily = "Georgia"; it.align = TextAlign.CENTER }
        ed.setStyle(ParagraphStyle.HEADING2)
        val f = ed.activeParagraph.runs[0].format
        assertEquals("Georgia", f.fontFamily); assertEquals(16f, f.fontSizePt); assertTrue(f.bold)
        assertEquals(TextAlign.CENTER, ed.activeParagraph.format.align); assertEquals("Heading2", ed.activeParagraph.format.rawStyleId)
        ed.setStyle(ParagraphStyle.NORMAL)
        assertFalse(ed.activeParagraph.runs[0].format.bold); assertEquals(11f, ed.activeParagraph.runs[0].format.fontSizePt)
        assertEquals("direct font survives a style change", "Georgia", ed.activeParagraph.runs[0].format.fontFamily)
    }
}
