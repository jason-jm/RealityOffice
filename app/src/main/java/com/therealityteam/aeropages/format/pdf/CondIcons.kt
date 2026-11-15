package com.therealityteam.aeropages.format.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.therealityteam.aeropages.model.IconSetKind

/**
 * Excel's conditional-format icons drawn with paths (no picture assets), one [size] square at ([x], [y]):
 * arrows, flags, traffic lights, signs, symbols, stars, triangles, ratings, quarters and boxes, the
 * set's icon [index] (0 for the lowest values) — the same on the grid and on paper.
 */
object CondIcons {
    private const val RED = 0xFFE0404AL
    private const val YELLOW = 0xFFF5B800L
    private const val GREEN = 0xFF21A366L
    private const val GRAY = 0xFF8C8C8CL
    private const val BLACK = 0xFF404040L
    private const val PINK = 0xFFF4A0A8L
    private const val GOLD = 0xFFF2B400L

    fun draw(canvas: Canvas, set: IconSetKind, index: Int, x: Float, y: Float, size: Float) {
        val i = index.coerceIn(0, set.count - 1)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun fill(c: Long) = p.apply { style = Paint.Style.FILL; color = c.toInt() }
        fun stroke(c: Long, w: Float) = p.apply { style = Paint.Style.STROKE; color = c.toInt(); strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val cx = x + size / 2f
        val cy = y + size / 2f
        val r = size * 0.42f
        when (set) {
            IconSetKind.ARROWS_3 -> arrow(canvas, cx, cy, r, listOf(-90f, 0f, 90f)[i], fill(listOf(RED, YELLOW, GREEN)[i]))
            IconSetKind.ARROWS_GRAY_3 -> arrow(canvas, cx, cy, r, listOf(-90f, 0f, 90f)[i], fill(GRAY))
            IconSetKind.ARROWS_4 -> arrow(canvas, cx, cy, r, listOf(-90f, -45f, 45f, 90f)[i], fill(listOf(RED, YELLOW, YELLOW, GREEN)[i]))
            IconSetKind.ARROWS_GRAY_4 -> arrow(canvas, cx, cy, r, listOf(-90f, -45f, 45f, 90f)[i], fill(GRAY))
            IconSetKind.ARROWS_5 -> arrow(canvas, cx, cy, r, listOf(-90f, -45f, 0f, 45f, 90f)[i], fill(listOf(RED, YELLOW, YELLOW, YELLOW, GREEN)[i]))
            IconSetKind.ARROWS_GRAY_5 -> arrow(canvas, cx, cy, r, listOf(-90f, -45f, 0f, 45f, 90f)[i], fill(GRAY))
            IconSetKind.FLAGS_3 -> {
                canvas.drawLine(x + size * 0.25f, y + size * 0.12f, x + size * 0.25f, y + size * 0.9f, stroke(BLACK, size * 0.08f))
                val flag = Path().apply {
                    moveTo(x + size * 0.28f, y + size * 0.14f); lineTo(x + size * 0.85f, y + size * 0.3f); lineTo(x + size * 0.28f, y + size * 0.52f); close()
                }
                canvas.drawPath(flag, fill(listOf(RED, YELLOW, GREEN)[i]))
            }
            IconSetKind.TRAFFIC_LIGHTS_3 -> canvas.drawCircle(cx, cy, r, fill(listOf(RED, YELLOW, GREEN)[i]))
            IconSetKind.TRAFFIC_LIGHTS_RIMMED_3 -> {
                canvas.drawCircle(cx, cy, r, fill(BLACK))
                canvas.drawCircle(cx, cy, r * 0.72f, fill(listOf(RED, YELLOW, GREEN)[i]))
            }
            IconSetKind.TRAFFIC_LIGHTS_4 -> canvas.drawCircle(cx, cy, r, fill(listOf(BLACK, RED, YELLOW, GREEN)[i]))
            IconSetKind.RED_TO_BLACK_4 -> canvas.drawCircle(cx, cy, r, fill(listOf(BLACK, GRAY, PINK, RED)[i]))
            IconSetKind.SIGNS_3 -> when (i) {
                0 -> canvas.drawPath(Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }, fill(RED))
                1 -> canvas.drawPath(Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy + r * 0.8f); lineTo(cx - r, cy + r * 0.8f); close() }, fill(YELLOW))
                else -> canvas.drawCircle(cx, cy, r, fill(GREEN))
            }
            IconSetKind.SYMBOLS_3, IconSetKind.SYMBOLS_UNCIRCLED_3 -> {
                val circled = set == IconSetKind.SYMBOLS_3
                val color = listOf(RED, YELLOW, GREEN)[i]
                val ink = if (circled) 0xFFFFFFFFL else color
                if (circled) canvas.drawCircle(cx, cy, r, fill(color))
                val k = if (circled) r * 0.5f else r * 0.8f
                val w = size * (if (circled) 0.12f else 0.16f)
                when (i) {
                    0 -> { stroke(ink, w); canvas.drawLine(cx - k, cy - k, cx + k, cy + k, p); canvas.drawLine(cx + k, cy - k, cx - k, cy + k, p) }
                    1 -> { stroke(ink, w); canvas.drawLine(cx, cy - k, cx, cy + k * 0.35f, p); canvas.drawPoint(cx, cy + k * 0.9f, p) }
                    else -> {
                        val tick = Path().apply { moveTo(cx - k, cy); lineTo(cx - k * 0.25f, cy + k * 0.7f); lineTo(cx + k, cy - k * 0.6f) }
                        canvas.drawPath(tick, stroke(ink, w))
                    }
                }
            }
            IconSetKind.STARS_3 -> {
                val star = star(cx, cy, r, r * 0.45f)
                canvas.drawPath(star, fill(0xFFFFFFFFL))
                if (i == 2) canvas.drawPath(star, fill(GOLD))
                if (i == 1) canvas.withClip(cx - r, cy - r, cx, cy + r) { drawPath(star, fill(GOLD)) }
                canvas.drawPath(star, stroke(GOLD, size * 0.06f))
            }
            IconSetKind.TRIANGLES_3 -> when (i) {
                0 -> canvas.drawPath(Path().apply { moveTo(cx - r, cy - r * 0.5f); lineTo(cx + r, cy - r * 0.5f); lineTo(cx, cy + r * 0.7f); close() }, fill(RED))
                1 -> canvas.drawRect(cx - r * 0.8f, cy - r * 0.18f, cx + r * 0.8f, cy + r * 0.18f, fill(YELLOW))
                else -> canvas.drawPath(Path().apply { moveTo(cx - r, cy + r * 0.5f); lineTo(cx + r, cy + r * 0.5f); lineTo(cx, cy - r * 0.7f); close() }, fill(GREEN))
            }
            IconSetKind.RATING_4, IconSetKind.RATING_5 -> {
                // Signal bars, as many dark as the rating (a 4-rating's lowest has one).
                val bars = 4
                val lit = if (set == IconSetKind.RATING_4) i + 1 else i
                val bw = size * 0.16f
                for (b in 0 until bars) {
                    val bx = x + size * 0.1f + b * (bw + size * 0.05f)
                    val top = y + size * (0.75f - 0.17f * b)
                    canvas.drawRect(bx, top, bx + bw, y + size * 0.88f, fill(if (b < lit) 0xFF2F6EBAL else 0xFFD0D7E2L))
                }
            }
            IconSetKind.QUARTERS_5 -> {
                canvas.drawCircle(cx, cy, r, fill(0xFFFFFFFFL))
                if (i > 0) canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 90f * i, true, fill(BLACK))
                canvas.drawCircle(cx, cy, r, stroke(BLACK, size * 0.07f))
            }
            IconSetKind.BOXES_5 -> {
                val cell = size * 0.36f
                for (b in 0 until 4) {
                    val bx = x + size * 0.12f + (b % 2) * (cell + size * 0.04f)
                    val by = y + size * 0.12f + (b / 2) * (cell + size * 0.04f)
                    canvas.drawRect(bx, by, bx + cell, by + cell, fill(if (b < i) 0xFF2F6EBAL else 0xFFD0D7E2L))
                }
            }
        }
    }

    /** An arrow through ([cx], [cy]), [r] from its middle to its tip, pointing [deg] degrees (0 right, 90 up). */
    private fun arrow(canvas: Canvas, cx: Float, cy: Float, r: Float, deg: Float, paint: Paint) {
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(0f, -r * 0.75f); lineTo(0f, -r * 0.3f); lineTo(-r, -r * 0.3f); lineTo(-r, r * 0.3f); lineTo(0f, r * 0.3f); lineTo(0f, r * 0.75f)
            close()
        }
        canvas.withTranslation(cx, cy) {
            rotate(-deg)
            drawPath(path, paint)
        }
    }

    /** A five-pointed star around ([cx], [cy]): its points [outer] out, its notches [inner]. */
    private fun star(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
        for (k in 0 until 10) {
            val a = Math.toRadians(-90.0 + 36.0 * k)
            val rr = if (k % 2 == 0) outer else inner
            val px = cx + (rr * Math.cos(a)).toFloat()
            val py = cy + (rr * Math.sin(a)).toFloat()
            if (k == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}
