package com.therealityteam.aeropages.ui

import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalIndication
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.VerticalDivider
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.graphics.SpatialHoverStyle
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.ResolvedStyle
import com.therealityteam.aeropages.model.TextAlign
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

/** The ribbon's tabs; a [contextual] one shows only while what it works on is (Shape Format: a text box or shape being worked on). */
private enum class Tab(val title: Int, val contextual: Boolean = false) {
    HOME(R.string.tab_home), INSERT(R.string.tab_insert), DESIGN(R.string.tab_design), LAYOUT(R.string.tab_layout), REFERENCE(R.string.tab_references),
    REVIEW(R.string.tab_review), VIEW(R.string.tab_view), PICTURE_FORMAT(R.string.tab_picture_format, true), CHART_DESIGN(R.string.tab_chart_design, true),
    SHAPE_FORMAT(R.string.tab_shape_format, true),
    TABLE(R.string.tab_table_design, true), TABLE_LAYOUT(R.string.tab_table_layout, true)
}
private enum class Picker {
    NONE, STYLE, FONT, SIZE, COLOR, HIGHLIGHT, SPACING, SYMBOL, TABLE, CELLSHADE, CASE, CHARSPACING, MARKUP, SHOWMARKUP, ACCEPT, REJECT, DELCOMMENT,
    STYLESET, THEMECOLOR, THEMEFONT, DOCSPACING, WATERMARK, PAGECOLOR, MODIFYSTYLE, SHAPES, SHAPEFILL, SHAPEBORDER, SHAPESHADOW, CELLBORDER, BULLETS, NUMBERING, PAGENUMBERS, HEADER, FOOTER, MARGINS, ORIENTATION, PAPER, COLUMNS, BREAKS,
    TABLESTYLE, ZOOM, PARASHADE, PARABORDER, LINENUMBERS, DROPCAP, COVERPAGE
}

/**
 * Word-style tabbed ribbon: a tab strip (文件 + 开始/插入/布局/视图) with a
 * quick-access toolbar on the right, a per-tab group area with labeled groups,
 * and expandable picker panels. Layout and grouping mirror Word's Home tab so
 * existing Word users feel at home.
 */
@Composable
fun Ribbon(
    editor: DocumentEditor,
    view: PagesView,
    onTabs: () -> Unit,
    modifier: Modifier = Modifier,
    /** The document's name and whether it is saved, for the title row. */
    title: String = "",
    saveState: String = "",
    onHome: () -> Unit = {},
    onSave: () -> Unit,
    onFind: () -> Unit,
    /** Search's "Find in Document": the words found in the document (the Navigation pane's results). */
    onFindInDocument: (String) -> Unit = {},
    fileMenuOpen: Boolean,
    onToggleFileMenu: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertLink: () -> Unit,
    onBookmarks: () -> Unit = {},
    onInsertEquation: () -> Unit = {},
    navOpen: Boolean,
    onToggleNav: () -> Unit,
    onWordCount: () -> Unit,
    onCompare: () -> Unit = {},
    fullWidth: Boolean,
    onToggleLayout: () -> Unit,
    commentsOpen: Boolean,
    onToggleComments: () -> Unit,
    onAddComment: () -> Unit,
    onProofing: () -> Unit,
    spatialCommentsOpen: Boolean = false,
    onToggleSpatialComments: () -> Unit = {},
    footnotesOpen: Boolean,
    onToggleFootnotes: () -> Unit,
    sourcesOpen: Boolean,
    onToggleSources: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onShortcuts: () -> Unit = {},
    onFocus: () -> Unit = {},
    onStylesPane: () -> Unit = {},
    onClipboardPane: () -> Unit = {},
    onPageSetup: (PageSetupTab) -> Unit = {},
    /** References → Captions: Insert Caption, Cross-reference (their dialogs), Update Fields (and what it did, for the status). */
    onInsertCaption: () -> Unit = {},
    onCrossReference: () -> Unit = {},
    onMoreSymbols: () -> Unit = {},
    onTableOfFigures: () -> Unit = {},
    indexOpen: Boolean = false,
    onMarkEntry: () -> Unit = {},
    onInsertIndex: () -> Unit = {},
    onFieldsUpdated: (Int) -> Unit = {},
    /** A message for the status bar (nothing more to go to, how many went). */
    onStatus: (String) -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var tab by remember { mutableStateOf(when (devDialogProp()) { "shapes" -> Tab.INSERT; "shapeformat" -> Tab.SHAPE_FORMAT; "picformat", "alttext" -> Tab.PICTURE_FORMAT; "chartdesign" -> Tab.CHART_DESIGN; "tableborders", "tabletab", "tablestyles" -> Tab.TABLE; "tablelayout", "tablesort", "tabletotext", "tableformula" -> Tab.TABLE_LAYOUT
        "margins", "papersizes", "columnsgallery", "breaksmenu" -> Tab.LAYOUT
        "stylesets", "themefonts", "docspacing", "watermarks" -> Tab.DESIGN
        "tablegrid", "texttotable" -> Tab.INSERT
        "zoommenu", "onepage", "multipage", "pagewidth", "rulertabs" -> Tab.VIEW
        "zhtraditional", "zhconvert", "showmarkup", "locktracking" -> Tab.REVIEW
        else -> Tab.entries.firstOrNull { !it.contextual && it.name.equals(devTabName(), true) } ?: Tab.HOME }) }
    // Word's: a picture just inserted opens Picture Format, a shape or text box Shape Format, a table Table Design.
    androidx.compose.runtime.LaunchedEffect(editor.imageInserts) { if (editor.imageInserts > 0 && editor.selectedImage != null) tab = Tab.PICTURE_FORMAT }
    androidx.compose.runtime.LaunchedEffect(editor.chartInserts) { if (editor.chartInserts > 0 && editor.selectedChart != null) tab = Tab.CHART_DESIGN }
    androidx.compose.runtime.LaunchedEffect(editor.shapeInserts) { if (editor.shapeInserts > 0 && editor.activeBox != null) tab = Tab.SHAPE_FORMAT }
    androidx.compose.runtime.LaunchedEffect(editor.tableInserts) { if (editor.tableInserts > 0 && editor.activeInTable) tab = Tab.TABLE }
    // Picture Format goes when no picture is picked, Shape Format when no text box or shape is being worked on, Table
    // when the caret leaves the table.
    if (tab == Tab.PICTURE_FORMAT && editor.selectedImage == null && devDialogProp() !in setOf("picformat", "alttext")) tab = Tab.HOME
    if (tab == Tab.CHART_DESIGN && editor.selectedChart == null && devDialogProp() != "chartdesign") tab = Tab.HOME
    if (tab == Tab.SHAPE_FORMAT && editor.activeBox == null && devDialogProp() != "shapeformat") tab = Tab.HOME
    if ((tab == Tab.TABLE || tab == Tab.TABLE_LAYOUT) && !editor.activeInTable &&
        devDialogProp() !in setOf("tableborders", "tabletab", "tablestyles", "tablelayout", "tablesort", "tabletotext", "tableformula")) tab = Tab.HOME
    // Dev hook (debug builds): `setprop debug.aero.dialog paragraphpanel` opens the line spacing / indents panel.
    // Dev hooks: `paragraphpanel` opens the line spacing panel, `shapes` the Insert tab's shapes gallery, `shapeformat` Shape Format's fill.
    var picker by remember { mutableStateOf(when (devDialogProp()) { "paragraphpanel" -> Picker.SPACING; "shapes" -> Picker.SHAPES; "shapeformat" -> Picker.SHAPEFILL; "tableborders" -> Picker.CELLBORDER; "numbering" -> Picker.NUMBERING; "pagenumbers" -> Picker.PAGENUMBERS; "headergallery" -> Picker.HEADER; "footergallery" -> Picker.FOOTER; "margins" -> Picker.MARGINS; "papersizes" -> Picker.PAPER; "columnsgallery" -> Picker.COLUMNS; "bullets" -> Picker.BULLETS; "tablestyles" -> Picker.TABLESTYLE; "markuppanel" -> Picker.MARKUP; "showmarkup" -> Picker.SHOWMARKUP; "acceptmenu" -> Picker.ACCEPT; "zoommenu" -> Picker.ZOOM; "fontcolor" -> Picker.COLOR; "fontlist" -> Picker.FONT; "casemenu" -> Picker.CASE; "tablegrid", "texttotable" -> Picker.TABLE; "stylesets" -> Picker.STYLESET; "themefonts" -> Picker.THEMEFONT; "docspacing" -> Picker.DOCSPACING; "watermarks" -> Picker.WATERMARK; "breaksmenu" -> Picker.BREAKS; "modifystyle" -> Picker.MODIFYSTYLE; "paraborders" -> Picker.PARABORDER; "linenumbers" -> Picker.LINENUMBERS; "dropcap" -> Picker.DROPCAP; "coverpages" -> Picker.COVERPAGE; else -> Picker.NONE }) }
    fun toggle(p: Picker) { picker = if (picker == p) Picker.NONE else p }
    // Word's Font and Paragraph dialogs, from their groups' launchers (dev hooks `fontdialog`, `paragraphdialog`: once the
    // file is in, with the caret in its second paragraph).
    var fontDialog by remember { mutableStateOf(false) }
    var paragraphDialog by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val hook = devDialogProp()
        if (hook == "fontdialog" || hook == "paragraphdialog") {
            kotlinx.coroutines.delay(3_000)
            if (editor.paragraphCount() > 1) editor.focusParagraph(1)
            if (hook == "fontdialog") fontDialog = true else paragraphDialog = true
        }
    }
    if (fontDialog) FontDialog(editor) { fontDialog = false }
    // Word's Date and Time (Insert ▸ Text; dev hook `datetime`).
    var dateDialog by remember { mutableStateOf(devDialogProp() == "datetime") }
    if (dateDialog) DateTimeDialog({ dateDialog = false }) { editor.insertAtCaret(it) }
    if (paragraphDialog) ParagraphDialog(editor, { paragraphDialog = false }, onTabs)
    var autoCorrectDialog by remember {
        val hook = if (com.therealityteam.aeropages.BuildConfig.DEBUG) devDialogProp() else null
        // Dev hook `autocorrectentries`: two entries of one's own, for this run only (not saved), shown in the dialog.
        if (hook == "autocorrectentries") com.therealityteam.aeropages.editor.AutoCorrect.options = com.therealityteam.aeropages.editor.AutoCorrect.options
            .copy(entries = mapOf("addr" to "12 Main Street, Springfield", "btw" to "by the way"))
        mutableStateOf(hook == "autocorrect" || hook == "autocorrectentries")
    }
    if (autoCorrectDialog) AutoCorrectOptionsDialog { autoCorrectDialog = false }
    // Styles ▸ Create a Style (dev hook `createstyle`).
    var createStyleDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "createstyle") }
    if (createStyleDialog) CreateStyleDialog(editor) { createStyleDialog = false }
    // Layout ▸ Columns ▸ More Columns (dev hook `morecolumns`).
    var columnsDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "morecolumns") }
    if (columnsDialog) ColumnsDialog(editor) { columnsDialog = false }
    // Word's Line Numbers dialog, from the Line Numbers menu.
    var lineNumbersDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "lineoptions") }
    if (lineNumbersDialog) LineNumbersDialog(editor) { lineNumbersDialog = false }
    var dropCapDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "dropcapdialog") }
    if (dropCapDialog) DropCapDialog(editor) { dropCapDialog = false }
    // Word's Borders and Shading, from the Borders menu (the Page Border tab opens from Design ▸ Page Borders).
    var bordersDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() in setOf("bordersshading", "shadingtab")) }
    if (bordersDialog) BordersAndShadingDialog(editor, if (devDialogProp() == "shadingtab") BordersTab.SHADING else BordersTab.BORDERS) { bordersDialog = false }
    // Sort (Home ▸ Paragraph): the table's rows when the caret is in one, else Sort Text for the paragraphs.
    var homeSort by remember { mutableStateOf(false) }
    if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "sorttext") androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000); editor.selectAll(); homeSort = true
    }
    if (homeSort) {
        if (editor.activeInTable) TableSortDialog(editor, { homeSort = false }, onStatus)
        else SortTextDialog(editor, { homeSort = false }, onStatus)
    }

    // Office's Search in the title bar: what is typed finds commands, shown in the ribbon's place; a pick runs it
    // (opening its tab and menu when it is one) and ends the search. Dev hook `commandsearch`: "页" typed.
    val search = rememberCommandSearch("pages")
    fun open(t: Tab, p: Picker = Picker.NONE) { tab = t; picker = p }
    val tabName: @Composable (Tab) -> String = { stringResource(it.title) }
    @Composable fun commands(): List<AppCommand> {
        val home = tabName(Tab.HOME); val insert = tabName(Tab.INSERT); val design = tabName(Tab.DESIGN); val layout = tabName(Tab.LAYOUT)
        val refs = tabName(Tab.REFERENCE); val review = tabName(Tab.REVIEW); val viewTab = tabName(Tab.VIEW)
        @Composable fun c(id: Int, icon: String, where: String, run: () -> Unit) = AppCommand(stringResource(id), icon, where, run)
        @Composable fun d(id: Int, icon: String, where: String, run: () -> Unit) = AppCommand(stringResource(id) + "…", icon, where, run)
        return listOf(
            c(R.string.paste, "📋", home, onPaste), c(R.string.cut, "✂", home, onCut), c(R.string.copy, "⧉", home, onCopy),
            c(R.string.format_painter, "🖌", home, editor::formatPainter), c(R.string.clipboard, "📋", home, onClipboardPane),
            c(R.string.bold, "B", home, editor::toggleBold), c(R.string.italic, "I", home, editor::toggleItalic),
            c(R.string.underline, "U", home, editor::toggleUnderline), c(R.string.strikethrough, "S", home, editor::toggleStrike),
            c(R.string.superscript, "x²", home, editor::toggleSuperscript), c(R.string.subscript, "x₂", home, editor::toggleSubscript),
            d(R.string.font, "Aa", home) { fontDialog = true }, d(R.string.paragraph, "↕", home) { paragraphDialog = true },
            c(R.string.bullets, "•", home, editor::toggleBullet), c(R.string.numbering, "1.", home, editor::toggleNumber),
            c(R.string.align_left, "⇠", home) { editor.setAlign(TextAlign.LEFT) }, c(R.string.center, "⇔", home) { editor.setAlign(TextAlign.CENTER) },
            c(R.string.align_right, "⇢", home) { editor.setAlign(TextAlign.RIGHT) }, c(R.string.justify, "⇿", home) { editor.setAlign(TextAlign.JUSTIFY) },
            c(R.string.line_spacing, "↕", home) { open(Tab.HOME, Picker.SPACING) }, c(R.string.styles, "x_theme", home, onStylesPane),
            d(R.string.sort_text, "x_sortasc", home) { homeSort = true },
            c(R.string.shading, "ic_fill", home) { open(Tab.HOME, Picker.PARASHADE) }, c(R.string.bs_borders, "ic_borders", home) { open(Tab.HOME, Picker.PARABORDER) },
            c(R.string.borders_and_shading_more, "ic_borders", home) { bordersDialog = true },
            c(R.string.find, "x_search", home, onFind), c(R.string.replace, "⇄", home) { editor.requestFindPanel(1) },
            c(R.string.goto_tab, "→", home) { editor.requestFindPanel(2) }, c(R.string.select_all, "▦", home) { editor.selectAll() },
            c(R.string.table, "▦", insert) { open(Tab.INSERT, Picker.TABLE) }, c(R.string.picture, "🖼", insert, onInsertImage),
            c(R.string.shapes_insert, "⬛", insert) { open(Tab.INSERT, Picker.SHAPES) }, c(R.string.chart_label, "📊", insert, editor::insertChart),
            c(R.string.link, "🔗", insert, onInsertLink), c(R.string.bookmark, "bookmark", insert, onBookmarks),
            c(R.string.hf_header, "x_header", insert) { open(Tab.INSERT, Picker.HEADER) }, c(R.string.hf_footer, "x_footer", insert) { open(Tab.INSERT, Picker.FOOTER) },
            c(R.string.page_number, "x_pagenumber", insert) { open(Tab.INSERT, Picker.PAGENUMBERS) }, c(R.string.text_box, "文本框", insert, editor::insertTextBox),
            c(R.string.page_break, "⤓", insert) { editor.insertPageBreak() }, d(R.string.date_and_time, "📅", insert) { dateDialog = true },
            c(R.string.cover_page, "ic_cover_page", insert) { open(Tab.INSERT, Picker.COVERPAGE) }, c(R.string.blank_page, "ic_blank_page", insert) { editor.insertBlankPage() },
            c(R.string.drop_cap, "ic_drop_cap", insert) { open(Tab.INSERT, Picker.DROPCAP) }, d(R.string.drop_cap_options, "ic_drop_cap", insert) { dropCapDialog = true },
            c(R.string.equation, "π", insert, onInsertEquation), d(R.string.symbol, "Ω", insert, onMoreSymbols),
            c(R.string.watermark, "💧", design) { open(Tab.DESIGN, Picker.WATERMARK) }, c(R.string.page_color, "▥", design) { open(Tab.DESIGN, Picker.PAGECOLOR) },
            c(R.string.margins, "x_margins", layout) { open(Tab.LAYOUT, Picker.MARGINS) }, c(R.string.orientation, "x_orientation", layout) { open(Tab.LAYOUT, Picker.ORIENTATION) },
            c(R.string.size, "x_paper", layout) { open(Tab.LAYOUT, Picker.PAPER) }, c(R.string.page_columns, "x_pagecolumns", layout) { open(Tab.LAYOUT, Picker.COLUMNS) },
            c(R.string.breaks, "x_breaks", layout) { open(Tab.LAYOUT, Picker.BREAKS) }, c(R.string.line_numbers, "ic_line_numbers", layout) { open(Tab.LAYOUT, Picker.LINENUMBERS) },
            c(R.string.ln_options_more, "ic_line_numbers", layout) { lineNumbersDialog = true }, d(R.string.page_setup, "x_margins", layout) { onPageSetup(PageSetupTab.MARGINS) },
            c(R.string.table_of_contents, "☰", refs, editor::insertToc), c(R.string.insert_footnote, "ab¹", refs, editor::insertFootnote),
            c(R.string.insert_endnote, "abⁱ", refs, editor::insertEndnote), d(R.string.insert_caption, "x_caption", refs, onInsertCaption),
            d(R.string.cross_reference, "x_xref", refs, onCrossReference), d(R.string.insert_table_of_figures, "x_tof", refs, onTableOfFigures),
            c(R.string.update_fields, "x_updatefields", refs) { onFieldsUpdated(editor.updateFields()) },
            c(R.string.mark_entry, "ic_mark_entry", refs, onMarkEntry), d(R.string.insert_index, "ic_insert_index", refs, onInsertIndex),
            c(R.string.spelling_check, "拼写", review, onProofing), c(R.string.title_word_count, "字数", review, onWordCount),
            c(R.string.compare_documents, "ic_compare", review, onCompare),
            AppCommand(stringResource(R.string.ac_options) + "…", "ic_lightning", review) { autoCorrectDialog = true },
            c(R.string.new_comment, "💬", review, onAddComment), c(R.string.track_changes, "✎", review, editor::toggleTrackChanges), c(R.string.lock_tracking, "x_lock", review) { if (editor.trackingLocked) editor.unlockTracking() else editor.lockTracking() },
            c(R.string.show_markup, "ic_eye", review) { open(Tab.REVIEW, Picker.SHOWMARKUP) },
            c(R.string.focus_mode, "x_focus", viewTab, onFocus), c(R.string.ruler_toggle, "ic_ruler", viewTab) { view.ruler = !view.ruler },
            c(R.string.navigation_pane, "ic_list", viewTab, onToggleNav), c(R.string.zoom_100, "100", viewTab) { view.zoomTo(1f) },
            c(R.string.keyboard_shortcuts, "kbd_help", viewTab, onShortcuts),
        )
    }
    val anchor = remember { FlyoutAnchor() }
    androidx.compose.runtime.CompositionLocalProvider(LocalFlyoutAnchor provides anchor) {
    Column(modifier = modifier.fillMaxWidth()) {
        val all = if (search.active) commands() else emptyList()
        AppTitleRow(title, saveState, onHome, search = { TitleSearchBox(search, all) }) {
            TitleIconButton("ic_undo", stringResource(R.string.undo), editor::undo, enabled = editor.canUndo)
            TitleIconButton("ic_redo", stringResource(R.string.redo), editor::redo, enabled = editor.canRedo)
            TitleIconButton("ic_save", stringResource(R.string.save), onSave)
        }
        RibbonTabRow(trailing = { TabRowCommand("ic_comment", stringResource(R.string.comments), commentsOpen, onToggleComments) }) {
            FileTab(fileMenuOpen, onToggleFileMenu)
            Tab.entries.filter { !it.contextual || (it == Tab.PICTURE_FORMAT && editor.selectedImage != null) || (it == Tab.CHART_DESIGN && editor.selectedChart != null) ||
                (it == Tab.SHAPE_FORMAT && editor.activeBox != null) || ((it == Tab.TABLE || it == Tab.TABLE_LAYOUT) && editor.activeInTable) }.forEach { t ->
                RibbonTab(stringResource(t.title), selected = tab == t, contextual = t.contextual) {
                    tab = t; picker = Picker.NONE
                }
            }
        }
        RibbonCard(if (search.active) "search" else tab) {
            if (search.active) SearchCard(search, all, stringResource(tab.title), stringResource(R.string.search_find_doc), onFindInDocument)
            else when (tab) {
                Tab.HOME -> HomeTab(editor, ::toggle, picker, onCopy, onCut, onPaste, onFind, { fontDialog = true }, { paragraphDialog = true }, { homeSort = true }, onStylesPane, onClipboardPane)
                Tab.INSERT -> InsertTab(editor, ::toggle, picker, onInsertImage, onInsertLink, onInsertEquation, onBookmarks) { dateDialog = true }
                Tab.PICTURE_FORMAT -> PictureFormatTab(editor)
                Tab.CHART_DESIGN -> ChartDesignTab(editor)
                Tab.SHAPE_FORMAT -> ShapeFormatTab(editor, ::toggle, picker)
                Tab.TABLE -> TableDesignTab(editor, ::toggle, picker)
                Tab.TABLE_LAYOUT -> TableLayoutTab(editor, onStatus)
                Tab.DESIGN -> DesignTab(editor, ::toggle, picker)
                Tab.LAYOUT -> LayoutTab(editor, ::toggle, picker, onPageSetup) { paragraphDialog = true }
                Tab.REFERENCE -> ReferenceTab(editor, footnotesOpen, onToggleFootnotes, sourcesOpen, onToggleSources, onInsertCaption, onCrossReference, onFieldsUpdated, onTableOfFigures, indexOpen, onMarkEntry, onInsertIndex)
                Tab.REVIEW -> ReviewTab(editor, commentsOpen, onToggleComments, onAddComment, onProofing, spatialCommentsOpen, onToggleSpatialComments, picker, ::toggle, onStatus, onWordCount, onCompare)
                Tab.VIEW -> ViewTab(editor, view, picker, ::toggle, navOpen, onToggleNav, fullWidth, onToggleLayout, onShortcuts, onFocus)
            }
        }

        // A dropdown's panel: a flyout under the command that opened it.
        if (picker != Picker.NONE) {
            RibbonFlyout({ picker = Picker.NONE }) {
                when (picker) {
                    Picker.CASE -> CasePanel(editor) { picker = Picker.NONE }
                    Picker.CHARSPACING -> CharSpacingPanel(editor) { picker = Picker.NONE }
                    Picker.STYLESET -> StyleSetPanel(editor) { picker = Picker.NONE }
                    Picker.MODIFYSTYLE -> ModifyStylePanel(editor) { picker = Picker.NONE }
                    Picker.THEMECOLOR -> ThemeColorPanel(editor) { picker = Picker.NONE }
                    Picker.THEMEFONT -> ThemeFontPanel(editor) { picker = Picker.NONE }
                    Picker.DOCSPACING -> DocSpacingPanel(editor) { picker = Picker.NONE }
                    Picker.WATERMARK -> WatermarkPanel(editor) { picker = Picker.NONE }
                    Picker.PAGECOLOR -> PageColorPanel(editor) { picker = Picker.NONE }
                    Picker.STYLE -> StylePanel(editor, onModify = { picker = Picker.MODIFYSTYLE }, onCreate = { createStyleDialog = true }) { picker = Picker.NONE }
                    Picker.FONT -> FontPanel(editor) { picker = Picker.NONE }
                    Picker.SIZE -> SizePanel(editor) { picker = Picker.NONE }
                    Picker.COLOR -> ColorPanel(editor, highlight = false) { picker = Picker.NONE }
                    Picker.HIGHLIGHT -> ColorPanel(editor, highlight = true) { picker = Picker.NONE }
                    Picker.SPACING -> SpacingPanel(editor) { picker = Picker.NONE }
                    Picker.SYMBOL -> SymbolMenu({ editor.insertAtCaret(it); picker = Picker.NONE }) { picker = Picker.NONE; onMoreSymbols() }
                    Picker.MARKUP -> MarkupPanel(editor) { picker = Picker.NONE }
                    Picker.SHOWMARKUP -> ShowMarkupPanel(editor)
                    Picker.ACCEPT -> ResolvePanel(editor, accept = true, onStatus) { picker = Picker.NONE }
                    Picker.REJECT -> ResolvePanel(editor, accept = false, onStatus) { picker = Picker.NONE }
                    Picker.DELCOMMENT -> DeleteCommentPanel(editor, onStatus) { picker = Picker.NONE }
                    Picker.TABLE -> TablePanel(editor) { picker = Picker.NONE }
                    Picker.CELLSHADE -> CellShadePanel(editor) { picker = Picker.NONE }
                    Picker.PARASHADE -> ColorPalette(editor.paragraphShading, { editor.setParagraphShading(it); picker = Picker.NONE }, noneLabel = stringResource(R.string.no_color))
                    Picker.PARABORDER -> ParagraphBordersMenu(editor, { picker = Picker.NONE }, onMore = { picker = Picker.NONE; bordersDialog = true })
                    Picker.CELLBORDER -> TableBordersMenu(editor) { picker = Picker.NONE }
                    Picker.TABLESTYLE -> TableLookGallery(editor) { picker = Picker.NONE }
                    Picker.BULLETS -> ListsPanel(editor, bullets = true) { picker = Picker.NONE }
                    Picker.NUMBERING -> ListsPanel(editor, bullets = false) { picker = Picker.NONE }
                    Picker.PAGENUMBERS -> PageNumbersPanel(editor)
                    Picker.ZOOM -> FlyoutMenu(width = 160.dp) {
                        PagesView.LEVELS.forEach { z ->
                            MenuCommand("$z%", { view.zoomTo(z / 100f); picker = Picker.NONE }, checked = !view.multiPage && view.percent == z)
                        }
                    }
                    Picker.MARGINS -> MarginsPanel(editor, { picker = Picker.NONE }) { onPageSetup(PageSetupTab.MARGINS) }
                    Picker.ORIENTATION -> OrientationPanel(editor) { picker = Picker.NONE }
                    Picker.PAPER -> PaperPanel(editor, { picker = Picker.NONE }) { onPageSetup(PageSetupTab.PAPER) }
                    Picker.COLUMNS -> ColumnsPanel(editor, { picker = Picker.NONE }, onMore = { columnsDialog = true })
                    Picker.BREAKS -> BreaksPanel(editor) { picker = Picker.NONE }
                    Picker.LINENUMBERS -> LineNumbersMenu(editor, { picker = Picker.NONE }, onMore = { picker = Picker.NONE; lineNumbersDialog = true })
                    Picker.DROPCAP -> DropCapMenu(editor, { picker = Picker.NONE }, onMore = { picker = Picker.NONE; dropCapDialog = true })
                    Picker.COVERPAGE -> CoverPageGallery(editor) { picker = Picker.NONE }
                    Picker.HEADER -> HeaderFooterGallery(editor, header = true) { picker = Picker.NONE }
                    Picker.FOOTER -> HeaderFooterGallery(editor, header = false) { picker = Picker.NONE }
                    // Word's Insert → Shapes: the gallery Slides and Sheets use; the shape floats in front of the text.
                    Picker.SHAPES -> ShapeGallery { name -> editor.insertShape(name); picker = Picker.NONE }
                    Picker.SHAPEFILL -> BoxFillPanel(editor) { picker = Picker.NONE }
                    Picker.SHAPEBORDER -> BoxOutlinePanel(editor) { picker = Picker.NONE }
                    Picker.SHAPESHADOW -> BoxShadowPanel(editor) { picker = Picker.NONE }
                    Picker.NONE -> Unit
                }
            }
        }
    }
    }
}

// --- tab content -----------------------------------------------------------

@Composable
private fun HomeTab(
    editor: DocumentEditor,
    toggle: (Picker) -> Unit,
    picker: Picker,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onFind: () -> Unit,
    onFontDialog: () -> Unit,
    onParagraphDialog: () -> Unit,
    onSort: () -> Unit,
    onStylesPane: () -> Unit,
    onClipboardPane: () -> Unit,
) {
    val cf = editor.currentFormat
    val pf = editor.activeParagraph.format

    // Word's Clipboard group: Paste, Cut, Copy and the Format Painter — the only way to them
    // without a keyboard.
    Group(stringResource(R.string.clipboard), launcher = onClipboardPane) {
        RibbonButton("📋", stringResource(R.string.paste), onPaste)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RibbonMiniButton("✂", stringResource(R.string.cut), onCut)
                RibbonMiniButton("⧉", stringResource(R.string.copy), onCopy)
            }
            RibbonMiniButton("🖌", stringResource(R.string.format_painter), editor::formatPainter, selected = editor.painterArmed)
        }
    }

    Group(stringResource(R.string.font), launcher = onFontDialog) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RibbonCombo(cf.fontFamily ?: "Calibri", { toggle(Picker.FONT) }, width = 110.dp, selected = picker == Picker.FONT, tooltip = stringResource(R.string.font))
                RibbonCombo(currentSize(editor).toInt().toString(), { toggle(Picker.SIZE) }, width = 46.dp, selected = picker == Picker.SIZE, tooltip = stringResource(R.string.font_size))
                RibbonMiniButton("A↑", stringResource(R.string.increase_font_size), { editor.setFontSize(stepSize(currentSize(editor), +1)) })
                RibbonMiniButton("A↓", stringResource(R.string.decrease_font_size), { editor.setFontSize(stepSize(currentSize(editor), -1)) })
                RibbonMiniButton("Aa", stringResource(R.string.change_case), { toggle(Picker.CASE) }, selected = picker == Picker.CASE, dropdown = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RibbonMiniButton("B", stringResource(R.string.bold), editor::toggleBold, selected = cf.bold)
                RibbonMiniButton("I", stringResource(R.string.italic), editor::toggleItalic, selected = cf.italic)
                RibbonMiniButton("U", stringResource(R.string.underline), editor::toggleUnderline, selected = cf.underline)
                RibbonMiniButton("S", stringResource(R.string.strikethrough), editor::toggleStrike, selected = cf.strikethrough)
                RibbonMiniButton("x²", stringResource(R.string.superscript), editor::toggleSuperscript, selected = cf.superscript)
                RibbonMiniButton("x₂", stringResource(R.string.subscript), editor::toggleSubscript, selected = cf.subscript)
                RibbonMiniButton("ᴀʙ", stringResource(R.string.small_caps), editor::toggleSmallCaps, selected = cf.smallCaps)
                RibbonMiniButton("⟷", stringResource(R.string.character_spacing), { toggle(Picker.CHARSPACING) }, selected = picker == Picker.CHARSPACING, dropdown = true)
                RibbonMiniButton("A", stringResource(R.string.font_color), { toggle(Picker.COLOR) }, selected = picker == Picker.COLOR, dropdown = true, accent = Color(0xFFCC0000))
                RibbonMiniButton("▆", stringResource(R.string.text_highlight_color), { toggle(Picker.HIGHLIGHT) }, selected = picker == Picker.HIGHLIGHT, dropdown = true, accent = Color(0xFFFFD400))
            }
        }
    }
    Group(stringResource(R.string.paragraph), launcher = onParagraphDialog) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RibbonMiniButton("•", stringResource(R.string.bullets), editor::toggleBullet, selected = pf.listKind == ListKind.BULLET)
                RibbonMiniButton("", stringResource(R.string.bullet_library), { toggle(Picker.BULLETS) }, selected = picker == Picker.BULLETS, dropdown = true)
                RibbonMiniButton("1.", stringResource(R.string.numbering), editor::toggleNumber, selected = pf.listKind == ListKind.NUMBER)
                RibbonMiniButton("", stringResource(R.string.numbering_library), { toggle(Picker.NUMBERING) }, selected = picker == Picker.NUMBERING, dropdown = true)
                RibbonMiniButton("⇤", stringResource(R.string.decrease_indent), editor::outdent)
                RibbonMiniButton("⇥", stringResource(R.string.increase_indent), editor::indent)
                // Word's Sort: a table's rows in a table, the paragraphs elsewhere.
                RibbonMiniButton("x_sortasc", stringResource(R.string.table_sort), onSort)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RibbonMiniButton("⇠", stringResource(R.string.align_left), { editor.setAlign(TextAlign.LEFT) }, selected = pf.align == TextAlign.LEFT)
                RibbonMiniButton("⇔", stringResource(R.string.center), { editor.setAlign(TextAlign.CENTER) }, selected = pf.align == TextAlign.CENTER)
                RibbonMiniButton("⇢", stringResource(R.string.align_right), { editor.setAlign(TextAlign.RIGHT) }, selected = pf.align == TextAlign.RIGHT)
                RibbonMiniButton("⇿", stringResource(R.string.justify), { editor.setAlign(TextAlign.JUSTIFY) }, selected = pf.align == TextAlign.JUSTIFY)
                RibbonMiniButton("↕", stringResource(R.string.line_spacing), { toggle(Picker.SPACING) }, selected = picker == Picker.SPACING, dropdown = true)
                // Word's Shading and Borders, after the alignment and line spacing.
                RibbonMiniButton("ic_fill", stringResource(R.string.shading), { toggle(Picker.PARASHADE) }, selected = picker == Picker.PARASHADE, dropdown = true,
                    accent = pf.shading?.let { Color(it) })
                RibbonMiniButton("ic_borders", stringResource(R.string.bs_borders), { toggle(Picker.PARABORDER) }, selected = picker == Picker.PARABORDER, dropdown = true)
            }
        }
    }
    Group(stringResource(R.string.styles), launcher = onStylesPane) {
        StyleGallery(editor) { toggle(Picker.STYLE) }
    }
    // Editing as Word's: Find, Replace and Go To (the same panel on its tabs), Select All.
    Group(stringResource(R.string.find_and_replace)) {
        RibbonButton("x_search", stringResource(R.string.find), onFind)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonSmallButton("⇄", stringResource(R.string.replace), { editor.requestFindPanel(1) }, compactAt = 3)
            RibbonSmallButton("→", stringResource(R.string.goto_tab), { editor.requestFindPanel(2) }, compactAt = 3)
            RibbonSmallButton("▦", stringResource(R.string.select_all), { editor.selectAll() }, compactAt = 3)
        }
    }
}

/** 修改样式: edit the active paragraph's style definition; every paragraph using it (or based on it) follows. */
@Composable
internal fun ModifyStylePanel(editor: DocumentEditor, style: ParagraphStyle = editor.activeParagraph.format.style, heading: Boolean = true, onPicked: () -> Unit) {
    val r = editor.resolvedStyle(style)
    val byDefault = stringResource(R.string.default_option)
    // Word's Modify Style: formatting (font, size, bold, italic, colour, alignment), spacing, then the style's commands.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(560.dp)) {
        if (heading) DialogLabel(stringResource(R.string.label_modify_style, style.display))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ComboField(r.fontFamily ?: byDefault, listOf<String?>(null) + TextStyling.FONT_FAMILIES, { it ?: byDefault }, r.fontFamily,
                { f -> editor.modifyStyle(style) { it.fontFamily = f } }, width = 200.dp)
            ComboField(r.sizePt.toInt().toString(), TextStyling.FONT_SIZES, { it.toInt().toString() }, r.sizePt, { sz -> editor.modifyStyle(style) { it.sizePt = sz } }, width = 80.dp)
            RibbonMiniButton("B", stringResource(R.string.bold), { editor.modifyStyle(style) { it.bold = !r.bold } }, selected = r.bold)
            RibbonMiniButton("I", stringResource(R.string.italic), { editor.modifyStyle(style) { it.italic = !r.italic } }, selected = r.italic)
            BarDivider()
            RibbonMiniButton("ic_align_left", stringResource(R.string.align_left), { editor.modifyStyle(style) { it.align = TextAlign.LEFT } }, selected = r.align == TextAlign.LEFT)
            RibbonMiniButton("ic_align_center", stringResource(R.string.center), { editor.modifyStyle(style) { it.align = TextAlign.CENTER } }, selected = r.align == TextAlign.CENTER)
            RibbonMiniButton("ic_align_right", stringResource(R.string.align_right), { editor.modifyStyle(style) { it.align = TextAlign.RIGHT } }, selected = r.align == TextAlign.RIGHT)
            RibbonMiniButton("ic_align_justify", stringResource(R.string.justify), { editor.modifyStyle(style) { it.align = TextAlign.JUSTIFY } }, selected = r.align == TextAlign.JUSTIFY)
        }
        DialogLabel(stringResource(R.string.font_color))
        ColorPalette(r.color, { c -> editor.modifyStyle(style) { it.color = c } }, noneLabel = byDefault)
        DialogLabel(stringResource(R.string.paragraph_spacing))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.spacing_before), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                SpinBox(stringResource(R.string.unit_pt, charCount(r.spaceBeforePt ?: 0f)), { d -> editor.modifyStyle(style) { it.spaceBeforePt = ((r.spaceBeforePt ?: 0f) + 6f * d).coerceAtLeast(0f) } },
                    description = stringResource(R.string.spacing_before), canLess = (r.spaceBeforePt ?: 0f) > 0f, valueWidth = 52.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.spacing_after), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
                SpinBox(stringResource(R.string.unit_pt, charCount(r.spaceAfterPt ?: 0f)), { d -> editor.modifyStyle(style) { it.spaceAfterPt = ((r.spaceAfterPt ?: 0f) + 6f * d).coerceAtLeast(0f) } },
                    description = stringResource(R.string.spacing_after), canLess = (r.spaceAfterPt ?: 0f) > 0f, valueWidth = 52.dp)
            }
            val multiples = listOf(1.0f, 1.15f, 1.5f, 2.0f)
            val m = r.lineSpacing.takeIf { r.lineHeightPt == null && it in multiples }
            ComboField(m?.let { "$it" } ?: stringResource(R.string.line_spacing), multiples, { "$it" }, m,
                { v -> editor.modifyStyle(style) { it.lineSpacing = v; it.lineHeightPt = null } }, width = 96.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            DialogButton(stringResource(R.string.update_style_from_selection), { editor.updateStyleFromSelection(style) })
            DialogButton(stringResource(R.string.reset_style), { editor.resetStyle(style) })
            DialogButton(stringResource(R.string.close), onPicked, primary = true)
        }
    }
}

/** Word-style horizontal gallery of paragraph-style preview cards. */
@Composable
private fun StyleGallery(editor: DocumentEditor, onMore: () -> Unit) {
    val current = editor.activeParagraph.format.style
    // No horizontalScroll here: the whole ribbon group row already scrolls
    // horizontally; nesting same-direction scroll measures with infinite width.
    Row(
        modifier = Modifier.heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Word's narrow gallery: the first styles — fewer as the window narrows (the ribbon's squeeze) — the current one
        // shown even when it is further on; the rest under ▾.
        val count = (4 - LocalRibbonSqueeze.current).coerceAtLeast(2)
        // The caret's paragraph in a style of the document's own (one just created): its card, last, picked.
        val own = editor.ownStyles.firstOrNull { it.id.equals(editor.activeStyleId, ignoreCase = true) }
        val builtins = if (own != null) count - 1 else count
        val shown = ParagraphStyle.entries.take(builtins).let { if (own != null || current in it) it else it.take(builtins - 1) + current }
        shown.forEach { style ->
            StyleCard(style.display, editor.resolvedStyle(style), selected = own == null && style == current) { editor.setStyle(style) }
        }
        own?.let { d -> StyleCard(d.name, editor.resolvedStyle(d.id), selected = true) { editor.setStyleId(d.id) } }
        RibbonMiniButton("", stringResource(R.string.more_styles), onMore, dropdown = true)
    }
}

@Composable
private fun StyleCard(label: String, r: ResolvedStyle, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .width(74.dp)
            .heightIn(min = 50.dp)
            .clip(shape)
            .border(
                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) LocalAccent.current else PicoTheme.colorScheme.dividerLine),
                shape,
            )
            .background(Color.White)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction),
    ) {
        GazeFill(shape)
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                "AaBbCc",
                fontSize = r.sizePt.coerceAtMost(15f).sp,
                color = Color(r.color ?: 0xFF1A1A1A),
                maxLines = 1,
                fontStyle = if (r.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                style = if (r.bold) PicoTheme.typography.titleSmall else AeroType.body,
            )
            Text(
                label,
                fontSize = 11.sp,
                // On the white card: Word's grey name, readable at headset distance.
                color = if (selected) LocalAccent.current else Color(0xFF616161),
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun InsertTab(editor: DocumentEditor, toggle: (Picker) -> Unit, picker: Picker, onInsertImage: () -> Unit, onInsertLink: () -> Unit,
                      onInsertEquation: () -> Unit, onBookmarks: () -> Unit, onDateTime: () -> Unit) {
    // Word's Pages group: Cover Page, Blank Page and Page Break, stacked small.
    Group(stringResource(R.string.group_pages)) {
        RibbonStack {
            RibbonSmallButton("ic_cover_page", stringResource(R.string.cover_page), { toggle(Picker.COVERPAGE) }, selected = picker == Picker.COVERPAGE, dropdown = true, compactAt = 2)
            RibbonSmallButton("ic_blank_page", stringResource(R.string.blank_page), { editor.insertBlankPage() }, compactAt = 2)
            RibbonSmallButton("⤓", stringResource(R.string.page_break), { editor.insertPageBreak() }, compactAt = 2)
        }
    }
    Group(stringResource(R.string.table)) {
        RibbonButton("▦", stringResource(R.string.table), { toggle(Picker.TABLE) }, selected = picker == Picker.TABLE, dropdown = true)
    }
    Group(stringResource(R.string.illustrations)) {
        RibbonButton("🖼", stringResource(R.string.picture), onInsertImage)
        RibbonButton("⬛", stringResource(R.string.shapes_insert), { toggle(Picker.SHAPES) }, selected = picker == Picker.SHAPES, dropdown = true)
        RibbonButton("📊", stringResource(R.string.chart_label), editor::insertChart, selected = editor.selectedChart != null)
    }
    Group(stringResource(R.string.link)) {
        RibbonButton("🔗", stringResource(R.string.link), onInsertLink)
        RibbonButton("bookmark", stringResource(R.string.bookmark), onBookmarks)
    }
    Group(stringResource(R.string.header_footer)) {
        // Word's Header, Footer and Page Number galleries.
        RibbonButton("x_header", stringResource(R.string.hf_header), { toggle(Picker.HEADER) }, selected = picker == Picker.HEADER, dropdown = true)
        RibbonButton("x_footer", stringResource(R.string.hf_footer), { toggle(Picker.FOOTER) }, selected = picker == Picker.FOOTER, dropdown = true)
        RibbonButton("x_pagenumber", stringResource(R.string.page_number), { toggle(Picker.PAGENUMBERS) }, selected = picker == Picker.PAGENUMBERS, dropdown = true)
        // The two switches stacked small, as on Word's Header & Footer tab; icons alone when the window is narrow.
        RibbonStack {
            RibbonSmallButton("①②", stringResource(R.string.different_odd_even), editor::toggleOddEvenHeaders, selected = editor.document.differentOddEven, compactAt = 1)
            RibbonSmallButton("①", stringResource(R.string.different_first_page), editor::toggleFirstPageHeader, selected = editor.document.differentFirstPage, compactAt = 1)
        }
    }
    Group(stringResource(R.string.text)) {
        // Word's Text group: Text Box, and the smaller commands stacked beside it.
        RibbonButton("文本框", stringResource(R.string.text_box), editor::insertTextBox, selected = editor.activeInTextBox)
        RibbonStack {
            RibbonSmallButton("─", stringResource(R.string.horizontal_line), { editor.insertHorizontalLine() }, compactAt = 2)
            // Word's Drop Cap: the caret's paragraph's first letter set large.
            RibbonSmallButton("ic_drop_cap", stringResource(R.string.drop_cap), { toggle(Picker.DROPCAP) }, selected = picker == Picker.DROPCAP || editor.dropCap != null,
                dropdown = true, enabled = editor.canDropCap || editor.dropCap != null, compactAt = 2)
            RibbonSmallButton("📅", stringResource(R.string.date_and_time), onDateTime, compactAt = 2)
        }
    }
    Group(stringResource(R.string.symbol)) {
        // Word's Equation: a new one, or the one picked / at the caret to edit.
        RibbonButton("π", stringResource(R.string.equation), onInsertEquation, selected = editor.selectedEquation != null || editor.inlineMathAtCaret != null)
        RibbonButton("Ω", stringResource(R.string.symbol), { toggle(Picker.SYMBOL) }, selected = picker == Picker.SYMBOL, dropdown = true)
    }
}

/**
 * The Table tab (Word's Table Design and Layout in one), shown while the caret is in a table: styles,
 * shading and borders, rows and columns, merging, column widths and the repeated header row.
 */
@Composable
private fun TableDesignTab(editor: DocumentEditor, toggle: (Picker) -> Unit, picker: Picker) {
    // Word's Table Design: the Table Styles gallery in the ribbon (its first looks, the rest under More), Shading ▾,
    // and Borders ▾.
    Group(stringResource(R.string.group_table_styles)) {
        com.therealityteam.aeropages.editor.TableLook.GALLERY.take(4).forEach { look -> TableLookTile(look) { editor.applyTableLook(look) } }
        RibbonMiniButton("", stringResource(R.string.more_table_styles), { toggle(Picker.TABLESTYLE) }, selected = picker == Picker.TABLESTYLE, dropdown = true)
    }
    Group(stringResource(R.string.borders)) {
        RibbonButton("填充", stringResource(R.string.shading), { toggle(Picker.CELLSHADE) }, selected = picker == Picker.CELLSHADE, dropdown = true)
        RibbonButton("x_bdall", stringResource(R.string.borders), { toggle(Picker.CELLBORDER) }, selected = picker == Picker.CELLBORDER, dropdown = true)
    }
}

/** A table style in the ribbon's gallery: the look drawn as a little table on white, a tap applies it. */
@Composable
private fun TableLookTile(look: com.therealityteam.aeropages.editor.TableLook, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 64.dp, height = 46.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)
            .border(1.dp, PicoTheme.colorScheme.dividerLine, RoundedCornerShape(6.dp))
            .press(onClickLabel = stringResource(R.string.table_styles), onClick = onClick).padding(5.dp),
    ) { androidx.compose.foundation.Canvas(Modifier.matchParentSize()) { drawTableLook(look) } }
}

/**
 * Word's Table Layout: Rows & Columns (Delete ▾, Insert Above large, the other inserts stacked), Merge, Cell Size and
 * Data (Sort, Repeat Header Rows, Convert to Text, Formula).
 */
@Composable
private fun TableLayoutTab(editor: DocumentEditor, onStatus: (String) -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    Group(stringResource(R.string.group_rows_columns)) {
        Box {
            RibbonButton("ic_delete", stringResource(R.string.delete_menu), { deleting = !deleting }, selected = deleting, dropdown = true)
            if (deleting) AnchoredFlyout(onDismiss = { deleting = false }) {
                FlyoutMenu(width = 220.dp) {
                    MenuCommand(stringResource(R.string.delete_column), { editor.deleteTableColumn(); deleting = false }, icon = "ic_delete_col")
                    MenuCommand(stringResource(R.string.delete_row), { editor.deleteTableRow(); deleting = false }, icon = "ic_delete_row")
                    MenuCommand(stringResource(R.string.delete_table), { editor.deleteTable(); deleting = false }, icon = "ic_table")
                }
            }
        }
        RibbonButton("上方插入", stringResource(R.string.insert_row_above), { editor.insertTableRow(above = true) })
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonSmallButton("下方插入", stringResource(R.string.insert_row_below), { editor.insertTableRow(above = false) })
            RibbonSmallButton("插入列", stringResource(R.string.insert_column_left), { editor.insertTableColumn(left = true) })
            RibbonSmallButton("x_insright", stringResource(R.string.insert_column_right), { editor.insertTableColumn(left = false) })
        }
    }
    Group(stringResource(R.string.group_merge)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonSmallButton("x_merge", stringResource(R.string.merge_right), editor::mergeCellRight)
            RibbonSmallButton("x_mergedown", stringResource(R.string.merge_down), editor::mergeCellDown)
            RibbonSmallButton("x_split", stringResource(R.string.split_cells), editor::splitMergedCell)
        }
    }
    Group(stringResource(R.string.group_cell_size)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonSmallButton("宽-", stringResource(R.string.narrow_column), { editor.adjustActiveColumnWidth(-0.3f) })
            RibbonSmallButton("宽+", stringResource(R.string.wide_column), { editor.adjustActiveColumnWidth(+0.3f) })
            RibbonSmallButton("x_distribute", stringResource(R.string.distribute_columns), editor::distributeTableColumns)
        }
    }
    // Data as Word's: Sort the rows, Convert to Text.
    var sorting by remember { mutableStateOf(devDialogProp() == "tablesort") }
    var toText by remember { mutableStateOf(devDialogProp() == "tabletotext") }
    var formula by remember { mutableStateOf(devDialogProp() == "tableformula") }
    Group(stringResource(R.string.group_table_data)) {
        RibbonButton("x_sortasc", stringResource(R.string.table_sort), { sorting = true })
        RibbonButton("x_headers", stringResource(R.string.repeat_header_rows), editor::toggleHeaderRow, selected = editor.activeRowIsHeader)
        RibbonButton("x_totext", stringResource(R.string.table_to_text), { toText = true })
        RibbonButton("x_fx", stringResource(R.string.formula_title), { formula = true })
    }
    if (formula) TableFormulaDialog(editor) { formula = false }
    if (sorting) TableSortDialog(editor, { sorting = false }, onStatus)
    if (toText) TableToTextDialog(editor) { toText = false }
}

/** The table style gallery: each look drawn as a little table in its colours and rules. */
@Composable
private fun TableLookGallery(editor: DocumentEditor, onPicked: () -> Unit) {
    val names = listOf(R.string.table_look_grid, R.string.table_look_plain, R.string.table_look_three_line, R.string.table_look_rows,
        R.string.table_look_blue, R.string.table_look_green, R.string.table_look_orange, R.string.table_look_navy)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        com.therealityteam.aeropages.editor.TableLook.GALLERY.forEachIndexed { i, look ->
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current) { editor.applyTableLook(look); onPicked() }.controllerHapticFeedback(interactionSource = interaction)
                    .padding(6.dp),
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(width = 76.dp, height = 52.dp).background(Color.White)) { drawTableLook(look) }
                Text(stringResource(names[i]), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** A four-row, three-column table drawn in [look]: header fill, bands, rules, and bars for its text. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTableLook(look: com.therealityteam.aeropages.editor.TableLook) {
    val rows = 4; val cols = 3
    val w = size.width; val h = size.height
    val rh = h / rows; val cw = w / cols
    val line = Color(look.line.color ?: 0xFF404040)
    val thin = 1.dp.toPx()
    fun at(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
    for (r in 0 until rows) {
        val fill = if (r == 0) look.header else if (look.band != null && r % 2 == 1) look.band else null
        if (fill != null) drawRect(Color(fill), at(0f, r * rh), androidx.compose.ui.geometry.Size(w, rh))
        val text = if (r == 0) Color(look.headerText ?: 0xFF404040) else Color(0xFFB0B4BA)
        for (c in 0 until cols) drawRect(text, at(c * cw + cw * 0.18f, r * rh + rh * 0.42f), androidx.compose.ui.geometry.Size(cw * (if (r == 0) 0.5f else 0.6f), rh * 0.18f))
    }
    fun hl(y: Float, width: Float = thin) = drawLine(line, at(0f, y), at(w, y), width)
    fun vl(x: Float) = drawLine(line, at(x, 0f), at(x, h), thin)
    when (look.rules) {
        com.therealityteam.aeropages.editor.TableLook.Rules.GRID -> {
            for (r in 0..rows) hl((r * rh).coerceIn(thin / 2, h - thin / 2))
            for (c in 0..cols) vl((c * cw).coerceIn(thin / 2, w - thin / 2))
        }
        com.therealityteam.aeropages.editor.TableLook.Rules.HORIZONTAL -> for (r in 1..rows) hl((r * rh).coerceAtMost(h - thin / 2))
        com.therealityteam.aeropages.editor.TableLook.Rules.THREE_LINE -> { hl(thin, thin * 2); hl(rh); hl(h - thin, thin * 2) }
        com.therealityteam.aeropages.editor.TableLook.Rules.NONE -> Unit
    }
}

@Composable
private fun DesignTab(editor: DocumentEditor, toggle: (Picker) -> Unit, picker: Picker) {
    // Page Borders opens Word's dialog (dev hook `pageborders`, with the Design tab open).
    var pageBordersDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "pageborders") }
    if (pageBordersDialog) BordersAndShadingDialog(editor, BordersTab.PAGE) { pageBordersDialog = false }
    Group(stringResource(R.string.document_formatting)) {
        RibbonButton("Aa", stringResource(R.string.style_set), { toggle(Picker.STYLESET) }, selected = picker == Picker.STYLESET, dropdown = true)
        RibbonButton("🎨", stringResource(R.string.colors), { toggle(Picker.THEMECOLOR) }, selected = picker == Picker.THEMECOLOR, dropdown = true)
        RibbonButton(stringResource(R.string.fonts_icon), stringResource(R.string.font), { toggle(Picker.THEMEFONT) }, selected = picker == Picker.THEMEFONT, dropdown = true)
        RibbonButton("↕", stringResource(R.string.paragraph_spacing), { toggle(Picker.DOCSPACING) }, selected = picker == Picker.DOCSPACING, dropdown = true)
    }
    Group(stringResource(R.string.page_background)) {
        RibbonButton("💧", stringResource(R.string.watermark), { toggle(Picker.WATERMARK) }, selected = picker == Picker.WATERMARK, dropdown = true)
        RibbonButton("▥", stringResource(R.string.page_color), { toggle(Picker.PAGECOLOR) }, selected = picker == Picker.PAGECOLOR, dropdown = true)
        RibbonButton("▢", stringResource(R.string.page_borders), { pageBordersDialog = true }, selected = editor.document.pageBorders != null)
    }
}

private val STYLE_SETS get() = listOf(
    Triple(Strings.get(R.string.default_option), Triple(0xFF2E74B5, "Calibri", "Calibri Light"), Unit),
    Triple(Strings.get(R.string.style_black), Triple(0xFF000000, "Arial", "Arial"), Unit),
    Triple(Strings.get(R.string.style_elegant), Triple(0xFF7B4F9D, "Georgia", "Georgia"), Unit),
    Triple(Strings.get(R.string.style_classic), Triple(0xFF1F6E43, "Times New Roman", "Cambria"), Unit),
    Triple(Strings.get(R.string.style_modern), Triple(0xFFC0392B, "Verdana", "Verdana"), Unit),
)

@Composable
private fun StyleSetPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    // Word's Style Sets gallery: each set as a little page — its title, heading and body in their fonts and colour.
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        STYLE_SETS.forEach { (name, theme, _) ->
            val (color, body, heading) = theme
            GalleryTile(name, { editor.applyStyleSet(color, body, heading); onPicked() }) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(com.therealityteam.aeropages.model.ParagraphStyle.TITLE.display, fontSize = 13.sp, fontFamily = TextStyling.familyOf(heading), color = Aero.ink, maxLines = 1)
                    Text(com.therealityteam.aeropages.model.ParagraphStyle.HEADING1.display, fontSize = 9.sp, fontFamily = TextStyling.familyOf(heading), color = Color(color), maxLines = 1)
                    repeat(3) { i -> Box(Modifier.fillMaxWidth(if (i == 2) 0.6f else 1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33000000))) }
                }
            }
        }
    }
}

/** A gallery's tile (Word's Style Sets, Watermark…): a white card holding [content], its [name] under it. */
@Composable
private fun GalleryTile(name: String, onClick: () -> Unit, width: androidx.compose.ui.unit.Dp = 96.dp, height: androidx.compose.ui.unit.Dp = 72.dp, content: @Composable () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(width)) {
        Box(
            Modifier.size(width, height).clip(RoundedCornerShape(8.dp)).background(Color.White)
                .border(1.dp, Aero.barEdge, RoundedCornerShape(8.dp))
                .semantics { contentDescription = name }
                .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction),
        ) { GazeFill(RoundedCornerShape(8.dp)); content() }
        Text(name, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
}

@Composable
private fun ThemeColorPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.label_theme_colors_headings), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0xFF2E74B5, 0xFF000000, 0xFF7B4F9D, 0xFF1F6E43, 0xFFC0392B, 0xFFD68910, 0xFF16A085).forEach { c ->
                ColorSwatch(c, { editor.applyThemeColor(c); onPicked() })
            }
        }
    }
}

@Composable
private fun ThemeFontPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    val pairs = listOf(
        "Calibri / Cambria" to ("Calibri" to "Cambria"),
        "Arial" to ("Arial" to "Arial"),
        "Times New Roman" to ("Times New Roman" to "Times New Roman"),
        "Georgia" to ("Georgia" to "Georgia"),
        "Verdana" to ("Verdana" to "Verdana"),
    )
    // Word's Fonts menu: each pair with its "Aa" in the heading font, the heading and body fonts named.
    FlyoutMenu(width = 300.dp) {
        MenuHeading(stringResource(R.string.theme_fonts))
        pairs.forEach { (name, fonts) ->
            MenuCommand(fonts.second, { editor.applyThemeFonts(fonts.first, fonts.second); onPicked() }, detail = fonts.first,
                leading = { Text("Aa", fontSize = 13.sp, fontFamily = TextStyling.familyOf(fonts.second), color = Aero.ink, maxLines = 1, softWrap = false) })
        }
    }
}

@Composable
private fun DocSpacingPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    // Word's Paragraph Spacing menu: each preset with what it sets.
    FlyoutMenu(width = 320.dp) {
        listOf(Triple(R.string.spacing_compact, 0f, 1.0f), Triple(R.string.default_option, 8f, 1.15f),
            Triple(R.string.spacing_relaxed, 12f, 1.5f), Triple(R.string.spacing_double, 14f, 2.0f)).forEach { (name, after, line) ->
            MenuCommand(stringResource(name), { editor.applyDocSpacing(0f, after, line); onPicked() }, icon = "ic_line_spacing",
                detail = stringResource(R.string.spacing_preset_detail, charCount(after), "$line"))
        }
    }
}

@Composable
private fun WatermarkPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    // Word's Watermark gallery: pages with the word across them in faint grey; Remove Watermark under it.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(stringResource(R.string.watermark_confidential), stringResource(R.string.watermark_draft), stringResource(R.string.watermark_sample), stringResource(R.string.watermark_urgent), stringResource(R.string.watermark_do_not_copy)).forEach { wm ->
                GalleryTile(wm, { editor.setWatermark(wm); onPicked() }, width = 84.dp, height = 108.dp) {
                    Box(Modifier.size(84.dp, 108.dp), contentAlignment = Alignment.Center) {
                        Text(wm, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Color(0x80000000), maxLines = 1, softWrap = false,
                            modifier = Modifier.graphicsLayer(rotationZ = -45f))
                    }
                }
            }
        }
        MenuDivider()
        FlyoutMenu(width = 280.dp) { MenuCommand(stringResource(R.string.remove_watermark), { editor.setWatermark(null); onPicked() }, icon = "ic_delete") }
    }
}

@Composable
private fun PageColorPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    ColorPalette(editor.document.pageColor, { c -> editor.setPageColor(c); onPicked() }, noneLabel = stringResource(R.string.no_color))
}

@Composable
private fun LayoutTab(editor: DocumentEditor, toggle: (Picker) -> Unit, picker: Picker, onPageSetup: (PageSetupTab) -> Unit, onParagraphDialog: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    // Page setup applies to the section the caret is in, so a landscape section can sit inside a
    // portrait document (Word's 分节符 behaviour); each gallery shows its choices drawn and measured.
    val (sectionIndex, sectionCount) = editor.sectionPosition()
    // Page Setup's launcher opens the Page Setup dialog, as Word's does.
    Group(stringResource(R.string.page_setup), launcher = { onPageSetup(PageSetupTab.MARGINS) }) {
        RibbonButton("x_margins", stringResource(R.string.margins), { toggle(Picker.MARGINS) }, selected = picker == Picker.MARGINS, dropdown = true)
        RibbonButton("x_orientation", stringResource(R.string.orientation), { toggle(Picker.ORIENTATION) }, selected = picker == Picker.ORIENTATION, dropdown = true)
        RibbonButton("x_paper", stringResource(R.string.size), { toggle(Picker.PAPER) }, selected = picker == Picker.PAPER, dropdown = true)
        RibbonButton("x_pagecolumns", stringResource(R.string.page_columns), { toggle(Picker.COLUMNS) }, selected = picker == Picker.COLUMNS, dropdown = true)
        // Word's arrangement: Breaks and Line Numbers stacked beside the four large buttons.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("x_breaks", stringResource(R.string.breaks), { toggle(Picker.BREAKS) }, selected = picker == Picker.BREAKS, dropdown = true, compactAt = 2)
            RibbonSmallButton("ic_line_numbers", stringResource(R.string.line_numbers), { toggle(Picker.LINENUMBERS) },
                selected = picker == Picker.LINENUMBERS || editor.lineNumbering != null, dropdown = true, compactAt = 2)
        }
    }
    // Word's Layout ▸ Paragraph: the selected paragraphs' left and right indents and their space before and after; its
    // launcher opens the Paragraph dialog (Tabs… is there, as in Word).
    Group(stringResource(R.string.paragraph), launcher = onParagraphDialog) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DialogLabel(stringResource(R.string.indentation))
            IndentStepper(stringResource(R.string.indent_left), editor.leftIndentChars, stringResource(R.string.decrease_left_indent), stringResource(R.string.increase_left_indent)) { editor.stepIndent(it) }
            IndentStepper(stringResource(R.string.indent_right), editor.rightIndentChars, stringResource(R.string.decrease_right_indent), stringResource(R.string.increase_right_indent)) { editor.stepIndent(it, right = true) }
        }
        Spacer(Modifier.width(12.dp))
        val f = editor.activeParagraph.format
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DialogLabel(stringResource(R.string.spacing_group))
            SpacingStepper(stringResource(R.string.spacing_before), f.spaceBeforePt, stringResource(R.string.less_space_before), stringResource(R.string.more_space_before)) { editor.stepSpacing(it, 0f) }
            SpacingStepper(stringResource(R.string.spacing_after), f.spaceAfterPt, stringResource(R.string.less_space_after), stringResource(R.string.more_space_after)) { editor.stepSpacing(0f, it) }
        }
    }
    Group(stringResource(R.string.sections)) {
        Text(
            stringResource(R.string.label_section_of, sectionIndex, sectionCount),
            style = AeroType.caption,
            color = PicoTheme.colorScheme.labelSecondary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
        )
    }
}

/** [label], a − button, the space in points and a + button; each press moves it 6 pt (Word's step). */
@Composable
private fun SpacingStepper(label: String, pt: Float, lessLabel: String, moreLabel: String, onStep: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
        SpinBox(stringResource(R.string.unit_pt, charCount(pt)), { d -> onStep(6f * d) }, description = label, canLess = pt > 0f, valueWidth = 52.dp,
            lessLabel = lessLabel, moreLabel = moreLabel)
    }
}

@Composable
private fun ReferenceTab(
    editor: DocumentEditor,
    footnotesOpen: Boolean,
    onToggleFootnotes: () -> Unit,
    sourcesOpen: Boolean,
    onToggleSources: () -> Unit,
    onInsertCaption: () -> Unit,
    onCrossReference: () -> Unit,
    onFieldsUpdated: (Int) -> Unit,
    onTableOfFigures: () -> Unit,
    indexOpen: Boolean,
    onMarkEntry: () -> Unit,
    onInsertIndex: () -> Unit,
) {
    // Table of Contents where the caret is (one per document), and Remove beside it once there is one.
    Group(stringResource(R.string.table_of_contents)) {
        RibbonButton("☰", stringResource(R.string.table_of_contents), editor::insertToc)
        RibbonSmallButton("🗑", stringResource(R.string.remove_toc), editor::removeToc, enabled = editor.hasToc)
    }
    Group(stringResource(R.string.footnote)) {
        RibbonButton("ab¹", stringResource(R.string.insert_footnote), editor::insertFootnote)
        RibbonButton("abⁱ", stringResource(R.string.insert_endnote), editor::insertEndnote)
        RibbonButton("▤", stringResource(R.string.footnote_pane), onToggleFootnotes, selected = footnotesOpen)
    }
    Group(stringResource(R.string.group_citations_bibliography)) {
        RibbonButton("x_sources", stringResource(R.string.manage_sources_2), onToggleSources, selected = sourcesOpen)
        RibbonButton("≣", stringResource(R.string.bibliography_2), editor::insertBibliography)
    }
    // Captions: Insert Caption, Cross-reference, Update Fields (Word's Captions group, its F9 beside them).
    Group(stringResource(R.string.group_captions)) {
        // Word's Captions group: Insert Caption, and the rest stacked small beside it.
        RibbonButton("x_caption", stringResource(R.string.insert_caption), onInsertCaption)
        RibbonStack {
            RibbonSmallButton("x_tof", stringResource(R.string.table_of_figures_short), onTableOfFigures, tooltip = stringResource(R.string.insert_table_of_figures), compactAt = 2)
            RibbonSmallButton("x_xref", stringResource(R.string.cross_reference), onCrossReference, compactAt = 2)
            RibbonSmallButton("x_updatefields", stringResource(R.string.update_fields), { onFieldsUpdated(editor.updateFields()) }, compactAt = 2)
        }
    }
    // Word's Index group: Mark Entry (the pane that marks the selection), Insert Index, and Remove beside it once there is one.
    Group(stringResource(R.string.group_index)) {
        RibbonButton("ic_mark_entry", stringResource(R.string.mark_entry), onMarkEntry, selected = indexOpen)
        RibbonStack {
            RibbonSmallButton("ic_insert_index", stringResource(R.string.insert_index), onInsertIndex, compactAt = 2)
            RibbonSmallButton("ic_delete", stringResource(R.string.remove_index), editor::removeIndex, enabled = editor.indexBlock != null, compactAt = 2)
        }
    }
}

@Composable
private fun ReviewTab(
    editor: DocumentEditor, commentsOpen: Boolean, onToggleComments: () -> Unit, onAddComment: () -> Unit, onProofing: () -> Unit,
    spatialCommentsOpen: Boolean, onToggleSpatialComments: () -> Unit, picker: Picker, toggle: (Picker) -> Unit, onStatus: (String) -> Unit,
    onWordCount: () -> Unit,
    onCompare: () -> Unit,
) {
    // Proofing as Word's: spelling, and the word count.
    Group(stringResource(R.string.group_proofing)) {
        RibbonButton("拼写", stringResource(R.string.spelling_check), onProofing)
        RibbonButton("字数", stringResource(R.string.title_word_count), onWordCount)
    }
    // Word's Accessibility group: Check Accessibility lists what a screen reader would stumble on.
    var checking by remember { mutableStateOf(devDialogProp() == "accessibility") }
    Group(stringResource(R.string.group_accessibility)) {
        RibbonButton("ic_accessibility", stringResource(R.string.check_accessibility), { checking = true })
    }
    if (checking) PagesAccessibility(editor) { checking = false }
    if (showChineseConversion) Group(stringResource(R.string.zh_group)) {
        ChineseConversionButtons({ t, v -> editor.convertChinese(t, v) }, onStatus)
    }
    // Comments as Word's group: New, Delete ▾, Previous / Next, the pane, the comments floating in space.
    Group(stringResource(R.string.comment)) {
        RibbonButton("💬", stringResource(R.string.new_comment), onAddComment)
        RibbonButton("🗑", stringResource(R.string.delete), { toggle(Picker.DELCOMMENT) }, selected = picker == Picker.DELCOMMENT, dropdown = true,
            enabled = editor.document.comments.isNotEmpty())
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val noMore = stringResource(R.string.status_no_more_comments)
            RibbonSmallButton("▲", stringResource(R.string.previous_comment), { if (!editor.goToComment(back = true)) onStatus(noMore) })
            RibbonSmallButton("▼", stringResource(R.string.next_comment), { if (!editor.goToComment()) onStatus(noMore) })
        }
        RibbonButton("▤", stringResource(R.string.comment_pane), onToggleComments, selected = commentsOpen)
        RibbonButton("x_spatialnote", stringResource(R.string.spatial_comments), onToggleSpatialComments, selected = spatialCommentsOpen)
    }
    // Tracking as Word's: Track Changes, and beside it how the changes show (Display for Review ▾) and which (Show Markup ▾).
    // Lock Tracking (Word's Track Changes ▾ ▸ Lock Tracking) keeps it on: Track Changes and Accept / Reject dim while it holds.
    val locked = editor.trackingLocked
    val lockNote = stringResource(R.string.tracking_locked_note)
    val lockPassword = stringResource(R.string.tracking_locked_password)
    Group(stringResource(R.string.track_changes)) {
        RibbonButton("✎", stringResource(R.string.track_changes), editor::toggleTrackChanges, selected = editor.trackChanges, enabled = !locked)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonSmallButton("x_markup", markupName(editor.markup), { toggle(Picker.MARKUP) }, selected = picker == Picker.MARKUP, dropdown = true, compactAt = 2)
            RibbonSmallButton("ic_eye", stringResource(R.string.show_markup), { toggle(Picker.SHOWMARKUP) }, selected = picker == Picker.SHOWMARKUP, dropdown = true, compactAt = 2)
            RibbonSmallButton("x_lock", stringResource(R.string.lock_tracking), {
                if (!locked) { editor.lockTracking(); onStatus(lockNote) } else if (!editor.unlockTracking()) onStatus(lockPassword)
            }, selected = locked, compactAt = 2)
        }
    }
    // Changes: Accept ▾ / Reject ▾ (this one and on to the next, this one, all), Previous / Next.
    Group(stringResource(R.string.group_changes)) {
        RibbonButton("x_acceptall", stringResource(R.string.accept), { toggle(Picker.ACCEPT) }, selected = picker == Picker.ACCEPT, dropdown = true, enabled = !locked)
        RibbonButton("x_rejectall", stringResource(R.string.reject), { toggle(Picker.REJECT) }, selected = picker == Picker.REJECT, dropdown = true, enabled = !locked)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val noMore = stringResource(R.string.status_no_more_changes)
            RibbonSmallButton("▲", stringResource(R.string.previous_change), { if (!editor.goToChange(back = true)) onStatus(noMore) })
            RibbonSmallButton("▼", stringResource(R.string.next_change), { if (!editor.goToChange()) onStatus(noMore) })
        }
    }
    // Compare: two versions of a document, their differences as tracked changes in a document of their own.
    Group(stringResource(R.string.compare)) {
        RibbonButton("ic_compare", stringResource(R.string.compare), onCompare)
    }
}

/** Display for Review's four ways, by name. */
@Composable
private fun markupName(m: DocumentEditor.Markup): String = stringResource(when (m) {
    DocumentEditor.Markup.ALL -> R.string.markup_all
    DocumentEditor.Markup.SIMPLE -> R.string.markup_simple
    DocumentEditor.Markup.NONE -> R.string.markup_none
    DocumentEditor.Markup.ORIGINAL -> R.string.markup_original
})

/** Display for Review ▾: each way the changes can show, with what it shows. */
@Composable
private fun MarkupPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DocumentEditor.Markup.entries.forEach { m ->
            val desc = stringResource(when (m) {
                DocumentEditor.Markup.ALL -> R.string.markup_all_desc; DocumentEditor.Markup.SIMPLE -> R.string.markup_simple_desc
                DocumentEditor.Markup.NONE -> R.string.markup_none_desc; DocumentEditor.Markup.ORIGINAL -> R.string.markup_original_desc
            })
            val on = editor.markup == m
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.width(170.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) LocalAccent.current.copy(alpha = 0.16f) else PicoTheme.colorScheme.fillTertiary)
                    .press { editor.showMarkup(m); onPicked() }.padding(10.dp),
            ) {
                Text(markupName(m), style = AeroType.label, color = if (on) LocalAccent.current else PicoTheme.colorScheme.labelPrimary)
                MarkupSample(m)
                Text(desc, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
        }
    }
}

/** "a quick brown fox" as the way [m] shows a change: "quick" deleted, "brown" inserted. */
@Composable
private fun MarkupSample(m: DocumentEditor.Markup) {
    val red = Color(0xFFCC0000)
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append("a ")
        when (m) {
            DocumentEditor.Markup.ALL -> {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = red, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)); append("quick"); pop()
                append(" ")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = red, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)); append("brown"); pop()
            }
            DocumentEditor.Markup.SIMPLE, DocumentEditor.Markup.NONE -> append("brown")
            DocumentEditor.Markup.ORIGINAL -> append("quick")
        }
        append(" fox")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Box(Modifier.width(2.dp).height(16.dp).background(if (m == DocumentEditor.Markup.SIMPLE) red else Color.Transparent))
        Text(text, fontSize = 13.sp, color = Color(0xFF202020))
    }
}

/**
 * Show Markup ▾ (Word's): which changes and comments show — Comments, Insertions and Deletions, Formatting — and whose
 * (Specific People: All Reviewers, each one). Ticks toggle in place, the menu staying open for the next.
 */
@Composable
private fun ShowMarkupPanel(editor: DocumentEditor) {
    val people = editor.reviewersInDocument()
    val unknown = stringResource(R.string.markup_unattributed)
    FlyoutMenu(width = 300.dp) {
        MenuToggle(stringResource(R.string.markup_comments), editor.showComments, { editor.setShowMarkup(comments = !editor.showComments) })
        MenuToggle(stringResource(R.string.markup_ins_del), editor.showInsertionsDeletions, { editor.setShowMarkup(insertionsDeletions = !editor.showInsertionsDeletions) })
        MenuToggle(stringResource(R.string.markup_formatting), editor.showFormatting, { editor.setShowMarkup(formatting = !editor.showFormatting) })
        MenuDivider()
        MenuHeading(stringResource(R.string.markup_people))
        MenuToggle(stringResource(R.string.markup_all_reviewers), editor.hiddenReviewers.isEmpty(), {
            editor.setShowMarkup(hidden = if (editor.hiddenReviewers.isEmpty()) people.toSet() else emptySet())
        }, enabled = people.isNotEmpty())
        people.forEach { who ->
            val hidden = who in editor.hiddenReviewers
            MenuToggle(who.ifEmpty { unknown }, !hidden, {
                editor.setShowMarkup(hidden = if (hidden) editor.hiddenReviewers - who else editor.hiddenReviewers + who)
            }, leading = { Avatar(who.ifEmpty { "?" }, size = 22.dp) })
        }
    }
}

/** Accept ▾ / Reject ▾: the change at the caret and on to the next, that change alone, every change (those shown, when Show Markup leaves some out). */
@Composable
private fun ResolvePanel(editor: DocumentEditor, accept: Boolean, onStatus: (String) -> Unit, onPicked: () -> Unit) {
    val none = stringResource(R.string.status_no_change_here)
    val icon = if (accept) "x_acceptall" else "x_rejectall"
    FlyoutMenu {
        MenuCommand(stringResource(if (accept) R.string.accept_and_next else R.string.reject_and_next), {
            onPicked(); if (!editor.resolveAtCaret(accept)) onStatus(none)
        }, icon = icon)
        MenuCommand(stringResource(if (accept) R.string.accept_this else R.string.reject_this), {
            onPicked()
            val r = editor.changeAtCaret()
            if (r == null) onStatus(none) else if (accept) editor.acceptRevision(r) else editor.rejectRevision(r)
        }, icon = icon)
        MenuDivider()
        if (editor.filteringMarkup) MenuCommand(stringResource(if (accept) R.string.accept_all_shown else R.string.reject_all_shown), {
            onPicked(); editor.resolveShownChanges(accept)
        }, icon = icon)
        MenuCommand(stringResource(if (accept) R.string.accept_all else R.string.reject_all), {
            onPicked(); if (accept) editor.acceptAllChanges() else editor.rejectAllChanges()
        }, icon = icon)
        MenuCommand(stringResource(if (accept) R.string.accept_all_stop else R.string.reject_all_stop), {
            onPicked(); editor.resolveAllAndStopTracking(accept)
        }, icon = icon, enabled = editor.trackChanges)
    }
}

/** Delete ▾ (comments): the comment the reader is on, or every comment in the document. */
@Composable
private fun DeleteCommentPanel(editor: DocumentEditor, onStatus: (String) -> Unit, onPicked: () -> Unit) {
    val current = editor.currentComment ?: editor.commentsInOrder().firstOrNull { editor.indexOfParagraphId(it.targetId) == editor.active }
    val deleted = stringResource(R.string.status_comments_deleted, editor.document.comments.count { it.parentId == null })
    FlyoutMenu {
        MenuCommand(stringResource(R.string.delete_this_comment), { onPicked(); current?.let { editor.deleteComment(it); editor.currentComment = null } },
            icon = "ic_delete", enabled = current != null)
        MenuCommand(stringResource(R.string.delete_all_comments), { onPicked(); if (editor.deleteAllComments() > 0) onStatus(deleted) }, icon = "ic_delete")
    }
}

@Composable
private fun ViewTab(
    editor: DocumentEditor, view: PagesView, picker: Picker, toggle: (Picker) -> Unit, navOpen: Boolean, onToggleNav: () -> Unit,
    fullWidth: Boolean, onToggleLayout: () -> Unit, onShortcuts: () -> Unit, onFocus: () -> Unit,
) {
    // Views: the page as printed, or the text across the window.
    Group(stringResource(R.string.group_views)) {
        RibbonButton("x_printlayout", stringResource(R.string.view_print_layout), { if (fullWidth) onToggleLayout() }, selected = !fullWidth)
        RibbonButton("x_weblayout", stringResource(R.string.view_web_layout), { if (!fullWidth) onToggleLayout() }, selected = fullWidth)
    }
    // Immersive: Word's Focus — the document alone, without the ribbon, the status bar or the panes.
    Group(stringResource(R.string.group_immersive)) {
        RibbonButton("x_focus", stringResource(R.string.focus_mode), onFocus)
    }
    // Show: Word's ticks — the ruler and the navigation pane.
    Group(stringResource(R.string.group_show)) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            CheckOption(stringResource(R.string.ruler_toggle), view.ruler, { view.ruler = !view.ruler }, small = true)
            CheckOption(stringResource(R.string.navigation_pane), navOpen, { onToggleNav() }, small = true)
        }
    }
    // Zoom as Word's group: the menu of zooms, 100%, One Page, Multiple Pages, Page Width — fitted to the caret's section's page.
    Group(stringResource(R.string.group_zoom)) {
        val doc = editor.document
        val pg = (doc.locations().getOrNull(editor.active) as? com.therealityteam.aeropages.model.TopLoc)?.let { doc.setupAt(it.blockIndex) } ?: doc.setupAt(0)
        RibbonButton("x_zoom", stringResource(R.string.zoom), { toggle(Picker.ZOOM) }, selected = picker == Picker.ZOOM, dropdown = true)
        RibbonButton("100", stringResource(R.string.zoom_100), { view.zoomTo(1f) }, selected = !view.multiPage && view.percent == 100)
        RibbonButton("x_onepage", stringResource(R.string.view_one_page), { view.onePage(pg.widthPt, pg.heightPt) })
        RibbonButton("x_multipage", stringResource(R.string.view_multiple_pages), { view.multiplePages(pg.widthPt, pg.heightPt) }, selected = view.multiPage, enabled = !fullWidth)
        RibbonButton("x_pagewidth", stringResource(R.string.view_page_width), { view.pageWidth(pg.widthPt) }, enabled = !fullWidth)
    }
    Group(stringResource(R.string.group_window)) {
        RibbonButton("kbd_help", stringResource(R.string.keyboard_shortcuts), onShortcuts)
    }
}

// --- panels ----------------------------------------------------------------

@Composable
private fun CharSpacingPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    val now = editor.currentFormat.letterSpacingPt
    FlyoutMenu(width = 220.dp) {
        listOf(-0.5f to R.string.spacing_condensed, null to R.string.standard, 1f to R.string.spacing_expanded, 2f to R.string.spacing_wider).forEach { (pt, name) ->
            MenuCommand(stringResource(name), { editor.setLetterSpacing(pt); onPicked() }, checked = now == pt)
        }
    }
}

/** Word's Change Case: sentence-like choices, each written the way it makes the text. */
@Composable
private fun CasePanel(editor: DocumentEditor, onPicked: () -> Unit) {
    FlyoutMenu(width = 240.dp) {
        listOf(
            com.therealityteam.aeropages.editor.CaseMode.LOWER to R.string.case_lower,
            com.therealityteam.aeropages.editor.CaseMode.UPPER to R.string.case_upper,
            com.therealityteam.aeropages.editor.CaseMode.TITLE to R.string.case_title,
            com.therealityteam.aeropages.editor.CaseMode.TOGGLE to R.string.case_toggle,
        ).forEach { (mode, name) -> MenuCommand(stringResource(name), { editor.changeCase(mode); onPicked() }) }
    }
}

@Composable
private fun StylePanel(editor: DocumentEditor, onModify: () -> Unit, onCreate: () -> Unit, onPicked: () -> Unit) {
    // Word's Styles menu: every style with a sample in its look (the document's own after the built-in ones), then Create
    // a Style… and Modify Style….
    FlyoutMenu(width = 280.dp) {
        ParagraphStyle.entries.forEach { s ->
            val r = editor.resolvedStyle(s)
            MenuCommand(s.display, { editor.setStyle(s); onPicked() }, checked = editor.activeParagraph.format.style == s,
                leading = {
                    Text("Aa", fontSize = r.sizePt.coerceIn(10f, 15f).sp, color = Color(r.color ?: 0xFF1A1A1A), maxLines = 1, softWrap = false,
                        fontWeight = if (r.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                        fontStyle = if (r.italic) androidx.compose.ui.text.font.FontStyle.Italic else null, fontFamily = TextStyling.familyOf(r.fontFamily))
                })
        }
        // The document's own styles in use (Word's recommended ones; the Styles pane lists them all).
        editor.ownStyles.filter { editor.styleUses(it.id) > 0 }.forEach { d ->
            val r = editor.resolvedStyle(d.id)
            MenuCommand(d.name, { editor.setStyleId(d.id); onPicked() }, checked = editor.activeStyleId.equals(d.id, ignoreCase = true),
                leading = {
                    Text("Aa", fontSize = r.sizePt.coerceIn(10f, 15f).sp, color = Color(r.color ?: 0xFF1A1A1A), maxLines = 1, softWrap = false,
                        fontWeight = if (r.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                        fontStyle = if (r.italic) androidx.compose.ui.text.font.FontStyle.Italic else null, fontFamily = TextStyling.familyOf(r.fontFamily))
                })
        }
        MenuDivider()
        MenuCommand(stringResource(R.string.create_style) + "…", { onPicked(); onCreate() }, icon = "ic_plus")
        MenuCommand(stringResource(R.string.modify_style) + "…", onModify, icon = "ic_edit")
    }
}

/** The font list: every family, the current one ticked. */
@Composable
private fun FontPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    FlyoutMenu(width = 260.dp, maxHeight = 440.dp) {
        MenuHeading(stringResource(R.string.all_fonts))
        TextStyling.FONT_FAMILIES.forEach { name ->
            MenuCommand(name, { editor.setFontFamily(name); onPicked() }, checked = (editor.currentFormat.fontFamily ?: "Calibri") == name)
        }
    }
}

/** The size list, narrow as Office's, the current size ticked. */
@Composable
private fun SizePanel(editor: DocumentEditor, onPicked: () -> Unit) {
    FlyoutMenu(width = 140.dp, maxHeight = 440.dp) {
        TextStyling.FONT_SIZES.forEach { sz ->
            MenuCommand(sz.toInt().toString(), { editor.setFontSize(sz); onPicked() }, checked = currentSize(editor) == sz)
        }
    }
}

/** Font Color ▾ / Text Highlight Color ▾: Office's palette (Automatic / No Color first). */
@Composable
private fun ColorPanel(editor: DocumentEditor, highlight: Boolean, onPicked: () -> Unit) {
    val f = editor.currentFormat
    ColorPalette(
        current = if (highlight) f.highlight else f.color,
        onPick = { c -> if (highlight) editor.setHighlight(c) else editor.setColor(c); onPicked() },
        noneLabel = stringResource(if (highlight) R.string.no_color else R.string.automatic),
        highlight = highlight,
    )
}

/**
 * Line and Paragraph Spacing ▾ as Word's menu: the line spacings, the paragraph's line and page break
 * options, and its indentation (the menu stays open for the toggles and steps).
 */
@Composable
private fun SpacingPanel(editor: DocumentEditor, onPicked: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val f = editor.activeParagraph.format
    FlyoutMenu(width = 340.dp, maxHeight = 600.dp) {
        listOf(1.0f, 1.15f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { m ->
            MenuCommand(m.toString(), { editor.setLineSpacing(m); onPicked() }, checked = f.lineSpacing == m && f.lineHeightPt == null)
        }
        MenuDivider()
        MenuHeading(stringResource(R.string.line_and_page_breaks))
        MenuCommand(stringResource(R.string.keep_with_next), editor::toggleKeepWithNext, checked = editor.keepsWithNext)
        MenuCommand(stringResource(R.string.keep_lines_together), editor::toggleKeepTogether, checked = f.keepTogether)
        MenuCommand(stringResource(R.string.page_break_before), editor::togglePageBreakBefore, checked = f.pageBreakBefore)
        MenuCommand(stringResource(R.string.widow_control), editor::toggleWidowControl, checked = f.widowControl)
        MenuDivider()
        // Word's Paragraph ▸ Indentation, in characters (字符) as Chinese Word counts them.
        MenuHeading(stringResource(R.string.indentation))
        val first = editor.firstLineIndentChars
        val twoChars = stringResource(R.string.indent_chars, charCount(2f))
        MenuCommand(stringResource(R.string.none), { editor.setFirstLineIndent(0f) }, checked = abs(first) < 0.05f)
        MenuCommand("${stringResource(R.string.first_line_indent)} $twoChars", { editor.setFirstLineIndent(2f) }, checked = abs(first - 2f) < 0.05f)
        MenuCommand("${stringResource(R.string.hanging_indent)} $twoChars", { editor.setFirstLineIndent(-2f) }, checked = abs(first + 2f) < 0.05f)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IndentStepper(stringResource(R.string.indent_left), editor.leftIndentChars, stringResource(R.string.decrease_indent), stringResource(R.string.increase_indent)) { editor.stepIndent(it) }
            IndentStepper(stringResource(R.string.indent_right), editor.rightIndentChars, stringResource(R.string.decrease_right_indent), stringResource(R.string.increase_right_indent)) { editor.stepIndent(it, right = true) }
        }
    }
}

/**
 * Word's Page Number gallery and Format Page Numbers in one panel: where the numbers go — the top or bottom of the
 * page, left, centred or right (a number already there moves; the current place is marked) — and how they read
 * (1, Page 1, Page 1 of 3); the caret's section's number format (1, i, I, a, A, 一) and whether it starts again
 * (from a number, − / +) or goes on from the section before; Remove Page Numbers. The panel stays open.
 */
@Composable
private fun PageNumbersPanel(editor: DocumentEditor) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val pg = editor.currentPage
    val place = editor.pageNumberPlace
    val phrases = listOf(com.therealityteam.aeropages.model.HeaderFooters.PAGE, stringResource(R.string.pn_page_x), stringResource(R.string.sample_page_of_pages))
    val current = editor.pageNumberText
    var style by remember(current) { mutableIntStateOf(phrases.indexOf(current).coerceAtLeast(0)) }
    fun sample(phrase: String) = com.therealityteam.aeropages.model.HeaderFooters.fill(phrase, com.therealityteam.aeropages.model.Lists.number(1, pg.pageNumberFormat), 3)
    val label = @Composable { text: String -> Text(text, style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary) }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (top in listOf(true, false)) {
                val where = stringResource(if (top) R.string.pn_top else R.string.pn_bottom)
                label(where)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TextAlign.LEFT to R.string.align_left, TextAlign.CENTER to R.string.center, TextAlign.RIGHT to R.string.align_right).forEach { (align, name) ->
                        val description = stringResource(R.string.pn_spot, where, stringResource(name))
                        MiniPage(top, listOf(com.therealityteam.aeropages.editor.HeaderFooterEdits.slotOf(align) to true), selected = place == (top to align),
                            description = description) { editor.insertPageNumber(top, align, phrases[style], phrases) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            label(stringResource(R.string.pn_style))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                phrases.forEachIndexed { i, phrase ->
                    RadioOption(sample(phrase), style == i, {
                        style = i
                        place?.let { (top, align) -> editor.insertPageNumber(top, align, phrase, phrases) }
                    })
                }
            }
            label(stringResource(R.string.page_number_format))
            val formats = listOf(com.therealityteam.aeropages.model.NumFormat.DECIMAL, com.therealityteam.aeropages.model.NumFormat.LOWER_ROMAN, com.therealityteam.aeropages.model.NumFormat.UPPER_ROMAN,
                com.therealityteam.aeropages.model.NumFormat.LOWER_LETTER, com.therealityteam.aeropages.model.NumFormat.UPPER_LETTER, com.therealityteam.aeropages.model.NumFormat.CHINESE)
            val sampleOf: (com.therealityteam.aeropages.model.NumFormat) -> String = { f -> (1..3).joinToString(", ") { com.therealityteam.aeropages.model.Lists.number(it, f) } }
            ComboField(sampleOf(pg.pageNumberFormat), formats, { sampleOf(it) }, pg.pageNumberFormat, { f -> editor.setPageNumbering(f, pg.pageNumberStart) }, width = 200.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioOption(stringResource(R.string.page_numbers_continue), pg.pageNumberStart == null, { editor.setPageNumbering(pg.pageNumberFormat, null) })
                val start = pg.pageNumberStart
                RadioOption(stringResource(R.string.page_numbers_start_at), start != null, { editor.setPageNumbering(pg.pageNumberFormat, start ?: 1) })
                if (start != null) {
                    SpinBox(com.therealityteam.aeropages.model.Lists.number(start, pg.pageNumberFormat),
                        { d -> editor.setPageNumbering(pg.pageNumberFormat, (start + d).coerceAtLeast(0)) },
                        description = stringResource(R.string.page_numbers_start_at), canLess = start > 0)
                }
                if (place != null) {
                    Spacer(Modifier.width(12.dp))
                    RibbonSmallButton("ic_delete", stringResource(R.string.pn_remove), { editor.removePageNumbers(phrases) })
                }
            }
        }
    }
}

/**
 * Word's built-in headers (or footers): Blank, Blank (Three Columns), and Title and Page Number (a footer's: Page X of
 * Y), each drawn on a page's top (or foot); picking one fills it in and opens it for typing. Then Edit and Remove.
 */
@Composable
private fun HeaderFooterGallery(editor: DocumentEditor, header: Boolean, onDone: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val type = stringResource(R.string.hf_type_here)
    val page = com.therealityteam.aeropages.model.HeaderFooters.PAGE
    val pageOf = stringResource(R.string.sample_page_of_pages)
    class Layout(val name: String, val text: String, val align: TextAlign, val slots: List<Pair<Int, Boolean>>)
    val layouts = listOf(
        Layout(stringResource(R.string.hf_blank), type, TextAlign.CENTER, listOf(1 to false)),
        Layout(stringResource(R.string.hf_three_columns), "$type\t$type\t$type", TextAlign.LEFT, listOf(0 to false, 1 to false, 2 to false)),
        if (header) Layout(stringResource(R.string.hf_title_page), stringResource(R.string.hf_title_placeholder) + "\t\t" + page, TextAlign.LEFT, listOf(0 to false, 2 to true))
        else Layout(pageOf.replace(page, "X").replace(com.therealityteam.aeropages.model.HeaderFooters.PAGES, "Y"), pageOf, TextAlign.CENTER, listOf(1 to true)),
    )
    val exists = (if (header) editor.document.header else editor.document.footer) != null
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        layouts.forEach { l ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp)) {
                MiniPage(header, l.slots, selected = false, description = l.name, wide = true) { editor.applyHeaderFooterLayout(header, l.text, l.align); onDone() }
                Text(l.name, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        // Word's commands under the gallery: Edit Header, Remove Header.
        FlyoutMenu(width = 220.dp) {
            MenuCommand(stringResource(if (header) R.string.hf_edit_header else R.string.hf_edit_footer), {
                if (!exists) { if (header) editor.addHeader() else editor.addFooter() }
                editor.requestHeaderFooterEdit(header)
                onDone()
            }, icon = "ic_edit")
            if (exists) MenuCommand(stringResource(if (header) R.string.hf_remove_header else R.string.hf_remove_footer), {
                if (header) editor.removeHeader() else editor.removeFooter()
                onDone()
            }, icon = "ic_delete")
        }
    }
}

/**
 * A page drawn small for a gallery: its header band (or footer band) with marks in the left / centre / right slots
 * ([slots]: slot to whether it is the page number — accent — or text — grey), a few lines of body text, and a
 * frame when [selected]. [wide]: the top (or foot) of a page only, for the header and footer galleries.
 */
@Composable
private fun MiniPage(top: Boolean, slots: List<Pair<Int, Boolean>>, selected: Boolean, description: String, wide: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val frame = if (selected) accent else Color(0xFFC4C8CE)
    androidx.compose.foundation.Canvas(
        Modifier.size(if (wide) 104.dp else 48.dp, if (wide) 58.dp else 62.dp)
            .clip(RoundedCornerShape(4.dp))
            .press(onClick = onClick)
            .semantics { contentDescription = description }
            .background(Color.White)
            .border(if (selected) 2.dp else 1.dp, frame, RoundedCornerShape(4.dp)),
    ) {
        val w = size.width; val h = size.height
        val pad = w * (if (wide) 0.1f else 0.16f)
        val band = h * (if (wide) 0.16f else 0.12f)
        val y = if (top) h * 0.1f else h - h * 0.1f - band
        val textGrey = Color(0xFF9AA0A6)
        val body = Color(0xFFDADDE1)
        val markW = if (wide) w * 0.22f else w * 0.2f
        for ((slot, number) in slots) {
            val mw = if (number) markW * (if (wide) 0.45f else 1f) else markW
            val x = when (slot) { 0 -> pad; 1 -> (w - mw) / 2; else -> w - pad - mw }
            drawRoundRect(if (number) accent else textGrey, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(mw, band),
                androidx.compose.ui.geometry.CornerRadius(band / 3))
        }
        // The body's lines, between the header and footer bands.
        val first = if (top) y + band * 2.4f else h * (if (wide) 0.16f else 0.14f)
        val last = if (top) h * (if (wide) 0.92f else 0.86f) else y - band * 1.4f
        val step = h * (if (wide) 0.14f else 0.1f)
        var ly = first
        var i = 0
        while (ly <= last) {
            drawRect(body, androidx.compose.ui.geometry.Offset(pad, ly), androidx.compose.ui.geometry.Size((w - 2 * pad) * (if (i % 3 == 2) 0.6f else 1f), h * 0.035f))
            ly += step; i++
        }
    }
}

/** "2", "1.5": a count of characters as an indent shows it. */
private fun charCount(v: Float): String =
    if (abs(v - Math.round(v)) < 0.05f) Math.round(v).toString() else String.format(java.util.Locale.getDefault(), "%.1f", v)

/** [label], a − button, the indent in characters and a + button; each press moves it one character. */
@Composable
private fun IndentStepper(label: String, chars: Float, lessLabel: String, moreLabel: String, onStep: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
        SpinBox(stringResource(R.string.indent_chars, charCount(chars)), { d -> onStep(d.toFloat()) }, description = label, canLess = chars > 0f, valueWidth = 52.dp,
            lessLabel = lessLabel, moreLabel = moreLabel)
    }
}

@Composable
private fun TablePanel(editor: DocumentEditor, onPicked: () -> Unit) {
    // Word's Insert ▸ Table: the size grid, then turning the selected text into a table.
    var textToTable by remember { mutableStateOf(devDialogProp() == "texttotable") }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
        TableGridPicker { r, c -> editor.insertTable(r, c); onPicked() }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 22.dp)) {
            RibbonSmallButton("x_table", stringResource(R.string.text_to_table), { textToTable = true }, enabled = editor.selectedBodyParagraphs() != null)
        }
    }
    if (textToTable) TextToTableDialog(editor) { textToTable = false; onPicked() }
}

@Composable
private fun CellShadePanel(editor: DocumentEditor, onPicked: () -> Unit) {
    Column {
        ColorPalette(null, { c -> editor.setCellShade(c); onPicked() }, noneLabel = stringResource(R.string.no_color))
        MenuDivider()
        MenuCommand(stringResource(R.string.header_row_shading), { editor.shadeHeaderRow(0xFFD9E2F3); onPicked() })
    }
}

/**
 * Word's Line Numbers menu (Layout ▸ Page Setup): none, continuous, restart each page, restart each section — the caret's
 * section's, checked —, suppress for the current paragraph, and Line Numbering Options….
 */
@Composable
private fun LineNumbersMenu(editor: DocumentEditor, onDone: () -> Unit, onMore: () -> Unit) {
    val n = editor.lineNumbering
    FlyoutMenu(width = 300.dp) {
        MenuCommand(stringResource(R.string.none), { editor.setLineNumberRestart(null); onDone() }, checked = n == null)
        MenuCommand(stringResource(R.string.ln_continuous), { editor.setLineNumberRestart(com.therealityteam.aeropages.model.LineNumberRestart.CONTINUOUS); onDone() },
            checked = n?.restart == com.therealityteam.aeropages.model.LineNumberRestart.CONTINUOUS)
        MenuCommand(stringResource(R.string.ln_restart_page), { editor.setLineNumberRestart(com.therealityteam.aeropages.model.LineNumberRestart.PAGE); onDone() },
            checked = n?.restart == com.therealityteam.aeropages.model.LineNumberRestart.PAGE)
        MenuCommand(stringResource(R.string.ln_restart_section), { editor.setLineNumberRestart(com.therealityteam.aeropages.model.LineNumberRestart.SECTION); onDone() },
            checked = n?.restart == com.therealityteam.aeropages.model.LineNumberRestart.SECTION)
        MenuDivider()
        MenuCommand(stringResource(R.string.ln_suppress), { editor.toggleSuppressLineNumbers(); onDone() }, checked = editor.lineNumbersSuppressed)
        MenuDivider()
        MenuCommand(stringResource(R.string.ln_options_more), onMore, icon = "ic_line_numbers")
    }
}

/**
 * Word's Borders menu for paragraphs (Home ▸ Paragraph): each side turned on or off for the selected paragraphs — checked
 * when the caret's has it —, none, all (the box and the lines between), the box, the lines between, a horizontal line,
 * and Borders and Shading….
 */
@Composable
private fun ParagraphBordersMenu(editor: DocumentEditor, onDone: () -> Unit, onMore: () -> Unit) {
    val b = editor.paragraphBorders
    val ink = PicoTheme.colorScheme.labelPrimary
    val boxed = b != null && b.top != null && b.bottom != null && b.left != null && b.right != null
    FlyoutMenu(width = 280.dp) {
        @Composable
        fun item(preset: DocumentEditor.ParaBorderPreset, label: Int, on: Boolean) =
            MenuCommand(stringResource(label), { editor.applyParagraphBorders(preset); onDone() }, checked = on, leading = { ParaBorderIcon(preset, ink) })
        item(DocumentEditor.ParaBorderPreset.BOTTOM, R.string.border_bottom, b?.bottom != null)
        item(DocumentEditor.ParaBorderPreset.TOP, R.string.border_top, b?.top != null)
        item(DocumentEditor.ParaBorderPreset.LEFT, R.string.border_left, b?.left != null)
        item(DocumentEditor.ParaBorderPreset.RIGHT, R.string.border_right, b?.right != null)
        MenuDivider()
        item(DocumentEditor.ParaBorderPreset.NONE, R.string.border_none, false)
        item(DocumentEditor.ParaBorderPreset.ALL, R.string.border_all, boxed && b?.between != null)
        item(DocumentEditor.ParaBorderPreset.OUTSIDE, R.string.border_outside, boxed)
        item(DocumentEditor.ParaBorderPreset.INSIDE, R.string.border_inside_horizontal, b?.between != null)
        MenuDivider()
        MenuCommand(stringResource(R.string.horizontal_line), { editor.insertHorizontalLine(); onDone() }, leading = {
            androidx.compose.foundation.Canvas(Modifier.size(16.dp)) { drawLine(ink, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5.dp.toPx()) }
        })
        MenuDivider()
        MenuCommand(stringResource(R.string.borders_and_shading_more), onMore, icon = "ic_borders")
    }
}

/** A paragraph border preset's icon: grey lines of text with the lines it draws. */
@Composable
private fun ParaBorderIcon(preset: DocumentEditor.ParaBorderPreset, ink: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val t = 1.2.dp.toPx()
        val faint = ink.copy(alpha = 0.3f)
        for (y in listOf(0.3f, 0.5f, 0.7f)) drawLine(faint, Offset(w * 0.22f, h * y), Offset(w * 0.78f, h * y), 0.8.dp.toPx())
        fun top() = drawLine(ink, Offset(0f, t / 2f), Offset(w, t / 2f), t)
        fun bottom() = drawLine(ink, Offset(0f, h - t / 2f), Offset(w, h - t / 2f), t)
        fun left() = drawLine(ink, Offset(t / 2f, 0f), Offset(t / 2f, h), t)
        fun right() = drawLine(ink, Offset(w - t / 2f, 0f), Offset(w - t / 2f, h), t)
        fun between() = drawLine(ink, Offset(0f, h / 2f), Offset(w, h / 2f), t)
        when (preset) {
            DocumentEditor.ParaBorderPreset.BOTTOM -> bottom()
            DocumentEditor.ParaBorderPreset.TOP -> top()
            DocumentEditor.ParaBorderPreset.LEFT -> left()
            DocumentEditor.ParaBorderPreset.RIGHT -> right()
            DocumentEditor.ParaBorderPreset.NONE -> drawRect(faint, style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
            DocumentEditor.ParaBorderPreset.ALL -> { top(); bottom(); left(); right(); between() }
            DocumentEditor.ParaBorderPreset.OUTSIDE -> { top(); bottom(); left(); right() }
            DocumentEditor.ParaBorderPreset.INSIDE -> between()
        }
    }
}

/**
 * Word's Borders menu for a table: the caret cell's sides (its neighbours' facing ones with them) or the whole
 * table's — each with an icon of what it draws —, then the line they draw: its kind, width and colour.
 */
@Composable
private fun TableBordersMenu(editor: DocumentEditor, onDone: () -> Unit) {
    val line = editor.borderLine
    val ink = PicoTheme.colorScheme.labelPrimary
    @Composable
    fun presets(scope: Int, list: List<Pair<DocumentEditor.TableBorderPreset, Int>>) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(scope), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
            list.forEach { (preset, name) ->
                IconPill(stringResource(name), { editor.applyTableBorders(preset); onDone() }) { TableBorderIcon(preset, line, ink) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets(R.string.border_scope_cell, listOf(
            DocumentEditor.TableBorderPreset.BOTTOM to R.string.border_bottom, DocumentEditor.TableBorderPreset.TOP to R.string.border_top,
            DocumentEditor.TableBorderPreset.LEFT to R.string.border_left, DocumentEditor.TableBorderPreset.RIGHT to R.string.border_right,
            DocumentEditor.TableBorderPreset.CELL_NONE to R.string.border_none,
        ))
        presets(R.string.border_scope_table, listOf(
            DocumentEditor.TableBorderPreset.ALL to R.string.border_all, DocumentEditor.TableBorderPreset.OUTSIDE to R.string.border_outside,
            DocumentEditor.TableBorderPreset.INSIDE to R.string.border_inside, DocumentEditor.TableBorderPreset.NONE to R.string.border_none,
        ))
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.border_line_style), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
            listOf(
                com.therealityteam.aeropages.model.LineStyle.THIN, com.therealityteam.aeropages.model.LineStyle.DOTTED, com.therealityteam.aeropages.model.LineStyle.DASHED,
                com.therealityteam.aeropages.model.LineStyle.DASH_DOT, com.therealityteam.aeropages.model.LineStyle.DOUBLE,
            ).forEach { style ->
                IconPill("", { editor.borderLine = line.copy(style = style) }, selected = line.style == style) {
                    androidx.compose.foundation.Canvas(Modifier.size(32.dp, 12.dp)) {
                        val y = size.height / 2f
                        val w = 1.5.dp.toPx()
                        if (style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) {
                            drawLine(ink, Offset(0f, y - w), Offset(size.width, y - w), w * 0.7f); drawLine(ink, Offset(0f, y + w), Offset(size.width, y + w), w * 0.7f)
                        } else drawLine(ink, Offset(0f, y), Offset(size.width, y), w,
                            pathEffect = style.dash?.let { d -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * w * 1.5f }) })
                    }
                }
            }
            Text(stringResource(R.string.border_width_pt), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
            val widths = listOf(0.5f, 1f, 1.5f, 2.25f, 3f)
            val widthName: (Float) -> String = { w -> if (w == Math.floor(w.toDouble()).toFloat()) w.toInt().toString() else w.toString() }
            ComboField(widthName(line.widthPt), widths, { widthName(it) }, line.widthPt, { w -> editor.borderLine = line.copy(widthPt = w) }, width = 88.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.border_line_color), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
            ColorPalette(line.color, { c -> editor.borderLine = line.copy(color = c) }, noneLabel = stringResource(R.string.border_automatic))
        }
    }
}

/** A table border preset's icon: a faint 2×2 grid with the lines it draws, in the line's colour. */
@Composable
private fun TableBorderIcon(preset: DocumentEditor.TableBorderPreset, line: com.therealityteam.aeropages.model.CellBorder, ink: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val t = 1.dp.toPx()
        val faint = ink.copy(alpha = 0.3f)
        val color = line.color?.let { Color(it) } ?: ink
        drawRect(faint, style = androidx.compose.ui.graphics.drawscope.Stroke(0.8f * t))
        drawLine(faint, Offset(w / 2f, 0f), Offset(w / 2f, h), 0.8f * t)
        drawLine(faint, Offset(0f, h / 2f), Offset(w, h / 2f), 0.8f * t)
        fun top() = drawLine(color, Offset(0f, 0f), Offset(w, 0f), t)
        fun bottom() = drawLine(color, Offset(0f, h), Offset(w, h), t)
        fun left() = drawLine(color, Offset(0f, 0f), Offset(0f, h), t)
        fun right() = drawLine(color, Offset(w, 0f), Offset(w, h), t)
        fun inside() { drawLine(color, Offset(w / 2f, 0f), Offset(w / 2f, h), t); drawLine(color, Offset(0f, h / 2f), Offset(w, h / 2f), t) }
        when (preset) {
            DocumentEditor.TableBorderPreset.BOTTOM -> bottom()
            DocumentEditor.TableBorderPreset.TOP -> top()
            DocumentEditor.TableBorderPreset.LEFT -> left()
            DocumentEditor.TableBorderPreset.RIGHT -> right()
            DocumentEditor.TableBorderPreset.ALL -> { top(); bottom(); left(); right(); inside() }
            DocumentEditor.TableBorderPreset.OUTSIDE -> { top(); bottom(); left(); right() }
            DocumentEditor.TableBorderPreset.INSIDE -> inside()
            DocumentEditor.TableBorderPreset.CELL_NONE, DocumentEditor.TableBorderPreset.NONE -> {}
        }
    }
}

// --- small building blocks -------------------------------------------------

/** A ribbon group with its [label] under it (the kit's [RibbonGroup]). */
@Composable
private fun Group(label: String, launcher: (() -> Unit)? = null, content: @Composable () -> Unit) = RibbonGroup(label, launcher = launcher, content = content)

// --- helpers ---------------------------------------------------------------

internal fun currentSize(editor: DocumentEditor): Float =
    editor.currentFormat.fontSizePt ?: editor.activeParagraph.format.style.sizePt

internal fun stepSize(current: Float, dir: Int): Float {
    val sizes = TextStyling.FONT_SIZES
    val idx = sizes.indexOfFirst { it >= current }.let { if (it < 0) sizes.lastIndex else it }
    return sizes[(idx + dir).coerceIn(0, sizes.lastIndex)]
}



/**
 * Shape Format (a text box or shape being worked on): its fill, outline and shadow — the menus Slides
 * gives its shapes — and its size, smaller or larger.
 */
@Composable
private fun ShapeFormatTab(editor: DocumentEditor, toggle: (Picker) -> Unit, picker: Picker) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val box = editor.activeBox ?: return
    // Word's Shape Format as its narrow ribbon has it: Insert Shapes, Shape Styles (a gallery; Fill, Outline, Effects),
    // Arrange (Wrap Text, Position) and Size (Height and Width).
    Group(stringResource(R.string.group_insert_shapes)) {
        RibbonButton("ic_shape_star", stringResource(R.string.shapes_insert), { toggle(Picker.SHAPES) }, selected = picker == Picker.SHAPES, dropdown = true)
    }
    Group(stringResource(R.string.group_shape_styles)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SHAPE_STYLE_PRESETS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { st -> ShapeStyleSwatch(st) { editor.applyBoxStyle(box, st.fill, st.border, st.text) } }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_fill", stringResource(R.string.shape_fill), { toggle(Picker.SHAPEFILL) }, selected = picker == Picker.SHAPEFILL, dropdown = true)
            RibbonSmallButton("ic_outline", stringResource(R.string.shape_outline), { toggle(Picker.SHAPEBORDER) }, selected = picker == Picker.SHAPEBORDER, dropdown = true)
            RibbonSmallButton("ic_shadow", stringResource(R.string.shape_shadow), { toggle(Picker.SHAPESHADOW) }, selected = picker == Picker.SHAPESHADOW, dropdown = true)
        }
    }
    Group(stringResource(R.string.arrange)) {
        WrapTextMenu(box.floating?.wrap) { wrap -> editor.setBoxFloat(box, wrappedAs(editor, box, box.floating, wrap, null, 1f)) }
        PositionMenu(box.floating) { align -> box.floating?.let { editor.setBoxFloat(box, it.copy(hAlign = align)) } }
    }
    Group(stringResource(R.string.group_size)) {
        // Height and Width in centimetres or inches, half a centimetre or a quarter inch a press; a box without a
        // height of its own grows with its text (Automatic until a height is set).
        val step = if (Lengths.inches) 18f else 72f / 2.54f / 2f
        val w = box.widthPt ?: 320f
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonGlyph("ic_shape_height", PicoTheme.colorScheme.labelSecondary, 18.dp)
                val h = box.heightPt
                SpinBox(if (h == null) stringResource(R.string.automatic) else Lengths.label(h), { d -> editor.setBoxSize(box, null, ((h ?: 72f) + d * step).coerceAtLeast(step)) },
                    description = stringResource(R.string.ps_height), valueWidth = 64.dp, compact = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonGlyph("ic_shape_width", PicoTheme.colorScheme.labelSecondary, 18.dp)
                SpinBox(Lengths.label(w), { d -> editor.setBoxSize(box, (w + d * step).coerceAtLeast(step), null) },
                    description = stringResource(R.string.ps_width), valueWidth = 64.dp, compact = true)
            }
        }
    }
}

/**
 * Arrange's Wrap Text (Word's Layout Options as a menu): in line with the text, square, top and bottom, behind or in
 * front of the text — [current] ticked, the button showing it.
 */
@Composable
private fun WrapTextMenu(current: com.therealityteam.aeropages.model.FloatWrap?, onWrap: (com.therealityteam.aeropages.model.FloatWrap?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonButton(wrapIcon(current), stringResource(R.string.picture_wrap), { open = !open }, selected = open, dropdown = true)
        if (open) AnchoredFlyout(onDismiss = { open = false }) {
            FlyoutMenu(width = 248.dp) {
                listOf(
                    null to R.string.wrap_inline,
                    com.therealityteam.aeropages.model.FloatWrap.SQUARE to R.string.wrap_square,
                    com.therealityteam.aeropages.model.FloatWrap.TOP_BOTTOM to R.string.wrap_top_bottom,
                    com.therealityteam.aeropages.model.FloatWrap.BEHIND to R.string.wrap_behind,
                    com.therealityteam.aeropages.model.FloatWrap.FRONT to R.string.wrap_front,
                ).forEach { (wrap, label) ->
                    MenuCommand(stringResource(label), { onWrap(wrap); open = false }, icon = wrapIcon(wrap), checked = current == wrap)
                }
            }
        }
    }
}

/** Arrange's Position: a floating object at the left, the centre or the right of what it counts from (in line: off). */
@Composable
private fun PositionMenu(pos: com.therealityteam.aeropages.model.FloatPosition?, onAlign: (com.therealityteam.aeropages.model.FloatAlign) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonButton("ic_align_objects", stringResource(R.string.picture_position), { open = !open }, selected = open, dropdown = true, enabled = pos != null)
        if (open) AnchoredFlyout(onDismiss = { open = false }) {
            FlyoutMenu(width = 220.dp) {
                listOf(
                    Triple(com.therealityteam.aeropages.model.FloatAlign.START, R.string.align_left, "ic_align_left"),
                    Triple(com.therealityteam.aeropages.model.FloatAlign.CENTER, R.string.center, "ic_align_center"),
                    Triple(com.therealityteam.aeropages.model.FloatAlign.END, R.string.align_right, "ic_align_right"),
                ).forEach { (align, label, icon) ->
                    MenuCommand(stringResource(label), { onAlign(align); open = false }, icon = icon, checked = pos?.hAlign == align)
                }
            }
        }
    }
}

/**
 * Word's Picture Format, as its narrow ribbon has it: Adjust (Reset Picture ▾), Picture Styles (the shadows, each
 * drawn), Arrange (Wrap Text, Position — a picture of its own, not one in a table's cell — and Rotate's flips) and Size
 * (Crop; Height and Width, its proportions kept). Copy and Delete stay on the picture's own bar.
 */
@Composable
private fun PictureFormatTab(editor: DocumentEditor) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val image = editor.selectedImage ?: return
    var showCrop by remember { mutableStateOf(false) }
    Group(stringResource(R.string.group_adjust)) {
        var resetting by remember { mutableStateOf(false) }
        Box {
            RibbonButton("ic_reset_picture", stringResource(R.string.reset_picture), { resetting = !resetting }, selected = resetting, dropdown = true)
            if (resetting) AnchoredFlyout(onDismiss = { resetting = false }) {
                FlyoutMenu(width = 260.dp) {
                    MenuCommand(stringResource(R.string.reset_picture), { editor.resetImage(image, size = false); resetting = false }, icon = "ic_reset_picture",
                        enabled = image.cropped || image.shadow != null || image.flipH || image.flipV)
                    MenuCommand(stringResource(R.string.reset_picture_and_size), { editor.resetImage(image, size = true); resetting = false }, icon = "x_resetsize")
                }
            }
        }
    }
    Group(stringResource(R.string.group_picture_styles)) {
        ShadowSample(stringResource(R.string.no_shadow), null, image.shadow == null) { editor.setImageShadow(image, null) }
        listOf(R.string.shadow_bottom_right, R.string.shadow_bottom, R.string.shadow_right, R.string.shadow_soft).zip(com.therealityteam.aeropages.model.ShapeShadow.PRESETS).forEach { (label, s) ->
            ShadowSample(stringResource(label), s, s.sameAs(image.shadow)) { editor.setImageShadow(image, s) }
        }
    }
    // Word's Accessibility group: the picture's Alt Text.
    var altText by remember { mutableStateOf(devDialogProp() == "alttext") }
    Group(stringResource(R.string.group_accessibility)) {
        RibbonButton("ic_accessibility", stringResource(R.string.alt_text), { altText = true })
    }
    if (altText) AltTextDialog(image.altText, image.decorative, onDismiss = { altText = false }) { t, d -> editor.setImageAltText(image, t, d); altText = false }
    Group(stringResource(R.string.arrange)) {
        // A picture in a table's cell stays in line in it: no wrapping or place of its own.
        val own = editor.document.blocks.any { it === image }
        if (own) {
            WrapTextMenu(image.floating?.wrap) { wrap -> editor.setImageFloat(image, wrappedAs(editor, image, image.floating, wrap, null, 1f)) }
            PositionMenu(image.floating) { align -> image.floating?.let { editor.setImageFloat(image, it.copy(hAlign = align)) } }
        }
        RotateMenu(emptyList(), onRotate = {},
            flipV = image.flipV, onFlipV = { editor.setImageFlip(image, image.flipH, !image.flipV) },
            flipH = image.flipH, onFlipH = { editor.setImageFlip(image, !image.flipH, image.flipV) })
    }
    Group(stringResource(R.string.group_size)) {
        RibbonButton("x_crop", stringResource(R.string.crop), { showCrop = true })
        // Height and Width in centimetres or inches, half a centimetre or a quarter inch a press; the other side follows.
        val step = if (Lengths.inches) 18f else 72f / 2.54f / 2f
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonGlyph("ic_shape_height", PicoTheme.colorScheme.labelSecondary, 18.dp)
                SpinBox(Lengths.label(image.heightPt), { d -> editor.scaleImage(image, (image.heightPt + d * step).coerceAtLeast(step) / image.heightPt.coerceAtLeast(1f)) },
                    description = stringResource(R.string.ps_height), valueWidth = 64.dp, compact = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonGlyph("ic_shape_width", PicoTheme.colorScheme.labelSecondary, 18.dp)
                SpinBox(Lengths.label(image.widthPt), { d -> editor.scaleImage(image, (image.widthPt + d * step).coerceAtLeast(step) / image.widthPt.coerceAtLeast(1f)) },
                    description = stringResource(R.string.ps_width), valueWidth = 64.dp, compact = true)
            }
        }
    }
    if (showCrop) {
        val (fullW, fullH) = image.uncroppedSize(image.widthPt, image.heightPt)
        CropDialog(image.data, fullW / fullH.coerceAtLeast(1f), image,
            onCrop = { l, t, r, b -> editor.cropImage(image, l, t, r, b) }, onDismiss = { showCrop = false })
    }
}

/**
 * Word's Chart Design for a picked chart, as Slides has it: Chart Layouts (Add Chart Element ▾: legend, gridlines, data
 * labels, and Chart Elements… for titles, legend place and the value axis), Chart Styles (the colour sets), Data (Switch
 * Row/Column, Edit Data) and Type (Change Chart Type ▾). Delete stays on the chart's own bar.
 */
@Composable
private fun ChartDesignTab(editor: DocumentEditor) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val block = editor.selectedChart ?: return
    val c = block.chart
    var showElements by remember { mutableStateOf(false) }
    Group(stringResource(R.string.group_chart_layouts)) {
        var adding by remember { mutableStateOf(false) }
        Box {
            RibbonButton("ic_chart_add", stringResource(R.string.add_chart_element), { adding = !adding }, selected = adding, dropdown = true)
            if (adding) AnchoredFlyout(onDismiss = { adding = false }) {
                FlyoutMenu(width = 260.dp) {
                    MenuCommand(stringResource(R.string.chart_legend), { editor.editChart(block) { it.showLegend = !it.showLegend } }, checked = c.showLegend)
                    MenuCommand(stringResource(R.string.gridlines), { editor.editChart(block) { it.showGrid = !it.showGrid } }, checked = c.showGrid)
                    MenuCommand(stringResource(R.string.chart_data_labels), { editor.editChart(block) { it.showValues = !it.showValues } }, checked = c.showValues)
                    MenuDivider()
                    MenuCommand(stringResource(R.string.chart_elements) + "…", { adding = false; showElements = true }, icon = "ic_chart")
                }
            }
        }
    }
    if (showElements) ChartElementsDialog(c, onDismiss = { showElements = false }) { t, cat, v, legend, pos, axis ->
        editor.setChartElements(block, t, cat, v, legend, pos, axis); showElements = false
    }
    Group(stringResource(R.string.group_chart_styles)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CHART_PALETTES.withIndex().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (i, pal) -> ChartPaletteSwatch(pal, selected = c.colorSet == i) { if (c.colorSet != i) editor.editChart(block) { it.colorSet = i } } }
                }
            }
        }
    }
    Group(stringResource(R.string.group_table_data)) {
        RibbonButton("ic_switch_row_col", stringResource(R.string.switch_row_column), { editor.editChart(block) { it.switchRowColumn() } })
        RibbonButton("ic_edit_data", stringResource(R.string.edit_chart_data_2), editor::requestChartData)
    }
    Group(stringResource(R.string.group_chart_type)) {
        var typing by remember { mutableStateOf(false) }
        Box {
            RibbonButton(CHART_TYPES.firstOrNull { it.first == c.type }?.second?.second ?: "ic_chart", stringResource(R.string.change_chart_type), { typing = !typing },
                selected = typing, dropdown = true)
            if (typing) AnchoredFlyout(onDismiss = { typing = false }) {
                FlyoutMenu(width = 220.dp) {
                    CHART_TYPES.forEach { (type, lbl) -> MenuCommand(lbl.first, { editor.setChartType(block, type); typing = false }, icon = lbl.second, checked = c.type == type) }
                }
            }
        }
    }
}

/**
 * Word's Alt Text for a picture: what a screen reader should say for it — or, marked decorative, that it should pass it
 * by (the description is then put aside).
 */
@Composable
internal fun AltTextDialog(text: String, decorative: Boolean, onDismiss: () -> Unit, onApply: (String, Boolean) -> Unit) {
    var value by remember(text) { mutableStateOf(text) }
    var plain by remember(decorative) { mutableStateOf(decorative) }
    AeroDialog(stringResource(R.string.alt_text), onDismiss, width = 440.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { onApply(value, plain) }, primary = true)
    }) {
        Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.alt_text_hint), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
            InputBox(if (plain) "" else value, { if (!plain) value = it }, Modifier.fillMaxWidth(), description = stringResource(R.string.alt_text), minLines = 4, maxLines = 8)
            CheckOption(stringResource(R.string.alt_text_decorative), plain, { plain = !plain })
            Text(stringResource(R.string.alt_text_decorative_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

/** Shape Fill: a colour, or none. */
@Composable
private fun BoxFillPanel(editor: DocumentEditor, close: () -> Unit) {
    val box = editor.activeBox ?: return
    ColorPalette(box.shape?.fill, { c -> editor.editBoxShape(box) { it.fill = c; it.fillGradient = null }; close() }, noneLabel = stringResource(R.string.shape_no_fill))
}

/** Shape Outline: its colour (or none), weight and dashes. */
@Composable
private fun BoxOutlinePanel(editor: DocumentEditor, close: () -> Unit) {
    val box = editor.activeBox ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorPalette(box.shape?.border, { c -> editor.editBoxShape(box) { it.border = c }; close() }, noneLabel = stringResource(R.string.shape_no_outline))
        MenuDivider()
        LineOptions(box.shape,
            onWeight = { w -> editor.editBoxShape(box) { it.borderWidthPt = w; if (it.border == null) it.border = 0xFF000000 }; close() },
            onDash = { d -> editor.editBoxShape(box) { it.dash = d; if (it.border == null) it.border = 0xFF000000 }; close() },
            onArrows = null)
    }
}

/** Shadow: none, or one of the outer shadows Word's and PowerPoint's shapes cast. */
@Composable
private fun BoxShadowPanel(editor: DocumentEditor, close: () -> Unit) {
    val box = editor.activeBox ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShadowSample(stringResource(R.string.no_shadow), null, box.shape?.shadow == null) { editor.editBoxShape(box) { it.shadow = null }; close() }
            listOf(R.string.shadow_bottom_right, R.string.shadow_bottom, R.string.shadow_right, R.string.shadow_soft).zip(com.therealityteam.aeropages.model.ShapeShadow.PRESETS).forEach { (label, s) ->
                ShadowSample(stringResource(label), s, s.sameAs(box.shape?.shadow)) { editor.editBoxShape(box) { it.shadow = s }; close() }
            }
        }
    }
}
