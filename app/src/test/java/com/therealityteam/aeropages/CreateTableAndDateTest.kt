package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.FieldDates
import com.therealityteam.aeropages.model.SheetTable
import com.therealityteam.aeropages.ui.dateTimePictures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

/** Excel's Create Table guess and range, and Word's Date and Time formats. */
class CreateTableAndDateTest {
    @Test
    fun createTableGuessesTheDataAndItsHeadersAndTakesAnyRange() {
        val e = SheetEditor()
        listOf(listOf("Region", "Sales"), listOf("East", "10"), listOf("West", "20")).forEachIndexed { r, row ->
            row.forEachIndexed { c, t -> e.sheet.ensure(r, c).text = t }
        }
        e.select(1, 1)
        assertEquals("\$A\$1:\$B\$3" to true, e.tableGuess())
        // Numbers in the first row: no headers.
        val n = SheetEditor().apply { sheet.ensure(0, 0).text = "1"; sheet.ensure(1, 0).text = "2"; select(0, 0) }
        assertFalse(n.tableGuess()!!.second)
        assertNull("nothing there", SheetEditor().apply { select(5, 5) }.tableGuess())
        // A range typed in: only those rows; headers as asked.
        assertTrue(e.createTable("=\$A\$1:\$B\$2", SheetTable.DEFAULT_STYLE, headers = true))
        val t = e.sheet.tables.single()
        assertEquals(listOf(0, 0, 1, 1), listOf(t.r1, t.c1, t.r2, t.c2))
        assertTrue(t.headerRow)
        assertFalse("a range that does not read", e.createTable("=nonsense", SheetTable.DEFAULT_STYLE, headers = true))
    }

    @Test
    fun theDateFormatsFollowTheLanguage() {
        val at = LocalDateTime.of(2026, 10, 20, 14, 5)
        val zh = dateTimePictures(Locale.SIMPLIFIED_CHINESE).map { FieldDates.format(it, at) }
        assertEquals("2026/10/20", zh[0]); assertTrue("2026年10月20日" in zh); assertTrue("2026年10月20日星期二" in zh)
        val en = dateTimePictures(Locale.US).map { FieldDates.format(it, at) }
        assertEquals("10/20/2026", en[0]); assertTrue("Tuesday, October 20, 2026" in en); assertTrue("2:05 pm" in en)
        val ja = dateTimePictures(Locale.JAPANESE).map { FieldDates.format(it, at) }
        assertTrue("2026年10月20日(火)" in ja); assertTrue("火曜日" in ja)
    }
}
