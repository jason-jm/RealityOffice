package com.therealityteam.aeropages.format.ppt

import com.therealityteam.aeropages.model.SlideShape

/**
 * How an OfficeArt shape looks — its colours, fill, gradient and outline — from its properties
 * (OfficeArtFOPT) over its drawing's defaults ([defaultOpt], the drawing group's FOPT): PowerPoint's
 * .ppt drawings and Word's .doc drawings alike ([MS-ODRAW] 2.3).
 */
internal class OfficeArtLook(var defaultOpt: Map<Int, Int> = emptyMap()) {
    /**
     * An OfficeArtCOLORREF: an RGB, an index into the colour scheme, or (a system index) one of the
     * shape's own colours in [props] — its fill, line or fill's second colour — darkened, lightened or
     * otherwise changed as it says; null for a system colour of the device's.
     */
    fun colorOf(v: Int, scheme: LongArray?, props: Map<Int, Int>? = null, depth: Int = 0): Long? {
        val flags = (v ushr 24) and 0xFF
        val r = v and 0xFF; val g = (v ushr 8) and 0xFF; val bl = (v ushr 16) and 0xFF
        return when {
            flags and 0x08 != 0 -> scheme?.getOrNull(r)
            flags and 0x10 != 0 -> {
                if (props == null || depth > 2) return null
                fun prop(id: Int) = props[id] ?: defaultOpt[id]
                val base = when (r) {
                    0xF0 -> prop(0x0181)                       // the fill colour
                    0xF1 -> prop(0x01C0) ?: prop(0x0181)       // the line colour, or the fill's
                    0xF2 -> prop(0x01C0)                       // the line colour
                    0xF5 -> prop(0x0183)                       // the fill's second colour
                    0xF6 -> prop(0x01C2)                       // the line's second colour
                    0xF7 -> prop(0x0181) ?: prop(0x01C0)       // the fill colour, or the line's
                    else -> null
                }?.let { colorOf(it, scheme, props, depth + 1) } ?: return null
                modified(base, g, bl)
            }
            else -> 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or bl.toLong()
        }
    }

    /** [c] changed as a system colour's [how] says (its low four bits: darken, lighten, add, take away or reverse-take a grey, or a threshold, by [p]; then grey, invert, flip the top bit). */
    fun modified(c: Long, how: Int, p: Int): Long {
        var out = when (how and 0x0F) {
            1 -> channels(c) { it * p / 255 }                    // darken
            2 -> channels(c) { 255 - (255 - it) * p / 255 }      // lighten
            3 -> channels(c) { it + p }
            4 -> channels(c) { it - p }
            5 -> channels(c) { p - it }
            6 -> channels(c) { if (it < p) 0 else 255 }
            else -> c
        }
        if (how and 0x80 != 0) {
            val l = ((((out shr 16) and 0xFF) * 30 + ((out shr 8) and 0xFF) * 59 + (out and 0xFF) * 11) / 100).toInt()
            out = channels(out) { l }
        }
        if (how and 0x20 != 0) out = channels(out) { 255 - it }
        if (how and 0x40 != 0) out = channels(out) { it xor 0x80 }
        return out
    }

    /** [c] with [f] applied to each of its red, green and blue (its alpha kept). */
    fun channels(c: Long, f: (Int) -> Int): Long {
        val r = f(((c shr 16) and 0xFF).toInt()); val g = f(((c shr 8) and 0xFF).toInt()); val b = f((c and 0xFF).toInt())
        return (c and 0xFF000000L) or (r.coerceIn(0, 255).toLong() shl 16) or (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()
    }

    /** A shape's fill: its colour when filled (a gradient's first), null when not. */
    /** A flag ([bit] of the flags property [id]) as the shape says it, else as the drawing's defaults do; null when neither does. */
    fun flagOf(props: Map<Int, Int>, id: Int, bit: Int): Boolean? {
        val use = bit shl 16
        props[id]?.let { if (it and use != 0) return it and bit != 0 }
        defaultOpt[id]?.let { if (it and use != 0) return it and bit != 0 }
        return null
    }

    fun fillOf(props: Map<Int, Int>, scheme: LongArray?): Long? {
        if (flagOf(props, 0x01BF, 0x10) == false) return null
        // White when it says no colour (Office's default fill); as see-through as its opacity says.
        val color = colorOf(props[0x0181] ?: defaultOpt[0x0181] ?: return 0xFFFFFFFFL, scheme, props) ?: return null
        return withOpacity(color, props[0x0182])
    }

    /** [c] as see-through as [fixed] says (16.16, 1 opaque), when it says. */
    fun withOpacity(c: Long, fixed: Int?): Long =
        if (fixed == null || fixed >= 65536) c else (c and 0xFFFFFFL) or (Math.round(fixed.coerceAtLeast(0) / 65536f * 255).toLong() shl 24)

    /**
     * A shaded fill as the shape's gradient: linear (shade, shade-scale — its angle, which way round by
     * its focus, the middle out at ±50), from the centre or by the shape's outline (its focus point the
     * centre). Office measures its angle from the vertical, the other way round from DrawingML's.
     */
    fun gradient(sh: SlideShape, fill: Long, props: Map<Int, Int>, scheme: LongArray?) {
        val type = props[0x0180] ?: return
        if (type !in 4..7) return
        val back = withOpacity(props[0x0183]?.let { colorOf(it, scheme, props) } ?: 0xFFFFFFFFL, props[0x0184])
        when (type) {
            4, 7 -> {
                val focus = props[0x018C] ?: 0
                sh.gradientAngle = ((270f + (props[0x018B] ?: 0) / 65536f) % 360f + 360f) % 360f
                when {
                    focus == 50 || focus == -50 -> { sh.gradientKind = com.therealityteam.aeropages.model.GradientKind.AXIAL; sh.fill = fill; sh.fillGradient = back }
                    focus >= 50 -> { sh.fill = back; sh.fillGradient = fill }
                    else -> { sh.fill = fill; sh.fillGradient = back }
                }
            }
            else -> {
                sh.gradientKind = if (type == 5) com.therealityteam.aeropages.model.GradientKind.RADIAL else com.therealityteam.aeropages.model.GradientKind.RECTANGULAR
                sh.fill = back; sh.fillGradient = fill
                fun f(id: Int) = (props[id] ?: 0) / 65536f
                sh.gradientCx = ((f(0x018D) + f(0x018F)) / 2f).coerceIn(0f, 1f)
                sh.gradientCy = ((f(0x018E) + f(0x0190)) / 2f).coerceIn(0f, 1f)
            }
        }
    }

    /** How a shape's outline is dashed (lineDashing, MSOLINEDASHING: the system dashes, then the "GEL" ones). */
    fun dashOf(props: Map<Int, Int>): com.therealityteam.aeropages.model.LineDash = when (props[0x01CE] ?: defaultOpt[0x01CE]) {
        1 -> com.therealityteam.aeropages.model.LineDash.SYS_DASH
        2 -> com.therealityteam.aeropages.model.LineDash.SYS_DOT
        3 -> com.therealityteam.aeropages.model.LineDash.SYS_DASH_DOT
        4 -> com.therealityteam.aeropages.model.LineDash.SYS_DASH_DOT_DOT
        5 -> com.therealityteam.aeropages.model.LineDash.DOT
        6 -> com.therealityteam.aeropages.model.LineDash.DASH
        7 -> com.therealityteam.aeropages.model.LineDash.LONG_DASH
        8 -> com.therealityteam.aeropages.model.LineDash.DASH_DOT
        9 -> com.therealityteam.aeropages.model.LineDash.LONG_DASH_DOT
        10 -> com.therealityteam.aeropages.model.LineDash.LONG_DASH_DOT_DOT
        else -> com.therealityteam.aeropages.model.LineDash.SOLID
    }

    /** A shape's outline colour, null when it has none. */
    fun lineOf(props: Map<Int, Int>, scheme: LongArray?): Long? {
        if (flagOf(props, 0x01FF, 0x8) == false) return null
        val color = props[0x01C0] ?: defaultOpt[0x01C0] ?: return 0xFF000000L
        return withOpacity(colorOf(color, scheme, props) ?: 0xFF000000L, props[0x01C1])
    }
}
