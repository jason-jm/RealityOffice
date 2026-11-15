package com.therealityteam.aeropages.format.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The typeface a PDF page draws a run in: serif, monospace or sans serif by the run's font name,
 * in its bold / italic style. Android's PDF pages ignore fake bold and skew, so a style has to be
 * a real face.
 */
internal object PdfFaces {
    /**
     * Flags for text on a PDF page: anti-aliased, placed at fractional positions and scaled linearly
     * — a page's pixel is a point, and whole-point advances spaced letters unevenly.
     */
    const val TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG

    /**
     * Font features for text on a PDF page: no required ligatures. PICO Sans, the system's font,
     * takes I, i, J, j and l (and their accented forms) at its regular width through 'rlig' to
     * alternates no character maps to — as wide as the letters — so a page could not say what text
     * they were, and copying or searching a PDF lost them. Text in a script whose ligatures are
     * required (Arabic, Syriac, Indic…) keeps them: see [forText].
     */
    const val TEXT_FEATURES = "'rlig' 0"

    /** A paint for text on a PDF page ([TEXT_FLAGS]; [TEXT_FEATURES] unless [text] needs required ligatures). */
    fun paint(text: CharSequence? = null): Paint = Paint(TEXT_FLAGS).apply { if (text == null || plain(text)) fontFeatureSettings = TEXT_FEATURES }

    /**
     * [p] for drawing [text]: itself, or a copy — with the required ligatures of a script that needs them, and with
     * Japanese (or Korean) as its text locale where kana (or hangul) show the text is, so its Chinese characters take
     * those forms (see HanLocale; a paragraph's own choice, already on [p], stays for its runs of Han alone).
     */
    fun forText(p: Paint, text: CharSequence): Paint {
        val ligatures = p.fontFeatureSettings == TEXT_FEATURES && !plain(text)
        val locale = com.therealityteam.aeropages.model.HanLocale.strong(text)?.takeIf { it != p.textLocale }
        if (!ligatures && locale == null) return p
        return Paint(p).apply {
            if (ligatures) fontFeatureSettings = null
            if (locale != null) textLocale = locale
        }
    }

    /**
     * Whether [text] is all in scripts no font needs required ligatures for: Latin, Greek, Cyrillic,
     * Chinese, Japanese, Korean, their punctuation and symbols, emoji.
     */
    fun plain(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val c = Character.codePointAt(text, i)
            val ok = c < 0x0590 || c in 0x1D00..0x1FFF || c in 0x2000..0x2BFF || c in 0x2E00..0x9FFF || c in 0xA640..0xA69F ||
                c in 0xAC00..0xD7AF || c in 0xF900..0xFAFF || c in 0xFE10..0xFE6F || c in 0xFF00..0xFFEF || c >= 0x1F000
            if (!ok) return false
            i += Character.charCount(c)
        }
        return true
    }

    /** [text] drawn on a PDF page with [p], its script's required ligatures kept (see [forText]). */
    fun Canvas.drawPdfText(text: String, x: Float, y: Float, p: Paint) = drawText(text, x, y, forText(p, text))

    fun of(family: String?, bold: Boolean, italic: Boolean): Typeface {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val base = when {
            family == null -> Typeface.SANS_SERIF
            family.contains("Courier", true) || family.contains("Mono", true) || family.contains("Consolas", true) -> Typeface.MONOSPACE
            family.contains("Times", true) || family.contains("Georgia", true) || family.contains("Cambria", true) ||
                (family.contains("Serif", true) && !family.contains("Sans", true)) ||
                family.contains("宋") || family.contains("Song", true) || family.contains("Mincho", true) || family.contains("明朝") -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
        return Typeface.create(base, style)
    }
}
