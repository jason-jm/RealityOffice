package com.therealityteam.aeropages.model

/**
 * How a sheet prints over its pages, as Excel lays it out — the PDF prints so and the grid draws its page breaks so: the
 * print area (or the used range), its scale or the scale that fits so many pages, and its rows and columns cut into pages
 * by their sizes and the manual breaks ([SheetPrint.rowBreaks] / [SheetPrint.colBreaks], which fitting to pages ignores),
 * each page leaving room for the headings and the title rows or columns.
 */
object SheetPages {
    /** A column of the default width (Excel's 8.43 characters, 64 px) and a row of the default height, in points. */
    const val BASE_CELL_W = 48f
    const val CELL_H = 15f
    const val HDR_W = 28f
    /** Excel's smallest scale. */
    const val MIN_SCALE = 0.1f
    /** Page points per picture point: the page's rows are 18 pt for the grid's 15. */
    const val PICTURE_SCALE = CELL_H / SheetImage.ROW_PT
    /** How far a picture's far corner is followed down the sheet. */
    private const val MAX_PICTURE_ROW = 100_000

    /** A sheet's printout: its pages' rows ([rowPages]) and columns ([colBands]) at [scale] on a [pageW] × [pageH] page. */
    class Layout(val rows: IntRange, val cols: IntRange, val scale: Float, val rowPages: List<List<Int>>, val colBands: List<List<Int>>, val pageW: Int, val pageH: Int) {
        /** The rows that begin a page after the first, and the columns that begin a band. */
        val rowStarts: List<Int> get() = rowPages.drop(1).mapNotNull { it.firstOrNull() }
        val colStarts: List<Int> get() = colBands.drop(1).mapNotNull { it.firstOrNull() }
        /** The printed number of the page holding row page [i] and column band [j], in Excel's page order. */
        fun pageNumber(i: Int, j: Int, overThenDown: Boolean): Int = if (overThenDown) i * colBands.size + j + 1 else j * rowPages.size + i + 1
    }

    /** A column's width on paper: the default [BASE_CELL_W] as the sheet widens or narrows it. */
    fun colW(sheet: Sheet, c: Int): Float = BASE_CELL_W * sheet.colWidthFactor(c)

    /** A row's height on paper: the default [CELL_H] scaled as the grid scales it. */
    fun rowHeight(sheet: Sheet, r: Int): Float = CELL_H * sheet.rowHeightFactor(r)

    /** The shown rows (or columns) of a print-title range. */
    fun titles(range: IntRange?, hidden: Set<Int>): List<Int> = range?.filter { it !in hidden }.orEmpty()

    /**
     * Rows and columns to print: the print area when set, else the used range — the cells, and the cells the pictures
     * and shapes cover; null for an empty sheet.
     */
    fun printRange(sheet: Sheet): Pair<IntRange, IntRange>? {
        sheet.printArea?.let { return (it.r1..it.r2) to (it.c1..it.c2) }
        var maxRow = sheet.cells.keys.maxOfOrNull { (it / 1000L).toInt() } ?: -1
        var maxCol = sheet.cells.keys.maxOfOrNull { (it % 1000L).toInt() } ?: -1
        for (img in sheet.images + sheet.shapes) {
            // The picture's (or shape's) far corner, walked out from its anchor through the columns and rows it spans.
            var c = img.col; var x = img.dxPt * PICTURE_SCALE + img.widthPt * PICTURE_SCALE
            while (c < Sheet.MAX_COLS - 1 && x > BASE_CELL_W * sheet.colWidthFactor(c)) { x -= BASE_CELL_W * sheet.colWidthFactor(c); c++ }
            var r = img.row; var y = img.dyPt * PICTURE_SCALE + img.heightPt * PICTURE_SCALE
            while (r < MAX_PICTURE_ROW && y > rowHeight(sheet, r)) { y -= rowHeight(sheet, r); r++ }
            maxRow = maxOf(maxRow, r); maxCol = maxOf(maxCol, c)
        }
        if (maxRow < 0 || maxCol < 0) return null
        return (0..maxRow) to (0..maxCol)
    }

    /** [sheet]'s printout over its print range (as [pr] sets it up, the sheet's own by default), or null for an empty sheet. */
    fun layout(sheet: Sheet, pr: SheetPrint = sheet.print): Layout? = printRange(sheet)?.let { (rows, cols) -> layout(sheet, rows, cols, pr) }

    /**
     * How [sheet] prints [rows] × [cols]: at its scale, or — fitting so many pages wide and tall — at the largest scale
     * (down to Excel's 10 %) at which its columns and rows go onto that many pages.
     */
    fun layout(sheet: Sheet, rows: IntRange, cols: IntRange, pr: SheetPrint = sheet.print): Layout {
        val pageW = if (sheet.landscape) sheet.paper.heightPt else sheet.paper.widthPt
        val pageH = if (sheet.landscape) sheet.paper.widthPt else sheet.paper.heightPt
        val roomW = (pageW - pr.marginLeft - pr.marginRight).coerceAtLeast(36f)
        val roomH = (pageH - pr.marginTop - pr.marginBottom).coerceAtLeast(36f)
        fun at(scale: Float): Layout {
            val (pages, bands) = paginate(sheet, rows, cols, roomW / scale, roomH / scale, pr)
            return Layout(rows, cols, scale, pages, bands, pageW, pageH)
        }
        if (!pr.fits) return at((pr.scale / 100f).coerceIn(MIN_SCALE, 4f))
        val wide = pr.fitWidth ?: 0; val tall = pr.fitHeight ?: 0
        fun fits(p: Layout) = (wide <= 0 || p.colBands.size <= wide) && (tall <= 0 || p.rowPages.size <= tall)
        // A first guess from the whole range's size, then smaller until it fits.
        val hidden = sheet.invisibleRows(); val hiddenCols = sheet.invisibleCols()
        val totalW = (if (pr.headings) HDR_W else 0f) + cols.filter { it !in hiddenCols }.sumOf { colW(sheet, it).toDouble() }.toFloat()
        val totalH = (if (pr.headings) CELL_H else 0f) + rows.filter { it !in hidden }.sumOf { rowHeight(sheet, it).toDouble() }.toFloat()
        var scale = 1f
        if (wide > 0 && totalW > 0f) scale = minOf(scale, wide * roomW / totalW)
        if (tall > 0 && totalH > 0f) scale = minOf(scale, tall * roomH / totalH)
        scale = scale.coerceIn(MIN_SCALE, 1f)
        var p = at(scale)
        var guard = 0
        while (!fits(p) && scale > MIN_SCALE && guard++ < 60) { scale = (scale * 0.97f).coerceAtLeast(MIN_SCALE); p = at(scale) }
        return p
    }

    /**
     * [rows] and [cols] cut into pages [roomW] × [roomH] (unscaled points): bands of columns and pages of rows, each
     * leaving room for the headings when they print and for the title rows or columns on a page past them; a manual break
     * starts a page (unless the sheet fits to pages).
     */
    fun paginate(sheet: Sheet, rows: IntRange, cols: IntRange, roomW: Float, roomH: Float, pr: SheetPrint = sheet.print): Pair<List<List<Int>>, List<List<Int>>> {
        val hidden = sheet.invisibleRows(); val hiddenCols = sheet.invisibleCols()
        val hdrW = if (pr.headings) HDR_W else 0f; val hdrH = if (pr.headings) CELL_H else 0f
        val titleRows = titles(pr.titleRows, hidden); val titleCols = titles(pr.titleCols, hiddenCols)
        val titleH = titleRows.sumOf { rowHeight(sheet, it).toDouble() }.toFloat()
        val titleW = titleCols.sumOf { colW(sheet, it).toDouble() }.toFloat()
        val rowBreaks = if (pr.fits) emptySet() else pr.rowBreaks.toHashSet()
        val colBreaks = if (pr.fits) emptySet() else pr.colBreaks.toHashSet()
        // Columns: as many as fit across (a column wider than the page gets a band of its own), a break starting a band.
        val bands = ArrayList<List<Int>>()
        var band = ArrayList<Int>(); var width = 0f
        fun roomAcross(first: Int?) = roomW - hdrW - (if (titleCols.isNotEmpty() && first != null && first > titleCols.last()) titleW else 0f)
        for (c in cols) {
            if (c in hiddenCols) continue
            val w = colW(sheet, c)
            if (band.isNotEmpty() && (width + w > roomAcross(band.first()) || c in colBreaks)) { bands.add(band); band = ArrayList(); width = 0f }
            band.add(c); width += w
        }
        if (band.isNotEmpty() || bands.isEmpty()) bands.add(band)
        // Rows: as many as their heights allow (a row taller than a page gets one to itself), a break starting a page.
        val pages = ArrayList<List<Int>>()
        var page = ArrayList<Int>(); var used = 0f
        fun roomDown(first: Int?) = roomH - hdrH - (if (titleRows.isNotEmpty() && first != null && first > titleRows.last()) titleH else 0f)
        for (r in rows) {
            if (r in hidden) continue
            val h = rowHeight(sheet, r).coerceAtMost(roomH)
            if (page.isNotEmpty() && (used + h > roomDown(page.first()) || r in rowBreaks)) { pages.add(page); page = ArrayList(); used = 0f }
            page.add(r); used += h
        }
        if (page.isNotEmpty()) pages.add(page)
        if (pages.isEmpty()) pages.add(emptyList())
        return pages to bands
    }
}
