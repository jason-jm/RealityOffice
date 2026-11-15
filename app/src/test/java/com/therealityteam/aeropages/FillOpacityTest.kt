package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A see-through shape (its fill's alpha) as pptx (`a:alpha`) and odp (`draw:opacity`) keep it. */
class FillOpacityTest {

    private fun deck(): Presentation {
        val s = Slide()
        // 50% red, solid; a gradient from 80% blue to 80% white; a 60% outline.
        s.shapes += SlideShape(ShapeType.RECT, fill = 0x80FF0000, border = 0x99000000)
        s.shapes += SlideShape(ShapeType.ELLIPSE, fill = 0xCC0000FF, border = null).also { it.fillGradient = 0xCCFFFFFF }
        s.shapes += SlideShape(ShapeType.RECT, fill = 0xFF00FF00, border = 0xFF000000)
        // Purple fading out from left to right.
        s.shapes += SlideShape(ShapeType.RECT, fill = 0xFF7030A0, border = null).also { it.fillGradient = 0x007030A0; it.gradientAngle = 0f }
        return Presentation(mutableListOf(s))
    }

    private fun alpha(c: Long?) = ((c ?: 0L) ushr 24) and 0xFF

    @Test fun pptxKeepsAFillsAlpha() {
        val bos = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }
        val xml = String(com.therealityteam.aeropages.format.OfficeXml.unzip(bos.toByteArray())["ppt/slides/slide1.xml"]!!)
        assertTrue(xml.contains("""<a:srgbClr val="FF0000"><a:alpha val="50196"/></a:srgbClr>"""))
        val shapes = PptxReader.read(bos.toByteArray()).slides[0].shapes
        assertEquals(0x80L, alpha(shapes[0].fill)); assertEquals(0xFF0000L, shapes[0].fill!! and 0xFFFFFF)
        assertEquals(0x99L, alpha(shapes[0].border))
        assertEquals(0xCCL, alpha(shapes[1].fill)); assertEquals(0xCCL, alpha(shapes[1].fillGradient))
        // An opaque fill stays as it was written before: no a:alpha.
        assertEquals(0xFFL, alpha(shapes[2].fill))
        assertTrue(!xml.contains("""<a:srgbClr val="00FF00"><a:alpha"""))
    }

    @Test fun odpKeepsAFillsOpacity() {
        val bos = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }
        val shapes = OdpReader.read(bos.toByteArray()).slides[0].shapes
        assertEquals(0x80L, alpha(shapes[0].fill)); assertEquals(0xFF0000L, shapes[0].fill!! and 0xFFFFFF)
        assertEquals(0xCCL, alpha(shapes[1].fill))
        assertEquals(0xFFL, alpha(shapes[2].fill))
        // A fade: an opacity gradient beside the colour one.
        assertEquals(0xFFL, alpha(shapes[3].fill)); assertEquals(0L, alpha(shapes[3].fillGradient)); assertEquals(0f, shapes[3].gradientAngle, 0.5f)
    }

    @Test fun pptxKeepsAFade() {
        val bos = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }
        val s = PptxReader.read(bos.toByteArray()).slides[0].shapes[3]
        assertEquals(0xFF7030A0, s.fill); assertEquals(0x007030A0L, s.fillGradient)
    }
}
