package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FlashFill
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Excel's Flash Fill: the typed examples' way of building text from the row, used for the rest of the column. */
class FlashFillTest {

    private fun row(vararg texts: String) = texts.withIndex().associate { it.index to it.value }

    private fun fill(example: Pair<Map<Int, String>, String>, vararg more: Pair<Map<Int, String>, String>, on: Map<Int, String>): String? =
        FlashFill.learn(listOf(example, *more))?.let { FlashFill.apply(it, on) }

    @Test
    fun namesSplitTurnedAndJoined() {
        assertEquals("Grace", fill(row("Ada Lovelace") to "Ada", on = row("Grace Hopper")))
        assertEquals("the last word, whatever comes before", "Hopper", fill(row("Ada Lovelace") to "Lovelace", on = row("Grace Brewster Hopper")))
        assertEquals("Hopper, Grace", fill(row("Ada Lovelace") to "Lovelace, Ada", on = row("Grace Hopper")))
        assertEquals("GH", fill(row("Ada Lovelace") to "AL", on = row("Grace Hopper")))
        assertEquals("G. Hopper", fill(row("Ada Lovelace") to "A. Lovelace", on = row("Grace Hopper")))
        assertEquals("GRACE", fill(row("Ada Lovelace") to "ADA", on = row("Grace Hopper")))
        assertEquals("two columns joined", "grace.hopper@example.com",
            fill(row("Ada", "Lovelace") to "ada.lovelace@example.com", on = row("Grace", "Hopper")))
    }

    @Test
    fun partsOfCodesAndChineseNames() {
        assertEquals("2026", fill(row("INV-2025-0001") to "2025", on = row("INV-2026-0042")))
        assertEquals("0042", fill(row("INV-2025-0001") to "0001", on = row("INV-2026-0042")))
        assertEquals("the surname, the first character", "李", fill(row("张三") to "张", on = row("李四")))
    }

    @Test
    fun aSecondExampleSettlesWhatOneLeavesOpen() {
        // One example: "B" could be the second word's initial or the last word's; the second example decides.
        val way = FlashFill.learn(listOf(row("Anna Bell Cole") to "B", row("Dan Eve Ford") to "E"))!!
        assertEquals("H", FlashFill.apply(way, row("Gil Hal Ivy Jo")))
    }

    @Test
    fun noWayWhenTheExamplesDisagree() {
        assertNull(FlashFill.learn(listOf(row("Ada Lovelace") to "Ada", row("Grace Hopper") to "Hopper")))
    }

    @Test
    fun theColumnFillsInOneUndoStepPastAHeader() {
        val e = SheetEditor().apply {
            load(Workbook.blank())
            setCell(0, 0, "Full name"); setCell(0, 1, "First name")
            listOf("Ada Lovelace", "Grace Hopper", "Alan Turing", "Katherine Johnson").forEachIndexed { i, n -> setCell(i + 1, 0, n) }
            setCell(1, 1, "Ada")
            select(2, 1)
        }
        assertEquals(3, e.flashFill())
        assertEquals(listOf("Grace", "Alan", "Katherine"), (2..4).map { e.sheet.cell(it, 1)!!.text })
        assertEquals("nothing left to fill", 0, e.flashFill())
        e.undo()
        assertNull(e.sheet.cell(3, 1)?.text?.takeIf { it.isNotEmpty() })
        assertEquals("Ada", e.sheet.cell(1, 1)!!.text)
    }

    @Test
    fun noExamplesNoFill() {
        val e = SheetEditor().apply { load(Workbook.blank()); setCell(0, 0, "Ada Lovelace"); setCell(1, 0, "Grace Hopper"); select(0, 1) }
        assertEquals(-1, e.flashFill())
    }
}
