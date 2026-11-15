package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondStyle
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Sparkline
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inserting and deleting rows and columns moves what refers to cells along with them, as Excel does:
 * conditional formats' formulas (their ranges move too), defined names, and sparklines.
 */
class StructuralRefsTest {
    private val red = CondStyle(fill = 0xFFFFC7CE)

    /** Data: B2:B5 = 1, 10, 3, 12; a rule over A2:A5 lighting the rows whose B is over 5; a sparkline in D3 of A3:C3. Other: a rule on Data's B2. */
    private fun editor(): SheetEditor {
        val data = Sheet(name = "Data")
        listOf(1, 10, 3, 12).forEachIndexed { i, v -> data.ensure(i + 1, 1).text = v.toString(); data.ensure(i + 1, 0).text = "r${i + 2}" }
        data.condFormats.add(CondFormat(1, 0, 4, 0, CondType.FORMULA, formula1 = "\$B2>5", style = red))
        data.sparklines.add(Sparkline(2, 3, 2, 0, 2, 2))
        val other = Sheet(name = "Other")
        other.ensure(0, 0).text = "x"
        other.condFormats.add(CondFormat(0, 0, 0, 0, CondType.FORMULA, formula1 = "Data!\$B\$3>5", style = red))
        return SheetEditor().apply { load(Workbook(mutableListOf(data, other), names = mutableMapOf("Total" to "Data!\$B\$5"))) }
    }

    /** The rows of column A the rules light, as their A-cell texts. */
    private fun lit(e: SheetEditor): List<String> =
        (0 until e.sheet.rowCount).filter { e.conditionalLook(it, 0)?.style?.fill != null }.map { e.sheet.cell(it, 0)?.text.orEmpty() }

    @Test
    fun aRulesFormulaMovesWithItsRange() {
        val e = editor()
        assertEquals(listOf("r3", "r5"), lit(e))
        e.insertRowAt(0)
        assertEquals("\$B3>5", e.sheet.condFormats[0].formula1)
        assertEquals(2 to 5, e.sheet.condFormats[0].r1 to e.sheet.condFormats[0].r2)
        assertEquals(listOf("r3", "r5"), lit(e))
        // A row inserted inside the range: the formula counts from the range's corner, which stays.
        e.insertRowAt(4)
        assertEquals("\$B3>5", e.sheet.condFormats[0].formula1)
        assertEquals(listOf("r3", "r5"), lit(e))
        e.undo(); e.undo()
        assertEquals("\$B2>5", e.sheet.condFormats[0].formula1)
        assertEquals(listOf("r3", "r5"), lit(e))
    }

    @Test
    fun rulesAndNamesElsewhereFollowTheSheetTheyName() {
        val e = editor()
        e.insertRowAt(0)
        assertEquals("Data!\$B\$4>5", e.workbook.sheets[1].condFormats[0].formula1)
        assertEquals("Data!\$B\$6", e.workbook.names["Total"])
        e.insertColAt(0)
        assertEquals("Data!\$C\$6", e.workbook.names["Total"])
        // The cell a name names goes: the name says so, as Excel's #REF! does.
        e.deleteRowAt(5)
        assertTrue(e.workbook.names["Total"]!!.contains("#REF!"))
        e.undo(); e.undo(); e.undo()
        assertEquals("Data!\$B\$5", e.workbook.names["Total"])
        assertEquals("Data!\$B\$3>5", e.workbook.sheets[1].condFormats[0].formula1)
    }

    @Test
    fun sparklinesMoveWithTheirCellsAndData() {
        val e = editor()
        e.insertRowAt(0)
        val sp = e.sheet.sparklines.single()
        assertEquals(listOf(3, 3, 3, 0, 3, 2), listOf(sp.row, sp.col, sp.r1, sp.c1, sp.r2, sp.c2))
        // A column inserted inside its data widens it; the sparkline's cell moves right.
        e.insertColAt(1)
        val wider = e.sheet.sparklines.single()
        assertEquals(listOf(3, 4, 0, 3), listOf(wider.row, wider.col, wider.c1, wider.c2))
        // Its row deleted: it goes.
        e.deleteRowAt(3)
        assertNull(e.sheet.sparklines.firstOrNull())
        e.undo()
        assertEquals(1, e.sheet.sparklines.size)
    }
}
