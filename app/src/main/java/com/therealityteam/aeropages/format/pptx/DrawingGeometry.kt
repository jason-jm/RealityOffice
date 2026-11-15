package com.therealityteam.aeropages.format.pptx

import com.therealityteam.aeropages.format.XmlNode
import com.therealityteam.aeropages.format.odf.OdfGeometry
import com.therealityteam.aeropages.model.Freeform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * DrawingML's custom geometry (`a:custGeom`): its guides worked out as Office works them out, its
 * paths — moves, lines, cubic and quadratic curves, arcs, closes, each path filled and outlined
 * as it says — as a [Freeform] in the shape's box; and a freeform written as one.
 */
internal object DrawingGeometry {

    /** The coordinate space a written path draws in. */
    private const val SPACE = 100000L

    /** Degrees in DrawingML's angle unit. */
    private const val ANGLE = 60000.0

    /**
     * [custGeom] of a shape [w] × [h] EMU (what its guides' w and h are) as a freeform, or null
     * when its paths draw nothing.
     */
    fun read(custGeom: XmlNode, w: Double, h: Double): Freeform? = runCatching {
        val guides = Guides(w.coerceAtLeast(1.0), h.coerceAtLeast(1.0))
        custGeom.child("avLst")?.children("gd")?.forEach { guides.define(it) }
        custGeom.child("gdLst")?.children("gd")?.forEach { guides.define(it) }
        val parts = ArrayList<Freeform.Part>()
        for (path in custGeom.child("pathLst")?.children("path").orEmpty()) {
            // A path's own w and h are the space its points are in; without them, the shape's EMU.
            val pw = path.attr("w")?.toDoubleOrNull()?.takeIf { it > 0 } ?: guides.w
            val ph = path.attr("h")?.toDoubleOrNull()?.takeIf { it > 0 } ?: guides.h
            val ops = ArrayList<Freeform.Op>()
            var cur: Pair<Double, Double>? = null
            var start: Pair<Double, Double>? = null
            fun pt(n: XmlNode?): Pair<Double, Double>? = n?.let { guides.value(it.attr("x")) to guides.value(it.attr("y")) }
            fun u(x: Double) = (x / pw).toFloat()
            fun v(y: Double) = (y / ph).toFloat()
            for (cmd in path.children) {
                val pts = cmd.children("pt").map { pt(it)!! }
                when (cmd.name) {
                    "moveTo" -> pts.firstOrNull()?.let { (x, y) -> ops.add(Freeform.Move(u(x), v(y))); cur = x to y; start = cur }
                    "lnTo" -> pts.firstOrNull()?.let { (x, y) ->
                        if (cur == null) { ops.add(Freeform.Move(u(x), v(y))); start = x to y } else ops.add(Freeform.Line(u(x), v(y)))
                        cur = x to y
                    }
                    "cubicBezTo" -> if (pts.size >= 3) {
                        if (cur == null) { ops.add(Freeform.Move(u(pts[0].first), v(pts[0].second))); start = pts[0] }
                        ops.add(Freeform.Cubic(u(pts[0].first), v(pts[0].second), u(pts[1].first), v(pts[1].second), u(pts[2].first), v(pts[2].second)))
                        cur = pts[2]
                    }
                    "quadBezTo" -> if (pts.size >= 2) {
                        val (x0, y0) = cur ?: pts[0].also { ops.add(Freeform.Move(u(it.first), v(it.second))); start = it }
                        val q = Freeform.quadratic(u(x0), v(y0), u(pts[0].first), v(pts[0].second), u(pts[1].first), v(pts[1].second))
                        ops.add(q); cur = pts[1]
                    }
                    "arcTo" -> {
                        val rx = guides.value(cmd.attr("wR")); val ry = guides.value(cmd.attr("hR"))
                        val st = Math.toRadians(guides.value(cmd.attr("stAng")) / ANGLE)
                        val sw = Math.toRadians(guides.value(cmd.attr("swAng")) / ANGLE)
                        val (x0, y0) = cur ?: (0.0 to 0.0).also { ops.add(Freeform.Move(0f, 0f)); start = it }
                        if (rx > 0 && ry > 0 && sw != 0.0) {
                            // The arc starts where the pen is: the ellipse's centre lies back along the start angle.
                            val t0 = OdfGeometry.parametric(st, rx, ry)
                            val t1 = OdfGeometry.parametric(st + sw, rx, ry)
                            val cx = x0 - rx * cos(t0); val cy = y0 - ry * sin(t0)
                            val arc = ArrayList<Freeform.Op>()
                            Freeform.arc(cx, cy, rx, ry, 0.0, t0, t1 - t0, arc)
                            arc.forEach { a -> val c = a as Freeform.Cubic; ops.add(Freeform.Cubic(u(c.x1.toDouble()), v(c.y1.toDouble()), u(c.x2.toDouble()), v(c.y2.toDouble()), u(c.x.toDouble()), v(c.y.toDouble()))) }
                            cur = (cx + rx * cos(t1)) to (cy + ry * sin(t1))
                        }
                    }
                    "close" -> if (ops.isNotEmpty()) { ops.add(Freeform.Close); cur = start }
                }
            }
            if (ops.any { it is Freeform.Line || it is Freeform.Cubic }) {
                val fill = path.attr("fill")
                val stroked = path.attr("stroke").let { it != "0" && it != "false" }
                parts.add(Freeform.Part(ops, fill != "none", stroked, shadeOf(fill)))
            }
        }
        Freeform(parts).takeIf { it.drawsSomething }
    }.getOrNull()

    /** A path's fill mode (`darken`, `lightenLess` …) as a part's shade; 0 for a plain one. */
    fun shadeOf(fill: String?): Float = when (fill) {
        "darken" -> Freeform.DARKEN
        "darkenLess" -> Freeform.DARKEN_LESS
        "lighten" -> Freeform.LIGHTEN
        "lightenLess" -> Freeform.LIGHTEN_LESS
        else -> 0f
    }

    /** A part's shade as a path's fill mode, or null for a plain one. */
    fun fillModeOf(shade: Float): String? = when {
        shade <= Freeform.DARKEN -> "darken"
        shade < 0f -> "darkenLess"
        shade >= Freeform.LIGHTEN -> "lighten"
        shade > 0f -> "lightenLess"
        else -> null
    }

    /** [f] as `a:custGeom`: each part a path in a 100000-unit space, not filled / outlined / shaded as it says. */
    fun write(f: Freeform): String = buildString {
        append("""<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="l" t="t" r="r" b="b"/><a:pathLst>""")
        fun n(v: Float) = (v.toDouble() * SPACE).roundToLong()
        fun pt(x: Float, y: Float) = """<a:pt x="${n(x)}" y="${n(y)}"/>"""
        for (p in f.parts) {
            append("""<a:path w="$SPACE" h="$SPACE"""")
            if (!p.filled) append(""" fill="none"""") else fillModeOf(p.shade)?.let { append(""" fill="$it"""") }
            if (!p.stroked) append(""" stroke="0"""")
            append(">")
            for (op in p.ops) when (op) {
                is Freeform.Move -> append("<a:moveTo>").append(pt(op.x, op.y)).append("</a:moveTo>")
                is Freeform.Line -> append("<a:lnTo>").append(pt(op.x, op.y)).append("</a:lnTo>")
                is Freeform.Cubic -> append("<a:cubicBezTo>").append(pt(op.x1, op.y1)).append(pt(op.x2, op.y2)).append(pt(op.x, op.y)).append("</a:cubicBezTo>")
                Freeform.Close -> append("<a:close/>")
            }
            append("</a:path>")
        }
        append("</a:pathLst></a:custGeom>")
    }

    /**
     * A shape's guides: the built-in ones its size makes (w, h, hc, wd2, ss, cd4 …) and those its
     * `a:gd`s define, each a formula of numbers and earlier guides.
     */
    private class Guides(val w: Double, val h: Double) {
        private val values = HashMap<String, Double>()

        fun define(gd: XmlNode) {
            val name = gd.attr("name") ?: return
            values[name] = runCatching { formula(gd.attr("fmla").orEmpty()) }.getOrDefault(0.0).takeIf { it.isFinite() } ?: 0.0
        }

        /** A number, or a guide by its name (0 when it is none). */
        fun value(token: String?): Double {
            val t = token?.trim() ?: return 0.0
            t.toDoubleOrNull()?.let { return it }
            values[t]?.let { return it }
            return builtin(t) ?: 0.0
        }

        private fun builtin(name: String): Double? {
            val ss = minOf(w, h); val ls = maxOf(w, h)
            return when (name) {
                "w" -> w; "h" -> h; "l", "t" -> 0.0; "r" -> w; "b" -> h
                "hc" -> w / 2; "vc" -> h / 2
                "ss" -> ss; "ls" -> ls
                "cd2" -> 10800000.0; "cd4" -> 5400000.0; "cd8" -> 2700000.0
                "3cd4" -> 16200000.0; "3cd8" -> 8100000.0; "5cd8" -> 13500000.0; "7cd8" -> 18900000.0
                else -> {
                    // wd2 … hd10, ssd2 … ssd32: a side (or the short side) over a number.
                    val m = Regex("""(wd|hd|ssd)(\d+)""").matchEntire(name) ?: return null
                    val d = m.groupValues[2].toDouble()
                    when (m.groupValues[1]) { "wd" -> w / d; "hd" -> h / d; else -> ss / d }
                }
            }
        }

        /** DrawingML's guide formulas: an operator and its arguments, angles in 60000ths of a degree. */
        private fun formula(f: String): Double {
            val t = f.trim().split(Regex("\\s+"))
            val op = t.firstOrNull() ?: return 0.0
            fun a(i: Int) = value(t.getOrNull(i + 1))
            fun rad(x: Double) = Math.toRadians(x / ANGLE)
            return when (op) {
                "val" -> a(0)
                "*/" -> if (a(2) == 0.0) 0.0 else a(0) * a(1) / a(2)
                "+-" -> a(0) + a(1) - a(2)
                "+/" -> if (a(2) == 0.0) 0.0 else (a(0) + a(1)) / a(2)
                "?:" -> if (a(0) > 0) a(1) else a(2)
                "abs" -> abs(a(0))
                "at2" -> Math.toDegrees(atan2(a(1), a(0))) * ANGLE
                "cat2" -> a(0) * cos(atan2(a(2), a(1)))
                "sat2" -> a(0) * sin(atan2(a(2), a(1)))
                "cos" -> a(0) * cos(rad(a(1)))
                "sin" -> a(0) * sin(rad(a(1)))
                "tan" -> a(0) * tan(rad(a(1)))
                "max" -> maxOf(a(0), a(1))
                "min" -> minOf(a(0), a(1))
                "mod" -> sqrt(a(0) * a(0) + a(1) * a(1) + a(2) * a(2))
                "pin" -> when { a(1) < a(0) -> a(0); a(1) > a(2) -> a(2); else -> a(1) }
                "sqrt" -> sqrt(a(0).coerceAtLeast(0.0))
                else -> 0.0
            }
        }
    }
}
