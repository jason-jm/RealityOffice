package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.VerticalDivider
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Workbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

/** The ribbon's tabs; Table Design ([TABLE]) is only there while the active cell is in a table. */
internal enum class XTab(val title: Int, val contextual: Boolean = false) {
    HOME(R.string.tab_home), INSERT(R.string.tab_insert), LAYOUT(R.string.tab_page_layout), FORMULA(R.string.formulas), DATA(R.string.tab_data), REVIEW(R.string.tab_review), VIEW(R.string.tab_view),
    TABLE(R.string.tab_table_design, true), PICTURE(R.string.tab_picture_format, true), SHAPE(R.string.tab_shape_format, true)
}
/** What the side window beside the sheet holds (one in front, a switcher when more than one is open). */
internal enum class SheetSidePane(val label: Int) { FIND(R.string.find), CLIPBOARD(R.string.clipboard), COMMENTS(R.string.comments), WATCH(R.string.watch_window) }

internal enum class XPicker { NONE, TEXTCOLOR, FILL, FONT, SIZE, NUMFMT, BORDERS, CELLSTYLES, TABLESTYLES, CLEAR, TOTALS, SHAPES, AUTOSUM, SORTFILTER, INSERTCELLS, DELETECELLS, FORMATCELLS, PASTEMENU, FILLMENU, SYMBOL, ZOOM, FREEZE, PMARGINS, PORIENT, PPAPER, PPRINTAREA, PBREAKS, PSCALE, DATAVALID, WHATIF, GROUPMENU, UNGROUPMENU, USENAME, ORIENT, MERGE, FINDSELECT, ARROWS }

internal fun numFmtName(f: com.therealityteam.aeropages.model.NumberFormat): String = when (f) {
    com.therealityteam.aeropages.model.NumberFormat.GENERAL -> Strings.get(R.string.general_2)
    com.therealityteam.aeropages.model.NumberFormat.NUMBER2 -> Strings.get(R.string.numfmt_number)
    com.therealityteam.aeropages.model.NumberFormat.CURRENCY_CNY -> Strings.get(R.string.numfmt_currency_cny)
    com.therealityteam.aeropages.model.NumberFormat.CURRENCY_USD -> Strings.get(R.string.numfmt_currency_usd)
    com.therealityteam.aeropages.model.NumberFormat.PERCENT -> Strings.get(R.string.numfmt_percentage)
    com.therealityteam.aeropages.model.NumberFormat.THOUSANDS -> Strings.get(R.string.numfmt_comma_style)
    com.therealityteam.aeropages.model.NumberFormat.DATE -> Strings.get(R.string.date)
    com.therealityteam.aeropages.model.NumberFormat.TIME -> Strings.get(R.string.numfmt_time)
    com.therealityteam.aeropages.model.NumberFormat.DATE_TIME -> Strings.get(R.string.numfmt_date_time)
    com.therealityteam.aeropages.model.NumberFormat.SCIENTIFIC -> Strings.get(R.string.numfmt_scientific)
}

internal val CELL_W = 88.dp
internal val CELL_H = 26.dp
internal val ROWHDR_W = 44.dp



/** Sheets (Excel) module: a formula bar over a scrollable cell grid, reusing our ribbon. */
@Composable
fun SheetsApp(openPath: String? = null, template: DeskTemplate? = null, windowId: String = OpenRequests.MAIN, onHome: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = remember { SheetEditor().also { it.reviewer = loadReviewer(context) } }
    val workDir = remember { (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() } }
    val session = remember {
        DocumentSession(
            context, scope, workDir, com.therealityteam.aeropages.format.DeskKind.SHEETS, "xlsx",
            listOf("xlsx", "ods", "csv"), ".draft-sheets.xlsx", Strings.get(R.string.workbook),
            object : DocumentSession.Adapter<Workbook> {
                override val dirty: Boolean get() = editor.dirty
                override val editVersion: Long get() = editor.saveState.version
                override val sourcePath: String? get() = editor.workbook.sourcePath
                override fun snapshot(): Workbook = editor.workbook.copyOf()
                override fun live(): Workbook = editor.workbook
                override fun write(model: Workbook, out: java.io.OutputStream, ext: String) {
                    // The snapshot carries the formulas; their values are worked out here — on this thread, off the
                    // live editor's caches, one memo for the whole pass — so the file holds them beside the formulas.
                    val memo = com.therealityteam.aeropages.editor.FormulaEngine.Memo()
                    val values = { si: Int, r: Int, c: Int -> com.therealityteam.aeropages.editor.FormulaEngine.cached(model.sheets[si], r, c, model.sheets, model.names, memo) }
                    when (ext) {
                        "ods" -> com.therealityteam.aeropages.format.odf.OdsWriter.write(model, out, values)
                        // The first sheet shown, its values as they show (a hidden sheet is no CSV's content).
                        "csv" -> com.therealityteam.aeropages.format.FormatLosses.csvSheet(model).let { sheet ->
                            out.write(com.therealityteam.aeropages.editor.SheetEditor.csvOf(sheet) { r, c ->
                                com.therealityteam.aeropages.editor.FormulaEngine.display(sheet, r, c, model.sheets, model.names, memo)
                            }.toByteArray())
                        }
                        else -> XlsxWriter.write(model, out, values)
                    }
                }
                override fun read(path: String): Workbook = readWorkbook(path)
                override fun load(model: Workbook, recovered: Boolean) = editor.load(model, recovered)
                override fun detach(model: Workbook) { model.sourcePath = null }
                override fun markSaved(path: String, clean: Boolean) = editor.markSaved(path, clean)
                override fun markSaved() = editor.markSaved()
                override fun losses(ext: String) =
                    if (ext == "csv") com.therealityteam.aeropages.format.FormatLosses.csv(editor.workbook) else emptySet()
            },
            windowId = windowId,
        )
    }

    var tab by remember { mutableStateOf(XTab.entries.firstOrNull { !it.contextual && it.name.equals(devTabName(), true) } ?: XTab.HOME) }
    // Excel's: a picture just inserted opens Picture Format, a shape or text box Shape Format.
    LaunchedEffect(editor.imageInserts) { if (editor.imageInserts > 0 && editor.selectedImage != null) tab = XTab.PICTURE }
    LaunchedEffect(editor.shapeInserts) { if (editor.shapeInserts > 0 && editor.selectedShape != null) tab = XTab.SHAPE }
    var picker by remember { mutableStateOf(XPicker.NONE) }
    // Page Layout ▸ Page Setup (dev hooks: `sheetsetup`, `sheetsetupsheet`, `sheetmargins`).
    var setupTab by remember { mutableStateOf(when (devDialogProp()) { "sheetsetup" -> SheetSetupTab.PAGE; "sheetsetupsheet" -> SheetSetupTab.SHEET; "sheetsetupheader" -> SheetSetupTab.HEADER_FOOTER; else -> null }) }
    var showCond by remember { mutableStateOf(false) }
    var condStart by remember { mutableStateOf(CondCategory.HIGHLIGHT) }
    var showFind by remember { mutableStateOf(devDialogProp() == "findpanel" || devDialogProp() == "replacepanel") }
    // The Office Clipboard pane (Home ▸ Clipboard's launcher; dev hook `clipboardpane`) and Excel's Comments pane (the
    // tab row's Comments; dev hook `sheetcomments`), for this session only; with more than one open (Find too), the one
    // opened last is in front of the side window.
    var showClipboard by remember { mutableStateOf(devDialogProp() == "clipboardpane") }
    var showComments by remember { mutableStateOf(devDialogProp() == "sheetcomments") }
    // Formulas ▸ Watch Window (`watchwindow`: B6, B3 and the selection's formulas watched once the workbook has loaded).
    var showWatch by remember { mutableStateOf(devDialogProp() == "watchwindow" || devDialogProp() == "watchmove") }
    var sideFront by remember { mutableStateOf(if (showWatch) SheetSidePane.WATCH else if (showClipboard) SheetSidePane.CLIPBOARD else if (showComments) SheetSidePane.COMMENTS else SheetSidePane.FIND) }
    // Dev hook `sheetcomments`: two comments on the sheet (when it has none) three seconds in, the first one's cell active.
    LaunchedEffect(Unit) {
        if (devDialogProp() != "sheetcomments") return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        if (editor.commentCells().isEmpty()) {
            editor.select(3, 2); editor.setComment(Strings.get(R.string.search_demo) + " · Q3")
            editor.select(1, 1); editor.setComment(Strings.get(R.string.comment_placeholder))
        }
        editor.commentCells().firstOrNull()?.let { editor.select(it.row, it.col) }
    }
    // Check Accessibility from the status bar.
    var a11yOpen by remember { mutableStateOf(false) }
    if (a11yOpen) SheetsAccessibility(editor) { a11yOpen = false }
    var findReplace by remember { mutableStateOf(devDialogProp() == "replacepanel") }
    var showProofing by remember { mutableStateOf(false) }
    var showPivot by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var showGoalSeek by remember { mutableStateOf(false) }
    var cellFormatTab by remember { mutableIntStateOf(0) }
    // Insert Function: open, and the category it opened on (null: the most used).
    var showFunctions by remember { mutableStateOf(false) }
    var functionCategory by remember { mutableStateOf<FunctionCategory?>(null) }
    var showDataTable by remember { mutableStateOf(false) }
    var showScenarios by remember { mutableStateOf(false) }
    var showSubtotal by remember { mutableStateOf(false) }
    var showComment by remember { mutableStateOf(false) }
    // Dev hook `shownotes`: three seconds in, Show All Notes on and the first note's cell active; `shownote`: only that note kept shown.
    LaunchedEffect(Unit) {
        val hook = devDialogProp()
        if (hook != "shownotes" && hook != "shownote") return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        editor.commentCells().firstOrNull()?.let { editor.select(it.row, it.col) }
        if (hook == "shownotes") editor.toggleShowAllNotes() else editor.toggleNoteShown()
    }
    // Dev hook `notedialog`: the first note's cell active and its note open three seconds in.
    LaunchedEffect(Unit) {
        if (devDialogProp() != "notedialog") return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        editor.commentCells().firstOrNull()?.let { editor.select(it.row, it.col); showComment = true }
    }
    var showLink by remember { mutableStateOf(false) }
    // The link dialog opens on "Place in this workbook" (dev hook `sheetlinks`).
    var linkInWorkbook by remember { mutableStateOf(false) }
    /** Follows a cell's link: a place in this workbook (its sheet, its cells selected), else whatever app opens the address. */
    fun openLink(target: String) {
        if (editor.followLink(target)) return
        session.status = Strings.get(
            if (com.therealityteam.aeropages.model.CellLinks.place(target) != null) R.string.link_missing_place
            else if (openExternalUrl(context, target)) return else R.string.link_no_app)
    }
    var formulaBarVisible by remember { mutableStateOf(true) }
    var chartPanelOpen by rememberLayoutFlag("sheets.chartPanel")
    var showValidation by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showNames by remember { mutableStateOf(false) }
    // Name Manager opened on a new name for the selection (Define Name); Create from Selection.
    var namesNew by remember { mutableStateOf(false) }
    var showCreateNames by remember { mutableStateOf(false) }
    var showSheetManage by remember { mutableStateOf(false) }
    // Row Height… (true) or Column Width… (false), from Home → Format.
    var cellSizeRows by remember { mutableStateOf<Boolean?>(null) }
    // Insert → Symbol → More Symbols… (`dialog symbols`).
    var showSymbols by remember { mutableStateOf(devDialogProp() == "symbols") }
    // File → Info (`dialog fileinfo`).
    var showInfo by remember { mutableStateOf(devDialogProp() == "fileinfo") }
    var showShortcuts by remember { mutableStateOf(false) }
    var filterMenuCol by remember { mutableStateOf<Int?>(null) }
    // Excel's Remove Duplicates, and the message it ends with.
    var showRemoveDuplicates by remember { mutableStateOf(false) }
    var duplicatesResult by remember { mutableStateOf<String?>(null) }
    // Excel's Custom AutoFilter and Top 10 AutoFilter, opened from a filter button's Number / Text / Date Filters.
    var customFilter by remember { mutableStateOf<Pair<Int, FilterSeed>?>(null) }
    var top10Col by remember { mutableStateOf<Int?>(null) }
    var showSortDialog by remember { mutableStateOf(false) }
    // Excel's Go To (F5 / Ctrl+G) and Go To Special.
    var showGoTo by remember { mutableStateOf(false) }
    var showGoSpecial by remember { mutableStateOf(false) }
    var showCellFormat by remember { mutableStateOf(false) }
    var showPasteSpecial by remember { mutableStateOf(false) }
    // Data ▸ Filter (the ribbon and the grid's context menu): the AutoFilter on or off, said in the status bar.
    val toggleFilter = {
        session.status = when {
            !editor.toggleAutoFilter() -> Strings.get(R.string.status_select_data_first)
            editor.autoFilter == null -> Strings.get(R.string.status_filter_off)
            else -> editor.autoFilter!!.let { Strings.get(R.string.status_filter_on, com.therealityteam.aeropages.model.A1.rangeRef(it.r1, it.c1, it.r2, it.c2)) }
        }
    }
    var showImage by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(emptyList<FileEntry>()) }
    /** Bumped when the grid wants the formula bar focused (type-to-edit / Enter / F2). */
    var editRequest by remember { mutableIntStateOf(0) }
    // Insert → Symbol: after what the active cell holds (nothing is lost), left in editing so typing goes on.
    fun insertSymbol(sym: String) {
        val r = editor.selRow; val c = editor.selCol
        editor.typeCell(r, c, editor.sheet.cell(r, c)?.text.orEmpty() + sym)
        formulaBarVisible = true; editRequest++
    }
    // Bumped to open the active cell's in-cell list (a dev hook for screenshots).
    var listRequest by remember { mutableIntStateOf(0) }
    var gridFocusRequest by remember { mutableIntStateOf(0) }
    val tooltipHost = remember { TooltipHost() }
    // The dev hooks' and the status bar's clipboard (the grid and the ribbon keep their own).
    val devClipboard = rememberTextClipboard()

    session.Effects()

    // A protected sheet refuses edits silently at the editor level; say why here.
    LaunchedEffect(editor.structureNotice) {
        if (editor.structureNotice > 0) session.status = Strings.get(R.string.err_structure_locked)
    }
    LaunchedEffect(editor.protectionNotice) {
        if (editor.protectionNotice > 0) session.status = Strings.get(R.string.status_sheet_protected)
    }

    LaunchedEffect(Unit) {
        if (openPath != null) session.openNow(openPath)
        else if (template != null && !template.blank) { editor.load(Templates().workbook(template)); session.startedFrom(template) }
        else { editor.load(Workbook.newBlank()); session.status = Strings.get(R.string.new_workbook) }
        session.checkDraft()
        when (devDialogProp()) { "open" -> session.showOpen = true; "save" -> session.showSave = true; "filemenu" -> session.showFileMenu = true; "keeporiginal" -> session.save()
            // A second sheet, then Save: what a CSV cannot hold, so Save in This Format? asks (open a .csv with it).
            "lossycsv" -> { editor.addSheet(); session.save() } }
        // Perf hooks (debug builds): `setprop debug.aero.dialog bigsheet` fills 6000 rows (18k cells) to exercise grid
        // virtualisation; `bigsheet100k` fills 25 000 rows × 4 columns (100k cells, 25k formulas) for the performance baseline.
        val sheetHook = devDialogProp()
        // `bigsheetzoom` is `bigsheet` at 10 % — the most cells the grid ever composes at once.
        if (sheetHook == "bigsheet" || sheetHook == "bigsheet100k" || sheetHook == "bigsheetzoom") {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val rows = if (sheetHook == "bigsheet100k") 25_000 else 6000
            val s = editor.sheet
            for (r in 0 until rows) {
                s.ensure(r, 0).text = Strings.get(R.string.row_label, r + 1)
                s.ensure(r, 1).text = "${(r * 37) % 1000}"
                s.ensure(r, 2).text = "=B${r + 1}*2"
                if (rows > 6000) s.ensure(r, 3).text = if (r % 7 == 0) "=SUM(B${(r - 6).coerceAtLeast(0) + 1}:B${r + 1})" else "item ${r % 100}"
            }
            s.rowCount = rows; s.colCount = 26
            if (sheetHook == "bigsheetzoom") s.zoom = com.therealityteam.aeropages.model.Sheet.MIN_ZOOM
            editor.load(editor.workbook)
            val loaded = android.os.SystemClock.elapsedRealtime() - t0
            android.util.Log.i("AeroPerf", "$sheetHook generated+loaded in $loaded ms (${rows} rows)")
            logFirstFrame("$sheetHook first frame", t0)
            session.status = Strings.get(R.string.status_perf_test_generated)
        }
        // Dev hooks (debug builds): Format Cells, what-if dialogs, pivot / selection-stats demos for screenshot verification.
        when (devDialogProp()) {
            "cellformat" -> showCellFormat = true
            // Format Cells on another tab, the budget's expense block selected (`cellformat_align|font|border|fill|protect`).
            "cellformat_number" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(12, 2); showCellFormat = true }
            // Format Cells → Alignment on A1 (a turned header in the orientation sample).
            "cellformat_orient" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(0, 0); cellFormatTab = 1; showCellFormat = true }
            "cellformat_align", "cellformat_font", "cellformat_border", "cellformat_fill", "cellformat_protect" -> scope.launch {
                kotlinx.coroutines.delay(3_000); editor.select(11, 0); editor.extendSelection(3, 3)
                cellFormatTab = listOf("", "align", "font", "border", "fill", "protect").indexOf(devDialogProp().substringAfter('_')); showCellFormat = true
            }
            // Insert Function on its most used functions (`insertfunction`) or Lookup & Reference (`fnlookup`); AutoSum's menu under the budget's food row (`autosum`).
            "insertfunction" -> showFunctions = true
            "fnlookup" -> { functionCategory = FunctionCategory.LOOKUP; showFunctions = true }
            "autosum" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(18, 2); picker = XPicker.AUTOSUM }
            // A row inserted at the top once the workbook has loaded: the rules' ranges and formulas move with the cells (`condinsert`).
            "condinsert" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(0, 0); editor.insertRowAt(0) }
            // Sort from one cell of the second column (`sortquick`: A→Z, the header guessed) or Custom Sort's dialog there (`customsort`).
            "sortquick" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 1); editor.sortSelection(true) }
            // Remove Duplicates on the data around B2, once the workbook has loaded.
            "removeduplicates" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 1); showRemoveDuplicates = true }
            // Go To with a name defined (`goto`), Go To Special (`gotospecial`), the Find & Select menu (`findselect`),
            // and the formulas and the blanks of the data selected as areas, then bolded (`gospecialformulas`, `gospecialblanks`).
            // Formulas ▸ Trace Precedents twice from B6 (`traceprecedents`) / Trace Dependents twice from B3 (`tracedependents`).
            "watchwindow" -> scope.launch {
                kotlinx.coroutines.delay(3_000); tab = XTab.FORMULA
                editor.addWatches("B6"); editor.addWatches("B3"); editor.addWatches("B13:B14"); editor.select(2, 1)
            }
            // The same watches, then two rows inserted at the top: they follow their cells (B6 → B8 …).
            "watchmove" -> scope.launch {
                kotlinx.coroutines.delay(3_000); tab = XTab.FORMULA
                editor.addWatches("B6"); editor.addWatches("B3"); editor.addWatches("B13:B14")
                editor.insertRowAt(0); editor.insertRowAt(0); editor.select(7, 1)
            }
            "evaluateformula" -> scope.launch { kotlinx.coroutines.delay(3_000); tab = XTab.FORMULA; editor.select(5, 1) }
            "traceprecedents" -> scope.launch { kotlinx.coroutines.delay(3_000); tab = XTab.FORMULA; editor.select(5, 1); editor.tracePrecedents(); editor.tracePrecedents() }
            "tracedependents" -> scope.launch { kotlinx.coroutines.delay(3_000); tab = XTab.FORMULA; editor.select(2, 1); editor.traceDependents(); editor.traceDependents() }
            "goto" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 1); editor.goToRecent.add("D12"); showGoTo = true }
            "gotospecial" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 1); showGoSpecial = true }
            "findselect" -> scope.launch { kotlinx.coroutines.delay(3_000); tab = XTab.HOME; picker = XPicker.FINDSELECT }
            "gospecialformulas" -> scope.launch {
                kotlinx.coroutines.delay(3_000); editor.select(0, 0)
                editor.goToSpecial(com.therealityteam.aeropages.editor.SheetEditor.GoSpecial(com.therealityteam.aeropages.editor.SheetEditor.Special.FORMULAS))
            }
            "gospecialblanks" -> scope.launch {
                kotlinx.coroutines.delay(3_000); editor.select(0, 0)
                editor.goToSpecial(com.therealityteam.aeropages.editor.SheetEditor.GoSpecial(com.therealityteam.aeropages.editor.SheetEditor.Special.BLANKS))
                editor.setHighlight(0xFFFFF2CC)
            }
            "customsort", "sortlevels", "sortcustomlist" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 1); showSortDialog = true }
            // Insert → Link on "Place in this workbook" for the first cell (`sheetlinks`), once the workbook has loaded.
            "sheetlinks" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 0); linkInWorkbook = true; showLink = true }
            // The first cell linking to a place selected, its link tip showing (`sheetlinktip`) — and followed (`sheetlinkfollow`).
            "sheetlinktip", "sheetlinkfollow" -> scope.launch {
                kotlinx.coroutines.delay(3_000)
                editor.sheet.cells.entries.sortedBy { it.key }.firstOrNull { com.therealityteam.aeropages.model.CellLinks.place(it.value.link) != null }?.let { e ->
                    editor.select((e.key / 1000L).toInt(), (e.key % 1000L).toInt())
                    if (devDialogProp() == "sheetlinkfollow") { kotlinx.coroutines.delay(1_500); openLink(e.value.link!!) }
                }
            }
            // Conditional formatting on the first column's cells: the rule menu, its icon sets, or the rules it holds.
            "condformat", "condicons", "condmanage" -> scope.launch {
                kotlinx.coroutines.delay(3_000)
                editor.select(1, 0); editor.extendSelection(11, 0)
                condStart = when (devDialogProp()) { "condicons" -> CondCategory.ICON_SETS; "condmanage" -> CondCategory.MANAGE; else -> CondCategory.HIGHLIGHT }
                showCond = true
            }
            // Review → Proofing on the opened workbook (once it has loaded).
            "proofing" -> scope.launch { kotlinx.coroutines.delay(4_000); showProofing = true }
            "pastespecial" -> { editor.select(0, 0); editor.copySelection(); editor.select(0, 2); showPasteSpecial = true }
            // The object clipboard end to end: the first picture copied as Ctrl+C copies it, pasted at H2 as Ctrl+V pastes.
            "pasteobject" -> editor.sheet.images.firstOrNull()?.let { img ->
                editor.selectImage(img); copyCells(editor, devClipboard)
                editor.select(1, 7); pasteCells(editor, devClipboard)
            }
            "bordersmenu" -> picker = XPicker.BORDERS
            "sheetmargins" -> { tab = XTab.LAYOUT; picker = XPicker.PMARGINS }
            "whatifmenu" -> { tab = XTab.DATA; picker = XPicker.WHATIF }
            // Formulas → Defined Names, once the workbook has loaded: Name Manager; a new name for A2:A13; Create from Selection over A1:C13.
            "namemanager" -> scope.launch { kotlinx.coroutines.delay(3_000); tab = XTab.FORMULA; showNames = true }
            "definename" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 0); editor.extendSelection(11, 0); namesNew = true; showNames = true }
            "createnames" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(0, 0); editor.extendSelection(12, 2); showCreateNames = true }
            "datavalidmenu" -> { tab = XTab.DATA; picker = XPicker.DATAVALID }
            "sheetscale" -> { tab = XTab.LAYOUT; picker = XPicker.PSCALE }
            // A manual break above row 8 and left of column D, then the breaks shown in Normal (or the preview).
            // Page Break Preview with the break above row 11 being dragged down to row 15, shown where it would go.
            "pagebreakdrag" -> scope.launch {
                kotlinx.coroutines.delay(3_000)
                editor.select(7, 3); editor.insertPageBreak(); editor.setPageBreakPreview(true); tab = XTab.VIEW
                editor.edgeDrag = SheetEditor.EdgeDrag(true, 10, 14)
            }
            "pagebreaks", "pagebreakpreview" -> scope.launch {
                kotlinx.coroutines.delay(3_000)
                editor.select(7, 3); editor.insertPageBreak()
                if (devDialogProp() == "pagebreakpreview") { editor.setPageBreakPreview(true); tab = XTab.VIEW } else { tab = XTab.LAYOUT; picker = XPicker.PBREAKS }
            }
            "cellstyles" -> picker = XPicker.CELLSTYLES
            "filtermenu" -> { editor.select(1, 1); if (editor.autoFilter == null) editor.toggleAutoFilter(); filterMenuCol = 1 }
            // The filter menu's submenus (`filtercriteria`, `filtercolor`, on column B), Custom AutoFilter (`customfilter`:
            // greater than or equal to 20 And less than or equal to 80) and Top 10 (`top10filter`), once the workbook has loaded.
            "filtercriteria", "filtercolor", "sortcolor", "customfilter", "top10filter" -> scope.launch {
                kotlinx.coroutines.delay(3_000)
                editor.select(1, 1); if (editor.autoFilter == null) editor.toggleAutoFilter()
                when (devDialogProp()) {
                    "customfilter" -> customFilter = 1 to FilterSeed(com.therealityteam.aeropages.model.FilterCondition.Op.GREATER_OR_EQUAL, com.therealityteam.aeropages.model.FilterCondition.Op.LESS_OR_EQUAL)
                    "top10filter" -> top10Col = 1
                    else -> filterMenuCol = 1
                }
            }
            // Data validation: the dialog on a list rule, the in-cell list open, an input message, a Stop alert, circled invalid data.
            "validation", "validationlist", "validationinput", "validationalert", "validationcircles" -> {
                val s = editor.sheet
                listOf("Task", "Owner", "Status", "Due", "Hours").forEachIndexed { c, t -> s.ensure(0, c).text = t }
                listOf(listOf("Design review", "Ann", "Done", "46284", "6"), listOf("Build", "Ben", "In progress", "46290", "30"),
                    listOf("Launch", "Cy", "Blocked", "46300", "90")).forEachIndexed { r, row ->
                    row.forEachIndexed { c, t -> s.ensure(r + 1, c).text = t; if (c == 3) s.ensure(r + 1, c).numberFormat = com.therealityteam.aeropages.model.NumberFormat.DATE }
                }
                editor.select(1, 2); editor.extendSelection(9, 0)
                editor.setValidation(com.therealityteam.aeropages.model.DataValidation(0, 0, 0, 0, com.therealityteam.aeropages.model.ValidationType.LIST,
                    items = listOf("Not started", "In progress", "Done"), inputTitle = "Status", inputMessage = "Pick the task's status from the list."))
                editor.select(1, 4); editor.extendSelection(9, 0)
                editor.setValidation(com.therealityteam.aeropages.model.DataValidation(0, 0, 0, 0, com.therealityteam.aeropages.model.ValidationType.INTEGER, 0.0, 40.0,
                    errorTitle = "Too many hours", errorMessage = "A task takes 0 to 40 hours."))
                when (devDialogProp()) {
                    "validation" -> { editor.select(1, 2); showValidation = true }
                    "validationlist" -> { editor.select(2, 2); listRequest++ }
                    "validationinput" -> editor.select(2, 2)
                    "validationalert" -> { editor.select(2, 4); editor.typeCell(2, 4, "55"); editor.select(3, 4) }
                    else -> { editor.circleInvalidData(); editor.select(6, 0) }
                }
            }
            "tablestyles" -> picker = XPicker.TABLESTYLES
            // The sheet's first shape picked (its handles and actions); "shapetext" opens its Edit Text too,
            // "shapeoutline" picks its first line and opens the outline menu (see SheetGrid).
            "shapes", "shapetext" -> editor.sheet.shapes.firstOrNull()?.let { editor.selectShape(it); if (devDialogProp() == "shapetext") editor.requestShapeText() }
            "shapeoutline" -> (editor.sheet.shapes.firstOrNull { it.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE } ?: editor.sheet.shapes.firstOrNull())?.let { editor.selectShape(it) }
            // The first table's Table Design tab, from a cell of its total row when it has one; "tablegallery" opens its styles too.
            "tabledesign", "tablegallery" -> editor.sheet.tables.firstOrNull()?.let { t ->
                editor.select(if (t.totalsRow) t.r2 else t.dataR1, t.c2)
                tab = XTab.TABLE
                if (devDialogProp() == "tablegallery") picker = XPicker.TABLESTYLES
            }
            // The sheet's first picture picked: its frame, handles and actions.
            "picture" -> editor.sheet.images.firstOrNull()?.let { editor.selectImage(it) }
            // The first picture or shape picked with its Picture Format / Shape Format tab open.
            "picformat", "alttext" -> editor.sheet.images.firstOrNull()?.let { editor.selectImage(it); tab = XTab.PICTURE }
            "shapeformat" -> editor.sheet.shapes.firstOrNull()?.let { editor.selectShape(it); tab = XTab.SHAPE }
            // Sheet tabs: five sheets, three with tab colours and one hidden, a summary reading two of them;
            // "sheetmanage" opens the active sheet's commands too.
            "sheettabs", "sheetmanage" -> {
                repeat(4) { editor.addSheet() }
                listOf("Summary", "Q1 Sales", "Q2 Sales", "Archive", "Notes").forEachIndexed { i, n -> editor.renameSheet(i, n) }
                editor.setTabColor(1, TAB_COLORS[7]); editor.setTabColor(2, TAB_COLORS[5]); editor.setTabColor(3, TAB_COLORS[0])
                for ((si, q) in listOf(1 to 1, 2 to 2)) {
                    val s = editor.workbook.sheets[si]
                    listOf("Region", "Sales").forEachIndexed { c, t -> s.ensure(0, c).text = t }
                    listOf("North" to 120 * q, "South" to 90 * q, "East" to 75 * q).forEachIndexed { r, (region, v) -> s.ensure(r + 1, 0).text = region; s.ensure(r + 1, 1).text = "$v" }
                }
                val sum = editor.workbook.sheets[0]
                listOf("Quarter", "Total").forEachIndexed { c, t -> sum.ensure(0, c).text = t }
                sum.ensure(1, 0).text = "Q1"; sum.ensure(1, 1).text = "=SUM('Q1 Sales'!B2:B4)"
                sum.ensure(2, 0).text = "Q2"; sum.ensure(2, 1).text = "=SUM('Q2 Sales'!B2:B4)"
                editor.hideSheet(3)
                editor.selectSheet(1)
                if (devDialogProp() == "sheetmanage") showSheetManage = true
            }
            // Home's menus and dialogs, once the workbook has loaded: Paste's options, Fill, Format (and its Row Height… /
            // Column Width…), and Format Painter taken from A2:B3 and armed.
            // View: the zoom and Freeze Panes menus; the sheet at 150 %; Zoom to Selection over A1:D6 (once the workbook has loaded).
            "zoommenu" -> { tab = XTab.VIEW; picker = XPicker.ZOOM }
            "freezemenu" -> { tab = XTab.VIEW; picker = XPicker.FREEZE }
            "zoom150" -> scope.launch { kotlinx.coroutines.delay(3_000); tab = XTab.VIEW; editor.setZoom(150) }
            "zoomfit" -> scope.launch { kotlinx.coroutines.delay(3_000); tab = XTab.VIEW; editor.select(0, 0); editor.extendSelection(5, 3); editor.requestZoomToSelection() }
            "homepaste" -> picker = XPicker.PASTEMENU
            "fillcolor" -> picker = XPicker.FILL
            // The grid's selection look (`selectrange`: B2:D5) and the formula bar while a cell is typed in (`formulatyping`).
            "selectrange", "statusfigures" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 1); editor.extendSelection(3, 2) }
            // `flashfill`: names in F, "Last, First" typed once in G, Flash Fill for the rest (the status bar says how many).
            "flashfill" -> scope.launch {
                kotlinx.coroutines.delay(3_000)
                editor.setCell(0, 5, "Full name"); editor.setCell(0, 6, "Last, first")
                listOf("Ada Lovelace", "Grace Hopper", "Alan Turing", "Katherine Johnson", "Tim Berners Lee").forEachIndexed { i, n -> editor.setCell(i + 1, 5, n) }
                editor.setCell(1, 6, "Lovelace, Ada")
                editor.select(2, 6)
                runFlashFill(editor) { session.status = it }
            }
            "shortcuts" -> showShortcuts = true
            "picklist" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(5, 0); listRequest++ }
            "formulatyping" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(8, 1); editor.typeCell(8, 1, "=SUM(B2:B7"); editRequest++ }
            // A column chart from A1:C5 and its pane (`setprop debug.aero.dialog chartpane`).
            "chartpane" -> scope.launch {
                kotlinx.coroutines.delay(3_000); editor.select(0, 0); editor.extendSelection(4, 2)
                if (editor.insertChartFromSelection(com.therealityteam.aeropages.model.ChartType.COLUMN)) chartPanelOpen = true
            }
            "homefill" -> picker = XPicker.FILLMENU
            // Home → Alignment: the Orientation and Merge menus on A1 (once the file has loaded).
            "orientmenu", "mergemenu" -> scope.launch {
                kotlinx.coroutines.delay(3_000); editor.select(0, 0)
                picker = if (devDialogProp() == "orientmenu") XPicker.ORIENT else XPicker.MERGE
            }
            "homeformat" -> picker = XPicker.FORMATCELLS
            "rowheight", "colwidth" -> scope.launch { kotlinx.coroutines.delay(3_000); editor.select(1, 1); cellSizeRows = devDialogProp() == "rowheight" }
            "painter" -> scope.launch {
                kotlinx.coroutines.delay(3_000); editor.select(1, 0); editor.extendSelection(1, 1); editor.formatPainter()
                session.status = Strings.get(R.string.status_sheet_painter_armed)
            }
            "goalseek" -> showGoalSeek = true
            "datatable" -> showDataTable = true
            "scenarios" -> showScenarios = true
            "subtotal" -> showSubtotal = true
            "pivot", "pivotresult" -> {
                val s = editor.sheet
                val demo = listOf(
                    listOf(Strings.get(R.string.demo_col_region), Strings.get(R.string.demo_col_product), Strings.get(R.string.demo_col_sales)),
                    listOf(Strings.get(R.string.demo_region_north), Strings.get(R.string.demo_product_apple), "10"), listOf(Strings.get(R.string.demo_region_north), Strings.get(R.string.demo_product_banana), "20"),
                    listOf(Strings.get(R.string.demo_region_south), Strings.get(R.string.demo_product_apple), "30"), listOf(Strings.get(R.string.demo_region_south), Strings.get(R.string.demo_product_banana), "40"), listOf(Strings.get(R.string.demo_region_south), Strings.get(R.string.demo_product_apple), "5"),
                )
                demo.forEachIndexed { r, cols -> cols.forEachIndexed { c, t -> s.ensure(r, c).text = t } }
                editor.select(0, 0); editor.extendSelection(5, 2)
                if (devDialogProp() == "pivot") showPivot = true
                else editor.buildPivot(0, 1, 2, com.therealityteam.aeropages.editor.PivotAgg.SUM)
            }
            "stats" -> {
                val s = editor.sheet
                for (r in 0..4) s.ensure(r, 0).text = "${(r + 1) * 10}"
                editor.select(0, 0); editor.extendSelection(4, 0)
                session.status = Strings.get(R.string.status_selection_stats_demo)
            }
        }
        // Dev hook (debug builds): `setprop debug.aero.page B2:D8` goes there through the Name Box (emulator screenshots).
        devPageProp().takeIf { it.isNotEmpty() && it != "0" }?.let { editor.goTo(it) }
        // Dev hook (debug builds): `adb shell setprop debug.aero.export pdf` → export the open workbook
        // on launch as dev-sheets.pdf (a blank workbook gets a sample grid first).
        if (devExportProp() == "pdf") {
            val wb = editor.workbook
            if (wb.sheets.all { it.cells.isEmpty() }) wb.sheets[0].let { s ->
                listOf(Strings.get(R.string.demo_col_product), Strings.get(R.string.demo_col_q1), Strings.get(R.string.demo_col_q2)).forEachIndexed { c, t -> s.cells[s.key(0, c)] = com.therealityteam.aeropages.model.Cell(t, com.therealityteam.aeropages.model.CharacterFormat(bold = true)) }
                for (r in 1..30) {
                    s.cells[s.key(r, 0)] = com.therealityteam.aeropages.model.Cell(Strings.get(R.string.demo_product_label, r))
                    s.cells[s.key(r, 1)] = com.therealityteam.aeropages.model.Cell("${r * 10}")
                    s.cells[s.key(r, 2)] = com.therealityteam.aeropages.model.Cell("=B${r + 1}*2")
                }
            }
            val snap = wb.copyOf()
            val outFile = File(workDir, "dev-sheets.pdf")
            runCatching {
                withContext(Dispatchers.IO) {
                    outFile.outputStream().use { out ->
                        com.therealityteam.aeropages.format.pdf.SheetsPdfExporter.export(snap, { si, r, c ->
                            com.therealityteam.aeropages.editor.FormulaEngine.display(snap.sheets[si], r, c, snap.sheets, snap.names)
                        }, out)
                    }
                }
            }.onSuccess { android.util.Log.i("AeroExport", "sheets pdf ok: ${outFile.absolutePath} (${outFile.length()} bytes)") }
                .onFailure { android.util.Log.e("AeroExport", "sheets pdf fail", it) }
        }
    }

    val doExportPdf: () -> Unit = {
        session.export("pdf", "PDF") { wb, out ->
            com.therealityteam.aeropages.format.pdf.SheetsPdfExporter.export(wb, { si, r, c ->
                com.therealityteam.aeropages.editor.FormulaEngine.display(wb.sheets[si], r, c, wb.sheets, wb.names)
            }, out, fileName = session.sourcePath?.let { File(it).name } ?: "")
        }
    }
    val doExportCsv: () -> Unit = {
        val csv = editor.toCsv() // evaluated here, on the thread that owns the editor's caches
        session.export("csv", "CSV") { _, out -> out.write(csv.toByteArray()) }
    }

    com.pico.spatial.ui.design.text.TextSelectionAndToolbarProvider(
        colors = androidx.compose.foundation.text.selection.TextSelectionColors(
            handleColor = PicoTheme.colorScheme.interaction,
            backgroundColor = PicoTheme.colorScheme.interaction.copy(alpha = 0.35f),
        ),
    ) {
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Search's "Find in Workbook" opens Find and Replace with the words in it (a new panel each time, so the words show).
    var findSeed by remember { mutableStateOf(if (devDialogProp() == "findpanel") "e" else "") }
    var findSeedRev by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val ribbonFold = rememberRibbonFold("sheets")
    androidx.compose.runtime.CompositionLocalProvider(LocalTooltipHost provides tooltipHost, LocalAccent provides Aero.sheets, LocalSearchFocus provides searchFocus,
        LocalRibbonFold provides ribbonFold) {
    Box(Modifier.fillMaxSize().ribbonPeekDismiss(ribbonFold).onPreviewKeyEvent { e -> searchShortcut(e, searchFocus) || ribbonShortcut(e, ribbonFold) }) {
        Column(Modifier.fillMaxSize()) {
            SheetsRibbon(editor, tab, { tab = it }, picker, { picker = if (picker == it) XPicker.NONE else it }, onPageSetup = { setupTab = it },
                title = session.sourceName ?: session.untitledName, saveState = docState(session),
                onCellSize = { cellSizeRows = it }, onManageSheets = { showSheetManage = true },
                onSymbol = { insertSymbol(it) }, onMoreSymbols = { showSymbols = true },
                onHome = { session.guardDirty(onHome) }, onSave = session::save, dirty = editor.dirty, onStatus = { session.status = it },
                onCondFormat = { showCond = true }, onFind = { findReplace = false; showFind = true; sideFront = SheetSidePane.FIND },
                onReplace = { findReplace = true; showFind = true; sideFront = SheetSidePane.FIND },
                onGoTo = { showGoTo = true }, onGoSpecial = { showGoSpecial = true },
                onFindInFile = { q -> findSeed = q; findSeedRev++; findReplace = false; showFind = true; sideFront = SheetSidePane.FIND },
                commentsOpen = showComments && (sideFront == SheetSidePane.COMMENTS || !(showFind || showClipboard)),
                onToggleComments = {
                    if (showComments && sideFront != SheetSidePane.COMMENTS) sideFront = SheetSidePane.COMMENTS
                    else { showComments = !showComments; if (showComments) sideFront = SheetSidePane.COMMENTS }
                },
                watchOpen = showWatch && (sideFront == SheetSidePane.WATCH || !(showFind || showClipboard || showComments)),
                onWatchWindow = {
                    if (showWatch && sideFront != SheetSidePane.WATCH) sideFront = SheetSidePane.WATCH
                    else { showWatch = !showWatch; if (showWatch) sideFront = SheetSidePane.WATCH }
                },
                onClipboardPane = {
                    if (showClipboard && sideFront != SheetSidePane.CLIPBOARD) sideFront = SheetSidePane.CLIPBOARD
                    else { showClipboard = !showClipboard; if (showClipboard) sideFront = SheetSidePane.CLIPBOARD }
                },
                onTextToColumns = { showSplit = true }, onComment = { showComment = true }, onProofing = { showProofing = true }, onInsertLink = { showLink = true }, onOpenLink = { openLink(it) }, onGoalSeek = { showGoalSeek = true }, onDataTable = { showDataTable = true }, onScenarios = { showScenarios = true }, onSubtotal = { showSubtotal = true },
                onInsertChart = {
                    if (editor.insertChartFromSelection(com.therealityteam.aeropages.model.ChartType.COLUMN)) {
                        chartPanelOpen = true; session.status = Strings.get(R.string.status_chart_generated)
                    } else session.status = Strings.get(R.string.status_need_range_2d)
                },
                onInsertPicture = { images = listImages(workDir); showImage = true },
                onValidation = { showValidation = true },
                onPivot = {
                    if (editor.rangeRowMax() > editor.rangeRowMin()) showPivot = true
                    else session.status = Strings.get(R.string.status_need_range_header)
                },
                onFilterDialog = { showFilterDialog = true },
                onAutoFilter = toggleFilter,
                onSortDialog = { showSortDialog = true },
                onRemoveDuplicates = { showRemoveDuplicates = true },
                onCellFormat = { cellFormatTab = it; showCellFormat = true },
                onPasteSpecial = { showPasteSpecial = true },
                onNames = { namesNew = false; showNames = true },
                onDefineName = { namesNew = true; showNames = true },
                onCreateNames = { showCreateNames = true },
                onUseName = { n ->
                    // Into a formula being written (after an operator, a bracket or a comma) the name joins it; else it starts one.
                    val r = editor.selRow; val c = editor.selCol
                    val current = editor.sheet.cell(r, c)?.text.orEmpty()
                    val start = if (current.startsWith("=") && current.trimEnd().lastOrNull()?.let { it in "=(,+-*/&^<>:" } == true) current else "="
                    editor.typeCell(r, c, start + n)
                    formulaBarVisible = true; editRequest++
                },
                onExportCsv = doExportCsv,
                onExportPdf = doExportPdf,
                fileMenuOpen = session.showFileMenu,
                onToggleFileMenu = { session.showFileMenu = !session.showFileMenu },
                onShortcuts = { showShortcuts = true },
                formulaBarVisible = formulaBarVisible, onToggleFormulaBar = { formulaBarVisible = !formulaBarVisible },
                showGridlines = editor.sheet.showGridlines, onToggleGridlines = editor::toggleGridlines,
                onInsertFunction = { functionCategory = it; showFunctions = true },
                onStartFormula = { formulaBarVisible = true; editRequest++ })
            SessionBanner(session)
            if (formulaBarVisible) FormulaBar(editor, editRequest, onEditComment = { showComment = true },
                onStatus = { session.status = it }, onNameBoxDone = { gridFocusRequest++ },
                onCommit = { editor.moveSelection(1, 0); gridFocusRequest++ },
                onInsertFunction = { showFunctions = true })
            Row(Modifier.weight(1f)) {
                // The sheet's zoom: the grid laid out at a density scaled by it (cells, type, pictures and gestures alike);
                // the bars and tips over it keep the app's own size.
                val base = androidx.compose.ui.platform.LocalDensity.current
                @Suppress("UNUSED_VARIABLE") val zoomRev = editor.revision
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * editor.sheet.viewZoom / 100f, base.fontScale),
                    LocalUiDensity provides base,
                ) {
                SheetGrid(editor, Modifier.weight(1f), editor.sheet.showGridlines, onFind = { showFind = true; sideFront = SheetSidePane.FIND }, onStartEdit = { seed ->
                    // Excel-style: typing on a selected cell starts editing it (the formula bar takes focus).
                    if (seed != null) editor.typeCell(editor.selRow, editor.selCol, seed)
                    formulaBarVisible = true
                    editRequest++
                }, focusRequest = gridFocusRequest, onFilterMenu = { filterMenuCol = it }, listRequest = listRequest,
                    onTableRefused = { session.status = tableRefusalText(editor.tableRefusal) }, onOpenLink = { openLink(it) },
                    menu = remember(editor) { GridMenuActions(
                        onPasteSpecial = { showPasteSpecial = true }, onComment = { showComment = true },
                        onCellFormat = { cellFormatTab = 0; showCellFormat = true }, onLink = { showLink = true },
                        onDefineName = { namesNew = true; showNames = true }, onFilter = { toggleFilter() }, onCustomSort = { showSortDialog = true },
                        onCellSize = { cellSizeRows = it }, onStatus = { session.status = it }, onGoTo = { showGoTo = true },
                    ) }, quick = remember(editor) { QuickActions(
                        onGreaterThan = { condStart = CondCategory.HIGHLIGHT; showCond = true },
                        onChartMade = { chartPanelOpen = true }, onTableMade = { tab = XTab.TABLE },
                        onPivot = { showPivot = true }, onStatus = { session.status = it },
                    ) })
                }
                if (chartPanelOpen && editor.sheet.charts.isNotEmpty()) {
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                    SheetChartPanel(editor, onClose = { chartPanelOpen = false })
                }
            }
            HorizontalDivider()
            SheetTabBar(editor, onManage = { showSheetManage = true }, onStatus = { session.status = it })

            @Suppress("UNUSED_VARIABLE") val statusRev = editor.revision
            AeroStatusBar(
                start = {
                    // A validation message is a warning: its own colour and a mark, not colour alone.
                    val warning = editor.validationError
                    if (warning != null) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RibbonGlyph("ic_warning", PicoTheme.colorScheme.error, 16.dp)
                        Text(warning, style = AeroType.caption, color = PicoTheme.colorScheme.error, maxLines = 1)
                    }
                    else {
                        // Excel's mode first — Ready, or Enter while a cell is being typed in — then what just happened.
                        StatusText(stringResource(if (editor.entryOpen) R.string.status_enter else R.string.status_ready))
                        AccessibilityStatus(statusFindings(com.therealityteam.aeropages.model.AccessibilityCheck.of(editor.workbook))) { a11yOpen = true }
                        StatusText(session.status, Modifier.weight(1f))
                    }
                },
                end = {
                    // Excel's selection aggregates (Sum, Average, Count), and the zoom by tens (the active cell is in the Name Box).
                    SelectionFigures(editor) { value -> devClipboard.copy(value); session.status = Strings.get(R.string.status_copied) }
                    // Excel's view buttons: Normal and Page Break Preview.
                    StatusView("ic_grid", stringResource(R.string.view_normal), !editor.sheet.pageBreakPreview) { editor.setPageBreakPreview(false) }
                    StatusView("ic_page_break_preview", stringResource(R.string.page_break_preview), editor.sheet.pageBreakPreview) { editor.setPageBreakPreview(true) }
                    val z = editor.sheet.viewZoom
                    StatusZoom(z, com.therealityteam.aeropages.model.Sheet.MIN_ZOOM, com.therealityteam.aeropages.model.Sheet.MAX_ZOOM, editor::setZoom,
                        { tab = XTab.VIEW; picker = XPicker.ZOOM }, stringResource(R.string.zoom_out), stringResource(R.string.zoom_in))
                },
            )
        }

        SessionFileMenu(
            session,
            newLabel = stringResource(R.string.new_workbook),
            onNew = { session.newFrom(DeskTemplate.BLANK_BOOK, Strings.get(R.string.new_workbook)) { Workbook.newBlank() } },
            onExportPdf = doExportPdf,
            templates = DeskTemplate.of(com.therealityteam.aeropages.format.DeskKind.SHEETS),
            onNewFrom = { t -> session.newFrom(t, Strings.get(R.string.new_workbook)) { Templates().workbook(t) } },
            onExportCsv = doExportCsv,
            onInfo = { showInfo = true },
        )
        if (showInfo) {
            val wb = editor.workbook
            DocInfoDialog(wb.props, wb.sourcePath, listOf(
                stringResource(R.string.stat_sheets) to wb.sheets.size.toString(),
                stringResource(R.string.stat_cells) to wb.sheets.sumOf { s -> s.cells.values.count { it.text.isNotEmpty() } }.toString(),
                stringResource(R.string.stat_formulas) to wb.sheets.sumOf { s -> s.cells.values.count { it.text.startsWith("=") } }.toString(),
                stringResource(R.string.stat_pictures) to wb.sheets.sumOf { it.images.size }.toString(),
            ), onApply = editor::setProps) { showInfo = false }
        }

        setupTab?.let { t -> SheetPageSetupDialog(editor, t) { setupTab = null } }
        if (showCond) {
            CondFormatDialog(editor, onDismiss = { showCond = false }, onApplied = { msg -> showCond = false; session.status = msg }, start = condStart)
        }

        if (showPivot) {
            PivotDialog(
                headers = editor.selectionHeaders(),
                onDismiss = { showPivot = false },
                onApply = { rowF, colF, valF, agg ->
                    showPivot = false
                    val name = editor.buildPivot(rowF, colF, valF, agg)
                    session.status = if (name != null) Strings.get(R.string.status_pivot_generated, name, agg.display) else Strings.get(R.string.status_no_summarizable_data)
                },
            )
        }

        if (showProofing) {
            // The words of every sheet shown: the device's spell checker's typos, then AeroDesk's own checks.
            val targets = remember { editor.proofTargets() }
            val texts = remember(targets) { targets.map { editor.proofText(it) } }
            ProofingSession(
                texts = texts,
                ownIssues = { editor.proofread(targets) },
                onJump = { i -> targets.getOrNull(i)?.let { editor.showProofTarget(it) } },
                apply = { issue, s -> val t = targets.getOrNull(issue.index); val w = issue.word; t != null && w != null && editor.applySpelling(t, issue.start, w, s) },
                onDismiss = { showProofing = false },
                // The cell: its sheet's name and its address.
                where = { issue ->
                    targets.getOrNull(issue.index)?.let { t ->
                        "${editor.workbook.sheets.getOrNull(t.sheet)?.name.orEmpty()} · ${com.therealityteam.aeropages.model.Workbook.colLabel(t.col)}${t.row + 1}"
                    }.orEmpty()
                },
            )
        }

        // Find and Replace beside the window, modeless as Excel's (dev hooks `findpanel`, `replacepanel`).
        val sidePanes = listOfNotNull(SheetSidePane.FIND.takeIf { showFind }, SheetSidePane.CLIPBOARD.takeIf { showClipboard }, SheetSidePane.COMMENTS.takeIf { showComments }, SheetSidePane.WATCH.takeIf { showWatch })
        if (sidePanes.isNotEmpty()) {
            com.pico.spatial.ui.design.windows.Subwindow(placement = com.pico.spatial.ui.design.windows.SubwindowPlacement.Right) {
                val front = sideFront.takeIf { it in sidePanes } ?: sidePanes.first()
                Column(Modifier.fillMaxSize()) {
                    if (sidePanes.size > 1) PaneSwitcher(sidePanes, front, { sideFront = it }, { stringResource(it.label) })
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (front) {
                            SheetSidePane.CLIPBOARD -> ClipboardPane(Modifier.fillMaxSize(), onPasteText = { if (it.isNotBlank()) editor.pasteTsv(it) }, onPasteObject = { editor.pasteItem(it) }) {
                                showClipboard = false
                            }
                            SheetSidePane.COMMENTS -> SheetCommentsPane(editor, Modifier.fillMaxSize(), onEdit = { showComment = true }) { showComments = false }
                            SheetSidePane.WATCH -> SheetWatchWindow(editor, Modifier.fillMaxSize()) { showWatch = false }
                            SheetSidePane.FIND -> androidx.compose.runtime.key(findSeedRev) { SheetFindPanel(editor, findReplace, { findReplace = it }, initialQuery = findSeed) { showFind = false } }
                        }
                    }
                }
            }
        }

        if (showSubtotal) {
            SubtotalDialog(onDismiss = { showSubtotal = false }) { groupRef, fn, targetRefs, header ->
                val group = groupRef.uppercase().takeIf { it.all { ch -> ch in 'A'..'Z' } && it.isNotEmpty() }?.let { com.therealityteam.aeropages.model.A1.colIndex(it) }
                val targets = targetRefs.uppercase().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    .mapNotNull { t -> t.takeIf { it.all { ch -> ch in 'A'..'Z' } }?.let { com.therealityteam.aeropages.model.A1.colIndex(it) } }
                if (group == null || targets.isEmpty()) {
                    session.status = Strings.get(R.string.err_subtotal)
                } else {
                    showSubtotal = false
                    val n = editor.subtotal(group, fn, targets, header)
                    session.status = if (n == 0) Strings.get(R.string.err_subtotal) else Strings.get(R.string.status_subtotal_done, n)
                }
            }
        }
        if (showScenarios) {
            ScenarioDialog(editor, onDismiss = { showScenarios = false }, onStatus = { session.status = it })
        }
        if (showDataTable) {
            DataTableDialog(onDismiss = { showDataTable = false }) { rowRef, colRef ->
                val row = rowRef.takeIf { it.isNotEmpty() }?.let { com.therealityteam.aeropages.model.A1.parse(it.uppercase()) }
                val col = colRef.takeIf { it.isNotEmpty() }?.let { com.therealityteam.aeropages.model.A1.parse(it.uppercase()) }
                if ((rowRef.isNotEmpty() && row == null) || (colRef.isNotEmpty() && col == null) || (row == null && col == null)) {
                    session.status = Strings.get(R.string.err_data_table)
                } else {
                    showDataTable = false
                    val n = editor.dataTable(row, col)
                    session.status = if (n == 0) Strings.get(R.string.err_data_table) else Strings.get(R.string.status_data_table_done, n)
                }
            }
        }
        if (showFunctions) {
            InsertFunctionDialog(functionCategory, onDismiss = { showFunctions = false }) { sig ->
                showFunctions = false
                val r = editor.selRow; val c = editor.selCol
                // Into a formula being written (after an operator, a bracket or a comma) the function joins it; else it starts one.
                val current = editor.sheet.cell(r, c)?.text.orEmpty()
                val start = if (current.startsWith("=") && current.trimEnd().lastOrNull()?.let { it in "=(,+-*/&^<>:" } == true) current else "="
                if (sig.params.isEmpty()) {
                    editor.setCell(r, c, start + sig.name + "()")
                    session.status = Strings.get(R.string.status_function_inserted, sig.name + "()")
                } else {
                    editor.typeCell(r, c, start + sig.name + "(")
                    formulaBarVisible = true; editRequest++
                    session.status = Strings.get(R.string.status_function_started, sig.name)
                }
            }
        }
        cellSizeRows?.let { rows -> CellSizeDialog(editor, rows) { cellSizeRows = null } }
        if (showSymbols) SymbolDialog(onInsert = { insertSymbol(it) }) { showSymbols = false }
        if (showGoalSeek) {
            GoalSeekDialog(initialSet = com.therealityteam.aeropages.model.A1.ref(editor.selRow, editor.selCol), onDismiss = { showGoalSeek = false }) { setRef, targetText, changeRef ->
                val set = com.therealityteam.aeropages.model.A1.parse(setRef.uppercase())
                val change = com.therealityteam.aeropages.model.A1.parse(changeRef.uppercase())
                val target = targetText.toDoubleOrNull()
                if (set == null || change == null || target == null) {
                    session.status = Strings.get(R.string.err_goal_seek_refs)
                } else {
                    showGoalSeek = false
                    val x = editor.goalSeek(set.first, set.second, target, change.first, change.second)
                    session.status = if (x == null) Strings.get(R.string.status_goal_seek_failed)
                    else Strings.get(R.string.status_goal_seek_found, changeRef.uppercase(), editor.display(change.first, change.second), setRef.uppercase(), editor.display(set.first, set.second))
                }
            }
        }
        if (showSplit) {
            TextToColumnsDialog(onDismiss = { showSplit = false }, onApply = { delim -> showSplit = false; editor.textToColumns(delim); session.status = Strings.get(R.string.status_split_columns) })
        }

        if (showComment) {
            CommentDialog(initial = editor.activeComment ?: "", onDismiss = { showComment = false },
                author = if (editor.activeComment != null) editor.sheet.cell(editor.selRow, editor.selCol)?.commentAuthor else editor.reviewerName, onApply = { t -> showComment = false; editor.setComment(t); session.status = if (t.isBlank()) Strings.get(R.string.status_comment_deleted) else Strings.get(R.string.status_comment_added) })
        }

        if (showLink) {
            SheetLinkDialog(editor, startInWorkbook = linkInWorkbook, onDismiss = { showLink = false; linkInWorkbook = false },
                onApplied = { url -> showLink = false; linkInWorkbook = false; editor.setLink(url); session.status = Strings.get(R.string.status_link_set, url) })
        }

        if (showImage) {
            ImageDialog(images = images, onDismiss = { showImage = false }, onPick = { path ->
                showImage = false
                scope.launch {
                    val loaded = runCatching { withContext(Dispatchers.IO) { loadImageForInsert(path) } }.getOrNull()
                    session.status = when {
                        loaded != null && editor.insertImage(loaded.data, loaded.mime) -> Strings.get(R.string.status_image_inserted)
                        editor.sheet.protected -> Strings.get(R.string.status_sheet_protected)
                        else -> Strings.get(R.string.err_cannot_read_image, File(path).name)
                    }
                    gridFocusRequest++
                }
            })
        }

        if (showValidation) {
            ValidationDialog(editor, onDismiss = { showValidation = false }, onStatus = { session.status = it })
        }

        // An entry that broke its cell's rule: the rule's alert (Retry / No go back into the cell to put it right).
        editor.validationAlert?.let { alert ->
            ValidationAlertDialog(editor, alert, onRetry = { editor.retryEntry(); formulaBarVisible = true; editRequest++ })
        }

        if (showFilterDialog) {
            KeywordFilterDialog(onDismiss = { showFilterDialog = false }, onApply = { q ->
                showFilterDialog = false; editor.filterContains(q); session.status = Strings.get(R.string.status_filtered_column, q)
            })
        }

        if (showNames) {
            NameManagerDialog(editor, onDismiss = { showNames = false }, onStatus = { session.status = it }, startNew = namesNew)
        }
        if (showCreateNames) {
            CreateNamesDialog(editor, onDismiss = { showCreateNames = false }, onStatus = { session.status = it })
        }

        if (showSheetManage) {
            SheetManageDialog(editor, onDismiss = { showSheetManage = false }, onStatus = { session.status = it })
        }

        filterMenuCol?.let { col ->
            FilterMenuDialog(editor, col, onDismiss = { filterMenuCol = null }, onStatus = { session.status = it },
                onCustom = { seed -> customFilter = col to seed }, onTop10 = { top10Col = col })
        }
        customFilter?.let { (col, seed) ->
            val header = editor.autoFilter?.let { editor.display(it.r1, col) }?.ifBlank { null } ?: com.therealityteam.aeropages.model.Workbook.colLabel(col)
            CustomAutoFilterDialog(editor, col, header, seed, onDismiss = { customFilter = null }, onStatus = { session.status = it })
        }
        top10Col?.let { col -> Top10Dialog(editor, col, onDismiss = { top10Col = null }, onStatus = { session.status = it }) }

        if (showGoTo) GoToDialog(editor, onSpecial = { showGoSpecial = true }) { showGoTo = false }
        if (showGoSpecial) GoToSpecialDialog(editor) { showGoSpecial = false }
        if (showSortDialog) {
            MultiSortDialog(editor, onDismiss = { showSortDialog = false }, onStatus = { session.status = it })
        }
        if (showRemoveDuplicates) {
            RemoveDuplicatesDialog(editor, onDismiss = { showRemoveDuplicates = false }, onResult = { duplicatesResult = it; session.status = it })
        }
        duplicatesResult?.let { message ->
            // Excel's message box after Remove Duplicates.
            AeroDialog(title = stringResource(R.string.remove_duplicates), onDismiss = { duplicatesResult = null }, width = 420.dp, buttons = {
                DialogButton(stringResource(R.string.btn_ok), { duplicatesResult = null }, primary = true)
            }) { Text(message, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(380.dp)) }
        }

        if (showCellFormat) {
            CellFormatDialog(editor, onDismiss = { showCellFormat = false; cellFormatTab = 0 }, onStatus = { session.status = it }, startTab = cellFormatTab)
        }

        if (showPasteSpecial) {
            PasteSpecialDialog(editor, onDismiss = { showPasteSpecial = false }, onStatus = { session.status = it })
        }

        if (showShortcuts) {
            ShortcutsDialog("Sheets", COMMON_SHORTCUTS + listOf(
                stringResource(R.string.shortcut_group_cell) to listOf(
                    ShortcutEntry(stringResource(R.string.key_arrow_keys), stringResource(R.string.shortcut_move_cell)),
                    ShortcutEntry(stringResource(R.string.key_shift_arrow), stringResource(R.string.shortcut_extend_selection)),
                    ShortcutEntry(stringResource(R.string.key_type_enter_f2), stringResource(R.string.shortcut_edit_cell)),
                    ShortcutEntry("Ctrl+C / Ctrl+V", stringResource(R.string.shortcut_copy_paste_range)),
                    ShortcutEntry("Delete", stringResource(R.string.shortcut_clear_content)),
                    ShortcutEntry("Ctrl+Q", stringResource(R.string.quick_analysis)),
                    ShortcutEntry("Ctrl+E", stringResource(R.string.flash_fill)),
                    ShortcutEntry("Ctrl+↑ ↓ ← →", stringResource(R.string.shortcut_data_edge)),
                    ShortcutEntry("Ctrl+Home / Ctrl+End", stringResource(R.string.shortcut_first_last_cell)),
                    ShortcutEntry("Ctrl+Space / Shift+Space", stringResource(R.string.shortcut_select_cols_rows)),
                    ShortcutEntry("Ctrl+; / Ctrl+Shift+;", stringResource(R.string.shortcut_date_time)),
                    ShortcutEntry("Alt+=", stringResource(R.string.autosum)),
                    ShortcutEntry("F4", stringResource(R.string.shortcut_absolute_refs)),
                    ShortcutEntry("Ctrl+1", stringResource(R.string.format_cells)),
                    ShortcutEntry("Ctrl+K", stringResource(R.string.insert_link)),
                    ShortcutEntry("Ctrl+Shift+$ % # @ !", stringResource(R.string.shortcut_number_formats)),
                    ShortcutEntry("Ctrl+9 / Ctrl+0", stringResource(R.string.shortcut_hide_rows_cols)),
                ),
            ), onDismiss = { showShortcuts = false })
        }

        SessionDialogs(session, saveTitle = stringResource(R.string.title_save_workbook), untitledLabel = stringResource(R.string.untitled_workbook))

        TooltipOverlay(tooltipHost)
    }
    }
    }
}

/** Open any spreadsheet format the module supports (runs on IO). */
internal fun readWorkbook(path: String): Workbook {
    val bytes = com.therealityteam.aeropages.platform.Trace.section("file.read") { File(path).readBytes() }
    // A password-protected file is refused, never read as a blank workbook that a save would write over it.
    com.therealityteam.aeropages.format.Encryption.check(path, bytes)
    val ext = path.substringAfterLast('.', "").lowercase()
    val wb = com.therealityteam.aeropages.platform.Trace.section("sheets.parse") { when {
        com.therealityteam.aeropages.format.doc.CompoundFileReader.isCfb(bytes) -> com.therealityteam.aeropages.format.xls.XlsReader.read(bytes)
        com.therealityteam.aeropages.format.odf.OdfPackage.typeOf(bytes)?.startsWith(com.therealityteam.aeropages.format.odf.OdfPackage.SPREADSHEET) == true ||
            ext == "ods" || ext == "ots" -> com.therealityteam.aeropages.format.odf.OdsReader.read(bytes)
        ext == "csv" -> com.therealityteam.aeropages.format.Csv.toWorkbook(bytes)
        else -> XlsxReader.read(bytes)
    } }
    wb.sourcePath = path
    return wb
}
