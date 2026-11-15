package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.LineStyle
import java.util.Locale

/**
 * Cell borders in ODF: `fo:border-*` values (`0.74pt solid #000000`, `2.49pt double #1F4E79`) to
 * Excel's line styles and back — a solid line's width picks hair, thin, medium or thick; dashes,
 * dots and double lines keep their kind.
 */
internal object OdfBorders {

    fun lineOf(value: String): BorderLine? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts.any { it == "none" || it == "hidden" }) return null
        var widthPt = 0.75f
        var kind = "solid"
        var color: Long? = null
        for (p in parts) when {
            p.startsWith("#") -> color = OdfValue.color(p)
            p.first().isDigit() || p.startsWith(".") -> widthPt = points(p) ?: widthPt
            else -> kind = p
        }
        val medium = widthPt >= 1.5f
        val style = when (kind) {
            "double", "double-thin" -> LineStyle.DOUBLE
            "dotted" -> LineStyle.DOTTED
            "dashed", "fine-dashed" -> if (medium) LineStyle.MEDIUM_DASHED else LineStyle.DASHED
            "dash-dot" -> if (medium) LineStyle.MEDIUM_DASH_DOT else LineStyle.DASH_DOT
            "dash-dot-dot" -> if (medium) LineStyle.MEDIUM_DASH_DOT_DOT else LineStyle.DASH_DOT_DOT
            else -> when {
                widthPt < 0.5f -> LineStyle.HAIR
                widthPt < 1.5f -> LineStyle.THIN
                widthPt < 2.25f -> LineStyle.MEDIUM
                else -> LineStyle.THICK
            }
        }
        return BorderLine(style, color?.takeIf { it != 0xFF000000L })
    }

    private fun points(length: String): Float? {
        val n = length.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: return null
        return when (length.substring(length.takeWhile { it.isDigit() || it == '.' }.length)) {
            "pt" -> n; "cm" -> n * 28.3465f; "mm" -> n * 2.83465f; "in" -> n * 72f; "px" -> n * 0.75f; else -> n
        }
    }

    /** A line as `fo:border-*` writes it. */
    fun valueOf(line: BorderLine): String {
        val (width, kind) = when (line.style) {
            LineStyle.HAIR -> "0.06pt" to "solid"
            LineStyle.THIN -> "0.74pt" to "solid"
            LineStyle.DOTTED -> "0.74pt" to "dotted"
            // Dash-dot lines by LibreOffice's own names for them (Calc draws and keeps them).
            LineStyle.DASHED -> "0.74pt" to "dashed"
            LineStyle.DASH_DOT -> "0.74pt" to "dash-dot"
            LineStyle.DASH_DOT_DOT -> "0.74pt" to "dash-dot-dot"
            LineStyle.MEDIUM -> "1.76pt" to "solid"
            LineStyle.MEDIUM_DASHED -> "1.76pt" to "dashed"
            LineStyle.MEDIUM_DASH_DOT, LineStyle.SLANT_DASH_DOT -> "1.76pt" to "dash-dot"
            LineStyle.MEDIUM_DASH_DOT_DOT -> "1.76pt" to "dash-dot-dot"
            LineStyle.THICK -> "2.49pt" to "solid"
            LineStyle.DOUBLE -> "2.01pt" to "double"
        }
        return "$width $kind #" + String.format(Locale.ROOT, "%06X", (line.color ?: 0xFF000000L) and 0xFFFFFF)
    }

    /** The inner line, gap and outer line of a double border (`style:border-line-width-*`), which Calc needs to draw two lines. */
    const val DOUBLE_WIDTHS = "0.7pt 0.61pt 0.7pt"

    /**
     * A document table cell's border side from its `fo:border-*` value: its kind, its width (a double
     * line's is its whole width — two lines and the gap between, a third each), its colour; null for none.
     */
    fun cellLineOf(value: String): com.therealityteam.aeropages.model.CellBorder? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts.any { it == "none" || it == "hidden" }) return null
        var widthPt = 0.5f
        var kind = "solid"
        var color: Long? = null
        for (p in parts) when {
            p.startsWith("#") -> color = OdfValue.color(p)
            p.first().isDigit() || p.startsWith(".") -> widthPt = points(p) ?: widthPt
            else -> kind = p
        }
        val style = when (kind) {
            "double", "double-thin" -> LineStyle.DOUBLE
            "dotted" -> LineStyle.DOTTED
            "dashed", "fine-dashed" -> LineStyle.DASHED
            "dash-dot" -> LineStyle.DASH_DOT
            "dash-dot-dot" -> LineStyle.DASH_DOT_DOT
            else -> LineStyle.THIN
        }
        if (widthPt <= 0f) return null
        val w = if (style == LineStyle.DOUBLE) widthPt / 3f else widthPt
        return com.therealityteam.aeropages.model.CellBorder(style, (Math.round(w * 100f) / 100f).coerceAtLeast(0.05f), color?.takeIf { it != 0xFF000000L })
    }

    /** A document table cell's border side as `fo:border-*` writes it ([line] null: none). */
    fun cellValueOf(line: com.therealityteam.aeropages.model.CellBorder?): String {
        if (line == null) return "none"
        val kind = when (line.style) {
            LineStyle.DOUBLE -> "double"
            LineStyle.DOTTED -> "dotted"
            LineStyle.DASHED, LineStyle.MEDIUM_DASHED -> "dashed"
            LineStyle.DASH_DOT, LineStyle.MEDIUM_DASH_DOT, LineStyle.SLANT_DASH_DOT -> "dash-dot"
            LineStyle.DASH_DOT_DOT, LineStyle.MEDIUM_DASH_DOT_DOT -> "dash-dot-dot"
            else -> "solid"
        }
        val w = if (line.style == LineStyle.DOUBLE) line.widthPt * 3f else line.widthPt
        return String.format(Locale.ROOT, "%.2fpt %s #%06X", w, kind, (line.color ?: 0xFF000000L) and 0xFFFFFF)
    }

    /** A double side's inner line, gap and outer line (`style:border-line-width-*`), each a third of its width. */
    fun doubleWidths(line: com.therealityteam.aeropages.model.CellBorder): String =
        String.format(Locale.ROOT, "%.2fpt %.2fpt %.2fpt", line.widthPt, line.widthPt, line.widthPt)
}
