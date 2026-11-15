package com.therealityteam.aeropages

import com.therealityteam.aeropages.TestCfb.le16
import com.therealityteam.aeropages.TestCfb.le32
import com.therealityteam.aeropages.format.ppt.MsoGeometry
import com.therealityteam.aeropages.model.Freeform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Office's own shape geometry ([MS-ODRAW] 2.3.6): vertices, guides and segments as an outline in the shape's box. */
class MsoGeometryTest {

    /** An IMsoArray of [elements], each [size] bytes (0xFFF0: 4-byte points). */
    private fun array(size: Int, elements: List<ByteArray>) = le16(elements.size) + le16(elements.size) + le16(size) + elements.fold(ByteArray(0)) { a, e -> a + e }
    private fun points(vararg xy: Int) = array(8, xy.toList().chunked(2).map { (x, y) -> le32(x) + le32(y) })
    private fun points16(vararg xy: Int) = array(0xFFF0, xy.toList().chunked(2).map { (x, y) -> le16(x) + le16(y) })
    private fun segments(vararg s: Int) = array(2, s.map { le16(it) })
    private fun guides(vararg g: IntArray) = array(8, g.map { (f, a, b, c) -> le16(f) + le16(a) + le16(b) + le16(c) })

    private fun draw(props: Map<Int, Int> = emptyMap(), complex: Map<Int, ByteArray>, w: Double = 914400.0, h: Double = 914400.0) =
        MsoGeometry.freeform(props, complex, w, h)

    private fun moves(f: Freeform) = f.parts.flatMap { it.ops }

    @Test fun aClosedPolygonInItsGeometrySpace() {
        // A diamond in a 1000-unit space: move, three lines, close, end.
        val f = draw(mapOf(0x0142 to 1000, 0x0143 to 1000), mapOf(
            MsoGeometry.VERTICES to points(500, 0, 1000, 500, 500, 1000, 0, 500),
            MsoGeometry.SEGMENTS to segments(0x4000, 0x0003, 0x6001, 0x8000),
        ))!!
        val ops = moves(f)
        assertEquals(Freeform.Move(0.5f, 0f), ops[0])
        assertEquals(Freeform.Line(1f, 0.5f), ops[1])
        assertEquals(Freeform.Line(0f, 0.5f), ops[3])
        assertEquals(Freeform.Close, ops[4])
        assertTrue(f.parts.single().filled)
    }

    @Test fun shortPointsCurvesAndTheDefaultSpace() {
        // 16-bit points in the default 21600 space; one cubic curve.
        val f = draw(complex = mapOf(
            MsoGeometry.VERTICES to points16(0, 21600, 0, 0, 21600, 0, 21600, 21600),
            MsoGeometry.SEGMENTS to segments(0x4000, 0x2001, 0x8000),
        ))!!
        val c = moves(f)[1] as Freeform.Cubic
        assertEquals(0f, c.x1, 1e-4f); assertEquals(0f, c.y1, 1e-4f)
        assertEquals(1f, c.x, 1e-4f); assertEquals(1f, c.y, 1e-4f)
    }

    @Test fun noFillAndNoLineMarkTheirPath() {
        val f = draw(mapOf(0x0142 to 10, 0x0143 to 10), mapOf(
            MsoGeometry.VERTICES to points(0, 0, 10, 0, 10, 10, 0, 10, 0, 0, 10, 10),
            // A filled triangle with no line, then an open line with no fill.
            MsoGeometry.SEGMENTS to segments(0x4000, 0x0002, 0x6001, 0xAB00, 0x8000, 0x4000, 0x0002, 0xAA00, 0x8000),
        ))!!
        assertEquals(2, f.parts.size)
        assertTrue(f.parts[0].filled); assertFalse(f.parts[0].stroked)
        assertFalse(f.parts[1].filled); assertTrue(f.parts[1].stroked)
    }

    @Test fun anArcAndAQuadraticCurveComeInAsCubics() {
        // ArcTo: the box (0,0)-(20,20), from the ray to (20,10) to the ray to (10,0) — a quarter, anticlockwise.
        val f = draw(mapOf(0x0142 to 20, 0x0143 to 20), mapOf(
            MsoGeometry.VERTICES to points(0, 0, 20, 20, 20, 10, 10, 0, 20, 0, 20, 20),
            MsoGeometry.SEGMENTS to segments(0xA304, 0xA902, 0x8000),
        ))!!
        val ops = moves(f)
        assertEquals(Freeform.Move(1f, 0.5f), ops[0])
        val arcEnd = ops.filterIsInstance<Freeform.Cubic>().first { kotlin.math.abs(it.x - 0.5f) < 1e-3f && kotlin.math.abs(it.y) < 1e-3f }
        assertNotNull(arcEnd)
        // The quadratic curve to (20,20) through (20,0), as one cubic ending there.
        val last = ops.last { it is Freeform.Cubic } as Freeform.Cubic
        assertEquals(1f, last.x, 1e-4f); assertEquals(1f, last.y, 1e-4f)
    }

    @Test fun verticesTakeTheirGuidesValues() {
        // g0 = width of the space / 2 (product); g1 = g0 + adjust 1 (sum); a vertex 0x80000000 + n is guide n.
        val g = guides(
            intArrayOf(0x0001 or 0x2000, 0x0142, 1, 2),
            intArrayOf(0x0000 or 0x2000 or 0x4000, 0x0400, 0x0147, 0),
        )
        val f = draw(mapOf(0x0142 to 1000, 0x0143 to 1000, 0x0147 to 250), mapOf(
            MsoGeometry.VERTICES to points(0x80000000.toInt(), 0, 0x80000001.toInt(), 1000, 0, 1000),
            MsoGeometry.SEGMENTS to segments(0x4000, 0x0002, 0x6001, 0x8000),
            MsoGeometry.GUIDES to g,
        ))!!
        val ops = moves(f)
        assertEquals(Freeform.Move(0.5f, 0f), ops[0])
        assertEquals(Freeform.Line(0.75f, 1f), ops[1])
    }

    @Test fun theFormulas() {
        assertEquals(7.0, MsoGeometry.formula(0, 5.0, 4.0, 2.0), 1e-9)          // sum
        assertEquals(10.0, MsoGeometry.formula(1, 5.0, 4.0, 2.0), 1e-9)         // product
        assertEquals(0.0, MsoGeometry.formula(1, 5.0, 4.0, 0.0), 1e-9)          // … over nothing
        assertEquals(4.5, MsoGeometry.formula(2, 5.0, 4.0, 0.0), 1e-9)          // mid
        assertEquals(5.0, MsoGeometry.formula(3, -5.0, 0.0, 0.0), 1e-9)
        assertEquals(4.0, MsoGeometry.formula(4, 5.0, 4.0, 0.0), 1e-9)
        assertEquals(5.0, MsoGeometry.formula(5, 5.0, 4.0, 0.0), 1e-9)
        assertEquals(1.0, MsoGeometry.formula(6, 1.0, 1.0, 2.0), 1e-9)          // if
        assertEquals(2.0, MsoGeometry.formula(6, 0.0, 1.0, 2.0), 1e-9)
        assertEquals(5.0, MsoGeometry.formula(7, 3.0, 4.0, 0.0), 1e-9)          // mod
        assertEquals(45.0 * 65536, MsoGeometry.formula(8, 1.0, 1.0, 0.0), 1e-6) // atan2, degrees 16.16
        assertEquals(50.0, MsoGeometry.formula(9, 100.0, 30.0 * 65536, 0.0), 1e-9)   // sin
        assertEquals(50.0, MsoGeometry.formula(10, 100.0, 60.0 * 65536, 0.0), 1e-9)  // cos
        assertEquals(6.0, MsoGeometry.formula(11, 10.0, 3.0, 4.0), 1e-9)        // cosatan2
        assertEquals(8.0, MsoGeometry.formula(12, 10.0, 3.0, 4.0), 1e-9)        // sinatan2
        assertEquals(3.0, MsoGeometry.formula(13, 9.0, 0.0, 0.0), 1e-9)
        assertEquals((10 + 20 * 65536 - 5 * 65536).toDouble(), MsoGeometry.formula(14, 10.0, 20.0, 5.0), 1e-9)
        assertEquals(8.0, MsoGeometry.formula(15, 3.0, 5.0, 10.0), 1e-9)        // ellipse: 10 · √(1 − (3/5)²)
        assertEquals(100.0, MsoGeometry.formula(16, 100.0, 45.0 * 65536, 0.0), 1e-9)  // tan
    }

    @Test fun withoutSegmentsTheVerticesMakeAClosedPolygon() {
        val f = draw(mapOf(0x0142 to 2, 0x0143 to 2), mapOf(MsoGeometry.VERTICES to points(0, 0, 2, 0, 1, 2)))!!
        assertEquals(Freeform.Close, moves(f).last())
        // An open one when its path says lines alone.
        val open = draw(mapOf(0x0142 to 2, 0x0143 to 2, MsoGeometry.SHAPE_PATH to 0), mapOf(MsoGeometry.VERTICES to points(0, 0, 2, 0, 1, 2)))!!
        assertFalse(moves(open).contains(Freeform.Close))
    }

    @Test fun aSpaceOfNoSizeIsTheShapesHundredthsOfAMillimetre() {
        // As LibreOffice writes Office's geometry: a 1-inch shape is 2540 hundredths of a millimetre.
        val f = draw(mapOf(0x0140 to 0, 0x0141 to 0, 0x0142 to 0, 0x0143 to 0), mapOf(
            MsoGeometry.VERTICES to points(0, 0, 2540, 1270),
            MsoGeometry.SEGMENTS to segments(0x4000, 0x0001, 0x8000),
        ))!!
        assertEquals(Freeform.Line(1f, 0.5f), moves(f)[1])
    }

    @Test fun nothingToDraw() {
        assertNull(draw(complex = emptyMap()))
        assertNull(draw(complex = mapOf(MsoGeometry.VERTICES to points(5, 5))))
    }
}
