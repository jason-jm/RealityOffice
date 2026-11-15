package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.EntryParser
import com.therealityteam.aeropages.editor.EntryParser.Entry
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode.Lang
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Excel's reading of typed entries — amounts, percentages, dates, times — and the formula bar's text for them. */
class EntryParserTest {

    private val today = LocalDate.of(2026, 9, 29)
    private fun p(text: String, lang: Lang = Lang.EN) = EntryParser.parse(text, lang, today)
    private fun serial(y: Int, m: Int, d: Int) = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(y, m, d)).toString()
    private val day = serial(2026, 9, 29)

    @Test
    fun amountsAndPercentagesBecomeNumbers() {
        assertEquals(Entry("0.15", NumberFormat.PERCENT, 0), p("15%"))
        assertEquals(Entry("0.155", NumberFormat.PERCENT, 1), p("15.5%"))
        assertEquals(Entry("-0.02", NumberFormat.PERCENT, 0), p("-2 %"))
        assertEquals(Entry("1234.5", NumberFormat.CURRENCY_USD, 2), p("$1,234.50"))
        assertEquals(Entry("-5", NumberFormat.CURRENCY_USD, 0), p("-$5"))
        assertEquals(Entry("-5", NumberFormat.CURRENCY_USD, 0), p("$-5"))
        assertEquals(Entry("80", NumberFormat.CURRENCY_CNY, 0), p("¥80"))
        assertEquals(Entry("80.5", NumberFormat.CURRENCY_CNY, 2), p("￥80.5"))
        assertEquals("accounting negatives", Entry("-12", NumberFormat.CURRENCY_USD, 0), p("($12)"))
        assertEquals(Entry("99", NumberFormat.GENERAL, -1, "\"€\"#,##0"), p("€99"))
        assertEquals(Entry("1000", NumberFormat.THOUSANDS, -1), p("1,000"))
        assertEquals(Entry("1234.5", NumberFormat.THOUSANDS, 2), p("1,234.5"))
        assertEquals(Entry("-42"), p("(42)"))
        assertEquals(Entry("1.5E3", NumberFormat.SCIENTIFIC), p("1.5E3"))
        assertEquals(Entry("TRUE"), p("true"))
        assertNull("already TRUE", p("TRUE"))
    }

    @Test
    fun plainNumbersTextAndFormulasStayAsTyped() {
        for (t in listOf("00123", "1.50", "123456789012345678", "-7", "abc", "=A1", "12,34", "1,2345", "", "  ", "Room 12", "(555) 123-4567", "555-1234", "10 kg", "$", "%"))
            assertNull(t, p(t))
    }

    @Test
    fun datesAsExcelReadsThem() {
        assertEquals(Entry(day, NumberFormat.DATE), p("2026-09-29"))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "yyyy/m/d"), p("2026/9/29"))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "yyyy\"年\"m\"月\"d\"日\""), p("2026年9月29日", Lang.ZH_CN))
        assertEquals("month and day: this year", Entry(day, NumberFormat.GENERAL, -1, "m\"月\"d\"日\""), p("9月29日", Lang.ZH_CN))
        assertEquals(Entry(serial(2026, 9, 1), NumberFormat.GENERAL, -1, "yyyy\"年\"m\"月\""), p("2026年9月", Lang.ZH_CN))
        assertEquals("m/d/yyyy in English", Entry(day, NumberFormat.GENERAL, -1, "m/d/yyyy"), p("9/29/2026"))
        assertNull("…but not in Chinese", p("9/29/2026", Lang.ZH_CN))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "m/d/yyyy"), p("9/29/26"))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "d-mmm"), p("9/29"))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "m\"月\"d\"日\""), p("9/29", Lang.ZH_CN))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "d-mmm-yy"), p("29-Sep-2026"))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "d-mmm-yy"), p("Sep 29, 2026"))
        assertEquals(Entry(day, NumberFormat.GENERAL, -1, "d-mmm-yy"), p("September 29 2026"))
        assertNull("no 30th of February", p("2026-02-30"))
        assertNull(p("13/1/2026"))
        assertNull(p("2026-9"))
    }

    @Test
    fun timesAndDateTimes() {
        assertEquals(Entry("0.395833333333333", NumberFormat.GENERAL, -1, "h:mm"), p("9:30"))
        assertEquals(Entry("0.395833333333333", NumberFormat.GENERAL, -1, "h:mm AM/PM"), p("9:30 am"))
        assertEquals(Entry("0.895833333333333", NumberFormat.GENERAL, -1, "h:mm AM/PM"), p("9:30PM"))
        assertEquals(Entry("0.5", NumberFormat.GENERAL, -1, "h:mm AM/PM"), p("12:00 PM"))
        assertEquals(Entry("0.396006944444444", NumberFormat.GENERAL, -1, "h:mm:ss"), p("9:30:15"))
        assertEquals("elapsed", Entry("1.0625", NumberFormat.GENERAL, -1, "[h]:mm"), p("25:30"))
        assertEquals(Entry("0.395833333333333", NumberFormat.GENERAL, -1, "h\"时\"mm\"分\""), p("9时30分", Lang.ZH_CN))
        assertNull(p("9:61"))
        assertNull(p("13:00 PM"))
        assertEquals(Entry("$day.5", NumberFormat.DATE_TIME), p("2026-09-29 12:00"))
        assertEquals(Entry("$day.75", NumberFormat.GENERAL, -1, "yyyy/m/d h:mm AM/PM"), p("2026/9/29 6:00 PM"))
        assertEquals(Entry("$day.5", NumberFormat.GENERAL, -1, "d-mmm-yy h:mm"), p("Sep 29, 2026 12:00"))
    }

    @Test
    fun theFormulaBarShowsNumbersAsTyped() {
        assertEquals("15%", EntryParser.editText(Cell("0.15", numberFormat = NumberFormat.PERCENT)))
        assertEquals("15.5%", EntryParser.editText(Cell("0.155", formatCode = "0.0%", numberFormat = NumberFormat.PERCENT)))
        assertEquals("2026-09-29", EntryParser.editText(Cell(day, numberFormat = NumberFormat.DATE)))
        assertEquals("2026-09-29", EntryParser.editText(Cell(day, formatCode = "yyyy\"年\"m\"月\"d\"日\"", numberFormat = NumberFormat.DATE)))
        assertEquals("2026-09-29 18:00", EntryParser.editText(Cell("$day.75", numberFormat = NumberFormat.DATE_TIME)))
        assertEquals("9:30", EntryParser.editText(Cell("0.395833333333333", formatCode = "h:mm", numberFormat = NumberFormat.TIME)))
        assertEquals("9:30:15", EntryParser.editText(Cell("0.396006944444444", numberFormat = NumberFormat.TIME)))
        assertEquals("elapsed hours pass 24", "36:00", EntryParser.editText(Cell("1.5", formatCode = "[h]:mm", numberFormat = NumberFormat.TIME)))
        assertEquals("1234.5", EntryParser.editText(Cell("1234.5", numberFormat = NumberFormat.CURRENCY_USD)))
        assertEquals("=A1", EntryParser.editText(Cell("=A1", numberFormat = NumberFormat.PERCENT)))
        assertEquals("text", EntryParser.editText(Cell("text", numberFormat = NumberFormat.DATE)))
        // Round trip: what the bar shows reads back as the same number.
        for (c in listOf(Cell("0.15", numberFormat = NumberFormat.PERCENT), Cell(day, numberFormat = NumberFormat.DATE), Cell("$day.75", numberFormat = NumberFormat.DATE_TIME)))
            assertEquals(c.text.toDouble(), EntryParser.parse(EntryParser.editText(c), Lang.EN, today)!!.value.toDouble(), 1e-9)
    }

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    @Test
    fun typingIsReadWhenTheCellIsLeft() {
        val e = editor()
        e.select(0, 0)
        e.typeCell(0, 0, "1"); e.typeCell(0, 0, "15"); e.typeCell(0, 0, "15%")
        assertEquals("while typing, the cell holds what was typed", "15%", e.sheet.cell(0, 0)!!.text)
        assertEquals("15%", e.editText(0, 0))
        e.select(1, 0)
        val a1 = e.sheet.cell(0, 0)!!
        assertEquals("0.15", a1.text)
        assertEquals(NumberFormat.PERCENT, a1.numberFormat)
        assertEquals("15%", e.display(0, 0))
        assertEquals("15%", e.editText(0, 0))
        e.typeCell(1, 0, "$1,234.50")
        e.typeCell(2, 0, "=SUM(A1:A2)")   // typing into another cell reads the one before
        assertEquals("1234.5", e.sheet.cell(1, 0)!!.text)
        e.select(3, 0)
        assertEquals("1234.65", e.display(2, 0))
        e.undo()
        assertNull(e.sheet.cell(2, 0)?.text?.takeIf { it.isNotEmpty() })
        assertEquals("1234.5", e.sheet.cell(1, 0)!!.text)
        e.undo()
        assertEquals("typing and reading are one undo step", null, e.sheet.cell(1, 0)?.text?.takeIf { it.isNotEmpty() })
        e.undo()
        assertEquals(null, e.sheet.cell(0, 0)?.text?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun commandsOnTheSelectionReadTheEntryFirst() {
        val e = editor()
        e.select(0, 0)
        e.typeCell(0, 0, "15%")
        e.extendSelection(2, 0)                 // still typing: A1 stays the active cell
        e.fillDown()
        for (r in 0..2) {
            assertEquals("0.15", e.sheet.cell(r, 0)!!.text)
            assertEquals("15%", e.display(r, 0))
        }
        e.select(0, 1)
        e.typeCell(0, 1, "2026/9/29")
        e.setNumberFormat(NumberFormat.NUMBER2)  // formatting ends the entry first: the serial takes the format
        assertEquals("$day.00", e.display(0, 1))
    }

    @Test
    fun cellsWithFormatsTextCellsAndTheQuotePrefix() {
        val e = editor()
        // A percentage cell: 20 is 20%, and the cell keeps its own format.
        e.select(0, 0); e.setNumberFormat(NumberFormat.PERCENT); e.setDecimals(1)
        e.typeCell(0, 0, "20"); e.select(1, 0)
        assertEquals("0.2", e.sheet.cell(0, 0)!!.text)
        assertEquals("20.0%", e.display(0, 0))
        // A date typed into a Number cell becomes its serial, shown as a number.
        e.setNumberFormat(NumberFormat.NUMBER2)
        e.typeCell(1, 0, "2026-09-29"); e.select(2, 0)
        assertEquals(day, e.sheet.cell(1, 0)!!.text)
        assertEquals(NumberFormat.NUMBER2, e.sheet.cell(1, 0)!!.numberFormat)
        // A Text cell keeps it all as typed.
        e.setFormatCode("@")
        e.typeCell(2, 0, "15%"); e.select(3, 0)
        assertEquals("15%", e.sheet.cell(2, 0)!!.text)
        // The quote prefix: what follows stays text, a number as a Text cell.
        e.typeCell(3, 0, "'00123"); e.select(4, 0)
        assertEquals("00123", e.sheet.cell(3, 0)!!.text)
        assertEquals("@", e.sheet.cell(3, 0)!!.formatCode)
        e.typeCell(4, 0, "'hello"); e.select(5, 0)
        assertEquals("hello", e.sheet.cell(4, 0)!!.text)
        assertNull(e.sheet.cell(4, 0)!!.formatCode)
        // setCell (paste, fill, the ribbon) is not typing: nothing is read.
        e.setCell(5, 0, "15%"); e.select(6, 0)
        assertEquals("15%", e.sheet.cell(5, 0)!!.text)
    }
}
