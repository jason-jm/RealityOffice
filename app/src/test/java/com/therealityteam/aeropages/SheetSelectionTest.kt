package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selecting with the pointer (grips, press-and-drag), the Name Box, and the room past a sheet's end. */
class SheetSelectionTest {

    private fun editor(vararg sheets: String = arrayOf("Sheet1")): SheetEditor =
        SheetEditor().apply { load(Workbook(sheets.map { Sheet(name = it) }.toMutableList())) }

    private fun SheetEditor.block() = listOf(selRow, selCol, selRow2, selCol2)

    @Test
    fun aDraggedBlockKeepsItsAnchorAndGrowsOverMergesOnTheFarSide() {
        val e = editor()
        e.sheet.merges.add(MergeRegion(1, 1, 2, 2))                     // B2:C3
        e.selectRange(0, 0, 1, 1)                                       // A1 → B2 cuts the merge
        assertEquals("grown to A1:C3", listOf(0, 0, 2, 2), e.block())
        e.selectRange(3, 3, 1, 1)                                       // D4 → B2 holds it whole
        assertEquals(listOf(3, 3, 1, 1), e.block())
        e.selectRange(3, 0, 0, 1)                                       // A4 → B1: grows to the right only
        assertEquals(listOf(3, 0, 0, 2), e.block())
        val rev = e.revision
        e.selectRange(3, 0, 0, 1)
        assertEquals("the same block does not recompose", rev, e.revision)
        e.selectRange(-5, -5, 500, 500)
        assertEquals("clamped to the sheet", listOf(0, 0, e.sheet.rowCount - 1, e.sheet.colCount - 1), e.block())
    }

    @Test
    fun theNameBoxShowsCellsBlocksAndWholeLines() {
        val e = editor()
        e.select(2, 1)
        assertEquals("B3", e.selectionRef())
        e.selectRange(7, 3, 1, 1)
        assertEquals("B2:D8", e.selectionRef())
        e.selectColumns(1, 3)
        assertEquals("B:D", e.selectionRef())
        e.selectRows(4, 2)
        assertEquals("3:5", e.selectionRef())
        e.selectAll()
        assertEquals("the whole sheet shows its active cell", "A1", e.selectionRef())
    }

    @Test
    fun theNameBoxGoesToReferencesOnAnySheet() {
        val e = editor("Sheet1", "Q3 Sales")
        assertEquals(SheetEditor.GoTo.SELECTED, e.goTo("C5"))
        assertEquals(listOf(4, 2, 4, 2), e.block())
        assertEquals(SheetEditor.GoTo.SELECTED, e.goTo(" d10:b2 "))
        assertEquals(listOf(1, 1, 9, 3), e.block())
        assertEquals(SheetEditor.GoTo.SELECTED, e.goTo("'Q3 Sales'!A2"))
        assertEquals(1, e.sheetIndex)
        assertEquals(listOf(1, 0, 1, 0), e.block())
        assertEquals(SheetEditor.GoTo.SELECTED, e.goTo("sheet1!B:B"))
        assertEquals(0, e.sheetIndex)
        assertTrue(e.wholeColumnsSelected)
        assertEquals(SheetEditor.GoTo.SELECTED, e.goTo("2:3"))
        assertTrue(e.wholeRowsSelected)
        assertEquals(SheetEditor.GoTo.INVALID, e.goTo("Nowhere!A1"))
        assertEquals("inside Excel's grid, past the sheet's 1000 columns", SheetEditor.GoTo.INVALID, e.goTo("AMK1"))
        assertEquals(SheetEditor.GoTo.INVALID, e.goTo("  "))
        assertEquals(SheetEditor.GoTo.INVALID, e.goTo("two words"))
    }

    @Test
    fun goingPastTheEndMakesRoomWithoutAnEdit() {
        val e = editor()
        val rows = e.sheet.rowCount
        assertEquals(SheetEditor.GoTo.SELECTED, e.goTo("A500"))
        assertEquals(499, e.selRow)
        assertTrue(e.sheet.rowCount >= 500 + SheetEditor.ROOM_ROWS - 1)
        assertTrue(rows < 500)
        assertEquals(SheetEditor.GoTo.SELECTED, e.goTo("AB1"))
        assertEquals(27, e.selCol)
        assertTrue(e.sheet.colCount > 27)
        assertFalse("making room is not an edit", e.dirty)
        assertFalse(e.canUndo)
    }

    @Test
    fun aNewNameInTheNameBoxNamesTheSelection() {
        val e = editor()
        e.selectRange(1, 1, 2, 2)
        assertEquals(SheetEditor.GoTo.DEFINED, e.goTo("Totals"))
        assertEquals("Sheet1!\$B\$2:\$C\$3", e.workbook.names["Totals"])
        e.select(0, 0)
        assertEquals("names go case-insensitively", SheetEditor.GoTo.SELECTED, e.goTo("totals"))
        assertEquals(listOf(1, 1, 2, 2), e.block())
        e.undo()
        assertTrue("defining is one undo step", e.workbook.names.isEmpty())
    }

    @Test
    fun theSheetGrowsPastItsEndAndWholeSelectionsFollow() {
        val e = editor()
        val rows = e.sheet.rowCount
        e.selectColumns(2)
        e.growRows(SheetEditor.ROOM_ROWS)
        assertEquals(rows + SheetEditor.ROOM_ROWS, e.sheet.rowCount)
        assertTrue("whole columns stay whole", e.wholeColumnsSelected)
        e.selectRows(3)
        e.growCols(SheetEditor.ROOM_COLS)
        assertTrue("whole rows stay whole", e.wholeRowsSelected)
        assertFalse(e.dirty)
        repeat(200) { e.growCols(SheetEditor.ROOM_COLS) }
        assertEquals(Sheet.MAX_COLS, e.sheet.colCount)
        e.sheet.rowCount = SheetEditor.MAX_ROWS - 5
        e.growRows(SheetEditor.ROOM_ROWS)
        assertEquals(SheetEditor.MAX_ROWS, e.sheet.rowCount)
    }

    @Test
    fun definedNamesAreAbsoluteAndQuotedSoExcelReadsThemAlike() {
        val e = editor("Sales Q3", "Bob's")
        for (r in 0..4) e.setCell(r, 0, "${r + 1}")
        e.selectRange(0, 0, 4, 0)
        assertTrue(e.defineName("Units"))
        assertEquals("'Sales Q3'!\$A\$1:\$A\$5", e.workbook.names["Units"])
        e.setCell(7, 2, "=SUM(Units)")
        assertEquals("15", e.display(7, 2))
        e.selectSheet(1)
        e.setCell(0, 0, "4")
        e.selectRange(0, 0, 0, 0)
        assertTrue(e.defineName("Four"))
        assertEquals("'Bob''s'!\$A\$1", e.workbook.names["Four"])
        e.selectSheet(0)
        e.setCell(8, 2, "=Four*2")
        assertEquals("a doubled quote in the sheet name", "8", e.display(8, 2))
        for (bad in listOf("A1", "R1C1", "two words", "1st")) assertFalse(bad, e.defineName(bad))
    }
}
