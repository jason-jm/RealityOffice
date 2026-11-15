package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.SvgPath
import com.therealityteam.aeropages.model.Freeform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Freeform geometry in OpenDocument: a custom shape's enhanced geometry — view box, modifiers,
 * equations and enhanced path, worked out as Impress works them out — and the points and path
 * data of `draw:polygon`, `draw:polyline` and `draw:path`, each as a [Freeform] in its shape's
 * box; and a freeform written as an enhanced path.
 */
internal object OdfGeometry {

    /** The view box a custom shape without one is drawn in (Impress's and Office's 21600 units). */
    private const val UNITS = 21600.0

    /** Hundredths of a millimetre in a point: the units of `logwidth` / `logheight`. */
    private const val HMM_PER_PT = 2540.0 / 72.0

    /**
     * [geometry]'s enhanced path worked out for a shape [widthPt] × [heightPt] (its equations see
     * the shape's size), or null when it has none that draws.
     */
    fun freeform(geometry: OdfNode, widthPt: Float, heightPt: Float): Freeform? {
        val path = geometry.attr("enhanced-path")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Enhanced(geometry, widthPt.toDouble(), heightPt.toDouble()).draw(path) }.getOrNull()
    }

    /**
     * An enhanced path of numbers alone ([path], no equations or modifiers) in [viewBox] (left, top,
     * width, height; a width or height of 0 drawing in the shape's hundredths of a millimetre) for a
     * shape [widthPt] × [heightPt]: Office's own geometry, whose segments are these commands.
     */
    fun path(path: String, viewBox: DoubleArray, widthPt: Float, heightPt: Float): Freeform? =
        freeform(OdfNode("enhanced-geometry", mapOf("enhanced-path" to path, "viewBox" to viewBox.joinToString(" "))), widthPt, heightPt)

    /** A `draw:polygon`'s, `draw:polyline`'s or `draw:path`'s outline in its box (its view box), or null. */
    fun drawing(node: OdfNode): Freeform? {
        val ops: List<Freeform.Op> = when (node.name) {
            "polygon", "polyline" -> {
                val nums = node.attr("points")?.split(' ', ',', '\n', '\t')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
                if (nums.size < 4) return null
                buildList {
                    add(Freeform.Move(nums[0], nums[1]))
                    for (i in 2 until nums.size - 1 step 2) add(Freeform.Line(nums[i], nums[i + 1]))
                    if (node.name == "polygon") add(Freeform.Close)
                }
            }
            "path" -> SvgPath.parse(node.attr("d") ?: return null)
            else -> return null
        }
        // Impress neither fills a polyline nor a path left open.
        val filled = node.name == "polygon" || ops.any { it == Freeform.Close }
        val part = Freeform.Part(ops, filled = filled)
        val box = node.attr("viewBox")?.split(' ', ',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }
        if (box != null) return Freeform.fitted(listOf(part), box[0], box[1], box[2], box[3])
        // No view box: the points' own extent.
        val b = Freeform(listOf(part)).bounds() ?: return null
        return Freeform.fitted(listOf(part), b[0], b[1], (b[2] - b[0]).coerceAtLeast(1e-3f), (b[3] - b[1]).coerceAtLeast(1e-3f))
    }

    /**
     * [f] as an enhanced path in a 21600-unit view box: `N` ends each part, `F` / `S` one not filled
     * / outlined, `H` `I` `J` `K` one darkened, a little darkened, lightened or a little lightened.
     */
    fun enhancedPath(f: Freeform): String = f.parts.joinToString(" ") { p ->
        buildString {
            when (com.therealityteam.aeropages.format.pptx.DrawingGeometry.fillModeOf(p.shade)) {
                "darken" -> append("H ")
                "darkenLess" -> append("I ")
                "lighten" -> append("J ")
                "lightenLess" -> append("K ")
            }
            append(SvgPath.format(p.ops, UNITS.toFloat(), UNITS.toFloat(), decimals = 1))
            if (!p.filled) append(" F")
            if (!p.stroked) append(" S")
            append(" N")
        }
    }

    /**
     * An enhanced geometry being worked out for a shape [w] × [h] points: its view box, the
     * stretch that keeps a rounded corner round in a long shape, its modifiers and equations.
     */
    private class Enhanced(val g: OdfNode, w: Double, h: Double) {
        val box: DoubleArray = g.attr("viewBox")?.split(' ', ',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }?.toDoubleArray()
            ?: doubleArrayOf(0.0, 0.0, UNITS, UNITS)
        val logW = w * HMM_PER_PT
        val logH = h * HMM_PER_PT
        val modifiers: List<Double> = g.attr("modifiers")?.split(' ', ',')?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
        val formulas: Map<String, String> = g.children("equation").mapNotNull { e -> e.attr("name")?.let { n -> e.attr("formula")?.let { n to it } } }.toMap()
        val stretchX: Double? = g.attr("path-stretchpoint-x")?.toDoubleOrNull()
        val stretchY: Double? = g.attr("path-stretchpoint-y")?.toDoubleOrNull()
        // A shape with a stretch point longer one way than the other draws in a longer view box
        // that way (so its round corners stay round): Impress's x and y ratios.
        val xRatio = if (stretchX != null && logH > 0 && logW / logH > 1) logW / logH else 1.0
        val yRatio = if (stretchY != null && logW > 0 && logH / logW > 1) logH / logW else 1.0
        // A shape from OOXML sizes each path on its own ("0 0": in the shape's hundredths of a millimetre).
        val subViews: List<Double> = g.attr("sub-view-size")?.split(' ', ',')?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
        var pathW = box[2]
        var pathH = box[3]

        private val values = HashMap<String, Double>()
        private val working = HashSet<String>()

        fun constant(name: String): Double? = when (name) {
            "pi" -> PI
            "left" -> box[0]
            "top" -> box[1]
            "right" -> box[0] + pathW * xRatio
            "bottom" -> box[1] + pathH * yRatio
            "xstretch" -> stretchX ?: 0.0
            "ystretch" -> stretchY ?: 0.0
            "hasstroke", "hasfill" -> 1.0
            "width" -> pathW
            "height" -> pathH
            "logwidth" -> logW
            "logheight" -> logH
            else -> null
        }

        /** Equation [name]'s value; one that refers back to itself is 0. */
        fun equation(name: String): Double {
            values[name]?.let { return it }
            val f = formulas[name] ?: return 0.0
            if (!working.add(name)) return 0.0
            val v = runCatching { Formula(f, this).value() }.getOrDefault(0.0).takeIf { it.isFinite() } ?: 0.0
            working.remove(name)
            values[name] = v
            return v
        }

        /** A path parameter: a number, `?equation`, `$modifier` or a named value. [x]: a literal the view box's width long reaches the stretched width. */
        fun param(t: String, x: Boolean?): Double = when {
            t.startsWith("?") -> equation(t.substring(1))
            t.startsWith("$") -> modifiers.getOrNull(t.substring(1).toIntOrNull() ?: -1) ?: 0.0
            else -> t.toDoubleOrNull()?.let { v ->
                when {
                    x == true && v == box[2] && xRatio != 1.0 -> v * xRatio
                    x == false && v == box[3] && yRatio != 1.0 -> v * yRatio
                    else -> v
                }
            } ?: constant(t) ?: 0.0
        }

        /** Where a point of the path lands in the shape's box, as fractions. */
        fun u(v: Double): Float = (if (pathW == 0.0) v / logW else (v - box[0]) / (pathW * xRatio)).toFloat()
        fun v(v: Double): Float = (if (pathH == 0.0) v / logH else (v - box[1]) / (pathH * yRatio)).toFloat()

        fun draw(path: String): Freeform? {
            val tokens = tokenize(path)
            val parts = ArrayList<Freeform.Part>()
            var ops = ArrayList<Freeform.Op>()
            var filled = true; var stroked = true; var shade = 0f
            var i = 0
            var cmd = ' '
            var cur: Pair<Double, Double>? = null      // the current point, in path units
            var start: Pair<Double, Double>? = null
            var quadrantX = true                         // X / Y alternate their first tangent
            fun setPath(index: Int) {
                pathW = subViews.getOrNull(index * 2) ?: box[2]
                pathH = subViews.getOrNull(index * 2 + 1) ?: box[3]
            }
            setPath(0)
            fun point(px: Double, py: Double) = u(px) to v(py)
            fun moveTo(px: Double, py: Double) { val (a, b) = point(px, py); ops.add(Freeform.Move(a, b)); cur = px to py; start = cur }
            fun lineTo(px: Double, py: Double) {
                if (cur == null) { moveTo(px, py); return }
                val (a, b) = point(px, py); ops.add(Freeform.Line(a, b)); cur = px to py
            }
            fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, px: Double, py: Double) {
                if (cur == null) moveTo(x1, y1)
                val (a1, b1) = point(x1, y1); val (a2, b2) = point(x2, y2); val (a, b) = point(px, py)
                ops.add(Freeform.Cubic(a1, b1, a2, b2, a, b)); cur = px to py
            }
            /** An arc of the ellipse centred ([cx], [cy]), radii ([rx], [ry]) in path units, from parametric [t0] through [sweep] (clockwise, y down). */
            fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, t0: Double, sweep: Double, newFigure: Boolean) {
                val sx = cx + rx * cos(t0); val sy = cy + ry * sin(t0)
                if (newFigure || cur == null) moveTo(sx, sy) else lineTo(sx, sy)
                // Worked in path units, then each point placed in the box.
                val arc = ArrayList<Freeform.Op>()
                Freeform.arc(cx, cy, rx, ry, 0.0, t0, sweep, arc)
                arc.forEach { op ->
                    val c = op as Freeform.Cubic
                    curveTo(c.x1.toDouble(), c.y1.toDouble(), c.x2.toDouble(), c.y2.toDouble(), c.x.toDouble(), c.y.toDouble())
                }
                cur = (cx + rx * cos(t0 + sweep)) to (cy + ry * sin(t0 + sweep))
            }
            var pathIndex = 0
            fun endPart() {
                if (ops.any { it is Freeform.Line || it is Freeform.Cubic }) parts.add(Freeform.Part(ops, filled, stroked, shade))
                ops = ArrayList(); filled = true; stroked = true; shade = 0f; cur = null; start = null
                setPath(++pathIndex)
            }
            fun has(n: Int) = i + n <= tokens.size && (0 until n).none { isCommand(tokens[i + it]) }
            fun x() = param(tokens[i++], true)
            fun y() = param(tokens[i++], false)
            fun any() = param(tokens[i++], null)
            while (i < tokens.size) {
                if (isCommand(tokens[i])) {
                    cmd = tokens[i++][0]
                    quadrantX = cmd == 'X'
                    when (cmd) {
                        'Z' -> { if (ops.isNotEmpty()) ops.add(Freeform.Close); cur = start }
                        'N' -> endPart()
                        'F' -> filled = false
                        'S' -> stroked = false
                        // The fill darkened, a little darkened, lightened, a little lightened.
                        'H' -> shade = Freeform.DARKEN
                        'I' -> shade = Freeform.DARKEN_LESS
                        'J' -> shade = Freeform.LIGHTEN
                        'K' -> shade = Freeform.LIGHTEN_LESS
                    }
                    continue   // the command's numbers (if it takes any) come next
                }
                val before = i
                when (cmd) {
                    'M' -> if (has(2)) { moveTo(x(), y()); cmd = 'L' }
                    'L' -> if (has(2)) lineTo(x(), y())
                    'C' -> if (has(6)) curveTo(x(), y(), x(), y(), x(), y())
                    'Q' -> if (has(4)) {
                        val qx = x(); val qy = y(); val px = x(); val py = y()
                        val (x0, y0) = cur ?: (qx to qy)
                        curveTo(x0 + 2.0 / 3 * (qx - x0), y0 + 2.0 / 3 * (qy - y0), px + 2.0 / 3 * (qx - px), py + 2.0 / 3 * (qy - py), px, py)
                    }
                    'X', 'Y' -> if (has(2)) {
                        val px = x(); val py = y()
                        val (x0, y0) = cur ?: (px to py)
                        val k = 0.5522847498
                        // A quarter ellipse to (px, py), leaving the current point along x (X) or y (Y).
                        if (quadrantX) curveTo(x0 + k * (px - x0), y0, px, py - k * (py - y0), px, py)
                        else curveTo(x0, y0 + k * (py - y0), px - k * (px - x0), py, px, py)
                        quadrantX = !quadrantX
                    }
                    'T', 'U' -> if (has(6)) {
                        // Centre, radii, and angles in degrees anticlockwise from 3 o'clock; equal angles a whole turn.
                        val cx = x(); val cy = y(); val rx = x(); val ry = y()
                        val a0 = any(); val a1 = any()
                        var sweepDeg = ((a1 - a0) % 360.0 + 360.0) % 360.0
                        if (sweepDeg == 0.0) sweepDeg = 360.0
                        ellipse(cx, cy, rx, ry, Math.toRadians(-a0), -Math.toRadians(sweepDeg), cmd == 'U')
                    }
                    'A', 'B', 'W', 'V' -> if (has(8)) {
                        // An arc of the ellipse in a box, from where the ray to one point meets it to where the ray to another does:
                        // anticlockwise (A, B) or clockwise (W, V); B and V start a figure, A and W draw a line to their start.
                        val x1 = x(); val y1 = y(); val x2 = x(); val y2 = y()
                        val x3 = x(); val y3 = y(); val x4 = x(); val y4 = y()
                        val cx = (x1 + x2) / 2; val cy = (y1 + y2) / 2
                        val rx = abs(x2 - x1) / 2; val ry = abs(y2 - y1) / 2
                        if (rx > 0 && ry > 0) {
                            val t0 = atan2((y3 - cy) / ry, (x3 - cx) / rx)
                            val t1 = atan2((y4 - cy) / ry, (x4 - cx) / rx)
                            val clockwise = cmd == 'W' || cmd == 'V'
                            var sweep = t1 - t0
                            if (clockwise) { while (sweep <= 0) sweep += 2 * PI } else { while (sweep >= 0) sweep -= 2 * PI }
                            ellipse(cx, cy, rx, ry, t0, sweep, cmd == 'B' || cmd == 'V')
                        }
                    }
                    'G' -> if (has(4)) {
                        // OOXML's arcTo: radii, then start angle and swing in degrees (clockwise, y down) on the ellipse as seen.
                        val rx = x(); val ry = y(); val st = Math.toRadians(any()); val sw = Math.toRadians(any())
                        val (x0, y0) = cur ?: (0.0 to 0.0)
                        if (rx > 0 && ry > 0) {
                            val t0 = parametric(st, rx, ry)
                            val t1 = parametric(st + sw, rx, ry)
                            val cx = x0 - rx * cos(t0); val cy = y0 - ry * sin(t0)
                            ellipse(cx, cy, rx, ry, t0, t1 - t0, false)
                        }
                    }
                    else -> i++
                }
                if (i == before) i++   // too few numbers left for the command: skip one
            }
            endPart()
            return Freeform(parts).takeIf { it.drawsSomething }
        }

        private fun isCommand(t: String) = t.length == 1 && t[0] in 'A'..'Z'

        private fun tokenize(path: String): List<String> {
            val out = ArrayList<String>()
            for (raw in path.split(' ', ',', '\n', '\t', '\r')) {
                if (raw.isEmpty()) continue
                // A command glued to its first number ("M0").
                if (raw.length > 1 && raw[0] in 'A'..'Z' && (raw[1].isDigit() || raw[1] in "-+.?$")) { out.add(raw.substring(0, 1)); out.add(raw.substring(1)) }
                else out.add(raw)
            }
            return out
        }
    }

    /**
     * The parametric angle of the point an ellipse with radii [rx], [ry] shows at angle [a] (the
     * ray from its centre), kept within a quarter turn of [a] so a swing keeps its turns.
     */
    internal fun parametric(a: Double, rx: Double, ry: Double): Double {
        val base = atan2(rx * sin(a), ry * cos(a))
        return base + 2 * PI * Math.round((a - base) / (2 * PI))
    }

    /**
     * An equation of an enhanced geometry (`draw:formula`): numbers, `?equation`, `$modifier`,
     * the named values (left, width, logwidth, pi …), + − × ÷, brackets, and abs, sqrt, sin, cos,
     * tan, atan, atan2, min, max and if.
     */
    private class Formula(val s: String, val geo: Enhanced) {
        var i = 0

        fun value(): Double { val v = sum(); return v }

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun sum(): Double {
            var v = product()
            while (true) {
                skip()
                if (i >= s.length) return v
                when (s[i]) { '+' -> { i++; v += product() }; '-' -> { i++; v -= product() }; else -> return v }
            }
        }

        private fun product(): Double {
            var v = unary()
            while (true) {
                skip()
                if (i >= s.length) return v
                when (s[i]) {
                    '*' -> { i++; v *= unary() }
                    '/' -> { i++; val d = unary(); v = if (d == 0.0) 0.0 else v / d }
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            skip()
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return primary()
        }

        private fun primary(): Double {
            skip()
            if (i >= s.length) return 0.0
            val c = s[i]
            return when {
                c == '(' -> { i++; val v = sum(); skip(); if (i < s.length && s[i] == ')') i++; v }
                c == '?' -> { i++; geo.equation(word()) }
                c == '$' -> { i++; geo.modifiers.getOrNull(word().toIntOrNull() ?: -1) ?: 0.0 }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                        i += 2; while (i < s.length && s[i].isDigit()) i++
                    }
                    s.substring(start, i).toDoubleOrNull() ?: 0.0
                }
                c.isLetter() -> {
                    val name = word()
                    skip()
                    if (i < s.length && s[i] == '(') {
                        i++
                        val args = ArrayList<Double>()
                        while (true) {
                            args.add(sum()); skip()
                            if (i < s.length && s[i] == ',') { i++; continue }
                            if (i < s.length && s[i] == ')') i++
                            break
                        }
                        call(name, args)
                    } else geo.constant(name) ?: 0.0
                }
                else -> { i++; 0.0 }
            }
        }

        private fun word(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            return s.substring(start, i)
        }

        private fun call(name: String, a: List<Double>): Double {
            fun arg(k: Int) = a.getOrElse(k) { 0.0 }
            return when (name) {
                "abs" -> abs(arg(0))
                "sqrt" -> sqrt(arg(0).coerceAtLeast(0.0))
                "sin" -> sin(arg(0))
                "cos" -> cos(arg(0))
                "tan" -> tan(arg(0))
                "atan" -> atan(arg(0))
                "atan2" -> atan2(arg(0), arg(1))
                "min" -> minOf(arg(0), arg(1))
                "max" -> maxOf(arg(0), arg(1))
                "if" -> if (arg(0) > 0) arg(1) else arg(2)
                else -> 0.0
            }
        }
    }
}
