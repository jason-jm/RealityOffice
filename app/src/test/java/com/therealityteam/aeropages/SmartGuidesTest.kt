package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PowerPoint's smart guides while dragging: every kind of object snaps, to every other kind, and never sticks. */
class SmartGuidesTest {

    private fun deckWithPicture(): Pair<SlideEditor, SlideImage> {
        val e = SlideEditor(); e.load(Presentation(mutableListOf(Slide())))
        val img = SlideImage(byteArrayOf(1), "image/png", 100f, 100f, xPct = 0.1f, yPct = 0.1f, wPct = 0.2f, hPct = 0.2f)
        e.slide.images.add(img); e.selectImage(img)
        return e to img
    }

    @Test
    fun aPictureSnapsToTheSlidesCentreAndLetsGoAsThePointerMovesOn() {
        val (e, img) = deckWithPicture()
        e.dragSelected(0.295f, 0f)   // its centre at 0.495: onto the slide's centre line
        assertEquals(0.4f, img.xPct, 0.0005f)
        assertTrue(e.guides.any { it.vertical && kotlin.math.abs(it.pos - 0.5f) < 1e-4f })
        repeat(5) { e.dragSelected(0.004f, 0f) }   // the pointer goes on in small steps: past the guide, not held by it
        assertEquals(0.415f, img.xPct, 0.0005f)
        assertTrue(e.guides.none { it.vertical })
        e.clearGuides()
        assertTrue(e.guides.isEmpty())
    }

    @Test
    fun aShapeSnapsToAPictureAndAPictureToAShape() {
        val (e, img) = deckWithPicture()
        e.addShape(ShapeType.RECT)
        val sh = e.selectedShape!!; sh.xPct = 0.6f; sh.yPct = 0.6f; sh.wPct = 0.1f; sh.hPct = 0.1f
        // The shape's left edge brought near the picture's right edge (0.3).
        e.moveSelectedShape(0.305f - 0.6f, 0f)
        assertEquals(0.3f, sh.xPct, 0.0005f)
        e.clearGuides()
        // And the picture's top brought near the shape's top (0.6).
        e.selectImage(img)
        e.dragSelected(0f, 0.594f - 0.1f)
        assertEquals(0.6f, img.yPct, 0.0005f)
    }

    @Test
    fun aDraggedTitleSnapsToo() {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.freePlaceholders()
        val f = e.slide.title.frame!!
        val goal = 0.5f - f.wPct / 2f   // centred
        e.dragBox(e.slide.title, goal - f.xPct + 0.006f, 0f)
        assertEquals(goal, f.xPct, 0.0005f)
    }
}
