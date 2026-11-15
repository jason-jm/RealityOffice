package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.FieldDates
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.PagePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

/** What changes with the language besides the words: date names, short dates, AM/PM, and the paper a new file starts on. */
class LocaleFormatsTest {
    private val oct8 = LocalDateTime.of(2026, 10, 8, 14, 5)

    @Test
    fun wordDateFieldsNameMonthsAndDaysInTheLanguage() {
        assertEquals("8. Oktober 2026", FieldDates.format("d. MMMM yyyy", oct8, FormatCode.Lang.DE))
        assertEquals("jeudi 8 octobre 2026", FieldDates.format("dddd d MMMM yyyy", oct8, FormatCode.Lang.FR))
        assertEquals("8 de octubre de 2026", FieldDates.format("d 'de' MMMM 'de' yyyy", oct8, FormatCode.Lang.ES))
        assertEquals("the month in the genitive, as a date writes it", "8 октября 2026 г.", FieldDates.format("d MMMM yyyy 'г.'", oct8, FormatCode.Lang.RU))
        assertEquals("English for an English, Chinese or Japanese app", "October 8, 2026", FieldDates.format("MMMM d, yyyy", oct8, FormatCode.Lang.ZH_CN))
        assertEquals("hangul is not Chinese: Korean's own AM/PM", "2026년 10월 8일 오후 2:05", FieldDates.format("yyyy년 M월 d일 AM/PM h:mm", oct8, FormatCode.Lang.KO))
        assertEquals("a picture in Chinese names in Chinese", "2026年10月8日星期四", FieldDates.format("yyyy年M月d日dddd", oct8, FormatCode.Lang.DE))
        assertEquals("08.10.2026", FieldDates.format(FieldDates.shortDate(FormatCode.Lang.DE), oct8))
        assertEquals("8-10-2026", FieldDates.format(FieldDates.shortDate(FormatCode.Lang.NL), oct8))
        assertEquals("10/8/2026", FieldDates.format(FieldDates.shortDate(FormatCode.Lang.EN), oct8))
    }

    @Test
    fun excelDateFormatsNameMonthsAsTheLanguageDoes() {
        val serial = 46303.0 // 2026-10-08
        assertEquals("oct 2026", FormatCode.format(serial, "mmm yyyy", FormatCode.Lang.FR).text)
        assertEquals("Oktober", FormatCode.format(serial, "mmmm", FormatCode.Lang.DE).text)
        assertEquals("donderdag", FormatCode.format(serial, "dddd", FormatCode.Lang.NL).text)
        assertEquals("Excel's own English names", "Oct", FormatCode.format(serial, "mmm", FormatCode.Lang.EN).text)
        assertEquals(FormatCode.Lang.DE, FormatCode.Lang.ofLcid(0x0807))
        assertEquals(FormatCode.Lang.PT, FormatCode.Lang.ofLcid(0x0816))
        assertEquals(FormatCode.Lang.ZH_TW, FormatCode.Lang.ofLcid(0x0C04))
        assertEquals(FormatCode.Lang.ID, FormatCode.Lang.of(Locale.forLanguageTag("in-ID")))
        assertEquals(FormatCode.Lang.EN, FormatCode.Lang.of(Locale.forLanguageTag("sv-SE")))
    }

    @Test
    fun aNewFileStartsOnTheRegionsPaper() {
        assertTrue(PagePresets.usesLetter(Locale.forLanguageTag("en-US")))
        assertTrue(PagePresets.usesLetter(Locale.forLanguageTag("fr-CA")))
        assertTrue(PagePresets.usesLetter(Locale.forLanguageTag("es-MX")))
        assertFalse(PagePresets.usesLetter(Locale.forLanguageTag("en-GB")))
        assertFalse(PagePresets.usesLetter(Locale.forLanguageTag("de")))
        assertFalse(PagePresets.usesLetter(Locale.forLanguageTag("zh-CN")))
        val zh = PagePresets.regionalPage(Locale.forLanguageTag("zh-CN"))
        assertEquals("A4", PagePresets.paperOf(zh)?.name)
        assertEquals("Chinese Word's wider side margins", 90f, zh.marginLeftPt)
        val us = PagePresets.regionalPage(Locale.forLanguageTag("en-US"))
        assertEquals("Letter", PagePresets.paperOf(us)?.name)
        assertEquals(72f, us.marginLeftPt)
    }

    @Test
    fun numbersShowWithTheLanguagesSymbols() {
        val de = NumberSymbols.of(Locale.GERMANY)
        val fr = NumberSymbols.of(Locale.FRANCE)
        assertEquals(NumberSymbols(',', '.'), de)
        assertEquals("French groups with a no-break space", NumberSymbols(',', '\u00A0'), fr)
        assertEquals(NumberSymbols.CANONICAL, NumberSymbols.of(Locale.forLanguageTag("ko-KR")))
        assertEquals(NumberSymbols.CANONICAL, NumberSymbols.of(Locale.forLanguageTag("hi-IN")))
        assertEquals(';', de.argument)
        assertEquals("1.234,50", FormatCode.format(1234.5, "#,##0.00", FormatCode.Lang.DE, de).text)
        assertEquals("General never groups, as in Excel", "1234,5", FormatCode.format(1234.5, "General", FormatCode.Lang.FR, fr).text)
        assertEquals("1\u00A0234,5", FormatCode.format(1234.5, "#,##0.0", FormatCode.Lang.FR, fr).text)
        assertEquals("12,5%", FormatCode.format(0.125, "0.0%", FormatCode.Lang.DE, de).text)
        assertEquals("1,23E+04", FormatCode.format(12345.0, "0.00E+00", FormatCode.Lang.DE, de).text)
        assertEquals("a literal dot and a date's dots stay", "08.10.2026", FormatCode.format(46303.0, "dd.mm.yyyy", FormatCode.Lang.DE, de).text)
        assertEquals("quoted text stays", "1.234,5 x.y", FormatCode.format(1234.5, "#,##0.0\" x.y\"", FormatCode.Lang.DE, de).text)
        assertEquals("the standard symbols by default", "1,234.50", FormatCode.format(1234.5, "#,##0.00").text)
    }
}
