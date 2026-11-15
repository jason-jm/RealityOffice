package com.therealityteam.aeropages.model

import kotlin.math.pow

/**
 * The colour of text that sets none of its own — LibreOffice's automatic colour, PowerPoint's dark
 * and light text of a slide's colour map: the colour it inherits (a master's) where that reads over
 * what the text sits on, else near-black over light and white over dark. A run's own colour is
 * never changed.
 */
object TextColors {
    const val DARK = 0xFF1A1A1A
    const val LIGHT = 0xFFFFFFFF

    /** The least contrast (WCAG's ratio, 1–21) an inherited colour keeps its place at. */
    const val MIN_CONTRAST = 2.0

    /** An ARGB colour's relative luminance (WCAG): 0 black … 1 white. */
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** The contrast ratio of two colours, 1 (none) to 21 (black on white). */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Whether light text reads better than dark over [backdrop]. */
    fun isDark(backdrop: Long): Boolean = contrast(backdrop, LIGHT) > contrast(backdrop, DARK)

    /**
     * Text's colour over [backdrop] (null: unknown — a picture): [inherited] where it reads there,
     * else dark or light, whichever does; over an unknown backdrop, [inherited] or dark.
     */
    fun over(inherited: Long?, backdrop: Long?): Long {
        if (backdrop == null) return inherited ?: DARK
        if (inherited != null && contrast(inherited, backdrop) >= MIN_CONTRAST) return inherited
        return if (isDark(backdrop)) LIGHT else DARK
    }

    /** [top] (its alpha) laid over the opaque [under]. */
    fun blend(top: Long, under: Long): Long {
        val a = ((top ushr 24) and 0xFF) / 255.0
        if (a >= 1.0) return top or 0xFF000000
        fun mix(shift: Int) = Math.round(((top shr shift) and 0xFF) * a + ((under shr shift) and 0xFF) * (1 - a)).toLong()
        return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/** These defaults with their colour made readable over [backdrop] (see [TextColors.over]). */
fun MasterText.over(backdrop: Long?): MasterText = MasterText(font, sizePt, TextColors.over(color, backdrop), bold)

/** These defaults with [top]'s (a design's) laid over them: each of its values that is set wins. */
fun MasterText.under(top: MasterText?): MasterText =
    if (top == null) this else MasterText(top.font ?: font, top.sizePt ?: sizePt, top.color ?: color, top.bold ?: bold)
