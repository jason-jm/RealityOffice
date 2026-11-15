package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.GoalSeek
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Goal Seek: the pure solver and the editor command (one undo step, restored on failure). */
class GoalSeekTest {

    @Test
    fun solverFindsRootsOfLinearQuadraticAndTranscendentalFunctions() {
        assertEquals(3.0, GoalSeek.solve({ x -> 3 * x + 1 }, 10.0, 0.0)!!, 1e-6)
        assertEquals(4.0, GoalSeek.solve({ x -> x * x }, 16.0, 1.0)!!, 1e-6)                // converges from 1 to +4
        assertEquals(Math.log(20.0), GoalSeek.solve({ x -> Math.exp(x) }, 20.0, 0.0)!!, 1e-6)
        assertEquals("already at target", 5.0, GoalSeek.solve({ x -> x }, 5.0, 5.0)!!, 0.0)
        // x² = -1 has no real solution; a constant function cannot move; a null result fails.
        assertNull(GoalSeek.solve({ x -> x * x }, -1.0, 1.0))
        assertNull(GoalSeek.solve({ _ -> 7.0 }, 3.0, 0.0))
        assertNull(GoalSeek.solve({ _ -> null }, 3.0, 0.0))
    }

    @Test
    fun editorSeeksThroughTheFormulaEngineWithUndo() {
        val e = SheetEditor()
        e.load(Workbook.blank())
        e.setCell(0, 0, "2")                 // A1 input
        e.setCell(1, 0, "=A1*3+1")           // A2 = 7
        e.setCell(2, 0, "=A2*A2")            // A3 = 49
        val x = e.goalSeek(2, 0, 100.0, 0, 0)   // A3 = 100 → A2 = 10 → A1 = 3
        assertNotNull(x)
        assertEquals(3.0, x!!, 1e-6)
        assertEquals("3", e.rawValue(0, 0))
        assertEquals(100.0, e.numericValue(2, 0)!!, 1e-6)
        e.undo()
        assertEquals("input restored by undo", "2", e.rawValue(0, 0))
        assertEquals(49.0, e.numericValue(2, 0)!!, 1e-9)
        // No solution: the changing cell does not influence the set cell → restored, nothing recorded.
        e.setCell(0, 1, "5")
        assertNull(e.goalSeek(2, 0, 1.0, 0, 1))
        assertEquals("5", e.rawValue(0, 1))
        assertEquals("2", e.rawValue(0, 0))
        // Large magnitudes are not mangled by the rounding step.
        e.setCell(3, 0, "1"); e.setCell(4, 0, "=A4*2")
        assertEquals(1.5e9, e.goalSeek(4, 0, 3e9, 3, 0)!!, 1e-3)
        assertEquals("1500000000", e.rawValue(3, 0))
        // Same cell for both → refused; a formula as the changing cell → refused (Excel needs a value there).
        assertNull(e.goalSeek(0, 0, 1.0, 0, 0))
        assertNull(e.goalSeek(2, 0, 1.0, 1, 0))
        assertEquals("=A1*3+1", e.rawValue(1, 0).let { e.sheet.cell(1, 0)!!.text })
    }
}
