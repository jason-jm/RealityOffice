package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation pane actions: promote / demote a heading, move a section above or below its sibling. */
class OutlineActionsTest {

    private fun doc(): DocumentEditor {
        val d = Document()
        fun add(text: String, style: ParagraphStyle = ParagraphStyle.NORMAL) {
            val p = com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun(text, com.therealityteam.aeropages.model.CharacterFormat())))
            p.format.style = style
            d.addParagraph(p)
        }
        add("Intro", ParagraphStyle.HEADING1); add("intro text")
        add("Methods", ParagraphStyle.HEADING1); add("m1"); add("Sampling", ParagraphStyle.HEADING2); add("s1")
        add("Results", ParagraphStyle.HEADING1); add("r1")
        return DocumentEditor().apply { load(d) }
    }
    private fun DocumentEditor.texts() = document.blocks.map { (it as ParagraphBlock).paragraph.plainText() }
    private fun DocumentEditor.indexOf(text: String) = document.locations().indexOfFirst { it.paragraph.plainText() == text }

    @Test
    fun promoteAndDemoteChangeTheHeadingLevelWithinHeading1To3() {
        val e = doc()
        e.demoteHeading(e.indexOf("Methods"))
        assertEquals(ParagraphStyle.HEADING2, e.document.locations()[e.indexOf("Methods")].paragraph.format.style)
        e.demoteHeading(e.indexOf("Methods")); e.demoteHeading(e.indexOf("Methods"))
        assertEquals("Heading 3 is the floor", ParagraphStyle.HEADING3, e.document.locations()[e.indexOf("Methods")].paragraph.format.style)
        e.promoteHeading(e.indexOf("Methods"))
        assertEquals(ParagraphStyle.HEADING2, e.document.locations()[e.indexOf("Methods")].paragraph.format.style)
        e.undo()
        assertEquals(ParagraphStyle.HEADING3, e.document.locations()[e.indexOf("Methods")].paragraph.format.style)
        e.promoteHeading(e.indexOf("intro text"))   // body text: nothing happens
        assertEquals(ParagraphStyle.NORMAL, e.document.locations()[e.indexOf("intro text")].paragraph.format.style)
    }

    @Test
    fun sectionsMoveAsAWholeAmongSiblings() {
        val e = doc()
        // Methods (with its Heading 2 child) moves above Intro.
        assertTrue(e.moveSectionUp(e.indexOf("Methods")))
        assertEquals(listOf("Methods", "m1", "Sampling", "s1", "Intro", "intro text", "Results", "r1"), e.texts())
        assertEquals("caret follows the moved heading", "Methods", e.document.locations()[e.active].paragraph.plainText())
        assertFalse("already first", e.moveSectionUp(e.indexOf("Methods")))
        e.undo()
        assertEquals(listOf("Intro", "intro text", "Methods", "m1", "Sampling", "s1", "Results", "r1"), e.texts())
        // Methods moves below Results.
        assertTrue(e.moveSectionDown(e.indexOf("Methods")))
        assertEquals(listOf("Intro", "intro text", "Results", "r1", "Methods", "m1", "Sampling", "s1"), e.texts())
        assertFalse("already last", e.moveSectionDown(e.indexOf("Methods")))
        // A Heading 2 without a sibling cannot leave its parent.
        assertFalse(e.moveSectionUp(e.indexOf("Sampling")))
        assertFalse(e.moveSectionDown(e.indexOf("Sampling")))
        e.undo()
        assertEquals(listOf("Intro", "intro text", "Methods", "m1", "Sampling", "s1", "Results", "r1"), e.texts())
    }
}
