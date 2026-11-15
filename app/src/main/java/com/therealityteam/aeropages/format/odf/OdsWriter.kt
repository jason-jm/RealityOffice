package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.HEAD
import com.therealityteam.aeropages.format.odf.OdfPackage.NS
import com.therealityteam.aeropages.format.odf.OdfPackage.color
import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.format.odf.OdfPackage.pt
import com.therealityteam.aeropages.model.CachedValue
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.VAlign
import com.therealityteam.aeropages.model.Workbook
import java.io.OutputStream
import java.util.Locale

/**
 * OpenDocument Spreadsheet (.ods) writer: the counterpart of
 * [com.therealityteam.aeropages.format.xlsx.OdsReader].
 *
 * Cells carry a real value type (`float` / `percentage` / `currency` / `date` / `string`), so a
 * reader gets numbers rather than text; formulas are translated from the A1 dialect the editor
 * uses into ODF's `of:` syntax (`=SUM(B2:B4)` → `of:=SUM([.B2:.B4])`). Number formats become
 * data styles, cell formatting (font, colour, fill, borders, alignment, wrap) becomes cell styles,
 * and merges, hidden rows, column widths and row heights are preserved.
 *
 * [values] is the editor's computed-value lookup: ODF keeps a cached value beside every formula
 * so the numbers are right before the reader recalculates. Without it formulas are written bare.
 * TRUE and FALSE are boolean values, shown through a boolean data style as LibreOffice's are.
 */
object OdsWriter {

    private const val DEF_COL_PT = 64f
    private const val DEF_ROW_PT = 15f

    /** The looks the conditional formats of the workbook being written give (named cell styles in styles.xml). */
    private val condLooks = ThreadLocal<OdfCondFormats.Looks>()

    fun write(wb: Workbook, out: OutputStream, values: ((sheetIdx: Int, row: Int, col: Int) -> CachedValue?)? = null) {
        val looks = OdfCondFormats.Looks().also { condLooks.set(it) }
        try { writeWith(wb, out, values, looks) } finally { condLooks.remove() }
    }

    private fun writeWith(wb: Workbook, out: OutputStream, values: ((sheetIdx: Int, row: Int, col: Int) -> CachedValue?)?, looks: OdfCondFormats.Looks) {
        val styles = CellStyles()
        val objects = LinkedHashMap<String, ByteArray>()   // embedded chart objects and pictures
        val clips = LinkedHashMap<String, String>()        // cropped or shadowed pictures' graphic styles, by their properties
        val shapeStyles = OdpWriter.SlideStyles("sh")       // shapes' graphic, paragraph, text and list styles
        // Validation rules are named once for the document ("val1" …) and listed before the tables;
        // each cell a rule covers names it. A list with nothing in it is left out.
        val rules = wb.sheets.map { s ->
            s.validations.filter { it.type != com.therealityteam.aeropages.model.ValidationType.LIST || it.formula1 != null || it.items.isNotEmpty() }
        }
        var n = 0
        val ruleNames = rules.map { list -> list.map { it to "val${++n}" } }
        // How each sheet prints: a master page per print setup (paper, margins, scale, header and footer…).
        val printing = OdsPrint.write(wb)
        // Each sheet's table style ("ta1" …), one per look: left out of the tabs, its tab's colour, its master page.
        val tableStyles = LinkedHashMap<Triple<Boolean, Long?, String>, String>()
        val tableStyleOf = wb.sheets.mapIndexed { si, s ->
            tableStyles.getOrPut(Triple(s.hidden, s.tabColor, printing.masterOf[si])) { "ta${tableStyles.size + 1}" }
        }
        val body = buildString {
            append("<office:body><office:spreadsheet${com.therealityteam.aeropages.format.ProtectionXml.odsSpreadsheetAttrs(wb)}>")
            append(OdfDataRules.contentValidations(wb, ruleNames.flatMapIndexed { si, list -> list.map { (dv, name) -> Triple(si, dv, name) } }))
            wb.sheets.forEachIndexed { si, sheet -> append(sheet(si, sheet, styles, values, objects, ruleNames[si], clips, wb.sheets, shapeStyles, tableStyleOf[si])) }
            append(namedRanges(wb))
            append(OdfDataRules.databaseRanges(wb))
            append("</office:spreadsheet></office:body>")
        }
        val parts = LinkedHashMap<String, ByteArray>()
        parts["content.xml"] = buildString {
            append(HEAD)
            append("<office:document-content $NS>")
            append("<office:automatic-styles>").append(styles.render())
            // A hidden sheet's table:display="false" (ODF has no "very hidden": such a sheet is written hidden), and the tab
            // colour as ODF 1.3 has it (table:tab-color, what LibreOffice writes now) and as older LibreOffice reads it (tableooo:).
            tableStyles.forEach { (look, name) ->
                val color = look.second?.let { OdfPackage.color(it) }?.let { """ table:tab-color="$it" tableooo:tab-color="$it"""" } ?: ""
                append("""<style:style style:name="$name" style:family="table" style:master-page-name="${look.third}"><style:table-properties table:display="${!look.first}"$color/></style:style>""")
            }
            if (wb.sheets.any { it.images.isNotEmpty() }) append(PICTURE_STYLE)
            clips.forEach { (props, name) -> append("""<style:style style:name="$name" style:family="graphic"><style:graphic-properties draw:stroke="none" draw:fill="none"$props/></style:style>""") }
            append(shapeStyles.render())
            append("</office:automatic-styles>")
            append(body)
            append("</office:document-content>")
        }.toByteArray(Charsets.UTF_8)
        parts["styles.xml"] = buildString {
            append(HEAD)
            // The named drawing styles (gradients, dashes, markers) the shapes' graphic styles use.
            append("<office:document-styles $NS><office:styles>").append(shapeStyles.renderNamed())
            // The looks conditional formats give, as named cell styles.
            append(looks.render())
            append("</office:styles><office:automatic-styles>").append(printing.layouts).append("</office:automatic-styles>")
            append("<office:master-styles>").append(printing.masters).append("</office:master-styles></office:document-styles>")
        }.toByteArray(Charsets.UTF_8)
        parts["meta.xml"] = com.therealityteam.aeropages.format.OdfMeta.write(wb.props)
        // Each sheet's frozen panes, zoom and gridlines, where Calc keeps them.
        parts["settings.xml"] = OdsView.write(wb)
        parts.putAll(objects)
        OdfPackage.write(out, OdfPackage.SPREADSHEET, parts)
    }

    /**
     * The workbook's defined names: ranges as `table:named-range`s (`Sheet1!$A$1:$B$5` → `$Sheet1.$A$1:.$B$5`), constants
     * and formulas as `table:named-expression`s in ODF's formula syntax (`of:=0.08`, `of:=[$Sheet1.$A$1]*2`).
     */
    private fun namedRanges(wb: Workbook): String {
        val first = OdfDataRules.odfSheet(wb.sheets.firstOrNull()?.name ?: "Sheet1")
        val ranges = wb.names.mapNotNull { (name, ref) ->
            val t = com.therealityteam.aeropages.model.A1.parseTarget(ref)
            if (t == null || t.wholeRows || t.wholeColumns)
                return@mapNotNull """<table:named-expression table:name="${escape(name)}" table:base-cell-address="${escape("$$first.\$A\$1")}" table:expression="${escape(toOdfFormula(ref))}"/>"""
            val sheet = OdfDataRules.odfSheet(t.sheet ?: wb.sheets.firstOrNull()?.name ?: "Sheet1")
            fun cell(r: Int, c: Int) = "$" + Workbook.colLabel(c) + "$" + (r + 1)
            val range = "$$sheet.${cell(t.r1, t.c1)}" + (if (t.r1 == t.r2 && t.c1 == t.c2) "" else ":.${cell(t.r2, t.c2)}")
            """<table:named-range table:name="${escape(name)}" table:base-cell-address="${escape("$$sheet.${cell(t.r1, t.c1)}")}" table:cell-range-address="${escape(range)}"/>"""
        }
        return if (ranges.isEmpty()) "" else "<table:named-expressions>" + ranges.joinToString("") + "</table:named-expressions>"
    }

    private fun sheet(
        index: Int, sheet: Sheet, styles: CellStyles, values: ((Int, Int, Int) -> CachedValue?)?, objects: LinkedHashMap<String, ByteArray>,
        rules: List<Pair<com.therealityteam.aeropages.model.DataValidation, String>>, clips: LinkedHashMap<String, String> = LinkedHashMap(),
        sheets: List<Sheet> = listOf(sheet),
        shapeStyles: OdpWriter.SlideStyles = OdpWriter.SlideStyles("sh"),
        /** The sheet's table style (hidden, tab colour), or null. */
        tableStyle: String? = null,
    ): String = buildString {
        fun ruleAt(r: Int, c: Int): String? = rules.firstOrNull { it.first.covers(r, c) }?.second
        val used = sheet.cells.keys
        // Row outline: groups nest (outer first when two start together); folded rows are written collapsed.
        val groups = sheet.rowGroups.sortedWith(compareBy({ it.r1 }, { -it.r2 }))
        val folded = sheet.foldedRows()
        // Print titles: the table's header rows and columns, as LibreOffice keeps its rows and columns to repeat —
        // where the outline lets them nest (a group either holds them all or none of them).
        fun nests(t: IntRange?, gs: List<com.therealityteam.aeropages.model.RowGroup>) =
            t?.takeIf { gs.all { g -> (g.r1 <= t.first && g.r2 >= t.last) || g.r2 < t.first || g.r1 > t.last } }
        val titleRows = nests(sheet.print.titleRows, groups)
        // Pictures sit in their anchor cells (as LibreOffice keeps a cell-anchored shape), so those cells are written too.
        val pictures = sheet.images.withIndex().groupBy { (_, img) -> img.row.toLong() * 1000L + img.col }
        // Shapes sit in their anchor cells too, drawn after the pictures.
        val shapes = sheet.shapes.withIndex().groupBy { (_, sh) -> sh.row.toLong() * 1000L + sh.col }
        val lastRow = maxOf(used.maxOfOrNull { (it / 1000L).toInt() } ?: -1, groups.maxOfOrNull { it.r2 } ?: -1,
            sheet.hiddenRows.maxOrNull() ?: -1, sheet.manualHiddenRows.maxOrNull() ?: -1, sheet.images.maxOfOrNull { it.row } ?: -1,
            sheet.shapes.maxOfOrNull { it.row } ?: -1,
            sheet.tables.maxOfOrNull { it.r2 } ?: -1, titleRows?.last ?: -1)
        val lastCol = maxOf(used.maxOfOrNull { (it % 1000L).toInt() } ?: -1, sheet.images.maxOfOrNull { it.col } ?: -1, sheet.tables.maxOfOrNull { it.c2 } ?: -1,
            sheet.shapes.maxOfOrNull { it.col } ?: -1)
        // OpenDocument has no tables: their looks are written as the cells' own formatting and their
        // structured references as the cells they stand for.
        val tables = sheets.any { it.tables.isNotEmpty() }
        // Columns: hidden ones and the column outline reach past the last used cell too.
        val colGroups = sheet.colGroups.sortedWith(compareBy({ it.r1 }, { -it.r2 }))
        val foldedCols = sheet.foldedCols()
        // Validation rules reach past the content too: their cells carry the rule's name.
        val ruleLastCol = rules.maxOfOrNull { it.first.c2.coerceAtMost(Sheet.MAX_COLS - 1) } ?: -1
        val ruleLastRow = rules.maxOfOrNull { it.first.r2.coerceAtMost(ODF_MAX_ROW) } ?: -1
        val titleCols = nests(sheet.print.titleCols, colGroups)
        val lastColumn = maxOf(lastCol, sheet.hiddenCols.maxOrNull() ?: -1, colGroups.maxOfOrNull { it.r2 } ?: -1, ruleLastCol, titleCols?.last ?: -1)
        append("""<table:table table:name="${escape(sheet.name)}"${tableStyle?.let { """ table:style-name="$it"""" } ?: ""}${com.therealityteam.aeropages.format.ProtectionXml.odsTableAttrs(sheet)}""")
        OdsPrint.printRanges(sheet)?.let { append(""" table:print-ranges="${escape(it)}"""") }
        append(">")
        append(com.therealityteam.aeropages.format.ProtectionXml.odsTableChild(sheet))
        // Charts: embedded objects in table:shapes (before the columns, per the schema), stacked
        // down the sheet like the xlsx drawing anchors; bound charts point at their block.
        if (sheet.charts.isNotEmpty()) {
            append("<table:shapes>")
            sheet.charts.forEachIndexed { k, c ->
                val n = objects.keys.count { it.startsWith("Object ") } + 1
                objects["Object $n/content.xml"] = OdfChart.contentXml(c, CHART_W, CHART_H, sheet.name).toByteArray(Charsets.UTF_8)
                append(OdfChart.frame("./Object $n", CHART_X, k * (CHART_H + 15f), CHART_W, CHART_H, layer = false))
            }
            append("</table:shapes>")
        }
        for (c in 0..lastColumn.coerceAtLeast(0)) {
            colGroups.filter { it.r1 == c }.forEach { g -> append(if (g.collapsed) """<table:table-column-group table:display="false">""" else "<table:table-column-group>") }
            if (titleCols?.first == c) append("<table:table-header-columns>")
            val visibility = if (c in sheet.hiddenCols || c in foldedCols) """ table:visibility="collapse"""" else ""
            append("""<table:table-column table:style-name="${styles.column(sheet.colWidths[c] ?: 1f, c in sheet.print.colBreaks)}"$visibility table:default-cell-style-name="Default"/>""")
            if (titleCols?.last == c) append("</table:table-header-columns>")
            repeat(colGroups.count { it.r2 == c }) { append("</table:table-column-group>") }
        }
        for (r in 0..lastRow) {
            groups.filter { it.r1 == r }.forEach { g -> append(if (g.collapsed) """<table:table-row-group table:display="false">""" else "<table:table-row-group>") }
            if (titleRows?.first == r) append("<table:table-header-rows>")
            // Filtered rows say so ("filter"); rows hidden by hand or folded in a group collapse.
            val visibility = when {
                r in sheet.manualHiddenRows || r in folded -> """ table:visibility="collapse""""
                r in sheet.hiddenRows -> """ table:visibility="filter""""
                else -> ""
            }
            append("""<table:table-row table:style-name="${styles.rowOf(sheet, r)}"$visibility>""")
            val rowLastCol = maxOf(lastCol, rules.filter { r in it.first.r1..it.first.r2 }.maxOfOrNull { it.first.c2.coerceAtMost(Sheet.MAX_COLS - 1) } ?: -1)
            if (rowLastCol < 0) append("<table:table-cell/>")   // a row needs at least one cell
            var c = 0
            while (c <= rowLastCol) {
                val frames = pictures[r.toLong() * 1000L + c]?.joinToString("") { (k, img) -> pictureFrame(img, sheet.charts.size + k, objects, clips) }.orEmpty() +
                    shapes[r.toLong() * 1000L + c]?.joinToString("") { (k, sh) ->
                        val name = if (sh.name.isNotEmpty()) """ draw:name="${escape(sh.name)}"""" else ""
                        OdpWriter.shapeXml(sh.shape, sh.dxPt, sh.dyPt, sh.widthPt, sh.heightPt, shapeStyles, """ draw:z-index="${sheet.charts.size + sheet.images.size + k}"$name""")
                    }.orEmpty()
                val merge = sheet.merges.firstOrNull { it.covers(r, c) }
                if (merge != null && !merge.isAnchor(r, c)) {
                    append(if (frames.isEmpty()) "<table:covered-table-cell/>" else "<table:covered-table-cell>$frames</table:covered-table-cell>"); c++; continue
                }
                val raw = sheet.cell(r, c)
                var cell = com.therealityteam.aeropages.model.TableLooks.styled(sheet, r, c, raw)
                if (tables && cell != null && cell.text.startsWith("=")) {
                    val a1 = "=" + com.therealityteam.aeropages.model.StructuredRefs.toA1(cell.text.substring(1), sheet, r, c, sheets)
                    if (a1 != cell.text) cell = (if (cell === raw) cell.copyOf() else cell).also { it.text = a1 }
                }
                val spans = if (merge != null)
                    """ table:number-columns-spanned="${merge.c2 - merge.c1 + 1}" table:number-rows-spanned="${merge.r2 - merge.r1 + 1}"""" else ""
                append(cell(index, r, c, cell, styles, values, spans, ruleAt(r, c), frames, sheet.name))
                c++
            }
            append("</table:table-row>")
            if (titleRows?.last == r) append("</table:table-header-rows>")
            repeat(groups.count { it.r2 == r }) { append("</table:table-row-group>") }
        }
        // Past the content, rows differ only in the rules that reach them: one repeated row per band,
        // its cells in runs of one rule (a rule on a whole column is a few elements, not a million).
        if (ruleLastRow > lastRow) {
            val cuts = sortedSetOf(lastRow + 1, ruleLastRow + 1)
            rules.forEach { (dv, _) ->
                if (dv.r1 > lastRow && dv.r1 <= ruleLastRow) cuts.add(dv.r1)
                val end = dv.r2.coerceAtMost(ODF_MAX_ROW) + 1
                if (end > lastRow + 1 && end <= ruleLastRow + 1) cuts.add(end)
            }
            val bands = cuts.toList()
            for (k in 0 until bands.size - 1) {
                val top = bands[k]; val count = bands[k + 1] - top
                val here = rules.filter { top in it.first.r1..it.first.r2 }
                append("""<table:table-row table:style-name="${styles.row(1f, fitted = true)}"${if (count > 1) """ table:number-rows-repeated="$count"""" else ""}>""")
                val bandLastCol = here.maxOfOrNull { it.first.c2.coerceAtMost(Sheet.MAX_COLS - 1) } ?: -1
                if (bandLastCol < 0) append("<table:table-cell/>")
                var c = 0
                while (c <= bandLastCol) {
                    val name = here.firstOrNull { it.first.covers(top, c) }?.second
                    var end = c
                    while (end < bandLastCol && here.firstOrNull { it.first.covers(top, end + 1) }?.second == name) end++
                    append("<table:table-cell")
                    if (end > c) append(""" table:number-columns-repeated="${end - c + 1}"""")
                    if (name != null) append(""" table:content-validation-name="$name"""")
                    append("/>")
                    c = end + 1
                }
                append("</table:table-row>")
            }
        }
        append(OdfCondFormats.write(sheet, sheet.name, condLooks.get() ?: OdfCondFormats.Looks()))
        appendSparklines(sheet)
        append("</table:table>")
    }

    /** The last row ODF (LibreOffice's 1,048,576-row grid) can address. */
    private const val ODF_MAX_ROW = 1_048_575
    private const val CHART_X = 320f
    private const val CHART_W = 512f
    private const val CHART_H = 225f

    /** LibreOffice's sparklines (`calcext:sparkline-groups`, Calc 7.4+), grouped by type and colour. */
    private fun StringBuilder.appendSparklines(sheet: Sheet) {
        if (sheet.sparklines.isEmpty()) return
        val name = escape(sheet.name)
        append("<calcext:sparkline-groups>")
        sheet.sparklines.groupBy { Triple(it.type, it.color, it.markers) }.forEach { (key, group) ->
            val (type, color, markers) = key
            val typeName = when (type) {
                com.therealityteam.aeropages.model.SparklineType.COLUMN -> "column"
                com.therealityteam.aeropages.model.SparklineType.WIN_LOSS -> "stacked"
                else -> "line"
            }
            append("""<calcext:sparkline-group calcext:type="$typeName" calcext:color-series="${color(color)}" calcext:color-negative="#d00000"${if (markers) """ calcext:markers="true"""" else ""}>""")
            append("<calcext:sparklines>")
            group.forEach { s ->
                val a1 = "${Workbook.colLabel(s.c1)}${s.r1 + 1}"; val a2 = "${Workbook.colLabel(s.c2)}${s.r2 + 1}"
                append("""<calcext:sparkline calcext:cell-address="$name.${Workbook.colLabel(s.col)}${s.row + 1}" calcext:data-range="$name.$a1:$name.$a2"/>""")
            }
            append("</calcext:sparklines></calcext:sparkline-group>")
        }
        append("</calcext:sparkline-groups>")
    }

    private fun cell(
        sheetIdx: Int, row: Int, col: Int, cell: Cell?, styles: CellStyles,
        values: ((Int, Int, Int) -> CachedValue?)?, spans: String, rule: String? = null, frames: String = "", sheetName: String = "",
    ): String {
        val validation = if (rule != null) """ table:content-validation-name="$rule"""" else ""
        if (cell == null || (cell.text.isEmpty() && cell.comment == null)) {
            // An empty cell keeps its formatting (a fill, borders — a table's look) when it has any.
            val own = cell?.takeIf { styles.formatted(it) }?.let { """ table:style-name="${styles.cell(it)}"""" } ?: ""
            return if (frames.isEmpty()) "<table:table-cell$own$spans$validation/>" else "<table:table-cell$own$spans$validation>$frames</table:table-cell>"
        }
        val style = styles.cell(cell)
        val text = cell.text
        // A formula's value is the one [values] works out; a literal cell shows its own text.
        val cached = if (text.startsWith("=")) values?.invoke(sheetIdx, row, col) else null
        val shown = when (cached) {
            null -> if (text.startsWith("=")) "" else text
            is CachedValue.Number -> FormatCode.general(cached.value)
            is CachedValue.Text -> cached.value
            is CachedValue.Logical -> if (cached.value) "TRUE" else "FALSE"
            is CachedValue.Error -> cached.code
        }
        val attrs = StringBuilder(""" table:style-name="$style"$spans$validation""")
        // A format code's kind picks the value type (a percentage, a currency) as a plain format's does.
        val kind = cell.formatCode?.let { FormatCode.category(it) } ?: cell.numberFormat
        val number: Double?

        if (text.startsWith("=")) {
            attrs.append(""" table:formula="${escape(toOdfFormula(text))}"""")
            // ODF keeps a cached value beside the formula, so the numbers are right before recalc.
            // Without one (text, an error) the cell carries the formula alone — a value type would need a value.
            number = (cached as? CachedValue.Number)?.value
            number?.let { attrs.append(valueAttrs(it, kind)) }
            (cached as? CachedValue.Logical)?.let { attrs.append(booleanAttrs(it.value)) }
        } else {
            // A number only where the text is one (see Cell.storedNumber): `00123` or a long code stays a string.
            number = cell.storedNumber()
            val logical = cell.storedLogical()
            attrs.append(if (logical != null) booleanAttrs(logical) else valueAttrs(number, kind))
        }
        // The paragraph holds what the cell shows, its format code applied, as LibreOffice writes it.
        val code = cell.formatCode
        val printed = if (number != null && code != null) FormatCode.format(number, code).text else shown

        val inner = buildString {
            // A note with who wrote it and when (dc:creator, dc:date — LibreOffice's), a paragraph per line.
            cell.comment?.let { note ->
                append(if (cell.commentShown) "<office:annotation office:display=\"true\">" else "<office:annotation>")
                cell.commentAuthor?.let { append("<dc:creator>${escape(it)}</dc:creator>") }
                if (cell.commentDate > 0) append("<dc:date>" + java.time.Instant.ofEpochMilli(cell.commentDate).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)) + "</dc:date>")
                note.split('\n').forEach { append("<text:p>${escape(it)}</text:p>") }
                append("</office:annotation>")
            }
            // A place in the workbook as ODF names cells (`#Sheet2.A1`), an address as it is.
            val link = cell.link?.let { com.therealityteam.aeropages.model.CellLinks.toOdf(it, sheetName) }
            val rich = if (text.startsWith("=")) null else cell.richRuns()
            if (!link.isNullOrBlank()) append("""<text:p><text:a xlink:type="simple" xlink:href="${escape(link)}">${escape(printed)}</text:a></text:p>""")
            else if (rich != null) {
                // One paragraph per line; each run a span with its own text style (plain runs stay bare text).
                append("<text:p>")
                rich.forEach { run ->
                    run.text.split('\n').forEachIndexed { li, piece ->
                        if (li > 0) append("</text:p><text:p>")
                        if (piece.isEmpty()) return@forEachIndexed
                        val span = styles.span(run.format)
                        if (span == null) append(escape(piece)) else append("""<text:span text:style-name="$span">${escape(piece)}</text:span>""")
                    }
                }
                append("</text:p>")
            }
            else if (printed.isNotEmpty()) append("<text:p>${escape(printed)}</text:p>")
            append(frames)   // after the text, where LibreOffice writes a cell's shapes
        }
        return "<table:table-cell$attrs>$inner</table:table-cell>"
    }

    /** Graphic style of a picture frame: no line and no fill around the picture (LibreOffice's own for a picture). */
    private const val PICTURE_STYLE = """<style:style style:name="grPic" style:family="graphic"><style:graphic-properties draw:stroke="none" draw:fill="none"/></style:style>"""

    /**
     * A picture as the `draw:frame` its anchor cell holds, placed from the cell's top-left corner
     * (LibreOffice's reference point for a cell-anchored shape); the file goes into `Pictures/`. A
     * crop is its graphic style's `fo:clip`, one style per distinct clip.
     */
    private fun pictureFrame(img: com.therealityteam.aeropages.model.SheetImage, z: Int, parts: LinkedHashMap<String, ByteArray>, clips: LinkedHashMap<String, String>): String {
        val href = "Pictures/image${parts.keys.count { it.startsWith("Pictures/") } + 1}.${OdfPackage.imageExt(img.mime)}"
        parts[href] = img.data
        val name = if (img.name.isNotEmpty()) """ draw:name="${escape(img.name)}"""" else ""
        // A crop and a shadow are its graphic style's: one style per distinct pair.
        val clip = com.therealityteam.aeropages.format.PictureCrop.foClip(img, img.data)
        val style = if (clip == null && img.shadow == null && !img.decorative) "grPic"
            else clips.getOrPut((clip?.let { """ fo:clip="$it"""" } ?: "") + OdfLines.shadowAttrs(img.shadow) + OdfAltText.decorativeProp(img.decorative)) { "grPic${clips.size + 1}" }
        return """<draw:frame draw:z-index="$z"$name draw:style-name="$style" svg:width="${pt(img.widthPt)}" svg:height="${pt(img.heightPt)}" svg:x="${pt(img.dxPt)}" svg:y="${pt(img.dyPt)}">""" +
            """<draw:image xlink:href="$href" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad" draw:mime-type="${escape(img.mime)}"/>${OdfAltText.desc(img.altText)}</draw:frame>"""
    }

    /** The `office:value-type` / `office:value` pair a value carries, following its number format. */
    private fun valueAttrs(n: Double?, fmt: NumberFormat): String = when {
        n == null -> """ office:value-type="string""""
        fmt == NumberFormat.DATE || fmt == NumberFormat.TIME || fmt == NumberFormat.DATE_TIME ->
            """ office:value-type="float" office:value="${OdfPackage.number(n)}""""
        fmt == NumberFormat.PERCENT -> """ office:value-type="percentage" office:value="${OdfPackage.number(n)}""""
        fmt == NumberFormat.CURRENCY_CNY -> """ office:value-type="currency" office:currency="CNY" office:value="${OdfPackage.number(n)}""""
        fmt == NumberFormat.CURRENCY_USD -> """ office:value-type="currency" office:currency="USD" office:value="${OdfPackage.number(n)}""""
        else -> """ office:value-type="float" office:value="${OdfPackage.number(n)}""""
    }

    /** A logical value's `office:value-type` / `office:boolean-value` pair. */
    private fun booleanAttrs(v: Boolean): String = """ office:value-type="boolean" office:boolean-value="$v""""

    /**
     * Translate the editor's A1 formulas into ODF's `of:` dialect: references are bracketed and
     * dot-prefixed (`B2` → `[.B2]`, `Sheet2!A1:B2` → `[Sheet2.A1:.B2]`) and argument separators
     * become semicolons. Text inside quotes is left alone.
     */
    fun toOdfFormula(formula: String): String {
        val src = formula.removePrefix("=")
        val out = StringBuilder("of:=")
        val ref = Regex("""(?:('[^']+'|[A-Za-z_][A-Za-z0-9_]*)!)?(\$?[A-Za-z]{1,3}\$?[0-9]{1,7})(?::(\$?[A-Za-z]{1,3}\$?[0-9]{1,7}))?(?![A-Za-z0-9_(.$])""")
        var i = 0
        var braces = 0
        while (i < src.length) {
            val ch = src[i]
            if (ch == '"') {                                   // string literal: copy verbatim
                val end = src.indexOf('"', i + 1).let { if (it < 0) src.length - 1 else it }
                out.append(src, i, end + 1); i = end + 1; continue
            }
            // An array constant: OpenFormula's `;` between columns and `|` between rows (Excel's `,` and `;`).
            if (ch == '{') braces++ else if (ch == '}') braces = (braces - 1).coerceAtLeast(0)
            if (braces > 0 && ch == ';') { out.append('|'); i++; continue }
            if (ch == ',') { out.append(';'); i++; continue }
            val prev = if (i == 0) ' ' else src[i - 1]
            // The logical values are OpenFormula's functions TRUE() and FALSE().
            val logical = LOGICAL.matchAt(src, i)
            if (logical != null && !prev.isLetterOrDigit() && prev != '_' && prev != '.' && prev != '$' && prev != '\'') {
                out.append(logical.groupValues[1].uppercase(Locale.ROOT)).append("()"); i = logical.range.last + 1; continue
            }
            // A function by its ODF name (COM.MICROSOFT.TEXTJOIN, LEGACY.NORMSDIST …).
            val call = OdfFunctions.CALL.matchAt(src, i)
            if (call != null && !prev.isLetterOrDigit() && prev != '_' && prev != '.' && prev != '$' && prev != '\'') {
                out.append(OdfFunctions.toOdf(call.value)); i = call.range.last + 1; continue
            }
            val m = ref.matchAt(src, i)
            if (m != null && !prev.isLetterOrDigit() && prev != '_' && prev != '.' && prev != '!' && prev != '$') {
                // A quoted sheet name stays quoted: ODF quotes `'Q3 Sales'.A1` as A1 references do.
                val sheet = m.groupValues[1]
                val a = m.groupValues[2].uppercase(Locale.ROOT)
                val b = m.groupValues[3].uppercase(Locale.ROOT)
                val head = if (sheet.isEmpty()) "." else "${sheet}."
                out.append(if (b.isEmpty()) "[$head$a]" else "[$head$a:.$b]")
                i = m.range.last + 1; continue
            }
            out.append(ch); i++
        }
        return out.toString()
    }

    /** The data style TRUE and FALSE cells show through. */
    private const val BOOLEAN_STYLE = "Nbool"

    /** TRUE or FALSE standing alone: not a function's name, a sheet's or part of a longer name. */
    private val LOGICAL = Regex("""(?i)(TRUE|FALSE)(?![A-Za-z0-9_.!]|\s*\()""")

    /** Automatic styles for columns, rows, cells and the number formats they reference. */
    private class CellStyles {
        private val parts = LinkedHashMap<String, String>()
        private val cells = LinkedHashMap<String, String>()
        private val dataStyles = LinkedHashMap<String, String>()
        private val spans = LinkedHashMap<String, String>()

        /** A column style [factor] times the default wide; [pageBreak]: a manual page break before it (`fo:break-before`). */
        fun column(factor: Float, pageBreak: Boolean = false): String {
            val name = "co${(factor * 100).toInt()}" + (if (pageBreak) "b" else "")
            parts.getOrPut(name) {
                val brk = if (pageBreak) """ fo:break-before="page"""" else ""
                """<style:style style:name="$name" style:family="table-column"><style:table-column-properties$brk style:column-width="${pt(DEF_COL_PT * factor)}"/></style:style>"""
            }
            return name
        }

        /**
         * A row style [factor] times the default tall: a height the row was given, or ([fitted]) the one its content needs;
         * [pageBreak]: a manual page break before it.
         */
        fun row(factor: Float, fitted: Boolean = false, pageBreak: Boolean = false): String {
            val name = (if (fitted) "roa" else "ro") + "${(factor * 100).toInt()}" + (if (pageBreak) "b" else "")
            parts.getOrPut(name) {
                val brk = if (pageBreak) """ fo:break-before="page"""" else ""
                """<style:style style:name="$name" style:family="table-row"><style:table-row-properties style:row-height="${pt(DEF_ROW_PT * factor)}"$brk style:use-optimal-row-height="$fitted"/></style:style>"""
            }
            return name
        }

        /** Row [r]'s style: its own height, else fitted to its content (the default when nothing needs more); a manual break before it. */
        fun rowOf(sheet: Sheet, r: Int): String {
            val brk = r in sheet.print.rowBreaks
            return sheet.rowHeights[r]?.let { row(it, pageBreak = brk) } ?: row(sheet.autoRowHeights[r] ?: 1f, fitted = true, pageBreak = brk)
        }

        private fun textProps(f: com.therealityteam.aeropages.model.CharacterFormat): String = buildString {
            if (f.bold) append(OdfPackage.weightAttr("bold"))
            if (f.italic) append(OdfPackage.italicAttr("italic"))
            if (f.underline) append("""style:text-underline-style="solid" style:text-underline-width="auto" """)
            if (f.strikethrough) append("""style:text-line-through-style="solid" """)
            f.fontSizePt?.let { append(OdfPackage.sizeAttr(pt(it))) }
            f.fontFamily?.let { append("""style:font-name="${escape(it)}" """) }
            f.color?.let { append("""fo:color="${color(it)}" """) }
        }.trim()

        /** A text-family style for a rich run inside a cell, or null when the run adds nothing. */
        fun span(f: com.therealityteam.aeropages.model.CharacterFormat): String? {
            val props = textProps(f)
            if (props.isEmpty()) return null
            return spans.getOrPut(props) {
                val name = "T${spans.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="text"><style:text-properties $props/></style:style>"""
                name
            }
        }

        /** Whether [c] has any formatting of its own, i.e. its style is not a blank cell's. */
        fun formatted(c: Cell): Boolean = key(c) != plainKey

        private val plainKey by lazy { key(Cell()) }

        private fun key(c: Cell): String = "${dataStyle(c)}|${textProps(c.format)}|${cellProps(c)}|${paraProps(c)}"

        private fun cellProps(c: Cell): String = buildString {
            if (c.unlocked) append("""style:cell-protect="none" """)
            c.format.highlight?.let { append("""fo:background-color="${color(it)}" """) }
            append("""style:vertical-align="${vAlign(c.vAlign)}" """)
            if (c.wrap) append("""fo:wrap-option="wrap" """)
            // Orientation: turned counterclockwise (−45° is 315), or one character under another, as LibreOffice writes it.
            if (c.stacked) append("""style:direction="ttb" """)
            else if (c.rotation != 0) append("""style:rotation-angle="${(c.rotation + 360) % 360}" style:rotation-align="none" """)
            // Fill repeats the content across the cell; Shrink to fit makes the type smaller to fit (LibreOffice's names).
            if (c.spread == com.therealityteam.aeropages.model.CellSpread.FILL) append("""style:repeat-content="true" """)
            if (c.shrink) append("""style:shrink-to-fit="true" """)
            for ((side, line) in listOf("top" to c.borderTop, "bottom" to c.borderBottom, "left" to c.borderLeft, "right" to c.borderRight)) {
                if (line == null) continue
                append("""fo:border-$side="${OdfBorders.valueOf(line)}" """)
                if (line.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) append("""style:border-line-width-$side="${OdfBorders.DOUBLE_WIDTHS}" """)
            }
            // General alignment follows the value's type (numbers right, text left); a set one is fixed.
            append(if (c.align == null) """style:text-align-source="value-type" """ else """style:text-align-source="fix" """)
        }.trim()

        // An indent is a left margin of 7.5 pt a level, whichever side the text sits on (LibreOffice's mapping of Excel's).
        private fun paraProps(c: Cell): String = listOfNotNull(
            c.align?.let { """fo:text-align="${align(it)}"""" },
            """css3t:text-justify="distribute"""".takeIf { c.spread == com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED },
            c.indent.takeIf { it > 0 }?.let { """fo:margin-left="${String.format(java.util.Locale.ROOT, "%.2fpt", it * Cell.INDENT_PT)}"""" },
        ).joinToString(" ")

        fun cell(c: Cell): String {
            val dataStyle = dataStyle(c)
            val textProps = textProps(c.format)
            val cellProps = cellProps(c)
            val paraProps = paraProps(c)
            val key = "$dataStyle|$textProps|$cellProps|$paraProps"
            return cells.getOrPut(key) {
                val name = "ce${cells.size + 1}"
                val dataAttr = if (dataStyle.isEmpty()) "" else """ style:data-style-name="$dataStyle""""
                parts[name] = """<style:style style:name="$name" style:family="table-cell"$dataAttr>""" +
                    """<style:table-cell-properties $cellProps/>""" + (if (paraProps.isEmpty()) "" else """<style:paragraph-properties $paraProps/>""") +
                    (if (textProps.isEmpty()) "" else """<style:text-properties $textProps/>""") +
                    "</style:style>"
                name
            }
        }

        /** Format codes written so far, by the name of the style that shows each ("" when none can). */
        private val codeStyles = HashMap<String, String>()

        /**
         * The data style showing [c]'s number format: its format code as the number styles
         * LibreOffice writes for one ([FormatCode.toOdf]) — else, or for a plain code, the app's
         * plain style for its format.
         */
        private fun dataStyle(c: Cell): String {
            // TRUE and FALSE show as such through a boolean style (with a number style they would show 1 and 0).
            if (c.storedLogical() != null) {
                parts.getOrPut(BOOLEAN_STYLE) { """<number:boolean-style style:name="$BOOLEAN_STYLE"><number:boolean/></number:boolean-style>""" }
                return BOOLEAN_STYLE
            }
            val code = c.formatCode
            if (code != null) {
                val plain = FormatCode.plain(code)
                if (plain != null) return plainStyle(plain.first, plain.second)
                val named = codeStyles.getOrPut(code) {
                    val name = "Nc${codeStyles.size + 1}"
                    FormatCode.toOdf(code, name)?.let { xml ->
                        xml.forEachIndexed { i, style -> parts[if (i == xml.lastIndex) name else "${name}P$i"] = style }
                        name
                    } ?: ""
                }
                if (named.isNotEmpty()) return named
            }
            return plainStyle(c.numberFormat, c.decimals)
        }

        /** A data style per plain number format (ODF's equivalent of a number format code). */
        private fun plainStyle(format: NumberFormat, places: Int): String {
            val decimals = if (places >= 0) places else null
            val name = when (format) {
                NumberFormat.GENERAL -> return ""
                NumberFormat.NUMBER2 -> "N2_${decimals ?: 2}"
                NumberFormat.THOUSANDS -> "Nk_${decimals ?: 0}"
                NumberFormat.PERCENT -> "Np_${decimals ?: 1}"
                NumberFormat.CURRENCY_CNY -> "Ncny_${decimals ?: 2}"
                NumberFormat.CURRENCY_USD -> "Nusd_${decimals ?: 2}"
                NumberFormat.SCIENTIFIC -> "Nsci_${decimals ?: 2}"
                NumberFormat.DATE -> "Ndate"
                NumberFormat.TIME -> "Ntime"
                NumberFormat.DATE_TIME -> "Ndatetime"
            }
            dataStyles.getOrPut(name) {
                val d = decimals
                val xml = when (format) {
                    NumberFormat.NUMBER2 -> """<number:number-style style:name="$name"><number:number number:decimal-places="${d ?: 2}" number:min-integer-digits="1"/></number:number-style>"""
                    NumberFormat.THOUSANDS -> """<number:number-style style:name="$name"><number:number number:decimal-places="${d ?: 0}" number:min-integer-digits="1" number:grouping="true"/></number:number-style>"""
                    NumberFormat.PERCENT -> """<number:percentage-style style:name="$name"><number:number number:decimal-places="${d ?: 1}" number:min-integer-digits="1"/><number:text>%</number:text></number:percentage-style>"""
                    NumberFormat.CURRENCY_CNY -> """<number:currency-style style:name="$name"><number:currency-symbol number:language="zh" number:country="CN">¥</number:currency-symbol><number:number number:decimal-places="${d ?: 2}" number:min-integer-digits="1" number:grouping="true"/></number:currency-style>"""
                    NumberFormat.CURRENCY_USD -> """<number:currency-style style:name="$name"><number:currency-symbol number:language="en" number:country="US">${'$'}</number:currency-symbol><number:number number:decimal-places="${d ?: 2}" number:min-integer-digits="1" number:grouping="true"/></number:currency-style>"""
                    NumberFormat.SCIENTIFIC -> """<number:number-style style:name="$name"><number:scientific-number number:decimal-places="${d ?: 2}" number:min-integer-digits="1" number:min-exponent-digits="2"/></number:number-style>"""
                    NumberFormat.DATE -> """<number:date-style style:name="$name"><number:year number:style="long"/><number:text>-</number:text><number:month number:style="long"/><number:text>-</number:text><number:day number:style="long"/></number:date-style>"""
                    NumberFormat.TIME -> """<number:time-style style:name="$name"><number:hours/><number:text>:</number:text><number:minutes number:style="long"/></number:time-style>"""
                    else -> """<number:date-style style:name="$name"><number:year number:style="long"/><number:text>-</number:text><number:month number:style="long"/><number:text>-</number:text><number:day number:style="long"/><number:text> </number:text><number:hours number:style="long"/><number:text>:</number:text><number:minutes number:style="long"/></number:date-style>"""
                }
                parts[name] = xml
                xml
            }
            return name
        }

        fun render(): String = parts.values.joinToString("")

        private fun align(a: TextAlign) = when (a) {
            TextAlign.LEFT -> "start"; TextAlign.CENTER -> "center"; TextAlign.RIGHT -> "end"; TextAlign.JUSTIFY -> "justify"
        }

        private fun vAlign(v: VAlign) = when (v) {
            VAlign.TOP -> "top"; VAlign.MIDDLE -> "middle"; VAlign.BOTTOM -> "bottom"
        }
    }
}
