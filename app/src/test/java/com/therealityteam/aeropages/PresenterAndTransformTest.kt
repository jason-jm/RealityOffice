package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.ui.ShowSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detached show's session state and the two-hand transform of slide elements. */
class PresenterAndTransformTest {

    @Test
    fun showSessionCarriesNavigationRequests() {
        val e = SlideEditor(); e.load(Presentation(mutableListOf(Slide(), Slide(), Slide()))); e.select(1)
        ShowSession.start(e)
        assertTrue(ShowSession.active); assertEquals(1, ShowSession.index); assertEquals(0, ShowSession.navSeq); assertFalse(ShowSession.stopRequested)
        ShowSession.requestNext(); assertEquals(1, ShowSession.navSeq); assertEquals(1, ShowSession.navDir)
        ShowSession.requestPrev(); assertEquals(2, ShowSession.navSeq); assertEquals(-1, ShowSession.navDir)
        ShowSession.requestStop(); assertTrue(ShowSession.stopRequested)
        ShowSession.stop(); assertFalse(ShowSession.active); assertNull(ShowSession.editor)
        ShowSession.start(e); assertEquals(0, ShowSession.navSeq); assertFalse(ShowSession.stopRequested); ShowSession.stop()
    }

    @Test
    fun wallPermissionFollowsThePresentingEditor() {
        val first = SlideEditor()
        val second = SlideEditor()
        try {
            ShowSession.start(first)
            assertNull(ShowSession.wallTrackingAllowed)
            ShowSession.reportWallPermission(first, false)
            assertEquals(false, ShowSession.wallTrackingAllowed)
            ShowSession.reportWallPermission(first, true)
            assertEquals(true, ShowSession.wallTrackingAllowed)
            ShowSession.start(second)
            ShowSession.reportWallPermission(first, true)
            assertNull(ShowSession.wallTrackingAllowed)
            ShowSession.reportWallPermission(second, false)
            assertEquals(false, ShowSession.wallTrackingAllowed)
        } finally {
            ShowSession.stop()
        }
        ShowSession.reportWallPermission(second, true)
        assertNull(ShowSession.wallTrackingAllowed)
    }

    @Test
    fun twoHandTransformScalesAboutTheCentreAndRotates() {
        val e = SlideEditor(); e.load(Presentation(mutableListOf(Slide())))
        e.addShape(ShapeType.RECT)
        val s = e.selectedShape!!
        s.xPct = 0.2f; s.yPct = 0.2f; s.wPct = 0.4f; s.hPct = 0.3f
        e.transformSelectedShape(0.1f, 0f, 1.5f, 15f)
        assertEquals(0.6f, s.wPct, 0.001f); assertEquals(0.45f, s.hPct, 0.001f)
        assertEquals(0.2f, s.xPct, 0.001f); assertEquals(0.125f, s.yPct, 0.001f); assertEquals(15f, s.rotationDeg, 0.001f)
        e.transformSelectedShape(0f, 0f, 1f, -30f) // same gesture key: coalesced into one undo step
        assertEquals(345f, s.rotationDeg, 0.001f)
        e.transformSelectedShape(0f, 0f, 4f, 0f) // zoom is clamped per event and the shape stays on the slide
        assertEquals(1f, s.wPct, 0.001f); assertEquals(0f, s.xPct, 0.001f)
        e.undo()
        assertEquals(0.4f, e.slide.shapes[0].wPct, 0.001f); assertEquals(0f, e.slide.shapes[0].rotationDeg, 0.001f)
        // Pictures pan + scale about their centre without rotating.
        e.setImage(byteArrayOf(1), "image/png", 100f, 100f)
        val img = e.selectedImage!!
        img.xPct = 0.3f; img.yPct = 0.3f; img.wPct = 0.2f; img.hPct = 0.2f
        e.transformSelected(0.05f, -0.05f, 0.5f)
        assertEquals(0.1f, img.wPct, 0.001f); assertEquals(0.1f, img.hPct, 0.001f)
        assertEquals(0.4f, img.xPct, 0.001f); assertEquals(0.3f, img.yPct, 0.001f)
    }
}
