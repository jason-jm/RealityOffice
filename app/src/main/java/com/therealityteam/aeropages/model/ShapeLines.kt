package com.therealityteam.aeropages.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How a shape's outline or a line is dashed: DrawingML's preset dashes (`a:prstDash`), which ODF
 * keeps as a `draw:stroke-dash` of the same proportions. [pattern] is dash, gap, dash, gap … in
 * widths of the line, as the spec draws each preset.
 */
enum class LineDash(val ooxml: String, private val pattern: FloatArray) {
    SOLID("solid", floatArrayOf()),
    SYS_DOT("sysDot", floatArrayOf(1f, 1f)),
    SYS_DASH("sysDash", floatArrayOf(3f, 1f)),
    DASH("dash", floatArrayOf(4f, 3f)),
    DASH_DOT("dashDot", floatArrayOf(4f, 3f, 1f, 3f)),
    LONG_DASH("lgDash", floatArrayOf(8f, 3f)),
    LONG_DASH_DOT("lgDashDot", floatArrayOf(8f, 3f, 1f, 3f)),
    LONG_DASH_DOT_DOT("lgDashDotDot", floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)),
    DOT("dot", floatArrayOf(1f, 3f)),
    SYS_DASH_DOT("sysDashDot", floatArrayOf(3f, 1f, 1f, 1f)),
    SYS_DASH_DOT_DOT("sysDashDotDot", floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f));

    /** The on/off lengths for a line [width] wide; empty for a solid line. */
    fun intervals(width: Float): FloatArray = FloatArray(pattern.size) { pattern[it] * width }

    /** The dash's length, the dots after it (0–2) and the gap between them, in widths of the line — how ODF spells a dash. */
    val dashLength: Float get() = pattern.firstOrNull() ?: 0f
    val dots: Int get() = (pattern.size / 2 - 1).coerceAtLeast(0)
    val gap: Float get() = pattern.getOrNull(1) ?: 0f

    companion object {
        /** The preset named [ooxml] (`a:prstDash@val`); anything else is solid. */
        fun of(ooxml: String?): LineDash = entries.firstOrNull { it.ooxml == ooxml } ?: SOLID

        /** What PowerPoint's Dashes menu lists, in its order. */
        val MENU = listOf(SOLID, SYS_DOT, SYS_DASH, DASH, DASH_DOT, LONG_DASH, LONG_DASH_DOT, LONG_DASH_DOT_DOT)

        /**
         * The preset closest to a pattern of [dashes] (each dash and dot of one repeat, in widths of
         * the line) with [gap] between them — a custom dash (`a:custDash`) or an ODF stroke dash.
         */
        fun nearest(dashes: List<Float>, gap: Float): LineDash {
            val marks = dashes.filter { it > 0f }
            if (marks.isEmpty()) return SOLID
            val longest = marks.max()
            val tight = gap <= 1.5f
            if (longest <= 1.5f) return if (tight) SYS_DOT else DOT
            val dotCount = marks.count { it <= 1.5f }
            return when {
                longest >= 6f -> when (dotCount) { 0 -> LONG_DASH; 1 -> LONG_DASH_DOT; else -> LONG_DASH_DOT_DOT }
                tight -> when (dotCount) { 0 -> SYS_DASH; 1 -> SYS_DASH_DOT; else -> SYS_DASH_DOT_DOT }
                else -> when (dotCount) { 0 -> DASH; 1 -> DASH_DOT; else -> LONG_DASH_DOT_DOT }
            }
        }
    }
}

/** An arrowhead's kind — DrawingML's line end types (`a:headEnd@type`, `a:tailEnd@type`). */
enum class ArrowHead(val ooxml: String) {
    NONE("none"), TRIANGLE("triangle"), STEALTH("stealth"), ARROW("arrow"), DIAMOND("diamond"), OVAL("oval");

    companion object {
        fun of(ooxml: String?): ArrowHead = entries.firstOrNull { it.ooxml == ooxml } ?: NONE
    }
}

/**
 * An end of a line: its arrowhead and how big — [width] across the line and [length] along it,
 * each 0 small, 1 medium, 2 large (`w` / `len`: sm, med, lg), as multiples of the line's width.
 */
data class LineEnd(val head: ArrowHead = ArrowHead.NONE, val width: Int = 1, val length: Int = 1) {
    val isNone: Boolean get() = head == ArrowHead.NONE

    companion object {
        val NONE = LineEnd()
        private val SIZES = listOf("sm", "med", "lg")

        /** A line end as `a:headEnd` / `a:tailEnd` has it; null or none for no arrowhead. */
        fun of(type: String?, w: String?, len: String?): LineEnd {
            val head = ArrowHead.of(type)
            if (head == ArrowHead.NONE) return NONE
            return LineEnd(head, SIZES.indexOf(w).takeIf { it >= 0 } ?: 1, SIZES.indexOf(len).takeIf { it >= 0 } ?: 1)
        }

        fun sizeName(size: Int): String = SIZES[size.coerceIn(0, 2)]
    }
}

/**
 * Arrowheads as drawn: where each point of an end's outline lies, for the screen and the PDF alike.
 * Sizes follow Office's: small, medium and large are 2, 3 and 5 times the line's width — a thin
 * line's taken as [MIN_STROKE_PT] (in the caller's units) wide, so its arrowheads still show.
 */
object ArrowHeads {
    private val FACTORS = floatArrayOf(2f, 3f, 5f)

    /** A thin line's arrowheads are sized as if it were this many points wide. */
    const val MIN_STROKE_PT = 1.5f

    /** How many line widths a size class (0 small, 1 medium, 2 large) spans. */
    fun factor(size: Int): Float = FACTORS[size.coerceIn(0, 2)]

    /** Where the notch of a stealth arrowhead sits, as a share of its length from the tip. */
    const val STEALTH_NOTCH = 0.6f

    /**
     * An arrowhead's outline: [points] (x, y, x, y …) closed and [filled], or — an open arrow — two
     * strokes meeting at the tip, stroked as the line is. The line itself stops [setBack] short of
     * the tip, so its end never shows through the head.
     */
    class Head(val points: FloatArray, val filled: Boolean, val setBack: Float)

    /** Across and along sizes of [end] on a line [stroke] wide. */
    fun size(end: LineEnd, stroke: Float, minStroke: Float): Pair<Float, Float> {
        val base = maxOf(stroke, minStroke)
        return factor(end.width) * base to factor(end.length) * base
    }

    /** [end]'s head at ([tipX], [tipY]), the line arriving from ([fromX], [fromY]); null for none or a line with no length. */
    fun head(end: LineEnd, tipX: Float, tipY: Float, fromX: Float, fromY: Float, stroke: Float, minStroke: Float): Head? {
        if (end.isNone) return null
        val len = hypot(tipX - fromX, tipY - fromY)
        if (len < 1e-3f) return null
        // u runs back from the tip along the line, n across it.
        val ux = (fromX - tipX) / len; val uy = (fromY - tipY) / len
        val nx = -uy; val ny = ux
        val (w, l) = size(end, stroke, minStroke)
        fun pt(along: Float, across: Float) = floatArrayOf(tipX + ux * along + nx * across, tipY + uy * along + ny * across)
        return when (end.head) {
            ArrowHead.TRIANGLE -> Head(pt(0f, 0f) + pt(l, w / 2) + pt(l, -w / 2), true, l / 2)
            ArrowHead.STEALTH -> Head(pt(0f, 0f) + pt(l, w / 2) + pt(l * STEALTH_NOTCH, 0f) + pt(l, -w / 2), true, l / 2)
            ArrowHead.ARROW -> Head(pt(l, w / 2) + pt(0f, 0f) + pt(l, -w / 2), false, 0f)
            // Diamonds and ovals sit on the end, centred on it.
            ArrowHead.DIAMOND -> Head(pt(-l / 2, 0f) + pt(0f, w / 2) + pt(l / 2, 0f) + pt(0f, -w / 2), true, 0f)
            ArrowHead.OVAL -> {
                val n = 32
                val out = FloatArray(2 * n)
                for (k in 0 until n) {
                    val a = 2.0 * Math.PI * k / n
                    val p = pt((l / 2 * cos(a)).toFloat(), (w / 2 * sin(a)).toFloat())
                    out[2 * k] = p[0]; out[2 * k + 1] = p[1]
                }
                Head(out, true, 0f)
            }
            ArrowHead.NONE -> null
        }
    }

    /**
     * The part of the line from ([x1], [y1]) to ([x2], [y2]) that is drawn with [begin] and [end]
     * on it: each end pulled back by its head's set-back (never past the middle).
     */
    fun trimmed(x1: Float, y1: Float, x2: Float, y2: Float, begin: Head?, end: Head?): FloatArray {
        val len = hypot(x2 - x1, y2 - y1)
        if (len < 1e-3f) return floatArrayOf(x1, y1, x2, y2)
        val ux = (x2 - x1) / len; val uy = (y2 - y1) / len
        val a = minOf(begin?.setBack ?: 0f, len / 2); val b = minOf(end?.setBack ?: 0f, len / 2)
        return floatArrayOf(x1 + ux * a, y1 + uy * a, x2 - ux * b, y2 - uy * b)
    }

    /** The size class (0 small, 1 medium, 2 large) nearest an arrowhead [extent] across or along a line [stroke] wide. */
    fun sizeOf(extent: Float, stroke: Float, minStroke: Float): Int {
        val ratio = extent / maxOf(stroke, minStroke)
        return FACTORS.indices.minBy { abs(FACTORS[it] - ratio) }
    }
}
