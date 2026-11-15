package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideLinks
import com.therealityteam.aeropages.model.SlideShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PowerPoint's Reuse Slides: another deck's slides inserted after the current one, with this deck's theme or their own. */
class ReuseSlidesTest {

    private fun slide(title: String, layout: SlideLayout = SlideLayout.TITLE_CONTENT) =
        Slide(title = SlideBox(title, CharacterFormat(fontSizePt = 40f, bold = true)), layout = layout)

    /** Two slides on the "Brand" design. */
    private fun destination(): SlideEditor {
        val p = Presentation(mutableListOf(slide("Ours 1"), slide("Ours 2")))
        p.designs += SlideDesign("Brand", background = 0xFF1F3864)
        p.slides.forEach { it.design = "Brand" }
        return SlideEditor().apply { load(p) }
    }

    /** Three slides: the first on a design also called "Brand" (another one), the second linking to the third, the third plain blue. */
    private fun source(): Presentation {
        val a = slide("Theirs A"); val b = slide("Theirs B"); val c = slide("Theirs C")
        val p = Presentation(mutableListOf(a, b, c))
        p.designs += SlideDesign("Brand", background = 0xFFC00000, items = mutableListOf(DesignShape(SlideShape(ShapeType.RECT))))
        a.design = "Brand"
        b.shapes += SlideShape(ShapeType.RECT).apply { action = SlideLinks.toSlide(c) }
        b.title.paragraph.runs.first().format.link = SlideLinks.toSlide(a)
        c.background = 0xFF2E75B6
        return p
    }

    @Test
    fun withThisDecksThemeTheSlidesComeAfterTheCurrentOne() {
        val e = destination()
        val src = source()
        assertEquals(2, e.reuseSlides(src, listOf(1, 2), keepSource = false))
        assertEquals(listOf("Ours 1", "Theirs B", "Theirs C", "Ours 2"), e.presentation.slides.map { it.title.text })
        assertEquals("the last inserted is the current one", 2, e.current)
        assertTrue("they take this deck's design", e.presentation.slides.subList(1, 3).all { it.design == "Brand" })
        assertEquals("no design came along", 1, e.presentation.designs.size)
        // The link to C follows the copy; the one to A (left behind) is dropped.
        val b = e.presentation.slides[1]; val c = e.presentation.slides[2]
        assertEquals(c.id, SlideLinks.slideId(b.shapes.single().action))
        assertNull(b.title.paragraph.runs.first().format.link)
        assertNotSame("copies, not the source's own", src.slides[2], c)
        e.undo()
        assertEquals(listOf("Ours 1", "Ours 2"), e.presentation.slides.map { it.title.text })
    }

    @Test
    fun keepingSourceFormattingBringsTheirDesignAndBackground() {
        val e = destination()
        e.select(1)
        assertEquals(3, e.reuseSlides(source(), listOf(0, 1, 2), keepSource = true))
        assertEquals(listOf("Ours 1", "Ours 2", "Theirs A", "Theirs B", "Theirs C"), e.presentation.slides.map { it.title.text })
        // Their "Brand" is not ours: it comes along under a name of its own.
        assertEquals(listOf("Brand", "Brand 2"), e.presentation.designs.map { it.name })
        assertEquals("Brand 2", e.presentation.slides[2].design)
        assertEquals(0xFFC00000, e.presentation.designOf(e.presentation.slides[2])!!.background)
        // C keeps its own blue; B (no design) keeps the background it showed.
        assertEquals(0xFF2E75B6, e.presentation.slides[4].background)
        e.undo()
        assertEquals(listOf("Brand"), e.presentation.designs.map { it.name })
        assertEquals(2, e.presentation.slides.size)
        assertEquals(0, e.reuseSlides(source(), listOf(7), keepSource = true))
    }

    /** A dark template: the background and white text come from its master, which comes along folded into the designs. */
    @Test
    fun keepingSourceFormattingBringsWhatTheSourceMasterGives() {
        val e = destination()
        val src = source()
        src.master.background = 0xFF1B1F2A; src.master.titleColor = 0xFFFFFFFF; src.master.bodyColor = 0xFFDDDDDD
        src.designs[0].background = null  // "Brand" there only draws a shape; its dark comes from the master
        assertEquals(3, e.reuseSlides(src, listOf(0, 1, 2), keepSource = true, sourceName = "Midnight"))
        val p = e.presentation
        val (a, b, c) = p.slides.subList(1, 4)
        assertEquals("Brand 2", a.design)
        assertEquals(0xFF1B1F2A, p.backgroundOf(a))
        assertEquals(0xFFFFFFFF, p.titleTextOn(a).color)
        // A slide without a design gets one made of the source's master — one, shared by both such slides.
        assertEquals("Midnight", b.design); assertEquals("Midnight", c.design)
        assertEquals(0xFFDDDDDD, p.bodyTextOn(b).color)
        assertEquals(0xFF1B1F2A, p.backgroundOf(b))
        assertEquals("C's own colour still wins", 0xFF2E75B6, p.backgroundOf(c))
        assertEquals(listOf("Brand", "Brand 2", "Midnight"), p.designs.map { it.name })
        // This deck's own slides keep their look.
        assertEquals(0xFF1F3864, p.backgroundOf(p.slides[0]))
    }
}
