package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.DrawingShape
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SheetShape
import com.therealityteam.aeropages.model.ShapeShadow
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shapes' outer shadows: PowerPoint's `a:outerShdw`, Impress's `draw:shadow` — read, written, kept through the app. */
class ShapeShadowTest {

    private fun near(e: Float, a: Float, what: String = "") = assertEquals(what, e, a, 0.02f)

    private fun assertShadow(e: ShapeShadow, a: ShapeShadow?, what: String) {
        assertNotNull(what, a)
        assertEquals(what, e.color, a!!.color)
        near(e.dxPt, a.dxPt, what); near(e.dyPt, a.dyPt, what); near(e.blurPt, a.blurPt, what)
    }

    @Test
    fun powerPointsOuterShadow() {
        // PowerPoint's "Offset: Bottom Right": 3 pt at 45°, blurred 4 pt, black at 40 %.
        val sp = XmlPull.parse(("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<p:nvSpPr><p:cNvPr id="4" name="S"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="4472C4"/></a:solidFill>""" +
            """<a:effectLst><a:outerShdw blurRad="50800" dist="38100" dir="2700000" algn="tl" rotWithShape="0"><a:prstClr val="black"><a:alpha val="40000"/></a:prstClr></a:outerShdw></a:effectLst>""" +
            """</p:spPr></p:sp>""").toByteArray())!!
        val s = DrawingShape.read(sp, PptxReader.Palette.DEFAULT) { SlideBox("") }.shadow!!
        assertEquals(0x66000000, s.color)
        near(3f * 0.70710677f, s.dxPt); near(3f * 0.70710677f, s.dyPt); near(4f, s.blurPt)
        // An empty effect list is no shadow.
        val none = XmlPull.parse(("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<p:nvSpPr><p:cNvPr id="4" name="S"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:prstGeom prst="rect"/><a:effectLst/></p:spPr></p:sp>""").toByteArray())!!
        assertNull(DrawingShape.read(none, PptxReader.Palette.DEFAULT) { SlideBox("") }.shadow)
    }

    @Test
    fun shadowsComeBackFromEveryFormat() {
        val shadows = ShapeShadow.PRESETS + ShapeShadow(0xFF808080, -4f, 6f, 0f)
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("Shadows")).also { slide ->
            shadows.forEachIndexed { k, s -> slide.shapes.add(SlideShape(ShapeType.ROUNDED, SlideBox("$k"), 0.05f + k * 0.18f, 0.3f, 0.15f, 0.2f, shadow = s).also { it.rotationDeg = if (k == 0) 30f else 0f }) }
        }))
        val fromPptx = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()).slides[0].shapes
        val fromOdp = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()).slides[0].shapes
        shadows.forEachIndexed { k, s ->
            assertShadow(s, fromPptx[k].shadow, "pptx $k")
            assertShadow(s, fromOdp[k].shadow, "odp $k")
        }
        assertEquals("the turn is the shape's, not the shadow's", 30f, fromOdp[0].rotationDeg, 0.01f)
        // On a sheet.
        val wb = Workbook.blank()
        wb.sheets[0].shapes.add(SheetShape(SlideShape(ShapeType.ELLIPSE, SlideBox(""), shadow = ShapeShadow.PRESETS[3]), 1, 1, 0f, 0f, 90f, 60f, "Dot"))
        assertShadow(ShapeShadow.PRESETS[3], XlsxReader.read(ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()).sheets[0].shapes.single().shape.shadow, "xlsx")
        // A copy keeps it; a shape without one has none.
        assertEquals(shadows[0], deck.slides[0].shapes[0].copyOf().shadow)
        assertNull(SlideShape(ShapeType.RECT).shadow)
    }

    @Test
    fun impressShadowsWithTheirDefaults() {
        val styles = """<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0"><office:styles>""" +
            """<style:style style:name="base" style:family="graphic"><style:graphic-properties draw:shadow="visible" draw:shadow-color="#009bdd"/></style:style>""" +
            """<style:style style:name="child" style:family="graphic" style:parent-style-name="base"><style:graphic-properties draw:shadow-offset-x="0cm" draw:shadow-offset-y="0.03cm" draw:shadow-opacity="50%" loext:shadow-blur="0.1cm"/></style:style>""" +
            """<style:style style:name="off" style:family="graphic" style:parent-style-name="base"><style:graphic-properties draw:shadow="hidden"/></style:style>""" +
            """</office:styles></office:document-styles>"""
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"><office:body><office:presentation><draw:page>""" +
            """<draw:custom-shape draw:style-name="base" svg:x="1cm" svg:y="1cm" svg:width="4cm" svg:height="2cm"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>""" +
            """<draw:custom-shape draw:style-name="child" svg:x="1cm" svg:y="4cm" svg:width="4cm" svg:height="2cm"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>""" +
            """<draw:custom-shape draw:style-name="off" svg:x="1cm" svg:y="7cm" svg:width="4cm" svg:height="2cm"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>""" +
            """</draw:page></office:presentation></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> listOf("content.xml" to content, "styles.xml" to styles).forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        val (base, child, off) = OdpReader.read(bos.toByteArray()).slides[0].shapes
        assertShadow(ShapeShadow(0xFF009BDD, 5.67f, 5.67f, 0f), base.shadow, "ODF's default offset, opaque, sharp")
        assertShadow(ShapeShadow(0x80009BDD, 0f, 0.85f, 2.83f), child.shadow, "its own offset, opacity and blur")
        assertNull(off.shadow)
    }

    /** A 1×1 PNG. */
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
        0, 0, 0, 13, 73, 68, 65, 84, 120, -38, 99, -8, -1, -1, 63, 0, 5, -2, 2, -2, -89, 53, -127, -124, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    @Test
    fun picturesCastShadowsToo() {
        val s = ShapeShadow(0x4D000000, 4f, 4f, 6f)
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("Photo")).also {
            it.images.add(com.therealityteam.aeropages.model.SlideImage(png, "image/png", 1f, 1f, 0.2f, 0.2f, 0.3f, 0.3f, shadow = s))
            it.images.add(com.therealityteam.aeropages.model.SlideImage(png, "image/png", 1f, 1f, 0.6f, 0.2f, 0.3f, 0.3f).also { img -> img.cropL = 0.1f })
        }))
        val fromPptx = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()).slides[0].images
        assertShadow(s, fromPptx[0].shadow, "pptx"); assertNull(fromPptx[1].shadow)
        val fromOdp = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()).slides[0].images
        assertShadow(s, fromOdp[0].shadow, "odp"); assertNull(fromOdp[1].shadow)
        assertEquals("a crop still reads beside a shadowless style", 0.1f, fromOdp[1].cropL, 0.01f)
        assertEquals(s, deck.slides[0].images[0].copyOf().shadow)
    }

    @Test
    fun pagesAndSheetsPicturesToo() {
        val s = ShapeShadow(0x59000000, 3f, 3f, 5f)
        val doc = com.therealityteam.aeropages.model.Document(mutableListOf(
            com.therealityteam.aeropages.model.ParagraphBlock(com.therealityteam.aeropages.model.Paragraph.of("A picture")),
            com.therealityteam.aeropages.model.ImageBlock(png, "image/png", 120f, 80f, shadow = s),
            com.therealityteam.aeropages.model.ImageBlock(png, "image/png", 60f, 40f)))
        fun pictures(d: com.therealityteam.aeropages.model.Document) = d.blocks.filterIsInstance<com.therealityteam.aeropages.model.ImageBlock>()
        val fromDocx = pictures(com.therealityteam.aeropages.format.docx.DocxReader.read(java.io.ByteArrayInputStream(
            ByteArrayOutputStream().also { com.therealityteam.aeropages.format.docx.DocxWriter.write(doc, it) }.toByteArray())))
        assertShadow(s, fromDocx[0].shadow, "docx"); assertNull(fromDocx[1].shadow)
        val fromOdt = pictures(com.therealityteam.aeropages.format.odf.OdtReader.read(
            ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdtWriter.write(doc, it) }.toByteArray()))
        assertShadow(s, fromOdt[0].shadow, "odt"); assertNull(fromOdt[1].shadow)
        assertEquals(s, (doc.blocks[1].copyOf() as com.therealityteam.aeropages.model.ImageBlock).shadow)

        val wb = Workbook.blank()
        wb.sheets[0].images.add(com.therealityteam.aeropages.model.SheetImage(png, "image/png", 1, 1, 0f, 0f, 90f, 60f, "Shadowed").also { it.shadow = s })
        wb.sheets[0].images.add(com.therealityteam.aeropages.model.SheetImage(png, "image/png", 5, 1, 0f, 0f, 90f, 60f, "Plain"))
        val fromXlsx = XlsxReader.read(ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()).sheets[0].images
        assertShadow(s, fromXlsx[0].shadow, "xlsx"); assertNull(fromXlsx[1].shadow)
        val fromOds = com.therealityteam.aeropages.format.odf.OdsReader.read(
            ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdsWriter.write(wb, it) }.toByteArray()).sheets[0].images
        assertShadow(s, fromOds.first { it.name == "Shadowed" }.shadow, "ods"); assertNull(fromOds.first { it.name == "Plain" }.shadow)
        assertEquals(s, wb.sheets[0].images[0].copyOf().shadow)
    }

    @Test
    fun theThemesEffectStyleCastsTheShadowItsStyleNames() {
        // PowerPoint 2007's theme: its second effect style an outer shadow in the reference's colour (phClr).
        val theme = XmlPull.parse(("""<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements><a:fmtScheme><a:effectStyleLst>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """<a:effectStyle><a:effectLst><a:outerShdw blurRad="40000" dist="23000" dir="5400000" rotWithShape="0"><a:schemeClr val="phClr"><a:alpha val="35000"/></a:schemeClr></a:outerShdw></a:effectLst></a:effectStyle>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst></a:fmtScheme></a:themeElements></a:theme>""").toByteArray())!!
        val palette = PptxReader.paletteOf(theme)
        fun shapeWith(effectRef: String, own: String = "") = XmlPull.parse(("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<p:nvSpPr><p:cNvPr id="4" name="S"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:prstGeom prst="rect"/>$own</p:spPr>""" +
            """<p:style><a:lnRef idx="1"><a:schemeClr val="accent1"/></a:lnRef><a:fillRef idx="3"><a:schemeClr val="accent1"/></a:fillRef>$effectRef<a:fontRef idx="minor"/></p:style></p:sp>""").toByteArray())!!
        val themed = DrawingShape.read(shapeWith("""<a:effectRef idx="2"><a:srgbClr val="00FF00"/></a:effectRef>"""), palette) { SlideBox("") }.shadow!!
        assertEquals("the reference's colour, the theme's alpha", 0x5900FF00, themed.color)
        near(0f, themed.dxPt); near(23000f / 12700f, themed.dyPt); near(40000f / 12700f, themed.blurPt)
        assertNull("idx 0 is none", DrawingShape.read(shapeWith("""<a:effectRef idx="0"/>"""), palette) { SlideBox("") }.shadow)
        assertNull("its own empty effect list wins", DrawingShape.read(shapeWith("""<a:effectRef idx="2"/>""", "<a:effectLst/>"), palette) { SlideBox("") }.shadow)
    }

    @Test
    fun libreOfficesBlueCurveCastsItsShadow() {
        val file = File("/Applications/LibreOffice.app/Contents/Resources/template/common/presnt/Blue_Curve.otp")
        assumeTrue("LibreOffice's templates are on this machine", file.exists())
        val p = OdpReader.read(file.readBytes())
        val curve = p.designs.flatMap { d -> d.items.filterIsInstance<com.therealityteam.aeropages.model.DesignShape>().map { it.shape } }
            .first { it.type == ShapeType.FREEFORM }
        assertTrue(curve.shadow != null)
        assertEquals(0xFF009BDD, curve.shadow!!.color)
    }
}
