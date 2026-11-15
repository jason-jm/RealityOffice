package com.therealityteam.aeropages.ui

import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import androidx.compose.ui.text.withStyle
import com.pico.spatial.ui.design.VerticalDivider
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R
@Composable
internal fun SheetsRibbon(
    editor: SheetEditor, tab: XTab, onTab: (XTab) -> Unit, picker: XPicker, onPicker: (XPicker) -> Unit,
    onHome: () -> Unit, onSave: () -> Unit, dirty: Boolean, onStatus: (String) -> Unit,
    title: String = "", saveState: String = "",
    onCondFormat: () -> Unit = {}, onFind: () -> Unit = {}, onPivot: () -> Unit = {},
    onReplace: () -> Unit = {}, onGoTo: () -> Unit = {}, onGoSpecial: () -> Unit = {},
    watchOpen: Boolean = false, onWatchWindow: () -> Unit = {},
    onTextToColumns: () -> Unit = {}, onComment: () -> Unit = {}, onProofing: () -> Unit = {}, onInsertLink: () -> Unit = {}, onInsertChart: () -> Unit = {}, onInsertPicture: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onValidation: () -> Unit = {}, onFilterDialog: () -> Unit = {}, onNames: () -> Unit = {}, onDefineName: () -> Unit = {}, onCreateNames: () -> Unit = {}, onUseName: (String) -> Unit = {}, onExportCsv: () -> Unit = {}, onExportPdf: () -> Unit = {}, onShortcuts: () -> Unit = {},
    onGoalSeek: () -> Unit = {}, onDataTable: () -> Unit = {}, onScenarios: () -> Unit = {}, onSubtotal: () -> Unit = {},
    onAutoFilter: () -> Unit = {}, onSortDialog: () -> Unit = {}, onRemoveDuplicates: () -> Unit = {}, onCellFormat: (tab: Int) -> Unit = {}, onPasteSpecial: () -> Unit = {},
    onClipboardPane: () -> Unit = {},
    onFindInFile: (String) -> Unit = {},
    commentsOpen: Boolean = false, onToggleComments: () -> Unit = {},
    fileMenuOpen: Boolean = false, onToggleFileMenu: () -> Unit = {},
    formulaBarVisible: Boolean = true, onToggleFormulaBar: () -> Unit = {},
    showGridlines: Boolean = true, onToggleGridlines: () -> Unit = {},
    onInsertFunction: (FunctionCategory?) -> Unit = {}, onStartFormula: () -> Unit = {},
    onPageSetup: (SheetSetupTab) -> Unit = {},
    onCellSize: (rows: Boolean) -> Unit = {}, onManageSheets: () -> Unit = {},
    onSymbol: (String) -> Unit = {}, onMoreSymbols: () -> Unit = {},
) {
    val clipboard = rememberTextClipboard()
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val cf = editor.currentFormat
    // Table Design is there while the active cell is in a table; off it, Home shows.
    val table = editor.selectedTable
    // Picture Format and Shape Format while a picture or a shape is picked.
    val image = editor.selectedImage
    val shape = editor.selectedShape
    fun present(t: XTab) = when (t) { XTab.TABLE -> table != null; XTab.PICTURE -> image != null; XTab.SHAPE -> shape != null; else -> true }
    val shownTab = if (present(tab)) tab else XTab.HOME
    // Excel's Create Table, for Insert ▸ Table and a style picked outside a table (the style it will take; dev hook
    // `createtable`: from A1, three seconds in).
    var creatingTable by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (devDialogProp() == "createtable") { kotlinx.coroutines.delay(3_000); editor.select(0, 0); creatingTable = com.therealityteam.aeropages.model.SheetTable.DEFAULT_STYLE }
    }
    // Formulas ▸ Evaluate Formula (`evaluateformula`: on the cell the hook leaves selected).
    var evaluating by remember { mutableStateOf(false) }
    // Review ▸ Protect Sheet / Protect Workbook: the dialog asked for (`protectsheet`, `protectworkbook`: opened by the hook).
    var protectAsk by remember { mutableStateOf(ProtectAsk.NONE) }
    when (devDialogProp()) {
        "protectsheet" -> androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(3_000); protectAsk = ProtectAsk.SHEET }
        "protectworkbook" -> androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(3_000); protectAsk = ProtectAsk.WORKBOOK }
        // Protected with a password, then Unprotect Sheet asking for it (`unprotectsheet`).
        "unprotectsheet" -> androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(3_000); editor.protectSheet(com.therealityteam.aeropages.model.SheetProtection(), "pw"); protectAsk = ProtectAsk.UNPROTECT_SHEET
        }
    }
    ProtectDialogs(editor, protectAsk) { protectAsk = ProtectAsk.NONE }
    if (devDialogProp() == "evaluateformula") androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(3_500); evaluating = true }
    if (evaluating) EvaluateFormulaDialog(editor) { evaluating = false }
    creatingTable?.let { st -> CreateTableDialog(editor, st, { creatingTable = null }, onStatus) { onTab(XTab.TABLE) } }
    // Office's Search in the title bar (as Pages' and Slides'): commands found by name in the ribbon's place; a pick runs
    // it, opens its menu on its tab, or its dialog. Dev hook `commandsearch`.
    // AutoCorrect Options (Office's File ▸ Options ▸ Proofing), reached from Search here.
    var autoCorrectDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "autocorrect") }
    // Excel's Edit Custom Lists (File ▸ Options ▸ Advanced): reached from Search here, and from Sort's Order ▸ Custom List.
    var customListsDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "customlists") }
    if (customListsDialog) CustomListsDialog(editor, { customListsDialog = false })
    if (autoCorrectDialog) AutoCorrectOptionsDialog { autoCorrectDialog = false }
    val search = rememberCommandSearch("sheets")
    fun open(t: XTab, p: XPicker = XPicker.NONE) { onTab(t); if (p != XPicker.NONE && picker != p) onPicker(p) }
    @Composable fun commands(): List<AppCommand> {
        val home = stringResource(XTab.HOME.title); val insert = stringResource(XTab.INSERT.title); val layout = stringResource(XTab.LAYOUT.title)
        val formulas = stringResource(XTab.FORMULA.title); val data = stringResource(XTab.DATA.title); val review = stringResource(XTab.REVIEW.title)
        val viewTab = stringResource(XTab.VIEW.title)
        @Composable fun c(id: Int, icon: String, where: String, run: () -> Unit) = AppCommand(stringResource(id), icon, where, run)
        @Composable fun d(id: Int, icon: String, where: String, run: () -> Unit) = AppCommand(stringResource(id) + "…", icon, where, run)
        return listOf(
            c(R.string.paste, "📋", home) { pasteCells(editor, clipboard) }, c(R.string.cut, "✂", home) { cutCells(editor, clipboard) },
            c(R.string.copy, "⧉", home) { copyCells(editor, clipboard) }, c(R.string.paste_special, "📋", home, onPasteSpecial),
            c(R.string.paste_values, "123", home) { pasteAs(editor, SheetEditor.PasteWhat.VALUES, onStatus) },
            c(R.string.format_painter, "🖌", home, editor::formatPainter), c(R.string.clipboard, "📋", home, onClipboardPane),
            c(R.string.bold, "B", home, editor::toggleBold), c(R.string.italic, "I", home, editor::toggleItalic), c(R.string.underline, "U", home, editor::toggleUnderline),
            c(R.string.borders, "x_bdall", home) { open(XTab.HOME, XPicker.BORDERS) }, c(R.string.fill_color, "填充", home) { open(XTab.HOME, XPicker.FILL) },
            c(R.string.font_color, "A", home) { open(XTab.HOME, XPicker.TEXTCOLOR) },
            c(R.string.wrap_text, "x_wrap", home, editor::toggleWrap), c(R.string.merge_center, "x_merge", home, editor::toggleMergeCenter),
            c(R.string.merge_across, "ic_merge_across", home) { editor.mergeAcross() }, c(R.string.unmerge_cells, "ic_unmerge", home) { editor.unmergeCells() },
            c(R.string.text_orientation, "ic_text_orientation", home) { open(XTab.HOME, XPicker.ORIENT) },
            c(R.string.increase_indent, "ic_indent_increase", home) { editor.stepIndent(+1) }, c(R.string.decrease_indent, "ic_indent_decrease", home) { editor.stepIndent(-1) },
            d(R.string.format_cells, "x_cellsize", home) { onCellFormat(0) },
            c(R.string.increase_decimal, "+.0", home) { editor.adjustDecimals(+1) }, c(R.string.decrease_decimal, "-.0", home) { editor.adjustDecimals(-1) },
            c(R.string.title_cond_format, "x_condfmt", home, onCondFormat),
            c(R.string.format_as_table, "x_tablestyle", home) { open(XTab.HOME, XPicker.TABLESTYLES) }, c(R.string.cell_styles, "x_theme", home) { open(XTab.HOME, XPicker.CELLSTYLES) },
            c(R.string.autosum, "x_sigma", home) { open(XTab.HOME, XPicker.AUTOSUM) }, c(R.string.fill_menu, "x_filldown", home) { open(XTab.HOME, XPicker.FILLMENU) },
            c(R.string.clear, "⌫", home) { open(XTab.HOME, XPicker.CLEAR) }, c(R.string.find_and_select, "x_search", home) { open(XTab.HOME, XPicker.FINDSELECT) },
            c(R.string.fs_go_to, "ic_arrow_right", home, onGoTo), c(R.string.go_special_title, "x_target", home, onGoSpecial),
            c(R.string.trace_precedents, "ic_trace_precedents", formulas) { traceStatus(editor.tracePrecedents(), false, onStatus) },
            c(R.string.trace_dependents, "ic_trace_dependents", formulas) { traceStatus(editor.traceDependents(), true, onStatus) },
            c(R.string.remove_arrows, "ic_remove_arrows", formulas) { editor.removeArrows() },
            c(R.string.evaluate_formula, "ic_evaluate_formula", formulas) { evaluating = true },
            c(R.string.watch_window, "ic_watch_window", formulas, onWatchWindow),
            d(R.string.insert_table, "x_table", insert) { if (table == null) creatingTable = com.therealityteam.aeropages.model.SheetTable.DEFAULT_STYLE },
            c(R.string.picture, "🖼", insert, onInsertPicture), c(R.string.shapes_insert, "⬛", insert) { open(XTab.INSERT, XPicker.SHAPES) },
            c(R.string.text_box, "文本框", insert) { if (editor.insertShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX)) editor.requestShapeText() },
            c(R.string.chart_label, "x_chart", insert, onInsertChart), d(R.string.pivot_table, "x_pivot", insert, onPivot),
            c(R.string.insert_link, "🔗", insert, onInsertLink), c(R.string.new_comment, "💬", insert, onComment), d(R.string.symbol, "Ω", insert, onMoreSymbols),
            c(R.string.new_sheet, "ic_plus", insert, editor::addSheet), c(R.string.manage_sheets_more, "x_cellsize", home, onManageSheets),
            c(R.string.margins, "x_margins", layout) { open(XTab.LAYOUT, XPicker.PMARGINS) }, c(R.string.orientation, "x_orientation", layout) { open(XTab.LAYOUT, XPicker.PORIENT) },
            c(R.string.print_area, "x_printarea", layout) { open(XTab.LAYOUT, XPicker.PPRINTAREA) },
            c(R.string.insert_page_break, "x_breaks", layout) { if (!editor.insertPageBreak()) onStatus(Strings.get(R.string.status_no_page_break)) },
            c(R.string.reset_page_breaks, "x_breaks", layout) { editor.resetPageBreaks() },
            c(R.string.page_break_preview, "ic_page_break_preview", viewTab) { editor.setPageBreakPreview(true) },
            c(R.string.view_normal, "ic_grid", viewTab) { editor.setPageBreakPreview(false) }, c(R.string.print_titles, "x_headers", layout) { onPageSetup(SheetSetupTab.SHEET) },
            d(R.string.page_setup, "x_paper", layout) { onPageSetup(SheetSetupTab.PAGE) },
            d(R.string.insert_function, "x_fx", formulas) { onInsertFunction(null) }, c(R.string.show_formulas, "x_showformula", formulas, editor::toggleShowFormulas),
            c(R.string.title_name_manager, "x_names", formulas, onNames), d(R.string.define_name, "🏷", formulas, onDefineName),
            d(R.string.create_from_selection, "x_fromsel", formulas, onCreateNames),
            c(R.string.sort_ascending, "x_sortasc", data) { onStatus(Strings.get(if (editor.sortSelection(true)) R.string.status_sorted_asc else R.string.sort_no_data)) },
            c(R.string.sort_descending, "x_sortdesc", data) { onStatus(Strings.get(if (editor.sortSelection(false)) R.string.status_sorted_desc else R.string.sort_no_data)) },
            d(R.string.custom_sort, "x_sortmulti", data, onSortDialog), c(R.string.filter, "x_filter", data, onAutoFilter),
            d(R.string.text_to_columns, "x_split", data, onTextToColumns),
            c(R.string.flash_fill, "ic_flash_fill", data) { runFlashFill(editor, onStatus) },
            d(R.string.remove_duplicates, "x_dedupe", data, onRemoveDuplicates),
            d(R.string.cl_edit, "x_sortmulti", data) { customListsDialog = true },
            d(R.string.title_data_validation, "x_validate", data, onValidation),
            c(R.string.goal_seek_more, "x_target", data, onGoalSeek), c(R.string.scenario_manager_more, "x_target", data, onScenarios),
            c(R.string.data_table_more, "x_target", data, onDataTable), d(R.string.subtotal, "Σ", data, onSubtotal),
            c(R.string.spelling_check, "拼写", review, onProofing), c(R.string.new_edit_comment, "💬", review, onComment),
            AppCommand(stringResource(R.string.ac_options) + "…", "ic_lightning", review) { autoCorrectDialog = true },
            c(R.string.show_comments_pane, "显示批注", review, onToggleComments),
            c(if (editor.isProtected) R.string.unprotect_sheet else R.string.protect_sheet, "x_lock", review) { protectAsk = sheetProtectAsk(editor) },
            c(if (editor.structureLocked) R.string.unprotect_workbook else R.string.protect_workbook, "ic_protect_workbook", review) { protectAsk = workbookProtectAsk(editor) },
            c(R.string.zoom, "x_zoom", viewTab) { open(XTab.VIEW, XPicker.ZOOM) }, c(R.string.zoom_to_selection, "x_zoomsel", viewTab, editor::requestZoomToSelection),
            c(R.string.freeze_panes, "x_freeze", viewTab) { open(XTab.VIEW, XPicker.FREEZE) },
            c(R.string.formula_bar, "x_showformula", viewTab, onToggleFormulaBar), c(R.string.gridlines, "grid", viewTab, onToggleGridlines),
            c(R.string.export_csv, "export_pdf", home, onExportCsv), c(R.string.export_pdf, "export_pdf", home, onExportPdf),
            c(R.string.keyboard_shortcuts, "kbd_help", viewTab, onShortcuts),
        )
    }
    val anchor = remember { FlyoutAnchor() }
    androidx.compose.runtime.CompositionLocalProvider(LocalFlyoutAnchor provides anchor) {
    Column(Modifier.fillMaxWidth()) {
        val all = if (search.active) commands() else emptyList()
        AppTitleRow(title, saveState, onHome, search = { TitleSearchBox(search, all) }) {
            TitleIconButton("ic_undo", stringResource(R.string.undo), editor::undo, enabled = editor.canUndo)
            TitleIconButton("ic_redo", stringResource(R.string.redo), editor::redo, enabled = editor.canRedo)
            TitleIconButton("ic_save", stringResource(R.string.save), onSave)
        }
        RibbonTabRow(trailing = { TabRowCommand("ic_comment", stringResource(R.string.comments), commentsOpen, onToggleComments) }) {
            FileTab(fileMenuOpen, onToggleFileMenu)
            XTab.entries.forEach { t ->
                if (present(t)) RibbonTab(stringResource(t.title), selected = shownTab == t, contextual = t.contextual) { onTab(t) }
            }
        }
        RibbonCard(if (search.active) "search" else tab) {
            if (search.active) SearchCard(search, all, stringResource(shownTab.title), stringResource(R.string.search_find_book), onFindInFile)
            else when (shownTab) {
                XTab.PICTURE -> if (image != null) SheetPictureFormatTab(editor, image)
                XTab.SHAPE -> if (shape != null) SheetShapeFormatTab(editor, shape, shapesOpen = picker == XPicker.SHAPES,
                    onShapes = { onPicker(if (picker == XPicker.SHAPES) XPicker.NONE else XPicker.SHAPES) })
                XTab.TABLE -> if (table != null) TableDesignTab(editor, table, onStatus,
                    stylesOpen = picker == XPicker.TABLESTYLES, onStyles = { onPicker(if (picker == XPicker.TABLESTYLES) XPicker.NONE else XPicker.TABLESTYLES) },
                    totalsOpen = picker == XPicker.TOTALS, onTotals = { onPicker(if (picker == XPicker.TOTALS) XPicker.NONE else XPicker.TOTALS) })
                XTab.HOME -> {
                    val size = cf.fontSizePt ?: 12f
                    // Excel's Clipboard group: Paste (its options under the caret beside it), Cut, Copy, Format Painter, Paste Values.
                    XGroup(stringResource(R.string.clipboard), launcher = onClipboardPane) {
                        RibbonButton("📋", stringResource(R.string.paste), { pasteCells(editor, clipboard) })
                        RibbonMiniButton("", stringResource(R.string.paste_options), { onPicker(if (picker == XPicker.PASTEMENU) XPicker.NONE else XPicker.PASTEMENU) },
                            selected = picker == XPicker.PASTEMENU, dropdown = true)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonMiniButton("✂", stringResource(R.string.cut), { cutCells(editor, clipboard); onStatus(Strings.get(R.string.status_cut)) })
                                RibbonMiniButton("⧉", stringResource(R.string.copy), { copyCells(editor, clipboard); onStatus(Strings.get(R.string.status_copied)) })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                // Takes the selection's formats; the next cell tapped or block dragged over takes them (Esc puts it down).
                                RibbonMiniButton("🖌", stringResource(R.string.format_painter), {
                                    editor.formatPainter()
                                    if (editor.painterArmed) onStatus(Strings.get(R.string.status_sheet_painter_armed))
                                }, selected = editor.painterArmed)
                                RibbonMiniButton("123", stringResource(R.string.paste_values), { pasteAs(editor, com.therealityteam.aeropages.editor.SheetEditor.PasteWhat.VALUES, onStatus) })
                            }
                        }
                    }
                    // 字体
                    XGroup(stringResource(R.string.font), launcher = { onCellFormat(2) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonCombo(cf.fontFamily ?: "Calibri", { onPicker(XPicker.FONT) }, width = 108.dp, selected = picker == XPicker.FONT, tooltip = stringResource(R.string.font))
                                RibbonCombo(size.toInt().toString(), { onPicker(XPicker.SIZE) }, width = 46.dp, selected = picker == XPicker.SIZE, tooltip = stringResource(R.string.font_size))
                                RibbonMiniButton("A↑", stringResource(R.string.increase_font_size), { editor.setFontSize((size + 1f).coerceAtMost(72f)) })
                                RibbonMiniButton("A↓", stringResource(R.string.decrease_font_size), { editor.setFontSize((size - 1f).coerceAtLeast(8f)) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonMiniButton("B", stringResource(R.string.bold), editor::toggleBold, selected = cf.bold)
                                RibbonMiniButton("I", stringResource(R.string.italic), editor::toggleItalic, selected = cf.italic)
                                RibbonMiniButton("U", stringResource(R.string.underline), editor::toggleUnderline, selected = cf.underline)
                                RibbonMiniButton("x_bdall", stringResource(R.string.borders), { onPicker(XPicker.BORDERS) }, selected = picker == XPicker.BORDERS, dropdown = true)
                                RibbonMiniButton("填充", stringResource(R.string.fill_color), { onPicker(XPicker.FILL) }, selected = picker == XPicker.FILL, dropdown = true, accent = Color(0xFFFFD400))
                                RibbonMiniButton("A", stringResource(R.string.font_color), { onPicker(XPicker.TEXTCOLOR) }, selected = picker == XPicker.TEXTCOLOR, dropdown = true, accent = Color(0xFFCC0000))
                            }
                        }
                    }
                    // 对齐方式
                    XGroup(stringResource(R.string.group_alignment), launcher = { onCellFormat(1) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            // Excel's two rows: vertical alignment, Orientation ▾ and Wrap Text; horizontal alignment, the indents and
                            // Merge & Center (a split button: the merge itself, its other merges under the caret).
                            val vAlign = editor.sheet.cell(editor.selRow, editor.selCol)?.vAlign ?: com.therealityteam.aeropages.model.VAlign.MIDDLE
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonMiniButton("x_valtop", stringResource(R.string.top_align), { editor.setVAlign(com.therealityteam.aeropages.model.VAlign.TOP) }, selected = vAlign == com.therealityteam.aeropages.model.VAlign.TOP)
                                RibbonMiniButton("x_valmid", stringResource(R.string.middle_align), { editor.setVAlign(com.therealityteam.aeropages.model.VAlign.MIDDLE) }, selected = vAlign == com.therealityteam.aeropages.model.VAlign.MIDDLE)
                                RibbonMiniButton("x_valbottom", stringResource(R.string.bottom_align), { editor.setVAlign(com.therealityteam.aeropages.model.VAlign.BOTTOM) }, selected = vAlign == com.therealityteam.aeropages.model.VAlign.BOTTOM)
                                RibbonMiniButton("ic_text_orientation", stringResource(R.string.text_orientation), { onPicker(if (picker == XPicker.ORIENT) XPicker.NONE else XPicker.ORIENT) },
                                    selected = picker == XPicker.ORIENT || editor.activeRotation != 0 || editor.activeStacked, dropdown = true)
                                RibbonMiniButton("x_wrap", stringResource(R.string.wrap_text), editor::toggleWrap, selected = editor.sheet.cell(editor.selRow, editor.selCol)?.wrap == true)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                // Pressed while the active cell has that alignment; pressing again returns it to General.
                                RibbonMiniButton("⇠", stringResource(R.string.align_left), { editor.setAlign(com.therealityteam.aeropages.model.TextAlign.LEFT) }, selected = editor.activeAlign == com.therealityteam.aeropages.model.TextAlign.LEFT)
                                RibbonMiniButton("⇔", stringResource(R.string.center), { editor.setAlign(com.therealityteam.aeropages.model.TextAlign.CENTER) }, selected = editor.activeAlign == com.therealityteam.aeropages.model.TextAlign.CENTER)
                                RibbonMiniButton("⇢", stringResource(R.string.align_right), { editor.setAlign(com.therealityteam.aeropages.model.TextAlign.RIGHT) }, selected = editor.activeAlign == com.therealityteam.aeropages.model.TextAlign.RIGHT)
                                RibbonMiniButton("ic_indent_decrease", stringResource(R.string.decrease_indent), { editor.stepIndent(-1) }, enabled = editor.activeIndent > 0)
                                RibbonMiniButton("ic_indent_increase", stringResource(R.string.increase_indent), { editor.stepIndent(+1) })
                                RibbonMiniButton("x_merge", stringResource(R.string.merge_center), editor::toggleMergeCenter, selected = editor.isMergedSelection)
                                RibbonMiniButton("", stringResource(R.string.merge_options), { onPicker(if (picker == XPicker.MERGE) XPicker.NONE else XPicker.MERGE) },
                                    selected = picker == XPicker.MERGE, dropdown = true)
                            }
                        }
                    }
                    // 数字
                    XGroup(stringResource(R.string.group_number), launcher = { onCellFormat(0) }, launcherLabel = stringResource(R.string.format_cells)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            RibbonCombo(
                                editor.activeFormatCode?.let { if (it == "@") stringResource(R.string.numfmt_text) else stringResource(R.string.numfmt_custom) } ?: numFmtName(editor.activeNumberFormat),
                                { onPicker(XPicker.NUMFMT) }, width = 92.dp, selected = picker == XPicker.NUMFMT, tooltip = stringResource(R.string.number_format),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                // The language's currency, where it writes it (1.234,50 € in German, ₩1,235 in Korean); ¥ in Chinese, $ in English.
                                val money = remember { com.therealityteam.aeropages.model.LocaleCurrency.of() }
                                RibbonMiniButton(money.symbol, stringResource(R.string.currency), { applyLocaleCurrency(editor) })
                                RibbonMiniButton("%", stringResource(R.string.numfmt_percentage), { editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.PERCENT) })
                                RibbonMiniButton("x_thousands", stringResource(R.string.numfmt_comma_style), { editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.THOUSANDS) })
                                RibbonMiniButton("+.0", stringResource(R.string.increase_decimal), { editor.adjustDecimals(+1) })
                                RibbonMiniButton("-.0", stringResource(R.string.decrease_decimal), { editor.adjustDecimals(-1) })
                            }
                        }
                    }
                    // Styles: Conditional Formatting, and Format as Table and Cell Styles stacked beside it (Excel's narrow layout).
                    XGroup(stringResource(R.string.styles)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("x_condfmt", stringResource(R.string.title_cond_format), onCondFormat, dropdown = true, compactAt = 1)
                            RibbonSmallButton("x_tablestyle", stringResource(R.string.format_as_table), { onPicker(if (picker == XPicker.TABLESTYLES) XPicker.NONE else XPicker.TABLESTYLES) },
                                selected = picker == XPicker.TABLESTYLES, dropdown = true, compactAt = 1)
                            RibbonSmallButton("x_theme", stringResource(R.string.cell_styles), { onPicker(if (picker == XPicker.CELLSTYLES) XPicker.NONE else XPicker.CELLSTYLES) },
                                selected = picker == XPicker.CELLSTYLES, dropdown = true, compactAt = 1)
                        }
                    }
                    // Cells as Excel's narrow ribbon has them: Insert, Delete and Format stacked.
                    XGroup(stringResource(R.string.group_cells)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("x_insrow", stringResource(R.string.tab_insert), { onPicker(if (picker == XPicker.INSERTCELLS) XPicker.NONE else XPicker.INSERTCELLS) },
                                selected = picker == XPicker.INSERTCELLS, dropdown = true, compactAt = 2)
                            RibbonSmallButton("🗑", stringResource(R.string.delete), { onPicker(if (picker == XPicker.DELETECELLS) XPicker.NONE else XPicker.DELETECELLS) },
                                selected = picker == XPicker.DELETECELLS, dropdown = true, compactAt = 2)
                            RibbonSmallButton("x_cellsize", stringResource(R.string.format_menu), { onPicker(if (picker == XPicker.FORMATCELLS) XPicker.NONE else XPicker.FORMATCELLS) },
                                selected = picker == XPicker.FORMATCELLS, dropdown = true, compactAt = 2)
                        }
                    }
                    // Editing as Excel's narrow ribbon: AutoSum, Fill and Clear stacked; Sort & Filter and Find & Select stacked.
                    XGroup(stringResource(R.string.group_editing)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("x_sigma", stringResource(R.string.autosum), { onPicker(if (picker == XPicker.AUTOSUM) XPicker.NONE else XPicker.AUTOSUM) },
                                selected = picker == XPicker.AUTOSUM, dropdown = true, compactAt = 3)
                            RibbonSmallButton("x_filldown", stringResource(R.string.fill_menu), { onPicker(if (picker == XPicker.FILLMENU) XPicker.NONE else XPicker.FILLMENU) },
                                selected = picker == XPicker.FILLMENU, dropdown = true, compactAt = 3)
                            RibbonSmallButton("⌫", stringResource(R.string.clear), { onPicker(if (picker == XPicker.CLEAR) XPicker.NONE else XPicker.CLEAR) },
                                selected = picker == XPicker.CLEAR, dropdown = true, compactAt = 3)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("x_sortasc", stringResource(R.string.sort_and_filter), { onPicker(if (picker == XPicker.SORTFILTER) XPicker.NONE else XPicker.SORTFILTER) },
                                selected = picker == XPicker.SORTFILTER, dropdown = true, compactAt = 3)
                            RibbonSmallButton("x_search", stringResource(R.string.find_and_select), { onPicker(if (picker == XPicker.FINDSELECT) XPicker.NONE else XPicker.FINDSELECT) },
                                selected = picker == XPicker.FINDSELECT, dropdown = true, compactAt = 3)
                        }
                    }
                }
                XTab.INSERT -> {
                    // Insert → Table: the data around the active cell becomes a table in Excel's default look.
                    XGroup(stringResource(R.string.group_tables)) {
                        RibbonButton("x_table", stringResource(R.string.insert_table), { creatingTable = com.therealityteam.aeropages.model.SheetTable.DEFAULT_STYLE }, enabled = table == null)
                    }
                    XGroup(stringResource(R.string.illustrations)) {
                        RibbonButton("🖼", stringResource(R.string.picture), onInsertPicture)
                        // Insert → Shapes (a gallery row) and Text Box (typed into straight away).
                        RibbonButton("⬛", stringResource(R.string.shapes_insert), { onPicker(if (picker == XPicker.SHAPES) XPicker.NONE else XPicker.SHAPES) },
                            selected = picker == XPicker.SHAPES, dropdown = true)
                        RibbonButton("文本框", stringResource(R.string.text_box), { if (editor.insertShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX)) editor.requestShapeText() })
                    }
                    XGroup(stringResource(R.string.group_charts)) {
                        RibbonButton("x_chart", stringResource(R.string.chart_label), onInsertChart)
                        RibbonButton("x_pivot", stringResource(R.string.pivot_table), onPivot)
                    }
                    XGroup(stringResource(R.string.group_sparklines)) {
                        RibbonButton("x_sparkline", stringResource(R.string.sparkline_line), { editor.insertSparklines(com.therealityteam.aeropages.model.SparklineType.LINE); onStatus(Strings.get(R.string.status_sparklines_inserted)) })
                        RibbonButton("x_sparkcolumn", stringResource(R.string.sparkline_column), { editor.insertSparklines(com.therealityteam.aeropages.model.SparklineType.COLUMN); onStatus(Strings.get(R.string.status_sparklines_inserted)) })
                        RibbonButton("x_sparkwinloss", stringResource(R.string.sparkline_winloss), { editor.insertSparklines(com.therealityteam.aeropages.model.SparklineType.WIN_LOSS); onStatus(Strings.get(R.string.status_sparklines_inserted)) })
                        RibbonButton("x_clearspark", stringResource(R.string.clear_sparklines), editor::clearSparklines, enabled = editor.sparklineAt(editor.selRow, editor.selCol) != null)
                    }
                    XGroup(stringResource(R.string.group_links)) {
                        RibbonButton("🔗", stringResource(R.string.insert_link), onInsertLink, selected = editor.activeLink != null)
                        editor.activeLink?.let { link ->
                            RibbonButton("🌐", stringResource(R.string.open_link), { onOpenLink(link) })
                            // A HYPERLINK formula is the cell's own text: only a cell's link comes off.
                            if (editor.sheet.cell(editor.selRow, editor.selCol)?.link != null)
                                RibbonButton("⛓", stringResource(R.string.remove_link), { editor.setLink(null); onStatus(Strings.get(R.string.status_link_removed)) })
                        }
                        RibbonButton("💬", stringResource(R.string.new_comment), onComment)
                        RibbonButton("Ω", stringResource(R.string.symbol), { onPicker(if (picker == XPicker.SYMBOL) XPicker.NONE else XPicker.SYMBOL) }, selected = picker == XPicker.SYMBOL, dropdown = true)
                    }
                    XGroup(stringResource(R.string.group_sheets)) {
                        RibbonButton("ic_plus", stringResource(R.string.new_sheet), editor::addSheet)
                    }
                }
                XTab.FORMULA -> {
                    // Excel's Function Library: Insert Function, AutoSum's menu, then a button per category.
                    XGroup(stringResource(R.string.group_function_library)) {
                        RibbonButton("x_fx", stringResource(R.string.insert_function), { onInsertFunction(null) })
                        RibbonButton("x_sigma", stringResource(R.string.autosum), { onPicker(XPicker.AUTOSUM) }, selected = picker == XPicker.AUTOSUM, dropdown = true)
                        listOf(
                            FunctionCategory.FINANCIAL to "x_fn_financial", FunctionCategory.LOGICAL to "x_fn_logical", FunctionCategory.TEXT to "x_fn_text",
                            FunctionCategory.DATE to "📅", FunctionCategory.LOOKUP to "x_search", FunctionCategory.MATH to "x_fn_math",
                            FunctionCategory.STATISTICAL to "📊", FunctionCategory.INFORMATION to "x_fn_info",
                        ).forEach { (c, glyph) -> RibbonButton(glyph, stringResource(c.label), { onInsertFunction(c) }) }
                    }
                    // Formula Auditing as Excel lays it out: Trace Precedents / Trace Dependents / Remove Arrows ▾, then
                    // Show Formulas and Error Checking (a dialog stepping through the sheet's formula errors).
                    XGroup(stringResource(R.string.group_formula_auditing)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("ic_trace_precedents", stringResource(R.string.trace_precedents), { traceStatus(editor.tracePrecedents(), false, onStatus) }, compactAt = 1)
                            RibbonSmallButton("ic_trace_dependents", stringResource(R.string.trace_dependents), { traceStatus(editor.traceDependents(), true, onStatus) }, compactAt = 1)
                            RibbonSmallButton("ic_remove_arrows", stringResource(R.string.remove_arrows), { onPicker(if (picker == XPicker.ARROWS) XPicker.NONE else XPicker.ARROWS) },
                                selected = picker == XPicker.ARROWS, dropdown = true, enabled = editor.hasArrows(), compactAt = 1)
                        }
                        var checking by remember { mutableStateOf(devDialogProp() == "errorcheck") }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("x_showformula", stringResource(R.string.show_formulas), editor::toggleShowFormulas, selected = editor.showFormulas, compactAt = 1)
                            RibbonSmallButton("x_errorcheck", stringResource(R.string.error_checking), { checking = true }, compactAt = 1)
                            RibbonSmallButton("ic_evaluate_formula", stringResource(R.string.evaluate_formula), { evaluating = true }, compactAt = 1)
                        }
                        // Watch Window: a pane keeping chosen cells' values and formulas in view.
                        RibbonButton("ic_watch_window", stringResource(R.string.watch_window), onWatchWindow, selected = watchOpen)
                        if (checking) ErrorCheckingDialog(editor, onEditInBar = { checking = false; onStartFormula() }) { checking = false }
                    }
                    // Defined Names: Name Manager, Define Name, Use in Formula ▾, Create from Selection.
                    XGroup(stringResource(R.string.group_defined_names)) {
                        RibbonButton("x_names", stringResource(R.string.title_name_manager), onNames)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("🏷", stringResource(R.string.define_name), onDefineName, tooltip = stringResource(R.string.define_name_hint), compactAt = 1)
                            RibbonSmallButton("x_fx", stringResource(R.string.use_in_formula), { onPicker(if (picker == XPicker.USENAME) XPicker.NONE else XPicker.USENAME) },
                                selected = picker == XPicker.USENAME, compactAt = 1)
                            RibbonSmallButton("x_fromsel", stringResource(R.string.create_from_selection), onCreateNames, compactAt = 1)
                        }
                    }
                }
                XTab.DATA -> {
                    // Sort & Filter as Excel has it: A→Z / Z→A beside Sort…, Clear / Reapply / Keyword beside Filter.
                    XGroup(stringResource(R.string.group_sort_filter)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RibbonSmallButton("x_sortasc", stringResource(R.string.sort_ascending), { onStatus(Strings.get(if (editor.sortSelection(true)) R.string.status_sorted_asc else R.string.sort_no_data)) })
                            RibbonSmallButton("x_sortdesc", stringResource(R.string.sort_descending), { onStatus(Strings.get(if (editor.sortSelection(false)) R.string.status_sorted_desc else R.string.sort_no_data)) })
                        }
                        RibbonButton("x_sortmulti", stringResource(R.string.sort_dialog), onSortDialog, tooltip = stringResource(R.string.custom_sort))
                        RibbonButton("x_filter", stringResource(R.string.filter), onAutoFilter, selected = editor.autoFilter != null)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("x_filterclr", stringResource(R.string.clear_short), { editor.clearFilter() }, enabled = editor.hasFilter, tooltip = stringResource(R.string.clear_filter))
                            RibbonSmallButton("↻", stringResource(R.string.reapply_filter), { editor.reapplyFilter() }, enabled = editor.autoFilter != null || editor.hasFilter)
                            RibbonSmallButton("x_search", stringResource(R.string.title_keyword_filter), onFilterDialog)
                        }
                    }
                    // Data Tools: Text to Columns, Flash Fill, Remove Duplicates, Data Validation ▾ (the rules, circling what breaks them).
                    XGroup(stringResource(R.string.group_data_tools)) {
                        RibbonButton("x_split", stringResource(R.string.text_to_columns), onTextToColumns, tooltip = stringResource(R.string.text_to_columns_hint))
                        RibbonButton("ic_flash_fill", stringResource(R.string.flash_fill), { runFlashFill(editor, onStatus) }, tooltip = stringResource(R.string.flash_fill_hint))
                        RibbonButton("x_dedupe", stringResource(R.string.remove_duplicates), onRemoveDuplicates)
                        RibbonButton("x_validate", stringResource(R.string.title_data_validation), { onPicker(if (picker == XPicker.DATAVALID) XPicker.NONE else XPicker.DATAVALID) },
                            selected = picker == XPicker.DATAVALID || editor.hasCircles, dropdown = true)
                    }
                    // Forecast: What-If Analysis ▾ (scenarios, goal seek, data table).
                    XGroup(stringResource(R.string.group_forecast)) {
                        RibbonButton("x_target", stringResource(R.string.what_if_analysis), { onPicker(if (picker == XPicker.WHATIF) XPicker.NONE else XPicker.WHATIF) },
                            selected = picker == XPicker.WHATIF, dropdown = true)
                    }
                    // Outline: Group ▾ / Ungroup ▾ (rows or columns; clear the outline), Subtotal. The −/+ and levels live in the grid's gutter.
                    XGroup(stringResource(R.string.group_outline)) {
                        RibbonButton("⊕", stringResource(R.string.outline_group), { onPicker(if (picker == XPicker.GROUPMENU) XPicker.NONE else XPicker.GROUPMENU) },
                            selected = picker == XPicker.GROUPMENU, dropdown = true)
                        RibbonButton("⊖", stringResource(R.string.outline_ungroup), { onPicker(if (picker == XPicker.UNGROUPMENU) XPicker.NONE else XPicker.UNGROUPMENU) },
                            selected = picker == XPicker.UNGROUPMENU, dropdown = true, enabled = editor.rowGroups.isNotEmpty() || editor.colGroups.isNotEmpty())
                        RibbonButton("Σ", stringResource(R.string.subtotal), onSubtotal)
                    }
                }
                XTab.LAYOUT -> {
                    // Excel's Page Layout: how the sheet prints (and its PDF), round-tripping through xlsx and ODS.
                    val sheet = editor.sheet
                    val pr = sheet.print
                    // Excel's launchers: Page Setup and Scale to Fit open Page Setup on Page, Sheet Options on Sheet.
                    XGroup(stringResource(R.string.page_setup), launcher = { onPageSetup(SheetSetupTab.PAGE) }) {
                        RibbonButton("x_margins", stringResource(R.string.margins), { onPicker(XPicker.PMARGINS) }, selected = picker == XPicker.PMARGINS, dropdown = true)
                        RibbonButton("x_orientation", stringResource(R.string.orientation), { onPicker(XPicker.PORIENT) }, selected = picker == XPicker.PORIENT, dropdown = true)
                        RibbonButton("x_paper", stringResource(R.string.size), { onPicker(XPicker.PPAPER) }, selected = picker == XPicker.PPAPER, dropdown = true)
                        RibbonButton("x_printarea", stringResource(R.string.print_area), { onPicker(XPicker.PPRINTAREA) }, selected = picker == XPicker.PPRINTAREA || sheet.printArea != null, dropdown = true)
                        RibbonButton("x_breaks", stringResource(R.string.breaks), { onPicker(XPicker.PBREAKS) }, selected = picker == XPicker.PBREAKS, dropdown = true)
                        RibbonButton("x_headers", stringResource(R.string.print_titles), { onPageSetup(SheetSetupTab.SHEET) }, selected = pr.titleRows != null || pr.titleCols != null)
                    }
                    XGroup(stringResource(R.string.group_scale_to_fit), launcher = { onPageSetup(SheetSetupTab.PAGE) }) {
                        RibbonButton("x_fit", if (pr.fits) stringResource(R.string.fit_pages, pr.fitWidth?.takeIf { it > 0 }?.toString() ?: "–", pr.fitHeight?.takeIf { it > 0 }?.toString() ?: "–") else "${pr.scale}%",
                            { onPicker(XPicker.PSCALE) }, selected = picker == XPicker.PSCALE, dropdown = true)
                    }
                    // Sheet Options: gridlines and headings, shown on screen and printed.
                    XGroup(stringResource(R.string.group_sheet_options), launcher = { onPageSetup(SheetSetupTab.SHEET) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.gridlines), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
                            CheckOption(stringResource(R.string.view_word), showGridlines, { onToggleGridlines() }, small = true)
                            CheckOption(stringResource(R.string.print_word), pr.gridlines, { editor.setPrint { it.copy(gridlines = !it.gridlines) } }, small = true)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.headings), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
                            CheckOption(stringResource(R.string.view_word), sheet.showHeadings, { editor.toggleHeadings() }, small = true)
                            CheckOption(stringResource(R.string.print_word), pr.headings, { editor.setPrint { it.copy(headings = !it.headings) } }, small = true)
                        }
                    }
                }
                XTab.REVIEW -> {
                    XGroup(stringResource(R.string.group_proofing)) {
                        RibbonButton("拼写", stringResource(R.string.spelling_check), onProofing)
                        // Excel's Workbook Statistics: the current sheet's counts, then the whole workbook's.
                        var stats by remember { mutableStateOf(devDialogProp() == "bookstats") }
                        RibbonButton("📊", stringResource(R.string.workbook_statistics), { stats = true })
                        if (stats) {
                            val here = com.therealityteam.aeropages.model.SheetStats.of(editor.sheet)
                            val all = com.therealityteam.aeropages.model.SheetStats.of(editor.workbook)
                            @Composable fun rows(s: com.therealityteam.aeropages.model.SheetStats) = listOf(
                                stringResource(R.string.stat_cells) to s.cells.toString(),
                                stringResource(R.string.stat_tables) to s.tables.toString(),
                                stringResource(R.string.stat_formulas) to s.formulas.toString(),
                                stringResource(R.string.stat_charts) to s.charts.toString(),
                                stringResource(R.string.stat_pictures) to s.images.toString(),
                                stringResource(R.string.stat_comments) to s.comments.toString(),
                            )
                            StatisticsDialog(stringResource(R.string.workbook_statistics), listOf(
                                stringResource(R.string.stat_current_sheet, editor.sheet.name) to
                                    (listOf(stringResource(R.string.stat_end_of_sheet) to (here.end ?: stringResource(R.string.stat_none))) + rows(here)),
                                stringResource(R.string.stat_workbook) to (listOf(stringResource(R.string.stat_sheets) to editor.workbook.sheets.size.toString()) + rows(all)),
                            )) { stats = false }
                        }
                    }
                    // Excel's Accessibility group: Check Accessibility lists what a screen reader would stumble on.
                    var checking by remember { mutableStateOf(devDialogProp() == "accessibility") }
                    XGroup(stringResource(R.string.group_accessibility)) {
                        RibbonButton("ic_accessibility", stringResource(R.string.check_accessibility), { checking = true })
                    }
                    if (checking) SheetsAccessibility(editor) { checking = false }
                    if (showChineseConversion) XGroup { ChineseConversionButtons({ t, v -> editor.convertChinese(t, v) }, onStatus) }
                    // Comments: new or edit, delete the active cell's, and the one before or after it.
                    XGroup(stringResource(R.string.comments)) {
                        RibbonButton("💬", stringResource(R.string.new_edit_comment), onComment)
                        RibbonButton("显示批注", stringResource(R.string.show_comments_pane), onToggleComments, selected = commentsOpen)
                        RibbonButton("x_delcomment", stringResource(R.string.delete_comment), { editor.clearComment(); onStatus(Strings.get(R.string.status_comment_deleted)) },
                            enabled = editor.activeHasComment)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val noMore = stringResource(R.string.status_no_more_comments)
                            RibbonSmallButton("▲", stringResource(R.string.previous_comment), { if (!editor.goToComment(back = true)) onStatus(noMore) })
                            RibbonSmallButton("▼", stringResource(R.string.next_comment), { if (!editor.goToComment()) onStatus(noMore) })
                        }
                        // Excel's Show/Hide Note (the active cell's, kept beside it and saved) and Show All Notes (a view setting).
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("ic_comment", stringResource(R.string.show_hide_note), { editor.toggleNoteShown() },
                                selected = editor.activeNoteShown, enabled = editor.activeHasComment, compactAt = 2)
                            RibbonSmallButton("ic_comments", stringResource(R.string.show_all_notes), editor::toggleShowAllNotes,
                                selected = editor.showAllNotes, compactAt = 2)
                        }
                    }
                    XGroup(stringResource(R.string.group_protect)) {
                        RibbonButton("x_lock", stringResource(if (editor.isProtected) R.string.unprotect_sheet else R.string.protect_sheet), { protectAsk = sheetProtectAsk(editor) }, selected = editor.isProtected)
                        RibbonButton("ic_protect_workbook", stringResource(if (editor.structureLocked) R.string.unprotect_workbook else R.string.protect_workbook),
                            { protectAsk = workbookProtectAsk(editor) }, selected = editor.structureLocked)
                        RibbonButton("x_unlock", stringResource(if (editor.activeUnlocked) R.string.lock_cell else R.string.unlock_cell), editor::toggleUnlockedSelection, selected = editor.activeUnlocked)
                    }
                }
                XTab.VIEW -> {
                    // Workbook Views: Normal and Page Break Preview (where the pages break, numbered, the rest greyed).
                    XGroup(stringResource(R.string.group_workbook_views)) {
                        RibbonButton("ic_grid", stringResource(R.string.view_normal), { editor.setPageBreakPreview(false) }, selected = !editor.sheet.pageBreakPreview)
                        RibbonButton("ic_page_break_preview", stringResource(R.string.page_break_preview), { editor.setPageBreakPreview(true) }, selected = editor.sheet.pageBreakPreview)
                    }
                    // Show: Excel's ticks — the formula bar, gridlines, headings — and Show Formulas.
                    XGroup(stringResource(R.string.group_show)) {
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            CheckOption(stringResource(R.string.formula_bar), formulaBarVisible, { onToggleFormulaBar() }, small = true)
                            CheckOption(stringResource(R.string.gridlines), showGridlines, { onToggleGridlines() }, small = true)
                            CheckOption(stringResource(R.string.headings), editor.sheet.showHeadings, { editor.toggleHeadings() }, small = true)
                        }
                        RibbonButton("x_showformula", stringResource(R.string.show_formulas), editor::toggleShowFormulas, selected = editor.showFormulas)
                    }
                    // Zoom: the menu of zooms, back to 100 %, Zoom to Selection.
                    XGroup(stringResource(R.string.group_zoom)) {
                        RibbonButton("x_zoom", stringResource(R.string.zoom), { onPicker(if (picker == XPicker.ZOOM) XPicker.NONE else XPicker.ZOOM) },
                            selected = picker == XPicker.ZOOM, dropdown = true)
                        RibbonButton("100", stringResource(R.string.zoom_100), { editor.setZoom(100) }, selected = editor.sheet.viewZoom == 100)
                        RibbonButton("x_zoomsel", stringResource(R.string.zoom_to_selection), editor::requestZoomToSelection)
                    }
                    // Window: Excel's Freeze Panes menu.
                    XGroup(stringResource(R.string.group_window)) {
                        RibbonButton("x_freeze", stringResource(R.string.freeze_panes), { onPicker(if (picker == XPicker.FREEZE) XPicker.NONE else XPicker.FREEZE) },
                            selected = picker == XPicker.FREEZE || editor.isFrozen, dropdown = true)
                    }
                    XGroup(stringResource(R.string.group_help)) {
                        RibbonButton("kbd_help", stringResource(R.string.keyboard_shortcuts), onShortcuts)
                    }
                }
            }
        }
        if (picker != XPicker.NONE) {
            RibbonFlyout({ onPicker(XPicker.NONE) }) {
                when (picker) {
                    XPicker.TEXTCOLOR, XPicker.FILL -> {
                        val fill = picker == XPicker.FILL
                        ColorPalette(if (fill) cf.highlight else cf.color, { c -> if (fill) editor.setHighlight(c) else editor.setColor(c); onPicker(XPicker.NONE) },
                            noneLabel = stringResource(if (fill) R.string.no_fill else R.string.automatic))
                    }
                    XPicker.FONT -> FlyoutMenu(width = 260.dp, maxHeight = 440.dp) {
                        MenuHeading(stringResource(R.string.all_fonts))
                        TextStyling.FONT_FAMILIES.forEach { n -> MenuCommand(n, { editor.setFontFamily(n); onPicker(XPicker.NONE) }, checked = (cf.fontFamily ?: "Calibri") == n) }
                    }
                    XPicker.SIZE -> FlyoutMenu(width = 140.dp, maxHeight = 440.dp) {
                        TextStyling.FONT_SIZES.forEach { s -> MenuCommand(s.toInt().toString(), { editor.setFontSize(s); onPicker(XPicker.NONE) }, checked = (cf.fontSizePt ?: 12f) == s) }
                    }
                    XPicker.NUMFMT -> FlyoutMenu(width = 260.dp) {
                        com.therealityteam.aeropages.model.NumberFormat.entries.forEach { nf ->
                            MenuCommand(numFmtName(nf), { editor.setNumberFormat(nf); onPicker(XPicker.NONE) }, checked = editor.activeFormatCode == null && editor.activeNumberFormat == nf)
                        }
                        // Text keeps what is typed (leading zeros, long IDs); More… opens Format Cells for a custom code.
                        MenuCommand(stringResource(R.string.numfmt_text), { editor.setFormatCode("@"); onPicker(XPicker.NONE) }, checked = editor.activeFormatCode == "@")
                        MenuCommand(stringResource(R.string.numfmt_more), { onPicker(XPicker.NONE); onCellFormat(0) },
                            checked = editor.activeFormatCode.let { it != null && it != "@" })
                    }
                    XPicker.BORDERS -> BordersMenu(editor) { onPicker(XPicker.NONE) }
                    XPicker.CELLSTYLES -> CellStylesMenu(editor, onStatus) { onPicker(XPicker.NONE) }
                    XPicker.ARROWS -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.remove_arrows), { editor.removeArrows(); onPicker(XPicker.NONE) }, icon = "ic_remove_arrows")
                        MenuCommand(stringResource(R.string.remove_precedent_arrows), { editor.removeArrows(dependents = false); onPicker(XPicker.NONE) },
                            enabled = editor.hasArrows(dependent = false))
                        MenuCommand(stringResource(R.string.remove_dependent_arrows), { editor.removeArrows(precedents = false); onPicker(XPicker.NONE) },
                            enabled = editor.hasArrows(dependent = true))
                    }
                    XPicker.FINDSELECT -> FindSelectMenu(editor, onFind, onReplace, onGoTo, onGoSpecial, onStatus) { onPicker(XPicker.NONE) }
                    XPicker.CLEAR -> FlyoutMenu {
                        listOf(
                            com.therealityteam.aeropages.editor.SheetEditor.ClearWhat.ALL to R.string.clear_all,
                            com.therealityteam.aeropages.editor.SheetEditor.ClearWhat.FORMATS to R.string.clear_formats,
                            com.therealityteam.aeropages.editor.SheetEditor.ClearWhat.CONTENTS to R.string.clear_contents,
                            com.therealityteam.aeropages.editor.SheetEditor.ClearWhat.COMMENTS to R.string.clear_comments,
                            com.therealityteam.aeropages.editor.SheetEditor.ClearWhat.HYPERLINKS to R.string.clear_hyperlinks,
                        ).forEach { (what, name) -> MenuCommand(stringResource(name), { editor.clear(what); onPicker(XPicker.NONE) }) }
                    }
                    XPicker.TABLESTYLES -> TableStylesGallery(editor, onStatus, onCreate = { creatingTable = it }) { onPicker(XPicker.NONE) }
                    XPicker.TOTALS -> TotalFunctionsMenu(editor) { onPicker(XPicker.NONE) }
                    XPicker.PMARGINS -> SheetMarginsPanel(editor, { onPicker(XPicker.NONE) }) { onPageSetup(SheetSetupTab.MARGINS) }
                    XPicker.PORIENT -> SheetOrientationPanel(editor) { onPicker(XPicker.NONE) }
                    XPicker.PPAPER -> SheetPaperPanel(editor) { onPicker(XPicker.NONE) }
                    XPicker.PSCALE -> SheetScalePanel(editor) { onPicker(XPicker.NONE) }
                    XPicker.PPRINTAREA -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.print_area), {
                            editor.setPrintAreaFromSelection()
                            onStatus(Strings.get(if (editor.sheet.printArea != null) R.string.status_print_area_set else R.string.status_print_area_cleared))
                            onPicker(XPicker.NONE)
                        })
                        if (editor.sheet.printArea != null) MenuCommand(stringResource(R.string.clear_print_area), {
                            editor.clearPrintArea(); onStatus(Strings.get(R.string.status_print_area_cleared)); onPicker(XPicker.NONE)
                        })
                    }
                    XPicker.PBREAKS -> FlyoutMenu {
                        val pr = editor.sheet.print
                        MenuCommand(stringResource(R.string.insert_page_break), {
                            onPicker(XPicker.NONE)
                            if (!editor.insertPageBreak()) onStatus(Strings.get(R.string.status_no_page_break))
                        }, icon = "x_breaks")
                        MenuCommand(stringResource(R.string.remove_page_break), {
                            onPicker(XPicker.NONE); editor.removePageBreak()
                        }, enabled = editor.canRemovePageBreak)
                        MenuCommand(stringResource(R.string.reset_page_breaks), {
                            onPicker(XPicker.NONE); editor.resetPageBreaks()
                        }, enabled = pr.rowBreaks.isNotEmpty() || pr.colBreaks.isNotEmpty())
                    }
                    XPicker.SHAPES -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            com.therealityteam.aeropages.model.ShapeType.RECT to ("⬛" to R.string.shape_rectangle),
                            com.therealityteam.aeropages.model.ShapeType.ROUNDED to ("▢" to R.string.shape_rounded_short),
                            com.therealityteam.aeropages.model.ShapeType.ELLIPSE to ("◯" to R.string.shape_oval),
                            com.therealityteam.aeropages.model.ShapeType.TRIANGLE to ("△" to R.string.shape_triangle_short),
                            com.therealityteam.aeropages.model.ShapeType.STAR to ("☆" to R.string.shape_star),
                            com.therealityteam.aeropages.model.ShapeType.ARROW to ("➜" to R.string.shape_arrow),
                            com.therealityteam.aeropages.model.ShapeType.LINE to ("╲" to R.string.shape_line),
                        ).forEach { (type, look) ->
                            RibbonButton(look.first, stringResource(look.second), { editor.insertShape(type); onPicker(XPicker.NONE) })
                        }
                        // Excel's Line Arrow and Line Arrow: Double.
                        val head = com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE)
                        RibbonButton("→", stringResource(R.string.shape_line_arrow), { editor.insertShape(com.therealityteam.aeropages.model.ShapeType.LINE, end = head); onPicker(XPicker.NONE) })
                        RibbonButton("↔", stringResource(R.string.shape_double_arrow), { editor.insertShape(com.therealityteam.aeropages.model.ShapeType.LINE, begin = head, end = head); onPicker(XPicker.NONE) })
                    }
                    // AutoSum's menu: the function over the numbers above (or left of) the active cell.
                    XPicker.AUTOSUM -> FlyoutMenu {
                        listOf("SUM" to R.string.autosum_sum, "AVERAGE" to R.string.average, "COUNT" to R.string.count_value, "MAX" to R.string.max_value, "MIN" to R.string.autosum_min)
                            .forEach { (fn, label) ->
                                MenuCommand(stringResource(label), {
                                    onPicker(XPicker.NONE)
                                    val range = editor.autoFunction(fn)
                                    if (range != null) onStatus(Strings.get(R.string.status_function_inserted, "=$fn($range)"))
                                    else { onStartFormula(); onStatus(Strings.get(R.string.status_function_started, fn)) }
                                })
                            }
                        MenuCommand(stringResource(R.string.insert_function) + "…", { onPicker(XPicker.NONE); onInsertFunction(null) })
                    }
                    // Home → Cells: Insert, Delete and Format menus, as Excel's.
                    XPicker.INSERTCELLS -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.insert_row_above), { onPicker(XPicker.NONE); editor.insertRowAt() })
                        MenuCommand(stringResource(R.string.insert_col_left), { onPicker(XPicker.NONE); editor.insertColAt() })
                        MenuCommand(stringResource(R.string.new_sheet), { onPicker(XPicker.NONE); editor.addSheet() })
                    }
                    XPicker.DELETECELLS -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.delete_row), { onPicker(XPicker.NONE); editor.deleteRowAt() })
                        MenuCommand(stringResource(R.string.delete_column), { onPicker(XPicker.NONE); editor.deleteColAt() })
                        if (editor.workbook.sheets.count { !it.hidden } > 1)
                            MenuCommand(stringResource(R.string.delete_sheet), { onPicker(XPicker.NONE); editor.deleteSheet(editor.sheetIndex) })
                    }
                    // Excel's Format menu, by its headings: cell size, visibility, the sheets, protection.
                    XPicker.FORMATCELLS -> FlyoutMenu(width = 300.dp, maxHeight = 600.dp) {
                        MenuSection(stringResource(R.string.cell_size)) {
                            MenuCommand(stringResource(R.string.row_height_more), { onPicker(XPicker.NONE); onCellSize(true) })
                            MenuCommand(stringResource(R.string.autofit_row_height), { onPicker(XPicker.NONE); editor.autoFitRowHeight() })
                            MenuCommand(stringResource(R.string.col_width_more), { onPicker(XPicker.NONE); onCellSize(false) })
                            MenuCommand(stringResource(R.string.autofit_col_width), { onPicker(XPicker.NONE); editor.autoFitColWidth(); onStatus(Strings.get(R.string.status_col_width_autofit)) })
                        }
                        MenuSection(stringResource(R.string.visibility)) {
                            MenuCommand(stringResource(R.string.hide_rows), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.hideRows()) R.string.status_rows_hidden else R.string.err_hide_rows)) })
                            if (editor.sheet.manualHiddenRows.isNotEmpty())
                                MenuCommand(stringResource(R.string.unhide_rows), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.unhideRows()) R.string.status_rows_unhidden else R.string.err_unhide_rows)) })
                            MenuCommand(stringResource(R.string.hide_columns), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.hideColumns()) R.string.status_cols_hidden else R.string.err_hide_cols)) })
                            if (editor.sheet.hiddenCols.isNotEmpty())
                                MenuCommand(stringResource(R.string.unhide_columns), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.unhideColumns()) R.string.status_cols_unhidden else R.string.err_unhide_cols)) })
                        }
                        MenuSection(stringResource(R.string.organize_sheets)) {
                            MenuCommand(stringResource(R.string.manage_sheets_more), { onPicker(XPicker.NONE); onManageSheets() })
                        }
                        MenuSection(stringResource(R.string.protection)) {
                            MenuCommand(stringResource(if (editor.isProtected) R.string.unprotect_sheet else R.string.protect_sheet), { onPicker(XPicker.NONE); protectAsk = sheetProtectAsk(editor) }, checked = editor.isProtected)
                            MenuCommand(stringResource(R.string.lock_cell), { editor.toggleUnlockedSelection() }, checked = !editor.activeUnlocked)
                            MenuCommand(stringResource(R.string.format_cells) + "…", { onPicker(XPicker.NONE); onCellFormat(0) })
                        }
                    }
                    // Paste's options, as Excel's gallery names them.
                    XPicker.PASTEMENU -> FlyoutMenu(width = 300.dp, maxHeight = 600.dp) {
                        val what = com.therealityteam.aeropages.editor.SheetEditor.PasteWhat.entries
                        MenuCommand(stringResource(R.string.paste), { onPicker(XPicker.NONE); pasteCells(editor, clipboard) })
                        listOf(what[1] to R.string.paste_what_formulas, what[2] to R.string.paste_what_values, what[3] to R.string.paste_what_values_formats, what[4] to R.string.paste_what_formats)
                            .forEach { (w, name) -> MenuCommand(stringResource(name), { onPicker(XPicker.NONE); pasteAs(editor, w, onStatus) }) }
                        MenuCommand(stringResource(R.string.paste_transpose), {
                            onPicker(XPicker.NONE)
                            if (editor.pasteSpecial(com.therealityteam.aeropages.editor.SheetEditor.PasteWhat.ALL, transpose = true)) onStatus(Strings.get(R.string.status_pasted_as, Strings.get(R.string.paste_transpose)))
                            else if (!editor.canPaste) onStatus(Strings.get(R.string.status_copy_cells_first))
                        })
                        MenuCommand(stringResource(R.string.paste_link), {
                            onPicker(XPicker.NONE)
                            if (editor.pasteLink()) onStatus(Strings.get(R.string.status_pasted_as, Strings.get(R.string.paste_link)))
                            else if (!editor.canPaste) onStatus(Strings.get(R.string.status_copy_cells_first))
                        })
                        MenuCommand(stringResource(R.string.paste_special), { onPicker(XPicker.NONE); if (editor.canPaste) onPasteSpecial() else onStatus(Strings.get(R.string.status_copy_cells_first)) })
                    }
                    // Fill: the block's first row (column) down (right), its last up (left), or a series.
                    // Excel's Orientation menu: each turn ticked while the active cell reads that way (picking it again turns it back).
                    XPicker.ORIENT -> FlyoutMenu(width = 300.dp) {
                        val rot = editor.activeRotation; val stacked = editor.activeStacked
                        listOf(Triple(45, R.string.angle_ccw, "ic_text_angle_ccw"), Triple(-45, R.string.angle_cw, "ic_text_angle_cw")).forEach { (deg, name, icon) ->
                            MenuCommand(stringResource(name), { onPicker(XPicker.NONE); editor.setOrientation(deg) }, icon = icon, checked = !stacked && rot == deg)
                        }
                        MenuCommand(stringResource(R.string.vertical_text), { onPicker(XPicker.NONE); editor.setOrientation(0, stacked = true) }, icon = "ic_text_vertical", checked = stacked)
                        listOf(Triple(90, R.string.rotate_text_up, "ic_text_rotate_up"), Triple(-90, R.string.rotate_text_down, "ic_text_rotate_down")).forEach { (deg, name, icon) ->
                            MenuCommand(stringResource(name), { onPicker(XPicker.NONE); editor.setOrientation(deg) }, icon = icon, checked = !stacked && rot == deg)
                        }
                        MenuDivider()
                        MenuCommand(stringResource(R.string.format_cell_alignment) + "…", { onPicker(XPicker.NONE); onCellFormat(1) }, icon = "ic_dialog_launcher")
                    }
                    // Merge & Center's menu, as Excel's.
                    XPicker.MERGE -> FlyoutMenu(width = 280.dp) {
                        MenuCommand(stringResource(R.string.merge_center), { onPicker(XPicker.NONE); editor.mergeCells(center = true) }, icon = "ic_merge")
                        MenuCommand(stringResource(R.string.merge_across), { onPicker(XPicker.NONE); editor.mergeAcross() }, icon = "ic_merge_across")
                        MenuCommand(stringResource(R.string.merge_cells_only), { onPicker(XPicker.NONE); editor.mergeCells(center = false) }, icon = "ic_merge_cells")
                        MenuCommand(stringResource(R.string.unmerge_cells), { onPicker(XPicker.NONE); editor.unmergeCells() }, icon = "ic_unmerge", enabled = editor.selectionHasMerge)
                    }
                    XPicker.FILLMENU -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.fill_dir_down), { onPicker(XPicker.NONE); editor.fillDown(); onStatus(Strings.get(R.string.status_filled)) })
                        MenuCommand(stringResource(R.string.fill_dir_right), { onPicker(XPicker.NONE); editor.fillRight(); onStatus(Strings.get(R.string.status_filled)) })
                        MenuCommand(stringResource(R.string.fill_dir_up), { onPicker(XPicker.NONE); editor.fillUp(); onStatus(Strings.get(R.string.status_filled)) })
                        MenuCommand(stringResource(R.string.fill_dir_left), { onPicker(XPicker.NONE); editor.fillLeft(); onStatus(Strings.get(R.string.status_filled)) })
                        MenuCommand(stringResource(R.string.fill_dir_series), {
                            onPicker(XPicker.NONE)
                            onStatus(if (editor.fillSeries()) Strings.get(R.string.status_filled_series) else Strings.get(R.string.err_select_two_cells))
                        })
                        MenuCommand(stringResource(R.string.flash_fill), { onPicker(XPicker.NONE); runFlashFill(editor, onStatus) }, icon = "ic_flash_fill", trailing = "Ctrl+E")
                    }
                    XPicker.SORTFILTER -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.sort_ascending), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.sortSelection(true)) R.string.status_sorted_asc else R.string.sort_no_data)) })
                        MenuCommand(stringResource(R.string.sort_descending), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.sortSelection(false)) R.string.status_sorted_desc else R.string.sort_no_data)) })
                        MenuCommand(stringResource(R.string.custom_sort), { onPicker(XPicker.NONE); onSortDialog() })
                        MenuCommand(stringResource(R.string.filter), { onPicker(XPicker.NONE); onAutoFilter() }, checked = editor.autoFilter != null)
                        if (editor.hasFilter) MenuCommand(stringResource(R.string.clear_filter), { onPicker(XPicker.NONE); editor.clearFilter() })
                    }
                    XPicker.ZOOM -> FlyoutMenu {
                        listOf(25, 50, 75, 100, 125, 150, 200, 400).forEach { z ->
                            MenuCommand("$z%", { editor.setZoom(z); onPicker(XPicker.NONE) }, checked = editor.sheet.viewZoom == z)
                        }
                        MenuCommand(stringResource(R.string.zoom_to_selection), { onPicker(XPicker.NONE); editor.requestZoomToSelection() })
                    }
                    // Freeze Panes as Excel's menu: the first item unfreezes once something is frozen.
                    XPicker.FREEZE -> FlyoutMenu {
                        if (editor.isFrozen) MenuCommand(stringResource(R.string.unfreeze_panes), { onPicker(XPicker.NONE); editor.unfreeze(); onStatus(Strings.get(R.string.status_unfrozen)) })
                        else MenuCommand(stringResource(R.string.freeze_panes), { onPicker(XPicker.NONE); editor.freezeAtSelection(); onStatus(Strings.get(R.string.status_frozen)) })
                        MenuCommand(stringResource(R.string.freeze_top_row), { onPicker(XPicker.NONE); editor.freezeTopRow(); onStatus(Strings.get(R.string.status_top_row_frozen)) })
                        MenuCommand(stringResource(R.string.freeze_first_column), { onPicker(XPicker.NONE); editor.freezeFirstColumn(); onStatus(Strings.get(R.string.status_first_col_frozen)) })
                    }
                    XPicker.SYMBOL -> SymbolMenu({ onPicker(XPicker.NONE); onSymbol(it) }) { onPicker(XPicker.NONE); onMoreSymbols() }
                    XPicker.USENAME -> UseNamePanel(editor, onPick = { onPicker(XPicker.NONE); onUseName(it) }, onManage = { onPicker(XPicker.NONE); onNames() })
                    XPicker.DATAVALID -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.data_validation_more), { onPicker(XPicker.NONE); onValidation() })
                        MenuCommand(stringResource(R.string.circle_invalid_data), {
                            onPicker(XPicker.NONE)
                            val n = editor.circleInvalidData()
                            onStatus(if (n > 0) Strings.get(R.string.status_circled_invalid, n) else Strings.get(R.string.status_no_invalid))
                        })
                        if (editor.hasCircles) MenuCommand(stringResource(R.string.clear_validation_circles), { onPicker(XPicker.NONE); editor.clearValidationCircles() })
                    }
                    XPicker.WHATIF -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.scenario_manager_more), { onPicker(XPicker.NONE); onScenarios() })
                        MenuCommand(stringResource(R.string.goal_seek_more), { onPicker(XPicker.NONE); onGoalSeek() })
                        MenuCommand(stringResource(R.string.data_table_more), { onPicker(XPicker.NONE); onDataTable() })
                    }
                    XPicker.GROUPMENU -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.outline_rows), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.groupRows()) R.string.status_rows_grouped else R.string.err_group_rows)) })
                        MenuCommand(stringResource(R.string.outline_columns), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.groupColumns()) R.string.status_cols_grouped else R.string.err_group_cols)) })
                    }
                    XPicker.UNGROUPMENU -> FlyoutMenu {
                        MenuCommand(stringResource(R.string.outline_rows), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.ungroupRows()) R.string.status_rows_ungrouped else R.string.err_ungroup_rows)) })
                        MenuCommand(stringResource(R.string.outline_columns), { onPicker(XPicker.NONE); onStatus(Strings.get(if (editor.ungroupColumns()) R.string.status_rows_ungrouped else R.string.err_ungroup_rows)) })
                        MenuCommand(stringResource(R.string.clear_outline), { onPicker(XPicker.NONE); editor.clearOutline() })
                    }
                    XPicker.NONE -> Unit
                }
            }
        }
    }
    }
}

/** Pastes [what] of the copied cells (Paste Values, Formulas…), saying so — or that there is nothing copied. */
private fun pasteAs(editor: SheetEditor, what: SheetEditor.PasteWhat, onStatus: (String) -> Unit) {
    val name = when (what) {
        SheetEditor.PasteWhat.FORMULAS -> R.string.paste_what_formulas
        SheetEditor.PasteWhat.VALUES -> R.string.paste_what_values
        SheetEditor.PasteWhat.VALUES_AND_NUMBER_FORMATS -> R.string.paste_what_values_formats
        SheetEditor.PasteWhat.FORMATS -> R.string.paste_what_formats
        SheetEditor.PasteWhat.COMMENTS -> R.string.paste_what_comments
        SheetEditor.PasteWhat.VALIDATION -> R.string.paste_what_validation
        SheetEditor.PasteWhat.ALL -> R.string.paste_what_all
    }
    if (editor.pasteSpecial(what)) onStatus(Strings.get(R.string.status_pasted_as, Strings.get(name)))
    else if (!editor.canPaste) onStatus(Strings.get(R.string.status_copy_cells_first))
}

/** A heading over a group of a menu's choices (Excel's Format menu: Cell Size, Visibility…). */
@Composable
private fun MenuSection(title: String, content: @Composable () -> Unit) {
    MenuHeading(title)
    content()
}

/** An Excel ribbon group with its [label] under it (the kit's [RibbonGroup]). */
@Composable
internal fun XGroup(label: String = "", launcher: (() -> Unit)? = null, launcherLabel: String = label, content: @Composable () -> Unit) =
    RibbonGroup(label, launcher = launcher, launcherLabel = launcherLabel, content = content)

/** Size's Height and Width in centimetres or inches, half a centimetre or a quarter inch a press. */
@Composable
private fun ObjectSizeRows(wPt: Float, hPt: Float, onWidth: (Float) -> Unit, onHeight: (Float) -> Unit) {
    val step = if (Lengths.inches) 18f else 72f / 2.54f / 2f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonGlyph("ic_shape_height", PicoTheme.colorScheme.labelSecondary, 18.dp)
            SpinBox(Lengths.label(hPt), { d -> onHeight((hPt + d * step).coerceAtLeast(step)) }, description = stringResource(R.string.ps_height), valueWidth = 64.dp, compact = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonGlyph("ic_shape_width", PicoTheme.colorScheme.labelSecondary, 18.dp)
            SpinBox(Lengths.label(wPt), { d -> onWidth((wPt + d * step).coerceAtLeast(step)) }, description = stringResource(R.string.ps_width), valueWidth = 64.dp, compact = true)
        }
    }
}

/**
 * Excel's Picture Format for the picked picture: Adjust (Reset Picture ▾), Picture Styles (the drawn shadows), Arrange
 * (the layers among the sheet's pictures) and Size (Crop; Height and Width, its proportions kept). Duplicate and Delete
 * stay on the picture's own bar.
 */
@Composable
private fun SheetPictureFormatTab(editor: SheetEditor, img: com.therealityteam.aeropages.model.SheetImage) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var showCrop by remember { mutableStateOf(false) }
    XGroup(stringResource(R.string.group_adjust)) {
        var resetting by remember { mutableStateOf(false) }
        Box {
            RibbonButton("ic_reset_picture", stringResource(R.string.reset_picture), { resetting = !resetting }, selected = resetting, dropdown = true)
            if (resetting) AnchoredFlyout(onDismiss = { resetting = false }) {
                FlyoutMenu(width = 260.dp) {
                    MenuCommand(stringResource(R.string.reset_picture), { editor.resetImage(img, size = false); resetting = false }, icon = "ic_reset_picture",
                        enabled = img.cropped || img.shadow != null)
                    MenuCommand(stringResource(R.string.reset_picture_and_size), { editor.resetImage(img, size = true); resetting = false }, icon = "x_resetsize")
                }
            }
        }
    }
    XGroup(stringResource(R.string.group_picture_styles)) {
        ShadowSample(stringResource(R.string.no_shadow), null, img.shadow == null) { editor.setImageShadow(img, null) }
        listOf(R.string.shadow_bottom_right, R.string.shadow_bottom, R.string.shadow_right, R.string.shadow_soft).zip(com.therealityteam.aeropages.model.ShapeShadow.PRESETS).forEach { (label, s) ->
            ShadowSample(stringResource(label), s, s.sameAs(img.shadow)) { editor.setImageShadow(img, s) }
        }
    }
    // Excel's Accessibility group: the picture's Alt Text.
    var altText by remember { mutableStateOf(devDialogProp() == "alttext") }
    XGroup(stringResource(R.string.group_accessibility)) {
        RibbonButton("ic_accessibility", stringResource(R.string.alt_text), { altText = true })
    }
    if (altText) AltTextDialog(img.altText, img.decorative, onDismiss = { altText = false }) { t, d -> editor.setImageAltText(img, t, d); altText = false }
    XGroup(stringResource(R.string.arrange)) {
        LayerCommands({ editor.arrangeSelected(forward = true, toEdge = false) }, { editor.arrangeSelected(forward = false, toEdge = false) },
            { editor.arrangeSelected(forward = true, toEdge = true) }, { editor.arrangeSelected(forward = false, toEdge = true) })
    }
    XGroup(stringResource(R.string.group_size)) {
        RibbonButton("x_crop", stringResource(R.string.crop), { showCrop = true })
        ObjectSizeRows(img.widthPt, img.heightPt,
            onWidth = { w -> editor.resizeImage(img, w, img.heightPt * w / img.widthPt.coerceAtLeast(1f)) },
            onHeight = { h -> editor.resizeImage(img, img.widthPt * h / img.heightPt.coerceAtLeast(1f), h) })
    }
    if (showCrop) {
        val (fullW, fullH) = img.uncroppedSize(img.widthPt, img.heightPt)
        CropDialog(img.data, fullW / fullH.coerceAtLeast(1f), img, onCrop = { l, t, r, b -> editor.cropImage(img, l, t, r, b) }, onDismiss = { showCrop = false })
    }
}

/**
 * Excel's Shape Format for the picked shape: Insert Shapes (the gallery; Edit Text), Shape Styles (the presets; Shape
 * Fill ▾, Shape Outline ▾), Arrange (the layers among the sheet's shapes) and Size (Height and Width).
 */
@Composable
private fun SheetShapeFormatTab(editor: SheetEditor, sh: com.therealityteam.aeropages.model.SheetShape, shapesOpen: Boolean, onShapes: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val line = sh.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE
    XGroup(stringResource(R.string.group_insert_shapes)) {
        RibbonButton("ic_shape_star", stringResource(R.string.shapes_insert), onShapes, selected = shapesOpen, dropdown = true)
        if (!line) RibbonButton("ic_textbox", stringResource(R.string.edit_text), editor::requestShapeText)
    }
    XGroup(stringResource(R.string.group_shape_styles)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SHAPE_STYLE_PRESETS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { st -> ShapeStyleSwatch(st) { editor.applyShapeStyle(sh, st.fill, st.border, st.text) } }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var filling by remember { mutableStateOf(false) }
            if (!line) Box {
                RibbonSmallButton("ic_fill", stringResource(R.string.shape_fill), { filling = !filling }, selected = filling, dropdown = true)
                if (filling) AnchoredFlyout(onDismiss = { filling = false }) {
                    ColorPalette(sh.shape.fill, { c -> editor.setShapeFill(sh, c); filling = false }, noneLabel = stringResource(R.string.shape_no_fill))
                }
            }
            var outlining by remember { mutableStateOf(false) }
            Box {
                RibbonSmallButton("ic_outline", stringResource(R.string.shape_outline), { outlining = !outlining }, selected = outlining, dropdown = true)
                if (outlining) AnchoredFlyout(onDismiss = { outlining = false }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorPalette(sh.shape.border, { c -> editor.setShapeOutline(sh, c); outlining = false }, noneLabel = stringResource(R.string.shape_no_outline))
                        MenuDivider()
                        LineOptions(sh.shape,
                            onWeight = { editor.setShapeOutlineWeight(sh, it); outlining = false },
                            onDash = { editor.setShapeDash(sh, it); outlining = false },
                            onArrows = if (sh.shape.hasEnds) { b, e -> editor.setShapeArrows(sh, b, e); outlining = false } else null)
                    }
                }
            }
        }
    }
    XGroup(stringResource(R.string.arrange)) {
        LayerCommands({ editor.arrangeSelected(forward = true, toEdge = false) }, { editor.arrangeSelected(forward = false, toEdge = false) },
            { editor.arrangeSelected(forward = true, toEdge = true) }, { editor.arrangeSelected(forward = false, toEdge = true) })
    }
    XGroup(stringResource(R.string.group_size)) {
        ObjectSizeRows(sh.widthPt, sh.heightPt, onWidth = { editor.setShapeSize(sh, it, null) }, onHeight = { editor.setShapeSize(sh, null, it) })
    }
}

/**
 * Excel's Name Box: shows the selection (`B3`, `B3:D8`, `B:D`, `3:5`); type a reference, a
 * sheet-qualified one or a defined name and press Enter to go there — or a new name to give it to
 * the selection. Escape gives up. Either way [onDone] hands the keyboard back to the grid.
 */
@Composable
private fun NameBox(editor: SheetEditor, onStatus: (String) -> Unit, onDone: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val shown = editor.selectionRef()
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(shown)) }
    // Following the selection while nobody types in it; typing starts with everything selected.
    LaunchedEffect(shown, focused) { if (!focused) value = androidx.compose.ui.text.input.TextFieldValue(shown) }
    LaunchedEffect(focused) { if (focused) value = value.copy(selection = androidx.compose.ui.text.TextRange(0, value.text.length)) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val name = stringResource(R.string.name_box)
    fun leave() { focusManager.clearFocus(); onDone() }
    fun commit() {
        val entry = value.text.trim()
        when (editor.goTo(entry)) {
            SheetEditor.GoTo.SELECTED -> {}
            SheetEditor.GoTo.DEFINED -> onStatus(Strings.get(R.string.status_name_defined_for, entry, editor.selectionRef()))
            SheetEditor.GoTo.INVALID -> if (entry.isNotEmpty()) onStatus(Strings.get(R.string.status_goto_invalid, entry))
        }
        leave()
    }
    var namesOpen by remember { mutableStateOf(false) }
    val names = editor.workbook.names.keys.sorted()
    // Its own semantics group, so the formula field stays the only text field beside the "fx" label.
    // Excel's Name Box: the field, and a caret that lists the defined names to go to.
    Row(Modifier.semantics { isTraversalGroup = true }.width(140.dp).height(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(Aero.commandRadius))
        .background(Aero.field), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = AeroType.command.copy(color = PicoTheme.colorScheme.labelPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { commit() }),
            modifier = Modifier.weight(1f).padding(start = 10.dp)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                    else when (e.key) {
                        androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter -> { commit(); true }
                        androidx.compose.ui.input.key.Key.Escape -> { leave(); true }
                        else -> false
                    }
                }
                .semantics { contentDescription = name },
        )
        Box {
            val namesLabel = stringResource(R.string.group_defined_names)
            Box(Modifier.size(28.dp, 32.dp).semantics { contentDescription = namesLabel }.press { namesOpen = true }, contentAlignment = Alignment.Center) {
                DropCaret(PicoTheme.colorScheme.labelSecondary, width = 8.dp)
            }
            if (namesOpen) AnchoredFlyout(onDismiss = { namesOpen = false }) {
                FlyoutMenu(width = 240.dp, maxHeight = 360.dp) {
                    if (names.isEmpty()) MenuCommand(stringResource(R.string.no_defined_names), {}, enabled = false)
                    names.forEach { n -> MenuCommand(n, { namesOpen = false; value = androidx.compose.ui.text.input.TextFieldValue(n); commit() }, icon = "ic_tag") }
                }
            }
        }
    }
}

/** A formula-bar command (✕, ✓): an icon button that leaves the keyboard in the formula field while it is clicked. */
@Composable
private fun FormulaBarButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) = Tooltip(label) { a11y ->
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Aero.commandRadius)
    Box(
        Modifier.then(a11y).size(32.dp).clip(shape)
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) GazeFill(shape)
        RibbonGlyph(icon, PicoTheme.colorScheme.labelPrimary.copy(alpha = if (enabled) 1f else 0.3f), 18.dp)
    }
}

/**
 * Formula/edit bar: the Name Box and the active cell's content — a percentage, date or time as it
 * would be typed (15%, 2026-09-29, 9:30), as in Excel. [editRequest] bumps focus the field
 * (type-to-edit); [onStatus] reports what the Name Box did, [onNameBoxDone] returns the keyboard to
 * the grid, and [onCommit] runs on Enter (the entry is read and the selection moves down).
 */
@Composable
internal fun FormulaBar(
    editor: SheetEditor, editRequest: Int = 0, onEditComment: (() -> Unit)? = null,
    onStatus: (String) -> Unit = {}, onNameBoxDone: () -> Unit = {}, onCommit: () -> Unit = {},
    onInsertFunction: () -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val cell = editor.sheet.cell(editor.selRow, editor.selCol)
    // A TextFieldValue so the selection is known: Bold / colour on a partial selection formats just
    // that stretch of the cell (rich runs), as in Excel's in-cell editing.
    var value by remember(editor.selRow, editor.selCol, editor.sheetIndex) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(editor.editText(editor.selRow, editor.selCol))) }
    LaunchedEffect(editor.selRow, editor.selCol, editor.sheetIndex) { editor.setTextSelection(0, 0) }
    // Resync after undo/redo/replace-all: the model changed without typing here. While typing,
    // the model already equals the field so nothing moves.
    LaunchedEffect(editor.revision) {
        val t = editor.editText(editor.selRow, editor.selCol)
        if (t != value.text) value = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length))
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(editRequest) { if (editRequest > 0) runCatching { focus.requestFocus() } }
    val onTyped: (androidx.compose.ui.text.input.TextFieldValue) -> Unit = { typed ->
        // Excel's AutoComplete: characters typed at the end that begin one entry of the column above or
        // below get the rest of it, selected — typing on replaces it, Backspace takes it away, Enter keeps
        // it (in the entry's own case).
        val before = if (value.selection.collapsed) value.text else value.text.substring(0, value.selection.min) + value.text.substring(value.selection.max)
        // Excel's AutoCorrect first: one character typed at the caret (not into a formula, not while an input method
        // composes) — "(c)" ©, "teh " the, a day's name or a sentence after ". " capitalised.
        val oneKey = typed.composition == null && typed.selection.collapsed && typed.text.length == before.length + 1 &&
            typed.selection.end > 0 && before.regionMatches(0, typed.text, 0, typed.selection.end - 1) &&
            before.regionMatches(typed.selection.end - 1, typed.text, typed.selection.end, before.length - typed.selection.end + 1)
        val corrected = if (oneKey && !typed.text.startsWith("=")) editor.autoCorrected(typed.text, typed.selection.end) else null
        @Suppress("NAME_SHADOWING") val typed = corrected?.let { (t, caret) -> androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(caret)) } ?: typed
        val grew = typed.selection.collapsed && typed.selection.end == typed.text.length && typed.text.length > before.length
        val entry = if (grew) editor.autoComplete(editor.selRow, editor.selCol, typed.text) else null
        val nv = if (entry != null) androidx.compose.ui.text.input.TextFieldValue(typed.text + entry.substring(typed.text.trimStart().length),
            androidx.compose.ui.text.TextRange(typed.text.length, typed.text.length + entry.length - typed.text.trimStart().length)) else typed
        val changed = nv.text != value.text
        value = nv
        editor.setTextSelection(nv.selection.min, nv.selection.max)
        if (changed) { editor.typeCell(editor.selRow, editor.selCol, nv.text); editor.noteCompletion(entry) }
    }
    // Dev hook `autocomplete`: "Be" typed into A6 under a column of cities, as if on the keyboard.
    val latestTyped by androidx.compose.runtime.rememberUpdatedState(onTyped)
    if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "autocomplete") LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000); editor.select(5, 0); kotlinx.coroutines.delay(500)
        runCatching { focus.requestFocus() }
        kotlinx.coroutines.delay(1_500)   // the field focused (and the keyboard up) first, as when typing
        latestTyped(androidx.compose.ui.text.input.TextFieldValue("Be", androidx.compose.ui.text.TextRange(2)))
    }
    // Dev hook `cellautocorrect`: "teh (c) monday" typed into B2 key by key — Excel's AutoCorrect makes it "the © Monday".
    if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "cellautocorrect") LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000); editor.select(1, 1); kotlinx.coroutines.delay(500)
        runCatching { focus.requestFocus() }
        kotlinx.coroutines.delay(1_500)
        var shown = ""
        for (ch in "teh (c) monday ") {
            val t = shown + ch
            latestTyped(androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)))
            kotlinx.coroutines.delay(250)
            shown = editor.editText(1, 1)
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = Aero.bandGap).chromeCard()) {
        // Excel's formula bar: the Name Box, ✕ (cancel the entry) and ✓ (enter it) while a cell is typed in,
        // fx (Insert Function), then the cell's content.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NameBox(editor, onStatus, onNameBoxDone)
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(Aero.barEdge))
            FormulaBarButton("ic_close", stringResource(R.string.cancel), editor.entryOpen) { editor.abandonEntry(); onNameBoxDone() }
            FormulaBarButton("ic_check", stringResource(R.string.enter_entry), editor.entryOpen) { editor.commitEntry(); onNameBoxDone() }
            // fx stays a plain text node beside the field (the UI tests find the field by it); a click inserts a function.
            val insertLabel = stringResource(R.string.insert_function)
            Text("fx", style = AeroType.heading.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                color = PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(Aero.commandRadius))
                    .press(onClickLabel = insertLabel, onClick = onInsertFunction).padding(horizontal = 8.dp, vertical = 2.dp))
            // The field stays a direct sibling of the "fx" label (the UI tests find it that way).
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onTyped,
                singleLine = true,
                // Enter commits the entry and moves down, as in Excel.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onCommit() }),
                textStyle = AeroType.body.copy(color = PicoTheme.colorScheme.labelPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current),
                modifier = Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(Aero.commandRadius))
                    .background(Aero.field).padding(horizontal = 12.dp, vertical = 7.dp)
                    .focusRequester(focus).semantics { contentDescription = "fx" }
                    // Excel's keys while typing: F4 turns the reference at the caret ($A$1, A$1, $A1, A1); Esc gives the entry
                    // up (the cell as it was) and hands the keyboard back to the grid.
                    .onPreviewKeyEvent { e ->
                        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            androidx.compose.ui.input.key.Key.F4 -> {
                                val turned = com.therealityteam.aeropages.editor.RefCycle.cycle(value.text, value.selection.end) ?: return@onPreviewKeyEvent false
                                value = androidx.compose.ui.text.input.TextFieldValue(turned.first, androidx.compose.ui.text.TextRange(turned.second))
                                editor.typeCell(editor.selRow, editor.selCol, turned.first)
                                true
                            }
                            androidx.compose.ui.input.key.Key.Escape -> if (editor.entryOpen) { editor.abandonEntry(); onNameBoxDone(); true } else false
                            else -> false
                        }
                    }
                    // Leaving the bar ends the entry (as a click elsewhere does in Excel).
                    .onFocusChanged { if (!it.isFocused) editor.commitEntry() },
            )
        }
        // The active cell's note, Excel's hover popup made permanent: shown under the formula
        // while that cell is selected, with a shortcut to edit it.
        cell?.comment?.let { note ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RibbonGlyph("ic_comment", PicoTheme.colorScheme.labelSecondary, 16.dp)
                Text(note.replace('\n', ' '), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onEditComment != null) RibbonSmallButton("ic_edit", stringResource(R.string.new_edit_comment), onEditComment)
            }
        }
        // Formula assistance: function names completing the identifier being typed, or the
        // signature of the call the caret is inside with the current argument emphasised.
        val ident = com.therealityteam.aeropages.editor.FormulaFunctions.trailingIdentifier(value.text)
        val matches = if (ident != null) com.therealityteam.aeropages.editor.FormulaFunctions.matching(ident) else emptyList()
        if (matches.isNotEmpty()) {
            Row(Modifier.padding(start = 96.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                matches.forEach { sig ->
                    RibbonSmallButton("x_fx", sig.name, {
                        val completed = value.text.dropLast(ident!!.length) + sig.name + "("
                        value = androidx.compose.ui.text.input.TextFieldValue(completed, androidx.compose.ui.text.TextRange(completed.length))
                        editor.typeCell(editor.selRow, editor.selCol, completed)
                    })
                }
                Text(matches.first().text.replace(", ", editor.symbols.argument + " "), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
            }
        } else com.therealityteam.aeropages.editor.FormulaFunctions.openCall(
            com.therealityteam.aeropages.editor.FormulaLocale.toCanonical(value.text, editor.symbols))?.let { (sig, argIdx) ->
            val hint = androidx.compose.ui.text.buildAnnotatedString {
                append(sig.name); append("(")
                sig.params.forEachIndexed { k, param ->
                    // Arguments apart as the language types them (; where the decimal symbol is a comma).
                    if (k > 0) append(editor.symbols.argument + " ")
                    // The last parameter absorbs any extra arguments (variadic "…").
                    val active = k == argIdx || (k == sig.params.lastIndex && argIdx >= sig.params.lastIndex)
                    if (active) withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = PicoTheme.colorScheme.interaction)) { append(param) } else append(param)
                }
                append(")")
            }
            Text(hint, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.padding(start = 96.dp, end = 8.dp, bottom = 6.dp))
        }
    }
}

/** Excel's Borders menu: the presets (each with its drawn icon), then the line style and colour they draw with. */
@Composable
private fun BordersMenu(editor: SheetEditor, onDone: () -> Unit) {
    val pen = editor.borderPen
    val ink = PicoTheme.colorScheme.labelPrimary
    val presets = listOf(
        SheetEditor.BorderPreset.BOTTOM to R.string.border_bottom, SheetEditor.BorderPreset.TOP to R.string.border_top,
        SheetEditor.BorderPreset.LEFT to R.string.border_left, SheetEditor.BorderPreset.RIGHT to R.string.border_right,
        SheetEditor.BorderPreset.NONE to R.string.border_none, SheetEditor.BorderPreset.ALL to R.string.border_all,
        SheetEditor.BorderPreset.OUTSIDE to R.string.border_outside, SheetEditor.BorderPreset.THICK_BOX to R.string.border_thick_box,
        SheetEditor.BorderPreset.BOTTOM_DOUBLE to R.string.border_bottom_double, SheetEditor.BorderPreset.THICK_BOTTOM to R.string.border_thick_bottom,
        SheetEditor.BorderPreset.TOP_BOTTOM to R.string.border_top_bottom, SheetEditor.BorderPreset.TOP_THICK_BOTTOM to R.string.border_top_thick_bottom,
        SheetEditor.BorderPreset.TOP_DOUBLE_BOTTOM to R.string.border_top_double_bottom,
    )
    // Excel's Borders menu: the presets in its four groups, then the pen they draw with (style and colour).
    FlyoutMenu(width = 320.dp, maxHeight = 640.dp) {
        presets.forEachIndexed { i, (preset, name) ->
            if (i == 4 || i == 8) MenuDivider()
            MenuCommand(stringResource(name), { editor.applyBorders(preset); onDone() }, leading = { BorderPresetIcon(preset, pen, ink) })
        }
        MenuDivider()
        MenuHeading(stringResource(R.string.border_line_style))
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                com.therealityteam.aeropages.model.LineStyle.HAIR, com.therealityteam.aeropages.model.LineStyle.THIN,
                com.therealityteam.aeropages.model.LineStyle.DOTTED, com.therealityteam.aeropages.model.LineStyle.DASHED,
                com.therealityteam.aeropages.model.LineStyle.DASH_DOT, com.therealityteam.aeropages.model.LineStyle.MEDIUM,
                com.therealityteam.aeropages.model.LineStyle.MEDIUM_DASHED, com.therealityteam.aeropages.model.LineStyle.THICK,
                com.therealityteam.aeropages.model.LineStyle.DOUBLE,
            ).forEach { style ->
                IconPill("", { editor.borderPen = pen.copy(style = style) }, selected = pen.style == style) {
                    androidx.compose.foundation.Canvas(Modifier.size(40.dp, 12.dp)) {
                        val y = size.height / 2f - (if (style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) 1.25.dp.toPx() else 0f)
                        drawBorderLine(com.therealityteam.aeropages.model.BorderLine(style, pen.color), Offset(0f, y), Offset(size.width, y), Offset(0f, 1f), ink)
                    }
                }
            }
        }
        MenuHeading(stringResource(R.string.border_line_color))
        Box(Modifier.padding(horizontal = 8.dp)) {
            ColorPalette(pen.color, { c -> editor.borderPen = pen.copy(color = c) }, noneLabel = stringResource(R.string.border_automatic))
        }
    }
}

/** A preset's icon: a faint 2×2 grid with the lines the preset draws, in the pen's colour (thick and double as they will look). */
@Composable
private fun BorderPresetIcon(preset: SheetEditor.BorderPreset, pen: com.therealityteam.aeropages.model.BorderLine, ink: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val t = 1.dp.toPx()
        val faint = ink.copy(alpha = 0.3f)
        val color = pen.color?.let { Color(it) } ?: ink
        drawRect(faint, style = androidx.compose.ui.graphics.drawscope.Stroke(0.8f * t))
        drawLine(faint, Offset(w / 2f, 0f), Offset(w / 2f, h), 0.8f * t)
        drawLine(faint, Offset(0f, h / 2f), Offset(w, h / 2f), 0.8f * t)
        fun top(width: Float = t) = drawLine(color, Offset(0f, 0f), Offset(w, 0f), width)
        fun bottom(width: Float = t) = drawLine(color, Offset(0f, h), Offset(w, h), width)
        fun left() = drawLine(color, Offset(0f, 0f), Offset(0f, h), t)
        fun right() = drawLine(color, Offset(w, 0f), Offset(w, h), t)
        fun doubleBottom() { bottom(0.8f * t); drawLine(color, Offset(0f, h - 2.5f * t), Offset(w, h - 2.5f * t), 0.8f * t) }
        when (preset) {
            SheetEditor.BorderPreset.BOTTOM -> bottom()
            SheetEditor.BorderPreset.TOP -> top()
            SheetEditor.BorderPreset.LEFT -> left()
            SheetEditor.BorderPreset.RIGHT -> right()
            SheetEditor.BorderPreset.NONE -> {}
            SheetEditor.BorderPreset.ALL -> {
                top(); bottom(); left(); right()
                drawLine(color, Offset(w / 2f, 0f), Offset(w / 2f, h), t)
                drawLine(color, Offset(0f, h / 2f), Offset(w, h / 2f), t)
            }
            SheetEditor.BorderPreset.OUTSIDE -> { top(); bottom(); left(); right() }
            SheetEditor.BorderPreset.THICK_BOX -> drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * t))
            SheetEditor.BorderPreset.BOTTOM_DOUBLE -> doubleBottom()
            SheetEditor.BorderPreset.THICK_BOTTOM -> bottom(2.5f * t)
            SheetEditor.BorderPreset.TOP_BOTTOM -> { top(); bottom() }
            SheetEditor.BorderPreset.TOP_THICK_BOTTOM -> { top(); bottom(2.5f * t) }
            SheetEditor.BorderPreset.TOP_DOUBLE_BOTTOM -> { top(); doubleBottom() }
        }
    }
}

/** A cell style's name as Excel's gallery shows it (headings and accents numbered). */
@Composable
private fun cellStyleName(style: com.therealityteam.aeropages.model.CellStyle): String = when (style) {
    com.therealityteam.aeropages.model.CellStyle.NORMAL -> stringResource(R.string.style_normal)
    com.therealityteam.aeropages.model.CellStyle.GOOD -> stringResource(R.string.style_good)
    com.therealityteam.aeropages.model.CellStyle.BAD -> stringResource(R.string.style_bad)
    com.therealityteam.aeropages.model.CellStyle.NEUTRAL -> stringResource(R.string.style_neutral)
    com.therealityteam.aeropages.model.CellStyle.CALCULATION -> stringResource(R.string.style_calculation)
    com.therealityteam.aeropages.model.CellStyle.CHECK_CELL -> stringResource(R.string.style_check_cell)
    com.therealityteam.aeropages.model.CellStyle.EXPLANATORY -> stringResource(R.string.style_explanatory)
    com.therealityteam.aeropages.model.CellStyle.INPUT -> stringResource(R.string.style_input)
    com.therealityteam.aeropages.model.CellStyle.LINKED_CELL -> stringResource(R.string.style_linked_cell)
    com.therealityteam.aeropages.model.CellStyle.NOTE -> stringResource(R.string.style_note)
    com.therealityteam.aeropages.model.CellStyle.OUTPUT -> stringResource(R.string.style_output)
    com.therealityteam.aeropages.model.CellStyle.WARNING -> stringResource(R.string.style_warning)
    com.therealityteam.aeropages.model.CellStyle.HEADING_1 -> stringResource(R.string.style_heading, 1)
    com.therealityteam.aeropages.model.CellStyle.HEADING_2 -> stringResource(R.string.style_heading, 2)
    com.therealityteam.aeropages.model.CellStyle.HEADING_3 -> stringResource(R.string.style_heading, 3)
    com.therealityteam.aeropages.model.CellStyle.HEADING_4 -> stringResource(R.string.style_heading, 4)
    com.therealityteam.aeropages.model.CellStyle.TITLE -> stringResource(R.string.style_title)
    com.therealityteam.aeropages.model.CellStyle.TOTAL -> stringResource(R.string.style_total)
    com.therealityteam.aeropages.model.CellStyle.COMMA -> stringResource(R.string.style_comma)
    com.therealityteam.aeropages.model.CellStyle.COMMA_0 -> stringResource(R.string.style_comma_0)
    com.therealityteam.aeropages.model.CellStyle.CURRENCY -> stringResource(R.string.style_currency)
    com.therealityteam.aeropages.model.CellStyle.CURRENCY_0 -> stringResource(R.string.style_currency_0)
    com.therealityteam.aeropages.model.CellStyle.PERCENT -> stringResource(R.string.style_percent)
    else -> if (style.name.endsWith("_20")) stringResource(R.string.style_accent_20, style.accentIndex + 1) else stringResource(R.string.style_accent, style.accentIndex + 1)
}

/** Excel's Cell Styles gallery: each style's name drawn in the style (its font, fill and borders), by section. */
@Composable
private fun CellStylesMenu(editor: SheetEditor, onStatus: (String) -> Unit, onDone: () -> Unit) {
    val groups = listOf(
        com.therealityteam.aeropages.model.CellStyle.Group.GOOD_BAD to R.string.style_group_good_bad,
        com.therealityteam.aeropages.model.CellStyle.Group.DATA to R.string.style_group_data,
        com.therealityteam.aeropages.model.CellStyle.Group.TITLES to R.string.style_group_titles,
        com.therealityteam.aeropages.model.CellStyle.Group.THEMED to R.string.style_group_themed,
        com.therealityteam.aeropages.model.CellStyle.Group.NUMBER to R.string.style_group_number,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { (group, label) ->
            Text(stringResource(label), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.therealityteam.aeropages.model.CellStyle.entries.filter { it.group == group }.forEach { style ->
                    val name = cellStyleName(style)
                    CellStyleChip(style, name) {
                        editor.applyCellStyle(style)
                        onStatus(Strings.get(R.string.status_cell_style, name))
                        onDone()
                    }
                }
            }
        }
    }
}

/** A gallery chip: [name] on a sample cell carrying [style]. */
@Composable
private fun CellStyleChip(style: com.therealityteam.aeropages.model.CellStyle, name: String, onClick: () -> Unit) {
    val sample = remember(style) { com.therealityteam.aeropages.model.Cell().also { style.applyTo(it) } }
    val ink = PicoTheme.colorScheme.labelPrimary
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.height(36.dp).widthIn(min = 72.dp).clip(shape)
            .background(sample.format.highlight?.let { Color(it) } ?: Color.White)
            .border(BorderStroke(0.5.dp, PicoTheme.colorScheme.dividerLine), shape)
            .drawWithContent {
                drawContent()
                fun line(l: com.therealityteam.aeropages.model.BorderLine?, a: Offset, b: Offset, inward: Offset) {
                    if (l != null) drawBorderLine(l, a, b, inward, ink)
                }
                val w = size.width; val h = size.height
                line(sample.borderTop, Offset(0f, 1f), Offset(w, 1f), Offset(0f, 1f))
                line(sample.borderBottom, Offset(0f, h - 2f), Offset(w, h - 2f), Offset(0f, -1f))
                line(sample.borderLeft, Offset(1f, 0f), Offset(1f, h), Offset(1f, 0f))
                line(sample.borderRight, Offset(w - 1f, 0f), Offset(w - 1f, h), Offset(-1f, 0f))
            }
            .semantics { contentDescription = name }
            .press(onClick = onClick)
            .padding(horizontal = 10.dp),
    ) {
        val f = sample.format
        Text(
            name, maxLines = 1,
            fontSize = ((f.fontSizePt ?: 12f).coerceAtMost(16f)).sp,
            fontWeight = if (f.bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            fontStyle = if (f.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            color = f.color?.let { Color(it) } ?: Color(0xFF1A1A1A),
        )
    }
}

/** Excel's Flash Fill on the active cell's column, saying in the status bar what it did. */
internal fun runFlashFill(editor: SheetEditor, onStatus: (String) -> Unit) {
    val n = editor.flashFill()
    onStatus(when {
        n > 0 -> Strings.get(R.string.status_flash_filled, n)
        n == 0 -> Strings.get(R.string.status_flash_nothing)
        else -> Strings.get(R.string.status_flash_no_pattern)
    })
}

/** The language's currency on the selection (the Currency button, Ctrl+Shift+$): ¥ in Chinese, $ in English, else its own code. */
internal fun applyLocaleCurrency(editor: SheetEditor) {
    val money = com.therealityteam.aeropages.model.LocaleCurrency.of()
    when (money.symbol) {
        "¥" -> editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.CURRENCY_CNY)
        "$" -> editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.CURRENCY_USD)
        else -> editor.setFormatCode(money.code)
    }
}

/** What a Trace command says when it drew nothing more: no formula (or none reading the cell), or no further level. */
internal fun traceStatus(result: SheetEditor.Trace, dependents: Boolean, onStatus: (String) -> Unit) {
    when (result) {
        SheetEditor.Trace.ADDED -> {}
        SheetEditor.Trace.NO_FORMULA -> onStatus(Strings.get(if (dependents) R.string.trace_no_dependents else R.string.trace_no_formula))
        SheetEditor.Trace.NOTHING_MORE -> onStatus(Strings.get(R.string.trace_no_more))
    }
}
