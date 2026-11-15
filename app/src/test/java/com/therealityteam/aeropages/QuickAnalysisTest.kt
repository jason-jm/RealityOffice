package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Quick Analysis ▸ Totals: the row under the columns, the column beside the rows. */
class QuickAnalysisTest {

    /** A2:C4 — a header row, a column of names, two columns of amounts (the second in currency). */
    private fun sales(): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        setCell(0, 0, "Region"); setCell(0, 1, "Q1"); setCell(0, 2, "Q2")
        listOf("North" to (10 to 20), "South" to (30 to 40), "East" to (50 to 60)).forEachIndexed { i, (name, q) ->
            setCell(i + 1, 0, name); setCell(i + 1, 1, q.first.toString()); setCell(i + 1, 2, q.second.toString())
        }
        select(1, 2); extendSelection(2, 0); setNumberFormat(NumberFormat.CURRENCY_USD)
        selectRange(0, 0, 3, 2)
    }

    @Test
    fun aTotalGoesUnderEachColumnOfNumbersAndTheLabelsColumnGetsTheName() {
        val e = sales()
        assertTrue(e.quickTotals("SUM", label = "Sum"))
        assertEquals("=SUM(B2:B4)", e.sheet.cell(4, 1)!!.text)
        assertEquals("=SUM(C2:C4)", e.sheet.cell(4, 2)!!.text)
        assertEquals("Sum", e.sheet.cell(4, 0)!!.text)
        assertTrue(e.sheet.cell(4, 1)!!.format.bold)
        assertEquals("in the values' number format", NumberFormat.CURRENCY_USD, e.sheet.cell(4, 2)!!.numberFormat)
        e.undo()
        assertNull("one undo step", e.sheet.cell(4, 1)?.text?.takeIf { it.isNotEmpty() })
        assertNull(e.sheet.cell(4, 0)?.text?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun aCountIsInGeneralAndATotalBesideTheRowsHasItsHeader() {
        val e = sales()
        assertTrue(e.quickTotals("COUNT", label = "Count"))
        assertEquals(NumberFormat.GENERAL, e.sheet.cell(4, 2)!!.numberFormat)
        e.undo()
        assertTrue(e.quickTotals("AVERAGE", right = true, label = "Average"))
        assertEquals("Average", e.sheet.cell(0, 3)!!.text)
        assertEquals("=AVERAGE(A2:C2)", e.sheet.cell(1, 3)!!.text)
        assertEquals("=AVERAGE(A4:C4)", e.sheet.cell(3, 3)!!.text)
        assertTrue(e.sheet.colCount > 3)
    }

    @Test
    fun nothingToTotalIsRefused() {
        val e = SheetEditor().apply { load(Workbook.blank()); setCell(0, 0, "a"); setCell(1, 0, "b"); selectRange(0, 0, 1, 0) }
        assertFalse(e.quickTotals("SUM", label = "Sum"))
        assertNull(e.sheet.cell(2, 0))
    }
}
