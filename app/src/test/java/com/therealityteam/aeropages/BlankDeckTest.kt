package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A new deck starts as PowerPoint's: one Title Slide of empty placeholders, whose prompts are never saved as text. */
class BlankDeckTest {
    @Test
    fun aNewDeckIsATitleSlideOfEmptyPlaceholders() {
        val p = Presentation.blank()
        val s = p.slides.single()
        assertEquals(SlideLayout.TITLE, s.layout)
        assertEquals("", s.title.text); assertEquals("", s.body.text)
        assertEquals(TextAlign.CENTER, s.title.align); assertEquals(TextAlign.CENTER, s.body.align)
        // Saved untouched, the file holds no prompt text.
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val back = PptxReader.read(bytes).slides.single()
        assertEquals("", back.title.text.trim()); assertEquals("", back.body.text.trim())
    }

    @Test
    fun typingIntoTheEmptyTitleKeepsItsLook() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        e.replaceText(e.slide.title, "", "Quarterly review")
        val run = e.slide.title.paragraph.runs.first { it.text.isNotEmpty() }
        assertEquals("Quarterly review", e.slide.title.text)
        assertEquals(40f, run.format.fontSizePt ?: 0f, 0.01f)
        assertTrue("the title's bold stays", run.format.bold == true)
    }

    @Test
    fun aNewSlideHasEmptyPlaceholders() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        e.addSlide()
        assertEquals(SlideLayout.TITLE_CONTENT, e.slide.layout)
        assertFalse(e.slide.title.text.isNotEmpty() || e.slide.body.text.isNotEmpty())
    }

    @Test
    fun insertedWordArtComesSelectedToTypeOver() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        e.addWordArt()
        val box = e.slide.shapes.last().box
        assertTrue(box.text.isNotEmpty())
        assertTrue(e.focusedRef === box)
        assertEquals(0, e.selStart); assertEquals(box.text.length, e.selEnd)
    }

    @Test
    fun anInsertedPictureComesPicked() {
        val d = com.therealityteam.aeropages.editor.DocumentEditor()
        d.load(com.therealityteam.aeropages.model.Document.blank())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        d.insertImage(png, "image/png", 120f, 80f)
        assertTrue(d.selectedImage != null)
        assertTrue(d.document.blocks.any { it === d.selectedImage })
    }

    @Test
    fun insertingAShapeIsCountedClickingOneIsNot() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        e.addShape(com.therealityteam.aeropages.model.ShapeType.RECT)
        assertEquals(1, e.shapesInserted)
        e.selectShape(null); e.selectShape(e.slide.shapes.first())
        assertEquals("a click is not an insert", 1, e.shapesInserted)
        e.addWordArt()
        assertEquals(2, e.shapesInserted)
    }
}
