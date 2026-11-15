package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdfLines
import com.therealityteam.aeropages.format.odf.OdfStyles
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.ArrowHead
import com.therealityteam.aeropages.model.ArrowHeads
import com.therealityteam.aeropages.model.LineDash
import com.therealityteam.aeropages.model.LineEnd
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SheetShape
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.hypot

/** Lines' arrowheads and outlines' dashes (and ODF gradients): drawn, read and written in pptx, xlsx, ODP and ODS, edited. */
class ShapeLinesTest {

    private fun zip(files: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun assertSameLine(a: SlideShape, b: SlideShape) {
        assertEquals(a.type, b.type)
        assertEquals(a.dash, b.dash)
        assertEquals(a.beginArrow, b.beginArrow)
        assertEquals(a.endArrow, b.endArrow)
        assertEquals(a.border, b.border)
        assertEquals(a.borderWidthPt, b.borderWidthPt, 0.02f)
        assertEquals(a.flipH, b.flipH); assertEquals(a.flipV, b.flipV)
    }

    @Test
    fun arrowheadsSitOnTheLinesEnds() {
        // A medium triangle on a 2 pt line: 3 widths across and along, its tip on the end, the line stopping inside it.
        val head = ArrowHeads.head(LineEnd(ArrowHead.TRIANGLE), 100f, 0f, 0f, 0f, 2f, 1.5f)!!
        assertArrayEquals(floatArrayOf(100f, 0f, 94f, -3f, 94f, 3f), head.points, 1e-4f)
        assertTrue(head.filled)
        val (x1, _, x2, _) = ArrowHeads.trimmed(0f, 0f, 100f, 0f, null, head)
        assertEquals(0f, x1, 1e-4f); assertEquals(97f, x2, 1e-4f)
        // A thin line's heads are sized as if it were 1.5 wide; small and large are 2 and 5 widths.
        assertEquals(4.5f to 4.5f, ArrowHeads.size(LineEnd(ArrowHead.TRIANGLE), 0.5f, 1.5f))
        assertEquals(4f to 10f, ArrowHeads.size(LineEnd(ArrowHead.TRIANGLE, 0, 2), 2f, 1.5f))
        // Upwards: the head points up at the top end.
        val up = ArrowHeads.head(LineEnd(ArrowHead.STEALTH), 0f, 0f, 0f, 50f, 2f, 1.5f)!!
        assertEquals(0f, up.points[1], 1e-4f)
        assertTrue("its notch behind the tip", up.points[5] in 3f..5f)
        // An open arrow is two strokes, the line running to its tip; an oval sits centred on the end.
        val open = ArrowHeads.head(LineEnd(ArrowHead.ARROW), 100f, 0f, 0f, 0f, 2f, 1.5f)!!
        assertEquals(false, open.filled); assertEquals(0f, open.setBack, 0f)
        val oval = ArrowHeads.head(LineEnd(ArrowHead.OVAL, 2, 2), 100f, 0f, 0f, 0f, 2f, 1.5f)!!
        for (i in oval.points.indices step 2) assertEquals(5f, hypot(oval.points[i] - 100f, oval.points[i + 1]), 1e-3f)
        // No head, or a line with no length, draws none.
        assertNull(ArrowHeads.head(LineEnd.NONE, 100f, 0f, 0f, 0f, 2f, 1.5f))
        assertNull(ArrowHeads.head(LineEnd(ArrowHead.TRIANGLE), 5f, 5f, 5f, 5f, 2f, 1.5f))
        assertEquals(0, ArrowHeads.sizeOf(4f, 2f, 1.5f)); assertEquals(1, ArrowHeads.sizeOf(6f, 2f, 1.5f)); assertEquals(2, ArrowHeads.sizeOf(10f, 2f, 1.5f))
    }

    @Test
    fun dashesKeepTheirPatterns() {
        assertEquals(LineDash.LONG_DASH_DOT, LineDash.of("lgDashDot"))
        assertEquals(LineDash.SOLID, LineDash.of("zigzag"))
        assertArrayEquals(floatArrayOf(8f, 6f), LineDash.DASH.intervals(2f), 0f)
        assertEquals(0, LineDash.SOLID.intervals(2f).size)
        // Each preset is the one nearest its own pattern.
        LineDash.entries.filter { it != LineDash.SOLID }.forEach { d ->
            assertEquals(d, LineDash.nearest(listOf(d.dashLength) + List(d.dots) { 1f }, d.gap))
        }
        assertEquals(LineDash.SOLID, LineDash.nearest(emptyList(), 1f))
        assertEquals("a custom long dash", LineDash.LONG_DASH, LineDash.nearest(listOf(10f), 4f))
    }

    private fun deck(): Presentation {
        val p = Presentation.blank()
        p.slides[0].shapes.add(SlideShape(ShapeType.LINE, fill = null, border = 0xFFC00000, borderWidthPt = 2.25f, xPct = 0.1f, yPct = 0.3f, wPct = 0.5f, hPct = 0f,
            dash = LineDash.LONG_DASH_DOT, beginArrow = LineEnd(ArrowHead.OVAL, 0, 2), endArrow = LineEnd(ArrowHead.STEALTH, 2, 2)).also { it.flipH = true })
        p.slides[0].shapes.add(SlideShape(ShapeType.LINE, fill = null, border = 0xFF4472C4, borderWidthPt = 1f, xPct = 0.1f, yPct = 0.5f, wPct = 0.3f, hPct = 0.2f,
            beginArrow = LineEnd(ArrowHead.DIAMOND), endArrow = LineEnd(ArrowHead.ARROW, 1, 0)))
        p.slides[0].shapes.add(SlideShape(ShapeType.RECT, fill = 0xFFFFC000, border = 0xFF000000, xPct = 0.6f, yPct = 0.5f, wPct = 0.2f, hPct = 0.2f,
            dash = LineDash.SYS_DOT).also { it.fillGradient = 0xFFC00000; it.gradientAngle = 45f })
        return p
    }

    @Test
    fun powerPointLinesKeepArrowsAndDashes() {
        val p = deck()
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val slide = String(OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue("a line is a connector, not a rectangle", slide.contains("""<p:cxnSp><p:nvCxnSpPr>"""))
        assertTrue(slide.contains("""<a:prstGeom prst="line"><a:avLst/></a:prstGeom>"""))
        assertTrue("fill, dash, then the ends, as the schema orders them",
            slide.contains("""<a:ln w="28575"><a:solidFill><a:srgbClr val="C00000"/></a:solidFill><a:prstDash val="lgDashDot"/><a:headEnd type="oval" w="sm" len="lg"/><a:tailEnd type="stealth" w="lg" len="lg"/></a:ln>"""))
        assertTrue(slide.contains("""<a:prstDash val="sysDot"/>"""))
        val back = PptxReader.read(bytes).slides[0].shapes
        assertEquals(3, back.size)
        p.slides[0].shapes.zip(back).forEach { (a, b) -> assertSameLine(a, b) }
        assertEquals("a level line stays level", 0f, back[0].hPct, 0.001f)
    }

    @Test
    fun officesCustomDashesAndDefaultSizesAreRead() {
        val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
        val slide = """<p:sld $ns><p:cSld><p:spTree>""" +
            """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="5" name="Straight Arrow Connector 4"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr><a:xfrm><a:off x="1000000" y="3000000"/><a:ext cx="3000000" cy="0"/></a:xfrm>""" +
            """<a:prstGeom prst="straightConnector1"><a:avLst/></a:prstGeom><a:ln w="19050" cap="rnd"><a:solidFill><a:srgbClr val="00B050"/></a:solidFill>""" +
            """<a:custDash><a:ds d="800000" sp="300000"/><a:ds d="100000" sp="300000"/></a:custDash><a:round/><a:headEnd type="diamond"/><a:tailEnd type="arrow" w="sm" len="med"/></a:ln></p:spPr></p:cxnSp>""" +
            """</p:spTree></p:cSld></p:sld>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>"""
        val bytes = zip(mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels,
            "ppt/slides/slide1.xml" to slide,
        ))
        val line = PptxReader.read(bytes).slides[0].shapes.single()
        assertEquals(ShapeType.LINE, line.type)
        assertEquals("a custom long dash and dot, as the nearest preset", LineDash.LONG_DASH_DOT, line.dash)
        assertEquals("sizes left out are medium", LineEnd(ArrowHead.DIAMOND, 1, 1), line.beginArrow)
        assertEquals(LineEnd(ArrowHead.ARROW, 0, 1), line.endArrow)
    }

    @Test
    fun excelLinesKeepArrowsAndDashes() {
        val wb = Workbook.blank()
        val line = SlideShape(ShapeType.LINE, fill = null, border = 0xFF4472C4, borderWidthPt = 1.5f, dash = LineDash.DASH,
            beginArrow = LineEnd(ArrowHead.OVAL), endArrow = LineEnd(ArrowHead.TRIANGLE, 2, 1))
        wb.sheets[0].shapes.add(SheetShape(line, 2, 1, 0f, 0f, 120f, 0f, "Arrow 1"))
        val box = SlideShape(ShapeType.ROUNDED, fill = 0xFF70AD47, border = 0xFF375623, dash = LineDash.SYS_DASH)
        wb.sheets[0].shapes.add(SheetShape(box, 5, 1, 0f, 0f, 90f, 40f, "Box 2"))
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val drawing = String(OfficeXml.unzip(bytes)["xl/drawings/drawing1.xml"]!!, Charsets.UTF_8)
        assertTrue(drawing.contains("""<a:prstDash val="dash"/><a:headEnd type="oval" w="med" len="med"/><a:tailEnd type="triangle" w="lg" len="med"/></a:ln>"""))
        assertTrue("a closed shape has no ends", drawing.contains("""<a:prstDash val="sysDash"/></a:ln>"""))
        val back = XlsxReader.read(bytes).sheets[0].shapes
        wb.sheets[0].shapes.zip(back).forEach { (a, b) -> assertSameLine(a.shape, b.shape) }
    }

    @Test
    fun openDocumentKeepsArrowsDashesAndGradients() {
        val p = deck()
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        val files = OfficeXml.unzip(bytes)
        val styles = String(files["styles.xml"]!!, Charsets.UTF_8)
        val content = String(files["content.xml"]!!, Charsets.UTF_8)
        assertTrue("LibreOffice's names for DrawingML's heads", styles.contains("""<draw:marker draw:name="msArrowOvalEnd_20_3" draw:display-name="msArrowOvalEnd 3" svg:viewBox="0 0 200 500""""))
        assertTrue(styles.contains("""draw:name="msArrowStealthEnd_20_9""""))
        assertTrue(styles.contains("""<draw:stroke-dash draw:name="Dash_lgDashDot" draw:style="rect" draw:dots1="1" draw:dots1-length="800%" draw:dots2="1" draw:dots2-length="100%" draw:distance="300%"/>"""))
        assertTrue(styles.contains("""<draw:gradient draw:name="Gradient_1" draw:style="linear" draw:start-color="#FFC000" draw:end-color="#C00000" """))
        assertTrue(styles.contains("""draw:angle="45deg""""))
        assertTrue(content.contains("""draw:stroke="dash" draw:stroke-dash="Dash_lgDashDot""""))
        assertTrue("the flipped line starts at its right end, where its oval is",
            content.contains("""draw:marker-start="msArrowOvalEnd_20_3" draw:marker-start-width="4.50pt" draw:marker-start-center="true""""))
        assertTrue(content.contains("""draw:fill="gradient" draw:fill-gradient-name="Gradient_1""""))
        val back = OdpReader.read(bytes).slides[0].shapes
        assertEquals(3, back.size)
        p.slides[0].shapes.zip(back).forEach { (a, b) -> assertSameLine(a, b) }
        assertEquals(0xFFFFC000, back[2].fill); assertEquals(0xFFC00000, back[2].fillGradient)
        assertEquals(45f, back[2].gradientAngle, 0.01f)
        assertEquals("a level line stays level", 0f, back[0].hPct, 0.001f)
    }

    @Test
    fun spreadsheetShapesKeepTheirLinesInOpenDocument() {
        val wb = Workbook.blank()
        val line = SlideShape(ShapeType.LINE, fill = null, border = 0xFF000000, borderWidthPt = 3f, dash = LineDash.SYS_DASH_DOT,
            endArrow = LineEnd(ArrowHead.ARROW, 2, 2)).also { it.flipV = true }
        wb.sheets[0].shapes.add(SheetShape(line, 1, 1, 0f, 0f, 100f, 50f, "Line 1"))
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        val styles = String(OfficeXml.unzip(bytes)["styles.xml"]!!, Charsets.UTF_8)
        assertTrue("its names cannot meet the sheet's", styles.contains("""draw:name="shDash_sysDashDot""""))
        assertTrue("an open arrow's outline carries its line's width", styles.contains("""draw:name="msArrowOpenEnd_20_9_20_106""""))
        assertSameLine(line, OdsReader.read(bytes).sheets[0].shapes.single().shape)
    }

    @Test
    fun libreOfficesOwnMarkersAndDashesAreRead() {
        // LibreOffice's own markers go by kind, sized from how wide they are drawn and their box.
        val arrow = OdfLines.endOf("Arrow", OdfStyles.Marker("Arrow", 20f, 30f), 6f, 2f)
        assertEquals(LineEnd(ArrowHead.TRIANGLE, 1, 2), arrow)
        assertEquals(ArrowHead.STEALTH, OdfLines.endOf("Arrow_20_concave", null, null, 1f).head)
        assertEquals(ArrowHead.ARROW, OdfLines.endOf("Line_20_Arrow", null, null, 1f).head)
        assertEquals(ArrowHead.OVAL, OdfLines.endOf("Circle", null, null, 1f).head)
        assertEquals(ArrowHead.DIAMOND, OdfLines.endOf("Square_20_45", null, null, 1f).head)
        // Its names for DrawingML's heads say kind and sizes outright.
        assertEquals(LineEnd(ArrowHead.STEALTH, 2, 2), OdfLines.endOf("msArrowStealthEnd_20_9", null, 3f, 1f))
        assertEquals(LineEnd(ArrowHead.ARROW, 0, 0), OdfLines.endOf("x", OdfStyles.Marker("msArrowOpenEnd 1 35", 1f, 1f), null, 1f))
        // Dashes: percentages of the line's width, or lengths against it.
        assertEquals(LineDash.DASH_DOT, OdfLines.dashOf(OdfStyles.StrokeDash(1, "400%", 1, "100%", "300%"), 2f))
        assertEquals(LineDash.SYS_DOT, OdfLines.dashOf(OdfStyles.StrokeDash(2, null, 0, null, "100%"), 2f))
        assertEquals(LineDash.LONG_DASH, OdfLines.dashOf(OdfStyles.StrokeDash(1, "0.2cm", 0, null, "0.1cm"), 0.75f))
    }

    @Test
    fun editorsDrawLinesWithArrowsAndDashes() {
        val e = SlideEditor()
        e.addLine(end = LineEnd(ArrowHead.TRIANGLE))
        val line = e.selectedShape!!
        assertEquals(ShapeType.LINE, line.type)
        assertNull("a line has no fill", line.fill)
        assertEquals("inserted level", 0f, line.hPct)
        assertEquals(ArrowHead.TRIANGLE, line.endArrow.head)
        e.resizeSelectedShape(0.1f, 0f)
        assertEquals("resizing keeps it level", 0f, line.hPct)
        e.setShapeDash(LineDash.DASH)
        e.setShapeArrows(LineEnd(ArrowHead.OVAL), LineEnd.NONE)
        e.setShapeBorderWidth(0f)
        assertEquals(LineDash.DASH, line.dash); assertEquals(ArrowHead.OVAL, line.beginArrow.head); assertEquals(0.25f, line.borderWidthPt)
        e.undo()
        val restored = e.slide.shapes.single()
        assertEquals(1.5f, restored.borderWidthPt)
        e.undo()
        assertEquals(ArrowHead.TRIANGLE, e.slide.shapes.single().endArrow.head)
        // Insert → Line is a line too, not a filled box; a closed shape takes no arrows.
        e.addShape(ShapeType.LINE)
        assertNull(e.selectedShape!!.fill)
        e.addShape(ShapeType.RECT)
        e.setShapeArrows(LineEnd(ArrowHead.OVAL), LineEnd(ArrowHead.OVAL))
        assertTrue(e.selectedShape!!.beginArrow.isNone)

        val s = SheetEditor().apply { load(Workbook.blank()) }
        s.select(2, 2)
        assertTrue(s.insertShape(ShapeType.LINE, begin = LineEnd(ArrowHead.TRIANGLE), end = LineEnd(ArrowHead.TRIANGLE)))
        val sh = s.selectedShape!!
        assertEquals(ArrowHead.TRIANGLE, sh.shape.beginArrow.head)
        s.setShapeDash(sh, LineDash.SYS_DOT)
        s.setShapeOutlineWeight(sh, 4.5f)
        s.setShapeArrows(sh, LineEnd.NONE, LineEnd(ArrowHead.STEALTH))
        assertEquals(LineDash.SYS_DOT, sh.shape.dash); assertEquals(4.5f, sh.shape.borderWidthPt); assertEquals(ArrowHead.STEALTH, sh.shape.endArrow.head)
        s.undo(); s.undo(); s.undo()
        val back = s.sheet.shapes.single().shape
        assertEquals(LineDash.SOLID, back.dash); assertEquals(1f, back.borderWidthPt); assertEquals(ArrowHead.TRIANGLE, back.beginArrow.head)
        // A shape (not a line) keeps no arrows.
        s.insertShape(ShapeType.RECT, end = LineEnd(ArrowHead.TRIANGLE))
        assertTrue(s.selectedShape!!.shape.endArrow.isNone)
    }
}
