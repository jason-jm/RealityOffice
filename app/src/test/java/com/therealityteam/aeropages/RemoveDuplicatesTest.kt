package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Excel's Remove Duplicates: the columns compared, values not text, within the block only, formulas following the cells. */
class RemoveDuplicatesTest {

    /** Name / City / Amount with a header, and beside the block a note in column E. */
    private fun editor(): SheetEditor {
        val s = Sheet(name = "Data")
        val rows = listOf(
            listOf("Name", "City", "Amount"),
            listOf("Ann", "Paris", "10"),
            listOf("ann", "PARIS", "10.0"),     // the same as row 1: text without case, 10 = 10.0
            listOf("Bob", "Rome", "20"),
            listOf("Ann", "Oslo", "10"),
            listOf("Cid", "Rome", "20"),
        )
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, t -> s.cells[s.key(r, c)] = Cell(t) } }
        (0..5).forEach { r -> s.cells[s.key(r, 4)] = Cell("note $r") }
        s.cells[s.key(8, 0)] = Cell("=C4")      // Bob's amount
        s.cells[s.key(9, 0)] = Cell("=A3")      // the row that goes
        s.cells[s.key(10, 0)] = Cell("=SUM(C2:C6)")
        return SheetEditor().apply { load(Workbook(mutableListOf(s))) }
    }

    @Test
    fun allColumnsCompareValuesAndTheBlockAloneMoves() {
        val e = editor()
        assertEquals(1 to 4, e.removeDuplicates(0, 0, 5, 2, listOf(0, 1, 2), header = true))
        assertEquals(listOf("Ann", "Bob", "Ann", "Cid"), (1..4).map { e.sheet.cell(it, 0)!!.text })
        assertNull(e.sheet.cell(5, 0))
        assertEquals("the cells beside the block stay", "note 2", e.sheet.cell(2, 4)!!.text)
        assertEquals("=C3", e.sheet.cell(8, 0)!!.text)
        assertEquals("#REF!", e.sheet.cell(9, 0)!!.text.removePrefix("="))
        assertEquals("=SUM(C2:C5)", e.sheet.cell(10, 0)!!.text)
        e.undo()
        assertEquals("ann", e.sheet.cell(2, 0)!!.text); assertEquals("=C4", e.sheet.cell(8, 0)!!.text)
    }

    @Test
    fun theChosenColumnsAloneDecide() {
        val e = editor()
        // By Name only: ann and the second Ann go.
        assertEquals(2 to 3, e.removeDuplicates(0, 0, 5, 2, listOf(0), header = true))
        assertEquals(listOf("Ann", "Bob", "Cid"), (1..3).map { e.sheet.cell(it, 0)!!.text })
        e.undo()
        // By City: PARIS repeats Paris, the second Rome repeats Rome.
        assertEquals(2 to 3, e.removeDuplicates(0, 0, 5, 2, listOf(1), header = true))
        assertEquals(listOf("Paris", "Rome", "Oslo"), (1..3).map { e.sheet.cell(it, 1)!!.text })
        e.undo()
        // Without a header the header row is data too (unique here); no columns, nothing.
        assertEquals(1 to 5, e.removeDuplicates(0, 0, 5, 2, listOf(0, 1, 2), header = false))
        e.undo()
        assertEquals(0 to 0, e.removeDuplicates(0, 0, 5, 2, emptyList(), header = true))
    }

    /** With `-Daero.sampleDir`: the sheet with its duplicates, for the dialog in the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "duplicates.xlsx").outputStream().use { com.therealityteam.aeropages.format.xlsx.XlsxWriter.write(editor().workbook, it) }
    }

    @Test
    fun inATableTheTableShrinksAndItsTotalRowMovesUp() {
        val s = Sheet(name = "Data")
        val rows = listOf(listOf("Name", "Amount"), listOf("Ann", "10"), listOf("Bob", "20"), listOf("Ann", "10"), listOf("Cid", "30"), listOf("Total", "=SUBTOTAL(109,C2:C5)"))
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, t -> s.cells[s.key(r, c)] = Cell(t) } }
        s.tables += com.therealityteam.aeropages.model.SheetTable("Sales", 0, 0, 5, 1, headerRow = true, totalsRow = true)
        val e = SheetEditor().apply { load(Workbook(mutableListOf(s))) }
        assertEquals(1 to 3, e.removeDuplicates(0, 0, 4, 1, listOf(0, 1), header = true))
        val t = e.sheet.tables.single()
        assertEquals("the table lost the row", 4, t.r2)
        assertEquals(listOf("Ann", "Bob", "Cid", "Total"), (1..4).map { e.sheet.cell(it, 0)!!.text })
        assertNull("nothing is left under the moved total row", e.sheet.cell(5, 0))
        e.undo()
        assertEquals(5, e.sheet.tables.single().r2)
        assertEquals("Total", e.sheet.cell(5, 0)!!.text)
    }
}
