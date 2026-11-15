package com.therealityteam.aeropages.ui

import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.model.Workbook
@Composable
internal fun SheetGrid(editor: SheetEditor, modifier: Modifier, gridlines: Boolean = true, onFind: () -> Unit = {}, onStartEdit: (String?) -> Unit = {}, focusRequest: Int = 0,
    onFilterMenu: (Int) -> Unit = {}, listRequest: Int = 0, onTableRefused: () -> Unit = {}, onOpenLink: (String) -> Unit = {},
    menu: GridMenuActions = GridMenuActions(), quick: QuickActions = QuickActions()) {
    val clipboard = rememberTextClipboard()
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val sheet = editor.sheet
    val h = rememberScrollState()
    val v = rememberScrollState()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The active cell's in-cell list (a list rule's drop-down, Alt+↓) with the value the keys are on,
    // and where the active cell lies in the grid — the list and the input message sit under it.
    var listOpen by remember { mutableStateOf(false) }
    var listIndex by remember { mutableIntStateOf(-1) }
    var activeRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val places = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(2) }  // the grid, the active cell
    fun placeActive() {
        val grid = places[0]; val cell = places[1]
        activeRect = if (grid != null && cell != null && grid.isAttached && cell.isAttached) grid.localBoundingBoxOf(cell, clipBounds = true) else null
    }
    fun openList() {
        val choices = editor.pickChoices() ?: return
        val current = editor.display(editor.selRow, editor.selCol)
        listIndex = choices.indexOfFirst { it.equals(current, ignoreCase = true) }
        listOpen = true
    }
    LaunchedEffect(editor.selRow, editor.selCol, editor.sheetIndex) { listOpen = false }
    LaunchedEffect(listRequest) { if (listRequest > 0) openList() }
    // Bumped when the keyboard should come back to the grid (after the Name Box, say).
    LaunchedEffect(focusRequest) { if (focusRequest > 0) runCatching { focus.requestFocus() } }
    val fr = sheet.freezeRows.coerceIn(0, sheet.rowCount)
    val fc = sheet.freezeCols.coerceIn(0, sheet.colCount)
    val hidden = editor.invisibleRows.let { if (it.isEmpty()) emptySet() else it.toSet() }
    // Columns hidden by hand or folded in a collapsed group are skipped everywhere.
    val hiddenCols = editor.invisibleCols
    // Outline gutter (row groups): one lane per level, left of the row numbers.
    val outlineLevels = editor.maxOutlineLevel
    // Column outline band (column groups): one lane per level, above the column letters.
    val colLevels = editor.maxColOutlineLevel

    fun colW(c: Int) = CELL_W * sheet.colWidthFactor(c)
    fun rowH(r: Int) = CELL_H * sheet.rowHeightFactor(r)

    // ---- Virtualised windows: only the rows/cols near the viewport are composed; ----
    // ---- Spacers stand in for the rest so the shared scroll geometry is unchanged. ----
    val density = LocalDensity.current
    // How wide a cell's text really is (bold capitals are far wider than an average letter): whether it runs on over its neighbours.
    val spillMeasurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 64)
    fun textWidthDp(r: Int, c: Int): Float = with(density) {
        spillMeasurer.measure(editor.display(r, c), cellTextStyle(sheet.cell(r, c)?.format), maxLines = 1, softWrap = false).size.width.toDp().value
    }
    var bodyHpx by remember { mutableIntStateOf(0) }
    var bodyWpx by remember { mutableIntStateOf(0) }
    val overscanPx = with(density) { 160.dp.toPx() }

    class Window(val first: Int, val last: Int, val leadPad: Float, val tailPad: Float)
    fun rowWindow(): Window {
        val top = v.value - overscanPx
        val bottom = v.value + bodyHpx + overscanPx
        var y = 0f; var first = -1; var last = fr - 1; var lead = 0f; var tail = 0f
        for (r in fr until sheet.rowCount) {
            if (r in hidden) continue
            val px = with(density) { rowH(r).toPx() }
            when {
                y + px < top -> lead += px
                y > bottom -> tail += px
                else -> { if (first < 0) first = r; last = r }
            }
            y += px
        }
        return Window(if (first < 0) fr else first, last, lead, tail)
    }
    fun colWindow(): Window {
        val left = h.value - overscanPx
        val right = h.value + bodyWpx + overscanPx
        var x = 0f; var first = -1; var last = fc - 1; var lead = 0f; var tail = 0f
        for (c in fc until sheet.colCount) {
            if (c in hiddenCols) continue
            val px = with(density) { colW(c).toPx() }
            when {
                x + px < left -> lead += px
                x > right -> tail += px
                else -> { if (first < 0) first = c; last = c }
            }
            x += px
        }
        return Window(if (first < 0) fc else first, last, lead, tail)
    }
    val rw = rowWindow()
    val cw = colWindow()
    fun Float.toDpUnit() = with(density) { toDp() }

    // ---- Pointer geometry: body-viewport px (origin at the scrolling body's top-left) to rows and ----
    // ---- columns; the frozen panes lie at negative positions, left of and above the body.        ----
    fun colPx(c: Int) = with(density) { colW(c).toPx() }
    fun rowPx(r: Int) = with(density) { rowH(r).toPx() }
    val frozenWpx = GridAxis.offsetOf(fc, 0, hiddenCols) { colPx(it) }
    val frozenHpx = GridAxis.offsetOf(fr, 0, hidden) { rowPx(it) }
    // Pictures are sized in points; the grid draws a default row's 15 pt in its 26 dp.
    val pxPerPt = with(density) { SheetEditor.GRID_DP_PER_PT.dp.toPx() }
    /** A picture's top-left in the scrolling body's content, px (as [SheetEditor.imageOrigin] places it, on this grid's own axes). */
    fun pictureCorner(img: com.therealityteam.aeropages.model.SheetFloating): Offset = Offset(
        GridAxis.offsetOf(img.col, 0, hiddenCols) { colPx(it) } + (if (img.col in hiddenCols) 0f else img.dxPt * pxPerPt) - frozenWpx,
        GridAxis.offsetOf(img.row, 0, hidden) { rowPx(it) } + (if (img.row in hidden) 0f else img.dyPt * pxPerPt) - frozenHpx,
    )
    // The shape whose Edit Text dialog is open, and which colours its action bar offers (fill or outline).
    var shapeTextFor by remember { mutableStateOf<com.therealityteam.aeropages.model.SheetShape?>(null) }
    // Dev hook (debug builds): `setprop debug.aero.dialog shapeoutline` opens the picked shape's outline menu.
    var shapePalette by remember { mutableStateOf(if (devDialogProp() == "shapeoutline") "outline" else null) }
    LaunchedEffect(editor.shapeTextRequest) { if (editor.shapeTextRequest > 0) editor.selectedShape?.let { shapeTextFor = it } }
    // The picture whose Crop dialog is open; `setprop debug.aero.dialog crop` opens it on the sheet's
    // first picture once the workbook holding one has loaded.
    var cropFor by remember { mutableStateOf<com.therealityteam.aeropages.model.SheetImage?>(null) }
    var devCropShown by remember { mutableStateOf(false) }
    val firstPicture = sheet.images.firstOrNull()
    LaunchedEffect(firstPicture) {
        if (!devCropShown && firstPicture != null && devDialogProp() == "crop") { cropFor = firstPicture; devCropShown = true }
    }
    val drag = remember { SelectDrag() }
    drag.colAt = { x ->
        if (x < 0f && fc > 0) GridAxis.indexAt(x + frozenWpx, 0, fc, hiddenCols) { colPx(it) }
        else GridAxis.indexAt(x + h.value, fc, sheet.colCount, hiddenCols) { colPx(it) }
    }
    drag.rowAt = { y ->
        if (y < 0f && fr > 0) GridAxis.indexAt(y + frozenHpx, 0, fr, hidden) { rowPx(it) }
        else GridAxis.indexAt(y + v.value, fr, sheet.rowCount, hidden) { rowPx(it) }
    }
    val haptic = LocalHapticFeedback.current
    fun startDrag(mode: SelectDrag.Mode, row: Int, col: Int, pointer: Offset) {
        // A press on a merged cell anchors at the merge's first cell.
        val m = if (mode == SelectDrag.Mode.CELLS) editor.sheet.mergeAt(row, col) else null
        drag.anchorRow = m?.r1 ?: row; drag.anchorCol = m?.c1 ?: col
        drag.pointer = pointer
        drag.mode = mode
        when (mode) {
            SelectDrag.Mode.CELLS -> editor.select(drag.anchorRow, drag.anchorCol)
            SelectDrag.Mode.COLUMNS -> editor.selectColumns(col)
            SelectDrag.Mode.ROWS -> editor.selectRows(row)
            SelectDrag.Mode.FILL -> {}
        }
        runCatching { focus.requestFocus() }
    }

    // A selection made with the pointer gets a small bar of actions; it stays while that selection
    // does (any other selection change — a tap, the keyboard, the Name Box — hides it).
    var barFor by remember { mutableStateOf<List<Int>?>(null) }
    // Its Quick Analysis button's panel (Excel's), open while that bar shows.
    var quickOpen by remember { mutableStateOf(false) }
    var quickStart by remember { mutableStateOf(QuickTab.FORMATTING) }
    // The Paste Options button's menu.
    var pasteMenu by remember { mutableStateOf(false) }
    var pasteMenuUp by remember { mutableStateOf(false) }
    LaunchedEffect(barFor) { if (barFor == null) quickOpen = false }

    // The grid's context menu — Excel's right-click — opened by a press and hold let go without moving: on the
    // cells (the selection kept when the press was inside it), a row number or a column letter.
    var menuFor by remember { mutableStateOf<Pair<GridMenuKind, Int>?>(null) }
    // Ctrl (⌘) held: a tap adds a cell as one more area of the selection (Excel's Ctrl+click).
    var ctrlHeld by remember { mutableStateOf(false) }
    val holdStillPx = with(density) { 10.dp.toPx() }
    class Hold { var moved = 0f; var keep: IntArray? = null; var target: Pair<GridMenuKind, Int>? = null }
    val hold = remember { Hold() }
    fun holdStart(target: Pair<GridMenuKind, Int>, inside: Boolean) {
        hold.moved = 0f; hold.target = target
        val many = editor.selRow != editor.selRow2 || editor.selCol != editor.selCol2
        hold.keep = if (inside && many) intArrayOf(editor.selRow, editor.selCol, editor.selRow2, editor.selCol2) else null
    }
    // Dev hooks (debug builds): `cellmenu` (B2:C4), `rowmenu` (row 3), `colmenu` (column B) open the context menu.
    if (com.therealityteam.aeropages.BuildConfig.DEBUG) LaunchedEffect(Unit) {
        when (devDialogProp()) {
            "cellmenu" -> { kotlinx.coroutines.delay(3_000); editor.selectRange(1, 1, 3, 2); menuFor = GridMenuKind.CELLS to 0 }
            // The cell menu on A2 (a yellow cell in the samples) with its Sort or Filter submenu open.
            "cellmenusort", "cellmenufilter" -> { kotlinx.coroutines.delay(3_000); editor.select(1, 0); menuFor = GridMenuKind.CELLS to 0 }
            "cellmenulow" -> { kotlinx.coroutines.delay(3_000); editor.select(15, 1); menuFor = GridMenuKind.CELLS to 0 }
            "rowmenu" -> { kotlinx.coroutines.delay(3_000); editor.selectRows(2); menuFor = GridMenuKind.ROWS to 2 }
            "colmenu" -> { kotlinx.coroutines.delay(3_000); editor.selectColumns(1); menuFor = GridMenuKind.COLUMNS to 1 }
            "pasteoptions" -> { kotlinx.coroutines.delay(3_000); editor.selectRange(0, 0, 2, 1); editor.copySelection(); editor.select(1, 4); editor.pasteSelection(); pasteMenu = true }
            "quicktotals", "quickcharts" -> { kotlinx.coroutines.delay(3_000); editor.selectRange(0, 0, 4, 2); quickStart = if (devDialogProp() == "quicktotals") QuickTab.TOTALS else QuickTab.CHARTS
                barFor = listOf(editor.sheetIndex, editor.selRow, editor.selCol, editor.selRow2, editor.selCol2); quickOpen = true }
            "quickanalysis" -> { kotlinx.coroutines.delay(3_000); editor.selectRange(0, 0, 4, 2); barFor = listOf(editor.sheetIndex, editor.selRow, editor.selCol, editor.selRow2, editor.selCol2); quickOpen = true }
        }
    }
    // The active cell in the grid's lower half: its menu opens upward, as Office's do near the screen's foot.
    fun activeInLowerHalf(): Boolean {
        val rect = activeRect ?: return false
        val grid = places[0]?.takeIf { it.isAttached } ?: return false
        return rect.center.y > grid.size.height / 2f
    }
    fun holdEnd() {
        val target = hold.target ?: return
        hold.target = null
        if (hold.moved >= holdStillPx) return
        hold.keep?.let { editor.selectRange(it[0], it[1], it[2], it[3]) }
        barFor = null
        menuFor = target
    }
    fun selectionKey() = listOf(editor.sheetIndex, editor.selRow, editor.selCol, editor.selRow2, editor.selCol2)
    fun endSelectDrag() {
        val mode = drag.mode
        // Format Painter paints the block dragged over (cells, whole rows or columns) as the drag ends.
        if (mode != null && mode != SelectDrag.Mode.FILL && editor.painterArmed) editor.paintFormats()
        else if (mode != null && mode != SelectDrag.Mode.FILL) barFor = selectionKey()
        drag.mode = null
    }

    // While a selection is dragged to the body's edge (or past it) the sheet scrolls under the pointer.
    val edgePx = with(density) { 28.dp.toPx() }
    LaunchedEffect(drag.mode) {
        val mode = drag.mode ?: return@LaunchedEffect
        fun speed(depth: Float) = (depth / edgePx).coerceIn(0f, 3f) * edgePx * 0.45f
        while (true) {
            val p = drag.pointer
            var dx = 0f
            var dy = 0f
            if (mode != SelectDrag.Mode.ROWS) {
                if (p.x > bodyWpx - edgePx) dx = speed(p.x - (bodyWpx - edgePx)) else if (p.x < edgePx && h.value > 0) dx = -speed(edgePx - p.x)
            }
            if (mode != SelectDrag.Mode.COLUMNS) {
                if (p.y > bodyHpx - edgePx) dy = speed(p.y - (bodyHpx - edgePx)) else if (p.y < edgePx && v.value > 0) dy = -speed(edgePx - p.y)
            }
            if (dx != 0f) h.dispatchRawDelta(dx)
            if (dy != 0f) v.dispatchRawDelta(dy)
            if (dx != 0f || dy != 0f) drag.apply(editor)
            kotlinx.coroutines.delay(16)
        }
    }

    // The moving corner of the selection stays in view (arrow keys, the Name Box, Shift+arrows);
    // whole columns do not scroll up and down, whole rows not sideways.
    val measured = bodyWpx > 0 && bodyHpx > 0
    LaunchedEffect(editor.selRow2, editor.selCol2, editor.sheetIndex, measured) {
        if (drag.mode != null || !measured) return@LaunchedEffect
        val r = editor.selRow2
        val c = editor.selCol2
        if (!editor.wholeColumnsSelected && r >= fr && r !in hidden) {
            val top = GridAxis.offsetOf(r, fr, hidden) { rowPx(it) }
            val bottom = top + rowPx(r)
            if (top < v.value) v.animateScrollTo(top.toInt())
            else if (bottom > v.value + bodyHpx) v.animateScrollTo((bottom - bodyHpx).roundToInt())
        }
        if (!editor.wholeRowsSelected && c >= fc && c !in hiddenCols) {
            val left = GridAxis.offsetOf(c, fc, hiddenCols) { colPx(it) }
            val right = left + colPx(c)
            if (left < h.value) h.animateScrollTo(left.toInt())
            else if (right > h.value + bodyWpx) h.animateScrollTo((right - bodyWpx).roundToInt())
        }
    }

    // Zooming keeps the cell at the top left where it was, as Excel does: the scroll offsets follow the zoom — or, after
    // Zoom to Selection, the selection's top left goes there.
    val zoom = sheet.viewZoom
    var shownZoom by remember(editor.sheetIndex) { mutableIntStateOf(zoom) }
    val fitTarget = remember { arrayOfNulls<Offset>(1) }
    LaunchedEffect(zoom, editor.sheetIndex) {
        if (zoom == shownZoom) return@LaunchedEffect
        val k = zoom.toFloat() / shownZoom
        shownZoom = zoom
        val target = fitTarget[0]; fitTarget[0] = null
        androidx.compose.runtime.withFrameNanos { }     // the cells laid out at their new size first
        v.scrollTo((target?.y ?: (v.value * k)).roundToInt())
        h.scrollTo((target?.x ?: (h.value * k)).roundToInt())
    }
    LaunchedEffect(editor.zoomFitRequest) {
        if (editor.zoomFitRequest == 0 || !measured) return@LaunchedEffect
        val rows = (editor.rangeRowMin()..editor.rangeRowMax()).filter { it !in hidden }
        val cols = (editor.rangeColMin()..editor.rangeColMax()).filter { it !in hiddenCols }
        if (rows.isEmpty() || cols.isEmpty()) return@LaunchedEffect
        val w = cols.sumOf { colPx(it).toDouble() }.toFloat()
        val ht = rows.sumOf { rowPx(it).toDouble() }.toFloat()
        // The headings and frozen panes grow with the zoom too: what fits is worked out with them.
        val fixedW = with(density) { (if (sheet.showHeadings) ROWHDR_W else 0.dp).toPx() + (if (outlineLevels > 0) OUTLINE_W * outlineLevels else 0.dp).toPx() } + frozenWpx
        val fixedH = with(density) { (if (sheet.showHeadings) CELL_H else 0.dp).toPx() } + frozenHpx
        val z = (zoom * minOf((bodyWpx + fixedW) / (w + fixedW), (bodyHpx + fixedH) / (ht + fixedH))).toInt().coerceIn(com.therealityteam.aeropages.model.Sheet.MIN_ZOOM, com.therealityteam.aeropages.model.Sheet.MAX_ZOOM)
        val k = z.toFloat() / zoom
        val top = if (rows.first() >= fr) GridAxis.offsetOf(rows.first(), fr, hidden) { rowPx(it) } * k else 0f
        val left = if (cols.first() >= fc) GridAxis.offsetOf(cols.first(), fc, hiddenCols) { colPx(it) } * k else 0f
        if (z == zoom) { v.animateScrollTo(top.roundToInt()); h.animateScrollTo(left.roundToInt()); return@LaunchedEffect }
        fitTarget[0] = Offset(left, top)
        editor.setZoom(z)
    }

    // The sheet has room past its end: nearing its last row or column adds more (which is not an edit).
    // A sheet shorter or narrower than the window (zoomed out) grows at once by what the window lacks; looked at again
    // whenever the zoom changes.
    val defaultRowPx = with(density) { CELL_H.toPx() }
    val defaultColPx = with(density) { CELL_W.toPx() }
    LaunchedEffect(zoom) {
        snapshotFlow { Triple(v.value, v.maxValue, bodyHpx) }.collect { (value, max, viewport) ->
            if (viewport > 0 && max != Int.MAX_VALUE && max - value < viewport)
                editor.growRows(SheetEditor.ROOM_ROWS + if (max == 0) (viewport / defaultRowPx).toInt() else 0)
        }
    }
    LaunchedEffect(zoom) {
        snapshotFlow { Triple(h.value, h.maxValue, bodyWpx) }.collect { (value, max, viewport) ->
            if (viewport > 0 && max != Int.MAX_VALUE && max - value < viewport)
                editor.growCols(SheetEditor.ROOM_COLS + if (max == 0) (viewport / defaultColPx).toInt() else 0)
        }
    }

    // A single Spacer above ~262k px overflows Compose's packed Constraints — emit chunks.
    val chunkPx = 200_000f
    @Composable
    fun VPad(px: Float) {
        var rest = px
        while (rest > 0f) { val take = if (rest > chunkPx) chunkPx else rest; Spacer(Modifier.height(take.toDpUnit())); rest -= take }
    }
    @Composable
    fun HPad(px: Float) {
        var rest = px
        while (rest > 0f) { val take = if (rest > chunkPx) chunkPx else rest; Spacer(Modifier.width(take.toDpUnit())); rest -= take }
    }

    /**
     * Where long text runs on over empty cells to its right in row [r] across [cols] (one grid
     * region: the frozen columns or the scrolling ones), as Excel draws it: each overflowing
     * column's extra width, and the empty cells it covers (true for the last); right-aligned
     * text likewise over the empty cells to its left.
     */
    fun rowSpill(r: Int, cols: List<Int>): RowSpill? {
        var spill: RowSpill? = null
        var i = 0
        while (i < cols.size) {
            val c = cols[i]
            // Center Across Selection: the text centred over the empty cells after it that are set the same way.
            if (editor.centersAcross(r, c)) {
                var got = 0f
                var j = i + 1
                while (j < cols.size && editor.acrossContinues(r, cols[j])) { got += colW(cols[j]).value; j++ }
                if (j > i + 1) {
                    val s = spill ?: RowSpill().also { spill = it }
                    s.extra[c] = got.dp; s.across.add(c)
                    for (k in i + 1 until j) s.covered[cols[k]] = k == j - 1
                    i = j
                } else i++
                continue
            }
            if (editor.overflowsLeft(r, c)) {
                val need = textWidthDp(r, c) + 8f - colW(c).value
                var got = 0f
                var j = i - 1
                while (need > 0f && got < need && j >= 0) {
                    val n = cols[j]
                    if (editor.display(r, n).isNotEmpty() || sheet.mergeAt(r, n) != null || spill?.covered?.containsKey(n) == true || spill?.extra?.containsKey(n) == true) break
                    got += colW(n).value
                    j--
                }
                if (j < i - 1) {
                    val s = spill ?: RowSpill().also { spill = it }
                    s.left[c] = got.dp
                    for (k in j + 1 until i) s.coveredLeft.add(cols[k])
                }
                i++; continue
            }
            if (!editor.overflows(r, c)) { i++; continue }
            val need = textWidthDp(r, c) + 8f - colW(c).value
            var got = 0f
            var j = i + 1
            while (need > 0f && got < need && j < cols.size) {
                val n = cols[j]
                if (editor.display(r, n).isNotEmpty() || sheet.mergeAt(r, n) != null) break
                got += colW(n).value
                j++
            }
            if (j > i + 1) {
                val s = spill ?: RowSpill().also { spill = it }
                s.extra[c] = got.dp
                for (k in i + 1 until j) s.covered[cols[k]] = k == j - 1
                i = j
            } else i++
        }
        return spill
    }

    @Composable
    fun cellAt(r: Int, c: Int, windowStartCol: Int = 0, spill: RowSpill? = null) {
        val merge = sheet.mergeAt(r, c)
        if (merge != null && !merge.isAnchor(r, c)) {
            // Horizontal-only merge: the anchor is widened to cover these cells — skip
            // them, UNLESS the anchor sits left of the composed window (then a blank
            // placeholder keeps the row's width aligned).
            if (merge.r1 == merge.r2 && merge.c1 >= windowStartCol) return
            val anchorLabel = "${Workbook.colLabel(merge.c1)}${merge.r1 + 1}"
            Box(Modifier.size(colW(c), rowH(r)).background(Color.White).semantics { contentDescription = anchorLabel }.clickable { editor.select(merge.r1, merge.c1); if (editor.painterArmed) editor.paintFormats(); runCatching { focus.requestFocus() } })
            return
        }
        // A table's look shows under the cell's own formatting.
        val cell = com.therealityteam.aeropages.model.TableLooks.styled(sheet, r, c, sheet.cell(r, c))
        val active = r == editor.selRow && c == editor.selCol
        // Width: this column's width, plus covered columns' widths for a horizontal merge.
        var w = colW(c)
        if (merge != null && merge.r1 == merge.r2) for (cc in (merge.c1 + 1)..merge.c2) if (cc !in hiddenCols) w += colW(cc)
        val spark = editor.sparklineAt(r, c)
        val ref = "${Workbook.colLabel(c)}${r + 1}"
        // The AutoFilter's header row carries a filter button per column (a funnel while that column filters).
        // A table's header row has its own buttons; the menu then works on the table's filter.
        val tableButton = editor.tableButtonAt(r, c)
        val button = if (tableButton != null) FilterButton(editor.filterIsTable(tableButton) && editor.columnFilter(c) != null, stringResource(R.string.a11y_filter_button, Workbook.colLabel(c))) {
            if (editor.adoptTableFilter(tableButton)) onFilterMenu(c) else onTableRefused()
        } else editor.autoFilter?.takeIf { r == it.r1 && c in it.c1..it.c2 }?.let {
            FilterButton(editor.columnFilter(c) != null, stringResource(R.string.a11y_filter_button, Workbook.colLabel(c))) { onFilterMenu(c) }
        }
        if (active) androidx.compose.runtime.DisposableEffect(r, c) { onDispose { places[1] = null; activeRect = null } }
        GridCell(editor.displayFitted(r, c, w.value), cell, active, editor.inSelection(r, c), editor.conditionalLook(r, c),
            if (active) Modifier.onGloballyPositioned { places[1] = it; placeActive() } else Modifier, w, rowH(r), gridlines,
            sparkline = spark?.let { SparkDraw(it.type, it.color, editor.sparklineValues(it), it.markers) }, textColor = editor.displayColor(r, c),
            align = editor.displayAlign(r, c),
            spill = spill?.extra?.get(c) ?: 0.dp, covered = spill?.covered?.containsKey(c) == true, coverEnd = spill?.covered?.get(c) == true,
            spillLeft = spill?.left?.get(c) ?: 0.dp, coveredLeft = spill?.coveredLeft?.contains(c) == true,
            across = spill?.across?.contains(c) == true,
            filter = button, circled = (r < fr || c < fc) && editor.isCircled(r, c),
            overlay = if (active && menuFor?.first == GridMenuKind.CELLS) ({ AnchoredFlyout({ menuFor = null }, above = activeInLowerHalf()) { SheetContextMenu(editor, GridMenuKind.CELLS, clipboard, menu, onDismiss = { menuFor = null }, onPickList = { openList() }) } }) else null,
            label = ref + (cell?.comment?.let { ", ${stringResource(R.string.comment)}: $it" } ?: "")) {
            // With Format Painter taken up, the cell tapped takes its formats.
            if (ctrlHeld && !editor.painterArmed) editor.addArea(r, c) else editor.select(r, c)
            if (editor.painterArmed) editor.paintFormats(); runCatching { focus.requestFocus() }
        }
    }

    // Headers of the selected range are highlighted; tapping one selects the whole column / row.
    // Every area's rows and columns light up in the headers, as Excel's do for a selection of several.
    val areas = editor.selectedAreas()
    val selRows = areas.map { it[0]..it[2] }
    val selCols = areas.map { it[1]..it[3] }
    // Dragging a header's edge resizes (by default column widths / row heights; double-tap fits the content).
    val baseColPx = with(density) { CELL_W.toPx() }
    val baseRowPx = with(density) { CELL_H.toPx() }
    @Composable
    fun ColHeader(c: Int) {
        val label = Workbook.colLabel(c)
        val a11y = stringResource(R.string.a11y_select_column, label)
        Box {
            HeaderCell(label, colW(c), selected = selCols.any { c in it }, description = a11y, onClick = { editor.selectColumns(c); if (editor.painterArmed) editor.paintFormats(); runCatching { focus.requestFocus() } })
            if (menuFor == GridMenuKind.COLUMNS to c) AnchoredFlyout({ menuFor = null }) { SheetContextMenu(editor, GridMenuKind.COLUMNS, clipboard, menu, onDismiss = { menuFor = null }) }
            EdgeHandle(
                acrossX = true, modifier = Modifier.align(Alignment.CenterEnd).size(8.dp, CELL_H),
                onStart = { editor.beginColResize(c) }, onDrag = { px -> editor.dragColWidth(px / baseColPx) },
                onEnd = editor::endResize, onDoubleTap = { editor.autoFitColumns(c..c) },
            )
        }
    }
    @Composable
    fun RowHeader(r: Int) {
        val a11y = stringResource(R.string.a11y_select_row, r + 1)
        Box {
            HeaderCell("${r + 1}", ROWHDR_W, rowH(r), selected = selRows.any { r in it }, description = a11y, column = false, onClick = { editor.selectRows(r); if (editor.painterArmed) editor.paintFormats(); runCatching { focus.requestFocus() } })
            if (menuFor == GridMenuKind.ROWS to r) AnchoredFlyout({ menuFor = null }) { SheetContextMenu(editor, GridMenuKind.ROWS, clipboard, menu, onDismiss = { menuFor = null }) }
            EdgeHandle(
                acrossX = false, modifier = Modifier.align(Alignment.BottomCenter).size(ROWHDR_W, 6.dp),
                onStart = { editor.beginRowResize(r) }, onDrag = { px -> editor.dragRowHeight(px / baseRowPx) },
                onEnd = editor::endResize, onDoubleTap = { editor.autoFitRows(r..r) },
            )
        }
    }

    @Composable
    fun scrollingCells(r: Int) {
        val cols = (cw.first..cw.last).filter { it !in hiddenCols }
        val spill = rowSpill(r, cols)
        Row {
            HPad(cw.leadPad)
            for (c in cols) cellAt(r, c, windowStartCol = cw.first, spill = spill)
            HPad(cw.tailPad)
        }
    }

    var gridHpx by remember { mutableIntStateOf(0) }
    var gridWpx by remember { mutableIntStateOf(0) }
    Box(
        modifier.fillMaxSize().background(Color.White)
            .onGloballyPositioned { places[0] = it; gridHpx = it.size.height; gridWpx = it.size.width; placeActive() }
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    androidx.compose.ui.input.key.Key.CtrlLeft, androidx.compose.ui.input.key.Key.CtrlRight,
                    androidx.compose.ui.input.key.Key.MetaLeft, androidx.compose.ui.input.key.Key.MetaRight ->
                        ctrlHeld = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                    else -> {}
                }
                // Excel's F5 / Ctrl+G: Go To.
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (e.key == androidx.compose.ui.input.key.Key.F5 ||
                        (e.isCtrlPressed || e.isMetaPressed) && e.key == androidx.compose.ui.input.key.Key.G)) { menu.onGoTo(); return@onPreviewKeyEvent true }
                // The open list takes the arrows, Enter and Esc; Alt+↓ opens it, as in Excel.
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && listOpen) {
                    val n = editor.pickChoices()?.size ?: 0
                    when (e.key) {
                        androidx.compose.ui.input.key.Key.DirectionDown -> { listIndex = (listIndex + 1).coerceAtMost(n - 1); return@onPreviewKeyEvent true }
                        androidx.compose.ui.input.key.Key.DirectionUp -> { listIndex = (listIndex - 1).coerceAtLeast(0); return@onPreviewKeyEvent true }
                        androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter, androidx.compose.ui.input.key.Key.Tab -> {
                            editor.pickChoices()?.getOrNull(listIndex)?.let { editor.pickFromList(it) }
                            listOpen = false
                            return@onPreviewKeyEvent true
                        }
                        androidx.compose.ui.input.key.Key.Escape -> { listOpen = false; return@onPreviewKeyEvent true }
                        else -> {}
                    }
                }
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.isAltPressed && e.key == androidx.compose.ui.input.key.Key.DirectionDown &&
                    !editor.pickChoices().isNullOrEmpty()) { openList(); return@onPreviewKeyEvent true }
                // Excel's Ctrl+1 (Format Cells), Ctrl+K (Insert Link) and Alt+= (AutoSum).
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && editor.selectedImage == null && editor.selectedShape == null) {
                    val c = e.isCtrlPressed || e.isMetaPressed
                    when {
                        c && !e.isShiftPressed && e.key == androidx.compose.ui.input.key.Key.One -> { menu.onCellFormat(); return@onPreviewKeyEvent true }
                        c && e.key == androidx.compose.ui.input.key.Key.K -> { menu.onLink(); return@onPreviewKeyEvent true }
                        e.isAltPressed && e.key == androidx.compose.ui.input.key.Key.Equals -> {
                            if (editor.autoFunction("SUM") == null) onStartEdit(null)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                // Excel's Ctrl+E: Flash Fill down the active cell's column.
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (e.isCtrlPressed || e.isMetaPressed) && e.key == androidx.compose.ui.input.key.Key.E &&
                    editor.selectedImage == null && editor.selectedShape == null) { runFlashFill(editor, menu.onStatus); return@onPreviewKeyEvent true }
                // Excel's Ctrl+Q: Quick Analysis for a selected block (its bar shown, the panel open).
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (e.isCtrlPressed || e.isMetaPressed) && e.key == androidx.compose.ui.input.key.Key.Q &&
                    editor.selectedImage == null && editor.selectedShape == null && (editor.selRow != editor.selRow2 || editor.selCol != editor.selCol2)) {
                    barFor = selectionKey(); quickOpen = true; return@onPreviewKeyEvent true
                }
                handleGridKey(editor, e, clipboard, onFind, onStartEdit)
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // Column outline band: level buttons in the corner, brackets and −/+ over the columns.
            if (colLevels > 0) Row {
                val corner = (if (outlineLevels > 0) OUTLINE_W * outlineLevels else 0.dp) + (if (sheet.showHeadings) ROWHDR_W else 0.dp)
                ColOutlineLevelButtons(editor, colLevels, corner)
                for (c in 0 until fc) if (c !in hiddenCols) ColOutlineCell(editor, c, colW(c), colLevels)
                Box(Modifier.weight(1f).horizontalScroll(h)) {
                    Row {
                        HPad(cw.leadPad)
                        for (c in cw.first..cw.last) if (c !in hiddenCols) ColOutlineCell(editor, c, colW(c), colLevels)
                        HPad(cw.tailPad)
                    }
                }
            }
            // Column-letter header row: corner + frozen letters + h-scrolling letters (windowed).
            if (sheet.showHeadings || outlineLevels > 0) Row {
                if (outlineLevels > 0) OutlineLevelButtons(editor, outlineLevels)
                if (sheet.showHeadings) CornerCell(onClick = { editor.selectAll(); runCatching { focus.requestFocus() } })
                for (c in 0 until fc) if (c !in hiddenCols) ColHeader(c)
                Box(Modifier.weight(1f).onSizeChanged { bodyWpx = it.width }.horizontalScroll(h)) {
                    // Press and hold a letter, then drag across the letters: several whole columns.
                    Row(Modifier.pointerInput(editor) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { o ->
                                val x = o.x - h.value
                                val c = drag.colAt(x)
                                if (c >= 0) {
                                    val whole = editor.rangeRowMin() == 0 && editor.rangeRowMax() == sheet.rowCount - 1 && c in editor.rangeColMin()..editor.rangeColMax()
                                    holdStart(GridMenuKind.COLUMNS to c, whole)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress); startDrag(SelectDrag.Mode.COLUMNS, 0, c, Offset(x, 0f))
                                }
                            },
                            onDrag = { change, amount -> hold.moved += amount.getDistance(); if (drag.mode == SelectDrag.Mode.COLUMNS) { drag.pointer = Offset(change.position.x - h.value, 0f); drag.apply(editor) } },
                            onDragEnd = { endSelectDrag(); holdEnd() }, onDragCancel = { endSelectDrag(); hold.target = null },
                        )
                    }) {
                        HPad(cw.leadPad)
                        for (c in cw.first..cw.last) if (c !in hiddenCols) ColHeader(c)
                        HPad(cw.tailPad)
                    }
                }
            }
            // Frozen top rows: row header + frozen cells + h-scrolling cells (windowed cols).
            for (r in 0 until fr) {
                if (r in hidden) continue
                Row {
                    if (outlineLevels > 0) OutlineGutter(editor, r, rowH(r), outlineLevels)
                    if (sheet.showHeadings) RowHeader(r)
                    val frozenCols = (0 until fc).filter { it !in hiddenCols }
                    val frozenSpill = rowSpill(r, frozenCols)
                    for (c in frozenCols) cellAt(r, c, spill = frozenSpill)
                    Box(Modifier.weight(1f).horizontalScroll(h)) { scrollingCells(r) }
                }
            }
            // Body band: left frozen column (v-scroll) + body (v+h-scroll). Shared h/v keep
            // them synced; both compose only the windowed rows with lead/tail spacers.
            Row(Modifier.weight(1f).onSizeChanged { bodyHpx = it.height }) {
                // Row numbers: press and hold, then drag across them for several whole rows; on the
                // frozen columns beside them the same gesture selects cells.
                val gutterPx = with(density) { (if (outlineLevels > 0) OUTLINE_W * outlineLevels else 0.dp).toPx() }
                val headerPx = with(density) { (if (sheet.showHeadings) ROWHDR_W else 0.dp).toPx() }
                Column(Modifier.fillMaxHeight().verticalScroll(v).pointerInput(editor, gutterPx, headerPx, frozenWpx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o ->
                            val y = o.y - v.value
                            val onHeader = o.x >= gutterPx && o.x < gutterPx + headerPx
                            val onCells = o.x >= gutterPx + headerPx
                            val r = drag.rowAt(y)
                            if (r >= 0 && (onHeader || onCells)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (onHeader) {
                                    val whole = editor.rangeColMin() == 0 && editor.rangeColMax() == sheet.colCount - 1 && r in editor.rangeRowMin()..editor.rangeRowMax()
                                    holdStart(GridMenuKind.ROWS to r, whole)
                                    startDrag(SelectDrag.Mode.ROWS, r, 0, Offset(0f, y))
                                } else {
                                    val x = o.x - gutterPx - headerPx - frozenWpx
                                    val c = drag.colAt(x)
                                    if (c >= 0) { holdStart(GridMenuKind.CELLS to 0, editor.inSelection(r, c)); startDrag(SelectDrag.Mode.CELLS, r, c, Offset(x, y)) }
                                }
                            }
                        },
                        onDrag = { change, amount ->
                            hold.moved += amount.getDistance()
                            val p = change.position
                            when (drag.mode) {
                                SelectDrag.Mode.ROWS -> { drag.pointer = Offset(0f, p.y - v.value); drag.apply(editor) }
                                SelectDrag.Mode.CELLS -> { drag.pointer = Offset(p.x - gutterPx - headerPx - frozenWpx, p.y - v.value); drag.apply(editor) }
                                else -> {}
                            }
                        },
                        onDragEnd = { endSelectDrag(); holdEnd() }, onDragCancel = { endSelectDrag(); hold.target = null },
                    )
                }) {
                    VPad(rw.leadPad)
                    for (r in rw.first..rw.last) {
                        if (r in hidden) continue
                        Row {
                            if (outlineLevels > 0) OutlineGutter(editor, r, rowH(r), outlineLevels)
                            if (sheet.showHeadings) RowHeader(r)
                            val frozenCols = (0 until fc).filter { it !in hiddenCols }
                            val frozenSpill = rowSpill(r, frozenCols)
                            for (c in frozenCols) cellAt(r, c, spill = frozenSpill)
                        }
                    }
                    VPad(rw.tailPad)
                }
                val viewport = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
                Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { bodyWpx = it.width }.onGloballyPositioned { viewport[0] = it }.clipToBounds()) {
                    Box(Modifier.fillMaxSize().verticalScroll(v).horizontalScroll(h)) {
                        // Press and hold a cell, then drag: the block from that cell to the pointer.
                        Column(Modifier.pointerInput(editor) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { o ->
                                    val p = Offset(o.x - h.value, o.y - v.value)
                                    val r = drag.rowAt(p.y)
                                    val c = drag.colAt(p.x)
                                    if (r >= 0 && c >= 0) {
                                        holdStart(GridMenuKind.CELLS to 0, editor.inSelection(r, c))
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress); startDrag(SelectDrag.Mode.CELLS, r, c, p)
                                    }
                                },
                                onDrag = { change, amount ->
                                    hold.moved += amount.getDistance()
                                    if (drag.mode == SelectDrag.Mode.CELLS) { drag.pointer = Offset(change.position.x - h.value, change.position.y - v.value); drag.apply(editor) }
                                },
                                onDragEnd = { endSelectDrag(); holdEnd() }, onDragCancel = { endSelectDrag(); hold.target = null },
                            )
                        }) {
                            VPad(rw.leadPad)
                            for (r in rw.first..rw.last) {
                                if (r in hidden) continue
                                scrollingCells(r)
                            }
                            VPad(rw.tailPad)
                        }
                        // Pictures float over the cells, each placed from its anchor cell (one anchored in the
                        // frozen rows or columns scrolls with the body); only those over the composed rows and
                        // columns (the viewport and its overscan) are composed.
                        val shownTop = rw.leadPad
                        val shownBottom = shownTop + (rw.first..rw.last).sumOf { if (it in hidden) 0.0 else rowPx(it).toDouble() }.toFloat()
                        val shownLeft = cw.leadPad
                        val shownRight = shownLeft + (cw.first..cw.last).sumOf { if (it in hiddenCols) 0.0 else colPx(it).toDouble() }.toFloat()
                        for (img in sheet.images) {
                            val (left, top) = pictureCorner(img)
                            val w = img.widthPt * pxPerPt
                            val ht = img.heightPt * pxPerPt
                            if (left + w < shownLeft || left > shownRight || top + ht < shownTop || top > shownBottom) continue
                            androidx.compose.runtime.key(img) {
                                SheetPictureView(img, left, top, w, ht, img === editor.selectedImage, SheetEditor.MIN_PICTURE_PT * pxPerPt,
                                    onSelect = { barFor = null; editor.selectImage(img); runCatching { focus.requestFocus() } },
                                    onMove = { dx, dy -> val (x, y) = editor.imageOrigin(img); editor.moveImage(img, x + dx / pxPerPt, y + dy / pxPerPt) },
                                    onFrame = { f ->
                                        val (x, y) = editor.imageOrigin(img)
                                        editor.setImageFrame(img, x + f.left / pxPerPt, y + f.top / pxPerPt, f.width / pxPerPt, f.height / pxPerPt)
                                    })
                            }
                        }
                        // Shapes and text boxes, drawn over the pictures, placed the same way.
                        for (sh in sheet.shapes) {
                            val (left, top) = pictureCorner(sh)
                            val w = sh.widthPt * pxPerPt
                            val ht = sh.heightPt * pxPerPt
                            if (left + w < shownLeft || left > shownRight || top + ht < shownTop || top > shownBottom) continue
                            androidx.compose.runtime.key(sh) {
                                SheetShapeView(sh, left, top, w, ht, sh === editor.selectedShape, pxPerPt, SheetEditor.MIN_PICTURE_PT * pxPerPt, editor.revision,
                                    onSelect = { barFor = null; shapePalette = null; editor.selectShape(sh); runCatching { focus.requestFocus() } },
                                    onMove = { dx, dy -> val (x, y) = editor.floatingOrigin(sh); editor.moveShape(sh, x + dx / pxPerPt, y + dy / pxPerPt) },
                                    onFrame = { f ->
                                        val (x, y) = editor.floatingOrigin(sh)
                                        editor.setShapeFrame(sh, x + f.left / pxPerPt, y + f.top / pxPerPt, f.width / pxPerPt, f.height / pxPerPt)
                                    })
                            }
                        }
                        // Notes kept shown (or all of them, with Show All Notes): Excel's yellow boxes beside their cells, over the shapes.
                        val shownNotes = remember(sheet, editor.revision, editor.showAllNotes) {
                            sheet.cells.entries.filter { editor.noteVisible(it.value) }.map { it.key to it.value }
                        }
                        for ((k, cell) in shownNotes) {
                            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
                            if (r in hidden || c in hiddenCols) continue
                            val cx = GridAxis.offsetOf(c + 1, 0, hiddenCols) { colPx(it) } - frozenWpx
                            val cy = GridAxis.offsetOf(r, 0, hidden) { rowPx(it) } - frozenHpx
                            if (cx > shownRight || cx + 130 * pxPerPt < shownLeft || cy > shownBottom || cy + 240 * pxPerPt < shownTop) continue
                            androidx.compose.runtime.key(k) {
                                SheetNoteBox(cell.comment.orEmpty(), cell.commentAuthor, cx, cy, pxPerPt) { editor.select(r, c); runCatching { focus.requestFocus() } }
                            }
                        }
                    }
                    // Trace Precedents / Dependents: Excel's blue arrows, a dot at the tail and a head at the formula; a range
                    // boxed with its arrow from the box; red from a cell showing an error; dashed from a sheet icon for another sheet.
                    val arrows = editor.traceArrows.filter { it.sheet == editor.sheetIndex && it.r1 >= fr && it.c1 >= fc && it.row >= fr && it.col >= fc }
                    if (arrows.isNotEmpty()) Box(Modifier.fillMaxSize().drawBehind {
                        val sx = h.value.toFloat(); val sy = v.value.toFloat()
                        fun xOf(c: Int) = GridAxis.offsetOf(c, fc, hiddenCols) { colPx(it) } - sx
                        fun yOf(r: Int) = GridAxis.offsetOf(r, fr, hidden) { rowPx(it) } - sy
                        val line = 1.5.dp.toPx(); val head = 9.dp.toPx(); val half = 4.dp.toPx()
                        for (a in arrows) {
                            val ink = when { a.error -> Color(0xFFE02020); a.offSheet -> Color(0xFF404040); else -> Color(0xFF2F64D0) }
                            val tx = xOf(a.col) + colPx(a.col) / 2; val ty = yOf(a.row) + rowPx(a.row) / 2
                            val (fx, fy) = if (a.offSheet) (tx - 56.dp.toPx()) to (ty - 30.dp.toPx())
                                else (xOf(a.c1) + colPx(a.c1) / 2) to (yOf(a.r1) + rowPx(a.r1) / 2)
                            if (a.r1 != a.r2 || a.c1 != a.c2) drawRect(ink, topLeft = Offset(xOf(a.c1), yOf(a.r1)),
                                size = androidx.compose.ui.geometry.Size(xOf(a.c2 + 1) - xOf(a.c1), yOf(a.r2 + 1) - yOf(a.r1)),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(line))
                            val dx = tx - fx; val dy = ty - fy
                            val len = kotlin.math.sqrt(dx * dx + dy * dy)
                            if (len < 1f) continue
                            val ux = dx / len; val uy = dy / len
                            val baseX = tx - ux * head; val baseY = ty - uy * head
                            drawLine(ink, Offset(fx, fy), Offset(baseX, baseY), line,
                                pathEffect = if (a.offSheet) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null)
                            drawPath(androidx.compose.ui.graphics.Path().apply {
                                moveTo(tx, ty); lineTo(baseX - uy * half, baseY + ux * half); lineTo(baseX + uy * half, baseY - ux * half); close()
                            }, ink)
                            if (a.offSheet) {
                                // A small worksheet: a page with a grid, where the dashed arrow starts.
                                val w = 16.dp.toPx(); val hgt = 13.dp.toPx()
                                val l = fx - w / 2; val t = fy - hgt / 2
                                drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(w, hgt))
                                drawRect(ink, Offset(l, t), androidx.compose.ui.geometry.Size(w, hgt), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                                for (i in 1..2) drawLine(ink, Offset(l, t + hgt * i / 3), Offset(l + w, t + hgt * i / 3), 0.75.dp.toPx())
                                drawLine(ink, Offset(l + w / 2, t), Offset(l + w / 2, t + hgt), 0.75.dp.toPx())
                            } else drawCircle(ink, 3.dp.toPx(), Offset(fx, fy))
                        }
                    })
                    // Circle Invalid Data's red rings, a little larger than their cells as Excel draws them
                    // (the frozen rows and columns ring their cells inside them).
                    val rings = editor.circledCells().mapNotNull { (r, c) ->
                        if (r < fr || c < fc || r !in rw.first..rw.last || c !in cw.first..cw.last || r in hidden || c in hiddenCols) return@mapNotNull null
                        var top = rw.leadPad
                        for (i in rw.first until r) if (i !in hidden) top += rowPx(i)
                        var left = cw.leadPad
                        for (i in cw.first until c) if (i !in hiddenCols) left += colPx(i)
                        androidx.compose.ui.geometry.Rect(left, top, left + colPx(c), top + rowPx(r))
                    }
                    if (rings.isNotEmpty()) Box(Modifier.fillMaxSize().drawBehind {
                        val sx = h.value.toFloat(); val sy = v.value.toFloat()
                        val padX = 6.dp.toPx(); val padY = 3.dp.toPx()
                        for (ring in rings) drawOval(Color(0xFFE02020), topLeft = Offset(ring.left - sx - padX, ring.top - sy - padY),
                            size = androidx.compose.ui.geometry.Size(ring.width + 2 * padX, ring.height + 2 * padY),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    })
                    // Page breaks: once shown, Excel's dashed lines in Normal (solid where a break is manual); in Page Break
                    // Preview the print range's blue frame, its breaks (solid manual, dashed automatic), "Page N" on each page
                    // (numbered in the sheet's page order, as the PDF prints them) and grey beyond the range.
                    val preview = sheet.pageBreakPreview
                    val pages = if (preview || sheet.showPageBreaks) editor.pageLayout() else null
                    if (preview || pages != null) {
                        val pr = sheet.print
                        val res = androidx.compose.ui.platform.LocalResources.current
                        val rowEdges = FloatArray(maxOf(0, rw.last - rw.first + 2)).also { a ->
                            var y = rw.leadPad
                            for (i in a.indices) { a[i] = y; val r = rw.first + i; if (r !in hidden && i < a.size - 1) y += rowPx(r) }
                        }
                        val colEdges = FloatArray(maxOf(0, cw.last - cw.first + 2)).also { a ->
                            var x = cw.leadPad
                            for (i in a.indices) { a[i] = x; val c = cw.first + i; if (c !in hiddenCols && i < a.size - 1) x += colPx(c) }
                        }
                        val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color(0x47808080))
                        Box(Modifier.fillMaxSize().drawBehind {
                            if (rowEdges.isEmpty() || colEdges.isEmpty()) return@drawBehind
                            val sx = h.value.toFloat(); val sy = v.value.toFloat()
                            fun yOf(r: Int) = rowEdges[(r - rw.first).coerceIn(0, rowEdges.size - 1)] - sy
                            fun xOf(c: Int) = colEdges[(c - cw.first).coerceIn(0, colEdges.size - 1)] - sx
                            fun rowShown(r: Int) = r >= fr && r - rw.first in rowEdges.indices
                            fun colShown(c: Int) = c >= fc && c - cw.first in colEdges.indices
                            val grey = Color(0x26000000)
                            if (pages == null) { drawRect(grey); return@drawBehind }
                            val top = if (pages.rows.first < rw.first) 0f else yOf(pages.rows.first)
                            val bottom = yOf(pages.rows.last + 1)
                            val left = if (pages.cols.first < cw.first) 0f else xOf(pages.cols.first)
                            val right = xOf(pages.cols.last + 1)
                            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
                            if (preview) {
                                // Grey beyond the print range, then "Page N" in the middle of each page's visible part.
                                drawRect(grey, size = androidx.compose.ui.geometry.Size(size.width, top.coerceIn(0f, size.height)))
                                drawRect(grey, topLeft = Offset(0f, bottom.coerceAtLeast(0f)), size = androidx.compose.ui.geometry.Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
                                val bandTop = top.coerceIn(0f, size.height); val bandH = (bottom.coerceAtMost(size.height) - bandTop).coerceAtLeast(0f)
                                drawRect(grey, topLeft = Offset(0f, bandTop), size = androidx.compose.ui.geometry.Size(left.coerceIn(0f, size.width), bandH))
                                drawRect(grey, topLeft = Offset(right.coerceAtLeast(0f), bandTop), size = androidx.compose.ui.geometry.Size((size.width - right).coerceAtLeast(0f), bandH))
                                pages.rowPages.forEachIndexed { i, pageRows ->
                                    if (pageRows.isEmpty()) return@forEachIndexed
                                    val t = maxOf(yOf(pageRows.first()), 0f); val b = minOf(yOf(pageRows.last() + 1), size.height)
                                    if (b - t < 24.dp.toPx()) return@forEachIndexed
                                    pages.colBands.forEachIndexed { j, band ->
                                        if (band.isEmpty()) return@forEachIndexed
                                        val l = maxOf(xOf(band.first()), 0f); val r = minOf(xOf(band.last() + 1), size.width)
                                        if (r - l < 48.dp.toPx()) return@forEachIndexed
                                        val text = spillMeasurer.measure(res.getString(R.string.page_number_label, pages.pageNumber(i, j, pr.overThenDown)), labelStyle)
                                        if (text.size.width > r - l || text.size.height > b - t) return@forEachIndexed
                                        drawText(text, topLeft = Offset((l + r - text.size.width) / 2, (t + b - text.size.height) / 2))
                                    }
                                }
                            }
                            val ink = if (preview) Color(0xFF2F64D0) else Color(0xFF7A7A7A)
                            val heavy = (if (preview) 2.5 else 1.0).dp.toPx(); val light = (if (preview) 1.5 else 1.0).dp.toPx()
                            val lineTop = top.coerceAtLeast(0f); val lineBottom = bottom.coerceAtMost(size.height)
                            val lineLeft = left.coerceAtLeast(0f); val lineRight = right.coerceAtMost(size.width)
                            for (r in pages.rowStarts) if (rowShown(r)) {
                                val manual = !pr.fits && r in pr.rowBreaks
                                drawLine(ink, Offset(lineLeft, yOf(r)), Offset(lineRight, yOf(r)), if (manual) heavy else light, pathEffect = if (manual) null else dash)
                            }
                            for (c in pages.colStarts) if (colShown(c)) {
                                val manual = !pr.fits && c in pr.colBreaks
                                drawLine(ink, Offset(xOf(c), lineTop), Offset(xOf(c), lineBottom), if (manual) heavy else light, pathEffect = if (manual) null else dash)
                            }
                            // The line being dragged, where it would go.
                            editor.edgeDrag?.let { d ->
                                val heavyDrag = 3.dp.toPx(); val dragInk = Color(0xFF1F4BA5)
                                if (d.rows && rowShown(d.to)) drawLine(dragInk, Offset(lineLeft, yOf(d.to)), Offset(lineRight, yOf(d.to)), heavyDrag)
                                if (!d.rows && colShown(d.to)) drawLine(dragInk, Offset(xOf(d.to), lineTop), Offset(xOf(d.to), lineBottom), heavyDrag)
                            }
                            if (preview) {
                                // The print range's frame, where its edges are on screen.
                                if (rowShown(pages.rows.first)) drawLine(ink, Offset(lineLeft, top), Offset(lineRight, top), heavy)
                                if (rowShown(pages.rows.last + 1)) drawLine(ink, Offset(lineLeft, bottom), Offset(lineRight, bottom), heavy)
                                if (colShown(pages.cols.first)) drawLine(ink, Offset(left, lineTop), Offset(left, lineBottom), heavy)
                                if (colShown(pages.cols.last + 1)) drawLine(ink, Offset(right, lineTop), Offset(right, lineBottom), heavy)
                            }
                        })
                        // Page Break Preview: each break and each edge of the print range can be dragged, as in Excel —
                        // a strip along the line, lit where it is looked at; the line goes to the nearest row or column edge.
                        if (preview && pages != null && rowEdges.isNotEmpty() && colEdges.isNotEmpty()) {
                            // Viewport positions, read as the strips are laid out and dragged (not while composing).
                            fun yAt(r: Int) = rowEdges[(r - rw.first).coerceIn(0, rowEdges.size - 1)] - v.value
                            fun xAt(c: Int) = colEdges[(c - cw.first).coerceIn(0, colEdges.size - 1)] - h.value
                            fun top() = (if (pages.rows.first < rw.first) 0f else yAt(pages.rows.first)).coerceAtLeast(0f)
                            fun bottom() = yAt(pages.rows.last + 1).coerceAtMost(bodyHpx.toFloat())
                            fun left() = (if (pages.cols.first < cw.first) 0f else xAt(pages.cols.first)).coerceAtLeast(0f)
                            fun right() = xAt(pages.cols.last + 1).coerceAtMost(bodyWpx.toFloat())
                            val grabPx = with(density) { 12.dp.toPx() }
                            // The row (or column) edge nearest a viewport position.
                            fun rowEdgeAt(y: Float): Int {
                                val r = drag.rowAt(y).coerceIn(fr, sheet.rowCount - 1)
                                val t = GridAxis.offsetOf(r, fr, hidden) { rowPx(it) } - v.value
                                return if (y - t < t + rowPx(r) - y) r else r + 1
                            }
                            fun colEdgeAt(x: Float): Int {
                                val c = drag.colAt(x).coerceIn(fc, sheet.colCount - 1)
                                val l = GridAxis.offsetOf(c, fc, hiddenCols) { colPx(it) } - h.value
                                return if (x - l < l + colPx(c) - x) c else c + 1
                            }
                            val breakLabel = stringResource(R.string.page_break)
                            val edgeLabel = stringResource(R.string.print_area)
                            val rowLines = (pages.rowStarts + pages.rows.first + (pages.rows.last + 1)).distinct()
                                .filter { it >= fr && it - rw.first in rowEdges.indices }
                            val colLines = (pages.colStarts + pages.cols.first + (pages.cols.last + 1)).distinct()
                                .filter { it >= fc && it - cw.first in colEdges.indices }
                            for (r in rowLines) androidx.compose.runtime.key("r", r) {
                                val from = androidx.compose.runtime.remember { FloatArray(1) }
                                PageEdgeHandle(
                                    place = { floatArrayOf(left(), yAt(r) - grabPx / 2, right() - left(), grabPx) },
                                    across = true,
                                    label = if (r == pages.rows.first || r == pages.rows.last + 1) edgeLabel else breakLabel,
                                    onStart = { from[0] = yAt(r); editor.edgeDrag = SheetEditor.EdgeDrag(true, r, r) },
                                    onMove = { gone -> editor.edgeDrag = SheetEditor.EdgeDrag(true, r, rowEdgeAt(from[0] + gone)) },
                                    onEnd = { editor.edgeDrag?.let { editor.edgeDrag = null; editor.movePageEdge(true, it.from, it.to) } },
                                    onCancel = { editor.edgeDrag = null })
                            }
                            for (c in colLines) androidx.compose.runtime.key("c", c) {
                                val from = androidx.compose.runtime.remember { FloatArray(1) }
                                PageEdgeHandle(
                                    place = { floatArrayOf(xAt(c) - grabPx / 2, top(), grabPx, bottom() - top()) },
                                    across = false,
                                    label = if (c == pages.cols.first || c == pages.cols.last + 1) edgeLabel else breakLabel,
                                    onStart = { from[0] = xAt(c); editor.edgeDrag = SheetEditor.EdgeDrag(false, c, c) },
                                    onMove = { gone -> editor.edgeDrag = SheetEditor.EdgeDrag(false, c, colEdgeAt(from[0] + gone)) },
                                    onEnd = { editor.edgeDrag?.let { editor.edgeDrag = null; editor.movePageEdge(false, it.from, it.to) } },
                                    onCancel = { editor.edgeDrag = null })
                            }
                        }
                    }
                    // The selection's frame over the body, with a grip on its first and last corner:
                    // dragging one moves that corner, the opposite one staying put.
                    val sr1 = editor.rangeRowMin(); val sr2 = editor.rangeRowMax()
                    val sc1 = editor.rangeColMin(); val sc2 = editor.rangeColMax()
                    if (sr2 >= fr && sc2 >= fc) {
                        val top = GridAxis.offsetOf(maxOf(sr1, fr), fr, hidden) { rowPx(it) }
                        val bottom = GridAxis.offsetOf(sr2 + 1, fr, hidden) { rowPx(it) }
                        val left = GridAxis.offsetOf(maxOf(sc1, fc), fc, hiddenCols) { colPx(it) }
                        val right = GridAxis.offsetOf(sc2 + 1, fc, hiddenCols) { colPx(it) }
                        val frameColor = LocalAccent.current
                        Box(Modifier.fillMaxSize().drawBehind {
                            val sx = h.value.toFloat(); val sy = v.value.toFloat()
                            drawRect(frameColor, topLeft = Offset(left - sx, top - sy), size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        })
                        val toViewport = { pos: Offset, grip: androidx.compose.ui.layout.LayoutCoordinates? ->
                            val vp = viewport[0]
                            if (vp != null && grip != null && vp.isAttached && grip.isAttached) vp.localPositionOf(grip, pos) else pos
                        }
                        // The first corner shows only where the block starts inside the body.
                        if (sr1 >= fr && sc1 >= fc) SelectionGrip(
                            stringResource(R.string.a11y_selection_start), { Offset(left - h.value, top - v.value) }, toViewport,
                            onStart = { p -> startGripDrag(drag, editor.rangeRowMax(), editor.rangeColMax(), p) },
                            onMove = { p -> drag.pointer = p; drag.apply(editor) }, onEnd = { endSelectDrag() },
                        )
                        SelectionGrip(
                            stringResource(R.string.a11y_selection_end), { Offset(right - h.value, bottom - v.value) }, toViewport,
                            onStart = { p -> startGripDrag(drag, editor.rangeRowMin(), editor.rangeColMin(), p) },
                            onMove = { p -> drag.pointer = p; drag.apply(editor) }, onEnd = { endSelectDrag() },
                        )
                        // Excel's fill handle on the bottom edge (whole columns have nowhere to fill down to).
                        if (!editor.wholeColumnsSelected) {
                            val inset = with(density) { 14.dp.toPx() }
                            FillHandle(
                                stringResource(R.string.a11y_fill_handle),
                                {
                                    val lo = maxOf(left - h.value, 0f) + inset
                                    val hi = minOf(right - h.value, bodyWpx.toFloat()) - inset
                                    val mid = (left + right) / 2 - h.value
                                    Offset(if (lo <= hi) mid.coerceIn(lo, hi) else mid, bottom - v.value)
                                },
                                toViewport,
                                onStart = { p -> drag.pointer = p; drag.fillTarget = null; drag.mode = SelectDrag.Mode.FILL },
                                onMove = { p -> drag.pointer = p; drag.apply(editor) },
                                onEnd = {
                                    drag.fillTarget?.let { (r, c) -> editor.autoFill(r, c) }
                                    drag.fillTarget = null; drag.mode = null
                                    runCatching { focus.requestFocus() }
                                },
                                onDoubleTap = { editor.autoFillToAdjacentData() },
                            )
                        }
                        // After a selection made with the pointer: what one does with it next, right above it
                        // (below it at the top of the sheet).
                        if (barFor == selectionKey() && drag.mode == null && editor.selectedImage == null) {
                            val gapPx = with(density) { 8.dp.toPx() }
                            val belowPx = with(density) { 18.dp.toPx() }
                            FloatingActionBar(stringResource(R.string.a11y_selection_actions), place = { size ->
                                val x = (left - h.value).coerceIn(4f, maxOf(4f, bodyWpx - size.width - 4f))
                                val above = top - v.value - size.height - gapPx
                                val y = if (above >= 4f) above else bottom - v.value + belowPx
                                IntOffset(x.roundToInt(), y.roundToInt())
                            }) {
                                RibbonMiniButton("✂", stringResource(R.string.cut), { cutCells(editor, clipboard) })
                                RibbonMiniButton("⧉", stringResource(R.string.copy), { copyCells(editor, clipboard) })
                                RibbonMiniButton("📋", stringResource(R.string.paste), { pasteCells(editor, clipboard) })
                                BarDivider()
                                if (editor.canPaste) RibbonMiniButton("123", stringResource(R.string.paste_values), { editor.pasteSpecial(SheetEditor.PasteWhat.VALUES) })
                                RibbonMiniButton("⌫", stringResource(R.string.clear_contents), editor::clearSelection)
                                RibbonMiniButton("x_filldown", stringResource(R.string.fill_down), editor::fillDown)
                                BarDivider()
                                Box {
                                    RibbonMiniButton("ic_lightning", stringResource(R.string.quick_analysis), { quickOpen = !quickOpen }, selected = quickOpen)
                                    if (quickOpen) AnchoredFlyout(onDismiss = { quickOpen = false }) {
                                        QuickAnalysisPanel(editor, quick, onDismiss = { quickOpen = false; barFor = null }, start = quickStart)
                                    }
                                }
                            }
                        }
                    }
                    // Excel's Paste Options button: just outside the bottom-right corner of what was just pasted, until the
                    // next edit; it pastes the same cells again another way (values, formulas, formatting, transposed…).
                    editor.pasteOptions?.let { mark ->
                        if (mark.r2 >= fr && mark.c2 >= fc && editor.selectedImage == null && editor.selectedShape == null) {
                            val pBottom = GridAxis.offsetOf(mark.r2 + 1, fr, hidden) { rowPx(it) }
                            val pRight = GridAxis.offsetOf(mark.c2 + 1, fc, hiddenCols) { colPx(it) }
                            val gapPx = with(density) { 4.dp.toPx() }
                            FloatingActionBar(stringResource(R.string.paste_options), place = { size ->
                                val x = pRight - h.value + gapPx; val y = pBottom - v.value + gapPx
                                if (x > bodyWpx || y > bodyHpx || x + size.width < 0f || y + size.height < 0f) return@FloatingActionBar null
                                // In the lower part of the grid its menu opens upward (worked out here, at layout: the scroll
                                // position changes all the time and is not read while composing).
                                val low = y > bodyHpx * 0.55f
                                if (low != pasteMenuUp) pasteMenuUp = low
                                IntOffset(x.coerceAtMost(bodyWpx - size.width - 4f).roundToInt(), y.coerceAtMost(bodyHpx - size.height - 4f).roundToInt())
                            }) {
                                Box {
                                    RibbonMiniButton("📋", stringResource(R.string.paste_options), { pasteMenu = !pasteMenu }, selected = pasteMenu, dropdown = true)
                                    if (pasteMenu) AnchoredFlyout(onDismiss = { pasteMenu = false }, above = pasteMenuUp) {
                                        PasteOptionsMenu(mark.kind) { kind -> pasteMenu = false; editor.repaste(kind) }
                                    }
                                }
                            }
                        }
                    }
                    // The picked picture's actions, above it (below it at the top of the sheet).
                    editor.selectedImage?.let { img ->
                        val (pl, pt) = pictureCorner(img)
                        val pb = pt + img.heightPt * pxPerPt; val pr = pl + img.widthPt * pxPerPt
                        val gapPx = with(density) { 12.dp.toPx() }
                        FloatingActionBar(stringResource(R.string.a11y_picture_actions), place = { size ->
                            // Out of view, the bar goes with it.
                            if (pr < h.value || pl > h.value + bodyWpx || pb < v.value || pt > v.value + bodyHpx) return@FloatingActionBar null
                            val x = (pl - h.value).coerceIn(4f, maxOf(4f, bodyWpx - size.width - 4f))
                            val above = pt - v.value - size.height - gapPx
                            val y = if (above >= 4f) above else (pb - v.value + gapPx).coerceAtMost(maxOf(4f, bodyHpx - size.height - 4f))
                            IntOffset(x.roundToInt(), y.roundToInt())
                        }) {
                            RibbonMiniButton("x_crop", stringResource(R.string.crop), { cropFor = img })
                            // A shadow on or off (Picture Styles' offset one).
                            RibbonMiniButton("阴影", stringResource(if (img.shadow == null) R.string.shape_shadow else R.string.no_shadow),
                                { editor.setImageShadow(img, if (img.shadow == null) com.therealityteam.aeropages.model.ShapeShadow.PRESETS[0] else null) }, selected = img.shadow != null)
                            RibbonMiniButton("x_resetsize", stringResource(R.string.reset_picture_size), { editor.resetImageSize(img) })
                            BarDivider()
                            RibbonMiniButton("⧉", stringResource(R.string.duplicate_picture), editor::duplicateSelectedImage)
                            RibbonMiniButton("🗑", stringResource(R.string.delete_picture), editor::deleteSelectedImage)
                        }
                    }
                    // The picked shape's actions: its text, fill and outline (a colour row opens under them), a copy, delete.
                    editor.selectedShape?.let { sh ->
                        val (pl, pt) = pictureCorner(sh)
                        val pb = pt + sh.heightPt * pxPerPt; val pr = pl + sh.widthPt * pxPerPt
                        val gapPx = with(density) { 12.dp.toPx() }
                        FloatingActionBar(stringResource(R.string.a11y_shape_actions), place = { size ->
                            if (pr < h.value || pl > h.value + bodyWpx || pb < v.value || pt > v.value + bodyHpx) return@FloatingActionBar null
                            val x = (pl - h.value).coerceIn(4f, maxOf(4f, bodyWpx - size.width - 4f))
                            val above = pt - v.value - size.height - gapPx
                            val y = if (above >= 4f) above else (pb - v.value + gapPx).coerceAtMost(maxOf(4f, bodyHpx - size.height - 4f))
                            IntOffset(x.roundToInt(), y.roundToInt())
                        }) {
                            val line = sh.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE
                            if (shapePalette == null) {
                                if (!line) RibbonMiniButton("ic_textbox", stringResource(R.string.edit_text), { shapeTextFor = sh })
                                if (!line) RibbonMiniButton("ic_fill", stringResource(R.string.shape_fill), { shapePalette = "fill" }, accent = sh.shape.fill?.let { Color(it) })
                                RibbonMiniButton("ic_outline", stringResource(R.string.shape_outline), { shapePalette = "outline" }, accent = sh.shape.border?.let { Color(it) })
                                BarDivider()
                                RibbonMiniButton("⧉", stringResource(R.string.duplicate_shape), editor::duplicateSelectedShape)
                                RibbonMiniButton("🗑", stringResource(R.string.delete_shape), editor::deleteSelectedShape)
                            } else {
                                val fill = shapePalette == "fill"
                                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                    // Excel's Shape Fill / Shape Outline: the Office palette, No Fill / No Outline as its "none".
                                    ColorPalette(if (fill) sh.shape.fill else sh.shape.border, { c ->
                                        if (fill) editor.setShapeFill(sh, c) else editor.setShapeOutline(sh, c); shapePalette = null
                                    }, noneLabel = stringResource(if (fill) R.string.shape_no_fill else R.string.shape_no_outline))
                                    // The outline's weight, dashes and — a line's — arrows, as Excel's Shape Outline menu has them.
                                    if (!fill) LineOptions(sh.shape,
                                        onWeight = { editor.setShapeOutlineWeight(sh, it); shapePalette = null },
                                        onDash = { editor.setShapeDash(sh, it); shapePalette = null },
                                        onArrows = if (sh.shape.hasEnds) { b, e -> editor.setShapeArrows(sh, b, e); shapePalette = null } else null)
                                }
                            }
                        }
                    }
                    // While the fill handle is dragged: the cells it will fill, and what the last one gets.
                    val target = drag.fillTarget
                    val fillRange = if (drag.mode == SelectDrag.Mode.FILL && target != null) editor.autoFillRange(target.first, target.second) else null
                    if (fillRange != null) {
                        val fTop = GridAxis.offsetOf(maxOf(fillRange[0], fr), fr, hidden) { rowPx(it) }
                        val fBottom = GridAxis.offsetOf(fillRange[2] + 1, fr, hidden) { rowPx(it) }
                        val fLeft = GridAxis.offsetOf(maxOf(fillRange[1], fc), fc, hiddenCols) { colPx(it) }
                        val fRight = GridAxis.offsetOf(fillRange[3] + 1, fc, hiddenCols) { colPx(it) }
                        val dashColor = LocalAccent.current
                        Box(Modifier.fillMaxSize().drawBehind {
                            val sx = h.value.toFloat(); val sy = v.value.toFloat()
                            drawRect(dashColor.copy(alpha = 0.08f), topLeft = Offset(fLeft - sx, fTop - sy), size = androidx.compose.ui.geometry.Size(fRight - fLeft, fBottom - fTop))
                            drawRect(dashColor, topLeft = Offset(fLeft - sx, fTop - sy), size = androidx.compose.ui.geometry.Size(fRight - fLeft, fBottom - fTop),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                        })
                        // The value the far end of the fill gets, beside it (Excel's AutoFill tip).
                        editor.autoFillPreview(target!!.first, target.second)?.takeIf { it.isNotEmpty() }?.let { tip ->
                            val down = fillRange[0] > editor.rangeRowMax()
                            val up = fillRange[2] < editor.rangeRowMin()
                            val gap = with(density) { 6.dp.toPx() }
                            Box(
                                Modifier.offset {
                                    val x = if (fillRange[1] < editor.rangeColMin()) fLeft else fRight + gap
                                    val y = when { down -> fBottom - with(density) { CELL_H.toPx() }; up -> fTop; else -> fBottom + gap }
                                    IntOffset((x - h.value).roundToInt(), (y - v.value).roundToInt())
                                }
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .background(PicoTheme.colorScheme.labelPrimary.copy(alpha = 0.86f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) { Text(tip, fontSize = 12.sp, color = Color.White, maxLines = 1) }
                        }
                    }
                }
            }
        }
        // Over the grid, at the active cell: a list rule's arrow just right of it (as Excel puts it, so
        // the value keeps the cell's width), and under it the list while open, else the input message.
        val rect = activeRect
        if (rect != null && rect.width > 0f && rect.height > 0f) {
            if (editor.hasListDropdown(editor.selRow, editor.selCol)) {
                val ref = "${Workbook.colLabel(editor.selCol)}${editor.selRow + 1}"
                ListArrow(rect, stringResource(R.string.a11y_list_dropdown, ref)) { if (listOpen) listOpen = false else openList() }
            }
            val choices = if (listOpen) editor.pickChoices() ?: emptyList() else emptyList()
            val message = editor.inputMessage
            if (choices.isNotEmpty()) {
                InCellList(choices, editor.display(editor.selRow, editor.selCol), listIndex, rect, gridHpx, onPick = { value ->
                    editor.pickFromList(value); listOpen = false; runCatching { focus.requestFocus() }
                })
            } else message?.let { (title, text) -> AtUiSize { InputMessageTip(title, text, rect, gridWpx) } }
            // The active cell's link: where it goes, and opening it — above the cell when its input message is below.
            val link = editor.activeLink
            val single = editor.rangeRowMin() == editor.rangeRowMax() && editor.rangeColMin() == editor.rangeColMax()
            if (link != null && choices.isEmpty() && single) AtUiSize { LinkTip(link, rect, gridWpx, above = message != null) { onOpenLink(link) } }
        }
    }
    shapeTextFor?.let { sh ->
        ShapeTextDialog(sh.shape.box.text, onDismiss = { shapeTextFor = null; runCatching { focus.requestFocus() } },
            onApply = { text -> editor.setShapeText(sh, text); shapeTextFor = null; runCatching { focus.requestFocus() } })
    }
    cropFor?.let { img ->
        // The whole picture as its frame shows it: the kept part's size grown back by the crop.
        val (fullW, fullH) = img.uncroppedSize(img.widthPt, img.heightPt)
        CropDialog(img.data, fullW / fullH.coerceAtLeast(1f), img,
            onCrop = { l, t, r, b -> editor.cropImage(img, l, t, r, b) }, onDismiss = { cropFor = null; runCatching { focus.requestFocus() } })
    }
}

/**
 * A list rule's drop-down under its cell (above it when there is no room below), as wide as the
 * cell or its longest value: eight values show and the rest scroll; the cell's value is ticked and
 * the one the arrow keys are on is lit.
 */
@Composable
private fun InCellList(choices: List<String>, current: String, highlighted: Int, anchor: androidx.compose.ui.geometry.Rect, gridHeightPx: Int, onPick: (String) -> Unit) {
    val density = LocalDensity.current
    val rowPx = with(density) { 30.dp.toPx() }
    val listPx = rowPx * minOf(choices.size, 8).coerceAtLeast(1) + with(density) { 8.dp.toPx() }
    val below = anchor.bottom + listPx <= gridHeightPx || anchor.top < listPx
    val scroll = rememberScrollState()
    // Keep the lit value in view as the arrow keys move it.
    LaunchedEffect(highlighted) { if (highlighted >= 0) scroll.animateScrollTo(((highlighted - 3) * rowPx).roundToInt().coerceAtLeast(0)) }
    Column(
        Modifier.offset { IntOffset(anchor.left.roundToInt(), (if (below) anchor.bottom + 2f else anchor.top - listPx - 2f).roundToInt()) }
            .let { m -> val minW = with(density) { anchor.width.toDp() }.coerceIn(120.dp, 480.dp); m.widthIn(min = minW, max = maxOf(minW, 320.dp)) }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, Color(0xFFBDBDBD)), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .heightIn(max = with(density) { (rowPx * 8).toDp() } + 8.dp)
            .verticalScroll(scroll)
            .padding(vertical = 4.dp),
    ) {
        choices.forEachIndexed { i, value ->
            val on = value.equals(current, ignoreCase = true)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(30.dp)
                    .background(if (i == highlighted) PicoTheme.colorScheme.interaction.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onPick(value) }.padding(horizontal = 10.dp),
            ) {
                Box(Modifier.width(20.dp)) { if (on) RibbonGlyph("ic_check", PicoTheme.colorScheme.interaction, 14.dp) }
                Text(value, fontSize = 13.sp, color = Color(0xFF1A1A1A), maxLines = 1)
            }
        }
    }
}

/** A list rule's drop-down arrow, just right of its cell and as tall as it. */
@Composable
private fun ListArrow(anchor: androidx.compose.ui.geometry.Rect, description: String, onClick: () -> Unit) {
    val density = LocalDensity.current
    val side = with(density) { anchor.height.toDp() }.coerceIn(18.dp, 28.dp)
    val ink = PicoTheme.colorScheme.labelSecondary
    Box(
        Modifier.offset { IntOffset(anchor.right.roundToInt(), (anchor.top + (anchor.height - side.toPx()) / 2f).roundToInt()) }
            .size(side)
            .background(Color(0xFFF1F1F1))
            .border(BorderStroke(0.5.dp, Color(0xFFBDBDBD)))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(side * 0.3f)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, size.height * 0.25f); lineTo(size.width, size.height * 0.25f); lineTo(size.width / 2f, size.height * 0.8f); close()
            }
            drawPath(path, ink)
        }
    }
}

/**
 * The selected cell's link, as Excel's tip over one says where it goes: the place in this workbook
 * (`Sheet2!A1`) or the address, and an arrow that follows it — under the cell, or [above] it.
 */
@Composable
private fun LinkTip(link: String, anchor: androidx.compose.ui.geometry.Rect, gridWidthPx: Int, above: Boolean, onOpen: () -> Unit) {
    val density = LocalDensity.current
    val heightPx = with(density) { 38.dp.toPx() }
    val maxW = 300.dp
    val place = com.therealityteam.aeropages.model.CellLinks.place(link)
    Row(
        Modifier.offset {
            val x = anchor.left.coerceAtMost(gridWidthPx - with(density) { maxW.toPx() } - 4f).coerceAtLeast(4f)
            val y = if (above) (anchor.top - heightPx - 4f).coerceAtLeast(0f) else anchor.bottom + 4f
            IntOffset(x.roundToInt(), y.roundToInt())
        }
            .widthIn(max = maxW)
            .barSurface()
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        RibbonGlyph(if (place != null) "ic_grid" else "ic_link", Aero.link, 16.dp)
        Text(place ?: link, style = AeroType.command, color = Aero.link, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        RibbonMiniButton("x_openlink", stringResource(R.string.open_link), onOpen)
    }
}

/** A rule's input message beside its selected cell, as Excel shows one: a pale note with the title in bold. */
@Composable
private fun InputMessageTip(title: String, text: String, anchor: androidx.compose.ui.geometry.Rect, gridWidthPx: Int) {
    val density = LocalDensity.current
    val widthPx = with(density) { 240.dp.toPx() }
    Column(
        Modifier.offset {
            val x = (anchor.left + anchor.width / 2f).coerceAtMost(gridWidthPx - widthPx - 4f).coerceAtLeast(4f)
            IntOffset(x.roundToInt(), (anchor.bottom + 6f).roundToInt())
        }
            .width(240.dp)
            .background(Color(0xFFFFFDE7), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .border(BorderStroke(0.5.dp, Color(0xFF9E9E9E)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (title.isNotEmpty()) Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
        if (text.isNotEmpty()) Text(text, fontSize = 12.sp, color = Color(0xFF333333))
    }
}

/**
 * The grab strip on a header's edge: drag it to resize ([acrossX]: sideways for a column, else up
 * and down for a row), double-tap to fit the content. It lights up while pointed at or dragged.
 * The callbacks are read fresh on every event, so a header that now shows another column still
 * resizes the right one.
 */
@Composable
private fun EdgeHandle(
    acrossX: Boolean, modifier: Modifier,
    onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, onDoubleTap: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val drag by androidx.compose.runtime.rememberUpdatedState(onDrag)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val fit by androidx.compose.runtime.rememberUpdatedState(onDoubleTap)
    // The handle moves with the edge it drags, so pointer travel is measured in root coordinates
    // (local deltas would shrink by the handle's own movement and the edge would lag behind).
    // The drag only starts past the touch slop (large on PICO's ray input); measuring from where the
    // press began makes the edge jump to the pointer and then follow it exactly, as in Excel.
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val pressedAt = remember { floatArrayOf(Float.NaN) }
    fun rootOf(p: androidx.compose.ui.geometry.Offset): Float {
        val c = placed[0]
        val r = if (c != null && c.isAttached) c.localToRoot(p) else p
        return if (acrossX) r.x else r.y
    }
    Box(
        modifier
            .hoverable(interaction)
            .onGloballyPositioned { placed[0] = it }
            .pointerInput(Unit) {
                awaitEachGesture { pressedAt[0] = rootOf(awaitFirstDown(requireUnconsumed = false).position) }
            }
            .pointerInput(acrossX) {
                var last = 0f
                val begin = { o: androidx.compose.ui.geometry.Offset ->
                    last = pressedAt[0].takeUnless { it.isNaN() } ?: rootOf(o)
                    dragging = true; start()
                }
                val finish = { dragging = false; end() }
                val move = { change: androidx.compose.ui.input.pointer.PointerInputChange ->
                    change.consume()
                    val now = rootOf(change.position)
                    drag(now - last); last = now
                }
                if (acrossX) detectHorizontalDragGestures(onDragStart = begin, onDragEnd = finish, onDragCancel = finish) { change, _ -> move(change) }
                else detectVerticalDragGestures(onDragStart = begin, onDragEnd = finish, onDragCancel = finish) { change, _ -> move(change) }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { fit() }) },
        contentAlignment = if (acrossX) Alignment.CenterEnd else Alignment.BottomCenter,
    ) {
        if (hovered || dragging) Box(
            (if (acrossX) Modifier.width(2.dp).fillMaxHeight() else Modifier.height(2.dp).fillMaxWidth())
                .background(LocalAccent.current),
        )
    }
}

/**
 * A selection being dragged with the pointer: what it selects, the corner that stays put (the
 * active cell) and the pointer in body-viewport px. [rowAt] / [colAt] map viewport px to rows and
 * columns with the grid's current geometry and scroll; the grid refreshes them on every composition.
 */
private class SelectDrag {
    enum class Mode { CELLS, COLUMNS, ROWS, FILL }
    var mode by mutableStateOf<Mode?>(null)
    var anchorRow = 0
    var anchorCol = 0
    var pointer = Offset.Zero
    var rowAt: (Float) -> Int = { -1 }
    var colAt: (Float) -> Int = { -1 }
    /** While the fill handle is dragged: the cell under the pointer (the fill reaches its row or column). */
    var fillTarget by mutableStateOf<Pair<Int, Int>?>(null)

    /** Selects from the anchor to whatever lies under the pointer (or, filling, notes the cell under it). */
    fun apply(editor: SheetEditor) {
        when (mode) {
            Mode.FILL -> {
                val r = rowAt(pointer.y)
                val c = colAt(pointer.x)
                if (r >= 0 && c >= 0 && fillTarget != (r to c)) fillTarget = r to c
            }
            Mode.CELLS -> {
                val r = rowAt(pointer.y)
                val c = colAt(pointer.x)
                if (r >= 0 && c >= 0) editor.selectRange(anchorRow, anchorCol, r, c)
            }
            Mode.COLUMNS -> colAt(pointer.x).let { if (it >= 0) editor.selectColumns(anchorCol, it) }
            Mode.ROWS -> rowAt(pointer.y).let { if (it >= 0) editor.selectRows(anchorRow, it) }
            null -> {}
        }
    }
}

/** A grip drag moves one corner of the block; the opposite corner ([anchorRow], [anchorCol]) stays put and becomes the active cell. */
private fun startGripDrag(drag: SelectDrag, anchorRow: Int, anchorCol: Int, pointer: Offset) {
    drag.anchorRow = anchorRow
    drag.anchorCol = anchorCol
    drag.pointer = pointer
    drag.mode = SelectDrag.Mode.CELLS
}

private val GRIP_HIT = 32.dp
private val GRIP_DOT = 12.dp

/**
 * A round grip on a corner of the selection, centred on [center] (body-viewport px, read at layout
 * time so scrolling moves it without recomposing). It follows the pointer from the press on: no
 * touch slop, since nothing else competes for a drag that starts on it. [toViewport] turns the
 * grip's own coordinates into the body viewport's.
 */
@Composable
private fun SelectionGrip(
    description: String, center: () -> Offset,
    toViewport: (Offset, androidx.compose.ui.layout.LayoutCoordinates?) -> Offset,
    onStart: (Offset) -> Unit, onMove: (Offset) -> Unit, onEnd: () -> Unit,
) {
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val convert by androidx.compose.runtime.rememberUpdatedState(toViewport)
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val hitPx = with(LocalDensity.current) { GRIP_HIT.toPx() }
    Box(
        Modifier
            .offset { val c = center(); IntOffset((c.x - hitPx / 2).roundToInt(), (c.y - hitPx / 2).roundToInt()) }
            .size(GRIP_HIT)
            .onGloballyPositioned { placed[0] = it }
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    start(convert(down.position, placed[0]))
                    try {
                        drag(down.id) { change -> change.consume(); move(convert(change.position, placed[0])) }
                    } finally {
                        end()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(GRIP_DOT).background(Color.White, CircleShape).border(2.dp, LocalAccent.current, CircleShape))
    }
}

private val FILL_HIT = 28.dp

/**
 * Excel's fill handle: a small square on the selection's bottom edge, centred on the part of it in
 * view ([center], body-viewport px). Dragging it fills the block down, up or sideways ([onMove]
 * reports the pointer, [onEnd] fills); a double tap fills down beside the adjacent data. It grows a
 * little while pointed at.
 */
@Composable
private fun FillHandle(
    description: String, center: () -> Offset,
    toViewport: (Offset, androidx.compose.ui.layout.LayoutCoordinates?) -> Offset,
    onStart: (Offset) -> Unit, onMove: (Offset) -> Unit, onEnd: () -> Unit, onDoubleTap: () -> Unit,
) {
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val convert by androidx.compose.runtime.rememberUpdatedState(toViewport)
    val doubleTap by androidx.compose.runtime.rememberUpdatedState(onDoubleTap)
    val placed = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hitPx = with(LocalDensity.current) { FILL_HIT.toPx() }
    Box(
        Modifier
            .offset { val c = center(); IntOffset((c.x - hitPx / 2).roundToInt(), (c.y - hitPx / 2).roundToInt()) }
            .size(FILL_HIT)
            .hoverable(interaction)
            .onGloballyPositioned { placed[0] = it }
            .semantics { contentDescription = description }
            // The press is left unconsumed so the double-tap detector below sees it; moves are the drag's.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    start(convert(down.position, placed[0]))
                    try {
                        drag(down.id) { change -> change.consume(); move(convert(change.position, placed[0])) }
                    } finally {
                        end()
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (hovered) 12.dp else 9.dp).background(LocalAccent.current)
                .border(1.5.dp, Color.White),
        )
    }
}

/** Width of one outline lane in the row-group gutter. */
private val OUTLINE_W = 16.dp

/**
 * The outline gutter beside row [r]: a bracket line in each level's lane for the expanded groups
 * that contain the row, and the group's −/+ toggle on its summary row (the row just below it, or
 * its own last row at the end of the sheet).
 */
@Composable
private fun OutlineGutter(editor: SheetEditor, r: Int, height: androidx.compose.ui.unit.Dp, levels: Int) {
    val sheet = editor.sheet
    Box(Modifier.width(OUTLINE_W * levels).height(height).background(PicoTheme.colorScheme.fillLight)) {
        for (g in editor.rowGroups) {
            val lane = OUTLINE_W * (sheet.levelOf(g) - 1)
            val summary = if (g.r2 + 1 < sheet.rowCount) g.r2 + 1 else g.r2
            if (r == summary) {
                val label = stringResource(if (g.collapsed) R.string.outline_expand else R.string.outline_collapse, g.r1 + 1, g.r2 + 1)
                Box(
                    Modifier.offset(x = lane + 2.dp).align(Alignment.CenterStart).size(12.dp)
                        .border(BorderStroke(1.dp, PicoTheme.colorScheme.labelTertiary), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .semantics { contentDescription = label }
                        .clickable { editor.toggleRowGroup(g) },
                    contentAlignment = Alignment.Center,
                ) { Text(if (g.collapsed) "+" else "−", fontSize = 9.sp, color = PicoTheme.colorScheme.labelSecondary) }
            } else if (!g.collapsed && r in g.r1..g.r2) {
                // Bracket: a vertical line down the lane, with a tick at the group's first row.
                Box(Modifier.offset(x = lane + 7.dp).width(1.dp).fillMaxHeight().background(PicoTheme.colorScheme.labelTertiary))
                if (r == g.r1) Box(Modifier.offset(x = lane + 7.dp).width(5.dp).height(1.dp).background(PicoTheme.colorScheme.labelTertiary))
            }
        }
    }
}

/**
 * The column outline band over column [c]: in each level's lane a bracket across the expanded
 * groups that contain the column, and a group's −/+ toggle over its summary column (the column
 * just right of it, or its own last column at the sheet's edge).
 */
@Composable
private fun ColOutlineCell(editor: SheetEditor, c: Int, width: androidx.compose.ui.unit.Dp, levels: Int) {
    val sheet = editor.sheet
    Box(Modifier.width(width).height(OUTLINE_W * levels).background(PicoTheme.colorScheme.fillLight)) {
        for (g in editor.colGroups) {
            val lane = OUTLINE_W * (sheet.levelOfCol(g) - 1)
            val summary = if (g.r2 + 1 < sheet.colCount) g.r2 + 1 else g.r2
            if (c == summary) {
                val label = stringResource(if (g.collapsed) R.string.outline_expand_cols else R.string.outline_collapse_cols,
                    Workbook.colLabel(g.r1), Workbook.colLabel(g.r2))
                Box(
                    Modifier.offset(y = lane + 2.dp).align(Alignment.TopCenter).size(12.dp)
                        .border(BorderStroke(1.dp, PicoTheme.colorScheme.labelTertiary), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .semantics { contentDescription = label }
                        .clickable { editor.toggleColGroup(g) },
                    contentAlignment = Alignment.Center,
                ) { Text(if (g.collapsed) "+" else "−", fontSize = 9.sp, color = PicoTheme.colorScheme.labelSecondary) }
            } else if (!g.collapsed && c in g.r1..g.r2) {
                // Bracket: a line along the lane, with a tick down at the group's first column.
                Box(Modifier.offset(y = lane + 7.dp).height(1.dp).fillMaxWidth().background(PicoTheme.colorScheme.labelTertiary))
                if (c == g.r1) Box(Modifier.offset(y = lane + 7.dp).width(1.dp).height(5.dp).background(PicoTheme.colorScheme.labelTertiary))
            }
        }
    }
}

/** The column outline's level buttons, stacked in the corner beside the band (one per lane, as in Excel). */
@Composable
private fun ColOutlineLevelButtons(editor: SheetEditor, levels: Int, width: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(width).height(OUTLINE_W * levels).background(PicoTheme.colorScheme.fillLight), horizontalAlignment = Alignment.End) {
        for (n in 1..levels) {
            val label = stringResource(R.string.outline_level_cols, n)
            Box(
                Modifier.size(OUTLINE_W).semantics { contentDescription = label }.clickable { editor.showColOutlineLevel(n) },
                contentAlignment = Alignment.Center,
            ) { Text("$n", fontSize = 9.sp, color = PicoTheme.colorScheme.labelSecondary) }
        }
    }
}

/** Excel's outline level buttons (1, 2, …) above the gutter: level n shows the outline down to that level. */
@Composable
private fun OutlineLevelButtons(editor: SheetEditor, levels: Int) {
    Row(Modifier.width(OUTLINE_W * levels).height(CELL_H).background(PicoTheme.colorScheme.fillLight), verticalAlignment = Alignment.CenterVertically) {
        for (n in 1..levels) {
            val label = stringResource(R.string.outline_level, n)
            Box(
                Modifier.width(OUTLINE_W).fillMaxHeight().semantics { contentDescription = label }.clickable { editor.showOutlineLevel(n) },
                contentAlignment = Alignment.Center,
            ) { Text("$n", fontSize = 9.sp, color = PicoTheme.colorScheme.labelSecondary) }
        }
    }
}

// --- the cells' clipboard: in the app with formats and formulas, and as tab-separated text so the
// --- selection also pastes into Pages, Slides and other apps (Ctrl+C/X/V, the ribbon, the context bar).

internal fun copyCells(editor: SheetEditor, clipboard: TextClipboard?) {
    // A picked picture or shape is what is copied — for any module's paste — not the cells under it.
    if (editor.copySelectedObject() != null) { clipboard?.copyCurrentObject(); return }
    editor.copySelection()
    clipboard?.copy(editor.selectionTsv())
}

internal fun cutCells(editor: SheetEditor, clipboard: TextClipboard?) {
    if (editor.hasObjectSelected) { if (editor.cutSelectedObject() != null) clipboard?.copyCurrentObject(); return }
    clipboard?.copy(editor.selectionTsv())
    editor.cutSelection()
}

/**
 * What was copied last: an object (a picture, a shape, a chart, a slide's table — from any module)
 * at the active cell, else the app's own cells (richer), else tab-separated or plain text from elsewhere.
 */
internal fun pasteCells(editor: SheetEditor, clipboard: TextClipboard?) {
    if (clipboard == null) { if (editor.canPaste) editor.pasteSelection(); return }
    clipboard.pasteObjectOrText(onObject = { editor.pasteItem(it) }, onText = { text ->
        if (editor.canPaste) editor.pasteSelection() else if (text.isNotBlank()) editor.pasteTsv(text)
    })
}

/**
 * Grid keyboard: arrows navigate, Shift+arrows extend, Ctrl+C/X/V/Z/Y/F/A/D/R, Delete clears; a
 * printable key starts editing the cell with that character (Excel's type-to-edit), Enter/F2
 * edit the existing content — both via [onStartEdit].
 */
internal fun handleGridKey(editor: SheetEditor, e: androidx.compose.ui.input.key.KeyEvent, clipboard: TextClipboard? = null, onFind: () -> Unit = {}, onStartEdit: (String?) -> Unit = {}): Boolean {
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    // Format Painter taken up: Esc puts it down; Enter paints the selection (the keyboard's way to it).
    if (editor.painterArmed) when (e.key) {
        androidx.compose.ui.input.key.Key.Escape -> { editor.cancelPainter(); return true }
        androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter -> { editor.paintFormats(); return true }
        else -> {}
    }
    // A picked picture takes the keys, as in Excel: arrows nudge it a point, Delete removes it,
    // Ctrl+D duplicates it, Esc goes back to the cells; typing does not reach the cell under it.
    // A picked shape likewise; Enter or F2 edits its text.
    if (editor.selectedShape != null && !(ctrl && (e.key == androidx.compose.ui.input.key.Key.Z || e.key == androidx.compose.ui.input.key.Key.Y))) {
        when (e.key) {
            androidx.compose.ui.input.key.Key.Delete, androidx.compose.ui.input.key.Key.Backspace -> editor.deleteSelectedShape()
            androidx.compose.ui.input.key.Key.Escape -> editor.selectShape(null)
            androidx.compose.ui.input.key.Key.DirectionUp -> editor.nudgeSelectedShape(0f, -1f)
            androidx.compose.ui.input.key.Key.DirectionDown -> editor.nudgeSelectedShape(0f, 1f)
            androidx.compose.ui.input.key.Key.DirectionLeft -> editor.nudgeSelectedShape(-1f, 0f)
            androidx.compose.ui.input.key.Key.DirectionRight -> editor.nudgeSelectedShape(1f, 0f)
            androidx.compose.ui.input.key.Key.D -> if (ctrl) editor.duplicateSelectedShape()
            androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.F2 -> editor.requestShapeText()
            else -> {}
        }
        return true
    }
    if (editor.selectedImage != null && !(ctrl && (e.key == androidx.compose.ui.input.key.Key.Z || e.key == androidx.compose.ui.input.key.Key.Y))) {
        when (e.key) {
            androidx.compose.ui.input.key.Key.Delete, androidx.compose.ui.input.key.Key.Backspace -> editor.deleteSelectedImage()
            androidx.compose.ui.input.key.Key.Escape -> editor.selectImage(null)
            androidx.compose.ui.input.key.Key.DirectionUp -> editor.nudgeSelectedImage(0f, -1f)
            androidx.compose.ui.input.key.Key.DirectionDown -> editor.nudgeSelectedImage(0f, 1f)
            androidx.compose.ui.input.key.Key.DirectionLeft -> editor.nudgeSelectedImage(-1f, 0f)
            androidx.compose.ui.input.key.Key.DirectionRight -> editor.nudgeSelectedImage(1f, 0f)
            androidx.compose.ui.input.key.Key.D -> if (ctrl) editor.duplicateSelectedImage()
            else -> {}
        }
        return true
    }
    when {
        e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter || e.key == androidx.compose.ui.input.key.Key.F2 -> { onStartEdit(null); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.L -> { editor.toggleAutoFilter(); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.Z -> { editor.undo(); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.Y -> { editor.redo(); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.F -> { onFind(); return true }
        // Excel's Ctrl+D / Ctrl+R: fill down / right from the block's first row / column; Ctrl+A: the whole sheet.
        ctrl && e.key == androidx.compose.ui.input.key.Key.D -> { editor.fillDown(); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.R -> { editor.fillRight(); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.A -> { editor.selectAll(); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.X -> { cutCells(editor, clipboard); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.C -> { copyCells(editor, clipboard); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.V -> { pasteCells(editor, clipboard); return true }
        e.key == androidx.compose.ui.input.key.Key.Delete || e.key == androidx.compose.ui.input.key.Key.Backspace -> { editor.clearSelection(); return true }
        // Excel's Ctrl+arrows: to the edge of the data (with Shift, the selection to there); Home, Ctrl+Home, Ctrl+End.
        ctrl && e.key == androidx.compose.ui.input.key.Key.DirectionUp -> { editor.jumpToEdge(-1, 0, shift); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.DirectionDown -> { editor.jumpToEdge(1, 0, shift); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.DirectionLeft -> { editor.jumpToEdge(0, -1, shift); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.DirectionRight -> { editor.jumpToEdge(0, 1, shift); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.MoveHome -> { editor.goHome(sheetStart = true, extend = shift); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.MoveEnd -> { editor.goEnd(shift); return true }
        e.key == androidx.compose.ui.input.key.Key.MoveHome -> { editor.goHome(sheetStart = false, extend = shift); return true }
        e.key == androidx.compose.ui.input.key.Key.PageDown -> { if (shift) editor.extendSelection(PAGE_ROWS, 0) else editor.moveSelection(PAGE_ROWS, 0); return true }
        e.key == androidx.compose.ui.input.key.Key.PageUp -> { if (shift) editor.extendSelection(-PAGE_ROWS, 0) else editor.moveSelection(-PAGE_ROWS, 0); return true }
        e.key == androidx.compose.ui.input.key.Key.Tab -> { editor.moveSelection(0, if (shift) -1 else 1); return true }
        // Ctrl+Space: the selection's whole columns; Shift+Space: its whole rows.
        ctrl && e.key == androidx.compose.ui.input.key.Key.Spacebar -> { editor.selectColumns(editor.rangeColMin(), editor.rangeColMax()); return true }
        shift && e.key == androidx.compose.ui.input.key.Key.Spacebar -> { editor.selectRows(editor.rangeRowMin(), editor.rangeRowMax()); return true }
        // Formatting: Ctrl+B / I / U / 5 (strikethrough); Ctrl+Shift+$ % # @ ! ^ ~ the number formats.
        ctrl && !shift && e.key == androidx.compose.ui.input.key.Key.B -> { editor.toggleBold(); return true }
        ctrl && !shift && e.key == androidx.compose.ui.input.key.Key.I -> { editor.toggleItalic(); return true }
        ctrl && !shift && e.key == androidx.compose.ui.input.key.Key.U -> { editor.toggleUnderline(); return true }
        ctrl && !shift && e.key == androidx.compose.ui.input.key.Key.Five -> { editor.toggleStrikethrough(); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.Four -> { applyLocaleCurrency(editor); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.Five -> { editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.PERCENT); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.Three -> { editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.DATE); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.Two -> { editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.TIME); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.One -> { editor.setFormatCode("#,##0.00"); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.Six -> { editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.SCIENTIFIC); return true }
        ctrl && shift && e.key == androidx.compose.ui.input.key.Key.Grave -> { editor.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.GENERAL); return true }
        // Ctrl+; today's date, Ctrl+Shift+; the time; Ctrl+9 / Ctrl+0 hide rows / columns (with Shift, show them).
        ctrl && e.key == androidx.compose.ui.input.key.Key.Semicolon -> { editor.enterNow(time = shift); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.Nine -> { if (shift) editor.unhideRows() else editor.hideRows(); return true }
        ctrl && e.key == androidx.compose.ui.input.key.Key.Zero -> { if (shift) editor.unhideColumns() else editor.hideColumns(); return true }
        e.key == androidx.compose.ui.input.key.Key.DirectionUp -> { if (shift) editor.extendSelection(-1, 0) else editor.moveSelection(-1, 0); return true }
        e.key == androidx.compose.ui.input.key.Key.DirectionDown -> { if (shift) editor.extendSelection(1, 0) else editor.moveSelection(1, 0); return true }
        e.key == androidx.compose.ui.input.key.Key.DirectionLeft -> { if (shift) editor.extendSelection(0, -1) else editor.moveSelection(0, -1); return true }
        e.key == androidx.compose.ui.input.key.Key.DirectionRight -> { if (shift) editor.extendSelection(0, 1) else editor.moveSelection(0, 1); return true }
        !ctrl && !e.isAltPressed && e.utf16CodePoint >= 32 && e.utf16CodePoint != 127 -> {
            onStartEdit(String(Character.toChars(e.utf16CodePoint))); return true
        }
    }
    return false
}

@Composable
internal fun CornerCell(onClick: (() -> Unit)? = null) {
    val label = stringResource(R.string.a11y_select_all_cells)
    Box(
        Modifier.size(ROWHDR_W, CELL_H).background(GridColors.header).border(BorderStroke(0.5.dp, GridColors.headerLine))
            .then(if (onClick != null) Modifier.semantics { contentDescription = label }.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.BottomEnd,
    ) {
        // Excel's select-all triangle in the corner.
        if (onClick != null) androidx.compose.foundation.Canvas(Modifier.size(10.dp).padding(2.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(path, Color(0xFFB0B0B0))
        }
    }
}

/**
 * A column's or row's header as Excel 365 draws it: light grey with a hairline; under the selection a darker
 * grey, the label in the accent and an accent line along the edge that meets the cells ([column]: its foot,
 * else its right side).
 */
@Composable
internal fun HeaderCell(
    label: String, w: androidx.compose.ui.unit.Dp, hgt: androidx.compose.ui.unit.Dp = CELL_H,
    selected: Boolean = false, description: String? = null, onClick: (() -> Unit)? = null, column: Boolean = true,
) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(w, hgt)
            .background(if (selected) GridColors.headerSelected else GridColors.header)
            .drawBehind {
                val hair = 1.dp.toPx() / 2f
                drawLine(GridColors.headerLine, Offset(size.width - hair, 0f), Offset(size.width - hair, size.height), hair * 2)
                drawLine(GridColors.headerLine, Offset(0f, size.height - hair), Offset(size.width, size.height - hair), hair * 2)
                if (selected) {
                    val t = 2.dp.toPx()
                    if (column) drawRect(accent, Offset(0f, size.height - t), androidx.compose.ui.geometry.Size(size.width, t))
                    else drawRect(accent, Offset(size.width - t, 0f), androidx.compose.ui.geometry.Size(t, size.height))
                }
            }
            .then(if (onClick != null) Modifier.semantics { if (description != null) contentDescription = description }.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
            color = if (selected) accent else GridColors.headerInk)
    }
}

/** The grid's own colours (a white sheet, as Excel's): headers, their lines, a selected block's shading. */
internal object GridColors {
    val header = Color(0xFFF3F3F3) // design-style: grid header
    val headerSelected = Color(0xFFE1E1E1) // design-style: selected header
    val headerLine = Color(0xFFD4D4D4) // design-style: header hairline
    val headerInk = Color(0xFF616161) // design-style: header label
    val range = Color(0x14000000) // design-style: selected block shading (8 % black)
}

/** What a cell's sparkline draws: its shape, colour and the numbers behind it. */
internal class SparkDraw(val type: com.therealityteam.aeropages.model.SparklineType, val color: Long, val values: List<Double>, val markers: Boolean)

@Composable
internal fun GridCell(text: String, cell: com.therealityteam.aeropages.model.Cell?, active: Boolean, inRange: Boolean, cond: com.therealityteam.aeropages.editor.CondEval.Look?, modifier: Modifier = Modifier, cellW: androidx.compose.ui.unit.Dp = CELL_W, cellH: androidx.compose.ui.unit.Dp = CELL_H, gridlines: Boolean = true, sparkline: SparkDraw? = null, textColor: Long? = null, align: com.therealityteam.aeropages.model.TextAlign? = null,
    spill: androidx.compose.ui.unit.Dp = 0.dp, covered: Boolean = false, coverEnd: Boolean = false, filter: FilterButton? = null,
    circled: Boolean = false, label: String? = null, spillLeft: androidx.compose.ui.unit.Dp = 0.dp, coveredLeft: Boolean = false,
    across: Boolean = false, overlay: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val format = cell?.format
    // [align] is where the value sits (the cell's own alignment, or General's for its value).
    val horiz = align ?: cell?.align
    val vert = cell?.vAlign ?: com.therealityteam.aeropages.model.VAlign.MIDDLE
    val alignment = run {
        val top = vert == com.therealityteam.aeropages.model.VAlign.TOP
        val bottom = vert == com.therealityteam.aeropages.model.VAlign.BOTTOM
        when (horiz) {
            com.therealityteam.aeropages.model.TextAlign.CENTER -> if (top) Alignment.TopCenter else if (bottom) Alignment.BottomCenter else Alignment.Center
            com.therealityteam.aeropages.model.TextAlign.RIGHT -> if (top) Alignment.TopEnd else if (bottom) Alignment.BottomEnd else Alignment.CenterEnd
            else -> if (top) Alignment.TopStart else if (bottom) Alignment.BottomStart else Alignment.CenterStart
        }
    }
    // An empty cell under a neighbour's long text lets it show through (the grid behind is white).
    val condStyle = cond?.style
    val bg = condStyle?.fill?.let { Color(it) }
        ?: format?.highlight?.let { Color(it) }
        ?: if (inRange && !active) GridColors.range else if (covered) Color.Transparent else Color.White
    val borderColor = PicoTheme.colorScheme.labelPrimary
    val lineColor = PicoTheme.colorScheme.dividerLine
    // Text running on to the right has no gridline across it, as in Excel.
    val openRight = spill > 0.dp || (covered && !coverEnd) || coveredLeft
    Box(
        modifier = modifier.size(cellW, cellH)
            // Circle Invalid Data's red ring, over everything the cell draws.
            .then(if (circled) Modifier.drawWithContent {
                drawContent()
                val inset = 1.dp.toPx()
                drawOval(Color(0xFFE02020), topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            } else Modifier)
            // Cell borders go over the gridlines and the cell's content, each stroke inside the cell.
            .then(if (cell != null && (cell.bTop || cell.bBottom || cell.bLeft || cell.bRight)) Modifier.drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                fun inset(line: com.therealityteam.aeropages.model.BorderLine) = line.style.width.dp.toPx() / 2f
                cell.borderTop?.let { val d = inset(it); drawBorderLine(it, androidx.compose.ui.geometry.Offset(0f, d), androidx.compose.ui.geometry.Offset(w, d), androidx.compose.ui.geometry.Offset(0f, 1f), borderColor) }
                cell.borderBottom?.let { val d = inset(it); drawBorderLine(it, androidx.compose.ui.geometry.Offset(0f, h - d), androidx.compose.ui.geometry.Offset(w, h - d), androidx.compose.ui.geometry.Offset(0f, -1f), borderColor) }
                cell.borderLeft?.let { val d = inset(it); drawBorderLine(it, androidx.compose.ui.geometry.Offset(d, 0f), androidx.compose.ui.geometry.Offset(d, h), androidx.compose.ui.geometry.Offset(1f, 0f), borderColor) }
                cell.borderRight?.let { val d = inset(it); drawBorderLine(it, androidx.compose.ui.geometry.Offset(w - d, 0f), androidx.compose.ui.geometry.Offset(w - d, h), androidx.compose.ui.geometry.Offset(-1f, 0f), borderColor) }
            } else Modifier)
            .background(bg)
            .then(cond?.bar?.let { bar -> Modifier.drawBehind {
                // Excel's data bar: from the cell's left, as long as the value, fading to white, edged in its colour.
                val inset = 1.5.dp.toPx()
                val w = (size.width - 2 * inset) * bar.fraction
                if (w > 0f) {
                    val c = Color(bar.color)
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.15f)), startX = inset, endX = inset + w),
                        topLeft = Offset(inset, inset * 1.5f), size = androidx.compose.ui.geometry.Size(w, size.height - inset * 3f))
                    drawRect(c, topLeft = Offset(inset, inset * 1.5f), size = androidx.compose.ui.geometry.Size(w, size.height - inset * 3f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(0.75.dp.toPx()))
                }
            } } ?: Modifier)
            // An icon set's icon at the cell's left.
            .then(cond?.icon?.let { icon -> Modifier.drawBehind {
                val s = minOf(size.height - 4.dp.toPx(), 14.dp.toPx()).coerceAtLeast(1f)
                drawIntoCanvas { canvas ->
                    com.therealityteam.aeropages.format.pdf.CondIcons.draw(canvas.nativeCanvas, icon.set, icon.index, 2.dp.toPx(), (size.height - s) / 2f, s)
                }
            } } ?: Modifier)
            .then(if (sparkline != null && sparkline.values.isNotEmpty()) Modifier.drawBehind { drawSparkline(sparkline) } else Modifier)
            // Inside a selected block the gridlines stay as they are (the block has its own frame and tint);
            // the active cell is outlined.
            .then(when {
                active -> Modifier.border(BorderStroke(2.dp, LocalAccent.current))
                gridlines && (openRight || covered) -> Modifier.drawBehind {
                    val w = 0.5.dp.toPx()
                    drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, w / 2), androidx.compose.ui.geometry.Offset(size.width, w / 2), w)
                    drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, size.height - w / 2), androidx.compose.ui.geometry.Offset(size.width, size.height - w / 2), w)
                    if (!covered) drawLine(lineColor, androidx.compose.ui.geometry.Offset(w / 2, 0f), androidx.compose.ui.geometry.Offset(w / 2, size.height), w)
                    if (!openRight) drawLine(lineColor, androidx.compose.ui.geometry.Offset(size.width - w / 2, 0f), androidx.compose.ui.geometry.Offset(size.width - w / 2, size.height), w)
                }
                gridlines -> Modifier.border(BorderStroke(0.5.dp, PicoTheme.colorScheme.dividerLine))
                else -> Modifier
            })
            // Excel-style note indicator: a small red triangle in the top-right corner.
            .then(if (cell?.comment != null) Modifier.drawBehind {
                val s = 6.dp.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width - s, 0f); lineTo(size.width, 0f); lineTo(size.width, s); close()
                }
                drawPath(path, Color(0xFFD00000))
            } else Modifier)
            // The cell reference ("B3") names the cell for accessibility and for UI tests.
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            // Turned or stacked text stays inside its cell (it never runs on to the neighbours).
            .then(if (cell?.turned == true) Modifier.clipToBounds() else Modifier)
            .clickable(onClick = onClick)
            // Indent: levels of three spaces from the aligned edge (left, or right for right-aligned text).
            .padding(start = (if (cond?.icon != null) 18.dp else 4.dp) + (if (horiz != com.therealityteam.aeropages.model.TextAlign.RIGHT && horiz != com.therealityteam.aeropages.model.TextAlign.CENTER) indentDp(cell) else 0.dp),
                end = 4.dp + (if (horiz == com.therealityteam.aeropages.model.TextAlign.RIGHT) indentDp(cell) else 0.dp)),
        contentAlignment = alignment,
    ) {
        if (text.isNotEmpty() && cond?.hideValue != true) {
            // A link — the cell's own, or a HYPERLINK formula (Excel gives it the Hyperlink look) — is blue and underlined.
            val linked = cell?.link != null || com.therealityteam.aeropages.model.CellLinks.isHyperlinkFormula(cell?.text)
            val style = cellTextStyle(format).let { if (linked) it.copy(color = Color(0xFF0563C1), textDecoration = TextDecoration.Underline) else it }
                // A format code's section colour ([Red] for negatives …) wins over the cell's font colour, as in Excel.
                .let { s -> if (textColor != null) s.copy(color = Color(textColor)) else s }
                // A conditional format's text colour and traits win over both.
                .let { s -> condStyle?.let { cs -> conditional(s, cs) } ?: s }
                // Chinese characters in their language's forms (Japanese text in Japanese glyphs; see HanLocale).
                .let { s -> com.therealityteam.aeropages.model.HanLocale.of(text)?.let { s.copy(localeList = androidx.compose.ui.text.intl.LocaleList(it.toLanguageTag())) } ?: s }
            // Mixed formatting (Excel rich runs / Calc spans) shows as an annotated string while the runs still match the text.
            val rich = cell?.richRuns()?.takeIf { text == cell.text }
            // Long text runs on over the empty cells to the right (its [spill]), cut where they end; a filter button keeps its corner.
            // Right-aligned text runs on to the left the same way (its [spillLeft]), over cells drawn before it.
            val runOn = if (spill > 0.dp) Modifier.wrapContentWidth(Alignment.Start, unbounded = true).widthIn(max = cellW + spill - 8.dp)
                else if (spillLeft > 0.dp) Modifier.wrapContentWidth(Alignment.End, unbounded = true).widthIn(max = cellW + spillLeft - 8.dp)
                else if (filter != null) Modifier.padding(end = 20.dp) else Modifier
            // A cell that does not wrap is cut at its edge, never broken at a word (Excel shows "In progres", not "In").
            val wraps = cell?.wrap == true && spill == 0.dp && spillLeft == 0.dp
            val spread = cell?.spread
            if (across && spill > 0.dp) {
                // Center Across Selection: centred over the cells it spans.
                Text(text, style = style.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 1, softWrap = false,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(cellW + spill - 8.dp))
            } else if (cell != null && !cell.turned && !cell.wrap && (cell.shrink || spread == com.therealityteam.aeropages.model.CellSpread.FILL || spread == com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED)) {
                FittedCellText(text, style, cell, cellW - 8.dp - indentDp(cell) - (if (filter != null) 20.dp else 0.dp))
            } else if (cell?.stacked == true) {
                // Vertical Text: one character under another, centred on its column.
                Text(com.therealityteam.aeropages.model.Cell.stackedLetters(text).joinToString("\n"), style = style.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), softWrap = false)
            } else if (cell != null && cell.rotation != 0) {
                // Turned text: laid out on one line, then turned about its centre inside the box it then fills.
                val turned = Modifier.turned(cell.rotation)
                if (rich != null) Text(TextStyling.annotatedOf(com.therealityteam.aeropages.model.Paragraph(rich.toMutableList())), style = style, maxLines = 1, softWrap = false, modifier = turned)
                else Text(text, style = style, softWrap = false, modifier = turned)
            } else if (rich != null) Text(TextStyling.annotatedOf(com.therealityteam.aeropages.model.Paragraph(rich.toMutableList())), style = style, maxLines = if (cell.wrap) Int.MAX_VALUE else 1, softWrap = wraps, modifier = runOn)
            else Text(text, style = style, maxLines = if (cell?.wrap == true) Int.MAX_VALUE else 1, softWrap = wraps, modifier = runOn)
        }
        if (filter != null) FilterButtonView(filter, Modifier.align(Alignment.CenterEnd))
        // What opens on the cell itself: the context menu, anchored under it.
        overlay?.invoke()
    }
}

/**
 * Text that answers to the cell's width, on one line: Fill repeats it as often as it fits, Distributed spreads its letters
 * edge to edge, Shrink to fit makes the type smaller until it fits (never larger) — Excel's three.
 */
@Composable
private fun FittedCellText(text: String, style: androidx.compose.ui.text.TextStyle, cell: com.therealityteam.aeropages.model.Cell, room: androidx.compose.ui.unit.Dp) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val roomPx = with(density) { room.toPx() }.coerceAtLeast(1f)
    val width = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toFloat().coerceAtLeast(1f)
    when {
        cell.spread == com.therealityteam.aeropages.model.CellSpread.FILL -> {
            val times = (roomPx / width).toInt().coerceAtLeast(1)
            Text(text.repeat(times), style = style, maxLines = 1, softWrap = false)
        }
        cell.shrink && width > roomPx -> Text(text, style = style.copy(fontSize = style.fontSize * (roomPx / width)), maxLines = 1, softWrap = false)
        cell.spread == com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED && text.length > 1 && width < roomPx -> {
            val gaps = com.therealityteam.aeropages.model.Cell.stackedLetters(text).size - 1
            val extra = with(density) { ((roomPx - width) / gaps.coerceAtLeast(1)).toSp() }
            Text(text, style = style.copy(letterSpacing = extra), maxLines = 1, softWrap = false, modifier = Modifier.width(room))
        }
        else -> Text(text, style = style, maxLines = 1, softWrap = false)
    }
}

/** A cell's indent as space on the page: [com.therealityteam.aeropages.model.Cell.INDENT_PT] a level. */
private fun indentDp(cell: com.therealityteam.aeropages.model.Cell?): androidx.compose.ui.unit.Dp =
    ((cell?.indent ?: 0) * com.therealityteam.aeropages.model.Cell.INDENT_PT).dp

/**
 * Text turned [degrees] counterclockwise: measured on one line without a width limit, then turned about its centre; the
 * box it takes is the turned text's bounding box (as far as the cell allows), so the cell's alignment places the turned
 * text as Excel does.
 */
private fun Modifier.turned(degrees: Int): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(androidx.compose.ui.unit.Constraints())
    val a = Math.toRadians(degrees.toDouble())
    val c = kotlin.math.abs(kotlin.math.cos(a)); val sn = kotlin.math.abs(kotlin.math.sin(a))
    val bw = (p.width * c + p.height * sn).toInt().coerceAtMost(constraints.maxWidth)
    val bh = (p.width * sn + p.height * c).toInt().coerceAtMost(constraints.maxHeight)
    layout(bw, bh) { p.placeWithLayer((bw - p.width) / 2, (bh - p.height) / 2) { rotationZ = -degrees.toFloat() } }
}

/** [base] with a conditional format's text colour, bold, italic, underline and strikethrough where it sets them. */
private fun conditional(base: androidx.compose.ui.text.TextStyle, cs: com.therealityteam.aeropages.model.CondStyle): androidx.compose.ui.text.TextStyle {
    var s = base
    cs.font?.let { s = s.copy(color = Color(it)) }
    cs.bold?.let { s = s.copy(fontWeight = if (it) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) }
    cs.italic?.let { s = s.copy(fontStyle = if (it) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal) }
    if (cs.underline != null || cs.strike != null) {
        val under = cs.underline ?: (s.textDecoration?.contains(TextDecoration.Underline) == true)
        val strike = cs.strike ?: (s.textDecoration?.contains(TextDecoration.LineThrough) == true)
        s = s.copy(textDecoration = TextDecoration.combine(listOfNotNull(TextDecoration.Underline.takeIf { under }, TextDecoration.LineThrough.takeIf { strike })))
    }
    return s
}

/**
 * A sparkline inside the cell: a line through the points, one column per value, or win/loss bars
 * above / below a midline. Padded 3dp all round; the vertical scale spans the data's own range.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkline(s: SparkDraw) {
    val v = s.values
    val pad = 3.dp.toPx()
    val w = size.width - pad * 2
    val h = size.height - pad * 2
    if (w <= 0f || h <= 0f) return
    val color = Color(s.color)
    when (s.type) {
        com.therealityteam.aeropages.model.SparklineType.LINE -> {
            val min = v.min(); val max = v.max()
            val span = (max - min).takeIf { it > 0.0 } ?: 1.0
            val step = if (v.size > 1) w / (v.size - 1) else 0f
            val points = v.mapIndexed { i, d -> androidx.compose.ui.geometry.Offset(pad + i * step, pad + h - ((d - min) / span * h).toFloat()) }
            for (i in 1 until points.size) drawLine(color, points[i - 1], points[i], strokeWidth = 1.5.dp.toPx())
            if (points.size == 1) drawCircle(color, 2.dp.toPx(), points[0])
            if (s.markers) points.forEach { drawCircle(color, 1.8.dp.toPx(), it) }
        }
        com.therealityteam.aeropages.model.SparklineType.COLUMN -> {
            val min = minOf(0.0, v.min()); val max = maxOf(0.0, v.max())
            val span = (max - min).takeIf { it > 0.0 } ?: 1.0
            val slot = w / v.size
            val zeroY = pad + h - ((0.0 - min) / span * h).toFloat()
            v.forEachIndexed { i, d ->
                val y = pad + h - ((d - min) / span * h).toFloat()
                val top = minOf(y, zeroY); val bottom = maxOf(y, zeroY)
                drawRect(if (d < 0) Color(0xFFD00000) else color,
                    topLeft = androidx.compose.ui.geometry.Offset(pad + i * slot + slot * 0.15f, top),
                    size = androidx.compose.ui.geometry.Size(slot * 0.7f, (bottom - top).coerceAtLeast(1f)))
            }
        }
        com.therealityteam.aeropages.model.SparklineType.WIN_LOSS -> {
            val slot = w / v.size
            val mid = pad + h / 2f
            v.forEachIndexed { i, d ->
                val up = d >= 0
                drawRect(if (up) color else Color(0xFFD00000),
                    topLeft = androidx.compose.ui.geometry.Offset(pad + i * slot + slot * 0.15f, if (up) pad else mid),
                    size = androidx.compose.ui.geometry.Size(slot * 0.7f, h / 2f - 1f))
            }
        }
    }
}

internal fun cellTextStyle(f: CharacterFormat?): TextStyle {
    if (f == null) return TextStyle(fontSize = 12.sp, color = Color(0xFF1A1A1A))
    return TextStyle(
        color = f.color?.let { Color(it) } ?: Color(0xFF1A1A1A),
        fontSize = (f.fontSizePt ?: 12f).sp,
        fontWeight = if (f.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (f.italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (f.underline) TextDecoration.Underline else null,
    )
}

/** Long text running on over empty cells in one row of the grid: each source column's extra width, and the covered columns (true for the last). */
internal class RowSpill {
    val extra = HashMap<Int, androidx.compose.ui.unit.Dp>()
    val covered = HashMap<Int, Boolean>()
    /** Right-aligned text running on to the left: its column's extra width, and the empty cells it covers. */
    val left = HashMap<Int, androidx.compose.ui.unit.Dp>()
    val coveredLeft = HashSet<Int>()
    /** Center Across Selection: its text is centred over its own [extra] width. */
    val across = HashSet<Int>()
}

/**
 * One side of a cell's border from [start] to [end], as Excel draws its line style: hair to thick
 * widths, dashes and dots, two lines for double ([inward] points into the cell); [auto] colours an
 * automatic line.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBorderLine(
    line: com.therealityteam.aeropages.model.BorderLine, start: androidx.compose.ui.geometry.Offset, end: androidx.compose.ui.geometry.Offset,
    inward: androidx.compose.ui.geometry.Offset, auto: Color,
) {
    val color = line.color?.let { Color(it) } ?: auto
    val px = 1.dp.toPx()
    if (line.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) {
        drawLine(color, start, end, px)
        drawLine(color, start + inward * (2.5f * px), end + inward * (2.5f * px), px)
        return
    }
    val pattern = line.style.dash
    drawLine(color, start, end, line.style.width * px, pathEffect = pattern?.let { p -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(FloatArray(p.size) { p[it] * px }) })
}

/** A header cell's AutoFilter button: whether its column filters, its label, and what a tap opens. */
internal class FilterButton(val active: Boolean, val description: String, val onClick: () -> Unit)

/** Excel's filter drop-down button: an arrow, or a funnel while the column filters. */
@Composable
private fun FilterButtonView(button: FilterButton, modifier: Modifier) {
    val accent = LocalAccent.current
    val ink = PicoTheme.colorScheme.labelSecondary
    Box(
        modifier.size(18.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(if (button.active) accent.copy(alpha = 0.16f) else Color(0xFFF1F1F1))
            .border(BorderStroke(0.5.dp, if (button.active) accent else Color(0xFFC8C8C8)), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .semantics { contentDescription = button.description }
            .clickable(onClick = button.onClick),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val w = size.width; val h = size.height
            val path = androidx.compose.ui.graphics.Path()
            if (button.active) {
                // A funnel: the wide mouth narrowing to a stem.
                path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w * 0.62f, h * 0.5f); path.lineTo(w * 0.62f, h)
                path.lineTo(w * 0.38f, h * 0.82f); path.lineTo(w * 0.38f, h * 0.5f); path.close()
                drawPath(path, accent)
            } else {
                path.moveTo(w * 0.15f, h * 0.3f); path.lineTo(w * 0.85f, h * 0.3f); path.lineTo(w * 0.5f, h * 0.75f); path.close()
                drawPath(path, ink)
            }
        }
    }
}

/** How far Page Up / Page Down go: about a window of rows. */
private const val PAGE_ROWS = 20

/**
 * A page break (or print-range edge) in Page Break Preview that can be dragged: a strip along the line, [place] giving its
 * x, y, width and height in the body viewport as it is laid out (nothing when it has no length), [across] for a horizontal
 * line. [onMove] gets how far the pointer has gone since the drag began.
 */
@Composable
private fun PageEdgeHandle(place: () -> FloatArray, across: Boolean, label: String,
                           onStart: () -> Unit, onMove: (Float) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit) {
    val at by androidx.compose.runtime.rememberUpdatedState(place)
    val start by androidx.compose.runtime.rememberUpdatedState(onStart)
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val end by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val cancel by androidx.compose.runtime.rememberUpdatedState(onCancel)
    Box(
        Modifier
            .layout { measurable, _ ->
                val (x, y, w, h) = at()
                if (w <= 0f || h <= 0f) return@layout layout(0, 0) {}
                val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w.roundToInt(), h.roundToInt()))
                layout(0, 0) { p.place(x.roundToInt(), y.roundToInt()) }
            }
            .semantics { contentDescription = label }
            .spatialHoverEffect()
            .pointerInput(across) {
                var gone = 0f
                val begin = { _: Offset -> gone = 0f; start() }
                if (across) detectVerticalDragGestures(onDragStart = begin, onDragEnd = { end() }, onDragCancel = { cancel() }) { change, d -> change.consume(); gone += d; move(gone) }
                else detectHorizontalDragGestures(onDragStart = begin, onDragEnd = { end() }, onDragCancel = { cancel() }) { change, d -> change.consume(); gone += d; move(gone) }
            },
    )
}
