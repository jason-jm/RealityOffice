package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A deck's slide size: read from and written to pptx (`p:sldSz`) and ODP (the page), and set from Design → Slide Size. */
class SlideSizeTest {

    private fun zip(files: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""

    /** A one-slide deck of [cx] × [cy] EMU holding a rectangle over its right half. */
    private fun deckOf(cx: Int, cy: Int, type: String? = null): ByteArray {
        val slide = """<p:sld $ns><p:cSld><p:spTree>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="4" name="Right half"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="${cx / 2}" y="0"/><a:ext cx="${cx / 2}" cy="$cy"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:sp>""" +
            """</p:spTree></p:cSld></p:sld>"""
        val typeAttr = type?.let { """ type="$it"""" } ?: ""
        return zip(mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="$cx" cy="$cy"$typeAttr/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide,
        ))
    }

    @Test
    fun powerPointsSlideSizeIsWhatFramesAreTakenOf() {
        // A 4:3 deck: its shapes were placed as if the slide were 16:9 wide, squeezed into three quarters.
        val standard = PptxReader.read(deckOf(9144000, 6858000, "screen4x3"))
        assertEquals(SlideSize.STANDARD, standard.slideSize)
        assertFalse(standard.widescreen)
        val half = standard.slides[0].shapes.single()
        assertEquals(0.5f, half.xPct, 0.001f); assertEquals(0.5f, half.wPct, 0.001f); assertEquals(1f, half.hPct, 0.001f)
        // A size of its own (an A3-ish poster slide) is kept as it is; no size at all is widescreen.
        val poster = PptxReader.read(deckOf(15120000, 10692000))
        assertEquals(1190.55f, poster.slideSize.widthPt, 0.01f); assertEquals(841.89f, poster.slideSize.heightPt, 0.01f)
        assertEquals(0.5f, poster.slides[0].shapes.single().xPct, 0.001f)
        assertEquals(SlideSize.A4, PptxReader.read(deckOf(9906000, 6858000, "A4")).slideSize)
    }

    @Test
    fun theSizeIsWrittenAndEverythingOnTheSlideScalesToIt() {
        val p = Presentation.blank().apply { slideSize = SlideSize.STANDARD; footer = "Q3 review"; showSlideNumber = true }
        p.slides[0].shapes.add(SlideShape(ShapeType.RECT, xPct = 0.5f, yPct = 0.25f, wPct = 0.5f, hPct = 0.5f))
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        assertTrue(String(parts["ppt/presentation.xml"]!!, Charsets.UTF_8).contains("""<p:sldSz cx="9144000" cy="6858000" type="screen4x3"/>"""))
        val slide = String(parts["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue("the shape at half the 4:3 width", slide.contains("""<a:off x="4572000" y="1714500"/><a:ext cx="4572000" cy="3429000"/>"""))
        // The footer and the slide number stay on a slide narrower than 16:9.
        Regex("""<a:off x="(\d+)" y="\d+"/><a:ext cx="(\d+)" cy="\d+"/>""").findAll(slide).forEach { m ->
            assertTrue("inside the slide: ${m.value}", m.groupValues[1].toLong() + m.groupValues[2].toLong() <= 9144000L)
        }
        val back = PptxReader.read(bytes)
        assertEquals(SlideSize.STANDARD, back.slideSize)
        val sh = back.slides[0].shapes.single()
        assertEquals(0.5f, sh.xPct, 0.001f); assertEquals(0.25f, sh.yPct, 0.001f); assertEquals(0.5f, sh.wPct, 0.001f)
        // Widescreen, as PowerPoint writes it, carries no type.
        val wide = ByteArrayOutputStream().also { PptxWriter.write(Presentation.blank(), it) }.toByteArray()
        assertTrue(String(OfficeXml.unzip(wide)["ppt/presentation.xml"]!!, Charsets.UTF_8).contains("""<p:sldSz cx="12192000" cy="6858000"/>"""))
    }

    @Test
    fun openDocumentsPageIsTheSlide() {
        val p = Presentation.blank().apply { slideSize = SlideSize.SCREEN_16_10 }
        p.slides[0].shapes.add(SlideShape(ShapeType.ELLIPSE, xPct = 0.5f, yPct = 0.5f, wPct = 0.25f, hPct = 0.25f))
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        val styles = String(OfficeXml.unzip(bytes)["styles.xml"]!!, Charsets.UTF_8)
        assertTrue(styles.contains("""fo:page-width="720.00pt" fo:page-height="450.00pt""""))
        val back = OdpReader.read(bytes)
        assertEquals(SlideSize.SCREEN_16_10, back.slideSize)
        assertEquals(0.5f, back.slides[0].shapes.single().xPct, 0.001f)
        assertTrue("16:10 is wide", back.widescreen)
    }

    @Test
    fun designSlideSizeIsOneUndoableStep() {
        val e = SlideEditor()
        assertEquals(SlideSize.WIDESCREEN, e.presentation.slideSize)
        e.setSlideSize(SlideSize.A4)
        assertEquals(SlideSize.A4, e.presentation.slideSize)
        assertFalse(e.presentation.widescreen)
        e.setSlideSize(SlideSize.A4)
        e.undo()
        assertEquals("setting the same size again is no step", SlideSize.WIDESCREEN, e.presentation.slideSize)
        assertEquals(SlideSize.STANDARD, SlideSize.of(720.4f, 539.7f))
        assertEquals(SlideSize(800f, 600f), SlideSize.of(800f, 600f))
    }
}
