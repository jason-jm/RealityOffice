package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Test

/** SUBTOTAL in the engine (nested subtotals skipped) and Data → Subtotal's inserted rows. */
class SubtotalTest {

    private fun SheetEditor.selectBlock(r1: Int, c1: Int, r2: Int, c2: Int) { select(r1, c1); extendSelection(r2 - r1, c2 - c1) }

    @Test
    fun subtotalFunctionSkipsNestedSubtotals() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        listOf("10", "20", "=SUBTOTAL(9, A1:A2)", "5", "=SUBTOTAL(9, A4:A4)", "x").forEachIndexed { i, t -> e.setCell(i, 0, t) }
        e.setCell(0, 1, "=SUBTOTAL(9, A1:A6)")       // grand total: 10+20+5, the two group rows skipped
        assertEquals("35", e.rawValue(0, 1))
        e.setCell(1, 1, "=SUBTOTAL(1, A1:A6)"); assertEquals("11.666667", e.rawValue(1, 1))
        e.setCell(2, 1, "=SUBTOTAL(2, A1:A6)"); assertEquals("3", e.rawValue(2, 1))
        e.setCell(3, 1, "=SUBTOTAL(3, A1:A6)"); assertEquals("counts the text too", "4", e.rawValue(3, 1))
        e.setCell(4, 1, "=SUBTOTAL(104, A1:A6)"); assertEquals("20", e.rawValue(4, 1))
        e.setCell(5, 1, "=SUBTOTAL(5, A1:A6)"); assertEquals("5", e.rawValue(5, 1))
        e.setCell(6, 1, "=SUBTOTAL(9, 1, 2, 3)"); assertEquals("plain numbers work as well", "6", e.rawValue(6, 1))
    }

    @Test
    fun subtotalCommandInsertsGroupRowsAndAGrandTotal() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        val rows = listOf("Region" to "Sales", "East" to "10", "East" to "20", "West" to "5", "West" to "7", "West" to "8")
        rows.forEachIndexed { i, (a, b) -> e.setCell(i, 0, a); e.setCell(i, 1, b) }
        e.setCell(7, 0, "below")                       // content under the block shifts down
        e.selectBlock(0, 0, 5, 1)
        assertEquals(3, e.subtotal(groupCol = 0, fn = 9, targetCols = listOf(1), headerRow = true))
        val col = { c: Int -> (0..10).map { r -> e.sheet.cell(r, c)?.text ?: "" } }
        assertEquals(listOf("Region", "East", "East", "East Total", "West", "West", "West", "West Total", "Grand Total", "", "below"), col(0))
        assertEquals("=SUBTOTAL(9, B2:B3)", e.sheet.cell(3, 1)!!.text); assertEquals("30", e.rawValue(3, 1))
        assertEquals("=SUBTOTAL(9, B5:B7)", e.sheet.cell(7, 1)!!.text); assertEquals("20", e.rawValue(7, 1))
        assertEquals("whole block, group rows included but skipped", "=SUBTOTAL(9, B2:B8)", e.sheet.cell(8, 1)!!.text); assertEquals("50", e.rawValue(8, 1))
        assertEquals(true, e.sheet.cell(8, 0)!!.format.bold)
        e.undo()
        assertEquals("below", e.sheet.cell(7, 0)!!.text)
        assertEquals(null, e.sheet.cell(8, 0))
        // Unusable: the group column outside the block, or no target column.
        e.selectBlock(0, 0, 5, 1)
        assertEquals(0, e.subtotal(groupCol = 3, fn = 9, targetCols = listOf(1)))
        assertEquals(0, e.subtotal(groupCol = 0, fn = 9, targetCols = listOf(0)))
    }
}
