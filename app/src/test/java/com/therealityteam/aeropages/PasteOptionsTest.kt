package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.PasteKind
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Paste Options button: the last paste, made again another way, until the next edit. */
class PasteOptionsTest {

    /** A1 = 10 (bold, currency), A2 = 20, A3 = =A1+A2; the block copied and pasted at C2. */
    private fun pasted(): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        setCell(0, 0, "10"); setCell(1, 0, "20"); setCell(2, 0, "=A1+A2")
        select(0, 0); toggleBold(); setNumberFormat(NumberFormat.CURRENCY_USD)
        selectRange(0, 0, 2, 0); copySelection()
        select(1, 2); pasteSelection()
    }

    @Test
    fun aPasteOffersItsOptionsAndPastesAgainAnotherWay() {
        val e = pasted()
        val mark = e.pasteOptions!!
        assertEquals(PasteKind.ALL, mark.kind)
        assertEquals(listOf(1, 2, 3, 2), listOf(mark.r1, mark.c1, mark.r2, mark.c2))
        assertEquals("=C2+C3", e.sheet.cell(3, 2)!!.text)
        assertTrue(e.sheet.cell(1, 2)!!.format.bold)

        assertTrue(e.repaste(PasteKind.VALUES))
        assertEquals(PasteKind.VALUES, e.pasteOptions!!.kind)
        assertEquals("30", e.sheet.cell(3, 2)!!.text)
        assertFalse("values without their formatting", e.sheet.cell(1, 2)!!.format.bold)

        assertTrue(e.repaste(PasteKind.FORMULAS))
        assertEquals("=C2+C3", e.sheet.cell(3, 2)!!.text)

        e.undo()
        assertNull("one undo step takes the paste away, whichever way it went", e.sheet.cell(3, 2)?.text?.takeIf { it.isNotEmpty() })
        assertNull("undo puts the button away", e.pasteOptions)
    }

    @Test
    fun transposedAndLinked() {
        val e = pasted()
        assertTrue(e.repaste(PasteKind.TRANSPOSE))
        val m = e.pasteOptions!!
        assertEquals(listOf(1, 2, 1, 4), listOf(m.r1, m.c1, m.r2, m.c2))
        assertEquals("20", e.sheet.cell(1, 3)!!.text)
        assertTrue(e.repaste(PasteKind.LINK))
        assertEquals("=A2", e.sheet.cell(2, 2)!!.text)
        assertNull("the transposed cells are gone again", e.sheet.cell(1, 3)?.text?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun anEditOrACutPutsTheButtonAway() {
        val e = pasted()
        e.setCell(8, 8, "x")
        assertNull(e.pasteOptions)
        assertFalse(e.repaste(PasteKind.VALUES))

        val c = SheetEditor().apply { load(Workbook.blank()); setCell(0, 0, "1"); select(0, 0); cutSelection(); select(4, 4); pasteSelection() }
        assertEquals("1", c.sheet.cell(4, 4)!!.text)
        assertNull("a cut's paste moves the cells", c.pasteOptions)
    }
}
