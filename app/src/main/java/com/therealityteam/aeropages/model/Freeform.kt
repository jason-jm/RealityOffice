package com.therealityteam.aeropages.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A freeform outline — ODF's `draw:path`, `draw:polygon` and `draw:polyline`, the enhanced path a
 * custom shape is drawn with, DrawingML's `a:custGeom` — in the shape's own box: x 0..1 across,
 * y 0..1 down (a figure may reach outside it). Arcs and quadratic curves come in as cubics.
 *
 * Each [Part] is drawn on its own, filled and outlined as it says: a DrawingML `a:path`, an ODF
 * enhanced path up to its `N`. Figures within a part fill even-odd, so a figure inside another is
 * a hole, as Impress fills them.
 */
data class Freeform(val parts: List<Part>) {

    /**
     * One stretch of the outline: figures of [ops], filled (an open figure as if closed) and outlined
     * unless it says not; its fill [shade]d darker (below 0) or lighter (above 0) — a cube's sides, a
     * can's top, a curved arrow's fold — as a share of the way to black or white.
     */
    data class Part(val ops: List<Op>, val filled: Boolean = true, val stroked: Boolean = true, val shade: Float = 0f)

    sealed interface Op
    data class Move(val x: Float, val y: Float) : Op
    data class Line(val x: Float, val y: Float) : Op
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Op
    data object Close : Op

    /** The same outline with every point moved by [f] (its x and y in, x and y out). */
    fun mapped(f: (Float, Float) -> Pair<Float, Float>): Freeform = Freeform(parts.map { p ->
        p.copy(ops = p.ops.map { op ->
            when (op) {
                is Move -> f(op.x, op.y).let { (x, y) -> Move(x, y) }
                is Line -> f(op.x, op.y).let { (x, y) -> Line(x, y) }
                is Cubic -> {
                    val (x1, y1) = f(op.x1, op.y1); val (x2, y2) = f(op.x2, op.y2); val (x, y) = f(op.x, op.y)
                    Cubic(x1, y1, x2, y2, x, y)
                }
                Close -> Close
            }
        })
    })

    /** The box its points (curves' control points too) span: left, top, right, bottom; null when it has none. */
    fun bounds(): FloatArray? {
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
        fun take(x: Float, y: Float) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        parts.forEach { p ->
            p.ops.forEach { op ->
                when (op) {
                    is Move -> take(op.x, op.y)
                    is Line -> take(op.x, op.y)
                    is Cubic -> { take(op.x1, op.y1); take(op.x2, op.y2); take(op.x, op.y) }
                    Close -> Unit
                }
            }
        }
        return if (l > r) null else floatArrayOf(l, t, r, b)
    }

    /**
     * Its open ends, where a line's arrowheads go: the first figure's start and the point it
     * heads for, the last figure's end and the point it comes from — [x0, y0, x0next, y0next,
     * x1, y1, x1prev, y1prev] — or null when the figure an end is on closes (or nothing is outlined).
     */
    fun openEnds(): FloatArray? {
        val ops = parts.filter { it.stroked }.flatMap { it.ops }
        val figures = ArrayList<List<Op>>()
        var cur = ArrayList<Op>()
        for (op in ops) {
            if (op is Move && cur.isNotEmpty()) { figures.add(cur); cur = ArrayList() }
            cur.add(op)
        }
        if (cur.isNotEmpty()) figures.add(cur)
        val first = figures.firstOrNull { f -> f.any { it is Line || it is Cubic } } ?: return null
        val last = figures.last { f -> f.any { it is Line || it is Cubic } }
        if (first.last() == Close || last.last() == Close) return null
        val start = first.first() as? Move ?: return null
        // The first point after the start that differs from it, along the first stretch.
        val next = when (val op = first[1]) {
            is Line -> op.x to op.y
            is Cubic -> listOf(op.x1 to op.y1, op.x2 to op.y2, op.x to op.y).firstOrNull { it != start.x to start.y } ?: (op.x to op.y)
            else -> return null
        }
        val endOp = last.last()
        val before = last.getOrNull(last.size - 2)
        fun pointOf(op: Op?): Pair<Float, Float>? = when (op) { is Move -> op.x to op.y; is Line -> op.x to op.y; is Cubic -> op.x to op.y; else -> null }
        val end = pointOf(endOp) ?: return null
        val prev = when (endOp) {
            is Cubic -> listOf(endOp.x2 to endOp.y2, endOp.x1 to endOp.y1).firstOrNull { it != end } ?: pointOf(before)
            else -> pointOf(before)
        } ?: return null
        return floatArrayOf(start.x, start.y, next.first, next.second, end.first, end.second, prev.first, prev.second)
    }

    /** Whether it draws anything: some part has a figure with a stretch after its start. */
    val drawsSomething: Boolean get() = parts.any { p -> p.ops.any { it is Line || it is Cubic } }

    companion object {
        /** DrawingML's path fill modes (`a:path@fill` darken, darkenLess, lighten, lightenLess) and ODF's enhanced-path H I J K, as shades. */
        const val DARKEN = -0.4f
        const val DARKEN_LESS = -0.2f
        const val LIGHTEN = 0.4f
        const val LIGHTEN_LESS = 0.2f

        /**
         * [parts] drawn in some space (points, EMU, a view box) fitted to the box ([left], [top],
         * [width] × [height]) of that space: every point as a fraction of the box. Null when the
         * box has no size or nothing is drawn.
         */
        fun fitted(parts: List<Part>, left: Float, top: Float, width: Float, height: Float): Freeform? {
            if (!(width > 0f) || !(height > 0f)) return null
            val f = Freeform(parts.filter { p -> p.ops.any { it is Line || it is Cubic } })
            if (!f.drawsSomething) return null
            return f.mapped { x, y -> (x - left) / width to (y - top) / height }
        }

        /**
         * An elliptical arc as cubic curves, each at most a quarter turn: the ellipse centred at
         * ([cx], [cy]) with radii [rx], [ry], its x axis turned [phi] radians, from parametric angle
         * [start] through [sweep] radians (positive clockwise, y running down). Its first point is
         * the caller's to reach.
         */
        fun arc(cx: Double, cy: Double, rx: Double, ry: Double, phi: Double, start: Double, sweep: Double, out: MutableList<Op>) {
            if (sweep == 0.0 || rx == 0.0 || ry == 0.0) return
            val n = ceil(abs(sweep) / (PI / 2) - 1e-9).toInt().coerceIn(1, 64)
            val step = sweep / n
            val k = 4.0 / 3.0 * tan(step / 4)
            val cp = cos(phi); val sp = sin(phi)
            // A point of the unturned ellipse, turned and moved to the centre.
            fun px(ex: Double, ey: Double) = (cx + ex * cp - ey * sp).toFloat()
            fun py(ex: Double, ey: Double) = (cy + ex * sp + ey * cp).toFloat()
            var a = start
            repeat(n) {
                val b = a + step
                val x0 = rx * cos(a); val y0 = ry * sin(a)
                val x3 = rx * cos(b); val y3 = ry * sin(b)
                val x1 = x0 - k * rx * sin(a); val y1 = y0 + k * ry * cos(a)
                val x2 = x3 + k * rx * sin(b); val y2 = y3 - k * ry * cos(b)
                out.add(Cubic(px(x1, y1), py(x1, y1), px(x2, y2), py(x2, y2), px(x3, y3), py(x3, y3)))
                a = b
            }
        }

        /** The point at parametric angle [t] of the ellipse centred at ([cx], [cy]) with radii [rx], [ry] (y down). */
        fun onEllipse(cx: Double, cy: Double, rx: Double, ry: Double, t: Double): Pair<Float, Float> =
            (cx + rx * cos(t)).toFloat() to (cy + ry * sin(t)).toFloat()

        /** A quadratic curve from ([x0], [y0]) through control ([qx], [qy]) to ([x], [y]) as the cubic it is. */
        fun quadratic(x0: Float, y0: Float, qx: Float, qy: Float, x: Float, y: Float): Cubic =
            Cubic(x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0), x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y)
    }
}
