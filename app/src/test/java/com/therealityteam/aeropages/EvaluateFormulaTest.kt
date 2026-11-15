package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaStep
import com.therealityteam.aeropages.editor.SheetEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Excel's Evaluate Formula: references first, left to right; then the innermost call or bracket; then the whole. */
class EvaluateFormulaTest {
    private fun editor(vararg cells: Pair<String, String>): SheetEditor {
        val e = SheetEditor()
        for ((ref, text) in cells) e.sheet.ensure(ref.drop(1).toInt() - 1, ref[0] - 'A').text = text
        return e
    }

    /** Every step of the formula in [ref]: the text with the part to work out next in [brackets]. */
    private fun SheetEditor.steps(ref: String): List<String> {
        select(ref.drop(1).toInt() - 1, ref[0] - 'A')
        var s: FormulaStep = evaluationStart()!!
        val out = ArrayList<String>()
        while (true) {
            out += s.next?.let { s.text.substring(0, it.first) + "[" + s.text.substring(it) + "]" + s.text.substring(it.last + 1) } ?: s.text
            if (s.next == null) return out
            s = evaluationNext(s)
        }
    }

    @Test
    fun referencesThenCallsThenTheWhole() {
        val e = editor("A1" to "4", "A2" to "6", "B1" to "=SUM(A1:A2)*2", "B2" to "=IF(A1>3,\"big\",\"small\")")
        assertEquals(listOf("SUM([A1:A2])*2", "[SUM({4;6})]*2", "[10*2]", "20"), e.steps("B1"))
        assertEquals(listOf("IF([A1]>3,\"big\",\"small\")", "[IF(4>3,\"big\",\"small\")]", "\"big\""), e.steps("B2"))
    }

    @Test
    fun textNamesErrorsAndOtherSheets() {
        val e = editor("A1" to "4", "B1" to "=\"A1 is \"&A1", "B2" to "=Rate*A1", "B3" to "=1/(A1-4)", "B4" to "=LOG10(100)+Sheet2!A1")
        e.workbook.names["Rate"] = "0.5"
        e.addSheet(); e.workbook.sheets[1].ensure(0, 0).text = "7"
        e.selectSheet(0)
        assertEquals("a reference inside text is text", listOf("\"A1 is \"&[A1]", "[\"A1 is \"&4]", "\"A1 is 4\""), e.steps("B1"))
        assertEquals(listOf("[Rate]*A1", "0.5*[A1]", "[0.5*4]", "2"), e.steps("B2"))
        assertEquals(listOf("1/(A1-4)".let { "1/([A1]-4)" }, "1/[(4-4)]", "[1/0]", "#DIV/0!"), e.steps("B3"))
        assertEquals("LOG10( is a function, not a cell", listOf("LOG10(100)+[Sheet2!A1]", "[LOG10(100)]+7", "[2+7]", "9"), e.steps("B4"))
        // A cell without a formula has no steps.
        e.select(0, 0)
        assertNull(e.evaluationStart())
    }
}
