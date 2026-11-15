package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.PasteOp
import com.therealityteam.aeropages.editor.SheetEditor.PasteWhat
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Paste Special: values, formulas, formats, comments, operations, skip blanks, transpose, Paste Link and tiling. */
class PasteSpecialTest {

    /** A1 1 (bold, 0.0%), A2 2, A3 =A1+A2 with a comment. */
    private fun editor() = SheetEditor().apply {
        load(Workbook.blank())
        setCell(0, 0, "1"); setCell(1, 0, "2"); setCell(2, 0, "=A1+A2")
        select(0, 0); toggleBold(); setNumberFormat(NumberFormat.PERCENT)
        select(2, 0); setComment("total")
    }

    private fun SheetEditor.copy(r1: Int, c1: Int, r2: Int, c2: Int) { select(r1, c1); extendSelection(r2 - r1, c2 - c1); copySelection() }
    private fun SheetEditor.text(r: Int, c: Int) = sheet.cell(r, c)?.text ?: ""

    @Test
    fun valuesFormulasAndAll() {
        val e = editor()
        e.copy(0, 0, 2, 0)
        e.select(0, 2); assertTrue(e.pasteSpecial(PasteWhat.VALUES))
        assertEquals("the value, not the formula", "3", e.text(2, 2))
        assertFalse("values leave formats behind", e.sheet.cell(0, 2)!!.format.bold)
        e.select(0, 3); e.pasteSpecial(PasteWhat.FORMULAS)
        assertEquals("=D1+D2", e.text(2, 3))
        assertNull("formulas only: no comment", e.sheet.cell(2, 3)!!.comment)
        assertFalse(e.sheet.cell(0, 3)!!.format.bold)
        e.select(0, 4); e.pasteSelection()
        assertEquals("=E1+E2", e.text(2, 4))
        assertTrue("all of it", e.sheet.cell(0, 4)!!.format.bold)
        assertEquals("total", e.sheet.cell(2, 4)!!.comment)
        e.undo()
        assertEquals("one undo step per paste", "", e.text(2, 4))
    }

    @Test
    fun formatsValuesWithNumberFormatsAndComments() {
        val e = editor()
        e.setCell(4, 5, "0.5")
        e.copy(0, 0, 0, 0)
        e.select(4, 5); e.pasteSpecial(PasteWhat.FORMATS)
        assertEquals("0.5", e.text(4, 5))
        assertTrue(e.sheet.cell(4, 5)!!.format.bold)
        assertEquals("50.0%", e.display(4, 5))
        e.select(6, 5); e.pasteSpecial(PasteWhat.VALUES_AND_NUMBER_FORMATS)
        assertEquals("1", e.text(6, 5))
        assertEquals(NumberFormat.PERCENT, e.sheet.cell(6, 5)!!.numberFormat)
        assertFalse(e.sheet.cell(6, 5)!!.format.bold)
        e.copy(2, 0, 2, 0)
        e.setCell(8, 5, "keep")
        e.select(8, 5); e.pasteSpecial(PasteWhat.COMMENTS)
        assertEquals("keep", e.text(8, 5))
        assertEquals("total", e.sheet.cell(8, 5)!!.comment)
    }

    @Test
    fun operationsCombineWithWhatIsThere() {
        val e = editor()
        e.setCell(0, 1, "10"); e.setCell(1, 1, "20"); e.setCell(2, 1, "=B1*2"); e.setCell(3, 1, "text")
        e.setCell(0, 2, "5")
        e.copy(0, 2, 0, 2)
        // One copied cell over a selected column: it is pasted onto each.
        e.select(0, 1); e.extendSelection(3, 0)
        e.pasteSpecial(PasteWhat.VALUES, PasteOp.ADD)
        assertEquals("15", e.text(0, 1))
        assertEquals("25", e.text(1, 1))
        assertEquals("a formula gains the operation", "=(B1*2)+5", e.text(2, 1))
        assertEquals("text stays", "text", e.text(3, 1))
        e.select(0, 1); e.pasteSpecial(PasteWhat.VALUES, PasteOp.MULTIPLY)
        assertEquals("75", e.text(0, 1))
        e.setCell(0, 2, "0"); e.copy(0, 2, 0, 2)
        e.select(0, 1); e.pasteSpecial(PasteWhat.VALUES, PasteOp.DIVIDE)
        assertEquals("#DIV/0!", e.text(0, 1))
        e.setCell(5, 1, ""); e.setCell(0, 2, "2.5"); e.copy(0, 2, 0, 2)
        e.select(5, 1); e.pasteSpecial(PasteWhat.VALUES, PasteOp.SUBTRACT)
        assertEquals("a blank counts as 0", "-2.5", e.text(5, 1))
    }

    @Test
    fun skipBlanksTransposeAndTiling() {
        val e = editor()
        e.setCell(1, 0, "")
        e.copy(0, 0, 2, 0)
        for (r in 0..2) e.setCell(r, 3, "old")
        e.select(0, 3); e.pasteSpecial(PasteWhat.ALL, skipBlanks = true)
        assertEquals("under a copied blank the cell stays", "old", e.text(1, 3))
        e.select(0, 4); e.pasteSelection()
        assertEquals("", e.text(1, 4))
        // Rows into columns; a formula's references turn with it.
        e.setCell(1, 0, "2")
        e.copy(0, 0, 2, 0)
        e.select(5, 2); e.pasteSpecial(PasteWhat.ALL, transpose = true)
        assertEquals("1", e.text(5, 2))
        assertEquals("2", e.text(5, 3))
        assertEquals("=C6+D6", e.text(5, 4))
        assertEquals("3", e.display(5, 4))
        // A copied cell over a larger block fills it.
        e.setCell(9, 0, "7")
        e.copy(9, 0, 9, 0)
        e.select(10, 3); e.extendSelection(1, 1)
        e.pasteSelection()
        for (r in 10..11) for (c in 3..4) assertEquals("7", e.text(r, c))
    }

    @Test
    fun pasteLinkFollowsTheOriginal() {
        val e = editor()
        e.copy(0, 0, 1, 0)
        e.select(0, 6); assertTrue(e.pasteLink())
        assertEquals("=A1", e.text(0, 6))
        assertEquals("=A2", e.text(1, 6))
        e.setCell(1, 0, "40")
        assertEquals("40", e.display(1, 6))
        e.addSheet()
        e.selectSheet(1)
        e.select(0, 0); e.pasteLink()
        assertEquals("=Sheet1!A1", e.text(0, 0))
    }

    @Test
    fun aProtectedSheetRefusesThePaste() {
        val e = editor()
        e.copy(0, 0, 0, 0)
        e.toggleProtection()
        e.select(5, 5)
        assertFalse(e.pasteSpecial(PasteWhat.VALUES))
        assertFalse(e.pasteLink())
        assertEquals("", e.text(5, 5))
    }
}
