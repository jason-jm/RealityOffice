package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.SortLevel
import com.therealityteam.aeropages.editor.SheetEditor.SortOn
import com.therealityteam.aeropages.model.AutoFilter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Sort dialog by levels — values each with its own order, cell and font colours on top or at the bottom — and Sort by Color on a filter button. */
class SortLevelsTest {

    private val yellow = 0xFFFFFF00
    private val red = 0xFFFF0000

    /** Name, Team, Score; Banana and Date filled yellow, Cherry's score in red. */
    private fun editor(): SheetEditor {
        val s = Sheet(name = "Data")
        val rows = listOf(listOf("Name", "Team", "Score"), listOf("Apple", "B", "30"), listOf("Banana", "A", "10"), listOf("Cherry", "B", "20"),
            listOf("Date", "A", "40"), listOf("Elder", "A", "10"))
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, t ->
            s.cells[s.key(r, c)] = Cell(t, CharacterFormat().also {
                if (c == 0 && (t == "Banana" || t == "Date")) it.highlight = yellow
                if (c == 2 && r == 3) it.color = red
            })
        } }
        return SheetEditor().apply { load(Workbook(mutableListOf(s))); select(0, 0) }
    }

    private fun names(e: SheetEditor) = (1..5).map { e.sheet.cell(it, 0)!!.text }

    @Test
    fun eachLevelHasItsOwnOrder() {
        val e = editor()
        assertTrue(e.sortLevels(listOf(SortLevel(1, ascending = true), SortLevel(2, ascending = false)), header = true))
        assertEquals("team A first, its scores largest first, then team B", listOf("Date", "Banana", "Elder", "Apple", "Cherry"), names(e))
        e.undo()
        assertEquals(listOf("Apple", "Banana", "Cherry", "Date", "Elder"), names(e))
        assertFalse("no level on the data's columns", e.sortLevels(listOf(SortLevel(9)), header = true))
    }

    @Test
    fun aColourGoesOnTopOrToTheBottom() {
        val e = editor()
        assertTrue(e.sortLevels(listOf(SortLevel(0, on = SortOn.CELL_COLOR, color = yellow)), header = true))
        assertEquals("yellow on top, the rest in their order", listOf("Banana", "Date", "Apple", "Cherry", "Elder"), names(e))
        e.undo()
        assertTrue(e.sortLevels(listOf(SortLevel(0, on = SortOn.CELL_COLOR, color = yellow, onTop = false)), header = true))
        assertEquals(listOf("Apple", "Cherry", "Elder", "Banana", "Date"), names(e))
        e.undo()
        // No fill on top, then by score: a colour level and a value level together.
        assertTrue(e.sortLevels(listOf(SortLevel(0, on = SortOn.CELL_COLOR, color = null), SortLevel(2, ascending = true)), header = true))
        assertEquals(listOf("Elder", "Cherry", "Apple", "Banana", "Date"), names(e))
        e.undo()
        assertTrue(e.sortLevels(listOf(SortLevel(2, on = SortOn.FONT_COLOR, color = red)), header = true))
        assertEquals("the red score's row on top", "Cherry", names(e).first())
        assertEquals(setOf(yellow, null), e.sortColors(0, 1..5, font = false).toSet())
        assertEquals(listOf(null, red), e.sortColors(2, 1..5, font = true).sortedBy { it ?: 0L })
    }

    @Test
    fun aFilterButtonSortsByColorAndRefilters() {
        val e = editor()
        e.sheet.autoFilter = AutoFilter(0, 0, 5, 2)
        e.sortFilterColumnByColor(0, yellow, font = false)
        assertEquals(listOf("Banana", "Date", "Apple", "Cherry", "Elder"), names(e))
        e.undo()
        assertEquals(listOf("Apple", "Banana", "Cherry", "Date", "Elder"), names(e))
        // The old two-key Custom Sort still sorts by its keys.
        e.sortByColumns(primaryCol = 1, secondaryCol = 0, ascending = true, header = true)
        assertEquals(listOf("Banana", "Date", "Elder", "Apple", "Cherry"), names(e))
    }
}
