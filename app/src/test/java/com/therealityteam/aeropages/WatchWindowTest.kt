package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Watch Window: cells added by reference, each with its sheet, name, value and formula kept up to date. */
class WatchWindowTest {
    private fun editor(vararg cells: Pair<String, String>): SheetEditor {
        val e = SheetEditor()
        for ((ref, text) in cells) e.sheet.ensure(ref.drop(1).toInt() - 1, ref[0] - 'A').text = text
        return e
    }

    @Test
    fun watchesFollowTheirCells() {
        val e = editor("A1" to "4", "A2" to "6", "B1" to "=A1+A2")
        e.workbook.names["Total"] = "Sheet1!\$B\$1"
        e.select(0, 1)
        assertEquals("Sheet1!B1", e.watchReference())
        assertEquals(1, e.addWatches(e.watchReference()))
        assertEquals(2, e.addWatches("A1:A2"))
        assertEquals("a watched cell is watched once", 0, e.addWatches("B1"))
        assertEquals(-1, e.addWatches("not a place"))
        var rows = e.watchRows()
        assertEquals(listOf("B1", "A1", "A2"), rows.map { it.cell })
        assertEquals("Total", rows[0].name); assertEquals("10", rows[0].value); assertEquals("=A1+A2", rows[0].formula)
        assertEquals("", rows[1].formula)
        // Values change as the cells do.
        e.typeCell(0, 0, "40")
        rows = e.watchRows()
        assertEquals("46", rows[0].value)
        // Another sheet's cell; renaming follows it, going there switches sheets; deleting the sheet drops it.
        e.addSheet(); e.sheet.ensure(0, 0).text = "7"
        e.selectSheet(0)
        assertEquals(1, e.addWatches("Sheet2!A1"))
        e.renameSheet(1, "Data")
        assertEquals("Data", e.watchRows().last().sheet)
        e.goToWatch(e.watches.last())
        assertEquals(1, e.sheetIndex); assertEquals(0 to 0, e.selRow to e.selCol)
        e.selectSheet(0)
        e.deleteSheet(1)
        assertEquals(3, e.watchRows().size)
        e.removeWatch(e.watches.first())
        assertEquals(listOf("A1", "A2"), e.watchRows().map { it.cell })
        assertTrue(e.addWatches("Total") == 1)
    }

    @Test
    fun watchesMoveWithRowsAndColumnsAndComeBackOnUndo() {
        val e = editor("B6" to "5", "C6" to "=B6*2")
        assertEquals(2, e.addWatches("B6:C6"))
        // A row inserted above and a column inserted left: the watches follow their cells (B6 → C7, C6 → D7).
        e.insertRowAt(2)
        e.insertColAt(0)
        assertEquals(listOf("C7", "D7"), e.watchRows().map { it.cell })
        assertEquals(listOf("5", "10"), e.watchRows().map { it.value })
        // Undo puts them back with the cells; it no longer loses them (the sheet comes back as a copy).
        e.undo(); e.undo()
        assertEquals(listOf("B6", "C6"), e.watchRows().map { it.cell })
        e.redo()
        assertEquals(listOf("B7", "C7"), e.watchRows().map { it.cell })
        // Going to a watch read before the rows moved finds it where it is now.
        val before = e.watches.first()
        e.insertRowAt(0)
        e.goToWatch(before)
        assertEquals(7 to 1, e.selRow to e.selCol)
        // Deleting a watched cell's column takes its watch away; a copied sheet does not bring watches along.
        e.deleteColAt(1)
        assertEquals(listOf("B8"), e.watchRows().map { it.cell })
        e.duplicateSheet(0)
        assertEquals(1, e.watchRows().size)
    }
}
