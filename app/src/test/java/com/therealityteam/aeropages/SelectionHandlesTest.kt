package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.editor.handleFrame
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Test

/** PowerPoint's eight sizing handles: the dragged side follows, the opposite side stays, a drag is one undo step. */
class SelectionHandlesTest {

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-4f)

    @Test
    fun theOppositeSideStaysWhereItIs() {
        // Left edge dragged right by 0.1: x moves, the right edge (0.6) stays.
        val left = handleFrame(0.2f, 0.2f, 0.4f, 0.3f, -1, 0, 0.1f, 0.5f, 0.05f, 0.05f)
        near(0.3f, left[0]); near(0.3f, left[2]); near(0.2f, left[1]); near(0.3f, left[3])
        // Top-right corner: the bottom-left corner stays.
        val corner = handleFrame(0.2f, 0.2f, 0.4f, 0.3f, 1, -1, 0.1f, -0.1f, 0.05f, 0.05f)
        near(0.2f, corner[0]); near(0.5f, corner[2]); near(0.1f, corner[1]); near(0.4f, corner[3])
        // Past the minimum, the side stops; past the slide's edge too.
        val tiny = handleFrame(0.2f, 0.2f, 0.4f, 0.3f, -1, 0, 0.9f, 0f, 0.05f, 0.05f)
        near(0.55f, tiny[0]); near(0.05f, tiny[2])
        val out = handleFrame(0.2f, 0.2f, 0.4f, 0.3f, -1, -1, -0.5f, -0.5f, 0.05f, 0.05f)
        near(0f, out[0]); near(0.6f, out[2]); near(0f, out[1]); near(0.5f, out[3])
    }

    @Test
    fun aDragOfManyStepsIsOneUndoStep() {
        val e = SlideEditor().apply { load(Presentation.blank()) }
        e.addShape(ShapeType.RECT)
        val sh = e.slide.shapes.last()
        val (x0, y0, w0, h0) = listOf(sh.xPct, sh.yPct, sh.wPct, sh.hPct)
        repeat(5) { e.dragHandle(-1, -1, -0.01f, -0.01f) }
        near(x0 - 0.05f, sh.xPct); near(w0 + 0.05f, sh.wPct)
        near(x0 + w0, sh.xPct + sh.wPct)
        e.undo()
        val back = e.slide.shapes.last()
        near(x0, back.xPct); near(y0, back.yPct); near(w0, back.wPct); near(h0, back.hPct)
    }
}
