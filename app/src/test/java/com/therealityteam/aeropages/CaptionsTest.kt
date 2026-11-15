package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.CrossReferences
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Fields
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Captions numbered by sequence fields and cross-references to them, to headings and bookmarks (Word's SEQ, REF, PAGEREF). */
class CaptionsTest {
    private fun document(): Document {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Results").also { it.format.style = ParagraphStyle.HEADING1 })
        doc.addParagraph(Paragraph.of("The first chart."))
        doc.addParagraph(Paragraph.of("The second chart."))
        doc.addParagraph(Paragraph.of("See the figure."))
        return doc
    }

    private fun text(e: DocumentEditor, i: Int) = e.document.locations()[i].paragraph.plainText()

    @Test
    fun captionsNumberThemselves() {
        val e = DocumentEditor().apply { load(document()) }
        e.setSelection(1, 0, 0); e.insertCaption("Figure")
        assertEquals("Figure 1 ", text(e, 2))
        e.typeOver("Sales")
        assertEquals("Figure 1 Sales", text(e, 2))
        e.setSelection(3, 0, 0); e.insertCaption("Figure")
        assertEquals("Figure 2 ", text(e, 4))
        // One above the first: the sequence is numbered again in text order.
        e.setSelection(1, 0, 0); e.insertCaption("Figure", below = false)
        assertEquals(listOf("Figure 1 ", "Figure 2 Sales", "Figure 3 "), listOf(text(e, 1), text(e, 3), text(e, 5)))
        // Other labels number on their own, in their own formats; a caption without its label is just the number.
        e.setSelection(5, 0, 0); e.insertCaption("Table", format = "ALPHABETIC")
        e.setSelection(6, 0, 0); e.insertCaption("Table", format = "ALPHABETIC", withLabel = false)
        assertEquals(listOf("Table A ", "B "), listOf(text(e, 6), text(e, 7)))
        assertEquals(listOf("Figure", "Table"), CrossReferences.labels(e.document))
        // Captions take Word's Caption style.
        assertEquals("Caption", e.document.locations()[3].paragraph.format.rawStyleId)
        e.undo()
        assertEquals(8, e.document.locations().size)
    }

    @Test
    fun crossReferencesFollowWhatTheyNameUntilUpdated() {
        val e = DocumentEditor().apply { load(document()) }
        e.setSelection(1, 0, 0); e.insertCaption("Figure"); e.typeOver("Sales")
        e.setSelection(3, 0, 0); e.insertCaption("Figure"); e.typeOver("Costs")
        val costs = CrossReferences.captions(e.document, "Figure")[1]
        assertEquals("Figure 2 Costs", costs.title)
        // At the end of "See the figure.": its label and number, its text, above / below, the heading, its page.
        fun refAt(t: CrossReferences.Target, c: CrossReferences.Content) {
            val last = e.document.locations().lastIndex
            e.setSelection(last, Int.MAX_VALUE, Int.MAX_VALUE)
            e.typeOver(" ")
            e.insertCrossReference(t, c, link = true)
        }
        e.pageOfLocation = { i -> if (i < 3) 1 else 2 }
        refAt(costs, CrossReferences.Content.LABEL_NUMBER)
        refAt(CrossReferences.captions(e.document, "Figure")[1], CrossReferences.Content.CAPTION_TEXT)
        refAt(CrossReferences.captions(e.document, "Figure")[0], CrossReferences.Content.ABOVE_BELOW)
        refAt(CrossReferences.headings(e.document)[0], CrossReferences.Content.TEXT)
        refAt(CrossReferences.headings(e.document)[0], CrossReferences.Content.PAGE)
        val last = e.document.locations().lastIndex
        assertEquals("See the figure. Figure 2 Costs above Results 1", text(e, last))
        // Each is a field, linking to its bookmark; typing after one is not part of it.
        val placed = Fields.placed(e.document).filter { it.field.kind != "SEQ" }
        assertEquals(5, placed.size)
        val p = e.document.locations()[last].paragraph
        assertTrue(p.runs.filter { it.format.field != null }.all { it.format.link?.startsWith("#_Ref") == true })
        e.setSelection(last, Int.MAX_VALUE, Int.MAX_VALUE); e.typeOver(".")
        assertNull(e.document.locations()[last].paragraph.runs.last().format.field)
        assertNull("nor its link", e.document.locations()[last].paragraph.runs.last().format.link)
        e.undo()
        // A figure put in front: the reference to "Figure 2" says so until the fields are updated.
        e.setSelection(1, 0, 0); e.insertCaption("Figure", below = false)
        assertTrue(text(e, e.document.locations().lastIndex), text(e, e.document.locations().lastIndex).contains("Figure 3 Costs"))
        // (Insert Caption updates the sequences and references: Word's own captions do too once updated.)
        e.updateFields()
        assertEquals("See the figure. Figure 3 Costs above Results 1", text(e, e.document.locations().lastIndex))
        // The page as its section numbers it: front matter in roman numerals shows "i", not the page's place.
        e.pageLabelOf = { n -> listOf("i", "ii").getOrElse(n - 1) { n.toString() } }
        e.updateFields()
        assertEquals("See the figure. Figure 3 Costs above Results i", text(e, e.document.locations().lastIndex))
    }

    @Test
    fun wordKeepsTheFields() {
        val e = DocumentEditor().apply { load(document()) }
        e.setSelection(1, 0, 0); e.insertCaption("Figure"); e.typeOver("Sales")
        val last = e.document.locations().lastIndex
        e.setSelection(last, Int.MAX_VALUE, Int.MAX_VALUE)
        e.typeOver(" ")
        e.insertCrossReference(CrossReferences.captions(e.document, "Figure")[0], CrossReferences.Content.LABEL_NUMBER, link = true)
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray()
        val xml = entry(bytes, "word/document.xml")
        assertTrue(xml.contains("""<w:instrText xml:space="preserve"> SEQ Figure \* ARABIC </w:instrText>"""))
        assertTrue(Regex("""<w:instrText xml:space="preserve"> REF _Ref\d+ \\h </w:instrText>""").containsMatchIn(xml))
        assertTrue(Regex("""<w:bookmarkStart w:id="\d+" w:name="_Ref\d+"/>""").containsMatchIn(xml))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        val fields = Fields.placed(back)
        assertEquals(listOf("SEQ", "REF"), fields.map { it.field.kind })
        assertEquals("SEQ Figure \\* ARABIC", fields[0].field.code)
        assertEquals("See the figure. Figure 1", back.locations()[fields[1].para].paragraph.plainText())
        assertTrue(back.locations()[fields[1].para].paragraph.runs.first { it.format.field != null }.format.link!!.startsWith("#_Ref"))
        // Still live after the round trip.
        assertEquals("Figure 1", Fields.results(back)[fields[1].field])
        // ODF's sequence and bookmark reference.
        val odt = ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdtWriter.write(e.document, it) }.toByteArray()
        val content = entry(odt, "content.xml")
        assertTrue(content, content.contains("""<text:sequence text:name="Figure" text:formula="ooow:Figure+1" style:num-format="1">1</text:sequence>"""))
        assertTrue(content.contains("""<text:sequence-decl text:display-outline-level="0" text:name="Figure"/>"""))
        assertTrue(Regex("""<text:bookmark-ref text:reference-format="text" text:ref-name="_Ref\d+">Figure 1</text:bookmark-ref>""").containsMatchIn(content))
        val odfBack = com.therealityteam.aeropages.format.odf.OdtReader.read(odt)
        assertEquals(listOf("SEQ Figure \\* ARABIC", fields[1].field.code), Fields.placed(odfBack).map { it.field.code })
        assertEquals("Figure 1", Fields.results(odfBack)[Fields.placed(odfBack)[1].field])
    }

    @Test
    fun legacyWordKeepsThemToo() {
        // captions.doc: LibreOffice's .doc of the sample below — its captions' sequences and cross-references.
        val doc = com.therealityteam.aeropages.format.doc.DocReader.read(javaClass.getResourceAsStream("/doc/captions.doc")!!)
        val placed = Fields.placed(doc)
        assertEquals(listOf("SEQ", "SEQ", "REF", "REF"), placed.map { it.field.kind })
        assertEquals("Figure", placed[0].field.target)
        assertEquals("See the figure. Figure 2 shows the costs; Results sums them up.", doc.locations()[placed[2].para].paragraph.plainText())
        // Live: the reference reads its bookmark again, the sequences count again.
        val results = Fields.results(doc)
        assertEquals(listOf("1", "2", "Figure 2", "Results"), placed.map { results[it.field] })
    }

    private fun entry(zip: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) { val en = z.nextEntry ?: break; if (en.name == name) return String(z.readBytes()) }
        }
        error("no $name")
    }

    /** With `-Daero.sampleDir`: a document with captions and cross-references, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = DocumentEditor().apply { load(document()) }
        e.setSelection(1, 0, 0); e.insertCaption("Figure"); e.typeOver("Sales by month")
        e.setSelection(3, 0, 0); e.insertCaption("Figure"); e.typeOver("Costs by month")
        val last = e.document.locations().lastIndex
        e.setSelection(last, Int.MAX_VALUE, Int.MAX_VALUE); e.typeOver(" ")
        e.insertCrossReference(CrossReferences.captions(e.document, "Figure")[1], CrossReferences.Content.LABEL_NUMBER, link = true)
        e.typeOver(" shows the costs; ")
        e.insertCrossReference(CrossReferences.headings(e.document)[0], CrossReferences.Content.TEXT, link = true)
        e.typeOver(" sums them up.")
        File(dir, "captions.docx").outputStream().use { DocxWriter.write(e.document, it) }
        File(dir, "captions.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(e.document, it) }
    }
}
