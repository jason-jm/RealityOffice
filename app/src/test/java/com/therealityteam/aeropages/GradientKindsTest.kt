package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.DrawingShape
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.GradientKind
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SheetShape
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Gradients that run other ways than along a line: axial, radial and rectangular, with their centres. */
class GradientKindsTest {

    private fun shape(kind: GradientKind, cx: Float = 0.5f, cy: Float = 0.5f, type: ShapeType = ShapeType.ROUNDED) =
        SlideShape(type, SlideBox("x"), 0.1f, 0.1f, 0.3f, 0.3f, fill = 0xFFFFFFFF, border = null, fillGradient = 0xFF2E75B6, gradientAngle = 90f,
            gradientKind = kind, gradientCx = cx, gradientCy = cy)

    private fun deck() = Presentation(mutableListOf(Slide(title = SlideBox("Gradients")).also {
        it.shapes.add(shape(GradientKind.LINEAR))
        it.shapes.add(shape(GradientKind.AXIAL))
        it.shapes.add(shape(GradientKind.RADIAL, 0.2f, 0.75f))
        it.shapes.add(shape(GradientKind.RECTANGULAR))
    }))

    private fun check(back: List<SlideShape>, what: String) {
        assertEquals(what, listOf(GradientKind.LINEAR, GradientKind.AXIAL, GradientKind.RADIAL, GradientKind.RECTANGULAR), back.map { it.gradientKind })
        back.forEach { s -> assertEquals(what, 0xFFFFFFFF, s.fill); assertEquals(what, 0xFF2E75B6, s.fillGradient) }
        assertEquals(what, 90f, back[1].gradientAngle, 0.01f)
        assertEquals(what, 0.2f, back[2].gradientCx, 0.001f); assertEquals(what, 0.75f, back[2].gradientCy, 0.001f)
        assertEquals(what, 0.5f, back[3].gradientCx, 0.001f)
    }

    @Test
    fun everyKindComesBackFromPptxAndOdp() {
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        check(PptxReader.read(pptx).slides[0].shapes, "pptx")
        val odp = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray()
        check(OdpReader.read(odp).slides[0].shapes, "odp")
    }

    @Test
    fun aMirroredShapesRadialCentreStaysWhereItShows() {
        // Impress mirrors a custom shape's outline, not its fill: the centre is written where it shows.
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("M")).also { it.shapes.add(shape(GradientKind.RADIAL, 0.2f, 0.3f, ShapeType.TRIANGLE).also { s -> s.flipH = true }) }))
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()
        val parts = HashMap<String, String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { e -> parts[e.name] = String(z.readBytes()) } }
        assert(parts.values.any { it.contains("""draw:cx="80%"""") }) { "the centre as shown, mirrored in the file" }
        val back = OdpReader.read(bytes).slides[0].shapes.single()
        assertEquals(0.2f, back.gradientCx, 0.001f); assertEquals(0.3f, back.gradientCy, 0.001f)
    }

    @Test
    fun powerPointsPathGradientsAndImpressStyles() {
        // "From the top right corner": the fill-to rectangle closed on that corner.
        val sp = XmlPull.parse(("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<p:nvSpPr><p:cNvPr id="4" name="S"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:prstGeom prst="rect"/>""" +
            """<a:gradFill><a:gsLst><a:gs pos="0"><a:srgbClr val="FFFF00"/></a:gs><a:gs pos="100000"><a:srgbClr val="FF0000"/></a:gs></a:gsLst>""" +
            """<a:path path="circle"><a:fillToRect l="100000" t="0" r="0" b="100000"/></a:path></a:gradFill></p:spPr></p:sp>""").toByteArray())!!
        val s = DrawingShape.read(sp, PptxReader.Palette.DEFAULT) { SlideBox("") }
        assertEquals(GradientKind.RADIAL, s.gradientKind); assertEquals(1f, s.gradientCx); assertEquals(0f, s.gradientCy)
        assertEquals("the first stop at the centre", 0xFFFFFF00, s.fill)
        // Impress's "Shapes" style: rectangular, its start colour (grey) at the edges, its end colour (white) at the centre.
        val styles = """<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"><office:styles>""" +
            """<draw:gradient draw:name="Shapes" draw:style="rectangular" draw:cx="50%" draw:cy="50%" draw:start-color="#cccccc" draw:end-color="#ffffff" draw:angle="0deg" draw:border="0%"/>""" +
            """<draw:gradient draw:name="Ax" draw:style="axial" draw:start-color="#000000" draw:end-color="#ffffff" draw:angle="0deg"/>""" +
            """<style:style style:name="gs" style:family="graphic"><style:graphic-properties draw:fill="gradient" draw:fill-gradient-name="Shapes"/></style:style>""" +
            """<style:style style:name="ga" style:family="graphic"><style:graphic-properties draw:fill="gradient" draw:fill-gradient-name="Ax"/></style:style>""" +
            """</office:styles></office:document-styles>"""
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"><office:body><office:presentation><draw:page>""" +
            """<draw:custom-shape draw:style-name="gs" svg:x="1cm" svg:y="1cm" svg:width="4cm" svg:height="2cm"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>""" +
            """<draw:custom-shape draw:style-name="ga" svg:x="1cm" svg:y="4cm" svg:width="4cm" svg:height="2cm"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>""" +
            """</draw:page></office:presentation></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> listOf("content.xml" to content, "styles.xml" to styles).forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        val (rect, axial) = OdpReader.read(bos.toByteArray()).slides[0].shapes
        assertEquals(GradientKind.RECTANGULAR, rect.gradientKind)
        assertEquals("white at the centre", 0xFFFFFFFF, rect.fill); assertEquals(0xFFCCCCCC, rect.fillGradient)
        assertEquals(GradientKind.AXIAL, axial.gradientKind)
        assertEquals("Impress's end colour at the edges", 0xFFFFFFFF, axial.fill); assertEquals("its start colour on the centre line", 0xFF000000, axial.fillGradient)
        assertEquals("ODF's 0° runs top to bottom", 90f, axial.gradientAngle, 0.01f)
    }

    @Test
    fun sheetShapesKeepTheirKind() {
        val wb = Workbook.blank()
        wb.sheets[0].shapes.add(SheetShape(shape(GradientKind.RADIAL, 0.3f, 0.3f), 1, 1, 0f, 0f, 120f, 80f, "Glow"))
        val back = XlsxReader.read(ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()).sheets[0].shapes.single().shape
        assertEquals(GradientKind.RADIAL, back.gradientKind); assertEquals(0.3f, back.gradientCx, 0.001f)
    }
}
