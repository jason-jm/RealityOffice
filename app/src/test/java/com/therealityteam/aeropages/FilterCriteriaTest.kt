package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.AutoFilter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ColorFilter
import com.therealityteam.aeropages.model.ColumnFilter
import com.therealityteam.aeropages.model.DynamicFilter
import com.therealityteam.aeropages.model.FilterCondition
import com.therealityteam.aeropages.model.FilterCondition.Op
import com.therealityteam.aeropages.model.FilterStats
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.TopFilter
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/** Excel's Number / Text / Date Filters, Top 10, above and below the average, and Filter by Color — the model, the sheet, xlsx and ODS. */
class FilterCriteriaTest {

    @Test
    fun conditionsTakeWildcardsAndJoinByAndOrOr() {
        assertTrue(FilterCondition(Op.EQUALS, "A*").test("Apple", null))
        assertFalse(FilterCondition(Op.EQUALS, "A*").test("Banana", null))
        assertTrue(FilterCondition(Op.EQUALS, "b?n*").test("Banana", null))
        assertTrue("~* is a star itself", FilterCondition(Op.EQUALS, "5~*").test("5*", null))
        assertFalse(FilterCondition(Op.EQUALS, "5~*").test("55", null))
        assertTrue(FilterCondition(Op.NOT_EQUALS, "A*").test("Banana", null))
        assertTrue(FilterCondition(Op.NOT_BEGINS_WITH, "Ap").test("Banana", null))
        assertFalse(FilterCondition(Op.NOT_ENDS_WITH, "na").test("Banana", null))
        val between = ColumnFilter(blanks = false, condition = FilterCondition(Op.GREATER_OR_EQUAL, "20"), condition2 = FilterCondition(Op.LESS_OR_EQUAL, "80"))
        assertTrue(between.passes("50", 50.0)); assertFalse(between.passes("90", 90.0))
        val outside = ColumnFilter(blanks = false, condition = FilterCondition(Op.LESS, "20"), condition2 = FilterCondition(Op.GREATER, "80"), or = true)
        assertTrue(outside.passes("90", 90.0)); assertTrue(outside.passes("10", 10.0)); assertFalse(outside.passes("50", 50.0))
    }

    @Test
    fun topTenAverageDatesAndColorsWeighTheWholeColumn() {
        val stats = FilterStats(listOf(5.0, 10.0, 10.0, 20.0, 30.0, 40.0), today = LocalDate.of(2026, 10, 21))   // a Wednesday
        val top2 = ColumnFilter(blanks = false, top = TopFilter(top = true, count = 2.0))
        assertEquals(listOf(30.0, 40.0), listOf(5.0, 10.0, 20.0, 30.0, 40.0).filter { top2.passes("", it, stats) })
        val bottom2 = ColumnFilter(blanks = false, top = TopFilter(top = false, count = 2.0))
        assertEquals("ties all stay", listOf(5.0, 10.0, 10.0), listOf(5.0, 10.0, 10.0, 20.0).filter { bottom2.passes("", it, stats) })
        val top50 = ColumnFilter(blanks = false, top = TopFilter(count = 50.0, percent = true))
        assertTrue(top50.passes("", 20.0, stats)); assertFalse(top50.passes("", 10.0, stats))
        assertFalse("text never makes a Top 10", top2.passes("x", null, stats))
        val above = ColumnFilter(blanks = false, dynamic = DynamicFilter.ABOVE_AVERAGE)   // average 19.17
        assertTrue(above.passes("", 20.0, stats)); assertFalse(above.passes("", 10.0, stats))
        fun serial(d: LocalDate) = (d.toEpochDay() - FilterStats.EPOCH.toEpochDay()).toDouble()
        val week = ColumnFilter(blanks = false, dynamic = DynamicFilter.THIS_WEEK)
        assertTrue("Sunday starts Excel's week", week.passes("", serial(LocalDate.of(2026, 10, 18)), stats))
        assertFalse(week.passes("", serial(LocalDate.of(2026, 10, 25)), stats))
        assertTrue(ColumnFilter(blanks = false, dynamic = DynamicFilter.LAST_QUARTER).passes("", serial(LocalDate.of(2026, 7, 1)), stats))
        assertFalse(ColumnFilter(blanks = false, dynamic = DynamicFilter.YEAR_TO_DATE).passes("", serial(LocalDate.of(2026, 11, 1)), stats))
        val yellow = ColumnFilter(blanks = false, color = ColorFilter(0xFFFFFF00))
        assertTrue(yellow.passes("a", null, fill = 0xFFFFFF00)); assertFalse(yellow.passes("a", null, fill = null))
        val noFill = ColumnFilter(blanks = false, color = ColorFilter(null))
        assertTrue(noFill.passes("a", null, fill = null))
        val red = ColumnFilter(blanks = false, color = ColorFilter(0xFFFF0000, font = true))
        assertTrue(red.passes("a", null, fill = 0xFFFFFF00, font = 0xFFFF0000))
    }

    private fun sheet(): Sheet = Sheet(name = "Data").also { s ->
        s.cells[s.key(0, 0)] = Cell("Name"); s.cells[s.key(0, 1)] = Cell("Score"); s.cells[s.key(0, 2)] = Cell("Due")
        val names = listOf("Apple", "Banana", "Cherry", "Date", "Elder", "Fig")
        val scores = listOf(10, 55, 90, 30, 75, 20)
        names.forEachIndexed { i, n ->
            s.cells[s.key(i + 1, 0)] = Cell(n, CharacterFormat().also { if (i % 2 == 0) it.highlight = 0xFFFFFF00 })
            s.cells[s.key(i + 1, 1)] = Cell(scores[i].toString(), CharacterFormat().also { if (i == 2) it.color = 0xFFFF0000 })
            s.cells[s.key(i + 1, 2)] = Cell((46300 + i * 10).toString(), numberFormat = NumberFormat.DATE)
        }
        s.autoFilter = AutoFilter(0, 0, 6, 2)
    }

    @Test
    fun theSheetFiltersByThemAndKnowsWhichKindToOffer() {
        val e = SheetEditor().apply { load(Workbook(mutableListOf(sheet()))) }
        assertEquals(SheetEditor.FilterKind.TEXT, e.filterKind(0))
        assertEquals(SheetEditor.FilterKind.NUMBER, e.filterKind(1))
        assertEquals(SheetEditor.FilterKind.DATE, e.filterKind(2))
        val (fills, fonts) = e.filterColors(0)
        assertEquals(listOf(0xFFFFFF00, null), fills); assertTrue("one font colour lists none", fonts.isEmpty())
        e.setColumnFilter(1, ColumnFilter(top = TopFilter(count = 2.0)))
        assertEquals("only 90 and 75 stay", setOf(1, 2, 4, 6), e.hiddenRows.toSet())
        assertNull("a Top 10 keeps no list of values", e.columnFilter(1)?.values)
        e.setColumnFilter(1, ColumnFilter(dynamic = DynamicFilter.BELOW_AVERAGE))   // average 46.7
        assertEquals(setOf(2, 3, 5), e.hiddenRows.toSet())
        e.setColumnFilter(1, null)
        e.setColumnFilter(0, ColumnFilter(color = ColorFilter(0xFFFFFF00)))
        assertEquals(setOf(2, 4, 6), e.hiddenRows.toSet())
        e.undo()
        assertTrue(e.hiddenRows.isEmpty())
    }

    private fun filtered(): Workbook = Workbook(mutableListOf(sheet().also { s ->
        s.autoFilter!!.columns[0] = ColumnFilter(blanks = false, color = ColorFilter(0xFFFFFF00))
        s.autoFilter!!.columns[1] = ColumnFilter(blanks = false, condition = FilterCondition(Op.LESS, "20"), condition2 = FilterCondition(Op.GREATER, "80"), or = true)
        s.autoFilter!!.columns[2] = ColumnFilter(blanks = false, top = TopFilter(top = false, count = 25.0, percent = true))
    }))

    @Test
    fun excelKeepsThem() {
        val wb = filtered()
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!)
        assertTrue(xml, xml.contains("""<customFilters><customFilter operator="lessThan" val="20"/><customFilter operator="greaterThan" val="80"/></customFilters>"""))
        assertTrue(xml, xml.contains("""<top10 top="0" percent="1" val="25"/>"""))
        assertTrue(xml, xml.contains("""<colorFilter dxfId="0"/>"""))
        assertTrue(String(OfficeXml.unzip(bytes)["xl/styles.xml"]!!).contains("""<dxf><fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/><bgColor rgb="FFFFFF00"/></patternFill></fill></dxf>"""))
        val back = XlsxReader.read(bytes).sheets[0].autoFilter!!
        assertEquals(wb.sheets[0].autoFilter!!.columns, back.columns)
        // And joined, a font colour, a dynamic filter, and Excel's not-begins-with.
        val more = Workbook(mutableListOf(sheet().also { s ->
            s.autoFilter!!.columns[0] = ColumnFilter(blanks = false, condition = FilterCondition(Op.NOT_BEGINS_WITH, "A"), condition2 = FilterCondition(Op.NOT_ENDS_WITH, "g"))
            s.autoFilter!!.columns[1] = ColumnFilter(blanks = false, color = ColorFilter(0xFFFF0000, font = true))
            s.autoFilter!!.columns[2] = ColumnFilter(blanks = false, dynamic = DynamicFilter.THIS_MONTH)
        }))
        val bytes2 = ByteArrayOutputStream().also { XlsxWriter.write(more, it) }.toByteArray()
        val xml2 = String(OfficeXml.unzip(bytes2)["xl/worksheets/sheet1.xml"]!!)
        assertTrue(xml2, xml2.contains("""<customFilters and="1"><customFilter operator="notEqual" val="A*"/><customFilter operator="notEqual" val="*g"/></customFilters>"""))
        assertTrue(xml2, xml2.contains("""<colorFilter dxfId="0" cellColor="0"/>"""))
        assertTrue(xml2, xml2.contains("""<dynamicFilter type="thisMonth"/>"""))
        assertEquals(more.sheets[0].autoFilter!!.columns, XlsxReader.read(bytes2).sheets[0].autoFilter!!.columns)
    }

    @Test
    fun odfKeepsWhatItCanSay() {
        val wb = filtered()
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue(content, content.contains("<table:filter-or>"))
        assertTrue(content, content.contains("""table:operator="bottom percent""""))
        assertTrue(content, content.contains("""loext:data-type="background-color""""))
        assertEquals(wb.sheets[0].autoFilter!!.columns, OdsReader.read(bytes).sheets[0].autoFilter!!.columns)
        // And joined on one column: two more conditions of the outer And; a dynamic filter has no ODF form.
        val and = Workbook(mutableListOf(sheet().also { s ->
            s.autoFilter!!.columns[1] = ColumnFilter(blanks = false, condition = FilterCondition(Op.GREATER_OR_EQUAL, "20"), condition2 = FilterCondition(Op.LESS_OR_EQUAL, "80"))
            s.autoFilter!!.columns[2] = ColumnFilter(blanks = false, dynamic = DynamicFilter.TODAY)
        }))
        val back = OdsReader.read(ByteArrayOutputStream().also { OdsWriter.write(and, it) }.toByteArray()).sheets[0].autoFilter!!
        assertEquals(and.sheets[0].autoFilter!!.columns[1], back.columns[1])
        assertNull(back.columns[2])
    }

    /** With `-Daero.sampleDir`: the filtered sheet as xlsx and ODS, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "filter-criteria.xlsx").outputStream().use { XlsxWriter.write(filtered(), it) }
        File(dir, "filter-criteria.ods").outputStream().use { OdsWriter.write(filtered(), it) }
        // One column's two conditions joined by Or, alone (an Or inside the columns' And LibreOffice reads as nothing).
        File(dir, "filter-or.ods").outputStream().use { o ->
            OdsWriter.write(Workbook(mutableListOf(sheet().also { s ->
                s.autoFilter!!.columns[1] = ColumnFilter(blanks = false, condition = FilterCondition(Op.LESS, "20"), condition2 = FilterCondition(Op.GREATER, "80"), or = true)
            })), o)
        }
        // The sheet unfiltered, for the menus in the app.
        File(dir, "filter-menus.xlsx").outputStream().use { XlsxWriter.write(Workbook(mutableListOf(sheet())), it) }
    }
}
