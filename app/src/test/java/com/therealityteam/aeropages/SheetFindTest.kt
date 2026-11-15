package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.CellHit
import com.therealityteam.aeropages.editor.LookIn
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetFindOptions
import com.therealityteam.aeropages.editor.SheetSearch
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Find and Replace: its wildcards, within the sheet or the workbook, by rows or columns, in formulas, values or comments. */
class SheetFindTest {

    @Test
    fun excelsWildcards() {
        val o = SheetFindOptions()
        fun hit(q: String, t: String, opts: SheetFindOptions = o) = SheetSearch.matches(SheetSearch.pattern(q, opts)!!, t)
        assertTrue(hit("a*c", "xxabbbcxx"))
        assertTrue(hit("b?t", "a bit"))
        assertFalse(hit("b?t", "a bt"))
        assertTrue(hit("~*", "5*3")); assertFalse(hit("~*", "53"))
        assertTrue(hit("~?", "why?")); assertTrue(hit("a~~b", "a~b"))
        assertTrue(hit("SALES", "Sales total"))
        assertFalse(hit("SALES", "Sales total", o.copy(matchCase = true)))
        assertFalse(hit("Sales", "Sales total", o.copy(wholeCell = true)))
        assertTrue(hit("Sales*", "Sales total", o.copy(wholeCell = true)))
        assertNull(SheetSearch.pattern("", o))
    }

    private fun workbook(): SheetEditor {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        e.setCell(0, 0, "apple"); e.setCell(0, 2, "Apple pie"); e.setCell(1, 1, "=1+1"); e.setCell(2, 0, "pear")
        e.sheet.ensure(2, 0).comment = "an apple a day"
        e.addSheet()
        e.setCell(3, 3, "apple tree")
        e.selectSheet(0)
        return e
    }

    @Test
    fun withinBySearchAndLookIn() {
        val e = workbook()
        // By rows (A1, C1), by columns (A1, C1 still — one row), the workbook adds Sheet2's D4.
        assertEquals(listOf(CellHit(0, 0, 0), CellHit(0, 0, 2)), e.findCells("apple", SheetFindOptions()))
        assertEquals(3, e.findCells("apple", SheetFindOptions(workbook = true)).size)
        // In what a formula shows, not what it says; in comments.
        assertTrue(e.findCells("2", SheetFindOptions()).isEmpty())
        assertEquals(listOf(CellHit(0, 1, 1)), e.findCells("2", SheetFindOptions(lookIn = LookIn.VALUES)))
        assertEquals(listOf(CellHit(0, 2, 0)), e.findCells("apple", SheetFindOptions(lookIn = LookIn.COMMENTS)))
        // By columns: column A before column C, top to bottom.
        e.setCell(5, 0, "apple sauce")
        assertEquals(listOf(CellHit(0, 0, 0), CellHit(0, 5, 0), CellHit(0, 0, 2)), e.findCells("apple", SheetFindOptions(byColumns = true)))
    }

    @Test
    fun nextAndPreviousAcrossSheets() {
        val e = workbook()
        val o = SheetFindOptions(workbook = true)
        e.select(0, 0)
        assertEquals(1 to 3, e.findStep("apple", o))
        assertEquals(0 to 2, e.selRow to e.selCol)
        assertEquals(2 to 3, e.findStep("apple", o))
        assertEquals("on to Sheet2", 1, e.sheetIndex)
        assertEquals(3 to 3, e.selRow to e.selCol)
        assertEquals("round to the first", 0 to 3, e.findStep("apple", o))
        assertEquals(0, e.sheetIndex)
        assertEquals(2 to 3, e.findStep("apple", o, back = true))
        assertNull(e.findStep("banana", o))
    }

    @Test
    fun replaceOneAndAll() {
        val e = workbook()
        val o = SheetFindOptions(workbook = true)
        e.select(0, 0)
        // Replace: the active cell (every match in it), then the next is selected.
        e.replaceStep("apple", "plum", o)
        assertEquals("plum", e.workbook.sheets[0].cell(0, 0)?.text)
        assertEquals(0 to 2, e.selRow to e.selCol)
        // Replace All over both sheets, one undo step.
        assertEquals(2, e.replaceAllCells("apple", "plum", o))
        assertEquals("plum pie", e.workbook.sheets[0].cell(0, 2)?.text)
        assertEquals("plum tree", e.workbook.sheets[1].cell(3, 3)?.text)
        e.undo()
        assertEquals("Apple pie", e.workbook.sheets[0].cell(0, 2)?.text)
        assertEquals("apple tree", e.workbook.sheets[1].cell(3, 3)?.text)
        assertEquals("plum", e.workbook.sheets[0].cell(0, 0)?.text)
        // The old calls still work (their query as it is, case kept).
        assertEquals(0, e.replaceAll("APPLE", "x"))
        assertEquals(0 to 2, e.findNext("Apple"))
    }
}
