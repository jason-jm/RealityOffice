package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.model.Freeform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SVG path data (`svg:d` of an ODF `draw:path`, an SVG picture's `d`): moves, lines, cubic and
 * quadratic curves, arcs and closes — absolute or relative, commands repeated by more numbers —
 * as absolute moves, lines, cubics and closes; and written back.
 */
internal object SvgPath {

    /** [d] as absolute ops; data that goes wrong ends the path there, as a browser ends it. */
    fun parse(d: String): List<Freeform.Op> {
        val out = ArrayList<Freeform.Op>()
        val s = Scanner(d)
        var cx = 0f; var cy = 0f            // the current point
        var sx = 0f; var sy = 0f            // where the figure started
        var lastCtrlX = 0f; var lastCtrlY = 0f
        var lastCmd = ' '
        var cmd = ' '
        while (true) {
            s.skipSeparators()
            if (s.atEnd) break
            val c = s.peek()
            if (c.isLetter()) { cmd = c; s.advance() } else if (cmd == ' ') break
            else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'   // numbers after a move are lines
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f; val oy = if (rel) cy else 0f
            try {
                when (cmd.uppercaseChar()) {
                    'M' -> { cx = ox + s.number(); cy = oy + s.number(); sx = cx; sy = cy; out.add(Freeform.Move(cx, cy)) }
                    'L' -> { cx = ox + s.number(); cy = oy + s.number(); out.add(Freeform.Line(cx, cy)) }
                    'H' -> { cx = ox + s.number(); out.add(Freeform.Line(cx, cy)) }
                    'V' -> { cy = oy + s.number(); out.add(Freeform.Line(cx, cy)) }
                    'C' -> {
                        val x1 = ox + s.number(); val y1 = oy + s.number(); val x2 = ox + s.number(); val y2 = oy + s.number()
                        cx = ox + s.number(); cy = oy + s.number()
                        out.add(Freeform.Cubic(x1, y1, x2, y2, cx, cy)); lastCtrlX = x2; lastCtrlY = y2
                    }
                    'S' -> {
                        // The first control point mirrors the last curve's second (or is the current point).
                        val smooth = lastCmd.uppercaseChar() in "CS"
                        val x1 = if (smooth) 2 * cx - lastCtrlX else cx; val y1 = if (smooth) 2 * cy - lastCtrlY else cy
                        val x2 = ox + s.number(); val y2 = oy + s.number()
                        cx = ox + s.number(); cy = oy + s.number()
                        out.add(Freeform.Cubic(x1, y1, x2, y2, cx, cy)); lastCtrlX = x2; lastCtrlY = y2
                    }
                    'Q' -> {
                        val qx = ox + s.number(); val qy = oy + s.number()
                        val x = ox + s.number(); val y = oy + s.number()
                        out.add(Freeform.quadratic(cx, cy, qx, qy, x, y)); cx = x; cy = y; lastCtrlX = qx; lastCtrlY = qy
                    }
                    'T' -> {
                        val smooth = lastCmd.uppercaseChar() in "QT"
                        val qx = if (smooth) 2 * cx - lastCtrlX else cx; val qy = if (smooth) 2 * cy - lastCtrlY else cy
                        val x = ox + s.number(); val y = oy + s.number()
                        out.add(Freeform.quadratic(cx, cy, qx, qy, x, y)); cx = x; cy = y; lastCtrlX = qx; lastCtrlY = qy
                    }
                    'A' -> {
                        val rx = s.number(); val ry = s.number(); val rot = s.number()
                        val large = s.flag(); val sweep = s.flag()
                        val x = ox + s.number(); val y = oy + s.number()
                        arc(cx, cy, rx, ry, rot, large, sweep, x, y, out); cx = x; cy = y
                    }
                    'Z' -> { out.add(Freeform.Close); cx = sx; cy = sy }
                    else -> return out
                }
            } catch (_: NumberFormatException) {
                return out
            }
            lastCmd = cmd
            // A close takes no numbers: the next letter must follow.
            if (cmd.uppercaseChar() == 'Z') cmd = ' '
        }
        return out
    }

    /**
     * An SVG arc from ([x0], [y0]) to ([x], [y]) as cubics (the SVG spec's endpoint to centre
     * conversion): radii too small grow to fit, a zero radius is a line.
     */
    private fun arc(x0: Float, y0: Float, rxIn: Float, ryIn: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x: Float, y: Float, out: MutableList<Freeform.Op>) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { out.add(Freeform.Line(x, y)); return }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cp = cos(phi); val sp = sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val k = sqrt(lambda); rx *= k; ry *= k }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = if (den == 0.0) 0.0 else sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry; val cyp = -co * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x0 + x) / 2.0
        val cy = sp * cxp + cp * cyp + (y0 + y) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            val a = acos((dot / len).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
        val start = angle(1.0, 0.0, ux, uy)
        var delta = angle(ux, uy, vx, vy)
        if (!sweep && delta > 0) delta -= 2 * PI else if (sweep && delta < 0) delta += 2 * PI
        Freeform.arc(cx, cy, rx, ry, phi, start, delta, out)
    }

    /** [ops] as path data, x times [sx] and y times [sy], to [decimals] places. */
    fun format(ops: List<Freeform.Op>, sx: Float = 1f, sy: Float = 1f, decimals: Int = 2): String = buildString {
        fun n(v: Float): String {
            val r = Math.round(v.toDouble() * Math.pow(10.0, decimals.toDouble())) / Math.pow(10.0, decimals.toDouble())
            return if (r == Math.rint(r)) r.toLong().toString() else r.toString()
        }
        for (op in ops) {
            if (isNotEmpty()) append(' ')
            when (op) {
                is Freeform.Move -> append("M ").append(n(op.x * sx)).append(' ').append(n(op.y * sy))
                is Freeform.Line -> append("L ").append(n(op.x * sx)).append(' ').append(n(op.y * sy))
                is Freeform.Cubic -> append("C ").append(n(op.x1 * sx)).append(' ').append(n(op.y1 * sy)).append(' ')
                    .append(n(op.x2 * sx)).append(' ').append(n(op.y2 * sy)).append(' ').append(n(op.x * sx)).append(' ').append(n(op.y * sy))
                Freeform.Close -> append('Z')
            }
        }
    }

    /** Numbers and flags of path data, SVG's way: "-1.5e-3", ".5.5" (two), "1-2" (two), flags without separators. */
    private class Scanner(val s: String) {
        var i = 0
        val atEnd get() = i >= s.length
        fun peek() = s[i]
        fun advance() { i++ }
        fun skipSeparators() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }

        fun number(): Float {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var digits = false
            while (i < s.length && s[i].isDigit()) { i++; digits = true }
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) { i++; digits = true } }
            if (!digits) { i = start; throw NumberFormatException("no number at $start") }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val mark = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i < s.length && s[i].isDigit()) { while (i < s.length && s[i].isDigit()) i++ } else i = mark
            }
            return s.substring(start, i).toFloat()
        }

        fun flag(): Boolean {
            skipSeparators()
            if (i >= s.length || (s[i] != '0' && s[i] != '1')) throw NumberFormatException("no flag at $i")
            return s[i++] == '1'
        }
    }
}
