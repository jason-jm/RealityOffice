package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.RefCycle
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's keyboard: F4's $, Ctrl+arrows, Ctrl+Home / End, Ctrl+; and the formatting keys' commands. */
class ExcelShortcutsTest {

    @Test
    fun f4TurnsTheReferenceAtTheCaret() {
        var t = "=A1+B2"
        val seen = mutableListOf<String>()
        repeat(4) { t = RefCycle.cycle(t, 2)!!.first; seen += t }
        assertEquals(listOf("=\$A\$1+B2", "=A\$1+B2", "=\$A1+B2", "=A1+B2"), seen)
        assertEquals("the caret after the reference", 5, RefCycle.cycle("=A1+B2", 3)!!.second)
        assertEquals("a range's both ends", "=SUM(\$A\$1:\$B\$3)", RefCycle.cycle("=SUM(A1:B3)", 9)!!.first)
        assertEquals("=Sheet1!\$C\$3", RefCycle.cycle("=Sheet1!C3", 10)!!.first)
        assertNull("inside quotes", RefCycle.cycle("=\"A1\"&B1", 3))
        assertNull("a function's name", RefCycle.cycle("=LOG10(4)", 4))
        assertNull("not a formula", RefCycle.cycle("A1", 1))
    }

    /** A1:A5 filled, A8, and D3. */
    private fun data(): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        (0..4).forEach { setCell(it, 0, "v$it") }
        setCell(7, 0, "x"); setCell(2, 3, "d")
        select(0, 0)
    }

    @Test
    fun ctrlArrowsJumpAlongTheData() {
        val e = data()
        e.jumpToEdge(1, 0); assertEquals(4, e.selRow)          // the block's end
        e.jumpToEdge(1, 0); assertEquals(7, e.selRow)          // the next filled cell
        e.jumpToEdge(1, 0); assertEquals(e.sheet.rowCount - 1, e.selRow)   // nothing more: the sheet's edge
        e.select(7, 0); e.jumpToEdge(-1, 0); assertEquals(4, e.selRow)
        e.select(0, 0); e.jumpToEdge(1, 0, extend = true)
        assertEquals(listOf(0, 4), listOf(e.rangeRowMin(), e.rangeRowMax()))
        e.select(2, 0); e.jumpToEdge(0, 1); assertEquals(3, e.selCol)
    }

    @Test
    fun homeAndEnd() {
        val e = data()
        e.select(3, 2); e.goHome(sheetStart = false); assertEquals(listOf(3, 0), listOf(e.selRow, e.selCol))
        e.goEnd(); assertEquals(listOf(7, 3), listOf(e.selRow, e.selCol))
        e.goHome(sheetStart = true); assertEquals(listOf(0, 0), listOf(e.selRow, e.selCol))
        e.sheet.freezeRows = 1; e.sheet.freezeCols = 1
        e.goHome(sheetStart = true); assertEquals("below and right of the frozen panes", listOf(1, 1), listOf(e.selRow, e.selCol))
    }

    @Test
    fun todaysDateAndTheTimeAreEntries() {
        val e = SheetEditor().apply { load(Workbook.blank()); select(0, 0) }
        val now = java.time.LocalDateTime.of(2026, 10, 22, 9, 5)
        e.enterNow(time = false, now = now)
        assertEquals(NumberFormat.DATE, e.sheet.cell(0, 0)!!.numberFormat)
        assertEquals("2026-10-22", e.displayStandard(0, 0))
        e.select(1, 0)
        e.enterNow(time = true, now = now)
        assertEquals(NumberFormat.TIME, e.sheet.cell(1, 0)!!.numberFormat)
        e.select(0, 0); e.toggleStrikethrough()
        assertTrue(e.sheet.cell(0, 0)!!.format.strikethrough)
    }
}
