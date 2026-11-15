package com.therealityteam.aeropages.format.svg

import com.therealityteam.aeropages.model.Freeform

/**
 * An SVG picture read into what it draws: shapes, text and embedded pictures, each with its fill
 * and outline (colours and gradients resolved), its transform, opacity and clip — in the order
 * they are painted. Plain Kotlin, no Android types: [SvgReader] makes one anywhere, the painter
 * ([SvgPainter]) draws it at any size.
 */
internal class SvgPicture(
    /** The size it declares, in CSS pixels (a 96th of an inch); its view box's when it declares none. */
    val width: Float,
    val height: Float,
    /** The part of its drawing it shows: min x, min y, width, height. */
    val viewBox: FloatArray,
    /** How the view box fits the size: `preserveAspectRatio`. */
    val aspect: SvgAspect,
    val items: List<SvgItem>,
)

/** `preserveAspectRatio`: none (stretch), or aligned by [alignX] / [alignY] (0 min, 0.5 mid, 1 max), fitted whole or ([slice]) filling. */
internal data class SvgAspect(val none: Boolean = false, val alignX: Float = 0.5f, val alignY: Float = 0.5f, val slice: Boolean = false) {
    /** The transform that puts the view box ([vx], [vy], [vw] × [vh]) into a box [w] × [h] at the origin: sx, sy, tx, ty. */
    fun fit(vx: Float, vy: Float, vw: Float, vh: Float, w: Float, h: Float): FloatArray {
        if (vw <= 0f || vh <= 0f) return floatArrayOf(1f, 1f, 0f, 0f)
        var sx = w / vw; var sy = h / vh
        if (!none) { val s = if (slice) maxOf(sx, sy) else minOf(sx, sy); sx = s; sy = s }
        val tx = (w - vw * sx) * alignX - vx * sx
        val ty = (h - vh * sy) * alignY - vy * sy
        return floatArrayOf(sx, sy, tx, ty)
    }

    companion object {
        fun parse(v: String?): SvgAspect {
            val t = v?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() && it != "defer" } ?: return SvgAspect()
            val align = t.firstOrNull() ?: return SvgAspect()
            if (align == "none") return SvgAspect(none = true)
            fun axis(s: String) = when (s) { "Min" -> 0f; "Max" -> 1f; else -> 0.5f }
            val m = Regex("x(Min|Mid|Max)Y(Min|Mid|Max)").matchEntire(align) ?: return SvgAspect(slice = t.getOrNull(1) == "slice")
            return SvgAspect(alignX = axis(m.groupValues[1]), alignY = axis(m.groupValues[2]), slice = t.getOrNull(1) == "slice")
        }
    }
}

/** Something an SVG draws. Each carries its own transform ([matrix], relative to what holds it: a, b, c, d, e, f), opacity and clip. */
internal sealed class SvgItem(val matrix: FloatArray?, val opacity: Float, val clip: SvgClip?)

/** A group: its members drawn together (so its opacity fades them as one), under its transform and clip. */
internal class SvgGroup(val items: List<SvgItem>, matrix: FloatArray?, opacity: Float, clip: SvgClip?) : SvgItem(matrix, opacity, clip)

/** A shape: its outline ([ops], in its own units), filled and stroked as its style says. */
internal class SvgShape(
    val ops: List<Freeform.Op>,
    matrix: FloatArray?,
    val fill: SvgPaint?,
    val fillOpacity: Float,
    val evenOdd: Boolean,
    val stroke: SvgPaint?,
    val strokeOpacity: Float,
    val strokeWidth: Float,
    /** 0 butt, 1 round, 2 square. */
    val cap: Int,
    /** 0 miter, 1 round, 2 bevel. */
    val join: Int,
    val miterLimit: Float,
    val dashes: FloatArray?,
    val dashOffset: Float,
    opacity: Float,
    clip: SvgClip?,
) : SvgItem(matrix, opacity, clip)

/** Text: its runs in order, each placed where it says or after the one before. */
internal class SvgText(val spans: List<SvgSpan>, matrix: FloatArray?, opacity: Float, clip: SvgClip?) : SvgItem(matrix, opacity, clip)

/**
 * A run of text: at ([x], [y]) when it sets them (a new chunk, anchored by [anchor]: 0 start,
 * 1 middle, 2 end), else after the run before, moved by [dx], [dy].
 */
internal class SvgSpan(
    val text: String,
    val x: Float?,
    val y: Float?,
    val dx: Float,
    val dy: Float,
    val size: Float,
    val family: String?,
    val bold: Boolean,
    val italic: Boolean,
    val anchor: Int,
    val fill: SvgPaint?,
    val fillOpacity: Float,
)

/** An embedded picture (a `data:` URI's bytes) in its box, fitted as [aspect] says. */
internal class SvgImage(val data: ByteArray, val x: Float, val y: Float, val w: Float, val h: Float, val aspect: SvgAspect, matrix: FloatArray?, opacity: Float, clip: SvgClip?) :
    SvgItem(matrix, opacity, clip)

/** A clip path: the union of its shapes (in [bboxUnits], fractions of the clipped thing's box). */
internal class SvgClip(val parts: List<Part>, val bboxUnits: Boolean, val matrix: FloatArray?) {
    class Part(val ops: List<Freeform.Op>, val matrix: FloatArray?, val evenOdd: Boolean)
}

/** A fill or outline's paint. */
internal sealed interface SvgPaint

/** A colour, ARGB. */
internal data class SvgColor(val argb: Int) : SvgPaint

/**
 * A gradient: linear from ([x1], [y1]) to ([x2], [y2]), or radial — centre ([x1], [y1]), radius
 * [r], focus ([x2], [y2]) — through its [stops] (offset 0..1, ARGB); in [bboxUnits] its lengths are
 * fractions of the painted shape's box. [spread]: 0 pad, 1 reflect, 2 repeat.
 */
internal class SvgGradient(
    val radial: Boolean,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float, val r: Float,
    val stops: List<Pair<Float, Int>>,
    val bboxUnits: Boolean,
    val matrix: FloatArray?,
    val spread: Int,
) : SvgPaint

/** 2D affine transforms as SVG writes them: [a, b, c, d, e, f] — x' = a x + c y + e, y' = b x + d y + f. */
internal object SvgMatrix {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    /** [m] then [n]: a point goes through [n] first, then [m] (as SVG composes `transform="m n"`). */
    fun times(m: FloatArray, n: FloatArray): FloatArray = floatArrayOf(
        m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
        m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
        m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5],
    )

    fun translate(x: Float, y: Float) = floatArrayOf(1f, 0f, 0f, 1f, x, y)
    fun scale(x: Float, y: Float) = floatArrayOf(x, 0f, 0f, y, 0f, 0f)

    fun apply(m: FloatArray?, x: Float, y: Float): Pair<Float, Float> =
        if (m == null) x to y else (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])

    private val FUNCTION = Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""")

    /** A `transform` attribute's functions composed, or null when it has none. */
    fun parse(t: String?): FloatArray? {
        if (t.isNullOrBlank()) return null
        var m: FloatArray? = null
        for (f in FUNCTION.findAll(t)) {
            val a = f.groupValues[2].split(Regex("[\\s,]+")).mapNotNull { it.trim().toFloatOrNull() }
            val n = when (f.groupValues[1]) {
                "matrix" -> if (a.size >= 6) floatArrayOf(a[0], a[1], a[2], a[3], a[4], a[5]) else null
                "translate" -> a.getOrNull(0)?.let { translate(it, a.getOrElse(1) { 0f }) }
                "scale" -> a.getOrNull(0)?.let { sx -> scale(sx, a.getOrElse(1) { sx }) }
                "rotate" -> a.getOrNull(0)?.let { deg ->
                    val r = Math.toRadians(deg.toDouble()); val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
                    val rot = floatArrayOf(c, s, -s, c, 0f, 0f)
                    if (a.size >= 3) times(times(translate(a[1], a[2]), rot), translate(-a[1], -a[2])) else rot
                }
                "skewX" -> a.getOrNull(0)?.let { floatArrayOf(1f, 0f, kotlin.math.tan(Math.toRadians(it.toDouble())).toFloat(), 1f, 0f, 0f) }
                "skewY" -> a.getOrNull(0)?.let { floatArrayOf(1f, kotlin.math.tan(Math.toRadians(it.toDouble())).toFloat(), 0f, 1f, 0f, 0f) }
                else -> null
            } ?: continue
            m = if (m == null) n else times(m, n)
        }
        return m
    }
}
