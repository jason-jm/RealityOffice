package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.Trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Trace Precedents / Dependents: one level per click, a range as one arrow, other sheets as one dashed arrow. */
class TraceArrowsTest {
    private fun editor(vararg cells: Pair<String, String>): SheetEditor {
        val e = SheetEditor()
        for ((ref, text) in cells) e.sheet.ensure(ref.drop(1).toInt() - 1, ref[0] - 'A').text = text
        return e
    }

    /** The arrows on the active sheet, as "source→target" (a range as A1:B2, another sheet as "sheet"). */
    private fun SheetEditor.arrows(dependent: Boolean): Set<String> = traceArrows.filter { it.sheet == sheetIndex && it.dependent == dependent }.map { a ->
        fun ref(r: Int, c: Int) = "${'A' + c}${r + 1}"
        val from = if (a.offSheet) "sheet" else if (a.r1 == a.r2 && a.c1 == a.c2) ref(a.r1, a.c1) else ref(a.r1, a.c1) + ":" + ref(a.r2, a.c2)
        "$from→${ref(a.row, a.col)}"
    }.toSet()

    private fun book() = editor(
        "A1" to "4", "A2" to "6", "A3" to "=1/0",
        "B1" to "=A1*2", "B2" to "=SUM(A1:A2)", "B3" to "=B1+B2", "B4" to "=B3+A3",
    )

    @Test
    fun precedentsOneLevelPerClick() {
        val e = book()
        e.select(2, 1)   // B3 = B1+B2
        assertEquals(Trace.ADDED, e.tracePrecedents())
        assertEquals(setOf("B1→B3", "B2→B3"), e.arrows(false))
        assertEquals(Trace.ADDED, e.tracePrecedents())
        assertEquals("B2's range is one arrow from its box", setOf("B1→B3", "B2→B3", "A1→B1", "A1:A2→B2"), e.arrows(false))
        assertEquals(Trace.NOTHING_MORE, e.tracePrecedents())
        // An error at the tail is a red arrow.
        e.select(3, 1)   // B4 = B3+A3
        e.tracePrecedents()
        assertTrue(e.traceArrows.single { it.r1 == 2 && it.c1 == 0 }.error)
        assertFalse(e.traceArrows.single { it.r1 == 2 && it.c1 == 1 && it.row == 3 }.error)
        // A cell without a formula says so.
        e.select(0, 0)
        assertEquals(Trace.NO_FORMULA, e.tracePrecedents())
    }

    @Test
    fun dependentsOneLevelPerClick() {
        val e = book()
        e.select(0, 0)   // A1
        e.traceDependents()
        assertEquals(setOf("A1→B1", "A1→B2"), e.arrows(true))
        e.traceDependents()
        assertEquals(setOf("A1→B1", "A1→B2", "B1→B3", "B2→B3"), e.arrows(true))
        e.traceDependents()
        assertTrue("then on to B4", "B3→B4" in e.arrows(true))
        assertEquals(Trace.NOTHING_MORE, e.traceDependents())
        e.select(3, 1)   // B4: nothing reads it
        assertEquals(Trace.NO_FORMULA, e.traceDependents())
    }

    @Test
    fun anotherSheetIsOneArrowAndArrowsGoAway() {
        val e = book()
        e.addSheet()
        e.selectSheet(1)
        e.sheet.ensure(0, 0).text = "=Sheet1!A1+B5"
        e.sheet.ensure(4, 1).text = "3"
        e.select(0, 0)
        e.tracePrecedents()
        assertEquals(setOf("sheet→A1", "B5→A1"), e.arrows(false))
        // Each sheet keeps its own; Remove Arrows takes one kind or all, on this sheet.
        e.selectSheet(0)
        e.select(0, 0); e.traceDependents()
        e.select(2, 1); e.tracePrecedents()
        e.removeArrows(precedents = false)
        assertTrue(e.arrows(true).isEmpty()); assertTrue(e.arrows(false).isNotEmpty())
        assertTrue(e.hasArrows(dependent = false)); assertFalse(e.hasArrows(dependent = true))
        e.selectSheet(1)
        assertTrue("the other sheet's arrows are still there", e.hasArrows())
        // An edit takes every arrow away, as in Excel.
        e.selectSheet(0)
        e.typeCell(5, 5, "9")
        assertTrue(e.traceArrows.isEmpty())
        e.select(2, 1)
        assertEquals("and tracing starts again from the first level", Trace.ADDED, e.tracePrecedents())
        assertEquals(setOf("B1→B3", "B2→B3"), e.arrows(false))
    }
}
