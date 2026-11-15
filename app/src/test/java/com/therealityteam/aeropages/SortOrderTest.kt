package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sorting as Excel sorts: by values in its order, blanks last, a guessed header kept on top, formulas following their rows. */
class SortOrderTest {

    /** A header row (Item, Qty, Total) over five rows; Total is Qty × 2 by formula. */
    private fun editor(): SheetEditor {
        val s = Sheet(name = "Data")
        listOf("Item", "Qty", "Total").forEachIndexed { c, t -> s.ensure(0, c).text = t }
        listOf("pear" to "12", "Apple" to "3", "fig" to "", "banana" to "TRUE", "apple pie" to "7").forEachIndexed { i, (item, qty) ->
            val r = i + 1
            s.ensure(r, 0).text = item
            if (qty.isNotEmpty()) s.ensure(r, 1).text = qty
            s.ensure(r, 2).text = "=B${r + 1}*2"
        }
        return SheetEditor().apply { load(Workbook(mutableListOf(s))) }
    }

    private fun column(e: SheetEditor, col: Int) = (0..5).map { e.sheet.cell(it, col)?.text.orEmpty() }

    @Test
    fun aQuickSortFromOneCellSortsTheDataAroundItKeepingItsHeader() {
        val e = editor()
        // The active cell in Qty: the block around it, its header (text over numbers) kept on top.
        e.select(3, 1)
        assertTrue(e.sortArea()!!.header)
        assertTrue(e.sortSelection(ascending = true))
        assertEquals(listOf("Item", "Apple", "apple pie", "pear", "banana", "fig"), column(e, 0))
        // Each Total formula went with its row and reads its own row's Qty.
        assertEquals(listOf("Total", "=B2*2", "=B3*2", "=B4*2", "=B5*2", "=B6*2"), column(e, 2))
        assertEquals(listOf("6", "14", "24", "2", "0"), (1..5).map { e.display(it, 2) })
        // Descending turns numbers, text and TRUE round — the blank stays last.
        e.sortSelection(ascending = false)
        assertEquals(listOf("Item", "banana", "pear", "apple pie", "Apple", "fig"), column(e, 0))
        e.undo(); e.undo()
        assertEquals(listOf("Item", "pear", "Apple", "fig", "banana", "apple pie"), column(e, 0))
    }

    @Test
    fun textSortsWithoutCaseAndFormulasByTheirValues() {
        val e = editor()
        // By Item: case aside, "Apple" and "apple pie" in a row.
        e.select(1, 0)
        e.sortSelection(ascending = true)
        assertEquals(listOf("Item", "Apple", "apple pie", "banana", "fig", "pear"), column(e, 0))
        // By Total — formulas, so by what they come to, not their text.
        e.select(1, 2)
        e.sortSelection(ascending = false)
        assertEquals(listOf("Item", "pear", "apple pie", "Apple", "banana", "fig"), column(e, 0))
    }

    @Test
    fun customSortTakesTheHeaderAsTold() {
        val e = editor()
        e.select(0, 0); e.extendSelection(5, 2)
        assertTrue("text over numbers reads as a header", e.sortArea()!!.header)
        e.sortByColumns(primaryCol = 0, ascending = true, header = false)
        // Told there is none, the header row sorts with the rest.
        assertEquals(listOf("Apple", "apple pie", "banana", "fig", "Item", "pear"), column(e, 0))
        e.undo()
        e.sortByColumns(primaryCol = 1, secondaryCol = 0, ascending = true, header = true)
        assertEquals(listOf("Item", "Apple", "apple pie", "pear", "banana", "fig"), column(e, 0))
    }

    @Test
    fun headersAreGuessedAsExcelGuesses() {
        val s = Sheet(name = "Names")
        listOf("Name", "Zoe", "Adam", "Mia").forEachIndexed { r, t -> s.ensure(r, 0).text = t }
        val e = SheetEditor().apply { load(Workbook(mutableListOf(s))) }
        // Text over text, all alike: no header (Excel sorts it with the rest).
        assertFalse(e.guessHeader(0, 0, 3, 0))
        // Bold sets it apart.
        e.sheet.cell(0, 0)!!.format.bold = true
        assertTrue(e.guessHeader(0, 0, 3, 0))
        e.select(2, 0)
        e.sortSelection(ascending = true)
        assertEquals(listOf("Name", "Adam", "Mia", "Zoe"), (0..3).map { e.sheet.cell(it, 0)?.text })
        // Nothing to sort at an empty cell far away.
        e.select(20, 5)
        assertFalse(e.sortSelection(ascending = true))
    }
}
