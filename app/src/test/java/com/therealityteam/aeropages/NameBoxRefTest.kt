package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.ui.GridAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Name Box accepts (references, whole rows / columns, sheet-qualified ones, names) and the grid's pointer hit testing. */
class NameBoxRefTest {

    private fun target(text: String): List<Any?> = A1.parseTarget(text)!!.let { listOf(it.sheet, it.r1, it.c1, it.r2, it.c2) }

    @Test
    fun cellsAndBlocksInAnyCornerOrder() {
        assertEquals(listOf(null, 4, 2, 4, 2), target("C5"))
        assertEquals(listOf(null, 4, 2, 4, 2), target(" c5 "))
        assertEquals(listOf(null, 1, 1, 9, 3), target("\$B\$2:D10"))
        assertEquals("corners in any order", listOf(null, 1, 1, 9, 3), target("D2:B10"))
        assertEquals("Excel's last cell", listOf(null, A1.EXCEL_ROWS - 1, A1.EXCEL_COLS - 1, A1.EXCEL_ROWS - 1, A1.EXCEL_COLS - 1), target("XFD1048576"))
    }

    @Test
    fun wholeColumnsAndRows() {
        val cols = A1.parseTarget("D:B")!!
        assertTrue(cols.wholeColumns); assertFalse(cols.wholeRows)
        assertEquals(1, cols.c1); assertEquals(3, cols.c2)
        val rows = A1.parseTarget("5:3")!!
        assertTrue(rows.wholeRows); assertFalse(rows.wholeColumns)
        assertEquals(2, rows.r1); assertEquals(4, rows.r2)
        assertTrue(A1.parseTarget("\$A:\$A")!!.wholeColumns)
    }

    @Test
    fun sheetQualifiedReferences() {
        assertEquals(listOf("Sheet2", 0, 0, 0, 0), target("Sheet2!A1"))
        assertEquals(listOf("Q3 Sales", 0, 0, 1, 1), target("'Q3 Sales'!A1:B2"))
        assertEquals("a doubled quote is one quote", listOf("Bob's", 2, 0, 2, 0), target("'Bob''s'!A3"))
        assertEquals(listOf("数据", -1, 1, -1, 1), target("数据!B:B"))
        assertNull("unterminated quote", A1.parseTarget("'Q3 Sales!A1"))
        assertNull("empty sheet", A1.parseTarget("!A1"))
    }

    @Test
    fun namesAndNonsenseAreNotReferences() {
        for (text in listOf("", "Total", "销售额", "A", "A0", "XFE1", "A1048577", "A1:B2:C3", "A1:", "1:C", "ABCD1", "Revenue2026"))
            assertNull(text, A1.parseTarget(text))
    }

    @Test
    fun excelRulesForDefinedNames() {
        for (name in listOf("Total", "销售额", "_tax", "\\notes", "Q3.sales", "Revenue2026", "XFE1"))
            assertTrue(name, A1.isValidName(name))
        for (name in listOf("", "A1", "c5", "ABC12", "R", "c", "R1C1", "rc", "R2", "1st", "two words", "a-b", "B:D", "x".repeat(256)))
            assertFalse(name, A1.isValidName(name))
    }

    @Test
    fun sheetNamesAreQuotedWhenReferencesNeedIt() {
        assertEquals("Sheet1", A1.quoteSheet("Sheet1"))
        assertEquals("数据", A1.quoteSheet("数据"))
        assertEquals("'Sales Q3'", A1.quoteSheet("Sales Q3"))
        assertEquals("'2026'", A1.quoteSheet("2026"))
        assertEquals("a sheet named like a cell", "'A1'", A1.quoteSheet("A1"))
        assertEquals("'Bob''s'", A1.quoteSheet("Bob's"))
        assertEquals("'Sales Q3'!\$A\$1:\$B\$5", A1.absoluteRef("Sales Q3", 0, 0, 4, 1))
    }

    @Test
    fun gridAxisFindsTheIndexUnderAPositionSkippingHiddenOnes() {
        // Widths 100, 50 (hidden), 200, 100 from index 2 on.
        val sizes = mapOf(2 to 100f, 3 to 50f, 4 to 200f, 5 to 100f)
        val size = { i: Int -> sizes.getValue(i) }
        val hidden = setOf(3)
        assertEquals(2, GridAxis.indexAt(0f, 2, 6, hidden, size))
        assertEquals(2, GridAxis.indexAt(99.9f, 2, 6, hidden, size))
        assertEquals("the hidden index takes no room", 4, GridAxis.indexAt(100f, 2, 6, hidden, size))
        assertEquals(5, GridAxis.indexAt(350f, 2, 6, hidden, size))
        assertEquals("before the start", 2, GridAxis.indexAt(-40f, 2, 6, hidden, size))
        assertEquals("past the end", 5, GridAxis.indexAt(10_000f, 2, 6, hidden, size))
        assertEquals("before the start with the first one hidden", 4, GridAxis.indexAt(-1f, 3, 6, hidden, size))
        assertEquals("nothing shown", -1, GridAxis.indexAt(0f, 3, 4, hidden, size))
        assertEquals(0f, GridAxis.offsetOf(2, 2, hidden, size), 0f)
        assertEquals(100f, GridAxis.offsetOf(4, 2, hidden, size), 0f)
        assertEquals(300f, GridAxis.offsetOf(5, 2, hidden, size), 0f)
        assertEquals(400f, GridAxis.offsetOf(6, 2, hidden, size), 0f)
    }
}
