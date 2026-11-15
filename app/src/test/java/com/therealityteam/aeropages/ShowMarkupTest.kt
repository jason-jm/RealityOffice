package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Comment
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Markups
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextRun
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word's Show Markup: which changes show marked (insertions and deletions, formatting), whether comments show, and whose —
 * a change left out reads as taken; Next / Previous and "Accept All Changes Shown" take only the changes shown.
 */
class ShowMarkupTest {
    private fun ins(who: String) = CharacterFormat(inserted = true, revAuthor = who, revDate = 1L)
    private fun del(who: String) = CharacterFormat(deleted = true, revAuthor = who, revDate = 1L)

    /** "Plan: grow fast in north." — Ann put in "fast ", Bo took out "north" and made "Plan" bold; a comment by each. */
    private fun doc() = Document(mutableListOf()).also { d ->
        d.addParagraph(Paragraph(mutableListOf(
            TextRun("Plan", CharacterFormat(bold = true, formatChanged = true, revAuthor = "Bo", revDate = 1L)), TextRun(": grow "),
            TextRun("fast ", ins("Ann")), TextRun("in "), TextRun("north", del("Bo")), TextRun("."))))
        d.addParagraph(Paragraph.of("Second line."))
        val p = d.paragraphs()[0].id
        d.comments.add(Comment("Ann", "Why fast?", p)); d.comments.add(Comment("Bo", "North matters", p))
    }

    private fun shownText(e: DocumentEditor) = e.document.paragraphs()[0].runs.filter { Markups.shows(it.format) }.joinToString("") { it.text }

    @After fun reset() = DocumentEditor().load(Document())   // Show Markup is shared: back to everything shown

    @Test
    fun aReviewerLeftOutReadsAsTakenAndIsSkipped() {
        val e = DocumentEditor().apply { load(doc()) }
        assertEquals(listOf("Bo", "Ann"), e.reviewersInDocument())
        e.setShowMarkup(hidden = setOf("Bo"))
        // Bo's deletion is gone from view, Ann's insertion marked; Bo's bold unmarked.
        assertEquals("Plan: grow fast in .", shownText(e))
        val runs = e.document.paragraphs()[0].runs
        assertTrue(Markups.marks(runs.first { it.text == "fast " }.format))
        assertFalse(Markups.marks(runs.first { it.text == "Plan" }.format))
        assertEquals(listOf("fast "), e.shownRevisions().map { it.text })
        // Next from the start: Ann's, then nothing (Bo's are not shown).
        e.setSelection(0, 0, 0)
        assertTrue(e.goToChange()); assertEquals("fast ", e.document.paragraphs()[0].plainText().substring(e.selStart, e.selEnd))
        assertFalse(e.goToChange())
        // Comments: Bo's hidden with him; all of them with Comments unticked.
        assertEquals(listOf("Ann"), e.document.comments.filter(e::commentShown).map { it.author })
        e.setShowMarkup(comments = false)
        assertTrue(e.document.comments.none(e::commentShown))
    }

    @Test
    fun acceptAllShownTakesOnlyTheShownReviewersChangesInOneStep() {
        val e = DocumentEditor().apply { load(doc()) }
        e.setShowMarkup(hidden = setOf("Bo"))
        assertTrue(e.filteringMarkup)
        e.resolveShownChanges(accept = true)
        val p = e.document.paragraphs()[0]
        assertEquals("Plan: grow fast in north.", p.plainText())
        assertFalse(p.runs.first { it.text.contains("fast") }.format.inserted)
        assertTrue(p.runs.first { it.text == "north" }.format.deleted)
        assertTrue("Bo's format change stays", p.runs.first { it.text == "Plan" }.format.formatChanged)
        e.undo()
        assertTrue(e.document.paragraphs()[0].runs.first { it.text == "fast " }.format.inserted)
        // Reject shown with only insertions and deletions ticked off: formatting alone is taken.
        e.setShowMarkup(insertionsDeletions = false, hidden = emptySet())
        assertEquals("deletions read as taken", "Plan: grow fast in .", shownText(e))
        e.resolveShownChanges(accept = false)
        val q = e.document.paragraphs()[0]
        assertFalse(q.runs.first { it.text == "Plan" }.format.formatChanged)
        assertTrue(q.runs.any { it.format.inserted } && q.runs.any { it.format.deleted })
    }

    @Test
    fun aNewDocumentShowsEverythingAgain() {
        val e = DocumentEditor().apply { load(doc()) }
        e.setShowMarkup(insertionsDeletions = false, formatting = false, hidden = setOf("Ann"))
        assertFalse(Markups.insertionsDeletions)
        val f = DocumentEditor().apply { load(doc()) }
        assertTrue(Markups.insertionsDeletions && Markups.formatting && Markups.hiddenReviewers.isEmpty())
        assertEquals("Plan: grow fast in north.", shownText(f))
    }

    /** With `-Daero.sampleDir`: show-markup.docx — two reviewers' changes and comments. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        val d = doc()
        d.paragraphs()[1].runs.clear()
        d.paragraphs()[1].runs.addAll(listOf(TextRun("Budget: "), TextRun("doubled", ins("Bo")), TextRun(" "), TextRun("flat", del("Ann")), TextRun(" for next year.")))
        java.io.File(dir, "show-markup.docx").outputStream().use { DocxWriter.write(d, it) }
    }
}
