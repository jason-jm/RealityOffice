package com.therealityteam.aeropages.format.pdf

import android.graphics.Canvas
import android.graphics.Paint
import com.therealityteam.aeropages.model.BoxEdge
import com.therealityteam.aeropages.model.ParagraphBoxes
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.PageMetrics

/**
 * A paragraph's box (Word's paragraph borders and shading) drawn behind its text — the PDF's, and the canvas's through
 * its native canvas — so both show the same. The paragraph's row is [x], [y], [w] wide and [h] tall with its spacing
 * (see ParagraphBoxes.beforePt); the side lines stand out from its indents by their space, the top line under its space
 * before (the first of a box), the bottom line over its space after (the last), the between line over each other
 * paragraph's text, the shading inside the lines. [scale] pixels to a point.
 */
object ParagraphBoxPainter {

    fun draw(canvas: Canvas, f: ParagraphFormat, edge: BoxEdge?, x: Float, y: Float, w: Float, h: Float, scale: Float = 1f) {
        if (!ParagraphBoxes.boxed(f)) return
        val e = edge ?: BoxEdge.ALONE
        val b = f.borders
        val inset = PageMetrics.indents(f)
        val top = y + if (e.first) f.spaceBeforePt else 0f
        val bottom = y + h - if (e.last) f.spaceAfterPt else 0f
        val topLine = if (e.first) b?.top else null
        val bottomLine = if (e.last) b?.bottom else null
        val left = x + inset[0] - (if (b?.left != null) b.spaceLeft else 0f)
        val right = x + w - inset[3] + (if (b?.right != null) b.spaceRight else 0f)
        val lw = b?.left?.thicknessPt ?: 0f
        val rw = b?.right?.thicknessPt ?: 0f
        f.shading?.let { fill ->
            val p = Paint().apply { color = fill.toInt(); style = Paint.Style.FILL }
            canvas.drawRect(left * scale, (top + (topLine?.thicknessPt ?: 0f)) * scale, right * scale, (bottom - (bottomLine?.thicknessPt ?: 0f)) * scale, p)
        }
        b ?: return
        topLine?.let { PageBorderPainter.side(canvas, it, left - lw, top + it.thicknessPt / 2f, right + rw, top + it.thicknessPt / 2f, scale) }
        if (!e.first) b.between?.let { val cy = y + f.spaceBeforePt + it.thicknessPt / 2f; PageBorderPainter.side(canvas, it, left, cy, right, cy, scale) }
        bottomLine?.let { PageBorderPainter.side(canvas, it, left - lw, bottom - it.thicknessPt / 2f, right + rw, bottom - it.thicknessPt / 2f, scale) }
        b.left?.let { PageBorderPainter.side(canvas, it, left - lw / 2f, top, left - lw / 2f, bottom, scale) }
        b.right?.let { PageBorderPainter.side(canvas, it, right + rw / 2f, top, right + rw / 2f, bottom, scale) }
    }
}
