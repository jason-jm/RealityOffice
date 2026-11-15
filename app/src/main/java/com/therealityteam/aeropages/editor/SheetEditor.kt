package com.therealityteam.aeropages.editor

import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.WatchMark
import com.therealityteam.aeropages.model.SheetImage
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationScope
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import java.util.Locale

/** Aggregation used by the pivot-table builder (数据透视表 值汇总方式). */
enum class PivotAgg(private val label: TextId) {
    SUM(TextId.PIVOT_SUM), COUNT(TextId.PIVOT_COUNT), AVERAGE(TextId.PIVOT_AVERAGE), MAX(TextId.PIVOT_MAX), MIN(TextId.PIVOT_MIN);

    /** Localised name shown in the pivot dialog and the summary header. */
    val display: String get() = Texts.get(label)
}

/**
 * Excel's status bar figures for a selection: [count] cells holding anything (COUNTA), [numbers] of them numbers
 * (COUNT — dates and times are numbers), and the [sum], [min] and [max] of those numbers; [format] is the first
 * number's cell, whose number format Excel shows the figures in.
 */
data class SelectionStats(val count: Int, val numbers: Int, val sum: Double, val min: Double, val max: Double, val format: com.therealityteam.aeropages.model.Cell?) {
    /** The numbers' average (0 when there are none). */
    val average: Double get() = if (numbers == 0) 0.0 else sum / numbers

    /** The figures Excel's status bar can show, in its order; which are on is the viewer's choice. */
    enum class Figure { AVERAGE, COUNT, NUMERICAL_COUNT, MIN, MAX, SUM }

    /** [f]'s value: counts as counts, the rest only when there are numbers (else null — Excel leaves them out). */
    fun valueOf(f: Figure): Double? = when (f) {
        Figure.COUNT -> count.toDouble()
        Figure.NUMERICAL_COUNT -> numbers.toDouble()
        else -> if (numbers == 0) null else when (f) {
            Figure.AVERAGE -> average
            Figure.MIN -> min
            Figure.MAX -> max
            else -> sum
        }
    }
}

/**
 * State holder for the Sheets (Excel) module: a workbook, the active sheet, the
 * selected cell, and formatting commands acting on that cell. Compose observes
 * [revision].
 */
class SheetEditor {
    var revision by mutableIntStateOf(0)
        private set
    var workbook by mutableStateOf(Workbook.blank())
        private set
    var sheetIndex by mutableIntStateOf(0)
        private set
    var selRow by mutableIntStateOf(0)
        private set
    var selCol by mutableIntStateOf(0)
        private set
    /** The opposite corner of the selection rectangle (equals sel* for a single cell). */
    var selRow2 by mutableIntStateOf(0)
        private set
    var selCol2 by mutableIntStateOf(0)
        private set
    internal val saveState = EditState()
    var dirty by saveState::dirty
        private set

    /** File → Info: the file's properties, a change to save (not an undo step, as in Office). */
    fun setProps(p: com.therealityteam.aeropages.model.DocProps) {
        if (p == workbook.props) return
        workbook.props = p; dirty = true; bump()
    }

    private var clipboard: List<List<Cell>>? = null

    fun rangeRowMin() = minOf(selRow, selRow2)
    fun rangeRowMax() = maxOf(selRow, selRow2)
    fun rangeColMin() = minOf(selCol, selCol2)
    fun rangeColMax() = maxOf(selCol, selCol2)
    fun inSelection(row: Int, col: Int) = row in rangeRowMin()..rangeRowMax() && col in rangeColMin()..rangeColMax() ||
        extraAreas.any { row in it[0]..it[2] && col in it[1]..it[3] }
    val canPaste: Boolean get() = clipboard != null

    private fun bump() { revision++ }
    /** Bumped only when content/structure changes (never on selection), so evaluated values stay cached while navigating. */
    private var contentRev = 0
    /** Content changed: mark dirty, drop the evaluated values the edit affects, recompose. */
    private fun touchContent() {
        contentRev++; dirty = true; revision++
        if (traceArrows.isNotEmpty()) { traceArrows.clear(); tracedPrecedents.clear(); tracedDependents.clear() }
        val scope = pendingScope
        applyInvalidation(scope); pendingScope = Scope.All
        refitRows(scope)
        refreshCharts(); verifyIfPending(); if (circles.isNotEmpty()) recheckCircles()
    }

    /**
     * Rows without a height of their own follow their content ([RowFit]): the rows an edit touched,
     * or every row when it may have changed any (structure, column widths, other sheets' deltas).
     */
    private fun refitRows(scope: Scope) {
        val rows = when (scope) {
            is Scope.Rect -> if (scope.sheet == sheetIndex && scope.r2 >= scope.r1) scope.r1..scope.r2 else null
            is Scope.Keys -> if (scope.sheet == sheetIndex && scope.keys.isNotEmpty()) scope.keys.minOf { (it / 1000L).toInt() }..scope.keys.maxOf { (it / 1000L).toInt() } else null
            Scope.All -> null
        }
        com.therealityteam.aeropages.model.RowFit.refresh(sheet, rows) { r, c -> display(r, c) }
    }

    /** The active sheet's automatic row heights, worked out if they never were (a sheet just shown or loaded). */
    private fun ensureRowFits() { if (!sheet.autoRowsReady) com.therealityteam.aeropages.model.RowFit.refresh(sheet, null) { r, c -> display(r, c) } }
    /** Content replaced without dirtying (load). */
    private fun invalidate() { contentRev++; revision++; displayCache.clear(); memo.clear() }
    /** Public recompose trigger for UI that mutated the model directly (e.g. chart type pills). */
    fun touchChart() { verifyPending = null; touchContent() }

    // --- incremental recalculation ------------------------------------------------
    /** What the mutation recorded by the last [pushUndo] may have changed; the evaluated-value caches are evicted accordingly. */
    private sealed class Scope {
        class Rect(val sheet: Int, val r1: Int, val c1: Int, val r2: Int, val c2: Int) : Scope()
        class Keys(val sheet: Int, val keys: Collection<Long>) : Scope()
        object All : Scope()
    }
    private var pendingScope: Scope = Scope.All

    /** The cells an undo delta covers, or [Scope.All] when it may change values anywhere (structure, names, sheets). */
    private fun scopeOf(delta: Delta<Workbook>): Scope = when (delta) {
        is CellRangeDelta -> Scope.Rect(delta.sheetIdx, delta.r1, delta.c1, delta.r2, delta.c2)
        is CellKeysDelta -> Scope.Keys(delta.sheetIdx, delta.cells.keys)
        is SheetMetaDelta -> Scope.Rect(sheetIndex, 0, 0, -1, -1) // metadata never changes a value
        is CompositeDelta -> {
            val parts = (0 until delta.size).map { scopeOf(delta[it]) }
            when {
                parts.any { it === Scope.All } -> Scope.All
                parts.size == 1 -> parts[0]
                else -> parts.firstOrNull { it is Scope.Rect && it.r2 >= it.r1 } ?: parts.firstOrNull { it is Scope.Keys } ?: parts[0]
            }
        }
        else -> Scope.All
    }

    private fun applyInvalidation(scope: Scope) {
        when (scope) {
            Scope.All -> { displayCache.clear(); memo.clear() }
            is Scope.Rect -> if (scope.r2 >= scope.r1) invalidateRect(scope.sheet, scope.r1, scope.c1, scope.r2, scope.c2)
            is Scope.Keys -> scope.keys.forEach { k -> val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt(); invalidateRect(scope.sheet, r, c, r, c) }
        }
    }

    private fun invalidateRect(sheetIdx: Int, r1: Int, c1: Int, r2: Int, c2: Int) {
        val dropped = memo.invalidate(sheetIdx, r1, c1, r2, c2)
        if (dropped == null || (r2 - r1 + 1).toLong() * (c2 - c1 + 1).toLong() > 20_000L) { displayCache.clear(); return }
        dropped.forEach { displayCache.remove(it) }
        for (r in r1..r2) for (c in c1..c2) displayCache.remove(FormulaEngine.compositeKey(sheetIdx, r, c))
    }

    // --- undo / redo (scoped deltas, see UndoHistory.kt / SheetDeltas.kt) ---------
    /** Active sheet + selection rectangle restored alongside an undo step. */
    private class Sel(val sheet: Int, val row: Int, val col: Int, val row2: Int, val col2: Int)
    private val history = History<Workbook, Sel>(80)
    private var lastKey: String? = null
    /** Test-only ([VERIFY_UNDO]): the latest step's delta plus a full pre-edit copy, dry-run on every content change. */
    private var verifyPending: Pair<Delta<Workbook>, Workbook>? = null
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    private fun sel() = Sel(sheetIndex, selRow, selCol, selRow2, selCol2)
    /** Undoable steps recorded so far (tests). */
    internal val undoDepth: Int get() = history.size

    /**
     * Record [delta] — the part of the workbook the coming mutation may change — as an undo
     * step. Consecutive calls sharing a non-null [key] (typing in one cell, nudging a column
     * width) coalesce into the first call's step.
     */
    private fun pushUndo(delta: Delta<Workbook>, key: String? = null) {
        pendingScope = scopeOf(delta)
        gathered?.let { it += delta; return }   // one area of a command over several: one step at the end (see spread)
        if (key != null && key == lastKey) return
        history.push(delta, sel()); lastKey = key
        if (VERIFY_UNDO) verifyPending = delta to workbook.copyOf()
    }
    // Scope helpers: what a mutation touches.
    private fun cells(r1: Int, c1: Int, r2: Int, c2: Int) = CellRangeDelta.capture(sheetIndex, sheet, r1, c1, r2, c2)
    private fun selectionCells() = cells(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
    private fun activeCell() = cells(selRow, selCol, selRow, selCol)
    private fun sheetMeta() = SheetMetaDelta.capture(sheetIndex, sheet)
    /**
     * The whole active sheet plus every formula on the other sheets and the defined names (structural
     * edits rewrite references everywhere).
     */
    private fun wholeSheet(): Delta<Workbook> = CompositeDelta(buildList {
        add(SheetDelta(sheetIndex, sheet.copyOf()))
        add(NamesDelta(workbook.names.toMap()))
        workbook.sheets.forEachIndexed { i, s ->
            if (i == sheetIndex) return@forEachIndexed
            CellKeysDelta.formulas(i, s)?.let { add(it) }
            // Their validation rules' and conditional formats' formulas may name this sheet too.
            if (s.validations.any { it.formula1 != null || it.formula2 != null } || s.condFormats.any { it.formula1 != null || it.formula2 != null })
                add(SheetMetaDelta.capture(i, s))
        }
    })
    private fun both(a: Delta<Workbook>, b: Delta<Workbook>): Delta<Workbook> = CompositeDelta(listOf(a, b))

    private fun restoreSel(s: Sel) {
        sheetIndex = s.sheet.coerceIn(0, workbook.sheets.lastIndex)
        // Undoing an Unhide (or redoing a Hide) leaves no hidden sheet on screen.
        if (sheet.hidden) sheetIndex = nearestVisible(sheetIndex)
        val maxR = sheet.rowCount - 1; val maxC = sheet.colCount - 1
        selRow = s.row.coerceIn(0, maxR); selCol = s.col.coerceIn(0, maxC)
        selRow2 = s.row2.coerceIn(0, maxR); selCol2 = s.col2.coerceIn(0, maxC)
    }
    fun undo() { pendingEntry = null; validationAlert = null; val s = history.undo(workbook, sel()) ?: return; verifyPending = null; selectedImage = null; restoreSel(s); lastKey = null; touchContent() }
    fun redo() { pendingEntry = null; validationAlert = null; val s = history.redo(workbook, sel()) ?: return; verifyPending = null; selectedImage = null; restoreSel(s); lastKey = null; touchContent() }

    private fun verifyIfPending() {
        val (delta, before) = verifyPending ?: return
        verifyDelta("sheet edit", delta, before, workbook, { it.copyOf() }, ModelDiff::sameWorkbook)
    }

    fun load(w: Workbook, recovered: Boolean = false) {
        // A workbook always shows a sheet (a file hiding them all shows its first); it opens on the first shown.
        if (w.sheets.isNotEmpty() && w.sheets.all { it.hidden }) w.sheets[0].state = com.therealityteam.aeropages.model.SheetState.VISIBLE
        pendingEntry = null; validationAlert = null; circles.clear(); selectedImage = null; workbook = w
        sheetIndex = w.sheets.indexOfFirst { !it.hidden }.coerceAtLeast(0)
        selRow = 0; selCol = 0; history.clear(); verifyPending = null; lastKey = null; dirty = recovered; invalidate(); ensureRowFits()
    }
    fun markSaved() { dirty = false; bump() }
    fun markSaved(path: String, clean: Boolean = true) {
        workbook.sourcePath = path
        if (clean) dirty = false
        bump()
    }

    val sheet: Sheet get() = workbook.sheets[sheetIndex.coerceIn(0, workbook.sheets.lastIndex)]

    fun select(row: Int, col: Int) {
        if (!selectable(row.coerceIn(0, sheet.rowCount - 1), col.coerceIn(0, sheet.colCount - 1))) return
        commitEntry()
        selectedImage = null; areaStore = null
        selRow = row.coerceIn(0, sheet.rowCount - 1); selCol = col.coerceIn(0, sheet.colCount - 1)
        selRow2 = selRow; selCol2 = selCol
        lastKey = null
        bump()
    }

    /** Move the active cell (and collapse the selection), keyboard navigation. */
    fun moveSelection(dRow: Int, dCol: Int) {
        // Arrow keys step over rows and columns the grid does not show (filtered, folded, hidden).
        var r = selRow + dRow
        var c = selCol + dCol
        if (dRow != 0) { val skip = invisibleRows; while (r in skip && r + dRow in 0 until sheet.rowCount) r += dRow; if (r in skip) r = selRow }
        if (dCol != 0) { val skip = invisibleCols; while (c in skip && c + dCol in 0 until sheet.colCount) c += dCol; if (c in skip) c = selCol }
        select(r, c)
    }

    /** Extend the selection rectangle's far corner (Shift+arrows). */
    fun extendSelection(dRow: Int, dCol: Int) {
        selRow2 = (selRow2 + dRow).coerceIn(0, sheet.rowCount - 1)
        selCol2 = (selCol2 + dCol).coerceIn(0, sheet.colCount - 1)
        bump()
    }

    // --- Excel's keyboard navigation ----------------------------------------------------------------

    private fun filledAt(r: Int, c: Int) = sheet.cell(r, c)?.text?.isNotEmpty() == true

    /**
     * Excel's Ctrl+arrow (with [extend], Ctrl+Shift+arrow): from the active cell — the selection's moving corner when
     * extending — to the edge of its block of filled cells in that direction; from an empty cell or a block's edge, on
     * to the next filled cell; with none, to the sheet's edge. Rows and columns the grid hides are stepped over.
     */
    fun jumpToEdge(dRow: Int, dCol: Int, extend: Boolean = false) {
        commitEntry()
        val r0 = if (extend) selRow2 else selRow; val c0 = if (extend) selCol2 else selCol
        val skipR = invisibleRows; val skipC = invisibleCols
        val lastR = sheet.rowCount - 1; val lastC = sheet.colCount - 1
        fun step(r: Int, c: Int): Pair<Int, Int>? {
            var nr = r + dRow; var nc = c + dCol
            while (nr in 0..lastR && nc in 0..lastC && (nr in skipR || nc in skipC)) { nr += dRow; nc += dCol }
            return if (nr in 0..lastR && nc in 0..lastC) nr to nc else null
        }
        var at = r0 to c0
        val next = step(r0, c0) ?: return
        if (filledAt(r0, c0) && filledAt(next.first, next.second)) {
            // Inside a block: to its last filled cell.
            at = next
            while (true) { val n = step(at.first, at.second) ?: break; if (!filledAt(n.first, n.second)) break; at = n }
        } else {
            // At a block's edge or in empty cells: on to the next filled cell, else the sheet's edge.
            at = next
            while (!filledAt(at.first, at.second)) { at = step(at.first, at.second) ?: break }
        }
        if (extend) { selRow2 = at.first; selCol2 = at.second; bump() } else select(at.first, at.second)
    }

    /** Excel's Home: the row's first column (past frozen columns' start). Ctrl+Home ([sheetStart]): the first cell below and right of frozen panes. */
    fun goHome(sheetStart: Boolean, extend: Boolean = false) {
        commitEntry()
        val r = if (sheetStart) sheet.freezeRows.coerceAtMost(sheet.rowCount - 1) else (if (extend) selRow2 else selRow)
        val c = sheet.freezeCols.takeIf { sheetStart }?.coerceAtMost(sheet.colCount - 1) ?: 0
        if (extend) { selRow2 = r; selCol2 = c; bump() } else select(r, c)
    }

    /** Excel's Ctrl+End: the last row and last column that hold anything (with [extend], the selection to there). */
    fun goEnd(extend: Boolean = false) {
        commitEntry()
        var mr = 0; var mc = 0
        for ((k, cell) in sheet.cells) if (cell.text.isNotEmpty()) { mr = maxOf(mr, (k / 1000L).toInt()); mc = maxOf(mc, (k % 1000L).toInt()) }
        if (extend) { selRow2 = mr; selCol2 = mc; bump() } else select(mr, mc)
    }

    /** Excel's Ctrl+; (or, [time], Ctrl+Shift+;): today's date — the time now — entered in the active cell. */
    fun enterNow(time: Boolean, now: java.time.LocalDateTime = java.time.LocalDateTime.now()) {
        commitEntry()
        val text = if (time) String.format(java.util.Locale.ROOT, "%02d:%02d", now.hour, now.minute) else now.toLocalDate().toString()
        typeCell(selRow, selCol, text)
        commitEntry()
    }

    /** Excel's Ctrl+5: strikethrough on or off for the selection. */
    fun toggleStrikethrough() { val on = !formatIsOn { it.strikethrough }; applyFmt { it.strikethrough = on } }

    fun selectSheet(i: Int) {
        if (workbook.sheets.getOrNull(i)?.hidden == true) return
        commitEntry(); selectedImage = null; sheetIndex = i.coerceIn(0, workbook.sheets.lastIndex); selRow = 0; selCol = 0; selRow2 = 0; selCol2 = 0; lastKey = null; ensureRowFits(); bump()
    }

    // --- clipboard / fill / clear (operate on the selection rectangle) ----------

    private var clipOrigin: Pair<Int, Int>? = null
    private var clipCut = false
    /** The copied cells' values as they were copied (Paste Values), and the sheet they came from (Paste Link). */
    private var clipValues: List<List<String>>? = null
    private var clipSheet = 0
    /** The copied cells' validation rules, each on its own cell (null: none; null throughout: no rules). */
    private var clipRules: List<List<DataValidation?>>? = null
    /** The copied cells' conditional formats: each rule on the part of the block it covers, and where that part sits in the block (null: none). */
    private var clipCond: List<Pair<IntArray, com.therealityteam.aeropages.model.CondFormat>>? = null

    fun copySelection() {
        commitEntry()
        clipboard = (rangeRowMin()..rangeRowMax()).map { r ->
            (rangeColMin()..rangeColMax()).map { c -> sheet.cell(r, c)?.copyOf() ?: Cell() }
        }
        clipValues = (rangeRowMin()..rangeRowMax()).map { r -> (rangeColMin()..rangeColMax()).map { c -> rawValue(r, c) } }
        clipOrigin = rangeRowMin() to rangeColMin()
        clipSheet = sheetIndex
        clipCut = false
        clipRules = rulesOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
        clipCond = condPiecesOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
        bump()
    }

    /**
     * Each conditional format reaching the block, on the part of it the rule covers (its formulas following
     * that part's corner), with that part's place in the block (rows and columns from its top-left); null when none.
     */
    private fun condPiecesOf(r1: Int, c1: Int, r2: Int, c2: Int): List<Pair<IntArray, com.therealityteam.aeropages.model.CondFormat>>? =
        sheet.condFormats.mapNotNull { cf ->
            if (cf.r2 < r1 || cf.r1 > r2 || cf.c2 < c1 || cf.c1 > c2) return@mapNotNull null
            val a = maxOf(cf.r1, r1); val b = maxOf(cf.c1, c1); val c = minOf(cf.r2, r2); val d = minOf(cf.c2, c2)
            intArrayOf(a - r1, b - c1, c - r1, d - c1) to condPiece(cf, a, b, c, d)
        }.ifEmpty { null }

    /** [cf] over the block [r1]…[c2] instead: relative references in its formulas follow the block's top-left cell. */
    private fun condPiece(cf: com.therealityteam.aeropages.model.CondFormat, r1: Int, c1: Int, r2: Int, c2: Int): com.therealityteam.aeropages.model.CondFormat {
        val dr = r1 - cf.r1; val dc = c1 - cf.c1
        fun rebase(f: String?) = if (f == null || (dr == 0 && dc == 0)) f else shiftFormulaRefs(f, dr, dc)
        return cf.over(r1, c1, r2, c2).copy(formula1 = rebase(cf.formula1), formula2 = rebase(cf.formula2))
    }

    /**
     * A fill's conditional formats, as Excel's fill carries formats: each rule over the seed block
     * [sr1]…[sc2] reaches the filled block [fr1]…[fc2] beside it ([vertical]: above or under it) —
     * the rule's own range grown where the fill continues it (one rule still: top-N and scales see
     * all of it), else a copy over the filled cells; the filled cells' own rules go. False when no
     * rule was there or came.
     */
    private fun fillCondFormats(sr1: Int, sc1: Int, sr2: Int, sc2: Int, fr1: Int, fc1: Int, fr2: Int, fc2: Int, vertical: Boolean): Boolean {
        // Each rule's part of the seed, repeated down (or across) the fill as the seed's values are.
        val size = if (vertical) sr2 - sr1 + 1 else sc2 - sc1 + 1
        val span = if (vertical) fr2 - fr1 + 1 else fc2 - fc1 + 1
        val pieces = ArrayList<com.therealityteam.aeropages.model.CondFormat>()
        for (cf in sheet.condFormats) {
            if (cf.r1 > sr2 || cf.r2 < sr1 || cf.c1 > sc2 || cf.c2 < sc1) continue
            val a = maxOf(cf.r1, sr1); val b = maxOf(cf.c1, sc1); val c = minOf(cf.r2, sr2); val d = minOf(cf.c2, sc2)
            for (t in 0 until (span + size - 1) / size) {
                if (vertical) {
                    val top = fr1 + t * size + (a - sr1); val bottom = minOf(fr1 + t * size + (c - sr1), fr2)
                    if (top <= bottom) pieces.add(condPiece(cf, top, b, bottom, d))
                } else {
                    val left = fc1 + t * size + (b - sc1); val right = minOf(fc1 + t * size + (d - sc1), fc2)
                    if (left <= right) pieces.add(condPiece(cf, a, left, c, right))
                }
            }
        }
        if (pieces.isEmpty() && !condTouches(fr1, fc1, fr2, fc2)) return false
        cutCondFormats(fr1, fc1, fr2, fc2)
        // In order, so the tiles a rule's fill makes join it one by one; others go first, in their rules' order.
        var insertAt = 0
        for (p in pieces) {
            val i = sheet.condFormats.indexOfFirst { x ->
                val beside = if (vertical) x.c1 == p.c1 && x.c2 == p.c2 && (x.r2 + 1 == p.r1 || p.r2 + 1 == x.r1)
                    else x.r1 == p.r1 && x.r2 == p.r2 && (x.c2 + 1 == p.c1 || p.c2 + 1 == x.c1)
                beside && condPiece(x, p.r1, p.c1, p.r2, p.c2) == p
            }
            if (i < 0) { sheet.condFormats.add(insertAt++, p); continue }
            val x = sheet.condFormats[i]
            sheet.condFormats[i] = condPiece(x, minOf(x.r1, p.r1), minOf(x.c1, p.c1), maxOf(x.r2, p.r2), maxOf(x.c2, p.c2))
        }
        return true
    }

    /** Whether a conditional format reaches the block [r1]…[c2]. */
    private fun condTouches(r1: Int, c1: Int, r2: Int, c2: Int): Boolean =
        sheet.condFormats.any { it.r1 <= r2 && it.r2 >= r1 && it.c1 <= c2 && it.c2 >= c1 }

    /** The block [r1]…[c2] taken out of the conditional formats' ranges (Clear Formats, a paste over them): what is left of each rule stays, in its place. */
    private fun cutCondFormats(r1: Int, c1: Int, r2: Int, c2: Int) {
        val out = ArrayList<com.therealityteam.aeropages.model.CondFormat>()
        for (cf in sheet.condFormats) {
            if (cf.r2 < r1 || cf.r1 > r2 || cf.c2 < c1 || cf.c1 > c2) { out.add(cf); continue }
            val top = maxOf(cf.r1, r1); val bottom = minOf(cf.r2, r2)
            if (cf.r1 < r1) out.add(condPiece(cf, cf.r1, cf.c1, r1 - 1, cf.c2))
            if (cf.r2 > r2) out.add(condPiece(cf, r2 + 1, cf.c1, cf.r2, cf.c2))
            if (cf.c1 < c1) out.add(condPiece(cf, top, cf.c1, bottom, c1 - 1))
            if (cf.c2 > c2) out.add(condPiece(cf, top, c2 + 1, bottom, cf.c2))
        }
        sheet.condFormats.clear(); sheet.condFormats.addAll(out)
    }

    /** Each cell's rule in the block, on that cell alone (its relative references following it); null when no rule reaches the block. */
    private fun rulesOf(r1: Int, c1: Int, r2: Int, c2: Int): List<List<DataValidation?>>? {
        if (sheet.validations.none { it.r1 <= r2 && it.r2 >= r1 && it.c1 <= c2 && it.c2 >= c1 }) return null
        return (r1..r2).map { r -> (c1..c2).map { c -> validationAt(r, c)?.let { piece(it, r, c, r, c) } } }
    }

    /**
     * Excel's paste and fill carry each cell's validation: the block [r1]…[c2] loses its own rules
     * and each of its cells takes the one [ruleOf] gives (a copied cell's rule on its own cell,
     * moved there — its relative references following), adjoining copies of one rule joined into
     * one again. False when no rule was there or came.
     */
    private fun placeRules(r1: Int, c1: Int, r2: Int, c2: Int, ruleOf: (Int, Int) -> DataValidation?): Boolean {
        val placed = ArrayList<DataValidation>()
        for (r in r1..r2) for (c in c1..c2) ruleOf(r, c)?.let { placed.add(piece(it, r, c, r, c)) }
        if (placed.isEmpty() && sheet.validations.none { it.r1 <= r2 && it.r2 >= r1 && it.c1 <= c2 && it.c2 >= c1 }) return false
        cutValidations(r1, c1, r2, c2)
        sheet.validations.addAll(placed)
        joinRules()
        return true
    }

    /** Whether a rule reaches the block or one of [sources] has one — a fill that must keep the sheet's rules in its undo. */
    private fun rulesTouch(r1: Int, c1: Int, r2: Int, c2: Int): Boolean = sheet.validations.any { it.r1 <= r2 && it.r2 >= r1 && it.c1 <= c2 && it.c2 >= c1 }

    /**
     * Rules that are one rule on adjoining blocks — the same but for where they sit, their formulas
     * following — joined into one, down the columns, then along the rows, until none join.
     */
    private fun joinRules() {
        var rules: List<DataValidation> = sheet.validations.toList()
        while (true) {
            val joined = sweep(sweep(rules, down = true), down = false)
            if (joined.size == rules.size) break
            rules = joined
        }
        sheet.validations.clear(); sheet.validations.addAll(rules)
    }

    private fun sweep(rules: List<DataValidation>, down: Boolean): List<DataValidation> {
        val sorted = if (down) rules.sortedWith(compareBy({ it.c1 }, { it.c2 }, { it.r1 })) else rules.sortedWith(compareBy({ it.r1 }, { it.r2 }, { it.c1 }))
        val out = ArrayList<DataValidation>(sorted.size)
        for (dv in sorted) {
            val last = out.lastOrNull()
            val next = last != null && (if (down) last.c1 == dv.c1 && last.c2 == dv.c2 && dv.r1 == last.r2 + 1 else last.r1 == dv.r1 && last.r2 == dv.r2 && dv.c1 == last.c2 + 1)
            if (next && piece(last!!, dv.r1, dv.c1, dv.r2, dv.c2) == dv) out[out.lastIndex] = if (down) last.copy(r2 = dv.r2) else last.copy(c2 = dv.c2)
            else out.add(dv)
        }
        return out
    }

    fun pasteSelection() { pasteSpecial(PasteWhat.ALL) }

    /** The ways Excel's Paste Options button offers to redo a paste. */
    enum class PasteKind { ALL, FORMULAS, VALUES, VALUES_AND_NUMBER_FORMATS, FORMATS, TRANSPOSE, LINK }

    /** A paste just made: the sheet, the block it covered, how it pasted, and the content revision right after it. */
    class PasteMark(val sheet: Int, val r1: Int, val c1: Int, val r2: Int, val c2: Int, val kind: PasteKind, internal val rev: Int)
    private var lastPaste: PasteMark? = null

    /**
     * The paste Excel's Paste Options button is for: the last paste, while nothing has been edited since and its sheet
     * is the one shown (null otherwise — typing, another command or undo puts the button away, as in Excel).
     */
    val pasteOptions: PasteMark? get() = lastPaste?.takeIf { it.rev == contentRev && it.sheet == sheetIndex }

    private fun markPaste(r1: Int, c1: Int, r2: Int, c2: Int, kind: PasteKind) { lastPaste = PasteMark(sheetIndex, r1, c1, r2, c2, kind, contentRev) }

    /** After a paste the pasted block is the selection, its first cell active, as Excel leaves it (undo puts the old one back). */
    private fun selectPasted(r1: Int, c1: Int, r2: Int, c2: Int) {
        if (r1 == r2 && c1 == c2) return
        selRow = r1; selCol = c1; selRow2 = r2; selCol2 = c2
        bump()
    }

    /**
     * Paste Options: the last paste made again as [kind] — undone, then pasted that way over the same selection (the
     * undo puts the selection back). One undo step remains, the new paste; false when there is no such paste or the
     * new one is refused.
     */
    fun repaste(kind: PasteKind): Boolean {
        val mark = pasteOptions ?: return false
        if (mark.kind == kind) return true
        undo()
        return when (kind) {
            PasteKind.ALL -> pasteSpecial(PasteWhat.ALL)
            PasteKind.FORMULAS -> pasteSpecial(PasteWhat.FORMULAS)
            PasteKind.VALUES -> pasteSpecial(PasteWhat.VALUES)
            PasteKind.VALUES_AND_NUMBER_FORMATS -> pasteSpecial(PasteWhat.VALUES_AND_NUMBER_FORMATS)
            PasteKind.FORMATS -> pasteSpecial(PasteWhat.FORMATS)
            PasteKind.TRANSPOSE -> pasteSpecial(PasteWhat.ALL, transpose = true)
            PasteKind.LINK -> pasteLink()
        }
    }

    /** What Paste Special takes from the copied cells (Excel's Paste options). */
    enum class PasteWhat { ALL, FORMULAS, VALUES, VALUES_AND_NUMBER_FORMATS, FORMATS, COMMENTS, VALIDATION }

    /** Paste Special's operation: the copied numbers added to, taken from, multiplying or dividing the numbers pasted onto. */
    enum class PasteOp { NONE, ADD, SUBTRACT, MULTIPLY, DIVIDE }

    /** Where a paste lands: its top-left corner, its size, and the copied block's (possibly turned) size it repeats. */
    private class PasteArea(val r0: Int, val c0: Int, val rows: Int, val cols: Int, val h: Int, val w: Int)

    /**
     * The copied block ([h] × [w], turned when transposed) lands at the active cell — or over the
     * selection, filling it with copies when it holds the block a whole number of times (Excel's
     * tiling of a copied cell over a selected range).
     */
    private fun pasteArea(h: Int, w: Int): PasteArea {
        val selH = rangeRowMax() - rangeRowMin() + 1
        val selW = rangeColMax() - rangeColMin() + 1
        return if ((selH > 1 || selW > 1) && selH % h == 0 && selW % w == 0) PasteArea(rangeRowMin(), rangeColMin(), selH, selW, h, w)
        else PasteArea(selRow, selCol, h, w, h, w)
    }

    /**
     * Excel's Paste and Paste Special: the copied cells over [pasteArea] — all of each cell, just its
     * formula, its value as copied, its value with its number format, its formats or its comment;
     * with [op] the copied numbers are added to (taken from, multiply, divide) the numbers pasted
     * onto (a formula there gains the operation, `=(A1*2)+5`); [skipBlanks] leaves cells under
     * copied blanks as they are; [transpose] turns rows into columns. Relative references move by
     * each copy's offset from where it was copied (a cut keeps them on the original data). One undo
     * step; refused where a protected sheet locks a cell. False when nothing was copied here.
     */
    fun pasteSpecial(what: PasteWhat, op: PasteOp = PasteOp.NONE, skipBlanks: Boolean = false, transpose: Boolean = false): Boolean {
        commitEntry()
        val clip = clipboard ?: return false
        val srcRows = clip.size
        val srcCols = clip.maxOfOrNull { it.size } ?: 0
        if (srcRows == 0 || srcCols == 0) return false
        val a = pasteArea(if (transpose) srcCols else srcRows, if (transpose) srcRows else srcCols)
        val r2 = a.r0 + a.rows - 1
        val c2 = minOf(a.c0 + a.cols - 1, Sheet.MAX_COLS - 1)
        if (refuseIfProtected(a.r0, a.c0, r2, c2)) return false
        // The values pasted onto, read before anything changes (the operations combine with them).
        val before = if (op == PasteOp.NONE) null else (a.r0..r2).map { r -> (a.c0..c2).map { c -> rawValue(r, c) } }
        pushUndo(both(cells(a.r0, a.c0, r2, c2), sheetMeta()))
        val origin = clipOrigin
        for (dr in 0 until a.rows) for (dc in 0 until a.cols) {
            val r = a.r0 + dr
            val c = a.c0 + dc
            if (c >= Sheet.MAX_COLS) continue
            val sr = if (transpose) dc % a.w else dr % a.h
            val sc = if (transpose) dr % a.h else dc % a.w
            val src = clip.getOrNull(sr)?.getOrNull(sc) ?: Cell()
            if (skipBlanks && src.text.isEmpty()) continue
            if (r >= sheet.rowCount) sheet.rowCount = r + 1
            if (c >= sheet.colCount) sheet.colCount = c + 1
            // Excel semantics: a copy's relative references move by its offset from where it was
            // copied; a cut keeps formulas pointing at their original data.
            val dRow = if (origin != null) r - (origin.first + sr) else 0
            val dCol = if (origin != null) c - (origin.second + sc) else 0
            val formula0 = when {
                !src.text.startsWith("=") || clipCut || origin == null -> src.text
                // Turned rows into columns: each reference's offset from its formula turns too (A3's =A1+A2 → E1's =C1+D1).
                transpose -> transposeFormulaRefs(src.text, origin.first + sr, origin.second + sc, r, c)
                dRow != 0 || dCol != 0 -> shiftFormulaRefs(src.text, dRow, dCol)
                else -> src.text
            }
            val value = clipValues?.getOrNull(sr)?.getOrNull(sc) ?: src.text
            val formula = leavingTable(formula0, workbook.sheets.getOrNull(clipSheet), origin?.let { it.first + sr }, origin?.let { it.second + sc }, r, c)
            val target = sheet.cell(r, c)
            val content = when (what) {
                PasteWhat.ALL, PasteWhat.FORMULAS -> formula
                PasteWhat.VALUES, PasteWhat.VALUES_AND_NUMBER_FORMATS -> value
                else -> null
            }
            val combined = content?.let { if (op == PasteOp.NONE) it else combine(target?.text ?: "", before!![dr][dc], it, value, op) }
            when (what) {
                PasteWhat.ALL -> sheet.cells[sheet.key(r, c)] = src.copyOf().also { it.text = combined!!; if (combined != src.text) it.runs = null }
                PasteWhat.FORMULAS -> sheet.ensure(r, c).apply {
                    text = combined!!
                    runs = if (combined == src.text && !src.text.startsWith("=")) src.runs?.map { com.therealityteam.aeropages.model.TextRun(it.text, it.format.copyOf()) }?.toMutableList() else null
                }
                PasteWhat.VALUES -> sheet.ensure(r, c).apply { text = combined!!; runs = null }
                PasteWhat.VALUES_AND_NUMBER_FORMATS -> sheet.ensure(r, c).apply {
                    text = combined!!; runs = null
                    numberFormat = src.numberFormat; decimals = src.decimals; formatCode = src.formatCode
                }
                PasteWhat.FORMATS -> formatInto(sheet.ensure(r, c), src)
                PasteWhat.COMMENTS -> if (src.comment != null) sheet.ensure(r, c).let { it.comment = src.comment; it.commentAuthor = src.commentAuthor; it.commentDate = src.commentDate; it.commentShown = src.commentShown }
                PasteWhat.VALIDATION -> Unit
            }
        }
        // All, or Validation: the copied cells' rules replace the pasted-onto cells' (none where they had none).
        if (what == PasteWhat.ALL || what == PasteWhat.VALIDATION) {
            val rules = clipRules
            placeRules(a.r0, a.c0, a.r0 + a.rows - 1, c2) { r, c ->
                val dr = r - a.r0; val dc = c - a.c0
                rules?.getOrNull(if (transpose) dc % a.w else dr % a.h)?.getOrNull(if (transpose) dr % a.h else dc % a.w)
            }
        }
        // All, or Formats: the copied cells' conditional formats come with them — each a rule over the cells it lands
        // on (each copy's), first in order as Excel puts them — and the pasted-onto cells' own go there.
        if ((what == PasteWhat.ALL || what == PasteWhat.FORMATS) && !transpose) {
            val pieces = clipCond
            if (pieces != null || condTouches(a.r0, a.c0, r2, c2)) {
                cutCondFormats(a.r0, a.c0, r2, c2)
                val placed = ArrayList<com.therealityteam.aeropages.model.CondFormat>()
                if (pieces != null) for (ti in 0 until a.rows / a.h) for (tj in 0 until a.cols / a.w) for ((at, cf) in pieces) {
                    val nr1 = a.r0 + ti * a.h + at[0]; val nc1 = a.c0 + tj * a.w + at[1]
                    if (nc1 > c2) continue
                    placed.add(condPiece(cf, nr1, nc1, a.r0 + ti * a.h + at[2], minOf(a.c0 + tj * a.w + at[3], c2)))
                }
                sheet.condFormats.addAll(0, placed)
            }
        }
        touchContent()
        // The kinds the Paste Options button offers (not Paste Special's operations, blanks skipped, comments, rules).
        val kind = when {
            op != PasteOp.NONE || skipBlanks || clipCut -> null   // a cut's paste moves the cells: Excel offers no options
            transpose -> if (what == PasteWhat.ALL) PasteKind.TRANSPOSE else null
            else -> when (what) {
                PasteWhat.ALL -> PasteKind.ALL; PasteWhat.FORMULAS -> PasteKind.FORMULAS; PasteWhat.VALUES -> PasteKind.VALUES
                PasteWhat.VALUES_AND_NUMBER_FORMATS -> PasteKind.VALUES_AND_NUMBER_FORMATS; PasteWhat.FORMATS -> PasteKind.FORMATS
                else -> null
            }
        }
        if (kind != null) markPaste(a.r0, a.c0, r2, c2, kind) else lastPaste = null
        selectPasted(a.r0, a.c0, r2, c2)
        return true
    }

    /**
     * Paste Special's arithmetic on one cell: its [existing] text (a formula gains the operation)
     * or its evaluated [was] value, with the copied [value]; blank counts as 0. Text on either side
     * — or a division by zero — leaves the pasted [content] or the cell as Excel does.
     */
    private fun combine(existing: String, was: String, content: String, value: String, op: PasteOp): String {
        val n = value.trim().toDoubleOrNull() ?: return content
        val sign = when (op) { PasteOp.ADD -> "+"; PasteOp.SUBTRACT -> "-"; PasteOp.MULTIPLY -> "*"; PasteOp.DIVIDE -> "/"; PasteOp.NONE -> return content }
        if (existing.startsWith("=")) return "=(" + existing.substring(1) + ")" + sign + FormulaEngine.numberText(n)
        val base = if (was.isBlank()) 0.0 else was.trim().toDoubleOrNull() ?: return existing
        val result = when (op) {
            PasteOp.ADD -> base + n; PasteOp.SUBTRACT -> base - n; PasteOp.MULTIPLY -> base * n
            else -> if (n == 0.0) return "#DIV/0!" else base / n
        }
        return FormulaEngine.numberText(result)
    }

    /**
     * Excel's Paste Link: each cell of the pasted block a reference to the copied cell it stands
     * for (`=B2`, `='Q3 Sales'!B2` from another sheet), so it follows the original. One undo step.
     */
    fun pasteLink(): Boolean {
        commitEntry()
        val clip = clipboard ?: return false
        val origin = clipOrigin ?: return false
        val source = workbook.sheets.getOrNull(clipSheet) ?: return false
        val rows = clip.size
        val cols = clip.maxOfOrNull { it.size } ?: 0
        if (rows == 0 || cols == 0) return false
        val a = pasteArea(rows, cols)
        val r2 = a.r0 + a.rows - 1
        val c2 = minOf(a.c0 + a.cols - 1, Sheet.MAX_COLS - 1)
        if (refuseIfProtected(a.r0, a.c0, r2, c2)) return false
        pushUndo(both(cells(a.r0, a.c0, r2, c2), sheetMeta()))
        val prefix = if (clipSheet == sheetIndex) "" else A1.quoteSheet(source.name) + "!"
        for (dr in 0 until a.rows) for (dc in 0 until a.cols) {
            val r = a.r0 + dr
            val c = a.c0 + dc
            if (c >= Sheet.MAX_COLS) continue
            if (r >= sheet.rowCount) sheet.rowCount = r + 1
            if (c >= sheet.colCount) sheet.colCount = c + 1
            sheet.ensure(r, c).apply { text = "=" + prefix + A1.ref(origin.first + dr % a.h, origin.second + dc % a.w); runs = null }
        }
        touchContent()
        markPaste(a.r0, a.c0, r2, c2, PasteKind.LINK)
        selectPasted(a.r0, a.c0, r2, c2)
        return true
    }

    /**
     * The references in a formula copied from ([fromRow], [fromCol]) and pasted transposed at
     * ([toRow], [toCol]): a fully relative reference keeps its offset with rows and columns swapped;
     * a partly anchored one moves its relative part as a plain paste would; an absolute one stays.
     */
    private fun transposeFormulaRefs(text: String, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int): String =
        com.therealityteam.aeropages.model.StructuredRefs.outsideLiterals(text) { code -> transposeCode(code, fromRow, fromCol, toRow, toCol) }

    private fun transposeCode(text: String, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int): String =
        a1RefRx.replace(text) { m ->
            val g = m.groupValues
            fun turn(dollarC: String, colS: String, dollarR: String, rowS: String): String? {
                val c0 = colIndexOf(colS)
                val r0 = rowS.toInt() - 1
                val (r, c) = when {
                    dollarC.isEmpty() && dollarR.isEmpty() -> (toRow + (c0 - fromCol)) to (toCol + (r0 - fromRow))
                    else -> (if (dollarR.isEmpty()) r0 + (toRow - fromRow) else r0) to (if (dollarC.isEmpty()) c0 + (toCol - fromCol) else c0)
                }
                if (r < 0 || c < 0) return null
                return "$dollarC${Workbook.colLabel(c)}$dollarR${r + 1}"
            }
            val head = turn(g[2], g[3], g[4], g[5]) ?: return@replace "#REF!"
            if (g[7].isEmpty()) "${g[1]}$head"
            else {
                val tail = turn(g[6], g[7], g[8], g[9]) ?: return@replace "#REF!"
                "${g[1]}$head:$tail"
            }
        }

    /** Shift relative A1 refs in [text] by (dRow,dCol); `$`-anchored parts stay; negative → #REF!. */
    private fun shiftFormulaRefs(text: String, dRow: Int, dCol: Int): String =
        com.therealityteam.aeropages.model.StructuredRefs.outsideLiterals(text) { code -> shiftCode(code, dRow, dCol) }

    private fun shiftCode(text: String, dRow: Int, dCol: Int): String =
        a1RefRx.replace(text) { m ->
            val g = m.groupValues
            fun shift(dollarC: String, colS: String, dollarR: String, rowS: String): String? {
                var c = colIndexOf(colS); var r = rowS.toInt() - 1
                if (dollarC.isEmpty()) c += dCol
                if (dollarR.isEmpty()) r += dRow
                if (c < 0 || r < 0) return null
                return "$dollarC${Workbook.colLabel(c)}$dollarR${r + 1}"
            }
            val head = shift(g[2], g[3], g[4], g[5]) ?: return@replace "#REF!"
            if (g[7].isEmpty()) "${g[1]}$head"
            else {
                val tail = shift(g[6], g[7], g[8], g[9]) ?: return@replace "#REF!"
                "${g[1]}$head:$tail"
            }
        }

    /** The selection's evaluated values as TSV — the cross-module clipboard payload. */
    fun selectionTsv(): String = buildString {
        for (r in rangeRowMin()..rangeRowMax()) {
            append((rangeColMin()..rangeColMax()).joinToString("\t") { c -> display(r, c) })
            if (r < rangeRowMax()) append('\n')
        }
    }

    /** Paste tab-separated text (from Pages/Slides/外部) into cells at the selection. */
    fun pasteTsv(text: String) {
        if (refuseIfProtected()) return
        if (text.isBlank()) return
        val lines = text.trimEnd('\n').split('\n')
        pushUndo(both(cells(selRow, selCol, selRow + lines.size - 1, selCol + lines.maxOf { it.split('\t').size } - 1), sheetMeta()))
        val r0 = selRow; val c0 = selCol
        lines.forEachIndexed { dr, line ->
            line.split('\t').forEachIndexed { dc, value ->
                val r = r0 + dr; val c = c0 + dc
                if (c >= Sheet.MAX_COLS) return@forEachIndexed
                if (r >= sheet.rowCount) sheet.rowCount = r + 1
                if (c >= sheet.colCount) sheet.colCount = c + 1
                // Read as typing reads it (1,234.50 → a number in the thousands format; 1,5 in German → 1.5).
                val cell = sheet.ensure(r, c)
                val e = if (value.startsWith("=")) null else EntryParser.parse(value, symbols = symbols)
                cell.text = e?.value ?: value
                if (e != null && cell.formatCode == null && cell.numberFormat == com.therealityteam.aeropages.model.NumberFormat.GENERAL) {
                    cell.numberFormat = e.code?.let { com.therealityteam.aeropages.model.FormatCode.category(it) } ?: e.format
                    cell.decimals = e.decimals
                    cell.formatCode = e.code
                }
            }
        }
        touchContent()
    }

    /** Excel's Clear menu. */
    enum class ClearWhat { ALL, FORMATS, CONTENTS, COMMENTS, HYPERLINKS }

    /**
     * Excel's Clear on the selection: everything (the cells go, merges are undone), the formats
     * (fonts, fills, borders, number formats, alignment, locking — merges undone, the text stays),
     * the contents (values and formulas — what Delete does; formats, comments and links stay), the
     * comments or the hyperlinks. A cell left with nothing is dropped. One undo step; a protected
     * sheet refuses formats, comments and everything, and its locked cells refuse the rest.
     */
    fun clear(what: ClearWhat, rules: Boolean = false) {
        commitEntry()
        if (spread { clear(what, rules) }) return
        val sheetWide = what == ClearWhat.ALL || what == ClearWhat.FORMATS || what == ClearWhat.COMMENTS
        val refused = when {
            what == ClearWhat.FORMATS -> refuseIfSheetProtected(Allow.FORMAT_CELLS)
            sheetWide -> refuseIfSheetProtected()
            else -> refuseIfProtected()
        }
        if (refused) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val unmerge = (what == ClearWhat.ALL || what == ClearWhat.FORMATS) && sheet.merges.any { it.intersects(r1, c1, r2, c2) }
        // Clear All and Clear Formats take the cells' conditional formats too, as in Excel (a cut moves them).
        val uncond = (what == ClearWhat.ALL || what == ClearWhat.FORMATS) && condTouches(r1, c1, r2, c2)
        pushUndo(if (unmerge || rules || uncond) both(sheetMeta(), selectionCells()) else selectionCells())
        if (unmerge) sheet.merges.removeAll { it.intersects(r1, c1, r2, c2) }
        if (uncond) cutCondFormats(r1, c1, r2, c2)
        // A cut takes the cells' validation rules with them.
        if (rules) cutValidations(r1, c1, r2, c2)
        for (r in r1..r2) for (c in c1..c2) {
            val key = sheet.key(r, c)
            val cell = sheet.cells[key] ?: continue
            when (what) {
                ClearWhat.ALL -> { sheet.cells.remove(key); continue }
                ClearWhat.CONTENTS -> { cell.text = ""; cell.runs = null }
                ClearWhat.FORMATS -> {
                    cell.format.assignFrom(CharacterFormat()); cell.runs = null
                    cell.align = null; cell.vAlign = com.therealityteam.aeropages.model.VAlign.MIDDLE; cell.wrap = false
                    cell.rotation = 0; cell.stacked = false; cell.indent = 0; cell.spread = null; cell.shrink = false
                    cell.numberFormat = com.therealityteam.aeropages.model.NumberFormat.GENERAL; cell.decimals = -1; cell.formatCode = null
                    cell.borderTop = null; cell.borderBottom = null; cell.borderLeft = null; cell.borderRight = null
                    cell.unlocked = false
                }
                ClearWhat.COMMENTS -> { cell.comment = null; cell.commentAuthor = null; cell.commentDate = 0L; cell.commentShown = false }
                ClearWhat.HYPERLINKS -> cell.link = null
            }
            if (isBlankCell(cell)) sheet.cells.remove(key)
        }
        touchContent()
    }

    /** Whether [cell] holds nothing a default one would not: no text, comment, link or format. */
    private fun isBlankCell(cell: Cell): Boolean =
        cell.text.isEmpty() && cell.comment == null && cell.link == null && cell.runs == null && plainFormat(cell) && !cell.unlocked

    /** Whether [cell] has the formats a new one has (what [formatInto] gives). */
    private fun plainFormat(cell: Cell): Boolean =
        cell.format == CharacterFormat() && cell.align == null && cell.vAlign == com.therealityteam.aeropages.model.VAlign.MIDDLE && !cell.wrap &&
            cell.rotation == 0 && !cell.stacked && cell.indent == 0 && cell.spread == null && !cell.shrink &&
            cell.numberFormat == com.therealityteam.aeropages.model.NumberFormat.GENERAL && cell.decimals == -1 && cell.formatCode == null &&
            cell.borderTop == null && cell.borderBottom == null && cell.borderLeft == null && cell.borderRight == null

    /** Delete: Excel's Clear Contents — values and formulas go, formats, comments and links stay. */
    fun clearSelection() = clear(ClearWhat.CONTENTS)

    /** Excel's Fill Down (Ctrl+D): the block's top row copied into the rows under it, relative references moving along. */
    fun fillDown() {
        commitEntry()
        if (refuseIfProtected()) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val rules = rulesOf(r1, c1, r1, c2)
        pushUndo(if (rules != null || rulesTouch(r1, c1, r2, c2) || condTouches(r1, c1, r2, c2)) both(selectionCells(), sheetMeta()) else selectionCells())
        for (c in c1..c2) {
            val src = sheet.cell(r1, c)
            for (r in r1 + 1..r2) putCopy(r, c, src, r - r1, 0)
        }
        // The top row's rules too, as Excel's fill carries a cell's validation and conditional formats.
        if (r2 > r1) placeRules(r1 + 1, c1, r2, c2) { _, c -> rules?.get(0)?.get(c - c1) }
        if (r2 > r1) fillCondFormats(r1, c1, r1, c2, r1 + 1, c1, r2, c2, vertical = true)
        touchContent()
    }

    /** Excel's Fill Right (Ctrl+R): the block's left column copied into the columns beside it. */
    fun fillRight() {
        commitEntry()
        if (refuseIfProtected()) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val rules = rulesOf(r1, c1, r2, c1)
        pushUndo(if (rules != null || rulesTouch(r1, c1, r2, c2) || condTouches(r1, c1, r2, c2)) both(selectionCells(), sheetMeta()) else selectionCells())
        for (r in r1..r2) {
            val src = sheet.cell(r, c1)
            for (c in c1 + 1..c2) putCopy(r, c, src, 0, c - c1)
        }
        if (c2 > c1) placeRules(r1, c1 + 1, r2, c2) { r, _ -> rules?.get(r - r1)?.get(0) }
        if (c2 > c1) fillCondFormats(r1, c1, r2, c1, r1, c1 + 1, r2, c2, vertical = false)
        touchContent()
    }

    /** Excel's Fill Up: the block's bottom row copied into the rows above it. */
    fun fillUp() {
        commitEntry()
        if (refuseIfProtected()) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val rules = rulesOf(r2, c1, r2, c2)
        pushUndo(if (rules != null || rulesTouch(r1, c1, r2, c2) || condTouches(r1, c1, r2, c2)) both(selectionCells(), sheetMeta()) else selectionCells())
        for (c in c1..c2) {
            val src = sheet.cell(r2, c)
            for (r in r1 until r2) putCopy(r, c, src, r - r2, 0)
        }
        if (r2 > r1) placeRules(r1, c1, r2 - 1, c2) { _, c -> rules?.get(0)?.get(c - c1) }
        if (r2 > r1) fillCondFormats(r2, c1, r2, c2, r1, c1, r2 - 1, c2, vertical = true)
        touchContent()
    }

    /** Excel's Fill Left: the block's right column copied into the columns before it. */
    fun fillLeft() {
        commitEntry()
        if (refuseIfProtected()) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val rules = rulesOf(r1, c2, r2, c2)
        pushUndo(if (rules != null || rulesTouch(r1, c1, r2, c2) || condTouches(r1, c1, r2, c2)) both(selectionCells(), sheetMeta()) else selectionCells())
        for (r in r1..r2) {
            val src = sheet.cell(r, c2)
            for (c in c1 until c2) putCopy(r, c, src, 0, c - c2)
        }
        if (c2 > c1) placeRules(r1, c1, r2, c2 - 1) { r, _ -> rules?.get(r - r1)?.get(0) }
        if (c2 > c1) fillCondFormats(r1, c2, r2, c2, r1, c1, r2, c2 - 1, vertical = false)
        touchContent()
    }

    // --- Format Painter -------------------------------------------------------------------------

    /** What Format Painter took: the block's cells (their formats) and the conditional formats on it, placed in it. */
    private class PainterClip(val cells: List<List<Cell>>, val cond: List<Pair<IntArray, com.therealityteam.aeropages.model.CondFormat>>?)
    private var painter: PainterClip? = null

    /** Format Painter has taken a block's formats: the next cells picked take them. */
    var painterArmed by mutableStateOf(false)
        private set

    /** Excel's Format Painter: takes the selected cells' formats for the next cells picked; pressed again, puts it down. */
    fun formatPainter() {
        commitEntry()
        if (painterArmed) { cancelPainter(); return }
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        painter = PainterClip((r1..r2).map { r -> (c1..c2).map { c -> sheet.cell(r, c)?.copyOf() ?: Cell() } }, condPiecesOf(r1, c1, r2, c2))
        painterArmed = true
        bump()
    }

    /** Puts Format Painter down without painting (Esc). */
    fun cancelPainter() {
        if (painter == null && !painterArmed) return
        painter = null; painterArmed = false
        bump()
    }

    /**
     * Paints the formats Format Painter took onto the selection — the block they came from repeated
     * over it, or, picked as one cell, the block's own size from there, as Excel does — conditional
     * formats too, and selects what it painted. One undo step; false when not armed or the sheet's
     * protection refuses.
     */
    fun paintFormats(): Boolean {
        val p = painter ?: return false
        painter = null; painterArmed = false
        val h = p.cells.size; val w = p.cells.firstOrNull()?.size ?: 0
        if (h == 0 || w == 0) { bump(); return false }
        val one = rangeRowMin() == rangeRowMax() && rangeColMin() == rangeColMax()
        val r1 = rangeRowMin(); val c1 = rangeColMin()
        val r2 = if (one) r1 + h - 1 else rangeRowMax()
        val c2 = minOf(if (one) c1 + w - 1 else rangeColMax(), Sheet.MAX_COLS - 1)
        if (refuseIfProtected(r1, c1, r2, c2)) { bump(); return false }
        if (r2 >= sheet.rowCount) sheet.rowCount = r2 + 1
        if (c2 >= sheet.colCount) sheet.colCount = c2 + 1
        pushUndo(both(cells(r1, c1, r2, c2), sheetMeta()))
        for (r in r1..r2) for (c in c1..c2) {
            val src = p.cells[(r - r1) % h][(c - c1) % w]
            val key = sheet.key(r, c)
            val target = sheet.cells[key] ?: if (plainFormat(src)) continue else sheet.ensure(r, c)
            formatInto(target, src)
            if (isBlankCell(target)) sheet.cells.remove(key)
        }
        // The conditional formats on the block it came from, over each copy of it (cut where the selection ends).
        if (p.cond != null || condTouches(r1, c1, r2, c2)) {
            cutCondFormats(r1, c1, r2, c2)
            val placed = ArrayList<com.therealityteam.aeropages.model.CondFormat>()
            if (p.cond != null) for (ti in 0 until (r2 - r1 + h) / h) for (tj in 0 until (c2 - c1 + w) / w) for ((at, cf) in p.cond) {
                val nr1 = r1 + ti * h + at[0]; val nc1 = c1 + tj * w + at[1]
                if (nr1 > r2 || nc1 > c2) continue
                placed.add(condPiece(cf, nr1, nc1, minOf(r1 + ti * h + at[2], r2), minOf(c1 + tj * w + at[3], c2)))
            }
            sheet.condFormats.addAll(0, placed)
        }
        if (one && (r2 > r1 || c2 > c1)) selectRange(r1, c1, r2, c2)
        touchContent()
        return true
    }

    /** [dst] takes [src]'s formats: font and fill, alignment, wrapping, number format and borders (its text stays). */
    private fun formatInto(dst: Cell, src: Cell) {
        dst.format.assignFrom(src.format); dst.align = src.align; dst.vAlign = src.vAlign; dst.wrap = src.wrap
        dst.rotation = src.rotation; dst.stacked = src.stacked; dst.indent = src.indent; dst.spread = src.spread; dst.shrink = src.shrink
        dst.numberFormat = src.numberFormat; dst.decimals = src.decimals; dst.formatCode = src.formatCode
        dst.borderTop = src.borderTop; dst.borderBottom = src.borderBottom; dst.borderLeft = src.borderLeft; dst.borderRight = src.borderRight
    }

    /** Puts a copy of [src] at ([r], [c]) — a formula's relative references moved by ([dRow], [dCol]) — or clears the cell when [src] is null. */
    private fun putCopy(r: Int, c: Int, src: Cell?, dRow: Int, dCol: Int, text: String? = null) {
        if (src == null) { sheet.cells.remove(sheet.key(r, c)); return }
        val cell = src.copyOf()
        when {
            text != null -> { cell.text = text; cell.runs = null }
            cell.text.startsWith("=") && (dRow != 0 || dCol != 0) -> cell.text = leavingTable(shiftFormulaRefs(cell.text, dRow, dCol), sheet, r - dRow, c - dCol, r, c)
        }
        sheet.cells[sheet.key(r, c)] = cell
    }

    /**
     * A formula copied from ([fromRow], [fromCol]) of [from] to ([r], [c]) of this sheet: leaving the
     * table it was written in, its references without a table name name that table (`[@Qty]` →
     * `Sales[@Qty]`), as Excel's copies do.
     */
    private fun leavingTable(formula: String, from: Sheet?, fromRow: Int?, fromCol: Int?, r: Int, c: Int): String {
        if (!formula.startsWith("=") || formula.indexOf('[') < 0 || from == null || fromRow == null || fromCol == null) return formula
        val table = from.tableAt(fromRow, fromCol) ?: return formula
        if (from === sheet && sheet.tableAt(r, c) === table) return formula
        return com.therealityteam.aeropages.model.StructuredRefs.qualify(formula, table.name)
    }

    /**
     * Excel's Fill Series over the selection: taller than wide fills down, else right. In each line
     * the cells up to the first blank one are the seeds and the blanks after them are filled with
     * the series ([AutoFill]; a lone number counts up by 1, text continues its number or list).
     * False when there is nothing to fill.
     */
    fun fillSeries(): Boolean {
        commitEntry()
        if (refuseIfProtected()) return false
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (r1 == r2 && c1 == c2) return false
        val down = (r2 - r1) >= (c2 - c1)
        // Each cell's rule as it was, and the seed each filled cell copies (whose rule it takes).
        val rules = rulesOf(r1, c1, r2, c2)
        val seedOf = HashMap<Long, Pair<Int, Int>>()
        pushUndo(if (rules != null) both(selectionCells(), sheetMeta()) else selectionCells())
        var filled = false
        val lines = if (down) c1..c2 else r1..r2
        for (line in lines) {
            val length = if (down) r2 - r1 + 1 else c2 - c1 + 1
            fun cellAt(i: Int) = if (down) sheet.cell(r1 + i, line) else sheet.cell(line, c1 + i)
            var n = 0
            while (n < length && !cellAt(n)?.text.isNullOrBlank()) n++
            if (n == 0 || n == length) continue
            val seeds = (0 until n).map { cellAt(it) }
            val steps = AutoFill.fill(seeds.map { AutoFill.Seed(it!!.text, it.numberFormat) }, (n until length).toList(), series = true)
            steps.forEachIndexed { k, st ->
                val i = n + k
                if (down) putCopy(r1 + i, line, seeds[st.copyOf], st.shift, 0, st.text)
                else putCopy(line, c1 + i, seeds[st.copyOf], 0, st.shift, st.text)
                if (down) seedOf[sheet.key(r1 + i, line)] = (r1 + st.copyOf) to line else seedOf[sheet.key(line, c1 + i)] = line to (c1 + st.copyOf)
            }
            filled = true
        }
        if (rules != null) placeRules(r1, c1, r2, c2) { r, c -> (seedOf[sheet.key(r, c)] ?: (r to c)).let { (sr, sc) -> rules[sr - r1][sc - c1] } }
        touchContent()
        return filled
    }

    // --- AutoFill (the fill handle) -----------------------------------------------------------

    /**
     * Where dragging the fill handle to ([targetRow], [targetCol]) fills: past the selected block
     * down, up, right or left — whichever way the target lies further outside it — as
     * [r1, c1, r2, c2]; null while the target is inside the block.
     */
    fun autoFillRange(targetRow: Int, targetCol: Int): IntArray? {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val tr = targetRow.coerceIn(0, sheet.rowCount - 1)
        val tc = targetCol.coerceIn(0, sheet.colCount - 1)
        val rowOut = if (tr > r2) tr - r2 else if (tr < r1) r1 - tr else 0
        val colOut = if (tc > c2) tc - c2 else if (tc < c1) c1 - tc else 0
        return when {
            rowOut == 0 && colOut == 0 -> null
            rowOut >= colOut && tr > r2 -> intArrayOf(r2 + 1, c1, tr, c2)
            rowOut >= colOut -> intArrayOf(tr, c1, r1 - 1, c2)
            tc > c2 -> intArrayOf(r1, c2 + 1, r2, tc)
            else -> intArrayOf(r1, tc, r2, c1 - 1)
        }
    }

    /** The seeds and fill of one line of an AutoFill: the block's cells in that column (filling up or down) or row, and the steps for [range]. */
    private fun autoFillLine(range: IntArray, line: Int, vertical: Boolean): Pair<List<Cell?>, List<Pair<Int, AutoFill.Step>>> {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val seeds = if (vertical) (r1..r2).map { sheet.cell(it, line) } else (c1..c2).map { sheet.cell(line, it) }
        val targets = if (vertical) (range[0]..range[2]).toList() else (range[1]..range[3]).toList()
        val origin = if (vertical) r1 else c1
        val steps = AutoFill.fill(seeds.map { AutoFill.Seed(it?.text ?: "", it?.numberFormat ?: com.therealityteam.aeropages.model.NumberFormat.GENERAL) }, targets.map { it - origin })
        return seeds to targets.zip(steps)
    }

    /**
     * Excel's AutoFill: drags the selected block's fill handle to ([targetRow], [targetCol]). Each
     * column (filling up or down) or row (sideways) continues its own series ([AutoFill]); the new
     * cells take their seed's format, formulas move their relative references along. The selection
     * then covers the seeds and the fill. One undo step; false when the target is inside the block.
     */
    fun autoFill(targetRow: Int, targetCol: Int): Boolean {
        commitEntry()
        val range = autoFillRange(targetRow, targetCol) ?: return false
        if (refuseIfProtected(range[0], range[1], range[2], range[3])) return false
        val vertical = range[0] > rangeRowMax() || range[2] < rangeRowMin()
        // The seeds' rules, which the filled cells take as they take the seeds' formats.
        val r1 = rangeRowMin(); val c1 = rangeColMin()
        val rules = rulesOf(r1, c1, rangeRowMax(), rangeColMax())
        val seedOf = HashMap<Long, Pair<Int, Int>>()
        val seedRules = condTouches(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax()) || condTouches(range[0], range[1], range[2], range[3])
        pushUndo(if (rules != null || rulesTouch(range[0], range[1], range[2], range[3]) || seedRules) both(cells(range[0], range[1], range[2], range[3]), sheetMeta())
            else cells(range[0], range[1], range[2], range[3]))
        val lines = if (vertical) rangeColMin()..rangeColMax() else rangeRowMin()..rangeRowMax()
        for (line in lines) {
            val (seeds, steps) = autoFillLine(range, line, vertical)
            for ((at, st) in steps) {
                if (vertical) putCopy(at, line, seeds[st.copyOf], st.shift, 0, st.text)
                else putCopy(line, at, seeds[st.copyOf], 0, st.shift, st.text)
                if (vertical) seedOf[sheet.key(at, line)] = (r1 + st.copyOf) to line else seedOf[sheet.key(line, at)] = line to (c1 + st.copyOf)
            }
        }
        placeRules(range[0], range[1], range[2], range[3]) { r, c -> seedOf[sheet.key(r, c)]?.let { (sr, sc) -> rules?.get(sr - r1)?.get(sc - c1) } }
        // The seeds' conditional formats reach the filled cells (a rule the fill continues grows).
        if (seedRules) fillCondFormats(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax(), range[0], range[1], range[2], range[3], vertical)
        // The block now spans seeds and fill; the active cell stays where it was.
        val top = minOf(rangeRowMin(), range[0]); val bottom = maxOf(rangeRowMax(), range[2])
        val left = minOf(rangeColMin(), range[1]); val right = maxOf(rangeColMax(), range[3])
        selRow2 = if (selRow == top) bottom else top
        selCol2 = if (selCol == left) right else left
        lastKey = null
        touchContent()
        return true
    }

    /** What the last cell of an AutoFill to ([targetRow], [targetCol]) would show — the value the fill handle's tip reports while dragging. */
    fun autoFillPreview(targetRow: Int, targetCol: Int): String? {
        val range = autoFillRange(targetRow, targetCol) ?: return null
        val vertical = range[0] > rangeRowMax() || range[2] < rangeRowMin()
        // The far end of the fill in the first line (the last row filled down, the first filled up …).
        val (seeds, steps) = autoFillLine(range, if (vertical) rangeColMin() else rangeRowMin(), vertical)
        val far = if (vertical) (if (range[0] > rangeRowMax()) steps.last() else steps.first()) else (if (range[1] > rangeColMax()) steps.last() else steps.first())
        val seed = seeds[far.second.copyOf] ?: return ""
        val text = far.second.text ?: return seed.text.takeUnless { it.startsWith("=") }
        return NumberFormatting.shown(text, seed, symbols, dates).text
    }

    /**
     * Excel's double-click on the fill handle: fills the block down as far as the data in the
     * column beside it (left, else right) runs on without a gap. False when neither neighbour has
     * data under the block.
     */
    fun autoFillToAdjacentData(): Boolean {
        commitEntry()
        val r2 = rangeRowMax()
        fun blank(r: Int, c: Int) = sheet.cell(r, c)?.text.isNullOrBlank()
        fun runEnd(c: Int): Int? {
            if (c < 0 || c >= sheet.colCount || r2 + 1 >= sheet.rowCount || blank(r2 + 1, c)) return null
            var r = r2 + 1
            while (r + 1 < sheet.rowCount && !blank(r + 1, c)) r++
            return r
        }
        val end = runEnd(rangeColMin() - 1) ?: runEnd(rangeColMax() + 1) ?: return false
        return autoFill(end, rangeColMax())
    }

    /** Message from the last rejected input (data validation), cleared on next accepted edit. */
    var validationError by mutableStateOf<String?>(null)
        private set

    // --- sheet protection (保护工作表) ---------------------------------------------

    /** Bumped whenever a protected sheet refuses an edit, so the UI can say why nothing happened. */
    var protectionNotice by mutableIntStateOf(0)
        private set
    /** Bumped whenever Protect Workbook refuses a sheet command (add, delete, rename, move, copy, hide, show, tab colour). */
    var structureNotice by mutableIntStateOf(0)
        private set

    val isProtected: Boolean get() = sheet.protected

    /** Whether editing (row, col) is refused: the sheet is protected and the cell was not unlocked. */
    fun isLocked(row: Int, col: Int): Boolean = sheet.protected && sheet.cell(row, col)?.unlocked != true

    /** True (and notified) when the sheet is protected and the selection touches a locked cell. */
    private fun refuseIfProtected(r1: Int = rangeRowMin(), c1: Int = rangeColMin(), r2: Int = rangeRowMax(), c2: Int = rangeColMax()): Boolean {
        if (!sheet.protected) return false
        for (r in r1..r2) for (c in c1..c2) if (isLocked(r, c)) { protectionNotice++; return true }
        return false
    }

    /** What Protect Sheet can allow everyone to do (Excel's list), for the commands that ask. */
    enum class Allow { NONE, FORMAT_CELLS, FORMAT_COLUMNS, FORMAT_ROWS, INSERT_COLUMNS, INSERT_ROWS, INSERT_HYPERLINKS, DELETE_COLUMNS, DELETE_ROWS, SORT, AUTOFILTER, PIVOT, OBJECTS, SCENARIOS }

    /** Whether the active sheet lets [what] be done: unprotected, or protected with it allowed. */
    fun allows(what: Allow): Boolean {
        if (!sheet.protected) return true
        val p = sheet.protection
        return when (what) {
            Allow.NONE -> false
            Allow.FORMAT_CELLS -> p.formatCells
            Allow.FORMAT_COLUMNS -> p.formatColumns
            Allow.FORMAT_ROWS -> p.formatRows
            Allow.INSERT_COLUMNS -> p.insertColumns
            Allow.INSERT_ROWS -> p.insertRows
            Allow.INSERT_HYPERLINKS -> p.insertHyperlinks
            Allow.DELETE_COLUMNS -> p.deleteColumns
            Allow.DELETE_ROWS -> p.deleteRows
            Allow.SORT -> p.sort
            Allow.AUTOFILTER -> p.autoFilter
            Allow.PIVOT -> p.pivotTables
            Allow.OBJECTS -> p.editObjects
            Allow.SCENARIOS -> p.editScenarios
        }
    }

    /**
     * Structural edits (rows, columns, sheet metadata) are refused on a protected sheet — unless Protect Sheet allowed
     * [allow] (formatting cells, inserting rows, sorting …), as in Excel.
     */
    private fun refuseIfSheetProtected(allow: Allow = Allow.NONE): Boolean {
        if (allows(allow)) return false
        protectionNotice++; return true
    }

    /** Protect Sheet without a password (or Unprotect Sheet when it has none) — what the old toggle did. */
    fun toggleProtection() { if (sheet.protected) unprotectSheet("") else protectSheet(sheet.protection.copy(passwords = emptyList())) }

    /** Excel's Protect Sheet: protected with [options] (what everyone may still do) and, when given, [password] to unprotect it. */
    fun protectSheet(options: com.therealityteam.aeropages.model.SheetProtection, password: String = "") {
        commitEntry()
        pushUndo(sheetMeta())
        sheet.protected = true
        sheet.protection = options.copy(passwords = if (password.isEmpty()) emptyList() else com.therealityteam.aeropages.model.Passwords.create(password))
        touchContent()
    }

    /** Unprotect Sheet: false (nothing done) when the sheet's password is not [password]. */
    fun unprotectSheet(password: String = ""): Boolean {
        if (!sheet.protected) return true
        if (!com.therealityteam.aeropages.model.Passwords.matches(sheet.protection.passwords, password)) return false
        pushUndo(sheetMeta())
        sheet.protected = false
        sheet.protection = sheet.protection.copy(passwords = emptyList())
        touchContent()
        return true
    }

    /** Whether unprotecting the active sheet asks for a password. */
    val sheetPasswordSet: Boolean get() = sheet.protected && sheet.protection.hasPassword

    // --- Protect Workbook (structure) ---------------------------------------------------------

    val structureLocked: Boolean get() = workbook.structureLocked
    val workbookPasswordSet: Boolean get() = workbook.structureLocked && workbook.structurePasswords.isNotEmpty()

    /** Excel's Protect Workbook (structure), with [password] to unprotect it when given. */
    fun protectWorkbook(password: String = "") {
        commitEntry()
        pushUndo(WorkbookProtectionDelta(workbook.structureLocked, workbook.structurePasswords))
        workbook.structureLocked = true
        workbook.structurePasswords = if (password.isEmpty()) emptyList() else com.therealityteam.aeropages.model.Passwords.create(password)
        touchContent()
    }

    /** Unprotect Workbook: false when the password is not [password]. */
    fun unprotectWorkbook(password: String = ""): Boolean {
        if (!workbook.structureLocked) return true
        if (!com.therealityteam.aeropages.model.Passwords.matches(workbook.structurePasswords, password)) return false
        pushUndo(WorkbookProtectionDelta(workbook.structureLocked, workbook.structurePasswords))
        workbook.structureLocked = false; workbook.structurePasswords = emptyList()
        touchContent()
        return true
    }

    /** Sheets cannot be added, deleted, renamed, moved, copied, hidden, shown or recoloured while the structure is locked. */
    private fun refuseIfStructureLocked(): Boolean {
        if (!workbook.structureLocked) return false
        sheetRefusal = SheetRefusal.STRUCTURE_LOCKED; structureNotice++; return true
    }

    /**
     * Whether the cell at ([row], [col]) may be selected: Protect Sheet can keep locked (or unlocked) cells from being
     * selected at all, as Excel's "Select locked cells" does.
     */
    fun selectable(row: Int, col: Int): Boolean {
        if (!sheet.protected) return true
        val locked = sheet.cell(row, col)?.unlocked != true
        return if (locked) sheet.protection.selectLocked else sheet.protection.selectUnlocked
    }

    /** Flip "locked" for the selected cells: unlocked cells stay editable while the sheet is protected. */
    fun toggleUnlockedSelection() {
        val unlock = !(sheet.cell(selRow, selCol)?.unlocked ?: false)
        pushUndo(cells(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax()))
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) sheet.ensure(r, c).unlocked = unlock
        touchContent()
    }

    val activeUnlocked: Boolean get() = sheet.cell(selRow, selCol)?.unlocked == true

    // --- Quick Analysis (Excel's button on a selection) ---------------------------------

    /**
     * Excel's Quick Analysis ▸ Totals: [fn] (SUM, AVERAGE, COUNT, MAX, MIN) of each selected column in the row under
     * the block (columns without numbers left alone, a first column of labels given [label]) — or, [right], [fn] of each
     * row in the column beside it (its header cell [label]) — bold, in the number format of the values totalled (COUNT
     * in General), as Excel puts them. A first row of text over numbers is a header and left out. One undo step; false
     * when the sheet refuses (protection), there is no room or there is nothing to total.
     */
    fun quickTotals(fn: String, right: Boolean = false, label: String? = null): Boolean {
        commitEntry()
        if (refuseIfProtected()) return false
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        // A number here is what COUNT counts: a typed number (in any format), a formula that comes to one.
        val memo = FormulaEngine.Memo()
        fun number(r: Int, c: Int): Boolean {
            val cell = sheet.cell(r, c) ?: return false
            return if (cell.text.startsWith("=")) FormulaEngine.cached(sheet, r, c, workbook.sheets, workbook.names, memo) is com.therealityteam.aeropages.model.CachedValue.Number
                else cell.formatCode != "@" && cell.text.toDoubleOrNull()?.isFinite() == true
        }
        val header = r2 > r1 && (c1..c2).all { c -> sheet.cell(r1, c)?.text?.isNotBlank() == true && !number(r1, c) }
        val top = if (header) r1 + 1 else r1
        fun put(r: Int, c: Int, formula: String, like: Cell?) {
            val cell = sheet.ensure(r, c)
            cell.text = formula
            cell.format.bold = true
            if (fn != "COUNT" && like != null) { cell.numberFormat = like.numberFormat; cell.formatCode = like.formatCode; cell.decimals = like.decimals }
        }
        if (right) {
            val c = c2 + 1
            if (c >= com.therealityteam.aeropages.model.Sheet.MAX_COLS) return false
            pushUndo(cells(r1, c, r2, c))
            if (header && label != null) sheet.ensure(r1, c).let { it.text = label; it.format.bold = true }
            for (r in top..r2) put(r, c, "=$fn(${Workbook.colLabel(c1)}${r + 1}:${Workbook.colLabel(c2)}${r + 1})", sheet.cell(r, c2))
            if (sheet.colCount <= c) sheet.colCount = c + 1
        } else {
            val r = r2 + 1
            if (r >= MAX_ROWS) return false
            // Only under columns that hold numbers; a column of labels gets [label] instead.
            fun numeric(c: Int) = (top..r2).any { rr -> number(rr, c) }
            val sums = (c1..c2).filter(::numeric)
            if (sums.isEmpty()) return false
            pushUndo(cells(r, c1, r, c2))
            for (c in sums) put(r, c, "=$fn(${Workbook.colLabel(c)}${top + 1}:${Workbook.colLabel(c)}${r2 + 1})", sheet.cell(r2, c))
            if (label != null && c1 !in sums) sheet.ensure(r, c1).let { it.text = label; it.format.bold = true }
            if (sheet.rowCount <= r) sheet.rowCount = r + 1
        }
        touchContent()
        return true
    }

    // --- sparklines (迷你图) ----------------------------------------------------------

    /**
     * Insert sparklines for the selected range, Excel-style: one per row of the selection, placed
     * in the column right after it (a single column of data gets one sparkline below it).
     */
    fun insertSparklines(type: com.therealityteam.aeropages.model.SparklineType) {
        if (refuseIfSheetProtected()) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        pushUndo(sheetMeta())
        val added = ArrayList<com.therealityteam.aeropages.model.Sparkline>()
        if (c1 == c2 && r1 < r2) {
            if (r2 + 1 < com.therealityteam.aeropages.model.Sheet.MAX_COLS) added.add(com.therealityteam.aeropages.model.Sparkline(r2 + 1, c1, r1, c1, r2, c1, type))
        } else {
            val target = c2 + 1
            if (target < com.therealityteam.aeropages.model.Sheet.MAX_COLS) for (r in r1..r2) added.add(com.therealityteam.aeropages.model.Sparkline(r, target, r, c1, r, c2, type))
        }
        added.forEach { s -> sheet.sparklines.removeAll { it.row == s.row && it.col == s.col }; sheet.sparklines.add(s) }
        if (sheet.colCount <= c2 + 1) sheet.colCount = (c2 + 2).coerceAtMost(com.therealityteam.aeropages.model.Sheet.MAX_COLS)
        touchContent()
    }

    /** Remove the sparklines whose cell is inside the selection. */
    fun clearSparklines() {
        if (refuseIfSheetProtected()) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (sheet.sparklines.none { it.row in r1..r2 && it.col in c1..c2 }) return
        pushUndo(sheetMeta())
        sheet.sparklines.removeAll { it.row in r1..r2 && it.col in c1..c2 }
        touchContent()
    }

    fun sparklineAt(row: Int, col: Int): com.therealityteam.aeropages.model.Sparkline? = sheet.sparklineAt(row, col)

    /** The numbers a sparkline draws, in row-major order over its range (non-numeric cells are skipped). */
    fun sparklineValues(s: com.therealityteam.aeropages.model.Sparkline): List<Double> {
        val out = ArrayList<Double>()
        for (r in s.r1..s.r2) for (c in s.c1..s.c2) displayStandard(r, c).replace(",", "").removeSuffix("%").toDoubleOrNull()?.let { out.add(it) }
        return out
    }

    /**
     * The cell being typed into (formula bar or in the cell), read as Excel reads an entry once the
     * selection leaves it; [before] is what it held when the typing began.
     */
    private class PendingEntry(val sheet: Int, val row: Int, val col: Int, val before: Cell?) {
        fun isAt(sheet: Int, row: Int, col: Int) = this.sheet == sheet && this.row == row && this.col == col
    }
    private var pendingEntry: PendingEntry? = null

    /**
     * Typing into a cell: the text goes in now, and when the selection leaves the cell the entry is
     * read as Excel reads one ([commitEntry]) — `15%`, `$1,234`, `2026/9/29`, `9:30` become numbers —
     * and checked against the cell's validation rule, as Excel checks an entry when it is made (not
     * on every key: a list's "Yes" starts as a "Y" no list allows).
     */
    fun typeCell(row: Int, col: Int, text: String) {
        pendingEntry?.let { if (!it.isAt(sheetIndex, row, col)) commitEntry() }
        if (refuseIfProtected(row, col, row, col)) return
        if (pendingEntry == null) pendingEntry = PendingEntry(sheetIndex, row, col, sheet.cell(row, col)?.copyOf())
        validationError = null
        writeCell(row, col, text)
    }

    /**
     * Excel's reading of the entry typed into the cell being left ([typeCell]), folded into the
     * typing's undo step: a percentage, an amount, a grouped number, a date or a time becomes the
     * number, taking the format it was typed in when the cell has none; a plain number typed into a
     * percentage cell is a percent (20 → 20%); a leading apostrophe keeps the rest as text. Text
     * cells (`@`), formulas and plain numbers stay as typed. Selection changes and the commands on
     * the selection (fill, copy, sort, number formats) call it first, as Excel ends editing. Then
     * the entry meets the cell's validation rule: one it breaks raises the rule's alert
     * ([validationAlert]) — a Stop alert has already put the cell back.
     */
    /**
     * Excel's AutoCorrect on an entry being typed: [text] with a character just typed before [caret], corrected — its
     * replacements, a day's name and a sentence's capital (a cell's first word starts none), no formatting — and the
     * caret after; null when nothing changes.
     */
    fun autoCorrected(text: String, caret: Int): Pair<String, Int>? {
        val fixes = AutoCorrect.after(text, caret, AutoCorrect.languageOf(text), AutoCorrect.options.forCells(), startsSentence = false)
        if (fixes.isEmpty()) return null
        var t = text
        var at = caret
        for (fx in fixes) if (fx is AutoCorrect.Replace) {
            t = t.substring(0, fx.start) + fx.text + t.substring(fx.end)
            at += fx.text.length - (fx.end - fx.start)
        }
        return if (t == text) null else t to at
    }

    fun commitEntry() {
        val p = pendingEntry ?: return
        pendingEntry = null
        val offered = completion
        completion = null
        if (p.sheet != sheetIndex) return
        val cell = sheet.cell(p.row, p.col) ?: return
        // An accepted AutoComplete: the entry as the column has it ("apple" typed, "Apple" there).
        if (offered != null && cell.text != offered && cell.text.trim().equals(offered, ignoreCase = true)) {
            pushUndo(cells(p.row, p.col, p.row, p.col), "cell:$sheetIndex:${p.row}:${p.col}")
            cell.text = offered; cell.runs = null
            touchContent()
        }
        val typed = cell.text
        readEntry(p, cell)
        linkEntry(p, cell)
        checkEntry(p, typed)
        tableEntry(p)
    }

    /**
     * Excel's "Internet and network paths with hyperlinks": an entry that is a web or e-mail address becomes a link to
     * it, in the typing's undo step (Ctrl+Z takes entry and link together, as there).
     */
    private fun linkEntry(p: PendingEntry, cell: Cell) {
        if (!AutoCorrect.options.hyperlinks || cell.link != null || cell.text.startsWith("=")) return
        val url = AutoCorrect.linkFor(cell.text.trim()) ?: return
        pushUndo(cells(p.row, p.col, p.row, p.col), "cell:$sheetIndex:${p.row}:${p.col}")
        cell.link = url
        touchContent()
    }

    private fun readEntry(p: PendingEntry, cell: Cell) {
        val text = cell.text
        if (text.startsWith("=")) {
            // A formula typed the language's way (=ROUND(A1;2), 1,5) is kept in the standard form.
            val canonical = FormulaLocale.toCanonical(text, symbols)
            if (canonical != text) { cell.text = canonical; touchContent() }
            return
        }
        if (cell.formatCode == "@") return
        val kind = cell.formatCode?.let { com.therealityteam.aeropages.model.FormatCode.category(it) } ?: cell.numberFormat
        val general = cell.formatCode == null && cell.numberFormat == com.therealityteam.aeropages.model.NumberFormat.GENERAL
        val key = "cell:$sheetIndex:${p.row}:${p.col}"
        if (text.startsWith("'")) {
            // Excel's quote prefix: what follows stays text; a number or a date keeps that as a Text cell.
            val rest = text.substring(1)
            pushUndo(cells(p.row, p.col, p.row, p.col), key)
            cell.text = rest
            if (rest.trim().toDoubleOrNull() != null || EntryParser.parse(rest, symbols = symbols) != null) { cell.formatCode = "@"; cell.numberFormat = com.therealityteam.aeropages.model.NumberFormat.GENERAL; cell.decimals = -1 }
            touchContent()
            return
        }
        val parsed = EntryParser.parse(text, symbols = symbols)
        // A plain number in the language's writing (1,5) is still a plain number: a percentage cell takes it as one.
        val plainLocal = parsed?.takeIf { it.format == com.therealityteam.aeropages.model.NumberFormat.GENERAL && it.code == null && it.value.toDoubleOrNull() != null && text.trim() != it.value }
        val entry = if (plainLocal != null && kind == com.therealityteam.aeropages.model.NumberFormat.PERCENT) null else parsed
        val typed = plainLocal?.value ?: text.trim()
        val value = when {
            entry != null -> entry.value
            // Automatic percent entry: 20 typed into a percentage cell is 20%.
            kind == com.therealityteam.aeropages.model.NumberFormat.PERCENT -> typed.toBigDecimalOrNull()
                ?.takeIf { it.abs() >= java.math.BigDecimal.ONE }?.movePointLeft(2)?.stripTrailingZeros()?.toPlainString() ?: plainLocal?.value ?: return
            else -> return
        }
        pushUndo(cells(p.row, p.col, p.row, p.col), key)
        cell.text = value
        cell.runs = null
        if (general && entry != null) {
            cell.numberFormat = entry.code?.let { com.therealityteam.aeropages.model.FormatCode.category(it) } ?: entry.format
            cell.decimals = entry.decimals
            cell.formatCode = entry.code
        }
        touchContent()
    }

    /**
     * What the formula bar shows for a cell: its formula or text, or its number as typed — 15%,
     * 2026-09-29, 9:30 — for percentages, dates and times. While the cell is being typed into it is
     * exactly what was typed (20 in a percentage cell stays 20 until the entry is read).
     */
    fun editText(row: Int, col: Int): String {
        val cell = sheet.cell(row, col) ?: return ""
        return if (pendingEntry?.isAt(sheetIndex, row, col) == true) cell.text else EntryParser.editText(cell, symbols)
    }

    /**
     * A whole entry into a cell (a ribbon command, a pick from a list): checked against the cell's
     * validation rule first — a Stop rule refuses what breaks it ([validationError] says why); a
     * Warning or Information rule keeps it and says so.
     */
    fun setCell(row: Int, col: Int, text: String) {
        if (refuseIfProtected(row, col, row, col)) return
        val broken = brokenRule(row, col, text)
        if (broken != null && broken.first.errorStyle == ValidationAlertStyle.STOP) {
            validationError = broken.second; bump(); return
        }
        validationError = broken?.second
        writeCell(row, col, text)
    }

    private fun writeCell(row: Int, col: Int, text: String) {
        pushUndo(cells(row, col, row, col), "cell:$sheetIndex:$row:$col")
        val cell = sheet.ensure(row, col)
        if (cell.text != text) cell.runs = null   // mixed formatting belongs to the old text
        cell.text = text
        touchContent()
    }

    // --- outline (组合 / 分级显示) ---------------------------------------------------------
    val rowGroups: List<com.therealityteam.aeropages.model.RowGroup> get() = sheet.rowGroups
    val colGroups: List<com.therealityteam.aeropages.model.RowGroup> get() = sheet.colGroups
    /** Deepest row outline level in use (0 = no groups). */
    val maxOutlineLevel: Int get() = com.therealityteam.aeropages.model.Outline.maxLevel(sheet.rowGroups)
    /** Deepest column outline level in use (0 = no groups). */
    val maxColOutlineLevel: Int get() = com.therealityteam.aeropages.model.Outline.maxLevel(sheet.colGroups)
    /** Rows the grid skips: filtered out, hidden by hand, or folded inside a collapsed group. */
    val invisibleRows: Set<Int> get() = sheet.invisibleRows()
    /** Columns the grid skips: hidden by hand, or folded inside a collapsed group. */
    val invisibleCols: Set<Int> get() = sheet.invisibleCols()

    /**
     * Data → Group on the selected rows. Refused (false) when the rows are already exactly a
     * group, when the new group would partly overlap one (outlines only nest), or past Excel's
     * eight outline levels.
     */
    fun groupRows(): Boolean = group(sheet.rowGroups, rangeRowMin(), rangeRowMax())

    /** Data → Group on the selected columns (same rules as [groupRows]). */
    fun groupColumns(): Boolean = group(sheet.colGroups, rangeColMin(), rangeColMax())

    private fun group(groups: MutableList<com.therealityteam.aeropages.model.RowGroup>, lo: Int, hi: Int): Boolean {
        if (refuseIfSheetProtected()) return false
        val g = com.therealityteam.aeropages.model.RowGroup(lo, hi)
        if (!com.therealityteam.aeropages.model.Outline.canAdd(groups, g)) return false
        pushUndo(sheetMeta())
        groups.add(g)
        com.therealityteam.aeropages.model.Outline.mergeAdjacent(groups)
        touchContent()
        return true
    }

    /**
     * Data → Ungroup: removes the deepest group inside the selected rows, else the innermost
     * group around them. False when nothing is grouped there.
     */
    fun ungroupRows(): Boolean = ungroup(sheet.rowGroups, rangeRowMin(), rangeRowMax())

    /** Data → Ungroup on the selected columns. */
    fun ungroupColumns(): Boolean = ungroup(sheet.colGroups, rangeColMin(), rangeColMax())

    private fun ungroup(groups: MutableList<com.therealityteam.aeropages.model.RowGroup>, lo: Int, hi: Int): Boolean {
        if (refuseIfSheetProtected()) return false
        val level = { g: com.therealityteam.aeropages.model.RowGroup -> com.therealityteam.aeropages.model.Outline.levelOf(groups, g) }
        val target = groups.filter { it.r1 >= lo && it.r2 <= hi }.maxByOrNull(level)
            ?: groups.filter { lo >= it.r1 && hi <= it.r2 }.maxByOrNull(level)
            ?: return false
        pushUndo(sheetMeta())
        groups.remove(target)
        touchContent()
        return true
    }

    /** Collapses or expands one row group (the outline's −/+); the selection leaves folded rows. */
    fun toggleRowGroup(g: com.therealityteam.aeropages.model.RowGroup) = toggle(sheet.rowGroups, g)

    /** Collapses or expands one column group. */
    fun toggleColGroup(g: com.therealityteam.aeropages.model.RowGroup) = toggle(sheet.colGroups, g)

    private fun toggle(groups: MutableList<com.therealityteam.aeropages.model.RowGroup>, g: com.therealityteam.aeropages.model.RowGroup) {
        if (g !in groups) return
        pushUndo(sheetMeta())
        g.collapsed = !g.collapsed
        keepSelectionVisible()
        touchContent()
    }

    /** The row outline's level buttons: level n shows rows down to outline level n − 1 (groups at level ≥ n collapse). */
    fun showOutlineLevel(level: Int) = showLevel(sheet.rowGroups, level)

    /** The column outline's level buttons. */
    fun showColOutlineLevel(level: Int) = showLevel(sheet.colGroups, level)

    private fun showLevel(groups: MutableList<com.therealityteam.aeropages.model.RowGroup>, level: Int) {
        if (groups.isEmpty()) return
        val want = groups.map { com.therealityteam.aeropages.model.Outline.levelOf(groups, it) >= level }
        if (groups.map { it.collapsed } == want) return
        pushUndo(sheetMeta())
        groups.forEachIndexed { i, g -> g.collapsed = want[i] }
        keepSelectionVisible()
        touchContent()
    }

    /** Data → Clear Outline: drops every row and column group (folded rows and columns show again). */
    fun clearOutline() {
        if (sheet.rowGroups.isEmpty() && sheet.colGroups.isEmpty()) return
        pushUndo(sheetMeta())
        sheet.rowGroups.clear()
        sheet.colGroups.clear()
        touchContent()
    }

    // --- hide / unhide rows and columns (Home → Format → Hide & Unhide) ----------------------

    /** Hides the selected rows (by hand — a filter keeps its own rows). False when that would hide every row. */
    fun hideRows(): Boolean {
        if (refuseIfSheetProtected(Allow.FORMAT_ROWS)) return false
        val rows = (rangeRowMin()..rangeRowMax()).toSet()
        if ((0 until sheet.rowCount).all { it in rows || it in sheet.manualHiddenRows }) return false
        if (sheet.manualHiddenRows.containsAll(rows)) return false
        pushUndo(sheetMeta())
        sheet.manualHiddenRows.addAll(rows)
        keepSelectionVisible()
        touchContent()
        return true
    }

    /** Shows the rows hidden by hand inside the selection, or — when there are none — the run right next to it. */
    fun unhideRows(): Boolean {
        if (refuseIfSheetProtected(Allow.FORMAT_ROWS)) return false
        val lo = rangeRowMin(); val hi = rangeRowMax()
        var show = sheet.manualHiddenRows.filter { it in lo..hi }
        if (show.isEmpty()) {
            val next = ArrayList<Int>()
            var r = lo - 1; while (r >= 0 && r in sheet.manualHiddenRows) { next.add(r); r-- }
            r = hi + 1; while (r < sheet.rowCount && r in sheet.manualHiddenRows) { next.add(r); r++ }
            show = next
        }
        if (show.isEmpty()) return false
        pushUndo(sheetMeta())
        sheet.manualHiddenRows.removeAll(show.toSet())
        touchContent()
        return true
    }

    // --- whole rows / columns (tapping a header) --------------------------------------------

    /** Selects whole columns [c1]..[c2] (tapping a column letter); the active cell goes to their top. */
    fun selectColumns(c1: Int, c2: Int = c1) {
        areaStore = null
        commitEntry()
        selectedImage = null
        val a = c1.coerceIn(0, sheet.colCount - 1); val b = c2.coerceIn(0, sheet.colCount - 1)
        if (selRow == 0 && selCol == a && selRow2 == sheet.rowCount - 1 && selCol2 == b) return
        selRow = 0; selCol = a; selRow2 = sheet.rowCount - 1; selCol2 = b
        lastKey = null
        bump()
    }

    /** Selects whole rows [r1]..[r2] (tapping a row number). */
    fun selectRows(r1: Int, r2: Int = r1) {
        areaStore = null
        commitEntry()
        selectedImage = null
        val a = r1.coerceIn(0, sheet.rowCount - 1); val b = r2.coerceIn(0, sheet.rowCount - 1)
        if (selRow == a && selCol == 0 && selRow2 == b && selCol2 == sheet.colCount - 1) return
        selRow = a; selCol = 0; selRow2 = b; selCol2 = sheet.colCount - 1
        lastKey = null
        bump()
    }

    /** Selects the whole sheet (the corner above the row numbers). */
    fun selectAll() {
        areaStore = null
        commitEntry()
        selectedImage = null
        selRow = 0; selCol = 0; selRow2 = sheet.rowCount - 1; selCol2 = sheet.colCount - 1
        lastKey = null
        bump()
    }

    /** Whether the selection spans whole columns / whole rows (as a header tap makes it). */
    val wholeColumnsSelected: Boolean get() = rangeRowMin() == 0 && rangeRowMax() == sheet.rowCount - 1
    val wholeRowsSelected: Boolean get() = rangeColMin() == 0 && rangeColMax() == sheet.colCount - 1

    // --- a selection of several areas (Go To Special, Ctrl+click) -----------------------------

    /** The selection's other areas, each (r1, c1, r2, c2), kept only while the active block is the one they came with. */
    private var areaStore by mutableStateOf<Pair<List<Int>, List<IntArray>>?>(null)
    private fun selKey() = listOf(sheetIndex, selRow, selCol, selRow2, selCol2)
    private var gathered: MutableList<Delta<Workbook>>? = null
    private var spreading = false

    /** The areas of the selection besides the active block — empty unless several are selected (Excel's Ctrl+click, Go To Special). */
    val extraAreas: List<IntArray> get() = areaStore?.takeIf { it.first == selKey() }?.second.orEmpty()

    /** Every area of the selection, the active block first, each (r1, c1, r2, c2). */
    fun selectedAreas(): List<IntArray> =
        listOf(intArrayOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())) + extraAreas

    /**
     * Selects [areas] (each r1, c1, r2, c2): the first is the active block, its top-left cell the active cell, as
     * Go To Special leaves it; the others are drawn and formatted with it until the selection moves.
     */
    fun selectAreas(areas: List<IntArray>) {
        val first = areas.firstOrNull() ?: return
        commitEntry()
        selectedImage = null
        selRow = first[0]; selCol = first[1]; selRow2 = first[2]; selCol2 = first[3]
        areaStore = if (areas.size > 1) selKey() to areas.drop(1).map { it.copyOf() } else null
        lastKey = null
        bump()
    }

    /**
     * Excel's Ctrl+click: keeps what is selected as one more area and starts a new one at ([row], [col]).
     * Clicking a cell already in an area with nothing else to keep just selects it.
     */
    fun addArea(row: Int, col: Int) {
        val kept = selectedAreas()
        val r = row.coerceIn(0, sheet.rowCount - 1); val c = col.coerceIn(0, sheet.colCount - 1)
        selectAreas(listOf(intArrayOf(r, c, r, c)) + kept)
    }

    /**
     * Runs [again] — the command it is called from — once on every area of a selection of several and records
     * one undo step for them all; false when there is only one area (or it is already running), and the command
     * goes on as it is. Formatting and clearing take every area, as in Excel.
     */
    private fun spread(again: () -> Unit): Boolean {
        if (spreading || partialTextSel() != null) return false
        val extra = extraAreas
        if (extra.isEmpty()) return false
        val keep = intArrayOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
        val active = intArrayOf(selRow, selCol, selRow2, selCol2); val store = areaStore
        val before = if (VERIFY_UNDO) workbook.copyOf() else null
        spreading = true; gathered = mutableListOf(); verifyPending = null
        try {
            for (a in listOf(keep) + extra) {
                selRow = a[0]; selCol = a[1]; selRow2 = a[2]; selCol2 = a[3]
                again()
            }
        } finally {
            val parts = gathered.orEmpty(); gathered = null; spreading = false
            selRow = active[0]; selCol = active[1]; selRow2 = active[2]; selCol2 = active[3]; areaStore = store
            if (parts.isNotEmpty()) {
                // Undone last area first, so areas that overlap come back right.
                val step = CompositeDelta(parts.asReversed().toList())
                history.push(step, sel()); lastKey = null
                if (before != null) { verifyPending = step to before; verifyIfPending() }
            }
            bump()
        }
        return true
    }

    /** What Go To Special looks for. */
    enum class Special {
        COMMENTS, CONSTANTS, FORMULAS, BLANKS, CURRENT_REGION, ROW_DIFFERENCES, COLUMN_DIFFERENCES,
        PRECEDENTS, DEPENDENTS, LAST_CELL, VISIBLE_CELLS, CONDITIONAL_FORMATS, DATA_VALIDATION,
    }

    /**
     * A Go To Special request: [kind]; for constants and formulas the kinds of value ([numbers] … [errors]); for
     * precedents and dependents whether [allLevels] or direct only; for conditional formats and data validation
     * whether only the cells with the [same] rules as the active cell.
     */
    data class GoSpecial(
        val kind: Special, val numbers: Boolean = true, val text: Boolean = true, val logicals: Boolean = true,
        val errors: Boolean = true, val allLevels: Boolean = false, val same: Boolean = false,
    )

    /** The used part of the sheet (r1, c1, r2, c2): from the first to the last row and column with something in them; null when empty. */
    fun usedRange(): IntArray? {
        var r1 = Int.MAX_VALUE; var c1 = Int.MAX_VALUE; var r2 = -1; var c2 = -1
        for ((k, cell) in sheet.cells) {
            if (cell.text.isEmpty() && cell.comment == null) continue
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            r1 = minOf(r1, r); r2 = maxOf(r2, r); c1 = minOf(c1, c); c2 = maxOf(c2, c)
        }
        return if (r2 < 0) null else intArrayOf(r1, c1, r2, c2)
    }

    /**
     * Excel's Go To Special: selects the cells [spec] asks for — within the selection when it is more than one
     * cell, else in the sheet's used range — as one area per block of them. Returns how many cells; 0 selects
     * nothing ("No cells were found.").
     */
    fun goToSpecial(spec: GoSpecial): Int {
        commitEntry()
        val single = extraAreas.isEmpty() && selRow == selRow2 && selCol == selCol2
        val scope: List<IntArray> = if (!single) selectedAreas() else listOfNotNull(usedRange())
        fun inScope(r: Int, c: Int) = scope.any { r in it[0]..it[2] && c in it[1]..it[3] }
        fun cellsIn(test: (Int, Int) -> Boolean): Set<Long> {
            val found = HashSet<Long>()
            for (a in scope) for (r in a[0]..a[2]) for (c in a[1]..a[3]) if (test(r, c)) found += sheet.key(r, c)
            return found
        }
        fun ofType(v: Any?): Boolean = when (v) {
            is Double -> spec.numbers
            is Boolean -> spec.logicals
            is com.therealityteam.aeropages.model.CachedValue.Error -> spec.errors
            null -> false
            else -> spec.text
        }
        val found: Set<Long> = when (spec.kind) {
            Special.COMMENTS -> sheet.cells.filter { (k, c) -> c.comment != null && inScope((k / 1000L).toInt(), (k % 1000L).toInt()) }.keys
            Special.CONSTANTS -> sheet.cells.filter { (k, c) ->
                c.text.isNotEmpty() && !c.text.startsWith("=") && inScope((k / 1000L).toInt(), (k % 1000L).toInt()) &&
                    ofType(if (c.text.startsWith("#") && com.therealityteam.aeropages.editor.FormulaEngine.isError(c.text))
                        com.therealityteam.aeropages.model.CachedValue.Error(c.text) else c.storedNumber() ?: c.storedLogical() ?: c.text)
            }.keys
            Special.FORMULAS -> sheet.cells.filter { (k, c) ->
                val r = (k / 1000L).toInt(); val col = (k % 1000L).toInt()
                c.text.startsWith("=") && inScope(r, col) && ofType(when (val v = FormulaEngine.cached(sheet, r, col, workbook.sheets, workbook.names, memo)) {
                    is com.therealityteam.aeropages.model.CachedValue.Number -> v.value
                    is com.therealityteam.aeropages.model.CachedValue.Logical -> v.value
                    is com.therealityteam.aeropages.model.CachedValue.Text -> v.value
                    is com.therealityteam.aeropages.model.CachedValue.Error -> v
                    null -> null
                })
            }.keys
            Special.BLANKS -> cellsIn { r, c -> sheet.cell(r, c)?.text.isNullOrEmpty() && sheet.merges.none { it.covers(r, c) && (it.r1 != r || it.c1 != c) } }
            Special.CURRENT_REGION -> currentRegion(selRow, selCol)?.let { a -> setOf(sheet.key(a[0], a[1]), sheet.key(a[2], a[3])) }.orEmpty()
            Special.LAST_CELL -> usedRange()?.let { setOf(sheet.key(it[2], it[3])) }.orEmpty()
            Special.VISIBLE_CELLS -> { val rows = sheet.invisibleRows(); val cols = sheet.invisibleCols(); cellsIn { r, c -> r !in rows && c !in cols } }
            Special.ROW_DIFFERENCES, Special.COLUMN_DIFFERENCES -> {
                val area = if (single) currentRegion(selRow, selCol) ?: intArrayOf(selRow, selCol, selRow, selCol)
                    else intArrayOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
                val rows = spec.kind == Special.ROW_DIFFERENCES
                val out = HashSet<Long>()
                for (r in area[0]..area[2]) for (c in area[1]..area[3]) {
                    // Each row (column) against its cell in the active cell's column (row).
                    val base = if (rows) sortValue(r, selCol) else sortValue(selRow, c)
                    if (sortValue(r, c) != base) out += sheet.key(r, c)
                }
                out
            }
            Special.PRECEDENTS -> {
                // The selected formulas' precedents — one cell's when one is selected.
                val start = if (single) setOf(sheet.key(selRow, selCol)) else cellsIn { r, c -> sheet.cell(r, c)?.text?.startsWith("=") == true }
                precedentsOf(start.filterTo(HashSet()) { k -> sheet.cells[k]?.text?.startsWith("=") == true }, spec.allLevels)
            }
            Special.DEPENDENTS -> {
                val start = if (single) setOf(sheet.key(selRow, selCol)) else cellsIn { _, _ -> true }
                dependentsOf(start, spec.allLevels)
            }
            Special.CONDITIONAL_FORMATS -> {
                val mine = sheet.condFormats.filter { selRow in it.r1..it.r2 && selCol in it.c1..it.c2 }.toSet()
                val rules = if (spec.same) mine else sheet.condFormats.toSet()
                if (spec.same && rules.isEmpty()) emptySet()
                else sheet.condFormats.filter { it in rules }.flatMapTo(HashSet()) { cf ->
                    (cf.r1..minOf(cf.r2, sheet.rowCount - 1)).flatMap { r -> (cf.c1..minOf(cf.c2, sheet.colCount - 1)).map { c -> r to c } }
                        .filter { (r, c) -> (spec.same || inScope(r, c)) && (!spec.same ||
                            sheet.condFormats.filter { r in it.r1..it.r2 && c in it.c1..it.c2 }.toSet() == mine) }
                        .map { (r, c) -> sheet.key(r, c) }
                }
            }
            Special.DATA_VALIDATION -> {
                val mine = sheet.validations.filter { selRow in it.r1..it.r2 && selCol in it.c1..it.c2 }.toSet()
                if (spec.same && mine.isEmpty()) emptySet()
                else sheet.validations.filter { !spec.same || it in mine }.flatMapTo(HashSet()) { v ->
                    (v.r1..minOf(v.r2, sheet.rowCount - 1)).flatMap { r -> (v.c1..minOf(v.c2, sheet.colCount - 1)).map { c -> r to c } }
                        .filter { (r, c) -> spec.same || inScope(r, c) }
                        .map { (r, c) -> sheet.key(r, c) }
                }
            }
        }
        if (found.isEmpty()) return 0
        if (spec.kind == Special.CURRENT_REGION) {
            val a = currentRegion(selRow, selCol) ?: return 0
            selectAreas(listOf(a)); return (a[2] - a[0] + 1) * (a[3] - a[1] + 1)
        }
        selectAreas(areasOf(found))
        return found.size
    }

    /** The cells [formulas] read — on this sheet — and with [allLevels] what those read in turn. */
    private fun precedentsOf(formulas: Set<Long>, allLevels: Boolean): Set<Long> {
        val out = HashSet<Long>()
        var todo = formulas.toList()
        val seen = HashSet<Long>()
        while (todo.isNotEmpty()) {
            val next = ArrayList<Long>()
            for (k in todo) {
                if (!seen.add(k)) continue
                val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
                FormulaEngine.cached(sheet, r, c, workbook.sheets, workbook.names, memo)
                for (p in memo.precedentsOf(FormulaEngine.compositeKey(sheetIndex, r, c))) {
                    if (p[0] != sheetIndex) continue
                    for (pr in p[1]..minOf(p[3], sheet.rowCount - 1)) for (pc in p[2]..minOf(p[4], sheet.colCount - 1)) {
                        val pk = sheet.key(pr, pc)
                        if (out.add(pk) && allLevels && sheet.cell(pr, pc)?.text?.startsWith("=") == true) next += pk
                    }
                }
            }
            todo = next
        }
        return out
    }

    /** The formulas on this sheet that read any of [cells] — and with [allLevels] the formulas that read those. */
    private fun dependentsOf(cells: Set<Long>, allLevels: Boolean): Set<Long> {
        val formulas = sheet.cells.filter { it.value.text.startsWith("=") }.keys
        val reads = formulas.associateWith { k -> precedentsOf(setOf(k), false) }
        val out = HashSet<Long>()
        var targets: Set<Long> = cells
        while (targets.isNotEmpty()) {
            val next = HashSet<Long>()
            for ((f, rs) in reads) if (f !in out && rs.any { it in targets }) { out += f; next += f }
            targets = if (allLevels) next else emptySet()
        }
        return out
    }

    // --- Formula Auditing: Trace Precedents / Dependents --------------------------------------------

    /**
     * A tracer arrow, as Excel draws it on [sheet]: from the cell or range ([r1], [c1])–([r2], [c2]) to the formula in
     * ([row], [col]) — or, for [offSheet], from another sheet (a dashed arrow from a small sheet icon). [error]: the
     * source shows an error (a red arrow); [dependent]: drawn by Trace Dependents.
     */
    class TraceArrow(
        val sheet: Int, val r1: Int, val c1: Int, val r2: Int, val c2: Int, val row: Int, val col: Int,
        val offSheet: Boolean, val error: Boolean, val dependent: Boolean,
    )

    /** The tracer arrows shown, on every sheet; an edit that changes cells takes them all away, as Excel's does. */
    val traceArrows = androidx.compose.runtime.mutableStateListOf<TraceArrow>()
    private val tracedPrecedents = HashSet<Long>()   // formulas whose precedents are drawn (composite keys)
    private val tracedDependents = HashSet<Long>()   // cells whose dependents are drawn

    /** What a Trace command did. */
    enum class Trace { ADDED, NO_FORMULA, NOTHING_MORE }

    private fun shownError(r: Int, c: Int): Boolean {
        val cell = sheet.cell(r, c) ?: return false
        return if (cell.text.startsWith("=")) FormulaEngine.cached(sheet, r, c, workbook.sheets, workbook.names, memo) is com.therealityteam.aeropages.model.CachedValue.Error
        else FormulaEngine.isError(cell.text)
    }

    /**
     * Excel's Trace Precedents: the first time, arrows from what the active cell's formula reads; each time again, one
     * level further back — from what the formulas at the arrows' tails read. A range is one arrow from its box.
     */
    fun tracePrecedents(): Trace {
        commitEntry()
        val own = FormulaEngine.compositeKey(sheetIndex, selRow, selCol)
        val frontier: List<Pair<Int, Int>> = if (own !in tracedPrecedents) {
            if (sheet.cell(selRow, selCol)?.text?.startsWith("=") != true) return Trace.NO_FORMULA
            listOf(selRow to selCol)
        } else traceArrows.filter { it.sheet == sheetIndex && !it.dependent && !it.offSheet }.flatMap { a ->
            (a.r1..a.r2).flatMap { r -> (a.c1..a.c2).map { c -> r to c } }
        }.distinct().filter { (r, c) ->
            sheet.cell(r, c)?.text?.startsWith("=") == true && FormulaEngine.compositeKey(sheetIndex, r, c) !in tracedPrecedents
        }
        var added = 0
        for ((r, c) in frontier) {
            val key = FormulaEngine.compositeKey(sheetIndex, r, c)
            tracedPrecedents += key
            FormulaEngine.cached(sheet, r, c, workbook.sheets, workbook.names, memo)
            var offSheet = false
            for (p in memo.precedentsOf(key)) {
                if (p[0] != sheetIndex) { offSheet = true; continue }
                val (pr1, pc1, pr2, pc2) = listOf(p[1], p[2], minOf(p[3], sheet.rowCount - 1), minOf(p[4], sheet.colCount - 1))
                if (traceArrows.any { it.sheet == sheetIndex && !it.dependent && it.row == r && it.col == c && it.r1 == pr1 && it.c1 == pc1 && it.r2 == pr2 && it.c2 == pc2 }) continue
                traceArrows += TraceArrow(sheetIndex, pr1, pc1, pr2, pc2, r, c, false, pr1 == pr2 && pc1 == pc2 && shownError(pr1, pc1), false)
                added++
            }
            if (offSheet) { traceArrows += TraceArrow(sheetIndex, r, c, r, c, r, c, true, false, false); added++ }
        }
        bump()
        return if (added > 0) Trace.ADDED else Trace.NOTHING_MORE
    }

    /**
     * Excel's Trace Dependents: the first time, arrows from the active cell to the formulas on this sheet that read it;
     * each time again, one level further — to the formulas that read those.
     */
    fun traceDependents(): Trace {
        commitEntry()
        val own = FormulaEngine.compositeKey(sheetIndex, selRow, selCol)
        val frontier: List<Pair<Int, Int>> = if (own !in tracedDependents) listOf(selRow to selCol)
        else traceArrows.filter { it.sheet == sheetIndex && it.dependent }.map { it.row to it.col }.distinct()
            .filter { (r, c) -> FormulaEngine.compositeKey(sheetIndex, r, c) !in tracedDependents }
        val reads = sheet.cells.filter { it.value.text.startsWith("=") }.keys.associateWith { k -> precedentsOf(setOf(k), false) }
        var added = 0
        for ((r, c) in frontier) {
            tracedDependents += FormulaEngine.compositeKey(sheetIndex, r, c)
            val k = sheet.key(r, c)
            for ((f, rs) in reads) {
                if (k !in rs) continue
                val fr = (f / 1000L).toInt(); val fc = (f % 1000L).toInt()
                if (traceArrows.any { it.sheet == sheetIndex && it.dependent && it.r1 == r && it.c1 == c && it.row == fr && it.col == fc }) continue
                traceArrows += TraceArrow(sheetIndex, r, c, r, c, fr, fc, false, shownError(r, c), true)
                added++
            }
        }
        bump()
        // Nothing reads the active cell at all (not merely nothing more to add): say so, as Excel does.
        if (added == 0 && traceArrows.none { it.sheet == sheetIndex && it.dependent && it.r1 == selRow && it.c1 == selCol }) return Trace.NO_FORMULA
        return if (added > 0) Trace.ADDED else Trace.NOTHING_MORE
    }

    /** Evaluate Formula's first step for the active cell, or null when it holds no formula. */
    fun evaluationStart(): FormulaStep? {
        commitEntry()
        val text = sheet.cell(selRow, selCol)?.text ?: return null
        if (!text.startsWith("=")) return null
        return FormulaSteps.start(text, workbook.names.keys)
    }

    /** The step after [step], worked out where the active cell is (its own references, ROW() and the like as there). */
    fun evaluationNext(step: FormulaStep): FormulaStep {
        val at = selRow to selCol
        val eval: (String) -> String = { f -> FormulaEngine.literalOf(sheet, f, workbook.sheets, workbook.names, at) }
        return FormulaSteps.next(step, workbook.names.keys, eval) { ref -> eval(ref) }
    }

    /** Remove Arrows: all of them on this sheet ([precedents] and [dependents] both), or one kind. */
    fun removeArrows(precedents: Boolean = true, dependents: Boolean = true) {
        traceArrows.removeAll { it.sheet == sheetIndex && (if (it.dependent) dependents else precedents) }
        if (precedents) tracedPrecedents.removeAll { (it / 1_000_000_000L).toInt() == sheetIndex }
        if (dependents) tracedDependents.removeAll { (it / 1_000_000_000L).toInt() == sheetIndex }
        bump()
    }

    // --- Watch Window ---------------------------------------------------------------------------

    /** A cell in Excel's Watch Window: on [sheet] (dropped with it) at ([row], [col]); [seq] the order it was added in. */
    data class Watch(val sheet: Sheet, val seq: Long, val row: Int, val col: Int)

    /** A watch as the Watch Window lists it: its sheet, the defined name naming just that cell, the cell, what it shows and its formula. */
    class WatchRow(val watch: Watch, val sheet: String, val name: String, val cell: String, val value: String, val formula: String)

    /**
     * The cells watched, in the order added — for this session, as Excel's Watch Window (it is not saved). Each sheet
     * keeps its own ([Sheet.watched]), so a watch moves with inserted and deleted rows and columns, and undo puts it back.
     */
    val watches: List<Watch> get() = workbook.sheets.flatMap { s -> s.watched.map { Watch(s, it.seq, it.row, it.col) } }.sortedBy { it.seq }
    private var watchSeq = 0L

    /**
     * Add Watch: every cell of [reference] (`B6`, `B2:C4`, `Sheet2!A1`, a defined name) watched, those already watched
     * once; returns how many were added, -1 when [reference] is not a reference. At most [MAX_WATCH_ADD] cells at a time.
     */
    fun addWatches(reference: String): Int {
        val entry = reference.trim().removePrefix("=")
        val named = workbook.names.entries.firstOrNull { it.key.equals(entry, ignoreCase = true) }?.value
        val t = A1.parseTarget(named ?: entry) ?: return -1
        if (t.wholeColumns || t.wholeRows) return -1
        val s = if (t.sheet == null) sheet else workbook.sheets.firstOrNull { it.name.equals(t.sheet, ignoreCase = true) } ?: return -1
        var added = 0
        loop@ for (r in t.r1..t.r2) for (c in t.c1..t.c2) {
            if (added >= MAX_WATCH_ADD) break@loop
            if (s.watched.any { it.row == r && it.col == c }) continue
            s.watched += WatchMark(++watchSeq, r, c); added++
        }
        if (added > 0) bump()
        return added
    }

    /**
     * Excel's Import list from cells (Edit Custom Lists): the text the cells of [reference] show, row by row, the empty
     * ones left out and each once; null when the reference does not name cells.
     */
    fun listFromCells(reference: String): List<String>? {
        val t = A1.parseTarget(reference.trim().removePrefix("=")) ?: return null
        if (t.wholeColumns || t.wholeRows) return null
        val s = if (t.sheet == null) sheet else workbook.sheets.firstOrNull { it.name.equals(t.sheet, ignoreCase = true) } ?: return null
        val out = LinkedHashMap<String, String>()
        for (r in t.r1..t.r2) for (c in t.c1..t.c2) {
            val v = shownOn(workbook.sheets.indexOf(s), r, c).trim()
            if (v.isNotEmpty()) out.putIfAbsent(v.lowercase(), v)
        }
        return out.values.toList()
    }

    /** The selection as Add Watch's reference: `Sheet1!$B$6` or `Sheet1!$B$2:$C$4`. */
    fun watchReference(): String = A1.quoteSheet(sheet.name) + "!" + A1.rangeRef(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())

    fun removeWatch(w: Watch) { workbook.sheets.forEach { s -> s.watched.removeAll { it.seq == w.seq } }; bump() }

    /** The watches as the Watch Window shows them now (worked out each time it asks). */
    fun watchRows(): List<WatchRow> {
        return watches.map { w ->
            val si = workbook.sheets.indexOf(w.sheet)
            val cell = A1.rangeRef(w.row, w.col, w.row, w.col).replace("$", "")
            val name = workbook.names.entries.firstOrNull { (_, ref) ->
                A1.parseTarget(ref)?.let { t -> t.r1 == w.row && t.r2 == w.row && t.c1 == w.col && t.c2 == w.col &&
                    (t.sheet?.equals(w.sheet.name, ignoreCase = true) ?: (si == sheetIndex)) } == true
            }?.key.orEmpty()
            val text = w.sheet.cell(w.row, w.col)?.text.orEmpty()
            WatchRow(w, w.sheet.name, name, cell, shownOn(si, w.row, w.col), if (text.startsWith("=")) text else "")
        }
    }

    /** Goes to a watched cell (its sheet first) — where it is now, should rows or columns have moved it since [w] was read. */
    fun goToWatch(watch: Watch) {
        val w = watches.firstOrNull { it.seq == watch.seq } ?: return
        val si = workbook.sheets.indexOf(w.sheet)
        if (si < 0 || w.sheet.hidden) return
        if (si != sheetIndex) selectSheet(si)
        select(w.row, w.col)
    }

    /** Whether any tracer arrows are shown on this sheet (of the kind asked for). */
    fun hasArrows(dependent: Boolean? = null): Boolean = traceArrows.any { it.sheet == sheetIndex && (dependent == null || it.dependent == dependent) }

    /** [cells] (sheet keys) as blocks: runs along each row, runs of the same columns in following rows joined. */
    private fun areasOf(cells: Set<Long>): List<IntArray> {
        val rows = cells.groupBy { (it / 1000L).toInt() }.toSortedMap()
        val open = ArrayList<IntArray>()   // blocks still growing downwards
        val done = ArrayList<IntArray>()
        for ((r, keys) in rows) {
            val cols = keys.map { (it % 1000L).toInt() }.sorted()
            val runs = ArrayList<IntArray>()
            var start = cols[0]; var prev = cols[0]
            for (c in cols.drop(1)) { if (c != prev + 1) { runs += intArrayOf(start, prev); start = c }; prev = c }
            runs += intArrayOf(start, prev)
            val still = ArrayList<IntArray>()
            for (run in runs) {
                val grow = open.firstOrNull { it[2] == r - 1 && it[1] == run[0] && it[3] == run[1] }
                if (grow != null) { grow[2] = r; open.remove(grow); still += grow } else still += intArrayOf(r, run[0], r, run[1])
            }
            done += open; open.clear(); open += still
        }
        done += open
        return done.sortedWith(compareBy({ it[0] }, { it[1] }))
    }

    // --- selecting with the pointer, the Name Box, room past the sheet's end --------------------

    /**
     * Selects the block from ([anchorRow], [anchorCol]) — which stays the active cell — to ([row],
     * [col]): a selection grip or a press-and-drag across the cells. Where the block cuts through
     * merged cells on the far side it grows to cover them, as in Excel.
     */
    fun selectRange(anchorRow: Int, anchorCol: Int, row: Int, col: Int) {
        if (!selectable(anchorRow.coerceIn(0, sheet.rowCount - 1), anchorCol.coerceIn(0, sheet.colCount - 1))) return
        areaStore = null
        if (pendingEntry?.let { it.row != anchorRow || it.col != anchorCol } == true) commitEntry()
        val ar = anchorRow.coerceIn(0, sheet.rowCount - 1); val ac = anchorCol.coerceIn(0, sheet.colCount - 1)
        val fr = row.coerceIn(0, sheet.rowCount - 1); val fc = col.coerceIn(0, sheet.colCount - 1)
        val down = fr >= ar; val right = fc >= ac
        var r1 = minOf(ar, fr); var r2 = maxOf(ar, fr); var c1 = minOf(ac, fc); var c2 = maxOf(ac, fc)
        // Covering one merge can cut into another: repeat until nothing grows. The anchor's sides
        // stay put (the active cell cannot move), so only the far sides grow.
        var grown = true
        while (grown) {
            grown = false
            for (m in sheet.merges) {
                if (m.r1 > r2 || m.r2 < r1 || m.c1 > c2 || m.c2 < c1) continue
                if (down && m.r2 > r2) { r2 = m.r2; grown = true }
                if (!down && m.r1 < r1) { r1 = m.r1; grown = true }
                if (right && m.c2 > c2) { c2 = m.c2; grown = true }
                if (!right && m.c1 < c1) { c1 = m.c1; grown = true }
            }
        }
        val farRow = if (down) r2 else r1
        val farCol = if (right) c2 else c1
        if (selRow == ar && selCol == ac && selRow2 == farRow && selCol2 == farCol) return
        selRow = ar; selCol = ac; selRow2 = farRow; selCol2 = farCol
        lastKey = null
        bump()
    }

    /** The selection as the Name Box shows it: `B3`, `B3:D8`, whole columns `B:D`, whole rows `3:5`; the whole sheet shows its active cell. */
    fun selectionRef(): String {
        val cols = wholeColumnsSelected
        val rows = wholeRowsSelected
        return when {
            cols && rows -> A1.ref(selRow, selCol)
            cols && sheet.rowCount > 1 -> Workbook.colLabel(rangeColMin()) + ":" + Workbook.colLabel(rangeColMax())
            rows && sheet.colCount > 1 -> "${rangeRowMin() + 1}:${rangeRowMax() + 1}"
            else -> A1.rangeRef(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
        }
    }

    /** What the Name Box did with an entry. */
    enum class GoTo { SELECTED, DEFINED, INVALID }

    /**
     * Excel's Name Box: goes to a reference (`C5`, `B2:D10`, `B:D`, `3:5`, `Sheet2!A1`) or to a
     * defined name — switching sheets when it points at another one, and making room when it lies
     * past the sheet's end. Any other valid name is defined for the selection, as in Excel.
     */
    fun goTo(text: String): GoTo {
        val entry = text.trim()
        if (entry.isEmpty()) return GoTo.INVALID
        val named = workbook.names.entries.firstOrNull { it.key.equals(entry, ignoreCase = true) }?.value
        val t = A1.parseTarget(named ?: entry)
            ?: return if (named == null && defineName(entry)) GoTo.DEFINED else GoTo.INVALID
        val idx = if (t.sheet == null) sheetIndex else workbook.sheets.indexOfFirst { it.name.equals(t.sheet, ignoreCase = true) }
        // A hidden sheet cannot be gone to, as in Excel (its formulas still read it).
        if (idx < 0 || workbook.sheets[idx].hidden || (!t.wholeRows && t.c2 >= Sheet.MAX_COLS) || (!t.wholeColumns && t.r2 >= MAX_ROWS)) return GoTo.INVALID
        if (idx != sheetIndex) selectSheet(idx)
        if (!t.wholeColumns && t.r2 >= sheet.rowCount) growRows(t.r2 + 1 - sheet.rowCount + ROOM_ROWS)
        if (!t.wholeRows && t.c2 >= sheet.colCount) growCols(t.c2 + 1 - sheet.colCount + ROOM_COLS)
        when {
            t.wholeColumns -> selectColumns(t.c1, t.c2)
            t.wholeRows -> selectRows(t.r1, t.r2)
            else -> selectRange(t.r1, t.c1, t.r2, t.c2)
        }
        return GoTo.SELECTED
    }

    /** Go To's earlier references, the latest first — the last four, as Excel lists them. */
    val goToRecent = androidx.compose.runtime.mutableStateListOf<String>()

    /**
     * Excel's Go To (F5): goes to a reference or a defined name — and, unlike the Name Box, never defines one;
     * false when [text] is neither. A reference gone to is kept at the top of [goToRecent].
     */
    fun goToReference(text: String): Boolean {
        val entry = text.trim().removePrefix("=")
        val named = workbook.names.keys.any { it.equals(entry, ignoreCase = true) }
        if (entry.isEmpty() || !named && A1.parseTarget(entry) == null) return false
        if (goTo(entry) != GoTo.SELECTED) return false
        if (!named) {
            goToRecent.removeAll { it.equals(entry, ignoreCase = true) }
            goToRecent.add(0, entry)
            while (goToRecent.size > 4) goToRecent.removeAt(goToRecent.lastIndex)
        }
        return true
    }

    /**
     * Room past the sheet's end, as the view or the selection reaches it. Empty rows and columns
     * are not saved, so this is no edit: no undo step and nothing new to save. A selection of whole
     * columns (or rows, or the sheet) keeps covering them.
     */
    fun growRows(n: Int) {
        if (n <= 0 || sheet.rowCount >= MAX_ROWS) return
        val whole = wholeColumnsSelected
        sheet.rowCount = (sheet.rowCount + n).coerceAtMost(MAX_ROWS)
        if (whole) { if (selRow2 >= selRow) selRow2 = sheet.rowCount - 1 else selRow = sheet.rowCount - 1 }
        bump()
    }

    /** [growRows] for columns, up to [Sheet.MAX_COLS]. */
    fun growCols(n: Int) {
        if (n <= 0 || sheet.colCount >= Sheet.MAX_COLS) return
        val whole = wholeRowsSelected
        sheet.colCount = (sheet.colCount + n).coerceAtMost(Sheet.MAX_COLS)
        if (whole) { if (selCol2 >= selCol) selCol2 = sheet.colCount - 1 else selCol = sheet.colCount - 1 }
        bump()
    }

    // --- hide / unhide columns -----------------------------------------------------------------

    /** Hides the selected columns. False when that would hide every column of the sheet. */
    fun hideColumns(): Boolean {
        if (refuseIfSheetProtected(Allow.FORMAT_COLUMNS)) return false
        val cols = (rangeColMin()..rangeColMax()).toSet()
        if ((0 until sheet.colCount).all { it in cols || it in sheet.hiddenCols }) return false
        if (sheet.hiddenCols.containsAll(cols)) return false
        pushUndo(sheetMeta())
        sheet.hiddenCols.addAll(cols)
        keepSelectionVisible()
        touchContent()
        return true
    }

    /**
     * Shows the hidden columns inside the selection (select across them, as in Excel), or — when
     * there are none — the hidden run right next to it. False when nothing was hidden there.
     */
    fun unhideColumns(): Boolean {
        if (refuseIfSheetProtected(Allow.FORMAT_COLUMNS)) return false
        val lo = rangeColMin(); val hi = rangeColMax()
        var show = sheet.hiddenCols.filter { it in lo..hi }
        if (show.isEmpty()) {
            val next = ArrayList<Int>()
            var c = lo - 1; while (c >= 0 && c in sheet.hiddenCols) { next.add(c); c-- }
            c = hi + 1; while (c < sheet.colCount && c in sheet.hiddenCols) { next.add(c); c++ }
            show = next
        }
        if (show.isEmpty()) return false
        pushUndo(sheetMeta())
        sheet.hiddenCols.removeAll(show.toSet())
        touchContent()
        return true
    }

    /** Moves the active cell off rows / columns that just folded away or were hidden. */
    private fun keepSelectionVisible() {
        val rows = invisibleRows
        val cols = invisibleCols
        var r = selRow
        while (r in rows && r < sheet.rowCount - 1) r++
        while (r in rows && r > 0) r--
        var c = selCol
        while (c in cols && c < sheet.colCount - 1) c++
        while (c in cols && c > 0) c--
        if (r != selRow || c != selCol) select(r, c)
    }

    // --- subtotals (分类汇总) ---------------------------------------------------------
    /**
     * Excel's Data → Subtotal over the selected block: at each change of [groupCol] a row with
     * "<key> Total" and `=SUBTOTAL(fn, …)` for every [targetCols] column is inserted, plus a
     * Grand Total row after the block (SUBTOTAL skips the group rows). [fn] is the SUBTOTAL
     * function number (9 SUM, 1 AVERAGE, 2 COUNT, 4 MAX, 5 MIN). Rows below the block shift
     * down; one undo step. Returns the number of rows inserted (0 when the block is unusable).
     */
    fun subtotal(groupCol: Int, fn: Int, targetCols: List<Int>, headerRow: Boolean = true): Int {
        if (refuseIfSheetProtected()) return 0
        val r1 = rangeRowMin() + (if (headerRow) 1 else 0); val r2 = rangeRowMax()
        val c1 = rangeColMin(); val c2 = rangeColMax()
        val targets = targetCols.filter { it in c1..c2 && it != groupCol }.distinct()
        if (r2 - r1 < 1 || groupCol !in c1..c2 || targets.isEmpty()) return 0
        // Groups: runs of equal text in the group column (the data is expected to be sorted by it).
        val groups = ArrayList<IntArray>()   // [start, end] inclusive, in the current coordinates
        var start = r1
        for (r in r1 + 1..r2 + 1) {
            if (r > r2 || (sheet.cell(r, groupCol)?.text ?: "") != (sheet.cell(start, groupCol)?.text ?: "")) { groups.add(intArrayOf(start, r - 1)); start = r }
        }
        pushUndo(wholeSheet())
        fun ref(c: Int, ra: Int, rb: Int) = "${Workbook.colLabel(c)}${ra + 1}:${Workbook.colLabel(c)}${rb + 1}"
        fun labelCell(r: Int, text: String) { sheet.ensure(r, groupCol).apply { this.text = text; runs = null; format.bold = true } }
        // Each group from the bottom up so earlier rows keep their indices, then the grand total
        // over the whole block (its group rows are skipped by SUBTOTAL).
        for (g in groups.asReversed()) {
            val at = g[1] + 1
            rewriteFormulas(Axis.ROW, at, +1); shiftInsertRow(at)
            val key = sheet.cell(g[0], groupCol)?.text ?: ""
            labelCell(at, Texts.get(TextId.SUBTOTAL_TOTAL).replace("%s", key))
            targets.forEach { c -> sheet.ensure(at, c).apply { text = "=SUBTOTAL($fn, ${ref(c, g[0], g[1])})"; runs = null; format.bold = true } }
        }
        val total = r2 + groups.size + 1
        rewriteFormulas(Axis.ROW, total, +1); shiftInsertRow(total)
        labelCell(total, Texts.get(TextId.SUBTOTAL_GRAND))
        targets.forEach { c -> sheet.ensure(total, c).apply { text = "=SUBTOTAL($fn, ${ref(c, r1, total - 1)})"; runs = null; format.bold = true } }
        // Excel outlines the result: the whole block at level 1 above the grand total, each detail
        // block at level 2 above its subtotal row (group i moved down by the i rows inserted above it).
        // Groups that would clash with an existing outline are left out.
        val outline = listOf(com.therealityteam.aeropages.model.RowGroup(r1, total - 1)) +
            groups.mapIndexed { i, g -> com.therealityteam.aeropages.model.RowGroup(g[0] + i, g[1] + i) }
        for (og in outline) if (com.therealityteam.aeropages.model.Outline.canAdd(sheet.rowGroups, og)) sheet.rowGroups.add(og)
        sheet.rowGroups.sortWith(compareBy({ it.r1 }, { -it.r2 }))
        touchContent()
        return groups.size + 1
    }

    // --- scenarios (方案管理器) ------------------------------------------------------
    /** Saves the selected cells' current texts as scenario [name] (replacing one of that name); false for an empty name or a block over 64 cells. */
    fun addScenario(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val values = LinkedHashMap<Long, String>()
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) values[sheet.key(r, c)] = sheet.cell(r, c)?.text ?: ""
        if (values.size > 64) return false
        pushUndo(sheetMeta())
        sheet.scenarios.removeAll { it.name == n }
        sheet.scenarios.add(com.therealityteam.aeropages.model.Scenario(n, values))
        dirty = true; bump()
        return true
    }
    fun deleteScenario(name: String) {
        if (sheet.scenarios.none { it.name == name }) return
        pushUndo(sheetMeta()); sheet.scenarios.removeAll { it.name == name }; dirty = true; bump()
    }
    /** Swaps a scenario's values into its input cells (one undo step). */
    fun showScenario(name: String): Boolean {
        val sc = sheet.scenarios.firstOrNull { it.name == name } ?: return false
        if (sc.values.isEmpty()) return false
        val rows = sc.values.keys.map { (it / 1000L).toInt() }; val cols = sc.values.keys.map { (it % 1000L).toInt() }
        if (refuseIfProtected(rows.min(), cols.min(), rows.max(), cols.max())) return false
        pushUndo(cells(rows.min(), cols.min(), rows.max(), cols.max()))
        sc.values.forEach { (k, v) -> sheet.ensure((k / 1000L).toInt(), (k % 1000L).toInt()).apply { text = v; runs = null } }
        touchContent()
        return true
    }
    /**
     * Excel's scenario summary: a new sheet with one row per input cell and per result cell (the
     * current selection when it lies outside the inputs), one column per scenario plus the
     * current values; result rows show each scenario's outcome. Returns the sheet's name.
     */
    fun scenarioSummary(): String? {
        val scenarios = sheet.scenarios.toList()
        if (scenarios.isEmpty()) return null
        val inputs = scenarios.flatMap { it.values.keys }.distinct().sorted()
        val results = ArrayList<Long>()
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) { val k = sheet.key(r, c); if (k !in inputs) results.add(k) }
        val rc = { k: Long -> (k / 1000L).toInt() to (k % 1000L).toInt() }
        // Evaluate every scenario in turn (values swapped in without undo, then restored).
        val original = inputs.associateWith { k -> sheet.cell(rc(k).first, rc(k).second)?.text ?: "" }
        val currentResults = results.map { k -> rawValue(rc(k).first, rc(k).second) }
        val perScenario = scenarios.map { sc ->
            inputs.forEach { k -> sheet.ensure(rc(k).first, rc(k).second).text = sc.values[k] ?: original[k] ?: "" }
            invalidate()
            results.map { k -> rawValue(rc(k).first, rc(k).second) }
        }
        inputs.forEach { k -> sheet.ensure(rc(k).first, rc(k).second).text = original[k] ?: "" }
        invalidate()
        // The summary sheet.
        val base = Texts.get(TextId.SCENARIO_SUMMARY_SHEET)
        var name = base; var n = 2
        while (workbook.sheets.any { it.name == name }) name = "$base ${n++}"
        val summary = Sheet(name = name, rowCount = (inputs.size + results.size + 4).coerceAtLeast(20), colCount = (scenarios.size + 3).coerceAtLeast(8))
        fun put(r: Int, c: Int, text: String, bold: Boolean = false) { summary.ensure(r, c).apply { this.text = text; format.bold = bold } }
        put(0, 0, Texts.get(TextId.SCENARIO_SUMMARY_CELL), bold = true)
        put(0, 1, Texts.get(TextId.SCENARIO_CURRENT), bold = true)
        scenarios.forEachIndexed { i, sc -> put(0, 2 + i, sc.name, bold = true) }
        inputs.forEachIndexed { row, k ->
            put(1 + row, 0, com.therealityteam.aeropages.model.A1.ref(rc(k).first, rc(k).second))
            put(1 + row, 1, original[k] ?: "")
            scenarios.forEachIndexed { i, sc -> put(1 + row, 2 + i, sc.values[k] ?: original[k] ?: "") }
        }
        results.forEachIndexed { row, k ->
            val r = 1 + inputs.size + row
            put(r, 0, com.therealityteam.aeropages.model.A1.ref(rc(k).first, rc(k).second), bold = true)
            put(r, 1, currentResults[row])
            perScenario.forEachIndexed { i, vals -> put(r, 2 + i, vals[row]) }
        }
        summary.colWidths[0] = 1.2f
        pushUndo(SheetSlotDelta(workbook.sheets.size, null))
        workbook.sheets.add(summary)
        sheetIndex = workbook.sheets.lastIndex; selRow = 0; selCol = 0
        touchContent()
        return name
    }

    // --- what-if analysis (模拟分析) ------------------------------------------------
    /**
     * Goal Seek: change ([changeRow], [changeCol]) until the formula at ([setRow], [setCol])
     * evaluates to [target]. One undo step; the changing cell keeps the solution (rounded to
     * ten decimals) or is restored when no solution exists. Returns the solution.
     */
    fun goalSeek(setRow: Int, setCol: Int, target: Double, changeRow: Int, changeCol: Int): Double? {
        if (refuseIfProtected(changeRow, changeCol, changeRow, changeCol)) return null
        if (setRow == changeRow && setCol == changeCol) return null
        val cell = sheet.ensure(changeRow, changeCol)
        val original = cell.text
        if (original.startsWith("=")) return null   // Excel: the changing cell must hold a value, not a formula
        val start = original.toDoubleOrNull() ?: 0.0
        fun probe(x: Double): Double? {
            cell.text = fmt(x); invalidate()
            return numericValue(setRow, setCol)
        }
        val solution = GoalSeek.solve(::probe, target, start)
        cell.text = original; invalidate()
        if (solution == null) { bump(); return null }
        pushUndo(cells(changeRow, changeCol, changeRow, changeCol))
        cell.text = fmt(solution)
        touchContent()
        return solution
    }
    /**
     * Data Table (模拟运算表) over the selected block: with both inputs the top-left cell is the
     * formula, the top row and left column the values substituted into [rowInput] / [colInput];
     * with only [colInput] the top row holds formulas and the left column the values (and the
     * mirror for [rowInput] alone). The interior receives the results as values — one undo step,
     * not a live array formula. Returns the number of cells filled, 0 when the block is unusable.
     */
    fun dataTable(rowInput: Pair<Int, Int>?, colInput: Pair<Int, Int>?): Int {
        val r1 = rangeRowMin(); val c1 = rangeColMin(); val r2 = rangeRowMax(); val c2 = rangeColMax()
        if (r2 - r1 < 1 || c2 - c1 < 1 || (rowInput == null && colInput == null)) return 0
        if (refuseIfProtected(r1 + 1, c1 + 1, r2, c2)) return 0
        val inputs = listOfNotNull(rowInput, colInput)
        if (inputs.any { (r, c) -> sheet.cell(r, c)?.text?.startsWith("=") == true }) return 0
        if (inputs.any { (r, c) -> r in r1..r2 && c in c1..c2 }) return 0
        val isFormula = { r: Int, c: Int -> sheet.cell(r, c)?.text?.startsWith("=") == true }
        // Which cell's formula each interior cell evaluates, and which header values feed the inputs.
        val formulaOf: (Int, Int) -> Pair<Int, Int>? = when {
            rowInput != null && colInput != null -> { _, _ -> (r1 to c1).takeIf { isFormula(r1, c1) } }
            colInput != null -> { _, c -> (r1 to c).takeIf { isFormula(r1, c) } }      // formulas across the top row
            else -> { r, _ -> (r to c1).takeIf { isFormula(r, c1) } }                  // formulas down the left column
        }
        val originals = inputs.map { (r, c) -> sheet.ensure(r, c).let { it to it.text } }
        val results = HashMap<Long, String>()
        for (r in r1 + 1..r2) for (c in c1 + 1..c2) {
            val f = formulaOf(r, c) ?: continue
            rowInput?.let { (ir, ic) -> sheet.ensure(ir, ic).text = sheet.cell(r1, c)?.text ?: "" }
            colInput?.let { (ir, ic) -> sheet.ensure(ir, ic).text = sheet.cell(r, c1)?.text ?: "" }
            invalidate()
            results[r * 1000L + c] = rawValue(f.first, f.second)
        }
        originals.forEach { (cell, text) -> cell.text = text }
        invalidate()
        if (results.isEmpty()) { bump(); return 0 }
        pushUndo(cells(r1 + 1, c1 + 1, r2, c2))
        results.forEach { (key, v) -> sheet.ensure((key / 1000L).toInt(), (key % 1000L).toInt()).apply { text = v; runs = null } }
        touchContent()
        return results.size
    }

    private fun fmt(x: Double): String {
        // Ten decimals, but Math.round overflows past ~9e8 × 1e10: leave big magnitudes as they are.
        val r = if (abs(x) < 1e8) Math.round(x * 1e10) / 1e10 else x
        return if (r == Math.rint(r) && abs(r) < 1e15) r.toLong().toString() else r.toString()
    }

    // --- data validation (数据验证) ----------------------------------------------

    /** Data Validation on the selection: a [type] rule within [min]..[max] or among [items] (the simple form of [setValidation]). */
    fun addValidation(type: ValidationType, min: Double, max: Double, items: List<String>) =
        setValidation(DataValidation(0, 0, 0, 0, type, min, max, items))

    /**
     * Data Validation's OK: [rule] (whatever cells it names) on the selected cells, in place of the
     * rules they had — a rule reaching past the selection keeps its other cells, as validation
     * belongs to each cell in Excel. Refused on a protected sheet.
     */
    fun setValidation(rule: DataValidation) {
        if (refuseIfSheetProtected()) return
        commitEntry()
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        pushUndo(sheetMeta())
        cutValidations(r1, c1, r2, c2)
        sheet.validations.add(rule.moved(r1, c1, r2, c2))
        touchContent()
    }

    /** Data Validation's Clear All: the selected cells lose their rules; a rule's other cells keep it. */
    fun clearValidations() {
        if (refuseIfSheetProtected()) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (sheet.validations.none { it.r1 <= r2 && it.r2 >= r1 && it.c1 <= c2 && it.c2 >= c1 }) return
        pushUndo(sheetMeta()) // record BEFORE mutating, or undo restores the already-cleared state
        cutValidations(r1, c1, r2, c2)
        touchContent()
    }

    /** Takes the block out of every rule: what is left of one stays, in up to four pieces. */
    private fun cutValidations(r1: Int, c1: Int, r2: Int, c2: Int) {
        val out = ArrayList<DataValidation>()
        for (dv in sheet.validations) {
            if (dv.r2 < r1 || dv.r1 > r2 || dv.c2 < c1 || dv.c1 > c2) { out.add(dv); continue }
            val top = maxOf(dv.r1, r1); val bottom = minOf(dv.r2, r2)
            if (dv.r1 < r1) out.add(piece(dv, dv.r1, dv.c1, r1 - 1, dv.c2))
            if (dv.r2 > r2) out.add(piece(dv, r2 + 1, dv.c1, dv.r2, dv.c2))
            if (dv.c1 < c1) out.add(piece(dv, top, dv.c1, bottom, c1 - 1))
            if (dv.c2 > c2) out.add(piece(dv, top, c2 + 1, bottom, dv.c2))
        }
        sheet.validations.clear(); sheet.validations.addAll(out)
    }

    /** [dv] on the block [r1],[c1]..[r2],[c2] only: relative references in its formulas follow its top-left cell there. */
    private fun piece(dv: DataValidation, r1: Int, c1: Int, r2: Int, c2: Int): DataValidation {
        val dr = r1 - dv.r1; val dc = c1 - dv.c1
        fun rebase(f: String?) = if (f == null || (dr == 0 && dc == 0)) f else shiftFormulaRefs(f, dr, dc)
        return dv.copy(r1 = r1, c1 = c1, r2 = r2, c2 = c2, formula1 = rebase(dv.formula1), formula2 = rebase(dv.formula2))
    }

    fun validationAt(row: Int, col: Int): DataValidation? = sheet.validations.firstOrNull { it.covers(row, col) }

    /** How rules read entries (as typing does) and evaluate their formulas (against this workbook, on the active sheet). */
    private val scope = object : ValidationScope {
        override fun number(text: String): Double? =
            text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: EntryParser.parse(text, symbols = symbols)?.value?.toDoubleOrNull()
        override fun evaluate(formula: String, rule: DataValidation, row: Int, col: Int): Double? =
            evalRule(formula, rule, row, col)?.let { v -> v.toDoubleOrNull() ?: number(v) }
        override fun values(source: String, rule: DataValidation, row: Int, col: Int): List<String> = listSource(source, rule, row, col)
        override fun isTrue(formula: String, rule: DataValidation, row: Int, col: Int): Boolean {
            val v = evalRule(formula, rule, row, col) ?: return false
            return v.equals("TRUE", ignoreCase = true) || (v.toDoubleOrNull()?.let { it != 0.0 } ?: false)
        }
    }

    /** A rule's [formula] evaluated at ([row], [col]): relative references move there from the rule's top-left cell. */
    private fun evalRule(formula: String, rule: DataValidation, row: Int, col: Int): String? {
        val f = formula.trim().removePrefix("=")
        val at = if (row == rule.r1 && col == rule.c1) f else shiftFormulaRefs(f, row - rule.r1, col - rule.c1)
        return FormulaEngine.evaluateText(sheet, at, workbook.sheets, workbook.names, row to col)
    }

    /**
     * What a list's [source] holds at ([row], [col]): a range (one row or column, as Excel asks) or a
     * defined name — or INDIRECT of text naming one, for lists that depend on another cell — read as
     * its cells show their values, blanks left out.
     */
    private fun listSource(source: String, rule: DataValidation, row: Int, col: Int): List<String> {
        var src = source.trim().removePrefix("=")
        val indirect = Regex("""^INDIRECT\s*\((.*)\)$""", RegexOption.IGNORE_CASE).matchEntire(src)
        src = if (indirect != null) evalRule(indirect.groupValues[1], rule, row, col) ?: return emptyList()
            else if (row != rule.r1 || col != rule.c1) shiftFormulaRefs(src, row - rule.r1, col - rule.c1) else src
        val named = workbook.names.entries.firstOrNull { it.key.equals(src.trim(), ignoreCase = true) }?.value
        val ref = named ?: src
        val memoKey = "$sheetIndex|$ref"
        listMemo?.get(memoKey)?.let { return it }
        val t = A1.parseTarget(ref) ?: return emptyList()
        val idx = if (t.sheet == null) sheetIndex else workbook.sheets.indexOfFirst { it.name.equals(t.sheet, ignoreCase = true) }
        val target = workbook.sheets.getOrNull(idx) ?: return emptyList()
        val r1 = if (t.wholeColumns) 0 else t.r1; val r2 = if (t.wholeColumns) Int.MAX_VALUE else t.r2
        val c1 = if (t.wholeRows) 0 else t.c1; val c2 = if (t.wholeRows) Int.MAX_VALUE else t.c2
        // The source's cells that hold something, in reading order: a block is read cell by cell, whole
        // columns or rows by what the sparse sheet holds.
        val area = (r2.toLong() - r1 + 1) * (c2.toLong() - c1 + 1)
        val keys = if (area <= 20_000L) (r1..r2).flatMap { r -> (c1..c2).map { c -> target.key(r, c) } }.filter { it in target.cells }
            else target.cells.keys.filter { k -> val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt(); r in r1..r2 && c in c1..c2 }.sorted()
        val out = ArrayList<String>()
        for (k in keys) {
            if (out.size >= MAX_LIST_ITEMS) break
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            val shown = if (idx == sheetIndex) display(r, c)
                else NumberFormatting.shown(FormulaEngine.display(target, r, c, workbook.sheets, workbook.names), target.cell(r, c), symbols, dates).text
            if (shown.isNotBlank()) out.add(shown)
        }
        listMemo?.put(memoKey, out)
        return out
    }

    /** List sources read during one pass over many cells (circling), by what they resolve to: a rule's cells mostly share one. */
    private var listMemo: HashMap<String, List<String>>? = null

    private inline fun <T> withListMemo(block: () -> T): T {
        listMemo = HashMap()
        try { return block() } finally { listMemo = null }
    }

    /** The rule [text] would break at ([row], [col]) and why; null when it passes or the rule raises no alert. */
    private fun brokenRule(row: Int, col: Int, text: String): Pair<DataValidation, String>? {
        val rule = validationAt(row, col) ?: return null
        if (!rule.showError) return null
        if (rule.type != ValidationType.CUSTOM) return rule.validate(text, row, col, scope)?.let { rule to it }
        // A Custom formula may read the cell itself: judge it with the entry in place, then put the cell back.
        val k = sheet.key(row, col)
        val had = sheet.cells[k]
        val saved = had?.text
        sheet.ensure(row, col).text = text
        try {
            return rule.validate(text, row, col, scope)?.let { rule to it }
        } finally {
            if (had == null) sheet.cells.remove(k) else had.text = saved ?: ""
        }
    }

    /**
     * An entry that broke its cell's validation rule, waiting for the alert's answer — Retry or
     * Cancel for Stop ([retryEntry], [cancelEntry]); Yes, No or Cancel for Warning ([keepEntry],
     * [retryEntry], [cancelEntry]); OK or Cancel for Information ([keepEntry], [cancelEntry]).
     */
    class ValidationAlert internal constructor(
        val sheet: Int, val row: Int, val col: Int,
        /** The entry as it was typed. */
        val text: String,
        val rule: DataValidation,
        /** What the rule allows, in words: the alert's message when the rule has none of its own. */
        val reason: String,
        internal val before: Cell?,
        internal val step: Any?,
    ) {
        val style: ValidationAlertStyle get() = rule.errorStyle
        val message: String get() = rule.errorMessage.ifBlank { reason }
    }

    /** The alert an entry raised, until it is answered. */
    var validationAlert by mutableStateOf<ValidationAlert?>(null)
        private set

    /**
     * The entry made in [p]'s cell against its rule: [typed] for a list (it matches what the list
     * shows), the value it was read as for the others; a Custom formula reads the cell as it now is.
     */
    private fun checkEntry(p: PendingEntry, typed: String) {
        val rule = validationAt(p.row, p.col) ?: return
        if (!rule.showError) return
        val value = sheet.cell(p.row, p.col)?.text ?: ""
        val why = rule.validate(if (rule.type == ValidationType.LIST) typed else value, p.row, p.col, scope) ?: return
        val step = if (lastKey == "cell:${p.sheet}:${p.row}:${p.col}") history.peek() else null
        val alert = ValidationAlert(p.sheet, p.row, p.col, typed, rule, why, p.before, step)
        validationAlert = alert
        if (rule.errorStyle == ValidationAlertStyle.STOP) revertEntry(alert)
    }

    /** The alert's cell as it was before the entry: the typing's undo step is taken back while it is still the latest. */
    private fun revertEntry(a: ValidationAlert) {
        if (a.step != null && history.peek() === a.step) {
            history.dropLast(workbook)
            verifyPending = null; lastKey = null
            pendingScope = Scope.Rect(a.sheet, a.row, a.col, a.row, a.col)
            touchContent()
            return
        }
        val target = workbook.sheets.getOrNull(a.sheet) ?: return
        pushUndo(CellRangeDelta.capture(a.sheet, target, a.row, a.col, a.row, a.col))
        val k = target.key(a.row, a.col)
        if (a.before == null) target.cells.remove(k) else target.cells[k] = a.before.copyOf()
        touchContent()
    }

    /** Retry (Stop) or No (Warning): back into the cell with the entry as it was typed, to put it right. */
    fun retryEntry() {
        val a = validationAlert ?: return
        validationAlert = null
        if (a.style != ValidationAlertStyle.STOP) revertEntry(a)
        if (a.sheet != sheetIndex) selectSheet(a.sheet)
        select(a.row, a.col)
        typeCell(a.row, a.col, a.text)
    }

    /** Whether a cell is being typed into (the formula bar's ✕ and ✓ apply). */
    val entryOpen: Boolean get() = pendingEntry != null

    /**
     * Excel's Cancel beside the formula bar (and Esc while typing): the cell goes back to what it held
     * before the entry. Returns whether there was an entry to give up.
     */
    fun abandonEntry(): Boolean {
        val p = pendingEntry ?: return false
        pendingEntry = null
        val target = workbook.sheets.getOrNull(p.sheet) ?: return false
        pushUndo(CellRangeDelta.capture(p.sheet, target, p.row, p.col, p.row, p.col))
        val k = target.key(p.row, p.col)
        if (p.before == null) target.cells.remove(k) else target.cells[k] = p.before.copyOf()
        touchContent()
        return true
    }

    /** Cancel: the cell keeps what it held before the entry (a Stop alert has put it back already). */
    fun cancelEntry() {
        val a = validationAlert ?: return
        validationAlert = null
        if (a.style != ValidationAlertStyle.STOP) revertEntry(a)
    }

    /** Yes (Warning) or OK (Information): the entry stays as it is. */
    fun keepEntry() { validationAlert = null }

    /** Whether ([row], [col]) has a list rule's drop-down (Excel's In-cell dropdown). */
    fun hasListDropdown(row: Int, col: Int): Boolean = validationAt(row, col)?.let { it.type == ValidationType.LIST && it.dropdown } == true

    /** What the drop-down on ([row], [col]) offers — a list rule's values, once each — or null when the cell has none. */
    fun listChoices(row: Int = selRow, col: Int = selCol): List<String>? {
        val rule = validationAt(row, col)?.takeIf { it.type == ValidationType.LIST && it.dropdown } ?: return null
        return rule.listValues(row, col, scope).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // --- Excel's Flash Fill ------------------------------------------------------------------------

    /**
     * Excel's Flash Fill (Data ▸ Flash Fill, Ctrl+E) in the active cell's column: the rows of its block that have text
     * there are examples of what to make from the row's other columns ([FlashFill]); the rows left blank get the same,
     * made from theirs — one undo step. A first example that fits no way with the others is taken for a header. How
     * many cells were filled: 0 when the column had no blanks to fill, -1 when no way fits the examples.
     */
    fun flashFill(): Int {
        commitEntry()
        if (refuseIfProtected()) return 0
        val col = selCol
        val region = currentRegion(selRow, col) ?: return -1
        val sources = (region[1]..region[3]).filter { it != col }
        if (sources.isEmpty()) return -1
        fun rowTexts(r: Int) = sources.associateWith { display(r, it).trim() }.filterValues { it.isNotEmpty() }
        fun typed(r: Int) = sheet.cell(r, col)?.text?.trim()?.takeIf { it.isNotEmpty() }
        val rows = region[0]..region[2]
        val examples = rows.filter { typed(it) != null && rowTexts(it).isNotEmpty() }
        if (examples.isEmpty()) return -1
        val blanks = rows.filter { typed(it) == null && rowTexts(it).isNotEmpty() }
        if (blanks.isEmpty()) return 0
        fun learn(from: List<Int>) = FlashFill.learn(from.map { rowTexts(it) to display(it, col).trim() })
        val way = learn(examples) ?: (if (examples.size > 1) learn(examples.drop(1)) else null) ?: return -1
        val filled = blanks.mapNotNull { r -> FlashFill.apply(way, rowTexts(r))?.let { r to it } }
        if (filled.isEmpty()) return -1
        pushUndo(cells(filled.first().first, col, filled.last().first, col))
        for ((r, v) in filled) sheet.ensure(r, col).also { it.text = v; it.runs = null }
        touchContent()
        return filled.size
    }

    // --- Excel's AutoComplete and Pick From Drop-down List ----------------------------------------

    /**
     * The text entries of column [col] in the block of filled cells around row [row] — up from the row above and down
     * from the row below, each to the first empty cell — once each (case aside) in the order met. Numbers, dates,
     * formulas and TRUE / FALSE are not entries, as Excel lists none of them.
     */
    private fun columnEntries(row: Int, col: Int): List<String> {
        val seen = LinkedHashMap<String, String>()
        fun take(r: Int): Boolean {
            val c = sheet.cell(r, col) ?: return false
            val t = c.text.trim()
            if (t.isEmpty()) return false
            val text = !t.startsWith("=") && t != "TRUE" && t != "FALSE" && t.toDoubleOrNull() == null &&
                (c.formatCode == "@" || EntryParser.parse(t, symbols = symbols) == null)
            if (text) seen.putIfAbsent(t.lowercase(), t)
            return true
        }
        var r = row - 1; var n = 0
        while (r >= 0 && n < ENTRY_SCAN && take(r)) { r--; n++ }
        r = row + 1; n = 0
        while (r < sheet.rowCount && n < ENTRY_SCAN && take(r)) { r++; n++ }
        return seen.values.toList()
    }

    /**
     * Excel's AutoComplete for [typed] going into ([row], [col]): the one entry of the column block around it that
     * begins with what was typed (case aside) and is longer — null when none or several do, or when [typed] is a
     * number or a formula. The caller shows the rest selected; [noteCompletion] lets the entry's own case win on Enter.
     */
    fun autoComplete(row: Int, col: Int, typed: String): String? {
        val t = typed.trimStart()
        if (t.isEmpty() || t.startsWith("=") || t.startsWith("'") || t.trim().toDoubleOrNull() != null) return null
        val candidates = columnEntries(row, col).filter { it.startsWith(t, ignoreCase = true) }
        return candidates.singleOrNull()?.takeIf { it.length > t.length }
    }

    /** The entry AutoComplete offered for the cell being typed in: entered as it stands in the column when accepted. */
    private var completion: String? = null
    fun noteCompletion(entry: String?) { completion = entry }

    /**
     * What the active cell's drop-down offers: a list rule's values, else Excel's Pick From Drop-down List — the text
     * entries of its column block, in alphabetical order. Null or empty: nothing to pick.
     */
    fun pickChoices(row: Int = selRow, col: Int = selCol): List<String>? =
        listChoices(row, col) ?: columnEntries(row, col).sortedWith(java.text.Collator.getInstance()).takeIf { it.isNotEmpty() }

    /** A value picked from the active cell's drop-down, entered as if typed (one undo step). */
    fun pickFromList(value: String) {
        commitEntry()
        typeCell(selRow, selCol, value)
        commitEntry()
    }

    /** The active cell's input message — title and text — shown while it is selected, as Excel shows it; null when it has none. */
    val inputMessage: Pair<String, String>?
        get() = validationAt(selRow, selCol)?.takeIf { it.hasInputMessage }?.let { it.inputTitle.trim() to it.inputMessage.trim() }

    /** Cells Circle Invalid Data marked on each sheet, each until its value passes or the circles are cleared. */
    private val circles = HashMap<Sheet, LinkedHashSet<Long>>()

    /**
     * Data → Circle Invalid Data: each cell on the sheet whose value breaks its rule — entered before
     * the rule, pasted, or worked out by a formula — is circled, at most 255 as in Excel. How many.
     */
    fun circleInvalidData(): Int {
        commitEntry()
        val found = LinkedHashSet<Long>()
        if (sheet.validations.isNotEmpty()) withListMemo {
            for (k in sheet.cells.keys.sorted()) {
                if (found.size >= MAX_CIRCLES) break
                if (isInvalid((k / 1000L).toInt(), (k % 1000L).toInt())) found.add(k)
            }
        }
        if (found.isEmpty()) circles.remove(sheet) else circles[sheet] = found
        bump()
        return found.size
    }

    /** Data → Clear Validation Circles. */
    fun clearValidationCircles() { if (circles.remove(sheet) != null) bump() }

    fun isCircled(row: Int, col: Int): Boolean = circles[sheet]?.contains(sheet.key(row, col)) == true
    /** The circled cells on the active sheet, as (row, column). */
    fun circledCells(): List<Pair<Int, Int>> = circles[sheet]?.map { (it / 1000L).toInt() to (it % 1000L).toInt() } ?: emptyList()
    val hasCircles: Boolean get() = circles[sheet]?.isNotEmpty() == true

    /** Whether the value in ([row], [col]) — as stored or as shown — breaks the cell's rule. */
    private fun isInvalid(row: Int, col: Int): Boolean {
        val rule = validationAt(row, col) ?: return false
        val cell = sheet.cell(row, col) ?: return false
        val value = if (cell.text.startsWith("=")) rawValue(row, col) else cell.text
        if (value.isBlank() || rule.validate(value, row, col, scope) == null) return false
        val shown = display(row, col)
        return shown == value || rule.validate(shown, row, col, scope) != null
    }

    /** A circled cell whose value now passes loses its circle, as in Excel. */
    private fun recheckCircles() {
        val set = circles[sheet] ?: return
        withListMemo { set.removeAll { k -> !isInvalid((k / 1000L).toInt(), (k % 1000L).toInt()) } }
        if (set.isEmpty()) circles.remove(sheet)
    }

    // --- named ranges (定义名称) ---------------------------------------------------
    /**
     * Names the selection. The name follows Excel's rules ([A1.isValidName]) and the reference is
     * absolute and quoted as Excel writes it (`'Sales Q3'!$A$1:$B$5`): Excel reads a relative
     * reference in a defined name relative to the cell that uses it.
     */
    fun defineName(name: String): Boolean {
        val clean = name.trim()
        if (!A1.isValidName(clean)) return false
        val ref = A1.absoluteRef(sheet.name, rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
        pushUndo(NamesDelta(workbook.names.toMap()))
        workbook.names[clean] = ref
        touchContent()
        return true
    }
    fun deleteName(name: String) { if (!workbook.names.containsKey(name)) return; pushUndo(NamesDelta(workbook.names.toMap())); workbook.names.remove(name); touchContent() }

    /** Why [name] cannot be given (in place of [old], when editing one), or null when it can. */
    fun nameIssue(old: String?, name: String): NameIssue? {
        val clean = name.trim()
        return when {
            !A1.isValidName(clean) -> NameIssue.INVALID
            workbook.names.keys.any { it.equals(clean, ignoreCase = true) && !it.equals(old, ignoreCase = true) } -> NameIssue.TAKEN
            else -> null
        }
    }

    /**
     * Name Manager's New / Edit: [name] standing for [refersTo] — a reference, a constant or a formula, its `=` optional,
     * kept as Excel keeps it (without the `=`) — in place of [old] when one is edited (renamed too), in one undo step;
     * false when the name breaks Excel's rules or another name has it, or [refersTo] is empty.
     */
    fun setName(old: String?, name: String, refersTo: String): Boolean {
        val clean = name.trim()
        val target = refersTo.trim().removePrefix("=").trim()
        if (nameIssue(old, clean) != null || target.isEmpty()) return false
        pushUndo(NamesDelta(workbook.names.toMap()))
        old?.let { o -> workbook.names.keys.firstOrNull { it.equals(o, ignoreCase = true) }?.let { workbook.names.remove(it) } }
        workbook.names[clean] = target
        touchContent()
        return true
    }

    /** The selection as a defined name would refer to it (`'Sales Q3'!$A$1:$B$5`). */
    fun selectionNameRef(): String = A1.absoluteRef(sheet.name, rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())

    /** What the defined name [name] comes to (Name Manager's Value): its value, a block's values, or its error. */
    fun nameValueText(name: String): String =
        FormulaEngine.evaluateText(sheet, name, workbook.sheets, workbook.names).orEmpty()

    /**
     * Create from Selection (Excel's): the labels in the selection's [top] row, [left] column, [bottom] row or [right] column
     * name the cells beside them — each column under (or over) its label, each row after (or before) its own —, labels
     * made into names as Excel makes them (spaces and other characters to `_`, a `_` before a leading digit); an existing
     * name of the same spelling is replaced. One undo step; how many names were made.
     */
    fun createNamesFromSelection(top: Boolean, left: Boolean, bottom: Boolean, right: Boolean): Int {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val rows = (if (top) r1 + 1 else r1)..(if (bottom) r2 - 1 else r2)
        val cols = (if (left) c1 + 1 else c1)..(if (right) c2 - 1 else c2)
        if (rows.isEmpty() || cols.isEmpty()) return 0
        val made = LinkedHashMap<String, String>()
        fun add(label: String, ra: Int, ca: Int, rb: Int, cb: Int) {
            val name = nameFromLabel(label) ?: return
            made[name] = A1.absoluteRef(sheet.name, ra, ca, rb, cb)
        }
        if (top) for (c in cols) add(display(r1, c), rows.first, c, rows.last, c)
        if (bottom) for (c in cols) add(display(r2, c), rows.first, c, rows.last, c)
        if (left) for (r in rows) add(display(r, c1), r, cols.first, r, cols.last)
        if (right) for (r in rows) add(display(r, c2), r, cols.first, r, cols.last)
        if (made.isEmpty()) return 0
        pushUndo(NamesDelta(workbook.names.toMap()))
        made.forEach { (n, ref) ->
            workbook.names.keys.firstOrNull { it.equals(n, ignoreCase = true) }?.let { workbook.names.remove(it) }
            workbook.names[n] = ref
        }
        touchContent()
        return made.size
    }

    /** Which edges of the selection Create from Selection would take labels from, as Excel guesses: a top row or left column of text. */
    fun guessNameLabels(): Pair<Boolean, Boolean> {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        fun text(r: Int, c: Int) = displayStandard(r, c).let { it.isNotBlank() && it.toDoubleOrNull() == null }
        // Mostly text along the top row (the corner aside), and down the left column below it.
        val top = r2 > r1 && (c1..c2).count { text(r1, it) } * 2 >= (c2 - c1 + 1)
        val left = c2 > c1 && (r1 + 1..r2).count { text(it, c1) } * 2 >= (r2 - r1)
        return top to left
    }

    /** Why a name cannot be given: it breaks Excel's rules for names, or another name has it. */
    enum class NameIssue { INVALID, TAKEN }

    // --- filter by keyword (筛选: 包含) -------------------------------------------
    /** The active column's text filter "contains [query]" (Excel's Text Filters → Contains), on the AutoFilter. */
    fun filterContains(query: String) {
        if (query.isEmpty()) return
        setColumnFilter(selCol, com.therealityteam.aeropages.model.ColumnFilter(
            condition = com.therealityteam.aeropages.model.FilterCondition(com.therealityteam.aeropages.model.FilterCondition.Op.CONTAINS, query)))
    }

    // --- CSV export ---------------------------------------------------------------
    /** The active sheet's evaluated values as CSV (quoted when needed, " doubled). */
    fun toCsv(): String = csvOf(sheet) { r, c -> displayStandard(r, c) }

    companion object {
        /**
         * A label made into a name as Excel's Create from Selection makes it: spaces and characters a name cannot have become
         * `_`, a leading digit gets a `_` before it; null for a blank label or one that still is no name (`A1`).
         */
        fun nameFromLabel(label: String): String? {
            var n = label.trim().map { if (it.isLetterOrDigit() || it == '_' || it == '.' || it == '\\') it else '_' }.joinToString("")
            if (n.isEmpty()) return null
            if (n[0].isDigit() || n[0] == '.') n = "_$n"
            if (!A1.isValidName(n)) n = "_$n"
            return n.takeIf { A1.isValidName(it) }
        }

        /** Auto-fit geometry: the grid's default column width, cell padding and base font size (dp / pt). */
        private const val FIT_CELL_W_DP = 88f
        private const val FIT_PAD_DP = 14f
        private const val FIT_BASE_PT = 12f
        /** The grid cell's horizontal padding, both sides. */
        private const val CELL_PAD_DP = 8f
        /** Excel's row limit, which the sheet can grow to. */
        const val MAX_ROWS = 1_048_576
        /** The most cells one Add Watch puts in the Watch Window. */
        const val MAX_WATCH_ADD = 500
        /** How far AutoComplete and the pick list look up and down a column. */
        private const val ENTRY_SCAN = 5_000
        /** Rows / columns of room made past the sheet's end at a time. */
        const val ROOM_ROWS = 100
        const val ROOM_COLS = 10
        /** The default row's height in points and column's width in characters of the default font, as Excel's dialogs give them. */
        const val ROW_PT = 15f
        const val COL_CHARS = 8.43f
        /** Resize limits, as factors of the default column width / row height. */
        const val MIN_COL_FACTOR = 0.25f
        const val MAX_COL_FACTOR = 6f
        const val MIN_ROW_FACTOR = 0.6f
        const val MAX_ROW_FACTOR = 8f
        /** Circle Invalid Data marks at most this many cells, as Excel does. */
        const val MAX_CIRCLES = 255
        /** A picture's size on insert at most (points), and the sizes a resize keeps it within. */
        const val MAX_PICTURE_W = 480f
        const val MAX_PICTURE_H = 360f
        const val MIN_PICTURE_PT = 8f
        const val MAX_PICTURE_PT = 4000f
        /** How far a duplicated picture sits from its original, each way. */
        const val DUPLICATE_OFFSET_PT = 12f
        /** Office's accent 1 and its darker shade: a new shape's fill and outline, as Excel draws one. */
        const val ACCENT = 0xFF4472C4
        const val ACCENT_DARK = 0xFF2F528F
        /** The grid's scale for pictures: a default row (26 dp) is Excel's 15 pt, so a default column (88 dp) is this many points. */
        const val GRID_DP_PER_PT = 26f / 15f
        const val GRID_COL_PT = 88f / GRID_DP_PER_PT
        /** The longest sheet name Excel allows. */
        const val MAX_SHEET_NAME = 31
        /** What a reference to a deleted sheet becomes. */
        const val DELETED_REF = "#REF!"
        /** A list's drop-down offers at most this many values from its source. */
        const val MAX_LIST_ITEMS = 1000

        /**
         * Width of [text] in dp at [sizePt] as the grid draws it: about 0.55 em per Latin character
         * and a full em per CJK / full-width one (they run twice as wide).
         */
        fun fitWidthDp(text: String, sizePt: Float): Float {
            var ems = 0f
            for (ch in text) ems += if (com.therealityteam.aeropages.model.PageMetrics.isCjk(ch)) 1f else 0.55f
            return ems * sizePt
        }

        /** CSV of one sheet, with [value] supplying each evaluated cell (shared with the file writers). */
        fun csvOf(sheet: Sheet, value: (row: Int, col: Int) -> String): String = buildString {
            val maxRow = sheet.cells.keys.maxOfOrNull { (it / 1000L).toInt() } ?: -1
            val maxCol = sheet.cells.keys.maxOfOrNull { (it % 1000L).toInt() } ?: -1
            for (r in 0..maxRow) {
                val fields = (0..maxCol).map { c ->
                    val v = value(r, c)
                    if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"${v.replace("\"", "\"\"")}\"" else v
                }
                append(fields.joinToString(","))
                if (r < maxRow) append('\n')
            }
        }
    }

    fun addSheet() {
        if (refuseIfStructureLocked()) return
        commitEntry()
        pushUndo(SheetSlotDelta(workbook.sheets.size, null))
        // Named as the language's Excel names a new sheet (Sheet2, Tabelle2, Feuil2…), the next number not taken.
        var n = workbook.sheets.size + 1
        while (workbook.sheets.any { it.name.equals(Texts.get(TextId.SHEET_N, n), ignoreCase = true) }) n++
        workbook.sheets.add(Sheet(name = Texts.get(TextId.SHEET_N, n)))
        sheetIndex = workbook.sheets.lastIndex; selRow = 0; selCol = 0; selRow2 = 0; selCol2 = 0; touchContent()
    }

    // --- the sheet tab's menu: rename, delete, move, copy, hide, tab colour ------

    /** Why the last sheet command did nothing (null when it did what was asked). */
    enum class SheetRefusal { BAD_NAME, NAME_TAKEN, LAST_VISIBLE, STRUCTURE_LOCKED }
    var sheetRefusal: SheetRefusal? = null
        private set

    /** The sheets the tabs show, by index. */
    val visibleSheets: List<Int> get() = workbook.sheets.indices.filter { !workbook.sheets[it].hidden }

    /** The hidden sheets Unhide offers (a very hidden one only a macro shows, as in Excel). */
    val unhidableSheets: List<Int> get() = workbook.sheets.indices.filter { workbook.sheets[it].state == com.therealityteam.aeropages.model.SheetState.HIDDEN }

    /**
     * Rename Sheet: sheet [index] called [name], and every formula, validation rule and defined
     * name in the workbook that names it follows. Refused when it is not a name Excel allows (empty,
     * longer than 31 characters, holding : \ / ? * [ ], or starting or ending with an apostrophe)
     * or another sheet has it.
     */
    fun renameSheet(index: Int, name: String): Boolean {
        sheetRefusal = null
        if (refuseIfStructureLocked()) return false
        val s = workbook.sheets.getOrNull(index) ?: return false
        val n = name.trim()
        if (n == s.name) return true
        if (!isValidSheetName(n)) { sheetRefusal = SheetRefusal.BAD_NAME; return false }
        if (workbook.sheets.any { it !== s && it.name.equals(n, ignoreCase = true) }) { sheetRefusal = SheetRefusal.NAME_TAKEN; return false }
        commitEntry()
        pushUndo(CompositeDelta(referencesEverywhere()))
        val old = s.name
        retargetSheetRefs { sheetName, _, _ -> if (sheetName.equals(old, ignoreCase = true)) A1.quoteSheet(n) + "!" else null }
        s.name = n
        touchContent()
        return true
    }

    /**
     * Delete Sheet: sheet [index] goes, and every reference to it elsewhere — in formulas,
     * validation rules and defined names — becomes #REF!, as in Excel. The last sheet showing stays.
     */
    fun deleteSheet(index: Int): Boolean {
        sheetRefusal = null
        if (refuseIfStructureLocked()) return false
        val s = workbook.sheets.getOrNull(index) ?: return false
        if (!s.hidden && visibleSheets.size <= 1) { sheetRefusal = SheetRefusal.LAST_VISIBLE; return false }
        commitEntry()
        // Undone: the sheet back in its slot first, then the references as they were.
        pushUndo(CompositeDelta(listOf<Delta<Workbook>>(SheetSlotDelta(index, s.copyOf())) + referencesEverywhere()))
        retargetSheetRefs { sheetName, _, _ -> if (sheetName.equals(s.name, ignoreCase = true)) DELETED_REF else null }
        workbook.sheets.removeAt(index)
        when {
            sheetIndex > index -> sheetIndex--
            sheetIndex == index -> sheetIndex = nearestVisible(index)
        }
        selRow = 0; selCol = 0; selRow2 = 0; selCol2 = 0
        touchContent()
        return true
    }

    /** Move sheet [index] one place among the tabs: left ([steps] < 0) or right, past any hidden one between. */
    fun moveSheet(index: Int, steps: Int): Boolean {
        sheetRefusal = null
        if (refuseIfStructureLocked()) return false
        val shown = visibleSheets
        val at = shown.indexOf(index).takeIf { it >= 0 } ?: return false
        val target = shown.getOrNull(at + steps) ?: return false
        commitEntry()
        pushUndo(SheetOrderDelta(target, index))
        workbook.sheets.add(target, workbook.sheets.removeAt(index))
        sheetIndex = target
        touchContent()
        return true
    }

    /**
     * Move or Copy → Create a copy: sheet [index] again, right after it, as "Name (2)"; its tables
     * get names of their own (and its formulas follow them), as Excel names a copy's tables.
     */
    fun duplicateSheet(index: Int): Boolean {
        sheetRefusal = null
        if (refuseIfStructureLocked()) return false
        val s = workbook.sheets.getOrNull(index) ?: return false
        commitEntry()
        val copy = s.copyOf()
        copy.name = copyName(s.name)
        copy.watched.clear()
        copy.state = com.therealityteam.aeropages.model.SheetState.VISIBLE
        val tableNames = com.therealityteam.aeropages.model.StructuredRefs.tableNames(workbook.sheets)
        val taken = (tableNames + workbook.names.keys).map { it.lowercase() }.toMutableSet()
        for (t in copy.tables) {
            val base = t.name.trimEnd { it.isDigit() }.ifEmpty { "Table" }
            var k = 2
            while ("$base$k".lowercase() in taken) k++
            val n = "$base$k"
            taken += n.lowercase()
            for (cell in copy.cells.values) if (cell.text.startsWith("=")) cell.text = com.therealityteam.aeropages.model.StructuredRefs.renameTable(cell.text, t.name, n, tableNames)
            t.name = n
        }
        pushUndo(SheetSlotDelta(index + 1, null))
        workbook.sheets.add(index + 1, copy)
        sheetIndex = index + 1; selRow = 0; selCol = 0; selRow2 = 0; selCol2 = 0
        touchContent()
        return true
    }

    /** Hide sheet [index] (its formulas still work); the last sheet showing stays. */
    fun hideSheet(index: Int): Boolean {
        sheetRefusal = null
        if (refuseIfStructureLocked()) return false
        val s = workbook.sheets.getOrNull(index) ?: return false
        if (s.hidden) return false
        if (visibleSheets.size <= 1) { sheetRefusal = SheetRefusal.LAST_VISIBLE; return false }
        commitEntry()
        pushUndo(SheetMetaDelta.capture(index, s))
        s.state = com.therealityteam.aeropages.model.SheetState.HIDDEN
        if (index == sheetIndex) { sheetIndex = nearestVisible(index); selRow = 0; selCol = 0; selRow2 = 0; selCol2 = 0 }
        touchContent()
        return true
    }

    /** Unhide: sheet [index] shows again, and becomes the active one. */
    fun unhideSheet(index: Int): Boolean {
        sheetRefusal = null
        if (refuseIfStructureLocked()) return false
        val s = workbook.sheets.getOrNull(index) ?: return false
        if (!s.hidden) return false
        commitEntry()
        pushUndo(SheetMetaDelta.capture(index, s))
        s.state = com.therealityteam.aeropages.model.SheetState.VISIBLE
        sheetIndex = index; selRow = 0; selCol = 0; selRow2 = 0; selCol2 = 0
        touchContent()
        return true
    }

    /** Tab Color: sheet [index]'s tab in [color], or none. */
    fun setTabColor(index: Int, color: Long?) {
        if (refuseIfStructureLocked()) return
        val s = workbook.sheets.getOrNull(index) ?: return
        if (s.tabColor == color) return
        pushUndo(SheetMetaDelta.capture(index, s))
        s.tabColor = color
        touchContent()
    }

    /** The visible sheet nearest [index] (after it, else before), once [index] is gone or hidden. */
    private fun nearestVisible(index: Int): Int {
        val shown = visibleSheets
        return shown.firstOrNull { it >= index } ?: shown.lastOrNull() ?: 0
    }

    /** "Sales" → "Sales (2)", the first such name free, kept within Excel's 31 characters. */
    private fun copyName(name: String): String {
        val base = Regex("""^(.*?)(?: \(\d+\))?$""").find(name)?.groupValues?.get(1)?.ifEmpty { name } ?: name
        var k = 2
        while (true) {
            val suffix = " ($k)"
            val n = base.take(MAX_SHEET_NAME - suffix.length) + suffix
            if (workbook.sheets.none { it.name.equals(n, ignoreCase = true) }) return n
            k++
        }
    }

    /** Every sheet's metadata (names, validation rules), its formulas and the defined names: what a sheet's name reaches. */
    private fun referencesEverywhere(): List<Delta<Workbook>> =
        listOf<Delta<Workbook>>(NamesDelta(workbook.names.toMap())) + workbook.sheets.mapIndexed { i, s -> SheetMetaDelta.capture(i, s) } + formulasEverywhere()

    /**
     * Each sheet-qualified reference in every formula, validation rule and defined name — `Data!B2`,
     * `'Q1 Sales'!A1:C9`, `Data!A:A` — handed to [replace] with its sheet's name, the whole
     * reference and the reference after the `!`; a non-null answer replaces the sheet prefix
     * (or, given [DELETED_REF], the whole reference). Text in quotes is left alone.
     */
    private fun retargetSheetRefs(replace: (sheet: String, whole: String, ref: String) -> String?) {
        fun rewritten(t: String): String = com.therealityteam.aeropages.model.StructuredRefs.outsideLiterals(t) { code ->
            val out = StringBuilder()
            var last = 0
            for (m in SHEET_PREFIX.findAll(code)) {
                if (m.range.first < last) continue
                val token = m.groupValues[1]
                val name = if (token.startsWith("'")) token.substring(1, token.length - 1).replace("''", "'") else token
                val ref = REF_AFTER_PREFIX.matchAt(code, m.range.last + 1)?.value ?: ""
                val r = replace(name, m.value + ref, ref) ?: continue
                out.append(code, last, m.range.first)
                if (r == DELETED_REF) { out.append(DELETED_REF); last = m.range.last + 1 + ref.length }
                else { out.append(r); last = m.range.last + 1 }
            }
            out.append(code, last, code.length).toString()
        }
        for (s in workbook.sheets) {
            for (cell in s.cells.values) if (cell.text.startsWith("=")) cell.text = rewritten(cell.text)
            if (s.validations.any { it.formula1 != null || it.formula2 != null }) s.validations.replaceAll { dv ->
                dv.copy(formula1 = dv.formula1?.let { rewritten(it) }, formula2 = dv.formula2?.let { rewritten(it) })
            }
        }
        for ((k, v) in workbook.names.entries.toList()) workbook.names[k] = rewritten(v)
    }

    /** Whether Excel allows [n] as a sheet's name. */
    private fun isValidSheetName(n: String): Boolean =
        n.isNotEmpty() && n.length <= MAX_SHEET_NAME && n.none { it in ":\\/?*[]" } && !n.startsWith("'") && !n.endsWith("'") &&
            !n.equals("History", ignoreCase = true)

    fun addRows(n: Int) { pushUndo(sheetMeta()); sheet.rowCount += n; touchContent() }
    fun addCols(n: Int) { pushUndo(sheetMeta()); sheet.colCount = (sheet.colCount + n).coerceAtMost(Sheet.MAX_COLS); touchContent() }

    // --- insert / delete rows & columns (shift the sparse cell map) -------------
    private fun remap(transform: (r: Int, c: Int) -> Pair<Int, Int>?) {
        val src = HashMap(sheet.cells)
        sheet.cells.clear()
        src.forEach { (k, v) ->
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            transform(r, c)?.let { (nr, nc) -> if (nr >= 0 && nc in 0 until Sheet.MAX_COLS) sheet.cells[sheet.key(nr, nc)] = v }
        }
    }
    private fun shiftInsertRow(row: Int) { remap { r, c -> (if (r >= row) r + 1 else r) to c }; shiftRegions(Axis.ROW, row, +1); sheet.rowCount += 1 }
    private fun shiftDeleteRow(row: Int) { remap { r, c -> if (r == row) null else (if (r > row) r - 1 else r) to c }; shiftRegions(Axis.ROW, row, -1); if (sheet.rowCount > 1) sheet.rowCount -= 1 }
    private fun shiftInsertCol(col: Int) { remap { r, c -> r to (if (c >= col) c + 1 else c) }; shiftRegions(Axis.COL, col, +1); sheet.colCount = (sheet.colCount + 1).coerceAtMost(Sheet.MAX_COLS) }
    private fun shiftDeleteCol(col: Int) { remap { r, c -> if (c == col) null else r to (if (c > col) c - 1 else c) }; shiftRegions(Axis.COL, col, -1); if (sheet.colCount > 1) sheet.colCount -= 1 }

    /**
     * Keep merges, conditional formats, validations and row/column sizes aligned with the
     * cells after a row/column is inserted (delta +1) or deleted (delta -1) at [index].
     * A region that lived entirely on the deleted line disappears; one that straddles it shrinks.
     */
    private fun shiftRegions(axis: Axis, index: Int, delta: Int) {
        val filtered = sheet.tables.firstOrNull { filterIsTable(it) }
        fun shift(lo: Int, hi: Int): Pair<Int, Int>? = when {
            delta > 0 -> (if (lo >= index) lo + 1 else lo) to (if (hi >= index) hi + 1 else hi)
            lo == index && hi == index -> null
            else -> (if (lo > index) lo - 1 else lo) to (if (hi >= index) hi - 1 else hi)
        }
        fun <T> rewrite(list: MutableList<T>, lo: (T) -> Int, hi: (T) -> Int, build: (T, Int, Int) -> T) {
            val out = ArrayList<T>(list.size)
            for (item in list) {
                val s = shift(lo(item), hi(item)) ?: continue
                if (s.second < s.first) continue
                out.add(build(item, s.first, s.second))
            }
            list.clear(); list.addAll(out)
        }
        val row = axis == Axis.ROW
        // Watches move with their cells; one on a deleted line goes (Excel's shows #REF! there).
        rewrite(sheet.watched, { if (row) it.row else it.col }, { if (row) it.row else it.col }) { w, lo, _ -> if (row) w.copy(row = lo) else w.copy(col = lo) }
        rewrite(sheet.merges, { if (row) it.r1 else it.c1 }, { if (row) it.r2 else it.c2 }) { m, lo, hi ->
            if (row) MergeRegion(lo, m.c1, hi, m.c2) else MergeRegion(m.r1, lo, m.r2, hi)
        }
        sheet.merges.removeAll { it.r1 == it.r2 && it.c1 == it.c2 } // a merge that shrank to one cell is no merge
        rewrite(sheet.condFormats, { if (row) it.r1 else it.c1 }, { if (row) it.r2 else it.c2 }) { cf, lo, hi ->
            if (row) cf.over(lo, cf.c1, hi, cf.c2)
            else cf.over(cf.r1, lo, cf.r2, hi)
        }
        rewrite(sheet.validations, { if (row) it.r1 else it.c1 }, { if (row) it.r2 else it.c2 }) { dv, lo, hi ->
            if (row) dv.moved(lo, dv.c1, hi, dv.c2) else dv.moved(dv.r1, lo, dv.r2, hi)
        }
        for (chart in sheet.charts) {
            val (r1, c1, r2, c2) = com.therealityteam.aeropages.model.A1.parseRange(chart.sourceRef ?: continue) ?: continue
            val s = shift(if (row) r1 else c1, if (row) r2 else c2)
            chart.sourceRef = if (s == null || s.second < s.first) null
                else if (row) com.therealityteam.aeropages.model.A1.rangeRef(s.first, c1, s.second, c2)
                else com.therealityteam.aeropages.model.A1.rangeRef(r1, s.first, r2, s.second)
        }
        // Manual page breaks move with their rows (columns); one at a deleted line's place stays where it now stands.
        sheet.print.let { pr ->
            val moved = (if (row) pr.rowBreaks else pr.colBreaks).mapNotNull { b -> shift(b, b)?.takeIf { it.second >= it.first }?.first ?: if (delta < 0 && b == index) b else null }
                .filter { it > 0 }.distinct().sorted()
            if (moved != (if (row) pr.rowBreaks else pr.colBreaks)) sheet.print = if (row) pr.copy(rowBreaks = moved) else pr.copy(colBreaks = moved)
        }
        sheet.printArea?.let { pa ->
            val s = shift(if (row) pa.r1 else pa.c1, if (row) pa.r2 else pa.c2)
            sheet.printArea = if (s == null || s.second < s.first) null
                else if (row) com.therealityteam.aeropages.model.PrintArea(s.first, pa.c1, s.second, pa.c2)
                else com.therealityteam.aeropages.model.PrintArea(pa.r1, s.first, pa.r2, s.second)
        }
        val sizes = if (row) sheet.rowHeights else sheet.colWidths
        val moved = HashMap<Int, Float>()
        sizes.forEach { (k, v) -> shift(k, k)?.let { moved[it.first] = v } }
        sizes.clear(); sizes.putAll(moved)
        // The AutoFilter's range moves (a column's criteria with it); it goes when its last line does.
        sheet.autoFilter?.let { af ->
            val s = shift(if (row) af.r1 else af.c1, if (row) af.r2 else af.c2)
            when {
                s == null || s.second < s.first -> sheet.autoFilter = null
                row -> { af.r1 = s.first; af.r2 = s.second }
                else -> {
                    af.c1 = s.first; af.c2 = s.second
                    val moved = af.columns.entries.mapNotNull { (c, f) -> shift(c, c)?.let { it.first to f } }
                    af.columns.clear(); moved.forEach { (c, f) -> af.columns[c] = f }
                }
            }
        }
        // Sparklines move with their cell, their data with its cells; one whose cell or data goes, goes.
        if (sheet.sparklines.isNotEmpty()) {
            val keep = ArrayList<com.therealityteam.aeropages.model.Sparkline>()
            for (sp in sheet.sparklines) {
                val at = shift(if (row) sp.row else sp.col, if (row) sp.row else sp.col) ?: continue
                val data = shift(if (row) sp.r1 else sp.c1, if (row) sp.r2 else sp.c2)?.takeIf { it.second >= it.first } ?: continue
                if (row) { sp.row = at.first; sp.r1 = data.first; sp.r2 = data.second } else { sp.col = at.first; sp.c1 = data.first; sp.c2 = data.second }
                keep.add(sp)
            }
            sheet.sparklines.clear(); sheet.sparklines.addAll(keep)
        }
        // Pictures and shapes move with their anchor cell; one on a deleted line stays where it was, anchored to the line that slides up.
        for (img in sheet.images + sheet.shapes) {
            val at = if (row) img.row else img.col
            val moved = if (delta > 0) (if (at >= index) at + 1 else at) else (if (at > index) at - 1 else at)
            if (row) img.row = moved else img.col = moved
        }
        shiftTables(row, index, delta, ::shift)
        // A table's filter keeps covering the table as it grows or shrinks.
        if (filtered != null && filtered in sheet.tables) sheet.autoFilter?.let { af -> af.r1 = filtered.r1; af.c1 = filtered.c1; af.r2 = filtered.dataR2; af.c2 = filtered.c2 }
        if (row) { // hidden (filtered) rows and outline groups move with their data
            val movedRows = sheet.hiddenRows.mapNotNull { r -> shift(r, r)?.first }
            sheet.hiddenRows.clear(); sheet.hiddenRows.addAll(movedRows)
            val movedManual = sheet.manualHiddenRows.mapNotNull { r -> shift(r, r)?.first }
            sheet.manualHiddenRows.clear(); sheet.manualHiddenRows.addAll(movedManual)
            rewrite(sheet.rowGroups, { it.r1 }, { it.r2 }) { g, lo, hi -> com.therealityteam.aeropages.model.RowGroup(lo, hi, g.collapsed) }
        } else { // hidden columns and column groups move with theirs
            val movedCols = sheet.hiddenCols.mapNotNull { c -> shift(c, c)?.first }
            sheet.hiddenCols.clear(); sheet.hiddenCols.addAll(movedCols)
            rewrite(sheet.colGroups, { it.r1 }, { it.r2 }) { g, lo, hi -> com.therealityteam.aeropages.model.RowGroup(lo, hi, g.collapsed) }
        }
    }

    /**
     * Tables grow and shrink with the lines inserted into or deleted from them. One whose header
     * row goes, or its last data row, stops being a table (its cells stay); a total row deleted
     * leaves the table without one. A column inserted inside a table gets a name, as in Excel.
     */
    private fun shiftTables(row: Boolean, index: Int, delta: Int, shift: (Int, Int) -> Pair<Int, Int>?) {
        if (sheet.tables.isEmpty()) return
        val keep = ArrayList<com.therealityteam.aeropages.model.SheetTable>()
        for (t in sheet.tables) {
            if (delta < 0 && row && t.headerRow && index == t.r1) continue
            val s = shift(if (row) t.r1 else t.c1, if (row) t.r2 else t.c2) ?: continue
            if (s.second < s.first) continue
            if (row) {
                if (delta < 0 && t.totalsRow && index == t.r2) t.totalsRow = false
                val grew = delta > 0 && index in t.r1 + 1..t.r2
                t.r1 = s.first; t.r2 = s.second
                if (t.dataR2 < t.dataR1 && !grew) continue
            } else {
                val inside = index - t.c1
                if (!t.headerRow && t.columns.isNotEmpty()) {
                    if (delta > 0 && inside in 1 until t.width) t.columns.add(inside, "")
                    else if (delta < 0 && inside in t.columns.indices) t.columns.removeAt(inside)
                }
                t.c1 = s.first; t.c2 = s.second
                if (delta > 0 && inside in 1 until t.c2 - t.c1 + 1 && t.headerRow && sheet.cell(t.r1, index)?.text.isNullOrBlank()) {
                    sheet.ensure(t.r1, index).text = t.columnNames(sheet)[inside]
                }
            }
            keep.add(t)
        }
        sheet.tables.clear(); sheet.tables.addAll(keep)
    }

    fun insertRowAt(row: Int = selRow) { if (refuseIfSheetProtected(Allow.INSERT_ROWS)) return; pushUndo(wholeSheet()); rewriteFormulas(Axis.ROW, row, +1); shiftInsertRow(row); touchContent() }
    fun deleteRowAt(row: Int = selRow) { if (refuseIfSheetProtected(Allow.DELETE_ROWS)) return; pushUndo(wholeSheet()); rewriteFormulas(Axis.ROW, row, -1); shiftDeleteRow(row); touchContent() }
    fun insertColAt(col: Int = selCol) { if (refuseIfSheetProtected(Allow.INSERT_COLUMNS)) return; pushUndo(wholeSheet()); rewriteFormulas(Axis.COL, col, +1); shiftInsertCol(col); touchContent() }
    fun deleteColAt(col: Int = selCol) { if (refuseIfSheetProtected(Allow.DELETE_COLUMNS)) return; pushUndo(wholeSheet()); rewriteFormulas(Axis.COL, col, -1); shiftDeleteCol(col); touchContent() }

    // --- formula reference rewriting on structural changes (Excel behavior) ------
    private enum class Axis { ROW, COL }

    /**
     * A sheet name before a `!`: quoted (`'Q1 Sales'!`, `''` an apostrophe) or plain — letters of any
     * script, digits and underscores, as [com.therealityteam.aeropages.model.A1.quoteSheet] leaves
     * them (`Data!`, `数据!`, `シート1!`).
     */
    private val SHEET_PREFIX = Regex("""(?<![\p{L}\p{N}_.'$])('(?:[^']|'')+'|[\p{L}_][\p{L}\p{N}_.]*)!""")

    /** What a sheet prefix qualifies: a cell, a range, whole columns or whole rows. */
    private val REF_AFTER_PREFIX = Regex("""\$?[A-Za-z]{1,3}\$?\d{1,7}(?:\s*:\s*\$?[A-Za-z]{1,3}\$?\d{1,7})?(?![A-Za-z0-9_(])|\$?[A-Za-z]{1,3}:\$?[A-Za-z]{1,3}(?![A-Za-z0-9_(])|\$?\d{1,7}:\$?\d{1,7}(?![0-9])""")

    /**
     * A1-style ref (optionally sheet-qualified, optionally a range). Lookarounds keep
     * function names (LOG10() — followed by '(') and identifier tails out of matches.
     */
    private val a1RefRx = Regex(
        """(?<![A-Za-z0-9_$])((?:'[^']+'|[A-Za-z_一-鿿][A-Za-z0-9_一-鿿]*)!)?""" +
            """(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})(?:\s*:\s*(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7}))?(?![A-Za-z0-9_(])"""
    )

    private fun colIndexOf(letters: String): Int = A1.colIndex(letters)

    /**
     * After inserting (delta=+1) or deleting (delta=-1) the row/col at [index] of the
     * ACTIVE sheet, shift matching refs in every formula of every sheet: unqualified
     * refs in the active sheet, plus refs qualified with the active sheet's name
     * anywhere. A deleted single-cell ref becomes #REF!; a range shrinks (Excel-style)
     * and only collapses to #REF! when emptied.
     */
    private fun rewriteFormulas(axis: Axis, index: Int, delta: Int) {
        val targetName = sheet.name
        /** [t] with its references to the changed sheet moved: ones naming it, and — when [own] (on that sheet) — ones naming no sheet. */
        fun rewritten(t: String, own: Boolean): String = com.therealityteam.aeropages.model.StructuredRefs.outsideLiterals(t) { code ->
            a1RefRx.replace(code) { m ->
                val prefix = m.groupValues[1]
                val refSheet = prefix.removeSuffix("!").trim('\'')
                val applies = if (prefix.isEmpty()) own else refSheet.equals(targetName, true)
                if (!applies) return@replace m.value
                rewriteRef(m, axis, index, delta)
            }
        }
        workbook.sheets.forEach { sh ->
            val own = sh === sheet
            sh.cells.values.forEach { cell ->
                val t = cell.text
                if (!t.startsWith("=")) return@forEach
                cell.text = rewritten(t, own)
            }
            // Validation rules' formulas (bounds, list sources, Custom rules) follow the cells as formulas do;
            // so do conditional formats' (their ranges move too, and a rule's formula counts from its range's corner).
            if (sh.validations.any { it.formula1 != null || it.formula2 != null }) sh.validations.replaceAll { dv ->
                dv.copy(formula1 = dv.formula1?.let { rewritten(it, own) }, formula2 = dv.formula2?.let { rewritten(it, own) })
            }
            if (sh.condFormats.any { it.formula1 != null || it.formula2 != null }) sh.condFormats.replaceAll { cf ->
                cf.copy(formula1 = cf.formula1?.let { rewritten(it, own) }, formula2 = cf.formula2?.let { rewritten(it, own) })
            }
        }
        // Defined names name their sheet: they follow its cells.
        for ((k, v) in workbook.names.entries.toList()) workbook.names[k] = rewritten(v, own = false)
    }

    private fun rewriteRef(m: MatchResult, axis: Axis, index: Int, delta: Int): String {
        val g = m.groupValues
        val isRange = g[7].isNotEmpty()
        var c1 = colIndexOf(g[3]); var r1 = g[5].toInt() - 1
        var c2 = if (isRange) colIndexOf(g[7]) else c1
        var r2 = if (isRange) g[9].toInt() - 1 else r1
        fun coord1() = if (axis == Axis.ROW) r1 else c1
        fun coord2() = if (axis == Axis.ROW) r2 else c2
        fun set1(v: Int) = if (axis == Axis.ROW) r1 = v else c1 = v
        fun set2(v: Int) = if (axis == Axis.ROW) r2 = v else c2 = v
        if (delta > 0) {
            if (coord1() >= index) set1(coord1() + 1)
            if (coord2() >= index) set2(coord2() + 1)
        } else {
            if (!isRange) {
                if (coord1() == index) return "#REF!"
                if (coord1() > index) set1(coord1() - 1)
                set2(coord1()); if (axis == Axis.ROW) c2 = c1 else r2 = r1
            } else {
                if (coord1() > index) set1(coord1() - 1)
                if (coord2() >= index) set2(coord2() - 1)
                if (coord2() < coord1()) return "#REF!"
            }
        }
        val head = "${g[1]}${g[2]}${Workbook.colLabel(c1)}${g[4]}${r1 + 1}"
        return if (isRange) "$head:${g[6]}${Workbook.colLabel(c2)}${g[8]}${r2 + 1}" else head
    }

    // --- borders & wrap ---------------------------------------------------------

    /** Excel's Borders menu. */
    enum class BorderPreset {
        BOTTOM, TOP, LEFT, RIGHT, NONE, ALL, OUTSIDE, THICK_BOX,
        BOTTOM_DOUBLE, THICK_BOTTOM, TOP_BOTTOM, TOP_THICK_BOTTOM, TOP_DOUBLE_BOTTOM,
    }

    /** The line the Borders menu draws with (Excel's Line Style and Line Color); thick and double presets keep its colour. */
    var borderPen by mutableStateOf(com.therealityteam.aeropages.model.BorderLine.THIN)

    /**
     * Excel's Borders menu on the selection, one undo step: a side or the outside edge in
     * [borderPen], every line, none, a thick box, or a bottom edge thick or double (with or without
     * a top) as totals take. Refused on a protected sheet.
     */
    fun applyBorders(preset: BorderPreset) {
        if (spread { applyBorders(preset) }) return
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        val pen = borderPen
        val thick = pen.copy(style = com.therealityteam.aeropages.model.LineStyle.THICK)
        val double = pen.copy(style = com.therealityteam.aeropages.model.LineStyle.DOUBLE)
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        pushUndo(selectionCells())
        for (r in r1..r2) for (c in c1..c2) {
            if (preset == BorderPreset.NONE) {
                sheet.cell(r, c)?.apply { borderTop = null; borderBottom = null; borderLeft = null; borderRight = null }
                continue
            }
            val top = r == r1; val bottom = r == r2; val left = c == c1; val right = c == c2
            fun at(edge: Boolean, line: com.therealityteam.aeropages.model.BorderLine) = if (edge) line else null
            // The lines this preset draws on this cell's top, bottom, left and right (null: that side stays).
            val lines = when (preset) {
                BorderPreset.ALL -> listOf(pen, pen, pen, pen)
                BorderPreset.OUTSIDE -> listOf(at(top, pen), at(bottom, pen), at(left, pen), at(right, pen))
                BorderPreset.THICK_BOX -> listOf(at(top, thick), at(bottom, thick), at(left, thick), at(right, thick))
                BorderPreset.TOP -> listOf(at(top, pen), null, null, null)
                BorderPreset.BOTTOM -> listOf(null, at(bottom, pen), null, null)
                BorderPreset.LEFT -> listOf(null, null, at(left, pen), null)
                BorderPreset.RIGHT -> listOf(null, null, null, at(right, pen))
                BorderPreset.THICK_BOTTOM -> listOf(null, at(bottom, thick), null, null)
                BorderPreset.BOTTOM_DOUBLE -> listOf(null, at(bottom, double), null, null)
                BorderPreset.TOP_BOTTOM -> listOf(at(top, pen), at(bottom, pen), null, null)
                BorderPreset.TOP_THICK_BOTTOM -> listOf(at(top, pen), at(bottom, thick), null, null)
                BorderPreset.TOP_DOUBLE_BOTTOM -> listOf(at(top, pen), at(bottom, double), null, null)
                BorderPreset.NONE -> listOf(null, null, null, null)
            }
            if (lines.all { it == null }) continue
            val cell = sheet.ensure(r, c)
            lines[0]?.let { cell.borderTop = it }
            lines[1]?.let { cell.borderBottom = it }
            lines[2]?.let { cell.borderLeft = it }
            lines[3]?.let { cell.borderRight = it }
        }
        touchContent()
    }

    // --- cell styles and table looks -------------------------------------------------

    /** Home → Cell Styles: [style] on every selected cell (the aspects it includes), one undo step; refused on a protected sheet. */
    fun applyCellStyle(style: com.therealityteam.aeropages.model.CellStyle) {
        if (spread { applyCellStyle(style) }) return
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        commitEntry()
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) {
            if (style == com.therealityteam.aeropages.model.CellStyle.NORMAL) sheet.cell(r, c)?.let { style.applyTo(it) }
            else style.applyTo(sheet.ensure(r, c))
        }
        touchContent()
    }

    /**
     * Excel's current region around ([row], [col]): the block of filled cells reached through
     * neighbours (diagonals too), bounded by empty rows and columns — r1, c1, r2, c2; null when
     * the cell stands alone and empty.
     */
    fun currentRegion(row: Int, col: Int): IntArray? {
        fun filled(r: Int, c: Int) = r in 0 until sheet.rowCount && c in 0 until sheet.colCount && sheet.cell(r, c)?.text?.isNotEmpty() == true
        var r1 = row; var r2 = row; var c1 = col; var c2 = col
        var grown = true
        while (grown) {
            grown = false
            if (r1 > 0 && (c1 - 1..c2 + 1).any { filled(r1 - 1, it) }) { r1--; grown = true }
            if (r2 < sheet.rowCount - 1 && (c1 - 1..c2 + 1).any { filled(r2 + 1, it) }) { r2++; grown = true }
            if (c1 > 0 && (r1 - 1..r2 + 1).any { filled(it, c1 - 1) }) { c1--; grown = true }
            if (c2 < sheet.colCount - 1 && (r1 - 1..r2 + 1).any { filled(it, c2 + 1) }) { c2++; grown = true }
        }
        if (r1 == r2 && c1 == c2 && !filled(row, col)) return null
        return intArrayOf(r1, c1, r2, c2)
    }

    // --- tables (Insert → Table, Format as Table, Table Design) --------------------

    /** The table the active cell is in. */
    val selectedTable: com.therealityteam.aeropages.model.SheetTable? get() = sheet.tableAt(selRow, selCol)

    /** Why a table command did nothing. */
    enum class TableRefusal { NO_DATA, OVERLAPS_TABLE, HAS_MERGES, BAD_NAME, NAME_TAKEN, FILTER_ELSEWHERE }

    /** Why the last table command did nothing (null when it did what was asked). */
    var tableRefusal: TableRefusal? = null
        private set

    /** A look from the Format as Table gallery by its old two-switch description. */
    /**
     * Excel's Create Table guess: the range a new table would take — the selection, else the data around the active
     * cell — as `$A$1:$D$5`, and whether its first row looks like headers ([guessHeader]); null with no data there.
     */
    fun tableGuess(): Pair<String, Boolean>? {
        val box = (if (selRow == selRow2 && selCol == selCol2) currentRegion(selRow, selCol)
            else intArrayOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())) ?: return null
        fun cell(r: Int, c: Int) = "$" + com.therealityteam.aeropages.model.Workbook.colLabel(c) + "$" + (r + 1)
        return "${cell(box[0], box[1])}:${cell(box[2], box[3])}" to guessHeader(box[0], box[1], box[2], box[3])
    }

    /**
     * Excel's Create Table: [range] (`=$A$1:$D$5`, `A1:D5`) becomes a table in [style], its first row the headers when
     * [headers]. False for a range that does not read, or as [formatAsTable] refuses.
     */
    fun createTable(range: String, style: String, headers: Boolean): Boolean {
        val box = com.therealityteam.aeropages.model.A1.parseRange(range.trim().removePrefix("=")) ?: run { tableRefusal = TableRefusal.NO_DATA; return false }
        select(box[0], box[1]); extendSelection(box[2] - box[0], box[3] - box[1])
        return formatAsTable(style, headers)
    }

    fun formatAsTable(style: com.therealityteam.aeropages.model.TableStyle, headers: Boolean = true, banded: Boolean = true): Boolean =
        formatAsTable(style.xlsxName, headers, banded)

    /**
     * Home → Format as Table, Insert → Table: the selection — or, from one cell, the data around it
     * (Excel's current region) — becomes a table named Table1, Table2 … in [style] (one of Excel's,
     * TableStyleMedium2 …), which becomes the selection. With [headers] its first row names the
     * columns: a formula there gives way to what it shows, a blank or repeated name becomes Column1
     * or Sales2 as in Excel; without, the columns are Column1 … . [banded] stripes the data rows.
     * Inside a table the look restyles it. Refused over another table or merged cells, or with no
     * data ([tableRefusal] says why). One undo step.
     */
    fun formatAsTable(style: String, headers: Boolean = true, banded: Boolean = true): Boolean {
        tableRefusal = null
        if (refuseIfSheetProtected()) return false
        commitEntry()
        selectedTable?.let { t ->
            pushUndo(TablesDelta.capture(sheetIndex, sheet))
            t.style = style; t.bandedRows = banded
            touchContent()
            return true
        }
        val box = if (selRow == selRow2 && selCol == selCol2) currentRegion(selRow, selCol)
            else intArrayOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
        if (box == null) { tableRefusal = TableRefusal.NO_DATA; return false }
        val r1 = box[0]; val c1 = box[1]; val c2 = box[3]
        // A header needs a data row under it, blank if need be.
        val r2 = if (headers && box[2] == r1) r1 + 1 else box[2]
        if (sheet.tables.any { it.intersects(r1, c1, r2, c2) }) { tableRefusal = TableRefusal.OVERLAPS_TABLE; return false }
        if (sheet.merges.any { it.intersects(r1, c1, r2, c2) }) { tableRefusal = TableRefusal.HAS_MERGES; return false }
        pushUndo(CompositeDelta(listOf(TablesDelta.capture(sheetIndex, sheet), sheetMeta(), cells(r1, c1, r1, c2))))
        val t = com.therealityteam.aeropages.model.SheetTable(newTableName(), r1, c1, r2, c2, headerRow = headers, style = style, bandedRows = banded, filterButton = headers)
        if (headers) {
            for (c in c1..c2) sheet.cell(r1, c)?.let { if (it.text.startsWith("=")) { it.text = display(r1, c); it.runs = null } }
            t.columnNames(sheet).forEachIndexed { i, name ->
                val cell = sheet.ensure(r1, c1 + i)
                if (cell.text != name) { cell.text = name; cell.runs = null }
            }
        } else t.columns.addAll((1..t.width).map { "Column$it" })
        if (r2 >= sheet.rowCount) sheet.rowCount = r2 + 1
        sheet.tables.add(t)
        // Its filter buttons are the sheet's filter when the sheet has none.
        if (headers && sheet.autoFilter == null) sheet.autoFilter = filterRangeOf(t)
        selRow = r1; selCol = c1; selRow2 = r2; selCol2 = c2
        touchContent()
        return true
    }

    /** [t]'s look, one of Excel's built-in styles (null: none). One undo step. */
    fun setTableStyle(t: com.therealityteam.aeropages.model.SheetTable, style: String?) {
        if (refuseIfSheetProtected()) return
        commitEntry()
        pushUndo(TablesDelta.capture(sheetIndex, sheet))
        t.style = style
        touchContent()
    }

    /** Table1, Table2 …: the first name no table or defined name has. */
    private fun newTableName(): String {
        var n = 1
        while (workbook.table("Table$n") != null || workbook.names.keys.any { it.equals("Table$n", ignoreCase = true) }) n++
        return "Table$n"
    }

    /** The AutoFilter a table's buttons stand for: its header row and data, never its total row. */
    private fun filterRangeOf(t: com.therealityteam.aeropages.model.SheetTable) =
        com.therealityteam.aeropages.model.AutoFilter(t.r1, t.c1, t.dataR2, t.c2)

    /** Whether the sheet's AutoFilter is [t]'s. */
    fun filterIsTable(t: com.therealityteam.aeropages.model.SheetTable): Boolean =
        sheet.autoFilter?.let { it.r1 == t.r1 && it.c1 == t.c1 && it.r2 == t.dataR2 && it.c2 == t.c2 } == true

    /** The table whose filter button is on ([row], [col]), if one is. */
    fun tableButtonAt(row: Int, col: Int): com.therealityteam.aeropages.model.SheetTable? =
        sheet.tableAt(row, col)?.takeIf { it.filterButton && it.headerRow && row == it.r1 }

    /**
     * Before [t]'s filter button opens its menu: the sheet's AutoFilter becomes the table's (the
     * file is the same either way, so this is no edit of its own). Refused while the sheet's
     * filter is somewhere else.
     */
    fun adoptTableFilter(t: com.therealityteam.aeropages.model.SheetTable): Boolean {
        tableRefusal = null
        if (filterIsTable(t)) return true
        if (sheet.autoFilter != null) { tableRefusal = TableRefusal.FILTER_ELSEWHERE; return false }
        sheet.autoFilter = filterRangeOf(t)
        bump()
        return true
    }

    /** Table Design's Table Style Options. */
    enum class TableOption { HEADER_ROW, TOTAL_ROW, BANDED_ROWS, BANDED_COLUMNS, FIRST_COLUMN, LAST_COLUMN, FILTER_BUTTON }

    fun tableOption(t: com.therealityteam.aeropages.model.SheetTable, option: TableOption): Boolean = when (option) {
        TableOption.HEADER_ROW -> t.headerRow
        TableOption.TOTAL_ROW -> t.totalsRow
        TableOption.BANDED_ROWS -> t.bandedRows
        TableOption.BANDED_COLUMNS -> t.bandedCols
        TableOption.FIRST_COLUMN -> t.firstColumn
        TableOption.LAST_COLUMN -> t.lastColumn
        TableOption.FILTER_BUTTON -> t.filterButton
    }

    /**
     * Turns one of [t]'s Table Style Options on or off, as Excel's Table Design tab does. The
     * header row going keeps its names and blanks the row; coming back it takes the row above
     * (a new row when that one is in use). The total row takes the row below the same way, with
     * [totalLabel] under the first column and the last one summed (counted when it holds no
     * numbers); going, it is blanked. The filter button going takes the table's filter with it.
     * One undo step.
     */
    fun setTableOption(t: com.therealityteam.aeropages.model.SheetTable, option: TableOption, on: Boolean, totalLabel: String = Texts.get(TextId.TABLE_TOTAL)): Boolean {
        tableRefusal = null
        if (refuseIfSheetProtected()) return false
        commitEntry()
        if (tableOption(t, option) == on) return true
        when (option) {
            TableOption.HEADER_ROW -> setHeaderRow(t, on)
            TableOption.TOTAL_ROW -> setTotalsRow(t, on, totalLabel)
            TableOption.FILTER_BUTTON -> {
                if (on && !t.headerRow) return false
                pushUndo(both(TablesDelta.capture(sheetIndex, sheet), sheetMeta()))
                if (!on && filterIsTable(t)) { sheet.autoFilter = null; sheet.hiddenRows.clear() }
                t.filterButton = on
            }
            else -> {
                pushUndo(TablesDelta.capture(sheetIndex, sheet))
                when (option) {
                    TableOption.BANDED_ROWS -> t.bandedRows = on
                    TableOption.BANDED_COLUMNS -> t.bandedCols = on
                    TableOption.FIRST_COLUMN -> t.firstColumn = on
                    else -> t.lastColumn = on
                }
            }
        }
        touchContent()
        return true
    }

    private fun lineFree(row: Int, c1: Int, c2: Int, except: com.therealityteam.aeropages.model.SheetTable): Boolean =
        row >= 0 && (c1..c2).all { sheet.cell(row, it)?.text.isNullOrEmpty() } &&
            sheet.tables.none { it !== except && it.intersects(row, c1, row, c2) } && sheet.merges.none { it.intersects(row, c1, row, c2) }

    private fun setHeaderRow(t: com.therealityteam.aeropages.model.SheetTable, on: Boolean) {
        pushUndo(wholeSheet())
        val synced = filterIsTable(t)
        if (!on) {
            val names = t.columnNames(sheet)
            t.columns.clear(); t.columns.addAll(names)
            for (c in t.c1..t.c2) sheet.cell(t.r1, c)?.let { it.text = ""; it.runs = null }
            if (synced) { sheet.autoFilter = null; sheet.hiddenRows.clear() }
            t.r1 += 1; t.headerRow = false; t.filterButton = false
            return
        }
        if (!lineFree(t.r1 - 1, t.c1, t.c2, t)) { rewriteFormulas(Axis.ROW, t.r1, +1); shiftInsertRow(t.r1) }
        val names = t.columnNames(sheet)
        t.r1 -= 1; t.headerRow = true; t.filterButton = true
        names.forEachIndexed { i, name -> sheet.ensure(t.r1, t.c1 + i).apply { text = name; runs = null } }
        t.columns.clear()
        if (sheet.autoFilter == null) sheet.autoFilter = filterRangeOf(t)
    }

    private fun setTotalsRow(t: com.therealityteam.aeropages.model.SheetTable, on: Boolean, label: String) {
        pushUndo(wholeSheet())
        val synced = filterIsTable(t)
        if (on) {
            val below = t.r2 + 1
            if (!lineFree(below, t.c1, t.c2, t)) { rewriteFormulas(Axis.ROW, below, +1); shiftInsertRow(below) }
            t.r2 = below; t.totalsRow = true
            if (below >= sheet.rowCount) sheet.rowCount = below + 1
            val names = t.columnNames(sheet)
            if (t.width > 1) sheet.ensure(below, t.c1).apply { text = label; runs = null }
            val numeric = (t.dataR1..t.dataR2).any { r -> numericValue(r, t.c2) != null }
            sheet.ensure(below, t.c2).apply { text = totalFormula(if (numeric) TotalFunction.SUM else TotalFunction.COUNT, names.last()); runs = null }
        } else {
            for (c in t.c1..t.c2) sheet.cell(t.r2, c)?.let { it.text = ""; it.runs = null }
            t.r2 -= 1; t.totalsRow = false
        }
        if (synced) sheet.autoFilter?.let { it.r2 = t.dataR2 }
    }

    /** What a total row cell can show, as Excel's drop-down lists them, with SUBTOTAL's code (ignoring filtered-out rows). */
    enum class TotalFunction(val code: Int) { AVERAGE(101), COUNT(103), COUNT_NUMBERS(102), MAX(104), MIN(105), SUM(109), STD_DEV(107), VAR(110) }

    private fun totalFormula(fn: TotalFunction, column: String): String =
        "=SUBTOTAL(${fn.code}," + com.therealityteam.aeropages.model.StructuredRefs.render(null, emptySet(), column, null, ui = true) + ")"

    /** The function the total row shows under [col] of [t], when it is one of the list's. */
    fun totalFunctionAt(t: com.therealityteam.aeropages.model.SheetTable, col: Int): TotalFunction? {
        if (!t.totalsRow) return null
        val text = sheet.cell(t.r2, col)?.text ?: return null
        val code = Regex("""^=SUBTOTAL\((\d+),""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return TotalFunction.entries.firstOrNull { it.code == code || it.code - 100 == code }
    }

    /** The total row's cell under [col] of [t] shows [fn] of its column (null: nothing). One undo step. */
    fun setTotalFunction(t: com.therealityteam.aeropages.model.SheetTable, col: Int, fn: TotalFunction?) {
        if (!t.totalsRow || col !in t.c1..t.c2 || refuseIfSheetProtected()) return
        commitEntry()
        pushUndo(cells(t.r2, col, t.r2, col))
        val cell = sheet.ensure(t.r2, col)
        cell.text = if (fn == null) "" else totalFormula(fn, t.columnNames(sheet)[col - t.c1])
        cell.runs = null
        touchContent()
    }

    /**
     * Table Design → Table Name: [name] for [t], and in every formula that names it. Refused when
     * it is not a name Excel allows or another table or defined name has it.
     */
    fun renameTable(t: com.therealityteam.aeropages.model.SheetTable, name: String): Boolean {
        tableRefusal = null
        val n = name.trim()
        if (n == t.name) return true
        if (!A1.isValidName(n)) { tableRefusal = TableRefusal.BAD_NAME; return false }
        if (workbook.table(n)?.second.let { it != null && it !== t } || workbook.names.keys.any { it.equals(n, ignoreCase = true) }) {
            tableRefusal = TableRefusal.NAME_TAKEN; return false
        }
        pushUndo(CompositeDelta(listOf(TablesDelta.capture(sheetIndex, sheet)) + formulasEverywhere()))
        val names = com.therealityteam.aeropages.model.StructuredRefs.tableNames(workbook.sheets)
        for (s in workbook.sheets) for (cell in s.cells.values) {
            if (cell.text.startsWith("=")) cell.text = com.therealityteam.aeropages.model.StructuredRefs.renameTable(cell.text, t.name, n, names)
        }
        t.name = n
        touchContent()
        return true
    }

    /** The formula cells of every sheet, as undo holds them. */
    private fun formulasEverywhere(): List<Delta<Workbook>> = workbook.sheets.mapIndexedNotNull { i, s -> CellKeysDelta.formulas(i, s) }

    /**
     * Table Design → Convert to Range: [t] stops being a table. Its look stays, as the cells' own
     * formatting; formulas that named it name its cells instead (absolute, as Excel writes them);
     * its filter goes, showing every row. One undo step.
     */
    fun convertTableToRange(t: com.therealityteam.aeropages.model.SheetTable): Boolean {
        if (refuseIfSheetProtected()) return false
        commitEntry()
        pushUndo(wholeSheet())
        val names = com.therealityteam.aeropages.model.StructuredRefs.tableNames(workbook.sheets)
        for (s in workbook.sheets) for ((k, cell) in s.cells) {
            if (!cell.text.startsWith("=")) continue
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            cell.text = com.therealityteam.aeropages.model.StructuredRefs.rewrite(cell.text, names) { ref ->
                val owner = com.therealityteam.aeropages.model.StructuredRefs.tableOf(ref, s, r, c, workbook.sheets)
                if (owner?.second !== t) return@rewrite null
                val target = com.therealityteam.aeropages.model.StructuredRefs.resolve(ref, s, r, c, workbook.sheets) ?: return@rewrite "#REF!"
                val thisRow = com.therealityteam.aeropages.model.StructuredRefs.Item.THIS_ROW in ref.items
                fun cellRef(row: Int, col: Int) = "$" + Workbook.colLabel(col) + (if (thisRow) "" else "$") + (row + 1)
                val a1 = cellRef(target.r1, target.c1) + (if (target.r1 == target.r2 && target.c1 == target.c2) "" else ":" + cellRef(target.r2, target.c2))
                if (target.sheet === s) a1 else A1.quoteSheet(target.sheet.name) + "!" + a1
            }
        }
        for (r in t.r1..t.r2) for (c in t.c1..t.c2) {
            val look = com.therealityteam.aeropages.model.TableLooks.lookAt(t, r, c) ?: break
            sheet.cells[sheet.key(r, c)] = com.therealityteam.aeropages.model.TableLooks.applied(sheet.cell(r, c), look)
        }
        if (filterIsTable(t)) { sheet.autoFilter = null; sheet.hiddenRows.clear() }
        sheet.tables.remove(t)
        touchContent()
        return true
    }

    /**
     * After an entry is read: a table's header typed over renames its column in every formula
     * (a blank or repeated one becomes Column1 or Sales2); a value just below a table or just right
     * of it takes the table over that line (Excel's AutoExpansion — its own undo step).
     */
    private fun tableEntry(p: PendingEntry) {
        if (sheet.tables.isEmpty()) return
        val t = sheet.tableAt(p.row, p.col)
        if (t != null) {
            if (t.headerRow && p.row == t.r1) renamedHeader(t, p)
            else if (p.row in t.dataR1..t.dataR2) calculatedColumn(t, p.row, p.col)
            return
        }
        if (sheet.cell(p.row, p.col)?.text.isNullOrEmpty()) return
        for (x in sheet.tables) {
            val below = !x.totalsRow && p.row == x.r2 + 1 && p.col in x.c1..x.c2
            val beside = p.col == x.c2 + 1 && p.row in x.r1..x.r2
            if (!below && !beside) continue
            val nr2 = if (below) x.r2 + 1 else x.r2
            val nc2 = if (beside) x.c2 + 1 else x.c2
            if (sheet.tables.any { it !== x && it.intersects(x.r1, x.c1, nr2, nc2) } || sheet.merges.any { it.intersects(x.r1, x.c1, nr2, nc2) }) return
            val synced = filterIsTable(x)
            val header = beside && x.headerRow && p.row != x.r1 && sheet.cell(x.r1, p.col)?.text.isNullOrBlank()
            // A new row takes the table's calculated columns' formulas.
            val calculated = if (below) (x.c1..x.c2).mapNotNull { c -> calculatedFormula(x, c)?.let { c to it } } else emptyList()
            if (calculated.isNotEmpty()) pushUndo(CompositeDelta(listOf(TablesDelta.capture(sheetIndex, sheet), sheetMeta(), cells(nr2, x.c1, nr2, x.c2))))
            else pushUndo(CompositeDelta(listOfNotNull(TablesDelta.capture(sheetIndex, sheet), sheetMeta(), if (header) cells(x.r1, p.col, x.r1, p.col) else null)))
            val firstData = x.dataR1
            x.r2 = nr2; x.c2 = nc2
            if (beside && !x.headerRow && x.columns.isNotEmpty()) x.columns.add("Column${x.width}")
            if (header) sheet.ensure(x.r1, p.col).apply { text = x.columnNames(sheet)[p.col - x.c1]; runs = null }
            for ((c, f) in calculated) if (sheet.cell(nr2, c)?.text.isNullOrEmpty()) sheet.ensure(nr2, c).apply { text = shiftFormulaRefs(f, nr2 - firstData, 0); runs = null }
            if (synced) sheet.autoFilter?.let { it.r2 = x.dataR2; it.c2 = x.c2 }
            touchContent()
            return
        }
    }

    /**
     * The formula of [t]'s column [col] when it is a calculated column — every data row holding the
     * same formula, moved to its row (as the first data row's) — else null.
     */
    private fun calculatedFormula(t: com.therealityteam.aeropages.model.SheetTable, col: Int): String? {
        val first = sheet.cell(t.dataR1, col)?.text?.takeIf { it.startsWith("=") } ?: return null
        for (r in t.dataR1 + 1..t.dataR2) if (sheet.cell(r, col)?.text != shiftFormulaRefs(first, r - t.dataR1, 0)) return null
        return first
    }

    /**
     * A formula typed into a table column that is otherwise empty fills the column (Excel's
     * calculated column), each row's copy moved to its row — an undo step of its own.
     */
    private fun calculatedColumn(t: com.therealityteam.aeropages.model.SheetTable, row: Int, col: Int) {
        val text = sheet.cell(row, col)?.text ?: return
        if (!text.startsWith("=") || t.dataR2 <= t.dataR1) return
        val others = (t.dataR1..t.dataR2).filter { it != row }
        if (others.any { !sheet.cell(it, col)?.text.isNullOrEmpty() }) return
        pushUndo(cells(t.dataR1, col, t.dataR2, col))
        for (r in others) sheet.ensure(r, col).apply { this.text = shiftFormulaRefs(text, r - row, 0); runs = null }
        touchContent()
    }

    private fun renamedHeader(t: com.therealityteam.aeropages.model.SheetTable, p: PendingEntry) {
        val key = sheet.key(p.row, p.col)
        val i = p.col - t.c1
        val old = t.columnNames(sheet, key to (p.before?.text ?: ""))[i]
        val new = t.columnNames(sheet)[i]
        val cell = sheet.cell(p.row, p.col)
        val fixHeader = cell == null || cell.text.startsWith("=").not() && cell.text != new
        // Formulas naming the old column, in every sheet.
        val changed = HashMap<Int, HashMap<Long, String>>()
        if (old != new) workbook.sheets.forEachIndexed { si, s ->
            for ((k, c) in s.cells) {
                if (!c.text.startsWith("=") || c.text.indexOf('[') < 0) continue
                val text = com.therealityteam.aeropages.model.StructuredRefs.renameColumn(c.text, s, (k / 1000L).toInt(), (k % 1000L).toInt(), t, old, new)
                if (text != c.text) changed.getOrPut(si) { HashMap() }[k] = text
            }
        }
        if (changed.isEmpty() && !fixHeader) return
        val parts = ArrayList<Delta<Workbook>>()
        changed.forEach { (si, m) -> parts.add(CellKeysDelta.capture(si, workbook.sheets[si], m.keys)) }
        if (fixHeader) parts.add(CellKeysDelta.capture(sheetIndex, sheet, listOf(key)))
        pushUndo(CompositeDelta(parts))
        changed.forEach { (si, m) -> m.forEach { (k, text) -> workbook.sheets[si].cells[k]?.text = text } }
        if (fixHeader) sheet.ensure(p.row, p.col).apply { text = new; runs = null }
        touchContent()
    }

    /** Borders on all four sides of every cell in the selection. */
    fun applyBordersAll() = applyBorders(BorderPreset.ALL)
    /** Borders only on the outer edge of the selected rectangle. */
    fun applyBordersOutside() = applyBorders(BorderPreset.OUTSIDE)
    fun clearBorders() = applyBorders(BorderPreset.NONE)
    fun toggleWrap() = setWrap(!(sheet.cell(selRow, selCol)?.wrap ?: false))

    private fun setWrap(on: Boolean) {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        if (spread { setWrap(on) }) return
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) sheet.ensure(r, c).wrap = on
        touchContent()
    }

    fun setVAlign(v: com.therealityteam.aeropages.model.VAlign) {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        if (spread { setVAlign(v) }) return
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) sheet.ensure(r, c).vAlign = v
        touchContent()
    }

    /**
     * Format Cells' OK, one undo step: [edit] on every selected cell — told whether the cell lies on the selection's
     * top, bottom, left and right edge, for the borders — and the selection merged ([merge] true) or its merges undone
     * (false). Refused on a protected sheet, as Excel greys Format Cells out there.
     */
    fun applyCellFormat(merge: Boolean? = null, edit: (cell: com.therealityteam.aeropages.model.Cell, top: Boolean, bottom: Boolean, left: Boolean, right: Boolean) -> Unit) {
        commitEntry()
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        pushUndo(if (merge != null) both(sheetMeta(), selectionCells()) else selectionCells())
        for (r in r1..r2) for (c in c1..c2) edit(sheet.ensure(r, c), r == r1, r == r2, c == c1, c == c2)
        when (merge) {
            true -> if (r1 != r2 || c1 != c2) {
                sheet.merges.removeAll { it.intersects(r1, c1, r2, c2) }
                sheet.merges.add(com.therealityteam.aeropages.model.MergeRegion(r1, c1, r2, c2))
            }
            false -> sheet.merges.removeAll { it.intersects(r1, c1, r2, c2) }
            null -> {}
        }
        touchContent()
    }

    // --- merge cells (合并后居中) ------------------------------------------------
    val isMergedSelection: Boolean get() = sheet.mergeAt(selRow, selCol) != null
    /** Merge the selected rectangle; keep the top-left content, center it. Replaces overlapping merges. */
    fun mergeCells(center: Boolean = true) {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (r1 == r2 && c1 == c2) return
        pushUndo(both(sheetMeta(), selectionCells()))
        sheet.merges.removeAll { it.intersects(r1, c1, r2, c2) }
        sheet.merges.add(com.therealityteam.aeropages.model.MergeRegion(r1, c1, r2, c2))
        if (center) sheet.ensure(r1, c1).align = com.therealityteam.aeropages.model.TextAlign.CENTER
        touchContent()
    }
    /** Remove any merge intersecting the selection. */
    fun unmergeCells() {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (sheet.merges.none { it.intersects(r1, c1, r2, c2) }) return
        pushUndo(sheetMeta())
        sheet.merges.removeAll { it.intersects(r1, c1, r2, c2) }
        touchContent()
    }
    /** Toggle: if the active cell is merged, unmerge; else merge the selection (centered). */
    fun toggleMergeCenter() { if (isMergedSelection) unmergeCells() else mergeCells(center = true) }

    /** Excel's Merge Across: every selected row merged on its own, one undo step; nothing for a single column. */
    fun mergeAcross() {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (c1 == c2 || refuseIfSheetProtected()) return
        pushUndo(both(sheetMeta(), selectionCells()))
        sheet.merges.removeAll { it.intersects(r1, c1, r2, c2) }
        for (r in r1..r2) sheet.merges.add(com.therealityteam.aeropages.model.MergeRegion(r, c1, r, c2))
        touchContent()
    }

    /** Whether any merge touches the selection (Unmerge Cells has something to do). */
    val selectionHasMerge: Boolean get() = sheet.merges.any { it.intersects(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax()) }

    // --- orientation and indent (Excel's Alignment group) -------------------------
    /** The active cell's text turned this many degrees counterclockwise. */
    val activeRotation: Int get() = sheet.cell(selRow, selCol)?.rotation ?: 0
    /** Whether the active cell's text is stacked (Vertical Text). */
    val activeStacked: Boolean get() = sheet.cell(selRow, selCol)?.stacked ?: false
    /** The active cell's indent levels. */
    val activeIndent: Int get() = sheet.cell(selRow, selCol)?.indent ?: 0

    /**
     * Orientation: the selection's text turned [degrees] counterclockwise (−90…90) or [stacked], one undo step — or back
     * to horizontal when the active cell already reads that way, as Excel's Orientation menu toggles. Refused on a
     * protected sheet.
     */
    fun setOrientation(degrees: Int, stacked: Boolean = false) {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        val deg = if (stacked) 0 else degrees.coerceIn(-90, 90)
        val again = activeRotation == deg && activeStacked == stacked
        orient(if (again) 0 else deg, !again && stacked)
    }

    private fun orient(toDeg: Int, toStack: Boolean) {
        if (spread { orient(toDeg, toStack) }) return
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) {
            val cell = if (toDeg == 0 && !toStack) sheet.cell(r, c) ?: continue else sheet.ensure(r, c)
            cell.rotation = toDeg; cell.stacked = toStack
        }
        touchContent()
    }

    /**
     * Increase / Decrease Indent, one undo step: every selected cell [delta] levels further from (or nearer) its edge,
     * 0…[com.therealityteam.aeropages.model.Cell.MAX_INDENT]. As in Excel, indenting General or centred text aligns it
     * left (right-aligned text keeps its side). Refused on a protected sheet.
     */
    fun stepIndent(delta: Int) {
        if (delta == 0 || refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        if (spread { stepIndent(delta) }) return
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val any = (r1..r2).any { r -> (c1..c2).any { c ->
            val cell = sheet.cell(r, c)
            if (delta > 0) (cell?.indent ?: 0) < com.therealityteam.aeropages.model.Cell.MAX_INDENT else (cell?.indent ?: 0) > 0
        } }
        if (!any) return
        pushUndo(selectionCells())
        for (r in r1..r2) for (c in c1..c2) {
            val cell = if (delta > 0) sheet.ensure(r, c) else sheet.cell(r, c) ?: continue
            cell.indent = (cell.indent + delta).coerceIn(0, com.therealityteam.aeropages.model.Cell.MAX_INDENT)
            if (delta > 0 && cell.align != com.therealityteam.aeropages.model.TextAlign.RIGHT) cell.align = com.therealityteam.aeropages.model.TextAlign.LEFT
        }
        touchContent()
    }

    // --- hyperlinks -------------------------------------------------------------
    /** Where the active cell links to — its hyperlink, or where its `=HYPERLINK(…)` formula goes — or null. */
    val activeLink: String? get() = sheet.cell(selRow, selCol)?.link ?: hyperlinkOf(selRow, selCol)

    /** Where the cell at ([row], [col]) goes when its formula is `=HYPERLINK(location, …)`: its location worked out; else null. */
    fun hyperlinkOf(row: Int, col: Int): String? {
        val text = sheet.cell(row, col)?.text ?: return null
        val location = com.therealityteam.aeropages.model.CellLinks.hyperlinkLocation(text) ?: return null
        return FormulaEngine.evaluateText(sheet, location, workbook.sheets, workbook.names, row to col)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Attach [url] to the active cell — an address, or a place in this workbook (`#Sheet2!A1`, `#Total`) —
     * null / blank removes the link; an empty cell shows the address (or the place) as its text.
     */
    fun setLink(url: String?) {
        if (isLocked(selRow, selCol) && refuseIfSheetProtected(Allow.INSERT_HYPERLINKS)) return
        val clean = url?.trim()?.ifBlank { null }
        pushUndo(activeCell())
        val cell = sheet.ensure(selRow, selCol)
        cell.link = clean
        if (clean != null && cell.text.isEmpty()) cell.text = com.therealityteam.aeropages.model.CellLinks.place(clean) ?: clean
        touchContent()
    }

    /**
     * Follows a link to a place in this workbook — a reference (`#Sheet2!A1`, `#'Q3 Sales'!B2:C5`, `#A1`
     * on this sheet) or a defined name (`#Total`) —: its sheet, its cells selected, as Excel goes there.
     * False for a link elsewhere, or to a place that is not there (a sheet gone, a hidden one, no such name).
     */
    fun followLink(link: String): Boolean {
        val place = com.therealityteam.aeropages.model.CellLinks.place(link) ?: return false
        val named = workbook.names.keys.any { it.equals(place, ignoreCase = true) }
        if (!named && A1.parseTarget(place) == null) return false
        return goTo(place) == GoTo.SELECTED
    }

    // --- page setup (页面布局) -----------------------------------------------------
    fun setLandscape(v: Boolean) { if (sheet.landscape == v) return; pushUndo(sheetMeta()); sheet.landscape = v; touchContent() }
    fun setPaper(p: com.therealityteam.aeropages.model.Paper) { if (sheet.paper == p) return; pushUndo(sheetMeta()); sheet.paper = p; touchContent() }
    /** Print area = the selection rectangle (a single cell clears it, like Excel's "Clear Print Area"). */
    fun setPrintAreaFromSelection() {
        pushUndo(sheetMeta())
        sheet.printArea = if (rangeRowMin() == rangeRowMax() && rangeColMin() == rangeColMax()) null
            else com.therealityteam.aeropages.model.PrintArea(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())
        touchContent()
    }
    fun clearPrintArea() { if (sheet.printArea == null) return; pushUndo(sheetMeta()); sheet.printArea = null; touchContent() }

    // --- page breaks (Page Layout ▸ Breaks, View ▸ Page Break Preview) -----------------------------

    /**
     * Excel's Insert Page Break: a break above the active cell's row and left of its column — only the one across in
     * column A (or with whole rows selected), only the one down in row 1 (or with whole columns). False when there is none
     * to add. One undo step; the sheet then shows its page breaks.
     */
    fun insertPageBreak(): Boolean {
        val pr = sheet.print
        val r = selRow; val c = selCol
        val rows = if (r > 0 && !wholeColumnsSelected) (pr.rowBreaks + r).distinct().sorted() else pr.rowBreaks
        val cols = if (c > 0 && !wholeRowsSelected) (pr.colBreaks + c).distinct().sorted() else pr.colBreaks
        if (rows == pr.rowBreaks && cols == pr.colBreaks) return false
        pushUndo(sheetMeta())
        sheet.print = pr.copy(rowBreaks = rows, colBreaks = cols)
        sheet.showPageBreaks = true
        touchContent()
        return true
    }

    /** Whether a manual break stands above the active cell's row or left of its column (Remove Page Break has one to remove). */
    val canRemovePageBreak: Boolean get() = selRow in sheet.print.rowBreaks || selCol in sheet.print.colBreaks

    /** Excel's Remove Page Break: the manual breaks above the active cell's row and left of its column. One undo step. */
    fun removePageBreak(): Boolean {
        if (!canRemovePageBreak) return false
        val pr = sheet.print
        pushUndo(sheetMeta())
        sheet.print = pr.copy(rowBreaks = pr.rowBreaks - selRow, colBreaks = pr.colBreaks - selCol)
        touchContent()
        return true
    }

    /** Excel's Reset All Page Breaks: every manual break goes. One undo step. */
    fun resetPageBreaks(): Boolean {
        val pr = sheet.print
        if (pr.rowBreaks.isEmpty() && pr.colBreaks.isEmpty()) return false
        pushUndo(sheetMeta())
        sheet.print = pr.copy(rowBreaks = emptyList(), colBreaks = emptyList())
        touchContent()
        return true
    }

    /** Excel's Page Break Preview (or back to Normal): a view setting, not an edit; leaving it, Normal shows the breaks. */
    fun setPageBreakPreview(on: Boolean) {
        if (sheet.pageBreakPreview == on) return
        sheet.pageBreakPreview = on
        sheet.showPageBreaks = true
        bump()
    }

    /** A page break or print-range edge being dragged in Page Break Preview: the line at [from] (above a row, or left of a column when ![rows]) shown at [to]. */
    data class EdgeDrag(val rows: Boolean, val from: Int, val to: Int)
    /** The line being dragged in Page Break Preview, which the grid draws where it would go; null when none is. */
    var edgeDrag by mutableStateOf<EdgeDrag?>(null)

    /**
     * Excel's dragging in Page Break Preview: the line above row (or left of column) [from] moved to [to]. The print
     * range's edges set the print area (the other edge staying, a row or column at least); a break — manual, or an
     * automatic one, which becomes manual — moves, and goes when dragged out of the range or onto its edge. Dragged past
     * what a page holds, the scale shrinks until it does, as Excel shrinks it; with the sheet fit to pages, dragging a
     * break turns that into the scale it prints at. One undo step; false when nothing changes.
     */
    fun movePageEdge(rows: Boolean, from: Int, to: Int): Boolean {
        val layout = pageLayout() ?: return false
        if (from == to) return false
        val range = if (rows) layout.rows else layout.cols
        val lo = range.first; val hi = range.last + 1
        val pr = sheet.print
        if (from == lo || from == hi) {
            val first = if (from == lo) to.coerceIn(0, hi - 1) else lo
            val last = if (from == hi) (to - 1).coerceAtLeast(lo) else range.last
            if (first == range.first && last == range.last) return false
            pushUndo(sheetMeta())
            sheet.printArea = if (rows) com.therealityteam.aeropages.model.PrintArea(first, layout.cols.first, last, layout.cols.last)
                else com.therealityteam.aeropages.model.PrintArea(layout.rows.first, first, layout.rows.last, last)
            touchContent()
            return true
        }
        val inside = to > lo && to < hi
        val list = if (rows) pr.rowBreaks else pr.colBreaks
        val moved = (list - from).let { if (inside) (it + to).distinct().sorted() else it }
        var next = if (rows) pr.copy(rowBreaks = moved) else pr.copy(colBreaks = moved)
        if (next.fits) next = next.copy(fitWidth = null, fitHeight = null, scale = (layout.scale * 100f).toInt().coerceIn(10, 400))
        if (next == pr) return false
        if (inside) next = next.copy(scale = scaleHolding(next, rows, to))
        pushUndo(sheetMeta())
        sheet.print = next
        touchContent()
        return true
    }

    /** The largest scale, at most [pr]'s, at which the page ending at the break [to] holds all its rows (or columns) — Excel's shrink when a break is dragged past a page. */
    private fun scaleHolding(pr: com.therealityteam.aeropages.model.SheetPrint, rows: Boolean, to: Int): Int {
        val breaks = if (rows) pr.rowBreaks else pr.colBreaks
        fun holds(scale: Int): Boolean {
            val l = com.therealityteam.aeropages.model.SheetPages.layout(sheet, pr.copy(scale = scale)) ?: return true
            val start = breaks.filter { it < to }.maxOrNull() ?: (if (rows) l.rows.first else l.cols.first)
            return (if (rows) l.rowStarts else l.colStarts).none { it in (start + 1) until to }
        }
        if (holds(pr.scale)) return pr.scale
        var low = 10; var high = pr.scale
        if (!holds(low)) return low
        while (high - low > 1) { val mid = (low + high) / 2; if (holds(mid)) low = mid else high = mid }
        return low
    }

    /** How the sheet prints (its pages' rows and columns), cached until its content or print settings change; null: empty. */
    fun pageLayout(): com.therealityteam.aeropages.model.SheetPages.Layout? {
        val key = Triple(System.identityHashCode(sheet), contentRev, sheet.print.hashCode() * 31 + sheet.paper.hashCode() + (if (sheet.landscape) 1 else 0))
        pageLayoutCache?.let { (k, l) -> if (k == key) return l }
        return com.therealityteam.aeropages.model.SheetPages.layout(sheet).also { pageLayoutCache = key to it }
    }
    private var pageLayoutCache: Pair<Triple<Int, Int, Int>, com.therealityteam.aeropages.model.SheetPages.Layout?>? = null
    fun toggleHeadings() { pushUndo(sheetMeta()); sheet.showHeadings = !sheet.showHeadings; touchContent() }

    /**
     * Excel's Page Setup and Page Layout tab: the sheet's print setup made [change]'s, its orientation [landscape] and its
     * [paper] — one step back, nothing when nothing changes.
     */
    fun setPrint(landscape: Boolean = sheet.landscape, paper: com.therealityteam.aeropages.model.Paper = sheet.paper,
                 change: (com.therealityteam.aeropages.model.SheetPrint) -> com.therealityteam.aeropages.model.SheetPrint = { it }) {
        val next = change(sheet.print)
        if (next == sheet.print && landscape == sheet.landscape && paper == sheet.paper) return
        pushUndo(sheetMeta()); sheet.print = next; sheet.landscape = landscape; sheet.paper = paper; touchContent()
    }

    /** Print Titles: the selected rows repeated at the top of every printed page. */
    fun setPrintTitleRowsFromSelection() = setPrint { it.copy(titleRows = rangeRowMin()..rangeRowMax()) }

    /** Print Titles: the selected columns repeated at the left of every printed page. */
    fun setPrintTitleColsFromSelection() = setPrint { it.copy(titleCols = rangeColMin()..rangeColMax()) }

    // --- cell comments / notes --------------------------------------------------
    /** Name stamped on new notes (Excel's user name); blank = the localised "Me". */
    var reviewer: String = ""
    val reviewerName: String get() = reviewer.ifBlank { com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.COMMENT_AUTHOR_ME) }

    /** The active cell's note: a new one the reviewer's, now; an edited one keeps who wrote it (Excel's). */
    fun setComment(text: String) {
        pushUndo(activeCell())
        val cell = sheet.ensure(selRow, selCol)
        val note = text.ifBlank { null }
        if (note == null) { cell.comment = null; cell.commentAuthor = null; cell.commentDate = 0L; cell.commentShown = false }
        else {
            if (cell.comment == null) { cell.commentAuthor = reviewerName; cell.commentDate = System.currentTimeMillis() }
            cell.comment = note
        }
        touchContent()
    }
    /** Review → Previous / Next Comment: the cell with a comment before (after) the active one, row by row; false when there is none. */
    fun goToComment(back: Boolean = false): Boolean {
        val here = sheet.key(selRow, selCol)
        val keys = sheet.cells.entries.filter { it.value.comment != null }.map { it.key }.sorted()
        val k = (if (back) keys.lastOrNull { it < here } else keys.firstOrNull { it > here }) ?: return false
        select((k / 1000L).toInt(), (k % 1000L).toInt())
        return true
    }
    /** Whether the active cell has a comment (Delete Comment has something to delete). */
    val activeHasComment: Boolean get() = sheet.cell(selRow, selCol)?.comment != null
    fun clearComment() { sheet.cell(selRow, selCol)?.let { if (it.comment != null) { pushUndo(activeCell()); it.comment = null; it.commentAuthor = null; it.commentDate = 0L; it.commentShown = false; touchContent() } } }

    /** Show All Notes (Excel's Review ▸ Notes): every note shown beside its cell; a view setting, not saved. */
    var showAllNotes by mutableStateOf(false)
        private set
    fun toggleShowAllNotes() { showAllNotes = !showAllNotes; revision++ }
    /** The active cell's note is kept shown (Excel's Show/Hide Note). */
    val activeNoteShown: Boolean get() = sheet.cell(selRow, selCol)?.let { it.comment != null && it.commentShown } == true
    /** Show/Hide Note on the active cell: one undo step, saved with the note; false when the cell has none. */
    fun toggleNoteShown(): Boolean {
        val cell = sheet.cell(selRow, selCol)?.takeIf { it.comment != null } ?: return false
        pushUndo(activeCell())
        cell.commentShown = !cell.commentShown
        touchContent()
        return true
    }
    /** A note drawn beside its cell: kept shown, or every note while Show All Notes is on. */
    fun noteVisible(cell: com.therealityteam.aeropages.model.Cell?): Boolean = cell?.comment != null && (showAllNotes || cell.commentShown)
    val activeComment: String? get() = sheet.cell(selRow, selCol)?.comment

    /** A cell's comment, for Excel's Comments pane. */
    data class CellNote(val row: Int, val col: Int, val text: String, val author: String? = null, val dateMs: Long = 0L)

    /** This sheet's comments in Excel's order: row by row, left to right; who wrote each and when, as far as known. */
    fun commentCells(): List<CellNote> = sheet.cells.entries
        .mapNotNull { (k, c) -> c.comment?.let { CellNote((k / 1000L).toInt(), (k % 1000L).toInt(), it, c.commentAuthor, c.commentDate) } }
        .sortedWith(compareBy({ it.row }, { it.col }))

    // --- cut (clipboard) --------------------------------------------------------
    /** Cut: the block copied to be moved, and cleared — formats too, as Excel's cut-and-paste moves them. */
    /** Cut: the cells copied and cleared, their rules going with them (a paste puts them where the cells land). */
    fun cutSelection() { if (refuseIfSheetProtected()) return; copySelection(); clipCut = true; clear(ClearWhat.ALL, rules = clipRules != null) }

    // --- text to columns (分列): split active column's cells by [delimiter] into adjacent columns -
    fun textToColumns(delimiter: String) {
        if (delimiter.isEmpty()) return
        val c = selCol
        pushUndo(cells(rangeRowMin(), c, rangeRowMax(), Sheet.MAX_COLS - 1))
        for (r in rangeRowMin()..rangeRowMax()) {
            val raw = sheet.cell(r, c)?.text ?: continue
            if (raw.startsWith("=")) continue
            val parts = raw.split(delimiter)
            if (parts.size <= 1) continue
            parts.forEachIndexed { i, part -> if (c + i < Sheet.MAX_COLS) sheet.ensure(r, c + i).text = part }
        }
        touchContent()
    }

    // --- charts (插入→图表) -------------------------------------------------------
    /**
     * Build a chart from the selected range. First selected column = categories; each remaining
     * column = a series. When the first row is non-numeric in the series columns it is treated
     * as a header row providing series names. Single-column selections chart that column.
     */
    private class ChartData(val categories: List<String>, val series: List<com.therealityteam.aeropages.model.ChartSeries>, val headerRow: Boolean, val multiCol: Boolean)

    /**
     * Categories + series from a block of [target]'s cells: the first column holds the category
     * labels when the block is wider than one column, and a first row with any non-numeric text
     * holds the series names (raw values, so a number-formatted "1,500" is still a number).
     * [header] / [cats] force the layout a bound chart was created with.
     */
    private fun chartDataOf(target: com.therealityteam.aeropages.model.Sheet, r1: Int, c1: Int, r2: Int, c2: Int, header: Boolean? = null, cats: Boolean? = null): ChartData? {
        if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 20_000L) return null
        fun raw(r: Int, c: Int) = FormulaEngine.display(target, r, c, workbook.sheets, workbook.names, memo)
        fun shown(r: Int, c: Int) = if (target === sheet) display(r, c) else raw(r, c)
        val multiCol = (cats ?: (c2 > c1)) && c2 > c1
        val firstDataCol = if (multiCol) c1 + 1 else c1
        val headerRow = header ?: (firstDataCol..c2).any { c -> val t = raw(r1, c); t.isNotBlank() && t.toDoubleOrNull() == null }
        val dataStart = if (headerRow) r1 + 1 else r1
        if (dataStart > r2) return null
        val categories = (dataStart..r2).map { r ->
            if (multiCol) shown(r, c1).ifBlank { Texts.get(TextId.ROW_N, r + 1) } else Texts.get(TextId.ROW_N, r + 1)
        }
        val series = (firstDataCol..c2).map { c ->
            val name = if (headerRow) shown(r1, c).ifBlank { com.therealityteam.aeropages.model.Workbook.colLabel(c) } else com.therealityteam.aeropages.model.Workbook.colLabel(c)
            com.therealityteam.aeropages.model.ChartSeries(name, (dataStart..r2).map { r -> raw(r, c).toDoubleOrNull()?.toFloat() ?: 0f }.toMutableList())
        }
        if (series.isEmpty()) return null
        return ChartData(categories, series, headerRow, multiCol)
    }

    /** Insert a chart bound to the selected block; false when the selection is a single cell or holds no data rows. */
    fun insertChartFromSelection(type: com.therealityteam.aeropages.model.ChartType): Boolean {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (r1 == r2 && c1 == c2) return false
        val data = chartDataOf(sheet, r1, c1, r2, c2) ?: return false
        pushUndo(sheetMeta())
        sheet.charts.add(com.therealityteam.aeropages.model.SlideChart(
            type = type, title = Texts.get(TextId.CHART_N, sheet.charts.size + 1),
            categories = data.categories.toMutableList(), series = data.series.toMutableList(),
            sourceRef = com.therealityteam.aeropages.model.A1.rangeRef(r1, c1, r2, c2), sourceHeader = data.headerRow, sourceCats = data.multiCol))
        touchContent()
        return true
    }

    /** Re-bind chart [index] to the selected block (选择数据); false when the selection is a single cell or holds no data rows. */
    fun setChartSource(index: Int): Boolean {
        val chart = sheet.charts.getOrNull(index) ?: return false
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (r1 == r2 && c1 == c2) return false
        val data = chartDataOf(sheet, r1, c1, r2, c2) ?: return false
        pushUndo(sheetMeta())
        chart.sourceRef = com.therealityteam.aeropages.model.A1.rangeRef(r1, c1, r2, c2)
        chart.sourceHeader = data.headerRow; chart.sourceCats = data.multiCol
        chart.categories.clear(); chart.categories.addAll(data.categories)
        chart.series.clear(); chart.series.addAll(data.series)
        touchContent()
        return true
    }

    /** Charts bound to a range follow their cells: recompute the cached categories/series after any content change. */
    private fun refreshCharts() {
        for (s in workbook.sheets) for (chart in s.charts) {
            val ref = chart.sourceRef ?: continue
            val (r1, c1, r2, c2) = com.therealityteam.aeropages.model.A1.parseRange(ref) ?: continue
            val data = chartDataOf(s, r1, c1, r2, c2, chart.sourceHeader, chart.sourceCats) ?: continue
            if (chart.categories != data.categories) { chart.categories.clear(); chart.categories.addAll(data.categories) }
            val same = chart.series.size == data.series.size && chart.series.indices.all { i -> chart.series[i].name == data.series[i].name && chart.series[i].values == data.series[i].values }
            if (!same) { chart.series.clear(); chart.series.addAll(data.series) }
        }
    }
    fun deleteChart(index: Int) { if (index in sheet.charts.indices) { pushUndo(sheetMeta()); sheet.charts.removeAt(index); touchContent() } }

    /** Changes chart [index] (its type, title, axis titles, legend…) as one undo step. */
    fun updateChart(index: Int, change: (com.therealityteam.aeropages.model.SlideChart) -> Unit) {
        val chart = sheet.charts.getOrNull(index) ?: return
        pushUndo(sheetMeta())
        change(chart)
        touchChart()
    }

    // --- pictures (Insert → Pictures): floating over the cells, each anchored to one -----------
    /** The picture picked on the grid — its frame and handles show, Delete removes it — or null. */
    var selectedImage by mutableStateOf<SheetImage?>(null)
        private set

    /** Pick [img] on the grid (null: back to the cells). */
    fun selectImage(img: SheetImage?) {
        commitEntry()
        selectedImage = img?.takeIf { p -> sheet.images.any { it === p } }
        if (selectedImage != null) selectedShape = null
        lastKey = null
        bump()
    }

    /**
     * Insert a picture at the active cell, as Excel places one: at its natural size (its pixels at
     * the resolution the file declares, 96 dpi when none), shrunk to fit [MAX_PICTURE_W] ×
     * [MAX_PICTURE_H] points. It becomes the picked picture; false when the data is no picture the
     * app can size (or the sheet is protected).
     */
    fun insertImage(data: ByteArray, mime: String): Boolean {
        if (refuseIfSheetProtected(Allow.OBJECTS)) return false
        val (w, h) = com.therealityteam.aeropages.format.ImageSize.naturalPt(data) ?: return false
        if (w <= 0f || h <= 0f) return false
        commitEntry()
        val fit = minOf(1f, MAX_PICTURE_W / w, MAX_PICTURE_H / h)
        pushUndo(sheetMeta())
        var n = sheet.images.size + 1
        while (sheet.images.any { it.name == Texts.get(TextId.PICTURE_N, n) }) n++
        val img = SheetImage(data, mime, selRow, selCol, 0f, 0f, w * fit, h * fit, Texts.get(TextId.PICTURE_N, n))
        sheet.images.add(img)
        selectedImage = img
        imageInserts++
        touchContent()
        return true
    }

    /** Bumped when a picture is inserted: Excel then opens Picture Format. */
    var imageInserts by mutableIntStateOf(0)
        private set
    /** Bumped when a shape or text box is inserted: Excel then opens Shape Format. */
    var shapeInserts by mutableIntStateOf(0)
        private set

    /** Remove the picked picture. */
    fun deleteSelectedImage() {
        val img = selectedImage ?: return
        if (refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        sheet.images.removeAll { it === img }
        selectedImage = null
        touchContent()
    }

    /** A column's width and a row's height in points as the grid lays pictures over them: hidden ones take no room. */
    private fun colPt(c: Int, skip: Set<Int>) = if (c in skip) 0f else GRID_COL_PT * sheet.colWidthFactor(c)
    private fun rowPt(r: Int, skip: Set<Int>) = if (r in skip) 0f else SheetImage.ROW_PT * sheet.rowHeightFactor(r)

    /** Where [img]'s top-left corner lies on the grid, in points from the sheet's top-left corner (x to y). */
    fun imageOrigin(img: SheetImage): Pair<Float, Float> = floatingOrigin(img)

    /** Where [f]'s — a picture's or a shape's — top-left corner lies on the grid, in points from the sheet's top-left corner. */
    fun floatingOrigin(f: com.therealityteam.aeropages.model.SheetFloating): Pair<Float, Float> {
        val rows = invisibleRows; val cols = invisibleCols
        var x = 0f; for (c in 0 until f.col) x += colPt(c, cols)
        var y = 0f; for (r in 0 until f.row) y += rowPt(r, rows)
        return x + (if (f.col in cols) 0f else f.dxPt) to y + (if (f.row in rows) 0f else f.dyPt)
    }

    private fun anchorImage(img: SheetImage, xPt: Float, yPt: Float) = anchorFloating(img, xPt, yPt)

    /** Anchor [f] at the cell under ([xPt], [yPt]) — points from the sheet's top-left — with the rest as its offset. */
    private fun anchorFloating(f: com.therealityteam.aeropages.model.SheetFloating, xPt: Float, yPt: Float) {
        val rows = invisibleRows; val cols = invisibleCols
        var x = xPt.coerceAtLeast(0f); var y = yPt.coerceAtLeast(0f)
        var c = 0
        while (c < sheet.colCount - 1 && (c in cols || x >= colPt(c, cols))) { x -= colPt(c, cols); c++ }
        var r = 0
        while (r < sheet.rowCount - 1 && (r in rows || y >= rowPt(r, rows))) { y -= rowPt(r, rows); r++ }
        f.col = c; f.row = r; f.dxPt = x; f.dyPt = y
    }

    // --- shapes and text boxes (Insert → Shapes, Text Box): floating like pictures ---------------
    /** The shape picked on the grid — its frame and handles show, Delete removes it — or null. */
    var selectedShape by mutableStateOf<com.therealityteam.aeropages.model.SheetShape?>(null)
        private set

    /** Pick [sh] on the grid (null: back to the cells). */
    fun selectShape(sh: com.therealityteam.aeropages.model.SheetShape?) {
        commitEntry()
        selectedShape = sh?.takeIf { p -> sheet.shapes.any { it === p } }
        if (selectedShape != null) selectedImage = null
        lastKey = null
        bump()
    }

    /**
     * Insert → Shapes / Text Box: a [type] at the active cell in Excel's default look — an accent-blue
     * shape with white centred text, a white text box with a grey rule and dark text at its top, a
     * blue line (arrowheads [begin] and [end]: Line Arrow, Double Arrow) — which becomes the picked
     * shape. False on a protected sheet.
     */
    fun insertShape(type: com.therealityteam.aeropages.model.ShapeType, text: String = "",
                    begin: com.therealityteam.aeropages.model.LineEnd = com.therealityteam.aeropages.model.LineEnd.NONE,
                    end: com.therealityteam.aeropages.model.LineEnd = com.therealityteam.aeropages.model.LineEnd.NONE): Boolean {
        if (refuseIfSheetProtected(Allow.OBJECTS)) return false
        commitEntry()
        pushUndo(sheetMeta())
        val textBox = type == com.therealityteam.aeropages.model.ShapeType.TEXTBOX
        val line = type == com.therealityteam.aeropages.model.ShapeType.LINE
        val format = CharacterFormat(fontSizePt = 11f, color = if (textBox) 0xFF000000 else 0xFFFFFFFF)
        val box = com.therealityteam.aeropages.model.SlideBox(text, format,
            if (textBox) com.therealityteam.aeropages.model.TextAlign.LEFT else com.therealityteam.aeropages.model.TextAlign.CENTER)
        val shape = com.therealityteam.aeropages.model.SlideShape(type, box, fill = if (line) null else if (textBox) 0xFFFFFFFF else ACCENT,
            border = if (textBox) 0xFF808080 else if (line) ACCENT else ACCENT_DARK, borderWidthPt = if (textBox) 0.75f else 1f,
            anchor = if (textBox) com.therealityteam.aeropages.model.ShapeAnchor.TOP else com.therealityteam.aeropages.model.ShapeAnchor.CENTER,
            beginArrow = if (line) begin else com.therealityteam.aeropages.model.LineEnd.NONE, endArrow = if (line) end else com.therealityteam.aeropages.model.LineEnd.NONE)
        var n = sheet.shapes.size + 1
        while (sheet.shapes.any { it.name == Texts.get(TextId.SHAPE_N, n) }) n++
        val (w, h) = when {
            textBox -> 144f to 48f
            line -> 120f to 0f
            else -> 96f to 64f
        }
        val sh = com.therealityteam.aeropages.model.SheetShape(shape, selRow, selCol, 0f, 0f, w, h, Texts.get(TextId.SHAPE_N, n))
        sheet.shapes.add(sh)
        selectedShape = sh; selectedImage = null
        shapeInserts++
        touchContent()
        return true
    }

    /** Bumped to ask the grid to open the picked shape's Edit Text (after Insert → Text Box, or Enter on a shape). */
    var shapeTextRequest by mutableIntStateOf(0)
        private set

    fun requestShapeText() { if (selectedShape != null) shapeTextRequest++ }

    /** Remove the picked shape. */
    fun deleteSelectedShape() {
        val sh = selectedShape ?: return
        if (refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        sheet.shapes.removeAll { it === sh }
        selectedShape = null
        touchContent()
    }

    /** Move [sh] so its top-left corner lies at ([xPt], [yPt]) points from the sheet's corner. */
    fun moveShape(sh: com.therealityteam.aeropages.model.SheetShape, xPt: Float, yPt: Float) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        anchorFloating(sh, xPt, yPt)
        touchContent()
    }

    /** Give [sh] a new frame in one step (what dragging a handle leaves); a line may be flat, a shape keeps [MIN_PICTURE_PT] a side. */
    fun setShapeFrame(sh: com.therealityteam.aeropages.model.SheetShape, xPt: Float, yPt: Float, wPt: Float, hPt: Float) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        val min = if (sh.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE) 0f else MIN_PICTURE_PT
        sh.widthPt = wPt.coerceIn(min, MAX_PICTURE_PT); sh.heightPt = hPt.coerceIn(min, MAX_PICTURE_PT)
        anchorFloating(sh, xPt, yPt)
        touchContent()
    }

    // --- the object clipboard: a picked picture or shape, shared with the other modules --------------
    /** Whether a picture or a shape is picked (what Copy and Cut then take instead of the cells). */
    val hasObjectSelected: Boolean get() = selectedImage != null || selectedShape != null

    /** Copy the picked picture or shape for any module's paste; the words it carries, or null when none is picked. */
    fun copySelectedObject(): String? {
        val item = selectedImage?.let { ObjectClipboard.Picture(it.data, it.mime, it.widthPt, it.heightPt, it.cropL, it.cropT, it.cropR, it.cropB) }
            ?: selectedShape?.let { ObjectClipboard.Shape(it.shape.copyOf(), it.widthPt, it.heightPt) }
            ?: return null
        ObjectClipboard.put(item)
        bump()
        return ObjectClipboard.textOf(item)
    }

    /**
     * Copy chart [index] (with its data as it stands) for any module's paste — Excel's chart pasted
     * into a slide or a page — at a page's default chart size; its title, or null when there is none.
     */
    fun copyChart(index: Int): String? {
        val chart = sheet.charts.getOrNull(index) ?: return null
        ObjectClipboard.put(ObjectClipboard.Chart(chart.copyOf().also { it.sourceRef = null },
            com.therealityteam.aeropages.model.ChartBlock.DEFAULT_W, com.therealityteam.aeropages.model.ChartBlock.DEFAULT_H))
        bump()
        return chart.title
    }

    /** Cut the picked picture or shape: copied as [copySelectedObject] copies it, then removed. */
    fun cutSelectedObject(): String? {
        if (!hasObjectSelected || refuseIfSheetProtected(Allow.OBJECTS)) return null
        val text = copySelectedObject() ?: return null
        if (selectedImage != null) deleteSelectedImage() else deleteSelectedShape()
        return text
    }

    /**
     * Paste [item] at the active cell, picked: a picture or a shape at the size it was shown at, a
     * chart as a chart of its own (its data as copied), a slide's table as cells. False on a
     * protected sheet.
     */
    fun pasteItem(item: ObjectClipboard.Item): Boolean {
        if (refuseIfSheetProtected()) return false
        commitEntry()
        when (item) {
            is ObjectClipboard.Picture -> {
                pushUndo(sheetMeta())
                var n = sheet.images.size + 1
                while (sheet.images.any { it.name == Texts.get(TextId.PICTURE_N, n) }) n++
                val img = SheetImage(item.data, item.mime, selRow, selCol, 0f, 0f, item.widthPt, item.heightPt, Texts.get(TextId.PICTURE_N, n))
                img.setCrop(item.cropL, item.cropT, item.cropR, item.cropB)
                sheet.images.add(img)
                selectedImage = img; selectedShape = null
            }
            is ObjectClipboard.Shape -> {
                pushUndo(sheetMeta())
                var n = sheet.shapes.size + 1
                while (sheet.shapes.any { it.name == Texts.get(TextId.SHAPE_N, n) }) n++
                val sh = com.therealityteam.aeropages.model.SheetShape(item.shape.copyOf(), selRow, selCol, 0f, 0f, item.widthPt, item.heightPt, Texts.get(TextId.SHAPE_N, n))
                sheet.shapes.add(sh)
                selectedShape = sh; selectedImage = null
            }
            is ObjectClipboard.Chart -> {
                pushUndo(sheetMeta())
                sheet.charts.add(item.chart.copyOf().also { it.sourceRef = null })
            }
            is ObjectClipboard.Table -> { pasteTsv(ObjectClipboard.textOf(item)); return true }
        }
        touchContent()
        return true
    }

    /** Ctrl+D on a shape: a copy a little below and to the right, which becomes the picked one. */
    fun duplicateSelectedShape() {
        val sh = selectedShape ?: return
        if (refuseIfSheetProtected(Allow.OBJECTS)) return
        val (x, y) = floatingOrigin(sh)
        pushUndo(sheetMeta())
        var n = sheet.shapes.size + 1
        while (sheet.shapes.any { it.name == Texts.get(TextId.SHAPE_N, n) }) n++
        val copy = sh.copyOf().also { it.name = Texts.get(TextId.SHAPE_N, n) }
        anchorFloating(copy, x + DUPLICATE_OFFSET_PT, y + DUPLICATE_OFFSET_PT)
        sheet.shapes.add(copy)
        selectedShape = copy
        touchContent()
    }

    /** Arrow keys on the picked shape: [dxPt] × [dyPt] further; a run of nudges is one undo step. */
    fun nudgeSelectedShape(dxPt: Float, dyPt: Float) {
        val sh = selectedShape ?: return
        if (refuseIfSheetProtected(Allow.OBJECTS)) return
        val (x, y) = floatingOrigin(sh)
        pushUndo(sheetMeta(), "nudgeShape")
        anchorFloating(sh, x + dxPt, y + dyPt)
        touchContent()
    }

    /** [sh]'s text, in the look of its first run (lines as typed; its levels and breaks kept as characters). */
    fun setShapeText(sh: com.therealityteam.aeropages.model.SheetShape, text: String) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS)) return
        if (sh.shape.box.text == text) return
        pushUndo(sheetMeta())
        val runs = sh.shape.box.paragraph.runs
        val format = runs.firstOrNull { it.text.isNotEmpty() }?.format?.copyOf() ?: runs.firstOrNull()?.format?.copyOf() ?: CharacterFormat(fontSizePt = 11f)
        runs.clear()
        runs.add(com.therealityteam.aeropages.model.TextRun(text, format))
        touchContent()
    }

    /** [sh]'s fill (null: none — a line has none). */
    fun setShapeFill(sh: com.therealityteam.aeropages.model.SheetShape, color: Long?) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS) || sh.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE) return
        pushUndo(sheetMeta())
        sh.shape.fill = color; sh.shape.fillGradient = null
        touchContent()
    }

    /** [sh]'s outline (a line's colour; null: none). */
    fun setShapeOutline(sh: com.therealityteam.aeropages.model.SheetShape, color: Long?) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        sh.shape.border = color
        touchContent()
    }

    /** [sh]'s outline weight, in points (¼–12). */
    fun setShapeOutlineWeight(sh: com.therealityteam.aeropages.model.SheetShape, pt: Float) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        sh.shape.borderWidthPt = pt.coerceIn(0.25f, 12f)
        touchContent()
    }

    /** [sh]'s outline dashes (a line's too). */
    fun setShapeDash(sh: com.therealityteam.aeropages.model.SheetShape, dash: com.therealityteam.aeropages.model.LineDash) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        sh.shape.dash = dash
        touchContent()
    }

    /** A line's (or an open freeform's) arrowheads at its [begin] and [end]; a closed shape has no ends. */
    fun setShapeArrows(sh: com.therealityteam.aeropages.model.SheetShape, begin: com.therealityteam.aeropages.model.LineEnd, end: com.therealityteam.aeropages.model.LineEnd) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS) || !sh.shape.hasEnds) return
        pushUndo(sheetMeta())
        sh.shape.beginArrow = begin; sh.shape.endArrow = end
        touchContent()
    }

    /** Move [img] so its top-left corner lies at ([xPt], [yPt]) points from the sheet's corner (where a drag left it). */
    fun moveImage(img: SheetImage, xPt: Float, yPt: Float) {
        if (sheet.images.none { it === img } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        anchorImage(img, xPt, yPt)
        touchContent()
    }

    /**
     * Give [img] a new frame in one step: its top-left corner at ([xPt], [yPt]) points from the
     * sheet's corner, [wPt] × [hPt] in size (what dragging a corner handle leaves).
     */
    fun setImageFrame(img: SheetImage, xPt: Float, yPt: Float, wPt: Float, hPt: Float) {
        if (sheet.images.none { it === img } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        img.widthPt = wPt.coerceIn(MIN_PICTURE_PT, MAX_PICTURE_PT); img.heightPt = hPt.coerceIn(MIN_PICTURE_PT, MAX_PICTURE_PT)
        anchorImage(img, xPt, yPt)
        touchContent()
    }

    /** Excel's Ctrl+D on a picture: a copy a little below and to the right, which becomes the picked one. */
    fun duplicateSelectedImage() {
        val img = selectedImage ?: return
        if (refuseIfSheetProtected(Allow.OBJECTS)) return
        val (x, y) = imageOrigin(img)
        pushUndo(sheetMeta())
        var n = sheet.images.size + 1
        while (sheet.images.any { it.name == Texts.get(TextId.PICTURE_N, n) }) n++
        val copy = img.copyOf().also { it.name = Texts.get(TextId.PICTURE_N, n) }
        anchorImage(copy, x + DUPLICATE_OFFSET_PT, y + DUPLICATE_OFFSET_PT)
        sheet.images.add(copy)
        selectedImage = copy
        touchContent()
    }

    /** Arrow keys on the picked picture: [dxPt] × [dyPt] further; a run of nudges is one undo step. */
    fun nudgeSelectedImage(dxPt: Float, dyPt: Float) {
        val img = selectedImage ?: return
        if (refuseIfSheetProtected(Allow.OBJECTS)) return
        val (x, y) = imageOrigin(img)
        pushUndo(sheetMeta(), "nudgeImage")
        anchorImage(img, x + dxPt, y + dyPt)
        touchContent()
    }

    /** Resize [img] to [wPt] × [hPt] ([MIN_PICTURE_PT]..[MAX_PICTURE_PT] a side); its top-left corner stays. */
    fun resizeImage(img: SheetImage, wPt: Float, hPt: Float) {
        if (sheet.images.none { it === img } || refuseIfSheetProtected(Allow.OBJECTS)) return
        val w = wPt.coerceIn(MIN_PICTURE_PT, MAX_PICTURE_PT); val h = hPt.coerceIn(MIN_PICTURE_PT, MAX_PICTURE_PT)
        if (w == img.widthPt && h == img.heightPt) return
        pushUndo(sheetMeta())
        img.widthPt = w; img.heightPt = h
        touchContent()
    }

    /** Excel's Reset Picture & Size, keeping the crop: the part the crop leaves at its natural size. */
    fun resetImageSize(img: SheetImage) {
        val (w, h) = com.therealityteam.aeropages.format.ImageSize.naturalPt(img.data) ?: return
        resizeImage(img, w * img.keptWidth(), h * img.keptHeight())
    }

    /**
     * Excel's Reset Picture on [img]: its crop and shadow gone, the whole picture back at the scale it was shown at (its
     * corner where the picture's own corner was); with [size], also back at the size it came in at. One undo step;
     * nothing when there is nothing to reset.
     */
    fun resetImage(img: SheetImage, size: Boolean) {
        if (sheet.images.none { it === img } || refuseIfSheetProtected(Allow.OBJECTS)) return
        val (fullW, fullH) = img.uncroppedSize(img.widthPt, img.heightPt)
        val natural = if (!size) null else com.therealityteam.aeropages.format.ImageSize.naturalPt(img.data)?.let { (w, h) ->
            val fit = minOf(1f, MAX_PICTURE_W / w, MAX_PICTURE_H / h); w * fit to h * fit
        }
        val (w, h) = natural ?: (fullW to fullH)
        if (!img.cropped && img.shadow == null && w == img.widthPt && h == img.heightPt) return
        pushUndo(sheetMeta())
        val (x, y) = imageOrigin(img)
        val dx = img.cropL * fullW; val dy = img.cropT * fullH
        img.setCrop(0f, 0f, 0f, 0f); img.shadow = null
        img.widthPt = w.coerceIn(MIN_PICTURE_PT, MAX_PICTURE_PT); img.heightPt = h.coerceIn(MIN_PICTURE_PT, MAX_PICTURE_PT)
        anchorImage(img, x - dx, y - dy)
        touchContent()
    }

    /**
     * Arrange on the picked picture or shape, among the sheet's pictures or shapes: one layer forward ([forward] true) or
     * back, or ([toEdge]) all the way to the front or the back. One undo step; nothing when it is already there.
     */
    fun arrangeSelected(forward: Boolean, toEdge: Boolean) {
        if (refuseIfSheetProtected(Allow.OBJECTS)) return
        fun <T> MutableList<T>.step(item: T): Boolean {
            val at = indexOfFirst { it === item }
            if (at < 0) return false
            val to = when {
                toEdge && forward -> lastIndex
                toEdge -> 0
                forward -> (at + 1).coerceAtMost(lastIndex)
                else -> (at - 1).coerceAtLeast(0)
            }
            if (to == at) return false
            pushUndo(sheetMeta())
            removeAt(at); add(to, item)
            return true
        }
        val moved = selectedImage?.let { sheet.images.step(it) } ?: selectedShape?.let { sheet.shapes.step(it) } ?: false
        if (moved) touchContent()
    }

    /**
     * Shape Styles on [sh]: Office's fill, outline and text colour together (a line takes only the outline). One undo step.
     */
    fun applyShapeStyle(sh: com.therealityteam.aeropages.model.SheetShape, fill: Long?, border: Long?, text: Long?) {
        if (sheet.shapes.none { it === sh } || refuseIfSheetProtected(Allow.OBJECTS)) return
        pushUndo(sheetMeta())
        val line = sh.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE
        if (!line) { sh.shape.fill = fill; sh.shape.fillGradient = null }
        sh.shape.border = border
        if (!line && text != null) sh.shape.box.paragraph.runs.forEach { it.format.color = text }
        touchContent()
    }

    /** Size's Height and Width for [sh] in points (null keeps that side); its corner stays. One undo step. */
    fun setShapeSize(sh: com.therealityteam.aeropages.model.SheetShape, wPt: Float?, hPt: Float?) {
        if (sheet.shapes.none { it === sh }) return
        val (x, y) = floatingOrigin(sh)
        val line = sh.shape.type == com.therealityteam.aeropages.model.ShapeType.LINE
        val w = (wPt ?: sh.widthPt).coerceAtLeast(4f); val h = (hPt ?: sh.heightPt).coerceAtLeast(if (line) 0f else 4f)
        if (w == sh.widthPt && h == sh.heightPt) return
        setShapeFrame(sh, x, y, w, h)
    }

    /** Alt Text on [img]: what a screen reader says for it, or ([decorative]) that it passes it by. One undo step. */
    fun setImageAltText(img: SheetImage, text: String, decorative: Boolean) {
        if (sheet.images.none { it === img } || refuseIfSheetProtected(Allow.OBJECTS)) return
        val t = if (decorative) "" else text.trim()
        if (img.altText == t && img.decorative == decorative) return
        pushUndo(sheetMeta())
        img.altText = t; img.decorative = decorative
        touchContent()
    }

    /** [img]'s outer shadow — Picture Styles' offset one, or none. */
    fun setImageShadow(img: SheetImage, shadow: com.therealityteam.aeropages.model.ShapeShadow?) {
        if (sheet.images.none { it === img } || refuseIfSheetProtected(Allow.OBJECTS) || img.shadow == shadow) return
        pushUndo(sheetMeta())
        img.shadow = shadow
        touchContent()
    }

    /**
     * Crop [img] to the fractions [l], [t], [r], [b] of its source cut from each side, as Office
     * crops: the part that stays keeps its size and place on the sheet — the frame shrinks or grows
     * around it and its corner moves by what the left and top cuts take.
     */
    fun cropImage(img: SheetImage, l: Float, t: Float, r: Float, b: Float) {
        if (sheet.images.none { it === img } || refuseIfSheetProtected(Allow.OBJECTS)) return
        val (nl, nt, nr, nb) = com.therealityteam.aeropages.model.Croppable.clamp(l, t, r, b)
        if (nl == img.cropL && nt == img.cropT && nr == img.cropR && nb == img.cropB) return
        pushUndo(sheetMeta())
        val (fullW, fullH) = img.uncroppedSize(img.widthPt, img.heightPt)
        val (x, y) = imageOrigin(img)
        val dx = (nl - img.cropL) * fullW; val dy = (nt - img.cropT) * fullH
        img.setCrop(nl, nt, nr, nb)
        img.widthPt = (fullW * img.keptWidth()).coerceAtLeast(MIN_PICTURE_PT)
        img.heightPt = (fullH * img.keptHeight()).coerceAtLeast(MIN_PICTURE_PT)
        anchorImage(img, x + dx, y + dy)
        touchContent()
    }

    // --- column width / row height (格式) ----------------------------------------
    /** Adjust the width factor of every selected column by [delta] (clamped 0.5..4). */
    fun adjustColWidth(delta: Float) {
        if (refuseIfSheetProtected(Allow.FORMAT_COLUMNS)) return
        pushUndo(sheetMeta(), "colWidth")
        for (c in rangeColMin()..rangeColMax()) {
            sheet.colWidths[c] = (sheet.colWidthFactor(c) + delta).coerceIn(0.5f, 4f)
        }
        touchContent()
    }
    /** Adjust the height factor of every selected row by [delta] (clamped 0.75..4). */
    fun adjustRowHeight(delta: Float) {
        if (refuseIfSheetProtected(Allow.FORMAT_ROWS)) return
        pushUndo(sheetMeta(), "rowHeight")
        for (r in rangeRowMin()..rangeRowMax()) {
            sheet.rowHeights[r] = (sheet.rowHeightFactor(r) + delta).coerceIn(0.75f, 4f)
        }
        touchContent()
    }
    /** Excel's Row Height…: the selected rows' height in points, null when they differ. */
    fun selectedRowHeightPt(): Float? =
        (rangeRowMin()..rangeRowMax()).map { sheet.rowHeightFactor(it) }.distinct().singleOrNull()?.let { it * ROW_PT }

    /** Excel's Column Width…: the selected columns' width in characters of the default font, null when they differ. */
    fun selectedColWidthChars(): Float? =
        (rangeColMin()..rangeColMax()).map { sheet.colWidthFactor(it) }.distinct().singleOrNull()?.let { it * COL_CHARS }

    /** Excel's Row Height…: the selected rows [pt] points tall (within the sheet's limits); 0 hides them, as in Excel. One undo step. */
    fun setRowHeightPt(pt: Float) {
        if (refuseIfSheetProtected(Allow.FORMAT_ROWS)) return
        commitEntry()
        if (pt <= 0f) { hideRows(); return }
        pushUndo(sheetMeta())
        val f = (pt / ROW_PT).coerceIn(MIN_ROW_FACTOR, MAX_ROW_FACTOR)
        for (r in rangeRowMin()..rangeRowMax()) if (f == 1f) sheet.rowHeights.remove(r) else sheet.rowHeights[r] = f
        touchContent()
    }

    /** Excel's Column Width…: the selected columns [chars] characters wide (within the sheet's limits); 0 hides them. One undo step. */
    fun setColWidthChars(chars: Float) {
        if (refuseIfSheetProtected(Allow.FORMAT_COLUMNS)) return
        commitEntry()
        if (chars <= 0f) { hideColumns(); return }
        pushUndo(sheetMeta())
        val f = (chars / COL_CHARS).coerceIn(MIN_COL_FACTOR, MAX_COL_FACTOR)
        for (c in rangeColMin()..rangeColMax()) if (f == 1f) sheet.colWidths.remove(c) else sheet.colWidths[c] = f
        touchContent()
    }

    /** Excel's AutoFit Row Height over the selected rows. */
    fun autoFitRowHeight() = autoFitRows(rangeRowMin()..rangeRowMax())

    /** Auto-fit the selected columns (Home → Format → AutoFit Column Width). */
    fun autoFitColWidth() = autoFitColumns(rangeColMin()..rangeColMax())

    /**
     * Fits [cols] to their widest shown text, measured the way the grid draws it: an average Latin
     * character, a CJK / full-width one about twice as wide, scaled by the cell's font size, plus the
     * cell padding (Excel's double-click on a column edge). One undo step.
     */
    fun autoFitColumns(cols: IntRange) {
        if (refuseIfSheetProtected(Allow.FORMAT_COLUMNS)) return
        pushUndo(sheetMeta())
        for (c in cols) {
            var widest = 0f
            for (r in 0 until sheet.rowCount) {
                val text = display(r, c)
                if (text.isEmpty()) continue
                val size = sheet.cell(r, c)?.format?.fontSizePt ?: FIT_BASE_PT
                widest = maxOf(widest, fitWidthDp(text, size))
            }
            if (widest > 0f) sheet.colWidths[c] = ((widest + FIT_PAD_DP) / FIT_CELL_W_DP).coerceIn(MIN_COL_FACTOR, MAX_COL_FACTOR)
            else sheet.colWidths.remove(c)
        }
        touchContent()
    }

    /**
     * Fits [rows] to their content: the tallest font on the row, and — for wrapped cells — as many
     * lines as the text needs at its column's width (Excel's double-click on a row edge).
     */
    /**
     * Excel's AutoFit Row Height (double-tap a row's edge): the rows lose the heights they were given
     * and follow their content again — their largest type and wrapped lines ([RowFit]).
     */
    fun autoFitRows(rows: IntRange) {
        if (refuseIfSheetProtected(Allow.FORMAT_ROWS)) return
        if (rows.any { it in sheet.rowHeights }) {
            pushUndo(sheetMeta())
            for (r in rows) sheet.rowHeights.remove(r)
            touchContent()
        } else {
            // Already automatic: fit them to the cells as they are now.
            com.therealityteam.aeropages.model.RowFit.refresh(sheet, rows) { r, c -> display(r, c) }
            bump()
        }
    }

    // --- dragging a header edge (resize like Excel) --------------------------------------------

    private var resizeSerial = 0
    private var resizeRange: IntRange? = null
    private var resizeAnchor = 0

    /**
     * Starts dragging the right edge of column [c]. The whole drag is one undo step; when [c] is one
     * of several whole columns selected, they all take the new width (as in Excel).
     */
    fun beginColResize(c: Int) {
        resizeSerial++; resizeAnchor = c
        resizeRange = if (wholeColumnsSelected && c in rangeColMin()..rangeColMax()) rangeColMin()..rangeColMax() else c..c
    }

    /** Moves the dragged column edge by [deltaFactor] default column widths. */
    fun dragColWidth(deltaFactor: Float) {
        if (refuseIfSheetProtected(Allow.FORMAT_COLUMNS)) return
        val cols = resizeRange ?: return
        val target = (sheet.colWidthFactor(resizeAnchor) + deltaFactor).coerceIn(MIN_COL_FACTOR, MAX_COL_FACTOR)
        if (cols.all { sheet.colWidthFactor(it) == target }) return
        pushUndo(sheetMeta(), "resize$resizeSerial")
        for (cc in cols) sheet.colWidths[cc] = target
        touchContent()
    }

    /** Starts dragging the bottom edge of row [r] (several whole rows selected resize together). */
    fun beginRowResize(r: Int) {
        resizeSerial++; resizeAnchor = r
        resizeRange = if (wholeRowsSelected && r in rangeRowMin()..rangeRowMax()) rangeRowMin()..rangeRowMax() else r..r
    }

    /** Moves the dragged row edge by [deltaFactor] default row heights. */
    fun dragRowHeight(deltaFactor: Float) {
        if (refuseIfSheetProtected(Allow.FORMAT_ROWS)) return
        val rows = resizeRange ?: return
        val target = (sheet.rowHeightFactor(resizeAnchor) + deltaFactor).coerceIn(MIN_ROW_FACTOR, MAX_ROW_FACTOR)
        if (rows.all { sheet.rowHeightFactor(it) == target }) return
        pushUndo(sheetMeta(), "resize$resizeSerial")
        for (rr in rows) sheet.rowHeights[rr] = target
        touchContent()
    }

    /** Ends a header-edge drag; the next change is a new undo step. */
    fun endResize() { resizeRange = null; lastKey = null }

    fun resetColRowSize() {
        if (refuseIfSheetProtected(Allow.FORMAT_COLUMNS) || refuseIfSheetProtected(Allow.FORMAT_ROWS)) return
        pushUndo(sheetMeta())
        for (c in rangeColMin()..rangeColMax()) sheet.colWidths.remove(c)
        for (r in rangeRowMin()..rangeRowMax()) sheet.rowHeights.remove(r)
        touchContent()
    }

    // --- conditional formatting -------------------------------------------------
    fun addCondFormat(type: com.therealityteam.aeropages.model.CondType, v1: Double, v2: Double, color: Long, color2: Long = 0xFFFFFFFF) =
        addCondRule(com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, type, v1, v2, color, color2,
            // A bar made here runs from nothing to the cell's whole width, as Excel 2010 on makes them.
            barMin = if (type == com.therealityteam.aeropages.model.CondType.DATA_BAR) 0 else 10,
            barMax = if (type == com.therealityteam.aeropages.model.CondType.DATA_BAR) 100 else 90))

    /** [rule] over the selection, after the sheet's other rules (one undo step). */
    fun addCondRule(rule: com.therealityteam.aeropages.model.CondFormat) {
        pushUndo(sheetMeta())
        sheet.condFormats.add(rule.over(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax()))
        touchContent()
    }
    /** The sheet's rules with their places in its list: those that touch the selection, or ([all]) every one. */
    fun condRules(all: Boolean): List<Pair<Int, com.therealityteam.aeropages.model.CondFormat>> {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        return sheet.condFormats.withIndex()
            .filter { all || (it.value.r1 <= r2 && it.value.r2 >= r1 && it.value.c1 <= c2 && it.value.c2 >= c1) }
            .map { it.index to it.value }
    }

    /** Moves rule [index] one place earlier ([delta] −1: it then wins over the one it passes) or later (+1). */
    fun moveCondRule(index: Int, delta: Int) {
        val to = index + delta
        if (index !in sheet.condFormats.indices || to !in sheet.condFormats.indices) return
        pushUndo(sheetMeta())
        java.util.Collections.swap(sheet.condFormats, index, to)
        touchContent()
    }

    fun removeCondRule(index: Int) {
        if (index !in sheet.condFormats.indices) return
        pushUndo(sheetMeta())
        sheet.condFormats.removeAt(index)
        touchContent()
    }

    /** Whether rule [index] ends the search for rules after it where it matches (Stop If True). */
    fun setCondRuleStop(index: Int, stop: Boolean) {
        val rule = sheet.condFormats.getOrNull(index) ?: return
        if (rule.stopIfTrue == stop) return
        pushUndo(sheetMeta())
        sheet.condFormats[index] = rule.copy(stopIfTrue = stop)
        touchContent()
    }

    /** Whether [formula] (no `=`) can be worked out for the selection's top-left cell (a rule's formula is written for it). */
    fun formulaWorksHere(formula: String): Boolean =
        FormulaEngine.evaluateText(sheet, formula.trim().removePrefix("="), workbook.sheets, workbook.names, rangeRowMin() to rangeColMin()) != null

    /** Removes every conditional rule of the sheet. */
    fun clearSheetCondFormats() {
        if (sheet.condFormats.isEmpty()) return
        pushUndo(sheetMeta())
        sheet.condFormats.clear()
        touchContent()
    }

    /** Remove conditional rules that intersect the current selection (清除规则). */
    fun clearCondFormats() {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        if (sheet.condFormats.none { it.r1 <= r2 && it.r2 >= r1 && it.c1 <= c2 && it.c2 >= c1 }) return
        pushUndo(sheetMeta())
        sheet.condFormats.removeAll { it.r1 <= r2 && it.r2 >= r1 && it.c1 <= c2 && it.c2 >= c1 }
        touchContent()
    }
    /** The sheet's conditional formats worked out for its content as it is (made again when the content or the sheet changes). */
    private var condEval: CondEval? = null
    private var condEvalFor: List<Any>? = null

    /** The fill the sheet's conditional formats give (row, col), or null. */
    fun conditionalFill(row: Int, col: Int): Long? = conditionalLook(row, col)?.style?.fill

    /** The data bar there — the share of the cell it takes, its colour — or null. */
    fun conditionalBar(row: Int, col: Int): Pair<Float, Long>? = conditionalLook(row, col)?.bar?.let { it.fraction to it.color }

    /** What the sheet's conditional formats do to (row, col): fill, text colour and traits, data bar, icon; null when nothing. */
    fun conditionalLook(row: Int, col: Int): CondEval.Look? {
        if (sheet.condFormats.isEmpty()) return null
        val key = listOf(contentRev, sheetIndex, sheet, sheet.condFormats.hashCode())
        if (condEvalFor != key) {
            condEvalFor = key
            condEval = CondEval(sheet, object : CondEval.Values {
                override fun raw(row: Int, col: Int): String = rawValue(row, col)
                override fun evaluate(formula: String, row: Int, col: Int): String? = FormulaEngine.evaluateText(sheet, formula, workbook.sheets, workbook.names, row to col)
            })
        }
        return condEval?.look(row, col)
    }

    // --- find & replace ---------------------------------------------------------
    /** Replace every literal occurrence of [find] with [replace] across the sheet. Returns occurrences replaced. */
    // --- Chinese conversion (Excel's Review ▸ 繁转简 / 简转繁) -------------------------------

    /**
     * The selected cells' text — the whole sheet's when one cell is selected, as Excel does — in Traditional
     * ([toTraditional]) or Simplified characters (formulas are left as they are); one undo step. How many cells
     * changed; -1 when the device cannot convert.
     */
    fun convertChinese(toTraditional: Boolean, vocabulary: Boolean = true): Int {
        if (!com.therealityteam.aeropages.model.ChineseConversion.available) return -1
        commitEntry()
        val single = rangeRowMin() == rangeRowMax() && rangeColMin() == rangeColMax()
        val changes = sheet.cells.entries.mapNotNull { (k, c) ->
            val r = (k / 1000L).toInt(); val col = (k % 1000L).toInt()
            if (!single && (r !in rangeRowMin()..rangeRowMax() || col !in rangeColMin()..rangeColMax())) return@mapNotNull null
            if (c.text.startsWith("=") || isLocked(r, col)) return@mapNotNull null
            val t = com.therealityteam.aeropages.model.ChineseConversion.convert(c.text, toTraditional, vocabulary)
            if (t != c.text) k to t else null
        }
        if (changes.isEmpty()) return 0
        pushUndo(CellKeysDelta.capture(sheetIndex, sheet, changes.map { it.first }))
        changes.forEach { (k, t) -> sheet.cells[k]?.text = t }
        touchContent()
        return changes.size
    }

    // --- find & replace (Excel's) -----------------------------------------------------------

    /** What a cell shows on [sheetIdx] (its value as formatted; this sheet's as the grid shows it). */
    private fun shownOn(sheetIdx: Int, row: Int, col: Int): String {
        if (sheetIdx == sheetIndex && !showFormulas) return display(row, col)
        val s = workbook.sheets[sheetIdx]
        val cell = s.cell(row, col) ?: return ""
        val base = FormulaEngine.display(s, row, col, workbook.sheets, workbook.names, memo)
        return NumberFormatting.shown(base, cell, symbols, dates).text
    }

    /** The text [o] looks in: what is typed, what is shown, or the comment. */
    private fun lookText(sheetIdx: Int, row: Int, col: Int, cell: com.therealityteam.aeropages.model.Cell, o: SheetFindOptions): String = when (o.lookIn) {
        LookIn.FORMULAS -> cell.text
        LookIn.VALUES -> shownOn(sheetIdx, row, col)
        LookIn.COMMENTS -> cell.comment.orEmpty()
    }

    /**
     * Find All: every cell holding [query] as [o] says — this sheet or every sheet shown (in their order), by rows or by
     * columns; nothing for an empty query.
     */
    fun findCells(query: String, o: SheetFindOptions): List<CellHit> {
        val regex = SheetSearch.pattern(query, o) ?: return emptyList()
        val sheets = if (o.workbook) workbook.sheets.indices.filter { !workbook.sheets[it].hidden } else listOf(sheetIndex)
        val out = ArrayList<CellHit>()
        for (si in sheets) {
            val s = workbook.sheets[si]
            val keys = s.cells.keys.toMutableList()
            keys.sortWith(if (o.byColumns) compareBy<Long>({ it % 1000L }, { it / 1000L }) else compareBy { it })
            for (k in keys) {
                val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
                val cell = s.cells[k] ?: continue
                if (SheetSearch.matches(regex, lookText(si, r, c, cell, o))) out.add(CellHit(si, r, c))
            }
        }
        return out
    }

    /** Selects [hit]'s cell — on its sheet. */
    fun showCell(hit: CellHit) {
        if (hit.sheet != sheetIndex) selectSheet(hit.sheet)
        select(hit.row, hit.col)
    }

    /**
     * Find Next (or, [back], Previous): the cell after the active one (before it) in [o]'s order, round to the first
     * (last) — on another sheet too when searching the workbook; selected. Its place among all, or null when none.
     */
    fun findStep(query: String, o: SheetFindOptions, back: Boolean = false): Pair<Int, Int>? {
        val hits = findCells(query, o)
        if (hits.isEmpty()) return null
        val sheets = hits.map { it.sheet }.distinct()
        fun order(h: CellHit): Long {
            val inSheet = if (o.byColumns) h.col.toLong() * 10_000_000L + h.row else h.row.toLong() * 1000L + h.col
            return sheets.indexOf(h.sheet).toLong() * 100_000_000_000L + inSheet
        }
        val here = order(CellHit(sheetIndex, selRow, selCol)).let { if (sheetIndex !in sheets) (if (back) Long.MAX_VALUE else -1L) else it }
        val i = if (!back) hits.indexOfFirst { order(it) > here }.takeIf { it >= 0 } ?: 0
            else hits.indexOfLast { order(it) < here }.takeIf { it >= 0 } ?: hits.lastIndex
        showCell(hits[i])
        return i to hits.size
    }

    /** What [regex] makes of [text] with [replacement] put in for each match (the whole of it, matching entire cells). */
    private fun replaced(text: String, regex: Regex, replacement: String): String = regex.replace(text) { replacement }

    /**
     * Replace: the active cell, when it holds [query] (in what is typed — Excel replaces there only), has every match
     * replaced; then the next is found. Where Find went next, as [findStep].
     */
    fun replaceStep(query: String, replacement: String, o: SheetFindOptions): Pair<Int, Int>? {
        val f = o.copy(lookIn = LookIn.FORMULAS)
        val regex = SheetSearch.pattern(query, f) ?: return null
        val cell = sheet.cell(selRow, selCol)
        if (cell != null && SheetSearch.matches(regex, cell.text) && !isLocked(selRow, selCol)) {
            commitEntry()
            pushUndo(CellKeysDelta.capture(sheetIndex, sheet, listOf(sheet.key(selRow, selCol))))
            cell.text = replaced(cell.text, regex, replacement)
            touchContent()
        }
        return findStep(query, f)
    }

    /** Replace All: every cell [o] finds (in what is typed), on every sheet searched — one undo step; how many replacements. */
    fun replaceAllCells(query: String, replacement: String, o: SheetFindOptions): Int {
        val f = o.copy(lookIn = LookIn.FORMULAS)
        val regex = SheetSearch.pattern(query, f) ?: return 0
        val hits = findCells(query, f).filterNot { h -> workbook.sheets[h.sheet].let { it.protected && it.cell(h.row, h.col)?.unlocked != true } }
        if (hits.isEmpty()) return 0
        commitEntry()
        val deltas = hits.groupBy { it.sheet }.map { (si, hs) ->
            CellKeysDelta.capture(si, workbook.sheets[si], hs.map { workbook.sheets[si].key(it.row, it.col) })
        }
        pushUndo(if (deltas.size == 1) deltas[0] else CompositeDelta(deltas))
        var count = 0
        for (h in hits) {
            val cell = workbook.sheets[h.sheet].cell(h.row, h.col) ?: continue
            count += regex.findAll(cell.text).count()
            cell.text = replaced(cell.text, regex, replacement)
        }
        touchContent()
        return count
    }

    // The old calls (this sheet, case kept, anywhere in what is typed), for their callers.
    fun replaceAll(find: String, replace: String): Int = replaceAllCells(find.replace("~", "~~").replace("*", "~*").replace("?", "~?"), replace, SheetFindOptions(matchCase = true))
    /** Index of the next cell (row,col) after the selection containing [find] (row-major); null if none. */
    fun findNext(find: String): Pair<Int, Int>? {
        val hits = findCells(find.replace("~", "~~").replace("*", "~*").replace("?", "~?"), SheetFindOptions(matchCase = true))
        val here = selRow.toLong() * 1000 + selCol
        val next = hits.firstOrNull { it.row.toLong() * 1000 + it.col > here } ?: hits.firstOrNull()
        return next?.let { it.row to it.col }
    }

    // --- proofing (校对) --------------------------------------------------------------

    /** A cell to proof: [row], [col] of sheet [sheet]. */
    class ProofTarget(val sheet: Int, val row: Int, val col: Int)

    /** The cells of words — not numbers, not formulas — of every sheet shown, sheet by sheet, row by row. */
    fun proofTargets(): List<ProofTarget> = buildList {
        workbook.sheets.forEachIndexed { i, s ->
            if (s.hidden) return@forEachIndexed
            s.cells.entries.filter { (_, c) -> !c.text.startsWith("=") && c.text.any { it.isLetter() } && c.storedNumber() == null }
                .map { it.key }.sorted().forEach { k -> add(ProofTarget(i, (k / 1000L).toInt(), (k % 1000L).toInt())) }
        }
    }

    /** What [t] says now. */
    fun proofText(t: ProofTarget): String = workbook.sheets.getOrNull(t.sheet)?.cell(t.row, t.col)?.text.orEmpty()

    /** AeroDesk's own checks on [targets] (the spell checker's typos come on top): words written twice, double spaces. */
    fun proofread(targets: List<ProofTarget>): List<ProofIssue> = targets.flatMapIndexed { i, t -> ownProofIssues(i, proofText(t)) }

    /**
     * A spelling suggestion taken: [word] at [start] of [t]'s text becomes [replacement] — in the
     * word's own look in a cell of mixed formatting — one undo step. False when the text there has
     * changed since it was checked.
     */
    fun applySpelling(t: ProofTarget, start: Int, word: String, replacement: String): Boolean {
        val s = workbook.sheets.getOrNull(t.sheet) ?: return false
        val c = s.cell(t.row, t.col) ?: return false
        val text = c.text
        if (!wordAt(text, start, word)) return false
        pushUndo(CellKeysDelta.capture(t.sheet, s, listOf(s.key(t.row, t.col))))
        val rich = c.richRuns()
        c.text = text.replaceRange(start, start + word.length, replacement)
        if (rich != null) {
            val p = com.therealityteam.aeropages.model.Paragraph(rich.map { com.therealityteam.aeropages.model.TextRun(it.text, it.format.copyOf()) }.toMutableList())
            ParagraphOps.replace(p, start, start + word.length, replacement, ParagraphOps.formatAt(p, start))
            c.runs = p.runs
        }
        touchContent()
        return true
    }

    /** Shows [t]: its sheet, the cell selected. */
    fun showProofTarget(t: ProofTarget) {
        if (t.sheet !in workbook.sheets.indices) return
        if (t.sheet != sheetIndex) selectSheet(t.sheet)
        select(t.row, t.col)
    }

    // --- remove duplicate rows (over the selected range, by selected columns) ----
    fun removeDuplicates(): Int {
        val c1 = rangeColMin(); val c2 = rangeColMax()
        return removeDuplicates(rangeRowMin(), c1, rangeRowMax(), c2, (c1..c2).toList(), header = false).first
    }

    /**
     * Excel's Remove Duplicates over rows [r1]..[r2] of columns [c1]..[c2] — its first row a header kept out when
     * [header] — comparing [cols]: a row whose values there (numbers by value, text without case, formulas by what they
     * give) match an earlier row's goes. As Excel deletes them: within the block, the rows under it moving up (the cells
     * beside the block stay), formulas following the cells that moved and a reference to a deleted one becoming #REF!.
     * One undo step; the duplicates removed and the unique rows kept.
     */
    fun removeDuplicates(r1: Int, c1: Int, r2: Int, c2: Int, cols: List<Int>, header: Boolean): Pair<Int, Int> {
        commitEntry()
        val r0 = r1 + (if (header) 1 else 0)
        val keyCols = cols.filter { it in c1..c2 }
        if (r0 > r2 || keyCols.isEmpty() || refuseIfSheetProtected()) return 0 to 0
        fun keyOf(v: Any?): String = when (v) {
            null -> ""
            is Double -> "n$v"
            is Boolean -> "b$v"
            is String -> "s" + v.lowercase()
            else -> "e$v"
        }
        val seen = HashSet<List<String>>()
        val removed = java.util.TreeSet<Int>()
        for (r in r0..r2) if (!seen.add(keyCols.map { keyOf(sortValue(r, it)) })) removed.add(r)
        if (removed.isEmpty()) return 0 to (r2 - r0 + 1)
        pushUndo(wholeSheet())
        // An Excel table's data: the table shrinks by the rows taken out, its total row moving up under the rest
        // (Excel deletes the table rows); elsewhere the rows under them in the block move up and the block's end is left empty.
        val table = sheet.tables.firstOrNull { it.c1 == c1 && it.c2 == c2 && it.dataR1 == r0 && it.dataR2 == r2 }
        val last = if (table?.totalsRow == true) table.r2 else r2
        rewriteForRemovedRows(c1, c2, r0, last, removed)
        val rows = (r0..last).associateWith { r -> (c1..c2).map { c -> sheet.cell(r, c) } }
        val kept = (r0..last).filter { it !in removed }
        table?.let { it.r2 -= removed.size }
        for ((i, r) in (r0..last).withIndex()) {
            val from = kept.getOrNull(i)
            for ((k, c) in (c1..c2).withIndex()) {
                val cell = from?.let { rows.getValue(it)[k] }
                if (cell == null) sheet.cells.remove(sheet.key(r, c)) else sheet.cells[sheet.key(r, c)] = cell
            }
        }
        displayCache.clear(); memo.clear()
        touchContent()
        return removed.size to (r2 - r0 + 1 - removed.size)
    }

    /**
     * Every formula's references after rows [removed] of columns [c1]..[c2] were deleted and the rows under them in
     * [r0]..[r2] moved up: a cell that moved, its new place; a deleted one, #REF!; a range within those columns, its ends so.
     */
    private fun rewriteForRemovedRows(c1: Int, c2: Int, r0: Int, r2: Int, removed: java.util.SortedSet<Int>) {
        val targetName = sheet.name
        fun before(r: Int) = removed.headSet(r).size
        fun mapped(m: MatchResult): String {
            val g = m.groupValues
            val isRange = g[7].isNotEmpty()
            val ca = colIndexOf(g[3]); var ra = g[5].toInt() - 1
            val cb = if (isRange) colIndexOf(g[7]) else ca
            var rb = if (isRange) g[9].toInt() - 1 else ra
            if (ca < c1 || cb > c2 || maxOf(ra, rb) < r0 || minOf(ra, rb) > r2) return m.value
            if (!isRange) {
                if (ra in removed) return "#REF!"
                ra -= before(ra)
            } else {
                val lo = minOf(ra, rb); val hi = maxOf(ra, rb)
                val newLo = lo - before(lo)
                val newHi = hi - before(hi + 1)
                if (newHi < newLo) return "#REF!"
                ra = newLo; rb = newHi
            }
            val head = "${g[1]}${g[2]}${Workbook.colLabel(ca)}${g[4]}${ra + 1}"
            return if (isRange) "$head:${g[6]}${Workbook.colLabel(cb)}${g[8]}${rb + 1}" else head
        }
        fun rewritten(t: String, own: Boolean): String = com.therealityteam.aeropages.model.StructuredRefs.outsideLiterals(t) { code ->
            a1RefRx.replace(code) { m ->
                val prefix = m.groupValues[1]
                val applies = if (prefix.isEmpty()) own else prefix.removeSuffix("!").trim('\'').equals(targetName, true)
                if (applies) mapped(m) else m.value
            }
        }
        workbook.sheets.forEach { sh ->
            val own = sh === sheet
            sh.cells.values.forEach { cell -> if (cell.text.startsWith("=")) cell.text = rewritten(cell.text, own) }
        }
        for ((k, v) in workbook.names.entries.toList()) workbook.names[k] = rewritten(v, own = false)
    }

    // --- pivot table (数据透视表) -------------------------------------------------

    /** Header labels of the selected range's first row, for the pivot field pickers. */
    fun selectionHeaders(): List<String> {
        val r1 = rangeRowMin(); val c1 = rangeColMin(); val c2 = rangeColMax()
        return (c1..c2).map { c -> display(r1, c).ifBlank { Workbook.colLabel(c) } }
    }

    /**
     * Build a pivot summary of the selected range onto a new sheet. The selection's
     * first row is the field header row; [rowField] / [colField] / [valueField] are
     * 0-based column offsets within the selection ([colField] null = no column split).
     * Returns the new sheet's name, or null when the selection has no data rows.
     */
    fun buildPivot(rowField: Int, colField: Int?, valueField: Int, agg: PivotAgg): String? {
        val r1 = rangeRowMin(); val r2 = rangeRowMax(); val c1 = rangeColMin(); val c2 = rangeColMax()
        val fieldCount = c2 - c1 + 1
        if (r2 - r1 < 1) return null
        if (rowField !in 0 until fieldCount || valueField !in 0 until fieldCount) return null
        if (colField != null && colField !in 0 until fieldCount) return null

        // Group data rows by (row key, column key); each group keeps its value list.
        // COUNT tallies non-blank value cells; the rest aggregate numeric values only.
        val groups = LinkedHashMap<String, LinkedHashMap<String, MutableList<Double>>>()
        val colKeySet = LinkedHashSet<String>()
        for (r in (r1 + 1)..r2) {
            val valueText = rawValue(r, c1 + valueField)
            val blank = Texts.get(TextId.PIVOT_BLANK)
            val rowKey = display(r, c1 + rowField).ifBlank { blank }
            val colKey = colField?.let { display(r, c1 + it).ifBlank { blank } } ?: ""
            val list = groups.getOrPut(rowKey) { LinkedHashMap() }.getOrPut(colKey) { mutableListOf() }
            if (agg == PivotAgg.COUNT) { if (valueText.isNotBlank()) list.add(1.0) }
            else valueText.toDoubleOrNull()?.let { list.add(it) }
            colKeySet.add(colKey)
        }
        if (groups.isEmpty()) return null

        fun aggOf(values: List<Double>): Double? = when {
            values.isEmpty() -> null
            agg == PivotAgg.SUM -> values.sum()
            agg == PivotAgg.COUNT -> values.size.toDouble()
            agg == PivotAgg.AVERAGE -> values.average()
            agg == PivotAgg.MAX -> values.max()
            else -> values.min()
        }
        // Numeric-aware key order: numbers ascending first, then text.
        val keyOrder = compareBy<String>({ it.toDoubleOrNull() == null }, { it.toDoubleOrNull() ?: 0.0 }, { it })
        val rowKeys = groups.keys.sortedWith(keyOrder)
        val colKeys = colKeySet.sortedWith(keyOrder)
        fun fmt(v: Double): String =
            if (kotlin.math.abs(v - Math.round(v)) < 1e-9) Math.round(v).toString()
            else (Math.round(v * 100.0) / 100.0).toString()

        val headers = selectionHeaders()
        var n = 1
        while (workbook.sheets.any { it.name == Texts.get(TextId.PIVOT_SHEET_N, n) }) n++
        val pivotName = Texts.get(TextId.PIVOT_SHEET_N, n)
        pushUndo(SheetSlotDelta(workbook.sheets.size, null))
        val out = Sheet(name = pivotName,
            rowCount = maxOf(40, rowKeys.size + 4), colCount = maxOf(16, colKeys.size + 4))
        fun put(r: Int, c: Int, text: String, bold: Boolean = false, right: Boolean = false) {
            val cell = out.ensure(r, c); cell.text = text
            if (bold) cell.format.bold = true
            if (right) cell.align = com.therealityteam.aeropages.model.TextAlign.RIGHT
        }
        // Header row.
        put(0, 0, headers.getOrElse(rowField) { "" }, bold = true)
        if (colField != null) {
            colKeys.forEachIndexed { j, ck -> put(0, 1 + j, ck, bold = true, right = true) }
            put(0, 1 + colKeys.size, Texts.get(TextId.TOTAL), bold = true, right = true)
        } else {
            put(0, 1, "${agg.display}:${headers.getOrElse(valueField) { "" }}", bold = true, right = true)
        }
        // Data rows + per-row totals.
        rowKeys.forEachIndexed { i, rk ->
            put(1 + i, 0, rk)
            val byCol = groups[rk] ?: emptyMap()
            if (colField != null) {
                val rowAll = ArrayList<Double>()
                colKeys.forEachIndexed { j, ck ->
                    val vals = byCol[ck] ?: emptyList()
                    aggOf(vals)?.let { put(1 + i, 1 + j, fmt(it), right = true) }
                    rowAll.addAll(vals)
                }
                aggOf(rowAll)?.let { put(1 + i, 1 + colKeys.size, fmt(it), bold = true, right = true) }
            } else {
                aggOf(byCol[""] ?: emptyList())?.let { put(1 + i, 1, fmt(it), right = true) }
            }
        }
        // Grand-total row.
        val totalRow = 1 + rowKeys.size
        put(totalRow, 0, Texts.get(TextId.TOTAL), bold = true)
        if (colField != null) {
            colKeys.forEachIndexed { j, ck ->
                val vals = rowKeys.flatMap { groups[it]?.get(ck) ?: emptyList() }
                aggOf(vals)?.let { put(totalRow, 1 + j, fmt(it), bold = true, right = true) }
            }
            val all = rowKeys.flatMap { rk -> colKeys.flatMap { groups[rk]?.get(it) ?: emptyList() } }
            aggOf(all)?.let { put(totalRow, 1 + colKeys.size, fmt(it), bold = true, right = true) }
        } else {
            val all = rowKeys.flatMap { groups[it]?.get("") ?: emptyList() }
            aggOf(all)?.let { put(totalRow, 1, fmt(it), bold = true, right = true) }
        }
        workbook.sheets.add(out)
        sheetIndex = workbook.sheets.lastIndex
        selRow = 0; selCol = 0; selRow2 = 0; selCol2 = 0
        touchContent()
        return pivotName
    }

    // --- selection statistics (状态栏聚合: 求和/平均/计数) ---------------------------

    /**
     * Excel's status bar figures for the selection ([SelectionStats]); null for a single cell or fewer than two
     * cells holding anything — Excel shows nothing then. Whole columns are cheap: only the cells there are counted.
     */
    fun selectionStats(): SelectionStats? {
        val areas = selectedAreas()
        if (areas.size == 1 && areas[0][0] == areas[0][2] && areas[0][1] == areas[0][3]) return null
        fun keysIn(a: IntArray): Sequence<Long> {
            val (r1, c1, r2, c2) = a.toList()
            return if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1).toLong() > sheet.cells.size)
                sheet.cells.keys.asSequence().filter { k -> (k / 1000L).toInt() in r1..r2 && (k % 1000L).toInt() in c1..c2 }.sorted()
            else sequence { for (r in r1..r2) for (c in c1..c2) yield(r * 1000L + c) }
        }
        // Several areas: each cell counted once, however the areas overlap.
        val keys: Sequence<Long> = if (areas.size == 1) keysIn(areas[0]) else areas.asSequence().flatMap { keysIn(it) }.distinct()
        var count = 0; var numbers = 0; var sum = 0.0
        var min = Double.POSITIVE_INFINITY; var max = Double.NEGATIVE_INFINITY
        var format: com.therealityteam.aeropages.model.Cell? = null
        for (k in keys) {
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            val cell = sheet.cell(r, c) ?: continue
            if (cell.text.isBlank()) continue
            count++
            // Numbers are what Excel's COUNT counts: typed numbers (dates and times are numbers), formulas that come to one;
            // not text, TRUE / FALSE or errors, nor a number kept as text.
            val n = if (cell.text.startsWith("=")) (FormulaEngine.cached(sheet, r, c, workbook.sheets, workbook.names, memo) as? com.therealityteam.aeropages.model.CachedValue.Number)?.value
                else if (cell.formatCode == "@") null else cell.text.toDoubleOrNull()?.takeIf { it.isFinite() }
            if (n != null) {
                numbers++; sum += n; min = minOf(min, n); max = maxOf(max, n)
                if (format == null) format = cell
            }
        }
        if (count < 2) return null
        return SelectionStats(count, numbers, sum, if (numbers > 0) min else 0.0, if (numbers > 0) max else 0.0, format)
    }

    /** The formula bar's text selection inside the active cell (start..end, exclusive); formatting applies to it when it is a real range. */
    var textSel by mutableStateOf<IntRange?>(null)
        private set
    fun setTextSelection(start: Int, end: Int) { textSel = if (end > start) start until end else null }

    /** The formula bar's selection when it is part of the active cell's literal text (formatting then applies to just those characters). */
    private fun partialTextSel(): IntRange? {
        val sel = textSel ?: return null
        val text = sheet.cell(selRow, selCol)?.text ?: return null
        // Characters of a formula or a number cannot be formatted apart (Excel's rule too).
        if (text.startsWith("=") || text.toDoubleOrNull() != null || sel.first < 0 || sel.last + 1 > text.length || (sel.first == 0 && sel.last + 1 == text.length)) return null
        return sel
    }

    /**
     * Character formatting: a partial selection in the formula bar formats just those characters
     * (rich runs); otherwise every cell of the selection takes it as one — one undo step, as Excel
     * formats a selected block. Refused on a protected sheet.
     */
    private fun applyFmt(change: (CharacterFormat) -> Unit) {
        if (spread { applyFmt(change) }) return
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        partialTextSel()?.let { sel -> formatCellRange(selRow, selCol, sel.first, sel.last + 1, change); return }
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) {
            val cell = sheet.ensure(r, c)
            change(cell.format)
            cell.richRuns()?.forEach { change(it.format) }   // keep a mixed cell mixed, but apply the change everywhere
        }
        touchContent()
    }

    /** Whether [flag] holds for what formatting applies to: the selected characters of the formula bar, else the active cell. */
    private fun formatIsOn(flag: (CharacterFormat) -> Boolean): Boolean {
        val cell = sheet.cell(selRow, selCol) ?: return false
        val sel = partialTextSel() ?: return flag(cell.format)
        val runs = cell.richRuns() ?: return flag(cell.format)
        var pos = 0
        return runs.all { run ->
            val start = pos
            pos += run.text.length
            pos <= sel.first || start > sel.last || flag(run.format)
        }
    }

    /** Formats characters [start] until [end] of a literal cell, turning it into rich runs (or back into one format when they agree). */
    fun formatCellRange(row: Int, col: Int, start: Int, end: Int, change: (CharacterFormat) -> Unit) {
        val cell = sheet.cell(row, col) ?: return
        val len = cell.text.length
        val a = start.coerceIn(0, len); val b = end.coerceIn(0, len)
        if (cell.text.startsWith("=") || a >= b) return
        pushUndo(cells(row, col, row, col))
        val runs = cell.richRuns()?.map { com.therealityteam.aeropages.model.TextRun(it.text, it.format.copyOf()) }?.toMutableList()
            ?: mutableListOf(com.therealityteam.aeropages.model.TextRun(cell.text, cell.format.copyOf()))
        val p = com.therealityteam.aeropages.model.Paragraph(runs)
        ParagraphOps.applyFormat(p, a, b, change)
        val merged = ArrayList<com.therealityteam.aeropages.model.TextRun>()
        for (r in p.runs) {
            if (r.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.format == r.format) merged[merged.lastIndex] = com.therealityteam.aeropages.model.TextRun(last.text + r.text, last.format)
            else merged.add(r)
        }
        if (merged.size <= 1) {
            merged.firstOrNull()?.let { cell.format.assignFrom(it.format) }
            cell.runs = null
        } else cell.runs = merged
        touchContent()
    }

    // Excel's toggles: off everywhere when the active cell (or the selected characters) has it, else on everywhere.
    fun toggleBold() { val on = !formatIsOn { it.bold }; applyFmt { it.bold = on } }
    fun toggleItalic() { val on = !formatIsOn { it.italic }; applyFmt { it.italic = on } }
    fun toggleUnderline() { val on = !formatIsOn { it.underline }; applyFmt { it.underline = on } }
    fun setColor(color: Long?) = applyFmt { it.color = color }
    fun setHighlight(color: Long?) = applyFmt { it.highlight = color }
    fun setFontSize(pt: Float) = applyFmt { it.fontSizePt = pt }
    fun setFontFamily(name: String) = applyFmt { it.fontFamily = name }
    val activeNumberFormat: com.therealityteam.aeropages.model.NumberFormat
        get() = sheet.cell(selRow, selCol)?.numberFormat ?: com.therealityteam.aeropages.model.NumberFormat.GENERAL

    /** The active cell's horizontal alignment; null = General. */
    val activeAlign: com.therealityteam.aeropages.model.TextAlign? get() = sheet.cell(selRow, selCol)?.align

    /**
     * Aligns the selection, one undo step: [align] on every cell — or back to General (null) when
     * the active cell already has it, as Excel's alignment buttons toggle. Refused on a protected sheet.
     */
    fun setAlign(align: com.therealityteam.aeropages.model.TextAlign?) {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        alignAll(if (align != null && activeAlign == align) null else align)
    }

    private fun alignAll(target: com.therealityteam.aeropages.model.TextAlign?) {
        if (spread { alignAll(target) }) return
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) {
            if (target == null) sheet.cell(r, c)?.let { it.align = null; it.spread = null } else sheet.ensure(r, c).let { it.align = target; it.spread = null }
        }
        touchContent()
    }

    val currentFormat: CharacterFormat get() = sheet.cell(selRow, selCol)?.format ?: CharacterFormat()

    // --- number format ----------------------------------------------------------

    fun setNumberFormat(fmt: com.therealityteam.aeropages.model.NumberFormat) {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        commitEntry()
        if (spread { setNumberFormat(fmt) }) return
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) sheet.ensure(r, c).apply { numberFormat = fmt; formatCode = null }
        touchContent()
    }

    /** The active cell's format code (Format Cells → Custom), or null when it uses a plain number format. */
    val activeFormatCode: String? get() = sheet.cell(selRow, selCol)?.formatCode

    /**
     * Format Cells → Custom: the Excel format [code] on the selection, one undo step; its kind (a
     * date, a percentage …) goes in [com.therealityteam.aeropages.model.Cell.numberFormat] for
     * what reads that. A code that cannot be read is refused (false).
     */
    fun setFormatCode(code: String): Boolean {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return false
        commitEntry()
        if (!com.therealityteam.aeropages.model.FormatCode.isValid(code)) return false
        if (spread { setFormatCode(code) }) return true
        val kind = com.therealityteam.aeropages.model.FormatCode.category(code)
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) sheet.ensure(r, c).apply {
            formatCode = code; numberFormat = kind; decimals = -1
        }
        touchContent()
        return true
    }

    /** Displayed value of a cell: evaluated formula / raw text, then a number format applied. */
    /** When true, cells show their raw formula text instead of the evaluated result (公式→显示公式). */
    var showFormulas by mutableStateOf(false)
        private set

    /**
     * The reader's decimal and grouping symbols (the app's language; Excel takes the system's): what cells show and
     * what typing reads. Files, CSV and formulas keep the standard . and , whatever these are.
     */
    var symbols: com.therealityteam.aeropages.model.NumberSymbols = com.therealityteam.aeropages.model.NumberSymbols.current()
        set(v) { field = v; displayCache.clear() }

    /** The language whose short date the plain Date format shows (Excel's built-in date follows the system); null: ISO. */
    var dates: com.therealityteam.aeropages.model.FormatCode.Lang? = com.therealityteam.aeropages.model.FormatCode.Lang.current()
        set(v) { field = v; displayCache.clear() }

    /** What a cell shows in the standard writing (1234.5, 1,234.50) — for CSV and anything that reads numbers back. */
    fun displayStandard(row: Int, col: Int): String {
        if (symbols.canonical && dates == null) return display(row, col)
        val base = FormulaEngine.display(sheet, row, col, workbook.sheets, workbook.names, memo)
        return NumberFormatting.shown(base, sheet.cell(row, col)).text
    }
    fun toggleShowFormulas() { showFormulas = !showFormulas; bump() }

    // Evaluated-value caches: the formatted display string per cell plus the engine memo of raw
    // values, which records each formula's precedents. Edits evict only what depends on the
    // changed cells ([applyInvalidation]); structural edits clear everything. Selection changes
    // never invalidate (arrow keys used to re-evaluate every visible cell).
    private val displayCache = HashMap<Long, Displayed>()

    /** A cell as the grid shows it: the formatted text (with its format code's colour) and where General alignment puts it. */
    private class Displayed(val shown: com.therealityteam.aeropages.model.FormatCode.Shown, val general: com.therealityteam.aeropages.model.TextAlign)
    private val memo = FormulaEngine.Memo()

    /**
     * The evaluated value WITHOUT the cell's number format — what numeric consumers (rules,
     * data bars, charts, pivots, statistics) must read; [display] is for the eye only.
     */
    fun rawValue(row: Int, col: Int): String = FormulaEngine.display(sheet, row, col, workbook.sheets, workbook.names, memo)

    /**
     * Excel's AutoSum range for the active cell: the run of numbers directly above it (up to the first cell
     * that is not one — a header, a blank), else the run directly to its left; null when neither is there.
     */
    fun autoSumRange(row: Int = selRow, col: Int = selCol): String? {
        fun number(r: Int, c: Int) = r >= 0 && c >= 0 && sheet.cell(r, c)?.text?.isNotBlank() == true && rawValue(r, c).toDoubleOrNull() != null
        if (number(row - 1, col)) {
            var top = row - 1
            while (number(top - 1, col)) top--
            return A1.rangeRef(top, col, row - 1, col)
        }
        if (number(row, col - 1)) {
            var left = col - 1
            while (number(row, left - 1)) left--
            return A1.rangeRef(row, left, row, col - 1)
        }
        return null
    }

    /**
     * Formulas → AutoSum (Sum, Average, Count, Max, Min): `=FN(range)` over [autoSumRange] in the active cell, or —
     * with no numbers beside it — `=FN(` left open in the formula bar for the range to be typed. The range used, or null.
     */
    fun autoFunction(fn: String): String? {
        commitEntry()
        val range = autoSumRange()
        if (range != null) setCell(selRow, selCol, "=$fn($range)") else typeCell(selRow, selCol, "=$fn(")
        return range
    }

    /** [rawValue] as a number, or null for blank / text / error cells. */
    fun numericValue(row: Int, col: Int): Double? = rawValue(row, col).toDoubleOrNull()

    fun display(row: Int, col: Int): String = displayed(row, col).shown.text

    /**
     * Excel's Error Checking on this sheet: the formula cells whose value is an error, row by row (what the dialog steps
     * through), each with that error (#DIV/0!, #NAME?…).
     */
    fun formulaErrors(): List<Triple<Int, Int, String>> = sheet.cells.entries
        .filter { (_, c) -> c.text.startsWith("=") }
        .mapNotNull { (k, _) ->
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            display(r, c).takeIf { FormulaEngine.isError(it) }?.let { Triple(r, c, it) }
        }
        .sortedWith(compareBy({ it.first }, { it.second }))

    /** The colour a format code's section gives the cell's value (`[Red]` …), or null for the cell's own. */
    fun displayColor(row: Int, col: Int): Long? = if (showFormulas) null else displayed(row, col).shown.color

    /** Where the cell's value sits: its own alignment, or General's (numbers right, text left, TRUE / FALSE and errors centred). */
    fun displayAlign(row: Int, col: Int): com.therealityteam.aeropages.model.TextAlign =
        sheet.cell(row, col)?.align ?: displayed(row, col).general

    /**
     * [display] fitted to a cell [widthDp] wide, as Excel fits a number that does not: a General
     * number loses decimals and then goes to E notation, a formatted one (an amount, a date …) shows
     * #### — never a cut-off value that reads as another number. Text stays as it is (it runs on
     * over empty cells or is cut).
     */
    fun displayFitted(row: Int, col: Int, widthDp: Float): String {
        val d = displayed(row, col)
        val text = d.shown.text
        if (text.isEmpty() || d.general != com.therealityteam.aeropages.model.TextAlign.RIGHT) return text
        val cell = sheet.cell(row, col)
        val size = cell?.format?.fontSizePt ?: FIT_BASE_PT
        val room = widthDp - CELL_PAD_DP
        if (fitWidthDp(text, size) <= room) return text
        val general = cell == null || (cell.formatCode == null && cell.numberFormat == com.therealityteam.aeropages.model.NumberFormat.GENERAL)
        val v = if (general) rawValue(row, col).toDoubleOrNull() else null
        return v?.let { NumberFormatting.fitGeneral(it, symbols) { s -> fitWidthDp(s, size) <= room } }
            ?: "#".repeat((room / (0.55f * size)).toInt().coerceAtLeast(1))
    }

    /**
     * Whether the cell's value may run on over empty cells to its right, as Excel draws long text:
     * text (not a number, TRUE / FALSE or an error) that is left-aligned — by General or by hand —
     * in a cell that neither wraps nor is merged.
     */
    /** Whether [row], [col] holds right-aligned text that may run on over empty cells to its left (as Excel draws it). */
    fun overflowsLeft(row: Int, col: Int): Boolean {
        val d = displayed(row, col)
        if (d.shown.text.isEmpty() || d.general != com.therealityteam.aeropages.model.TextAlign.LEFT) return false
        val cell = sheet.cell(row, col) ?: return false
        if (cell.wrap || cell.turned || cell.spread != null || cell.shrink || sheet.mergeAt(row, col) != null) return false
        return cell.align == com.therealityteam.aeropages.model.TextAlign.RIGHT
    }

    /** Whether ([row], [col]) holds text set to Center Across Selection. */
    fun centersAcross(row: Int, col: Int): Boolean =
        sheet.cell(row, col)?.spread == com.therealityteam.aeropages.model.CellSpread.CENTER_ACROSS && sheet.mergeAt(row, col) == null && display(row, col).isNotEmpty()

    /** Whether ([row], [col]) carries a Center Across Selection on: empty, unmerged and set the same way, as Excel requires. */
    fun acrossContinues(row: Int, col: Int): Boolean =
        sheet.cell(row, col)?.spread == com.therealityteam.aeropages.model.CellSpread.CENTER_ACROSS && sheet.mergeAt(row, col) == null && display(row, col).isEmpty()

    fun overflows(row: Int, col: Int): Boolean {
        val d = displayed(row, col)
        if (d.shown.text.isEmpty() || d.general != com.therealityteam.aeropages.model.TextAlign.LEFT) return false
        val cell = sheet.cell(row, col)
        if (cell?.wrap == true || cell?.turned == true || cell?.spread != null || cell?.shrink == true || sheet.mergeAt(row, col) != null) return false
        return (cell?.align ?: com.therealityteam.aeropages.model.TextAlign.LEFT) == com.therealityteam.aeropages.model.TextAlign.LEFT
    }

    private fun displayed(row: Int, col: Int): Displayed {
        if (showFormulas) {
            val cell = sheet.cell(row, col)
            val text = (cell?.text ?: "").let { if (it.startsWith("=")) FormulaLocale.toLocal(it, symbols) else it }
            return Displayed(com.therealityteam.aeropages.model.FormatCode.Shown(text), NumberFormatting.generalAlign(text, cell))
        }
        val key = FormulaEngine.compositeKey(sheetIndex, row, col)
        displayCache[key]?.let { return it }
        val base = FormulaEngine.display(sheet, row, col, workbook.sheets, workbook.names, memo)
        val cell = sheet.cell(row, col)
        val out = Displayed(NumberFormatting.shown(base, cell, symbols, dates), NumberFormatting.generalAlign(base, cell))
        displayCache[key] = out
        return out
    }

    /** Set the decimal-places override on the selection (-1 restores the format default). */
    fun setDecimals(n: Int) {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        commitEntry()
        if (spread { setDecimals(n) }) return
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) {
            sheet.ensure(r, c).decimals = n.coerceIn(-1, 8)
        }
        touchContent()
    }

    /** 增加/减少小数位 (Excel .00 ←→). Starts from the active cell's effective places. */
    fun adjustDecimals(delta: Int) {
        if (refuseIfSheetProtected(Allow.FORMAT_CELLS)) return
        commitEntry()
        val cell = sheet.cell(selRow, selCol)
        val fmt = cell?.numberFormat ?: com.therealityteam.aeropages.model.NumberFormat.GENERAL
        val def = when (fmt) {
            com.therealityteam.aeropages.model.NumberFormat.THOUSANDS -> 0
            com.therealityteam.aeropages.model.NumberFormat.PERCENT -> 1
            else -> 2
        }
        stepDecimals(delta, cell?.decimals?.takeIf { it >= 0 } ?: def)
    }

    /** Every selected cell [delta] places from [cur] (what the active cell shows) — a custom code changed inside itself. */
    private fun stepDecimals(delta: Int, cur: Int) {
        if (spread { stepDecimals(delta, cur) }) return
        pushUndo(selectionCells())
        for (r in rangeRowMin()..rangeRowMax()) for (c in rangeColMin()..rangeColMax()) {
            val target = sheet.ensure(r, c)
            // A custom code gains or loses a place inside the code itself (0.0,,"M" → 0.00,,"M").
            val code = target.formatCode
            if (code != null) { target.formatCode = com.therealityteam.aeropages.model.FormatCode.withDecimals(code, delta); continue }
            if (target.numberFormat == com.therealityteam.aeropages.model.NumberFormat.GENERAL) {
                target.numberFormat = com.therealityteam.aeropages.model.NumberFormat.NUMBER2
            }
            target.decimals = (cur + delta).coerceIn(0, 8)
        }
        touchContent()
    }

    // --- sort / filter ----------------------------------------------------------

    /** Sort the rows within the selection by its leftmost selected column. */
    /**
     * Sort A→Z / Z→A, as Excel's quick sort: the rows of [sortArea] by the active cell's column, a first
     * row that names the columns staying on top ([SortArea.header]). One undo step; false when there was
     * nothing to sort (no data at the active cell, a single row).
     */
    fun sortSelection(ascending: Boolean): Boolean {
        commitEntry()
        if (refuseIfSheetProtected(Allow.SORT)) return false
        val area = sortArea() ?: return false
        val r0 = area.r1 + (if (area.header) 1 else 0)
        if (area.r2 <= r0) return false
        pushUndo(cells(r0, area.c1, area.r2, area.c2))
        sortRows(r0, area.r2, area.c1, area.c2, listOf(selCol.coerceIn(area.c1, area.c2) to ascending))
        touchContent()
        return true
    }

    /** The block a sort orders, rows [r1]..[r2] of columns [c1]..[c2]; [header] when its first row names the columns. */
    class SortArea(val r1: Int, val c1: Int, val r2: Int, val c2: Int, val header: Boolean)

    /**
     * What a sort orders, as Excel picks it: the selection — or, from one cell, the data around it (in a
     * table, the table without its totals row) — and whether its first row looks like a header
     * ([guessHeader]; a table's own says). Null when there is nothing to sort.
     */
    fun sortArea(): SortArea? {
        if (selRow == selRow2 && selCol == selCol2) {
            sheet.tableAt(selRow, selCol)?.let { t -> return SortArea(t.r1, t.c1, t.r2 - (if (t.totalsRow) 1 else 0), t.c2, t.headerRow) }
            val box = currentRegion(selRow, selCol) ?: return null
            return SortArea(box[0], box[1], box[2], box[3], guessHeader(box[0], box[1], box[2], box[3]))
        }
        val r1 = rangeRowMin(); val c1 = rangeColMin(); val r2 = rangeRowMax(); val c2 = rangeColMax()
        return SortArea(r1, c1, r2, c2, guessHeader(r1, c1, r2, c2))
    }

    /**
     * Whether the first of rows [r1]..[r2] (columns [c1]..[c2]) names the columns, as Excel guesses before
     * a sort: it holds text alone, and it looks different from the row below (bold, a fill) or tops a
     * column of numbers, dates or TRUE / FALSE.
     */
    fun guessHeader(r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        if (r2 <= r1) return false
        val top = (c1..c2).mapNotNull { c -> sheet.cell(r1, c)?.takeIf { it.text.isNotEmpty() } }
        if (top.isEmpty() || top.any { it.text.startsWith("=") || it.storedNumber() != null || it.storedLogical() != null }) return false
        val look = { c: Cell? -> c?.let { it.format.bold to it.format.highlight } ?: (false to null) }
        if ((c1..c2).any { c -> sheet.cell(r1, c)?.text?.isNotEmpty() == true && look(sheet.cell(r1, c)) != look(sheet.cell(r1 + 1, c)) }) return true
        return (c1..c2).any { c ->
            sheet.cell(r1, c)?.text?.isNotEmpty() == true && (r1 + 1..r2).any { r -> sortValue(r, c).let { it is Double || it is Boolean } }
        }
    }

    /**
     * [row], [col]'s value as a sort orders it: a number (a date, an amount — whatever its format), text,
     * TRUE / FALSE, an error ([com.therealityteam.aeropages.model.CachedValue.Error]); null for a blank.
     */
    private fun sortValue(row: Int, col: Int): Any? {
        val cell = sheet.cell(row, col) ?: return null
        if (cell.text.startsWith("=")) return when (val v = FormulaEngine.cached(sheet, row, col, workbook.sheets, workbook.names, memo)) {
            is com.therealityteam.aeropages.model.CachedValue.Number -> v.value
            is com.therealityteam.aeropages.model.CachedValue.Text -> v.value.ifEmpty { null }
            is com.therealityteam.aeropages.model.CachedValue.Logical -> v.value
            is com.therealityteam.aeropages.model.CachedValue.Error -> v
            null -> null
        }
        if (cell.text.isEmpty()) return null
        return cell.storedNumber() ?: cell.storedLogical() ?: cell.text
    }

    /**
     * Rows [r0]..[r1] of columns [c0]..[c1] put in Excel's sort order by [keys] (column, ascending): numbers,
     * then text — without case, in the language's order (Chinese by pinyin) — then FALSE and TRUE, then
     * errors; descending the other way round; blanks last either way; rows that tie keep their order.
     * Formulas move with their rows, their references to cells around them moving too (`=B2*C2` in row 5
     * is `=B5*C5`), as Excel's do.
     */
    private fun sortRows(r0: Int, r1: Int, c0: Int, c1: Int, keys: List<Pair<Int, Boolean>>) =
        sortRowsBy(r0, r1, c0, c1, keys.map { (col, asc) -> SortLevel(col, asc) })

    /** What Excel's Sort orders a level by: the cells' values, their fill, or their font's colour. */
    enum class SortOn { VALUES, CELL_COLOR, FONT_COLOR }

    /**
     * One level of Excel's Sort: column [col] by its values (smallest first when [ascending]), or — sorting on a colour —
     * the rows whose cell (or font) is [color] (null: no fill / automatic) put on top, or at the bottom when ![onTop].
     */
    data class SortLevel(val col: Int, val ascending: Boolean = true, val on: SortOn = SortOn.VALUES, val color: Long? = null, val onTop: Boolean = true,
                         /** Excel's Order ▸ Custom List: values in this list's order (reversed when descending), the others after them. */
                         val custom: List<String>? = null)

    /** [sortRows] by Excel's levels: each level's values in Excel's order, or its colour on top or at the bottom; ties go to the next level, then keep their order. */
    private fun sortRowsBy(r0: Int, r1: Int, c0: Int, c1: Int, levels: List<SortLevel>) {
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
        val values = levels.map { l -> if (l.on == SortOn.VALUES) (r0..r1).map { r -> sortValue(r, l.col) } else emptyList() }
        val matches = levels.map { l ->
            if (l.on == SortOn.VALUES) BooleanArray(0)
            else BooleanArray(r1 - r0 + 1) { i -> sheet.cell(r0 + i, l.col)?.format.let { f -> if (l.on == SortOn.CELL_COLOR) f?.highlight else f?.color } == l.color }
        }
        fun rank(v: Any): Int = when (v) { is Double -> 0; is String -> 1; is Boolean -> 2; else -> 3 }
        // A custom list's place for each entry (any case).
        val places = levels.map { l -> l.custom?.withIndex()?.associate { (i, e) -> e.lowercase() to i } }
        val order = (r0..r1).sortedWith { a, b ->
            for ((k, level) in levels.withIndex()) {
                if (level.on != SortOn.VALUES) {
                    val ma = matches[k][a - r0]; val mb = matches[k][b - r0]
                    if (ma != mb) return@sortedWith if (ma == level.onTop) -1 else 1
                    continue
                }
                val key = level.col to level.ascending
                val va = values[k][a - r0]; val vb = values[k][b - r0]
                if (va == null || vb == null) {
                    if (va == null && vb == null) continue
                    return@sortedWith if (va == null) 1 else -1
                }
                // Excel's custom list order: the list's entries in its order (or the reverse), values not in it after them.
                val place = places[k]
                if (place != null) {
                    val pa = (va as? String)?.let { place[it.trim().lowercase()] } ?: -1
                    val pb = (vb as? String)?.let { place[it.trim().lowercase()] } ?: -1
                    if (pa >= 0 || pb >= 0) {
                        if (pa < 0) return@sortedWith 1
                        if (pb < 0) return@sortedWith -1
                        if (pa != pb) return@sortedWith if (level.ascending) pa.compareTo(pb) else pb.compareTo(pa)
                        continue
                    }
                }
                val c = if (rank(va) != rank(vb)) rank(va).compareTo(rank(vb)) else when (va) {
                    is Double -> va.compareTo(vb as Double)
                    is String -> collator.compare(va, vb as String)
                    is Boolean -> va.compareTo(vb as Boolean)
                    else -> 0
                }
                if (c != 0) return@sortedWith if (key.second) c else -c
            }
            0
        }
        val rows = (r0..r1).associateWith { r -> (c0..c1).map { c -> sheet.cell(r, c)?.copyOf() } }
        order.forEachIndexed { i, from ->
            val r = r0 + i
            rows.getValue(from).forEachIndexed { k, cell ->
                val key = sheet.key(r, c0 + k)
                if (cell == null) sheet.cells.remove(key)
                else sheet.cells[key] = cell.also { if (it.text.startsWith("=") && from != r) it.text = shiftFormulaRefs(it.text, r - from, 0) }
            }
        }
        // The rows moved: what was worked out for the old places is stale.
        displayCache.clear(); memo.clear()
    }

    /** Rows hidden by the current filter (stored on the sheet, saved to xlsx as hidden rows). */
    val hiddenRows: Set<Int> get() = sheet.hiddenRows

    // --- AutoFilter ---------------------------------------------------------------------

    /** The sheet's AutoFilter (the range with filter buttons, and its criteria), if it has one. */
    val autoFilter: com.therealityteam.aeropages.model.AutoFilter? get() = sheet.autoFilter

    /** The block an AutoFilter covers when it comes on: the selection, or from one cell the data around it. */
    private fun filterBox(): IntArray? =
        if (selRow == selRow2 && selCol == selCol2) currentRegion(selRow, selCol)
        else intArrayOf(rangeRowMin(), rangeColMin(), rangeRowMax(), rangeColMax())

    /**
     * Data → Filter (Ctrl+Shift+L): filter buttons on the first row of the selection — or, from one
     * cell, of the data around it (Excel's current region) — or, when the sheet has them, off again
     * with every row they hid shown. One undo step; false when there is no data or the sheet is protected.
     */
    fun toggleAutoFilter(): Boolean {
        commitEntry()
        if (refuseIfSheetProtected()) return false
        if (sheet.autoFilter != null) {
            pushUndo(sheetMeta())
            sheet.autoFilter = null
            sheet.hiddenRows.clear()
            touchContent()
            return true
        }
        val box = filterBox() ?: return false
        pushUndo(sheetMeta())
        sheet.autoFilter = com.therealityteam.aeropages.model.AutoFilter(box[0], box[1], box[2], box[3])
        touchContent()
        return true
    }

    /** [col]'s criteria in the AutoFilter, or null when it shows every row. */
    fun columnFilter(col: Int): com.therealityteam.aeropages.model.ColumnFilter? = sheet.autoFilter?.columns?.get(col)

    /**
     * [col]'s criteria (null clears them), then the AutoFilter filters again: a data row of its
     * range shows only when every filtered column passes. Without an AutoFilter one comes on over
     * the data around the active cell. One undo step; refused on a protected sheet.
     */
    fun setColumnFilter(col: Int, filter: com.therealityteam.aeropages.model.ColumnFilter?) {
        commitEntry()
        if (refuseIfSheetProtected(Allow.AUTOFILTER)) return
        pushUndo(sheetMeta())
        val af = sheet.autoFilter ?: filterBox()?.let { com.therealityteam.aeropages.model.AutoFilter(it[0], it[1], it[2], it[3]) }?.also { sheet.autoFilter = it } ?: return
        // Criteria and colours decide blanks themselves; keep them in one form (as files read them back).
        if (filter == null) af.columns.remove(col)
        else af.columns[col] = if (filter.isCriteria || filter.color != null) filter.copy(values = null, blanks = false) else filter
        refilter(af)
        touchContent()
    }

    /** Hides the AutoFilter's data rows some filtered column rejects, and shows the rest. */
    private fun refilter(af: com.therealityteam.aeropages.model.AutoFilter) {
        sheet.hiddenRows.clear()
        if (af.columns.isEmpty()) return
        val stats = filterStats(af, af.columns)
        for (r in af.r1 + 1..af.r2) if (af.columns.any { (c, f) -> !passesFilter(f, r, c, stats[c]) }) sheet.hiddenRows.add(r)
    }

    /** What each column of [filters] needing it holds (its data rows' numbers), for Top 10 and the average. */
    private fun filterStats(af: com.therealityteam.aeropages.model.AutoFilter, filters: Map<Int, com.therealityteam.aeropages.model.ColumnFilter>) =
        filters.filterValues { it.needsStats }.mapValues { (c, _) -> com.therealityteam.aeropages.model.FilterStats((af.r1 + 1..af.r2).mapNotNull { numericValue(it, c) }) }

    /** Whether row [r] passes [f] on column [c]: by what the cell shows and holds, and its fill and font colour. */
    private fun passesFilter(f: com.therealityteam.aeropages.model.ColumnFilter, r: Int, c: Int, stats: com.therealityteam.aeropages.model.FilterStats?): Boolean {
        val cell = sheet.cell(r, c)
        return f.passes(display(r, c), numericValue(r, c), stats, cell?.format?.highlight, cell?.format?.color)
    }

    /** The filters Excel's drop-down offers for a column, by what most of its cells hold. */
    enum class FilterKind { NUMBER, TEXT, DATE }

    /** Number, Text or Date Filters for [col]: whichever kind of value most of its data rows hold (text when tied or empty). */
    fun filterKind(col: Int): FilterKind {
        val rows = filterRows()
        var dates = 0; var numbers = 0; var texts = 0
        for (r in rows) {
            val cell = sheet.cell(r, col) ?: continue
            if (cell.text.isBlank()) continue
            when {
                numericValue(r, col) == null -> texts++
                cell.numberFormat == com.therealityteam.aeropages.model.NumberFormat.DATE || cell.numberFormat == com.therealityteam.aeropages.model.NumberFormat.DATE_TIME ||
                    cell.formatCode?.let { com.therealityteam.aeropages.model.FormatCode.isDate(it) } == true -> dates++
                else -> numbers++
            }
        }
        return when {
            dates > numbers && dates > texts -> FilterKind.DATE
            numbers > texts -> FilterKind.NUMBER
            else -> FilterKind.TEXT
        }
    }

    /** The fills and font colours [col]'s data rows show (null: no fill / automatic), for Filter by Color — each kind listed only when there are two or more. */
    fun filterColors(col: Int): Pair<List<Long?>, List<Long?>> {
        val fills = LinkedHashSet<Long?>(); val fonts = LinkedHashSet<Long?>()
        for (r in filterRows()) { val f = sheet.cell(r, col)?.format; fills.add(f?.highlight); fonts.add(f?.color) }
        return fills.toList().takeIf { it.size > 1 }.orEmpty() to fonts.toList().takeIf { it.size > 1 }.orEmpty()
    }

    /** The AutoFilter's data rows (or the sheet's rows under the first, when there is none yet). */
    private fun filterRows(): IntRange {
        val af = sheet.autoFilter
        return if (af != null) af.r1 + 1..af.r2 else 1..(sheet.cells.keys.maxOfOrNull { (it / 1000L).toInt() } ?: 0)
    }

    /** Data → Reapply: the same criteria again, after the data under them changed. */
    fun reapplyFilter() {
        val af = sheet.autoFilter ?: return
        pushUndo(sheetMeta())
        refilter(af)
        touchContent()
    }

    /** Data → Clear: every column's criteria cleared and every row shown; the filter buttons stay. */
    fun clearFilter() {
        val af = sheet.autoFilter
        if ((af == null || af.columns.isEmpty()) && sheet.hiddenRows.isEmpty()) return
        pushUndo(sheetMeta())
        af?.columns?.clear()
        sheet.hiddenRows.clear()
        touchContent()
    }

    /** Whether rows are filtered out (a column has criteria, or rows hidden by a filter in a file). */
    val hasFilter: Boolean get() = sheet.autoFilter?.columns?.isNotEmpty() == true || sheet.hiddenRows.isNotEmpty()

    /** Excel's right-click Filter ▸ Filter by Selected Cell's Color (or, [font], Font Color): the active column filtered by it. */
    fun filterByActiveCellColor(font: Boolean) {
        val f = sheet.cell(selRow, selCol)?.format
        setColumnFilter(selCol, com.therealityteam.aeropages.model.ColumnFilter(blanks = false,
            color = com.therealityteam.aeropages.model.ColorFilter(if (font) f?.color else f?.highlight, font)))
    }

    /**
     * Excel's right-click Sort ▸ Put Selected Cell Color (or, [font], Font Color) On Top: what a sort orders — the data
     * around the active cell — with the rows whose cell in its column has that colour first. False when there is nothing to sort.
     */
    fun putActiveColorOnTop(font: Boolean): Boolean {
        val area = sortArea() ?: return false
        val f = sheet.cell(selRow, selCol)?.format
        return sortLevels(listOf(SortLevel(selCol, on = if (font) SortOn.FONT_COLOR else SortOn.CELL_COLOR, color = if (font) f?.color else f?.highlight)), area.header)
    }

    /** Filter by the active cell's value (right-click → Filter → By Selected Cell's Value). */
    fun filterByActiveCell() {
        val target = display(selRow, selCol)
        setColumnFilter(selCol, com.therealityteam.aeropages.model.ColumnFilter(values = setOf(target), blanks = target.isEmpty()))
    }

    /**
     * The values a filter button lists for [col]: what the data rows show, as Excel lists them —
     * numbers by value, then text — counting only rows the other columns' criteria let through,
     * and whether any of those rows is blank.
     */
    fun filterValues(col: Int): Pair<List<String>, Boolean> {
        val af = sheet.autoFilter
        val rows = if (af != null) af.r1 + 1..af.r2 else 1..(sheet.cells.keys.maxOfOrNull { (it / 1000L).toInt() } ?: 0)
        val others = af?.columns?.filterKeys { it != col } ?: emptyMap()
        val stats = if (af != null) filterStats(af, others) else emptyMap()
        // Without case, as Excel lists them: the first way a value is written stands for the others.
        val firsts = LinkedHashMap<String, String>()
        var blanks = false
        for (r in rows) {
            if (others.any { (c, f) -> !passesFilter(f, r, c, stats[c]) }) continue
            val v = display(r, col)
            if (v.isEmpty()) blanks = true else firsts.putIfAbsent(v.lowercase(), v)
        }
        val seen = firsts.values
        val (numbers, texts) = seen.partition { it.replace(",", "").removeSuffix("%").toDoubleOrNull() != null }
        return (numbers.sortedBy { it.replace(",", "").removeSuffix("%").toDouble() } + texts.sortedWith(String.CASE_INSENSITIVE_ORDER)) to blanks
    }

    /** Distinct shown values of [col] (for the keyword and value filters). */
    fun columnValues(col: Int = selCol): List<String> = filterValues(col).first

    /** AutoFilter: [col] shows only rows whose value is in [allowed] (and blanks when [blanks]). */
    fun filterByValues(col: Int, allowed: Set<String>, blanks: Boolean = false) =
        setColumnFilter(col, com.therealityteam.aeropages.model.ColumnFilter(values = allowed, blanks = blanks))

    /**
     * Sort A→Z / Z→A from a filter button: the AutoFilter's data rows (its header stays) by [col] —
     * numbers before text, as Excel sorts — then the criteria apply again. One undo step.
     */
    fun sortFilterColumn(col: Int, ascending: Boolean) {
        commitEntry()
        if (refuseIfSheetProtected(Allow.SORT)) return
        val af = sheet.autoFilter ?: return
        val r0 = af.r1 + 1
        if (af.r2 <= r0) return
        pushUndo(both(sheetMeta(), cells(r0, af.c1, af.r2, af.c2)))
        sortRows(r0, af.r2, af.c1, af.c2, listOf(col to ascending))
        // The rows moved: the criteria test their new places.
        refilter(af)
        touchContent()
    }

    /**
     * Custom Sort: the rows of [sortArea] by [primaryCol] then [secondaryCol] (absolute column indices
     * within it), in Excel's order ([sortRows]); with [header] its first row stays on top. One undo step.
     */
    fun sortByColumns(primaryCol: Int, secondaryCol: Int? = null, ascending: Boolean = true, header: Boolean = false) {
        val levels = listOf(SortLevel(primaryCol, ascending)) + (secondaryCol?.takeIf { it != primaryCol }?.let { listOf(SortLevel(it, ascending)) } ?: emptyList())
        sortLevels(levels, header)
    }

    /**
     * Excel's Sort dialog: the rows of [sortArea] by [levels] in turn ([sortRowsBy]); with [header] its first row stays on
     * top. One undo step; false when there is nothing to sort (no data, or no level on one of its columns).
     */
    fun sortLevels(levels: List<SortLevel>, header: Boolean): Boolean {
        commitEntry()
        if (refuseIfSheetProtected(Allow.SORT)) return false
        val area = sortArea() ?: return false
        val r0 = area.r1 + (if (header) 1 else 0)
        val within = levels.filter { it.col in area.c1..area.c2 }
        if (area.r2 <= r0 || within.isEmpty()) return false
        pushUndo(cells(r0, area.c1, area.r2, area.c2))
        sortRowsBy(r0, area.r2, area.c1, area.c2, within)
        touchContent()
        return true
    }

    /** Excel's Sort by Color on a filter button: the rows whose cell (or, [font], its font) in [col] is [color] put on top. One undo step. */
    fun sortFilterColumnByColor(col: Int, color: Long?, font: Boolean) {
        commitEntry()
        if (refuseIfSheetProtected(Allow.SORT)) return
        val af = sheet.autoFilter ?: return
        val r0 = af.r1 + 1
        if (af.r2 <= r0) return
        pushUndo(both(sheetMeta(), cells(r0, af.c1, af.r2, af.c2)))
        sortRowsBy(r0, af.r2, af.c1, af.c2, listOf(SortLevel(col, on = if (font) SortOn.FONT_COLOR else SortOn.CELL_COLOR, color = color)))
        refilter(af)
        touchContent()
    }

    /** The colours [col]'s cells in rows [rows] show — fills, or with [font] the fonts' (null: none / automatic) — for a level sorting on one. */
    fun sortColors(col: Int, rows: IntRange, font: Boolean): List<Long?> {
        val seen = LinkedHashSet<Long?>()
        for (r in rows) sheet.cell(r, col)?.format.let { f -> seen.add(if (font) f?.color else f?.highlight) }
        return seen.toList()
    }

    // --- freeze panes -----------------------------------------------------------

    /** Freeze the rows above and the columns left of the active cell (Excel "Freeze Panes"). */
    fun freezeAtSelection() { pushUndo(sheetMeta()); sheet.freezeRows = selRow; sheet.freezeCols = selCol; touchContent() }
    /** Excel's Freeze Top Row: the first row only (the selection stays where it is). */
    fun freezeTopRow() { pushUndo(sheetMeta()); sheet.freezeRows = 1; sheet.freezeCols = 0; touchContent() }
    /** Excel's Freeze First Column: the first column only. */
    fun freezeFirstColumn() { pushUndo(sheetMeta()); sheet.freezeRows = 0; sheet.freezeCols = 1; touchContent() }

    /** View → Gridlines: the sheet's gridlines on screen, one undo step as Headings is. */
    fun toggleGridlines() { pushUndo(sheetMeta()); sheet.showGridlines = !sheet.showGridlines; touchContent() }

    /** The sheet's zoom, held to Excel's 10–400 %: a view setting, saved with the sheet but no undo step and no edit. */
    fun setZoom(percent: Int) {
        val z = percent.coerceIn(Sheet.MIN_ZOOM, Sheet.MAX_ZOOM)
        if (z == sheet.viewZoom) return
        sheet.viewZoom = z
        bump()
    }

    /** Zoom to Selection asked for: the grid, which knows its size, fits the selection and brings it to the top left. */
    var zoomFitRequest by mutableIntStateOf(0)
        private set
    fun requestZoomToSelection() { zoomFitRequest++ }
    fun unfreeze() { pushUndo(sheetMeta()); sheet.freezeRows = 0; sheet.freezeCols = 0; touchContent() }
    val isFrozen: Boolean get() = sheet.freezeRows > 0 || sheet.freezeCols > 0

    /** Sum of numeric cells in the current column above the selection (basic SUM helper). */
    fun sumColumnAboveSelection(): Double {
        var sum = 0.0
        for (r in 0 until selRow) {
            sheet.cell(r, selCol)?.text?.toDoubleOrNull()?.let { sum += it }
        }
        return sum
    }
}
