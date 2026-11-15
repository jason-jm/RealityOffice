package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.Sheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel semantics for blank/text cells in aggregates, memoised evaluation, and deep dependency chains. */
class FormulaSemanticsTest {

    private fun sheetWith(vararg cells: Pair<String, String>): Sheet {
        val s = Sheet(rowCount = 20_000)
        cells.forEach { (ref, t) -> val (r, c) = A1.parse(ref)!!; s.ensure(r, c).text = t }
        return s
    }

    private fun eval(s: Sheet, formula: String): String {
        s.ensure(19_000, 50).text = "=$formula"
        return FormulaEngine.display(s, 19_000, 50)
    }

    @Test fun statisticalFunctionsSkipBlankAndTextCells() {
        val s = sheetWith("A1" to "10", "A2" to "20", "A3" to "30", "A5" to "text")
        assertEquals("20", eval(s, "AVERAGE(A1:A10)"))
        assertEquals("3", eval(s, "COUNT(A1:A10)"))
        assertEquals("4", eval(s, "COUNTA(A1:A10)"))
        assertEquals("10", eval(s, "MIN(A1:A10)"))
        assertEquals("30", eval(s, "MAX(A1:A10)"))
        assertEquals("6000", eval(s, "PRODUCT(A1:A10)"))
        assertEquals("60", eval(s, "SUM(A1:A10)"))
        assertEquals("20", eval(s, "MEDIAN(A1:A10)"))
        assertEquals("10", eval(s, "STDEV(A1:A10)"))
        assertEquals("TRUE", eval(s, "AND(A1:A10)"))
        assertEquals("30", eval(s, "LARGE(A1:A10,1)"))
        assertEquals("1", eval(s, "RANK(30,A1:A10)"))
    }

    @Test fun theLogicalConstantsAreValues() {
        val s = sheetWith("A1" to "7")
        assertEquals("TRUE", eval(s, "IF(A1>5,TRUE,FALSE)"))
        assertEquals("FALSE", eval(s, "IF(A1>9,true,false)"))
        assertEquals("TRUE", eval(s, "AND(TRUE,A1>5)"))
        assertEquals("TRUE", eval(s, "NOT(FALSE)"))
        assertEquals("2", eval(s, "TRUE+1"))
        assertEquals("TRUE", eval(s, "TRUE()"))
    }

    @Test fun sumproductStaysPositional() {
        // Blank B2 must still line up with A2 (as 0), not collapse the vectors.
        val s = sheetWith("A1" to "1", "A2" to "2", "A3" to "3", "B1" to "4", "B3" to "6")
        assertEquals("22", eval(s, "SUMPRODUCT(A1:A3,B1:B3)"))
    }

    @Test fun scalarArgumentsStillCount() {
        val s = sheetWith("A1" to "5")
        assertEquals("3", eval(s, "COUNT(1,2,A1)"))
        assertEquals("2", eval(s, "AVERAGE(1,3)"))
    }

    @Test fun memoSharesEvaluationAcrossCells() {
        val s = sheetWith("A1" to "1")
        for (r in 1 until 300) s.ensure(r, 0).text = "=A$r+1"
        val memo = FormulaEngine.Memo()
        assertEquals("300", FormulaEngine.display(s, 299, 0, listOf(s), emptyMap(), memo))
        assertEquals("150", FormulaEngine.display(s, 149, 0, listOf(s), emptyMap(), memo))
    }

    @Test fun deepChainsEvaluateWithoutOverflowing() {
        val s = sheetWith("A1" to "1")
        for (r in 1 until 3000) s.ensure(r, 0).text = "=A$r+1"
        assertEquals("3000", FormulaEngine.display(s, 2999, 0))
        val memo = FormulaEngine.Memo()
        for (r in 2900 until 3000) FormulaEngine.display(s, r, 0, listOf(s), emptyMap(), memo)
        assertEquals("2950", FormulaEngine.display(s, 2949, 0, listOf(s), emptyMap(), memo))
    }

    @Test fun absurdDepthReportsDepthInsteadOfCrashing() {
        val s = sheetWith("A1" to "1")
        for (r in 1 until 12_000) s.ensure(r, 0).text = "=A$r+1"
        assertEquals("#DEPTH", FormulaEngine.display(s, 11_999, 0))
    }

    @Test fun nowCarriesTimeOfDay() {
        val s = sheetWith()
        val frac = eval(s, "NOW()-TODAY()").toDouble()
        assertTrue("fraction of a day: $frac", frac >= 0.0 && frac < 1.0)
    }
}
