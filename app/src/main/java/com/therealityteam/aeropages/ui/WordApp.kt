package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.Subwindow
import com.pico.spatial.ui.design.windows.SubwindowPlacement
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.DocumentRepository
import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

/**
 * Pages (Word) module root: tabbed ribbon + zoomable page canvas + status bar, with
 * dialogs. [openPath] (if given) is opened on entry; otherwise a blank document is
 * created. [onHome] returns to the Aero Desk launcher. The file lifecycle (save /
 * autosave / drafts / unsaved guard / recents) lives in [DocumentSession].
 */
@Composable
fun WordApp(openPath: String? = null, template: DeskTemplate? = null, windowId: String = OpenRequests.MAIN, onHome: () -> Unit = {}) {
    val context = LocalContext.current
    val openSideWindow = LocalOpenSideWindow.current
    val scope = rememberCoroutineScope()
    val clipboard = rememberTextClipboard()
    val editor = remember { DocumentEditor().also { it.changeReviewer(loadReviewer(context)) } }
    val workDir = remember { (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() } }
    val session = remember {
        DocumentSession(
            context, scope, workDir, com.therealityteam.aeropages.format.DeskKind.PAGES, "docx",
            listOf("docx", "odt", "txt"), ".draft-pages.docx", Strings.get(R.string.document_label),
            object : DocumentSession.Adapter<Document> {
                override val dirty: Boolean get() = editor.dirty
                override val editVersion: Long get() = editor.saveState.version
                override val sourcePath: String? get() = editor.document.sourcePath
                override fun snapshot(): Document = editor.document.copyOf()
                override fun live(): Document = editor.document
                override fun write(model: Document, out: java.io.OutputStream, ext: String) = when (ext) {
                    "odt" -> com.therealityteam.aeropages.format.odf.OdtWriter.write(model, out)
                    "txt" -> out.write(model.blocks.filterIsInstance<com.therealityteam.aeropages.model.ParagraphBlock>()
                        .joinToString("\n") { it.paragraph.plainText() }.toByteArray())
                    else -> com.therealityteam.aeropages.format.docx.DocxWriter.write(model, out)
                }
                override fun read(path: String): Document = DocumentRepository.open(path)
                override fun load(model: Document, recovered: Boolean) = editor.load(model, recovered)
                override fun detach(model: Document) { model.sourcePath = null }
                override fun markSaved(path: String, clean: Boolean) = editor.markSaved(path, clean)
                override fun markSaved() = editor.markSaved()
                override fun losses(ext: String) =
                    if (ext == "txt") com.therealityteam.aeropages.format.FormatLosses.text(editor.document) else emptySet()
            },
            windowId = windowId,
        )
    }
    var images by remember { mutableStateOf(listImages(workDir)) }
    var showFind by remember { mutableStateOf(devDialogProp() in setOf("findpanel", "replacepanel", "gotopanel")) }
    var findTab by remember { mutableStateOf(when (devDialogProp()) { "replacepanel" -> FindTab.REPLACE; "gotopanel" -> FindTab.GOTO; else -> FindTab.FIND }) }
    var showImage by remember { mutableStateOf(false) }
    // Panel layout is remembered per module (LayoutPrefs), so the window reopens the way it was left.
    val view = rememberPagesView()
    var showNav by rememberLayoutFlag("pages.nav")
    // With neither the find panel nor the navigation pane open nothing is searched for (the page's shading goes).
    LaunchedEffect(showFind, showNav) { if (!showFind && !showNav) editor.setSearch("") }
    var showWordCount by remember { mutableStateOf(false) }
    // Review ▸ Compare: the dialog, the browser picking the revised document, the pick and the label.
    var compareOpen by remember { mutableStateOf(devDialogProp() in setOf("compare", "comparemore")) }
    var compareBrowse by remember { mutableStateOf(false) }
    var compareExpanded by remember(compareOpen) { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "comparemore") }
    var compareRevised by remember { mutableStateOf<String?>(null) }
    var compareLabel by remember { mutableStateOf("") }
    // Layout ▸ Page Setup (dev hooks: `pagesetup`, `pagesetupgrid`, `pagesetuplayout`).
    // References → Insert Caption, Cross-reference (dev hooks `insertcaption`, `captionchapter` — with the chapter's number —, `crossref`).
    var showCaption by remember { mutableStateOf(devDialogProp() == "insertcaption" || devDialogProp() == "captionchapter") }
    // Insert → Symbol → More Symbols… (`dialog symbols`).
    var showSymbols by remember { mutableStateOf(devDialogProp() == "symbols") }
    // File → Info (`dialog fileinfo`).
    var showInfo by remember { mutableStateOf(devDialogProp() == "fileinfo") }
    // Dev hook: `filemenu` opens the File backstage once the session exists.
    androidx.compose.runtime.LaunchedEffect(Unit) { if (devDialogProp() == "filemenu") session.showFileMenu = true }
    // References → Insert Table of Figures (`dialog tableoffigures`).
    var showTof by remember { mutableStateOf(devDialogProp() == "tableoffigures") }
    var showCrossRef by remember { mutableStateOf(devDialogProp() == "crossref") }
    var pageSetupTab by remember { mutableStateOf(when (devDialogProp()) { "pagesetup" -> PageSetupTab.MARGINS; "pagesetupgrid" -> PageSetupTab.GRID; "pagesetuplayout" -> PageSetupTab.LAYOUT; else -> null }) }
    var showShortcuts by remember { mutableStateOf(devDialogProp() == "shortcuts") }
    // Layout ▸ Tabs… or a double-click on a ruler's tab stop (`dialog tabsdialog`).
    var showTabs by remember { mutableStateOf(false) }
    var fullWidth by rememberLayoutFlag("pages.fullWidth")
    // View ▸ Focus: the document alone (`dialog focus` turns it on two seconds after opening). Word does not keep it.
    var focus by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (devDialogProp() == "focus") { kotlinx.coroutines.delay(2000); focus = true } }
    var showComments by rememberLayoutFlag("pages.comments")
    var spatialComments by rememberLayoutFlag("pages.spatialComments")
    var showProofing by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    // The link dialog opens on "Place in this document" (dev hook `linkplaces`).
    var linkInDocument by remember { mutableStateOf(false) }
    /** Insert Hyperlink: with the caret in a link (nothing selected) it changes that link, its text selected first. */
    fun openLinkDialog() {
        if (!editor.hasSpan && editor.selStart == editor.selEnd) editor.linkAtCaret()?.let { (_, r) -> editor.setSelection(editor.active, r.first, r.last + 1) }
        showLink = true
    }
    /**
     * Follows a link: to a place in this document; to a document beside this one (a relative or `file:`
     * link — opened in a window of its own); else to whatever app opens the address.
     */
    fun openLink(target: String) {
        if (editor.followLink(target)) return
        if (com.therealityteam.aeropages.model.Bookmarks.targetOf(target) != null) { session.status = Strings.get(R.string.link_missing_place); return }
        val path = target.substringBefore('#')
        val local = when {
            path.startsWith("file:", ignoreCase = true) -> runCatching { path.toUri().path }.getOrNull()?.let { File(it) }
            !path.contains(':') && path.isNotBlank() -> editor.document.sourcePath?.let { File(it).parentFile }?.let { dir ->
                File(dir, runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)) }
            else -> null
        }
        if (local != null && local.isFile && com.therealityteam.aeropages.format.DeskFiles.kindOfOrNull(local.name) != null) {
            openSideWindow(local.path); return
        }
        if (!openExternalUrl(context, target)) session.status = Strings.get(R.string.link_no_app)
    }
    // The equation dialog and what it opens on: a new equation, a displayed one, or the one in the text at the caret.
    var equationEdit by remember { mutableStateOf<EquationEdit?>(null) }
    fun openEquation() {
        val block = editor.selectedEquation
        val inline = editor.inlineMathAtCaret
        equationEdit = when {
            block != null -> EquationEdit(com.therealityteam.aeropages.model.MathLatex.write(block.math), block, inline = false)
            inline != null -> EquationEdit(com.therealityteam.aeropages.model.MathLatex.write(inline.math), null, inline = true)
            else -> EquationEdit("", null, inline = false)
        }
    }
    LaunchedEffect(editor.equationEditRequest) { if (editor.equationEditRequest > 0) openEquation() }
    // The picked chart's data editor (its Edit Data action on the page).
    var chartData by remember { mutableStateOf<com.therealityteam.aeropages.model.ChartBlock?>(null) }
    LaunchedEffect(editor.chartDataRequest) { if (editor.chartDataRequest > 0) chartData = editor.selectedChart }
    var showFootnotes by rememberLayoutFlag("pages.footnotes")
    var showSources by rememberLayoutFlag("pages.sources")
    // Word's Styles pane (Home ▸ Styles' launcher; dev hook `stylespane`), for this session only, and the Modify Style it opens.
    var showStyles by remember { mutableStateOf(devDialogProp() == "stylespane" || devDialogProp() == "ownstyles") }
    // The Office Clipboard pane (Home ▸ Clipboard's launcher; dev hook `clipboardpane`), for this session only.
    var showClipboard by remember { mutableStateOf(devDialogProp() == "clipboardpane") }
    // References ▸ Mark Entry: the Mark Index Entry pane (dev hooks `markentry`, `indexpane`), for this session only.
    var showIndex by remember { mutableStateOf(devDialogProp() == "markentry" || devDialogProp() == "indexpane") }
    var insertIndexOpen by remember { mutableStateOf(devDialogProp() == "insertindex") }
    // Check Accessibility from the status bar.
    var a11yOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // The hook collects the first paragraphs, copied one after another, for the pane to show.
        if (devDialogProp() == "clipboardpane") {
            kotlinx.coroutines.delay(2_500)
            editor.document.paragraphs().map { it.plainText().trim() }.filter { it.isNotEmpty() }.take(4).reversed().forEach { ClipboardHistory.record(it) }
        }
    }
    var modifyingStyle by remember { mutableStateOf<com.therealityteam.aeropages.model.ParagraphStyle?>(null) }
    // The task pane in front of the right side window: the one opened (or asked for) last.
    var frontPane by remember {
        mutableStateOf(when { showIndex -> SidePane.INDEX; showClipboard -> SidePane.CLIPBOARD; showStyles -> SidePane.STYLES; showFind -> SidePane.FIND; showComments -> SidePane.COMMENTS; showFootnotes -> SidePane.FOOTNOTES; else -> SidePane.SOURCES })
    }
    fun isOpen(p: SidePane) = when (p) { SidePane.FIND -> showFind; SidePane.COMMENTS -> showComments; SidePane.FOOTNOTES -> showFootnotes; SidePane.SOURCES -> showSources; SidePane.STYLES -> showStyles; SidePane.CLIPBOARD -> showClipboard; SidePane.INDEX -> showIndex }
    fun setOpen(p: SidePane, on: Boolean) = when (p) {
        SidePane.FIND -> showFind = on; SidePane.COMMENTS -> showComments = on; SidePane.FOOTNOTES -> showFootnotes = on; SidePane.SOURCES -> showSources = on
        SidePane.STYLES -> showStyles = on
        SidePane.CLIPBOARD -> showClipboard = on
        SidePane.INDEX -> showIndex = on
    }
    fun shownPane(): SidePane? = frontPane.takeIf(::isOpen) ?: SidePane.entries.lastOrNull(::isOpen)
    // A ribbon toggle for a pane: one open behind another comes to the front; otherwise it opens (in front) or closes.
    fun togglePane(p: SidePane) {
        if (isOpen(p) && shownPane() != p) frontPane = p
        else { setOpen(p, !isOpen(p)); if (isOpen(p)) frontPane = p }
    }
    // Ctrl+H / Ctrl+G and the ribbon's Replace / Go To open the find panel on that tab.
    LaunchedEffect(editor.findPanelRequest) {
        val t = editor.findPanelRequest ?: return@LaunchedEffect
        findTab = FindTab.entries[t]; showFind = true; frontPane = SidePane.FIND; editor.findPanelRequest = null
    }
    // A comment's mark in the margin tapped: the pane opens on it.
    LaunchedEffect(editor.commentsPaneRequest) { if (editor.commentsPaneRequest > 0) { showComments = true; frontPane = SidePane.COMMENTS } }

    session.Effects()

    // Clipboard actions shared by the ribbon's Clipboard group and the selection's context bar.
    // With no text selected, a picked chart is what Copy and Cut take (for any module's paste).
    val doCopy: () -> Unit = {
        val t = editor.selectedText()
        if (t.isNotEmpty()) { copyText(editor, clipboard); session.status = Strings.get(R.string.status_copied) }
        else if (editor.copySelectedObject() != null) { clipboard.copyCurrentObject(); session.status = Strings.get(R.string.status_copied) }
    }
    val doCut: () -> Unit = {
        val t = editor.selectedText()
        if (t.isNotEmpty()) { copyText(editor, clipboard); editor.deleteSelection(); session.status = Strings.get(R.string.status_cut) }
        else if (editor.cutSelectedObject() != null) { clipboard.copyCurrentObject(); session.status = Strings.get(R.string.status_cut) }
    }
    // What was copied last: an object from any module (a picture, a shape, a chart, a slide's table)
    // after the caret's paragraph, else the text — tab-separated text (a Sheets selection) as a table.
    val doPaste: () -> Unit = {
        clipboard.pasteObjectOrText(onObject = { editor.pasteItem(it) }, onText = { text ->
            // Text copied in Pages: pasted with its formatting (Word's Keep Source Formatting), Paste Options after it.
            val rich = com.therealityteam.aeropages.editor.DocClipboard.matching(text)
            if (rich != null) editor.pasteClip(rich)
            else if (text.contains('\t') && editor.insertTableFromTsv(text)) {
                session.status = Strings.get(R.string.status_pasted_as_table)
            } else editor.insertAtCaret(text)
        })
    }
    // The Format Painter waits for a selection and a second press: say so while it is armed.
    LaunchedEffect(editor.painterArmed) {
        if (editor.painterArmed) session.status = Strings.get(R.string.status_format_painter_armed)
    }

    // Dev hook (debug builds): `setprop debug.aero.export pdf` exports the open document on launch,
    // so the PDF path (including per-section page geometry) can be exercised from adb; `docx` / `odt` save it as the
    // app saves (dev-pages.docx / .odt: a table of contents with the pages the canvas laid out).
    if (com.therealityteam.aeropages.BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            val kind = devExportProp()
            if (kind == "docx" || kind == "odt") {
                kotlinx.coroutines.delay(4000)
                val snap = editor.document.copyOf()
                val out = File(workDir, "dev-pages.$kind")
                runCatching {
                    withContext(Dispatchers.IO) {
                        out.outputStream().use { if (kind == "docx") com.therealityteam.aeropages.format.docx.DocxWriter.write(snap, it) else com.therealityteam.aeropages.format.odf.OdtWriter.write(snap, it) }
                    }
                }.onSuccess { android.util.Log.i("AeroExport", "pages $kind ok: ${out.absolutePath}") }.onFailure { android.util.Log.e("AeroExport", "pages $kind fail", it) }
                return@LaunchedEffect
            }
            if (kind != "pdf") return@LaunchedEffect
            kotlinx.coroutines.delay(2500)
            val snap = editor.document.copyOf()
            val outFile = File(workDir, "dev-pages.pdf")
            runCatching {
                withContext(Dispatchers.IO) {
                    outFile.outputStream().use { com.therealityteam.aeropages.format.pdf.PdfExporter.export(snap, it) }
                }
            }.onSuccess { android.util.Log.i("AeroExport", "pages pdf ok: ${outFile.absolutePath} (${outFile.length()} bytes, sections=${snap.sections().size})") }
                .onFailure { android.util.Log.e("AeroExport", "pages pdf fail", it) }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val sample = File(workDir, "Welcome.docx")
            if (!sample.exists()) {
                runCatching {
                    sample.outputStream().use { com.therealityteam.aeropages.format.docx.DocxWriter.write(Samples.welcome(), it) }
                }
            }
            runCatching { seedSampleImage(File(workDir, "sample-image.png")) }
        }
        images = listImages(workDir)
        if (openPath != null) {
            session.openNow(openPath)
        } else if (template != null && !template.blank) {
            editor.load(Templates().document(template))
            session.startedFrom(template)
            session.checkDraft()
        } else {
            editor.load(Document.newBlank())
            session.status = Strings.get(R.string.new_document)
            session.checkDraft()
        }
        // Perf hooks (debug builds): `setprop debug.aero.dialog bigdoc` loads ~600 paragraphs (≈50 pages);
        // `bigdoc1000` loads 12 000 paragraphs (≈1000 pages) for the performance baseline.
        val docHook = devDialogProp()
        if (docHook == "keeporiginal") session.save()
        if (docHook == "bigdoc" || docHook == "bigdoc1000") {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val count = if (docHook == "bigdoc1000") 12_000 else 600
            val doc = Document.blank()
            doc.blocks.clear()
            for (i in 1..count) {
                val p = com.therealityteam.aeropages.model.Paragraph.of(
                    if (i % 12 == 1) Strings.get(R.string.perf_test_chapter_heading, i / 12 + 1) else Strings.get(R.string.perf_test_paragraph_body, i))
                if (i % 12 == 1) p.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1
                doc.blocks.add(com.therealityteam.aeropages.model.ParagraphBlock(p))
            }
            editor.load(doc)
            android.util.Log.i("AeroPerf", "$docHook generated+loaded in ${android.os.SystemClock.elapsedRealtime() - t0} ms ($count paragraphs)")
            logFirstFrame("$docHook first frame", t0)
            session.status = Strings.get(R.string.status_perf_test_generated_2)
        }
        // Dev hook (debug builds): a selection made as the pointer makes it — across paragraphs, with
        // its handles and highlight — for screenshots (`setprop debug.aero.dialog pointerselect`).
        if (docHook == "pointerselect") {
            val doc = Document.blank()
            doc.blocks.clear()
            for (i in 1..8) doc.blocks.add(com.therealityteam.aeropages.model.ParagraphBlock(com.therealityteam.aeropages.model.Paragraph.of(Strings.get(R.string.perf_test_paragraph_body, i))))
            editor.load(doc)
            editor.selectRange(com.therealityteam.aeropages.editor.DocumentEditor.DocPos(1, 6), com.therealityteam.aeropages.editor.DocumentEditor.DocPos(3, 12))
        }
        // Dev hook (debug builds): the caret in the second paragraph, for the paragraph panel's screenshot (`paragraphpanel`, see Ribbon).
        if (docHook == "paragraphpanel") editor.setSelection(1, 0, 0)
        // Dev hook (debug builds): words typed into the second paragraph near its start — beside a picture in wrap-around.docx —
        // the caret staying there (`wraptype`): the field keeps the lines beside the picture.
        if (docHook == "wraptype") { editor.setSelection(1, 7, 7); editor.insertAtCaret("[typed beside the picture] ") }
        // Dev hooks (debug builds): Simple Markup (`markupsimple`); the caret before the first change, Next pressed (`nextchange`).
        if (docHook == "markupsimple") editor.showMarkup(com.therealityteam.aeropages.editor.DocumentEditor.Markup.SIMPLE)
        if (docHook == "nextchange") { editor.setSelection(0, 0, 0); editor.goToChange() }
        // Dev hook (debug builds): Show Markup with the last reviewer's changes left out, its menu open (`showmarkup`, see Ribbon).
        if (docHook == "showmarkup") editor.reviewersInDocument().lastOrNull()?.let { editor.setShowMarkup(hidden = setOf(it)) }
        // Dev hooks (debug builds): the caret in the first table's second row, the Table tab open (`tabletab`), with its styles
        // (`tablestyles`) or borders menu (`tableborders`).
        if (docHook == "tabletab" || docHook == "tablelayout" || docHook == "tablestyles" || docHook == "tableborders" || docHook == "tablesort" || docHook == "tabletotext" || docHook == "tableformula") {
            val cells = editor.document.locations().withIndex().filter { it.value is com.therealityteam.aeropages.model.CellLoc }
            (if (docHook == "tablesort") cells.firstOrNull() else cells.getOrNull(cells.size / 3))?.let { editor.setSelection(it.index, 0, 0) }
        }
        // Dev hooks (debug builds): Insert → Shapes (a star) floating from the first paragraph, the caret in it and its bar
        // showing (`insertshape`); with its wrapping options open (`shapeoptions`); with Shape Format's fill open (`shapeformat`).
        if (docHook == "insertshape" || docHook == "shapeoptions" || docHook == "shapeformat") {
            editor.setSelection(0, 0, 0)
            editor.insertShape("star5")
            if (docHook == "shapeoptions") editor.showImageOptions(true)
        }
        // Dev hooks (debug builds): View ▸ Zoom's presets once the canvas is laid out (`onepage`, `multipage`, `pagewidth`); a
        // paragraph's indents and tab stops on the ruler (`rulertabs`).
        if (docHook == "onepage" || docHook == "multipage" || docHook == "pagewidth") {
            kotlinx.coroutines.delay(2_000)
            val pg = editor.document.setupAt(0)
            when (docHook) { "onepage" -> view.onePage(pg.widthPt, pg.heightPt); "multipage" -> view.multiplePages(pg.widthPt, pg.heightPt); else -> view.pageWidth(pg.widthPt) }
        }
        // Dev hooks (debug builds): Find and Replace open on its tabs with a few words of the document searched for and the
        // first match found (`findpanel`, `replacepanel`, `gotopanel`); the navigation pane's search and results (`navsearch`).
        // The task panes open on the document (`commentspane`, `footnotespane`, `sourcespane`); the first comment is the current one.
        when (docHook) {
            "commentspane" -> { showComments = true; frontPane = SidePane.COMMENTS; editor.document.comments.firstOrNull { it.parentId == null }?.let { editor.currentComment = it } }
            "footnotespane" -> { showFootnotes = true; frontPane = SidePane.FOOTNOTES }
            "sourcespane" -> { showSources = true; frontPane = SidePane.SOURCES }
        }
        // `markentry`: the Mark Index Entry pane with a word of the first long paragraph selected (its main entry); `indexpane`
        // the pane as it is; `insertindex` the Index dialog.
        if (docHook == "markentry") editor.document.locations().withIndex().firstOrNull { it.value.paragraph.plainText().length > 40 }?.let { (i, loc) ->
            val m = Regex("""\p{L}{5,}""").findAll(loc.paragraph.plainText()).drop(1).firstOrNull()
            if (m != null) editor.setSelection(i, m.range.first, m.range.last + 1)
            frontPane = SidePane.INDEX
        }
        if (docHook == "indexpane") frontPane = SidePane.INDEX
        // `textmenu`: a selection from the first paragraph into the second, its bar and the bar's More menu open.
        if (docHook == "textmenu" && editor.document.paragraphs().size > 1) editor.selectRange(DocumentEditor.DocPos(0, 0), DocumentEditor.DocPos(1, 5))
        // `changemenu`: the first tracked change selected, the More menu open on it (its Accept / Reject); `locktracking`: Lock Tracking on.
        if (docHook == "changemenu") editor.revisions().firstOrNull()?.let { r -> editor.selectRange(DocumentEditor.DocPos(r.paragraphIndex, r.start), DocumentEditor.DocPos(r.paragraphIndex, r.end)) }
        if (docHook == "locktracking") editor.lockTracking()
        // `docpasteoptions`: the first two paragraphs copied with their formatting and pasted at the document's end; the
        // Paste Options button's menu open.
        if (docHook == "docpasteoptions" && editor.document.paragraphs().size > 1) {
            editor.selectRange(DocumentEditor.DocPos(0, 0), DocumentEditor.DocPos(1, 12))
            editor.copiedClip()?.let { clip ->
                com.therealityteam.aeropages.editor.DocClipboard.clip = clip
                editor.moveCaret(editor.paragraphCount() - 1, Int.MAX_VALUE)
                editor.splitParagraph(editor.paragraphCount() - 1, Int.MAX_VALUE.coerceAtMost(editor.activeParagraph.plainText().length))
                editor.pasteClip(clip)
            }
        }
        // `ownstyles`: a style "Callout" made from the first paragraph's look (the Styles pane open lists it).
        if (docHook == "ownstyles") { editor.setSelection(0, 0, 0); editor.createStyle("Callout") }
        // `linenumbers` / `lineoptions`: the document's lines numbered, restarting each page and every fifth shown (the
        // third paragraph suppressed); the Line Numbers menu or dialog open.
        if (docHook == "linenumbers" || docHook == "lineoptions") {
            editor.setLineNumbers(com.therealityteam.aeropages.model.LineNumbering(countBy = 5), wholeDocument = true)
            if (editor.paragraphCount() > 2) { editor.setSelection(2, 0, 0); editor.toggleSuppressLineNumbers() }
            editor.setSelection(0, 0, 0)
        }
        // `coverband`: a Band cover page at the start (the title placeholder chosen); `coverpages` opens the gallery.
        if (docHook == "coverband" || docHook == "coverpages") editor.insertCoverPage(com.therealityteam.aeropages.model.CoverDesign.BAND, coverFields(editor))
        // `dropcap` / `dropcapdialog`: the first paragraph that can take one given a drop cap three lines tall, the next such
        // paragraph after it one in the margin two lines tall (`dropcap` only); the Drop Cap menu or dialog open on the first.
        if (docHook == "dropcap" || docHook == "dropcapdialog") {
            val eligible = (0 until editor.paragraphCount()).filter { i -> editor.setSelection(i, 0, 0); editor.canDropCap && editor.activeParagraph.plainText().length > 80 }
            eligible.getOrNull(1)?.takeIf { docHook == "dropcap" }?.let { i -> editor.setSelection(i, 0, 0); editor.setDropCap(com.therealityteam.aeropages.model.DropCap(inMargin = true, lines = 2, distancePt = 4f)) }
            eligible.firstOrNull()?.let { i -> editor.setSelection(i, 0, 0); editor.setDropCap(com.therealityteam.aeropages.model.DropCap()) }
        }
        // `paraborders`: the second and third paragraphs boxed with a light fill (one box, the between line too), the
        // first given a bottom line, a horizontal line after the fourth; the Borders menu open on the box.
        if (docHook == "paraborders" || docHook == "bordersshading") {
            val line = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 1f, 0xFF2F5496)
            editor.setSelection(0, 0, 0)
            editor.applyParagraphBorders(DocumentEditor.ParaBorderPreset.BOTTOM, line)
            if (editor.paragraphCount() > 2) {
                editor.selectRange(DocumentEditor.DocPos(1, 0), DocumentEditor.DocPos(2, 1))
                editor.applyParagraphBorders(DocumentEditor.ParaBorderPreset.ALL, line)
                editor.setParagraphShading(0xFFDEEAF6)
            }
            if (editor.paragraphCount() > 3) { editor.setSelection(3, 0, 0); editor.insertHorizontalLine() }
            editor.setSelection(1, 0, 0)
        }
        // `autocorrectmark`: "i think teh " typed key by key into a new last paragraph — AutoCorrect makes it "I think the " —
        // and the AutoCorrect Options button's menu open.
        if (docHook == "autocorrectmark") {
            editor.moveCaret(editor.paragraphCount() - 1, Int.MAX_VALUE)
            editor.splitParagraph(editor.paragraphCount() - 1, editor.activeParagraph.plainText().length)
            for (ch in "i think teh ") {
                val old = editor.activeParagraph.plainText()
                val at = editor.selStart
                editor.replaceText(editor.active, old, old.substring(0, at) + ch + old.substring(at))
                editor.setSelection(editor.active, at + 1, at + 1)
                editor.autoCorrectTyped(editor.active)
            }
        }
        if (docHook in setOf("findpanel", "replacepanel", "navsearch")) {
            editor.document.paragraphs().map { it.plainText() }.firstOrNull { it.length > 8 }?.let { t -> editor.setSearch(t.substring(3, 6)) }
            editor.find()
            if (docHook == "navsearch") showNav = true
        }
        // Dev hook (debug builds): the whole document to Traditional characters with the device's converter (`zhtraditional`),
        // Review open (the Chinese Conversion group shows in Chinese).
        if (docHook == "zhtraditional") {
            kotlinx.coroutines.delay(1_500)
            session.status = editor.convertChinese(toTraditional = true).let { n -> if (n < 0) Strings.get(R.string.zh_unavailable) else Strings.get(R.string.zh_converted, n) }
        }
        // Dev hook (debug builds): the first paragraphs selected and Convert Text to Table open (`texttotable`, see Ribbon).
        if (docHook == "texttotable") editor.selectRange(com.therealityteam.aeropages.editor.DocumentEditor.DocPos(1, 0), com.therealityteam.aeropages.editor.DocumentEditor.DocPos(4, 0))
        if (docHook == "rulertabs" || docHook == "tabsdialog") {
            editor.setSelection(1, 0, 0)
            editor.setIndents(36f, 21f, 18f)
            editor.addTabStop(com.therealityteam.aeropages.model.TabStop(144f))
            editor.addTabStop(com.therealityteam.aeropages.model.TabStop(252f, com.therealityteam.aeropages.model.TabAlign.CENTER))
            editor.addTabStop(com.therealityteam.aeropages.model.TabStop(360f, com.therealityteam.aeropages.model.TabAlign.RIGHT, com.therealityteam.aeropages.model.TabLeader.DOT))
            if (docHook == "tabsdialog") showTabs = true
        }
        // Dev hook (debug builds): Insert → Header → Blank (Three Columns), open for typing with its first slot selected (`headerthree`);
        // a page number at the bottom right of a footer with text (`footernumber`).
        if (docHook == "headerthree") {
            val type = Strings.get(R.string.hf_type_here)
            editor.applyHeaderFooterLayout(true, "$type\t$type\t$type", com.therealityteam.aeropages.model.TextAlign.LEFT)
        }
        if (docHook == "footernumber") {
            editor.setFooterText(Strings.get(R.string.hf_title_placeholder))
            editor.insertPageNumber(false, com.therealityteam.aeropages.model.TextAlign.RIGHT, Strings.get(R.string.sample_page_of_pages),
                listOf(com.therealityteam.aeropages.model.HeaderFooters.PAGE, Strings.get(R.string.pn_page_x), Strings.get(R.string.sample_page_of_pages)))
        }
        // Dev hooks (debug builds): the Bookmarks dialog (`bookmarks`); Insert Hyperlink on "Place in this document" (`linkplaces`).
        if (docHook == "bookmarks") showBookmarks = true
        if (docHook == "linkplaces") { linkInDocument = true; showLink = true }
        // Dev hook (debug builds): the equation dialog with the quadratic formula typed (`setprop debug.aero.dialog equationeditor`).
        if (docHook == "equationeditor") equationEdit = EquationEdit("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", null, inline = false)
        // Dev hook (debug builds): spatial comment cards demo (XR 空间批注).
        // Proofing with misspelled English words and a repeated one (the device's spell checker lists them, with suggestions).
        if (devDialogProp() == "proofing") {
            editor.replaceText(0, "", "Thsi paragraph has a tset of speling, and and a repeated word.")
            showProofing = true
        }
        if (devDialogProp() == "spatialcomments") {
            editor.setSelection(0, 0, 0)
            editor.replaceText(0, "", Strings.get(R.string.demo_spatial_comment_paragraph))
            editor.addComment(Strings.get(R.string.demo_comment_title_suggestion))
            editor.addComment(Strings.get(R.string.demo_comment_need_source))
            editor.addComment(Strings.get(R.string.demo_comment_add_cta))
            spatialComments = true
            session.status = Strings.get(R.string.status_spatial_comments_demo)
        }
    }

    val tooltipHost = remember { TooltipHost() }

    // System text-selection colours (com.pico.spatial...TextSelectionAndToolbarProvider).
    // The system default selection background is near-white, so we override it with a
    // visible translucent blue so selected text actually highlights on the white page.
    com.pico.spatial.ui.design.text.TextSelectionAndToolbarProvider(
        colors = androidx.compose.foundation.text.selection.TextSelectionColors(
            handleColor = PicoTheme.colorScheme.interaction,
            backgroundColor = PicoTheme.colorScheme.interaction.copy(alpha = 0.35f),
        ),
    ) {
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val ribbonFold = rememberRibbonFold("pages")
    androidx.compose.runtime.CompositionLocalProvider(LocalTooltipHost provides tooltipHost, LocalAccent provides Aero.pages, LocalSearchFocus provides searchFocus,
        LocalRibbonFold provides ribbonFold) {
    // The window is the system glass; the ribbon card and the pages are the surfaces on it.
    Box(Modifier.fillMaxSize().ribbonPeekDismiss(ribbonFold).onPreviewKeyEvent { e ->
        // Alt+Q goes to Search, Ctrl+F1 folds the ribbon; Esc leaves Focus, as in Word.
        if (searchShortcut(e, searchFocus) || ribbonShortcut(e, ribbonFold)) true
        else if (focus && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Escape) { focus = false; true } else false
    }) {
        Column(Modifier.fillMaxSize()) {
            if (focus) FocusBar(session.sourceName ?: session.untitledName, stringResource(R.string.status_words, editor.wordCount()),
                onExit = { focus = false }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp))
            if (!focus) Ribbon(
                editor = editor,
                view = view,
                title = session.sourceName ?: session.untitledName,
                saveState = docState(session),
                onTabs = { showTabs = true },
                onHome = { session.guardDirty(onHome) },
                onSave = session::save,
                onFind = { findTab = FindTab.FIND; showFind = true; frontPane = SidePane.FIND },
                onFindInDocument = { q -> editor.setSearch(q); editor.find(); showNav = true },
                fileMenuOpen = session.showFileMenu,
                onToggleFileMenu = { session.showFileMenu = !session.showFileMenu },
                onInsertImage = { images = listImages(workDir); showImage = true },
                onInsertLink = { openLinkDialog() },
                onBookmarks = { showBookmarks = true },
                onInsertEquation = { openEquation() },
                navOpen = showNav,
                onToggleNav = { showNav = !showNav },
                onWordCount = { showWordCount = true },
                onCompare = { compareOpen = true },
                fullWidth = fullWidth,
                onToggleLayout = { fullWidth = !fullWidth },
                commentsOpen = showComments,
                onToggleComments = { togglePane(SidePane.COMMENTS) },
                onAddComment = { editor.addComment(""); showComments = true; frontPane = SidePane.COMMENTS; session.status = Strings.get(R.string.status_comment_added) },
                spatialCommentsOpen = spatialComments,
                onToggleSpatialComments = { spatialComments = !spatialComments },
                onProofing = { showProofing = true },
                footnotesOpen = showFootnotes,
                onToggleFootnotes = { togglePane(SidePane.FOOTNOTES) },
                sourcesOpen = showSources,
                onToggleSources = { togglePane(SidePane.SOURCES) },
                onCopy = doCopy,
                onCut = doCut,
                onPaste = doPaste,
                onShortcuts = { showShortcuts = true },
                onPageSetup = { pageSetupTab = it },
                onInsertCaption = { showCaption = true },
                onMoreSymbols = { showSymbols = true },
                onTableOfFigures = { showTof = true },
                indexOpen = showIndex,
                onMarkEntry = { togglePane(SidePane.INDEX) },
                onInsertIndex = { insertIndexOpen = true },
                onCrossReference = { showCrossRef = true },
                onFieldsUpdated = { n -> session.status = if (n > 0) Strings.get(R.string.status_fields_updated, n) else Strings.get(R.string.status_fields_current) },
                onStatus = { session.status = it },
                onFocus = { focus = true },
                onStylesPane = { togglePane(SidePane.STYLES) },
                onClipboardPane = { togglePane(SidePane.CLIPBOARD) },
            )
            if (!focus) SessionBanner(session)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                DocumentCanvas(editor, view.zoom, fullWidth = fullWidth, onSave = session::save, onFind = { showFind = true; frontPane = SidePane.FIND },
                    onCut = doCut, onCopy = doCopy, onPaste = doPaste, onOpenLink = { openLink(it) }, onEditLink = { openLinkDialog() },
                    multiPage = view.multiPage, ruler = view.ruler && !focus, onCanvasSize = { w, h -> view.canvasWidthDp = w; view.canvasHeightDp = h },
                    onOpenTabs = { showTabs = true })
                // XR 空间批注: glass cards floating in front of the page's right edge.
                if (spatialComments && !focus) {
                    SpatialCommentCards(
                        editor,
                        Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            if (!focus) StatusBar(editor, session.status, view, fullWidth, onWordCount = { showWordCount = true }, onFullWidth = { fullWidth = it }, onAccessibility = { a11yOpen = true })
        }

        // Side panels float beside the window container as system Subwindows.
        if (showNav && !focus) {
            Subwindow(placement = SubwindowPlacement.Left) {
                NavigationPane(editor, Modifier.fillMaxSize())
            }
        }
        // The right-hand task panes. Office shows one at full height; with several open, a strip at the top switches
        // between them (the one opened last in front) and each closes itself.
        val rightPanes = buildList {
            if (showFind) add(SidePane.FIND)
            if (showComments) add(SidePane.COMMENTS)
            if (showFootnotes) add(SidePane.FOOTNOTES)
            if (showSources) add(SidePane.SOURCES)
            if (showStyles) add(SidePane.STYLES)
            if (showClipboard) add(SidePane.CLIPBOARD)
            if (showIndex) add(SidePane.INDEX)
        }
        if (rightPanes.isNotEmpty() && !focus) {
            val front = shownPane() ?: rightPanes.last()
            Subwindow(placement = SubwindowPlacement.Right) {
                Column(Modifier.fillMaxSize()) {
                    if (rightPanes.size > 1) PaneSwitcher(rightPanes, front, { frontPane = it }, { stringResource(it.label) })
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (front) {
                            SidePane.FIND -> FindReplacePanel(editor, findTab, { findTab = it }) { showFind = false }
                            SidePane.COMMENTS -> CommentsPane(editor, Modifier.fillMaxSize()) { showComments = false }
                            SidePane.FOOTNOTES -> FootnotesPane(editor, Modifier.fillMaxSize()) { showFootnotes = false }
                            SidePane.SOURCES -> SourcesPane(editor, Modifier.fillMaxSize()) { showSources = false }
                            SidePane.STYLES -> StylesPane(editor, Modifier.fillMaxSize(), onModify = { modifyingStyle = it }) { showStyles = false }
                            SidePane.CLIPBOARD -> ClipboardPane(Modifier.fillMaxSize(), onPasteText = { editor.insertAtCaret(it) }, onPasteObject = { editor.pasteItem(it) }) { showClipboard = false }
                            SidePane.INDEX -> IndexEntryPane(editor, Modifier.fillMaxSize(), onInsertIndex = { insertIndexOpen = true }) { showIndex = false }
                        }
                    }
                }
            }
        }

        modifyingStyle?.let { st -> ModifyStyleDialog(editor, st) { modifyingStyle = null } }
        if (a11yOpen) PagesAccessibility(editor) { a11yOpen = false }
        if (insertIndexOpen) InsertIndexDialog(editor) { insertIndexOpen = false }

        SessionFileMenu(
            session,
            newLabel = stringResource(R.string.new_document),
            onNew = { session.newFrom(DeskTemplate.BLANK_DOC, Strings.get(R.string.status_new_blank_document)) { Document.newBlank() } },
            templates = DeskTemplate.of(com.therealityteam.aeropages.format.DeskKind.PAGES),
            onNewFrom = { t -> session.newFrom(t, Strings.get(R.string.status_new_blank_document)) { Templates().document(t) } },
            onExportPdf = { session.export("pdf", "PDF") { doc, out -> com.therealityteam.aeropages.format.pdf.PdfExporter.export(doc, out) } },
            onInfo = { showInfo = true },
        )
        if (showInfo) DocInfoDialog(editor.document.props, editor.document.sourcePath, listOf(
            stringResource(R.string.stat_words) to editor.wordCount().toString(),
            stringResource(R.string.stat_characters_no_spaces) to editor.charCountNoSpaces().toString(),
            stringResource(R.string.stat_characters) to editor.charCount().toString(),
            stringResource(R.string.stat_paragraphs) to editor.paragraphCount().toString(),
            stringResource(R.string.stat_comments) to editor.document.comments.count { it.parentId == null }.toString(),
        ), onApply = editor::setProps) { showInfo = false }

        if (showLink) {
            DocLinkDialog(
                editor, startInDocument = linkInDocument,
                onDismiss = { showLink = false; linkInDocument = false },
                onApplied = { showLink = false; linkInDocument = false; session.status = Strings.get(R.string.status_link_inserted) },
            )
        }
        if (showBookmarks) BookmarkDialog(editor, onDismiss = { showBookmarks = false })

        chartData?.let { block ->
            ChartDataGridDialog(block.chart, editor.revision, edit = { change -> editor.editChart(block, change) }, onDismiss = { chartData = null })
        }

        equationEdit?.let { eq ->
            val editing = eq.block != null || eq.inline
            EquationDialog(eq.latex, editing, allowInline = !editing, initialDisplay = true, onDismiss = { equationEdit = null }, onApply = { math, display ->
                equationEdit = null
                when {
                    eq.block != null -> editor.updateEquation(eq.block, math)
                    eq.inline -> editor.updateInlineMath(math)
                    else -> editor.insertEquation(math, display)
                }
                session.status = Strings.get(if (editing) R.string.status_equation_updated else R.string.status_equation_inserted)
            })
        }

        if (showProofing) {
            // The device's spell checker is asked once the dialog opens; its typos come first, AeroDesk's own checks after.
            val texts = remember { editor.proofTexts() }
            ProofingSession(
                texts = texts,
                ownIssues = { editor.proofread() },
                onJump = { editor.focusParagraph(it) },
                apply = { issue, s -> issue.word?.let { w -> editor.applySpelling(issue.index, issue.start, w, s) } == true },
                onDismiss = { showProofing = false },
            )
        }

        pageSetupTab?.let { t -> PageSetupDialog(editor, t) { pageSetupTab = null } }
        if (showTabs) TabsDialog(editor) { showTabs = false }
        if (showCaption) InsertCaptionDialog(editor, includeChapter = remember { devDialogProp() == "captionchapter" }) { showCaption = false }
        if (showSymbols) SymbolDialog(onInsert = { editor.insertAtCaret(it) }) { showSymbols = false }
        if (showTof) TableOfFiguresDialog(editor) { showTof = false }
        if (showCrossRef) CrossReferenceDialog(editor) { showCrossRef = false }
        // Word's comparison settings, remembered on the device.
        var cmpFormatting by rememberLayoutFlag("compare.formatting", true)
        var cmpCase by rememberLayoutFlag("compare.case", true)
        var cmpSpace by rememberLayoutFlag("compare.whitespace", true)
        var cmpTables by rememberLayoutFlag("compare.tables", true)
        var cmpChars by rememberLayoutFlag("compare.characters", false)
        val compareOptions = com.therealityteam.aeropages.editor.DocCompare.Options(cmpFormatting, cmpCase, cmpSpace, cmpTables, cmpChars)
        if (compareOpen && !compareBrowse) CompareDialog(
            original = session.suggestedName, revised = compareRevised,
            label = compareLabel.ifEmpty { editor.reviewerName }, onLabel = { compareLabel = it },
            options = compareOptions,
            onOptions = { o -> cmpFormatting = o.formatting; cmpCase = o.caseChanges; cmpSpace = o.whiteSpace; cmpTables = o.tables; cmpChars = o.characterLevel },
            expanded = compareExpanded, onExpandedChange = { compareExpanded = it },
            onBrowse = { compareBrowse = true },
            onCompare = {
                val revisedPath = compareRevised ?: return@CompareDialog
                val label = compareLabel.ifBlank { editor.reviewerName }
                val original = editor.document.copyOf()
                val options = compareOptions
                compareOpen = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val result = com.therealityteam.aeropages.editor.DocCompare.compare(original, com.therealityteam.aeropages.format.DocumentRepository.open(revisedPath), label, options = options)
                            // The result is a document of its own, beside the others, opened in a window of its own (as Word's is).
                            val name = "${Strings.get(R.string.compare_result)} ${File(revisedPath).nameWithoutExtension}"
                            var out = File(workDir, "$name.docx"); var n = 2
                            while (out.exists()) out = File(workDir, "$name ${n++}.docx")
                            result.document.sourcePath = null
                            com.therealityteam.aeropages.format.DocumentRepository.save(result.document, out.path)
                            result to out.path
                        }
                    }.onSuccess { (result, path) ->
                        session.status = if (result.formatting > 0) Strings.get(R.string.compare_done_formatting, result.insertions, result.deletions, result.formatting) else Strings.get(R.string.compare_done, result.insertions, result.deletions)
                        openSideWindow(path)
                    }.onFailure { session.status = Strings.get(R.string.compare_failed) }
                }
            },
            onDismiss = { compareOpen = false },
        )
        if (compareBrowse) FileBrowser(
            mode = BrowserMode.OPEN, title = stringResource(R.string.compare_revised),
            openExtensions = DeskFiles.PAGES_EXT,
            startDir = session.browseStart(), shortcuts = session.shortcuts(), suggestedName = "",
            hasStorageAccess = session.storageAccess, onRequestStorage = session::requestStorage, onRefresh = session::refreshStorage,
            onDismiss = { compareBrowse = false },
            onOpen = { path -> compareBrowse = false; compareRevised = path },
            onSave = {},
        )
        if (showWordCount) {
            val counts = editor.wordCounts()
            WordCountDialog(
                pages = editor.pageTotal,
                nonAsian = counts.other,
                words = counts.words,
                chars = editor.charCount(),
                paragraphs = editor.paragraphCount(),
                onDismiss = { showWordCount = false },
                charsNoSpaces = editor.charCountNoSpaces(),
                cjk = editor.cjkCount(),
            )
        }

        if (showShortcuts) {
            ShortcutsDialog("Pages", COMMON_SHORTCUTS + listOf(
                stringResource(R.string.shortcut_category_editing) to listOf(
                    ShortcutEntry("Ctrl+B", stringResource(R.string.bold)),
                    ShortcutEntry("Ctrl+I", stringResource(R.string.italic)),
                    ShortcutEntry("Ctrl+U", stringResource(R.string.underline)),
                    ShortcutEntry("Ctrl+F", stringResource(R.string.find_and_replace)),
                    ShortcutEntry(stringResource(R.string.shortcut_backspace_line_start), stringResource(R.string.shortcut_merge_prev_paragraph)),
                    ShortcutEntry("Ctrl+A", stringResource(R.string.select_all)),
                    ShortcutEntry("Shift+←↑→↓", stringResource(R.string.shortcut_extend_across_paragraphs)),
                    ShortcutEntry("Ctrl+Home / Ctrl+End", stringResource(R.string.shortcut_document_start_end)),
                    ShortcutEntry("Ctrl+L / E / R / J", stringResource(R.string.align_left) + " / " + stringResource(R.string.center) + " / " +
                        stringResource(R.string.align_right) + " / " + stringResource(R.string.justify)),
                    ShortcutEntry("Ctrl+Shift+> / <", stringResource(R.string.increase_font_size) + " / " + stringResource(R.string.decrease_font_size)),
                    ShortcutEntry("Ctrl+] / [", stringResource(R.string.shortcut_font_point)),
                    ShortcutEntry("Ctrl+1 / 2 / 5", stringResource(R.string.line_single) + " / " + stringResource(R.string.line_double) + " / " + stringResource(R.string.line_one_half)),
                    ShortcutEntry("Ctrl+Alt+1 / 2 / 3", stringResource(R.string.shortcut_headings)),
                    ShortcutEntry("Ctrl+Shift+N", stringResource(R.string.txt_style_normal)),
                    ShortcutEntry("Ctrl+Shift+L", stringResource(R.string.bullets)),
                    ShortcutEntry("Ctrl+M / Ctrl+Shift+M", stringResource(R.string.increase_indent) + " / " + stringResource(R.string.decrease_indent)),
                    ShortcutEntry("Ctrl+= / Ctrl+Shift+=", stringResource(R.string.subscript) + " / " + stringResource(R.string.superscript)),
                    ShortcutEntry("Shift+F3", stringResource(R.string.change_case)),
                    ShortcutEntry("Ctrl+Space", stringResource(R.string.clear_formatting)),
                    ShortcutEntry("Ctrl+D", stringResource(R.string.font)),
                    ShortcutEntry("Ctrl+K", stringResource(R.string.insert_link)),
                    ShortcutEntry("Ctrl+Enter", stringResource(R.string.page_break)),
                ),
            ), onDismiss = { showShortcuts = false })
        }

        if (showImage) {
            ImageDialog(
                images = images,
                onDismiss = { showImage = false },
                onPick = { path ->
                    showImage = false
                    scope.launch {
                        val loaded = runCatching { withContext(Dispatchers.IO) { loadImageForInsert(path) } }.getOrNull()
                        if (loaded == null) { session.status = Strings.get(R.string.err_cannot_read_image, File(path).name) }
                        else {
                            editor.insertImage(loaded.data, loaded.mime, loaded.widthPt, loaded.heightPt)
                            session.status = Strings.get(R.string.status_image_inserted_2, File(path).name)
                        }
                    }
                },
            )
        }

        SessionDialogs(session, saveTitle = stringResource(R.string.title_save_document), untitledLabel = stringResource(R.string.untitled_document))

        // Gaze tooltips, painted above everything and never clipped by the ribbon scroll.
        TooltipOverlay(tooltipHost)
    }
    }
    }
}

@Composable
private fun StatusBar(editor: DocumentEditor, status: String, view: PagesView, fullWidth: Boolean, onWordCount: () -> Unit, onFullWidth: (Boolean) -> Unit, onAccessibility: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    AeroStatusBar(
        start = {
            // Word's: the caret's page of all (it opens Go To) and the words — the selection's of all while there is one
            // (it opens Word Count).
            StatusLink(stringResource(R.string.status_page_of, editor.caretPage, editor.pageTotal.coerceAtLeast(editor.caretPage)), { editor.requestFindPanel(2) })
            val words = editor.wordCount()
            val picked = editor.selectedWordCount()
            StatusLink(if (picked > 0) stringResource(R.string.status_words_selected, picked, words) else stringResource(R.string.status_words, words), onWordCount)
            // Word's accessibility state, checked again after each command (not each key).
            val findings = remember(editor.commandRevision) { statusFindings(com.therealityteam.aeropages.model.AccessibilityCheck.of(editor.document)) }
            AccessibilityStatus(findings, onAccessibility)
            // Word's Track Changes item while it is on (a document can open recording): a tap turns it off — unless it is locked.
            if (editor.trackingLocked) StatusText(stringResource(R.string.status_tracking_locked))
            else if (editor.trackChanges) StatusLink(stringResource(R.string.status_tracking_on), editor::toggleTrackChanges)
            if (status.isNotEmpty()) StatusText(status, Modifier.weight(1f))
        },
        end = {
            // Word's view switches, then its zoom slider.
            StatusView("x_printlayout", stringResource(R.string.view_print_layout), !fullWidth) { onFullWidth(false) }
            StatusView("x_weblayout", stringResource(R.string.view_web_layout), fullWidth) { onFullWidth(true) }
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            StatusZoom(view.percent, Math.round(PagesView.MIN_ZOOM * 100), Math.round(PagesView.MAX_ZOOM * 100), { view.slideTo(it / 100f) }, { view.zoomTo(1f) },
                stringResource(R.string.zoom_out), stringResource(R.string.zoom_in))
        },
    )
}

/** Whether the app can read/write arbitrary local storage (All files access on API 30+). */
internal fun checkStorageAccess(@Suppress("UNUSED_PARAMETER") context: android.content.Context): Boolean =
    android.os.Environment.isExternalStorageManager()

/** Open the system "All files access" settings screen for this app. */
internal fun requestAllFilesAccess(context: android.content.Context) {
    val pkgUri = "package:${context.packageName}".toUri()
    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Where the browser should start: the external storage root if accessible, else the app dir. */
internal fun browseStartDir(workDir: File, hasAccess: Boolean): File =
    if (hasAccess) (android.os.Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory } ?: workDir) else workDir

/** Quick-access locations shown above the listing. */
internal fun storageShortcuts(workDir: File): List<Shortcut> {
    val out = ArrayList<Shortcut>()
    val root = android.os.Environment.getExternalStorageDirectory()
    if (root != null && root.isDirectory) {
        out.add(Shortcut(Strings.get(R.string.storage_internal), root, ShortcutKind.ROOT))
        listOf(
            Triple("Documents", Strings.get(R.string.storage_documents), ShortcutKind.FOLDER),
            Triple("Download", Strings.get(R.string.downloads), ShortcutKind.DOWNLOADS),
            Triple("DCIM", Strings.get(R.string.gallery), ShortcutKind.PICTURES),
            Triple("Pictures", Strings.get(R.string.picture), ShortcutKind.PICTURES),
        ).forEach { (dir, label, kind) ->
            val f = File(root, dir)
            if (f.isDirectory) out.add(Shortcut(label, f, kind))
        }
    }
    out.add(Shortcut(Strings.get(R.string.storage_app_folder), workDir, ShortcutKind.APP))
    return out
}

/** The recent-files list (newest first), see [RecentFiles]. */
internal fun loadRecents(context: android.content.Context): List<String> = RecentFiles.load(context).map { it.path }

/** Add [path] to the front of the recent list and persist; the list's paths. */
internal fun saveRecent(context: android.content.Context, path: String): List<String> = RecentFiles.add(context, path).map { it.path }

/** Reviewer name stamped on comments and tracked changes (persisted across sessions). */
internal fun loadReviewer(context: android.content.Context): String =
    context.getSharedPreferences("aeropages", android.content.Context.MODE_PRIVATE).getString("reviewer", "") ?: ""

internal fun saveReviewer(context: android.content.Context, name: String) {
    context.getSharedPreferences("aeropages", android.content.Context.MODE_PRIVATE).edit { putString("reviewer", name.trim()) }
}

/** What the equation dialog opens on: the LaTeX it starts from, and the displayed [block] or the equation in the text ([inline]) it edits. */
internal class EquationEdit(val latex: String, val block: com.therealityteam.aeropages.model.EquationBlock?, val inline: Boolean)

/** Decoded image ready to insert. */
internal class LoadedImage(val data: ByteArray, val mime: String, val widthPt: Float, val heightPt: Float)

/** Read an image file, decode its pixel size, and convert to a sensible point size. */
internal fun loadImageForInsert(path: String): LoadedImage? {
    val file = File(path)
    if (!file.exists()) return null
    val bytes = file.readBytes()
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    // An SVG has no pixels: the size it declares (CSS pixels, as a bitmap's at 96 dpi).
    val declared = if (opts.outWidth <= 0) com.therealityteam.aeropages.format.ImageSize.of(bytes) else null
    if (opts.outWidth <= 0 && declared == null) return null
    val wpx = declared?.first ?: opts.outWidth.coerceAtLeast(1).toFloat()
    val hpx = declared?.second ?: opts.outHeight.coerceAtLeast(1).toFloat()
    // px -> pt at 96 dpi, then cap the width to keep it on the page.
    var wpt = wpx * 0.75f
    var hpt = hpx * 0.75f
    val maxW = 400f
    if (wpt > maxW) { val s = maxW / wpt; wpt *= s; hpt *= s }
    return LoadedImage(bytes, com.therealityteam.aeropages.format.PictureType.mimeOf(bytes, path), wpt, hpt)
}

/** Generate a small demo PNG so the image-insert flow has something to pick on first run. */
private fun seedSampleImage(file: File) {
    if (file.exists()) return
    val bmp = androidx.core.graphics.createBitmap(480, 300)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.rgb(0x3D, 0x85, 0xC6))
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(0xF1, 0xC2, 0x32)
    canvas.drawCircle(240f, 150f, 90f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 40f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    canvas.drawText("Reality Office", 240f, 250f, paint)
    java.io.FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
}

/** The title row's word on the document: not saved yet, edited since the last save, or saved. */
@Composable
internal fun docState(session: DocumentSession<*>): String = stringResource(when {
    session.sourcePath == null -> R.string.info_not_saved
    session.dirty -> R.string.doc_state_edited
    else -> R.string.doc_state_saved
})

/** Pages' right-hand task panes, in the order the switcher lists them. */
internal enum class SidePane(val label: Int) {
    FIND(R.string.find), COMMENTS(R.string.comments), FOOTNOTES(R.string.footnote), SOURCES(R.string.pane_sources), STYLES(R.string.styles), CLIPBOARD(R.string.clipboard),
    INDEX(R.string.group_index)
}
