package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocCompare
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Format revisions remember the look before them (Word's `w:rPrChange`): rejecting one brings it back, the revisions pane
 * says what changed, docx keeps it both ways. Compare finds text formatted otherwise, and a table's changes cell by cell.
 */
class FormatRevisionsTest {
    private fun doc(vararg lines: String) = Document(mutableListOf()).also { d -> lines.forEach { d.addParagraph(Paragraph.of(it)) } }
    private fun editor(d: Document) = DocumentEditor().apply { load(d) }
    private fun run(d: Document, text: String) = d.paragraphs().flatMap { it.runs }.first { it.text == text }
    private fun docx(d: Document) = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) } }
        error("no $name")
    }

    @Test
    fun rejectingAFormatRevisionBringsBackTheLookBeforeIt() {
        val e = editor(doc("The plan is final."))
        e.toggleTrackChanges()
        e.setSelection(0, 12, 17); e.toggleBold()
        e.setSelection(0, 12, 17); e.toggleItalic()
        val final = run(e.document, "final")
        assertTrue(final.format.formatChanged && final.format.bold && final.format.italic)
        // The second change still goes back to the look before the first.
        assertEquals(CharacterFormat().look(), final.format.before)
        // The pane says what changed.
        val r = e.revisions().single()
        assertEquals(DocumentEditor.RevKind.FORMAT, r.kind)
        assertEquals(listOf(DocumentEditor.Trait(DocumentEditor.TraitKind.BOLD, true), DocumentEditor.Trait(DocumentEditor.TraitKind.ITALIC, true)), r.traits)
        e.rejectAllChanges()
        assertTrue(e.document.paragraphs().single().runs.all { !it.format.bold && !it.format.italic && !it.format.formatChanged && it.format.before == null })
        e.undo()
        e.acceptAllChanges()
        assertTrue(run(e.document, "final").format.let { it.bold && it.italic && !it.formatChanged && it.before == null })
        // One revision rejected where it is.
        val e2 = editor(doc("Size me"))
        e2.toggleTrackChanges()
        e2.setSelection(0, 0, 4); e2.setFontSize(20f)
        assertEquals(listOf(DocumentEditor.Trait(DocumentEditor.TraitKind.SIZE, true, "20")), e2.revisions().single().traits)
        e2.rejectRevision(e2.revisions().single())
        assertNull(e2.document.paragraphs().single().runs.first().format.fontSizePt)
    }

    @Test
    fun docxKeepsTheLookBeforeTheChange() {
        val d = Document(mutableListOf())
        d.addParagraph(Paragraph(mutableListOf(
            TextRun("plain "),
            TextRun("red now", CharacterFormat(bold = true, color = 0xFFFF0000, formatChanged = true, revAuthor = "Ann", revDate = 1_760_000_000_000L,
                before = CharacterFormat(italic = true, fontSizePt = 14f))),
        )))
        val bytes = docx(d)
        val xml = part(bytes, "word/document.xml")
        assertTrue(xml, Regex("""<w:rPrChange [^>]*w:author="Ann"[^>]*><w:rPr><w:i/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:rPrChange>""").containsMatchIn(xml))
        // CT_RPr's order: rFonts, b, i, …, color, spacing, sz, highlight, u, vertAlign, then rPrChange last.
        assertTrue(xml, xml.contains("""<w:b/><w:color w:val="FF0000"/><w:rPrChange"""))
        val back = DocxReader.read(bytes.inputStream())
        val f = run(back, "red now").format
        assertTrue(f.formatChanged && f.bold)
        assertEquals("Ann", f.revAuthor)
        val b = f.before!!
        assertTrue(b.italic && !b.bold)
        assertEquals(14f, b.fontSizePt)
        assertNull(b.color)
        // Rejected: the look before.
        val e = editor(back)
        e.rejectAllChanges()
        val after = run(e.document, "red now").format
        assertTrue(after.italic && !after.bold && after.color == null && after.fontSizePt == 14f)
    }

    @Test
    fun compareFindsTextFormattedOtherwise() {
        val original = doc("The plan is final.", "Budget follows soon.")
        val revised = Document(mutableListOf())
        // Same text, "final" bold: a format revision; a changed paragraph with a word made italic beside a new one.
        revised.addParagraph(Paragraph(mutableListOf(TextRun("The plan is "), TextRun("final", CharacterFormat(bold = true)), TextRun("."))))
        revised.addParagraph(Paragraph(mutableListOf(TextRun("Budget "), TextRun("follows", CharacterFormat(italic = true)), TextRun(" later."))))
        val r = DocCompare.compare(original, revised, "Rev", now = 1_760_000_000_000L)
        assertEquals(2, r.formatting)
        assertEquals(1, r.insertions); assertEquals(1, r.deletions)
        val final = run(r.document, "final").format
        assertTrue(final.formatChanged && final.bold)
        assertEquals(false, final.before?.bold)
        assertEquals("Rev", final.revAuthor)
        val follows = run(r.document, "follows").format
        assertTrue(follows.formatChanged && follows.italic && follows.before?.italic == false)
        // Accepting gives the revised look, rejecting the original's.
        val accepted = DocumentEditor().apply { load(r.document.copyOf()); acceptAllChanges() }.document
        assertTrue(run(accepted, "final").format.bold)
        val rejected = DocumentEditor().apply { load(r.document.copyOf()); rejectAllChanges() }.document
        assertEquals(listOf("The plan is final.", "Budget follows soon."), rejected.paragraphs().map { it.plainText() })
        assertTrue(rejected.paragraphs().flatMap { it.runs }.none { it.format.bold || it.format.italic || it.format.formatChanged })
    }

    private fun table(vararg rows: List<String>) = TableBlock(Table(rows.map { r -> TableRow(r.map { TableCell(mutableListOf(Paragraph.of(it))) }.toMutableList()) }.toMutableList()))
    private fun cells(d: Document) = d.blocks.filterIsInstance<TableBlock>().single().table.rows.map { r -> r.cells.map { c -> c.content.joinToString("|") { it.plainText() } } }

    @Test
    fun compareGoesThroughATableCellByCell() {
        val original = Document(mutableListOf()).apply {
            addParagraph(Paragraph.of("Intro to the figures"))
            blocks.add(table(listOf("Region", "Sales"), listOf("North", "10"), listOf("East", "12")))
            addParagraph(Paragraph.of("End of the report"))
        }
        val revised = Document(mutableListOf()).apply {
            addParagraph(Paragraph.of("Intro to these figures"))
            blocks.add(table(listOf("Region", "Sales"), listOf("South", "10"), listOf("East", "15 units")))
            addParagraph(Paragraph.of("End of this report"))
        }
        val r = DocCompare.compare(original, revised, "Rev", now = 1_760_000_000_000L)
        // The paragraphs around the table changed in place (not removed and added), the cells word by word.
        assertEquals(3, r.document.blocks.size)
        val intro = (r.document.blocks[0] as ParagraphBlock).paragraph
        assertEquals("the", intro.runs.filter { it.format.deleted }.joinToString("") { it.text })
        assertEquals("these", intro.runs.filter { it.format.inserted }.joinToString("") { it.text })
        val t = (r.document.blocks[1] as TableBlock).table
        val south = t.rows[1].cells[0].content
        assertEquals(1, south.size)
        assertEquals("North", south[0].runs.filter { it.format.deleted }.joinToString("") { it.text })
        assertEquals("South", south[0].runs.filter { it.format.inserted }.joinToString("") { it.text })
        assertFalse(t.rows[0].cells[0].content[0].runs.any { it.format.deleted || it.format.inserted })
        assertEquals(4, r.insertions); assertEquals(4, r.deletions)
        val accepted = DocumentEditor().apply { load(r.document.copyOf()); acceptAllChanges() }.document
        assertEquals(cells(revised), cells(accepted))
        assertEquals(revised.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() }, accepted.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() })
        val rejected = DocumentEditor().apply { load(r.document.copyOf()); rejectAllChanges() }.document
        assertEquals(cells(original), cells(rejected))
        // A table reshaped stays the revised one, unmarked.
        val reshaped = Document(mutableListOf()).apply { blocks.add(table(listOf("Region", "Sales", "Share"), listOf("North", "10", "45%"))) }
        val r2 = DocCompare.compare(Document(mutableListOf()).apply { blocks.add(table(listOf("Region", "Sales"), listOf("North", "10"))) }, reshaped, "Rev")
        assertEquals(cells(reshaped), cells(r2.document))
        assertTrue(r2.document.blocks.filterIsInstance<TableBlock>().single().table.rows.flatMap { it.cells }.flatMap { it.content }.flatMap { it.runs }.none { it.format.inserted || it.format.deleted })
    }

    /** Samples (with `-Daero.sampleDir`): the original and revised report and their comparison — a word made bold, a table's cells changed. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        val o = Document(mutableListOf()).apply {
            addParagraph(Paragraph.of("Regional sales").also { it.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1 })
            addParagraph(Paragraph.of("The plan for next quarter is final and the figures below are confirmed."))
            blocks.add(table(listOf("Region", "Sales", "Note"), listOf("North", "120", "Steady"), listOf("East", "95", "New store")))
            addParagraph(Paragraph.of("Questions go to the finance team."))
        }
        val n = Document(mutableListOf()).apply {
            addParagraph(Paragraph.of("Regional sales").also { it.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1 })
            addParagraph(Paragraph(mutableListOf(TextRun("The plan for next quarter is "), TextRun("final", CharacterFormat(bold = true)),
                TextRun(" and the figures below are "), TextRun("confirmed", CharacterFormat(italic = true, color = 0xFFC00000)), TextRun("."))))
            blocks.add(table(listOf("Region", "Sales", "Note"), listOf("South", "120", "Steady"), listOf("East", "140", "New store ahead of plan")))
            addParagraph(Paragraph.of("Questions go to the finance team by Friday."))
        }
        java.io.File(dir, "format-original.docx").outputStream().use { DocxWriter.write(o, it) }
        java.io.File(dir, "format-revised.docx").outputStream().use { DocxWriter.write(n, it) }
        val result = DocCompare.compare(o, n, "Reviewer", now = 1_760_000_000_000L).document
        java.io.File(dir, "format-revisions.docx").outputStream().use { DocxWriter.write(result, it) }
    }
}
