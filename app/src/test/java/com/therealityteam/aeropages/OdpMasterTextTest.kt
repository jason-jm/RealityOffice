package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.MasterText
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The master's title and body text — and a design's own — kept as Impress keeps them, `<master>-title` / `-outline1`. */
class OdpMasterTextTest {

    private fun deck(): Presentation {
        val band = SlideDesign("Band", items = mutableListOf(DesignShape(SlideShape(ShapeType.RECT, xPct = 0f, yPct = 0f, wPct = 1f, hPct = 0.3f, fill = 0xFF7A1F2B))),
            titleText = MasterText(null, null, 0xFFFFE699, null))
        val p = Presentation(mutableListOf(
            Slide(title = SlideBox("Plain"), body = SlideBox("Body")),
            Slide(title = SlideBox("On the band"), body = SlideBox("Body")).apply { design = "Band" },
        ), designs = mutableListOf(band))
        p.master.titleFont = "Georgia"; p.master.titleSizePt = 36f; p.master.titleColor = 0xFF203864; p.master.titleBold = true
        p.master.bodyFont = "Verdana"; p.master.bodySizePt = 20f; p.master.bodyColor = 0xFF404040
        return p
    }

    private fun assertMasterKept(want: Presentation, got: Presentation) {
        assertTrue("the master's text", want.master.sameAs(got.master))
        assertEquals("the design's own title colour", 0xFFFFE699, got.designs.single().titleText?.color)
        assertNull(got.designs.single().bodyText)
        got.slides.forEach { s -> (s.title.paragraph.runs + s.body.paragraph.runs).forEach { r -> assertNull("runs inherit", r.format.color); assertNull(r.format.fontFamily) } }
        assertEquals(0xFFFFE699, got.titleTextOn(got.slides[1]).color)
        assertEquals(0xFF203864, got.titleTextOn(got.slides[0]).color)
    }

    @Test
    fun odpKeepsTheMastersText() {
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray()
        val styles = String(com.therealityteam.aeropages.format.OfficeXml.unzip(bytes)["styles.xml"]!!)
        assertTrue(styles.contains("""<style:style style:name="Default-title" style:family="presentation">"""))
        assertTrue("the design's master its own", styles.contains("""style:name="Band-title""""))
        assertTrue(styles.contains("""presentation:class="outline" presentation:placeholder="true""""))
        assertMasterKept(deck(), OdpReader.read(bytes))
    }

    @Test
    fun pptxKeepsADesignsText() {
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        assertMasterKept(deck(), PptxReader.read(bytes))
    }

    /** (Opt-in, `-Daero.odpDir=…`: LibreOffice's templates as ODP.) Each master page's title look comes with it. */
    @Test
    fun libreOfficesTemplatesMasterText() {
        val dir = System.getProperty("aero.odpDir")?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(dir != null && java.io.File(dir, "tpl-Focus.odp").isFile)
        val p = OdpReader.read(java.io.File(dir, "tpl-Focus.odp").readBytes())
        // The title page's master (the deck's): white 32 pt titles; the others' #333333.
        assertEquals(32f, p.master.titleSizePt)
        assertEquals(0xFFFFFFFF, p.master.titleColor)
        assertEquals(0xFF333333, p.designs.first { it.name == "Focus1" }.titleText?.color)
        assertEquals(0xFF333333, p.titleTextOn(p.slides[1]).color)
    }
}
