package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.MasterText
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** PowerPoint's Replace Fonts (slides, master, designs; one undo step) and Select ▸ Select All. */
class ReplaceFontsTest {
    private fun deck(): Presentation {
        val s1 = Slide(title = SlideBox("Title", CharacterFormat(fontFamily = "Arial")))
        s1.shapes += SlideShape(ShapeType.RECT).apply { box.paragraph.runs.first().format.fontFamily = "arial" }
        s1.shapes += SlideShape(ShapeType.STAR).apply { hidden = true }
        val s2 = Slide(title = SlideBox("Second", CharacterFormat(fontFamily = "Georgia")))
        val p = Presentation(mutableListOf(s1, s2))
        p.master.bodyFont = "Arial"
        val logo = SlideShape(ShapeType.RECT).apply { box.paragraph.runs.first().format.fontFamily = "Arial" }
        p.designs += SlideDesign("Brand", titleText = MasterText("Arial", 40f, null, true), items = mutableListOf(DesignShape(logo)))
        return p
    }

    @Test
    fun everyUseIsReplacedInOneStep() {
        val e = SlideEditor().apply { load(deck()) }
        assertEquals("by name, any case once", listOf("Arial", "Georgia"), e.fontsUsed())
        val design = e.presentation.designs[0]
        assertEquals("title, shape, master body, design title and its shape", 5, e.replaceFont("ARIAL", "Verdana"))
        val p = e.presentation
        assertEquals("Verdana", p.slides[0].title.paragraph.runs[0].format.fontFamily)
        assertEquals("Verdana", p.slides[0].shapes[0].box.paragraph.runs[0].format.fontFamily)
        assertEquals("Georgia", p.slides[1].title.paragraph.runs[0].format.fontFamily)
        assertEquals("Verdana", p.master.bodyFont)
        assertNotSame("a design is replaced, never changed in place", design, p.designs[0])
        assertEquals("Verdana", p.designs[0].titleText!!.font)
        assertEquals("Arial", design.titleText!!.font)
        assertEquals(listOf("Georgia", "Verdana"), e.fontsUsed())
        assertEquals("nothing left to replace", 0, e.replaceFont("Arial", "Verdana"))
        e.undo()
        assertEquals("Arial", e.presentation.slides[0].title.paragraph.runs[0].format.fontFamily)
        assertEquals("Arial", e.presentation.master.bodyFont)
        assertSame(design, e.presentation.designs[0])
    }

    @Test
    fun selectAllTakesTheShapesShown() {
        val e = SlideEditor().apply { load(deck()) }
        assertTrue(e.selectAllShapes())
        assertEquals("the hidden star left out", 1, e.selectedShapes.size)
        e.select(1)
        assertFalse("a slide without shapes", e.selectAllShapes())
    }
}
