package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.CrossReferences
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Fields
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Word's "Include chapter number" for captions: `STYLEREF 1 \s`, a separator and `SEQ … \s 1`, numbered again in each chapter. */
class CaptionChaptersTest {

    /**
     * Two chapters ("Intro", "Methods"), two figures in the first and one in the second, each with its chapter's number;
     * the headings numbered "1." and "2." by an outline list when [numbered].
     */
    private fun chaptered(numbered: Boolean = true): DocumentEditor {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Intro").also { it.format.style = ParagraphStyle.HEADING1 })
        doc.addParagraph(Paragraph.of("First chart."))
        doc.addParagraph(Paragraph.of("Second chart."))
        doc.addParagraph(Paragraph.of("Methods").also { it.format.style = ParagraphStyle.HEADING1 })
        doc.addParagraph(Paragraph.of("Third chart."))
        if (numbered) number(doc)
        val e = DocumentEditor().apply { load(doc) }
        e.setSelection(1, 0, 0); e.insertCaption("Figure", chapterSeparator = "-"); e.typeOver("Sales")
        e.setSelection(3, 0, 0); e.insertCaption("Figure", chapterSeparator = "-"); e.typeOver("Costs")
        e.setSelection(6, 0, 0); e.insertCaption("Figure", chapterSeparator = "-"); e.typeOver("Staff")
        return e
    }

    /** [doc]'s Heading 1 paragraphs numbered by one outline list. */
    private fun number(doc: Document) {
        val id = doc.lists.keys.firstOrNull() ?: doc.newListId().also { doc.lists[it] = com.therealityteam.aeropages.model.ListDef.OUTLINE }
        doc.paragraphs().filter { it.format.style == ParagraphStyle.HEADING1 }.forEach { it.format.listKind = com.therealityteam.aeropages.model.ListKind.NUMBER; it.format.listId = id }
    }

    private fun captions(doc: Document) = doc.paragraphs().map { it.plainText().trim() }.filter { it.startsWith("Figure") }

    @Test
    fun eachChapterNumbersItsOwn() {
        val e = chaptered()
        assertEquals(listOf("Figure 1-1 Sales", "Figure 1-2 Costs", "Figure 2-1 Staff"), captions(e.document))
        // A new chapter before them: the numbers follow once the fields are updated.
        e.splitParagraph(0, 0); e.setSelection(0, 0, 0); e.typeOver("Preface")
        e.document.locations()[0].paragraph.format.style = ParagraphStyle.HEADING1
        number(e.document)
        e.updateFields()
        assertEquals(listOf("Figure 2-1 Sales", "Figure 2-2 Costs", "Figure 3-1 Staff"), captions(e.document))
        // A cross-reference to "label and number" takes the chapter with it.
        val third = CrossReferences.captions(e.document, "Figure")[2]
        val last = e.document.locations().lastIndex
        e.splitParagraph(last, e.document.locations()[last].paragraph.plainText().length)
        e.setSelection(last + 1, 0, 0)
        e.insertCrossReference(third, CrossReferences.Content.LABEL_NUMBER, link = true)
        assertEquals("Figure 3-1", e.document.locations()[last + 1].paragraph.plainText())
        // A reference typed at the start of what it shows stays outside it (it would show itself).
        val g = DocumentEditor().apply { load(Document(mutableListOf()).also { it.addParagraph(Paragraph.of("Chart.")) }) }
        g.setSelection(0, 0, 0); g.insertCaption("Figure")
        val own = CrossReferences.captions(g.document, "Figure").single()
        g.setSelection(own.para, 0, 0)
        g.insertCrossReference(own, CrossReferences.Content.LABEL_NUMBER, link = true)
        assertEquals("Figure 1Figure 1", g.document.locations()[own.para].paragraph.plainText().trim())
        // A caption before any chapter has no chapter to show: Word's message.
        val f = DocumentEditor().apply { load(Document(mutableListOf()).also { it.addParagraph(Paragraph.of("Chart.")) }) }
        f.setSelection(0, 0, 0); f.insertCaption("Figure", chapterSeparator = ".")
        assertTrue(captions(f.document).single(), captions(f.document).single().contains("Error!"))
    }

    @Test
    fun anUnnumberedChapterShowsNoughtAsWordDoes() {
        // Word's "Figure 0-1": its chapter number is the heading's list number, and these headings have none.
        val e = chaptered(numbered = false)
        assertEquals(listOf("Figure 0-1 Sales", "Figure 0-2 Costs", "Figure 0-1 Staff"), captions(e.document))
        assertTrue(!Fields.chapterNumbered(e.document.locations(), com.therealityteam.aeropages.model.PageMetrics.listLabels(e.document), 2, 1))
        // Without `\s`: the chapter's heading.
        assertEquals("Methods", chapterTitle(e.document))
        // `\s` keeps only the number of a heading numbered with words ("Appendix A" → "A").
        val doc = e.document
        val id = doc.newListId()
        doc.lists[id] = com.therealityteam.aeropages.model.ListDef(List(9) { com.therealityteam.aeropages.model.ListLevel(com.therealityteam.aeropages.model.NumFormat.UPPER_LETTER, "Appendix %1") })
        doc.paragraphs().filter { it.format.style == ParagraphStyle.HEADING1 }.forEach { it.format.listKind = com.therealityteam.aeropages.model.ListKind.NUMBER; it.format.listId = id }
        Fields.update(doc)
        assertEquals(listOf("Figure A-1 Sales", "Figure A-2 Costs", "Figure B-1 Staff"), captions(doc))
    }

    /** What `STYLEREF 1` (no `\s`) shows at the end of [doc]. */
    private fun chapterTitle(doc: Document): String {
        val p = Paragraph.of("x").also { it.runs[0].format.field = Fields.of("STYLEREF 1") }
        doc.addParagraph(p)
        Fields.update(doc)
        return p.plainText().also { doc.blocks.removeAt(doc.blocks.lastIndex) }
    }

    @Test
    fun wordKeepsThem() {
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(chaptered().document, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/document.xml"]!!)
        assertTrue(xml, xml.contains(""" STYLEREF 1 \s """))
        assertTrue(xml.contains(""" SEQ Figure \* ARABIC \s 1 """))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("Figure 1-1 Sales", "Figure 1-2 Costs", "Figure 2-1 Staff"), captions(back))
        assertEquals(0, Fields.update(back))
    }

    @Test
    fun odfKeepsThemAsChapterSequences() {
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(chaptered().document, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue(content, content.contains("""<text:sequence-decl text:display-outline-level="1" text:separation-character="-" text:name="Figure"/>"""))
        // The sequence holds its own number (LibreOffice reads the value from it and shows "1-2").
        assertTrue(content, Regex("""text:name="Figure"[^>]*>2</text:sequence>""").containsMatchIn(content))
        assertTrue(!content.contains(">1-2</text:sequence>"))
        val back = OdtReader.read(bytes)
        assertEquals(listOf("Figure 1-1 Sales", "Figure 1-2 Costs", "Figure 2-1 Staff"), captions(back))
        // Read back as Word's three parts, which work out to the same.
        val kinds = back.paragraphs().first { it.plainText().startsWith("Figure 2") }.runs.mapNotNull { it.format.field?.kind }
        assertEquals(listOf("STYLEREF", "SEQ"), kinds)
        assertEquals(0, Fields.update(back))
        // A chapter's number alone is `text:chapter`.
        val doc = chaptered().document
        doc.addParagraph(Paragraph.of("2").also { it.runs[0].format.field = Fields.of("STYLEREF 1 \\s") })
        val alone = String(OfficeXml.unzip(ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray())["content.xml"]!!)
        assertTrue(alone, alone.contains("""<text:chapter text:display="number" text:outline-level="1">2</text:chapter>"""))
        val read = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray())
        assertEquals("STYLEREF 1 \\s", read.paragraphs().last().runs.single().format.field?.code)
    }

    /** With `-Daero.sampleDir`: chapter-numbered captions, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = chaptered()
        File(dir, "caption-chapters.docx").outputStream().use { DocxWriter.write(e.document, it) }
        File(dir, "caption-chapters.odt").outputStream().use { OdtWriter.write(e.document, it) }
        val n = chaptered(numbered = false)
        File(dir, "caption-chapters-plain.docx").outputStream().use { DocxWriter.write(n.document, it) }
        File(dir, "caption-chapters-plain.odt").outputStream().use { OdtWriter.write(n.document, it) }
    }
}
