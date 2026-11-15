package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.ChromeLook
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChrome
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A template's own date, footer and number — where its master puts them and how they read — shown and kept. */
class ChromeLookTest {

    private val corner = ChromeLook(0.80f, 0.04f, 0.15f, 0.06f, TextAlign.RIGHT, 16f, 0xFFDDDDDD)

    private fun deck(): Presentation {
        val band = SlideDesign("Band", items = mutableListOf(DesignShape(SlideShape(ShapeType.RECT, xPct = 0f, yPct = 0f, wPct = 1f, hPct = 0.12f, fill = 0xFF7A1F2B))))
        band.chrome[SlideChrome.Part.NUMBER] = corner
        return Presentation(mutableListOf(
            Slide(title = SlideBox("Plain"), body = SlideBox("Body")),
            Slide(title = SlideBox("Banded"), body = SlideBox("Body")).apply { design = "Band" },
        ), footer = "AeroDesk", showSlideNumber = true, designs = mutableListOf(band))
    }

    private fun near(want: ChromeLook, got: ChromeLook?) {
        assertNotNull(got); got!!
        assertEquals(want.xPct, got.xPct, 0.002f); assertEquals(want.yPct, got.yPct, 0.002f)
        assertEquals(want.wPct, got.wPct, 0.002f); assertEquals(want.hPct, got.hPct, 0.002f)
        assertEquals(want.align, got.align); assertEquals(want.sizePt, got.sizePt, 0.01f); assertEquals(want.color, got.color)
    }

    @Test
    fun placedWhereTheTemplatePutsThem() {
        val p = deck()
        val plain = SlideChrome.placed(p, p.slides[0], 1).single { it.part == SlideChrome.Part.NUMBER }
        assertEquals(SlideChrome.Part.NUMBER.xPct, plain.xPct); assertEquals(SlideChrome.SIZE_PT, plain.sizePt)
        val banded = SlideChrome.placed(p, p.slides[1], 2).single { it.part == SlideChrome.Part.NUMBER }
        assertEquals(0.80f, banded.xPct); assertEquals(16f, banded.sizePt); assertEquals(0xFFDDDDDD, banded.color)
        assertEquals("the footer where the app puts it", SlideChrome.Part.FOOTER.xPct, SlideChrome.placed(p, p.slides[1], 2).single { it.part == SlideChrome.Part.FOOTER }.xPct)
    }

    @Test
    fun odpKeepsThem() {
        val back = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray())
        assertTrue("the app's own placement stays the app's", back.master.chrome.isEmpty())
        val band = back.designs.single()
        near(corner, band.chrome[SlideChrome.Part.NUMBER])
        assertEquals(1, band.chrome.size)
    }

    @Test
    fun pptxKeepsThem() {
        val back = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray())
        assertTrue(back.master.chrome.isEmpty())
        val band = back.designs.single()
        near(corner, band.chrome[SlideChrome.Part.NUMBER])
        assertEquals(1, band.chrome.size)
    }

    /** (Opt-in, `-Daero.odpDir=…`: LibreOffice's templates.) Focus's number sits at its own corner, light grey 14 pt. */
    @Test
    fun libreOfficesFocus() {
        val dir = System.getProperty("aero.odpDir")?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(dir != null && java.io.File(dir, "tpl-Focus.odp").isFile)
        val p = OdpReader.read(java.io.File(dir, "tpl-Focus.odp").readBytes())
        val n = SlideChrome.lookOf(p, p.slides[1], SlideChrome.Part.NUMBER)
        assertNotNull(n); n!!
        assertEquals(20.5f / 28f, n.xPct, 0.005f)
        assertEquals(14f, n.sizePt, 0.01f)
        assertEquals(TextAlign.RIGHT, n.align)
        assertEquals(0xFFDDDDDD, n.color)
    }
}
