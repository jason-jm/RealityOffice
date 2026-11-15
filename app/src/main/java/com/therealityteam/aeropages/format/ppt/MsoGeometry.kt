package com.therealityteam.aeropages.format.ppt

import com.therealityteam.aeropages.format.odf.OdfGeometry
import com.therealityteam.aeropages.model.Freeform
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A shape's own geometry in Office's drawing format ([MS-ODRAW] 2.3.6) — what a freeform drawn in
 * PowerPoint carries, and any shape without a preset (MSOSPT 0):
 *
 * - its geometry space (geoLeft, geoTop, geoRight, geoBottom: 21600 units square unless it says);
 * - its vertices (pVertices), each coordinate in that space or, from 0x80000000 to 0x8000007F, the
 *   value of a guide;
 * - its guides (pGuides): formulas over the adjust values, the geometry space, the shape's size and
 *   the guides before them;
 * - the segments that join the vertices (pSegmentInfo): lines, cubic curves, moves, closes and path
 *   ends, and the escapes — arcs, angle ellipses, elliptical quadrants, quadratic curves, no fill,
 *   no line — which are OpenDocument's enhanced path commands one for one, and so drawn by it.
 */
internal object MsoGeometry {

    const val SHAPE_PATH = 0x0144
    const val VERTICES = 0x0145
    const val SEGMENTS = 0x0146
    const val GUIDES = 0x0156

    /** The complex properties that are arrays (IMsoArray), whose size a writer may give without their 6-byte header. */
    val ARRAYS = setOf(0x0145, 0x0146, 0x0151, 0x0152, 0x0155, 0x0156, 0x0157, 0x0197, 0x01CE, 0x0383)

    /**
     * The geometry of a shape with simple properties [props] and complex ones [complex], [widthEmu] ×
     * [heightEmu] EMU in size, as an outline in its box; null when it has no vertices or draws nothing.
     */
    fun freeform(props: Map<Int, Int>, complex: Map<Int, ByteArray>, widthEmu: Double, heightEmu: Double): Freeform? {
        val vertexData = complex[VERTICES] ?: return null
        val left = (props[0x0140] ?: 0).toDouble(); val top = (props[0x0141] ?: 0).toDouble()
        val width = (props[0x0142] ?: 21600) - left; val height = (props[0x0143] ?: 21600) - top
        val guides = guides(complex[GUIDES], props, left, top, width, height, widthEmu, heightEmu)
        val points = vertices(vertexData, guides) ?: return null
        val path = path(points, complex[SEGMENTS], props[SHAPE_PATH]) ?: return null
        // (A geometry space of no size draws in the shape's hundredths of a millimetre, as LibreOffice writes it.)
        return OdfGeometry.path(path, doubleArrayOf(left, top, width, height), (widthEmu / 12700).toFloat(), (heightEmu / 12700).toFloat())
    }

    /** An IMsoArray: its element count and each element's size (0xFFF0: a 4-byte element, a POINT in halves), its elements after its 6-byte header. */
    private class MsoArray(val data: ByteArray) {
        val size: Int = if (data.size >= 6) PptReader.u16(data, 4).let { if (it == 0xFFF0) 4 else it } else 0
        val count: Int = if (data.size >= 6 && size > 0) min(PptReader.u16(data, 0), (data.size - 6) / size) else 0
        fun at(i: Int) = 6 + i * size
    }

    /** Each vertex as its x and y in the geometry space (a guide's value where a coordinate names one). */
    fun vertices(data: ByteArray, guides: DoubleArray): List<DoubleArray>? {
        val a = MsoArray(data)
        if (a.size != 4 && a.size != 8) return null
        val out = ArrayList<DoubleArray>(a.count)
        for (i in 0 until a.count) {
            val o = a.at(i)
            out += if (a.size == 8) doubleArrayOf(coordinate(PptReader.u32(data, o), guides), coordinate(PptReader.u32(data, o + 4), guides))
                else doubleArrayOf(coordinate16(PptReader.u16(data, o), guides), coordinate16(PptReader.u16(data, o + 2), guides))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun coordinate(v: Int, guides: DoubleArray): Double {
        val u = v.toLong() and 0xFFFFFFFFL
        return if (u in 0x80000000L..0x8000007FL) guides.getOrElse((u - 0x80000000L).toInt()) { 0.0 } else v.toDouble()
    }

    private fun coordinate16(v: Int, guides: DoubleArray): Double =
        if (v in 0x8000..0x807F) guides.getOrElse(v - 0x8000) { 0.0 } else v.toShort().toDouble()

    /**
     * The guides' values, in order: each SG record's formula over its three parameters — constants,
     * or (calculated) the geometry space's centre or size, an adjust value, xLimo / yLimo, whether
     * the shape is outlined, an earlier guide, or the shape's size in pixels or EMU.
     */
    fun guides(data: ByteArray?, props: Map<Int, Int>, left: Double, top: Double, width: Double, height: Double, widthEmu: Double, heightEmu: Double): DoubleArray {
        data ?: return DoubleArray(0)
        val a = MsoArray(data)
        if (a.size != 8) return DoubleArray(0)
        val g = DoubleArray(a.count)
        val lineBools = props[0x01FF]
        val lined = if (lineBools != null && lineBools and 0x80000 != 0) lineBools and 0x8 != 0 else true
        fun param(v: Int, calculated: Boolean, index: Int): Double = if (!calculated) v.toDouble() else when (v) {
            0x0140 -> left + width / 2
            0x0141 -> top + height / 2
            0x0142 -> width
            0x0143 -> height
            in 0x0147..0x0150, 0x0153, 0x0154 -> (props[v] ?: 0).toDouble()
            0x01FC -> if (lined) 1.0 else 0.0
            in 0x0400..0x047F -> (v - 0x0400).let { k -> if (k < index) g[k] else 0.0 }
            0x04F7 -> (props[0x01CB] ?: 9525) / 9525.0
            0x04F8 -> widthEmu / 9525.0
            0x04F9 -> heightEmu / 9525.0
            0x04FC -> widthEmu
            0x04FD -> heightEmu
            0x04FE -> widthEmu / 2
            0x04FF -> heightEmu / 2
            else -> 0.0
        }
        for (i in 0 until a.count) {
            val o = a.at(i)
            val flags = PptReader.u16(data, o)
            val p1 = param(PptReader.u16(data, o + 2), flags and 0x2000 != 0, i)
            val p2 = param(PptReader.u16(data, o + 4), flags and 0x4000 != 0, i)
            val p3 = param(PptReader.u16(data, o + 6), flags and 0x8000 != 0, i)
            g[i] = formula(flags and 0x1FFF, p1, p2, p3).takeIf { it.isFinite() } ?: 0.0
        }
        return g
    }

    /** Formula [sgf] over its parameters; an angle in and out in degrees as 16.16 fixed point. */
    fun formula(sgf: Int, p1: Double, p2: Double, p3: Double): Double {
        fun rad(fixed: Double) = Math.toRadians(fixed / 65536.0)
        return when (sgf) {
            0 -> p1 + p2 - p3                                   // sum
            1 -> if (p3 == 0.0) 0.0 else p1 * p2 / p3           // product
            2 -> (p1 + p2) / 2                                  // mid
            3 -> abs(p1)                                        // absolute
            4 -> min(p1, p2)
            5 -> max(p1, p2)
            6 -> if (p1 > 0) p2 else p3                         // if
            7 -> sqrt(p1 * p1 + p2 * p2 + p3 * p3)              // mod
            8 -> Math.toDegrees(atan2(p2, p1)) * 65536.0        // atan2
            9 -> p1 * sin(rad(p2))
            10 -> p1 * cos(rad(p2))
            11 -> p1 * cos(atan2(p3, p2))                       // cosatan2
            12 -> p1 * sin(atan2(p3, p2))                       // sinatan2
            13 -> if (p1 > 0) sqrt(p1) else 0.0
            14 -> p1 + (p2 - p3) * 65536.0                      // sumangle (as VML's: the second angle taken away)
            15 -> if (p2 == 0.0) 0.0 else p3 * sqrt(max(0.0, 1 - (p1 / p2) * (p1 / p2)))   // ellipse
            16 -> p1 * tan(rad(p2))
            else -> 0.0
        }
    }

    /**
     * The enhanced path [segments] (MSOPATHINFO: a type in the top 3 bits, a count below; an escape's
     * code in bits 8–12 and its count of vertices below) make of [points]; with no segments, lines
     * (or, as [shapePath] says, curves) through every vertex, closed unless it says open.
     */
    fun path(points: List<DoubleArray>, segments: ByteArray?, shapePath: Int?): String? {
        val out = StringBuilder()
        var next = 0
        fun take(command: Char, n: Int) {
            out.append(' ').append(command)
            for (k in 0 until n) {
                val p = points.getOrNull(next++) ?: return
                out.append(' ').append(num(p[0])).append(' ').append(num(p[1]))
            }
        }
        if (segments == null) {
            take('M', 1)
            take(if (shapePath == 2 || shapePath == 3) 'C' else 'L', points.size - 1)
            if (shapePath != 0 && shapePath != 2) out.append(" Z")
            return out.append(" N").toString().trim()
        }
        val a = MsoArray(segments)
        if (a.size != 2) return null
        for (i in 0 until a.count) {
            val v = PptReader.u16(segments, a.at(i))
            when (v ushr 13) {
                0 -> take('L', v and 0x1FFF)
                1 -> take('C', (v and 0x1FFF) * 3)
                2 -> take('M', 1)
                3 -> out.append(" Z")
                4 -> out.append(" N")
                5 -> {
                    val n = v and 0xFF
                    when ((v ushr 8) and 0x1F) {
                        1 -> take('T', n)      // angle ellipse to: centre, radii, start and end angles
                        2 -> take('U', n)      // angle ellipse, a new figure
                        3 -> take('A', n)      // arc to (anticlockwise): its box, then the rays to its ends
                        4 -> take('B', n)      // arc
                        5 -> take('W', n)      // clockwise arc to
                        6 -> take('V', n)      // clockwise arc
                        7 -> take('X', n)      // elliptical quadrants, the first leaving along x
                        8 -> take('Y', n)      // … along y
                        9 -> take('Q', n)      // quadratic curves: a control point and an end each
                        10 -> out.append(" F") // no fill
                        11 -> out.append(" S") // no line
                        0, 21, 22 -> next += n // points for what follows, or colours: not drawn
                        else -> Unit           // how an editor joins the vertices
                    }
                }
                6 -> next += v and 0xFF        // an application's own
            }
        }
        return out.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun num(v: Double): String = if (v == Math.rint(v) && abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%.4f", v)
}
