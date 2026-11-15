package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.model.DesignPicture
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A template's master pages — their picture fills, pictures and shapes — as the slides' designs. */
class SlideDesignsTest {

    /** A 1×1 PNG. */
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
        0, 0, 0, 13, 73, 68, 65, 84, 120, -38, 99, -8, -1, -1, 63, 0, 5, -2, 2, -2, -89, 53, -127, -124, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    private val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0""""

    @Test
    fun libreOfficeMasterPagesAreTheSlidesDesigns() {
        // As an Impress template writes one: a photograph as the page's fill, a band, a logo (in a
        // group), the master's own placeholders — and a page that hides the master's objects.
        val styles = """<office:document-styles $ns><office:styles><draw:fill-image draw:name="Bitmap_20_1" xlink:href="Pictures/photo.png"/></office:styles>""" +
            """<office:automatic-styles><style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="960pt" fo:page-height="540pt"/></style:page-layout>""" +
            """<style:style style:name="Mdp1" style:family="drawing-page"><style:drawing-page-properties draw:fill="bitmap" draw:fill-image-name="Bitmap_20_1" style:repeat="stretch"/></style:style>""" +
            """<style:style style:name="gr1" style:family="graphic"><style:graphic-properties draw:fill="solid" draw:fill-color="#e84c3d" draw:stroke="none"/></style:style></office:automatic-styles>""" +
            """<office:master-styles><style:master-page style:name="Candy" style:page-layout-name="PM1" draw:style-name="Mdp1">""" +
            """<draw:custom-shape draw:style-name="gr1" svg:x="0pt" svg:y="480pt" svg:width="960pt" svg:height="60pt"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>""" +
            """<draw:g><draw:frame svg:x="840pt" svg:y="20pt" svg:width="96pt" svg:height="48pt"><draw:image xlink:href="Pictures/logo.png"/></draw:frame></draw:g>""" +
            """<draw:frame presentation:class="title" presentation:placeholder="true" svg:x="48pt" svg:y="40pt" svg:width="864pt" svg:height="90pt"><draw:text-box/></draw:frame>""" +
            """<draw:frame presentation:class="page-number" svg:x="860pt" svg:y="500pt" svg:width="80pt" svg:height="30pt"><draw:text-box><text:p><text:page-number/></text:p></draw:text-box></draw:frame>""" +
            """</style:master-page></office:master-styles></office:document-styles>"""
        val content = """<office:document-content $ns><office:automatic-styles><style:style style:name="dp2" style:family="drawing-page"><style:drawing-page-properties presentation:background-objects-visible="false"/></style:style></office:automatic-styles>""" +
            """<office:body><office:presentation><draw:page draw:name="p1" draw:master-page-name="Candy"/><draw:page draw:name="p2" draw:style-name="dp2" draw:master-page-name="Candy"/></office:presentation></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            listOf("content.xml" to content.toByteArray(), "styles.xml" to styles.toByteArray(), "Pictures/photo.png" to png, "Pictures/logo.png" to png)
                .forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        val p = OdpReader.read(bos.toByteArray())
        val d = p.designs.single()
        assertEquals("Candy", d.name)
        assertArrayEquals(png, d.picture!!.data)
        assertEquals(1f, d.picture!!.wPct, 0.001f)
        assertEquals("the band and the logo — not the placeholders", 2, d.items.size)
        val band = (d.items[0] as DesignShape).shape
        assertEquals(ShapeType.RECT, band.type); assertEquals(0xFFE84C3D, band.fill); assertEquals(480f / 540f, band.yPct, 0.001f)
        val logo = (d.items[1] as DesignPicture).image
        assertEquals(840f / 960f, logo.xPct, 0.001f)
        assertEquals(listOf("Candy", "Candy"), p.slides.map { it.design })
        assertEquals(listOf(true, false), p.slides.map { it.designObjects })
        assertTrue(p.designOf(p.slides[0]) === d)
    }

    @Test
    fun designsComeBackFromOurOwnFiles() {
        val design = SlideDesign("Blue_20_Curve", background = 0xFFF2F2F2,
            picture = SlideImage(png, "image/png", 1f, 1f, 0f, 0f, 1f, 1f),
            items = mutableListOf(DesignShape(SlideShape(ShapeType.ELLIPSE, SlideBox(""), 0.7f, 0.6f, 0.4f, 0.6f, fill = 0xFF2E75B6, border = null)),
                DesignPicture(SlideImage(png, "image/png", 1f, 1f, 0.02f, 0.9f, 0.1f, 0.08f))))
        val p = Presentation(mutableListOf(Slide(title = SlideBox("On the design"), design = "Blue_20_Curve"),
            Slide(title = SlideBox("Without its objects"), design = "Blue_20_Curve", designObjects = false), Slide(title = SlideBox("Plain"))),
            designs = mutableListOf(design))
        val bos = ByteArrayOutputStream()
        OdpWriter.write(p, bos)
        val back = OdpReader.read(bos.toByteArray())
        val d = back.designs.single()
        assertEquals("Blue_20_Curve", d.name)
        assertArrayEquals(png, d.picture!!.data)
        assertEquals(2, d.items.size)
        val ellipse = (d.items[0] as DesignShape).shape
        assertEquals(ShapeType.ELLIPSE, ellipse.type); assertEquals(0xFF2E75B6, ellipse.fill); assertEquals(0.7f, ellipse.xPct, 0.001f)
        assertEquals(0.02f, (d.items[1] as DesignPicture).image.xPct, 0.001f)
        assertEquals(listOf("Blue_20_Curve", "Blue_20_Curve", null), back.slides.map { it.design })
        assertEquals(listOf(true, false, true), back.slides.map { it.designObjects })
        assertNull(back.designOf(back.slides[2]))
    }

    @Test
    fun libreOfficesOwnTemplate() {
        val file = File("/Applications/LibreOffice.app/Contents/Resources/template/common/presnt/Candy.otp")
        assumeTrue("LibreOffice's templates are on this machine", file.exists())
        val p = OdpReader.read(file.readBytes())
        assertTrue("its master pages draw", p.designs.size >= 5)
        // (Masters drawn with polygons alone are not designs yet.)
        assertTrue("the title slide is on its design", p.designOf(p.slides[0]) != null)
        assertTrue("most slides are", p.slides.count { p.designOf(it) != null } * 2 > p.slides.size)
        assertTrue("the photograph is there", p.designs.any { d -> d.picture != null || d.items.any { it is DesignPicture } })
        assertFalse(p.designs.any { d -> d.items.any { it is DesignShape && it.shape.box.text.contains("<number>") } })
    }
}
