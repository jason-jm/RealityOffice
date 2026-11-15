package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Text that does not wrap (PowerPoint's click-to-type text boxes, Office's wrap none): kept through pptx and ODP. */
class SlideTextWrapTest {

    @Test
    fun unwrappedTextIsKept() {
        val slide = Slide()
        val box = SlideShape(ShapeType.TEXTBOX, SlideBox("One long line that would wrap", CharacterFormat(fontSizePt = 24f), TextAlign.CENTER),
            xPct = 0.4f, yPct = 0.4f, wPct = 0.1f, hPct = 0.1f, fill = null, border = null).also { it.noWrap = true }
        val wrapped = SlideShape(ShapeType.RECT, SlideBox("Wraps", CharacterFormat(fontSizePt = 18f), TextAlign.LEFT))
        slide.shapes += box; slide.shapes += wrapped
        val p = Presentation(mutableListOf(slide))
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        assertTrue(String(OfficeXml.unzip(pptx)["ppt/slides/slide1.xml"]!!).contains("wrap=\"none\""))
        val fromPptx = PptxReader.read(pptx).slides[0].shapes
        assertEquals(listOf(true, false), fromPptx.map { it.noWrap })
        val odp = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        // In their stacking order too (a text box under a shape stays under it).
        assertEquals(listOf("One long line that would wrap" to true, "Wraps" to false), OdpReader.read(odp).slides[0].shapes.map { it.box.text to it.noWrap })
    }

    /** pptx's vertical text (`vert270`, `vert`): the box turned a quarter, its width and height changed about its centre. */
    @Test
    fun verticalTextTurnsItsBox() {
        val slide = Slide()
        slide.shapes += SlideShape(ShapeType.TEXTBOX, SlideBox("Upwards", CharacterFormat(fontSizePt = 20f)), xPct = 0.05f, yPct = 0.2f, wPct = 0.05f, hPct = 0.6f, fill = null, border = null)
        val p = Presentation(mutableListOf(slide))
        val parts = OfficeXml.unzip(ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()).toMutableMap()
        val xml = String(parts["ppt/slides/slide1.xml"]!!)
        parts["ppt/slides/slide1.xml"] = xml.replace("<a:bodyPr ", "<a:bodyPr vert=\"vert270\" ").toByteArray()
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> parts.forEach { (n, b) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
        val sh = PptxReader.read(out.toByteArray()).slides[0].shapes.single()
        assertEquals(270f, sh.rotationDeg, 0.01f)
        // 16:9: the 0.05-wide, 0.6-tall box becomes 0.6 × 16/9 tall-ish wide and 0.05 × 9/16 … about the same centre.
        assertEquals(0.6f * 9f / 16f, sh.wPct, 0.001f); assertEquals(0.05f * 16f / 9f, sh.hPct, 0.001f)
        assertEquals(0.075f, sh.xPct + sh.wPct / 2f, 0.001f); assertEquals(0.5f, sh.yPct + sh.hPct / 2f, 0.001f)
    }
}
