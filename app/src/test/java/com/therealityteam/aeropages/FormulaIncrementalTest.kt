package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.editor.FormulaFunctions
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dependency-tracked memo (incremental recalculation), memoised errors, and the newer functions. */
class FormulaIncrementalTest {

    private fun sheet(vararg cells: Pair<String, String>): Sheet {
        val s = Sheet()
        for ((ref, text) in cells) { val (r, c) = com.therealityteam.aeropages.model.A1.parse(ref)!!; s.ensure(r, c).text = text }
        return s
    }

    private fun key(row: Int, col: Int) = FormulaEngine.compositeKey(0, row, col)

    @Test
    fun invalidationFollowsDependenciesTransitively() {
        val s = sheet("A1" to "1", "A2" to "2", "A3" to "=A1+A2", "B1" to "=SUM(A1:A2)", "C1" to "=A3*2", "D1" to "=A2*2", "E1" to "=NOW()")
        val memo = FormulaEngine.Memo()
        listOf(2 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4).forEach { (r, c) -> FormulaEngine.display(s, r, c, listOf(s), emptyMap(), memo) }
        assertEquals("6", FormulaEngine.display(s, 0, 2, listOf(s), emptyMap(), memo))
        val dropped = memo.invalidate(0, 0, 0, 0, 0)!! // A1 changed
        assertEquals(setOf(key(2, 0), key(0, 1), key(0, 2), key(0, 4)), dropped) // A3, B1, C1 and the volatile E1 — not D1
        assertTrue(memo.values.containsKey(key(0, 3)))
        assertFalse(memo.values.containsKey(key(0, 2)))
    }

    @Test
    fun editingAFormulaDropsItsOwnValueToo() {
        // The memo used to keep an edited formula's old result: retyping a shown formula showed the old value.
        val e = SheetEditor()
        e.load(Workbook(mutableListOf(sheet("A1" to "Total", "B1" to "=A1&\"!\"", "C1" to "=B1&\"?\""))))
        assertEquals("Total!", e.display(0, 1)); assertEquals("Total!?", e.display(0, 2))
        e.select(0, 1); e.setCell(0, 1, "=LEN(A1)")
        assertEquals("5", e.display(0, 1))
        assertEquals("and what depends on it", "5?", e.display(0, 2))
        e.setCell(0, 1, "=LEN(A1)*2")   // the same cell again: one coalesced undo step
        assertEquals("10", e.display(0, 1))
        assertEquals("10?", e.display(0, 2))
        e.undo()
        assertEquals("Total!", e.display(0, 1))
    }

    @Test
    fun editorRecalculatesOnlyWhatChanged() {
        val e = SheetEditor()
        e.load(Workbook(mutableListOf(sheet("A1" to "1", "A2" to "2", "A3" to "=A1+A2", "B1" to "=SUM(A1:A2)", "C1" to "=A3*2", "D1" to "=A2*2"))))
        assertEquals("3", e.display(2, 0)); assertEquals("6", e.display(0, 2)); assertEquals("4", e.display(0, 3))
        e.select(0, 0); e.setCell(0, 0, "10")
        assertEquals("12", e.display(2, 0)); assertEquals("12", e.display(0, 1)); assertEquals("24", e.display(0, 2)); assertEquals("4", e.display(0, 3))
        e.select(1, 0); e.setCell(1, 0, "5")
        assertEquals("15", e.display(2, 0)); assertEquals("30", e.display(0, 2)); assertEquals("10", e.display(0, 3))
        // Structural edit → everything recomputes from scratch.
        e.select(0, 0); e.insertRowAt(0)
        assertEquals("15", e.display(3, 0))
        e.undo()
        assertEquals("15", e.display(2, 0))
        e.undo()
        assertEquals("12", e.display(2, 0))
    }

    @Test
    fun rangeDependenciesAndWholeColumnRanges() {
        val e = SheetEditor()
        val s = Sheet(rowCount = 9000)
        for (r in 0 until 9000) s.ensure(r, 0).text = "1"
        s.ensure(0, 1).text = "=SUM(A1:A9000)"
        s.ensure(0, 2).text = "=SUM(OFFSET(A1,0,0,3,1))"
        e.load(Workbook(mutableListOf(s)))
        assertEquals("9000", e.display(0, 1)); assertEquals("3", e.display(0, 2))
        e.select(8500, 0); e.setCell(8500, 0, "2")
        assertEquals("9001", e.display(0, 1)); assertEquals("3", e.display(0, 2))
        e.select(1, 0); e.setCell(1, 0, "7")
        assertEquals("9007", e.display(0, 1)); assertEquals("9", e.display(0, 2))
    }

    @Test
    fun errorsAreMemoisedAndRecoverAfterAFix() {
        val e = SheetEditor()
        e.load(Workbook(mutableListOf(sheet("A1" to "=B1", "B1" to "=A1", "C1" to "=A1+1", "D1" to "=1/0", "E1" to "=IFERROR(A1,\"x\")"))))
        assertEquals("#CYCLE", e.display(0, 0)); assertEquals("#CYCLE", e.display(0, 1)); assertEquals("#CYCLE", e.display(0, 2))
        assertEquals("#DIV/0!", e.display(0, 3)); assertEquals("x", e.display(0, 4))
        e.select(0, 1); e.setCell(0, 1, "5")
        assertEquals("5", e.display(0, 0)); assertEquals("6", e.display(0, 2)); assertEquals("5", e.display(0, 4))
    }

    @Test
    fun cellKeysDoNotCollideAcrossSheetsForTallSheets() {
        val a = Sheet(rowCount = 5000); a.ensure(4000, 0).text = "=1+1"
        val b = Sheet(name = "Sheet2"); b.ensure(0, 0).text = "=5"
        val memo = FormulaEngine.Memo()
        assertEquals("2", FormulaEngine.display(a, 4000, 0, listOf(a, b), emptyMap(), memo))
        assertEquals("5", FormulaEngine.display(b, 0, 0, listOf(a, b), emptyMap(), memo))
    }

    @Test
    fun newFunctions() {
        val s = sheet(
            "A1" to "10", "A2" to "20", "A3" to "30", "B1" to "x", "B2" to "y", "B3" to "z",
            "C1" to "=ROW()", "C2" to "=ROW(A3)", "C3" to "=COLUMN(B1)", "C4" to "=ROWS(A1:A3)", "C5" to "=COLUMNS(A1:B3)",
            "D1" to "=OFFSET(A1,2,0)", "D2" to "=SUM(OFFSET(A1,1,0,2,1))",
            "E1" to "=XLOOKUP(\"y\",B1:B3,A1:A3)", "E2" to "=XLOOKUP(\"q\",B1:B3,A1:A3,\"none\")",
            "F1" to "=DATEDIF(DATE(2024,1,15),DATE(2026,3,20),\"Y\")", "F2" to "=DATEDIF(DATE(2024,1,15),DATE(2026,3,20),\"M\")", "F3" to "=DATEDIF(DATE(2024,1,15),DATE(2026,3,20),\"YM\")",
            "G1" to "=NETWORKDAYS(DATE(2026,9,7),DATE(2026,9,18))", "G2" to "=WORKDAY(DATE(2026,9,4),1)-DATE(2026,9,7)",
            "H1" to "=MAXIFS(A1:A3,B1:B3,\"<>x\")", "H2" to "=MINIFS(A1:A3,B1:B3,\"<>x\")",
            "I1" to "=ISBLANK(Z9)", "I2" to "=ISNUMBER(A1)", "I3" to "=ISTEXT(B1)", "I4" to "=ISERROR(1/0)", "I5" to "=N(B1)", "I6" to "=ISEVEN(A1)",
            "J1" to "=TEXT(1234.5,\"#,##0.00\")", "J2" to "=TEXT(0.256,\"0.0%\")", "J3" to "=TEXT(DATE(2026,9,7),\"yyyy-mm-dd\")", "J4" to "=TEXT(DATE(2026,9,7)+0.5,\"hh:mm\")",
            "K1" to "=HYPERLINK(\"https://pico.com\",\"PICO\")", "K2" to "=RANDBETWEEN(3,3)",
        )
        fun v(ref: String): String { val (r, c) = com.therealityteam.aeropages.model.A1.parse(ref)!!; return FormulaEngine.display(s, r, c, listOf(s)) }
        assertEquals("1", v("C1")); assertEquals("3", v("C2")); assertEquals("2", v("C3")); assertEquals("3", v("C4")); assertEquals("2", v("C5"))
        assertEquals("30", v("D1")); assertEquals("50", v("D2"))
        assertEquals("20", v("E1")); assertEquals("none", v("E2"))
        assertEquals("2", v("F1")); assertEquals("26", v("F2")); assertEquals("2", v("F3"))
        assertEquals("10", v("G1")); assertEquals("0", v("G2"))
        assertEquals("30", v("H1")); assertEquals("20", v("H2"))
        assertEquals("TRUE", v("I1")); assertEquals("TRUE", v("I2")); assertEquals("TRUE", v("I3")); assertEquals("TRUE", v("I4")); assertEquals("0", v("I5")); assertEquals("TRUE", v("I6"))
        assertEquals("1,234.50", v("J1")); assertEquals("25.6%", v("J2")); assertEquals("2026-09-07", v("J3")); assertEquals("12:00", v("J4"))
        assertEquals("PICO", v("K1")); assertEquals("3", v("K2"))
    }

    @Test
    fun functionSignaturesAndFormulaBarHelpers() {
        assertEquals("SUM(number1, [number2], …)", FormulaFunctions.find("sum")!!.text)
        assertEquals(listOf("SUBSTITUTE", "SUBTOTAL", "SUM", "SUMIF", "SUMIFS", "SUMPRODUCT", "SUMSQ"), FormulaFunctions.matching("su").map { it.name })
        assertEquals("SU", FormulaFunctions.trailingIdentifier("=A1+SU"))
        assertNull(FormulaFunctions.trailingIdentifier("=A1+B2"))
        assertNull(FormulaFunctions.trailingIdentifier("SU"))
        val (sig, arg) = FormulaFunctions.openCall("=IF(SUM(A1:A2)>1, ")!!
        assertEquals("IF", sig.name); assertEquals(1, arg)
        val (sig2, arg2) = FormulaFunctions.openCall("=IF(SUM(A1,")!!
        assertEquals("SUM", sig2.name); assertEquals(1, arg2)
        assertNull(FormulaFunctions.openCall("=IF(SUM(A1:A2)>1, 1, 2)"))
        // Every function the engine dispatches has a signature.
        val src = java.io.File("src/main/java/com/therealityteam/aeropages/editor/FormulaEngine.kt").readText()
        val dispatched = Regex("\"([A-Z][A-Z0-9.]+)\"\\s*(?:,\\s*\"[A-Z][A-Z0-9.]+\"\\s*)*->").findAll(src).flatMap { m -> Regex("\"([A-Z][A-Z0-9.]+)\"").findAll(m.value).map { it.groupValues[1] } }.toSet()
        val missing = dispatched.filter { FormulaFunctions.find(it) == null && it !in setOf("AVG", "YM", "MD", "YD") }
        assertTrue("missing signatures: $missing", missing.isEmpty())
    }
}
