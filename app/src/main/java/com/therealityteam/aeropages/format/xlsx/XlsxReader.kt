package com.therealityteam.aeropages.format.xlsx

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.XmlNode
import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.format.attr
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.FilterCondition
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.VAlign
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.xmlpull.v1.XmlPullParser

/**
 * XLSX reader built on the XmlPull parser. Sheet order and names come from workbook.xml
 * (resolved through workbook.xml.rels); shared strings and worksheet cells are streamed
 * (real sheets carry 100k+ cells), everything else (styles, comments, charts, conditional
 * formats, validations) is read from small DOM trees. Elements are matched by local name,
 * so any namespace prefixes, attribute order or formatting a producer picks are accepted.
 * Formulas keep their text (shared formulas are expanded per follower cell).
 */
/**
 * Excel's default column width as xlsx files store it: 8.43 characters of Calibri 11 plus the 5 px
 * of padding the file's `width` includes — 64 px. Column width factors scale this.
 */
internal const val XLSX_DEFAULT_COL_WIDTH = 9.140625

object XlsxReader {

    private const val WORKBOOK = "xl/workbook.xml"
    private val SHEET_NUMBER = Regex("sheet(\\d+)\\.xml")

    fun read(bytes: ByteArray): Workbook {
        val files = OfficeXml.unzip(bytes)
        val rootRels = XmlPull.rels(files["_rels/.rels"])
        val main = rootRels.firstOrNull { it.type.endsWith("/officeDocument") }
        val workbookPart = main?.let { XmlPull.resolve("", it.target) } ?: WORKBOOK
        val workbook = XmlPull.parse(files[workbookPart])?.takeIf { it.name == "workbook" }
        val rels = XmlPull.rels(files[XmlPull.relsOf(workbookPart)])
        fun related(type: String, fallback: String): ByteArray? {
            val rel = rels.firstOrNull { it.type.endsWith("/$type") } ?: return files[XmlPull.resolve(workbookPart, fallback)]
            val part = XmlPull.partName(files, workbookPart, rel.target)
            if (part == null) XmlPull.noteSalvaged()
            return files[part]
        }
        val shared = com.therealityteam.aeropages.platform.Trace.section("xlsx.sst") {
            related("sharedStrings", "sharedStrings.xml")?.let { parseSharedStrings(it) } ?: emptyList()
        }
        val stylesRoot = XmlPull.parse(related("styles", "styles.xml"))
        val styles = com.therealityteam.aeropages.platform.Trace.section("xlsx.styles") { stylesRoot?.let { parseStyles(it) } ?: emptyList() }
        val theme by lazy {
            val dir = XmlPull.resolve(workbookPart, "theme") + "/"
            val bytes = related("theme", "theme/theme1.xml") ?: files.keys.filter { it.startsWith(dir) }.minOrNull()?.let(files::get)
            PptxReader.paletteOf(XmlPull.parse(bytes))
        }
        val relMap = rels.associateBy { it.id }
        // The looks conditional formats give (dxf: fill, text colour, font traits).
        val dxfs = stylesRoot?.let { parseDxfs(it) { theme } } ?: emptyList()
        val sheets = ArrayList<Sheet>()
        fun addSheet(part: String, name: String) {
            val linkIds = HashMap<Long, String>() // cell key → hyperlink r:id, resolved through the sheet's rels
            val sheet = com.therealityteam.aeropages.platform.Trace.section("xlsx.sheet") { parseWorksheet(files.getValue(part), name, shared, styles, dxfs, linkIds) { theme } }
            attachSheetExtras(sheet, part, files, linkIds, dxfs) { theme }
            sheets.add(sheet)
        }
        workbook?.child("sheets")?.children("sheet")?.forEach { s ->
            val name = s.attr("name")
            val rel = relMap[s.attr("id")]
            if (rel?.type?.endsWith("/chartsheet") == true) return@forEach // The unsupported-content guard handles chart sheets.
            val part = rel?.takeIf { it.type.endsWith("/worksheet") }?.let { XmlPull.partName(files, workbookPart, it.target) }
            if (part == null || name == null) {
                XmlPull.noteSalvaged()
            } else {
                addSheet(part, name)
                sheets.last().state = com.therealityteam.aeropages.model.SheetState.ofXlsx(s.attr("state"))
            }
        }
        if (sheets.isEmpty()) {
            // Recover the conventional numbered parts of an incomplete package, always as a protected original.
            val directory = XmlPull.resolve(workbookPart, "worksheets")
            val parts = files.keys.filter { it.substringBeforeLast('/', "") == directory && SHEET_NUMBER.matches(it.substringAfterLast('/')) }
                .sortedBy { SHEET_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            require(parts.isNotEmpty()) { "The workbook contains no readable worksheets" }
            XmlPull.noteSalvaged()
            parts.forEach { addSheet(it, "Sheet${sheets.size + 1}") }
        }
        val wb = com.therealityteam.aeropages.platform.Trace.section("xlsx.build") { Workbook(sheets) }
        // Protect Workbook (structure, and its password).
        workbook?.descendants("workbookProtection")?.firstOrNull()?.let { node -> com.therealityteam.aeropages.format.ProtectionXml.readXlsxWorkbook({ node.attr(it) }, wb) }
        // Defined names from workbook.xml; Excel's built-in `_xlnm.Print_Area` becomes the sheet's print area.
        workbook?.descendants("definedName")?.forEach { d ->
            val n = d.attr("name") ?: return@forEach
            val ref = d.text.trim()
            if (n == "_xlnm.Print_Area") {
                val sheet = d.attr("localSheetId")?.toIntOrNull()?.let { sheets.getOrNull(it) } ?: return@forEach
                sheet.printArea = parsePrintArea(ref)
            } else if (n == "_xlnm.Print_Titles") {
                // Rows repeated at the top ($1:$2) and columns at the left ($A:$B), either or both.
                val sheet = d.attr("localSheetId")?.toIntOrNull()?.let { sheets.getOrNull(it) } ?: return@forEach
                val (rows, cols) = parsePrintTitles(ref)
                sheet.print = sheet.print.copy(titleRows = rows, titleCols = cols)
            } else if (!n.startsWith("_xlnm.")) wb.names[n] = ref
        }
        val core = rootRels.firstOrNull { it.type.endsWith("/core-properties") }?.let { XmlPull.resolve("", it.target) }
        wb.props = com.therealityteam.aeropages.format.OoxmlCore.read(files[core ?: com.therealityteam.aeropages.format.OoxmlCore.PART])
        return wb
    }

    /** A shared / inline string: its text plus the rich runs when `<r>` runs carry formatting. */
    internal class SharedString(val text: String, val runs: List<com.therealityteam.aeropages.model.TextRun>?)

    /** xl/sharedStrings.xml, streamed: one string per `<si>` (plain `<t>` and rich `<r><t>` runs; phonetic `<rPh>` hints skipped). */
    private fun parseSharedStrings(bytes: ByteArray): List<SharedString> = XmlPull.recover { relaxed ->
        val out = ArrayList<SharedString>()
        out to XmlPull.forEachStart(bytes, relaxed) { p -> if (p.name == "si") out.add(richOf(XmlPull.readSubtree(p))) }
    }

    /**
     * An `<si>` / `<is>` element → text and runs. Runs are kept only when at least one `<r>`
     * carries an `<rPr>` (Excel writes plain `<t>` otherwise); phonetic `<rPh>` is skipped.
     */
    internal fun richOf(node: XmlNode): SharedString {
        val runs = ArrayList<com.therealityteam.aeropages.model.TextRun>()
        var formatted = false
        for (child in node.children) {
            when (child.name) {
                "t" -> runs.add(com.therealityteam.aeropages.model.TextRun(child.text, com.therealityteam.aeropages.model.CharacterFormat()))
                "r" -> {
                    val f = com.therealityteam.aeropages.model.CharacterFormat()
                    child.child("rPr")?.let { rPr ->
                        formatted = true
                        fun on(name: String) = rPr.child(name)?.let { it.attr("val").let { v -> v == null || v == "1" || v == "true" } } ?: false
                        f.bold = on("b"); f.italic = on("i"); f.strikethrough = on("strike")
                        rPr.child("u")?.let { f.underline = it.attr("val") != "none" }
                        rPr.child("sz")?.attr("val")?.toFloatOrNull()?.let { f.fontSizePt = it }
                        rPr.child("rFont")?.attr("val")?.let { f.fontFamily = it }
                        rgbOf(rPr.child("color")?.attr("rgb"))?.let { f.color = it }
                    }
                    val t = child.child("t")?.text ?: ""
                    if (t.isNotEmpty()) runs.add(com.therealityteam.aeropages.model.TextRun(t, f))
                }
            }
        }
        val text = runs.joinToString("") { it.text }
        return SharedString(text, runs.takeIf { formatted && it.isNotEmpty() })
    }

    /** A shared-formula follower met before its master; expanded once the whole sheet has been streamed. */
    private class PendingCell(val row: Int, val col: Int, val si: String, val fallback: String, val styleIdx: Int?)

    /** Streams one worksheet part (strictly, then relaxed when malformed). */
    /** Excel's Print_Titles: `Sheet1!$1:$2`, `Sheet1!$A:$B`, or both comma-separated — the rows and columns repeated on each page. */
    internal fun parsePrintTitles(ref: String): Pair<IntRange?, IntRange?> {
        var rows: IntRange? = null; var cols: IntRange? = null
        for (part in ref.split(',')) {
            val r = part.substringAfterLast('!').replace("$", "").trim()
            val bits = r.split(':')
            val a = bits.getOrElse(0) { "" }; val b = bits.getOrElse(1) { a }
            val ra = a.toIntOrNull(); val rb = b.toIntOrNull()
            if (ra != null && rb != null && ra >= 1) rows = (ra - 1)..(rb - 1).coerceAtLeast(ra - 1)
            else if (a.isNotEmpty() && a.all { it.isLetter() } && b.all { it.isLetter() }) {
                val ca = A1.parse(a + "1")?.second; val cb = A1.parse(b.ifEmpty { a } + "1")?.second
                if (ca != null && cb != null) cols = ca..cb.coerceAtLeast(ca)
            }
        }
        return rows to cols
    }

    /** `Sheet1!$A$1:$C$10` (sheet part optional, `$` optional) → [PrintArea]; null when unparseable. */
    internal fun parsePrintArea(ref: String): com.therealityteam.aeropages.model.PrintArea? {
        val range = ref.substringAfterLast('!').replace("$", "").trim()
        val parts = range.split(':')
        val (r1, c1) = A1.parse(parts[0]) ?: return null
        val (r2, c2) = if (parts.size > 1) A1.parse(parts[1]) ?: return null else (r1 to c1)
        return com.therealityteam.aeropages.model.PrintArea(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
    }

    private fun parseWorksheet(
        bytes: ByteArray, name: String, shared: List<SharedString>, styles: List<XfStyle>, dxfs: List<com.therealityteam.aeropages.model.CondStyle?>, linkIds: MutableMap<Long, String>,
        theme: () -> com.therealityteam.aeropages.format.pptx.PptxReader.Palette,
    ): Sheet = XmlPull.recover { relaxed -> streamWorksheet(bytes, relaxed, name, shared, styles, dxfs, linkIds, theme) }

    /** One streaming walk over a worksheet: panes, column widths, row heights, cells, merges, conditional formats, validations, hyperlinks, page setup. */
    private fun streamWorksheet(
        bytes: ByteArray, relaxed: Boolean, name: String, shared: List<SharedString>, styles: List<XfStyle>, dxfs: List<com.therealityteam.aeropages.model.CondStyle?>,
        linkIds: MutableMap<Long, String>, theme: () -> com.therealityteam.aeropages.format.pptx.PptxReader.Palette,
    ): Pair<Sheet, Boolean> {
        val sheet = Sheet(name = name)
        var maxRow = 0
        var maxCol = 0
        var paneSeen = false
        var defaultColWidth = XLSX_DEFAULT_COL_WIDTH
        var curRow = -1   // zero-based row of the current <row>; cells lacking an r attribute are placed positionally
        var nextCol = 0
        // Shared formulas: masters carry t="shared" ref + si + text; followers are empty
        // <f t="shared" si="N"/> and must be expanded by their offset from the master.
        val masters = HashMap<String, Triple<Int, Int, String>>()
        val outlineRows = HashMap<Int, Int>()          // row → outlineLevel
        val collapsedRows = HashSet<Int>()             // rows with collapsed="1" (a folded group's summary row)
        var hasAutoFilter = false
        // How it prints: margins, scale or fit (fit only with sheetPr/pageSetUpPr@fitToPage), options, header and footer.
        var print = com.therealityteam.aeropages.model.SheetPrint()
        var fitToPage = false
        val filterCol = FilterColumnReader(dxfs)
        val outlineCols = HashMap<Int, Int>()          // column → outlineLevel
        val collapsedCols = HashSet<Int>()             // columns with collapsed="1" (a folded group's summary column)
        val pending = ArrayList<PendingCell>()
        val condRules = CondRules()
        var formattedEmpties = 0
        fun place(row: Int, col: Int, text: String, styleIdx: Int?, runs: List<com.therealityteam.aeropages.model.TextRun>? = null) {
            if (text.isEmpty()) {
                // An empty cell keeps a style that shows something (a fill, borders, alignment, a number format …), up to
                // a limit that keeps a sheet formatted down a million rows from filling memory.
                val style = styleIdx?.takeIf { it != 0 }?.let { styles.getOrNull(it) } ?: return
                if (!style.visible || formattedEmpties >= MAX_FORMATTED_EMPTIES) return
                formattedEmpties++
                style.applyTo(sheet.ensure(row, col))
                return
            }
            val cell = sheet.ensure(row, col)
            cell.text = text
            // A cell that names no style has the first (Excel's Normal): its alignment too.
            val style = styles.getOrNull(styleIdx ?: 0)
            style?.applyTo(cell)
            // Excel spells out every run's face and size; the ones the cell has anyway are inherited, not the run's own.
            if (runs != null && !text.startsWith("=")) cell.runs = runs.map { r ->
                com.therealityteam.aeropages.model.TextRun(r.text, r.format.copyOf().apply {
                    if (fontFamily != null && fontFamily == style?.baseFont) fontFamily = null
                    if (fontSizePt != null && fontSizePt == style?.baseSize) fontSizePt = null
                })
            }.toMutableList()
            if (row > maxRow) maxRow = row
            if (col > maxCol) maxCol = col
        }
        var breaksOfRows = true
        var worksheet = false
        val complete = XmlPull.forEachStart(bytes, relaxed) { p ->
            if (p.depth == 1) worksheet = p.name == "worksheet"
            when (p.name) {
                "sheetView" -> {
                    if (p.attr("showRowColHeaders").let { it == "0" || it == "false" }) sheet.showHeadings = false
                    if (p.attr("showGridLines").let { it == "0" || it == "false" }) sheet.showGridlines = false
                    if (p.attr("view") == "pageBreakPreview") sheet.pageBreakPreview = true
                    fun zoomOf(a: String) = p.attr(a)?.toIntOrNull()?.takeIf { it > 0 }?.coerceIn(Sheet.MIN_ZOOM, Sheet.MAX_ZOOM)
                    // Each view's own zoom, else the shown one for the view the sheet is in.
                    (zoomOf("zoomScaleNormal") ?: zoomOf("zoomScale")?.takeIf { !sheet.pageBreakPreview })?.let { sheet.zoom = it }
                    (zoomOf("zoomScaleSheetLayoutView") ?: zoomOf("zoomScale")?.takeIf { sheet.pageBreakPreview })?.let { sheet.previewZoom = it }
                }
                // Manual page breaks: above a row (rowBreaks) or left of a column (colBreaks) — the automatic ones some files list are not kept.
                "rowBreaks" -> breaksOfRows = true
                "colBreaks" -> breaksOfRows = false
                "brk" -> if (p.attr("man").let { it == "1" || it == "true" }) p.attr("id")?.toIntOrNull()?.takeIf { it > 0 }?.let { id ->
                    print = if (breaksOfRows) print.copy(rowBreaks = (print.rowBreaks + id).distinct().sorted())
                        else print.copy(colBreaks = (print.colBreaks + id).distinct().sorted())
                }
                // A filter owns hidden rows only when the sheet has one; otherwise they were hidden by hand.
                "autoFilter" -> {
                    hasAutoFilter = true
                    p.attr("ref")?.let { A1.parseRange(it) }?.let { (r1, c1, r2, c2) ->
                        if (c2 < Sheet.MAX_COLS) sheet.autoFilter = com.therealityteam.aeropages.model.AutoFilter(r1, c1, r2, c2)
                    }
                }
                // Its criteria: a column's shown values (blanks too when blank="1"), or a custom comparison.
                "filterColumn" -> {
                    filterCol.flush(sheet)
                    val af = sheet.autoFilter
                    filterCol.col = if (af != null) p.attr("colId")?.toIntOrNull()?.let { af.c1 + it } else null
                }
                "filters", "filter", "customFilters", "customFilter", "top10", "dynamicFilter", "colorFilter" -> filterCol.take(p.name, p::attr)
                "pageSetup" -> {
                    sheet.landscape = p.attr("orientation") == "landscape"
                    // Its paper: a size code, or — as LibreOffice writes it (ECMA-376's later form) — its width and height ("297mm", "8.5in").
                    val byCode = p.attr("paperSize")?.toIntOrNull()
                    fun length(a: String): Float? = p.attr(a)?.trim()?.let { v ->
                        val n = v.dropLastWhile { it.isLetter() }.toFloatOrNull() ?: return@let null
                        when (v.takeLastWhile { it.isLetter() }) { "mm" -> n * 72f / 25.4f; "cm" -> n * 72f / 2.54f; "in" -> n * 72f; "pt" -> n; else -> null }
                    }
                    val bySize = if (byCode == null) length("paperWidth")?.let { w -> length("paperHeight")?.let { h -> com.therealityteam.aeropages.model.Paper.ofSize(w, h) } } else null
                    sheet.paper = bySize ?: com.therealityteam.aeropages.model.Paper.fromXlsxId(byCode)
                    print = print.copy(scale = p.attr("scale")?.toIntOrNull()?.coerceIn(10, 400) ?: 100,
                        fitWidth = p.attr("fitToWidth")?.toIntOrNull() ?: 1, fitHeight = p.attr("fitToHeight")?.toIntOrNull() ?: 1,
                        overThenDown = p.attr("pageOrder") == "overThenDown",
                        comments = com.therealityteam.aeropages.model.NotePrint.of(p.attr("cellComments")),
                        blackAndWhite = p.attr("blackAndWhite").let { it == "1" || it == "true" },
                        draft = p.attr("draft").let { it == "1" || it == "true" },
                        errors = com.therealityteam.aeropages.model.ErrorPrint.of(p.attr("errors")))
                }
                "pageSetUpPr" -> fitToPage = p.attr("fitToPage").let { it == "1" || it == "true" }
                "pageMargins" -> {
                    fun inches(a: String, d: Float) = p.attr(a)?.toFloatOrNull()?.let { Math.round(it * 7200f) / 100f } ?: d
                    print = print.copy(marginLeft = inches("left", print.marginLeft), marginRight = inches("right", print.marginRight),
                        marginTop = inches("top", print.marginTop), marginBottom = inches("bottom", print.marginBottom),
                        marginHeader = inches("header", print.marginHeader), marginFooter = inches("footer", print.marginFooter))
                }
                "printOptions" -> {
                    fun on(a: String) = p.attr(a).let { it == "1" || it == "true" }
                    print = print.copy(gridlines = on("gridLines"), headings = on("headings"), centerH = on("horizontalCentered"), centerV = on("verticalCentered"))
                }
                "oddHeader" -> print = print.copy(header = com.therealityteam.aeropages.model.SheetPrint.fromExcel(XmlPull.readSubtree(p).text))
                "oddFooter" -> print = print.copy(footer = com.therealityteam.aeropages.model.SheetPrint.fromExcel(XmlPull.readSubtree(p).text))
                // A hyperlink: an address (r:id, through the sheet's relationships; a place in it after "#"),
                // or a place in this workbook alone (`location`: `Sheet2!A1`, a defined name) — `#Sheet2!A1`.
                "hyperlink" -> {
                    val (r, c) = p.attr("ref")?.let { A1.parse(it.substringBefore(':')) } ?: return@forEachStart
                    if (c >= Sheet.MAX_COLS) return@forEachStart
                    val rid = p.attr(XmlPull.R_NS, "id")?.takeIf { it.isNotEmpty() }
                    val location = p.attr("location")?.takeIf { it.isNotBlank() }?.let { com.therealityteam.aeropages.model.CellLinks.excelPlace(it) }
                    if (rid != null) linkIds[sheet.key(r, c)] = rid + (location?.let { "#$it" } ?: "")
                    else if (location != null) sheet.ensure(r, c).link = "#$location"
                }
                // Freeze panes from sheetView/pane (the first pane wins).
                "pane" -> if (!paneSeen) {
                    paneSeen = true
                    sheet.freezeCols = p.attr("xSplit")?.toIntOrNull() ?: 0
                    sheet.freezeRows = p.attr("ySplit")?.toIntOrNull() ?: 0
                }
                // The sheet's own default column width, which columns written out anyway repeat.
                "sheetFormatPr" -> p.attr("defaultColWidth")?.toDoubleOrNull()?.let { defaultColWidth = it }
                // Columns: width (factor = width / 9.140625 — Excel's default 8.43 characters as the file
                // stores it, padding included: 64 px), hidden, outline level and the collapsed flag of a
                // folded group's summary column. A width not marked custom that only repeats the sheet's
                // default (LibreOffice writes one for every column) is no width of its own.
                "col" -> {
                    val min = p.attr("min")?.toIntOrNull() ?: return@forEachStart
                    val max = p.attr("max")?.toIntOrNull() ?: min
                    val custom = p.attr("customWidth").let { it == "1" || it == "true" }
                    val factor = p.attr("width")?.toDoubleOrNull()
                        ?.takeIf { custom || kotlin.math.abs(it - defaultColWidth) > 0.5 }
                        ?.let { (it / XLSX_DEFAULT_COL_WIDTH).toFloat() }
                    val hidden = p.attr("hidden").let { it == "1" || it == "true" }
                    val level = p.attr("outlineLevel")?.toIntOrNull()?.takeIf { it > 0 }
                    val collapsed = p.attr("collapsed").let { it == "1" || it == "true" }
                    for (c in (min - 1) until max.coerceAtMost(Sheet.MAX_COLS)) {
                        if (factor != null && factor != 1f && factor > 0f) sheet.colWidths[c] = factor
                        if (hidden) sheet.hiddenCols.add(c)
                        if (level != null) outlineCols[c] = level
                        if (collapsed) collapsedCols.add(c)
                    }
                }
                // Row heights: factor = ht / 15 (Excel's default row height in points) for a height the row
                // was given (customHeight). One not marked custom is the row fitted to its content, which
                // is fitted again here from the cells ([RowFit]) — LibreOffice writes one for every row.
                "row" -> {
                    curRow = p.attr("r")?.toIntOrNull()?.let { it - 1 } ?: (curRow + 1)
                    nextCol = 0
                    val customHeight = p.attr("customHeight").let { it == "1" || it == "true" }
                    if (customHeight) p.attr("ht")?.toDoubleOrNull()?.let { sheet.rowHeights[curRow] = (it / 15.0).toFloat() }
                    val hidden = p.attr("hidden")
                    if (hidden == "1" || hidden == "true") sheet.hiddenRows.add(curRow)
                    p.attr("outlineLevel")?.toIntOrNull()?.takeIf { it > 0 }?.let { outlineRows[curRow] = it }
                    if (p.attr("collapsed").let { it == "1" || it == "true" }) collapsedRows.add(curRow)
                }
                "c" -> {
                    val ref = p.attr("r")
                    val (row, col) = if (ref != null) (A1.parse(ref) ?: return@forEachStart) else (curRow.coerceAtLeast(0) to nextCol)
                    nextCol = col + 1
                    val type = p.attr("t")
                    val styleIdx = p.attr("s")?.toIntOrNull()
                    var formula: String? = null
                    var sharedSi: String? = null
                    var value: String? = null
                    var inline: String? = null
                    var inlineRuns: List<com.therealityteam.aeropages.model.TextRun>? = null
                    // Consume the cell's children: <f> (formula, maybe shared), <v> (value), <is> (inline string).
                    while (true) {
                        val e = p.next()
                        if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.name == "c")) break
                        if (e != XmlPullParser.START_TAG) continue
                        when (p.name) {
                            "f" -> {
                                val si = if (p.attr("t") == "shared") p.attr("si") else null
                                val text = XmlPull.readText(p).replace("_xlfn.", "").replace("_xlws.", "") // Excel's future-function prefixes
                                if (text.isNotEmpty()) {
                                    formula = text
                                    if (si != null) masters[si] = Triple(row, col, text)
                                } else sharedSi = si
                            }
                            "v" -> value = XmlPull.readText(p)
                            "is" -> richOf(XmlPull.readSubtree(p)).let { inline = it.text; inlineRuns = it.runs }
                        }
                    }
                    if (col >= Sheet.MAX_COLS) return@forEachStart // beyond the grid's column limit (key packing)
                    val sharedEntry = if (type == "s") value?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } else null
                    if (type == "s" && sharedEntry == null) XmlPull.noteSalvaged()
                    val fallback = when (type) {
                        "s" -> sharedEntry?.text ?: ""
                        "inlineStr" -> inline ?: ""
                        // A logical cell: TRUE or FALSE, as the app holds one typed in (not the 1 or 0 written).
                        "b" -> when (value?.trim()) { "1", "true" -> "TRUE"; "0", "false" -> "FALSE"; else -> value ?: "" }
                        else -> value ?: "" // n / str / e: the cached value as written
                    }
                    val richRuns = if (type == "s") sharedEntry?.runs else if (type == "inlineStr") inlineRuns else null
                    val f = formula
                    val si = sharedSi
                    when {
                        f != null -> place(row, col, "=$f", styleIdx)
                        si != null -> {
                            val m = masters[si]
                            if (m != null) place(row, col, "=" + shiftRefs(m.third, row - m.first, col - m.second), styleIdx)
                            else pending.add(PendingCell(row, col, si, fallback, styleIdx))
                        }
                        else -> place(row, col, fallback, styleIdx, richRuns)
                    }
                }
                // Merged regions.
                "mergeCell" -> p.attr("ref")?.split(':')?.takeIf { it.size == 2 }?.let { (a, b) ->
                    val (r1, c1) = refToRowCol(a)
                    val (r2, c2) = refToRowCol(b)
                    sheet.merges.add(MergeRegion(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2)))
                }
                "conditionalFormatting" -> parseCondFormat(XmlPull.readSubtree(p), dxfs, condRules)
                "dataValidation" -> parseValidation(XmlPull.readSubtree(p), sheet)
                "sheetProtection" -> com.therealityteam.aeropages.format.ProtectionXml.readXlsxSheet { p.attr(it) }?.let { sheet.protected = true; sheet.protection = it }
                // The tab's colour: its own, from Excel's indexed palette, or a theme colour — lightened or darkened by its tint.
                "tabColor" -> sheet.tabColor = colorOf(p.attr("rgb"), p.attr("indexed"), p.attr("theme"), p.attr("tint"), theme)
                "scenarios" -> XmlPull.readSubtree(p).children("scenario").forEach { sc ->
                    val name = sc.attr("name") ?: return@forEach
                    val values = LinkedHashMap<Long, String>()
                    sc.children("inputCells").forEach { ic ->
                        val (r, c) = ic.attr("r")?.let { A1.parse(it) } ?: return@forEach
                        values[r.toLong() * 1000L + c] = ic.attr("val") ?: ""
                    }
                    sheet.scenarios.add(com.therealityteam.aeropages.model.Scenario(name, values))
                }
                // Excel's 2009 extension: sparkline groups inside extLst.
                "sparklineGroup" -> parseSparklineGroup(XmlPull.readSubtree(p), sheet)
            }
        }
        pending.forEach { pc ->
            val m = masters[pc.si]
            place(pc.row, pc.col, if (m != null) "=" + shiftRefs(m.third, pc.row - m.first, pc.col - m.second) else pc.fallback, pc.styleIdx)
        }
        filterCol.flush(sheet)
        // The rules in their priority (Excel numbers them across the sheet; 1 first).
        sheet.condFormats.addAll(condRules.ordered())
        readOutline(sheet, outlineRows, collapsedRows)
        if (!hasAutoFilter && sheet.hiddenRows.isNotEmpty()) { sheet.manualHiddenRows.addAll(sheet.hiddenRows); sheet.hiddenRows.clear() }
        sheet.print = if (fitToPage) print else print.copy(fitWidth = null, fitHeight = null)
        if (sheet.rowGroups.isNotEmpty()) maxRow = maxOf(maxRow, sheet.rowGroups.maxOf { it.r2 + 1 })
        // Column outline: the same rebuild; folded columns are not hidden-by-hand ones.
        sheet.colGroups.addAll(com.therealityteam.aeropages.model.Outline.fromLevels(outlineCols, collapsedCols, sheet.hiddenCols))
        sheet.hiddenCols.removeAll(sheet.foldedCols())
        if (sheet.colGroups.isNotEmpty()) maxCol = maxOf(maxCol, sheet.colGroups.maxOf { it.r2 + 1 })
        sheet.rowCount = maxOf(sheet.rowCount, maxRow + 4)
        sheet.colCount = maxOf(sheet.colCount, maxCol + 2).coerceAtMost(Sheet.MAX_COLS)
        if (complete || relaxed) require(worksheet) { "Unreadable worksheet: $name" }
        return sheet to complete
    }

    /**
     * Rebuilds the row outline from per-row `outlineLevel`s: each maximal run of rows at level ≥ L
     * is a group at level L. A group is collapsed when its summary row (just below) carries
     * `collapsed="1"` or, failing that, when all its rows are hidden; its rows then count as folded
     * rather than filtered out.
     */
    internal fun readOutline(sheet: Sheet, levels: Map<Int, Int>, collapsedSummary: Set<Int>) {
        if (levels.isEmpty()) return
        sheet.rowGroups.addAll(com.therealityteam.aeropages.model.Outline.fromLevels(levels, collapsedSummary, sheet.hiddenRows))
        sheet.hiddenRows.removeAll(sheet.foldedRows())
    }

    /**
     * A sheet's conditional-format rules as they are read, with their priorities — and the data bars
     * whose Excel 2010 extension (matched by its id) says how long their bars run.
     */
    private class CondRules {
        val rules = ArrayList<Pair<Int, CondFormat>>()
        /** A data bar's extension id → the indexes of its rules (one per range). */
        val barIds = HashMap<String, MutableList<Int>>()

        fun ordered(): List<CondFormat> = rules.withIndex().sortedWith(compareBy({ it.value.first }, { it.index })).map { it.value.second }
    }

    /**
     * One `<conditionalFormatting>` block — or Excel 2010's form of one (`x14:conditionalFormatting`:
     * its formulas in `xm:f`, its cells in `xm:sqref`) — its rules each over every range of its sqref,
     * their formulas (written for the first range's top-left cell) moved to each range's.
     */
    private fun parseCondFormat(cf: XmlNode, dxfs: List<com.therealityteam.aeropages.model.CondStyle?>, out: CondRules) {
        val ranges = (cf.attr("sqref") ?: cf.child("sqref")?.text ?: return).split(' ').filter { it.isNotBlank() }.map { rangeOf(it) }
            .map { intArrayOf(minOf(it[0], it[2]), minOf(it[1], it[3]), maxOf(it[0], it[2]), maxOf(it[1], it[3])) }
        if (ranges.isEmpty()) return
        val first = ranges.first()
        for (rule in cf.children("cfRule")) {
            // Excel 2010's part of a data bar it also wrote the 2007 way: how long its bars run.
            val id = rule.attr("id")
            if (id != null && id in out.barIds) {
                rule.child("dataBar")?.let { db ->
                    out.barIds.getValue(id).forEach { i ->
                        val (pri, r) = out.rules[i]
                        out.rules[i] = pri to r.copy(barMin = db.attr("minLength")?.toIntOrNull() ?: 0, barMax = db.attr("maxLength")?.toIntOrNull() ?: 100)
                    }
                }
                continue
            }
            val base = condRuleOf(rule, dxfs) ?: continue
            val priority = rule.attr("priority")?.toIntOrNull() ?: Int.MAX_VALUE
            // A 2007 data bar with a 2010 extension: Excel 2010 draws it from no length to the cell's whole width.
            val extId = rule.descendants("id").firstOrNull()?.text?.trim()?.takeIf { base.type == CondType.DATA_BAR }
            ranges.forEach { r ->
                val dr = r[0] - first[0]; val dc = r[1] - first[1]
                fun moved(f: String?) = f?.let { com.therealityteam.aeropages.model.A1.shiftRefs(it, dr, dc) }
                extId?.let { out.barIds.getOrPut(it) { ArrayList() }.add(out.rules.size) }
                out.rules.add(priority to base.copy(r1 = r[0], c1 = r[1], r2 = r[2], c2 = r[3], formula1 = moved(base.formula1), formula2 = moved(base.formula2)))
            }
        }
    }

    /** One `<cfRule>` as a rule (its range set later); null for a kind this app does not know. */
    private fun condRuleOf(rule: XmlNode, dxfs: List<com.therealityteam.aeropages.model.CondStyle?>): CondFormat? {
        fun flag(v: String?, default: Boolean = false) = if (v == null) default else v == "1" || v == "true"
        val formulas = (rule.children("formula") + rule.children("f")).map { it.text.trim() }
        fun num(i: Int) = formulas.getOrNull(i)?.toDoubleOrNull()
        fun fml(i: Int) = formulas.getOrNull(i)?.takeIf { it.isNotEmpty() && it.toDoubleOrNull() == null }
        val style = rule.attr("dxfId")?.toIntOrNull()?.let { dxfs.getOrNull(it) }
        val base = CondFormat(0, 0, 0, 0, CondType.FORMULA, style = style ?: com.therealityteam.aeropages.model.CondStyle(), stopIfTrue = flag(rule.attr("stopIfTrue")))
        fun point(v: XmlNode): com.therealityteam.aeropages.model.ScalePoint {
            val raw = v.attr("val") ?: v.child("f")?.text
            val kind = when (v.attr("type")) {
                "min", "autoMin" -> com.therealityteam.aeropages.model.ScaleKind.MIN
                "max", "autoMax" -> com.therealityteam.aeropages.model.ScaleKind.MAX
                "percent" -> com.therealityteam.aeropages.model.ScaleKind.PERCENT
                "percentile" -> com.therealityteam.aeropages.model.ScaleKind.PERCENTILE
                "formula" -> com.therealityteam.aeropages.model.ScaleKind.FORMULA
                else -> if (raw?.trim()?.toDoubleOrNull() == null && raw != null) com.therealityteam.aeropages.model.ScaleKind.FORMULA else com.therealityteam.aeropages.model.ScaleKind.NUMBER
            }
            return com.therealityteam.aeropages.model.ScalePoint(kind, raw?.trim()?.toDoubleOrNull() ?: 0.0,
                raw?.takeIf { kind == com.therealityteam.aeropages.model.ScaleKind.FORMULA }, gte = flag(v.attr("gte"), true))
        }
        return when (val type = rule.attr("type")) {
            "cellIs" -> {
                val t = when (rule.attr("operator")) {
                    "greaterThan" -> CondType.GREATER
                    "lessThan" -> CondType.LESS
                    "greaterThanOrEqual" -> CondType.GREATER_EQUAL
                    "lessThanOrEqual" -> CondType.LESS_EQUAL
                    "equal" -> CondType.EQUAL
                    "notEqual" -> CondType.NOT_EQUAL
                    "between" -> CondType.BETWEEN
                    "notBetween" -> CondType.NOT_BETWEEN
                    else -> return null
                }
                base.copy(type = t, v1 = num(0) ?: 0.0, v2 = num(1) ?: 0.0, formula1 = fml(0), formula2 = fml(1))
            }
            "expression" -> base.copy(formula1 = formulas.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null)
            "containsText", "notContainsText", "beginsWith", "endsWith" -> base.copy(
                type = when (type) { "containsText" -> CondType.TEXT_CONTAINS; "notContainsText" -> CondType.TEXT_NOT_CONTAINS; "beginsWith" -> CondType.TEXT_BEGINS; else -> CondType.TEXT_ENDS },
                text = rule.attr("text") ?: return null)
            "containsBlanks" -> base.copy(type = CondType.BLANKS)
            "notContainsBlanks" -> base.copy(type = CondType.NO_BLANKS)
            "containsErrors" -> base.copy(type = CondType.ERRORS)
            "notContainsErrors" -> base.copy(type = CondType.NO_ERRORS)
            "duplicateValues" -> base.copy(type = CondType.DUPLICATES)
            "uniqueValues" -> base.copy(type = CondType.UNIQUES)
            "top10" -> base.copy(type = if (flag(rule.attr("bottom"))) CondType.BOTTOM else CondType.TOP,
                v1 = rule.attr("rank")?.toDoubleOrNull() ?: 10.0, percent = flag(rule.attr("percent")))
            "aboveAverage" -> base.copy(type = if (flag(rule.attr("aboveAverage"), true)) CondType.ABOVE_AVERAGE else CondType.BELOW_AVERAGE,
                orEqual = flag(rule.attr("equalAverage")))
            "timePeriod" -> base.copy(type = CondType.DATE, period = when (rule.attr("timePeriod")) {
                "today" -> com.therealityteam.aeropages.model.DatePeriod.TODAY
                "yesterday" -> com.therealityteam.aeropages.model.DatePeriod.YESTERDAY
                "tomorrow" -> com.therealityteam.aeropages.model.DatePeriod.TOMORROW
                "last7Days" -> com.therealityteam.aeropages.model.DatePeriod.LAST_7_DAYS
                "thisWeek" -> com.therealityteam.aeropages.model.DatePeriod.THIS_WEEK
                "lastWeek" -> com.therealityteam.aeropages.model.DatePeriod.LAST_WEEK
                "nextWeek" -> com.therealityteam.aeropages.model.DatePeriod.NEXT_WEEK
                "thisMonth" -> com.therealityteam.aeropages.model.DatePeriod.THIS_MONTH
                "lastMonth" -> com.therealityteam.aeropages.model.DatePeriod.LAST_MONTH
                "nextMonth" -> com.therealityteam.aeropages.model.DatePeriod.NEXT_MONTH
                else -> return null
            })
            "colorScale" -> {
                val scale = rule.child("colorScale") ?: return null
                val points = scale.children("cfvo").map { point(it) }
                val colors = scale.children("color").mapNotNull { argbOf(it.attr("rgb")) }
                if (points.size < 2 || colors.size != points.size) return null
                // From the lowest to the highest, two colours: the plain two-colour scale.
                val plain = points.size == 2 && points[0].kind == com.therealityteam.aeropages.model.ScaleKind.MIN && points[1].kind == com.therealityteam.aeropages.model.ScaleKind.MAX
                CondFormat(0, 0, 0, 0, CondType.COLOR_SCALE, color = colors.last(), color2 = colors.first(), stops = if (plain) emptyList() else points.zip(colors))
            }
            "dataBar" -> {
                val bar = rule.child("dataBar") ?: return null
                val points = bar.children("cfvo").map { point(it) }
                CondFormat(0, 0, 0, 0, CondType.DATA_BAR, color = bar.child("color")?.let { argbOf(it.attr("rgb")) } ?: 0xFF638EC6,
                    barEnds = points.takeIf { it.size == 2 && !(it[0].kind == com.therealityteam.aeropages.model.ScaleKind.MIN && it[1].kind == com.therealityteam.aeropages.model.ScaleKind.MAX) } ?: emptyList(),
                    barMin = bar.attr("minLength")?.toIntOrNull() ?: 10, barMax = bar.attr("maxLength")?.toIntOrNull() ?: 90,
                    showValue = flag(bar.attr("showValue"), true))
            }
            "iconSet" -> {
                val set = rule.child("iconSet") ?: return null
                val kind = com.therealityteam.aeropages.model.IconSetKind.of(set.attr("iconSet") ?: "3TrafficLights1") ?: return null
                // Equal shares of the way (Excel's defaults) are the set's own thresholds.
                val defaults = (0 until kind.count).map { com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.PERCENT, Math.round(100.0 * it / kind.count).toDouble()) }
                CondFormat(0, 0, 0, 0, CondType.ICON_SET, icons = kind,
                    iconPoints = set.children("cfvo").map { point(it) }.takeIf { it.size == kind.count && it != defaults } ?: emptyList(),
                    reverse = flag(set.attr("reverse")), showValue = flag(set.attr("showValue"), true))
            }
            else -> null
        }
    }

    /**
     * One `<dataValidation>` — or Excel 2010's form of one among the worksheet's extensions
     * (`x14:dataValidation`: formulas in `xm:f`, the cells in `xm:sqref`), which Excel uses for lists
     * on another sheet — as rules on [sheet], one per range of its sqref, each range's relative
     * references moved from the first range's top-left cell. OOXML's flags default to off; its
     * `showDropDown` hides the arrow.
     */
    private fun parseValidation(dv: XmlNode, sheet: Sheet) {
        val type = ValidationType.ofXlsx(dv.attr("type") ?: "none") ?: return
        val ranges = (dv.attr("sqref") ?: dv.child("sqref")?.text ?: return).split(' ').filter { it.isNotBlank() }
        if (ranges.isEmpty()) return
        fun formula(name: String): String? = dv.child(name)?.let { it.child("f")?.text ?: it.text }?.trim()?.takeIf { it.isNotEmpty() }
        fun flag(name: String): Boolean = dv.attr(name).let { it == "1" || it == "true" }
        val f1 = formula("formula1")
        val f2 = formula("formula2")
        val operator = ValidationOperator.ofXlsx(dv.attr("operator"))
        var min = 0.0; var max = 0.0
        var formula1: String? = null; var formula2: String? = null
        var items = emptyList<String>()
        when {
            type == ValidationType.LIST -> if (f1 != null && f1.startsWith("\"")) items = listLiteral(f1) else formula1 = f1
            type == ValidationType.CUSTOM -> formula1 = f1 ?: return
            type.compares -> {
                val n1 = f1?.toDoubleOrNull()
                if (n1 != null) min = n1 else formula1 = f1
                if (operator.twoBounds) { val n2 = f2?.toDoubleOrNull(); if (n2 != null) max = n2 else formula2 = f2 }
            }
        }
        val rule = DataValidation(
            0, 0, 0, 0, type, min, max, items, operator, formula1, formula2,
            allowBlank = flag("allowBlank"), dropdown = !flag("showDropDown"),
            showInput = flag("showInputMessage"), inputTitle = dv.attr("promptTitle") ?: "", inputMessage = dv.attr("prompt") ?: "",
            showError = flag("showErrorMessage"), errorStyle = ValidationAlertStyle.ofXlsx(dv.attr("errorStyle")),
            errorTitle = dv.attr("errorTitle") ?: "", errorMessage = dv.attr("error") ?: "",
        )
        val (fr, fc) = rangeOf(ranges[0]).let { it[0] to it[1] }
        for (range in ranges) {
            val (r1, c1, r2, c2) = rangeOf(range)
            val dr = minOf(r1, r2) - fr; val dc = minOf(c1, c2) - fc
            fun moved(f: String?) = if (f == null || (dr == 0 && dc == 0)) f else shiftRefs(f, dr, dc)
            sheet.validations.add(rule.copy(r1 = minOf(r1, r2), c1 = minOf(c1, c2), r2 = maxOf(r1, r2), c2 = maxOf(c1, c2),
                formula1 = if (type == ValidationType.LIST && items.isNotEmpty()) null else moved(rule.formula1), formula2 = moved(rule.formula2)))
        }
    }

    /** A list written out in the rule (`"Yes,No"`, a doubled quote standing for one) → its values. */
    private fun listLiteral(f: String): List<String> =
        f.removePrefix("\"").removeSuffix("\"").replace("\"\"", "\"").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Resolve worksheet rels for hyperlinks, comments + charts (via the drawing part) and attach them to [sheet]. */
    private fun attachSheetExtras(sheet: Sheet, sheetPart: String, files: Map<String, ByteArray>, linkIds: Map<Long, String>,
                                  dxfs: List<com.therealityteam.aeropages.model.CondStyle?>, theme: () -> PptxReader.Palette) {
        val rels = XmlPull.rels(files[XmlPull.relsOf(sheetPart)] ?: return)
        fun part(type: String): String? =
            rels.firstOrNull { it.type.endsWith("/$type") }?.let { XmlPull.partName(files, sheetPart, it.target) }
        // Tables (xl/tables/tableN.xml), then their formulas as Excel shows them ([@Qty]).
        rels.filter { it.type.endsWith("/table") }.forEach { rel ->
            val tablePart = XmlPull.partName(files, sheetPart, rel.target) ?: return@forEach
            XmlPull.parse(files[tablePart])?.let { readTable(sheet, it, dxfs) }
        }
        if (sheet.tables.isNotEmpty()) for ((k, cell) in sheet.cells) {
            if (cell.text.startsWith("=") && cell.text.indexOf('[') >= 0) {
                cell.text = "=" + com.therealityteam.aeropages.model.StructuredRefs.toUiForm(cell.text.substring(1), sheet, (k / 1000L).toInt(), (k % 1000L).toInt())
            }
        }
        if (linkIds.isNotEmpty()) {
            val byId = rels.filter { it.type.endsWith("/hyperlink") }.associate { it.id to it.target }
            linkIds.forEach { (key, ref) ->
                val rid = ref.substringBefore('#')
                val place = ref.substringAfter('#', "").takeIf { it.isNotEmpty() }
                byId[rid]?.let { url -> sheet.cells.getOrPut(key) { com.therealityteam.aeropages.model.Cell() }.link = url + (place?.let { "#$it" } ?: "") }
            }
        }
        // Comments: <comment ref="A1" authorId="0"><text>…<t>…</t>…</text></comment>, run texts concatenated; the author from <authors>.
        part("comments")?.let { XmlPull.parse(files[it]) }?.let { root ->
            val authors = root.child("authors")?.children("author")?.map { it.text }.orEmpty()
            root.descendants("comment").forEach { cm ->
                val (r, c) = cm.attr("ref")?.let { A1.parse(it) } ?: return@forEach
                val text = cm.textOf("t")
                if (text.isNotBlank()) sheet.ensure(r, c).let { cell ->
                    cell.comment = text
                    cell.commentAuthor = cm.attr("authorId")?.toIntOrNull()?.let { authors.getOrNull(it) }?.takeIf { it.isNotBlank() }
                }
            }
        }
        // The notes' VML shapes: a note with <x:Visible/> in its ClientData is shown beside its cell.
        part("vmlDrawing")?.let { files[it] }?.let { vml -> readShownNotes(sheet, vml) }
        // Charts: sheet → drawing part → its rels → chart parts.
        val drawing = part("drawing") ?: return
        val drawingRels = XmlPull.rels(files[XmlPull.relsOf(drawing)])
        drawingRels.filter { it.type.endsWith("/chart") }.forEach { rel ->
            val chartPart = XmlPull.partName(files, drawing, rel.target) ?: return@forEach
            PptxReader.parseChartPart(files[chartPart] ?: return@forEach, bindRanges = true)?.let { sheet.charts.add(it) }
        }
        XmlPull.parse(files[drawing])?.let { readDrawing(sheet, it, drawing, drawingRels, files, theme()) }
    }

    /**
     * Which notes the VML drawing keeps shown: each `x:ClientData ObjectType="Note"` with `x:Visible` names its cell by
     * `x:Row` / `x:Column` (0-based). The VML is old-style markup (often not well-formed XML), so it is read with patterns.
     */
    internal fun readShownNotes(sheet: Sheet, vml: ByteArray) {
        val text = vml.toString(Charsets.UTF_8)
        val data = Regex("""<x:ClientData\b[^>]*ObjectType="Note"[^>]*>(.*?)</x:ClientData>""", RegexOption.DOT_MATCHES_ALL)
        for (m in data.findAll(text)) {
            val body = m.groupValues[1]
            if (!Regex("""<x:Visible\s*/?>""").containsMatchIn(body)) continue
            val r = Regex("""<x:Row>\s*(\d+)\s*</x:Row>""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val c = Regex("""<x:Column>\s*(\d+)\s*</x:Column>""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            sheet.cell(r, c)?.takeIf { it.comment != null }?.commentShown = true
        }
    }

    /**
     * A table part: its name (the displayName formulas use), range, header and total rows, style
     * and its options, the columns' names, and its filter — whose criteria become the sheet's
     * filter when the sheet has none, the rows it hides then counted as filtered.
     */
    private fun readTable(sheet: Sheet, root: XmlNode, dxfs: List<com.therealityteam.aeropages.model.CondStyle?> = emptyList()) {
        val name = root.attr("displayName") ?: root.attr("name") ?: return
        val (r1, c1, r2, c2) = root.attr("ref")?.let { A1.parseRange(it) } ?: return
        if (c2 >= Sheet.MAX_COLS || sheet.tables.any { it.intersects(r1, c1, r2, c2) }) return
        val header = (root.attr("headerRowCount")?.toIntOrNull() ?: 1) > 0
        val totals = (root.attr("totalsRowCount")?.toIntOrNull() ?: 0) > 0
        if ((if (header) r1 + 1 else r1) > (if (totals) r2 - 1 else r2)) return // no data row
        val info = root.child("tableStyleInfo")
        fun flag(n: String) = info?.attr(n).let { it == "1" || it == "true" }
        val af = root.child("autoFilter")
        val t = com.therealityteam.aeropages.model.SheetTable(
            name, r1, c1, r2, c2, headerRow = header, totalsRow = totals, style = info?.attr("name"),
            bandedRows = flag("showRowStripes"), bandedCols = flag("showColumnStripes"),
            firstColumn = flag("showFirstColumn"), lastColumn = flag("showLastColumn"), filterButton = header && af != null,
            // With a header row its cells name the columns; without one the part is the only record.
            columns = if (header) mutableListOf() else root.child("tableColumns")?.children("tableColumn")?.map { it.attr("name") ?: "" }?.toMutableList() ?: mutableListOf(),
        )
        sheet.tables.add(t)
        if (af == null || sheet.autoFilter != null) return
        val (fr1, fc1, fr2, fc2) = af.attr("ref")?.let { A1.parseRange(it) } ?: intArrayOf(t.r1, t.c1, t.dataR2, t.c2)
        val filter = com.therealityteam.aeropages.model.AutoFilter(fr1, fc1, fr2, fc2)
        val reader = FilterColumnReader(dxfs)
        af.children("filterColumn").forEach { fc ->
            val col = (fc.attr("colId")?.toIntOrNull() ?: return@forEach) + fc1
            fun walk(n: XmlNode) { n.children.forEach { c -> reader.take(c.name, c::attr); walk(c) } }
            walk(fc)
            reader.filter()?.let { filter.columns[col] = it }
            reader.flush(sheet)
        }
        if (filter.columns.isEmpty()) return
        sheet.autoFilter = filter
        // The rows it hides were read as hidden by hand: the sheet itself has no filter.
        val filtered = sheet.manualHiddenRows.filter { it in fr1 + 1..fr2 }
        sheet.manualHiddenRows.removeAll(filtered.toSet())
        sheet.hiddenRows.addAll(filtered)
    }

    /** EMUs (DrawingML's unit) per point. */
    private const val EMU_PER_PT = 12700f

    /**
     * The sheet's pictures from its drawing: every anchor (two-cell, one-cell, absolute) holding an
     * `xdr:pic`, with the media its blip names. The top-left corner is the `from` cell and offsets
     * (an absolute anchor's position found among the columns and rows); the size is the picture's
     * own `xfrm`, else the anchor's `ext`, else the span from `from` to `to`.
     */
    /**
     * The sheet's pictures, shapes and text boxes from its drawing: every anchor (two-cell,
     * one-cell, absolute) holding an `xdr:pic`, an `xdr:sp` / `xdr:cxnSp`, or a group of them. The
     * top-left corner is the `from` cell and offsets (an absolute anchor's position found among the
     * columns and rows); the size is the element's own `a:ext`, else the anchor's, else what
     * the `to` cell leaves. A group's members are placed through its transform.
     */
    private fun readDrawing(sheet: Sheet, root: XmlNode, drawing: String, rels: List<XmlPull.Rel>, files: Map<String, ByteArray>, palette: PptxReader.Palette) {
        fun emu(v: String?) = (v?.trim()?.toLongOrNull() ?: 0L) / EMU_PER_PT
        fun colPt(c: Int) = com.therealityteam.aeropages.model.SheetImage.COL_PT * sheet.colWidthFactor(c)
        fun rowPt(r: Int) = com.therealityteam.aeropages.model.SheetImage.ROW_PT * sheet.rowHeightFactor(r)
        /** The cell under ([x], [y]) points from the sheet's corner, and the offset into it. */
        fun cellAt(x0: Float, y0: Float): FloatArray {
            var x = x0.coerceAtLeast(0f); var y = y0.coerceAtLeast(0f)
            var col = 0; while (x >= colPt(col) && col < Sheet.MAX_COLS - 1) { x -= colPt(col); col++ }
            var row = 0; while (y >= rowPt(row) && row < 1_048_575) { y -= rowPt(row); row++ }
            return floatArrayOf(row.toFloat(), col.toFloat(), x, y)
        }
        var named = 0
        for (anchor in root.children) {
            if (anchor.name != "twoCellAnchor" && anchor.name != "oneCellAnchor" && anchor.name != "absoluteAnchor") continue
            val element = anchor.children.firstOrNull { it.name == "pic" || it.name == "sp" || it.name == "cxnSp" || it.name == "grpSp" } ?: continue
            // The anchor's box: its corner as sheet points, its size.
            var x: Float; var y: Float
            val from = anchor.child("from")
            val fromCol = from?.child("col")?.text?.trim()?.toIntOrNull() ?: 0
            val fromRow = from?.child("row")?.text?.trim()?.toIntOrNull() ?: 0
            if (from != null) {
                x = (0 until fromCol).sumOf { colPt(it).toDouble() }.toFloat() + emu(from.child("colOff")?.text)
                y = (0 until fromRow).sumOf { rowPt(it).toDouble() }.toFloat() + emu(from.child("rowOff")?.text)
            } else { x = emu(anchor.child("pos")?.attr("x")); y = emu(anchor.child("pos")?.attr("y")) }
            val ownXfrm = (element.child("spPr") ?: element.child("grpSpPr"))?.child("xfrm")
            val ext = ownXfrm?.child("ext")?.takeIf { (it.attr("cx")?.toLongOrNull() ?: 0L) > 0L || (it.attr("cy")?.toLongOrNull() ?: 0L) > 0L } ?: anchor.child("ext")
            var w = emu(ext?.attr("cx")); var h = emu(ext?.attr("cy"))
            val to = anchor.child("to")
            if (w <= 0f && h <= 0f && to != null && from != null) {
                val c2 = to.child("col")?.text?.trim()?.toIntOrNull() ?: fromCol; val r2 = to.child("row")?.text?.trim()?.toIntOrNull() ?: fromRow
                w = (fromCol until c2).sumOf { colPt(it).toDouble() }.toFloat() - emu(from.child("colOff")?.text) + emu(to.child("colOff")?.text)
                h = (fromRow until r2).sumOf { rowPt(it).toDouble() }.toFloat() - emu(from.child("rowOff")?.text) + emu(to.child("rowOff")?.text)
            }
            // A group's members: their child-space frames mapped onto the group's box.
            val members = if (element.name == "grpSp") {
                val gx = ownXfrm
                val chOffX = gx?.child("chOff")?.attr("x")?.toLongOrNull() ?: 0L; val chOffY = gx?.child("chOff")?.attr("y")?.toLongOrNull() ?: 0L
                val chW = gx?.child("chExt")?.attr("cx")?.toLongOrNull()?.takeIf { it > 0 }; val chH = gx?.child("chExt")?.attr("cy")?.toLongOrNull()?.takeIf { it > 0 }
                val sx = if (chW != null && w > 0f) w / chW else 1f / EMU_PER_PT
                val sy = if (chH != null && h > 0f) h / chH else 1f / EMU_PER_PT
                element.descendants(setOf("sp", "cxnSp", "pic")).mapNotNull { m ->
                    val mx = m.child("spPr")?.child("xfrm") ?: return@mapNotNull null
                    val ox = mx.child("off")?.attr("x")?.toLongOrNull() ?: 0L; val oy = mx.child("off")?.attr("y")?.toLongOrNull() ?: 0L
                    val cx = mx.child("ext")?.attr("cx")?.toLongOrNull() ?: 0L; val cy = mx.child("ext")?.attr("cy")?.toLongOrNull() ?: 0L
                    m to floatArrayOf(x + (ox - chOffX) * sx, y + (oy - chOffY) * sy, cx * sx, cy * sy)
                }
            } else listOf(element to floatArrayOf(x, y, w, h))
            for ((m, box) in members) {
                val (row, col, dx, dy) = cellAt(box[0], box[1]).let { listOf(it[0], it[1], it[2], it[3]) }
                val bw = box[2]; val bh = box[3]
                val name = m.descendants("cNvPr").firstOrNull()?.attr("name").orEmpty()
                if (m.name == "pic") {
                    if (bw <= 0f || bh <= 0f) continue
                    val rid = m.child("blipFill")?.child("blip")?.attr(XmlPull.R_NS, "embed") ?: continue
                    val mediaPart = rels.firstOrNull { it.id == rid }?.let { XmlPull.partName(files, drawing, it.target) } ?: continue
                    // Excel's SVG picture: the SVG itself (asvg:svgBlip) beside its PNG stand-in.
                    val svg = m.child("blipFill")?.child("blip")?.descendants("svgBlip")?.firstOrNull()?.attr(XmlPull.R_NS, "embed")
                        ?.let { s -> rels.firstOrNull { it.id == s } }?.let { XmlPull.partName(files, drawing, it.target) }?.let { files[it] }
                        ?.takeIf { com.therealityteam.aeropages.format.svg.SvgReader.isSvg(it) }
                    val data = svg ?: files[mediaPart] ?: continue
                    val mime = com.therealityteam.aeropages.format.PictureType.mimeOf(data, mediaPart)
                    val img = com.therealityteam.aeropages.model.SheetImage(data, mime, row.toInt(), col.toInt(), dx, dy, bw, bh, name)
                    m.child("blipFill")?.child("srcRect")?.let { rect -> com.therealityteam.aeropages.format.PictureCrop.readSrcRect({ rect.attr(it) }, img) }
                    m.child("spPr")?.child("effectLst")?.child("outerShdw")?.let { img.shadow = com.therealityteam.aeropages.format.pptx.DrawingShape.shadowOf(it, palette) }
                    com.therealityteam.aeropages.format.pptx.OoxmlAltText.read(m.child("nvPicPr")?.child("cNvPr")).let { (alt, decorative) -> img.altText = alt; img.decorative = decorative }
                    sheet.images.add(img)
                } else {
                    // A shape or line: a line may be flat (no height or no width), a shape needs both.
                    val line = m.name == "cxnSp" || m.child("spPr")?.child("prstGeom")?.attr("prst") in setOf("line", "straightConnector1")
                    if (if (line) bw <= 0f && bh <= 0f else bw <= 0f || bh <= 0f) continue
                    val shape = com.therealityteam.aeropages.format.pptx.DrawingShape.read(m, palette) { txBody ->
                        com.therealityteam.aeropages.format.pptx.PptxReader.textBoxOf(txBody, 11f, palette)
                    }
                    sheet.shapes.add(com.therealityteam.aeropages.model.SheetShape(shape, row.toInt(), col.toInt(), dx, dy, bw, bh, name.ifEmpty { "Shape ${++named}" }))
                }
            }
        }
    }

    /** The most formatted empty cells a sheet keeps from a file (a style on every cell of a million rows is not read in). */
    private const val MAX_FORMATTED_EMPTIES = 100_000

    /** A resolved cell style from styles.xml (cellXfs entry + its font/fill/border/numFmt). */
    private class XfStyle(
        val numberFormat: NumberFormat, val align: TextAlign?,
        val bold: Boolean, val italic: Boolean, val underline: Boolean, val color: Long?, val fill: Long?,
        val sizePt: Float? = null, val family: String? = null, val strike: Boolean = false,
        val borderTop: BorderLine? = null, val borderBottom: BorderLine? = null, val borderLeft: BorderLine? = null, val borderRight: BorderLine? = null,
        val wrap: Boolean = false, val vAlign: VAlign = VAlign.MIDDLE,
        val decimals: Int = -1,
        val unlocked: Boolean = false,
        val formatCode: String? = null,
        /** The cell's font face and size, the workbook default included: what a rich run inherits. */
        val baseFont: String? = null,
        val baseSize: Float? = null,
        val rotation: Int = 0, val stacked: Boolean = false, val indent: Int = 0,
        val spread: com.therealityteam.aeropages.model.CellSpread? = null, val shrink: Boolean = false,
    ) {
        /** Whether it shows on an empty cell or changes what is typed into one (anything but Excel's plain Normal). */
        val visible: Boolean get() = fill != null || borderTop != null || borderBottom != null || borderLeft != null || borderRight != null ||
            bold || italic || underline || strike || color != null || sizePt != null || family != null || align != null || wrap ||
            vAlign != VAlign.BOTTOM || numberFormat != NumberFormat.GENERAL || formatCode != null || unlocked ||
            rotation != 0 || stacked || indent > 0 || spread != null || shrink

        fun applyTo(cell: Cell) {
            cell.numberFormat = numberFormat; cell.align = align; cell.decimals = decimals; cell.unlocked = unlocked; cell.formatCode = formatCode
            cell.format.bold = bold; cell.format.italic = italic; cell.format.underline = underline; cell.format.strikethrough = strike
            sizePt?.let { cell.format.fontSizePt = it }; family?.let { cell.format.fontFamily = it }
            color?.let { cell.format.color = it }; fill?.let { cell.format.highlight = it }
            cell.borderTop = borderTop; cell.borderBottom = borderBottom; cell.borderLeft = borderLeft; cell.borderRight = borderRight
            cell.wrap = wrap; cell.vAlign = vAlign
            cell.rotation = rotation; cell.stacked = stacked; cell.indent = indent
            cell.spread = spread; cell.shrink = shrink
        }
    }

    /** A `<font>` entry: b / i / u toggles (on unless val="0"/"false"/"none") and its rgb colour. */
    private class FontInfo(val bold: Boolean, val italic: Boolean, val underline: Boolean, val color: Long?, val size: Float?, val name: String?, val strike: Boolean)

    private fun on(toggle: XmlNode?): Boolean {
        val v = toggle?.attr("val") ?: return toggle != null
        return v != "0" && v != "false" && v != "none"
    }

    /** styles.xml → one resolved style per cellXfs entry (index = a cell's `s` attribute). */
    private fun parseStyles(root: XmlNode): List<XfStyle> {
        // custom numFmts: id -> code
        val numFmtCodes = HashMap<Int, String>()
        root.descendants("numFmt").forEach { n ->
            val id = n.attr("numFmtId")?.toIntOrNull() ?: return@forEach
            n.attr("formatCode")?.let { numFmtCodes[id] = it }
        }
        val fonts = root.child("fonts")?.children("font")?.map { f ->
            FontInfo(on(f.child("b")), on(f.child("i")), on(f.child("u")), argbOf(f.child("color")?.attr("rgb")),
                f.child("sz")?.attr("val")?.toFloatOrNull(), f.child("name")?.attr("val"), on(f.child("strike")))
        } ?: emptyList()
        // fills (index 0,1 reserved; solid fgColor in order)
        val fills = root.child("fills")?.children("fill")?.map { fl ->
            fl.descendants("fgColor").firstNotNullOfOrNull { argbOf(it.attr("rgb")) }
        } ?: emptyList()
        // borders: each side's line style and colour (e.g. <bottom style="double"><color rgb="FF1F4E79"/></bottom>)
        val borderSides = root.child("borders")?.children("border")?.map { b ->
            listOf("top", "bottom", "left", "right").map { side -> b.child(side)?.let { lineOf(it) } }
        } ?: emptyList()
        val xfs = root.child("cellXfs")?.children("xf") ?: return emptyList()
        return xfs.map { xf ->
            val numId = xf.attr("numFmtId")?.toIntOrNull() ?: 0
            val fontId = xf.attr("fontId")?.toIntOrNull() ?: 0
            val fillId = xf.attr("fillId")?.toIntOrNull() ?: 0
            val borderId = xf.attr("borderId")?.toIntOrNull() ?: 0
            val alignment = xf.child("alignment")
            val h = alignment?.attr("horizontal")
            val v = alignment?.attr("vertical")
            val wrap = alignment?.attr("wrapText").let { it == "1" || it == "true" }
            // textRotation: 0–90 counterclockwise, 91–180 clockwise by (value − 90), 255 one character under another.
            val turn = alignment?.attr("textRotation")?.toIntOrNull() ?: 0
            val font = fonts.getOrNull(fontId)
            val sides = borderSides.getOrNull(borderId) ?: listOf(null, null, null, null)
            val numFmt = numFmtOf(numId, numFmtCodes[numId])
            XfStyle(
                numberFormat = numFmt.format,
                decimals = numFmt.decimals,
                formatCode = numFmt.code,
                // No `horizontal` (or "general") is Excel's General alignment: numbers right, text left.
                align = when (h) {
                    "left", "fill" -> TextAlign.LEFT; "center", "centerContinuous" -> TextAlign.CENTER; "right" -> TextAlign.RIGHT
                    "justify", "distributed" -> TextAlign.JUSTIFY; else -> null
                },
                bold = font?.bold ?: false,
                italic = font?.italic ?: false,
                underline = font?.underline ?: false,
                color = font?.color,
                // The workbook's own font (the first) is the cells' default; others keep their size and face.
                sizePt = font?.size?.takeIf { it != fonts.firstOrNull()?.size },
                family = font?.name?.takeIf { it != fonts.firstOrNull()?.name },
                strike = font?.strike ?: false,
                fill = fills.getOrNull(fillId),
                borderTop = sides[0], borderBottom = sides[1], borderLeft = sides[2], borderRight = sides[3],
                wrap = wrap,
                // No `vertical` is Excel's default, the bottom of the cell.
                vAlign = when (v) { "top" -> VAlign.TOP; "center", "justify", "distributed" -> VAlign.MIDDLE; else -> VAlign.BOTTOM },
                unlocked = xf.child("protection")?.attr("locked").let { it == "0" || it == "false" },
                baseFont = font?.name ?: fonts.firstOrNull()?.name,
                baseSize = font?.size ?: fonts.firstOrNull()?.size,
                rotation = when (turn) { in 1..90 -> turn; in 91..180 -> 90 - turn; else -> 0 },
                stacked = turn == 255,
                indent = (alignment?.attr("indent")?.toIntOrNull() ?: 0).coerceIn(0, Cell.MAX_INDENT),
                spread = when (h) {
                    "fill" -> com.therealityteam.aeropages.model.CellSpread.FILL
                    "centerContinuous" -> com.therealityteam.aeropages.model.CellSpread.CENTER_ACROSS
                    "distributed" -> com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED
                    else -> null
                },
                shrink = alignment?.attr("shrinkToFit").let { it == "1" || it == "true" },
            )
        }
    }

    /** One `x14:sparklineGroup`: its type and colour, then a sparkline per `x14:sparkline` (range in `xm:f`, cell in `xm:sqref`). */
    private fun parseSparklineGroup(group: XmlNode, sheet: Sheet) {
        val type = when (group.attr("type")) {
            "column" -> com.therealityteam.aeropages.model.SparklineType.COLUMN
            "stacked" -> com.therealityteam.aeropages.model.SparklineType.WIN_LOSS
            else -> com.therealityteam.aeropages.model.SparklineType.LINE
        }
        val color = group.child("colorSeries")?.attr("rgb")?.let { argbOf(it) } ?: 0xFF376092
        val markers = group.attr("markers").let { it == "1" || it == "true" }
        group.descendants("sparkline").forEach { sp ->
            val f = sp.child("f")?.text?.trim() ?: return@forEach
            val cellRef = sp.child("sqref")?.text?.trim() ?: return@forEach
            val range = f.substringAfterLast('!')
            val (a, b) = range.split(':').let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
            val (r1, c1) = A1.parse(a) ?: return@forEach
            val (r2, c2) = A1.parse(b) ?: return@forEach
            val (row, col) = A1.parse(cellRef.substringBefore(':')) ?: return@forEach
            if (col >= Sheet.MAX_COLS || c2 >= Sheet.MAX_COLS) return@forEach
            sheet.sparklines.add(com.therealityteam.aeropages.model.Sparkline(row, col, minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2), type, color, markers))
        }
    }

    /**
     * dxf index → the look a conditional format gives (fill — a dxf keeps a solid fill's colour as its
     * `bgColor` — text colour, bold, italic, underline, strikethrough), colours rgb, indexed or the theme's.
     */
    private fun parseDxfs(root: XmlNode, theme: () -> com.therealityteam.aeropages.format.pptx.PptxReader.Palette): List<com.therealityteam.aeropages.model.CondStyle?> =
        root.child("dxfs")?.children("dxf")?.map { d ->
            fun color(n: XmlNode?) = n?.let { colorOf(it.attr("rgb"), it.attr("indexed"), it.attr("theme"), it.attr("tint"), theme) }
            val font = d.child("font")
            fun flag(name: String): Boolean? = font?.child(name)?.attr("val").let { v -> if (font?.child(name) == null) null else v == null || v == "1" || v == "true" }
            val pattern = d.child("fill")?.child("patternFill")?.takeIf { it.attr("patternType") != "none" }
            com.therealityteam.aeropages.model.CondStyle(
                fill = pattern?.let { color(it.child("bgColor")) ?: color(it.child("fgColor")) },
                font = color(font?.child("color")),
                bold = flag("b"), italic = flag("i"),
                underline = font?.child("u")?.let { it.attr("val") != "none" },
                strike = flag("strike"),
            )
        } ?: emptyList()

    /** One `<filterColumn>` being read, its elements given to [take]; [flush] puts its criteria on the sheet's AutoFilter. */
    private class FilterColumnReader(private val dxfs: List<com.therealityteam.aeropages.model.CondStyle?>) {
        var col: Int? = null
        private var values: MutableSet<String>? = null
        private var blanks = false
        private val conditions = ArrayList<FilterCondition>()
        private var and = false
        private var top: com.therealityteam.aeropages.model.TopFilter? = null
        private var dynamic: com.therealityteam.aeropages.model.DynamicFilter? = null
        private var color: com.therealityteam.aeropages.model.ColorFilter? = null

        /** An element inside `<filterColumn>`: the values, one or two custom filters, Top 10, a dynamic or a colour filter. */
        fun take(name: String, attr: (String) -> String?) {
            fun on(a: String) = attr(a).let { it == "1" || it == "true" }
            when (name) {
                "filters" -> { values = mutableSetOf(); blanks = on("blank") }
                "filter" -> attr("val")?.let { values?.add(it) }
                "customFilters" -> and = on("and")
                "customFilter" -> if (conditions.size < 2) conditions.add(customFilterOf(attr("operator"), attr("val") ?: ""))
                "top10" -> top = com.therealityteam.aeropages.model.TopFilter(
                    top = attr("top").let { it != "0" && it != "false" }, count = attr("val")?.toDoubleOrNull() ?: 10.0, percent = on("percent"))
                "dynamicFilter" -> dynamic = com.therealityteam.aeropages.model.DynamicFilter.ofExcel(attr("type"))
                "colorFilter" -> {
                    val font = attr("cellColor").let { it == "0" || it == "false" }
                    val look = attr("dxfId")?.toIntOrNull()?.let { dxfs.getOrNull(it) }
                    color = com.therealityteam.aeropages.model.ColorFilter(if (font) look?.font else look?.fill, font)
                }
            }
        }

        /** The column's criteria as read, or null when it had none. */
        fun filter(): com.therealityteam.aeropages.model.ColumnFilter? = when {
            color != null -> com.therealityteam.aeropages.model.ColumnFilter(blanks = false, color = color)
            top != null -> com.therealityteam.aeropages.model.ColumnFilter(blanks = false, top = top)
            dynamic != null -> com.therealityteam.aeropages.model.ColumnFilter(blanks = false, dynamic = dynamic)
            conditions.isNotEmpty() -> com.therealityteam.aeropages.model.ColumnFilter(blanks = false, condition = conditions[0],
                condition2 = conditions.getOrNull(1), or = conditions.size > 1 && !and)
            values != null -> com.therealityteam.aeropages.model.ColumnFilter(values = values, blanks = blanks)
            else -> null
        }

        fun flush(sheet: Sheet) {
            val c = col
            val af = sheet.autoFilter
            if (c != null && af != null) filter()?.let { af.columns[c] = it }
            col = null; values = null; blanks = false; conditions.clear(); and = false; top = null; dynamic = null; color = null
        }
    }

    /** A `<customFilter>` as a condition: the comparison operators, and wildcards `*text*` / `text*` / `*text` as contains / begins / ends. */
    private fun customFilterOf(operator: String?, value: String): FilterCondition {
        val starts = value.startsWith("*")
        val ends = value.endsWith("*") && value.length > 1
        val core = value.removePrefix("*").removeSuffix("*")
        return when (operator) {
            "notEqual" -> when {
                starts && ends -> FilterCondition(FilterCondition.Op.NOT_CONTAINS, core)
                ends && !core.contains('*') && !core.contains('?') -> FilterCondition(FilterCondition.Op.NOT_BEGINS_WITH, core)
                starts && !core.contains('*') && !core.contains('?') -> FilterCondition(FilterCondition.Op.NOT_ENDS_WITH, core)
                else -> FilterCondition(FilterCondition.Op.NOT_EQUALS, value)
            }
            "greaterThan" -> FilterCondition(FilterCondition.Op.GREATER, value)
            "greaterThanOrEqual" -> FilterCondition(FilterCondition.Op.GREATER_OR_EQUAL, value)
            "lessThan" -> FilterCondition(FilterCondition.Op.LESS, value)
            "lessThanOrEqual" -> FilterCondition(FilterCondition.Op.LESS_OR_EQUAL, value)
            else -> when {
                starts && ends -> FilterCondition(FilterCondition.Op.CONTAINS, core)
                ends -> FilterCondition(FilterCondition.Op.BEGINS_WITH, core)
                starts -> FilterCondition(FilterCondition.Op.ENDS_WITH, core)
                else -> FilterCondition(FilterCondition.Op.EQUALS, value)
            }
        }
    }

    /** A cell style's number format: a plain one with its decimals, or — when none shows it exactly — the Excel code. */
    private class NumFmt(val format: NumberFormat, val decimals: Int = -1, val code: String? = null)

    private fun numFmtOf(id: Int, customCode: String?): NumFmt {
        if (customCode == null) when (id) {
            // Built-ins the plain formats show exactly (14 / 20 / 22 are the locale's date and time).
            0 -> return NumFmt(NumberFormat.GENERAL)
            1 -> return NumFmt(NumberFormat.NUMBER2, 0)
            2 -> return NumFmt(NumberFormat.NUMBER2)
            3 -> return NumFmt(NumberFormat.THOUSANDS)
            4 -> return NumFmt(NumberFormat.THOUSANDS, 2)
            9 -> return NumFmt(NumberFormat.PERCENT, 0)
            10 -> return NumFmt(NumberFormat.PERCENT, 2)
            11 -> return NumFmt(NumberFormat.SCIENTIFIC)
            14 -> return NumFmt(NumberFormat.DATE)
            20 -> return NumFmt(NumberFormat.TIME)
            22 -> return NumFmt(NumberFormat.DATE_TIME)
        }
        val code = customCode ?: FormatCode.builtIn(id) ?: return NumFmt(NumberFormat.GENERAL)
        FormatCode.plain(code)?.let { (format, places) -> return NumFmt(format, places) }
        if (!FormatCode.isValid(code)) return NumFmt(NumberFormat.GENERAL)
        return NumFmt(FormatCode.category(code), -1, code)
    }


    /** A border side's line: its style (none → null) and colour — rgb or Excel's indexed palette; automatic and black read as automatic. */
    private fun lineOf(side: XmlNode): BorderLine? {
        val style = LineStyle.ofXlsx(side.attr("style")) ?: return null
        val c = side.child("color")
        val color = argbOf(c?.attr("rgb")) ?: c?.attr("indexed")?.toIntOrNull()?.let { indexedColor(it) }
        return BorderLine(style, color?.takeIf { it != 0xFF000000L })
    }

    /** Excel's indexed colours (`indexed="10"`): the eight legacy ones, then the 56-colour palette. */
    private fun indexedColor(i: Int): Long? = when (i) { in 0..7 -> FormatCode.paletteColor(i + 1); in 8..63 -> FormatCode.paletteColor(i - 7); else -> null }

    /** Excel's theme colour indexes: the light colours first (0 = lt1, 1 = dk1, 2 = lt2, 3 = dk2), then the accents and links. */
    private val THEME_ORDER = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")

    /**
     * A SpreadsheetML colour (a tab's `<tabColor>`): its rgb, an indexed colour, or a colour of the
     * workbook's [theme], then lightened or darkened by its tint as SpreadsheetML defines it — the HLS
     * luminance times 1 + tint when darkening, moved the tint's share of the way to white when lightening.
     */
    internal fun colorOf(rgb: String?, indexed: String?, theme: String?, tint: String?, palette: () -> com.therealityteam.aeropages.format.pptx.PptxReader.Palette): Long? {
        val base = argbOf(rgb) ?: indexed?.toIntOrNull()?.let { indexedColor(it) }
            ?: theme?.toIntOrNull()?.let { i -> THEME_ORDER.getOrNull(i)?.let { palette().themeColor(it) } }
            ?: return null
        val t = tint?.toDoubleOrNull()?.takeIf { it != 0.0 }?.coerceIn(-1.0, 1.0) ?: return base
        return com.therealityteam.aeropages.format.pptx.PptxReader.Palette.lum(base) { l -> (if (t < 0) l * (1 + t) else l * (1 - t) + t).toFloat() }
    }

    /** "FFRRGGBB" or "RRGGBB" hex → 0xAARRGGBB Long (default opaque); null for anything else. */
    private fun argbOf(hex: String?): Long? {
        if (hex == null || (hex.length != 6 && hex.length != 8)) return null
        return (if (hex.length == 6) "FF$hex" else hex).toLongOrNull(16)
    }

    /** Conditional-format colour: "RRGGBB" / "AARRGGBB" hex → opaque 0xFFRRGGBB; null for anything else. */
    private fun rgbOf(hex: String?): Long? {
        if (hex == null || (hex.length != 6 && hex.length != 8)) return null
        return hex.takeLast(6).toLongOrNull(16)?.let { 0xFF000000L or it }
    }

    /** "A1:C3" (or a single "B2") → [r1, c1, r2, c2], zero-based, in the order written. */
    private fun rangeOf(sq: String): IntArray {
        val parts = sq.split(':')
        val (r1, c1) = refToRowCol(parts[0])
        val (r2, c2) = if (parts.size > 1) refToRowCol(parts[1]) else (r1 to c1)
        return intArrayOf(r1, c1, r2, c2)
    }

    /** Shift relative A1 refs by (dRow,dCol) — for expanding Excel shared formulas. */
    private fun shiftRefs(formula: String, dRow: Int, dCol: Int): String = A1.shiftRefs(formula, dRow, dCol)

    /** "B3" -> (row=2, col=1) zero-based; a column-only ref ("B") maps to row 0. */
    private fun refToRowCol(ref: String): Pair<Int, Int> =
        A1.parse(ref) ?: (0 to A1.colIndex(ref.takeWhile { it.isLetter() }))
}
