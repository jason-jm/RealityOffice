package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.AutoFilter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ColumnFilter
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's right-click Sort and Filter submenus, and filter values without case. */
class CellMenuSortFilterTest {

    private val yellow = 0xFFFFFF00
    private val red = 0xFFFF0000

    /** City and Amount; Rome's rows filled yellow, the second amount red. */
    private fun editor(): SheetEditor {
        val s = Sheet(name = "Data")
        val rows = listOf(listOf("City", "Amount"), listOf("Paris", "10"), listOf("Rome", "20"), listOf("PARIS", "30"), listOf("Oslo", "40"), listOf("Rome", "50"))
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, t ->
            s.cells[s.key(r, c)] = Cell(t, CharacterFormat().also {
                if (c == 0 && t == "Rome") it.highlight = yellow
                if (c == 1 && r == 2) it.color = red
            })
        } }
        return SheetEditor().apply { load(Workbook(mutableListOf(s))) }
    }

    @Test
    fun filterValuesHaveNoCase() {
        assertTrue(ColumnFilter(values = setOf("Paris")).passes("PARIS", null))
        val e = editor()
        e.sheet.autoFilter = AutoFilter(0, 0, 5, 1)
        assertEquals("Paris and PARIS listed once, as first written", listOf("Oslo", "Paris", "Rome"), e.filterValues(0).first)
        e.filterByValues(0, setOf("Paris"))
        assertEquals("both spellings stay", setOf(2, 4, 5), e.hiddenRows.toSet())
    }

    @Test
    fun theSelectedCellsColourFiltersAndSorts() {
        val e = editor()
        e.select(2, 0)                       // a yellow Rome
        e.filterByActiveCellColor(font = false)
        assertEquals("only the yellow rows stay", setOf(1, 3, 4), e.hiddenRows.toSet())
        e.undo()
        e.select(2, 1)                       // the red amount
        e.filterByActiveCellColor(font = true)
        assertEquals(setOf(1, 3, 4, 5), e.hiddenRows.toSet())
        e.undo()
        assertTrue(e.hiddenRows.isEmpty())
        // Put Selected Cell Color On Top: the yellow rows first, the header kept on top.
        e.sheet.autoFilter = null
        e.select(2, 0)
        assertTrue(e.putActiveColorOnTop(font = false))
        assertEquals(listOf("City", "Rome", "Rome", "Paris", "PARIS", "Oslo"), (0..5).map { e.sheet.cell(it, 0)!!.text })
        e.undo()
        e.select(2, 1)
        assertTrue(e.putActiveColorOnTop(font = true))
        assertEquals("20", e.sheet.cell(1, 1)!!.text)
        assertFalse("nothing to sort on an empty sheet", SheetEditor().apply { load(Workbook(mutableListOf(Sheet(name = "E")))) }.putActiveColorOnTop(font = false))
    }
}
