package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondStyle
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conditional formats go where their cells go, as in Excel: copied and pasted, cut, cleared. */
class CondFormatPasteTest {
    private val red = CondStyle(fill = 0xFFFFC7CE)

    /** B2:B5 = 1, 10, 3, 12 (and B10:B13 the same); a rule over A2:A5 lighting the rows whose B is over 5. */
    private fun editor(): SheetEditor {
        val s = Sheet(name = "Data", rowCount = 40)
        listOf(1, 10, 3, 12).forEachIndexed { i, v ->
            s.ensure(i + 1, 1).text = v.toString(); s.ensure(i + 9, 1).text = v.toString()
            s.ensure(i + 1, 0).text = "a${i + 2}"
        }
        s.condFormats.add(CondFormat(1, 0, 4, 0, CondType.FORMULA, formula1 = "\$B2>5", style = red))
        return SheetEditor().apply { load(Workbook(mutableListOf(s))) }
    }

    /** The rows (1-based) of column [col] the rules light. */
    private fun lit(e: SheetEditor, col: Int): List<Int> = (0 until 20).filter { e.conditionalLook(it, col)?.style?.fill != null }.map { it + 1 }

    /** Selects rows [r1]…[r2] and columns [c1]…[c2] (extendSelection moves the far corner by steps). */
    private fun SheetEditor.selectBlock(r1: Int, c1: Int, r2: Int, c2: Int) { select(r1, c1); extendSelection(r2 - r1, c2 - c1) }

    @Test
    fun copiedRulesLandOverThePastedCells() {
        val e = editor()
        assertEquals(listOf(3, 5), lit(e, 0))
        e.selectBlock(1, 0, 4, 0); e.copySelection()
        // Pasted beside it: the same rows light.
        e.select(1, 3); e.pasteSelection()
        assertEquals(listOf(3, 5), lit(e, 3))
        // Pasted lower down, its relative rows follow: rows 10–13 test B10:B13.
        e.select(9, 4); e.pasteSelection()
        assertEquals(listOf(11, 13), lit(e, 4))
        assertEquals("\$B10>5", e.sheet.condFormats.first { it.c1 == 4 }.formula1)
        // Paste Values brings no rule.
        e.select(1, 6); e.pasteSpecial(SheetEditor.PasteWhat.VALUES)
        assertTrue(lit(e, 6).isEmpty())
        // Part of a rule's range copied: that part's rule, its formula following.
        e.selectBlock(2, 0, 3, 0); e.copySelection()
        e.select(0, 7); e.pasteSelection()
        assertEquals("\$B1>5", e.sheet.condFormats.first { it.c1 == 7 }.formula1)
        e.undo(); e.undo(); e.undo(); e.undo()
        assertEquals(1, e.sheet.condFormats.size)
    }

    @Test
    fun aFillCarriesTheRulesAndGrowsTheOneItContinues() {
        val e = editor()
        // A2:A5 filled down to A13 (the fill handle): the rule now covers A2:A13 — one rule — and B10:B13 light A11, A13.
        e.selectBlock(1, 0, 4, 0)
        assertTrue(e.autoFill(12, 0))
        val rule = e.sheet.condFormats.single()
        assertEquals(listOf(1, 12), listOf(rule.r1, rule.r2))
        assertEquals(listOf(3, 5, 11, 13), lit(e, 0))
        e.undo()
        assertEquals(4, e.sheet.condFormats.single().r2)
        // Fill Right: a rule on D2:D5 (testing $B) grows over E2:E5, one rule still.
        e.sheet.condFormats.add(CondFormat(1, 3, 4, 3, CondType.FORMULA, formula1 = "\$B2>5", style = red))
        e.selectBlock(1, 3, 4, 4); e.fillRight()
        assertEquals(listOf(3, 5), lit(e, 4))
        assertEquals(listOf(3, 4), e.sheet.condFormats.first { it.c1 == 3 }.let { listOf(it.c1, it.c2) })
        assertEquals(2, e.sheet.condFormats.size)
        // A rule on one seed row of two repeats down the fill as the values do.
        e.sheet.condFormats.clear(); e.sheet.condFormats.add(CondFormat(1, 6, 1, 6, CondType.FORMULA, formula1 = "TRUE", style = red))
        e.selectBlock(1, 6, 2, 6)
        assertTrue(e.autoFill(6, 6))
        assertEquals(listOf(2, 4, 6), lit(e, 6))
    }

    @Test
    fun clearingFormatsAndCuttingTakeTheRules() {
        val e = editor()
        // Clear Formats on A3 (lit): the rest of the rule keeps lighting its rows.
        e.select(2, 0); e.clear(SheetEditor.ClearWhat.FORMATS)
        assertEquals(listOf(5), lit(e, 0))
        assertEquals(2, e.sheet.condFormats.size)
        e.undo()
        assertEquals(listOf(3, 5), lit(e, 0))
        // Cut and pasted elsewhere: the rule moves with the cells.
        e.selectBlock(1, 0, 4, 0); e.cutSelection()
        e.select(1, 2); e.pasteSelection()
        assertTrue(lit(e, 0).isEmpty())
        assertEquals(listOf(3, 5), lit(e, 2))
    }
}
