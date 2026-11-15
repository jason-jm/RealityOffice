package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Markups
import com.therealityteam.aeropages.model.Paragraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reviewing: how tracked changes show, going from change to change and comment to comment, accepting one and moving on. */
class ReviewDisplayTest {
    @After fun reset() { Markups.shown = 0 }

    /** "The quick brown fox." with "quick " deleted and "lazy " inserted by Ann, and "Second." commented. */
    private fun editor(): DocumentEditor {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("The quick brown fox."))
        doc.addParagraph(Paragraph.of("Second paragraph."))
        doc.addParagraph(Paragraph.of("Third paragraph."))
        val e = DocumentEditor().apply { load(doc) }
        e.changeReviewer("Ann")
        e.toggleTrackChanges()
        e.setSelection(0, 4, 10); e.typeOver("")          // "quick " struck
        e.setSelection(0, 10, 10); e.typeOver("lazy ")    // before "brown"
        e.setSelection(2, 0, 5); e.typeOver("")            // "Third" struck
        e.toggleTrackChanges()
        e.setSelection(1, 0, 0); e.addComment("Check this")
        e.setSelection(2, 0, 0); e.addComment("And this")
        return e
    }

    @Test
    fun theReviewDisplayShowsWhatItSays() {
        val e = editor()
        val p = e.document.locations()[0].paragraph
        fun shown() = p.runs.filter { Markups.shows(it.format) }.joinToString("") { it.text }
        assertEquals("The quick lazy brown fox.", shown())
        e.showMarkup(DocumentEditor.Markup.NONE)
        assertEquals("The lazy brown fox.", shown())
        assertFalse(Markups.marked)
        e.showMarkup(DocumentEditor.Markup.ORIGINAL)
        assertEquals("The quick brown fox.", shown())
        e.showMarkup(DocumentEditor.Markup.SIMPLE)
        assertEquals("The lazy brown fox.", shown())
        e.showMarkup(DocumentEditor.Markup.ALL)
        assertTrue(Markups.marked)
    }

    @Test
    fun fromChangeToChangeAcceptingOnTheWay() {
        val e = editor()
        e.setSelection(0, 0, 0)
        assertTrue(e.goToChange())
        assertEquals(Triple(0, 4, 10), Triple(e.active, e.selStart, e.selEnd))
        assertTrue(e.goToChange())
        assertEquals(Triple(0, 10, 15), Triple(e.active, e.selStart, e.selEnd))
        assertTrue(e.goToChange())
        assertEquals(2, e.active)
        assertFalse(e.goToChange())
        assertTrue(e.goToChange(back = true))
        assertEquals(Triple(0, 10, 15), Triple(e.active, e.selStart, e.selEnd))
        // Accept "lazy " and on to the next: "Third" struck, the one after it.
        assertTrue(e.resolveAtCaret(accept = true))
        assertEquals("The quick lazy brown fox.", e.document.locations()[0].paragraph.plainText())
        assertFalse(e.document.locations()[0].paragraph.runs.any { it.format.inserted })
        assertEquals(2, e.active)
        // Reject it: back to "Third paragraph." unmarked.
        assertTrue(e.resolveAtCaret(accept = false))
        assertEquals("Third paragraph.", e.document.locations()[2].paragraph.plainText())
        assertTrue(e.document.locations()[2].paragraph.runs.none { it.format.deleted })
        assertEquals(1, e.revisions().size)
        e.setSelection(1, 3, 3)
        assertNull(e.changeAtCaret())
        assertFalse(e.resolveAtCaret(accept = true))
    }

    @Test
    fun fromCommentToComment() {
        val e = editor()
        e.setSelection(0, 0, 0)
        assertTrue(e.goToComment())
        assertEquals("Check this", e.currentComment?.text)
        assertEquals(1, e.active)
        assertTrue(e.goToComment())
        assertEquals("And this", e.currentComment?.text)
        assertFalse(e.goToComment())
        assertTrue(e.goToComment(back = true))
        assertEquals("Check this", e.currentComment?.text)
        assertEquals(2, e.deleteAllComments())
        assertTrue(e.document.comments.isEmpty())
        e.undo()
        assertEquals(2, e.document.comments.size)
    }

    /** With `-Daero.sampleDir`: a document under review, for the canvas. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = editor()
        File(dir, "review.docx").outputStream().use { DocxWriter.write(e.document, it) }
    }
}
