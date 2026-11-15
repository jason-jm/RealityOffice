package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetShape
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shapes, text boxes and lines over a sheet: Excel's drawings read and written, ODS, the editor, and the shape styles pptx shares. */
class SheetShapesTest {

    private fun zip(files: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val xdrNs = """xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main""""

    @Test
    fun excelsDrawnShapesAreRead() {
        // As Excel writes them: a shape coloured by its style (accent 1, white text), a text box with its
        // own fill and rule, a line running up (flipV), and a group of two shapes scaled through its transform.
        val drawing = """<xdr:wsDr $xdrNs>""" +
            """<xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>""" +
            """<xdr:to><xdr:col>3</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>5</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>""" +
            """<xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="2" name="Rounded Rectangle 1"/><xdr:cNvSpPr/></xdr:nvSpPr>""" +
            """<xdr:spPr><a:xfrm><a:off x="609600" y="190500"/><a:ext cx="1219200" cy="762000"/></a:xfrm><a:prstGeom prst="roundRect"><a:avLst/></a:prstGeom></xdr:spPr>""" +
            """<xdr:style><a:lnRef idx="2"><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:lnRef><a:fillRef idx="1"><a:schemeClr val="accent1"/></a:fillRef>""" +
            """<a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></xdr:style>""" +
            """<xdr:txBody><a:bodyPr rtlCol="0" anchor="ctr"/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:r><a:rPr lang="en-US" sz="1100"/><a:t>Target</a:t></a:r></a:p></xdr:txBody></xdr:sp><xdr:clientData/></xdr:twoCellAnchor>""" +
            """<xdr:oneCellAnchor><xdr:from><xdr:col>4</xdr:col><xdr:colOff>63500</xdr:colOff><xdr:row>2</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx="1828800" cy="457200"/>""" +
            """<xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="3" name="TextBox 2"/><xdr:cNvSpPr txBox="1"/></xdr:nvSpPr>""" +
            """<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1828800" cy="457200"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>""" +
            """<a:solidFill><a:schemeClr val="lt1"/></a:solidFill><a:ln w="9525"><a:solidFill><a:srgbClr val="808080"/></a:solidFill></a:ln></xdr:spPr>""" +
            """<xdr:txBody><a:bodyPr wrap="square"/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="1100" b="1"/><a:t>Note</a:t></a:r><a:br><a:rPr sz="1100"/></a:br><a:r><a:rPr sz="1100"/><a:t>second line</a:t></a:r></a:p></xdr:txBody></xdr:sp><xdr:clientData/></xdr:oneCellAnchor>""" +
            """<xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>8</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>""" +
            """<xdr:to><xdr:col>3</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>10</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>""" +
            """<xdr:cxnSp macro=""><xdr:nvCxnSpPr><xdr:cNvPr id="4" name="Straight Connector 3"/><xdr:cNvCxnSpPr/></xdr:nvCxnSpPr>""" +
            """<xdr:spPr><a:xfrm flipV="1"><a:off x="0" y="0"/><a:ext cx="1219200" cy="381000"/></a:xfrm><a:prstGeom prst="line"><a:avLst/></a:prstGeom><a:ln w="25400"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></a:ln></xdr:spPr>""" +
            """</xdr:cxnSp><xdr:clientData/></xdr:twoCellAnchor>""" +
            """<xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>12</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx="1219200" cy="609600"/>""" +
            """<xdr:grpSp><xdr:nvGrpSpPr><xdr:cNvPr id="5" name="Group 4"/><xdr:cNvGrpSpPr/></xdr:nvGrpSpPr>""" +
            """<xdr:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1219200" cy="609600"/><a:chOff x="1000000" y="1000000"/><a:chExt cx="2438400" cy="1219200"/></a:xfrm></xdr:grpSpPr>""" +
            """<xdr:sp><xdr:nvSpPr><xdr:cNvPr id="6" name="Oval 5"/><xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr><a:xfrm><a:off x="1000000" y="1000000"/><a:ext cx="1219200" cy="1219200"/></a:xfrm>""" +
            """<a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="70AD47"/></a:solidFill></xdr:spPr></xdr:sp>""" +
            """<xdr:sp><xdr:nvSpPr><xdr:cNvPr id="7" name="Star 6"/><xdr:cNvSpPr/></xdr:nvSpPr><xdr:spPr><a:xfrm><a:off x="2219200" y="1000000"/><a:ext cx="1219200" cy="1219200"/></a:xfrm>""" +
            """<a:prstGeom prst="star5"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFC000"/></a:solidFill></xdr:spPr></xdr:sp>""" +
            """</xdr:grpSp><xdr:clientData/></xdr:oneCellAnchor>""" +
            """</xdr:wsDr>"""
        val ns = """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
        val files = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook $ns><sheets><sheet name="Plan" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet $ns><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Plan</t></is></c></row></sheetData><drawing r:id="rId1"/></worksheet>""",
            "xl/worksheets/_rels/sheet1.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/></Relationships>""",
            "xl/drawings/drawing1.xml" to drawing,
        )
        val s = XlsxReader.read(zip(files)).sheets[0]
        assertEquals(5, s.shapes.size)
        val target = s.shapes[0]
        assertEquals(ShapeType.ROUNDED, target.shape.type)
        assertEquals(1, target.row); assertEquals(1, target.col)
        assertEquals(96f, target.widthPt, 0.01f); assertEquals(60f, target.heightPt, 0.01f)
        assertEquals("the style's fillRef: accent 1", 0xFF4472C4, target.shape.fill)
        assertEquals("its lnRef: the theme's second line width", 1f, target.shape.borderWidthPt, 0.01f)
        assertTrue(target.shape.border != null && target.shape.border != 0xFF4472C4)
        assertEquals("its fontRef: white text", 0xFFFFFFFF, target.shape.box.paragraph.runs.first { it.text == "Target" }.format.color)
        assertEquals(TextAlign.CENTER, target.shape.box.align)
        val note = s.shapes[1]
        assertEquals(ShapeType.TEXTBOX, note.shape.type)
        assertEquals(ShapeAnchor.TOP, note.shape.anchor)
        assertEquals(5f, note.dxPt, 0.01f)
        assertEquals(0xFFFFFFFF, note.shape.fill)
        assertEquals(0xFF808080, note.shape.border)
        assertEquals("Note second line", note.shape.box.text)
        val line = s.shapes[2]
        assertEquals(ShapeType.LINE, line.shape.type)
        assertTrue(line.shape.flipV)
        assertEquals(0xFFFF0000, line.shape.border)
        assertEquals(2f, line.shape.borderWidthPt, 0.01f)
        assertEquals(30f, line.heightPt, 0.01f)
        // The group's two members, halved by its transform, side by side.
        val (oval, star) = s.shapes[3] to s.shapes[4]
        assertEquals(ShapeType.ELLIPSE, oval.shape.type); assertEquals(ShapeType.STAR, star.shape.type)
        assertEquals(12, oval.row)
        assertEquals(48f, oval.widthPt, 0.01f)
        assertEquals("the second one a shape's width along", 48f, star.dxPt + (star.col - oval.col) * 48f - oval.dxPt, 0.5f)
    }

    private fun sampleSheet(): Workbook {
        val wb = Workbook.blank()
        val sheet = wb.sheets[0]
        val arrow = SlideShape(ShapeType.ARROW, SlideBox("Next", CharacterFormat(fontSizePt = 12f, bold = true, color = 0xFFFFFFFF), TextAlign.CENTER),
            fill = 0xFF70AD47, border = null, rotationDeg = 15f).also { it.fillGradient = 0xFF375623; it.gradientAngle = 90f }
        sheet.shapes.add(SheetShape(arrow, 2, 1, 4f, 3f, 120f, 50f, "Arrow 1"))
        val text = SlideBox(Paragraph(mutableListOf(TextRun("Points\n\tsub point", CharacterFormat(fontSizePt = 11f)))), SlideListStyle.BULLET)
        sheet.shapes.add(SheetShape(SlideShape(ShapeType.TEXTBOX, text, fill = null, border = 0xFF808080, borderWidthPt = 0.75f, anchor = ShapeAnchor.TOP), 6, 3, 0f, 0f, 150f, 60f, "TextBox 2"))
        sheet.shapes.add(SheetShape(SlideShape(ShapeType.LINE, fill = null, border = 0xFF4472C4, borderWidthPt = 2f).also { it.flipV = true }, 10, 0, 0f, 0f, 90f, 30f, "Line 3"))
        return wb
    }

    private fun assertSameShapes(a: Sheet, b: Sheet) {
        assertEquals(a.shapes.size, b.shapes.size)
        a.shapes.zip(b.shapes).forEach { (x, y) ->
            assertEquals(x.name, y.name); assertEquals(x.shape.type, y.shape.type)
            assertEquals(x.row, y.row); assertEquals(x.col, y.col)
            assertEquals(x.dxPt, y.dxPt, 0.05f); assertEquals(x.dyPt, y.dyPt, 0.05f)
            assertEquals(x.widthPt, y.widthPt, 0.05f); assertEquals(x.heightPt, y.heightPt, 0.05f)
            assertEquals(x.shape.fill, y.shape.fill); assertEquals(x.shape.border, y.shape.border)
            assertEquals(x.shape.borderWidthPt, y.shape.borderWidthPt, 0.05f)
            assertEquals(x.shape.flipV, y.shape.flipV); assertEquals(x.shape.anchor, y.shape.anchor)
            assertEquals(x.shape.box.text, y.shape.box.text)
        }
    }

    @Test
    fun shapesGoToExcelAndBack() {
        val wb = sampleSheet()
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val drawing = String(parts["xl/drawings/drawing1.xml"]!!, Charsets.UTF_8)
        assertTrue(drawing.contains("""<a:xfrm rot="900000"><a:off x="0" y="0"/><a:ext cx="1524000" cy="635000"/></a:xfrm><a:prstGeom prst="rightArrow">"""))
        assertTrue(drawing.contains("""<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:srgbClr val="70AD47"/></a:gs>"""))
        assertTrue(drawing.contains("""<xdr:cNvPr id="2" name="TextBox 2"/><xdr:cNvSpPr txBox="1"/>"""))
        assertTrue(drawing.contains("""<a:buChar char="–"/>"""))
        assertTrue(drawing.contains("""<xdr:cxnSp macro=""><xdr:nvCxnSpPr><xdr:cNvPr id="3" name="Line 3"/>"""))
        assertTrue(drawing.contains("""<a:xfrm flipV="1">"""))
        assertTrue(String(parts["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8).contains("""<drawing r:id="rIdDrw"/>"""))
        val back = XlsxReader.read(bytes).sheets[0]
        assertSameShapes(wb.sheets[0], back)
        assertEquals(15f, back.shapes[0].shape.rotationDeg, 0.001f)
        assertEquals(0xFF375623, back.shapes[0].shape.fillGradient)
        assertEquals(SlideListStyle.BULLET, back.shapes[1].shape.box.listStyle)
    }

    @Test
    fun shapesGoToOpenDocumentAndBack() {
        val wb = sampleSheet()
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue("in its anchor cell", Regex("""<table:table-cell[^>]*><draw:custom-shape[^>]*draw:name="Arrow 1"""").containsMatchIn(content))
        assertTrue(content.contains("""<draw:enhanced-geometry draw:type="right-arrow"/>"""))
        assertTrue("a line from its lower corner up", content.contains("""svg:x1="0.00pt" svg:y1="30.00pt" svg:x2="90.00pt" svg:y2="0.00pt""""))
        assertTrue("its styles cannot meet the sheet's", content.contains("""style:name="shgr1""""))
        val back = OdsReader.read(bytes).sheets[0]
        assertSameShapes(wb.sheets[0], back)
    }

    @Test
    fun insertingAndEditingShapes() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        e.select(3, 2)
        assertTrue(e.insertShape(ShapeType.ROUNDED, "Hello"))
        val sh = e.selectedShape!!
        assertEquals(3, sh.row); assertEquals(2, sh.col)
        assertEquals(SheetEditor.ACCENT, sh.shape.fill)
        assertEquals(0xFFFFFFFF, sh.shape.box.paragraph.runs.first().format.color)
        e.setShapeText(sh, "Goal")
        assertEquals("Goal", sh.shape.box.text)
        e.setShapeFill(sh, 0xFFFFC000)
        e.setShapeOutline(sh, null)
        assertEquals(0xFFFFC000, sh.shape.fill); assertNull(sh.shape.border)
        val (x, y) = e.floatingOrigin(sh)
        e.setShapeFrame(sh, x + 10f, y + 5f, 200f, 30f)
        assertEquals(200f, sh.widthPt)
        e.nudgeSelectedShape(1f, 0f)
        e.duplicateSelectedShape()
        assertEquals(2, e.sheet.shapes.size)
        assertTrue(e.selectedShape !== sh)
        e.deleteSelectedShape()
        assertEquals(1, e.sheet.shapes.size)
        // A row inserted above moves it down with its cell.
        e.select(0, 0)
        e.insertRowAt(0)
        assertEquals(4, e.sheet.shapes.single().row)
        repeat(9) { e.undo() }
        assertTrue("undone back to no shape", e.sheet.shapes.isEmpty())
        // A text box: white, ruled, its text at the top; a line: no fill.
        e.select(0, 0)
        e.insertShape(ShapeType.TEXTBOX)
        assertEquals(ShapeAnchor.TOP, e.selectedShape!!.shape.anchor)
        e.insertShape(ShapeType.LINE)
        assertNull(e.selectedShape!!.shape.fill)
        assertEquals(0f, e.selectedShape!!.heightPt)
    }

    @Test
    fun powerPointsShapeStylesAndLinesAreRead() {
        val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
        val slide = """<p:sld $ns><p:cSld><p:spTree>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="4" name="Rectangle 3"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="1000000" y="1000000"/><a:ext cx="2000000" cy="1000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""" +
            """<p:style><a:lnRef idx="2"><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:lnRef><a:fillRef idx="1"><a:schemeClr val="accent2"/></a:fillRef><a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></p:style>""" +
            """<p:txBody><a:bodyPr/><a:p><a:r><a:t>Box</a:t></a:r></a:p></p:txBody></p:sp>""" +
            """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="5" name="Straight Connector 4"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr><a:xfrm><a:off x="1000000" y="3000000"/><a:ext cx="3000000" cy="0"/></a:xfrm><a:prstGeom prst="line"><a:avLst/></a:prstGeom><a:ln w="38100"><a:solidFill><a:srgbClr val="C00000"/></a:solidFill></a:ln></p:spPr></p:cxnSp>""" +
            """</p:spTree></p:cSld></p:sld>"""
        val rels = { target: String, type: String -> """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type" Target="$target"/></Relationships>""" }
        val bytes = zip(mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels("slides/slide1.xml", "slide"),
            "ppt/slides/slide1.xml" to slide,
        ))
        val shapes = PptxReader.read(bytes).slides[0].shapes
        val box = shapes.first { it.type == ShapeType.RECT }
        assertEquals("filled by its style, as PowerPoint's own shapes are", 0xFFED7D31, box.fill)
        assertEquals(0xFFFFFFFF, box.box.paragraph.runs.first().format.color)
        val line = shapes.single { it.type == ShapeType.LINE }
        assertEquals("connectors are no longer dropped", 0xFFC00000, line.border)
        assertEquals(3f, line.borderWidthPt, 0.01f)
    }

    @Test
    fun odpLinesAndMirrorsKeepTheirWay() {
        val p = Presentation.blank()
        p.slides[0].shapes.add(SlideShape(ShapeType.LINE, fill = null, border = 0xFF000000, xPct = 0.1f, yPct = 0.2f, wPct = 0.3f, hPct = 0.2f).also { it.flipV = true })
        p.slides[0].shapes.add(SlideShape(ShapeType.TRIANGLE, xPct = 0.5f, yPct = 0.2f, wPct = 0.2f, hPct = 0.2f).also { it.flipH = true })
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        val back = OdpReader.read(bytes).slides[0].shapes
        val line = back.single { it.type == ShapeType.LINE }
        assertTrue(line.flipV); assertFalse(line.flipH)
        assertEquals(0.3f, line.wPct, 0.01f)
        assertTrue(back.single { it.type == ShapeType.TRIANGLE }.flipH)
    }
}
