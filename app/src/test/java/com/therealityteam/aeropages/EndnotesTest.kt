package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Endnote
import com.therealityteam.aeropages.model.Footnote
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Roman
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Endnotes: like footnotes but collected after the body, anchored by superscript roman markers
 * (`[i]`, `[ii]`…) so they never collide with footnote `[1]` anchors. Word keeps them in
 * `word/endnotes.xml`; ODF as `text:note text:note-class="endnote"`.
 */
class EndnotesTest {

    private fun editorWith(vararg texts: String): DocumentEditor {
        val doc = Document()
        texts.forEach { doc.blocks.add(ParagraphBlock(Paragraph.of(it))) }
        return DocumentEditor().also { it.load(doc) }
    }

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        val out = HashMap<String, String>()
        while (true) {
            val e = zin.nextEntry ?: break
            out[e.name] = zin.readBytes().decodeToString()
        }
        return out
    }

    @Test
    fun romanNumeralsBothWays() {
        assertEquals("i", Roman.of(1))
        assertEquals("iv", Roman.of(4))
        assertEquals("ix", Roman.of(9))
        assertEquals("xiv", Roman.of(14))
        assertEquals("xlix", Roman.of(49))
        assertEquals("mmmcmxcix", Roman.of(3999))
        assertEquals("4000", Roman.of(4000))
        assertEquals("0", Roman.of(0))
        assertEquals(4, Roman.parse("iv"))
        assertEquals(4, Roman.parse("IV"))
        assertEquals(1994, Roman.parse("mcmxciv"))
        assertNull("non-canonical numerals are not markers", Roman.parse("iiii"))
        assertNull(Roman.parse(""))
        assertNull(Roman.parse("abc"))
        (1..3999).forEach { assertEquals(it, Roman.parse(Roman.of(it))) }
    }

    @Test
    fun insertAndDeleteRenumberRomanAnchors() {
        val ed = editorWith("")
        ed.insertEndnote(); ed.insertEndnote(); ed.insertEndnote()
        assertEquals(3, ed.document.endnotes.size)
        assertEquals("[i][ii][iii]", ed.activeParagraph.plainText())
        assertTrue(ed.activeParagraph.runs.filter { it.text.startsWith("[") }.all { it.format.superscript })

        ed.deleteEndnote(ed.document.endnotes[1])
        assertEquals(2, ed.document.endnotes.size)
        assertEquals("[i][ii]", ed.activeParagraph.plainText())

        // Undo restores both the entry and its anchor.
        ed.undo()
        assertEquals(3, ed.document.endnotes.size)
        assertEquals("[i][ii][iii]", ed.activeParagraph.plainText())
    }

    @Test
    fun footnotesAndEndnotesAreIndependent() {
        val ed = editorWith("")
        ed.insertFootnote(); ed.insertEndnote(); ed.insertFootnote(); ed.insertEndnote()
        assertEquals("[1][i][2][ii]", ed.activeParagraph.plainText())
        assertEquals(2, ed.document.footnotes.size)
        assertEquals(2, ed.document.endnotes.size)

        // Deleting a footnote renumbers only digit anchors; deleting an endnote only roman ones.
        ed.deleteFootnote(ed.document.footnotes[0])
        assertEquals("[i][1][ii]", ed.activeParagraph.plainText())
        ed.deleteEndnote(ed.document.endnotes[0])
        assertEquals("[1][i]", ed.activeParagraph.plainText())
        assertEquals(1, ed.document.footnotes.size)
        assertEquals(1, ed.document.endnotes.size)
    }

    @Test
    fun editingAnEndnoteIsOneUndoStep() {
        val ed = editorWith("x")
        ed.insertEndnote()
        val en = ed.document.endnotes[0]
        ed.updateEndnote(en, "a"); ed.updateEndnote(en, "ab"); ed.updateEndnote(en, "abc")
        assertEquals("abc", ed.document.endnotes[0].text)
        ed.undo()
        assertEquals(1, ed.document.endnotes.size)
        assertFalse("typing session collapsed into one step", ed.document.endnotes[0].text == "ab")
    }

    @Test
    fun docxRoundTripKeepsEndnotesAndFootnotes() {
        val doc = Document()
        doc.footnotes.add(Footnote("脚注一"))
        doc.endnotes.add(Endnote("尾注一"))
        doc.endnotes.add(Endnote("尾注二"))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("正文", CharacterFormat()),
            TextRun("[1]", CharacterFormat(superscript = true)),
            TextRun("后续", CharacterFormat()),
            TextRun("[i]", CharacterFormat(superscript = true)),
            TextRun("再", CharacterFormat()),
            TextRun("[ii]", CharacterFormat(superscript = true)),
        ))))

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val parts = zipEntries(bos.toByteArray())
        assertTrue("endnotes part written", parts.containsKey("word/endnotes.xml"))
        assertTrue(parts["word/endnotes.xml"]!!.contains("w:type=\"separator\""))
        assertTrue(parts["word/endnotes.xml"]!!.contains("<w:endnote w:id=\"2\">"))
        assertTrue(parts["[Content_Types].xml"]!!.contains("/word/endnotes.xml"))
        assertTrue(parts["word/_rels/document.xml.rels"]!!.contains("relationships/endnotes"))
        val body = parts["word/document.xml"]!!
        assertTrue(body.contains("<w:footnoteReference w:id=\"1\"/>"))
        assertTrue(body.contains("<w:endnoteReference w:id=\"1\"/>"))
        assertTrue(body.contains("<w:endnoteReference w:id=\"2\"/>"))
        assertFalse("marker text not duplicated in body", body.contains("[i]"))

        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(listOf("脚注一"), back.footnotes.map { it.text })
        assertEquals(listOf("尾注一", "尾注二"), back.endnotes.map { it.text })
        val p = back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph
        assertEquals("正文[1]后续[i]再[ii]", p.plainText())
        assertTrue(p.runs.first { it.text == "[ii]" }.format.superscript)
    }

    @Test
    fun superscriptRomanWithoutEndnotesStaysText() {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("Chapter ", CharacterFormat()),
            TextRun("[iv]", CharacterFormat(superscript = true)),
        ))))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val parts = zipEntries(bos.toByteArray())
        assertFalse(parts.containsKey("word/endnotes.xml"))
        assertTrue(parts["word/document.xml"]!!.contains("[iv]"))
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(0, back.endnotes.size)
        assertEquals("Chapter [iv]", back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph.plainText())
    }

    @Test
    fun odtRoundTripKeepsNotesInline() {
        val doc = Document()
        doc.footnotes.add(Footnote("foot one"))
        doc.endnotes.add(Endnote("end one"))
        doc.endnotes.add(Endnote("end two"))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("Body", CharacterFormat()),
            TextRun("[1]", CharacterFormat(superscript = true)),
            TextRun(" more", CharacterFormat()),
            TextRun("[i]", CharacterFormat(superscript = true)),
            TextRun(" end", CharacterFormat()),
            TextRun("[ii]", CharacterFormat(superscript = true)),
        ))))

        val bos = ByteArrayOutputStream()
        OdtWriter.write(doc, bos)
        val content = zipEntries(bos.toByteArray())["content.xml"]!!
        assertTrue(content.contains("text:note-class=\"footnote\""))
        assertTrue(content.contains("text:note-class=\"endnote\""))
        assertTrue(content.contains("<text:note-citation>ii</text:note-citation>"))
        assertTrue(content.contains("<text:p>end two</text:p>"))
        assertFalse("markers are notes, not literal text", content.contains("[ii]"))

        val back = OdtReader.read(bos.toByteArray())
        assertEquals(listOf("foot one"), back.footnotes.map { it.text })
        assertEquals(listOf("end one", "end two"), back.endnotes.map { it.text })
        val p = back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph
        assertEquals("Body[1] more[i] end[ii]", p.plainText())
        assertTrue(p.runs.first { it.text == "[i]" }.format.superscript)
        assertFalse(p.runs.first { it.text == "Body" }.format.superscript)
    }

    @Test
    fun copyOfClonesEndnotes() {
        val doc = Document()
        doc.endnotes.add(Endnote("one"))
        val copy = doc.copyOf()
        copy.endnotes[0].text = "changed"
        assertEquals("one", doc.endnotes[0].text)
    }
}
