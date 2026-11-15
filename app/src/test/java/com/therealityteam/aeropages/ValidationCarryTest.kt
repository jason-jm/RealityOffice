package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A cell's data validation goes where the cell is copied, pasted, filled or moved — as in Excel. */
class ValidationCarryTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    private fun SheetEditor.rule(r1: Int, c1: Int, r2: Int, c2: Int, rule: DataValidation) {
        select(r1, c1); extendSelection(r2 - r1, c2 - c1); setValidation(rule)
    }

    private fun SheetEditor.block(r1: Int, c1: Int, r2: Int, c2: Int) { select(r1, c1); extendSelection(r2 - r1, c2 - c1) }

    private val yesNo = DataValidation(0, 0, 0, 0, ValidationType.LIST, items = listOf("Yes", "No"))
    /** A rule written for its top-left cell: the cell beside it must be positive. */
    private val besidePositive = DataValidation(0, 0, 0, 0, ValidationType.CUSTOM, formula1 = "B1>0")

    private fun SheetEditor.ranges() = sheet.validations.map { listOf(it.r1, it.c1, it.r2, it.c2) }.sortedWith(compareBy({ it[0] }, { it[1] }))

    @Test
    fun pasteCarriesTheRule() {
        val e = editor()
        e.rule(0, 0, 0, 0, yesNo)
        e.typeCell(0, 0, "Yes")
        e.select(0, 0); e.copySelection()
        e.select(2, 2); e.pasteSelection()
        assertEquals(ValidationType.LIST, e.validationAt(2, 2)?.type)
        assertNotNull("the copied cell keeps its own", e.validationAt(0, 0))
        // Pasted over a column, one rule there.
        e.block(4, 2, 13, 2); e.pasteSelection()
        assertEquals(listOf(listOf(0, 0, 0, 0), listOf(2, 2, 2, 2), listOf(4, 2, 13, 2)), e.ranges())
        // Undo takes it away again.
        e.undo()
        assertNull(e.validationAt(8, 2))
    }

    @Test
    fun aRulelessCellPastedOverOneTakesItAway() {
        val e = editor()
        e.rule(0, 0, 9, 0, yesNo)
        e.select(0, 3); e.copySelection()
        e.select(4, 0); e.pasteSelection()
        assertNull(e.validationAt(4, 0))
        assertEquals("the rule's other cells keep it", listOf(listOf(0, 0, 3, 0), listOf(5, 0, 9, 0)), e.ranges())
    }

    @Test
    fun fillDownAndTheFillHandleCarryItAndItsFormulaFollows() {
        val e = editor()
        e.rule(0, 0, 0, 0, besidePositive)
        e.block(0, 0, 9, 0); e.fillDown()
        assertEquals("one rule down the column", listOf(listOf(0, 0, 9, 0)), e.ranges())
        assertEquals("still written for its top-left cell", "B1>0", e.sheet.validations.single().formula1)
        // The rule at A6 reads B6: a non-positive B6 turns an entry in A6 away, a positive B7 lets A7's through.
        e.typeCell(5, 1, "-3"); e.typeCell(6, 1, "4")
        e.select(5, 0); e.typeCell(5, 0, "x"); e.select(7, 0)
        assertNotNull(e.validationAlert)
        e.cancelEntry()
        e.select(6, 0); e.typeCell(6, 0, "x"); e.select(7, 0)
        assertNull(e.validationAlert)
        // The fill handle: two seeds with two rules repeat them.
        val f = editor()
        f.rule(0, 3, 0, 3, yesNo)
        f.rule(1, 3, 1, 3, DataValidation(0, 0, 0, 0, ValidationType.INTEGER, 1.0, 9.0))
        f.typeCell(0, 3, "Yes"); f.typeCell(1, 3, "3")
        f.block(0, 3, 1, 3); assertTrue(f.autoFill(5, 3))
        assertEquals(ValidationType.LIST, f.validationAt(2, 3)?.type)
        assertEquals(ValidationType.INTEGER, f.validationAt(3, 3)?.type)
        assertEquals(ValidationType.LIST, f.validationAt(4, 3)?.type)
        f.undo()
        assertNull(f.validationAt(4, 3))
    }

    @Test
    fun pasteSpecialValidationLeavesTheCellsAlone() {
        val e = editor()
        e.rule(0, 0, 0, 0, yesNo)
        e.typeCell(0, 0, "Yes"); e.typeCell(3, 3, "kept")
        e.select(0, 0); e.copySelection()
        e.select(3, 3); assertTrue(e.pasteSpecial(SheetEditor.PasteWhat.VALIDATION))
        assertEquals("kept", e.sheet.cell(3, 3)!!.text)
        assertEquals(ValidationType.LIST, e.validationAt(3, 3)?.type)
        // Values alone leave the rules where they are.
        e.select(6, 6); e.pasteSpecial(SheetEditor.PasteWhat.VALUES)
        assertNull(e.validationAt(6, 6))
    }

    @Test
    fun carriedRulesAreSaved() {
        val e = editor()
        e.rule(0, 0, 0, 0, yesNo)
        e.block(0, 0, 9, 0); e.fillDown()
        e.select(0, 0); e.copySelection(); e.select(0, 2); e.pasteSelection()
        val want = e.ranges()
        val xlsx = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.xlsx.XlsxWriter.write(e.workbook, it) }.toByteArray()
        val fromXlsx = com.therealityteam.aeropages.format.xlsx.XlsxReader.read(xlsx).sheets[0].validations
        assertEquals(want, fromXlsx.map { listOf(it.r1, it.c1, it.r2, it.c2) }.sortedWith(compareBy({ it[0] }, { it[1] })))
        val ods = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdsWriter.write(e.workbook, it) }.toByteArray()
        val fromOds = com.therealityteam.aeropages.format.odf.OdsReader.read(ods).sheets[0].validations
        assertTrue(fromOds.all { it.type == ValidationType.LIST })
        assertTrue((0..9).all { r -> fromOds.any { it.covers(r, 0) } } && fromOds.any { it.covers(0, 2) })
    }

    @Test
    fun cutMovesIt() {
        val e = editor()
        e.rule(0, 0, 0, 0, yesNo)
        e.select(0, 0); e.cutSelection()
        assertNull("gone with the cut", e.validationAt(0, 0))
        e.select(0, 4); e.pasteSelection()
        assertEquals(ValidationType.LIST, e.validationAt(0, 4)?.type)
        e.undo(); e.undo()
        assertNotNull("both undone: back where it was", e.validationAt(0, 0))
    }
}
