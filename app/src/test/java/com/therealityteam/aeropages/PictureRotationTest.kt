package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Pictures turned and mirrored — Picture Format → Rotate — shown so and kept in pptx and ODP. */
class PictureRotationTest {

    /** A 1×1 PNG. */
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
        0, 0, 0, 13, 73, 68, 65, 84, 120, -38, 99, -8, -1, -1, 63, 0, 5, -2, 2, -2, -89, 53, -127, -124, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    private fun deck() = Presentation(mutableListOf(Slide(title = SlideBox("Pictures")).apply {
        images.add(SlideImage(png, "image/png", 1f, 1f, 0.10f, 0.30f, 0.20f, 0.25f, rotationDeg = 30f))
        images.add(SlideImage(png, "image/png", 1f, 1f, 0.40f, 0.30f, 0.20f, 0.25f, flipH = true))
        images.add(SlideImage(png, "image/png", 1f, 1f, 0.70f, 0.30f, 0.20f, 0.25f, rotationDeg = -90f, flipV = true))
    }))

    private fun assertKept(want: Presentation, got: Presentation) {
        want.slides[0].images.zip(got.slides[0].images).forEach { (a, b) ->
            assertEquals(a.rotationDeg, b.rotationDeg, 0.01f)
            assertEquals(a.flipH, b.flipH); assertEquals(a.flipV, b.flipV)
            assertEquals("its place, turned or not", a.xPct, b.xPct, 0.002f); assertEquals(a.yPct, b.yPct, 0.002f)
            assertEquals(a.wPct, b.wPct, 0.002f); assertEquals(a.hPct, b.hPct, 0.002f)
        }
    }

    @Test
    fun rotateAndFlipUndo() {
        val p = deck()
        val e = SlideEditor(); e.load(p)
        val img = e.slide.images[1]
        e.selectImage(img)
        e.rotateSelectedImage(90f); assertEquals(90f, img.rotationDeg)
        e.rotateSelectedImage(90f); e.rotateSelectedImage(90f); assertEquals("three quarters right is a quarter left", -90f, img.rotationDeg)
        e.rotateSelectedImage(90f); assertEquals(0f, img.rotationDeg)
        e.toggleImageFlipH(); assertFalse(img.flipH)
        e.toggleImageFlipV(); assertTrue(img.flipV)
        e.undo(); assertFalse(e.slide.images[1].flipV)
        e.undo(); assertTrue(e.slide.images[1].flipH)
    }

    @Test
    fun pptxKeepsThem() {
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        val slide = String(com.therealityteam.aeropages.format.OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!)
        assertTrue(slide.contains("""<a:xfrm rot="1800000">"""))
        assertTrue(slide.contains("""<a:xfrm flipH="1">"""))
        assertTrue("a quarter left, as PowerPoint writes it", slide.contains("""<a:xfrm rot="16200000" flipV="1">"""))
        assertKept(deck(), PptxReader.read(bytes))
    }

    @Test
    fun odpKeepsThem() {
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray()
        val content = String(com.therealityteam.aeropages.format.OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue("turned by its transform, as LibreOffice writes one", content.contains("draw:transform=\"rotate (-0.5235987"))
        assertTrue("mirrored in its style", content.contains("style:mirror=\"horizontal\""))
        assertFalse("top to bottom as a half turn, mirrored (LibreOffice draws no vertical mirror)", content.contains("style:mirror=\"vertical\""))
        assertKept(deck(), OdpReader.read(bytes))
    }
}
