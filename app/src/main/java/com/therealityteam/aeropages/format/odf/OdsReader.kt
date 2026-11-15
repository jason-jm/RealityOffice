package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetImage
import com.therealityteam.aeropages.model.Workbook
import java.util.Locale

/**
 * OpenDocument Spreadsheet (.ods) reader, the counterpart of [OdsWriter].
 *
 * Cells keep their typed value (`office:value` for numbers, percentages and currency, the text
 * otherwise) and their formula, translated back from the `of:` dialect into the A1 form the
 * editor uses. Formatting comes from the package's cell styles and data styles: font, colour,
 * fill, borders, alignment, wrap and the number format. Merges, hidden rows, column widths, row
 * heights, comments and links are read as well.
 *
 * `number-columns-repeated` / `number-rows-repeated` are capped: producers pad sheets out to the
 * full 1024×1M grid, and only the used range is worth materialising.
 */
object OdsReader {

    private const val REPEAT_CAP = 256
    /** The last row a rule's cells can reach (LibreOffice's and Excel's grid). */
    private const val LAST_ROW = 1_048_575
    private const val MAX_ROWS = 20_000
    private const val DEF_COL_PT = 64f
    private const val DEF_ROW_PT = 15f

    fun read(bytes: ByteArray): Workbook {
        val files = OfficeXml.unzip(bytes)
        val content = OdfNode.parse(files["content.xml"]) ?: return Workbook.blank()
        val stylesPart = OdfNode.parse(files["styles.xml"])
        val styles = OdfStyles.of(content, stylesPart)
        val spreadsheet = content.child("body")?.child("spreadsheet")
        val tables = spreadsheet?.children("table").orEmpty()
        val templates = OdfDataRules.templates(spreadsheet)
        val sheets = tables.map { sheet(it, styles, files, templates).also { s -> readPrint(it, s, styles, stylesPart) } }
        if (sheets.isEmpty()) return Workbook.blank()
        val wb = Workbook(sheets.toMutableList())
        spreadsheet?.let { sp -> com.therealityteam.aeropages.format.ProtectionXml.readOdsSpreadsheet({ sp.attr(it) }, wb) }
        readNames(spreadsheet, wb)
        OdfDataRules.readFilters(spreadsheet, sheets)
        OdsView.read(files["settings.xml"], sheets)
        wb.props = com.therealityteam.aeropages.format.OdfMeta.read(files["meta.xml"])
        return wb
    }

    /** How [sheet] prints: its table style's master page and page layout, and its print range. */
    private fun readPrint(node: OdfNode, sheet: Sheet, styles: OdfStyles, stylesPart: OdfNode?) {
        OdsPrint.read(stylesPart, styles.masterPageOf(node.attr("style-name")), sheet)
        OdsPrint.readPrintRanges(node.attr("print-ranges"))?.let { sheet.printArea = it }
    }

    /** `table:named-range`s as defined names (`$Sheet1.$A$1:.$B$5` → `Sheet1!$A$1:$B$5`). */
    private fun readNames(spreadsheet: OdfNode?, wb: Workbook) {
        spreadsheet?.child("named-expressions")?.children("named-range")?.forEach { nr ->
            val name = nr.attr("name") ?: return@forEach
            val parts = (nr.attr("cell-range-address") ?: return@forEach).split(':')
            val sheetName = OdfDataRules.unquote(parts[0].substringBeforeLast('.', ""))
                .ifEmpty { nr.attr("base-cell-address")?.let { OdfDataRules.unquote(it.substringBeforeLast('.', "")) } ?: "" }
            fun at(p: String) = com.therealityteam.aeropages.model.A1.parse(p.substringAfterLast('.').replace("$", ""))
            val (r1, c1) = at(parts[0]) ?: return@forEach
            val (r2, c2) = parts.getOrNull(1)?.let { at(it) } ?: (r1 to c1)
            if (sheetName.isEmpty()) return@forEach
            wb.names[name] = com.therealityteam.aeropages.model.A1.absoluteRef(sheetName, minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
        }
        // Constants and formulas (`table:named-expression`, `of:=0.08`), in Excel's syntax.
        spreadsheet?.child("named-expressions")?.children("named-expression")?.forEach { ne ->
            val name = ne.attr("name") ?: return@forEach
            val expr = ne.attr("expression")?.takeIf { it.isNotBlank() } ?: return@forEach
            wb.names[name] = fromOdfFormula(expr).removePrefix("=")
        }
    }

    private fun sheet(node: OdfNode, styles: OdfStyles, files: Map<String, ByteArray>, templates: Map<String, OdfDataRules.Template> = emptyMap()): Sheet {
        val sheet = Sheet(name = node.attr("name") ?: "Sheet1")
        com.therealityteam.aeropages.format.ProtectionXml.readOdsTable({ node.attr(it) }, node.child("table-protection")?.let { t -> { name: String -> t.attr(name) } })
            ?.let { sheet.protected = true; sheet.protection = it }
        // Its table style: left out of the tabs (`table:display="false"`), and its tab colour.
        node.attr("style-name")?.let { st ->
            if (styles.tableHidden(st)) sheet.state = com.therealityteam.aeropages.model.SheetState.HIDDEN
            sheet.tabColor = styles.tabColor(st)
        }
        // Charts: embedded objects in table:shapes; ranges into this sheet bind the chart to its block.
        // Pictures there are anchored to the page: placed once the rows' heights are known.
        val pagePictures = ArrayList<SheetImage>()
        val pageShapes = ArrayList<com.therealityteam.aeropages.model.SheetShape>()
        // Each picture's and shape's draw:z-index: they are drawn in that order, whichever cell holds them.
        val zOf = java.util.IdentityHashMap<Any, Int>()
        node.child("shapes")?.children?.forEach { frame ->
            if (frame.name in SHAPE_ELEMENTS || (frame.name == "frame" && frame.child("text-box") != null)) {
                val (shape, box) = OdpReader.readShape(frame, styles)
                val sh = com.therealityteam.aeropages.model.SheetShape(shape, 0, 0, box[0], box[1], box[2], box[3], frame.attr("name").orEmpty())
                pageShapes.add(sh)
                frame.attr("z-index")?.toIntOrNull()?.let { zOf[sh] = it }
                return@forEach
            }
            if (frame.name != "frame") return@forEach
            // A chart first: LibreOffice gives its frame a picture of it too (draw:image beside draw:object).
            frame.child("object")?.attr("href")?.removePrefix("./")?.trimEnd('/')?.let { href ->
                OdfChart.read(files["$href/content.xml"], sheet.name)?.let { sheet.charts.add(it); return@forEach }
            }
            picture(frame, files, styles, 0, 0)?.let { pagePictures.add(it); frame.attr("z-index")?.toIntOrNull()?.let { z -> zOf[it] = z }; return@forEach }
        }

        // Columns in document order (inside header-columns / columns / nested column groups), with
        // widths, hidden columns and the column outline.
        var col = 0
        var titleCols: IntRange? = null
        var titleRows: IntRange? = null
        val openCols = ArrayList<Pair<Int, Boolean>>()
        // The cell style of each column's cells that name none (LibreOffice writes a style its
        // cells share once, on the column).
        val colStyles = HashMap<Int, String>()
        // Manual page breaks, from the rows' and columns' styles.
        val rowBreaks = java.util.TreeSet<Int>(); val colBreaks = java.util.TreeSet<Int>()
        fun columns(container: OdfNode) {
            for (child in container.children) when (child.name) {
                "table-column" -> {
                    val repeat = (child.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, REPEAT_CAP)
                    val width = styles.columnWidth(child.attr("style-name"))
                    val hidden = child.attr("visibility").let { it == "collapse" || it == "filter" }
                    val cellStyle = child.attr("default-cell-style-name")
                    // A manual page break before the column (its style's fo:break-before), on the first of a repeated run.
                    if (styles.breaksPage(child.attr("style-name")) && col in 1 until Sheet.MAX_COLS) colBreaks.add(col)
                    repeat(repeat) {
                        if (col < Sheet.MAX_COLS) {
                            // A column as wide as the default (give or take the lengths' rounding) keeps the default.
                            if (width != null && kotlin.math.abs(width / DEF_COL_PT - 1f) > 0.002f) sheet.colWidths[col] = width / DEF_COL_PT
                            if (hidden) sheet.hiddenCols.add(col)
                            if (cellStyle != null) colStyles[col] = cellStyle
                        }
                        col++
                    }
                }
                // Header columns are the columns repeated on every printed page (Excel's print titles).
                "table-header-columns" -> { val start = col; columns(child); if (col > start && start < Sheet.MAX_COLS) titleCols = start..(col - 1).coerceAtMost(Sheet.MAX_COLS - 1) }
                "table-columns" -> columns(child)
                "table-column-group" -> {
                    openCols.add(col to (child.attr("display") == "false"))
                    columns(child)
                    val (start, collapsed) = openCols.removeAt(openCols.lastIndex)
                    if (col > start && start < Sheet.MAX_COLS) sheet.colGroups.add(com.therealityteam.aeropages.model.RowGroup(start, (col - 1).coerceAtMost(Sheet.MAX_COLS - 1), collapsed))
                }
            }
        }
        columns(node)
        sheet.colGroups.sortWith(compareBy({ it.r1 }, { -it.r2 }))
        sheet.hiddenCols.removeAll(sheet.foldedCols())

        var row = 0
        var maxCol = 0
        var lastUsed = -1   // last row holding content, a size / hidden flag, or an outline group
        // Rows in document order, with the start / end of each (possibly nested) row group marked.
        val items = ArrayList<Any>()
        fun collect(container: OdfNode) {
            for (child in container.children) when (child.name) {
                "table-row" -> items.add(child)
                "table-header-rows" -> { items.add(HeaderStart); collect(child); items.add(HeaderEnd) }
                "table-rows" -> collect(child)
                "table-row-group" -> { items.add(GroupStart(child.attr("display") == "false")); collect(child); items.add(GroupEnd) }
            }
        }
        collect(node)
        val open = ArrayList<Pair<Int, Boolean>>()
        var headerFrom = 0
        // Cells naming a validation rule, as blocks per rule: a repeated row or cell is one block.
        val ruleRuns = HashMap<String, MutableList<IntArray>>()
        for (item in items) {
            if (item is GroupStart) { open.add(row to item.collapsed); continue }
            if (item === HeaderStart) { headerFrom = row; continue }
            if (item === HeaderEnd) { if (row > headerFrom && headerFrom < MAX_ROWS) titleRows = headerFrom..(row - 1).coerceAtMost(MAX_ROWS - 1); continue }
            if (item === GroupEnd) {
                val (start, collapsed) = open.removeAt(open.lastIndex)
                if (row > start) { sheet.rowGroups.add(com.therealityteam.aeropages.model.RowGroup(start, (row - 1).coerceAtMost(MAX_ROWS - 1), collapsed)); lastUsed = maxOf(lastUsed, row) }
                continue
            }
            val r = item as OdfNode
            val rawRepeat = (r.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
            if (templates.isNotEmpty()) {
                val bottom = (row.toLong() + rawRepeat - 1).coerceAtMost(LAST_ROW.toLong()).toInt()
                var cc = 0L
                for (cellNode in r.children) {
                    if (cellNode.name != "table-cell" && cellNode.name != "covered-table-cell") continue
                    val rep = (cellNode.attr("number-columns-repeated")?.toLongOrNull() ?: 1L).coerceAtLeast(1L)
                    val name = cellNode.attr("content-validation-name")
                    if (name != null && cc < Sheet.MAX_COLS && row <= LAST_ROW)
                        ruleRuns.getOrPut(name) { ArrayList() }.add(intArrayOf(row, cc.toInt(), bottom, minOf(cc + rep - 1, Sheet.MAX_COLS - 1L).toInt()))
                    cc += rep
                }
            }
            val repeat = rawRepeat.coerceAtMost(REPEAT_CAP)
            // "filter": filtered out (the filter owns the row); "collapse": hidden by hand or folded.
            val filtered = r.attr("visibility") == "filter"
            val hidden = r.attr("visibility") == "collapse"
            val hiddenSet = if (filtered) sheet.hiddenRows else sheet.manualHiddenRows
            val height = styles.rowHeight(r.attr("style-name"))
            val rowStyle = r.attr("default-cell-style-name")
            if (styles.breaksPage(r.attr("style-name")) && row in 1 until MAX_ROWS) rowBreaks.add(row)
            if (row < MAX_ROWS && cellPictures(r, row, sheet, files, styles, zOf, pageShapes)) lastUsed = maxOf(lastUsed, row)
            // A row is blank without values and without empty cells that have a look of their own
            // (a fill, borders …) — a long run of those is padding, and stays blank.
            val blank = r.children.none { it.name == "table-cell" && (it.attr("value") != null || it.text.isNotBlank() || it.attr("formula") != null) } &&
                (rawRepeat > REPEAT_CAP || r.children.none { formattedEmpty(it, styles) })
            if (blank) {
                // Empty rows only move the cursor — every one of them, so the rows below keep their
                // numbers; a short run keeps its hidden flag / height, a long (trailing) one is skipped.
                if (rawRepeat <= REPEAT_CAP) for (k in 0 until rawRepeat) {
                    val rr = row + k
                    if (rr >= MAX_ROWS) break
                    if (hidden || filtered) { hiddenSet.add(rr); lastUsed = maxOf(lastUsed, rr) }
                    if (height != null) sheet.rowHeights[rr] = height / DEF_ROW_PT
                }
                row = (row.toLong() + rawRepeat).coerceAtMost(MAX_ROWS.toLong()).toInt()
                continue
            }
            repeat(repeat) {
                if (row >= MAX_ROWS) return@repeat
                if (hidden || filtered) hiddenSet.add(row)
                if (height != null) sheet.rowHeights[row] = height / DEF_ROW_PT
                var c = 0
                for (cellNode in r.children) {
                    if (cellNode.name != "table-cell" && cellNode.name != "covered-table-cell") continue
                    val cellRepeat = (cellNode.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(REPEAT_CAP)
                    val hasContent = cellNode.attr("value") != null || cellNode.attr("formula") != null || cellNode.text.isNotBlank()
                    if (!hasContent && formattedEmpty(cellNode, styles)) {
                        val look = styles.cell(cellNode.attr("style-name"))!!
                        repeat(cellRepeat) { k ->
                            if (c + k < Sheet.MAX_COLS) { applyStyle(sheet.ensure(row, c + k), look, styles); if (c + k > maxCol) maxCol = c + k }
                        }
                    }
                    if (hasContent && cellNode.name == "table-cell") {
                        repeat(cellRepeat) { k ->
                            if (c + k < Sheet.MAX_COLS) {
                                cell(sheet, row, c + k, cellNode, styles, rowStyle ?: colStyles[c + k])
                                if (c + k > maxCol) maxCol = c + k
                            }
                        }
                        val spanCols = cellNode.attr("number-columns-spanned")?.toIntOrNull() ?: 1
                        val spanRows = cellNode.attr("number-rows-spanned")?.toIntOrNull() ?: 1
                        if (spanCols > 1 || spanRows > 1) {
                            sheet.merges.add(MergeRegion(row, c, row + spanRows - 1, c + spanCols - 1))
                        }
                    }
                    c += cellRepeat
                }
                lastUsed = maxOf(lastUsed, row)
                row++
            }
        }
        // Rows folded inside a collapsed group are not filter-hidden.
        sheet.rowGroups.sortWith(compareBy({ it.r1 }, { -it.r2 }))
        sheet.hiddenRows.removeAll(sheet.foldedRows())
        sheet.manualHiddenRows.removeAll(sheet.foldedRows())
        // A page-anchored picture or shape belongs to the cell under its top-left corner, the rest an offset into it.
        for (img in pagePictures + pageShapes) {
            var x = img.dxPt; var y = img.dyPt
            // (A point on a cell's edge — within rounding of the lengths — is the next cell's corner.)
            var c = 0; while (c < Sheet.MAX_COLS - 1 && x >= DEF_COL_PT * sheet.colWidthFactor(c) - 0.05f) { x -= DEF_COL_PT * sheet.colWidthFactor(c); c++ }
            var r = 0; while (r < MAX_ROWS - 1 && y >= DEF_ROW_PT * sheet.rowHeightFactor(r) - 0.05f) { y -= DEF_ROW_PT * sheet.rowHeightFactor(r); r++ }
            if (x < 0f) x = 0f
            if (y < 0f) y = 0f
            img.row = r; img.col = c; img.dxPt = x; img.dyPt = y
            if (img is SheetImage) sheet.images.add(img) else if (img is com.therealityteam.aeropages.model.SheetShape) sheet.shapes.add(img)
            lastUsed = maxOf(lastUsed, r)
        }
        // Back into the order they are drawn in (cells list them row by row); those without an index keep theirs, last.
        sheet.shapes.sortWith(compareBy { zOf[it] ?: Int.MAX_VALUE })
        sheet.images.sortWith(compareBy { zOf[it] ?: Int.MAX_VALUE })
        sheet.images.maxOfOrNull { it.col }?.let { if (it > maxCol) maxCol = it }
        sheet.rowCount = maxOf(40, lastUsed + 5)
        sheet.colCount = maxOf(16, maxCol + 2, (sheet.colGroups.maxOfOrNull { it.r2 } ?: -1) + 2).coerceAtMost(Sheet.MAX_COLS)
        readSparklines(node, sheet)
        OdfDataRules.place(sheet, ruleRuns, templates)
        // LibreOffice's conditional formats, at the table's end.
        sheet.condFormats.addAll(OdfCondFormats.read(node, styles))
        if (titleRows != null || titleCols != null) sheet.print = sheet.print.copy(titleRows = titleRows, titleCols = titleCols)
        if (rowBreaks.isNotEmpty() || colBreaks.isNotEmpty()) sheet.print = sheet.print.copy(rowBreaks = rowBreaks.toList(), colBreaks = colBreaks.toList())
        return sheet
    }

    /**
     * The pictures anchored in row [row]'s cells (LibreOffice keeps a cell-anchored shape inside its
     * cell, placed from the cell's top-left corner); true when there were any.
     */
    private fun cellPictures(r: OdfNode, row: Int, sheet: Sheet, files: Map<String, ByteArray>, styles: OdfStyles, zOf: MutableMap<Any, Int>,
                             pageShapes: MutableList<com.therealityteam.aeropages.model.SheetShape>): Boolean {
        var found = false
        var c = 0L
        for (cellNode in r.children) {
            if (cellNode.name != "table-cell" && cellNode.name != "covered-table-cell") continue
            if (c < Sheet.MAX_COLS) for (frame in cellNode.children) {
                // A chart anchored in the cell (with the picture of it LibreOffice adds): a chart.
                val chartRef = if (frame.name == "frame") frame.child("object")?.attr("href")?.removePrefix("./")?.trimEnd('/') else null
                val chart = chartRef?.let { OdfChart.read(files["$it/content.xml"], sheet.name) }
                if (chart != null) { sheet.charts.add(chart); found = true; continue }
                if (frame.name == "frame" && frame.child("image") != null) picture(frame, files, styles, row, c.toInt())?.let {
                    sheet.images.add(it); found = true
                    frame.attr("z-index")?.toIntOrNull()?.let { z -> zOf[it] = z }
                }
                // Shapes and text boxes anchored in the cell, placed from its corner as pictures are.
                else if (frame.name in SHAPE_ELEMENTS || (frame.name == "frame" && frame.child("text-box") != null)) {
                    val (shape, box) = OdpReader.readShape(frame, styles)
                    // A connector gives its ends on the sheet, whichever cell holds it: placed as a page's shapes are.
                    if (frame.name == "connector") {
                        val sh = com.therealityteam.aeropages.model.SheetShape(shape, 0, 0, box[0], box[1], box[2], box[3], frame.attr("name").orEmpty())
                        pageShapes.add(sh)
                        frame.attr("z-index")?.toIntOrNull()?.let { z -> zOf[sh] = z }
                        continue
                    }
                    val sh = com.therealityteam.aeropages.model.SheetShape(shape, row, c.toInt(), box[0], box[1], box[2], box[3], frame.attr("name").orEmpty())
                    if (shape.type == com.therealityteam.aeropages.model.ShapeType.LINE || (box[2] > 0f && box[3] > 0f)) {
                        sheet.shapes.add(sh); found = true
                        frame.attr("z-index")?.toIntOrNull()?.let { z -> zOf[sh] = z }
                    }
                }
            }
            c += (cellNode.attr("number-columns-repeated")?.toLongOrNull() ?: 1L).coerceAtLeast(1L)
        }
        return found
    }

    /** The drawing elements that are shapes. */
    private val SHAPE_ELEMENTS = OdpReader.DRAWN_ELEMENTS

    /** A `draw:frame` holding a picture, at ([row], [col]) offset by the frame's position, cropped by its style's `fo:clip`; null for any other frame. */
    private fun picture(frame: OdfNode, files: Map<String, ByteArray>, styles: OdfStyles, row: Int, col: Int): SheetImage? {
        val image = frame.child("image") ?: return null
        val href = image.attr("href")?.removePrefix("./") ?: return null
        val data = files[href] ?: return null
        val w = OdfValue.lengthPt(frame.attr("width")) ?: return null
        val h = OdfValue.lengthPt(frame.attr("height")) ?: return null
        if (w <= 0f || h <= 0f) return null
        val mime = com.therealityteam.aeropages.format.PictureType.mimeOf(data, href)
        val img = SheetImage(data, mime, row, col, OdfValue.lengthPt(frame.attr("x")) ?: 0f, OdfValue.lengthPt(frame.attr("y")) ?: 0f, w, h, frame.attr("name").orEmpty())
        val look = styles.resolvedGraphic(frame.attr("style-name"))
        com.therealityteam.aeropages.format.PictureCrop.readFoClip(look?.clip, data, img)
        img.shadow = OdpReader.shadowOf(look)
        OdfAltText.read(frame, look).let { (alt, decorative) -> img.altText = alt; img.decorative = decorative }
        return img
    }

    /** Row-group boundaries in the flattened row list (`table:table-row-group`, `table:display="false"` = collapsed). */
    private class GroupStart(val collapsed: Boolean)
    private object GroupEnd
    private object HeaderStart
    private object HeaderEnd

    /** LibreOffice's `calcext:sparkline-groups`: addresses are `Sheet.A1`, ranges `Sheet.A1:Sheet.B1`. */
    private fun readSparklines(table: OdfNode, sheet: Sheet) {
        table.descendants("sparkline-group").forEach { g ->
            val type = when (g.attr("type")) {
                "column" -> com.therealityteam.aeropages.model.SparklineType.COLUMN
                "stacked" -> com.therealityteam.aeropages.model.SparklineType.WIN_LOSS
                else -> com.therealityteam.aeropages.model.SparklineType.LINE
            }
            val color = OdfValue.color(g.attr("color-series")) ?: 0xFF376092
            val markers = g.attr("markers") == "true"
            g.descendants("sparkline").forEach { sp ->
                fun addr(s: String) = com.therealityteam.aeropages.model.A1.parse(s.substringAfterLast('.').removePrefix("$"))
                val (row, col) = addr(sp.attr("cell-address") ?: return@forEach) ?: return@forEach
                val range = sp.attr("data-range") ?: return@forEach
                val parts = range.split(':')
                val (r1, c1) = addr(parts[0]) ?: return@forEach
                val (r2, c2) = addr(parts.getOrElse(1) { parts[0] }) ?: return@forEach
                sheet.sparklines.add(com.therealityteam.aeropages.model.Sparkline(row, col, minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2), type, color, markers))
            }
        }
    }

    /**
     * Whether [node] is an empty cell with a look of its own — a fill, borders, a number format,
     * bold … — that a sheet keeps (a table's banding, a form's boxes); not a long padding run.
     */
    private fun formattedEmpty(node: OdfNode, styles: OdfStyles): Boolean {
        if (node.name != "table-cell" || node.attr("value") != null || node.attr("formula") != null || node.text.isNotBlank()) return false
        if ((node.attr("number-columns-repeated")?.toIntOrNull() ?: 1) > REPEAT_CAP) return false
        val s = styles.cell(node.attr("style-name")) ?: return false
        return s.background != null || s.borderTop != null || s.borderBottom != null || s.borderLeft != null || s.borderRight != null ||
            s.dataStyle != null || s.text.bold || s.text.italic || s.text.underline || s.text.color != null || s.align != null || s.wrap || s.unlocked ||
            (s.rotation ?: 0) != 0 || s.stacked == true
    }

    /** [style]'s look on [cell]: character format, fill, alignment, borders, protection and number format. */
    private fun applyStyle(cell: Cell, style: OdfStyles.CellStyle, styles: OdfStyles) {
        cell.unlocked = style.unlocked
        cell.format.bold = style.text.bold
        cell.format.italic = style.text.italic
        cell.format.underline = style.text.underline
        cell.format.strikethrough = style.text.strikethrough
        style.text.fontSizePt?.let { cell.format.fontSizePt = it }
        style.text.fontFamily?.let { cell.format.fontFamily = it }
        style.text.color?.let { cell.format.color = it }
        style.background?.let { cell.format.highlight = it }
        style.align?.let { cell.align = it }
        style.vAlign?.let { cell.vAlign = it }
        cell.wrap = style.wrap
        cell.rotation = style.rotation ?: 0; cell.stacked = style.stacked ?: false; cell.indent = style.indent ?: 0
        cell.spread = style.spread; cell.shrink = style.shrink ?: false
        // Fill and Distributed rest on their plain alignment (left, justified).
        style.spread?.let { if (style.align == null || it == com.therealityteam.aeropages.model.CellSpread.FILL) cell.align = it.plain }
        cell.borderTop = style.borderTop; cell.borderBottom = style.borderBottom
        cell.borderLeft = style.borderLeft; cell.borderRight = style.borderRight
        styles.numberFormat(style.dataStyle)?.let {
            cell.numberFormat = it.format
            if (it.decimals >= 0) cell.decimals = it.decimals
            cell.formatCode = it.code
        }
    }

    /** Cell [node] at ([row], [col]); [defaultStyle] (its row's or column's) is the style when it names none. */
    private fun cell(sheet: Sheet, row: Int, col: Int, node: OdfNode, styles: OdfStyles, defaultStyle: String? = null) {
        val cell = sheet.ensure(row, col)
        val formula = node.attr("formula")
        // Dates and times become the serial numbers the sheet keeps (days since 1899-12-30), so a
        // date format shows them and formulas count with them; a boolean is TRUE or FALSE.
        val value = node.attr("value") ?: node.attr("date-value")?.let { OdfValue.dateSerial(it) } ?: node.attr("time-value")?.let { OdfValue.timeSerial(it) }
        val paragraphs = node.children("p")
        val text = paragraphs.joinToString("\n") { it.text }
        val style = styles.cell(node.attr("style-name") ?: defaultStyle)

        cell.text = when {
            formula != null -> fromOdfFormula(formula)
            node.attr("value-type") == "boolean" -> node.attr("boolean-value")?.let { if (it == "true") "TRUE" else "FALSE" } ?: text
            // A number in General keeps the way it was typed (`1.50`, `0.00E+00`) when that is the same number.
            value != null && style?.dataStyle == null && node.attr("value-type") == "float" && NUMBER.matches(text) &&
                text.toDouble() == value.toDoubleOrNull() -> text
            value != null -> trimNumber(value)
            // Text shown through a format (`@" 元"`) keeps what was typed beside what it shows.
            else -> node.attr("string-value") ?: text
        }
        // Mixed formatting: spans inside the paragraphs become runs (a plain paragraph yields one run, dropped).
        if (formula == null && cell.text == text && paragraphs.any { p -> p.children.any { it.name == "span" } }) {
            val runs = ArrayList<com.therealityteam.aeropages.model.TextRun>()
            paragraphs.forEachIndexed { i, p ->
                if (i > 0) runs.add(com.therealityteam.aeropages.model.TextRun("\n", CharacterFormat()))
                OdpReader.collectRuns(p, CharacterFormat(), null, runs, styles)
            }
            if (runs.size > 1 && runs.joinToString("") { it.text } == text) cell.runs = runs
        }

        style?.let { applyStyle(cell, it, styles) }
        // A percentage is stored as a fraction; the editor keeps the same convention.
        if (cell.numberFormat == NumberFormat.PERCENT && formula == null && value != null) cell.text = trimNumber(value)
        node.descendants("annotation").firstOrNull()?.let { a ->
            cell.comment = a.descendants("p").joinToString("\n") { p -> p.text }.trim()
            cell.commentAuthor = a.child("creator")?.text?.trim()?.takeIf { it.isNotEmpty() }
            cell.commentShown = a.attr("display") == "true"
            cell.commentDate = a.child("date")?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
                runCatching { java.time.OffsetDateTime.parse(d).toInstant().toEpochMilli() }.getOrNull()
                    ?: runCatching { java.time.LocalDateTime.parse(d.substringBefore('Z')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            } ?: 0L
        }
        // A link: an address, or a place in the workbook (ODF's `#Sheet2.A1`, kept as Excel writes it: `#Sheet2!A1`).
        node.descendants("a").firstOrNull()?.attr("href")?.let { cell.link = com.therealityteam.aeropages.model.CellLinks.fromOdf(it) }
    }

    /** A plain decimal number as typed: `12`, `-1.50`, `0.00E+00`. */
    private val NUMBER = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")

    /** `1.0` reads better as `1`; the editor stores cell values as text. */
    private fun trimNumber(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        return if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(Locale.ROOT, "%s", d)
    }

    /**
     * `of:=SUM([.B2:.B4])` → `=SUM(B2:B4)`, `[Sheet2.A1]` → `Sheet2!A1`, `;` → `,`.
     * The inverse of [OdsWriter.toOdfFormula].
     */
    fun fromOdfFormula(formula: String): String {
        var s = formula.removePrefix("of:").removePrefix("oooc:").removePrefix("=")
        s = Regex("""\[([^\]]*)]""").replace(s) { m ->
            m.groupValues[1].split(':').joinToString(":") { ref ->
                // `.B2`, `Sheet2.B2`, `$'Q3 Sales'.B2` (a quoted name may hold dots; a doubled quote is one).
                val trimmed = ref.trim().removePrefix("$")
                val dot = if (trimmed.startsWith("'")) {
                    var i = 1
                    while (i < trimmed.length) { if (trimmed[i] == '\'') { if (i + 1 < trimmed.length && trimmed[i + 1] == '\'') i += 2 else break } else i++ }
                    trimmed.indexOf('.', i)
                } else trimmed.indexOf('.')
                if (dot < 0) trimmed
                else {
                    val sheet = OdfDataRules.unquote(trimmed.substring(0, dot))
                    val addr = trimmed.substring(dot + 1)
                    if (sheet.isEmpty()) addr else com.therealityteam.aeropages.model.A1.quoteSheet(sheet) + "!" + addr
                }
            }
        }
        // Argument separators, but not the ones inside a string literal; OpenFormula's TRUE() and FALSE()
        // as Excel writes the logical values, TRUE and FALSE.
        val out = StringBuilder("=")
        var i = 0
        var braces = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"') {
                val end = s.indexOf('"', i + 1).let { if (it < 0) s.length - 1 else it }
                out.append(s, i, end + 1); i = end + 1; continue
            }
            // An array constant's rows (`|`) as Excel separates them (`;`); its columns' `;` become `,` below.
            if (ch == '{') braces++ else if (ch == '}') braces = (braces - 1).coerceAtLeast(0)
            if (braces > 0 && ch == '|') { out.append(';'); i++; continue }
            val logical = LOGICAL_CALL.matchAt(s, i)?.takeIf { i == 0 || !(s[i - 1].isLetterOrDigit() || s[i - 1] in "_.$'") }
            if (logical != null) { out.append(logical.groupValues[1].uppercase()); i = logical.range.last + 1; continue }
            // A function by its Excel name (TEXTJOIN for COM.MICROSOFT.TEXTJOIN, FORMULATEXT for FORMULA …).
            val call = OdfFunctions.CALL.matchAt(s, i)?.takeIf { i == 0 || !(s[i - 1].isLetterOrDigit() || s[i - 1] in "_.$'") }
            if (call != null) { out.append(OdfFunctions.fromOdf(call.value)); i = call.range.last + 1; continue }
            out.append(if (ch == ';') ',' else ch); i++
        }
        return out.toString()
    }

    private val LOGICAL_CALL = Regex("""(?i)(TRUE|FALSE)\(\s*\)""")
}
