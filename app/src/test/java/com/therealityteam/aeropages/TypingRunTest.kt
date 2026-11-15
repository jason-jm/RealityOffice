package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The editor tells the page layout when the only change since a revision is typing in one
 * paragraph, so a keystroke re-measures that paragraph instead of the whole document.
 */
class TypingRunTest {

    private fun editor(): DocumentEditor {
        val d = Document()
        d.addParagraph(Paragraph.of("first"))
        d.addParagraph(Paragraph.of("second"))
        return DocumentEditor().apply { load(d) }
    }

    @Test
    fun consecutiveTypingInOneParagraphIsOneRun() {
        val e = editor()
        assertNull(e.typingRun)
        val start = e.revision
        e.replaceText(0, "first", "first!")
        e.replaceText(0, "first!", "first!!")
        val run = e.typingRun!!
        assertSame(e.document.locations()[0].paragraph, run.paragraph)
        assertEquals(start, run.from)
        assertEquals(e.revision, run.to)
    }

    @Test
    fun typingInAnotherParagraphStartsANewRun() {
        val e = editor()
        e.replaceText(0, "first", "first!")
        val mid = e.revision
        e.replaceText(1, "second", "second?")
        val run = e.typingRun!!
        assertSame(e.document.locations()[1].paragraph, run.paragraph)
        assertEquals(mid, run.from)
    }

    @Test
    fun anyOtherChangeLeavesTheRunBehind() {
        val e = editor()
        e.replaceText(0, "first", "first!")
        e.splitParagraph(0, 2)
        // The run no longer ends at the current revision: the layout must look at everything.
        assertNotEquals(e.revision, e.typingRun!!.to)
        e.replaceText(0, "fi", "fix")
        assertEquals("a new run starts after the structural change", e.revision - 1, e.typingRun!!.from)
    }
}
