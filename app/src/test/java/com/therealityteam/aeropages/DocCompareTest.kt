package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocCompare
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Word's Review ▸ Compare: the revised document with the differences tracked, word by word; accepting gives the revised, rejecting the original. */
class DocCompareTest {
    private fun doc(vararg lines: String) = Document(mutableListOf()).also { d -> lines.forEach { d.addParagraph(Paragraph.of(it)) } }
    private fun texts(d: Document) = d.paragraphs().map { it.plainText() }

    private val original = doc("The quick brown fox", "Second paragraph", "Removed line", "Last words")
    private val revised = doc("The quick red fox jumps", "Second paragraph", "Last words", "New paragraph")

    @Test
    fun aChangedParagraphIsComparedWordByWord() {
        val r = DocCompare.compare(original, revised, "Reviewer", now = 1_000L)
        val first = r.document.paragraphs()[0]
        val del = first.runs.filter { it.format.deleted }.joinToString("") { it.text }
        val ins = first.runs.filter { it.format.inserted }.joinToString("") { it.text }
        assertEquals("brown", del)
        assertEquals("red jumps", ins)
        assertTrue(first.runs.filter { it.format.deleted || it.format.inserted }.all { it.format.revAuthor == "Reviewer" && it.format.revDate == 1_000L })
        // The removed paragraph: its text and mark deleted; the new one inserted.
        val removed = r.document.paragraphs().first { it.plainText() == "Removed line" }
        assertTrue(removed.runs.all { it.format.deleted }); assertEquals(false, removed.format.revisionMark?.inserted)
        val added = r.document.paragraphs().first { it.plainText() == "New paragraph" }
        assertTrue(added.runs.all { it.format.inserted }); assertEquals(true, added.format.revisionMark?.inserted)
        assertEquals("brown; red, jumps; the added paragraph", 3, r.insertions)
        assertEquals(2, r.deletions)
        // Alike documents: nothing to mark.
        val same = DocCompare.compare(original, original, "R")
        assertEquals(0, same.insertions); assertEquals(0, same.deletions)
        assertEquals(texts(original), texts(same.document))
    }

    @Test
    fun aSpaceBetweenTwoChangesJoinsThemAsWordShowsIt() {
        val p = DocCompare.compare(doc("We expect the same next quarter."), doc("We expect a stronger next quarter."), "R")
            .let { r -> assertEquals(1, r.deletions); assertEquals(1, r.insertions); r.document.paragraphs().single() }
        val changed = p.runs.filter { it.format.deleted || it.format.inserted }
        assertEquals("removed first, then added", listOf("the same" to true, "a stronger" to false), changed.map { it.text to it.format.deleted })
        assertEquals("We expect the samea stronger next quarter.", p.plainText())
    }

    @Test
    fun acceptingAllGivesTheRevisedRejectingAllTheOriginal() {
        val r = DocCompare.compare(original, revised, "Reviewer")
        val accept = DocumentEditor().apply { load(r.document.copyOf()) }
        accept.acceptAllChanges()
        assertEquals(texts(revised), texts(accept.document))
        val reject = DocumentEditor().apply { load(r.document.copyOf()) }
        reject.rejectAllChanges()
        assertEquals(texts(original), texts(reject.document))
    }

    @Test
    fun chineseIsComparedCharacterByCharacterAndFormattingIsKept() {
        val o = Document(mutableListOf()).also { it.addParagraph(Paragraph(mutableListOf(TextRun("今天天气很好", CharacterFormat(bold = true))))) }
        val n = Document(mutableListOf()).also { it.addParagraph(Paragraph(mutableListOf(TextRun("今天天气不好", CharacterFormat(bold = true))))) }
        val p = DocCompare.compare(o, n, "审阅者").document.paragraphs().single()
        assertEquals("很", p.runs.filter { it.format.deleted }.joinToString("") { it.text })
        assertEquals("不", p.runs.filter { it.format.inserted }.joinToString("") { it.text })
        assertTrue(p.runs.all { it.format.bold })
    }

    @Test
    fun theResultKeepsItsParagraphMarksThroughDocx() {
        val r = DocCompare.compare(original, revised, "Reviewer")
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(r.document, it) }.toByteArray()
        val xml = String(bytes, Charsets.ISO_8859_1)
        assertTrue(xml.isNotEmpty())
        val back = DocxReader.read(java.io.ByteArrayInputStream(bytes))
        val removed = back.paragraphs().first { it.plainText() == "Removed line" }
        assertNotNull("the deleted paragraph mark comes back", removed.format.revisionMark)
        val e = DocumentEditor().apply { load(back) }
        e.acceptAllChanges()
        assertEquals(texts(revised), texts(e.document))
    }

    /** With `-Daero.sampleDir`: the original, the revised and their Compare Result (compare-*.docx, the result also as ODT). */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        val o = doc("Quarterly review", "Sales grew in every region this quarter, led by the north.", "The new store opened in August.",
            "Costs stayed flat.", "We expect the same next quarter.")
        val n = doc("Quarterly review", "Sales grew strongly in every region this quarter, led by the south.", "The new store opened in August and is ahead of plan.",
            "We expect a stronger next quarter.", "Hiring starts in January.")
        o.paragraphs()[0].format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1
        n.paragraphs()[0].format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1
        java.io.File(dir, "compare-original.docx").outputStream().use { DocxWriter.write(o, it) }
        java.io.File(dir, "compare-revised.docx").outputStream().use { DocxWriter.write(n, it) }
        val result = DocCompare.compare(o, n, "Reviewer").document
        java.io.File(dir, "compare-result.docx").outputStream().use { DocxWriter.write(result, it) }
        java.io.File(dir, "compare-result.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(result, it) }
    }
}
