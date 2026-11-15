package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TextBoxBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Text boxes: Word's wps shapes (wrapped in mc:AlternateContent with a VML fallback that repeats the
 * same text) and ODF draw:text-box frames become [TextBoxBlock]s after their anchor paragraph.
 */
class TextBoxTest {

    private fun docxOf(bodyXml: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write("""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(bodyXml.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun entry(bytes: ByteArray, name: String): String {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = zin.nextEntry ?: break; if (e.name == name) return zin.readBytes().decodeToString() }
        error("no $name")
    }

    private val wordTextBox = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml">
<w:body>
<w:p><w:r><w:t>Before the box</w:t></w:r></w:p>
<w:p><w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><wp:anchor><wp:extent cx="2540000" cy="1270000"/><a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp><wps:txbx><w:txbxContent><w:p><w:r><w:t>Inside the box</w:t></w:r></w:p><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Second box line</w:t></w:r></w:p></w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice><mc:Fallback><w:pict><v:shape><v:textbox><w:txbxContent><w:p><w:r><w:t>Inside the box</w:t></w:r></w:p><w:p><w:r><w:t>Second box line</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></mc:Fallback></mc:AlternateContent></w:r><w:r><w:t> after the box in the same paragraph</w:t></w:r></w:p>
<w:p><w:r><w:t>After</w:t></w:r></w:p>
</w:body></w:document>"""

    @Test
    fun wordTextBoxIsReadOnceWithItsAnchorParagraphKept() {
        val doc = DocxReader.read(ByteArrayInputStream(docxOf(wordTextBox)))
        val kinds = doc.blocks.map { it::class.simpleName }
        assertEquals(listOf("ParagraphBlock", "ParagraphBlock", "TextBoxBlock", "ParagraphBlock"), kinds)
        assertEquals("Before the box", (doc.blocks[0] as ParagraphBlock).paragraph.plainText())
        assertEquals(" after the box in the same paragraph", (doc.blocks[1] as ParagraphBlock).paragraph.plainText())
        val box = doc.blocks[2] as TextBoxBlock
        assertEquals(listOf("Inside the box", "Second box line"), box.paragraphs.map { it.plainText() })
        assertTrue("run formatting inside the box survives", box.paragraphs[1].runs[0].format.bold)
        assertEquals(200f, box.widthPt!!, 0.5f)
        assertEquals(100f, box.heightPt!!, 0.5f)
        assertEquals("After", (doc.blocks[3] as ParagraphBlock).paragraph.plainText())
        // The text box's paragraphs are part of the reading order (find/replace, word count).
        assertEquals(listOf("Before the box", " after the box in the same paragraph", "Inside the box", "Second box line", "After"), doc.paragraphs().map { it.plainText() })
    }

    @Test
    fun legacyVmlTextBoxWithoutAlternateContentIsRead() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml"><w:body>
<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent><w:p><w:r><w:t>Old style box</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>
</w:body></w:document>"""
        val doc = DocxReader.read(ByteArrayInputStream(docxOf(xml)))
        assertEquals(1, doc.blocks.size)
        assertEquals("Old style box", (doc.blocks[0] as TextBoxBlock).paragraphs[0].plainText())
    }

    private fun docWithBox(): Document = Document().also {
        it.blocks.add(ParagraphBlock(Paragraph.of("Intro")))
        it.blocks.add(TextBoxBlock(mutableListOf(Paragraph.of("Callout line 1"), Paragraph.of("Callout line 2")), widthPt = 240f, heightPt = 80f))
        it.blocks.add(ParagraphBlock(Paragraph.of("Outro")))
    }

    @Test
    fun docxRoundTripKeepsTheTextBox() {
        val bos = ByteArrayOutputStream()
        DocxWriter.write(docWithBox(), bos)
        val body = entry(bos.toByteArray(), "word/document.xml")
        assertTrue(body.contains("<wps:txbx><w:txbxContent>"))
        assertTrue(body.contains("""<wp:extent cx="${(240 * 12700).toLong()}" cy="${(80 * 12700).toLong()}"/>"""))
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(listOf("ParagraphBlock", "TextBoxBlock", "ParagraphBlock"), back.blocks.map { it::class.simpleName })
        val box = back.blocks[1] as TextBoxBlock
        assertEquals(listOf("Callout line 1", "Callout line 2"), box.paragraphs.map { it.plainText() })
        assertEquals(240f, box.widthPt!!, 0.5f); assertEquals(80f, box.heightPt!!, 0.5f)
    }

    @Test
    fun odtRoundTripKeepsTheTextBox() {
        val bos = ByteArrayOutputStream()
        OdtWriter.write(docWithBox(), bos)
        val content = entry(bos.toByteArray(), "content.xml")
        assertTrue(content.contains("<draw:text-box>"))
        val back = OdtReader.read(bos.toByteArray())
        assertEquals(listOf("ParagraphBlock", "TextBoxBlock", "ParagraphBlock"), back.blocks.map { it::class.simpleName })
        val box = back.blocks[1] as TextBoxBlock
        assertEquals(listOf("Callout line 1", "Callout line 2"), box.paragraphs.map { it.plainText() })
        assertEquals(240f, box.widthPt!!, 0.5f)
    }

    @Test
    fun insertTypeAndUndoInsideATextBox() {
        val ed = DocumentEditor().also { it.load(Document().also { d -> d.blocks.add(ParagraphBlock(Paragraph.of("Body"))) }) }
        ed.focusParagraph(0)
        ed.insertTextBox()
        assertTrue(ed.activeInTextBox)
        // The body, the box, and — as Word keeps one after whatever ends a document — a paragraph after it.
        assertEquals(3, ed.document.blocks.size)
        assertTrue(ed.document.blocks[2] is ParagraphBlock)
        val box = ed.document.blocks[1] as TextBoxBlock
        assertTrue(ed.activeParagraph === box.paragraphs[0])
        ed.insertAtCaret("Note")
        assertEquals("Note", box.paragraphs[0].plainText())
        // Enter inside the box adds a paragraph to the box, not to the document.
        ed.splitParagraph(ed.active, 4)
        assertEquals(2, (ed.document.blocks[1] as TextBoxBlock).paragraphs.size)
        assertEquals(3, ed.document.blocks.size)
        ed.undo(); ed.undo(); ed.undo()
        assertEquals(1, ed.document.blocks.size)
        assertFalse(ed.activeInTextBox)
    }
}
