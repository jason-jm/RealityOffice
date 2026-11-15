package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocCompare
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.BookmarkMark
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.RevisionMark
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Tracked changes in ODT, as LibreOffice keeps them: the text as accepting the deletions gives it, insertions and format
 * changes between their starts and ends, deletions as marks with their text in `text:tracked-changes` — a deleted
 * paragraph mark the break between two of a deletion's paragraphs.
 */
class OdtTrackedChangesTest {
    private fun doc(vararg lines: String) = Document(mutableListOf()).also { d -> lines.forEach { d.addParagraph(Paragraph.of(it)) } }
    private fun texts(d: Document) = d.paragraphs().map { it.plainText() }
    private fun odt(d: Document) = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()
    private fun content(bytes: ByteArray): String {
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name == "content.xml") return z.readBytes().toString(Charsets.UTF_8) } }
        error("no content.xml")
    }
    private fun accepted(d: Document) = DocumentEditor().apply { load(d.copyOf()); acceptAllChanges() }.document
    private fun rejected(d: Document) = DocumentEditor().apply { load(d.copyOf()); rejectAllChanges() }.document

    private val original = doc("The quick brown fox", "Second paragraph", "Removed line", "Last words")
    private val revised = doc("The quick red fox jumps", "Second paragraph", "Last words", "New paragraph")

    @Test
    fun aComparisonGoesThroughOdtWithItsChanges() {
        val r = DocCompare.compare(original, revised, "Reviewer", now = 1_760_000_000_000L).document
        val bytes = odt(r)
        val xml = content(bytes)
        assertTrue("the regions first in the text", xml.indexOf("<text:tracked-changes") in 0 until xml.indexOf("<text:p"))
        assertTrue(xml.contains("""<text:deletion><office:change-info><dc:creator>Reviewer</dc:creator>"""))
        assertTrue("a deletion's text is in its region, not the text", Regex("<text:deletion>.*?brown.*?</text:deletion>").containsMatchIn(xml))
        assertTrue(xml.contains("<text:change text:change-id="))
        assertTrue(xml.contains("<text:change-start text:change-id="))
        // A deleted paragraph (its text and mark): a region of two paragraphs, the text joined over it.
        assertTrue(Regex("""<text:deletion>(?:(?!</text:deletion>).)*?Removed line</text:p><text:p[^>]*></text:p></text:deletion>""").containsMatchIn(xml))
        val back = OdtReader.read(bytes)
        assertEquals(texts(r), texts(back))
        val first = back.paragraphs()[0]
        assertEquals("brown", first.runs.filter { it.format.deleted }.joinToString("") { it.text })
        assertEquals("red jumps", first.runs.filter { it.format.inserted }.joinToString("") { it.text })
        assertTrue(first.runs.filter { it.format.deleted || it.format.inserted }.all { it.format.revAuthor == "Reviewer" && it.format.revDate == 1_760_000_000_000L })
        val removed = back.paragraphs().first { it.plainText() == "Removed line" }
        assertTrue(removed.runs.all { it.format.deleted })
        assertEquals(RevisionMark(false, "Reviewer", 1_760_000_000_000L), removed.format.revisionMark)
        // Accepting gives the revised document, rejecting the original — read back as written.
        assertEquals(texts(revised), texts(accepted(back)))
        assertEquals(texts(original), texts(rejected(back)))
    }

    @Test
    fun anInsertedMarkCarriesTheInsertionIntoTheNextParagraph() {
        val ins = CharacterFormat(inserted = true, revAuthor = "Ann", revDate = 1_760_000_000_000L)
        val d = Document(mutableListOf())
        d.addParagraph(Paragraph.of("Kept"))
        d.addParagraph(Paragraph(mutableListOf(TextRun("New one", ins.copyOf()))).also { it.format.revisionMark = RevisionMark(true, "Ann", 1_760_000_000_000L) })
        d.addParagraph(Paragraph.of("After"))
        val xml = content(odt(d))
        // The insertion starts in the new paragraph and ends at the next one's start: the break is inserted too.
        assertTrue(Regex("""<text:change-start text:change-id="(ct\d+)"/>New one</text:p><text:p[^>]*><text:change-end text:change-id="\1"/>After""").containsMatchIn(xml))
        val back = OdtReader.read(odt(d))
        assertEquals(listOf("Kept", "New one", "After"), texts(back))
        assertEquals(RevisionMark(true, "Ann", 1_760_000_000_000L), back.paragraphs()[1].format.revisionMark)
        assertNull(back.paragraphs()[2].format.revisionMark)
        assertFalse(back.paragraphs()[2].runs.any { it.format.inserted })
        assertEquals(listOf("Kept", "After"), texts(rejected(back)))
        assertEquals(listOf("Kept", "New one", "After"), texts(accepted(back)))
    }

    @Test
    fun aDeletedMarkAloneJoinsTheParagraphsAndSplitsBackWithBookmarksAndStyles() {
        val d = Document(mutableListOf())
        val hello = Paragraph.of("Hello").also {
            it.format.revisionMark = RevisionMark(false, "Bo", 1_760_000_000_000L)
            it.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1
            it.bookmarks.add(BookmarkMark("start", 0, false)); it.bookmarks.add(BookmarkMark("start", 5, true))
        }
        d.addParagraph(hello)
        d.addParagraph(Paragraph.of("World").also { it.bookmarks.add(BookmarkMark("w", 2, false)); it.bookmarks.add(BookmarkMark("w", 2, true)) })
        val xml = content(odt(d))
        // One paragraph in the text: the mark where the break was, its deletion two paragraphs (the first a heading).
        assertTrue(Regex("""Hello(<text:bookmark-end text:name="start"/>)?<text:change text:change-id="ct1"/>Wo""").containsMatchIn(xml))
        assertTrue(Regex("""<text:deletion>.*?<text:h [^>]*text:outline-level="1"[^>]*></text:h><text:p[^>]*></text:p></text:deletion>""").containsMatchIn(xml))
        val back = OdtReader.read(odt(d))
        assertEquals(listOf("Hello", "World"), texts(back))
        val (a, b) = back.paragraphs()
        assertEquals(com.therealityteam.aeropages.model.ParagraphStyle.HEADING1, a.format.style)
        assertEquals(RevisionMark(false, "Bo", 1_760_000_000_000L), a.format.revisionMark)
        assertNull(b.format.revisionMark)
        assertEquals(listOf(BookmarkMark("start", 0, false), BookmarkMark("start", 5, true)), a.bookmarks)
        assertEquals(listOf(BookmarkMark("w", 2, false), BookmarkMark("w", 2, true)), b.bookmarks)
        assertEquals(listOf("HelloWorld"), texts(accepted(back)))
        assertEquals(listOf("Hello", "World"), texts(rejected(back)))
        // The document written is untouched (the joining is on a copy).
        assertEquals(2, d.paragraphs().size)
    }

    @Test
    fun aFormatChangeRoundTrips() {
        val d = Document(mutableListOf())
        d.addParagraph(Paragraph(mutableListOf(TextRun("plain "), TextRun("bolder", CharacterFormat(bold = true, formatChanged = true, revAuthor = "Cy", revDate = 1_760_000_000_000L)))))
        val xml = content(odt(d))
        assertTrue(xml.contains("<text:format-change>"))
        val run = OdtReader.read(odt(d)).paragraphs().single().runs.first { it.text == "bolder" }
        assertTrue(run.format.formatChanged && run.format.bold)
        assertEquals("Cy", run.format.revAuthor)
    }

    @Test
    fun libreOfficesTrackedChangesAreRead() {
        // LibreOffice's ODT of our Compare Result (compare-result.docx): a word changed, words added, a paragraph deleted.
        val d = OdtReader.read(File("src/test/resources/odt/tracked-changes.odt").readBytes())
        val p = texts(d)
        assertTrue(p.toString(), "Costs stayed flat." in p)
        val sales = d.paragraphs().first { it.plainText().startsWith("Sales grew") }
        assertEquals("north", sales.runs.filter { it.format.deleted }.joinToString("") { it.text })
        assertEquals("strongly south", sales.runs.filter { it.format.inserted }.joinToString(" ") { it.text.trim() })
        val costs = d.paragraphs().first { it.plainText() == "Costs stayed flat." }
        assertEquals(false, costs.format.revisionMark?.inserted)
        assertEquals("Reviewer", costs.runs.first().format.revAuthor)
        assertEquals(listOf("Quarterly review", "Sales grew strongly in every region this quarter, led by the south.",
            "The new store opened in August and is ahead of plan.", "We expect a stronger next quarter.", "Hiring starts in January."), texts(accepted(d)))
        assertEquals("Sales grew in every region this quarter, led by the north.", texts(rejected(d))[1])
        assertTrue("Costs stayed flat." in texts(rejected(d)))
    }
}
