package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetState
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review → Spelling in Sheets: the cells of words on every sheet shown, a suggestion put in in the word's own look. */
class SheetProofingTest {

    private fun book(): SheetEditor {
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        s.ensure(0, 0).text = "Quartrly report"
        s.ensure(0, 1).text = "1250"
        s.ensure(1, 0).text = "=SUM(B1:B3)"
        s.ensure(2, 0).also { c ->
            c.text = "Revenu grew"
            c.runs = mutableListOf(TextRun("Revenu", CharacterFormat(bold = true)), TextRun(" grew", CharacterFormat()))
        }
        s.ensure(3, 2).text = "the the end"
        wb.sheets.add(Sheet("Hidden").also { it.ensure(0, 0).text = "Secrt"; it.state = SheetState.HIDDEN })
        wb.sheets.add(Sheet("Notes").also { it.ensure(4, 1).text = "Folow up" })
        return SheetEditor().also { it.load(wb) }
    }

    @Test fun theCellsOfWordsOnEverySheetShownAreProofed() {
        val ed = book()
        val targets = ed.proofTargets()
        assertEquals(listOf("Quartrly report", "Revenu grew", "the the end", "Folow up"), targets.map { ed.proofText(it) })
        assertEquals(listOf(0, 0, 0, 2), targets.map { it.sheet })
        assertEquals("the word written twice", listOf(2), ed.proofread(targets).map { it.index })
    }

    @Test fun aSuggestionKeepsTheWordsLookAndUndoes() {
        val ed = book()
        val targets = ed.proofTargets()
        assertTrue(ed.applySpelling(targets[1], 0, "Revenu", "Revenue"))
        val c = ed.workbook.sheets[0].cell(2, 0)!!
        assertEquals("Revenue grew", c.text)
        assertEquals(listOf("Revenue" to true, " grew" to false), c.richRuns()!!.map { it.text to it.format.bold })
        assertFalse("the text there has changed since", ed.applySpelling(targets[1], 0, "Revenu", "Revenue"))
        // On another sheet: shown, then fixed.
        ed.showProofTarget(targets[3])
        assertEquals(2, ed.sheetIndex)
        assertEquals(4 to 1, ed.selRow to ed.selCol)
        assertTrue(ed.applySpelling(targets[3], 0, "Folow", "Follow"))
        assertEquals("Follow up", ed.workbook.sheets[2].cell(4, 1)!!.text)
        ed.undo(); ed.undo()
        assertEquals("Folow up", ed.workbook.sheets[2].cell(4, 1)!!.text)
        assertEquals("Revenu grew", ed.workbook.sheets[0].cell(2, 0)!!.text)
    }
}
