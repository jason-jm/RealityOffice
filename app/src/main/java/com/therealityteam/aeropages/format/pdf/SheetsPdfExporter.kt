package com.therealityteam.aeropages.format.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import com.therealityteam.aeropages.format.pdf.PdfFaces.drawPdfText
import java.io.OutputStream

/**
 * Renders a [Workbook] to PDF: each sheet's print area (or its used range) as a grid with
 * row/column headers, cell borders, column widths and row heights — the rows' own or what their
 * content needs, as the grid fits them — paginated across both rows and columns (wide sheets
 * continue on the next page instead of being cut off). Text takes its cell's size, wraps inside a
 * wrapping cell and sits where its vertical alignment puts it. Paper size and orientation come
 * from the sheet's page setup. [display] supplies the evaluated value per (sheetIdx,row,col); each
 * cell's number format is applied here, as the grid applies it.
 */
object SheetsPdfExporter {
    /** The reader's decimal and grouping symbols, as the grid shows the cells (see SheetEditor.symbols). */
    private val symbols: com.therealityteam.aeropages.model.NumberSymbols get() = com.therealityteam.aeropages.model.NumberSymbols.current()


    /** A column of the default width (Excel's 8.43 characters, 64 px) and a row of the default height, in points (SheetPages'). */
    private const val BASE_CELL_W = com.therealityteam.aeropages.model.SheetPages.BASE_CELL_W
    private const val CELL_H = com.therealityteam.aeropages.model.SheetPages.CELL_H
    /** Page text is the cells' own size, as Excel prints it at 100 %. */
    private const val TEXT_SCALE = 1f
    /** A line of the notes listed at the end of a sheet (10 pt text). */
    private const val NOTE_LIST_LINE = 14f
    private const val HDR_W = com.therealityteam.aeropages.model.SheetPages.HDR_W

    /** One sheet's printout: its pages' rows and columns (title rows and columns added where a page needs them) at its scale. */
    private class Plan(val si: Int, val sheet: Sheet, val rows: IntRange, val cols: IntRange, val scale: Float,
                       val rowPages: List<List<Int>>, val colBands: List<List<Int>>, val pageW: Int, val pageH: Int)

    /**
     * Renders [wb] as Excel prints it: each sheet's print area (or used range) on its paper, in its orientation and
     * margins, at its scale or shrunk to fit so many pages, with its title rows and columns on every page, gridlines and
     * row and column headings only when it prints them, centred when it says so, its header and footer ([fileName]
     * for &F) at their distances from the edges.
     */
    fun export(wb: Workbook, display: (sheetIdx: Int, row: Int, col: Int) -> String, out: OutputStream, fileName: String = "") {
        val pdf = PdfDocument()
        val plans = ArrayList<Plan>()
        wb.sheets.forEachIndexed { si, sheet ->
            // A hidden sheet does not print, as Excel leaves it out of the whole workbook.
            if (sheet.hidden) return@forEachIndexed
            val (rows, cols) = printRange(sheet) ?: return@forEachIndexed
            // Rows without a height of their own fit their content, as in the grid.
            if (!sheet.autoRowsReady) com.therealityteam.aeropages.model.RowFit.refresh(sheet, null) { r, c -> display(si, r, c) }
            plans.add(plan(si, sheet, rows, cols))
        }
        // Notes printed at the end of a sheet (Excel's Sheet ▸ Comments ▸ At end of sheet): their pages, laid out first so
        // the page count in headers and footers takes them in.
        val notePages = plans.associateWith { notePages(it) }
        val total = plans.sumOf { it.rowPages.size * it.colBands.size + (notePages[it]?.size ?: 0) }
        var pageNo = 0
        for (pl in plans) {
            val sheet = pl.sheet; val si = pl.si; val pr = sheet.print
            val cond = if (sheet.condFormats.isEmpty()) null else com.therealityteam.aeropages.editor.CondEval(sheet, object : com.therealityteam.aeropages.editor.CondEval.Values {
                override fun raw(row: Int, col: Int): String = display(si, row, col)
                override fun evaluate(formula: String, row: Int, col: Int): String? = com.therealityteam.aeropages.editor.FormulaEngine.evaluateText(sheet, formula, wb.sheets, wb.names, row to col)
            })
            val hidden = sheet.invisibleRows(); val hiddenCols = sheet.invisibleCols()
            val areaW = (pl.pageW - pr.marginLeft - pr.marginRight) / pl.scale
            val areaH = (pl.pageH - pr.marginTop - pr.marginBottom) / pl.scale
            // Pictures decoded once per sheet, placed in page units from the printed range's corner.
            // Draft quality prints no pictures, shapes or shown notes.
            val pictures = if (pr.draft) emptyList() else sheet.images.mapNotNull { img ->
                val bmp = decode(img.data) ?: return@mapNotNull null
                val x = axisOffset(pl.cols.first, img.col, hiddenCols) { colW(sheet, it) } + (if (img.col in hiddenCols) 0f else img.dxPt * PICTURE_SCALE)
                val y = axisOffset(pl.rows.first, img.row, hidden) { rowHeight(sheet, it).coerceAtMost(areaH) } + (if (img.row in hidden) 0f else img.dyPt * PICTURE_SCALE)
                Placed(img, bmp, android.graphics.RectF(x, y, x + img.widthPt * PICTURE_SCALE, y + img.heightPt * PICTURE_SCALE))
            }
            val shapes = if (pr.draft) emptyList() else sheet.shapes.map { sh ->
                val x = axisOffset(pl.cols.first, sh.col, hiddenCols) { colW(sheet, it) } + (if (sh.col in hiddenCols) 0f else sh.dxPt * PICTURE_SCALE)
                val y = axisOffset(pl.rows.first, sh.row, hidden) { rowHeight(sheet, it).coerceAtMost(areaH) } + (if (sh.row in hidden) 0f else sh.dyPt * PICTURE_SCALE)
                sh to android.graphics.RectF(x, y, x + sh.widthPt * PICTURE_SCALE, y + sh.heightPt * PICTURE_SCALE)
            }
            // Notes kept shown beside their cells, printed so when the sheet prints its notes as displayed: corners in page units.
            val notes = if (pr.comments != com.therealityteam.aeropages.model.NotePrint.AS_DISPLAYED || pr.draft) emptyList()
                else sheet.cells.entries.filter { it.value.comment != null && it.value.commentShown }.mapNotNull { (k, cell) ->
                    val r = (k / 1000L).toInt(); val col = (k % 1000L).toInt()
                    if (r in hidden || col in hiddenCols) return@mapNotNull null
                    val x = axisOffset(pl.cols.first, col + 1, hiddenCols) { colW(sheet, it) }
                    val y = axisOffset(pl.rows.first, r, hidden) { rowHeight(sheet, it).coerceAtMost(areaH) }
                    Triple(cell, x, y)
                }
            val titleRows = titles(pr.titleRows, hidden); val titleCols = titles(pr.titleCols, hiddenCols)
            // Excel's page order: down the rows and then over (its default), or across and then down.
            val over = pr.overThenDown
            for (outer in if (over) pl.rowPages.indices else pl.colBands.indices) {
                for (inner in if (over) pl.colBands.indices else pl.rowPages.indices) {
                    val pageRows = pl.rowPages[if (over) outer else inner]; val band = pl.colBands[if (over) inner else outer]
                    pageNo++
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pl.pageW, pl.pageH, pageNo).create())
                    val c = page.canvas
                    // Title rows and columns on a page that does not reach them.
                    val withRows = if (titleRows.isNotEmpty() && pageRows.first() > titleRows.last()) titleRows + pageRows else pageRows
                    val withCols = if (titleCols.isNotEmpty() && band.isNotEmpty() && band.first() > titleCols.last()) titleCols + band else band
                    val hdrW = if (pr.headings) HDR_W else 0f; val hdrH = if (pr.headings) CELL_H else 0f
                    val usedW = hdrW + withCols.sumOf { colW(sheet, it).toDouble() }.toFloat()
                    val usedH = hdrH + withRows.sumOf { rowHeight(sheet, it).coerceAtMost(areaH).toDouble() }.toFloat()
                    val offX = if (pr.centerH) ((areaW - usedW) / 2f).coerceAtLeast(0f) else 0f
                    val offY = if (pr.centerV) ((areaH - usedH) / 2f).coerceAtLeast(0f) else 0f
                    // Inside the margins, at the sheet's scale, centred where it says.
                    c.withTranslation(pr.marginLeft, pr.marginTop) {
                        withScale(pl.scale, pl.scale) {
                            withTranslation(offX, offY) {
                                val body = drawSheetPage(this, sheet, si, display, withRows, withCols, areaW - offX, areaH - offY, cond, pr.headings, pr.gridlines && !pr.draft,
                                    withRows.size - pageRows.size, withCols.size - band.size, Ink(pr.blackAndWhite, pr.errors))
                                if ((pictures.isNotEmpty() || shapes.isNotEmpty() || notes.isNotEmpty()) && pageRows.isNotEmpty() && band.isNotEmpty()) {
                                    // The page shows the stretch of the sheet from its first row and column on.
                                    val ox = axisOffset(pl.cols.first, band.first(), hiddenCols) { colW(sheet, it) }
                                    val oy = axisOffset(pl.rows.first, pageRows.first(), hidden) { rowHeight(sheet, it).coerceAtMost(areaH) }
                                    drawPictures(this, pictures, body, body.left - ox, body.top - oy, grey = pr.blackAndWhite)
                                    drawShapes(this, shapes, body, body.left - ox, body.top - oy)
                                    // A note's box may reach past the printed cells: it is clipped to the page's printable area instead.
                                    drawNotes(this, notes, android.graphics.RectF(body.left, body.top, areaW - offX, areaH - offY), body.left - ox, body.top - oy)
                                }
                            }
                        }
                    }
                    drawHeaderFooter(c, pr, pl.pageW, pl.pageH, pageNo, total, sheet.name, fileName)
                    pdf.finishPage(page)
                }
            }
            pictures.forEach { it.bitmap.recycle() }
            // At end of sheet: the notes listed on pages of their own, with the sheet's paper, margins, header and footer.
            for (lines in notePages[pl].orEmpty()) {
                pageNo++
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pl.pageW, pl.pageH, pageNo).create())
                val c = page.canvas
                var y = pr.marginTop
                for ((text, bold) in lines) {
                    val paint = if (bold) noteListBold else noteListText
                    if (text.isNotEmpty()) c.drawPdfText(text, pr.marginLeft, y - paint.ascent(), paint)
                    y += NOTE_LIST_LINE
                }
                drawHeaderFooter(c, pr, pl.pageW, pl.pageH, pageNo, total, sheet.name, fileName)
                pdf.finishPage(page)
            }
        }
        if (pageNo == 0) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()); pdf.finishPage(page)
        }
        pdf.writeTo(out)
        pdf.close()
    }

    private val noteListText = PdfFaces.paint().apply { textSize = 10f; color = 0xFF1A1A1A.toInt() }
    private val noteListBold = PdfFaces.paint().apply { textSize = 10f; color = 0xFF1A1A1A.toInt(); isFakeBoldText = true }
    private val noteText = PdfFaces.paint().apply { textSize = 9f; color = 0xFF1F1F1F.toInt() }
    private val noteBold = PdfFaces.paint().apply { textSize = 9f; color = 0xFF1F1F1F.toInt(); isFakeBoldText = true }

    /**
     * The pages listing a sheet's notes when it prints them at the end (Excel's way: "Cell: A2", "Comment: Ann:", the note,
     * a blank line between them), row by row: each page's lines (text, bold), wrapped to the space inside the margins.
     */
    private fun notePages(pl: Plan): List<List<Pair<String, Boolean>>> {
        val sheet = pl.sheet; val pr = sheet.print
        if (pr.comments != com.therealityteam.aeropages.model.NotePrint.AT_END) return emptyList()
        val notes = sheet.cells.entries.filter { it.value.comment != null }.sortedWith(compareBy({ it.key / 1000L }, { it.key % 1000L }))
        if (notes.isEmpty()) return emptyList()
        val width = pl.pageW - pr.marginLeft - pr.marginRight
        val perPage = ((pl.pageH - pr.marginTop - pr.marginBottom) / NOTE_LIST_LINE).toInt().coerceAtLeast(1)
        val lines = ArrayList<Pair<String, Boolean>>()
        for ((k, cell) in notes) {
            if (lines.isNotEmpty()) lines.add("" to false)
            val ref = Workbook.colLabel((k % 1000L).toInt()) + ((k / 1000L).toInt() + 1)
            lines.add(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.NOTE_PRINT_CELL, ref) to true)
            val first = cell.commentAuthor?.let { "$it:" } ?: cell.comment.orEmpty().substringBefore('\n')
            lines.add(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.NOTE_PRINT_COMMENT, first) to true)
            val body = if (cell.commentAuthor != null) cell.comment.orEmpty() else cell.comment.orEmpty().substringAfter('\n', "")
            if (body.isNotEmpty()) wrap(body, noteListText, width).forEach { lines.add(it to false) }
        }
        return lines.chunked(perPage)
    }

    /** [text]'s lines in [width] with [paint]: its own line breaks, then breaking at a space where one fits. */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> = text.split('\n').flatMap { para ->
        if (para.isEmpty()) return@flatMap listOf("")
        val out = ArrayList<String>(); var rest = para
        while (rest.isNotEmpty()) {
            var n = paint.breakText(rest, true, width, null).coerceAtLeast(1)
            if (n < rest.length) rest.lastIndexOf(' ', n).takeIf { it > 0 }?.let { n = it + 1 }
            out.add(rest.substring(0, n).trimEnd()); rest = rest.substring(n)
        }
        out
    }

    /**
     * Notes printed as displayed: each Excel's pale yellow box — 108 pt wide, at least 59.25 pt tall, the author in bold and
     * then the note — 10.5 pt right of its cell and 7.5 pt above it, a line from the cell's corner; clipped to [area] (the printable page).
     */
    private fun drawNotes(c: Canvas, notes: List<Triple<com.therealityteam.aeropages.model.Cell, Float, Float>>, area: android.graphics.RectF, dx: Float, dy: Float) {
        if (notes.isEmpty()) return
        val fill = Paint().apply { color = 0xFFFFFFE1.toInt() }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x991F1F1F.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.75f }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F1F1F.toInt(); strokeWidth = 0.75f }
        c.withClip(area) {
            for ((cell, x, y) in notes) {
                val cx = x + dx; val cy = y + dy
                val lines = (cell.commentAuthor?.let { listOf("$it:" to true) } ?: emptyList()) +
                    wrap(cell.comment.orEmpty(), noteText, 102f).map { it to false }
                val left = cx + 10.5f; val top = cy - 7.5f
                val h = maxOf(59.25f, lines.size * 11.25f + 6f)
                drawLine(cx, cy, left, top + 2f, line)
                drawRect(left, top, left + 108f, top + h, fill)
                drawRect(left, top, left + 108f, top + h, edge)
                var ty = top + 3f
                for ((t, bold) in lines) {
                    val p = if (bold) noteBold else noteText
                    drawPdfText(t, left + 3f, ty - p.ascent(), p)
                    ty += 11.25f
                    if (ty > top + h - 3f) break
                }
            }
        }
    }

    /** A column's width on paper: the default [BASE_CELL_W] as the sheet widens or narrows it. */
    private fun colW(sheet: Sheet, c: Int): Float = com.therealityteam.aeropages.model.SheetPages.colW(sheet, c)

    /** The shown rows (or columns) of a print-title range. */
    private fun titles(range: IntRange?, hidden: Set<Int>): List<Int> = com.therealityteam.aeropages.model.SheetPages.titles(range, hidden)

    /** How [sheet] prints over its pages (SheetPages: its scale, its pages of rows and bands of columns, its manual breaks). */
    private fun plan(si: Int, sheet: Sheet, rows: IntRange, cols: IntRange): Plan {
        val l = com.therealityteam.aeropages.model.SheetPages.layout(sheet, rows, cols)
        return Plan(si, sheet, rows, cols, l.scale, l.rowPages, l.colBands, l.pageW, l.pageH)
    }

    /** The header's and footer's left, centre and right parts, their fields filled in, at their distances from the page's edges. */
    private fun drawHeaderFooter(c: Canvas, pr: com.therealityteam.aeropages.model.SheetPrint, pageW: Int, pageH: Int, page: Int, pages: Int, sheet: String, file: String) {
        if (pr.header.all { it.isEmpty() } && pr.footer.all { it.isEmpty() }) return
        val paint = PdfFaces.paint().apply { textSize = 10f; color = 0xFF1A1A1A.toInt() }
        fun line(parts: List<String>, baseline: Float) {
            parts.forEachIndexed { i, part ->
                if (part.isEmpty()) return@forEachIndexed
                val text = com.therealityteam.aeropages.model.SheetPrint.fill(part, page, pages, sheet, file).replace('\n', ' ')
                val w = paint.measureText(text)
                val x = when (i) { 0 -> pr.marginLeft; 1 -> (pageW - w) / 2f; else -> pageW - pr.marginRight - w }
                c.drawPdfText(text, x, baseline, paint)
            }
        }
        line(pr.header, pr.marginHeader + 9f)
        line(pr.footer, pageH - pr.marginFooter)
    }

    /** Rows and columns to print (SheetPages.printRange); null for an empty sheet. */
    private fun printRange(sheet: Sheet): Pair<IntRange, IntRange>? = com.therealityteam.aeropages.model.SheetPages.printRange(sheet)

    /** Page points per picture point: the page's rows are 18 pt for the grid's 15. */
    private const val PICTURE_SCALE = com.therealityteam.aeropages.model.SheetPages.PICTURE_SCALE

    /** A picture ready to print: its bitmap and its box in page units from the printed range's corner. */
    private class Placed(val image: com.therealityteam.aeropages.model.SheetImage, val bitmap: android.graphics.Bitmap, val box: android.graphics.RectF)

    /** Where [index] starts along an axis, from where [from] starts: the shown lines' sizes between them (negative before [from]). */
    private inline fun axisOffset(from: Int, index: Int, skip: Set<Int>, size: (Int) -> Float): Float {
        var acc = 0f
        if (index >= from) { for (i in from until index) if (i !in skip) acc += size(i) }
        else for (i in index until from) if (i !in skip) acc -= size(i)
        return acc
    }

    /** The picture file decoded at most 2048 px on its long side (a camera photo would not fit in memory whole); null when it cannot be. */
    private fun decode(data: ByteArray): android.graphics.Bitmap? = runCatching {
        if (com.therealityteam.aeropages.format.VectorPictures.isVector(data)) return@runCatching com.therealityteam.aeropages.format.VectorPictures.rasterize(data, 2048f, 2048f)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** The pictures over a page's cells ([area]), shifted by ([dx], [dy]) from range units to the page and cut at its edges. */
    private fun drawPictures(c: Canvas, pictures: List<Placed>, area: android.graphics.RectF, dx: Float, dy: Float, grey: Boolean = false) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            // Black and white: the picture in greys.
            if (grey) colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        }
        c.withClip(area) {
            for (p in pictures) {
                val box = android.graphics.RectF(p.box.left + dx, p.box.top + dy, p.box.right + dx, p.box.bottom + dy)
                if (!android.graphics.RectF.intersects(box, area)) continue
                val (l, t, r, b) = p.image.visiblePixels(p.bitmap.width, p.bitmap.height)
                p.image.shadow?.let { PictureShadows.draw(c, p.bitmap, android.graphics.Rect(l, t, r, b), box, it, PICTURE_SCALE) }
                c.drawBitmap(p.bitmap, android.graphics.Rect(l, t, r, b), box, paint)
            }
        }
    }

    /** The shapes over a page's cells ([area]), shifted by ([dx], [dy]) and cut at its edges; their text at the cells' text scale. */
    private fun drawShapes(c: Canvas, shapes: List<Pair<com.therealityteam.aeropages.model.SheetShape, android.graphics.RectF>>, area: android.graphics.RectF, dx: Float, dy: Float) {
        c.withClip(area) {
            for ((sh, b) in shapes) {
                val box = android.graphics.RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
                // A line may be flat: test its extent a little widened.
                if (!android.graphics.RectF.intersects(android.graphics.RectF(box.left - 1f, box.top - 1f, box.right + 1f, box.bottom + 1f), area)) continue
                ShapePainter.draw(c, sh.shape, box.left, box.top, box.width(), box.height(), scale = PICTURE_SCALE, textScale = TEXT_SCALE)
            }
        }
    }

    /** A row's height on paper: the default [CELL_H] scaled as the grid scales it (a page holds a row at most). */
    private fun rowHeight(sheet: Sheet, r: Int): Float = com.therealityteam.aeropages.model.SheetPages.rowHeight(sheet, r)

    /** Draws a page of the sheet; returns the box its cells take (where pictures may show). */
    /**
     * Draws a page of the sheet from the canvas's origin, inside [areaW] × [areaH]: the row and column headings when
     * [headings], gridlines when [gridlines], then fills, borders and text; returns the box of its cells past its first
     * [titleRows] rows and [titleCols] columns (the repeated titles), where pictures show.
     */
    private fun drawSheetPage(c: Canvas, sheet: Sheet, si: Int, display: (Int, Int, Int) -> String, pageRows: List<Int>, cols: List<Int>, areaW: Float, areaH: Float,
                              cond: com.therealityteam.aeropages.editor.CondEval? = null, headings: Boolean = true, gridlines: Boolean = true,
                              titleRows: Int = 0, titleCols: Int = 0, ink: Ink = Ink()): android.graphics.RectF {
        val grid = Paint().apply { color = 0xFFBBBBBB.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f }
        val hdrFill = Paint().apply { color = 0xFFEFEFEF.toInt() }
        val hdrText = PdfFaces.paint().apply { textSize = 8f; color = 0xFF666666.toInt() }
        val cellText = PdfFaces.paint().apply { textSize = 9f; color = 0xFF1A1A1A.toInt() }
        val linkText = PdfFaces.paint().apply { textSize = 9f; color = 0xFF0563C1.toInt(); isUnderlineText = true }

        fun colW(cc: Int) = BASE_CELL_W * sheet.colWidthFactor(cc)
        val hdrW = if (headings) HDR_W else 0f
        // Column header band (when the headings print).
        var x = hdrW
        var y = 0f
        if (headings) {
            c.drawRect(0f, y, x, y + CELL_H, hdrFill); c.drawRect(0f, y, x, y + CELL_H, grid)
            for (cc in cols) {
                val wCell = colW(cc)
                if (x + wCell > areaW + 0.5f) break
                c.drawRect(x, y, x + wCell, y + CELL_H, hdrFill); c.drawRect(x, y, x + wCell, y + CELL_H, grid)
                c.drawText(Workbook.colLabel(cc), x + wCell / 2f - 4f, y + CELL_H - 4f, hdrText)
                x += wCell
            }
            y += CELL_H
        }
        // Rows: fills and gridlines for the whole page first, then the cell borders, then the text —
        // a row's gridlines must not cover the border above them, and text runs on over its neighbours.
        val laidOut = ArrayList<Triple<Float, Float, List<Slot>>>()
        val bottom = areaH
        var bodyTop = y
        for ((ri, r) in pageRows.withIndex()) {
            if (ri == titleRows) bodyTop = y
            x = 0f
            val h = rowHeight(sheet, r).coerceAtMost(bottom - y)
            if (h <= 0f) break
            if (headings) {
                c.drawRect(x, y, x + HDR_W, y + h, hdrFill); c.drawRect(x, y, x + HDR_W, y + h, grid)
                c.drawText("${r + 1}", x + 6f, y + h / 2f + 3f, hdrText)   // the row number sits in the middle, as Excel prints it
            }
            x += hdrW
            // The page's columns that fit, then what each cell shows — numbers fitted as the grid
            // fits them (never cut off), long text running on over empty cells to its right.
            val slots = ArrayList<Slot>()
            run {
                var cx = x
                for (cc in cols) {
                    val w = colW(cc)
                    if (cx + w > areaW + 0.5f) break
                    slots.add(slot(sheet, si, r, cc, cx, w, display, cellText, linkText, cond?.look(r, cc), ink))
                    cx += w
                }
            }
            spill(slots)
            // Fills and gridlines (borders and text follow once the page's rows are all laid out).
            for ((i, sl) in slots.withIndex()) {
                // Black and white prints no fills and no data bars.
                if (!ink.blackAndWhite) (sl.look?.style?.fill ?: sl.cell?.format?.highlight)?.let { hl -> c.drawRect(sl.x, y, sl.x + sl.w, y + h, Paint().apply { color = hl.toInt() }) }
                // A conditional format's data bar (from the cell's left, fading to white, edged in its colour) and icon (at its left).
                if (!ink.blackAndWhite) sl.look?.bar?.let { bar ->
                    val bw = (sl.w - 3f) * bar.fraction
                    if (bw > 0f) {
                        val col = bar.color.toInt()
                        val fade = android.graphics.Color.argb(38, android.graphics.Color.red(col), android.graphics.Color.green(col), android.graphics.Color.blue(col))
                        c.drawRect(sl.x + 1.5f, y + 2f, sl.x + 1.5f + bw, y + h - 2f, Paint().apply {
                            shader = android.graphics.LinearGradient(sl.x + 1.5f, 0f, sl.x + 1.5f + bw, 0f, col, fade, android.graphics.Shader.TileMode.CLAMP)
                        })
                        c.drawRect(sl.x + 1.5f, y + 2f, sl.x + 1.5f + bw, y + h - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = 0.5f })
                    }
                }
                sl.look?.icon?.let { icon ->
                    val size = minOf(h - 4f, 10f).coerceAtLeast(1f)
                    CondIcons.draw(c, icon.set, icon.index, sl.x + 1.5f, y + (h - size) / 2f, size)
                }
                if (gridlines) {
                    c.drawLine(sl.x, y, sl.x + sl.w, y, grid)
                    c.drawLine(sl.x, y + h, sl.x + sl.w, y + h, grid)
                    if (!sl.covered && !sl.openLeft) c.drawLine(sl.x, y, sl.x, y + h, grid)
                    if (slots.getOrNull(i + 1)?.let { it.covered || it.openLeft } != true) c.drawLine(sl.x + sl.w, y, sl.x + sl.w, y + h, grid)
                }
            }
            laidOut.add(Triple(y, h, slots))
            y += h
        }
        // Cell borders in their line styles, over every gridline.
        for ((rowY, h, slots) in laidOut) for (sl in slots) sl.cell?.let { drawBorders(c, it, sl.x, rowY, sl.w, h, ink.blackAndWhite) }
        for ((rowY, h, slots) in laidOut) for (sl in slots) if (sl.text.isNotBlank()) drawSlot(c, sl, rowY, h)
        if (titleRows >= pageRows.size) bodyTop = y
        val right = laidOut.firstOrNull()?.third?.lastOrNull()?.let { it.x + it.w } ?: hdrW
        // The body's left: past the title columns.
        val bodyLeft = hdrW + cols.take(titleCols).sumOf { colW(it).toDouble() }.toFloat()
        return android.graphics.RectF(bodyLeft, bodyTop, right, y)
    }

    /** [cell]'s borders around the box at ([x], [y]) [w] wide and [h] tall, each side in its line style (a double one as two lines). */
    private fun drawBorders(c: Canvas, cell: com.therealityteam.aeropages.model.Cell, x: Float, y: Float, w: Float, h: Float, black: Boolean = false) {
        fun side(line: com.therealityteam.aeropages.model.BorderLine?, x1: Float, y1: Float, x2: Float, y2: Float, ix: Float, iy: Float) {
            line ?: return
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (if (black) 0xFF000000L else line.color ?: 0xFF000000L).toInt()
                style = Paint.Style.STROKE
                strokeWidth = line.style.width * 0.75f
                line.style.dash?.let { d -> pathEffect = android.graphics.DashPathEffect(FloatArray(d.size) { d[it] * 0.75f }, 0f) }
            }
            c.drawLine(x1, y1, x2, y2, p)
            if (line.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) c.drawLine(x1 + ix * 1.8f, y1 + iy * 1.8f, x2 + ix * 1.8f, y2 + iy * 1.8f, p)
        }
        side(cell.borderTop, x, y, x + w, y, 0f, 1f)
        side(cell.borderBottom, x, y + h, x + w, y + h, 0f, -1f)
        side(cell.borderLeft, x, y, x, y + h, 1f, 0f)
        side(cell.borderRight, x + w, y, x + w, y + h, -1f, 0f)
    }

    /** One cell of a page row: what it shows and how, and how far its text may run on ([reach]). */
    /** How a page's cells are inked: [blackAndWhite] (no fills, black text and borders) and what shows for an error ([errors]). */
    private data class Ink(val blackAndWhite: Boolean = false, val errors: com.therealityteam.aeropages.model.ErrorPrint = com.therealityteam.aeropages.model.ErrorPrint.DISPLAYED)

    private class Slot(
        val cell: com.therealityteam.aeropages.model.Cell?, val x: Float, val w: Float, val text: String,
        val align: com.therealityteam.aeropages.model.TextAlign, val paint: Paint, val rich: List<Pair<Paint, String>>?,
        val runsOn: Boolean, val merged: Boolean,
        /** Right-aligned text, which runs on to the left over empty cells (Excel's way). */
        val runsLeft: Boolean = false,
        /** What its conditional formats do to it (a fill, the text's look, a bar, an icon). */
        val look: com.therealityteam.aeropages.editor.CondEval.Look? = null,
    ) {
        /** Room at the left an icon takes. */
        val lead: Float get() = if (look?.icon != null) 12f else 0f
        /** Text wraps inside the cell. */
        val wrap: Boolean get() = cell?.wrap == true
        /** Right edge the text may reach: the cell's own, or the last empty cell its text covers. */
        var reach = x + w
        /** Left edge the text may reach: the cell's own, or the first empty cell its right-aligned text covers. */
        var start = x
        /** Empty, under a neighbour's text. */
        var covered = false
        /** Text runs across its left edge (no gridline there). */
        var openLeft = false
    }

    private fun slot(
        sheet: Sheet, si: Int, r: Int, cc: Int, x: Float, w: Float, display: (Int, Int, Int) -> String, cellText: Paint, linkText: Paint,
        look: com.therealityteam.aeropages.editor.CondEval.Look? = null, ink: Ink = Ink(),
    ): Slot {
        // A table's look prints as the cell's own formatting would.
        val cell = com.therealityteam.aeropages.model.TableLooks.styled(sheet, r, cc, sheet.cell(r, cc))
        val base = if (cell?.link != null) linkText else cellText
        // Merged regions: only the anchor shows its text; covered cells stay blank.
        if (sheet.isMergeCovered(r, cc)) return Slot(cell, x, w, "", com.therealityteam.aeropages.model.TextAlign.LEFT, base, null, runsOn = false, merged = true)
        // The cell's number format (or format code, with its section's colour) as the grid shows it.
        val raw = display(si, r, cc)
        val shown = com.therealityteam.aeropages.editor.NumberFormatting.shown(raw, cell, symbols, com.therealityteam.aeropages.model.FormatCode.Lang.current())
        val general = com.therealityteam.aeropages.editor.NumberFormatting.generalAlign(raw, cell)
        // The cell's alignment, or General's: numbers right, TRUE / FALSE and errors centred, text left.
        val align = cell?.align ?: general
        val paint = (cell?.let { runPaint(base, it.format) } ?: base).let { pp -> shown.color?.let { Paint(pp).apply { color = it.toInt() } } ?: pp }
            // A conditional format's text colour and traits win over the cell's and its format's.
            .let { pp -> look?.style?.let { cs -> conditional(pp, cell?.format, cs) } ?: pp }
            // Black and white: whatever colour the cell, its format or a rule gives, the text prints black (a link keeps its underline).
            .let { pp -> if (ink.blackAndWhite) Paint(pp).apply { color = 0xFF000000.toInt() } else pp }
        var text = if (look?.hideValue == true) "" else shown.text
        // Cell errors as: an error's text replaced (blank, "--", "#N/A").
        ink.errors.text?.let { if (com.therealityteam.aeropages.editor.FormulaEngine.isError(raw) || com.therealityteam.aeropages.editor.FormulaEngine.isError(text)) text = it }
        val room = w - 6f
        if (general == com.therealityteam.aeropages.model.TextAlign.RIGHT && paint.measureText(text) > room) {
            // A number that does not fit: General loses decimals / goes to E notation, a formatted one shows ####.
            val plain = cell == null || (cell.formatCode == null && cell.numberFormat == com.therealityteam.aeropages.model.NumberFormat.GENERAL)
            text = raw.toDoubleOrNull()?.takeIf { plain }?.let { v -> com.therealityteam.aeropages.editor.NumberFormatting.fitGeneral(v, symbols) { paint.measureText(it) <= room } }
                ?: "#".repeat((room / paint.measureText("#")).toInt().coerceAtLeast(1))
        }
        val rich = cell?.richRuns()?.takeIf { text == cell.text }?.map { run ->
            val rp = runPaint(base, run.format).let { pp -> if (ink.blackAndWhite) Paint(pp).apply { color = 0xFF000000.toInt() } else pp }
            PdfFaces.forText(rp, run.text) to run.text.replace('\n', ' ')
        }
        val merged = sheet.mergeAt(r, cc) != null
        val runsOn = general == com.therealityteam.aeropages.model.TextAlign.LEFT && align == com.therealityteam.aeropages.model.TextAlign.LEFT &&
            cell?.wrap != true && cell?.turned != true && cell?.spread == null && cell?.shrink != true && !merged
        val runsLeft = general == com.therealityteam.aeropages.model.TextAlign.LEFT && align == com.therealityteam.aeropages.model.TextAlign.RIGHT &&
            cell?.wrap != true && cell?.turned != true && cell?.spread == null && cell?.shrink != true && !merged
        return Slot(cell, x, w, text, align, PdfFaces.forText(paint, text), rich.takeIf { look?.style?.let { it.font == null && it.bold == null && it.italic == null } != false }, runsOn, merged, runsLeft = runsLeft, look = look)
    }

    /**
     * Long left-aligned text runs on over the empty cells to its right until it fits, or a cell has something or is
     * merged; right-aligned text likewise to its left (as Excel draws both).
     */
    private fun spill(slots: List<Slot>) {
        var i = 0
        while (i < slots.size) {
            val sl = slots[i]
            val width = sl.rich?.sumOf { (p, t) -> p.measureText(t).toDouble() }?.toFloat() ?: sl.paint.measureText(sl.text)
            // Center Across Selection: over the empty cells after it that are set the same way.
            if (sl.cell?.spread == com.therealityteam.aeropages.model.CellSpread.CENTER_ACROSS && sl.text.isNotBlank() && !sl.merged) {
                var j = i + 1
                while (j < slots.size && slots[j].text.isEmpty() && !slots[j].merged && slots[j].cell?.spread == com.therealityteam.aeropages.model.CellSpread.CENTER_ACROSS) {
                    slots[j].covered = true
                    sl.reach = slots[j].x + slots[j].w
                    j++
                }
                i = j; continue
            }
            if (sl.runsLeft && sl.text.isNotBlank() && width > sl.w - 6f) {
                var k = i - 1
                while (k >= 0 && slots[k].text.isEmpty() && !slots[k].merged && !slots[k].covered && sl.reach - 3f - width < sl.start + 3f) {
                    slots[k + 1].openLeft = true
                    slots[k].covered = true
                    sl.start = slots[k].x
                    k--
                }
                // The leftmost cell covered keeps its own left gridline.
                if (k + 1 < i) slots[k + 1].covered = false.also { slots[k + 1].openLeft = false }
                i++; continue
            }
            if (!sl.runsOn || sl.text.isBlank() || width <= sl.w - 6f) { i++; continue }
            var j = i + 1
            // The same margins the text is cut at ([drawSlot]: 3 points in from each edge).
            while (j < slots.size && slots[j].text.isEmpty() && !slots[j].merged && sl.x + 3f + width > sl.reach - 3f) {
                slots[j].covered = true
                sl.reach = slots[j].x + slots[j].w
                j++
            }
            i = j
        }
    }

    private fun drawSlot(c: Canvas, sl: Slot, y: Float, h: Float) {
        // Indent: levels of three spaces from the aligned edge (the right one for right-aligned text).
        val indent = (sl.cell?.indent ?: 0) * com.therealityteam.aeropages.model.Cell.INDENT_PT
        val limit = sl.reach - 3f - (if (sl.align == com.therealityteam.aeropages.model.TextAlign.RIGHT) indent else 0f)
        val left = sl.start + 3f + sl.lead + (if (sl.align == com.therealityteam.aeropages.model.TextAlign.LEFT) indent else 0f)
        fun startX(width: Float) = when (sl.align) {
            com.therealityteam.aeropages.model.TextAlign.RIGHT -> maxOf(left, limit - width)
            com.therealityteam.aeropages.model.TextAlign.CENTER -> maxOf(left, sl.x + (sl.reach - sl.x - width) / 2f)
            else -> left
        }
        val vAlign = sl.cell?.vAlign ?: com.therealityteam.aeropages.model.VAlign.MIDDLE
        // Where a block of [lines] lines of [p] starts (its first baseline), by the cell's vertical alignment.
        fun firstBaseline(p: Paint, lines: Int): Float {
            val fm = p.fontMetrics
            val lineH = fm.descent - fm.ascent
            val block = lineH * lines
            val top = when (vAlign) {
                com.therealityteam.aeropages.model.VAlign.TOP -> y + 2f
                com.therealityteam.aeropages.model.VAlign.BOTTOM -> y + h - 3f - block
                else -> y + (h - block) / 2f
            }
            return top - fm.ascent
        }
        // Text stays inside its row (and the cells it runs on over).
        c.withClip(sl.start, y, maxOf(sl.reach, sl.x + sl.w), y + h) {
            val cell = sl.cell
            if (cell != null && cell.stacked) {
                // Vertical Text: one character under another, each centred on the column.
                val chars = com.therealityteam.aeropages.model.Cell.stackedLetters(sl.text)
                val fm = sl.paint.fontMetrics
                var base = firstBaseline(sl.paint, chars.size)
                for (ch in chars) {
                    drawText(ch, sl.x + (sl.w - sl.paint.measureText(ch)) / 2f, base, sl.paint)
                    base += fm.descent - fm.ascent
                }
            } else if (cell != null && cell.rotation != 0) {
                // Turned text: its bounding box placed by the cell's alignment, the line turned about the box's centre.
                val tw = sl.paint.measureText(sl.text)
                val fm = sl.paint.fontMetrics
                val th = fm.descent - fm.ascent
                val a = Math.toRadians(cell.rotation.toDouble())
                val bw = (tw * kotlin.math.abs(kotlin.math.cos(a)) + th * kotlin.math.abs(kotlin.math.sin(a))).toFloat()
                val bh = (tw * kotlin.math.abs(kotlin.math.sin(a)) + th * kotlin.math.abs(kotlin.math.cos(a))).toFloat()
                val cx = when (sl.align) {
                    com.therealityteam.aeropages.model.TextAlign.RIGHT -> limit - bw / 2f
                    com.therealityteam.aeropages.model.TextAlign.CENTER -> sl.x + sl.w / 2f
                    else -> left + bw / 2f
                }
                val cy = when (vAlign) {
                    com.therealityteam.aeropages.model.VAlign.TOP -> y + 2f + bh / 2f
                    com.therealityteam.aeropages.model.VAlign.BOTTOM -> y + h - 3f - bh / 2f
                    else -> y + h / 2f
                }
                withRotation(-cell.rotation.toFloat(), cx, cy) { drawText(sl.text, cx - tw / 2f, cy - (fm.ascent + fm.descent) / 2f, sl.paint) }
            } else if (sl.wrap && sl.rich == null) {
                // Wrapping text: word by word (a CJK character at a time) into lines as wide as the cell.
                val lines = wrapLines(sl.text, sl.paint, sl.w - 6f)
                val fm = sl.paint.fontMetrics
                var base = firstBaseline(sl.paint, lines.size)
                for (line in lines) {
                    drawText(line, startX(sl.paint.measureText(line)), base, sl.paint)
                    base += fm.descent - fm.ascent
                }
            } else if (sl.rich != null) {
                // Mixed formatting: one paint per run, laid end to end on one baseline (the largest run's)
                // and cut where the text must end.
                val base = firstBaseline(sl.rich.maxByOrNull { it.first.textSize }?.first ?: sl.paint, 1)
                var tx = startX(sl.rich.sumOf { (p, t) -> p.measureText(t).toDouble() }.toFloat())
                for ((p, whole) in sl.rich) {
                    var t = whole
                    while (t.isNotEmpty() && tx + p.measureText(t) > limit) t = t.dropLast(1)
                    if (t.isEmpty()) break
                    drawText(t, tx, base, p)
                    tx += p.measureText(t)
                }
            } else if (cell != null && (cell.shrink || cell.spread == com.therealityteam.aeropages.model.CellSpread.FILL || cell.spread == com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED)) {
                // Fill repeats the text as often as it fits, Shrink to fit makes the type smaller to fit, Distributed
                // spreads the letters edge to edge.
                val room = (limit - left).coerceAtLeast(1f)
                val w = sl.paint.measureText(sl.text).coerceAtLeast(0.01f)
                when {
                    cell.spread == com.therealityteam.aeropages.model.CellSpread.FILL ->
                        drawText(sl.text.repeat((room / w).toInt().coerceAtLeast(1)), left, firstBaseline(sl.paint, 1), sl.paint)
                    cell.shrink && w > room -> {
                        val small = Paint(sl.paint).apply { textSize *= room / w }
                        drawText(sl.text, left, firstBaseline(small, 1), small)
                    }
                    cell.spread == com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED && w < room -> {
                        val letters = com.therealityteam.aeropages.model.Cell.stackedLetters(sl.text)
                        val gap = if (letters.size > 1) (room - w) / (letters.size - 1) else 0f
                        var tx = if (letters.size > 1) left else left + (room - w) / 2f
                        val base = firstBaseline(sl.paint, 1)
                        for (ch in letters) { drawText(ch, tx, base, sl.paint); tx += sl.paint.measureText(ch) + gap }
                    }
                    else -> drawText(sl.text, startX(w), firstBaseline(sl.paint, 1), sl.paint)
                }
            } else {
                var t = sl.text
                while (t.isNotEmpty() && left + sl.paint.measureText(t) > limit) t = t.dropLast(1)
                drawText(t, startX(sl.paint.measureText(t)), firstBaseline(sl.paint, 1), sl.paint)
            }
        }
    }

    /** [text] broken into lines no wider than [room]: at spaces, or between CJK characters, a line break starting a new one. */
    private fun wrapLines(text: String, p: Paint, room: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = StringBuilder()
            var i = 0
            while (i < para.length) {
                // The next piece: a word with its trailing space, or one CJK character.
                val cjk = com.therealityteam.aeropages.model.PageMetrics.isCjk(para[i])
                var j = i + 1
                if (!cjk) { while (j < para.length && para[j] != ' ' && !com.therealityteam.aeropages.model.PageMetrics.isCjk(para[j])) j++; while (j < para.length && para[j] == ' ') j++ }
                val piece = para.substring(i, j)
                if (line.isNotEmpty() && p.measureText(line.toString() + piece.trimEnd()) > room) { out.add(line.toString().trimEnd()); line = StringBuilder() }
                line.append(piece)
                i = j
            }
            out.add(line.toString().trimEnd())
        }
        return out
    }


    /**
     * [base] with a run's bold / italic (a bold or italic face: PDF pages draw no fake bold) /
     * underline / strike / colour and size (scaled as the page scales the grid) applied.
     */
    /** [p] with a conditional format's text colour, bold, italic, underline and strikethrough where it sets them ([f]: the cell's own). */
    private fun conditional(p: Paint, f: com.therealityteam.aeropages.model.CharacterFormat?, cs: com.therealityteam.aeropages.model.CondStyle): Paint = Paint(p).apply {
        if (cs.bold != null || cs.italic != null) typeface = PdfFaces.of(f?.fontFamily, cs.bold ?: (f?.bold == true), cs.italic ?: (f?.italic == true))
        cs.underline?.let { isUnderlineText = it }
        cs.strike?.let { isStrikeThruText = it }
        cs.font?.let { color = it.toInt() }
    }

    private fun runPaint(base: Paint, f: com.therealityteam.aeropages.model.CharacterFormat): Paint = Paint(base).apply {
        f.fontSizePt?.let { textSize = it * TEXT_SCALE }
        typeface = PdfFaces.of(f.fontFamily, f.bold, f.italic)
        isUnderlineText = f.underline
        isStrikeThruText = f.strikethrough
        f.color?.let { color = it.toInt() }
    }
}
