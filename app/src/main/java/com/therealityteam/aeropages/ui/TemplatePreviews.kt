package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import com.therealityteam.aeropages.format.DeskKind

/**
 * A sketch of what a template makes — a page, a sheet or a slide drawn in its colours with bars
 * for its text — for the home screen's gallery: drawn rather than rendered, so it costs nothing
 * and reads at a glance in any language.
 */
@Composable
fun TemplatePreview(t: DeskTemplate, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (t.kind) {
            DeskKind.PAGES -> page { paper -> pageSketch(t, paper) }
            DeskKind.SHEETS -> sheetSketch(t)
            DeskKind.SLIDES -> slides(t)
        }
    }
}

private val INK = Color(0xFF1F3864)
private val INK_TINT = Color(0xFFDEEAF6)
private val TEAL = Color(0xFF1D6A7A)
private val TEXT = Color(0xFFCDD2D9)
private val DARK_TEXT = Color(0xFF8A929C)
private val GREEN = Color(0xFF2E7D4F)
private val GREEN_TINT = Color(0xFFE2EFDA)
private val GRID = Color(0xFFE3E6EA)

/** A bar [x], [y], [w], [h] (fractions of [r]) with rounded ends. */
private fun DrawScope.bar(r: Rect, x: Float, y: Float, w: Float, h: Float, c: Color, round: Boolean = true) {
    val rad = if (round) minOf(w * r.width, h * r.height) / 2f else 0f
    drawRoundRect(c, Offset(r.left + x * r.width, r.top + y * r.height), Size(w * r.width, h * r.height), CornerRadius(rad, rad))
}

/** A line of text: a bar a line high. */
private fun DrawScope.line(r: Rect, x: Float, y: Float, w: Float, c: Color = TEXT, h: Float = 0.018f) = bar(r, x, y, w, h, c)

/** A paragraph: [n] lines, the last shorter. */
private fun DrawScope.lines(r: Rect, x: Float, y: Float, w: Float, n: Int, gap: Float = 0.036f) {
    for (i in 0 until n) line(r, x, y + i * gap, if (i == n - 1) w * 0.62f else w)
}

/** A portrait page with a soft shadow, centred: [content] draws on it. */
private fun DrawScope.page(content: DrawScope.(Rect) -> Unit) {
    val h = size.height * 0.88f
    val w = h * 0.773f
    val r = Rect(Offset((size.width - w) / 2f, (size.height - h) / 2f + size.height * 0.02f), Size(w, h))
    drawRoundRect(Color(0x14000000), r.topLeft + Offset(0f, 2f), r.size, CornerRadius(3f, 3f))
    drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(3f, 3f))
    content(r)
}

/** A 16:9 slide with a soft shadow at [r]. */
private fun DrawScope.slide(r: Rect, content: DrawScope.(Rect) -> Unit) {
    drawRoundRect(Color(0x1A000000), r.topLeft + Offset(0f, 2f), r.size, CornerRadius(4f, 4f))
    clipRect(r.left, r.top, r.right, r.bottom) { content(r) }
}

/** A deck: its cover, and below it a content page where there is room for both. */
private fun DrawScope.slides(t: DeskTemplate) {
    var w = size.width * 0.9f
    var h = w * 9f / 16f
    val two = !t.blank && h * 2.2f <= size.height
    val maxH = if (two) size.height * 0.46f else size.height * 0.84f
    if (h > maxH) { h = maxH; w = h * 16f / 9f }
    val x = (size.width - w) / 2f
    if (!two) { slide(Rect(Offset(x, (size.height - h) / 2f), Size(w, h))) { slideSketch(t, it) }; return }
    val gap = h * 0.16f
    val top = (size.height - 2 * h - gap) / 2f
    slide(Rect(Offset(x, top), Size(w, h))) { slideSketch(t, it) }
    slide(Rect(Offset(x, top + h + gap), Size(w, h))) { contentSketch(t, it) }
}

/** A deck's content page: its background and accent, a title and three points. */
private fun DrawScope.contentSketch(t: DeskTemplate, r: Rect) {
    val (bg, title, body) = when (t) {
        DeskTemplate.DECK_BUSINESS -> Triple(Color.White, Color(0xFF1F3864), TEXT)
        DeskTemplate.DECK_FRESH -> Triple(Color(0xFFF7F5EF), Color(0xFF2E5E4E), Color(0xFFC9CFC6))
        else -> Triple(Color(0xFF161A22), Color.White, Color(0xFF465063))
    }
    drawRect(bg, r.topLeft, r.size)
    when (t) {
        DeskTemplate.DECK_BUSINESS -> { bar(r, 0.056f, 0.07f, 0.06f, 0.025f, Color(0xFF2E75B6), round = false); bar(r, 0f, 0.95f, 1f, 0.05f, Color(0xFF1F3864), round = false) }
        DeskTemplate.DECK_FRESH -> { bar(r, 0f, 0f, 0.015f, 1f, Color(0xFF3F8F6B), round = false); drawOval(Color(0xFFE4EEDF), Offset(r.left + 0.9f * r.width, r.top + 0.8f * r.height), Size(0.14f * r.width, 0.249f * r.height)) }
        else -> bar(r, 0.035f, 0.13f, 0.008f, 0.14f, Color(0xFF3FC1C9), round = false)
    }
    bar(r, 0.07f, 0.15f, 0.42f, 0.08f, title)
    for (i in 0..2) { bar(r, 0.08f, 0.4f + i * 0.16f, 0.025f, 0.045f, title); bar(r, 0.13f, 0.4f + i * 0.16f, 0.62f - i * 0.12f, 0.045f, body) }
}

private fun DrawScope.pageSketch(t: DeskTemplate, r: Rect) {
    val x = 0.12f; val w = 0.76f
    when (t) {
        DeskTemplate.REPORT -> {
            bar(r, x, 0.09f, 0.55f, 0.045f, INK)
            line(r, x, 0.165f, 0.36f, DARK_TEXT)
            bar(r, x, 0.24f, 0.22f, 0.026f, INK)
            lines(r, x, 0.29f, w, 3)
            bar(r, x, 0.42f, 0.26f, 0.026f, INK)
            // The findings table: a dark header over three rows.
            bar(r, x, 0.47f, w, 0.04f, INK, round = false)
            for (i in 0..2) drawLine(GRID, Offset(r.left + x * r.width, r.top + (0.55f + i * 0.04f) * r.height), Offset(r.left + (x + w) * r.width, r.top + (0.55f + i * 0.04f) * r.height), 1f)
            drawRect(GRID, Offset(r.left + x * r.width, r.top + 0.47f * r.height), Size(w * r.width, 0.16f * r.height), style = Stroke(1f))
            bar(r, x, 0.69f, 0.3f, 0.026f, INK)
            for (i in 0..2) { bar(r, x, 0.745f + i * 0.04f, 0.02f, 0.016f, DARK_TEXT); line(r, x + 0.04f, 0.744f + i * 0.04f, w - 0.1f - i * 0.08f) }
        }
        DeskTemplate.LETTER -> {
            line(r, x, 0.09f, 0.26f, DARK_TEXT); line(r, x, 0.125f, 0.34f); line(r, x, 0.16f, 0.3f)
            line(r, x, 0.23f, 0.22f)
            line(r, x, 0.3f, 0.28f, DARK_TEXT); line(r, x, 0.335f, 0.32f); line(r, x, 0.37f, 0.26f)
            line(r, x, 0.45f, 0.3f, DARK_TEXT)
            lines(r, x, 0.5f, w, 3)
            lines(r, x, 0.63f, w, 3)
            line(r, x, 0.78f, 0.16f)
            line(r, x, 0.87f, 0.22f, DARK_TEXT)
        }
        DeskTemplate.MINUTES -> {
            bar(r, 0.3f, 0.08f, 0.4f, 0.045f, INK)
            val top = 0.17f; val rows = 6; val rh = 0.038f
            bar(r, x, top, 0.2f, rows * rh, INK_TINT, round = false)
            drawRect(GRID, Offset(r.left + x * r.width, r.top + top * r.height), Size(w * r.width, rows * rh * r.height), style = Stroke(1f))
            for (i in 0 until rows) {
                val y = top + i * rh
                if (i > 0) drawLine(GRID, Offset(r.left + x * r.width, r.top + y * r.height), Offset(r.left + (x + w) * r.width, r.top + y * r.height), 1f)
                line(r, x + 0.03f, y + 0.011f, 0.12f, DARK_TEXT, 0.014f); line(r, x + 0.24f, y + 0.011f, 0.3f - (i % 3) * 0.06f, TEXT, 0.014f)
            }
            bar(r, x, 0.44f, 0.18f, 0.024f, INK)
            for (i in 0..2) { line(r, x, 0.49f + i * 0.036f, 0.03f, DARK_TEXT); line(r, x + 0.05f, 0.49f + i * 0.036f, 0.4f - i * 0.05f) }
            bar(r, x, 0.62f, 0.22f, 0.024f, INK)
            lines(r, x, 0.67f, w, 2)
            bar(r, x, 0.77f, w, 0.035f, INK, round = false)
            drawRect(GRID, Offset(r.left + x * r.width, r.top + 0.77f * r.height), Size(w * r.width, 0.12f * r.height), style = Stroke(1f))
            drawLine(GRID, Offset(r.left + x * r.width, r.top + 0.85f * r.height), Offset(r.left + (x + w) * r.width, r.top + 0.85f * r.height), 1f)
        }
        DeskTemplate.RESUME -> {
            bar(r, x, 0.08f, 0.46f, 0.055f, TEAL)
            line(r, x, 0.165f, 0.62f, DARK_TEXT)
            var y = 0.24f
            for (section in 0..2) {
                bar(r, x, y, 0.2f, 0.024f, TEAL)
                drawLine(Color(0x401D6A7A), Offset(r.left + x * r.width, r.top + (y + 0.038f) * r.height), Offset(r.left + (x + w) * r.width, r.top + (y + 0.038f) * r.height), 1f)
                y += 0.06f
                line(r, x, y, 0.34f, DARK_TEXT); line(r, x + w - 0.16f, y, 0.16f)
                y += 0.04f
                for (i in 0 until (if (section == 0) 2 else 1)) { bar(r, x, y + 0.002f, 0.016f, 0.014f, DARK_TEXT); line(r, x + 0.04f, y, w - 0.12f - i * 0.1f); y += 0.034f }
                y += 0.05f
            }
        }
        else -> lines(r, x, 0.12f, w, 6)
    }
}

/** A sheet seen close: column widths and rows, gridlines, then the template's colours over them. */
private fun DrawScope.sheetSketch(t: DeskTemplate) {
    val r = Rect(Offset.Zero, size)
    drawRect(Color.White)
    val rows = 9; val rh = 1f / rows
    val cols = when (t) {
        DeskTemplate.BUDGET -> listOf(0.36f, 0.21f, 0.21f, 0.22f)
        DeskTemplate.TASKS -> listOf(0.3f, 0.14f, 0.14f, 0.14f, 0.14f, 0.14f)
        else -> listOf(0.44f, 0.14f, 0.2f, 0.22f)
    }
    fun cx(i: Int) = cols.take(i).sum()
    fun row(i: Int, fill: Color) = bar(r, 0f, i * rh, 1f, rh, fill, round = false)
    fun cellText(c: Int, rowI: Int, frac: Float, color: Color = TEXT, right: Boolean = false) {
        val w = cols[c] * frac * 0.8f
        val x = if (right) cx(c) + cols[c] * 0.9f - w else cx(c) + cols[c] * 0.1f
        line(r, x, rowI * rh + rh * 0.38f, w, color, rh * 0.24f)
    }
    when (t) {
        DeskTemplate.BUDGET -> {
            line(r, 0.05f, rh * 0.32f, 0.34f, GREEN, rh * 0.36f)
            row(2, GREEN); row(3, GREEN_TINT); row(6, GREEN); row(7, GREEN_TINT)
            line(r, 0.04f, 2 * rh + rh * 0.38f, 0.14f, Color.White, rh * 0.24f); line(r, 0.04f, 6 * rh + rh * 0.38f, 0.14f, Color.White, rh * 0.24f)
            for (rowI in listOf(4, 5, 8)) {
                cellText(0, rowI, 0.6f)
                // Actual spending as data bars; the difference green or red.
                bar(r, cx(2) + 0.01f, rowI * rh + rh * 0.2f, cols[2] * (0.3f + (rowI % 3) * 0.25f), rh * 0.6f, Color(0xFFB7DEC4), round = false)
                cellText(1, rowI, 0.6f, DARK_TEXT, right = true)
                bar(r, cx(3), rowI * rh, cols[3], rh, if (rowI == 5) Color(0xFFFFC7CE) else Color(0xFFC6EFCE), round = false)
            }
        }
        DeskTemplate.TASKS -> {
            line(r, 0.05f, rh * 0.32f, 0.3f, INK, rh * 0.36f)
            row(2, Color(0xFF4472C4))
            for (c in cols.indices) line(r, cx(c) + cols[c] * 0.12f, 2 * rh + rh * 0.38f, cols[c] * 0.5f, Color.White, rh * 0.24f)
            for (i in 3 until rows) {
                if (i % 2 == 1) row(i, Color(0xFFD9E1F2))
                cellText(0, i, 0.7f); cellText(1, i, 0.6f)
                val status = when (i) { 3 -> Color(0xFFC6EFCE); 4, 5 -> Color(0xFFFFEB9C); else -> null }
                if (status != null) bar(r, cx(4) + 0.01f, i * rh + rh * 0.18f, cols[4] - 0.02f, rh * 0.64f, status, round = false)
                bar(r, cx(5) + 0.01f, i * rh + rh * 0.25f, cols[5] * when (i) { 3 -> 0.9f; 4 -> 0.72f; 5 -> 0.4f; else -> 0.05f }, rh * 0.5f, Color(0xFF9DC3E6), round = false)
            }
        }
        else -> {
            line(r, 0.05f, rh * 0.3f, 0.24f, INK, rh * 0.3f)
            line(r, 0.05f, rh * 1.3f, 0.3f); line(r, 0.05f, rh * 2.0f, 0.26f)
            line(r, 0.7f, rh * 0.3f, 0.25f, INK, rh * 0.5f)
            row(4, INK)
            for (c in cols.indices) cellText(c, 4, 0.5f, Color.White, right = c > 0)
            for (i in 5..7) { cellText(0, i, 0.6f); cellText(3, i, 0.5f, DARK_TEXT, right = true); drawLine(GRID, Offset(0f, (i + 1) * rh * size.height), Offset(size.width, (i + 1) * rh * size.height), 1f) }
            bar(r, cx(2), 8 * rh, cols[2] + cols[3], rh, INK_TINT, round = false)
            cellText(3, 8, 0.6f, INK, right = true)
        }
    }
    // Gridlines under everything that has no fill of its own.
    for (c in 1 until cols.size) drawLine(GRID, Offset(cx(c) * size.width, 0f), Offset(cx(c) * size.width, size.height), 1f)
}

private fun DrawScope.slideSketch(t: DeskTemplate, r: Rect) {
    when (t) {
        DeskTemplate.DECK_BUSINESS -> {
            drawRect(Color(0xFF1F3864), r.topLeft, r.size)
            bar(r, 0f, 0.84f, 1f, 0.16f, Color(0xFF162B4D), round = false)
            bar(r, 0.08f, 0.32f, 0.52f, 0.09f, Color.White)
            bar(r, 0.08f, 0.52f, 0.10f, 0.018f, Color(0xFF5B9BD5), round = false)
            bar(r, 0.08f, 0.6f, 0.34f, 0.045f, Color(0xFFBDD7EE))
        }
        DeskTemplate.DECK_FRESH -> {
            drawRect(Color(0xFFF7F5EF), r.topLeft, r.size)
            drawOval(Color(0xFFDCE9D5), Offset(r.left + 0.64f * r.width, r.top - 0.22f * r.height), Size(0.5f * r.width, 0.889f * r.height))
            drawOval(Color(0xFFB9D7A8), Offset(r.left + 0.8f * r.width, r.top + 0.52f * r.height), Size(0.26f * r.width, 0.462f * r.height))
            bar(r, 0.08f, 0.32f, 0.48f, 0.09f, Color(0xFF2E5E4E))
            bar(r, 0.08f, 0.52f, 0.08f, 0.018f, Color(0xFF3F8F6B), round = false)
            bar(r, 0.08f, 0.6f, 0.32f, 0.045f, Color(0xFFA3ADA7))
        }
        DeskTemplate.DECK_DARK -> {
            drawRect(Color(0xFF10131A), r.topLeft, r.size)
            bar(r, 0.7f, 0f, 0.3f, 1f, Color(0xFF161C27), round = false)
            bar(r, 0.7f, 0f, 0.006f, 1f, Color(0xFF3FC1C9), round = false)
            bar(r, 0.08f, 0.32f, 0.48f, 0.09f, Color.White)
            bar(r, 0.08f, 0.52f, 0.12f, 0.014f, Color(0xFF3FC1C9), round = false)
            bar(r, 0.08f, 0.6f, 0.3f, 0.045f, Color(0xFF5C6678))
        }
        else -> {
            drawRect(Color.White, r.topLeft, r.size)
            bar(r, 0.2f, 0.38f, 0.6f, 0.09f, TEXT)
            bar(r, 0.3f, 0.55f, 0.4f, 0.05f, GRID)
        }
    }
}
