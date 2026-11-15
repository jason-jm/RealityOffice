package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

/** Slides' Home commands as PowerPoint has them: New Slide of a layout, Bring Forward / Send Backward one place. */
class SlideHomeCommandsTest {

    private fun editor() = SlideEditor().apply { load(Presentation.blank()) }

    @Test
    fun newSlideTakesTheLayoutPicked() {
        val e = editor()
        val before = e.presentation.slides.size
        e.addSlide(SlideLayout.SECTION)
        assertEquals(before + 1, e.presentation.slides.size)
        assertEquals(SlideLayout.SECTION, e.slide.layout)
        e.addSlide(SlideLayout.TITLE)
        assertEquals("a title slide's subtitle is centred", TextAlign.CENTER, e.slide.body.paragraph.format.align)
        e.addSlide()
        assertEquals(SlideLayout.TITLE_CONTENT, e.slide.layout)
        assertEquals(TextAlign.LEFT, e.slide.body.paragraph.format.align)
        e.undo(); e.undo(); e.undo()
        assertEquals(before, e.presentation.slides.size)
    }

    @Test
    fun bringForwardAndSendBackwardMoveOnePlace() {
        val e = editor()
        repeat(3) { e.addShape(ShapeType.RECT) }
        // Told apart by their fills (an undo may bring back copies).
        e.slide.shapes.forEachIndexed { i, sh -> sh.fill = 0xFF000000 + i }
        fun order() = e.slide.shapes.map { (it.fill!! - 0xFF000000).toInt() }
        e.selectShape(e.slide.shapes[0])
        e.stepShapeLayer(true)
        assertEquals(listOf(1, 0, 2), order())
        e.stepShapeLayer(true)
        assertEquals(listOf(1, 2, 0), order())
        // Already on top: nothing to do, nothing to undo.
        e.stepShapeLayer(true)
        assertEquals(listOf(1, 2, 0), order())
        e.stepShapeLayer(false)
        assertEquals(listOf(1, 0, 2), order())
        e.undo()
        assertEquals(listOf(1, 2, 0), order())
        e.selectShape(e.slide.shapes[2])
        e.sendShapeToBack()
        assertEquals(listOf(0, 1, 2), order())
    }
}
