package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's commands as Excel has them: Format Painter, Fill Up / Left, Row Height… and Column Width…. */
class SheetHomeCommandsTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    /** A1 bold on yellow, B1 a percentage with a rule: the block Format Painter takes. */
    private fun withFormats(): SheetEditor {
        val e = editor()
        e.setCell(0, 0, "Name"); e.setCell(0, 1, "0.25")
        e.select(0, 0); e.toggleBold(); e.setHighlight(0xFFFFFF00)
        e.select(0, 1); e.setNumberFormat(NumberFormat.PERCENT)
        e.addCondFormat(CondType.GREATER, 0.1, 0.0, 0xFFC6EFCE)
        e.setCell(4, 3, "keep"); e.setCell(4, 4, "0.5")
        return e
    }

    @Test
    fun formatPainterPaintsTheBlockAtTheCellPicked() {
        val e = withFormats()
        e.select(0, 0); e.extendSelection(0, 1)
        e.formatPainter()
        assertTrue(e.painterArmed)
        e.select(4, 3)
        assertTrue(e.paintFormats())
        assertFalse("one use, as Excel's single click", e.painterArmed)
        val d = e.sheet.cell(4, 3)!!; val f = e.sheet.cell(4, 4)!!
        assertEquals("keep", d.text); assertTrue(d.format.bold); assertEquals(0xFFFFFF00, d.format.highlight)
        assertEquals("0.5", f.text); assertEquals(NumberFormat.PERCENT, f.numberFormat)
        // Its conditional format went to the painted cell; the block painted is selected.
        assertTrue(e.sheet.condFormats.any { it.r1 == 4 && it.c1 == 4 && it.r2 == 4 && it.c2 == 4 })
        assertEquals(listOf(4, 3, 4, 4), listOf(e.rangeRowMin(), e.rangeColMin(), e.rangeRowMax(), e.rangeColMax()))
        e.undo()
        assertFalse(e.sheet.cell(4, 3)!!.format.bold)
        assertEquals(NumberFormat.GENERAL, e.sheet.cell(4, 4)!!.numberFormat)
        assertEquals(1, e.sheet.condFormats.size)
    }

    @Test
    fun formatPainterRepeatsTheBlockOverALargerSelectionAndClearsWithBlanks() {
        val e = withFormats()
        // Plain C1 next to the bold A1: painting A1:C1's pattern clears the third column's formats.
        e.select(6, 2); e.toggleBold()
        e.select(0, 0); e.extendSelection(0, 2)
        e.formatPainter()
        e.select(6, 0); e.extendSelection(1, 5)
        assertTrue(e.paintFormats())
        for (c in 0..5) for (r in 6..7) {
            val cell = e.sheet.cell(r, c)
            assertEquals("column $c", c % 3 == 0, cell?.format?.bold == true)
            assertEquals("column $c", c % 3 == 1, cell?.numberFormat == NumberFormat.PERCENT)
        }
        assertNull("nothing made where nothing is", e.sheet.cell(7, 2))
    }

    @Test
    fun formatPainterIsPutDownAgain() {
        val e = withFormats()
        e.formatPainter(); assertTrue(e.painterArmed)
        e.formatPainter(); assertFalse(e.painterArmed)
        e.formatPainter(); e.cancelPainter()
        e.select(4, 3)
        assertFalse(e.paintFormats())
        assertFalse(e.sheet.cell(4, 3)!!.format.bold)
    }

    @Test
    fun fillUpAndLeftCopyTheFarEdgeWithItsReferences() {
        val e = editor()
        e.setCell(3, 0, "=B4*2")
        e.select(3, 0); e.extendSelection(-3, 0)
        e.fillUp()
        assertEquals(listOf("=B1*2", "=B2*2", "=B3*2", "=B4*2"), (0..3).map { e.sheet.cell(it, 0)!!.text })
        e.setCell(5, 3, "=D1")
        e.select(5, 3); e.extendSelection(0, -3)
        e.fillLeft()
        assertEquals(listOf("=A1", "=B1", "=C1", "=D1"), (0..3).map { e.sheet.cell(5, it)!!.text })
        e.undo()
        assertNull(e.sheet.cell(5, 0))
    }

    @Test
    fun reviewStepsThroughCommentsRowByRow() {
        val e = editor()
        for ((r, c) in listOf(4 to 1, 0 to 3, 4 to 0)) { e.select(r, c); e.setComment("note $r,$c") }
        e.select(0, 0)
        assertFalse(e.activeHasComment)
        assertTrue(e.goToComment()); assertEquals(0 to 3, e.selRow to e.selCol)
        assertTrue(e.activeHasComment)
        assertTrue(e.goToComment()); assertEquals(4 to 0, e.selRow to e.selCol)
        assertTrue(e.goToComment()); assertEquals(4 to 1, e.selRow to e.selCol)
        assertFalse("no more after the last", e.goToComment())
        assertTrue(e.goToComment(back = true)); assertEquals(4 to 0, e.selRow to e.selCol)
    }

    @Test
    fun theCommentsPaneListsTheSheetsCommentsRowByRow() {
        val e = editor()
        for ((r, c) in listOf(4 to 1, 0 to 3, 4 to 0)) { e.select(r, c); e.setComment("note $r,$c") }
        assertEquals(listOf("note 0,3", "note 4,0", "note 4,1"), e.commentCells().map { it.text })
        e.select(4, 0); e.clearComment()
        assertEquals(listOf(0 to 3, 4 to 1), e.commentCells().map { it.row to it.col })
        e.undo()
        assertEquals("deleting from the pane is one undo step", 3, e.commentCells().size)
    }

    @Test
    fun rowHeightAndColumnWidthInExcelsUnits() {
        val e = editor()
        e.select(1, 1); e.extendSelection(1, 1)
        assertEquals(15f, e.selectedRowHeightPt()!!, 0.001f)
        assertEquals(8.43f, e.selectedColWidthChars()!!, 0.001f)
        e.setRowHeightPt(30f)
        assertEquals(2f, e.sheet.rowHeightFactor(1), 0.001f); assertEquals(2f, e.sheet.rowHeightFactor(2), 0.001f)
        e.setColWidthChars(16.86f)
        assertEquals(2f, e.sheet.colWidthFactor(2), 0.001f)
        assertEquals(30f, e.selectedRowHeightPt()!!, 0.001f)
        // Rows of different heights have no one height to show.
        e.select(0, 0); e.extendSelection(1, 0)
        assertNull(e.selectedRowHeightPt())
        // Out of range is held to the sheet's limits; the default height is no height of its own.
        e.select(1, 1); e.setRowHeightPt(1000f)
        assertEquals(SheetEditor.MAX_ROW_FACTOR, e.sheet.rowHeightFactor(1), 0.001f)
        e.setRowHeightPt(15f)
        assertFalse(1 in e.sheet.rowHeights)
        // 0 hides, as in Excel.
        e.select(4, 4); e.setColWidthChars(0f)
        assertTrue(4 in e.sheet.hiddenCols)
        e.undo(); e.undo()
        assertEquals(SheetEditor.MAX_ROW_FACTOR, e.sheet.rowHeightFactor(1), 0.001f)
    }

    @Test
    fun cancellingAnEntryPutsTheCellBack() {
        val e = editor()
        e.setCell(1, 1, "42")
        e.select(1, 1)
        assertFalse(e.entryOpen)
        e.typeCell(1, 1, "4")
        e.typeCell(1, 1, "40")
        assertTrue(e.entryOpen)
        assertTrue(e.abandonEntry())
        assertFalse(e.entryOpen)
        assertEquals("42", e.sheet.cell(1, 1)?.text)
        // An empty cell typed into goes back to empty.
        e.select(2, 2)
        e.typeCell(2, 2, "x")
        assertTrue(e.abandonEntry())
        assertNull(e.sheet.cell(2, 2))
        assertFalse(e.abandonEntry())
    }

    @Test
    fun errorCheckingStepsThroughTheSheetsFormulaErrorsRowByRow() {
        val e = editor()
        e.typeCell(1, 1, "=NOSUCH(1)")
        e.typeCell(0, 2, "=1/0")
        e.typeCell(2, 0, "5")
        e.typeCell(0, 0, "=A3*2")
        assertEquals(listOf(Triple(0, 2, "#DIV/0!"), Triple(1, 1, "#NAME?")), e.formulaErrors())
        e.typeCell(0, 2, "=1/2")
        assertEquals(listOf(Triple(1, 1, "#NAME?")), e.formulaErrors())
    }
}
