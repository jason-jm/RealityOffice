package com.therealityteam.aeropages.format

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface

/**
 * The device's fonts for [MathLayout]: a serif face, italic for variables, as Word sets its math in
 * Cambria Math. Ink is measured at a large size and scaled, so small sizes (points, in the PDF)
 * keep their fractions. One instance per thread.
 */
class PaintMathFont : MathFont {
    private val paints = HashMap<Int, Paint>()
    private val bounds = Rect()

    fun paint(italic: Boolean, bold: Boolean): Paint = paints.getOrPut((if (italic) 1 else 0) or (if (bold) 2 else 0)) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            })
        }
    }

    override fun width(text: String, size: Float, italic: Boolean, bold: Boolean): Float {
        val p = paint(italic, bold)
        p.textSize = REF
        return p.measureText(text) * size / REF
    }

    override fun ink(text: String, size: Float, italic: Boolean, bold: Boolean): Pair<Float, Float> {
        val p = paint(italic, bold)
        p.textSize = REF
        p.getTextBounds(text, 0, text.length, bounds)
        return -bounds.top * size / REF to bounds.bottom * size / REF
    }

    private companion object {
        const val REF = 200f
    }
}

/** Draws a [MathBox] on an Android canvas — the page on screen and the PDF alike. */
object MathPainter {
    /** [box] with its baseline's left end at ([x], [baseline]), in [color] (0xAARRGGBB). */
    fun draw(canvas: Canvas, box: MathBox, x: Float, baseline: Float, color: Int, font: PaintMathFont) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for (item in box.items) when (item) {
            is MathItem.Glyphs -> {
                val p = font.paint(item.italic, item.bold)
                p.textSize = item.size
                p.color = color
                canvas.drawText(item.text, x + item.x, baseline + item.y, p)
            }
            is MathItem.Rule -> canvas.drawRect(x + item.x, baseline + item.y, x + item.x + item.w, baseline + item.y + item.h, fill)
            is MathItem.Stroke -> {
                val path = Path()
                for (op in item.path) when (op) {
                    is PathOp.Move -> path.moveTo(x + op.x, baseline + op.y)
                    is PathOp.Line -> path.lineTo(x + op.x, baseline + op.y)
                    is PathOp.Cubic -> path.cubicTo(x + op.x1, baseline + op.y1, x + op.x2, baseline + op.y2, x + op.x, baseline + op.y)
                }
                stroke.strokeWidth = item.width
                canvas.drawPath(path, stroke)
            }
        }
    }
}
