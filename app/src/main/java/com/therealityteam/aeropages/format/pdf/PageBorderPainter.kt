package com.therealityteam.aeropages.format.pdf

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.therealityteam.aeropages.model.CellBorder
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.PageBorders
import com.therealityteam.aeropages.model.PageSetup

/**
 * Word's page borders drawn on a page — the PDF's, and the canvas's through its native canvas — so both show the same:
 * each side's line where [PageBorders.box] puts it, the sides meeting at the corners, dashes and dots scaled with the
 * line, a double line as two, and with [PageBorders.shadow] a band of the line's colour along the right and bottom.
 */
object PageBorderPainter {

    /** [b] on a page of [pg], [scale] pixels to a point. */
    fun draw(canvas: Canvas, b: PageBorders, pg: PageSetup, scale: Float = 1f) {
        val (l, t, r, bot) = b.box(pg).let { arrayOf(it[0], it[1], it[2], it[3]) }
        fun w(x: CellBorder?) = x?.thicknessPt ?: 0f
        // Each side runs past the corners by half the crossing sides' widths, so the corners close.
        b.top?.let { side(canvas, it, l - w(b.left) / 2f, t, r + w(b.right) / 2f, t, scale) }
        b.bottom?.let { side(canvas, it, l - w(b.left) / 2f, bot, r + w(b.right) / 2f, bot, scale) }
        b.left?.let { side(canvas, it, l, t - w(b.top) / 2f, l, bot + w(b.bottom) / 2f, scale) }
        b.right?.let { side(canvas, it, r, t - w(b.top) / 2f, r, bot + w(b.bottom) / 2f, scale) }
        if (b.shadow) {
            val line = b.right ?: b.bottom ?: return
            val s = maxOf(2f, line.widthPt * 2f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (line.color ?: 0xFF000000L).toInt(); style = Paint.Style.FILL }
            val re = r + w(b.right) / 2f; val be = bot + w(b.bottom) / 2f
            // Office's shadow: offset down and right by its own width, outside the box.
            canvas.drawRect((re) * scale, (t + s) * scale, (re + s) * scale, (be + s) * scale, p)
            canvas.drawRect((l + s) * scale, (be) * scale, (re + s) * scale, (be + s) * scale, p)
        }
    }

    /** One side from (x1, y1) to (x2, y2): its line's centre. */
    internal fun side(canvas: Canvas, line: CellBorder, x1: Float, y1: Float, x2: Float, y2: Float, scale: Float) {
        val w = line.widthPt
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (line.color ?: 0xFF000000L).toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        val horizontal = y1 == y2
        if (line.style == LineStyle.DOUBLE) {
            // Two strokes of the line's width with as much between them (the side three widths thick).
            p.strokeWidth = maxOf(w, 0.25f) * scale
            val d = w
            for (k in listOf(-1, 1)) {
                val o = k * d
                if (horizontal) canvas.drawLine(x1 * scale, (y1 + o) * scale, x2 * scale, (y2 + o) * scale, p)
                else canvas.drawLine((x1 + o) * scale, y1 * scale, (x2 + o) * scale, y2 * scale, p)
            }
            return
        }
        p.strokeWidth = maxOf(w, 0.25f) * scale
        line.style.dash?.let { pattern -> p.pathEffect = DashPathEffect(FloatArray(pattern.size) { pattern[it] * maxOf(w, 0.75f) * scale }, 0f) }
        canvas.drawLine(x1 * scale, y1 * scale, x2 * scale, y2 * scale, p)
    }
}
