package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.OutlineImport
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideListStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slides from Outline: unindented lines are titles, indented ones the slide text (deeper ones deeper levels); one undo step. */
class OutlineImportTest {

    @Test
    fun parsesTitlesAndIndentedLines() {
        val text = "Agenda\n\tGoals\n\tTimeline\n\nResults\n  - Revenue up\n  - Costs flat\n\t\tdeeper still\nClosing\n"
        val slides = OutlineImport.parse(text)
        assertEquals(listOf("Agenda", "Results", "Closing"), slides.map { it.title })
        assertEquals(listOf("Goals", "Timeline"), slides[0].lines)
        assertEquals("deeper indents are deeper list levels", listOf("Revenue up", "Costs flat", "\tdeeper still"), slides[1].lines)
        assertTrue(slides[2].lines.isEmpty())
        // Indented text before any title becomes its own slide rather than vanishing.
        assertEquals("Orphan", OutlineImport.parse("\tOrphan\nNext").first().title)
        assertTrue(OutlineImport.parse("   \n\n").isEmpty())
    }

    @Test
    fun editorInsertsAfterTheCurrentSlideWithUndo() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        e.addSlide()                       // two slides, current = 1
        e.select(0)
        val n = e.insertSlidesFromOutline("Intro\n\tWho we are\n\tWhat we do\nQuestions?")
        assertEquals(2, n)
        assertEquals(4, e.presentation.slides.size)
        assertEquals("Intro", e.presentation.slides[1].title.text)
        assertEquals("Who we are\nWhat we do", e.presentation.slides[1].body.text)
        assertEquals(SlideListStyle.BULLET, e.presentation.slides[1].body.listStyle)
        assertEquals(SlideLayout.TITLE_CONTENT, e.presentation.slides[1].layout)
        assertEquals("a title-only entry makes a title slide", SlideLayout.TITLE, e.presentation.slides[2].layout)
        assertEquals("Questions?", e.presentation.slides[2].title.text)
        assertEquals("the first new slide is selected", 1, e.current)
        e.undo()
        assertEquals(2, e.presentation.slides.size)
        assertEquals(0, e.insertSlidesFromOutline("\n\n"))
    }
}
