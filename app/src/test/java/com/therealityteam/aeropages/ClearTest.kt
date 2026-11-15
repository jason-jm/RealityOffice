package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.ClearWhat
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Clear menu, and Delete clearing the contents only. */
class ClearTest {

    /** A1 "5" bold, percent, bordered, with a comment and a link; A1:B1 merged; B2 "x". */
    private fun editor() = SheetEditor().apply {
        load(Workbook.blank())
        setCell(0, 0, "5"); setCell(1, 1, "x")
        select(0, 0)
        toggleBold(); setNumberFormat(NumberFormat.PERCENT); setComment("note")
        sheet.cell(0, 0)!!.apply { link = "https://example.com"; borderBottom = BorderLine.DOUBLE }
        select(0, 0); extendSelection(0, 1); mergeCells(center = false)
        select(0, 0); extendSelection(1, 1)   // A1:B2
    }

    @Test
    fun deleteClearsTheContentsOnly() {
        val e = editor()
        e.clearSelection()
        val a1 = e.sheet.cell(0, 0)!!
        assertEquals("", a1.text)
        assertTrue("formats stay", a1.format.bold)
        assertEquals(NumberFormat.PERCENT, a1.numberFormat)
        assertEquals(BorderLine.DOUBLE, a1.borderBottom)
        assertEquals("note", a1.comment)
        assertEquals("https://example.com", a1.link)
        assertEquals("merges stay", 1, e.sheet.merges.size)
        assertNull("a cell left with nothing goes", e.sheet.cell(1, 1))
        e.setCell(0, 0, "0.25")
        assertEquals("the percentage format is still there", "25.0%", e.display(0, 0))
    }

    @Test
    fun clearFormatsKeepsTheText() {
        val e = editor()
        e.sheet.cell(1, 1)!!.unlocked = true
        e.clear(ClearWhat.FORMATS)
        val a1 = e.sheet.cell(0, 0)!!
        assertEquals("5", a1.text)
        assertFalse(a1.format.bold)
        assertEquals(NumberFormat.GENERAL, a1.numberFormat)
        assertNull(a1.borderBottom)
        assertEquals("the comment is not a format", "note", a1.comment)
        assertTrue("merges are undone, as in Excel", e.sheet.merges.isEmpty())
        assertFalse("locking is a format too", e.sheet.cell(1, 1)!!.unlocked)
        e.undo()
        assertEquals("one undo step, merge included", 1, e.sheet.merges.size)
        assertTrue(e.sheet.cell(0, 0)!!.format.bold)
    }

    @Test
    fun clearAllCommentsAndHyperlinks() {
        val e = editor()
        e.clear(ClearWhat.COMMENTS)
        assertNull(e.sheet.cell(0, 0)!!.comment)
        assertEquals("5", e.sheet.cell(0, 0)!!.text)
        e.clear(ClearWhat.HYPERLINKS)
        assertNull(e.sheet.cell(0, 0)!!.link)
        assertTrue(e.sheet.cell(0, 0)!!.format.bold)
        e.clear(ClearWhat.ALL)
        assertNull(e.sheet.cell(0, 0))
        assertNull(e.sheet.cell(1, 1))
        assertTrue(e.sheet.merges.isEmpty())
    }

    @Test
    fun aProtectedSheetAndCut() {
        val e = editor()
        e.toggleProtection()
        e.clear(ClearWhat.FORMATS)
        assertTrue(e.sheet.cell(0, 0)!!.format.bold)
        e.cutSelection()
        assertEquals("cut refused on a protected sheet", "5", e.sheet.cell(0, 0)!!.text)
        e.toggleProtection()
        e.cutSelection()
        assertNull("cut takes the formats along", e.sheet.cell(0, 0))
        e.select(5, 5); e.pasteSelection()
        assertTrue(e.sheet.cell(5, 5)!!.format.bold)
        assertEquals("5", e.sheet.cell(5, 5)!!.text)
    }
}
