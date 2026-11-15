package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChrome
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideResize
import com.therealityteam.aeropages.model.SlideScale
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideSize
import com.therealityteam.aeropages.model.SlideSizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** PowerPoint's Custom Slide Size: its sizes, Number slides from, and Maximize / Ensure Fit scaling everything by one factor. */
class CustomSlideSizeTest {

    /** A widescreen deck: a 40 pt title and a shape over the slide's right half, top to bottom. */
    private fun editor(): SlideEditor {
        val slide = Slide(title = SlideBox("Plan", CharacterFormat(fontSizePt = 40f, bold = true)))
        slide.shapes += SlideShape(ShapeType.RECT, SlideBox("Half", CharacterFormat(fontSizePt = 20f)), xPct = 0.5f, yPct = 0f, wPct = 0.5f, hPct = 1f)
        val p = Presentation(mutableListOf(slide))
        p.master.titleSizePt = 44f
        return SlideEditor().apply { load(p) }
    }

    @Test
    fun ensureFitScalesDownAndCentresWithoutStretching() {
        val e = editor()
        e.setSlideSize(SlideSize.STANDARD, SlideScale.ENSURE_FIT)
        val sh = e.presentation.slides[0].shapes.single()
        // 960 × 540 to 720 × 540: everything three quarters, centred top to bottom (67.5 pt above and below).
        assertEquals(0.5f, sh.xPct, 0.001f); assertEquals(0.5f, sh.wPct, 0.001f)
        assertEquals(0.125f, sh.yPct, 0.001f); assertEquals(0.75f, sh.hPct, 0.001f)
        assertEquals("the shape keeps its shape", 480f / 540f, sh.wPct * 720f / (sh.hPct * 540f), 0.001f)
        assertEquals(30f, e.presentation.slides[0].title.paragraph.runs[0].format.fontSizePt!!, 0.01f)
        assertEquals(15f, sh.box.paragraph.runs[0].format.fontSizePt!!, 0.01f)
        assertEquals(33f, e.presentation.master.titleSizePt!!, 0.01f)
        e.undo()
        assertEquals("one step back", SlideSize.WIDESCREEN, e.presentation.slideSize)
        val back = e.presentation.slides[0].shapes.single()
        assertEquals(0f, back.yPct, 0.001f); assertEquals(20f, back.box.paragraph.runs[0].format.fontSizePt!!, 0.01f)
        assertEquals(44f, e.presentation.master.titleSizePt!!, 0.01f)
    }

    @Test
    fun maximizeKeepsTheSizeAndRunsPastTheEdges() {
        val e = editor()
        e.setSlideSize(SlideSize.STANDARD, SlideScale.MAXIMIZE)
        val sh = e.presentation.slides[0].shapes.single()
        // The same height: the content is 960 pt wide on a 720 pt slide, 120 pt past each side.
        assertEquals(0.5f, sh.xPct, 0.001f); assertEquals(480f / 720f, sh.wPct, 0.001f)
        assertTrue("past the right edge", sh.xPct + sh.wPct > 1f)
        assertEquals(0f, sh.yPct, 0.001f); assertEquals(1f, sh.hPct, 0.001f)
        assertEquals(40f, e.presentation.slides[0].title.paragraph.runs[0].format.fontSizePt!!, 0.01f)
        // The same shape (4:3 to A3): no question, everything 1.4 times, in place.
        assertFalse(SlideResize.reshapes(SlideSize.STANDARD, SlideSize.A3))
        e.setSlideSize(SlideSize.A3)
        assertEquals(0.5f, e.presentation.slides[0].shapes.single().xPct, 0.001f)
        assertEquals(56f, e.presentation.slides[0].title.paragraph.runs[0].format.fontSizePt!!, 0.01f)
    }

    @Test
    fun designsAreScaledAsCopiesAndChromeWithThem() {
        val e = editor()
        val band = SlideShape(ShapeType.RECT, xPct = 0f, yPct = 0.9f, wPct = 1f, hPct = 0.1f)
        val design = SlideDesign("Band", items = mutableListOf(DesignShape(band)))
        design.chrome[SlideChrome.Part.NUMBER] = com.therealityteam.aeropages.model.ChromeLook(0.8f, 0.9f, 0.2f, 0.1f, com.therealityteam.aeropages.model.TextAlign.RIGHT, 12f, null)
        e.presentation.designs += design
        e.presentation.slides[0].design = "Band"
        e.setSlideSize(SlideSize.STANDARD, SlideScale.ENSURE_FIT)
        val scaled = e.presentation.designs.single()
        assertNotSame("a scaled copy, the original untouched", design, scaled)
        assertEquals(0.9f, band.yPct, 0.001f)
        val sh = (scaled.items.single() as DesignShape).shape
        assertEquals((67.5f + 0.9f * 405f) / 540f, sh.yPct, 0.001f)
        assertEquals(9f, scaled.chrome[SlideChrome.Part.NUMBER]!!.sizePt, 0.01f)
        e.undo()
        assertSame(design, e.presentation.designs.single())
    }

    @Test
    fun numberSlidesFromShowsAndIsSaved() {
        val e = editor()
        e.presentation.showSlideNumber = true
        e.setSlideSize(SlideSize.WIDESCREEN, firstNumber = 5)
        assertEquals(5, e.presentation.firstSlideNumber)
        val number = SlideChrome.partsOf(e.presentation, e.presentation.slides[0], 1).first { it.first == SlideChrome.Part.NUMBER }.second
        assertEquals("the first slide shows 5", "5", number)
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(e.presentation, it) }.toByteArray()
        assertTrue(String(OfficeXml.unzip(bytes)["ppt/presentation.xml"]!!, Charsets.UTF_8).contains("""firstSlideNum="5""""))
        assertEquals(5, PptxReader.read(bytes).firstSlideNumber)
        e.undo()
        assertEquals("the number alone is one step too", 1, e.presentation.firstSlideNumber)
        assertEquals(SlideSize.WIDESCREEN, e.presentation.slideSize)
        val plain = ByteArrayOutputStream().also { PptxWriter.write(Presentation.blank(), it) }.toByteArray()
        assertFalse(String(OfficeXml.unzip(plain)["ppt/presentation.xml"]!!, Charsets.UTF_8).contains("firstSlideNum"))
    }

    @Test
    fun powerPointsSizesEitherWayRound() {
        assertEquals(SlideSizePreset.A3, SlideSizePreset.of(SlideSize(756f, 1008f)))
        assertEquals("letter paper is the 4:3 slide", SlideSizePreset.STANDARD, SlideSizePreset.of(SlideSize.STANDARD))
        assertEquals(SlideSize.LEDGER, SlideSize.of(959.2f, 719f))
        assertEquals(SlideSize(540f, 960f), SlideSize.WIDESCREEN.turned(portrait = true))
        assertEquals(SlideSize.WIDESCREEN, SlideSize.WIDESCREEN.turned(portrait = false))
        // A portrait A4 deck: PowerPoint's name for it either way round.
        val p = Presentation.blank().apply { slideSize = SlideSize.A4.turned(portrait = true) }
        val xml = String(OfficeXml.unzip(ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray())["ppt/presentation.xml"]!!, Charsets.UTF_8)
        assertTrue(xml, xml.contains("""<p:sldSz cx="6858000" cy="9906000" type="A4"/>"""))
    }

    /** With `-Daero.sampleDir`: a deck numbered from 5 on a portrait A4 slide, for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = editor()
        e.presentation.showSlideNumber = true
        e.presentation.slides += Slide(title = SlideBox("Second", CharacterFormat(fontSizePt = 40f, bold = true)))
        e.setSlideSize(SlideSize.STANDARD, SlideScale.ENSURE_FIT, firstNumber = 5)
        File(dir, "slide-size.pptx").outputStream().use { PptxWriter.write(e.presentation, it) }
        File(dir, "slide-size.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(e.presentation, it) }
    }
}
