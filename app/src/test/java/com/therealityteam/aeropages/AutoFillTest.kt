package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.AutoFill
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Excel's AutoFill series (numbers, dates, times, counted text, built-in lists) and the fills that use them. */
class AutoFillTest {

    /** What [count] cells after the seeds get (null = a copy of the seed). */
    private fun next(vararg seeds: String, count: Int = 3, series: Boolean = false): List<String?> =
        AutoFill.fill(seeds.map { AutoFill.Seed(it) }, (seeds.size until seeds.size + count).toList(), series).map { it.text }

    private fun serial(d: LocalDate) = (d.toEpochDay() + 25569).toString()

    @Test
    fun numbersRepeatAloneAndFollowTheirTrendTogether() {
        assertEquals("a lone number repeats", listOf(null, null, null), next("5"))
        assertEquals("…unless it is a series", listOf("6", "7", "8"), next("5", series = true))
        assertEquals(listOf("5", "7", "9"), next("1", "3"))
        assertEquals(listOf("30", "40"), next("10", "20", count = 2))
        assertEquals("no binary noise", listOf("0.3", "0.4"), next("0.1", "0.2", count = 2))
        assertEquals("the least-squares line", listOf("5.33333333333"), next("1", "2", "4", count = 1))
        val up = AutoFill.fill(listOf(AutoFill.Seed("5"), AutoFill.Seed("6")), listOf(-1, -2)).map { it.text }
        assertEquals("filling up counts down", listOf("4", "3"), up)
        assertEquals("a lone negative number repeats too", listOf(null), next("-1", count = 1))
    }

    @Test
    fun numbersInCurrencyPercentOrGroupedDressStayNumbers() {
        assertEquals("a lone one repeats", listOf(null), next("$5", count = 1))
        assertEquals(listOf("$15", "$20"), next("$5", "$10", count = 2))
        assertEquals(listOf("30%"), next("10%", "20%", count = 1))
        assertEquals(listOf("3,000"), next("1,000", "2,000", count = 1))
        assertEquals("decimals and grouping kept", listOf("$1,349.50"), next("$1,250.50", "$1,300.00", count = 1))
        assertEquals("a series counts up", listOf("¥11", "¥12"), next("¥10", count = 2, series = true))
    }

    @Test
    fun datesGoByDayMonthOrYear() {
        fun dates(vararg d: LocalDate, count: Int = 2) = AutoFill.fill(d.map { AutoFill.Seed(serial(it), NumberFormat.DATE) }, (d.size until d.size + count).toList()).map { it.text }
        assertEquals("a lone date counts up a day", listOf(serial(LocalDate.of(2026, 9, 29)), serial(LocalDate.of(2026, 9, 30))), dates(LocalDate.of(2026, 9, 28)))
        assertEquals("month ends stay month ends",
            listOf(serial(LocalDate.of(2026, 3, 31)), serial(LocalDate.of(2026, 4, 30))), dates(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)))
        assertEquals("the same day each month",
            listOf(serial(LocalDate.of(2026, 3, 15)), serial(LocalDate.of(2026, 4, 15))), dates(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15)))
        assertEquals("a year apart, leap days included",
            listOf(serial(LocalDate.of(2026, 2, 28)), serial(LocalDate.of(2027, 2, 28)), serial(LocalDate.of(2028, 2, 29))),
            dates(LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 28), count = 3))
        assertEquals("a week apart", listOf(serial(LocalDate.of(2026, 1, 15))), dates(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 8), count = 1))
        val time = AutoFill.fill(listOf(AutoFill.Seed("0.375", NumberFormat.TIME)), listOf(1)).map { it.text }
        assertEquals("a lone time counts up an hour", listOf("0.416666666667"), time)
    }

    @Test
    fun datesAndTimesWrittenAsText() {
        assertEquals(listOf("2026-09-29", "2026-09-30", "2026-10-01"), next("2026-09-28"))
        assertEquals("unpadded stays unpadded", listOf("2026/10/1"), next("2026/9/30", count = 1))
        assertEquals(listOf("2026年3月31日", "2026年4月30日"), next("2026年1月31日", "2026年2月28日", count = 2))
        assertEquals(listOf("2025-12-31"), AutoFill.fill(listOf(AutoFill.Seed("2026-01-01")), listOf(-1)).map { it.text })
        assertEquals("not a date: text counting on from its last number, as in Excel", listOf("2026-02-31"), next("2026-02-30", count = 1))
        assertEquals(listOf("10:00", "11:00"), next("9:00", count = 2))
        assertEquals(listOf("10:30", "11:00"), next("09:30", "10:00", count = 2))
        assertEquals("round the clock", listOf("00:00"), next("23:00", count = 1))
    }

    @Test
    fun textCountsOnFromItsNumber() {
        assertEquals(listOf("Item 2", "Item 3"), next("Item 1", count = 2))
        assertEquals(listOf("Q4"), next("Q3", count = 1))
        assertEquals("zero padding kept", listOf("Room 010"), next("Room 009", count = 1))
        assertEquals(listOf("第2季度"), next("第1季度", count = 1))
        assertEquals(listOf("Week 6", "Week 8"), next("Week 2", "Week 4", count = 2))
        assertEquals(listOf("4 apples"), next("3 apples", count = 1))
        assertEquals("ordinals", listOf("2nd", "3rd", "4th"), next("1st"))
        val ords = AutoFill.fill(listOf(AutoFill.Seed("1st Period")), listOf(10, 20, 21)).map { it.text }
        assertEquals(listOf("11th Period", "21st Period", "22nd Period"), ords)
        assertEquals("different prefixes repeat", listOf(null), next("A1x", "B2x", count = 1))
    }

    @Test
    fun builtInListsCycle() {
        assertEquals(listOf("Tue", "Wed"), next("Mon", count = 2))
        assertEquals("round the week", listOf("Sun"), next("Sat", count = 1))
        assertEquals("capitals kept", listOf("TUE"), next("MON", count = 1))
        assertEquals(listOf("tuesday"), next("monday", count = 1))
        assertEquals("May continues the short months, as in Excel", listOf("Jun"), next("May", count = 1))
        assertEquals(listOf("March"), next("January", "February", count = 1))
        assertEquals(listOf("星期日"), next("星期六", count = 1))
        assertEquals("the seeds' step", listOf("周五", "周日"), next("周一", "周三", count = 2))
        assertEquals(listOf("十二月", "一月"), next("十一月", count = 2))
        assertEquals(listOf("第一季"), next("第四季", count = 1))
        assertEquals(listOf("乙", "丙"), next("甲", count = 2))
        assertEquals(listOf("子"), next("亥", count = 1))
        assertEquals(listOf("土曜日"), next("金曜日", count = 1))
        assertEquals("months written with digits wrap", listOf("1月"), next("12月", count = 1))
        assertEquals("quarters wrap", listOf("Q1"), next("Q4", count = 1))
        assertEquals(listOf("Qtr4", "Qtr1"), next("Qtr3", count = 2))
        val back = AutoFill.fill(listOf(AutoFill.Seed("Jan")), listOf(-1)).map { it.text }
        assertEquals(listOf("Dec"), back)
    }

    @Test
    fun anythingElseRepeatsTheSeeds() {
        assertEquals(listOf(null, null), next("apple", count = 2))
        assertEquals("mixed seeds", listOf(null, null), next("a", "1", count = 2))
        val steps = AutoFill.fill(listOf(AutoFill.Seed("=A1"), AutoFill.Seed("x")), listOf(2, 3, 4, 5))
        assertEquals("seeds cycle", listOf(0, 1, 0, 1), steps.map { it.copyOf })
        assertEquals("by whole blocks", listOf(2, 2, 4, 4), steps.map { it.shift })
    }

    // --- the editor ------------------------------------------------------------------------------

    private fun editor(vararg cells: Triple<Int, Int, String>) = SheetEditor().apply {
        load(Workbook.blank())
        for ((r, c, t) in cells) setCell(r, c, t)
    }

    @Test
    fun fillDownAndRightMoveRelativeReferences() {
        val e = editor(Triple(0, 0, "=B1*2"), Triple(0, 3, "=\$B\$1+C1"))
        e.select(0, 0); e.extendSelection(2, 0)
        e.fillDown()
        assertEquals("=B2*2", e.sheet.cell(1, 0)?.text)
        assertEquals("=B3*2", e.sheet.cell(2, 0)?.text)
        e.select(0, 3); e.extendSelection(0, 2)
        e.fillRight()
        assertEquals("absolute parts stay", "=\$B\$1+D1", e.sheet.cell(0, 4)?.text)
        assertEquals("=\$B\$1+E1", e.sheet.cell(0, 5)?.text)
    }

    @Test
    fun theFillHandleContinuesEachLineAndSelectsTheFill() {
        val e = editor(Triple(0, 0, "Item 1"), Triple(0, 1, "=C1+1"))
        e.sheet.cell(0, 0)!!.format.bold = true
        e.select(0, 0); e.extendSelection(0, 1)
        val depth = e.undoDepth
        assertTrue(e.autoFill(4, 1))
        assertEquals((2..5).map { "Item $it" }, (1..4).map { e.sheet.cell(it, 0)?.text })
        assertEquals("formats go along", true, e.sheet.cell(3, 0)?.format?.bold)
        assertEquals("=C4+1", e.sheet.cell(3, 1)?.text)
        assertEquals("seeds and fill selected", listOf(0, 0, 4, 1), listOf(e.rangeRowMin(), e.rangeColMin(), e.rangeRowMax(), e.rangeColMax()))
        assertEquals(depth + 1, e.undoDepth)
        e.undo()
        assertNull("one undo step", e.sheet.cell(4, 0))
        assertFalse("inside the block", e.autoFill(0, 1))
    }

    @Test
    fun theFillHandleGoesUpAndSideways() {
        val e = editor(Triple(5, 0, "Mon"), Triple(0, 2, "1"), Triple(0, 3, "2"))
        e.select(5, 0)
        assertTrue(e.autoFill(3, 0))
        assertEquals(listOf("Sat", "Sun"), listOf(e.sheet.cell(3, 0)?.text, e.sheet.cell(4, 0)?.text))
        e.select(0, 2); e.extendSelection(0, 1)
        assertTrue(e.autoFill(0, 6))
        assertEquals(listOf("3", "4", "5"), (4..6).map { e.sheet.cell(0, it)?.text })
        // The target's larger offset picks the direction.
        e.select(0, 0); e.extendSelection(1, 1)
        assertEquals(listOf(2, 0, 5, 1), e.autoFillRange(5, 3)!!.toList())
        assertEquals(listOf(0, 2, 1, 6), e.autoFillRange(2, 6)!!.toList())
    }

    @Test
    fun thePreviewShowsTheLastValueFormatted() {
        val e = editor(Triple(0, 0, "Item 1"), Triple(0, 1, serial(LocalDate.of(2026, 9, 28))))
        e.sheet.cell(0, 1)!!.numberFormat = NumberFormat.DATE
        e.select(0, 0)
        assertEquals("Item 10", e.autoFillPreview(9, 0))
        assertNull(e.autoFillPreview(0, 0))
        e.select(0, 1)
        e.dates = com.therealityteam.aeropages.model.FormatCode.Lang.EN
        assertEquals("the language's short date, as Excel shows its built-in date", "10/8/2026", e.autoFillPreview(10, 1))
        e.dates = null
        assertEquals("2026-10-08", e.autoFillPreview(10, 1))
    }

    @Test
    fun doubleTappingTheHandleFillsDownBesideTheData() {
        val e = editor(*(0..5).map { Triple(it, 0, "row $it") }.toTypedArray(), Triple(0, 1, "1"), Triple(1, 1, "2"))
        e.select(0, 1); e.extendSelection(1, 0)
        assertTrue(e.autoFillToAdjacentData())
        assertEquals(listOf("3", "4", "5", "6"), (2..5).map { e.sheet.cell(it, 1)?.text })
        assertNull("stops where the data stops", e.sheet.cell(6, 1))
        e.select(0, 4)
        assertFalse("nothing beside it", e.autoFillToAdjacentData())
    }
}
