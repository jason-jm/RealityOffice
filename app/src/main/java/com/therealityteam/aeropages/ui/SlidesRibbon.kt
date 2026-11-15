package com.therealityteam.aeropages.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.VerticalDivider
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.GradientKind
import com.therealityteam.aeropages.model.ShapeShadow
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TextAlign
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R
@Composable
internal fun SlidesRibbon(
    editor: SlideEditor, tab: STab, onTab: (STab) -> Unit, picker: SPicker, onPicker: (SPicker) -> Unit,
    onHome: () -> Unit, onSave: () -> Unit, dirty: Boolean,
    title: String = "", saveState: String = "",
    onInsertImage: () -> Unit, onPlay: () -> Unit, onPlayCurrent: () -> Unit, notesOpen: Boolean, onToggleNotes: () -> Unit,
    onCopy: () -> Unit, onCut: () -> Unit, onPaste: () -> Unit, onCopyObject: () -> Unit = {},
    onInsertLink: () -> Unit, onFind: () -> Unit = {}, onProofing: () -> Unit = {}, commentsOpen: Boolean, onToggleComments: () -> Unit, onNewComment: () -> Unit = onToggleComments,
    onInsertEquation: () -> Unit = {},
    onShowOverview: () -> Unit, onStatus: (String) -> Unit,
    onInsertTable: () -> Unit, onInsertChart: () -> Unit, onHeaderFooter: () -> Unit, onEditChartData: () -> Unit,
    onSectionName: () -> Unit = {},
    onSlideMaster: () -> Unit = {},
    animPaneOpen: Boolean, onToggleAnimPane: () -> Unit, designerOpen: Boolean, onToggleDesigner: () -> Unit,
    showRuler: Boolean, onToggleRuler: () -> Unit, showGridlines: Boolean, onToggleGridlines: () -> Unit,
    showGuides: Boolean, onToggleGuides: () -> Unit, zoomPct: Int, onZoom: (Int) -> Unit,
    onExportPdf: () -> Unit = {}, onShortcuts: () -> Unit = {},
    onShowOutline: () -> Unit = {}, outlineOpen: Boolean = false, onSlidesFromOutline: () -> Unit = {},
    onDetachedShow: () -> Unit = {}, onRehearse: () -> Unit = {}, onSpatialShow: () -> Unit = {},
    onChart3D: () -> Unit = {}, chart3DOpen: Boolean = false,
    onShowSetup: () -> Unit = {},
    onPresenter: () -> Unit = {},
    onFindInFile: (String) -> Unit = {},
    onPlayCustom: (String) -> Unit = {},
    onCustomShows: () -> Unit = {},
    onMoreSymbols: () -> Unit = {},
    onInsertMedia: (video: Boolean) -> Unit = {},
    onClipboardPane: () -> Unit = {},
    onSelectionPane: () -> Unit = {},
    onReplace: () -> Unit = {},
    onReuseSlides: () -> Unit = {},
    fileMenuOpen: Boolean = false, onToggleFileMenu: () -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val cf = editor.currentFormat
    // PowerPoint's Font and Paragraph dialogs, from Home's launchers while a text box is being edited (dev hooks
    // `slidefontdialog`, `slideparagraphdialog`: the first slide's title, four seconds in).
    var fontDialog by remember { mutableStateOf(false) }
    var paragraphDialog by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val hook = devDialogProp()
        if (hook == "slidefontdialog" || hook == "slideparagraphdialog") {
            kotlinx.coroutines.delay(4_000)
            editor.select(0); editor.focus(editor.slide.title)
            if (hook == "slidefontdialog") fontDialog = true else paragraphDialog = true
        }
        // `smartguides`: the first picture in the deck picked and dragged until its centre is near the slide's — left there
        // mid-drag, its guide showing.
        if (hook == "smartguides") {
            kotlinx.coroutines.delay(4_000)
            val at = editor.presentation.slides.indexOfFirst { it.images.isNotEmpty() }
            if (at >= 0) {
                editor.select(at)
                val img = editor.slide.images.first()
                editor.selectImage(img)
                editor.dragSelected(0.496f - (img.xPct + img.wPct / 2f), 0f)
            }
        }
        // `slidepasteoptions`: the first slide's title copied with its formatting and pasted at the end of its body
        // (or subtitle); the Paste Options button's menu open.
        if (hook == "slidepasteoptions") {
            kotlinx.coroutines.delay(4_000)
            editor.select(0)
            val title = editor.slide.title
            editor.setSelection(title, 0, title.text.length)
            editor.copiedClip()?.let { clip ->
                com.therealityteam.aeropages.editor.DocClipboard.clip = clip
                val body = editor.slide.body
                editor.setSelection(body, body.text.length, body.text.length)
                editor.pasteClip(clip)
            }
        }
    }
    if (fontDialog) FontDialog(editor.currentFormat, editor.selectedText(), { change -> editor.formatCharacters(change) }) { fontDialog = false }
    if (paragraphDialog) SlideParagraphDialog(editor) { paragraphDialog = false }
    val editingText = editor.focusedBox() != null
    // Office's Search in the title bar (as Pages'): commands found by name, shown in the ribbon's place; a pick runs
    // it, opens its menu on its tab, or opens its dialog. Dev hook `commandsearch`: "页" typed.
    // AutoCorrect Options (Office's File ▸ Options ▸ Proofing), reached from Search here.
    var autoCorrectDialog by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "autocorrect") }
    if (autoCorrectDialog) AutoCorrectOptionsDialog { autoCorrectDialog = false }
    // Design ▸ Slide Size: Custom Slide Size, and PowerPoint's Maximize / Ensure Fit when the slides change shape. Kept up
    // here so they outlive the menu. Dev hooks `customslidesize` (the dialog), `slidescale` (the question, to 4:3).
    var customSize by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "customslidesize") }
    var scaleTo by remember { mutableStateOf<Pair<com.therealityteam.aeropages.model.SlideSize, Int>?>(
        if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "slidescale") com.therealityteam.aeropages.model.SlideSize.STANDARD to 1 else null) }
    fun sizeSlides(size: com.therealityteam.aeropages.model.SlideSize, first: Int) {
        if (asksToScale(editor, size)) scaleTo = size to first
        else editor.setSlideSize(size, com.therealityteam.aeropages.model.SlideScale.ENSURE_FIT, first)
    }
    if (customSize) CustomSlideSizeDialog(editor, ::sizeSlides) { customSize = false }
    // Action Settings: from Insert ▸ Links ▸ Action, and as soon as an action button goes on the slide (PowerPoint opens it then).
    var actionDialog by remember { mutableStateOf(false) }
    val buttonsIn = editor.actionButtonsInserted
    var buttonsSeen by remember { mutableIntStateOf(buttonsIn) }
    LaunchedEffect(buttonsIn) { if (buttonsIn != buttonsSeen) { buttonsSeen = buttonsIn; actionDialog = true } }
    if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() in setOf("actionsettings", "actionhover")) LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4_000)
        if (editor.actionTarget == null) editor.addPreset("actionButtonForwardNext") else actionDialog = true
    }
    if (actionDialog) ActionSettingsDialog(editor) { actionDialog = false }
    scaleTo?.let { (size, first) ->
        SlideScaleDialog(editor.presentation.slideSize, size, { scale -> editor.setSlideSize(size, scale, first) }) { scaleTo = null }
    }
    // Home ▸ Replace ▸ Replace Fonts (`replacefonts`).
    var replacingFonts by remember { mutableStateOf(false) }
    if (devDialogProp() == "replacefonts") androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(3_500); replacingFonts = true }
    if (replacingFonts) ReplaceFontsDialog(editor, onStatus) { replacingFonts = false }
    val search = rememberCommandSearch("slides")
    fun open(t: STab, p: SPicker = SPicker.NONE) { onTab(t); if (p != SPicker.NONE && picker != p) onPicker(p) }
    @Composable fun commands(): List<AppCommand> {
        val home = stringResource(STab.HOME.title); val insert = stringResource(STab.INSERT.title); val design = stringResource(STab.DESIGN.title)
        val anim = stringResource(STab.ANIM.title); val show = stringResource(STab.SHOW.title); val review = stringResource(STab.REVIEW.title)
        val viewTab = stringResource(STab.VIEW.title)
        @Composable fun c(id: Int, icon: String, where: String, run: () -> Unit) = AppCommand(stringResource(id), icon, where, run)
        @Composable fun d(id: Int, icon: String, where: String, run: () -> Unit) = AppCommand(stringResource(id) + "…", icon, where, run)
        return listOfNotNull(
            c(R.string.paste, "📋", home, onPaste), c(R.string.cut, "✂", home, onCut), c(R.string.copy, "⧉", home, onCopy),
            c(R.string.clipboard, "📋", home, onClipboardPane),
            AppCommand(stringResource(R.string.selection_pane), "ic_select_multiple", home, onSelectionPane),
            AppCommand(stringResource(R.string.replace_fonts) + "…", "ic_replace_fonts", home) { replacingFonts = true },
            AppCommand(stringResource(R.string.select_all), "ic_select_multiple", home) { editor.selectAllShapes() },
            AppCommand(stringResource(R.string.reuse_slides), "ic_new_slide", home, onReuseSlides),
            c(R.string.new_slide, "➕", home) { editor.addSlide() }, c(R.string.duplicate_slide, "⧉", home, editor::duplicateSlide),
            c(R.string.layout_picker, "▤", home) { open(STab.HOME, SPicker.LAYOUT) }, c(R.string.reset_slide, "↺", home, editor::resetSlide),
            c(R.string.bold, "B", home, editor::toggleBold), c(R.string.italic, "I", home, editor::toggleItalic),
            c(R.string.underline, "U", home, editor::toggleUnderline), c(R.string.strikethrough, "S", home, editor::toggleStrike),
            if (editingText) d(R.string.font, "Aa", home) { fontDialog = true } else null,
            if (editingText) d(R.string.paragraph, "↕", home) { paragraphDialog = true } else null,
            c(R.string.bullets, "•", home, editor::toggleBullet), c(R.string.numbering, "1.", home, editor::toggleNumber),
            c(R.string.align_left, "ic_align_left", home) { editor.setAlign(TextAlign.LEFT) }, c(R.string.center, "ic_align_center", home) { editor.setAlign(TextAlign.CENTER) },
            c(R.string.align_right, "ic_align_right", home) { editor.setAlign(TextAlign.RIGHT) }, c(R.string.justify, "⇿", home) { editor.setAlign(TextAlign.JUSTIFY) },
            c(R.string.line_spacing, "↕", home) { open(STab.HOME, SPicker.LINESPACING) },
            c(R.string.shape_fill, "ic_fill", home) { open(STab.HOME, SPicker.SHAPEFILL) }, c(R.string.shape_outline, "ic_outline", home) { open(STab.HOME, SPicker.SHAPEBORDER) },
            c(R.string.shape_shadow, "ic_shadow", home) { open(STab.HOME, SPicker.SHAPESHADOW) }, c(R.string.arrange, "置顶", home) { open(STab.HOME, SPicker.ARRANGE) },
            c(R.string.find_and_replace, "x_search", home, onFind),
            c(R.string.table, "▦", insert, onInsertTable), c(R.string.picture, "🖼", insert, onInsertImage),
            c(R.string.shapes_gallery, "⬛", insert) { open(STab.INSERT, SPicker.SHAPES) }, c(R.string.chart_label, "📊", insert, onInsertChart),
            AppCommand("SmartArt", "◫", insert) { open(STab.INSERT, SPicker.SMARTART) },
            c(R.string.insert_video_short, "🎬", insert) { onInsertMedia(true) }, c(R.string.insert_audio_short, "🔊", insert) { onInsertMedia(false) },
            c(R.string.link, "🔗", insert, onInsertLink), c(R.string.comment, "💬", insert, onNewComment),
            if (editor.actionTarget != null) d(R.string.action_command, "ic_action", insert) { actionDialog = true } else null,
            c(R.string.text_box, "文本框", insert) { editor.addShape(ShapeType.TEXTBOX) }, d(R.string.title_header_footer, "页眉页脚", insert, onHeaderFooter),
            c(R.string.word_art, "艺术字", insert, editor::addWordArt), c(R.string.equation, "π", insert, onInsertEquation), d(R.string.symbol, "Ω", insert, onMoreSymbols),
            c(R.string.design_ideas, "设计器", design, onToggleDesigner), c(R.string.slide_size, "幻灯片大小", design) { open(STab.DESIGN, SPicker.SLIDESIZE) },
            AppCommand(stringResource(R.string.slide_size_custom_more), "ic_slide_size", design) { customSize = true },
            c(R.string.format_background, "背景格式", design) { open(STab.DESIGN, SPicker.BACKGROUND) }, d(R.string.slide_master, "母版", design, onSlideMaster),
            c(R.string.animation_pane, "☰", anim, onToggleAnimPane),
            c(R.string.show_from_beginning, "▶", show, onPlay), c(R.string.show_from_current_slide, "⏵", show, onPlayCurrent),
            c(R.string.presenter_view, "👤", show, onPresenter), d(R.string.setup_slide_show, "x_showsetup", show, onShowSetup),
            d(R.string.show_custom_show, "x_customshow", show, onCustomShows), c(R.string.rehearse_timings, "x_rehearse", show, onRehearse),
            c(R.string.export_pdf, "export_pdf", show, onExportPdf),
            c(R.string.spelling_check, "拼写", review, onProofing), c(R.string.new_comment, "新建批注", review, onNewComment),
            AppCommand(stringResource(R.string.ac_options) + "…", "ic_lightning", review) { autoCorrectDialog = true },
            c(R.string.show_comments_pane, "显示批注", review, onToggleComments),
            c(R.string.outline_view, "大纲", viewTab, onShowOutline), c(R.string.slide_sorter, "浏览", viewTab, onShowOverview),
            c(R.string.notes_page, "备注页", viewTab, onToggleNotes), c(R.string.reading_view, "阅读视图", viewTab, onPlayCurrent),
            c(R.string.ruler_toggle, "ruler", viewTab, onToggleRuler), c(R.string.gridlines, "grid", viewTab, onToggleGridlines),
            c(R.string.guides_toggle, "guides", viewTab, onToggleGuides), c(R.string.keyboard_shortcuts, "kbd_help", viewTab, onShortcuts),
        )
    }
    val anchor = remember { FlyoutAnchor() }
    androidx.compose.runtime.CompositionLocalProvider(LocalFlyoutAnchor provides anchor) {
    Column(Modifier.fillMaxWidth()) {
        val all = if (search.active) commands() else emptyList()
        AppTitleRow(title, saveState, onHome, search = { TitleSearchBox(search, all) }) {
            TitleIconButton("ic_undo", stringResource(R.string.undo), editor::undo, enabled = editor.canUndo)
            TitleIconButton("ic_redo", stringResource(R.string.redo), editor::redo, enabled = editor.canRedo)
            TitleIconButton("ic_play", stringResource(R.string.tip_play_from_beginning), onPlay)
            TitleIconButton("ic_save", stringResource(R.string.save), onSave)
        }
        RibbonTabRow(trailing = { TabRowCommand("ic_comment", stringResource(R.string.comments), commentsOpen, onToggleComments) }) {
            FileTab(fileMenuOpen, onToggleFileMenu)
            val visible = STab.entries.filter {
                !it.contextual ||
                    (it == STab.SHAPE_FMT && editor.selectedShape != null) ||
                    (it == STab.PICTURE_FMT && editor.selectedImage != null) ||
                    (it == STab.TABLE_DESIGN && editor.selectedTable != null) ||
                    (it == STab.TABLE_LAYOUT && editor.selectedTable != null) ||
                    (it == STab.CHART_DESIGN && editor.selectedChart != null)
            }
            visible.forEach { t -> RibbonTab(stringResource(t.title), selected = tab == t, contextual = t.contextual) { onTab(t) } }
        }
        RibbonCard(if (search.active) "search" else tab) {
            if (search.active) SearchCard(search, all, stringResource(tab.title), stringResource(R.string.search_find_deck), onFindInFile)
            else when (tab) {
                STab.HOME -> {
                    val size = cf.fontSizePt ?: editor.focusedBox()?.defaultSizePt ?: 22f
                    // PowerPoint's Clipboard group: text or the selected object (shape, picture, table, chart); Format Painter for text.
                    SGroup(stringResource(R.string.clipboard), launcher = onClipboardPane) {
                        RibbonButton("📋", stringResource(R.string.paste), onPaste)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonMiniButton("✂", stringResource(R.string.cut), onCut)
                                RibbonMiniButton("⧉", stringResource(R.string.copy), onCopy)
                            }
                            RibbonMiniButton("🖌", stringResource(R.string.format_painter), {
                                editor.formatPainter()
                                if (editor.painterArmed) onStatus(Strings.get(R.string.status_format_painter_armed))
                            }, selected = editor.painterArmed)
                        }
                    }
                    // Slides: New Slide (its layouts under the caret), Layout and Reset, Section and the placeholders' free layout.
                    SGroup(stringResource(R.string.group_slides)) {
                        RibbonButton("➕", stringResource(R.string.new_slide), { editor.addSlide() })
                        RibbonMiniButton("", stringResource(R.string.new_slide_options), { onPicker(SPicker.NEWSLIDE) }, selected = picker == SPicker.NEWSLIDE, dropdown = true)
                        // PowerPoint's three stacked: Layout, Reset, Section (Free Layout is in Arrange's menu).
                        RibbonStack {
                            RibbonSmallButton("▤", stringResource(R.string.layout_picker), { onPicker(SPicker.LAYOUT) }, selected = picker == SPicker.LAYOUT, dropdown = true, compactAt = 2)
                            RibbonSmallButton("↺", stringResource(R.string.reset_slide), editor::resetSlide, compactAt = 2)
                            val startsSection = editor.slide.sectionName != null
                            RibbonSmallButton("§", stringResource(if (startsSection) R.string.rename_section else R.string.add_section), onSectionName, compactAt = 2)
                        }
                    }
                    SGroup(stringResource(R.string.font), launcher = if (editingText) ({ fontDialog = true }) else null) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonCombo(cf.fontFamily ?: "Calibri", { onPicker(SPicker.FONT) }, width = 108.dp, selected = picker == SPicker.FONT, tooltip = stringResource(R.string.font))
                                RibbonCombo(size.toInt().toString(), { onPicker(SPicker.SIZE) }, width = 46.dp, selected = picker == SPicker.SIZE, tooltip = stringResource(R.string.font_size))
                                RibbonMiniButton("A↑", stringResource(R.string.increase_font_size), { editor.setFontSize((size + 2f).coerceAtMost(96f)) })
                                RibbonMiniButton("A↓", stringResource(R.string.decrease_font_size), { editor.setFontSize((size - 2f).coerceAtLeast(8f)) })
                                RibbonMiniButton("Aa", stringResource(R.string.change_case), { onPicker(SPicker.CASE) }, selected = picker == SPicker.CASE, dropdown = true)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonMiniButton("B", stringResource(R.string.bold), editor::toggleBold, selected = cf.bold)
                                RibbonMiniButton("I", stringResource(R.string.italic), editor::toggleItalic, selected = cf.italic)
                                RibbonMiniButton("U", stringResource(R.string.underline), editor::toggleUnderline, selected = cf.underline)
                                RibbonMiniButton("S", stringResource(R.string.strikethrough), editor::toggleStrike, selected = cf.strikethrough)
                                RibbonMiniButton("x²", stringResource(R.string.superscript), editor::toggleSuperscript, selected = cf.superscript)
                                RibbonMiniButton("x₂", stringResource(R.string.subscript), editor::toggleSubscript, selected = cf.subscript)
                                RibbonMiniButton("ᴀʙ", stringResource(R.string.small_caps), editor::toggleSmallCaps, selected = cf.smallCaps)
                                RibbonMiniButton("⟷", stringResource(R.string.character_spacing), { onPicker(SPicker.CHARSPACING) }, selected = picker == SPicker.CHARSPACING, dropdown = true)
                                RibbonMiniButton("A", stringResource(R.string.font_color), { onPicker(SPicker.TEXTCOLOR) }, selected = picker == SPicker.TEXTCOLOR, dropdown = true, accent = Color(0xFFCC0000))
                                RibbonMiniButton("▆", stringResource(R.string.text_highlight_color), { onPicker(SPicker.HIGHLIGHT) }, selected = picker == SPicker.HIGHLIGHT, dropdown = true, accent = Color(0xFFFFD400))
                                RibbonMiniButton("⌫", stringResource(R.string.clear_formatting), editor::clearFormatting)
                            }
                        }
                    }
                    SGroup(stringResource(R.string.paragraph), launcher = if (editingText) ({ paragraphDialog = true }) else null) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonMiniButton("•", stringResource(R.string.bullets), editor::toggleBullet, selected = editor.focusedBox()?.listStyle == com.therealityteam.aeropages.model.SlideListStyle.BULLET)
                                RibbonMiniButton("1.", stringResource(R.string.numbering), editor::toggleNumber, selected = editor.focusedBox()?.listStyle == com.therealityteam.aeropages.model.SlideListStyle.NUMBER)
                                // PowerPoint's Decrease / Increase List Level (Shift+Tab / Tab at a line's start).
                                RibbonMiniButton("indent_dec", stringResource(R.string.decrease_list_level), { editor.changeListLevel(-1) })
                                RibbonMiniButton("indent_inc", stringResource(R.string.increase_list_level), { editor.changeListLevel(1) })
                                RibbonMiniButton("↕", stringResource(R.string.line_spacing), { onPicker(SPicker.LINESPACING) }, selected = picker == SPicker.LINESPACING, dropdown = true)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                RibbonMiniButton("⇠", stringResource(R.string.align_left), { editor.setAlign(TextAlign.LEFT) })
                                RibbonMiniButton("⇔", stringResource(R.string.center), { editor.setAlign(TextAlign.CENTER) })
                                RibbonMiniButton("⇢", stringResource(R.string.align_right), { editor.setAlign(TextAlign.RIGHT) })
                                RibbonMiniButton("⇿", stringResource(R.string.justify), { editor.setAlign(TextAlign.JUSTIFY) })
                                // Align Text: a shape's text at its top, middle or bottom.
                                RibbonMiniButton("⇕", stringResource(R.string.align_text), { onPicker(SPicker.ALIGNTEXT) }, selected = picker == SPicker.ALIGNTEXT, dropdown = true,
                                    enabled = editor.selectedShape != null)
                            }
                        }
                    }
                    // Drawing: Shape Fill / Outline and Effects / Arrange, stacked as PowerPoint's narrow ribbon has them.
                    SGroup(stringResource(R.string.group_drawing)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("填充", stringResource(R.string.shape_fill), { onPicker(SPicker.SHAPEFILL) }, selected = picker == SPicker.SHAPEFILL, dropdown = true, compactAt = 1)
                            RibbonSmallButton("轮廓", stringResource(R.string.shape_outline), { onPicker(SPicker.SHAPEBORDER) }, selected = picker == SPicker.SHAPEBORDER, dropdown = true, compactAt = 1)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("阴影", stringResource(R.string.shape_shadow), { onPicker(SPicker.SHAPESHADOW) }, selected = picker == SPicker.SHAPESHADOW, dropdown = true, compactAt = 1)
                            RibbonSmallButton("置顶", stringResource(R.string.arrange), { onPicker(SPicker.ARRANGE) }, selected = picker == SPicker.ARRANGE, dropdown = true, compactAt = 1)
                        }
                    }
                    // Editing as PowerPoint's: Find, Replace ▾ (Replace…, Replace Fonts…), Select ▾ (Select All, Selection Pane…).
                    SGroup(stringResource(R.string.group_editing)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("x_search", stringResource(R.string.find), { onFind() }, compactAt = 3)
                            RibbonSmallButton("ic_replace", stringResource(R.string.replace), { onPicker(SPicker.REPLACEMENU) },
                                selected = picker == SPicker.REPLACEMENU, dropdown = true, compactAt = 3)
                            RibbonSmallButton("ic_select_multiple", stringResource(R.string.select_menu), { onPicker(SPicker.SELECTMENU) },
                                selected = picker == SPicker.SELECTMENU, dropdown = true, compactAt = 3)
                        }
                    }
                }
                STab.INSERT -> {
                    // Slides: New Slide large; a copy and the section commands stacked beside it.
                    SGroup(stringResource(R.string.group_slides)) {
                        RibbonButton("➕", stringResource(R.string.new_slide), editor::addSlide)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val startsSection = editor.slide.sectionName != null
                            RibbonSmallButton("⧉", stringResource(R.string.duplicate_slide), editor::duplicateSlide)
                            RibbonSmallButton("§", stringResource(if (startsSection) R.string.rename_section else R.string.add_section), onSectionName, selected = startsSection)
                            if (startsSection) RibbonSmallButton("§✕", stringResource(R.string.remove_section), editor::removeSection)
                        }
                    }
                    SGroup(stringResource(R.string.group_tables)) {
                        RibbonButton("▦", stringResource(R.string.table), onInsertTable, dropdown = true)
                    }
                    SGroup(stringResource(R.string.illustrations)) {
                        RibbonButton("🖼", stringResource(R.string.picture), onInsertImage)
                        RibbonButton("⬛", stringResource(R.string.shapes_gallery), { onPicker(SPicker.SHAPES) }, selected = picker == SPicker.SHAPES, dropdown = true)
                        RibbonButton("📊", stringResource(R.string.chart_label), onInsertChart, dropdown = true)
                        RibbonButton("◫", "SmartArt", { onPicker(SPicker.SMARTART) }, selected = picker == SPicker.SMARTART, dropdown = true)
                    }
                    // Media: a video or a sound, embedded, played in the show.
                    SGroup(stringResource(R.string.group_media)) {
                        RibbonButton("🎬", stringResource(R.string.insert_video_short), { onInsertMedia(true) }, tooltip = stringResource(R.string.title_insert_video))
                        RibbonButton("🔊", stringResource(R.string.insert_audio_short), { onInsertMedia(false) }, tooltip = stringResource(R.string.title_insert_audio))
                    }
                    // PowerPoint's Links group: Link, and Action (what a shape or picture does in the show) beside Comment.
                    SGroup(stringResource(R.string.group_links)) {
                        RibbonButton("🔗", stringResource(R.string.link), onInsertLink)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("ic_action", stringResource(R.string.action_command), { actionDialog = true }, enabled = editor.actionTarget != null, compactAt = 2)
                            RibbonSmallButton("💬", stringResource(R.string.comment), onNewComment, compactAt = 2)
                        }
                    }
                    SGroup(stringResource(R.string.text)) {
                        // PowerPoint's Text group: Text Box, the rest stacked small beside it (icons alone when narrow).
                        RibbonButton("文本框", stringResource(R.string.text_box), { editor.addShape(ShapeType.TEXTBOX) })
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("页眉页脚", stringResource(R.string.title_header_footer), onHeaderFooter, compactAt = 2)
                            RibbonSmallButton("艺术字", stringResource(R.string.word_art), editor::addWordArt, compactAt = 2)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RibbonSmallButton("📅", stringResource(R.string.date_and_time), { editor.insertAtCaretOrTitle(todayString()) }, compactAt = 1)
                            RibbonSmallButton("#", stringResource(R.string.slide_number), { editor.insertAtCaretOrTitle("${editor.current + editor.presentation.firstSlideNumber}") }, compactAt = 1)
                        }
                    }
                    // Symbols, as PowerPoint's: Equation (a new one, or the selected one to edit) and Symbol.
                    SGroup(stringResource(R.string.group_symbols)) {
                        RibbonButton("π", stringResource(R.string.equation), onInsertEquation, selected = editor.focusedEquation != null)
                        RibbonButton("Ω", stringResource(R.string.symbol), { onPicker(SPicker.SYMBOL) }, selected = picker == SPicker.SYMBOL, dropdown = true)
                    }
                }
                STab.DESIGN -> {
                    // PowerPoint's Themes gallery: the first themes in the ribbon, all of them under ▾.
                    SGroup(stringResource(R.string.group_themes)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ThemeSwatches(editor, plain = 5)
                            RibbonMiniButton("", stringResource(R.string.more_themes), { onPicker(SPicker.THEMES) }, selected = picker == SPicker.THEMES, dropdown = true)
                        }
                    }
                    SGroup(stringResource(R.string.group_designer)) {
                        RibbonButton("设计器", stringResource(R.string.design_ideas), onToggleDesigner, selected = designerOpen)
                    }
                    SGroup(stringResource(R.string.group_customize)) {
                        RibbonButton("幻灯片大小", stringResource(R.string.slide_size), { onPicker(SPicker.SLIDESIZE) }, selected = picker == SPicker.SLIDESIZE, dropdown = true)
                        RibbonButton("背景格式", stringResource(R.string.format_background), { onPicker(SPicker.BACKGROUND) }, selected = picker == SPicker.BACKGROUND, dropdown = true)
                        RibbonButton("母版", stringResource(R.string.slide_master), onSlideMaster, selected = !editor.presentation.master.isDefault, dropdown = true)
                    }
                }
                STab.TRANSITION -> TransitionsTabContent(editor) { label, c -> SGroup(label, content = c) }
                STab.ANIM -> AnimationsTabContent(editor, animPaneOpen, onToggleAnimPane, onPlayCurrent) { label, c -> SGroup(label, content = c) }
                STab.SHOW -> ShowTabContent(editor, onPlay, onPlayCurrent, onPresenter,
                    customMenuOpen = picker == SPicker.CUSTOMSHOW, onCustomMenu = { onPicker(if (picker == SPicker.CUSTOMSHOW) SPicker.NONE else SPicker.CUSTOMSHOW) },
                    onShowSetup = onShowSetup, onRehearse = onRehearse, onDetachedShow = onDetachedShow, onSpatialShow = onSpatialShow,
                    onExportPdf = onExportPdf) { label, c -> SGroup(label, content = c) }
                STab.REVIEW -> {
                    SGroup(stringResource(R.string.group_proofing)) {
                        RibbonButton("x_search", stringResource(R.string.find_and_replace), onFind)
                        RibbonButton("拼写", stringResource(R.string.spelling_check), onProofing)
                        // PowerPoint's statistics as a dialog: slides, words and paragraphs on them, notes, media.
                        var stats by remember { androidx.compose.runtime.mutableStateOf(devDialogProp() == "slidestats") }
                        RibbonButton("字数", stringResource(R.string.title_word_count), { stats = true })
                        if (stats) {
                            val st = com.therealityteam.aeropages.model.DeckStats.of(editor.presentation)
                            StatisticsDialog(stringResource(R.string.title_word_count), listOf(null to listOf(
                                stringResource(R.string.stat_slides) to st.slides.toString(),
                                stringResource(R.string.stat_hidden_slides) to st.hidden.toString(),
                                stringResource(R.string.label_words) to st.words.toString(),
                                stringResource(R.string.label_paragraphs) to st.paragraphs.toString(),
                                stringResource(R.string.stat_notes) to st.notes.toString(),
                                stringResource(R.string.stat_media) to st.media.toString(),
                            ))) { stats = false }
                        }
                    }
                    // PowerPoint's Accessibility group: Check Accessibility lists what a screen reader would stumble on.
                    var checking by remember { androidx.compose.runtime.mutableStateOf(devDialogProp() == "accessibility") }
                    SGroup(stringResource(R.string.group_accessibility)) {
                        RibbonButton("ic_accessibility", stringResource(R.string.check_accessibility), { checking = true })
                    }
                    if (checking) SlidesAccessibility(editor) { checking = false }
                    if (showChineseConversion) SGroup(stringResource(R.string.group_language)) { ChineseConversionButtons({ t, v -> editor.convertChinese(t, v) }, onStatus) }
                    SGroup(stringResource(R.string.comments)) {
                        RibbonButton("新建批注", stringResource(R.string.new_comment), onNewComment)
                        RibbonButton("🗑", stringResource(R.string.delete_comment), { if (editor.slide.comments.isNotEmpty()) editor.deleteComment(editor.slide.comments.lastIndex) })
                        RibbonButton("显示批注", stringResource(R.string.show_comments_pane), onToggleComments, selected = commentsOpen)
                    }
                }
                STab.VIEW -> {
                    SGroup(stringResource(R.string.group_presentation_views)) {
                        RibbonButton("大纲", stringResource(R.string.outline_view), onShowOutline, selected = outlineOpen)
                        RibbonButton("浏览", stringResource(R.string.slide_sorter), onShowOverview)
                        RibbonButton("备注页", stringResource(R.string.notes_page), onToggleNotes, selected = notesOpen)
                        RibbonButton("阅读视图", stringResource(R.string.reading_view), onPlayCurrent)
                    }
                    SGroup(stringResource(R.string.group_show)) {
                        RibbonButton("ruler", stringResource(R.string.ruler_toggle), onToggleRuler, selected = showRuler)
                        RibbonButton("grid", stringResource(R.string.gridlines), onToggleGridlines, selected = showGridlines)
                        RibbonButton("guides", stringResource(R.string.guides_toggle), onToggleGuides, selected = showGuides)
                    }
                    SGroup(stringResource(R.string.group_zoom)) {
                        // PowerPoint's Zoom group: the zooms as a menu (the current one ticked), and Fit to Window.
                        RibbonButton("缩放", stringResource(R.string.anim_zoom), { onPicker(if (picker == SPicker.ZOOM) SPicker.NONE else SPicker.ZOOM) },
                            selected = picker == SPicker.ZOOM, dropdown = true)
                        RibbonButton("适应窗口", stringResource(R.string.fit_to_window), { onZoom(100) }, selected = zoomPct == 100)
                    }
                    SGroup(stringResource(R.string.group_help)) {
                        RibbonButton("kbd_help", stringResource(R.string.keyboard_shortcuts), onShortcuts)
                    }
                }
                STab.SHAPE_FMT -> ShapeFormatTab(editor, picker, onPicker, onSelectionPane)
                STab.PICTURE_FMT -> PictureFormatTab(editor, onSelectionPane)
                STab.TABLE_DESIGN -> TableDesignTab(editor, picker, onPicker)
                STab.TABLE_LAYOUT -> TableLayoutTab(editor)
                STab.CHART_DESIGN -> ChartDesignTab(editor, onEditChartData, onSpatial3D = onChart3D, spatial3DOpen = chart3DOpen)
            }
        }
        if (picker != SPicker.NONE) {
            RibbonFlyout({ onPicker(SPicker.NONE) }) {
                when (picker) {
                    SPicker.TEXTCOLOR -> ColorPalette(cf.color, { c -> editor.setColor(c); onPicker(SPicker.NONE) }, noneLabel = stringResource(R.string.automatic))
                    SPicker.HIGHLIGHT -> ColorPalette(cf.highlight, { c -> editor.setHighlight(c); onPicker(SPicker.NONE) }, noneLabel = stringResource(R.string.no_color), highlight = true)
                    SPicker.FONT -> FlyoutMenu(width = 260.dp, maxHeight = 440.dp) {
                        MenuHeading(stringResource(R.string.all_fonts))
                        TextStyling.FONT_FAMILIES.forEach { n -> MenuCommand(n, { editor.setFontFamily(n); onPicker(SPicker.NONE) }, checked = (cf.fontFamily ?: "Calibri") == n) }
                    }
                    SPicker.SIZE -> FlyoutMenu(width = 140.dp, maxHeight = 440.dp) {
                        TextStyling.FONT_SIZES.forEach { s -> MenuCommand(s.toInt().toString(), { editor.setFontSize(s); onPicker(SPicker.NONE) }, checked = cf.fontSizePt == s) }
                    }
                    SPicker.CASE -> FlyoutMenu(width = 240.dp) {
                        MenuCommand(stringResource(R.string.case_upper), { editor.changeCase(com.therealityteam.aeropages.editor.CaseMode.UPPER); onPicker(SPicker.NONE) })
                        MenuCommand(stringResource(R.string.case_lower), { editor.changeCase(com.therealityteam.aeropages.editor.CaseMode.LOWER); onPicker(SPicker.NONE) })
                        MenuCommand(stringResource(R.string.case_title), { editor.changeCase(com.therealityteam.aeropages.editor.CaseMode.TITLE); onPicker(SPicker.NONE) })
                        MenuCommand(stringResource(R.string.case_toggle), { editor.changeCase(com.therealityteam.aeropages.editor.CaseMode.TOGGLE); onPicker(SPicker.NONE) })
                    }
                    SPicker.CHARSPACING -> FlyoutMenu(width = 240.dp) {
                        MenuCommand(stringResource(R.string.spacing_condensed), { editor.setLetterSpacing(-0.5f); onPicker(SPicker.NONE) })
                        MenuCommand(stringResource(R.string.standard), { editor.setLetterSpacing(null); onPicker(SPicker.NONE) })
                        MenuCommand(stringResource(R.string.spacing_expanded), { editor.setLetterSpacing(1f); onPicker(SPicker.NONE) })
                        MenuCommand(stringResource(R.string.spacing_wider), { editor.setLetterSpacing(2f); onPicker(SPicker.NONE) })
                    }
                    // PowerPoint's Line Spacing list (0.9 is its titles'), the box's own marked.
                    SPicker.LINESPACING -> FlyoutMenu(width = 240.dp) {
                        val current = editor.focusedBox()?.lineSpacing
                        listOf(0.9f, 1.0f, 1.15f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { m ->
                            MenuCommand("$m", { editor.setLineSpacing(m); onPicker(SPicker.NONE) },
                                checked = current != null && kotlin.math.abs(current - m) < 0.005f)
                        }
                    }
                    // The layouts drawn as small slides: the current slide's marked.
                    SPicker.LAYOUT -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SlideLayout.entries.forEach { l -> LayoutCard(l, editor.slide.layout == l) { editor.setLayout(l); onPicker(SPicker.NONE) } }
                    }
                    // New Slide's gallery: a slide of each layout, a copy of this one, slides from an outline.
                    SPicker.NEWSLIDE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SlideLayout.entries.forEach { l -> LayoutCard(l, false) { editor.addSlide(l); onPicker(SPicker.NONE) } }
                        }
                        // PowerPoint's commands under the gallery.
                        MenuDivider()
                        FlyoutMenu(width = 300.dp) {
                            MenuCommand(stringResource(R.string.duplicate_slide), { editor.duplicateSlide(); onPicker(SPicker.NONE) }, icon = "ic_copy")
                            MenuCommand(stringResource(R.string.slides_from_outline) + "…", { onPicker(SPicker.NONE); onSlidesFromOutline() }, icon = "ic_outline_view")
                            MenuCommand(stringResource(R.string.reuse_slides) + "…", { onPicker(SPicker.NONE); onReuseSlides() }, icon = "ic_new_slide")
                        }
                    }
                    SPicker.REPLACEMENU -> FlyoutMenu(width = 240.dp) {
                        MenuCommand(stringResource(R.string.replace) + "…", { onPicker(SPicker.NONE); onReplace() }, icon = "ic_replace", trailing = "Ctrl+H")
                        MenuCommand(stringResource(R.string.replace_fonts) + "…", { onPicker(SPicker.NONE); replacingFonts = true }, icon = "ic_replace_fonts")
                    }
                    SPicker.SELECTMENU -> FlyoutMenu(width = 240.dp) {
                        MenuCommand(stringResource(R.string.select_all), { onPicker(SPicker.NONE); editor.selectAllShapes() }, icon = "ic_select_multiple",
                            trailing = "Ctrl+A", enabled = editor.slide.shapes.any { !it.hidden })
                        MenuCommand(stringResource(R.string.selection_pane) + "…", { onPicker(SPicker.NONE); onSelectionPane() }, icon = "ic_bring_front", trailing = "Alt+F10")
                    }
                    SPicker.ZOOM -> FlyoutMenu(width = 200.dp) {
                        listOf(50, 75, 100, 125, 150, 200).forEach { z ->
                            MenuCommand("$z%", { onPicker(SPicker.NONE); onZoom(z) }, checked = zoomPct == z)
                        }
                    }
                    SPicker.ARRANGE -> FlyoutMenu(width = 280.dp) {
                        val image = editor.selectedShape == null
                        val picked = editor.selectedShape != null || editor.selectedImage != null
                        MenuCommand(stringResource(R.string.bring_to_front), icon = "置顶", enabled = picked, onClick = { if (image) editor.bringImageToFront() else editor.bringShapeToFront() })
                        MenuCommand(stringResource(R.string.bring_forward), icon = "前移", enabled = picked, onClick = { if (image) editor.stepImageLayer(true) else editor.stepShapeLayer(true) })
                        MenuCommand(stringResource(R.string.send_backward), icon = "后移", enabled = picked, onClick = { if (image) editor.stepImageLayer(false) else editor.stepShapeLayer(false) })
                        MenuCommand(stringResource(R.string.send_to_back), icon = "置底", enabled = picked, onClick = { if (image) editor.sendImageToBack() else editor.sendShapeToBack() })
                        MenuDivider()
                        // Free layout pins the title / body at frames so they drag and resize like shapes; picked again, they flow.
                        val free = editor.placeholdersFree
                        MenuCommand(stringResource(R.string.placeholders_free), { if (free) editor.autoPlaceholders() else editor.freePlaceholders(); onPicker(SPicker.NONE) },
                            icon = "✥", checked = free)
                        MenuDivider()
                        // PowerPoint's last item: the Selection Pane, every object on the slide to pick, hide, rename or reorder.
                        MenuCommand(stringResource(R.string.selection_pane) + "…", { onPicker(SPicker.NONE); onSelectionPane() }, icon = "ic_select_multiple", trailing = "Alt+F10")
                    }
                    SPicker.ALIGNTEXT -> FlyoutMenu(width = 240.dp) {
                        val at = editor.selectedShape?.anchor
                        listOf(com.therealityteam.aeropages.model.ShapeAnchor.TOP to R.string.anchor_top, com.therealityteam.aeropages.model.ShapeAnchor.CENTER to R.string.anchor_middle,
                            com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM to R.string.anchor_bottom).forEach { (a, label) ->
                            MenuCommand(stringResource(label), { editor.setShapeAnchor(a); onPicker(SPicker.NONE) }, checked = at == a)
                        }
                    }
                    SPicker.TABLEBORDERS -> FlyoutMenu(width = 280.dp) {
                        // PowerPoint's order: none, all, outside, inside, then each edge — each drawn as it would be.
                        val pen = Color(editor.tablePen.color ?: 0xFF000000)
                        val ink = PicoTheme.colorScheme.labelPrimary
                        listOf(
                            SlideEditor.TableBorders.NONE to R.string.border_none, SlideEditor.TableBorders.ALL to R.string.border_all,
                            SlideEditor.TableBorders.OUTSIDE to R.string.border_outside, SlideEditor.TableBorders.INSIDE to R.string.border_inside,
                            SlideEditor.TableBorders.TOP to R.string.border_top, SlideEditor.TableBorders.BOTTOM to R.string.border_bottom,
                            SlideEditor.TableBorders.LEFT to R.string.border_left, SlideEditor.TableBorders.RIGHT to R.string.border_right,
                            SlideEditor.TableBorders.INSIDE_H to R.string.border_inside_horizontal, SlideEditor.TableBorders.INSIDE_V to R.string.border_inside_vertical,
                        ).forEach { (preset, label) ->
                            if (preset == SlideEditor.TableBorders.TOP) MenuDivider()
                            MenuCommand(stringResource(label), { editor.applyTableBorders(preset); onPicker(SPicker.NONE) }, leading = { SlideBorderIcon(preset, pen, ink) })
                        }
                    }
                    SPicker.PENSTYLE -> FlyoutMenu(width = 200.dp) {
                        val ink = PicoTheme.colorScheme.labelPrimary
                        SLIDE_PEN_STYLES.forEach { st ->
                            PenRow(editor.tablePen.style == st, stringResource(R.string.pen_style), { editor.tablePen = editor.tablePen.copy(style = st); onPicker(SPicker.NONE) }) {
                                PenSample(st, 1.5f, ink, Modifier.weight(1f))
                            }
                        }
                    }
                    SPicker.PENWEIGHT -> FlyoutMenu(width = 200.dp) {
                        val ink = PicoTheme.colorScheme.labelPrimary
                        SLIDE_PEN_WEIGHTS.forEach { w ->
                            val label = penWeightLabel(w)
                            PenRow(editor.tablePen.widthPt == w, label, { editor.tablePen = editor.tablePen.copy(widthPt = w); onPicker(SPicker.NONE) }) {
                                Text(label, style = AeroType.body, color = ink, modifier = Modifier.width(64.dp))
                                PenSample(editor.tablePen.style, w.coerceAtMost(4.5f), ink, Modifier.weight(1f))
                            }
                        }
                    }
                    SPicker.PENCOLOR -> ColorPalette(editor.tablePen.color, { c -> editor.tablePen = editor.tablePen.copy(color = c ?: 0xFF000000); onPicker(SPicker.NONE) },
                        noneLabel = stringResource(R.string.border_automatic))
                    SPicker.TABLESHADING -> ColorPalette(editor.tableShading?.takeUnless { com.therealityteam.aeropages.model.SlideTableLook.isNone(it) },
                        { c -> editor.setTableShading(c ?: 0L); onPicker(SPicker.NONE) }, noneLabel = stringResource(R.string.shape_no_fill))
                    SPicker.SHAPEFILL -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorPalette(editor.selectedShape?.fill, { c -> editor.setShapeFill(c); onPicker(SPicker.NONE) }, noneLabel = stringResource(R.string.shape_no_fill))
                        MenuDivider()
                        // Gradient: second colour + direction (kept as a:gradFill in pptx).
                        // Gradient: its second colour, then — on a gradient — which way and how it runs.
                        DialogLabel(stringResource(R.string.gradient))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SLIDE_BG_COLORS.forEach { c -> ColorSwatch(c, { editor.setShapeGradient(c) }) }
                        }
                        val sel = editor.selectedShape?.takeIf { it.fillGradient != null }
                        if (sel != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val angles = listOf(0f, 45f, 90f, 135f)
                            val linear = sel.gradientKind == GradientKind.LINEAR || sel.gradientKind == GradientKind.AXIAL
                            ChoiceSegments(angles, sel.gradientAngle.takeIf { linear && it in angles }, { "${it.toInt()}°" }, { editor.setShapeGradientAngle(it) }, width = 240.dp)
                            // The other ways PowerPoint and Impress run a gradient: from the centre line out, in circles, in rectangles.
                            val kinds = listOf(GradientKind.LINEAR to R.string.gradient_linear, GradientKind.AXIAL to R.string.gradient_axial, GradientKind.RADIAL to R.string.gradient_radial, GradientKind.RECTANGULAR to R.string.gradient_rectangular)
                            ComboField(stringResource(kinds.firstOrNull { it.first == sel.gradientKind }?.second ?: R.string.gradient_linear), kinds, { stringResource(it.second) },
                                kinds.firstOrNull { it.first == sel.gradientKind }, { editor.setShapeGradientKind(it.first) }, width = 160.dp)
                        }
                        MenuCommand(stringResource(R.string.no_gradient), { editor.setShapeGradient(null) }, icon = "ic_no_color", checked = editor.selectedShape?.fillGradient == null)
                    }
                    // Shape Effects → Shadow: none, or one of PowerPoint's outer shadows, each drawn as it will look.
                    SPicker.SHAPESHADOW -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ShadowSample(stringResource(R.string.no_shadow), null, editor.selectedShape?.shadow == null) { editor.setShapeShadow(null); onPicker(SPicker.NONE) }
                        listOf(R.string.shadow_bottom_right, R.string.shadow_bottom, R.string.shadow_right, R.string.shadow_soft).zip(ShapeShadow.PRESETS).forEach { (label, s) ->
                            ShadowSample(stringResource(label), s, s.sameAs(editor.selectedShape?.shadow)) { editor.setShapeShadow(s); onPicker(SPicker.NONE) }
                        }
                    }
                    // PowerPoint's Shape Outline menu: colour, weight, dashes, and a line's arrows.
                    SPicker.SHAPEBORDER -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorPalette(editor.selectedShape?.border, { c -> editor.setShapeBorder(c); onPicker(SPicker.NONE) }, noneLabel = stringResource(R.string.shape_no_outline))
                        MenuDivider()
                        val sh = editor.selectedShape
                        LineOptions(sh,
                            onWeight = { editor.setShapeBorderWidth(it); onPicker(SPicker.NONE) },
                            onDash = { editor.setShapeDash(it); onPicker(SPicker.NONE) },
                            onArrows = if (sh?.hasEnds == true) { b, e -> editor.setShapeArrows(b, e); onPicker(SPicker.NONE) } else null)
                    }
                    SPicker.BACKGROUND -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorPalette(editor.slide.background, { c -> editor.setBackground(c); onPicker(SPicker.NONE) }, noneLabel = stringResource(R.string.automatic))
                        MenuDivider()
                        // Gradient fill: a preset shading from one colour to another, and — on a slide shading already — which way it runs.
                        val first = editor.slide.background
                        val shade = editor.slide.backgroundShade
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.background_gradient), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                            SLIDE_BG_GRADIENTS.forEach { (a, b) ->
                                GradientSwatch(a, b, { editor.setBackgroundShade(a, shade?.copy(second = b) ?: com.therealityteam.aeropages.model.BackgroundShade(b)) },
                                    selected = first == a && shade?.second == b)
                            }
                        }
                        if (first != null && shade != null) {
                            // Which way it runs: four angles, or out from the centre.
                            val ways: List<Float?> = listOf(0f, 45f, 90f, 135f, null)
                            val radial = stringResource(R.string.gradient_radial)
                            ChoiceSegments(ways, if (shade.kind == GradientKind.RADIAL) null else shade.angleDeg.takeIf { shade.kind == GradientKind.LINEAR && it in ways },
                                { it?.let { a -> "${a.toInt()}°" } ?: radial }, { a ->
                                    if (a == null) editor.setBackgroundShade(first, shade.copy(angleDeg = 0f, kind = GradientKind.RADIAL, cx = 0.5f, cy = 0.5f))
                                    else editor.setBackgroundShade(first, shade.copy(angleDeg = a, kind = GradientKind.LINEAR))
                                }, width = 340.dp)
                        }
                        DialogButton(stringResource(R.string.apply_to_all_slides), { editor.setBackgroundAll(editor.slide.background); onPicker(SPicker.NONE) })
                        // A slide on a template's design: its pictures and shapes shown or hidden (the picture fill stays).
                        if (editor.presentation.designOf(editor.slide)?.items?.isNotEmpty() == true) {
                            CheckOption(stringResource(R.string.hide_background_graphics), !editor.slide.designObjects, { editor.setDesignObjects(!editor.slide.designObjects) }, small = true)
                        }
                    }
                    SPicker.SYMBOL -> SymbolMenu({ editor.insertAtCaretOrTitle(it); onPicker(SPicker.NONE) }) { onPicker(SPicker.NONE); onMoreSymbols() }
                    // PowerPoint's Convert to SmartArt: List, Process, Cycle.
                    SPicker.SMARTART -> FlyoutMenu(width = 300.dp) {
                        MenuHeading(stringResource(R.string.msg_smartart_from_lines))
                        MenuCommand(stringResource(R.string.validation_list), { editor.insertSmartArt(com.therealityteam.aeropages.editor.SmartArtKind.LIST); onPicker(SPicker.NONE) }, icon = "ic_bullets")
                        MenuCommand(stringResource(R.string.smartart_process), { editor.insertSmartArt(com.therealityteam.aeropages.editor.SmartArtKind.PROCESS); onPicker(SPicker.NONE) }, icon = "ic_arrow_right")
                        MenuCommand(stringResource(R.string.loop_show_2), { editor.insertSmartArt(com.therealityteam.aeropages.editor.SmartArtKind.CYCLE); onPicker(SPicker.NONE) }, icon = "ic_rotate_right")
                    }
                    // PowerPoint's Slide Size list; a size a file brought that is none of them shows as custom.
                    SPicker.THEMES -> androidx.compose.foundation.layout.FlowRow(Modifier.width(560.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeSwatches(editor, plain = SLIDE_THEMES.size)
                    }
                    // PowerPoint's menu: the two everyday sizes (picked or not), then Custom Slide Size for the rest.
                    SPicker.SLIDESIZE -> FlyoutMenu(width = 300.dp) {
                        val current = editor.presentation.slideSize
                        listOf(
                            com.therealityteam.aeropages.model.SlideSize.STANDARD to R.string.slide_size_standard,
                            com.therealityteam.aeropages.model.SlideSize.WIDESCREEN to R.string.slide_size_widescreen,
                        ).forEach { (size, name) ->
                            MenuCommand(stringResource(name), { onPicker(SPicker.NONE); sizeSlides(size, editor.presentation.firstSlideNumber) },
                                leading = { SlideShapeIcon(size, current == size) }, checked = current == size)
                        }
                        MenuDivider()
                        MenuCommand(stringResource(R.string.slide_size_custom_more), { onPicker(SPicker.NONE); customSize = true }, icon = "ic_slide_size")
                    }
                    // Insert → Shapes: PowerPoint's gallery, the shapes by category.
                    SPicker.CUSTOMSHOW -> CustomShowPanel(editor, onPlay = { onPicker(SPicker.NONE); onPlayCustom(it) }, onManage = { onPicker(SPicker.NONE); onCustomShows() })
                    SPicker.SHAPES -> ShapeGallery(onLine = { start, end ->
                        val head = com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE)
                        if (!start && !end) editor.addShape(ShapeType.LINE) else editor.addLine(if (start) head else com.therealityteam.aeropages.model.LineEnd.NONE, if (end) head else com.therealityteam.aeropages.model.LineEnd.NONE)
                        onPicker(SPicker.NONE)
                    }) { name -> editor.addPreset(name); onPicker(SPicker.NONE) }
                    SPicker.NONE -> Unit
                }
            }
        }
    }
    }
}

/** A PowerPoint ribbon group with its [label] under it (the kit's [RibbonGroup]). */
@Composable
internal fun SGroup(label: String = "", launcher: (() -> Unit)? = null, content: @Composable () -> Unit) = RibbonGroup(label, launcher = launcher, content = content)

/** A small theme-preview swatch (Aa on the theme background with an accent strip). */
@Composable
internal fun ThemeSwatch(theme: SlideTheme, selected: Boolean, onClick: () -> Unit) = Tooltip(stringResource(R.string.theme_label, theme.name)) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier.then(a11y).width(72.dp).height(48.dp).clip(shape)
            .background(Color(theme.bg))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) LocalAccent.current else PicoTheme.colorScheme.dividerLine), shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        GazeFill(shape)
        Text("Aa", color = Color(theme.title), fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.BottomStart).padding(4.dp).width(40.dp).height(4.dp).background(Color(theme.accent)))
    }
}

/** A designed look in Design's gallery: its cover drawn small, its name on hover. */
@Composable
internal fun DesignedThemeSwatch(look: DeckDesigns.Look, selected: Boolean, onClick: () -> Unit) = Tooltip(stringResource(R.string.theme_label, stringResource(DeckDesigns.labelOf(look)))) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier.then(a11y).width(72.dp).height(48.dp).clip(shape)
            .background(Color(look.cover))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) LocalAccent.current else PicoTheme.colorScheme.dividerLine), shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        GazeFill(shape)
        TemplatePreview(DeckDesigns.templateOf(look), Modifier.matchParentSize())
    }
}

// --- 形状格式 (contextual) -----------------------------------------------------
/**
 * PowerPoint's Shape Format tab as its narrow ribbon has it: Insert Shapes (a small gallery and Text Box), Shape Styles
 * (a style gallery; Fill, Outline and Effects), WordArt Styles (the text in a colour; Text Fill, Align Text), Arrange
 * (the layers, several picked at once, Align, Group and Rotate) and Size (Height and Width). Copy, Duplicate and Delete
 * are on the selection bar over the shape.
 */
@Composable
internal fun ShapeFormatTab(editor: SlideEditor, picker: SPicker, onPicker: (SPicker) -> Unit, onSelectionPane: () -> Unit = {}) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    SGroup(stringResource(R.string.group_insert_shapes)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RibbonMiniButton("ic_shape_rect", stringResource(R.string.shape_rectangle), { editor.addShape(ShapeType.RECT) })
                RibbonMiniButton("ic_shape_rounded", stringResource(R.string.shape_rounded_short), { editor.addShape(ShapeType.ROUNDED) })
                RibbonMiniButton("ic_shape_oval", stringResource(R.string.shape_oval), { editor.addShape(ShapeType.ELLIPSE) })
                RibbonMiniButton("ic_shape_triangle", stringResource(R.string.shape_triangle_short), { editor.addShape(ShapeType.TRIANGLE) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                RibbonMiniButton("ic_shape_star", stringResource(R.string.shape_star), { editor.addShape(ShapeType.STAR) })
                RibbonMiniButton("ic_shape_arrow", stringResource(R.string.shape_arrow), { editor.addShape(ShapeType.ARROW) })
                RibbonMiniButton("ic_shape_line", stringResource(R.string.shape_line), { editor.addShape(ShapeType.LINE) })
                RibbonMiniButton("ic_arrow_right", stringResource(R.string.shape_line_arrow), {
                    editor.addLine(end = com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE))
                })
            }
            RibbonSmallButton("ic_textbox", stringResource(R.string.text_box), { editor.addShape(ShapeType.TEXTBOX) })
        }
    }
    SGroup(stringResource(R.string.group_shape_styles)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SHAPE_STYLE_PRESETS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { st -> ShapeStyleSwatch(st) { editor.applyShapeStyle(st.fill, st.border, st.text) } }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_fill", stringResource(R.string.shape_fill), { onPicker(SPicker.SHAPEFILL) }, selected = picker == SPicker.SHAPEFILL, dropdown = true, compactAt = 3)
            RibbonSmallButton("ic_outline", stringResource(R.string.shape_outline), { onPicker(SPicker.SHAPEBORDER) }, selected = picker == SPicker.SHAPEBORDER, dropdown = true, compactAt = 3)
            RibbonSmallButton("ic_shadow", stringResource(R.string.shape_shadow), { onPicker(SPicker.SHAPESHADOW) }, selected = picker == SPicker.SHAPESHADOW, dropdown = true, compactAt = 3)
        }
    }
    SGroup(stringResource(R.string.group_wordart_styles)) {
        // The text in one of WordArt's colours, each drawn as a letter of it.
        val shape = editor.selectedShape
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(R.string.wordart_blue to 0xFF2B579A, R.string.wordart_red to 0xFFC43E1C, R.string.wordart_white to 0xFFFFFFFF).forEach { (label, c) ->
                WordArtSwatch(stringResource(label), c) { editor.setShapeTextColor(c) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var filling by remember { androidx.compose.runtime.mutableStateOf(false) }
            Box {
                RibbonSmallButton("ic_font_color", stringResource(R.string.text_fill), { filling = !filling }, selected = filling, dropdown = true, enabled = shape != null, compactAt = 2)
                if (filling) AnchoredFlyout(onDismiss = { filling = false }) {
                    ColorPalette(null, { c -> editor.setShapeTextColor(c); filling = false }, noneLabel = stringResource(R.string.automatic))
                }
            }
            RibbonSmallButton("x_valmid", stringResource(R.string.align_text), { onPicker(SPicker.ALIGNTEXT) }, selected = picker == SPicker.ALIGNTEXT, dropdown = true, enabled = shape != null, compactAt = 2)
        }
    }
    SGroup(stringResource(R.string.arrange)) {
        LayerCommands({ editor.stepShapeLayer(true) }, { editor.stepShapeLayer(false) }, editor::bringShapeToFront, editor::sendShapeToBack)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AlignMenu(editor)
            var grouping by remember { androidx.compose.runtime.mutableStateOf(false) }
            Box {
                RibbonSmallButton("ic_group", stringResource(R.string.group), { grouping = !grouping }, selected = grouping, dropdown = true)
                if (grouping) AnchoredFlyout(onDismiss = { grouping = false }) {
                    FlyoutMenu(width = 240.dp) {
                        MenuCommand(stringResource(R.string.select_multiple), { editor.toggleMultiSelect(); grouping = false }, icon = "ic_select_multiple", checked = editor.multiSelect)
                        MenuDivider()
                        MenuCommand(stringResource(R.string.group), { editor.groupSelected(); grouping = false }, icon = "ic_group", enabled = editor.selectedShapes.size > 1)
                        MenuCommand(stringResource(R.string.ungroup), { editor.ungroupSelected(); grouping = false }, icon = "ic_ungroup",
                            enabled = editor.selectedShapes.any { it.groupId != null })
                    }
                }
            }
            RotateMenu(
                listOf(R.string.rotate_right_90 to 90f, R.string.rotate_left_90 to -90f, R.string.rotate_right_15 to 15f, R.string.rotate_left_15 to -15f),
                onRotate = editor::rotateSelectedShape,
                flipV = editor.selectedShape?.flipV == true, onFlipV = editor::toggleFlipV,
                flipH = editor.selectedShape?.flipH == true, onFlipH = editor::toggleFlipH,
            )
        }
        RibbonSmallButton("ic_select_multiple", stringResource(R.string.selection_pane), onSelectionPane, compactAt = 1)
    }
    SGroup(stringResource(R.string.group_size)) { ObjectSize(editor) }
}

internal data class ShapeStylePreset(val fill: Long?, val border: Long?, val text: Long?)
internal val SHAPE_STYLE_PRESETS = listOf(
    ShapeStylePreset(0xFFFFFFFF, 0xFF8AA0BF, 0xFF1A1A1A),
    ShapeStylePreset(0xFF2B579A, 0xFF1F3A6B, 0xFFFFFFFF),
    ShapeStylePreset(0xFFB81E6A, 0xFF8A1450, 0xFFFFFFFF),
    ShapeStylePreset(0xFF217346, 0xFF155030, 0xFFFFFFFF),
    ShapeStylePreset(0xFFE8A33D, 0xFFB87A1E, 0xFF1A1A1A),
    ShapeStylePreset(0xFF1F1F1F, 0xFF000000, 0xFFFFFFFF),
)

/** A Shape Styles choice as Office draws one: the fill and outline round "Abc" in the text's colour. */
@Composable
internal fun ShapeStyleSwatch(st: ShapeStylePreset, onClick: () -> Unit) = Tooltip(stringResource(R.string.tip_shape_styles)) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier.then(a11y).width(46.dp).height(30.dp).clip(shape)
            .background(st.fill?.let { Color(it) } ?: Color.White)
            .border(BorderStroke(1.5.dp, Color(st.border ?: 0xFF8AA0BF)), shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) { GazeFill(shape); Text("Abc", color = Color(st.text ?: 0xFF1A1A1A), fontSize = 12.sp, maxLines = 1) }
}

/** A WordArt choice: a large "A" in [color] (outlined when it is white), on the field's grey. */
@Composable
private fun WordArtSwatch(label: String, color: Long, onClick: () -> Unit) = Tooltip(label) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Aero.commandRadius)
    Box(
        Modifier.then(a11y).size(40.dp, 64.dp).clip(shape).background(Aero.field)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        GazeFill(shape)
        val ink = Color(color)
        Text("A", color = ink, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            style = if (color == 0xFFFFFFFF) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), blurRadius = 3f)) else androidx.compose.ui.text.TextStyle.Default)
    }
}

/** Arrange's layer commands in two columns, as PowerPoint's narrow ribbon stacks them. */
@Composable
internal fun LayerCommands(forward: () -> Unit, backward: () -> Unit, front: () -> Unit, back: () -> Unit, compactAt: Int = 1) {
    // The four longest names on the format tabs: the first to keep only their icons when the window is narrow.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RibbonSmallButton("ic_bring_forward", stringResource(R.string.bring_forward), forward, compactAt = compactAt)
        RibbonSmallButton("ic_send_backward", stringResource(R.string.send_backward), backward, compactAt = compactAt)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RibbonSmallButton("ic_bring_front", stringResource(R.string.bring_to_front), front, compactAt = compactAt)
        RibbonSmallButton("ic_send_back", stringResource(R.string.send_to_back), back, compactAt = compactAt)
    }
}

/** Arrange ▸ Align ▾: the picked object against the slide (several shapes: against each other), or spread out evenly. */
@Composable
private fun AlignMenu(editor: SlideEditor) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        RibbonSmallButton("ic_align_objects", stringResource(R.string.align_objects), { open = !open }, selected = open, dropdown = true)
        if (open) AnchoredFlyout(onDismiss = { open = false }) {
            FlyoutMenu(width = 240.dp) {
                listOf(
                    Triple(R.string.align_left, 'L', "ic_align_left"), Triple(R.string.tip_align_center_h, 'C', "ic_align_center"), Triple(R.string.align_right, 'R', "ic_align_right"),
                ).forEach { (label, d, icon) -> MenuCommand(stringResource(label), { editor.alignShape(d); open = false }, icon = icon) }
                MenuDivider()
                listOf(
                    Triple(R.string.top_align, 'T', "x_valtop"), Triple(R.string.middle_align, 'M', "x_valmid"), Triple(R.string.bottom_align, 'B', "x_valbottom"),
                ).forEach { (label, d, icon) -> MenuCommand(stringResource(label), { editor.alignShape(d); open = false }, icon = icon) }
                if (editor.selectedShapes.size > 2) {
                    MenuDivider()
                    MenuCommand(stringResource(R.string.distribute_horizontally), { editor.distributeSelected(true); open = false }, icon = "ic_distribute_h")
                    MenuCommand(stringResource(R.string.distribute_vertically), { editor.distributeSelected(false); open = false }, icon = "ic_distribute_v")
                }
            }
        }
    }
}

/** Arrange ▸ Rotate ▾: [turns] (a label and degrees each), then Flip Vertical and Flip Horizontal (ticked when flipped). */
@Composable
internal fun RotateMenu(turns: List<Pair<Int, Float>>, onRotate: (Float) -> Unit, flipV: Boolean, onFlipV: () -> Unit, flipH: Boolean, onFlipH: () -> Unit) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        RibbonSmallButton("ic_rotate_right", stringResource(R.string.rotate_menu), { open = !open }, selected = open, dropdown = true)
        if (open) AnchoredFlyout(onDismiss = { open = false }) {
            FlyoutMenu(width = 240.dp) {
                turns.forEach { (label, deg) -> MenuCommand(stringResource(label), { onRotate(deg); open = false }, icon = if (deg > 0) "ic_rotate_right" else "ic_rotate_left") }
                if (turns.isNotEmpty()) MenuDivider()
                MenuCommand(stringResource(R.string.flip_vertical), { onFlipV(); open = false }, icon = "ic_flip_v", checked = flipV)
                MenuCommand(stringResource(R.string.flip_horizontal), { onFlipH(); open = false }, icon = "ic_flip_h", checked = flipH)
            }
        }
    }
}

/**
 * Size's Height and Width for the picked object, in centimetres or inches (as Page Setup shows lengths), half a
 * centimetre or a quarter inch a press; a picture keeps its proportions.
 */
@Composable
private fun ObjectSize(editor: SlideEditor) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val f = editor.selectedFrame()
    val slide = editor.presentation.slideSize
    val step = if (Lengths.inches) 18f else 72f / 2.54f / 2f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SizeRow("ic_shape_height", stringResource(R.string.ps_height), f?.let { it[3] * slide.heightPt }) { d ->
            f?.let { editor.setSelectedSize(null, (it[3] * slide.heightPt + d * step).coerceAtLeast(step) / slide.heightPt) }
        }
        SizeRow("ic_shape_width", stringResource(R.string.ps_width), f?.let { it[2] * slide.widthPt }) { d ->
            f?.let { editor.setSelectedSize((it[2] * slide.widthPt + d * step).coerceAtLeast(step) / slide.widthPt, null) }
        }
    }
}

@Composable
private fun SizeRow(icon: String, label: String, pt: Float?, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RibbonGlyph(icon, PicoTheme.colorScheme.labelSecondary, 18.dp)
        SpinBox(if (pt == null) "–" else Lengths.label(pt), onStep, description = label, valueWidth = 64.dp, compact = true, dim = pt == null, canLess = pt != null, canMore = pt != null)
    }
}

// --- 图片格式 (contextual) -----------------------------------------------------
/**
 * PowerPoint's Picture Format tab: Adjust (Reset Picture ▾), Picture Styles (the shadows, each drawn), Arrange (the
 * layers, Align, Rotate) and Size (Crop; Height and Width). Copy, Duplicate and Delete are on the selection bar.
 */
@Composable
internal fun PictureFormatTab(editor: SlideEditor, onSelectionPane: () -> Unit = {}) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    // Dev hook (debug builds): `setprop debug.aero.dialog crop` opens Crop on the picture it selects.
    var showCrop by remember { androidx.compose.runtime.mutableStateOf(devDialogProp() == "crop") }
    val img = editor.selectedImage
    SGroup(stringResource(R.string.group_adjust)) {
        var resetting by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            RibbonButton("ic_reset_picture", stringResource(R.string.reset_picture), { resetting = !resetting }, selected = resetting, dropdown = true)
            if (resetting) AnchoredFlyout(onDismiss = { resetting = false }) {
                FlyoutMenu(width = 260.dp) {
                    MenuCommand(stringResource(R.string.reset_picture), { editor.resetImageCrop(); resetting = false }, icon = "ic_reset_picture", enabled = img?.cropped == true)
                    MenuCommand(stringResource(R.string.reset_picture_and_size), { editor.resetImageCrop(); editor.resetImageAspect(); resetting = false }, icon = "x_resetsize")
                }
            }
        }
    }
    // Picture Styles' shadows, as Shape Effects offers them: none, or one of PowerPoint's presets.
    SGroup(stringResource(R.string.group_picture_styles)) {
        val current = img?.shadow
        ShadowSample(stringResource(R.string.no_shadow), null, img != null && current == null) { editor.setImageShadow(null) }
        listOf(R.string.shadow_bottom_right, R.string.shadow_bottom, R.string.shadow_right, R.string.shadow_soft).zip(ShapeShadow.PRESETS).forEach { (label, s) ->
            ShadowSample(stringResource(label), s, s.sameAs(current)) { editor.setImageShadow(s) }
        }
    }
    // PowerPoint's Accessibility group: the picture's Alt Text.
    var altText by remember { androidx.compose.runtime.mutableStateOf(devDialogProp() == "alttext") }
    SGroup(stringResource(R.string.group_accessibility)) {
        RibbonButton("ic_accessibility", stringResource(R.string.alt_text), { altText = true }, enabled = img != null)
    }
    if (altText && img != null) AltTextDialog(img.altText, img.decorative, onDismiss = { altText = false }) { t, d -> editor.setImageAltText(t, d); altText = false }
    SGroup(stringResource(R.string.arrange)) {
        LayerCommands({ editor.stepImageLayer(true) }, { editor.stepImageLayer(false) }, editor::bringImageToFront, editor::sendImageToBack)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AlignMenu(editor)
            RotateMenu(
                listOf(R.string.rotate_right_90 to 90f, R.string.rotate_left_90 to -90f),
                onRotate = editor::rotateSelectedImage,
                flipV = img?.flipV == true, onFlipV = editor::toggleImageFlipV,
                flipH = img?.flipH == true, onFlipH = editor::toggleImageFlipH,
            )
        }
        RibbonSmallButton("ic_select_multiple", stringResource(R.string.selection_pane), onSelectionPane, compactAt = 1)
    }
    SGroup(stringResource(R.string.group_size)) {
        RibbonButton("x_crop", stringResource(R.string.crop), { showCrop = true })
        ObjectSize(editor)
    }
    img?.takeIf { showCrop }?.let { CropDialog(it.data, it.aspect, it, onCrop = editor::cropImage, onDismiss = { showCrop = false }) }
}

// --- 表格设计 (contextual) -----------------------------------------------------
/** PowerPoint's Table Design tab: Table Style Options (six check boxes) and the Table Styles gallery. */
@Composable
internal fun TableDesignTab(editor: SlideEditor, picker: SPicker = SPicker.NONE, onPicker: (SPicker) -> Unit = {}) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val t = editor.selectedTable
    SGroup(stringResource(R.string.group_table_style_options)) {
        Column {
            CheckOption(stringResource(R.string.table_header_row), t?.headerRow == true, { editor.toggleTableOpt("header") }, small = true)
            CheckOption(stringResource(R.string.table_total_row), t?.totalRow == true, { editor.toggleTableOpt("total") }, small = true)
            CheckOption(stringResource(R.string.table_banded_rows), t?.bandedRows == true, { editor.toggleTableOpt("bandedRows") }, small = true)
        }
        Column {
            CheckOption(stringResource(R.string.table_first_column), t?.firstColumn == true, { editor.toggleTableOpt("firstCol") }, small = true)
            CheckOption(stringResource(R.string.table_last_column), t?.lastColumn == true, { editor.toggleTableOpt("lastCol") }, small = true)
            CheckOption(stringResource(R.string.table_banded_columns), t?.bandedColumns == true, { editor.toggleTableOpt("bandedCols") }, small = true)
        }
    }
    SGroup(stringResource(R.string.group_table_styles)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TABLE_STYLES.withIndex().chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (i, ts) -> TableStyleSwatch(ts, selected = t?.styleIndex == i) { editor.setTableStyle(i) } }
                }
            }
        }
        // PowerPoint's Shading and Borders beside the gallery: the cell being typed in, else every cell of the picked table.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_fill", stringResource(R.string.shading), { onPicker(SPicker.TABLESHADING) }, selected = picker == SPicker.TABLESHADING, dropdown = true,
                enabled = t != null, compactAt = 2)
            RibbonSmallButton("x_bdall", stringResource(R.string.borders), { onPicker(SPicker.TABLEBORDERS) }, selected = picker == SPicker.TABLEBORDERS, dropdown = true,
                enabled = t != null, compactAt = 2)
        }
    }
    // Draw Borders: the line Borders ▾ draws — its kind, weight and colour.
    SGroup(stringResource(R.string.group_draw_borders)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_pen_style", stringResource(R.string.pen_style), { onPicker(SPicker.PENSTYLE) }, selected = picker == SPicker.PENSTYLE, dropdown = true, compactAt = 1)
            RibbonSmallButton("ic_pen_weight", stringResource(R.string.pen_weight), { onPicker(SPicker.PENWEIGHT) }, selected = picker == SPicker.PENWEIGHT, dropdown = true, compactAt = 1)
            RibbonSmallButton("ic_pen_color", stringResource(R.string.pen_color), { onPicker(SPicker.PENCOLOR) }, selected = picker == SPicker.PENCOLOR, dropdown = true, compactAt = 1)
        }
    }
}

/** The pen weights PowerPoint offers (points). */
internal val SLIDE_PEN_WEIGHTS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2.25f, 3f, 4.5f, 6f)

/** "1 pt", "2.25 pt" — a pen weight as the ribbon names it. */
@Composable
internal fun penWeightLabel(w: Float): String =
    stringResource(R.string.pen_weight_pt, if (w == Math.floor(w.toDouble()).toFloat()) w.toInt().toString() else w.toString())

/** One choice of Pen Style ▾ / Pen Weight ▾: what it draws, the current one tinted in the module's colour. */
@Composable
internal fun PenRow(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(Aero.tint(LocalAccent.current)) else Modifier)
            .press(onClickLabel = label, onClick = onClick)
            .then(Modifier.semantics { this.selected = selected })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

/** The pen kinds Pen Style ▾ lists. */
internal val SLIDE_PEN_STYLES = listOf(
    com.therealityteam.aeropages.model.LineStyle.THIN, com.therealityteam.aeropages.model.LineStyle.DOTTED, com.therealityteam.aeropages.model.LineStyle.DASHED,
    com.therealityteam.aeropages.model.LineStyle.DASH_DOT, com.therealityteam.aeropages.model.LineStyle.DASH_DOT_DOT, com.therealityteam.aeropages.model.LineStyle.DOUBLE,
)

/** A pen line drawn as a sample: [style] at [weightDp], in [ink]. */
@Composable
internal fun PenSample(style: com.therealityteam.aeropages.model.LineStyle, weightDp: Float, ink: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.height(14.dp)) {
        val y = size.height / 2f
        val w = weightDp.dp.toPx().coerceAtLeast(1f)
        if (style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) {
            drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y - w), androidx.compose.ui.geometry.Offset(size.width, y - w), w)
            drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y + w), androidx.compose.ui.geometry.Offset(size.width, y + w), w)
        } else drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), w,
            pathEffect = style.dash?.let { d -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * w * 1.5f }) })
    }
}

/** A Borders ▾ preset's icon: a faint 2×2 grid with the lines it draws, in the pen's colour. */
@Composable
internal fun SlideBorderIcon(preset: SlideEditor.TableBorders, pen: Color, ink: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val t = 1.5.dp.toPx()
        val faint = ink.copy(alpha = 0.3f)
        val o = androidx.compose.ui.geometry.Offset.Zero
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: Color, width: Float) =
            drawLine(c, o + androidx.compose.ui.geometry.Offset(x1, y1), o + androidx.compose.ui.geometry.Offset(x2, y2), width)
        line(0f, 0f, w, 0f, faint, 1f); line(0f, h, w, h, faint, 1f); line(0f, 0f, 0f, h, faint, 1f); line(w, 0f, w, h, faint, 1f)
        line(w / 2f, 0f, w / 2f, h, faint, 1f); line(0f, h / 2f, w, h / 2f, faint, 1f)
        val top = { line(0f, t / 2, w, t / 2, pen, t) }
        val bottom = { line(0f, h - t / 2, w, h - t / 2, pen, t) }
        val left = { line(t / 2, 0f, t / 2, h, pen, t) }
        val right = { line(w - t / 2, 0f, w - t / 2, h, pen, t) }
        val insideH = { line(0f, h / 2f, w, h / 2f, pen, t) }
        val insideV = { line(w / 2f, 0f, w / 2f, h, pen, t) }
        when (preset) {
            SlideEditor.TableBorders.NONE -> {}
            SlideEditor.TableBorders.ALL -> { top(); bottom(); left(); right(); insideH(); insideV() }
            SlideEditor.TableBorders.OUTSIDE -> { top(); bottom(); left(); right() }
            SlideEditor.TableBorders.INSIDE -> { insideH(); insideV() }
            SlideEditor.TableBorders.TOP -> top(); SlideEditor.TableBorders.BOTTOM -> bottom()
            SlideEditor.TableBorders.LEFT -> left(); SlideEditor.TableBorders.RIGHT -> right()
            SlideEditor.TableBorders.INSIDE_H -> insideH(); SlideEditor.TableBorders.INSIDE_V -> insideV()
        }
    }
}

@Composable
internal fun TableStyleSwatch(ts: TableStyle, selected: Boolean, onClick: () -> Unit) = Tooltip(stringResource(R.string.tip_table_styles)) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(4.dp)
    val accent = LocalAccent.current
    Box(
        modifier = Modifier.then(a11y).width(54.dp).height(36.dp).clip(shape)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Color(0x1F000000)), shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction),
    ) {
        GazeFill(shape)
        // A small table: its header row, then plain and banded rows, ruled into columns.
        Column(Modifier.fillMaxSize().padding(if (selected) 2.dp else 1.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(ts.header)))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(ts.plain)))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(ts.band)))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(ts.plain)))
        }
    }
}

// --- 布局 (table layout, contextual) ------------------------------------------
/**
 * PowerPoint's table Layout tab: Rows & Columns (Delete ▾ — columns, rows, the table —, Insert Above large, the other
 * three inserts stacked), Alignment (the cells' text left, centred, right) and Table Size (Height and Width).
 */
@Composable
internal fun TableLayoutTab(editor: SlideEditor) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    // Table: Select ▾ — the cell, its column, its row or the whole table, for Merge Cells, Shading, Borders and Alignment.
    SGroup(stringResource(R.string.table)) {
        var picking by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            RibbonButton("ic_select_multiple", stringResource(R.string.select_menu), { picking = !picking }, selected = picking, dropdown = true)
            if (picking) AnchoredFlyout(onDismiss = { picking = false }) {
                FlyoutMenu(width = 220.dp) {
                    listOf(SlideEditor.TableSelect.CELL to R.string.select_cell, SlideEditor.TableSelect.COLUMN to R.string.select_column,
                        SlideEditor.TableSelect.ROW to R.string.select_row, SlideEditor.TableSelect.TABLE to R.string.select_table).forEach { (kind, label) ->
                        MenuCommand(stringResource(label), { editor.selectTableCells(kind); picking = false })
                    }
                }
            }
        }
    }
    SGroup(stringResource(R.string.group_rows_columns)) {
        var deleting by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            RibbonButton("ic_delete", stringResource(R.string.delete_menu), { deleting = !deleting }, selected = deleting, dropdown = true)
            if (deleting) AnchoredFlyout(onDismiss = { deleting = false }) {
                FlyoutMenu(width = 220.dp) {
                    MenuCommand(stringResource(R.string.delete_column), { editor.deleteTableColumn(); deleting = false }, icon = "ic_delete_col")
                    MenuCommand(stringResource(R.string.delete_row), { editor.deleteTableRow(); deleting = false }, icon = "ic_delete_row")
                    MenuCommand(stringResource(R.string.delete_table), { editor.deleteSelectedTable(); deleting = false }, icon = "ic_table")
                }
            }
        }
        RibbonButton("ic_insert_row_above", stringResource(R.string.insert_row_above), { editor.addTableRow(false) })
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_insert_row_below", stringResource(R.string.insert_row_below), { editor.addTableRow(true) })
            RibbonSmallButton("ic_insert_col_left", stringResource(R.string.insert_column_left), { editor.addTableColumn(left = true) })
            RibbonSmallButton("ic_insert_col_right", stringResource(R.string.insert_column_right), { editor.addTableColumn() })
        }
    }
    // Merge: a merged cell (from PowerPoint or LibreOffice) back into its places.
    SGroup(stringResource(R.string.group_merge)) {
        RibbonButton("ic_merge_cells", stringResource(R.string.merge_cells_only), { editor.mergeTableCells() }, enabled = editor.canMergeTableCells)
        RibbonButton("x_split", stringResource(R.string.split_cells), { editor.splitTableCell() }, enabled = editor.canSplitTableCell)
    }
    SGroup(stringResource(R.string.group_alignment)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_align_left", stringResource(R.string.align_left), { editor.setAlign(TextAlign.LEFT) })
            RibbonSmallButton("ic_align_center", stringResource(R.string.center), { editor.setAlign(TextAlign.CENTER) })
            RibbonSmallButton("ic_align_right", stringResource(R.string.align_right), { editor.setAlign(TextAlign.RIGHT) })
        }
        // PowerPoint's second column: where the text sits in the cell, top to bottom (the cell typed in, else the table).
        val anchor = editor.tableCellAnchor
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_valign_top", stringResource(R.string.cell_align_top), { editor.setTableCellAnchor(com.therealityteam.aeropages.model.ShapeAnchor.TOP) },
                selected = anchor == com.therealityteam.aeropages.model.ShapeAnchor.TOP, compactAt = 2)
            RibbonSmallButton("ic_valign_middle", stringResource(R.string.cell_center_vertically), { editor.setTableCellAnchor(com.therealityteam.aeropages.model.ShapeAnchor.CENTER) },
                selected = anchor == com.therealityteam.aeropages.model.ShapeAnchor.CENTER, compactAt = 2)
            RibbonSmallButton("ic_valign_bottom", stringResource(R.string.cell_align_bottom), { editor.setTableCellAnchor(com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM) },
                selected = anchor == com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM, compactAt = 2)
        }
    }
    // Cell Size: the row and column of the cell being typed in (else the first) — their height and width — and
    // Distribute Rows / Columns.
    SGroup(stringResource(R.string.group_cell_size)) {
        val step = if (Lengths.inches) 9f else 72f / 2.54f / 5f   // 0.125 in / 0.2 cm a click
        val rowH = editor.tableRowHeightPt
        val colW = editor.tableColWidthPt
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SizeRow("ic_shape_height", stringResource(R.string.ps_height), rowH) { d -> rowH?.let { editor.setTableRowHeight(it + d * step) } }
            SizeRow("ic_shape_width", stringResource(R.string.ps_width), colW) { d -> colW?.let { editor.setTableColWidth(it + d * step) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RibbonSmallButton("ic_distribute_rows", stringResource(R.string.distribute_rows), { editor.distributeTable(rows = true) }, enabled = rowH != null, compactAt = 2)
            RibbonSmallButton("ic_distribute_columns", stringResource(R.string.distribute_columns), { editor.distributeTable(rows = false) }, enabled = colW != null, compactAt = 2)
        }
    }
    SGroup(stringResource(R.string.group_size)) { ObjectSize(editor) }
}

// --- 图表设计 (contextual) -----------------------------------------------------
/**
 * PowerPoint's Chart Design tab: Chart Layouts (Add Chart Element ▾ — legend, gridlines, data labels, the titles and
 * axes —), Chart Styles (the colour sets), Data (Switch Row/Column, Edit Data), Type (Change Chart Type ▾) and the
 * chart seen in space.
 */
@Composable
internal fun ChartDesignTab(editor: SlideEditor, onEditChartData: () -> Unit, onSpatial3D: () -> Unit = {}, spatial3DOpen: Boolean = false) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val c = editor.selectedChart
    var showElements by remember { androidx.compose.runtime.mutableStateOf(false) }
    SGroup(stringResource(R.string.group_chart_layouts)) {
        var adding by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            RibbonButton("ic_chart_add", stringResource(R.string.add_chart_element), { adding = !adding }, selected = adding, dropdown = true)
            if (adding) AnchoredFlyout(onDismiss = { adding = false }) {
                FlyoutMenu(width = 260.dp) {
                    MenuCommand(stringResource(R.string.chart_legend), editor::toggleChartLegend, checked = c?.showLegend == true)
                    MenuCommand(stringResource(R.string.gridlines), editor::toggleChartGrid, checked = c?.showGrid == true)
                    MenuCommand(stringResource(R.string.chart_data_labels), editor::toggleChartValues, checked = c?.showValues == true)
                    MenuDivider()
                    MenuCommand(stringResource(R.string.chart_elements) + "…", { adding = false; showElements = true }, icon = "ic_chart")
                }
            }
        }
    }
    if (showElements && c != null) ChartElementsDialog(c, onDismiss = { showElements = false }) { t, cat, v, legend, pos, axis ->
        editor.setChartElements(t, cat, v, legend, pos, axis); showElements = false
    }
    SGroup(stringResource(R.string.group_chart_styles)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CHART_PALETTES.withIndex().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (i, pal) -> ChartPaletteSwatch(pal, selected = c?.colorSet == i) { editor.setChartColorSet(i) } }
                }
            }
        }
    }
    SGroup(stringResource(R.string.group_table_data)) {
        RibbonButton("ic_switch_row_col", stringResource(R.string.switch_row_column), editor::switchChartRowColumn)
        RibbonButton("ic_edit_data", stringResource(R.string.edit_chart_data_2), onEditChartData)
    }
    SGroup(stringResource(R.string.group_chart_type)) {
        var typing by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            RibbonButton(CHART_TYPES.firstOrNull { it.first == c?.type }?.second?.second ?: "ic_chart", stringResource(R.string.change_chart_type), { typing = !typing },
                selected = typing, dropdown = true)
            if (typing) AnchoredFlyout(onDismiss = { typing = false }) {
                FlyoutMenu(width = 220.dp) {
                    CHART_TYPES.forEach { (type, lbl) -> MenuCommand(lbl.first, { editor.setChartType(type); typing = false }, icon = lbl.second, checked = c?.type == type) }
                }
            }
        }
    }
    SGroup(stringResource(R.string.group_spatial)) {
        RibbonButton("ic_cube", stringResource(R.string.chart_3d_spatial_view), onSpatial3D, selected = spatial3DOpen)
    }
}

internal val CHART_TYPES get() = listOf(
    com.therealityteam.aeropages.model.ChartType.COLUMN to (Strings.get(R.string.chart_type_column_2) to "ic_chart"),
    com.therealityteam.aeropages.model.ChartType.BAR to (Strings.get(R.string.chart_type_bar) to "ic_chart_bar"),
    com.therealityteam.aeropages.model.ChartType.LINE to (Strings.get(R.string.chart_type_line_2) to "ic_chart_line"),
    com.therealityteam.aeropages.model.ChartType.PIE to (Strings.get(R.string.chart_type_pie) to "ic_chart_pie"),
    com.therealityteam.aeropages.model.ChartType.AREA to (Strings.get(R.string.chart_type_area_2) to "ic_chart_area"),
    com.therealityteam.aeropages.model.ChartType.SCATTER to (Strings.get(R.string.chart_type_scatter) to "ic_chart_scatter"),
    com.therealityteam.aeropages.model.ChartType.DOUGHNUT to (Strings.get(R.string.chart_type_doughnut) to "ic_chart_doughnut"),
    com.therealityteam.aeropages.model.ChartType.RADAR to (Strings.get(R.string.chart_type_radar) to "ic_chart_radar"),
)

@Composable
internal fun ChartPaletteSwatch(palette: List<Long>, selected: Boolean, onClick: () -> Unit) = Tooltip(stringResource(R.string.tip_change_colors)) { a11y ->
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(3.dp)
    Row(
        modifier = Modifier.then(a11y).height(34.dp).clip(shape)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) LocalAccent.current else Color(0x1F000000)), shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        palette.take(5).forEach { Box(Modifier.size(8.dp, 22.dp).background(Color(it))) ; Box(Modifier.width(1.dp)) }
    }
}

/** Lightweight accessibility check: flags slides missing titles / images without context. */
internal fun todayString(): String {
    val c = java.util.Calendar.getInstance()
    return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
}


/** [ms] as seconds the way the ribbon shows them: 0.25, 0.5, 1, 2. */
internal fun seconds(ms: Int): String = if (ms % 1000 == 0) (ms / 1000).toString() else (ms / 1000.0).toString().trimEnd('0').trimEnd('.')

/** A slide layout drawn as a small slide (title bars, text lines), with its name: the Layout and New Slide galleries. */
@Composable
private fun LayoutCard(layout: SlideLayout, selected: Boolean, onClick: () -> Unit) {
    val name = stringResource(when (layout) {
        SlideLayout.TITLE -> R.string.layout_title_slide
        SlideLayout.TITLE_CONTENT -> R.string.layout_title_content
        SlideLayout.SECTION -> R.string.layout_section_header
        SlideLayout.BLANK -> R.string.layout_blank
    })
    val ink = PicoTheme.colorScheme.labelTertiary
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(if (selected) LocalAccent.current.copy(alpha = 0.16f) else Color.Transparent)
            .press(onClick = onClick).padding(6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(88.dp, 50.dp)
            .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) LocalAccent.current else PicoTheme.colorScheme.dividerLine, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))) {
            val w = size.width; val h = size.height
            fun bar(x: Float, y: Float, bw: Float, bh: Float) = drawRect(ink, androidx.compose.ui.geometry.Offset(x * w, y * h), androidx.compose.ui.geometry.Size(bw * w, bh * h))
            fun line(x: Float, y: Float, lw: Float) = drawRect(ink.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(x * w, y * h), androidx.compose.ui.geometry.Size(lw * w, 0.05f * h))
            when (layout) {
                SlideLayout.TITLE -> { bar(0.18f, 0.34f, 0.64f, 0.14f); line(0.3f, 0.6f, 0.4f) }
                SlideLayout.TITLE_CONTENT -> {
                    bar(0.12f, 0.12f, 0.76f, 0.12f)
                    for ((i, lw) in listOf(0.6f, 0.5f, 0.56f).withIndex()) {
                        val y = 0.4f + i * 0.17f
                        drawCircle(ink.copy(alpha = 0.5f), 0.025f * h + 1f, androidx.compose.ui.geometry.Offset(0.15f * w, (y + 0.025f) * h))
                        line(0.2f, y, lw)
                    }
                }
                SlideLayout.SECTION -> { bar(0.12f, 0.46f, 0.6f, 0.13f); line(0.12f, 0.68f, 0.42f) }
                SlideLayout.BLANK -> {}
            }
        }
        Text(name, fontSize = 11.sp, color = if (selected) LocalAccent.current else PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
}

/** The deck's themes as swatches: the designed looks first, then the first [plain] colour themes. */
@Composable
private fun ThemeSwatches(editor: SlideEditor, plain: Int) {
    DeckDesigns.ALL.forEach { look ->
        DesignedThemeSwatch(look, selected = editor.presentation.themeIndex == DeckDesigns.indexOf(look)) {
            editor.applyDeckDesign(DeckDesigns.indexOf(look), DeckDesigns.cover(look), DeckDesigns.content(look), look.background, look.title, look.body,
                DeckDesigns.COVER_TITLE, DeckDesigns.COVER_BODY, DeckDesigns.NAMES)
        }
    }
    SLIDE_THEMES.take(plain).forEachIndexed { i, th ->
        ThemeSwatch(th, selected = editor.presentation.themeIndex == i) {
            editor.applyThemeFull(i, th.bg, th.title, th.body, null, DeckDesigns.NAMES)
        }
    }
}

/**
 * PowerPoint's Replace Fonts: a font the presentation uses (Replace) and the font to use instead (With) — everywhere,
 * the master and designs too; the dialog stays for another, as PowerPoint's does, and says how many places changed.
 */
@Composable
internal fun ReplaceFontsDialog(editor: SlideEditor, onStatus: (String) -> Unit, onDismiss: () -> Unit) {
    var used by remember { mutableStateOf(editor.fontsUsed()) }
    var from by remember { mutableStateOf(used.firstOrNull().orEmpty()) }
    var to by remember { mutableStateOf(TextStyling.FONT_FAMILIES.firstOrNull { !it.equals(from, true) } ?: "Calibri") }
    AeroDialog(title = stringResource(R.string.replace_fonts), onDismiss = onDismiss, width = 400.dp, buttons = {
        DialogButton(stringResource(R.string.close), onDismiss)
        DialogButton(stringResource(R.string.replace), {
            val n = editor.replaceFont(from, to)
            onStatus(Strings.get(R.string.status_fonts_replaced, n))
            used = editor.fontsUsed(); from = used.firstOrNull { it.equals(to, true) } ?: used.firstOrNull().orEmpty()
        }, primary = true, enabled = from.isNotEmpty() && !from.equals(to, ignoreCase = false))
    }) {
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (used.isEmpty()) { Text(stringResource(R.string.replace_fonts_none), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary); return@Column }
            DialogLabel(stringResource(R.string.replace_fonts_from))
            ComboField(from, used, { it }, from, { from = it }, width = 360.dp)
            DialogLabel(stringResource(R.string.replace_fonts_with))
            ComboField(to, TextStyling.FONT_FAMILIES, { it }, to, { to = it }, width = 360.dp)
        }
    }
}
