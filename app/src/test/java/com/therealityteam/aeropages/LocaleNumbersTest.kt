package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.EntryParser
import com.therealityteam.aeropages.editor.FormulaLocale
import com.therealityteam.aeropages.editor.ValidationForm
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.LocaleCurrency
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Spreadsheet numbers in the language's writing, as Excel there: what typing reads, what cells show, what files keep. */
class LocaleNumbersTest {
    private val de = NumberSymbols.of(Locale.GERMANY)
    private val fr = NumberSymbols.of(Locale.FRANCE)
    private val today = LocalDate.of(2026, 10, 21)
    private fun deEntry(t: String) = EntryParser.parse(t, FormatCode.Lang.DE, today, de)

    @Test
    fun typingReadsTheLanguagesNumbersAndDates() {
        assertEquals("1.5", deEntry("1,5")?.value)
        deEntry("1.234,5")!!.let { assertEquals("1234.5", it.value); assertEquals(NumberFormat.THOUSANDS, it.format) }
        assertEquals("a dot in threes groups", "1234", deEntry("1.234")?.value)
        deEntry("12,5 %")!!.let { assertEquals("0.125", it.value); assertEquals(NumberFormat.PERCENT, it.format); assertEquals(1, it.decimals) }
        deEntry("1.234,50 €")!!.let { assertEquals("1234.5", it.value); assertEquals("\"€\"#,##0.00", it.code) }
        assertEquals("-1.5", deEntry("-1,5")?.value)
        assertEquals("(1,5) is negative", "-1.5", deEntry("(1,5)")?.value)
        assertEquals(NumberFormat.SCIENTIFIC, deEntry("1,5E3")?.format)
        deEntry("8.10.2026")!!.let { assertEquals("46303", it.value); assertEquals("dd.mm.yyyy", it.code) }
        assertEquals("1.5 is the first of May, as in German Excel", "46143", deEntry("1.5")?.value)
        assertNull("not a date, not a German number: kept as typed", deEntry("3.14"))
        assertNull("zeros kept", deEntry("00123"))
        assertEquals("1234.5", EntryParser.parse("1 234,5", FormatCode.Lang.FR, today, fr)?.value)
        assertEquals("1234.5", EntryParser.parse("1 234,5", FormatCode.Lang.FR, today, fr)?.value)
        assertEquals("08/10/2026 in French", "46303", EntryParser.parse("08/10/2026", FormatCode.Lang.FR, today, fr)?.value)
        assertEquals("Thai: day first, the dot a decimal point", "46303", EntryParser.parse("8/10/2026", FormatCode.Lang.TH, today)?.value)
        assertNull(EntryParser.parse("1.5", FormatCode.Lang.TH, today))
        assertEquals("English: month first", "46303", EntryParser.parse("10/8/2026", FormatCode.Lang.EN, today)?.value)
    }

    @Test
    fun theFormulaBarShowsNumbersAsTheyAreTyped() {
        assertEquals("1,5", EntryParser.editText(Cell(text = "1.5"), de))
        assertEquals("12,5%", EntryParser.editText(Cell(text = "0.125", numberFormat = NumberFormat.PERCENT), de))
        assertEquals("dates as everywhere", "2026-10-08", EntryParser.editText(Cell(text = "46303", numberFormat = NumberFormat.DATE), de))
        assertEquals("a formula the language's way", "=A1*1,5", EntryParser.editText(Cell(text = "=A1*1.5"), de))
    }

    @Test
    fun aGermanSheetShowsAndReadsGermanNumbersAndKeepsTheStandardForm() {
        val e = SheetEditor().apply { load(Workbook.blank()); symbols = de }
        e.typeCell(0, 0, "1,5"); e.commitEntry()
        assertEquals("the cell keeps the standard form", "1.5", e.sheet.cell(0, 0)!!.text)
        assertEquals("1,5", e.display(0, 0))
        assertEquals("1,5", e.editText(0, 0))
        e.typeCell(1, 0, "1.234,5"); e.commitEntry()
        assertEquals("grouped with decimals: the thousands format, two places", "1.234,50", e.display(1, 0))
        e.select(2, 0); e.pasteTsv("2,25\t3.000\n")
        assertEquals("2.25", e.sheet.cell(2, 0)!!.text)
        assertEquals("3000", e.sheet.cell(2, 1)!!.text)
        assertTrue("CSV in the standard writing", e.toCsv().contains("1.5"))
        e.select(4, 0); e.setNumberFormat(NumberFormat.PERCENT)
        e.typeCell(4, 0, "20,5"); e.commitEntry()
        assertEquals("a percentage cell takes 20,5 as 20,5 %", "0.205", e.sheet.cell(4, 0)!!.text)
    }

    @Test
    fun formulasReadAndShowTheLanguagesWay() {
        fun local(f: String) = FormulaLocale.toLocal(f, de)
        fun std(f: String) = FormulaLocale.toCanonical(f, de)
        assertEquals("=ROUND(A1;2)", local("=ROUND(A1,2)"))
        assertEquals("=A1*1,5+SUM(B1:B3;2,25)", local("=A1*1.5+SUM(B1:B3,2.25)"))
        assertEquals("array constants: . between columns, ; between rows", "=SUM({1,5.2;3.4})", local("=SUM({1.5,2;3,4})"))
        assertEquals("strings, sheet names, names with dots stay", "=IF('Q1, 2'!A1>0,5;\"a, b. c\";NORM.S.DIST(1,5;TRUE))",
            local("=IF('Q1, 2'!A1>0.5,\"a, b. c\",NORM.S.DIST(1.5,TRUE))"))
        assertEquals("=LOG10(A2)*1E+3", local("=LOG10(A2)*1E+3"))
        for (f in listOf("=ROUND(A1,2)", "=A1*1.5+SUM(B1:B3,2.25)", "=SUM({1.5,2;3,4})", "=IF('Q1, 2'!A1>0.5,\"a, b. c\",NORM.S.DIST(1.5,TRUE))", "=Table1[[#Data],[Qty, net]]*0.5"))
            assertEquals("round trip of $f", f, std(local(f)))
        assertEquals("typed the English way, it still reads", "=SUM(A1,B1)", std("=SUM(A1,B1)"))
        assertEquals("nothing changes where 1.5 is written so", "=ROUND(A1,2)", FormulaLocale.toLocal("=ROUND(A1,2)", NumberSymbols.CANONICAL))

        val e = SheetEditor().apply { load(Workbook.blank()); symbols = de }
        e.typeCell(0, 0, "2"); e.commitEntry()
        e.typeCell(0, 1, "=ROUND(A1*1,25;1)"); e.commitEntry()
        assertEquals("kept in the standard form", "=ROUND(A1*1.25,1)", e.sheet.cell(0, 1)!!.text)
        assertEquals("2,5", e.display(0, 1))
        assertEquals("=ROUND(A1*1,25;1)", e.editText(0, 1))
    }

    @Test
    fun theCurrencyButtonGivesTheLanguagesCurrency() {
        LocaleCurrency.of(Locale.GERMAN).let { assertEquals("€", it.symbol); assertTrue("after the number: ${it.code}", it.code.endsWith("\"€\"")) }
        assertEquals("₩", LocaleCurrency.of(Locale.KOREAN).symbol)
        assertEquals("R$", LocaleCurrency.of(Locale.forLanguageTag("pt")).symbol)
        assertEquals("$", LocaleCurrency.of(Locale.ENGLISH).symbol)
        assertEquals("1.234,50 €", com.therealityteam.aeropages.model.FormatCode.format(1234.5, LocaleCurrency.of(Locale.GERMAN).code, FormatCode.Lang.DE, de).text)
    }

    @Test
    fun dialogsTakeFormulasAndNumbersTheLanguagesWay() {
        val (bounds, _) = ValidationForm.ruleOf(ValidationForm.Fields(type = ValidationType.DECIMAL, operator = ValidationOperator.BETWEEN,
            minimum = "1,5", maximum = "=ROUND(B1;1)"), de)
        assertEquals(1.5, bounds!!.min, 0.0)
        assertEquals("ROUND(B1,1)", bounds.formula2)
        ValidationForm.fieldsOf(bounds, symbols = de).let { assertEquals("1,5", it.minimum); assertEquals("=ROUND(B1;1)", it.maximum) }
        val (list, _) = ValidationForm.ruleOf(ValidationForm.Fields(type = ValidationType.LIST, source = "Ja;Nein;1,5"), de)
        assertEquals("a list's values apart with ;", listOf("Ja", "Nein", "1,5"), list!!.items)
        assertEquals("Ja;Nein;1,5", ValidationForm.fieldsOf(list, symbols = de).source)
        val (custom, _) = ValidationForm.ruleOf(ValidationForm.Fields(type = ValidationType.CUSTOM, formula = "=COUNTIF(\$A:\$A;A1)=1"), de)
        assertEquals("COUNTIF(\$A:\$A,A1)=1", custom!!.formula1)
        assertEquals("the formula in a hint, not the sentence's commas", "z. B. lässt =COUNTIF(\$A:\$A;A1)=1 keine Wiederholungen zu, oder (=Data!\$A$1:\$B$5), =0,08.",
            FormulaLocale.inText("z. B. lässt =COUNTIF(\$A:\$A,A1)=1 keine Wiederholungen zu, oder (=Data!\$A$1:\$B$5), =0.08.", de))
    }

    @Test
    fun plainDatesShowTheLanguagesShortDateAndStayIsoForFiles() {
        val cell = Cell(text = "46310", numberFormat = NumberFormat.DATE) // 2026-10-15
        fun shown(lang: FormatCode.Lang?) = com.therealityteam.aeropages.editor.NumberFormatting.shown("46310", cell, NumberSymbols.of(lang?.locale ?: Locale.US), lang).text
        assertEquals("15.10.2026", shown(FormatCode.Lang.DE))
        assertEquals("10/15/2026", shown(FormatCode.Lang.EN))
        assertEquals("2026/10/15", shown(FormatCode.Lang.ZH_CN))
        assertEquals("2026-10-15", shown(FormatCode.Lang.KO))
        assertEquals("15/10/2026", shown(FormatCode.Lang.FR))
        assertEquals("15-10-2026", shown(FormatCode.Lang.NL))
        assertEquals("ISO without a language", "2026-10-15", shown(null))
        val dt = Cell(text = "46310.5", numberFormat = NumberFormat.DATE_TIME)
        assertEquals("15.10.2026 12:00", com.therealityteam.aeropages.editor.NumberFormatting.shown("46310.5", dt, NumberSymbols.of(Locale.GERMANY), FormatCode.Lang.DE).text)
        // The grid shows the language's date; CSV keeps ISO, as other programs read it.
        val e = SheetEditor().apply { load(Workbook.blank()) }
        e.symbols = NumberSymbols.of(Locale.GERMANY); e.dates = FormatCode.Lang.DE
        e.typeCell(0, 0, "2026-10-15"); e.commitEntry()
        assertEquals("15.10.2026", e.display(0, 0))
        assertEquals("2026-10-15", e.displayStandard(0, 0))
    }

    @Test
    fun aNewWorkbookAndANewSheetAreNamedInTheLanguage() {
        val english = com.therealityteam.aeropages.model.Texts.provider
        try {
            // German Excel's names: Tabelle1, Tabelle2…
            com.therealityteam.aeropages.model.Texts.provider = { id, args ->
                if (id == com.therealityteam.aeropages.model.TextId.SHEET_N) String.format(Locale.ROOT, "Tabelle%1\$d", *args) else english(id, args)
            }
            val wb = Workbook.newBlank()
            assertEquals("Tabelle1", wb.sheets[0].name)
            val e = SheetEditor().apply { load(wb) }
            e.addSheet()
            assertEquals("Tabelle2", e.workbook.sheets[1].name)
            val issues = com.therealityteam.aeropages.model.AccessibilityCheck.of(e.workbook)
            assertEquals("Excel's tip to name the sheets, for the language's default names too", 2,
                issues.count { it.kind == com.therealityteam.aeropages.model.AccessibilityCheck.Kind.DEFAULT_SHEET_NAME })
        } finally {
            com.therealityteam.aeropages.model.Texts.provider = english
        }
        assertEquals("Sheet1", Workbook.blank().sheets[0].name)
    }
}
