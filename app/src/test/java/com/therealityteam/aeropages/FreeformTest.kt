package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.SvgPath
import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.format.odf.OdfNode
import com.therealityteam.aeropages.format.odf.OdfGeometry
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.DrawingGeometry
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.Freeform
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SheetShape
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Freeform outlines: SVG path data, Impress's enhanced geometry, DrawingML's custom geometry — read, drawn and written. */
class FreeformTest {

    private val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0""""

    private fun odf(xml: String): OdfNode = OdfNode.parse(xml.replaceFirst(Regex("^<([\\w:-]+)"), "<$1 $ns").toByteArray())!!

    private fun near(expected: Float, actual: Float, what: String = "") = assertEquals(what, expected, actual, 0.002f)

    private fun end(op: Freeform.Op): Pair<Float, Float> = when (op) {
        is Freeform.Move -> op.x to op.y
        is Freeform.Line -> op.x to op.y
        is Freeform.Cubic -> op.x to op.y
        Freeform.Close -> error("a close has no point")
    }

    @Test
    fun svgPathData() {
        // Relative and absolute, H and V, implicit repeats, a close.
        assertEquals(listOf(Freeform.Move(10f, 10f), Freeform.Line(50f, 10f), Freeform.Line(50f, 30f), Freeform.Line(10f, 30f), Freeform.Close,
            Freeform.Move(60f, 0f), Freeform.Line(70f, 5f), Freeform.Line(80f, 10f)),
            SvgPath.parse("M10 10 H 50 V 30 h-40 z M60,0 l10 5 10 5"))
        // Numbers run together as SVG allows: "1-2" and ".5.5" are two each.
        assertEquals(listOf(Freeform.Move(1f, -2f), Freeform.Line(0.5f, 0.5f)), SvgPath.parse("M1-2L.5.5"))
        // LibreOffice's relative cubics (a Candy template's frame).
        val candy = SvgPath.parse("M0 0c0 814 0 1627 0 2441 243 0 487 0 730 0 0-571 0-1141 0-1712z")
        assertEquals(5, candy.size)
        assertEquals(Freeform.Cubic(0f, 814f, 0f, 1627f, 0f, 2441f), candy[1])
        assertEquals(Freeform.Cubic(243f, 2441f, 487f, 2441f, 730f, 2441f), candy[2])
        assertEquals(730f to 729f, end(candy[3]))
        // A half circle arc, clockwise from the left end over the top; flags written without spaces.
        val arc = SvgPath.parse("M0 0 A 10 10 0 0 1 20 0")
        assertEquals(3, arc.size)
        val (mx, my) = end(arc[1])
        assertEquals(10f, mx, 0.01f); assertEquals(-10f, my, 0.01f)
        assertEquals(20f to 0f, end(arc[2]).let { (x, y) -> Math.round(x).toFloat() to Math.round(y).toFloat() })
        assertEquals(end(SvgPath.parse("M0 0 a10 10 0 0110 10")[1]).let { (x, y) -> Math.round(x) to Math.round(y) }, 10 to 10)
        // Smooth curves mirror the last control point.
        val s = SvgPath.parse("M0 0 C 0 10 10 10 10 0 S 20 -10 20 0")
        assertEquals(Freeform.Cubic(10f, -10f, 20f, -10f, 20f, 0f), s[2])
        // Bad data ends the path where it goes wrong.
        assertEquals(2, SvgPath.parse("M0 0 L 5 5 L oops").size)
    }

    @Test
    fun impressPolygonsAndPaths() {
        // Focus's master: a polygon in its view box.
        val poly = OdfGeometry.drawing(odf("""<draw:polygon svg:width="13.5cm" svg:height="8cm" svg:x="0cm" svg:y="0cm" svg:viewBox="0 0 13500 8000" draw:points="0,0 0,500 10404,8000 13500,0"/>"""))!!
        val ops = poly.parts.single().ops
        assertEquals(Freeform.Move(0f, 0f), ops[0])
        near(0.0625f, (ops[1] as Freeform.Line).y)
        near(10404f / 13500f, (ops[2] as Freeform.Line).x); near(1f, (ops[2] as Freeform.Line).y)
        assertEquals(Freeform.Close, ops.last())
        assertTrue(poly.parts.single().filled)
        // A polyline and an open path are not filled, as Impress draws them.
        val line = OdfGeometry.drawing(odf("""<draw:polyline svg:viewBox="0 0 100 100" draw:points="0,0 100,50 0,100"/>"""))!!
        assertFalse(line.parts.single().filled)
        val open = OdfGeometry.drawing(odf("""<draw:path svg:viewBox="0 0 100 100" svg:d="M0 0 C 50 100 50 100 100 0"/>"""))!!
        assertFalse(open.parts.single().filled)
        near(0.5f, (open.parts.single().ops[1] as Freeform.Cubic).x1)
    }

    @Test
    fun impressEnhancedGeometry() {
        // Candy's pill: a round rectangle whose corners are half its height, in a box seven times as wide —
        // the stretch point keeps them round.
        val pill = odf("""<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:path-stretchpoint-x="10800" draw:path-stretchpoint-y="10800" draw:type="round-rectangle" draw:modifiers="10800" draw:enhanced-path="M ?f7 0 X 0 ?f8 L 0 ?f9 Y ?f7 21600 L ?f10 21600 X 21600 ?f9 L 21600 ?f8 Y ?f10 0 Z N">""" +
            """<draw:equation draw:name="f0" draw:formula="45"/><draw:equation draw:name="f1" draw:formula="${'$'}0 *sin(?f0 *(pi/180))"/>""" +
            """<draw:equation draw:name="f7" draw:formula="left+${'$'}0 "/><draw:equation draw:name="f8" draw:formula="top+${'$'}0 "/>""" +
            """<draw:equation draw:name="f9" draw:formula="bottom-${'$'}0 "/><draw:equation draw:name="f10" draw:formula="right-${'$'}0 "/></draw:enhanced-geometry>""")
        val f = OdfGeometry.freeform(pill, 7 * 28.3465f, 28.3465f)!!
        val ops = f.parts.single().ops
        near(1f / 14f, (ops[0] as Freeform.Move).x, "the straight top starts half the height in"); near(0f, (ops[0] as Freeform.Move).y)
        assertEquals(0f to 0.5f, end(ops[1]).let { (x, y) -> Math.round(x * 1000) / 1000f to Math.round(y * 1000) / 1000f })
        val b = f.bounds()!!
        near(0f, b[0]); near(1f, b[2], "21600 across reaches the stretched right edge"); near(0f, b[1]); near(1f, b[3])
        near(13f / 14f, end(ops[4]).first, "the bottom runs to half the height from the right")

        // A left arrow with its own modifiers.
        val arrow = odf("""<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="left-arrow" draw:modifiers="5400 5400" draw:enhanced-path="M 21600 ?f0 L ?f1 ?f0 ?f1 0 0 10800 ?f1 21600 ?f1 ?f2 21600 ?f2 Z N">""" +
            """<draw:equation draw:name="f0" draw:formula="${'$'}1 "/><draw:equation draw:name="f1" draw:formula="${'$'}0 "/><draw:equation draw:name="f2" draw:formula="21600-${'$'}1 "/></draw:enhanced-geometry>""")
        val a = OdfGeometry.freeform(arrow, 100f, 50f)!!.parts.single().ops
        assertEquals(Freeform.Move(1f, 0.25f), a[0]); assertEquals(Freeform.Line(0f, 0.5f), a[3]); assertEquals(8, a.size)

        // An ellipse by angle (U), and a clockwise arc (V) — a three-quarter pie.
        val oval = OdfGeometry.freeform(odf("""<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="ellipse" draw:enhanced-path="U 10800 10800 10800 10800 0 360 Z N"/>"""), 50f, 50f)!!
        val o = oval.parts.single().ops
        assertEquals(Freeform.Move(1f, 0.5f), o[0]); assertEquals(6, o.size)
        near(0f, end(o[1]).second, "anticlockwise: from 3 o'clock up to 12")
        val pie = OdfGeometry.freeform(odf("""<draw:enhanced-geometry draw:type="mso-spt100" draw:enhanced-path="V 0 0 21600 21600 21600 10800 10800 0 L 10800 10800 Z N"/>"""), 50f, 50f)!!
        val p = pie.parts.single().ops
        assertEquals(Freeform.Move(1f, 0.5f), p[0])
        assertEquals(3, p.count { it is Freeform.Cubic })
        near(1f, end(p[1]).second, "clockwise: from 3 o'clock down to 6")
        near(0.5f, end(p[3]).first); near(0f, end(p[3]).second)
        assertEquals(Freeform.Line(0.5f, 0.5f), p[4])
        // No fill (F), no outline (S) and parts (N).
        val two = OdfGeometry.freeform(odf("""<draw:enhanced-geometry svg:viewBox="0 0 10 10" draw:enhanced-path="M 0 0 L 10 10 F N M 0 10 L 10 0 S N"/>"""), 10f, 10f)!!
        assertEquals(listOf(false to true, true to false), two.parts.map { it.filled to it.stroked })
    }

    @Test
    fun drawingMlCustomGeometry() {
        // PowerPoint's freeform: a triangle in its path's own space, guides unused by the points.
        val tri = XmlPull.parse(("""<a:custGeom xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:avLst/><a:gdLst><a:gd name="connsiteX0" fmla="*/ 0 w 2650"/></a:gdLst><a:ahLst/><a:cxnLst/><a:rect l="l" t="t" r="r" b="b"/>""" +
            """<a:pathLst><a:path w="2650" h="1531"><a:moveTo><a:pt x="0" y="1531"/></a:moveTo><a:lnTo><a:pt x="1325" y="0"/></a:lnTo><a:lnTo><a:pt x="2650" y="1531"/></a:lnTo><a:close/></a:path></a:pathLst></a:custGeom>""").toByteArray())!!
        val t = DrawingGeometry.read(tri, 914400.0, 457200.0)!!.parts.single().ops
        assertEquals(listOf(Freeform.Move(0f, 1f), Freeform.Line(0.5f, 0f), Freeform.Line(1f, 1f), Freeform.Close), t)
        // An arc: from the left end of an ellipse, half a turn clockwise over its top; guides in the points.
        val arc = XmlPull.parse(("""<a:custGeom xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:gdLst><a:gd name="half" fmla="*/ h 1 2"/></a:gdLst>""" +
            """<a:pathLst><a:path fill="none"><a:moveTo><a:pt x="l" y="half"/></a:moveTo><a:arcTo wR="wd2" hR="hd2" stAng="cd2" swAng="cd2"/></a:path></a:pathLst></a:custGeom>""").toByteArray())!!
        val a = DrawingGeometry.read(arc, 200.0, 100.0)!!
        assertFalse(a.parts.single().filled)
        val ops = a.parts.single().ops
        assertEquals(Freeform.Move(0f, 0.5f), ops[0])
        near(0.5f, end(ops[1]).first); near(0f, end(ops[1]).second, "over the top")
        near(1f, end(ops[2]).first); near(0.5f, end(ops[2]).second)
    }

    private val star = Freeform(listOf(
        Freeform.Part(listOf(Freeform.Move(0.5f, 0f), Freeform.Line(0.62f, 0.38f), Freeform.Line(1f, 0.38f), Freeform.Cubic(0.8f, 0.6f, 0.8f, 0.7f, 0.82f, 1f),
            Freeform.Line(0.5f, 0.76f), Freeform.Line(0.18f, 1f), Freeform.Line(0.3f, 0.62f), Freeform.Line(0f, 0.38f), Freeform.Line(0.38f, 0.38f), Freeform.Close)),
        Freeform.Part(listOf(Freeform.Move(0f, 0f), Freeform.Line(1f, 1f)), filled = false),
    ))

    private fun assertSameOutline(expected: Freeform, actual: Freeform?) {
        assertNotNull(actual)
        assertEquals(expected.parts.size, actual!!.parts.size)
        expected.parts.zip(actual.parts).forEach { (e, a) ->
            assertEquals(e.filled, a.filled); assertEquals(e.stroked, a.stroked)
            assertEquals(e.ops.map { it::class }, a.ops.map { it::class })
            e.ops.zip(a.ops).filter { it.first != Freeform.Close }.forEach { (x, y) ->
                val (ex, ey) = end(x); val (ax, ay) = end(y)
                assertEquals(ex, ax, 0.0005f); assertEquals(ey, ay, 0.0005f)
            }
        }
    }

    @Test
    fun freeformsComeBackFromEveryFormat() {
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("Drawn")).also {
            it.shapes.add(SlideShape(ShapeType.FREEFORM, SlideBox("Star"), 0.2f, 0.2f, 0.3f, 0.4f, fill = 0xFFFFC000, border = 0xFF7F6000, path = star, flipH = true))
        }))
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()
        val fromPptx = PptxReader.read(pptx).slides[0].shapes.single()
        assertEquals(ShapeType.FREEFORM, fromPptx.type); assertEquals("Star", fromPptx.box.text); assertTrue(fromPptx.flipH)
        assertEquals(0xFFFFC000, fromPptx.fill)
        assertSameOutline(star, fromPptx.path)

        val odp = ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()
        val fromOdp = OdpReader.read(odp).slides[0].shapes.single()
        assertEquals(ShapeType.FREEFORM, fromOdp.type); assertEquals("Star", fromOdp.box.text); assertTrue(fromOdp.flipH)
        assertEquals(0.3f, fromOdp.wPct, 0.001f)
        assertSameOutline(star, fromOdp.path)

        val wb = Workbook.blank()
        wb.sheets[0].shapes.add(SheetShape(SlideShape(ShapeType.FREEFORM, SlideBox(""), fill = 0xFF4472C4, border = null, path = star), 2, 1, 0f, 0f, 120f, 90f, "Freeform 1"))
        val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val fromXlsx = XlsxReader.read(xlsx).sheets[0].shapes.single().shape
        assertEquals(ShapeType.FREEFORM, fromXlsx.type)
        assertSameOutline(star, fromXlsx.path)
        // A copy shares the outline; a shape of another type has none.
        assertTrue(fromOdp.copyOf().path === fromOdp.path)
        assertNull(SlideShape(ShapeType.RECT).path)
    }

    @Test
    fun openFreeformsHaveEndsForArrowheads() {
        val wave = Freeform(listOf(Freeform.Part(listOf(Freeform.Move(0f, 0.5f), Freeform.Cubic(0f, 0.5f, 0.3f, 0f, 0.5f, 0.5f), Freeform.Line(1f, 0.5f)), filled = false)))
        // From the start toward the first control point that is not the start; into the end from the last point.
        assertEquals(listOf(0f, 0.5f, 0.3f, 0f, 1f, 0.5f, 0.5f, 0.5f), wave.openEnds()!!.toList())
        assertNull("a closed outline has no ends", star.openEnds())
        val arrowed = SlideShape(ShapeType.FREEFORM, SlideBox(""), 0.1f, 0.1f, 0.5f, 0.2f, fill = null, border = 0xFF7F59AE, path = wave,
            endArrow = com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE))
        assertTrue(arrowed.hasEnds); assertFalse(SlideShape(ShapeType.FREEFORM, path = star).hasEnds)
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("Arrows")).also { it.shapes.add(arrowed) }))
        val fromPptx = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()).slides[0].shapes.single()
        assertEquals(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE, fromPptx.endArrow.head)
        val fromOdp = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()).slides[0].shapes.single()
        assertEquals(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE, fromOdp.endArrow.head)
        assertTrue(fromOdp.beginArrow.isNone)
    }

    @Test
    fun smallShapesKeepTheirSize() {
        // An icon's dots, a tenth of an inch across, stay dots (they were read as a fiftieth of the slide).
        val content = """<office:document-content $ns><office:body><office:presentation><draw:page draw:name="p1">""" +
            """<draw:ellipse svg:x="1cm" svg:y="1cm" svg:width="0.126cm" svg:height="0.126cm"/>""" +
            """<draw:path svg:x="2cm" svg:y="1cm" svg:width="0.5cm" svg:height="0.08cm" svg:viewBox="0 0 512 82" svg:d="M0 41 L 512 41"/>""" +
            """</draw:page></office:presentation></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z -> z.putNextEntry(java.util.zip.ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry() }
        val (dot, bar) = OdpReader.read(bos.toByteArray()).slides[0].shapes
        assertEquals(0.126f / 2.54f * 72f / 720f, dot.wPct, 0.0005f)
        assertEquals(0.08f / 2.54f * 72f / 405f, bar.hPct, 0.0005f)
    }

    @Test
    fun customShapesImpressDrawsItsOwnWay() {
        // A pill and a left arrow on a page: freeforms; a plain round rectangle stays the app's own.
        val content = """<office:document-content $ns xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"><office:body><office:presentation><draw:page draw:name="p1">""" +
            """<draw:custom-shape svg:x="1cm" svg:y="12cm" svg:width="7cm" svg:height="1cm"><draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:path-stretchpoint-x="10800" draw:type="round-rectangle" draw:modifiers="10800" draw:enhanced-path="M ?f7 0 X 0 ?f8 L 0 ?f9 Y ?f7 21600 L ?f10 21600 X 21600 ?f9 L 21600 ?f8 Y ?f10 0 Z N">""" +
            """<draw:equation draw:name="f7" draw:formula="left+${'$'}0 "/><draw:equation draw:name="f8" draw:formula="top+${'$'}0 "/><draw:equation draw:name="f9" draw:formula="bottom-${'$'}0 "/><draw:equation draw:name="f10" draw:formula="right-${'$'}0 "/></draw:enhanced-geometry></draw:custom-shape>""" +
            """<draw:custom-shape svg:x="1cm" svg:y="1cm" svg:width="4cm" svg:height="2cm"><draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="round-rectangle" draw:enhanced-path="M 3600 0 L 18000 0 Z N"/></draw:custom-shape>""" +
            """<draw:g><draw:polygon svg:x="0cm" svg:y="0cm" svg:width="2cm" svg:height="2cm" svg:viewBox="0 0 100 100" draw:points="0,0 100,0 50,100"/>""" +
            """<draw:path svg:x="3cm" svg:y="0cm" svg:width="2cm" svg:height="2cm" svg:viewBox="0 0 100 100" svg:d="M0 0 L 100 100"/></draw:g>""" +
            """</draw:page></office:presentation></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z -> z.putNextEntry(java.util.zip.ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry() }
        val shapes = OdpReader.read(bos.toByteArray()).slides[0].shapes
        assertEquals(listOf(ShapeType.FREEFORM, ShapeType.ROUNDED, ShapeType.FREEFORM, ShapeType.FREEFORM), shapes.map { it.type })
        assertEquals("a group's members move together", shapes[2].groupId, shapes[3].groupId)
        assertNotNull(shapes[2].groupId); assertNull(shapes[0].groupId)
        assertFalse("an open path", shapes[3].path!!.parts.single().filled)
    }

    @Test
    fun libreOfficesFocusTemplate() {
        val file = File("/Applications/LibreOffice.app/Contents/Resources/template/common/presnt/Focus.otp")
        assumeTrue("LibreOffice's templates are on this machine", file.exists())
        val p = OdpReader.read(file.readBytes())
        // Its masters are drawn with polygons alone: now they are designs, every slide on one.
        assertTrue(p.designs.isNotEmpty())
        assertTrue(p.designs.all { d -> d.items.any { it is DesignShape && it.shape.type == ShapeType.FREEFORM } })
        assertTrue(p.slides.all { p.designOf(it) != null })
    }

    @Test
    fun libreOfficesOtherTemplatesDrawTheirShapes() {
        val dir = File("/Applications/LibreOffice.app/Contents/Resources/template/common/presnt")
        assumeTrue("LibreOffice's templates are on this machine", dir.isDirectory)
        // Every template reads; its custom shapes of types the app has no preset for are drawn from their paths.
        var freeforms = 0
        dir.listFiles { f -> f.name.endsWith(".otp") }!!.sortedBy { it.name }.forEach { f ->
            val p = OdpReader.read(f.readBytes())
            val shapes = p.slides.flatMap { it.shapes } + p.designs.flatMap { d -> d.items.filterIsInstance<DesignShape>().map { it.shape } }
            freeforms += shapes.count { it.type == ShapeType.FREEFORM }
            shapes.filter { it.type == ShapeType.FREEFORM }.forEach { s ->
                val b = s.path!!.bounds()!!
                // (A callout's pointer reaches out of its box; nothing lands in another unit's range.)
                assertTrue("${f.name}: an outline about its box", b[0] > -3f && b[1] > -3f && b[2] < 4f && b[3] < 4f)
            }
        }
        assertTrue("hundreds of them", freeforms > 200)
    }
}
