package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.HorizontalDivider
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.ParagraphBoxes
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PAGE_BREAK
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.therealityteam.aeropages.R
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.wrapContentSize

/**
 * The scrollable, zoomable page that renders and edits the document.
 *
 * Pages come from [CanvasFlow]: every paragraph is measured exactly as it is drawn, and
 * [com.therealityteam.aeropages.editor.PageFlow] breaks the pages by the same rules as the PDF
 * export — a paragraph continues on the next page between two lines, headings stay with what
 * follows, tables repeat their header rows. The header and footer sit in the page margins (as in
 * Word) and the body fills exactly the space between them, so nothing is cut off at a page foot.
 *
 * Active paragraph = a [BasicTextField] whose plain text is re-styled per-run via
 * a [VisualTransformation] (so inline formatting shows while editing) and whose
 * selection drives the toolbar. Other paragraphs render as rich [BasicText]; tapping one puts the
 * caret where it was tapped. Keyboard shortcuts (Ctrl+B/I/U/Z/Y/S/F) and Backspace-at-start merge match Word.
 */
@Composable
fun DocumentCanvas(
    editor: DocumentEditor,
    zoom: Float,
    fullWidth: Boolean,
    onSave: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier,
    onCut: () -> Unit = {},
    onCopy: () -> Unit = {},
    onPaste: () -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    onEditLink: () -> Unit = {},
    multiPage: Boolean = false,
    ruler: Boolean = false,
    onCanvasSize: (widthDp: Float, heightDp: Float) -> Unit = { _, _ -> },
    onOpenTabs: () -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    // The commented text's tint, worked out before the paragraphs are drawn (the current comment's darker).
    CommentShading.update(editor.document, rev, editor.currentComment?.let { it.parentId ?: it.id }, editor::commentShown, editor.markupFilter)
    SearchShading.update(editor)
    val focusRequester = remember { FocusRequester() }
    val flow = remember(editor) { CanvasFlow() }
    // The selection bar's More menu (Word's right-click menu) and the dialogs it opens — held here, so a dialog stays
    // when the bar goes as focus moves to it.
    var textMenu by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() in setOf("textmenu", "changemenu")) }
    // The Paste Options button's menu (open from the start under the dev hook `docpasteoptions`, once its paste is made).
    var pasteMenu by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "docpasteoptions") }
    var pasteMenuUp by remember { mutableStateOf(false) }
    // The AutoCorrect Options button's menu and the AutoCorrect dialog it opens.
    var autoFixMenu by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "autocorrectmark") }
    var autoFixMenuUp by remember { mutableStateOf(false) }
    var autoCorrectDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    var paragraphDialog by remember { mutableStateOf(false) }
    // A dialog asked for by a key (Ctrl+D, Ctrl+K).
    LaunchedEffect(editor.dialogRequest) {
        when (editor.dialogRequest) {
            "font" -> fontDialog = true
            "link" -> onEditLink()
        }
        editor.dialogRequest = null
    }
    if (fontDialog) FontDialog(editor) { fontDialog = false }
    if (paragraphDialog) ParagraphDialog(editor, { paragraphDialog = false }, onOpenTabs)
    if (autoCorrectDialog) AutoCorrectOptionsDialog { autoCorrectDialog = false }
    // The editor places what floats from a paragraph below it by the height the page gives it.
    val canvasDensity = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.runtime.SideEffect {
        editor.paragraphHeightPt = { p -> flow.metricsOf(p)?.heightPx?.let { it / (canvasDensity * zoom) } }
        // Page references (PAGEREF) count pages as the canvas lays them out.
        editor.pageOfLocation = { i -> editor.document.locations().getOrNull(i)?.paragraph?.let { flow.pageOf(it) } }
    }

    // Browse-until-tapped: the editable field (and with it the system keyboard's spatial
    // window) only appears after the user taps into the text. A focused field keeps the
    // InputMethod window on-screen, and the system then suppresses gaze hover/tooltips
    // for the whole app window — auto-focusing at open killed hover everywhere in Pages.
    val editArm = remember { EditArm() }
    val reveal = remember(editor) { RevealState() }
    val hits = remember(editor) { TextHits() }
    val press = remember { PointerSelect() }
    val canvasCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val haptic = LocalHapticFeedback.current
    fun toRoot(p: Offset): Offset = canvasCoords[0]?.takeIf { it.isAttached }?.localToRoot(p) ?: p
    /** Stretches the selection from the dragged selection's fixed place to the text under the pointer. */
    fun extendToPointer() {
        val p = press.pointer ?: return
        val pos = hits.at(toRoot(p)) ?: return
        val fwd = press.forward ?: return
        editor.selectRange(if (pos < fwd) press.backward ?: fwd else fwd, pos)
    }
    // Zoom by scaling the density the pages are laid out with: every dp and sp inside the
    // canvas grows together, the LazyColumn measures the real (zoomed) size, so scrolling
    // reaches the end and hit-testing stays exact. (A graphicsLayer scale only stretched the
    // pixels: at 150% the page was clipped at the sides and the bottom became unreachable.)
    val baseDensity = LocalDensity.current
    val zoomedDensity = remember(zoom, baseDensity) { Density(baseDensity.density * zoom, baseDensity.fontScale) }
    CompositionLocalProvider(
        LocalEditArm provides editArm,
        LocalDensity provides zoomedDensity,
        LocalCanvasFlow provides flow,
        LocalReveal provides reveal,
        LocalTextHits provides hits,
        LocalOpenLink provides onOpenLink,
    ) {
        val measurer = rememberTextMeasurer()
        // Press and hold on the text (not on the caret's text field, which keeps its own gestures)
        // selects the word there; dragging on stretches the selection across paragraphs. Once the
        // hold is recognised the drag is taken in the Initial pass, so the pages do not scroll.
        BoxWithConstraints(
            // The pages float on the window's own glass (no fill of their own behind them), as slides and posters do.
            modifier.fillMaxSize()
                .onGloballyPositioned { canvasCoords[0] = it }
                .pointerInput(editor) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        val (entry, local) = hits.entryAt(toRoot(held.position)) ?: return@awaitEachGesture
                        if (entry.field) return@awaitEachGesture
                        val word = hits.wordAt(DocumentEditor.DocPos(entry.index, entry.layout!!.getOffsetForPosition(local)))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        editArm.selecting = true
                        press.forward = word.first; press.backward = word.second; press.pointer = held.position
                        editor.selectRange(word.first, word.second)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == held.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                press.pointer = change.position
                                extendToPointer()
                            }
                        } finally {
                            editArm.selecting = false
                            press.pointer = null
                        }
                    }
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val revision = editor.revision
            val doc = editor.document
            val startIndices = computeStartIndices(doc)
            val availPx = constraints.maxWidth
            val sidePx = with(zoomedDensity) { 24.dp.roundToPx() }
            // LazyColumn so long documents only compose the on-screen pages (each page card
            // holds editable text fields — eager composition of a 100-page doc would choke).
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            // Long documents are laid out progressively: while some paragraphs are only estimated,
            // another pass runs every frame, and the pages on screen are measured first (tracked only
            // while refining, so scrolling a finished layout recomposes nothing).
            var refine by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            @Suppress("UNUSED_VARIABLE") val tick = refine
            // Multiple Pages: as many pages to a row as fit across at this zoom (Word tiles them so); one otherwise.
            val pageGapPx = with(zoomedDensity) { PagesView.PAGE_GAP_DP.dp.toPx() }
            val across = if (!multiPage || fullWidth) 1 else {
                val w = doc.setupAt(0).widthPt * zoomedDensity.density
                maxOf(1, ((availPx - 2 * sidePx + pageGapPx) / (w + pageGapPx)).toInt())
            }
            val firstVisible = remember(listState) { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex } }
            val first = if (flow.incomplete) firstVisible.value * across else 0
            // A page is drawn at its own width (never wider than the window), or the window's in full-width mode.
            val pages = flow.layout(
                doc, measurer, zoomedDensity, (first - 1)..(first + 2),
                geometry = Triple(availPx, fullWidth, zoomedDensity), revision = editor.revision, typing = editor.typingRun,
            ) { pg ->
                if (fullWidth) (availPx - 2 * sidePx).coerceAtLeast(1)
                else minOf(with(zoomedDensity) { pg.widthPt.dp.roundToPx() }, availPx)
            }
            // Measure the estimated rest in short slices between frames (input and drawing go on), then lay out
            // again — every 0.4 s, or five layout-pass durations for very long documents — so later pages
            // settle progressively without the passes themselves hogging the main thread.
            if (flow.incomplete) LaunchedEffect(refine) {
                val until = android.os.SystemClock.elapsedRealtime() + maxOf(400L, flow.lastPassMs * 5)
                while (flow.refineStep(8) && android.os.SystemClock.elapsedRealtime() < until) kotlinx.coroutines.delay(1)
                refine++
            }
            // Each page's number as its section shows it (i, ii… then 1, 2…), and the page each paragraph starts on so
            // numbered — the table of contents' and page references' numbers.
            val shownNumbers = com.therealityteam.aeropages.model.HeaderFooters.pageNumbers(pages.map { it.setup })
            val pageOfParagraph = HashMap<Long, String>()
            pages.forEachIndexed { i, page ->
                page.frames.forEach { frame -> frame.forEach { item -> if (item is PageItem.Para && item.from == 0) pageOfParagraph.putIfAbsent(item.block.paragraph.id, shownNumbers.getOrElse(i) { (i + 1).toString() }) } }
            }
            // The editor's page references and a saved table of contents use the same numbers (once the layout is complete).
            androidx.compose.runtime.SideEffect {
                editor.pageLabelOf = { n -> shownNumbers.getOrNull(n - 1) ?: n.toString() }
                if (editor.pageTotal != pages.size) editor.pageTotal = pages.size
                if (!flow.incomplete) doc.pageCache = pageOfParagraph
            }
            // Dev hook: `setprop debug.aero.page N` shows page N; "N.5" scrolls halfway down it (page feet in screenshots).
            LaunchedEffect(pages.size) {
                val v = devPageProp().toFloatOrNull() ?: return@LaunchedEffect
                val n = v.toInt()
                if (n in 1..pages.size) {
                    val itemPx = with(zoomedDensity) { (pages[n - 1].setup.heightPt + 32f).dp.roundToPx() }
                    listState.scrollToItem((n - 1) / across, ((v - n) * itemPx).toInt())
                }
            }
            // Navigation — the outline, the contents, Find, arrows across paragraphs, undo — brings the
            // caret into view: its page scrolls on screen here, then its paragraph shows the caret's line.
            LaunchedEffect(editor.revealRequest) {
                if (editor.revealRequest == 0) return@LaunchedEffect
                val target = editor.activeParagraph
                val line = flow.metricsOf(target)?.lineOf(editor.selEnd)
                val page = pageHolding(pages, target, line) ?: return@LaunchedEffect
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == page / across }) listState.scrollToItem(page / across)
            }
            // The ruler over the page the caret is on (one page to a row only), and the line it draws down the page while dragging.
            val showRuler = ruler && across == 1 && pages.isNotEmpty()
            val rulerPx = with(baseDensity) { 24.dp.toPx() }
            androidx.compose.runtime.SideEffect { onCanvasSize(constraints.maxWidth / baseDensity.density, (constraints.maxHeight - if (ruler) rulerPx else 0f) / baseDensity.density) }
            var guideX by remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
            Column(Modifier.fillMaxSize()) {
                if (showRuler) PageRuler(editor, rulerGeometry(editor, pages, flow, availPx, fullWidth, sidePx, zoomedDensity.density), { guideX = it }, onOpenTabs)
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items((pages.size + across - 1) / across, key = { it }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(PagesView.PAGE_GAP_DP.dp)) {
                            for (pi in row * across until minOf(pages.size, (row + 1) * across)) {
                                PageCard(
                                    shownNumber = shownNumbers.getOrElse(pi) { (pi + 1).toString() },
                                    editor = editor,
                                    page = pages[pi],
                                    startIndices = startIndices,
                                    pageNumber = pi + 1,
                                    pageCount = pages.size,
                                    pageOfParagraph = pageOfParagraph,
                                    fullWidth = fullWidth,
                                    focusRequester = focusRequester,
                                    onSave = onSave,
                                    onFind = onFind,
                                )
                            }
                        }
                    }
                }
            }
            guideX?.let { gx ->
                val dash = with(baseDensity) { 3.dp.toPx() }
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawLine(Color(0xFF5F6670), Offset(gx, rulerPx), Offset(gx, size.height), 1f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
                }
            }
            // While a selection is dragged near the top or bottom, the pages scroll under the pointer.
            val edge = with(baseDensity) { 56.dp.toPx() }
            val heightPx = constraints.maxHeight.toFloat()
            LaunchedEffect(editArm.selecting) {
                if (!editArm.selecting) return@LaunchedEffect
                while (true) {
                    val p = press.pointer
                    val dy = when {
                        p == null -> 0f
                        p.y < edge -> -(edge - p.y) * 0.6f
                        p.y > heightPx - edge -> (p.y - (heightPx - edge)) * 0.6f
                        else -> 0f
                    }
                    if (dy != 0f) { listState.dispatchRawDelta(dy); extendToPointer() }
                    kotlinx.coroutines.delay(16)
                }
            }
            // The selection's handles — for a selection across paragraphs or one made with the pointer
            // (the text field draws handles only for selections it made itself): drag one to move
            // that end, into other paragraphs too.
            val ends = editor.selectionEnds()
            var dragged by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
            if (ends.first != ends.second && (editor.hasSpan || !editArm.armed || editArm.selecting || editor.pointerSelection)) {
                for (role in 0..1) {
                    SelectionHandle(
                        description = stringResource(if (role == 0) R.string.a11y_selection_start else R.string.a11y_selection_end),
                        place = {
                            // Text moving (a scroll, a reflow, the first layout) places the handles again.
                            @Suppress("UNUSED_VARIABLE") val moved = hits.version
                            val sel = editor.selectionEnds()
                            val pos = when {
                                dragged == role -> editor.selectionFocus()
                                dragged >= 0 -> editor.selectionAnchor()
                                role == 0 -> sel.first
                                else -> sel.second
                            }
                            val origin = canvasCoords[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                            hits.caretAt(pos)?.let { (top, bottom) -> Triple(top.x - origin.x, top.y - origin.y, bottom - origin.y) }
                        },
                        toCanvas = { root -> root - (canvasCoords[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero) },
                        onStart = {
                            val sel = editor.selectionEnds()
                            val fixed = if (role == 0) sel.second else sel.first
                            press.forward = fixed; press.backward = fixed
                            dragged = role
                            editArm.selecting = true
                        },
                        onMove = { p -> press.pointer = p; extendToPointer() },
                        onEnd = { dragged = -1; editArm.selecting = false; press.pointer = null },
                    )
                }
            }
            // Beside a selection made with the pointer (or running across paragraphs): a small bar of
            // what one does with it next — on the headset the ribbon is far from where the eyes are.
            if (ends.first != ends.second && !editArm.selecting && (editor.hasSpan || editor.pointerSelection)) {
                val widthPx = constraints.maxWidth
                val gap = with(baseDensity) { 10.dp.toPx() }
                val underHandle = with(baseDensity) { (HANDLE_SIZE + 8.dp).toPx() }
                val margin = with(baseDensity) { 8.dp.toPx() }
                FloatingActionBar(
                    description = stringResource(R.string.a11y_selection_actions),
                    // Above the selection's first line; with no room there, under its last line (past the handle).
                    place = { size ->
                        @Suppress("UNUSED_VARIABLE") val moved = hits.version
                        val sel = editor.selectionEnds()
                        val origin = canvasCoords[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                        val first = hits.caretAt(sel.first)?.let { (top, _) -> top - origin }
                        val last = hits.caretAt(sel.second)?.let { (top, bottom) -> Offset(top.x - origin.x, bottom - origin.y) }
                        val at = when {
                            first != null && first.y - size.height - gap >= margin -> Offset(first.x - margin, first.y - size.height - gap)
                            last != null -> Offset(last.x - size.width / 2f, last.y + underHandle)
                            else -> null
                        }
                        at?.let { IntOffset(it.x.coerceIn(margin, maxOf(margin, widthPx - size.width - margin)).roundToInt(), it.y.roundToInt()) }
                    },
                ) {
                    val cf = editor.currentFormat
                    RibbonMiniButton("✂", stringResource(R.string.cut), onCut)
                    RibbonMiniButton("⧉", stringResource(R.string.copy), onCopy)
                    RibbonMiniButton("📋", stringResource(R.string.paste), onPaste)
                    BarDivider()
                    RibbonMiniButton("B", stringResource(R.string.bold), editor::toggleBold, selected = cf.bold)
                    RibbonMiniButton("I", stringResource(R.string.italic), editor::toggleItalic, selected = cf.italic)
                    RibbonMiniButton("U", stringResource(R.string.underline), editor::toggleUnderline, selected = cf.underline)
                    BarDivider()
                    // More: Word's right-click menu for the selection.
                    Box {
                        RibbonMiniButton("ic_more", stringResource(R.string.more_actions), { textMenu = !textMenu }, selected = textMenu)
                        if (textMenu) AnchoredFlyout({ textMenu = false }) {
                            TextContextMenu(editor, onFont = { fontDialog = true }, onParagraph = { paragraphDialog = true }, onLink = onEditLink) { textMenu = false }
                        }
                    }
                }
            }
            // Word's Paste Options button: just after the text pasted last, until the next change — the same text again
            // as copied, in the formatting where it landed, or plain.
            editor.pasteOptions?.let { mark ->
                val widthPx = constraints.maxWidth
                val heightPx = constraints.maxHeight
                val margin = with(baseDensity) { 8.dp.toPx() }
                val gap = with(baseDensity) { 4.dp.toPx() }
                FloatingActionBar(
                    description = stringResource(R.string.paste_options),
                    place = { size ->
                        @Suppress("UNUSED_VARIABLE") val moved = hits.version
                        val origin = canvasCoords[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                        hits.caretAt(mark.end)?.let { (top, bottom) ->
                            // In the lower part of the canvas the menu opens upward, as Office's do near the screen's foot
                            // (worked out here, at layout: the hits change with every layout, so reading them while
                            // composing would compose again and again).
                            val low = bottom - origin.y > heightPx * 0.55f
                            if (low != pasteMenuUp) pasteMenuUp = low
                            IntOffset((top.x - origin.x + gap).coerceIn(margin, maxOf(margin, widthPx - size.width - margin)).roundToInt(), (bottom - origin.y + gap).roundToInt())
                        }
                    },
                ) {
                    Box {
                        RibbonMiniButton("📋", stringResource(R.string.paste_options), { pasteMenu = !pasteMenu }, selected = pasteMenu, dropdown = true)
                        if (pasteMenu) AnchoredFlyout({ pasteMenu = false }, above = pasteMenuUp) {
                            DocPasteOptionsMenu(mark.mode) { mode -> pasteMenu = false; editor.repaste(mode) }
                        }
                    }
                }
            }
            // Word's AutoCorrect Options button: under the start of what was just corrected, until the next change —
            // undo the correction, stop making it, or the options.
            editor.autoFixMark?.takeIf { hasOptionsButton(it.kind) }?.let { fix ->
                val widthPx = constraints.maxWidth
                val heightPx = constraints.maxHeight
                val margin = with(baseDensity) { 8.dp.toPx() }
                val gap = with(baseDensity) { 2.dp.toPx() }
                FloatingActionBar(
                    description = stringResource(R.string.ac_options),
                    place = { size ->
                        @Suppress("UNUSED_VARIABLE") val moved = hits.version
                        val origin = canvasCoords[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                        hits.caretAt(fix.at)?.let { (top, bottom) ->
                            val low = bottom - origin.y > heightPx * 0.55f
                            if (low != autoFixMenuUp) autoFixMenuUp = low
                            IntOffset((top.x - origin.x).coerceIn(margin, maxOf(margin, widthPx - size.width - margin)).roundToInt(), (bottom - origin.y + gap).roundToInt())
                        }
                    },
                ) {
                    Box {
                        RibbonMiniButton("ic_lightning", stringResource(R.string.ac_options), { autoFixMenu = !autoFixMenu }, selected = autoFixMenu, dropdown = true)
                        if (autoFixMenu) AnchoredFlyout({ autoFixMenu = false }, above = autoFixMenuUp) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            AutoCorrectMenu(
                                fix.kind, fix.was,
                                onUndo = { autoFixMenu = false; editor.undoAutoFix() },
                                onStop = {
                                    autoFixMenu = false
                                    AutoCorrectPrefs.save(context, stopped(com.therealityteam.aeropages.editor.AutoCorrect.options, fix.kind, fix.was))
                                    editor.undoAutoFix()
                                },
                                onOptions = { autoFixMenu = false; autoCorrectDialog = true },
                            )
                        }
                    }
                }
            }
            // The link the caret is at: where it goes, and opening, changing or taking off the link — above its first line
            // (below it with no room there): under the caret is where the headset's keyboard button stands.
            val atLink = if (ends.first == ends.second && editArm.armed && !editArm.selecting) editor.linkAtCaret() else null
            if (atLink != null) {
                val widthPx = constraints.maxWidth
                val gap = with(baseDensity) { 6.dp.toPx() }
                val margin = with(baseDensity) { 8.dp.toPx() }
                FloatingActionBar(
                    description = stringResource(R.string.a11y_link_actions),
                    place = { size ->
                        @Suppress("UNUSED_VARIABLE") val moved = hits.version
                        val origin = canvasCoords[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                        hits.caretAt(DocumentEditor.DocPos(editor.active, atLink.second.first))?.let { (top, bottom) ->
                            val above = top.y - origin.y - size.height - gap
                            IntOffset((top.x - origin.x - margin).coerceIn(margin, maxOf(margin, widthPx - size.width - margin)).roundToInt(),
                                (if (above >= margin) above else bottom - origin.y + gap).roundToInt())
                        }
                    },
                ) {
                    // Where it goes: a place in this document (a bookmark's mark) or an address (a link's).
                    Box(Modifier.padding(start = 6.dp)) { RibbonGlyph(if (atLink.first.startsWith("#")) "ic_bookmark" else "ic_link", Aero.link, 16.dp) }
                    BasicText(
                        linkLabel(editor, atLink.first), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = AeroType.command.copy(color = Aero.link),
                        modifier = Modifier.padding(start = 2.dp, end = 8.dp).widthIn(max = 280.dp),
                    )
                    BarDivider()
                    RibbonMiniButton("x_openlink", stringResource(R.string.open_link), { onOpenLink(atLink.first) })
                    RibbonMiniButton("ic_edit", stringResource(R.string.link_edit), onEditLink)
                    RibbonMiniButton("x_unlink", stringResource(R.string.remove_link), editor::removeLinkAtCaret)
                }
            }
            // Dev hook (debug builds): the document's first link within it followed, as from its bar (`setprop debug.aero.dialog linkfollow`).
            if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "linkfollow") LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                editor.document.paragraphs().firstNotNullOfOrNull { p -> p.runs.firstNotNullOfOrNull { r -> r.format.link?.takeIf { it.startsWith("#") } } }?.let(onOpenLink)
            }
            // Dev hook (debug builds): the caret in the document's first link, its bar showing (`setprop debug.aero.dialog linkbubble`).
            if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "linkbubble") LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1500)
                val all = editor.document.locations()
                for ((i, loc) in all.withIndex()) {
                    var pos = 0
                    val run = loc.paragraph.runs.firstOrNull { r -> (r.format.link != null && r.text.isNotEmpty()).also { if (!it) pos += r.text.length } } ?: continue
                    editor.focusParagraph(i)
                    editor.setSelection(i, pos + minOf(2, run.text.length), pos + minOf(2, run.text.length))
                    editArm.armed = true
                    break
                }
            }
        }
    }
}

/** Opens a link — Ctrl+click on one (the caret's link opens from its bar instead). */
internal val LocalOpenLink = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

private val HANDLE_SIZE = 24.dp

/**
 * A selection handle: a round grip hanging under the text line at one end of the selection, a
 * thin stem up the line. [place] gives the line in canvas px (x, top, bottom) — null when that end
 * is off screen. Dragging reports the pointer ([onMove], canvas px, lifted from the grip to the
 * line it points at) from the press on, without waiting for a touch slop.
 */
@Composable
private fun SelectionHandle(
    description: String,
    place: () -> Triple<Float, Float, Float>?,
    toCanvas: (Offset) -> Offset,
    onStart: () -> Unit, onMove: (Offset) -> Unit, onEnd: () -> Unit,
) {
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val convert by androidx.compose.runtime.rememberUpdatedState(toCanvas)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val color = PicoTheme.colorScheme.interaction
    val density = LocalDensity.current
    val sizePx = with(density) { HANDLE_SIZE.toPx() }
    Box(
        Modifier
            .offset {
                val at = place()
                if (at == null) IntOffset(-100_000, -100_000)
                else IntOffset((at.first - sizePx / 2).roundToInt(), at.third.roundToInt())
            }
            .size(HANDLE_SIZE)
            .onGloballyPositioned { coords[0] = it }
            .semantics { contentDescription = description }
            .drawBehind {
                val at = place() ?: return@drawBehind
                val line = at.third - at.second
                drawLine(color, Offset(size.width / 2, -line), Offset(size.width / 2, size.height * 0.2f), strokeWidth = 2.dp.toPx())
                drawCircle(color, radius = size.width * 0.32f, center = Offset(size.width / 2, size.height * 0.52f))
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val lift = size.height * 0.5f + 6.dp.toPx()
                    fun canvasOf(p: Offset): Offset {
                        val c = coords[0]
                        val root = if (c != null && c.isAttached) c.localToRoot(p) else p
                        return convert(root) - Offset(0f, lift)
                    }
                    start()
                    move(canvasOf(down.position))
                    try {
                        drag(down.id) { change -> change.consume(); move(canvasOf(change.position)) }
                    } finally {
                        end()
                    }
                }
            },
    )
}

/** The canvas's layout of the open document, for the pieces that need its measurements. */
internal val LocalCanvasFlow = androidx.compose.runtime.compositionLocalOf<CanvasFlow?> { null }

/** Whether the user has tapped into the text (arming the editable caret + keyboard). */
private class EditArm {
    var armed by androidx.compose.runtime.mutableStateOf(false)
    /** While a selection is dragged with the pointer: no text field is swapped in (no keyboard flicker). */
    var selecting by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * Where each paragraph's text sits on screen, so a pointer position maps to a place in the text —
 * for press-and-hold selection and the selection handles. A paragraph split across pages has an
 * entry per piece; each piece only answers for the part of it that shows.
 */
internal class TextHits {
    class Entry(val index: Int) {
        var coords: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
        /** The caret's text field (it keeps its own gestures). */
        var field = false
    }

    private val entries = ArrayList<Entry>()
    fun add(e: Entry) { entries.add(e); touch() }
    fun remove(e: Entry) { entries.remove(e); touch() }

    /**
     * Bumped whenever a paragraph's text moves or is laid out anew — what reads positions from
     * here (the selection handles) reads this too, so it is placed again once the text has
     * reported where it is (a selection made before the first layout, a scroll, a reflow).
     */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    fun touch() { version++ }

    /** The entry whose visible text holds [root] (root px), else the nearest one — above or below counts most. */
    fun entryAt(root: Offset): Pair<Entry, Offset>? {
        var best: Entry? = null
        var bestScore = Float.MAX_VALUE
        for (e in entries) {
            val c = e.coords ?: continue
            if (e.layout == null || !c.isAttached) continue
            val shown = c.boundsInRoot()
            if (shown.width <= 0f || shown.height <= 0f) continue
            val dy = when { root.y < shown.top -> shown.top - root.y; root.y > shown.bottom -> root.y - shown.bottom; else -> 0f }
            val dx = when { root.x < shown.left -> shown.left - root.x; root.x > shown.right -> root.x - shown.right; else -> 0f }
            val score = dy * 1000f + dx
            if (score < bestScore) { bestScore = score; best = e }
        }
        val e = best ?: return null
        val c = e.coords!!
        val shown = c.boundsInRoot()
        // Clamp into what shows, then into the paragraph's own box.
        val inShown = Offset(root.x.coerceIn(shown.left, shown.right - 1f), root.y.coerceIn(shown.top, shown.bottom - 1f))
        val local = inShown - c.positionInRoot()
        return e to Offset(local.x.coerceIn(0f, c.size.width.toFloat()), local.y.coerceIn(0f, (c.size.height - 1).coerceAtLeast(0).toFloat()))
    }

    /** The place in the text under [root]. */
    fun at(root: Offset): DocumentEditor.DocPos? {
        val (e, local) = entryAt(root) ?: return null
        return DocumentEditor.DocPos(e.index, e.layout!!.getOffsetForPosition(local))
    }

    /** The word around [pos] (its ends), or [pos] twice between words. */
    fun wordAt(pos: DocumentEditor.DocPos): Pair<DocumentEditor.DocPos, DocumentEditor.DocPos> {
        val layout = entries.firstOrNull { it.index == pos.index && it.layout != null }?.layout ?: return pos to pos
        val len = layout.layoutInput.text.length
        if (len == 0) return pos to pos
        val w = layout.getWordBoundary(pos.offset.coerceIn(0, len - 1))
        val text = layout.layoutInput.text.text
        return if (w.collapsed || text.substring(w.start, w.end).isBlank()) pos to pos
        else DocumentEditor.DocPos(pos.index, w.start) to DocumentEditor.DocPos(pos.index, w.end)
    }

    /** The caret line at [pos] in root px (top-left and bottom), when that place shows; null otherwise. */
    fun caretAt(pos: DocumentEditor.DocPos): Pair<Offset, Float>? {
        for (e in entries) {
            if (e.index != pos.index) continue
            val c = e.coords ?: continue
            val layout = e.layout ?: continue
            if (!c.isAttached) continue
            val r = layout.getCursorRect(pos.offset.coerceIn(0, layout.layoutInput.text.length))
            val top = c.localToRoot(Offset(r.left, r.top))
            val shown = c.boundsInRoot()
            if (top.y + r.height / 2 < shown.top || top.y + r.height / 2 > shown.bottom) continue
            return top to top.y + r.height
        }
        return null
    }
}

internal val LocalTextHits = androidx.compose.runtime.compositionLocalOf { TextHits() }

/** A selection being dragged with the pointer: where the pointer is (canvas px) and what it stretches from. */
private class PointerSelect {
    var pointer: Offset? = null
    /** The fixed place when dragging forwards / backwards (a held word's start / end, or a handle's other end). */
    var forward: DocumentEditor.DocPos? = null
    var backward: DocumentEditor.DocPos? = null
}

private val LocalEditArm = androidx.compose.runtime.compositionLocalOf { EditArm() }

/** The last [DocumentEditor.revealRequest] a paragraph answered, so one request scrolls once (not on every recomposition). */
private class RevealState {
    var handled = 0
}

private val LocalReveal = androidx.compose.runtime.compositionLocalOf { RevealState() }

/** The page showing [para] — the part holding [line] when it runs across pages — or null (not laid out yet). */
/**
 * Where the ruler stands: over the page holding the caret (the first page while it is not laid out yet), the column it is
 * in — the page centred in the canvas as the page list centres it — and whether the caret is in a body paragraph.
 */
private fun rulerGeometry(editor: DocumentEditor, pages: List<LaidOutPage>, flow: CanvasFlow, availPx: Int, fullWidth: Boolean, sidePx: Int, z: Float): RulerGeometry {
    val para = editor.activeParagraph
    val pi = pageHolding(pages, para, flow.metricsOf(para)?.lineOf(editor.selEnd)) ?: 0
    val page = pages[pi.coerceIn(0, pages.lastIndex)]
    val pg = page.setup
    val cardW = if (fullWidth) (availPx - 2 * sidePx).toFloat() else minOf(pg.widthPt * z, availPx.toFloat())
    val pageX = (availPx - cardW) / 2f
    val columnIndex = page.frames.indexOfFirst { frame ->
        frame.any { item -> (item is PageItem.Para && item.block.paragraph === para) }
    }.coerceAtLeast(0)
    val marginLeft = pg.marginLeftPt * z
    val columns = (0 until maxOf(1, page.frames.size)).map { c -> (marginLeft + c * (page.columnPx + page.gapPx)) to page.columnPx.toFloat() }
    val body = editor.document.locations().getOrNull(editor.active) is com.therealityteam.aeropages.model.TopLoc
    return RulerGeometry(pageX, cardW, columns.getOrElse(columnIndex) { columns.first() }.first, page.columnPx.toFloat(), columns, z, body)
}

private fun pageHolding(pages: List<LaidOutPage>, para: Paragraph, line: Int?): Int? {
    pages.forEachIndexed { i, page ->
        for (frame in page.frames) for (item in frame) {
            val hit = when (item) {
                is PageItem.Para -> item.block.paragraph === para && (line == null || (line >= item.from && (item.to < 0 || line < item.to)))
                is PageItem.TablePart -> item.block.table.rows.withIndex().any { (r, row) ->
                    (item.slice == null || r in item.slice.rows) && row.cells.any { cell -> cell.content.any { it === para } }
                }
                is PageItem.Box -> item.block.paragraphs.any { it === para }
                else -> false
            }
            if (hit) return i
        }
    }
    return null
}

/**
 * A page: the header in the top margin, the body between the margins (its columns, then the
 * footnotes at its foot), the footer and page number in the bottom margin.
 */
@Composable
private fun PageCard(
    shownNumber: String,
    editor: DocumentEditor,
    page: LaidOutPage,
    startIndices: Map<com.therealityteam.aeropages.model.Block, Int>,
    pageNumber: Int,
    pageCount: Int,
    pageOfParagraph: Map<Long, String>,
    fullWidth: Boolean,
    focusRequester: FocusRequester,
    onSave: () -> Unit,
    onFind: () -> Unit,
) {
    val doc = editor.document
    val pg = page.setup   // this page's section: size, margins and columns can differ per section
    // Its paragraphs' tabs stretched to their stops, as the page layout measured them.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tabSpec = TabSpacing.Spec(doc.defaultTabPt, page.columnPx / density.density, density.fontScale)
    androidx.compose.runtime.CompositionLocalProvider(LocalTabSpec provides tabSpec) {
        // Page geometry comes straight from the document's PageSetup (points ≈ dp here),
        // so the Layout tab (size / orientation / margins) has a real visible effect.
        Box(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .then(if (fullWidth) Modifier.fillMaxWidth().padding(horizontal = 24.dp) else Modifier.width(pg.widthPt.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(doc.pageColor?.let { Color(it) } ?: Color.White)
                .height(pg.heightPt.dp),
        ) {
            // Word's page borders, where the page has them (not in Web Layout, which has no pages) — drawn as in the PDF.
            doc.pageBorders?.takeIf { !fullWidth && it.shows(pageNumber - 1) }?.let { b ->
                Box(Modifier.matchParentSize().drawBehind {
                    drawIntoCanvas { c -> com.therealityteam.aeropages.format.pdf.PageBorderPainter.draw(c.nativeCanvas, b, pg, 1.dp.toPx()) }
                })
            }
            doc.watermark?.takeIf { it.isNotBlank() }?.let { wm ->
                Text(
                    wm,
                    style = PicoTheme.typography.displayLarge,
                    color = Color(0xFF000000),
                    modifier = Modifier.align(Alignment.Center).rotate(-30f).alpha(0.08f),
                )
            }
            // The header's and footer's pictures (a letterhead's logo), under everything else on the page.
            HfPictures(doc, pageNumber, pg, page)
            // Pictures and text boxes behind the text, where they stand on the page (one picked comes to the front,
            // so it and its actions show).
            page.floats.filter { it.behind && editor.selectedImage !== it.block && editor.activeBox !== it.block }.forEach { f ->
                androidx.compose.runtime.key(f.block) { FloatView(editor, f, startIndices, focusRequester, onSave, onFind) }
            }
            // What the header and footer take from the body where they reach past the margins (the layout gave the
            // body the same room).
            val room = com.therealityteam.aeropages.model.HeaderFooters.room(doc, pageNumber, pg)
            // Header: in the top margin, its top the header distance below the page's edge (Word's layout);
            // the line marks where the body starts.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height((pg.marginTopPt + room.top).dp)
                    .padding(start = pg.marginLeftPt.dp, end = pg.marginRightPt.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = pg.headerPt.coerceIn(0f, pg.marginTopPt).dp)) {
                    HeaderFooterZone(editor, isHeader = true, hint = pageNumber <= 3, pageNumber = pageNumber, pageCount = pageCount, pg = pg, shown = shownNumber)
                }
                // Where the header reaches into the body, the line marks the body's top just below it.
                HorizontalDivider(Modifier.align(Alignment.BottomCenter).padding(bottom = if (room.top > 0f) 0.dp else 6.dp))
            }
            // Body: exactly the space between the margins — the height the layout filled. What spills is cut at its top and
            // foot; across, the margins stay open for what the text puts there (a change bar, a comment's mark).
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = pg.marginLeftPt.dp, end = pg.marginRightPt.dp, top = pg.marginTopPt.dp, bottom = pg.marginBottomPt.dp)
                    .drawWithContent {
                        clipRect(left = -pg.marginLeftPt.dp.toPx(), top = 0f, right = size.width + pg.marginRightPt.dp.toPx(), bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                ColumnsRow(page) { frame -> PageItems(editor, frame, startIndices, pageOfParagraph, focusRequester, onSave, onFind) }
                // Footnotes whose references sit on this page, at the foot of the body (Word's place for them).
                if (page.notes.isNotEmpty()) FootnotesArea(doc, page.notes, Modifier.align(Alignment.BottomStart).padding(bottom = room.foot.dp))
            }
            // Footer: in the bottom margin, its foot the footer distance above the page's edge; the app's
            // page indicator under it.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height((pg.marginBottomPt + room.foot).dp)
                    .padding(start = pg.marginLeftPt.dp, end = pg.marginRightPt.dp),
            ) {
                HorizontalDivider(Modifier.align(Alignment.TopCenter).padding(top = if (room.foot > 0f) 0.dp else 6.dp))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = pg.footerPt.coerceIn(0f, pg.marginBottomPt).dp)) {
                    HeaderFooterZone(editor, isHeader = false, hint = pageNumber <= 3, pageNumber = pageNumber, pageCount = pageCount, pg = pg, shown = shownNumber)
                }
                Text(
                    stringResource(R.string.status_page_of_pages, pageNumber, pageCount),
                    style = AeroType.caption,
                    color = PicoTheme.colorScheme.labelTertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            // In front of the text: over everything else on the page (the picked one over the others).
            page.floats.filter { !it.behind || editor.selectedImage === it.block || editor.activeBox === it.block }
                .sortedBy { if (editor.selectedImage === it.block || editor.activeBox === it.block) 1 else 0 }.forEach { f ->
                androidx.compose.runtime.key(f.block) { FloatView(editor, f, startIndices, focusRequester, onSave, onFind) }
            }
        }
    }
}

/**
 * The pictures of the header and footer page [pageNumber] shows, each where it stands on the page (a
 * page drawn at the window's width in full-width mode keeps them at its own scale's places across).
 */
@Composable
private fun HfPictures(doc: com.therealityteam.aeropages.model.Document, pageNumber: Int, pg: com.therealityteam.aeropages.model.PageSetup, page: LaidOutPage) {
    val pics = doc.hfPicturesFor(pageNumber, true).map { it to true } + doc.hfPicturesFor(pageNumber, false).map { it to false }
    if (pics.isEmpty()) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The page as drawn: its body as wide as the layout made its columns.
    val pageW = page.columnPx * com.therealityteam.aeropages.model.PageMetrics.columnsOf(pg) / density.density + page.gapPx * (com.therealityteam.aeropages.model.PageMetrics.columnsOf(pg) - 1) / density.density + pg.marginLeftPt + pg.marginRightPt
    pics.forEach { (img, header) ->
        // One in its line is drawn with its line (HeaderFooterZone); a floating one moving with its paragraph counts from the header's top.
        val paraTop = com.therealityteam.aeropages.model.HeaderFooters.paraTop(header, com.therealityteam.aeropages.model.HeaderFooters.layout(doc, pageNumber, 1, pg, header), pg)
        val (x, y) = com.therealityteam.aeropages.model.FloatPlacement.placeInHeader(img, header, pg, pageW, paraTop) ?: return@forEach
        PlainPicture(img, Modifier.offset(x.dp, y.dp).size(img.widthPt.dp, img.heightPt.dp))
    }
}

/** A picture drawn as it is — the part its crop leaves, mirrored as it says — with nothing to pick or work on. */
@Composable
private fun PlainPicture(image: ImageBlock, modifier: Modifier) {
    val bitmap = rememberDecodedImage(image.data) ?: return
    val (l, t, r, b) = image.visiblePixels(bitmap.width, bitmap.height)
    val painter = remember(bitmap, l, t, r, b) { BitmapPainter(bitmap, IntOffset(l, t), IntSize(r - l, b - t)) }
    Image(painter = painter, contentDescription = stringResource(R.string.picture),
        modifier = modifier.then(if (image.flipH || image.flipV)
            Modifier.graphicsLayer(scaleX = if (image.flipH) -1f else 1f, scaleY = if (image.flipV) -1f else 1f) else Modifier),
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
}

/**
 * A floating picture or text box over its page at the place the layout found for it: a picture at
 * its own size, a text box with its frame's top there (its margin above it).
 */
@Composable
private fun FloatView(
    editor: DocumentEditor, f: PlacedFloat, startIndices: Map<com.therealityteam.aeropages.model.Block, Int>,
    focusRequester: FocusRequester, onSave: () -> Unit, onFind: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val w = with(density) { f.w.toDp() }
    when (val b = f.block) {
        is ImageBlock -> androidx.compose.runtime.key(b) {
            // Picked, it moves where it is dragged (one undo step when let go).
            var drag by remember { mutableStateOf(Offset.Zero) }
            val picked = editor.selectedImage === b
            PictureFrame(editor, b, Modifier.offset { IntOffset(f.x + drag.x.roundToInt(), f.y + drag.y.roundToInt()) }.size(w, with(density) { f.h.toDp() })
                .then(if (!picked) Modifier else Modifier.pointerInput(b, f) {
                    detectDragGestures(
                        onDragEnd = { f.moveTo(f.x + drag.x, f.y + drag.y)?.let { editor.setImageFloat(b, it) }; drag = Offset.Zero },
                        onDragCancel = { drag = Offset.Zero },
                    ) { change, delta -> change.consume(); drag += delta }
                }))
        }
        is com.therealityteam.aeropages.model.TextBoxBlock -> androidx.compose.runtime.key(b) {
            val margin = with(density) { PageMetrics.BOX_MARGIN.dp.roundToPx() }
            // Dragged by its bar's handle, it moves (one undo step when let go).
            var drag by remember { mutableStateOf(Offset.Zero) }
            val move = MoveDrag(
                by = { drag += it },
                end = { f.moveTo(f.x + drag.x, f.y + drag.y)?.let { editor.setBoxFloat(b, it) }; drag = Offset.Zero },
                cancel = { drag = Offset.Zero },
            )
            Box(Modifier.offset { IntOffset(f.x + drag.x.roundToInt(), f.y - margin + drag.y.roundToInt()) }.width(w)) {
                TextBoxView(editor, b, startIndices[b] ?: 0, focusRequester, onSave, onFind, move = move)
            }
        }
        // A table placed on the page (its rows' top there, the table's padding above it).
        is com.therealityteam.aeropages.model.TableBlock -> androidx.compose.runtime.key(b) {
            val pad = with(density) { PageMetrics.TABLE_PAD.dp.roundToPx() }
            Box(Modifier.offset { IntOffset(f.x, f.y - pad) }.width(w)) {
                TableView(editor, b.table, startIndices[b] ?: 0, focusRequester, onSave, onFind)
            }
        }
        else -> Unit
    }
}

/** The body's text columns, each [LaidOutPage.columnPx] wide and [LaidOutPage.gapPx] apart. */
@Composable
private fun ColumnsRow(page: LaidOutPage, frame: @Composable (List<PageItem>) -> Unit) {
    val cols = com.therealityteam.aeropages.model.PageMetrics.columnsOf(page.setup)
    // Word's line between columns: in the middle of each gap, as tall as the tallest column on the page.
    val line = page.setup.columnLine && cols > 1
    Layout(content = {
        for (i in 0 until cols) FrameColumn { frame(page.frames.getOrNull(i).orEmpty()) }
        if (line) androidx.compose.foundation.Canvas(Modifier) {
            for (k in 1 until cols) {
                val x = k * (page.columnPx + page.gapPx) - page.gapPx / 2f
                drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx().coerceAtLeast(1f))
            }
        }
    }) { measurables, constraints ->
        val w = if (cols == 1) constraints.maxWidth else page.columnPx
        val placeables = measurables.take(cols).map { it.measure(Constraints(minWidth = w, maxWidth = w)) }
        val tallest = placeables.maxOfOrNull { it.height }?.coerceAtMost(constraints.maxHeight) ?: 0
        val linePlaceable = if (line) measurables[cols].measure(Constraints.fixed(constraints.maxWidth, tallest.coerceAtLeast(0))) else null
        layout(constraints.maxWidth, constraints.maxHeight) {
            linePlaceable?.place(0, 0)
            placeables.forEachIndexed { i, p -> p.place(i * (w + page.gapPx), 0) }
        }
    }
}

/** Stacks a column's pieces at their full heights; the layout already made them fit the page. */
@Composable
private fun FrameColumn(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        layout(constraints.maxWidth, placeables.sumOf { it.height }) {
            var y = 0
            placeables.forEach { it.place(0, y); y += it.height }
        }
    }
}

/** The pieces of one column. */
@Composable
private fun PageItems(
    editor: DocumentEditor,
    items: List<PageItem>,
    startIndices: Map<com.therealityteam.aeropages.model.Block, Int>,
    pageOfParagraph: Map<Long, String>,
    focusRequester: FocusRequester,
    onSave: () -> Unit,
    onFind: () -> Unit,
) {
    val flow = LocalCanvasFlow.current
    items.forEach { item ->
        when (item) {
            is PageItem.Para -> androidx.compose.runtime.key(item.block, item.from) {
                ParagraphPiece(editor, item, startIndices[item.block] ?: 0, flow?.labels?.get(item.block.paragraph), focusRequester, onSave, onFind)
            }
            is PageItem.TablePart -> androidx.compose.runtime.key(item.block, item.slice?.rows?.first) {
                val x = item.x; val w = item.w
                if (x == null || w == null) TableView(editor, item.block.table, startIndices[item.block] ?: 0, focusRequester, onSave, onFind, slice = item.slice)
                else {
                    // A table of its own width (or a floating one moving with its paragraph): at its place across the column.
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    Box(Modifier.offset { IntOffset(x, 0) }.width(with(density) { w.toDp() })) {
                        TableView(editor, item.block.table, startIndices[item.block] ?: 0, focusRequester, onSave, onFind, slice = item.slice)
                    }
                }
            }
            is PageItem.Picture -> androidx.compose.runtime.key(item.block) {
                ImageView(editor, item.block, item.x)
            }
            is PageItem.Formula -> androidx.compose.runtime.key(item.block) {
                EquationView(editor, item.block)
            }
            is PageItem.Chart -> androidx.compose.runtime.key(item.block) {
                ChartBlockView(editor, item.block)
            }
            is PageItem.Box -> androidx.compose.runtime.key(item.block) {
                val x = item.x
                // Worked on, it stands over what follows it: its bar and options may run out over that.
                val lift = if (editor.activeBox === item.block) Modifier.zIndex(1f) else Modifier
                Box(lift.then(if (x != null) Modifier.offset { IntOffset(x, 0) } else Modifier)) {
                    TextBoxView(editor, item.block, startIndices[item.block] ?: 0, focusRequester, onSave, onFind)
                }
            }
            is PageItem.Generated -> androidx.compose.runtime.key(item.paragraph, item.from) { GeneratedPiece(item) }
            is PageItem.Gap -> {
                val density = androidx.compose.ui.platform.LocalDensity.current
                Spacer(Modifier.height(with(density) { item.heightPx.toDp() }))
            }
            is PageItem.TocLine -> androidx.compose.runtime.key(item.entry.paragraph.id) {
                TocLineView(editor, item.entry, pageOfParagraph[item.entry.paragraph.id])
            }
            is PageItem.IndexLine -> androidx.compose.runtime.key(item.key) {
                IndexLineView(editor, item.line, item.line.pages { pageOfParagraph[it.id] }, item.block.rightAlign)
            }
        }
    }
}

/**
 * Header / footer zone. Each page shows the variant that applies to it (first page / even pages /
 * default) as the page prints it — its runs' looks, its alignment, its tab slots, the page number and
 * count filled in — dimmed as Word dims it while the body is edited; a tap opens it for typing (the page
 * fields as their {PAGE} / {PAGES} tokens), so every variant can be edited. An empty one says "Add
 * header" on the first pages ([hint]). A gallery's request (Insert → Header …) opens it on the first
 * page showing it, its "[Type here]" selected.
 */
@Composable
private fun HeaderFooterZone(editor: DocumentEditor, isHeader: Boolean, hint: Boolean, pageNumber: Int, pageCount: Int,
                             pg: com.therealityteam.aeropages.model.PageSetup, shown: String = pageNumber.toString()) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val kind = editor.headerKindFor(pageNumber)
    val para = if (isHeader) editor.document.headerFor(pageNumber) else editor.document.footerFor(pageNumber)
    val variant = when (kind) {
        DocumentEditor.HeaderKind.EVEN -> " (" + stringResource(R.string.label_even_pages) + ")"
        DocumentEditor.HeaderKind.FIRST -> " (" + stringResource(R.string.label_first_page) + ")"
        DocumentEditor.HeaderKind.DEFAULT -> ""
    }
    var editing by remember(isHeader, kind) { mutableStateOf(false) }
    var selectPlaceholder by remember(isHeader, kind) { mutableStateOf(false) }
    LaunchedEffect(editor.revision) {
        if (editor.hfEditRequest != null && editor.takeHeaderFooterEdit(isHeader, kind)) { editing = true; selectPlaceholder = true }
    }
    val fallback = editor.document.styles.resolve(com.therealityteam.aeropages.model.ParagraphStyle.NORMAL).sizePt
    // Its lines as the page prints them: a line holding a picture as tall as the picture.
    val laid = com.therealityteam.aeropages.model.HeaderFooters.layout(editor.document, pageNumber, pageCount, pg, isHeader, shown)
    if (para == null) {
        Box {
            // Pictures alone (a letterhead's logo): drawn as printed; the hint over them only while pointed at.
            if (laid.isNotEmpty()) LaidHeader(laid, null, fallback, Modifier.alpha(0.7f))
            if (hint) {
                val pictures = editor.document.hfPicturesFor(pageNumber, isHeader).isNotEmpty()
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Text(
                    (if (isHeader) stringResource(R.string.add_header) else stringResource(R.string.add_footer)) + variant,
                    style = AeroType.caption,
                    color = PicoTheme.colorScheme.labelQuaternary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .hoverable(interaction)
                        .alpha(if (pictures && !hovered) 0f else 1f)
                        .clickable { if (isHeader) editor.addHeader(kind) else editor.addFooter(kind); editing = true }
                        .padding(vertical = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else if (laid.isEmpty()) {
                Box(Modifier.height(20.dp))
            }
        }
        return
    }
    if (editing) {
        HeaderFooterEditor(editor, para, isHeader, kind, fallback, selectPlaceholder) {
            editing = false; selectPlaceholder = false
            // Left empty (no text, no pictures): no header after all — the page offers "Add header" again.
            if (para.plainText().isBlank() && editor.document.hfPicturesFor(pageNumber, isHeader).isEmpty()) {
                if (isHeader) editor.removeHeader(kind) else editor.removeFooter(kind)
            }
        }
    } else {
        LaidHeader(laid, para, fallback, Modifier.clickable { editing = true }.alpha(0.7f))
    }
}

/**
 * A header or footer open for typing. Without tabs, one field for its text (its lines, the paragraph's
 * alignment); with them, each line's left, centre and right slots as fields of their own where the page
 * shows them (tinted, so the three places show), each changing only its part of the text — the runs'
 * formatting kept. [selectPlaceholder]: the first "[…]" selected, so typing replaces it. Done when the
 * focus leaves it.
 */
@Composable
private fun HeaderFooterEditor(editor: DocumentEditor, para: com.therealityteam.aeropages.model.Paragraph, isHeader: Boolean, kind: DocumentEditor.HeaderKind,
                               fallbackSizePt: Float, selectPlaceholder: Boolean, onDone: () -> Unit) {
    val text = para.plainText()
    val lines = com.therealityteam.aeropages.editor.HeaderFooterEdits.lines(text)
    val style = TextStyle(color = Color.Black, fontSize = (para.runs.firstOrNull()?.format?.fontSizePt ?: fallbackSizePt).sp)
    var hadFocus by remember { mutableStateOf(false) }
    val container = Modifier.fillMaxWidth().onFocusChanged { if (hadFocus && !it.hasFocus) onDone(); hadFocus = it.hasFocus }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    if (lines.all { it.slots == null }) {
        val start = if (selectPlaceholder) com.therealityteam.aeropages.editor.HeaderFooterEdits.placeholder(text) else null
        var value by remember(isHeader, kind) {
            mutableStateOf(TextFieldValue(text, start?.let { TextRange(it.first, it.last + 1) } ?: TextRange(text.length)))
        }
        LaunchedEffect(text) { if (text != value.text) value = TextFieldValue(text, TextRange(text.length)) }
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                if (isHeader) editor.setHeaderText(it.text, kind) else editor.setFooterText(it.text, kind)
            },
            modifier = container.focusRequester(focus),
            textStyle = style.copy(textAlign = TextStyling.composeAlign(para.format.align)),
            cursorBrush = SolidColor(PicoTheme.colorScheme.interaction),
            // Empty, it says what it is ("Header" / "Footer"), faintly, as Word's header tab does.
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) Text(stringResource(if (isHeader) R.string.txt_header_placeholder else R.string.txt_footer_placeholder),
                        style = style.copy(textAlign = TextStyling.composeAlign(para.format.align)), color = Color.Black.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth())
                    inner()
                }
            },
        )
        return
    }
    // The field the focus starts in: the first holding a placeholder when asked to select one, else the first.
    val slotTexts = lines.flatMap { l -> (0..2).map { k -> l.slots?.getOrNull(k)?.let { text.substring(it.start, it.end) } } }
    val first = if (selectPlaceholder) slotTexts.indexOfFirst { it != null && com.therealityteam.aeropages.editor.HeaderFooterEdits.placeholder(it) != null }.coerceAtLeast(0) else 0
    val tint = PicoTheme.colorScheme.interaction.copy(alpha = 0.08f)
    Column(container, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEachIndexed { li, line ->
            val slots = line.slots
            if (slots == null) {
                androidx.compose.runtime.key(li, -1) {
                    SlotField(text.substring(line.start, line.end), TextStyling.composeAlign(para.format.align), style, if (li * 3 == first) focus else null, false,
                        Modifier.fillMaxWidth()) { editor.setHeaderFooterRange(isHeader, kind, line.start, line.end, it) }
                }
            } else Box(Modifier.fillMaxWidth()) {
                for (k in 0..2) androidx.compose.runtime.key(li, k) {
                    val slot = slots.getOrNull(k)
                    val at = li * 3 + k
                    val align = when (k) {
                        0 -> androidx.compose.ui.text.style.TextAlign.Start
                        1 -> androidx.compose.ui.text.style.TextAlign.Center
                        else -> androidx.compose.ui.text.style.TextAlign.End
                    }
                    val box = when (k) { 0 -> Alignment.CenterStart; 1 -> Alignment.Center; else -> Alignment.CenterEnd }
                    SlotField(slot?.let { text.substring(it.start, it.end) } ?: "", align, style, if (at == first) focus else null,
                        selectPlaceholder && at == first, Modifier.align(box).fillMaxWidth(0.32f).background(tint, RoundedCornerShape(3.dp))) { typed ->
                        // A slot the line does not have yet: the tabs that reach it go in before its text.
                        if (slot != null) editor.setHeaderFooterRange(isHeader, kind, slot.start, slot.end, typed)
                        else editor.setHeaderFooterRange(isHeader, kind, line.end, line.end, "\t".repeat(k - slots.size + 1) + typed)
                    }
                }
            }
        }
    }
}

/** One slot's (or line's) field: its text [slotText], selected whole when [select]; changes go to [onChange]. */
@Composable
private fun SlotField(slotText: String, align: androidx.compose.ui.text.style.TextAlign, style: TextStyle, focus: FocusRequester?, select: Boolean,
                      modifier: Modifier, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(slotText, if (select) TextRange(0, slotText.length) else TextRange(slotText.length))) }
    LaunchedEffect(slotText) { if (slotText != value.text) value = TextFieldValue(slotText, TextRange(slotText.length)) }
    BasicTextField(
        value = value,
        onValueChange = { value = it; if (it.text != slotText) onChange(it.text) },
        singleLine = true,
        modifier = modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).padding(horizontal = 2.dp),
        textStyle = style.copy(textAlign = align),
        cursorBrush = SolidColor(PicoTheme.colorScheme.interaction),
    )
}

/**
 * A header's or footer's lines as the page prints them ([laid], see HeaderFooters.layout): each line's
 * pictures and text side by side as its paragraph ([para]) aligns them, the text on the line's foot; a
 * line with tabs in its left, centre and right slots. The box is as tall as the lines.
 */
@Composable
private fun LaidHeader(laid: List<com.therealityteam.aeropages.model.HeaderFooters.LaidLine>, para: com.therealityteam.aeropages.model.Paragraph?,
                       fallbackSizePt: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val top = laid.first().top
    val align = para?.format?.align ?: com.therealityteam.aeropages.model.TextAlign.LEFT
    fun text(runs: List<com.therealityteam.aeropages.model.TextRun>) = androidx.compose.ui.text.buildAnnotatedString {
        runs.forEach { r -> withStyle(TextStyling.spanOf(r.format, fallbackSizePt)) { append(r.text) } }
    }
    BoxWithConstraints(modifier.fillMaxWidth().height((laid.last().bottom - top).dp)) {
        val width = maxWidth.value
        laid.forEach { l ->
            val textTop = l.top - top + l.height - l.textHeight
            val base = TextStyle(color = Color.Black, fontSize = fallbackSizePt.sp, lineHeight = l.textHeight.coerceAtLeast(1f).sp)
            val slots = l.line.slots
            val xs = if (slots == null) {
                val line = text(l.line.runs)
                val textW = if (line.isEmpty()) 0f else measurer.measure(line, base, softWrap = false, maxLines = 1).size.width / density.density
                val (xs, textX) = com.therealityteam.aeropages.model.HeaderFooters.across(l, align, textW, 0f, width)
                if (line.isNotEmpty()) BasicText(line, Modifier.offset(textX.dp, textTop.dp), style = base, softWrap = false, maxLines = 1)
                xs
            } else {
                Box(Modifier.fillMaxWidth().offset(y = textTop.dp)) {
                    BasicText(text(slots[0]), Modifier.align(Alignment.CenterStart), style = base, maxLines = 1)
                    BasicText(text(slots[1]), Modifier.align(Alignment.Center), style = base, maxLines = 1)
                    BasicText(text(slots[2]), Modifier.align(Alignment.CenterEnd), style = base, maxLines = 1)
                }
                com.therealityteam.aeropages.model.HeaderFooters.across(l, align, 0f, 0f, width).first
            }
            l.pictures.forEachIndexed { i, img ->
                PlainPicture(img, Modifier.offset(xs[i].dp, (l.top - top + l.height - img.heightPt).dp).size(img.widthPt.dp, img.heightPt.dp))
            }
        }
    }
}

/** Map each block to the flat reading-order index of its first paragraph. */
private fun computeStartIndices(doc: com.therealityteam.aeropages.model.Document): Map<com.therealityteam.aeropages.model.Block, Int> {
    val map = java.util.IdentityHashMap<com.therealityteam.aeropages.model.Block, Int>()
    var flat = 0
    doc.blocks.forEach { block ->
        map[block] = flat
        flat += when (block) {
            is ParagraphBlock -> 1
            is TableBlock -> block.table.rows.sumOf { row -> row.cells.sumOf { it.content.size } }
            is com.therealityteam.aeropages.model.TextBoxBlock -> block.paragraphs.size
            else -> 0
        }
    }
    return map
}

/**
 * A body paragraph on a page. When the paragraph runs across pages each page shows its own lines
 * through a [LineWindow]; the part holding the caret is the editable one, the other mirrors it.
 */
@Composable
private fun ParagraphPiece(
    editor: DocumentEditor,
    item: PageItem.Para,
    index: Int,
    label: String?,
    focusRequester: FocusRequester,
    onSave: () -> Unit,
    onFind: () -> Unit,
) {
    val para = item.block.paragraph
    val isActive = index == editor.active
    if (item.whole) {
        ParagraphRow(editor, para, index, isActive, label, focusRequester, onSave, onFind)
        return
    }
    val metrics = LocalCanvasFlow.current?.metricsOf(para)
    val hosts = if (!isActive || metrics == null) true else {
        val line = metrics.lineOf(editor.selStart)
        line >= item.from && (item.to < 0 || line < item.to)
    }
    LineWindow(item.from, item.to, metrics?.beforePx ?: 0) { onLayout ->
        ParagraphRow(editor, para, index, isActive, label, focusRequester, onSave, onFind, hostsEditor = hosts, onLayout = onLayout)
    }
}

/**
 * Shows lines [from, to) of the paragraph [content] draws ([to] = -1: through the last line), cut
 * exactly between lines of its real text layout. [beforePx] is the paragraph's space-before, which
 * only the first part shows (the last part keeps the space-after).
 */
@Composable
private fun LineWindow(from: Int, to: Int, beforePx: Int, content: @Composable ((TextLayoutResult) -> Unit) -> Unit) {
    val holder = remember { arrayOfNulls<TextLayoutResult>(1) }
    val onLayout = remember { { r: TextLayoutResult -> holder[0] = r } }
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val tl = holder[0]
                val n = tl?.lineCount ?: 0
                val a = from.coerceIn(0, n)
                val b = if (to < 0) n else to.coerceIn(a, n)
                val top = if (tl == null || a == 0) 0 else beforePx + tl.getLineTop(a).roundToInt()
                val bottom = if (tl == null || b >= n) p.height else beforePx + tl.getLineTop(b).roundToInt()
                layout(p.width, (bottom - top).coerceAtLeast(0)) { p.place(0, -top) }
            },
    ) { content(onLayout) }
}

/** Generated text (contents / bibliography / endnotes heading or entry), possibly one part of it. */
@Composable
private fun GeneratedPiece(item: PageItem.Generated) {
    if (item.from == 0 && item.to == -1) { StaticParagraph(item.paragraph); return }
    val before = LocalCanvasFlow.current?.metricsOf(item.paragraph)?.beforePx ?: 0
    LineWindow(item.from, item.to, before) { onLayout -> StaticParagraph(item.paragraph, onLayout) }
}

/**
 * Word's line numbers in the margin beside a body paragraph's lines: each number's right edge [CanvasFlow.LineNos.distancePt]
 * left of the column, on its line's baseline (of the text laid out in [layout], [textTopPt] down the row).
 */
private fun Modifier.lineNumbers(flow: CanvasFlow?, p: Paragraph, textTopPt: Float, layout: () -> TextLayoutResult?): Modifier =
    if (flow == null) this else drawWithContent {
        drawContent()
        @Suppress("UNUSED_VARIABLE") val version = flow.lineNumbersVersion.intValue
        val nos = flow.lineNumbersOf(p) ?: return@drawWithContent
        val l = layout() ?: return@drawWithContent
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); textSize = nos.sizePt.sp.toPx(); textAlign = android.graphics.Paint.Align.RIGHT
        }
        val right = -nos.distancePt.dp.toPx()
        val top = textTopPt.dp.toPx()
        drawIntoCanvas { c ->
            for (i in 0 until minOf(l.lineCount, nos.numbers.size)) {
                val n = nos.numbers[i]
                if (n > 0) c.nativeCanvas.drawText(n.toString(), right, top + l.getLineBaseline(i), paint)
            }
        }
    }

/**
 * [p]'s drop cap (see DropCaps, CanvasFlow.dropOf): its letters drawn large on the baseline of the line they drop to —
 * at the start of the lines indented past them, or in the margin left of the text — with the room the paragraph keeps
 * below a text shorter than the drop. Nothing without one.
 */
private fun Modifier.dropCap(flow: CanvasFlow?, p: Paragraph, measurer: androidx.compose.ui.text.TextMeasurer, layout: () -> TextLayoutResult?): Modifier {
    val drop = flow?.dropOf(p) ?: return this
    val extra = kotlin.math.ceil(drop.extraPx).toInt()
    return this
        .then(if (extra > 0) Modifier.layout { m, c ->
            val placed = m.measure(c)
            layout(placed.width, placed.height + extra) { placed.place(0, 0) }
        } else Modifier)
        .drawWithContent {
            drawContent()
            val l = layout() ?: return@drawWithContent
            if (l.lineCount == 0) return@drawWithContent
            val cap = measurer.measure(drop.text, softWrap = false)
            val line = drop.baselineLine
            val baseline = if (line < l.lineCount) l.getLineBaseline(line) else l.getLineBaseline(l.lineCount - 1) + (line - l.lineCount + 1) * drop.pitchPx
            drawText(cap, topLeft = Offset(drop.x, baseline - cap.firstBaseline))
        }
}

/** A paragraph's box (Word's borders and shading) behind its row, drawn as the PDF draws it (ParagraphBoxPainter). */
private fun Modifier.paragraphBox(f: com.therealityteam.aeropages.model.ParagraphFormat, edge: com.therealityteam.aeropages.model.BoxEdge?): Modifier =
    if (!ParagraphBoxes.boxed(f)) this else drawBehind {
        val scale = 1.dp.toPx()
        drawIntoCanvas { com.therealityteam.aeropages.format.pdf.ParagraphBoxPainter.draw(it.nativeCanvas, f, edge, 0f, 0f, size.width / scale, size.height / scale, scale) }
    }

/** Read-only text laid out exactly like a body paragraph (same spacing, pitch and width). */
@Composable
private fun StaticParagraph(p: Paragraph, onLayout: ((TextLayoutResult) -> Unit)? = null) {
    val f = p.format
    val inset = PageMetrics.indents(f)
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val flow = LocalCanvasFlow.current
    val edge = flow?.edgeOf(p)
    val before = ParagraphBoxes.beforePt(f, edge)
    Row(Modifier.fillMaxWidth().paragraphBox(f, edge).lineNumbers(flow, p, before) { layout[0] }
        .padding(start = inset[0].dp, end = inset[3].dp, top = before.dp, bottom = ParagraphBoxes.afterPt(f, edge).dp)) {
        BasicText(
            TextStyling.displayTextOf(p, LocalTabSpec.current),
            modifier = Modifier.fillMaxWidth().tabLeaders(p, LocalTabSpec.current) { layout[0] },
            style = TextStyling.editBaseStyle(p, LocalCanvasFlow.current?.gridOf(p)),
            onTextLayout = { layout[0] = it; onLayout?.invoke(it) },
        )
    }
}

/**
 * The leaders of [p]'s tabs — dots, hyphens, middle dots, a rule — drawn across the stretched spaces
 * that show the tabs (see TabSpacing), on each tab's line of the [layout].
 */
private fun Modifier.tabLeaders(p: Paragraph, spec: TabSpacing.Spec?, layout: () -> TextLayoutResult?): Modifier {
    if (spec == null) return this
    val tabs = TabSpacing.tabs(p, spec)
    if (tabs.none { it.leader != com.therealityteam.aeropages.model.TabLeader.NONE }) return this
    val offsets = ArrayList<Int>()
    var at = 0
    for (r in p.runs) for (c in r.text) { if (c == '\t') offsets.add(at); at++ }
    return drawBehind {
        val l = layout() ?: return@drawBehind
        val len = l.layoutInput.text.length
        offsets.forEachIndexed { i, off ->
            val tab = tabs.getOrNull(i) ?: return@forEachIndexed
            val leader = tab.leader
            if (leader == com.therealityteam.aeropages.model.TabLeader.NONE || off >= len) return@forEachIndexed
            // From where the tab starts, as wide as it advances (the layout's own positions inside a
            // letter-spaced character count only half its spacing).
            val line = l.getLineForOffset(off)
            val left = l.getHorizontalPosition(off, true)
            val right = (left + tab.advance.dp.toPx()).coerceAtMost(size.width)
            val box = androidx.compose.ui.geometry.Rect(left, l.getLineTop(line), right, l.getLineBottom(line))
            val baseline = l.getLineBaseline(line)
            val em = (box.bottom - box.top) / PageMetrics.SINGLE_LINE
            val ink = Color(0xFF1F1F1F)
            when (leader) {
                com.therealityteam.aeropages.model.TabLeader.UNDERSCORE ->
                    drawLine(ink, Offset(box.left, baseline + em * 0.12f), Offset(box.right, baseline + em * 0.12f), strokeWidth = (em / 16f).coerceAtLeast(1f))
                com.therealityteam.aeropages.model.TabLeader.HYPHEN -> {
                    var x = box.left + em * 0.15f
                    while (x + em * 0.3f <= box.right) { drawLine(ink, Offset(x, baseline - em * 0.3f), Offset(x + em * 0.3f, baseline - em * 0.3f), strokeWidth = (em / 14f).coerceAtLeast(1f)); x += em * 0.5f }
                }
                else -> {
                    // Dots on the baseline (middle dots at the x-height's middle), as far apart as the font's periods.
                    val y = if (leader == com.therealityteam.aeropages.model.TabLeader.MIDDLE_DOT) baseline - em * 0.3f else baseline - em * 0.06f
                    var x = box.right - em * 0.2f
                    while (x >= box.left + em * 0.1f) { drawCircle(ink, radius = (em * 0.055f).coerceAtLeast(0.8f), center = Offset(x, y)); x -= em * 0.28f }
                }
            }
        }
    }
}

/** One table-of-contents line: the heading, a dotted leader and the page it starts on; tap to go there. */
@Composable
private fun TocLineView(editor: DocumentEditor, entry: com.therealityteam.aeropages.model.TocEntry, page: String?) {
    val pageBg = editor.document.pageColor?.let { Color(it) } ?: Color.White
    val style = TextStyle(
        color = Color(0xFF1F1F1F),
        fontSize = PageMetrics.ENTRY_SIZE.sp,
        fontWeight = if (entry.level == 0 && !entry.figure) FontWeight.Bold else null,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(PageMetrics.TOC_LINE.dp)
            .clickable {
                val i = editor.document.locations().indexOfFirst { it.paragraph.id == entry.paragraph.id }
                if (i >= 0) editor.focusParagraph(i)
            }
            .padding(start = (entry.level * PageMetrics.TOC_INDENT).dp)
            .drawBehind {
                val y = size.height * 0.7f
                drawLine(
                    Color(0xFF9E9E9E), Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 1.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.5f, 5f)),
                )
            },
    ) {
        BasicText(
            entry.text.ifBlank { Texts.get(TextId.UNTITLED_HEADING) }, style = style, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(end = 44.dp).background(pageBg).padding(end = 6.dp),
        )
        BasicText(
            page?.toString() ?: "", style = style,
            modifier = Modifier.align(Alignment.CenterEnd).background(pageBg).padding(start = 6.dp),
        )
    }
}

/**
 * One index line: a letter heading, or an entry (a subentry indented) with its pages — after a comma, as Word's index
 * has them, or [rightAlign]ed after a dotted leader; tap an entry to go to where it is first marked.
 */
@Composable
private fun IndexLineView(editor: DocumentEditor, line: com.therealityteam.aeropages.model.IndexEntries.Line, pages: String, rightAlign: Boolean) {
    val pageBg = editor.document.pageColor?.let { Color(it) } ?: Color.White
    val style = TextStyle(
        color = Color(0xFF1F1F1F),
        fontSize = (if (line.heading) PageMetrics.ENTRY_SIZE + 1f else PageMetrics.ENTRY_SIZE).sp,
        fontWeight = if (line.heading) FontWeight.Bold else null,
    )
    val go = Modifier.then(if (line.refs.isEmpty()) Modifier else Modifier.clickable {
        val i = editor.document.locations().indexOfFirst { it.paragraph === line.refs.first() }
        if (i >= 0) editor.focusParagraph(i)
    })
    Box(
        Modifier
            .fillMaxWidth()
            .height(PageMetrics.TOC_LINE.dp)
            .then(go)
            .padding(start = (line.level.coerceAtLeast(0) * PageMetrics.TOC_INDENT).dp)
            .then(if (rightAlign && pages.isNotEmpty()) Modifier.drawBehind {
                val y = size.height * 0.7f
                drawLine(
                    Color(0xFF9E9E9E), Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 1.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.5f, 5f)),
                )
            } else Modifier),
    ) {
        if (rightAlign && pages.isNotEmpty()) {
            BasicText(
                line.text, style = style, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart).padding(end = 44.dp).background(pageBg).padding(end = 6.dp),
            )
            BasicText(pages, style = style, modifier = Modifier.align(Alignment.CenterEnd).background(pageBg).padding(start = 6.dp))
        } else BasicText(
            if (pages.isEmpty()) line.text else "${line.text}, $pages", style = style, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

/** The page's footnotes: a short rule, then "[n] text" for each note referenced on the page. */
@Composable
private fun FootnotesArea(doc: com.therealityteam.aeropages.model.Document, notes: List<Int>, modifier: Modifier) {
    val flow = LocalCanvasFlow.current ?: return
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(PageMetrics.NOTE_GAP_ABOVE.dp))
        Box(Modifier.width(PageMetrics.NOTE_RULE_WIDTH.dp).height(PageMetrics.NOTE_RULE.dp).background(Color(0xFF9E9E9E)))
        Spacer(Modifier.height(PageMetrics.NOTE_GAP_BELOW.dp))
        notes.sorted().forEach { n -> flow.footnoteParagraph(doc, n)?.let { StaticParagraph(it) } }
    }
}

/**
 * A picture in the text's flow (a block of its own or inside a table cell), scaled down to fit the
 * column: centred, or — a floating one the text goes around — at its place across the column ([x],
 * pixels).
 */
@Composable
private fun ImageView(editor: DocumentEditor, image: ImageBlock, x: Int? = null) {
    // Picked, it stands over what follows it in the column: its actions and options may run out over that.
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val lift = if (editor.selectedImage === image) Modifier.zIndex(1f) else Modifier
    BoxWithConstraints(Modifier.then(lift).fillMaxWidth().padding(vertical = PageMetrics.IMAGE_PAD.dp), contentAlignment = if (x == null) Alignment.TopCenter else Alignment.TopStart) {
        val (w, h) = PageMetrics.fitImage(image.widthPt, image.heightPt, maxWidth.value)
        PictureFrame(editor, image, Modifier.then(if (x != null) Modifier.offset { IntOffset(x, 0) } else Modifier).size(w.dp, h.dp))
    }
}

/**
 * A picture at the size [modifier] gives it: the part its crop leaves stretched to it (Word's
 * `a:stretch`), mirrored as it says. Pointed at, it is outlined; a tap picks it (another lets it go):
 * a frame shows, with its actions on its corner — smaller, larger, crop, shadow, copy, delete — so
 * the page shows pictures whole until one is being worked on.
 */
@Composable
private fun PictureFrame(editor: DocumentEditor, image: ImageBlock, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var showCrop by remember { mutableStateOf(devCropPicture(editor.document) === image) }
    val selected = editor.selectedImage === image
    // Its wrapping and place (Word's Layout Options): for a picture of its own, not one in a table's cell.
    val ownBlock = editor.document.blocks.any { it === image }
    val showLayout = selected && editor.imageOptionsOpen
    val flow = LocalCanvasFlow.current
    val pxPerPt = androidx.compose.ui.platform.LocalDensity.current.density
    // Dev hooks (`setprop debug.aero.dialog …`): `picture` — the document's first picture comes up picked (`picformat`:
    // with the Picture Format tab open);
    // `picturelayout` — and its wrapping options open; `picturebehind` — it goes behind the text, picked.
    LaunchedEffect(image) {
        val hook = devDialogProp()
        if (hook in setOf("picture", "picturelayout", "picturebehind", "picformat", "alttext") && editor.selectedImage == null && firstPicture(editor.document) === image) {
            if (hook == "picturebehind") editor.setImageFloat(image, wrappedAs(editor, image, image.floating, com.therealityteam.aeropages.model.FloatWrap.BEHIND, flow, pxPerPt))
            editor.selectImage(image)
            if (hook == "picturelayout") editor.showImageOptions(true)
        }
    }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val frame = PicoTheme.colorScheme.interaction
    // What a screen reader says: the picture's alt text, else "Picture" (decorative: so marked).
    val label = when {
        image.decorative -> stringResource(R.string.a11y_picture_decorative)
        image.altText.isNotEmpty() -> image.altText
        else -> stringResource(R.string.picture)
    }
    val bitmap = rememberDecodedImage(image.data)
    Box(
        modifier
            .hoverable(interaction)
            .semantics { contentDescription = label }
            .clickable(interaction, indication = null) { editor.selectImage(if (selected) null else image) },
    ) {
        if (bitmap != null) {
            // Decoding may downsample: the crop is a fraction of whatever size the bitmap came out.
            val (l, t, r, b) = image.visiblePixels(bitmap.width, bitmap.height)
            val painter = remember(bitmap, l, t, r, b) { BitmapPainter(bitmap, IntOffset(l, t), IntSize(r - l, b - t)) }
            // Its shadow under it (the page is laid out a point to a dp).
            image.shadow?.let { s ->
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { c ->
                        com.therealityteam.aeropages.format.pdf.PictureShadows.draw(c.nativeCanvas, bitmap.asAndroidBitmap(), android.graphics.Rect(l, t, r, b),
                            android.graphics.RectF(0f, 0f, size.width, size.height), s, density)
                    }
                }
            }
            Image(painter = painter, contentDescription = null,
                modifier = Modifier.fillMaxSize().then(if (image.flipH || image.flipV)
                    Modifier.graphicsLayer(scaleX = if (image.flipH) -1f else 1f, scaleY = if (image.flipV) -1f else 1f) else Modifier),
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
        } else {
            Text(stringResource(R.string.err_picture_unavailable), style = AeroType.caption,
                color = PicoTheme.colorScheme.labelTertiary, modifier = Modifier.align(Alignment.Center))
        }
        // Picked: a frame just outside it; pointed at, a fainter one.
        if (selected || hovered) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val out = 2.dp.toPx()
            drawRect(if (selected) frame else frame.copy(alpha = 0.45f), topLeft = Offset(-out, -out),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * out, size.height + 2 * out),
                style = androidx.compose.ui.graphics.drawscope.Stroke((if (selected) 1.5.dp else 1.dp).toPx()))
        }
        // Picked: Word's corner handles. A drag shows the new size as a dashed outline from the opposite
        // corner; letting go sizes the picture (keeping its proportions), one undo step.
        if (selected) {
            var ghost by remember(image) { mutableStateOf<Triple<Float, Int, Int>?>(null) }
            val handleLabel = stringResource(R.string.a11y_picture_handle)
            val wPx = image.widthPt * pxPerPt; val hPx = image.heightPt * pxPerPt
            for ((hx, hy) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
                var drag by remember(image, hx, hy) { mutableStateOf(Offset.Zero) }
                Box(
                    Modifier.align(androidx.compose.ui.BiasAlignment(hx.toFloat(), hy.toFloat()))
                        .offset(x = 14.dp * hx, y = 14.dp * hy).size(28.dp)
                        .semantics { contentDescription = handleLabel }
                        .pointerInput(image, hx, hy) {
                            detectDragGestures(
                                onDragStart = { drag = Offset.Zero },
                                onDrag = { change, amount ->
                                    change.consume(); drag += amount
                                    val fx = (wPx + hx * drag.x) / wPx.coerceAtLeast(1f); val fy = (hPx + hy * drag.y) / hPx.coerceAtLeast(1f)
                                    ghost = Triple((if (kotlin.math.abs(fx - 1f) >= kotlin.math.abs(fy - 1f)) fx else fy).coerceAtLeast(0.05f), hx, hy)
                                },
                                onDragEnd = { ghost?.let { (f, _, _) -> editor.scaleImage(image, f) }; ghost = null },
                                onDragCancel = { ghost = null },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) { HandleDot() }
            }
            ghost?.let { (f, hx, hy) ->
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width * f; val h = size.height * f
                    drawRect(frame, topLeft = Offset(if (hx < 0) size.width - w else 0f, if (hy < 0) size.height - h else 0f),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                }
            }
        }
        if (selected) ContextBar(
            stringResource(R.string.a11y_picture_actions),
            Modifier
                .align(Alignment.BottomEnd)
                // A picture narrower than its buttons still shows them all, the bar running out past its left edge.
                .wrapContentSize(Alignment.BottomEnd, unbounded = true)
                .padding(4.dp),
        ) {
            // How the text goes by it: in line, around it, behind or in front of it (Word's Layout Options).
            if (ownBlock) {
                RibbonMiniButton(wrapIcon(image.floating), stringResource(R.string.picture_wrap), { editor.showImageOptions(!showLayout) }, selected = showLayout)
                BarDivider()
            }
            RibbonMiniButton("x_smaller", stringResource(R.string.image_zoom_out), { editor.scaleImage(image, 0.85f) })
            RibbonMiniButton("x_bigger", stringResource(R.string.image_zoom_in), { editor.scaleImage(image, 1.18f) })
            RibbonMiniButton("x_crop", stringResource(R.string.crop), { showCrop = true })
            // A shadow on or off (Picture Styles' offset one).
            RibbonMiniButton("阴影", stringResource(if (image.shadow == null) R.string.shape_shadow else R.string.no_shadow),
                { editor.setImageShadow(image, if (image.shadow == null) com.therealityteam.aeropages.model.ShapeShadow.PRESETS[0] else null) },
                selected = image.shadow != null)
            BarDivider()
            // Copy the picture for a slide, a sheet or another page.
            val clipboard = rememberTextClipboard()
            RibbonMiniButton("ic_copy", stringResource(R.string.copy), { editor.copyImage(image); clipboard.copyCurrentObject() })
            RibbonMiniButton("ic_delete", stringResource(R.string.delete), { editor.deleteImage(image) })
        }
        // The wrapping options hang below the picture from its bottom right corner (above, they would run out of a
        // page's top when the picture is near it).
        if (selected && showLayout) Box(
            Modifier.align(Alignment.BottomEnd).layout { m, _ ->
                val p = m.measure(Constraints())
                layout(0, 0) { p.place(-p.width, 0) }
            },
        ) {
            WrapOptions(image.floating, { w -> editor.setImageFloat(image, wrappedAs(editor, image, image.floating, w, flow, pxPerPt)) },
                { a -> image.floating?.let { editor.setImageFloat(image, it.copy(hAlign = a)) } })
        }
    }
    if (showCrop) {
        // The whole picture as its frame shows it: the kept part's size grown back by the crop.
        val (fullW, fullH) = image.uncroppedSize(image.widthPt, image.heightPt)
        CropDialog(image.data, fullW / fullH.coerceAtLeast(1f), image,
            onCrop = { l, t, r, b -> editor.cropImage(image, l, t, r, b) }, onDismiss = { showCrop = false })
    }
}

/**
 * A picked picture's or shape's wrapping options (Word's Layout Options): in line with the text, square,
 * top and bottom, behind or in front of the text — the current one ([pos]'s) ticked — and, floating, its
 * place across: at the left, centred or at the right of what it counts from.
 */
@Composable
private fun WrapOptions(pos: com.therealityteam.aeropages.model.FloatPosition?, onWrap: (com.therealityteam.aeropages.model.FloatWrap?) -> Unit,
                        onAlign: (com.therealityteam.aeropages.model.FloatAlign) -> Unit) {
    val current = pos?.wrap
    Column(Modifier.padding(top = 4.dp).width(248.dp).barSurface().padding(vertical = 4.dp)) {
        MenuHeading(stringResource(R.string.picture_wrap))
        listOf(
            null to R.string.wrap_inline,
            com.therealityteam.aeropages.model.FloatWrap.SQUARE to R.string.wrap_square,
            com.therealityteam.aeropages.model.FloatWrap.TOP_BOTTOM to R.string.wrap_top_bottom,
            com.therealityteam.aeropages.model.FloatWrap.BEHIND to R.string.wrap_behind,
            com.therealityteam.aeropages.model.FloatWrap.FRONT to R.string.wrap_front,
        ).forEach { (wrap, label) ->
            MenuCommand(stringResource(label), { onWrap(wrap) }, icon = wrapIcon(wrap), checked = current == wrap)
        }
        if (pos != null) {
            MenuDivider()
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.picture_position), style = AeroType.command, color = PicoTheme.colorScheme.labelSecondary,
                    modifier = Modifier.weight(1f))
                listOf(
                    Triple(com.therealityteam.aeropages.model.FloatAlign.START, R.string.align_left, "ic_align_left"),
                    Triple(com.therealityteam.aeropages.model.FloatAlign.CENTER, R.string.center, "ic_align_center"),
                    Triple(com.therealityteam.aeropages.model.FloatAlign.END, R.string.align_right, "ic_align_right"),
                ).forEach { (align, label, icon) ->
                    RibbonMiniButton(icon, stringResource(label), { onAlign(align) }, selected = pos.hAlign == align)
                }
            }
        }
    }
}

/** Word's Layout Options icon for a block wrapped as [wrap] (null: in line with the text). */
internal fun wrapIcon(wrap: com.therealityteam.aeropages.model.FloatWrap?): String = when (wrap) {
    null -> "ic_wrap_inline"
    com.therealityteam.aeropages.model.FloatWrap.SQUARE -> "ic_wrap_square"
    com.therealityteam.aeropages.model.FloatWrap.TOP_BOTTOM -> "ic_wrap_top_bottom"
    com.therealityteam.aeropages.model.FloatWrap.BEHIND -> "ic_wrap_behind"
    com.therealityteam.aeropages.model.FloatWrap.FRONT -> "ic_wrap_front"
}

/** The Layout Options button's icon for a picture or a box placed as [pos]. */
private fun wrapIcon(pos: com.therealityteam.aeropages.model.FloatPosition?): String = wrapIcon(pos?.wrap)

/**
 * A block's position wrapped as [wrap]: in line (null) — or floating, its own position ([current])
 * re-wrapped, or (it was in line) where it stood: centred in its column just below the paragraph before
 * it, so it does not jump.
 */
internal fun wrappedAs(editor: DocumentEditor, block: com.therealityteam.aeropages.model.Block, current: com.therealityteam.aeropages.model.FloatPosition?,
                      wrap: com.therealityteam.aeropages.model.FloatWrap?, flow: CanvasFlow?, pxPerPt: Float): com.therealityteam.aeropages.model.FloatPosition? {
    if (wrap == null) return null
    current?.let { return it.copy(wrap = wrap) }
    val blocks = editor.document.blocks
    val para = (blocks.getOrNull(blocks.indexOfFirst { it === block } - 1) as? com.therealityteam.aeropages.model.ParagraphBlock)?.paragraph
    val below = para?.let { p -> flow?.metricsOf(p)?.heightPx?.let { it / pxPerPt } } ?: 0f
    return com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.COLUMN, com.therealityteam.aeropages.model.FloatAlign.CENTER,
        below + PageMetrics.IMAGE_PAD, com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH, null, wrap)
}

/**
 * A chart in the text, drawn as the slides draw theirs at its size (narrowed to the column in
 * proportion), centred. A tap picks it: a frame shows, with its actions — another kind of chart,
 * delete.
 */
@Composable
private fun ChartBlockView(editor: DocumentEditor, block: com.therealityteam.aeropages.model.ChartBlock) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val selected = editor.selectedChart === block
    // Dev hook (`setprop debug.aero.dialog chart`): the document's first chart comes up picked (`chartdesign`: with Chart Design open).
    LaunchedEffect(block) {
        if (devDialogProp() in setOf("chart", "chartdesign") && editor.selectedChart == null &&
            editor.document.blocks.firstOrNull { it is com.therealityteam.aeropages.model.ChartBlock } === block) editor.selectChart(block)
    }
    val ink = Color(com.therealityteam.aeropages.model.ChartStyle.inkFor(null))
    val frame = PicoTheme.colorScheme.interaction
    val label = stringResource(R.string.chart_label) + (block.chart.title.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = PageMetrics.IMAGE_PAD.dp), contentAlignment = Alignment.TopCenter) {
        val (w, h) = PageMetrics.fitImage(block.widthPt, block.heightPt, maxWidth.value)
        Column(
            Modifier.size(w.dp, h.dp)
                .border(BorderStroke(if (selected) 1.5.dp else 0.dp, if (selected) frame else Color.Transparent), RoundedCornerShape(4.dp))
                .semantics { contentDescription = label }
                .clickable { editor.selectChart(if (selected) null else block) }
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (block.chart.title.isNotBlank()) Text(block.chart.title, fontSize = 14.sp, color = ink, maxLines = 1)
            ChartBody(block.chart, Modifier.fillMaxWidth().weight(1f), ink = ink, surface = Color.White)
        }
        // The actions float just above the chart's corner (over the chart they would hide its title).
        if (selected) ContextBar(
            stringResource(R.string.a11y_chart_actions),
            Modifier.align(Alignment.TopEnd).offset(x = -((maxWidth.value - w) / 2).coerceAtLeast(0f).dp, y = (-40).dp),
        ) {
            listOf(
                Triple(com.therealityteam.aeropages.model.ChartType.COLUMN, R.string.chart_type_column, "ic_chart"),
                Triple(com.therealityteam.aeropages.model.ChartType.BAR, R.string.chart_type_bar, "ic_chart_bar"),
                Triple(com.therealityteam.aeropages.model.ChartType.LINE, R.string.chart_type_line, "ic_chart_line"),
                Triple(com.therealityteam.aeropages.model.ChartType.PIE, R.string.chart_type_pie, "ic_chart_pie"),
            ).forEach { (type, name, icon) ->
                RibbonMiniButton(icon, stringResource(name), { editor.setChartType(block, type) }, selected = block.chart.type == type)
            }
            BarDivider()
            RibbonMiniButton("ic_edit_data", stringResource(R.string.edit_chart_data), { editor.requestChartData() })
            RibbonMiniButton("ic_delete", stringResource(R.string.delete), { editor.deleteChart(block) })
        }
    }
}

/**
 * A displayed equation, typeset as the page flow measured it and placed as its block is aligned.
 * A tap picks it: a frame shows, with its actions — copy it as linear text, delete it.
 */
@Composable
private fun EquationView(editor: DocumentEditor, block: com.therealityteam.aeropages.model.EquationBlock) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val flow = LocalCanvasFlow.current
    val selected = editor.selectedEquation === block
    // Dev hook (`setprop debug.aero.dialog equation`): the document's first displayed equation comes up picked.
    LaunchedEffect(block) {
        if (devDialogProp() == "equation" && editor.selectedEquation == null &&
            editor.document.blocks.firstOrNull { it is com.therealityteam.aeropages.model.EquationBlock } === block) editor.selectEquation(block)
    }
    val clipboard = rememberTextClipboard()
    val label = stringResource(R.string.a11y_equation, com.therealityteam.aeropages.model.MathText.linear(block.math))
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = PageMetrics.EQUATION_PAD.dp)) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val box = flow?.equationBox(block, widthPx) ?: return@BoxWithConstraints
        val font = remember { com.therealityteam.aeropages.format.PaintMathFont() }
        val density = LocalDensity.current
        val align = when (block.align) {
            com.therealityteam.aeropages.model.TextAlign.LEFT -> Alignment.TopStart
            com.therealityteam.aeropages.model.TextAlign.RIGHT -> Alignment.TopEnd
            else -> Alignment.TopCenter
        }
        val frame = PicoTheme.colorScheme.interaction
        Box(
            Modifier
                .align(align)
                .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                .semantics { contentDescription = label }
                .clickable { editor.selectEquation(if (selected) null else block) }
                .drawBehind {
                    drawIntoCanvas { c -> com.therealityteam.aeropages.format.MathPainter.draw(c.nativeCanvas, box, 0f, box.ascent, 0xFF1A1A1A.toInt(), font) }
                    if (selected) drawRect(frame, topLeft = Offset(-4.dp.toPx(), -3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width + 8.dp.toPx(), size.height + 6.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                },
        )
        if (selected) ContextBar(
            stringResource(R.string.a11y_equation_actions),
            Modifier.align(Alignment.TopEnd).offset(y = (-42).dp),
        ) {
            RibbonMiniButton("ic_edit", stringResource(R.string.edit), { editor.requestEquationEdit() })
            RibbonMiniButton("ic_copy", stringResource(R.string.copy_as_text), {
                clipboard.copy(com.therealityteam.aeropages.model.MathText.linear(block.math))
            })
            RibbonMiniButton("ic_delete", stringResource(R.string.delete), { editor.deleteEquation(block) })
        }
    }
}

/** The document's first picture: a block of its own or in a table's cell. */
private fun firstPicture(doc: com.therealityteam.aeropages.model.Document): ImageBlock? = doc.blocks.firstNotNullOfOrNull { b ->
    when (b) {
        is ImageBlock -> b
        is com.therealityteam.aeropages.model.TableBlock -> b.table.rows.firstNotNullOfOrNull { r -> r.cells.firstNotNullOfOrNull { it.images.firstOrNull() } }
        else -> null
    }
}

/** Dev hook (`setprop debug.aero.dialog crop`): the document's first cropped picture (else its first) opens Crop. */
private fun devCropPicture(doc: com.therealityteam.aeropages.model.Document): ImageBlock? {
    if (devDialogProp() != "crop") return null
    val all = doc.blocks.flatMap { b ->
        when (b) {
            is ImageBlock -> listOf(b)
            is TableBlock -> b.table.rows.flatMap { r -> r.cells.flatMap { it.images } }
            else -> emptyList()
        }
    }
    return all.firstOrNull { it.cropped } ?: all.firstOrNull()
}

/**
 * What [TableRowLayout] needs to draw a cell: its span weight, shade, the lines along its sides, where a missing
 * line gets a guide ([guideTop] / [guideBottom]: not across a vertical merge) and its resize handle.
 */
private class CellPaint(
    val weight: Float, val shade: Long?, val sides: com.therealityteam.aeropages.model.CellBorders, val handle: Boolean, val lastCol: Int,
    val guideTop: Boolean = true, val guideBottom: Boolean = true,
)

/**
 * One table row in a single measure pass: every cell is measured once at its column width and
 * the row takes the tallest; shading and grid lines are drawn by the row from those sizes. (A
 * `Row(height = IntrinsicSize.Min)` re-measured every text field in the intrinsic pass, which
 * showed up as a multi-second measure on documents with pictures inside cells.)
 * Cell widths come from [PageMetrics.split] — the same split the page layout measured with — and
 * the resize handles straddle the borders without taking width.
 * [content] emits, in order, each cell and — when [CellPaint.handle] — its resize handle.
 */
@Composable
private fun TableRowLayout(cells: List<CellPaint>, guide: Color, modifier: Modifier = Modifier, minRow: androidx.compose.ui.unit.Dp = PageMetrics.ROW_MIN.dp,
                           exact: Boolean = false, content: @Composable () -> Unit) {
    val handleDp = 6.dp
    val minRowDp = minRow
    val weights = FloatArray(cells.size) { cells[it].weight }
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = modifier.drawBehind {
            val widths = PageMetrics.split(weights, size.width.roundToInt())
            val sw = 0.75.dp.toPx()
            val dashes = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
            fun drawLine(color: Color, start: Offset, end: Offset, width: Float) = drawLine(color, start, end, width, pathEffect = dashes)
            var x = 0f
            // Shades first, then the guides where no line runs, then the lines over them.
            cells.forEachIndexed { i, c ->
                val w = widths[i].toFloat()
                c.shade?.let { drawRect(Color(it), Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height)) }
                if (c.sides.left == null) drawLine(guide, Offset(x, 0f), Offset(x, size.height), sw)
                if (c.sides.right == null) drawLine(guide, Offset(x + w, 0f), Offset(x + w, size.height), sw)
                if (c.sides.top == null && c.guideTop) drawLine(guide, Offset(x, 0f), Offset(x + w, 0f), sw)
                if (c.sides.bottom == null && c.guideBottom) drawLine(guide, Offset(x, size.height), Offset(x + w, size.height), sw)
                x += w
            }
            x = 0f
            cells.forEachIndexed { i, c ->
                val w = widths[i].toFloat()
                c.sides.top?.let { drawCellSide(it, Offset(x, 0f), Offset(x + w, 0f), horizontal = true) }
                c.sides.bottom?.let { drawCellSide(it, Offset(x, size.height), Offset(x + w, size.height), horizontal = true) }
                c.sides.left?.let { drawCellSide(it, Offset(x, 0f), Offset(x, size.height), horizontal = false) }
                c.sides.right?.let { drawCellSide(it, Offset(x + w, 0f), Offset(x + w, size.height), horizontal = false) }
                x += w
            }
        },
    ) { measurables, constraints ->
        val handlePx = handleDp.roundToPx()
        val widths = PageMetrics.split(weights, constraints.maxWidth)
        // Measurables arrive as cell, [handle], cell, [handle]…; cells first (their heights set the row), handles after.
        val cellIndex = IntArray(cells.size)
        var mi = 0
        cells.forEachIndexed { i, c -> cellIndex[i] = mi++; if (c.handle) mi++ }
        var rowH = minRowDp.roundToPx()
        val cellPlaceables = cells.mapIndexed { i, _ ->
            measurables[cellIndex[i]].measure(Constraints(minWidth = widths[i], maxWidth = widths[i], minHeight = 0, maxHeight = Constraints.Infinity))
                .also { rowH = maxOf(rowH, it.height) }
        }
        if (exact) rowH = minRowDp.roundToPx()
        val handlePlaceables = cells.mapIndexed { i, c -> if (c.handle) measurables[cellIndex[i] + 1].measure(Constraints.fixed(handlePx, rowH)) else null }
        // A row is never split: the page layout moves a row that does not fit to the next page.
        layout(constraints.maxWidth, rowH.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var x = 0
            cells.forEachIndexed { i, _ ->
                cellPlaceables[i].placeRelative(x, 0)
                x += widths[i]
                handlePlaceables[i]?.placeRelative(x - handlePx / 2, 0)
            }
        }
    }
}

/**
 * A table cell's side from [from] to [to]: its width (a point a dp; a device pixel at least), colour (automatic:
 * black) and kind — dotted, dashed, dash-dot as dashes scaled to its width, a double line as two [horizontal]ly
 * or vertically apart.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCellSide(line: com.therealityteam.aeropages.model.CellBorder, from: Offset, to: Offset, horizontal: Boolean) {
    val w = line.widthPt.dp.toPx().coerceAtLeast(1f)
    val color = Color(line.color ?: 0xFF1A1A1A)
    if (line.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) {
        val d = if (horizontal) Offset(0f, w) else Offset(w, 0f)
        drawLine(color, from - d, to - d, w)
        drawLine(color, from + d, to + d, w)
        return
    }
    val effect = line.style.dash?.let { d -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * w * 1.5f }) }
    drawLine(color, from, to, w, pathEffect = effect)
}

/** A text box: its paragraphs in a framed, lightly tinted box (as wide as its frame, or the column). */
@Composable
private fun TextBoxView(editor: DocumentEditor, box: com.therealityteam.aeropages.model.TextBoxBlock, startFlatIndex: Int, focusRequester: FocusRequester, onSave: () -> Unit, onFind: () -> Unit,
                        move: MoveDrag? = null) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    Box {
        TextBoxBody(editor, box, startFlatIndex, focusRequester, onSave, onFind)
        // Worked on (the caret in it, or a shape without text picked): its bar above its top right corner.
        if (editor.activeBox === box) BoxBar(editor, box, move, Modifier.align(Alignment.TopEnd))
    }
}

/** Dragging a floating object by its handle: how far it has gone ([by]), let go ([end]) or put back ([cancel]). */
private class MoveDrag(val by: (Offset) -> Unit, val end: () -> Unit, val cancel: () -> Unit)

/**
 * A text box's or shape's bar, over its top right corner: its wrapping options, a handle to drag it by (one
 * floating over the page), delete — the options hanging down over it.
 */
@Composable
private fun BoxBar(editor: DocumentEditor, box: com.therealityteam.aeropages.model.TextBoxBlock, move: MoveDrag?, modifier: Modifier) {
    val flow = LocalCanvasFlow.current
    // The drag keeps going through the recompositions it causes: it reads the latest callbacks.
    val current = androidx.compose.runtime.rememberUpdatedState(move)
    val pxPerPt = androidx.compose.ui.platform.LocalDensity.current.density
    val open = editor.imageOptionsOpen
    // The bar: anchored at the corner, standing above it.
    ContextBar(
        stringResource(R.string.a11y_box_actions),
        modifier.layout { m, _ -> val p = m.measure(Constraints()); layout(0, 0) { p.place(-p.width, -p.height) } }
            .padding(bottom = 4.dp),
    ) {
        RibbonMiniButton(wrapIcon(box.floating), stringResource(R.string.picture_wrap), { editor.showImageOptions(!open) }, selected = open)
        if (move != null) {
            // A handle to drag the box by: the command's look, a drag instead of a click.
            val moveLabel = stringResource(R.string.move_object)
            Box(
                Modifier.semantics { contentDescription = moveLabel }
                    .size(32.dp, Aero.commandHeight).clip(RoundedCornerShape(Aero.commandRadius))
                    .pointerInput(box) {
                        detectDragGestures(onDragEnd = { current.value?.end?.invoke() }, onDragCancel = { current.value?.cancel?.invoke() }) { change, delta ->
                            change.consume(); current.value?.by?.invoke(delta)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                GazeFill(RoundedCornerShape(Aero.commandRadius))
                RibbonGlyph("ic_move", PicoTheme.colorScheme.labelPrimary, 20.dp)
            }
        }
        BarDivider()
        RibbonMiniButton("ic_delete", stringResource(R.string.delete), { editor.deleteBox(box) })
    }
    if (open) Box(modifier.layout { m, _ -> val p = m.measure(Constraints()); layout(0, 0) { p.place(-p.width, 0) } }) {
        WrapOptions(box.floating, { w -> editor.setBoxFloat(box, wrappedAs(editor, box, box.floating, w, flow, pxPerPt)) },
            { a -> box.floating?.let { editor.setBoxFloat(box, it.copy(hAlign = a)) } })
    }
}

/** A text box's or shape's frame and text (see [TextBoxView]); a shape holding no text is picked by a tap. */
@Composable
private fun TextBoxBody(editor: DocumentEditor, box: com.therealityteam.aeropages.model.TextBoxBlock, startFlatIndex: Int, focusRequester: FocusRequester, onSave: () -> Unit, onFind: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val commands = editor.commandRevision
    val label = stringResource(R.string.text_box)
    val ordinals = PageMetrics.listLabels(box.paragraphs.asSequence(), editor.document.lists)
    val shape = box.shape
    if (shape != null) {
        // A drawn shape: drawn as slides and the PDF draw it, its text inside it, turned with it; it
        // is at least its own height and grows with its text, as Word's resize-to-fit shapes do.
        val margin = PageMetrics.BOX_MARGIN.dp
        val pxPerPt = androidx.compose.ui.platform.LocalDensity.current.density
        Box(
            modifier = Modifier
                .then(box.widthPt?.let { Modifier.wrapContentWidth(Alignment.Start).width(it.dp) } ?: Modifier.fillMaxWidth())
                .heightIn(min = (box.heightPt ?: 0f).dp + margin * 2)
                .graphicsLayer(rotationZ = shape.rotationDeg)
                .semantics { contentDescription = label },
            contentAlignment = when (shape.anchor) {
                com.therealityteam.aeropages.model.ShapeAnchor.TOP -> Alignment.TopStart
                com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> Alignment.BottomStart
                com.therealityteam.aeropages.model.ShapeAnchor.CENTER -> Alignment.CenterStart
            },
        ) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize().then(
                if (box.paragraphs.isEmpty()) Modifier.clickable { editor.selectBox(if (editor.selectedBox === box) null else box) } else Modifier)) {
                val m = margin.toPx()
                drawIntoCanvas { c -> com.therealityteam.aeropages.format.pdf.ShapePainter.shape(c.nativeCanvas, shape, 0f, m, size.width, size.height - 2 * m, pxPerPt) }
            }
            if (box.paragraphs.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = margin).padding(PageMetrics.BOX_INSET.dp)) {
                box.paragraphs.forEachIndexed { i, para ->
                    val index = startFlatIndex + i
                    // A box that does not wrap its text: each line as long as it is, placed as its paragraph aligns it.
                    Box(Modifier.fillMaxWidth().then(if (shape.noWrap) Modifier.wrapContentWidth(when (para.format.align) {
                        com.therealityteam.aeropages.model.TextAlign.CENTER -> Alignment.CenterHorizontally
                        com.therealityteam.aeropages.model.TextAlign.RIGHT -> Alignment.End
                        else -> Alignment.Start
                    }, unbounded = true) else Modifier)) {
                        ParagraphRow(editor, para, index, index == editor.active, ordinals[para], focusRequester, onSave, onFind)
                    }
                }
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .padding(vertical = PageMetrics.BOX_MARGIN.dp)
            .then(box.widthPt?.let { Modifier.wrapContentWidth(Alignment.Start).width(it.dp) } ?: Modifier.fillMaxWidth())
            .semantics { contentDescription = label }
            .border(BorderStroke(1.dp, PicoTheme.colorScheme.dividerLine), RoundedCornerShape(4.dp))
            .background(Color(0xFFFAFAFA), RoundedCornerShape(4.dp))
            .padding(PageMetrics.BOX_INSET.dp),
    ) {
        box.paragraphs.forEachIndexed { i, para ->
            val index = startFlatIndex + i
            ParagraphRow(editor, para, index, index == editor.active, ordinals[para], focusRequester, onSave, onFind)
        }
    }
}

/** Renders a table as a grid; each cell hosts its paragraphs as editable rows. */
@Composable
private fun TableView(
    editor: DocumentEditor,
    table: Table,
    startFlatIndex: Int,
    focusRequester: FocusRequester,
    onSave: () -> Unit,
    onFind: () -> Unit,
    slice: com.therealityteam.aeropages.editor.TableSlice? = null,
) {
    // Drawn again after a command (its cells' text may have changed where it stands).
    @Suppress("UNUSED_VARIABLE") val commands = editor.commandRevision
    // A table's borders as they print, each side its own line; where a cell has none, Word's gridlines to edit by
    // (faint blue dashes, so the lines that print stand out).
    val guide = Color(0xFF8FB3E0).copy(alpha = 0.55f)
    val rowRange = slice?.rows ?: table.rows.indices
    // Flat paragraph index of a row's first paragraph.
    fun flatStart(ri: Int): Int = startFlatIndex + table.rows.take(ri).sumOf { r -> r.cells.sumOf { it.content.size } }
    // Rows drawn on this page: the repeated header rows (when the slice continues the table), then the slice.
    val drawn = buildList { if (slice?.repeatHeader == true) addAll(0 until table.headerRowCount()); addAll(rowRange) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = PageMetrics.TABLE_PAD.dp)) {
        drawn.forEach { ri ->
            val row = table.rows[ri]
            var idx = flatStart(ri)
            val weights = PageMetrics.cellWeights(table, row)
            // A vertical merge's cells draw as one: no lines across it (see Table.drawnSides).
            var c0 = 0
            val paints = row.cells.mapIndexed { ci, cell ->
                val span = cell.gridSpan.coerceAtLeast(1)
                // The merge goes on below this cell (the next row's cell at its column is covered).
                val goesOn = table.cellAtGrid(ri + 1, c0)?.let { table.rows[ri + 1].cells[it].vMerged } == true
                CellPaint(weights[ci], cell.shade, table.drawnSides(ri, ci), handle = ci < row.cells.lastIndex, lastCol = c0 + span - 1,
                    guideTop = !cell.vMerged, guideBottom = !goesOn).also { c0 += span }
            }
            TableRowLayout(paints, guide, Modifier.fillMaxWidth(), minRow = PageMetrics.rowMin(row).dp, exact = row.exactHeight && row.heightPt != null) {
                row.cells.forEachIndexed { ci, cell ->
                    Column(modifier = Modifier.padding(horizontal = PageMetrics.CELL_PAD_H.dp, vertical = PageMetrics.CELL_PAD_V.dp)) {
                        if (cell.vMerged) {
                            // Covered by a vertical merge: keep flat-index accounting, render nothing.
                            idx += cell.content.size
                        } else {
                            val ordinals = LocalCanvasFlow.current?.labels ?: PageMetrics.listLabels(cell.content.asSequence(), editor.document.lists)
                            cell.content.forEach { para ->
                                val i = idx++
                                ParagraphRow(editor, para, i, i == editor.active, ordinals[para], focusRequester, onSave, onFind)
                            }
                            cell.images.forEach { img -> ImageView(editor, img) }
                        }
                    }
                    // Drag handle to resize this cell's last column (skip after final cell): unseen on the
                    // page — the cell's own border marks the place — lit when the eyes or the pointer rest on it.
                    if (paints[ci].handle) {
                        val lastCol = paints[ci].lastCol
                        Box(
                            modifier = Modifier
                                .spatialHoverEffect(style = com.pico.spatial.ui.graphics.SpatialHoverStyle.Highlight)
                                .pointerInput(table, lastCol) {
                                    detectHorizontalDragGestures { change, dragAmount ->
                                        change.consume()
                                        editor.resizeColumn(table, lastCol, dragAmount / 160f)
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One paragraph: its list marker (in the paragraph's own size), then its text — the edit field
 * when it is the active paragraph (and, for a paragraph split across pages, this part holds the
 * caret), rich read-only text otherwise. Spacing and indent are the document's own, in points.
 */
@Composable
private fun ParagraphRow(
    editor: DocumentEditor,
    para: Paragraph,
    index: Int,
    isActive: Boolean,
    label: String?,
    focusRequester: FocusRequester,
    onSave: () -> Unit,
    onFind: () -> Unit,
    hostsEditor: Boolean = true,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    // Drawn again after a command: it may have changed this paragraph where it stands (the same object as before).
    @Suppress("UNUSED_VARIABLE") val commands = editor.commandRevision
    val f = para.format
    val layoutRef = remember { arrayOfNulls<TextLayoutResult>(1) }
    // Where this paragraph's text shows, for pointer selection and the selection handles.
    val hits = LocalTextHits.current
    val hit = remember(index) { TextHits.Entry(index) }
    androidx.compose.runtime.DisposableEffect(hit) { hits.add(hit); onDispose { hits.remove(hit) } }
    // The caret's paragraph answers a reveal request (navigation, Find, arrows across paragraphs,
    // undo) by bringing the caret's line into view — once per request.
    val bring = remember { BringIntoViewRequester() }
    val reveal = LocalReveal.current
    if (isActive && hostsEditor) LaunchedEffect(editor.revealRequest) {
        if (editor.revealRequest <= reveal.handled) return@LaunchedEffect
        reveal.handled = editor.revealRequest
        androidx.compose.runtime.withFrameNanos { }
        val caret = layoutRef[0]?.let { it.getCursorRect(editor.selEnd.coerceIn(0, it.layoutInput.text.length)) }
        runCatching { bring.bringIntoView(caret?.let { androidx.compose.ui.geometry.Rect(it.left, it.top - 32f, it.right + 1f, it.bottom + 32f) }) }
    }
    val inset = PageMetrics.indents(f)
    // Simple Markup: a bar in the margin beside a paragraph with changes (Word's red line), its text shown with them taken.
    val changeBar = editor.markup == DocumentEditor.Markup.SIMPLE && para.runs.any { it.format.inserted || it.format.deleted || it.format.formatChanged }
    // Comments on the paragraph: a mark in the right margin, which opens them.
    val comments = editor.document.comments.filter { it.parentId == null && it.targetId == para.id && editor.commentShown(it) }
    // Word's paragraph borders and shading: the box it shares with like neighbours (see ParagraphBoxes).
    val flow = LocalCanvasFlow.current
    val edge = flow?.edgeOf(para)
    val before = ParagraphBoxes.beforePt(f, edge)
    Box(Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (changeBar) Modifier.drawBehind {
                val x = -12.dp.toPx()
                drawLine(Color(0xFFCC0000), Offset(x, f.spaceBeforePt.dp.toPx()), Offset(x, size.height - f.spaceAfterPt.dp.toPx()), strokeWidth = 2.dp.toPx())
            } else Modifier)
            .paragraphBox(f, edge)
            .lineNumbers(flow, para, before) { layoutRef[0] }
            .padding(start = inset[0].dp, end = inset[3].dp, top = before.dp, bottom = ParagraphBoxes.afterPt(f, edge).dp),
        verticalAlignment = Alignment.Top,
    ) {
        PageMetrics.markerOf(para, label)?.let { marker ->
            BasicText(marker, style = TextStyling.editBaseStyle(para, LocalCanvasFlow.current?.gridOf(para)), maxLines = 1, softWrap = false, modifier = Modifier.width(PageMetrics.markerWidth(f, marker, PageMetrics.maxSizePt(para)).dp))
        }
        // An empty paragraph is exactly one line tall, as the page layout measured it.
        val emptyHeight = if (para.isEmpty()) LocalCanvasFlow.current?.metricsOf(para)?.bottoms?.lastOrNull()?.toInt() else null
        val exact = if (emptyHeight != null) Modifier.layout { m, c ->
            val p = m.measure(c)
            layout(p.width, emptyHeight) { p.place(0, 0) }
        } else Modifier
        val arm = LocalEditArm.current
        val laidOut: (TextLayoutResult) -> Unit = { layoutRef[0] = it; hit.layout = it; hits.touch(); onLayout?.invoke(it) }
        val capMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val dropCap = Modifier.dropCap(flow, para, capMeasurer) { layoutRef[0] }
        // While a selection is dragged with the pointer every paragraph stays plain text: the field
        // (and the keyboard with it) comes back where the selection ends.
        val editing = isActive && arm.armed && !arm.selecting && hostsEditor
        hit.field = editing
        if (editing) {
            ActiveParagraphEditor(editor, para, index, focusRequester, onSave, onFind,
                Modifier.fillMaxWidth().then(exact).then(dropCap).bringIntoViewRequester(bring).onGloballyPositioned { hit.coords = it; hits.touch() }, laidOut)
        } else {
            // An empty paragraph has no text to announce: name it by its number.
            val emptyLabel = stringResource(R.string.a11y_paragraph, index + 1)
            // Tapping puts the caret where the text was tapped; Shift+tap stretches the selection there.
            val downAt = remember { arrayOfNulls<Offset>(1) }
            val shiftAt = remember { booleanArrayOf(false) }
            val ctrlAt = remember { booleanArrayOf(false) }
            val openLink = LocalOpenLink.current
            // The part of a selection across paragraphs that falls in this one, painted as a text
            // field paints its own; a paragraph the selection runs past also shows its mark selected.
            val span = editor.span()
            val part = editor.selectionPartOf(index, com.therealityteam.aeropages.editor.ParagraphOps.length(para))
            val markSelected = span != null && index >= span.first.index && index < span.second.index
            val highlight = LocalTextSelectionColors.current.backgroundColor
            BasicText(
                text = TextStyling.wrappedText(TextStyling.annotatedOf(para, LocalTabSpec.current), para, LocalCanvasFlow.current),
                modifier = Modifier.fillMaxWidth().then(exact).then(dropCap).bringIntoViewRequester(bring)
                    .tabLeaders(para, LocalTabSpec.current) { layoutRef[0] }
                    .onGloballyPositioned { hit.coords = it; hits.touch() }
                    .then(if (part != null) Modifier.drawBehind {
                        val layout = layoutRef[0] ?: return@drawBehind
                        val len = layout.layoutInput.text.length
                        val a = part.first.coerceIn(0, len)
                        val b = (part.last + 1).coerceIn(0, len)
                        if (b > a) drawPath(layout.getPathForRange(a, b), highlight)
                        if (markSelected) {
                            val end = layout.getCursorRect(len)
                            drawRect(highlight, topLeft = Offset(end.left, end.top), size = androidx.compose.ui.geometry.Size(6.dp.toPx(), end.height))
                        }
                    } else Modifier)
                    .then(if (para.isEmpty()) Modifier.semantics { contentDescription = emptyLabel } else Modifier)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            downAt[0] = awaitFirstDown(requireUnconsumed = false).position
                            shiftAt[0] = currentEvent.keyboardModifiers.isPointerShiftPressed
                            ctrlAt[0] = currentEvent.keyboardModifiers.let { it.isPointerCtrlPressed || it.isPointerMetaPressed }
                        }
                    }
                    .clickable {
                        arm.armed = true
                        // Back to the text: a picked picture or shape is let go.
                        if (editor.selectedImage != null) editor.selectImage(null)
                        if (editor.selectedBox != null) editor.selectBox(null)
                        val offset = downAt[0]?.let { pos -> layoutRef[0]?.getOffsetForPosition(pos) }?.takeIf { !para.isEmpty() } ?: 0
                        // Ctrl+click on a link follows it (Word's way); the text stays as it is.
                        val link = if (ctrlAt[0]) linkAt(para, offset) else null
                        if (link != null) { openLink(link); downAt[0] = null; ctrlAt[0] = false; return@clickable }
                        if (shiftAt[0]) editor.extendTo(index, offset)
                        else {
                            editor.focusParagraph(index)
                            editor.setSelection(index, offset, offset)
                        }
                        downAt[0] = null
                        shiftAt[0] = false
                    },
                style = TextStyling.editBaseStyle(para, LocalCanvasFlow.current?.gridOf(para)),
                onTextLayout = laidOut,
            )
        }
    }
    if (comments.isNotEmpty()) CommentMark(comments.size, editor.currentComment in comments, Modifier.align(Alignment.TopEnd).offset(x = 34.dp, y = f.spaceBeforePt.dp)) {
        editor.currentComment = comments.first()
        editor.requestCommentsPane()
    }
    }
}

/** A paragraph's comments in the right margin: a small speech bubble with how many, picked when the reader is on one. */
@Composable
private fun CommentMark(count: Int, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(R.string.comment)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(22.dp, 18.dp).clip(RoundedCornerShape(6.dp))
            .background(if (current) Color(0xFFF6B800) else Color(0xFFFFE08A))
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
    ) {
        if (count > 1) Text(count.toString(), fontSize = 10.sp, color = Color(0xFF5A4500)) else RibbonGlyph("ic_comment", Color(0xFF5A4500), 12.dp)
    }
}

/** The link of [para]'s character at [offset] (the one before it at the end), or null. */
private fun linkAt(para: Paragraph, offset: Int): String? {
    var pos = 0
    var last: String? = null
    for (r in para.runs) {
        if (offset < pos + r.text.length) return r.format.link
        pos += r.text.length
        last = r.format.link
    }
    return last.takeIf { offset == pos }
}

/**
 * What an edit of [old] into [new] did — the replaced range and the inserted text — read from the
 * field's selection [a, b) when that explains the change (so the caret lands right when the same
 * letter repeats), else from the common prefix and suffix.
 */
private fun editOf(old: String, new: String, a: Int, b: Int): Triple<Int, Int, String> {
    val tail = old.length - b
    if (a in 0..b && b <= old.length && new.length >= a + tail &&
        new.regionMatches(0, old, 0, a) && new.regionMatches(new.length - tail, old, b, tail)
    ) return Triple(a, b, new.substring(a, new.length - tail))
    var p = 0
    val minLen = minOf(old.length, new.length)
    while (p < minLen && old[p] == new[p]) p++
    var s = 0
    while (s < minLen - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    return Triple(p, old.length - s, new.substring(p, new.length - s))
}

@Composable
private fun ActiveParagraphEditor(
    editor: DocumentEditor,
    para: Paragraph,
    index: Int,
    focusRequester: FocusRequester,
    onSave: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    var value by remember(index) {
        val t = para.plainText()
        val caret = editor.selStart.coerceIn(0, t.length)
        mutableStateOf(TextFieldValue(t, TextRange(caret, editor.selEnd.coerceIn(0, t.length))))
    }
    val layoutRef = remember { arrayOfNulls<TextLayoutResult>(1) }
    val clipboard = rememberTextClipboard()
    val flow = LocalCanvasFlow.current
    val arm = LocalEditArm.current

    LaunchedEffect(index) { runCatching { focusRequester.requestFocus() } }

    // Resync from the model after commands (undo/redo/replace-all) and after caret moves made
    // outside the field (arrows into this paragraph, Shift+click, Select All).
    LaunchedEffect(editor.revision) {
        val modelText = para.plainText()
        val sel = TextRange(editor.selStart.coerceIn(0, modelText.length), editor.selEnd.coerceIn(0, modelText.length))
        if (modelText != value.text) value = TextFieldValue(modelText, sel)
        else if (sel != value.selection) value = value.copy(selection = sel)
    }

    /** Up / Down out of the first / last line: the caret keeps its column on the neighbour's nearest line. */
    fun moveVertically(dir: Int, extend: Boolean) {
        val layout = layoutRef[0] ?: return
        val caret = value.selection.end.coerceIn(0, value.text.length)
        val column = caret - layout.getLineStart(layout.getLineForOffset(caret))
        val target = index + dir
        val next = editor.document.locations().getOrNull(target)?.paragraph ?: return
        val ends = flow?.metricsOf(next)?.lineEnds
        val offset = if (dir < 0) {
            val lineStart = if (ends != null && ends.size >= 2) ends[ends.size - 2] else 0
            lineStart + column
        } else minOf(column, ends?.firstOrNull() ?: Int.MAX_VALUE)
        editor.moveCaret(target, offset, extend)
    }

    BasicTextField(
        value = value,
        onValueChange = onChange@{ newValue ->
            // A field whose paragraph is no longer the caret's must not edit: after Enter or pasted
            // lines the caret has moved on, yet the old field can echo its last value once more —
            // which would paste the lines a second time into the paragraph it used to show.
            if (index != editor.active) return@onChange
            // A pointer selection (held word, drag, handle) swaps this field out; as it loses focus it
            // reports a caret at its end, which must not collapse the selection being dragged.
            if (arm.selecting && newValue.text == value.text) return@onChange
            val old = value.text
            val new = newValue.text
            val sa = minOf(value.selection.start, value.selection.end)
            val sb = maxOf(value.selection.start, value.selection.end)
            when {
                // Typing (or pasting, or Enter) over a selection that runs across paragraphs replaces all of it.
                new != old && editor.hasSpan -> editor.typeOver(editOf(old, new, sa, sb).third)
                // Enter splits the paragraph (a heading's new paragraph turns Normal); pasted lines become paragraphs.
                new.contains('\n') -> {
                    val (a, b, inserted) = editOf(old, new, sa, sb)
                    if (inserted == "\n") {
                        editor.replaceText(index, old, old.substring(0, a) + old.substring(b))
                        // Word's AutoCorrect: the word Enter ends is corrected (linked, capitalised) before the split.
                        val at = if (a == b) editor.autoCorrectAtEnter(index, a) else a
                        editor.splitParagraph(index, at)
                    } else {
                        editor.setSelection(index, a, b)
                        editor.typeOver(inserted)
                    }
                }
                new != old -> {
                    if (editor.trackChanges) {
                        // Deletions keep the struck text, so the model text differs from the
                        // field's text. Update the field in ONE step to the model's text +
                        // caret, so the line doesn't shrink-then-grow back (flicker/jump).
                        editor.replaceText(index, old, new)
                        val t = para.plainText()
                        val caret = editor.selStart.coerceIn(0, t.length)
                        value = TextFieldValue(t, TextRange(caret))
                    } else {
                        value = newValue
                        editor.replaceText(index, old, new)
                        editor.setSelection(index, newValue.selection.start, newValue.selection.end)
                        // Word's AutoCorrect after one character typed at the caret (not while an input method composes):
                        // the field shows the corrected text at once.
                        val (a, b, inserted) = editOf(old, new, sa, sb)
                        if (a == b && inserted.length == 1 && newValue.composition == null && newValue.selection.collapsed &&
                            newValue.selection.start == a + 1 && editor.autoCorrectTyped(index)
                        ) value = TextFieldValue(para.plainText(), TextRange(editor.selStart))
                    }
                }
                else -> {
                    // Selection only. Shift-extending a selection that runs across paragraphs moves
                    // its moving end (the field keeps the clipped anchor); anything else starts anew.
                    value = newValue
                    if (editor.hasSpan && newValue.selection.start == editor.selStart) editor.extendTo(index, newValue.selection.end, reveal = false)
                    else editor.setSelection(index, newValue.selection.start, newValue.selection.end)
                }
            }
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                val shift = event.isShiftPressed
                val caret = value.selection.end
                val layout = layoutRef[0]
                val span = editor.span()
                val arrow = event.key == Key.DirectionUp || event.key == Key.DirectionDown || event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                when {
                    ctrl && event.key == Key.A -> { editor.selectAll(); true }
                    ctrl && event.key == Key.MoveHome -> { editor.moveCaret(0, 0, extend = shift); true }
                    ctrl && event.key == Key.MoveEnd -> { editor.moveCaret(editor.paragraphCount() - 1, Int.MAX_VALUE, extend = shift); true }
                    // Copy / cut a selection across paragraphs (the field alone only knows its own part).
                    // Copy / cut selected text ourselves (the field alone only knows its own part, and only plain text):
                    // the formatting goes with it for a paste in Pages.
                    ctrl && (event.key == Key.C || event.key == Key.X) && (span != null || !value.selection.collapsed) -> {
                        copyText(editor, clipboard)
                        if (event.key == Key.X) editor.deleteSelection()
                        true
                    }
                    // A picked chart or picture with no text selected: the object; an object copied last: pasted as one.
                    ctrl && (event.key == Key.C || event.key == Key.X) && value.selection.collapsed && (editor.selectedChart != null || editor.selectedImage != null) -> {
                        val done = if (event.key == Key.X) editor.cutSelectedObject() else editor.copySelectedObject()
                        if (done != null) clipboard.copyCurrentObject()
                        true
                    }
                    ctrl && event.key == Key.V && (clipboard.objectCopiedLast || com.therealityteam.aeropages.editor.DocClipboard.clip != null) -> {
                        clipboard.pasteObjectOrText(onObject = { editor.pasteItem(it) }, onText = { t ->
                            com.therealityteam.aeropages.editor.DocClipboard.matching(t)?.let { editor.pasteClip(it) } ?: editor.insertAtCaret(t)
                        })
                        true
                    }
                    (event.key == Key.Backspace || event.key == Key.Delete) && span != null -> { editor.deleteSelection(); true }
                    // Delete with a picture picked and no text selected: the picture goes.
                    event.key == Key.Delete && value.selection.collapsed && editor.selectedImage != null -> { editor.selectedImage?.let { editor.deleteImage(it) }; true }
                    // A plain arrow collapses a selection across paragraphs to its start or end, as in Word.
                    arrow && !shift && span != null -> {
                        val to = if (event.key == Key.DirectionUp || event.key == Key.DirectionLeft) span.first else span.second
                        editor.moveCaret(to.index, to.offset); true
                    }
                    // Arrows leave the paragraph at its edges; with Shift the selection follows into the next one.
                    event.key == Key.DirectionUp && layout != null && index > 0 && layout.getLineForOffset(caret.coerceIn(0, value.text.length)) == 0 -> {
                        moveVertically(-1, shift); true
                    }
                    event.key == Key.DirectionDown && layout != null && layout.getLineForOffset(caret.coerceIn(0, value.text.length)) == layout.lineCount - 1 &&
                        index < editor.paragraphCount() - 1 -> { moveVertically(+1, shift); true }
                    event.key == Key.DirectionLeft && caret == 0 && index > 0 && (shift || value.selection.collapsed) -> {
                        editor.moveCaret(index - 1, Int.MAX_VALUE, extend = shift); true
                    }
                    event.key == Key.DirectionRight && caret == value.text.length && (shift || value.selection.collapsed) &&
                        index < editor.paragraphCount() - 1 -> { editor.moveCaret(index + 1, 0, extend = shift); true }
                    ctrl && event.key == Key.B -> { editor.toggleBold(); true }
                    ctrl && event.key == Key.I -> { editor.toggleItalic(); true }
                    ctrl && event.key == Key.U -> { editor.toggleUnderline(); true }
                    ctrl && event.key == Key.Z -> { editor.undo(); true }
                    ctrl && event.key == Key.Y -> { editor.redo(); true }
                    ctrl && event.key == Key.S -> { onSave(); true }
                    ctrl && event.key == Key.F -> { onFind(); true }
                    // Replace (Ctrl+H) and Go To (Ctrl+G), Word's shortcuts for the find panel's other tabs.
                    ctrl && event.key == Key.H -> { editor.requestFindPanel(1); true }
                    ctrl && event.key == Key.G -> { editor.requestFindPanel(2); true }
                    // F9: Update Field, as in Word (captions renumbered, cross-references worked out again).
                    event.key == Key.F9 -> { editor.updateFields(); true }
                    event.key == Key.Backspace && value.selection.collapsed && value.selection.start == 0 && index > 0 -> {
                        editor.mergeWithPrevious(index); true
                    }
                    // Tab as in Word: a list item at its start moves a level in (Shift: out); anywhere else a tab.
                    event.key == Key.Tab && !ctrl && para.format.listKind != com.therealityteam.aeropages.model.ListKind.NONE && value.selection.collapsed && caret == 0 -> {
                        if (shift) editor.outdent() else editor.indent(); true
                    }
                    event.key == Key.Tab && !ctrl && !shift -> { editor.insertAtCaret("\t"); true }
                    // Word's other keys: alignment, font size, line spacing, headings, page break, link, Font dialog…
                    else -> wordKey(editor, event)
                }
            },
        textStyle = TextStyling.editBaseStyle(para, LocalCanvasFlow.current?.gridOf(para)),
        visualTransformation = TextStyling.fieldTransformation(para, LocalTabSpec.current, LocalCanvasFlow.current?.shapeOf(para)),
        cursorBrush = SolidColor(PicoTheme.colorScheme.interaction),
        onTextLayout = { layoutRef[0] = it; onLayout?.invoke(it) },
    )
}

/** Copies the selected text — to the system clipboard as plain text, and with its formatting for a paste in Pages. */
internal fun copyText(editor: com.therealityteam.aeropages.editor.DocumentEditor, clipboard: TextClipboard) {
    val clip = editor.copiedClip() ?: return
    com.therealityteam.aeropages.editor.DocClipboard.clip = clip
    clipboard.copy(clip.text)
}
