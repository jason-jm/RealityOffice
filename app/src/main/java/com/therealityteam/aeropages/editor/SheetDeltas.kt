package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook

/** Undo deltas for [SheetEditor]; see [Delta]. All are positional: sheet index + cell keys. */

/**
 * Every cell inside a rectangle of one sheet. [cells] holds the cells that existed (keyed as in
 * [Sheet.cells]); applying removes whatever is in the rectangle now and puts these back, so
 * cells created inside the rectangle by the edit disappear again.
 */
internal class CellRangeDelta(
    val sheetIdx: Int,
    val r1: Int, val c1: Int, val r2: Int, val c2: Int,
    val cells: Map<Long, Cell>,
) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        val sheet = model.sheets[sheetIdx]
        val current = capture(sheetIdx, sheet, r1, c1, r2, c2)
        val doomed = ArrayList<Long>()
        forEachInRange(sheet, r1, c1, r2, c2) { k, _ -> doomed.add(k) }
        doomed.forEach { sheet.cells.remove(it) }
        cells.forEach { (k, v) -> sheet.cells[k] = v.copyOf() }
        return current
    }

    companion object {
        fun capture(sheetIdx: Int, sheet: Sheet, r1: Int, c1: Int, r2: Int, c2: Int): CellRangeDelta {
            val m = HashMap<Long, Cell>()
            forEachInRange(sheet, r1, c1, r2, c2) { k, cell -> m[k] = cell.copyOf() }
            return CellRangeDelta(sheetIdx, r1, c1, r2, c2, m)
        }

        /** Visit the existing cells of the rectangle — by direct lookup when it is smaller than the cell map, else by scanning the map. */
        private inline fun forEachInRange(sheet: Sheet, r1: Int, c1: Int, r2: Int, c2: Int, visit: (Long, Cell) -> Unit) {
            val cc2 = c2.coerceAtMost(Sheet.MAX_COLS - 1)
            if (r2 < r1 || cc2 < c1) return
            val area = (r2 - r1 + 1).toLong() * (cc2 - c1 + 1).toLong()
            if (area <= sheet.cells.size) {
                for (r in r1..r2) for (c in c1..cc2) {
                    val k = sheet.key(r, c)
                    sheet.cells[k]?.let { visit(k, it) }
                }
            } else {
                for ((k, cell) in sheet.cells) {
                    val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
                    if (r in r1..r2 && c in c1..cc2) visit(k, cell)
                }
            }
        }
    }
}

/** An explicit set of cells (by key) of one sheet; a null value means "the cell did not exist". */
internal class CellKeysDelta(val sheetIdx: Int, val cells: Map<Long, Cell?>) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        val sheet = model.sheets[sheetIdx]
        val current = HashMap<Long, Cell?>()
        cells.forEach { (k, v) ->
            current[k] = sheet.cells[k]?.copyOf()
            if (v == null) sheet.cells.remove(k) else sheet.cells[k] = v.copyOf()
        }
        return CellKeysDelta(sheetIdx, current)
    }

    companion object {
        fun capture(sheetIdx: Int, sheet: Sheet, keys: Collection<Long>) =
            CellKeysDelta(sheetIdx, keys.associateWith { sheet.cells[it]?.copyOf() })

        /** The formula cells of [sheet] — what a structural edit on another sheet may rewrite; null when there are none. */
        fun formulas(sheetIdx: Int, sheet: Sheet): CellKeysDelta? {
            val m = HashMap<Long, Cell?>()
            for ((k, c) in sheet.cells) if (c.text.startsWith("=")) m[k] = c.copyOf()
            return if (m.isEmpty()) null else CellKeysDelta(sheetIdx, m)
        }
    }
}

/** Everything of a sheet except its cells: name, size, freeze panes, row/column sizes, merges, rules, validations, charts, pictures, shapes, whether it shows and its tab colour. */
internal class SheetMetaDelta(private val sheetIdx: Int, private val meta: Sheet) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        val sheet = model.sheets[sheetIdx]
        val current = capture(sheetIdx, sheet)
        sheet.name = meta.name; sheet.rowCount = meta.rowCount; sheet.colCount = meta.colCount
        sheet.freezeRows = meta.freezeRows; sheet.freezeCols = meta.freezeCols
        sheet.condFormats.clear(); sheet.condFormats.addAll(meta.condFormats.map { it.copyOf() })
        sheet.merges.clear(); sheet.merges.addAll(meta.merges.map { it.copyOf() })
        sheet.colWidths.clear(); sheet.colWidths.putAll(meta.colWidths)
        sheet.rowHeights.clear(); sheet.rowHeights.putAll(meta.rowHeights)
        sheet.charts.clear(); sheet.charts.addAll(meta.charts.map { it.copyOf() })
        sheet.validations.clear(); sheet.validations.addAll(meta.validations.map { it.copyOf() })
        sheet.landscape = meta.landscape; sheet.paper = meta.paper; sheet.printArea = meta.printArea?.copyOf(); sheet.showHeadings = meta.showHeadings
        sheet.hiddenRows.clear(); sheet.hiddenRows.addAll(meta.hiddenRows)
        sheet.sparklines.clear(); sheet.sparklines.addAll(meta.sparklines.map { it.copyOf() })
        sheet.protected = meta.protected; sheet.protection = meta.protection
        sheet.scenarios.clear(); sheet.scenarios.addAll(meta.scenarios.map { it.copyOf() })
        sheet.rowGroups.clear(); sheet.rowGroups.addAll(meta.rowGroups.map { it.copyOf() })
        sheet.hiddenCols.clear(); sheet.hiddenCols.addAll(meta.hiddenCols)
        sheet.colGroups.clear(); sheet.colGroups.addAll(meta.colGroups.map { it.copyOf() })
        sheet.manualHiddenRows.clear(); sheet.manualHiddenRows.addAll(meta.manualHiddenRows)
        sheet.autoFilter = meta.autoFilter?.copyOf()
        sheet.images.clear(); sheet.images.addAll(meta.images.map { it.copyOf() })
        sheet.shapes.clear(); sheet.shapes.addAll(meta.shapes.map { it.copyOf() })
        sheet.state = meta.state; sheet.tabColor = meta.tabColor; sheet.print = meta.print; sheet.showGridlines = meta.showGridlines
        return current
    }

    companion object {
        /** A cell-less [Sheet] carries the metadata copy. */
        fun capture(sheetIdx: Int, s: Sheet) = SheetMetaDelta(sheetIdx, Sheet(
            s.name, s.rowCount, s.colCount, mutableMapOf(), s.freezeRows, s.freezeCols,
            s.condFormats.map { it.copyOf() }.toMutableList(), s.merges.map { it.copyOf() }.toMutableList(),
            s.colWidths.toMutableMap(), s.rowHeights.toMutableMap(),
            s.charts.map { it.copyOf() }.toMutableList(), s.validations.map { it.copyOf() }.toMutableList(),
            s.landscape, s.paper, s.printArea?.copyOf(), s.showHeadings, s.hiddenRows.toMutableSet(),
            s.sparklines.map { it.copyOf() }.toMutableList(), s.protected,
            s.scenarios.map { it.copyOf() }.toMutableList(),
            s.rowGroups.map { it.copyOf() }.toMutableList(),
            s.hiddenCols.toMutableSet(), s.colGroups.map { it.copyOf() }.toMutableList(),
            s.manualHiddenRows.toMutableSet(), s.autoFilter?.copyOf(),
            s.images.map { it.copyOf() }.toMutableList(),
            shapes = s.shapes.map { it.copyOf() }.toMutableList(), state = s.state, tabColor = s.tabColor, print = s.print, showGridlines = s.showGridlines,
        ).also { it.protection = s.protection })
    }
}

/** A sheet's tables — their ranges, names and options (what structured references resolve against, so values may change). */
internal class TablesDelta(private val sheetIdx: Int, private val tables: List<com.therealityteam.aeropages.model.SheetTable>) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        val sheet = model.sheets[sheetIdx]
        val current = capture(sheetIdx, sheet)
        sheet.tables.clear(); sheet.tables.addAll(tables.map { it.copyOf() })
        return current
    }

    companion object {
        fun capture(sheetIdx: Int, sheet: Sheet) = TablesDelta(sheetIdx, sheet.tables.map { it.copyOf() })
    }
}

/** A whole sheet — for structural edits (row/column insertion, duplicate removal) that shift every cell. */
internal class SheetDelta(private val sheetIdx: Int, private val stored: Sheet) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        val current = model.sheets[sheetIdx].copyOf()
        model.sheets[sheetIdx] = stored.copyOf()
        return SheetDelta(sheetIdx, current)
    }
}

/** A sheet-list slot: `stored == null` means "remove the sheet at [index]", otherwise "insert it there". */
internal class SheetSlotDelta(private val index: Int, private val stored: Sheet?) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> =
        if (stored == null) SheetSlotDelta(index, model.sheets.removeAt(index).copyOf())
        else { model.sheets.add(index, stored.copyOf()); SheetSlotDelta(index, null) }
}

/** A sheet moved from [from] to [to] in the sheet list (Move or Copy); undone by moving it back. */
internal class SheetOrderDelta(private val from: Int, private val to: Int) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        model.sheets.add(to, model.sheets.removeAt(from))
        return SheetOrderDelta(to, from)
    }
}

/** The workbook's defined names. */
/** Protect Workbook: whether the structure is locked and its password. */
internal class WorkbookProtectionDelta(private val locked: Boolean, private val passwords: List<com.therealityteam.aeropages.model.ProtectionHash>) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        val current = WorkbookProtectionDelta(model.structureLocked, model.structurePasswords)
        model.structureLocked = locked; model.structurePasswords = passwords
        return current
    }
}

internal class NamesDelta(private val names: Map<String, String>) : Delta<Workbook> {
    override fun apply(model: Workbook): Delta<Workbook> {
        val current = NamesDelta(model.names.toMap())
        model.names.clear(); model.names.putAll(names)
        return current
    }
}
