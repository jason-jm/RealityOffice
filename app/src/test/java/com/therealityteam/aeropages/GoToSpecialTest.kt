package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.GoSpecial
import com.therealityteam.aeropages.editor.SheetEditor.Special
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.ValidationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Go To and Go To Special: what each finds, as areas of one selection that formatting then takes as a whole. */
class GoToSpecialTest {
    private fun editor(vararg cells: Pair<String, String>): SheetEditor {
        val e = SheetEditor()
        for ((ref, text) in cells) e.sheet.ensure(ref.drop(1).toInt() - 1, ref[0] - 'A').text = text
        return e
    }

    /** The areas selected, as A1 ranges. */
    private fun SheetEditor.areas(): List<String> = selectedAreas().map { (r1, c1, r2, c2) ->
        if (r1 == r2 && c1 == c2) "${'A' + c1}${r1 + 1}" else "${'A' + c1}${r1 + 1}:${'A' + c2}${r2 + 1}"
    }.sorted()

    private operator fun IntArray.component5() = this[4]

    /** A small table: a heading row, numbers, a text, TRUE, a formula column, an error and gaps. */
    private fun table() = editor(
        "A1" to "Item", "B1" to "Qty", "C1" to "Total",
        "A2" to "Pens", "B2" to "4", "C2" to "=B2*2",
        "A3" to "Ink", "B3" to "TRUE", "C3" to "=B2+B4",
        "A4" to "Pads", "B4" to "7", "C4" to "=C2+C3",
        "B5" to "#N/A", "C5" to "=1/0",
    )

    @Test
    fun constantsAndFormulasOfEachKind() {
        val e = table()
        e.select(0, 0)
        assertEquals(4, e.goToSpecial(GoSpecial(Special.FORMULAS)))
        assertEquals(listOf("C2:C5"), e.areas())
        assertEquals("the active cell is the first area's corner", 1 to 2, e.selRow to e.selCol)
        e.select(0, 0)
        assertEquals("formulas coming to an error", 1, e.goToSpecial(GoSpecial(Special.FORMULAS, numbers = false, text = false, logicals = false)))
        assertEquals(listOf("C5"), e.areas())
        e.select(0, 0)
        assertEquals("numbers typed in", 2, e.goToSpecial(GoSpecial(Special.CONSTANTS, text = false, logicals = false, errors = false)))
        assertEquals(listOf("B2", "B4"), e.areas())
        e.select(0, 0)
        e.goToSpecial(GoSpecial(Special.CONSTANTS, numbers = false, text = false))
        assertEquals("TRUE and a typed error", listOf("B3", "B5"), e.areas())
        // Within a selection of more than one cell, only there.
        e.select(0, 0); e.extendSelection(4, 0)
        assertEquals(4, e.goToSpecial(GoSpecial(Special.CONSTANTS)))
        assertEquals(listOf("A1:A4"), e.areas())
    }

    @Test
    fun blanksLastCellVisibleCellsAndCurrentRegion() {
        val e = table()
        e.select(0, 0)
        assertEquals("the used range's one gap", 1, e.goToSpecial(GoSpecial(Special.BLANKS)))
        assertEquals(listOf("A5"), e.areas())
        e.select(0, 0)
        e.goToSpecial(GoSpecial(Special.LAST_CELL))
        assertEquals(listOf("C5"), e.areas())
        e.sheet.manualHiddenRows += 2
        e.select(0, 0); e.extendSelection(4, 1)
        e.goToSpecial(GoSpecial(Special.VISIBLE_CELLS))
        assertEquals("the hidden third row left out", listOf("A1:B2", "A4:B5"), e.areas())
        e.select(1, 1)
        e.goToSpecial(GoSpecial(Special.CURRENT_REGION))
        assertEquals(listOf("A1:C5"), e.areas())
        // Nothing found leaves the selection alone.
        val empty = SheetEditor()
        assertEquals(0, empty.goToSpecial(GoSpecial(Special.COMMENTS)))
        assertEquals(listOf("A1"), empty.areas())
    }

    @Test
    fun precedentsDependentsAndDifferences() {
        val e = table()
        e.select(3, 2)  // C4 = C2+C3
        e.goToSpecial(GoSpecial(Special.PRECEDENTS))
        assertEquals(listOf("C2:C3"), e.areas())
        e.select(3, 2)
        e.goToSpecial(GoSpecial(Special.PRECEDENTS, allLevels = true))
        assertEquals("through C2 and C3 to the quantities", listOf("B2:C2", "B4", "C3"), e.areas())
        e.select(1, 1)  // B2
        e.goToSpecial(GoSpecial(Special.DEPENDENTS))
        assertEquals(listOf("C2:C3"), e.areas())
        e.select(1, 1)
        e.goToSpecial(GoSpecial(Special.DEPENDENTS, allLevels = true))
        assertEquals(listOf("C2:C4"), e.areas())
        // Row differences: each row against the active cell's column.
        val d = editor("A1" to "1", "B1" to "1", "C1" to "2", "A2" to "x", "B2" to "y", "C2" to "x")
        d.select(0, 0); d.extendSelection(1, 2)
        d.goToSpecial(GoSpecial(Special.ROW_DIFFERENCES))
        assertEquals(listOf("B2", "C1"), d.areas())
    }

    @Test
    fun commentsConditionalFormatsAndValidation() {
        val e = table()
        e.sheet.ensure(0, 1).comment = "units"
        e.sheet.ensure(3, 0).comment = "check"
        e.select(0, 0)
        e.goToSpecial(GoSpecial(Special.COMMENTS))
        assertEquals(listOf("A4", "B1"), e.areas())
        e.sheet.condFormats += CondFormat(1, 1, 3, 1, CondType.GREATER, 5.0)
        e.sheet.condFormats += CondFormat(1, 2, 2, 2, CondType.LESS, 1.0)
        e.select(0, 0)
        e.goToSpecial(GoSpecial(Special.CONDITIONAL_FORMATS))
        assertEquals("joined into as few blocks as rows allow", listOf("B2:C3", "B4"), e.areas())
        e.select(2, 1)
        e.goToSpecial(GoSpecial(Special.CONDITIONAL_FORMATS, same = true))
        assertEquals("only the cells under B3's rule", listOf("B2:B4"), e.areas())
        e.sheet.validations += DataValidation(1, 0, 3, 0, ValidationType.LIST, items = listOf("Pens", "Ink", "Pads"))
        e.select(0, 0)
        e.goToSpecial(GoSpecial(Special.DATA_VALIDATION))
        assertEquals(listOf("A2:A4"), e.areas())
    }

    @Test
    fun formattingTakesEveryAreaAsOneStep() {
        val e = table()
        e.select(0, 0)
        e.goToSpecial(GoSpecial(Special.CONSTANTS, text = false, logicals = false, errors = false))   // B2, B4
        e.toggleBold()
        assertTrue(e.sheet.cell(1, 1)!!.format.bold && e.sheet.cell(3, 1)!!.format.bold)
        assertFalse("not the cell between", e.sheet.cell(2, 1)!!.format.bold)
        assertEquals("the areas are still selected", listOf("B2", "B4"), e.areas())
        e.setHighlight(0xFFFFF2CC)
        e.undo()
        assertNull(e.sheet.cell(3, 1)!!.format.highlight)
        e.undo()
        assertFalse("one undo per command, every area back", e.sheet.cell(1, 1)!!.format.bold || e.sheet.cell(3, 1)!!.format.bold)
        // Overlapping areas (Ctrl+click on a cell already selected) come back right too.
        e.select(1, 1); e.extendSelection(1, 0)   // B2:B3
        e.addArea(2, 1)                           // and B3 again
        e.clear(SheetEditor.ClearWhat.CONTENTS)
        assertTrue(e.sheet.cell(1, 1)?.text.isNullOrEmpty() && e.sheet.cell(2, 1)?.text.isNullOrEmpty())
        e.undo()
        assertEquals("4", e.sheet.cell(1, 1)!!.text); assertEquals("TRUE", e.sheet.cell(2, 1)!!.text)
        // The status bar counts every area; moving the selection lets the other areas go.
        e.select(0, 0)
        e.goToSpecial(GoSpecial(Special.CONSTANTS, text = false, logicals = false, errors = false))
        assertEquals(11.0, e.selectionStats()!!.sum, 0.0)
        e.select(0, 0)
        assertTrue(e.extraAreas.isEmpty())
    }

    @Test
    fun goToNeverDefinesANameAndRemembersReferences() {
        val e = table()
        assertFalse(e.goToReference("not a place"))
        assertTrue("no name was made of it", e.workbook.names.isEmpty())
        assertTrue(e.goToReference("B3"))
        assertEquals(2 to 1, e.selRow to e.selCol)
        assertTrue(e.goToReference("A1:C2"))
        assertEquals(listOf("A1:C2", "B3"), e.goToRecent.toList())
        e.workbook.names["Totals"] = "Sheet1!\$C\$2:\$C\$5"
        assertTrue(e.goToReference("totals"))
        assertEquals(listOf("C2:C5"), e.areas())
        assertEquals("names are listed apart, not as recent", 2, e.goToRecent.size)
    }
}
