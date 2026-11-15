package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review → Proofing in Slides: every box and every slide's notes are proofed, and a suggestion goes in where it was found. */
class SlideProofingTest {

    private fun deck(): SlideEditor {
        val bold = CharacterFormat(bold = true, fontSizePt = 40f)
        val s1 = Slide(title = SlideBox(Paragraph(mutableListOf(TextRun("Teh plan", bold)))), body = SlideBox("Ship it it soon"))
        s1.shapes.add(SlideShape(ShapeType.TEXTBOX).also { it.box.paragraph.runs.clear(); it.box.paragraph.runs.add(TextRun("Notte", CharacterFormat())) })
        s1.notes = "Mention  the dates"
        val s2 = Slide(title = SlideBox("Results"))
        return SlideEditor().also { it.load(Presentation(mutableListOf(s1, s2))) }
    }

    @Test fun everyBoxAndTheNotesAreProofedInReadingOrder() {
        val ed = deck()
        val targets = ed.proofTargets()
        assertEquals(listOf("Teh plan", "Ship it it soon", "Notte", "Mention  the dates", "Results", "", ""), targets.map { ed.proofText(it) })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), targets.map { it.slide })
        // AeroDesk's own checks: the word written twice in the body, the double space in the notes.
        assertEquals(listOf(1, 3), ed.proofread(targets).map { it.index })
    }

    @Test fun aSuggestionGoesInInTheWordsOwnFormattingAndUndoes() {
        val ed = deck()
        val targets = ed.proofTargets()
        val title = targets[0]
        assertTrue(ed.applySpelling(title, 0, "Teh", "The"))
        assertEquals("The plan", ed.presentation.slides[0].title.text)
        assertTrue("the title keeps its bold", ed.presentation.slides[0].title.paragraph.runs.all { it.format.bold })
        assertFalse("the text there has changed since", ed.applySpelling(title, 0, "Teh", "The"))
        // The notes too.
        assertTrue(ed.applySpelling(targets[3], 0, "Mention", "Mentions"))
        assertEquals("Mentions  the dates", ed.presentation.slides[0].notes)
        ed.undo(); ed.undo()
        assertEquals("Teh plan", ed.presentation.slides[0].title.text)
        assertEquals("Mention  the dates", ed.presentation.slides[0].notes)
    }

    @Test fun showingAnIssueSelectsItsSlideAndBox() {
        val ed = deck()
        val targets = ed.proofTargets()
        ed.showProofTarget(targets[4])
        assertEquals(1, ed.current)
        assertTrue(ed.focusedRef === ed.presentation.slides[1].title)
    }
}
