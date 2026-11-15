package com.therealityteam.aeropages.model

import kotlin.math.ceil

/**
 * Excel's automatic row height: a row without a height of its own ([Sheet.rowHeights]) is as tall
 * as its tallest cell needs — its largest type, every line a wrapping cell breaks into, turned or stacked text — never
 * below the default. The heights live in [Sheet.autoRowHeights] (factors of the default row, only
 * rows taller than it); they are derived from the cells, not saved or undone, and are worked out
 * again whenever the cells change ([refresh]). Merged cells do not count, as in Excel.
 *
 * Text is measured the way the grid lays it out: about 0.55 em per Latin character and a whole em
 * per CJK or full-width one, a default column [CELL_W_DP] wide with [PAD_DP] of padding, and the
 * default row fitting [BASE_PT]-point type.
 */
object RowFit {
    const val BASE_PT = 12f
    const val CELL_W_DP = 88f
    const val PAD_DP = 14f
    /** A row grows at most this many times the default. */
    const val MAX_FACTOR = 8f

    /** How wide [text] runs at [sizePt], in dp. */
    fun widthDp(text: String, sizePt: Float): Float {
        var ems = 0f
        for (ch in text) ems += if (PageMetrics.isCjk(ch)) 1f else 0.55f
        return ems * sizePt
    }

    /** The lines [text] wraps into in a column [colFactor] times the default wide, at [sizePt] (each line break starts one). */
    fun lines(text: String, sizePt: Float, colFactor: Float): Int {
        val room = (CELL_W_DP * colFactor - PAD_DP).coerceAtLeast(8f)
        return text.split('\n').sumOf { part -> ceil(widthDp(part, sizePt) / room).toInt().coerceAtLeast(1) }
    }

    /** The row height (as a factor of the default) [cell] showing [text] in a column [colFactor] wide needs. */
    fun cellFactor(cell: Cell, text: String, colFactor: Float): Float {
        if (text.isEmpty()) return 1f
        val cellSize = cell.format.fontSizePt ?: BASE_PT
        val size = cell.richRuns()?.maxOfOrNull { it.format.fontSizePt ?: cellSize } ?: cellSize
        // Turned text stands as tall as it runs: its length times the sine of the angle and a line's height times the
        // cosine; stacked text a line per character (Excel grows the row for both).
        if (cell.stacked) return Cell.stackedLetters(text).size * size / BASE_PT
        if (cell.rotation != 0) {
            val a = Math.toRadians(cell.rotation.toDouble())
            val longest = text.split('\n').maxOf { widthDp(it, size) }
            return ((longest * kotlin.math.abs(kotlin.math.sin(a)) + size * kotlin.math.abs(kotlin.math.cos(a))) / BASE_PT).toFloat()
        }
        val lines = if (cell.wrap) lines(text, size, colFactor) else 1
        return lines * size / BASE_PT
    }

    /**
     * Works [sheet]'s automatic heights out again for [rows] (null: every row), [display] giving what
     * a cell shows (a formula's value). Only cells that can be taller than a line of default type —
     * larger or mixed type, wrapping text — are looked at.
     */
    fun refresh(sheet: Sheet, rows: IntRange?, display: (Int, Int) -> String) {
        // A sheet never worked out, or a large block of rows: every row, in one pass over the cells.
        if (rows != null && (!sheet.autoRowsReady || rows.last - rows.first > 2_000)) return refresh(sheet, null, display)
        val span = rows?.let { it.first.coerceAtLeast(0)..minOf(it.last, sheet.rowCount - 1) }
        val out = sheet.autoRowHeights
        if (span == null) out.clear() else for (r in span) out.remove(r)
        fun consider(k: Long, cell: Cell) {
            if (!cell.wrap && !cell.turned && (cell.format.fontSizePt ?: BASE_PT) <= BASE_PT && cell.runs == null) return
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            if (sheet.merges.any { it.covers(r, c) }) return
            val f = cellFactor(cell, if (cell.wrap || cell.turned || cell.text.startsWith("=")) display(r, c) else cell.text, sheet.colWidthFactor(c))
            if (f > (out[r] ?: 1f)) out[r] = f.coerceAtMost(MAX_FACTOR)
        }
        if (span == null) sheet.cells.forEach { (k, cell) -> consider(k, cell) }
        else for (r in span) for (c in 0 until sheet.colCount) sheet.cells[sheet.key(r, c)]?.let { consider(sheet.key(r, c), it) }
        if (span == null) sheet.autoRowsReady = true
    }
}
