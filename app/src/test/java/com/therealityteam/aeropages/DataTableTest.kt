package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Test

/** Data Table (What-If): two-variable and one-variable blocks filled with values, one undo step. */
class DataTableTest {

    private fun editor(): SheetEditor = SheetEditor().apply { load(Workbook.blank()) }
    private fun SheetEditor.selectBlock(r1: Int, c1: Int, r2: Int, c2: Int) { select(r1, c1); extendSelection(r2 - r1, c2 - c1) }

    @Test
    fun twoVariableTableFillsTheInterior() {
        val e = editor()
        // Inputs: price in A1, quantity in B1; formula D1 = A1*B1 → the block D1:G4.
        e.setCell(0, 0, "10"); e.setCell(0, 1, "2")
        e.setCell(0, 3, "=A1*B1")
        listOf(1, 2, 3).forEachIndexed { i, q -> e.setCell(0, 4 + i, q.toString()) }        // row values → B1
        listOf(5, 10, 20).forEachIndexed { i, p -> e.setCell(1 + i, 3, p.toString()) }      // column values → A1
        e.selectBlock(0, 3, 3, 6)
        assertEquals(9, e.dataTable(rowInput = 0 to 1, colInput = 0 to 0))
        assertEquals("5", e.rawValue(1, 4)); assertEquals("10", e.rawValue(1, 5)); assertEquals("15", e.rawValue(1, 6))
        assertEquals("10", e.rawValue(2, 4)); assertEquals("40", e.rawValue(3, 5)); assertEquals("60", e.rawValue(3, 6))
        assertEquals("inputs restored", "10", e.rawValue(0, 0)); assertEquals("2", e.rawValue(0, 1))
        assertEquals("the formula still lives at the corner", "=A1*B1", e.sheet.cell(0, 3)!!.text)
        e.undo()
        assertEquals("", e.rawValue(2, 4))
    }

    @Test
    fun oneVariableTablesRunFormulasAcrossOrDown() {
        val e = editor()
        e.setCell(0, 0, "3")                       // input A1, outside both blocks
        // Column-oriented block B1:D4: formulas across the top row (C1, D1), values down B2:B4, corner B1 empty.
        e.setCell(0, 2, "=A1*2"); e.setCell(0, 3, "=A1+100")
        listOf(1, 2, 3).forEachIndexed { i, v -> e.setCell(1 + i, 1, v.toString()) }
        e.selectBlock(0, 1, 3, 3)
        assertEquals(6, e.dataTable(rowInput = null, colInput = 0 to 0))
        assertEquals("2", e.rawValue(1, 2)); assertEquals("6", e.rawValue(3, 2)); assertEquals("103", e.rawValue(3, 3))
        // Row-oriented block F1:I3: formulas down the left column (F2, F3), values across G1:I1.
        e.setCell(1, 5, "=A1*10"); e.setCell(2, 5, "=A1-1")
        listOf(4, 5, 6).forEachIndexed { i, v -> e.setCell(0, 6 + i, v.toString()) }
        e.selectBlock(0, 5, 2, 8)
        assertEquals(6, e.dataTable(rowInput = 0 to 0, colInput = null))
        assertEquals("40", e.rawValue(1, 6)); assertEquals("60", e.rawValue(1, 8)); assertEquals("5", e.rawValue(2, 8))
        assertEquals("input restored", "3", e.rawValue(0, 0))
        // Unusable blocks: a single row, an input inside the block, a formula as the input.
        e.selectBlock(0, 1, 0, 3)
        assertEquals(0, e.dataTable(null, 0 to 0))
        e.selectBlock(0, 1, 3, 3)
        assertEquals(0, e.dataTable(null, 1 to 1))
        assertEquals(0, e.dataTable(null, 0 to 2))
    }
}
