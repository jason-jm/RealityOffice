package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.ui.DeckDesigns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Design's designed looks on any deck, and new slides drawn as the deck's others are. */
class SlideDesignThemesTest {

    private fun deck(): SlideEditor {
        val p = Presentation(mutableListOf(Slide(layout = SlideLayout.TITLE), Slide(), Slide(layout = SlideLayout.SECTION), Slide()))
        p.slides[1].background = 0xFFFF0000
        return SlideEditor().apply { load(p) }
    }

    private fun SlideEditor.apply(look: DeckDesigns.Look) = applyDeckDesign(DeckDesigns.indexOf(look), DeckDesigns.cover(look), DeckDesigns.content(look),
        look.background, look.title, look.body, DeckDesigns.COVER_TITLE, DeckDesigns.COVER_BODY, DeckDesigns.NAMES)

    @Test
    fun aDesignedLookGoesOnEverySlideAndComesOffAgain() {
        val e = deck()
        e.apply(DeckDesigns.FRESH)
        val p = e.presentation
        assertEquals(listOf("Fresh Title", "Fresh", "Fresh Title", "Fresh"), p.slides.map { it.design })
        assertNull("a slide's own background gives way", p.slides[1].background)
        assertEquals(DeckDesigns.COVER_TITLE.xPct, p.slides[0].title.frame!!.xPct)
        assertEquals(TextAlign.LEFT, p.slides[0].title.align)
        assertEquals(DeckDesigns.FRESH.background, p.master.background)
        // Another look replaces it rather than piling up master pages.
        e.apply(DeckDesigns.DARK)
        assertEquals(listOf("Midnight Title", "Midnight"), p.designs.map { it.name })
        // Undo puts the first look back, designs and all.
        e.undo()
        assertEquals(listOf("Fresh Title", "Fresh"), e.presentation.designs.map { it.name })
        assertEquals("Fresh", e.presentation.slides[1].design)
        // A plain colour theme takes the designs off.
        e.applyThemeFull(3, 0xFFB91C1C, 0xFFFFFFFF, 0xFFFFE0E0, null, DeckDesigns.NAMES)
        assertTrue(e.presentation.slides.all { it.design == null })
        assertTrue(e.presentation.designs.isEmpty())
    }

    @Test
    fun newSlidesAndNewLayoutsTakeTheDeckDesign() {
        val e = deck()
        e.apply(DeckDesigns.BUSINESS)
        e.select(0)
        e.addSlide()
        assertEquals("a new content slide is drawn as the other content slides", "Business", e.slide.design)
        e.setLayout(SlideLayout.TITLE)
        assertEquals("a slide made a cover takes the cover design", "Business Title", e.slide.design)
        assertEquals("…and the cover's title place", DeckDesigns.COVER_TITLE.yPct, e.slide.title.frame!!.yPct)
        // A deck without designs is left alone.
        val plain = SlideEditor().apply { load(Presentation(mutableListOf(Slide()))) }
        plain.addSlide()
        assertNull(plain.slide.design)
        // Kept through pptx: each design is a master of its own.
        val back = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(e.presentation, it) }.toByteArray())
        assertEquals(e.presentation.slides.size, back.slides.size)
        assertTrue(back.slides.all { back.designOf(it)?.items?.isNotEmpty() == true })
    }
}
