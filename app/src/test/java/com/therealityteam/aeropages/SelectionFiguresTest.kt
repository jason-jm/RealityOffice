package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.NumberFormatting
import com.therealityteam.aeropages.editor.SelectionStats.Figure
import com.therealityteam.aeropages.editor.SheetEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Excel's status bar figures for a selection: what counts, when they show, and the format they are shown in. */
class SelectionFiguresTest {
    private fun editor(vararg cells: Pair<String, String>): SheetEditor {
        val e = SheetEditor()
        for ((ref, text) in cells) {
            val r = ref.drop(1).toInt() - 1; val c = ref[0] - 'A'
            e.sheet.ensure(r, c).text = text
        }
        return e
    }

    @Test
    fun theNumbersSumAverageAndRange() {
        val e = editor("A1" to "Sales", "A2" to "10", "A3" to "20", "A4" to "=A2+A3", "A5" to "5")
        e.select(0, 0); e.extendSelection(4, 0)
        val s = e.selectionStats()!!
        assertEquals("the heading counts as a cell, not a number", 5, s.count)
        assertEquals(4, s.numbers)
        assertEquals(65.0, s.sum, 0.0); assertEquals(16.25, s.average, 0.0)
        assertEquals(5.0, s.min, 0.0); assertEquals(30.0, s.max, 0.0)
    }

    @Test
    fun onlyTwoOrMoreValuesShowAndOnlyNumbersAreSummed() {
        val e = editor("A1" to "TRUE", "A2" to "5", "A3" to "7", "A4" to "=1/0", "B1" to "3")
        e.sheet.ensure(1, 0).formatCode = "@" // a number kept as text
        e.select(0, 1)
        assertNull("one cell shows nothing", e.selectionStats())
        e.extendSelection(2, 0)
        assertNull("one value in three cells shows nothing either", e.selectionStats())
        e.select(0, 0); e.extendSelection(4, 0)
        val s = e.selectionStats()!!
        assertEquals("TRUE, the text 5, 7 and the error: four cells with something; the blank is not one", 4, s.count)
        assertEquals("only 7 is a number", 1, s.numbers)
        assertEquals(7.0, s.valueOf(Figure.SUM)!!, 0.0)
        // No numbers: the counts still show, the sums do not.
        e.select(0, 0); e.extendSelection(1, 0)
        val text = e.selectionStats()!!
        assertEquals(0.0, text.valueOf(Figure.NUMERICAL_COUNT)!!, 0.0)
        assertNull(text.valueOf(Figure.AVERAGE)); assertNull(text.valueOf(Figure.SUM))
    }

    @Test
    fun aWholeColumnCountsOnlyItsCells() {
        val e = editor("C2" to "4", "C900" to "6", "D3" to "100")
        e.sheet.rowCount = 1_048_576
        e.selectColumns(2)
        val s = e.selectionStats()!!
        assertEquals(2, s.count); assertEquals(10.0, s.sum, 0.0)
    }

    @Test
    fun figuresTakeTheFirstNumbersFormatElseGeneral() {
        val e = editor("A1" to "Price", "A2" to "12.5", "A3" to "7.5")
        e.sheet.ensure(1, 0).formatCode = "#,##0.00"
        e.select(0, 0); e.extendSelection(2, 0)
        val s = e.selectionStats()!!
        assertEquals("10.00", NumberFormatting.figure(s.average, s.format))
        assertEquals("20.00", NumberFormatting.figure(s.sum, s.format))
        assertEquals("General shows ten significant digits", "3.333333333", NumberFormatting.figure(10.0 / 3, null))
        assertEquals("0.3", NumberFormatting.figure(0.1 + 0.2, null))
    }
}
