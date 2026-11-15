package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.FormatCode.Lang
import com.therealityteam.aeropages.model.FormatCode.Shown
import com.therealityteam.aeropages.model.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Excel number format codes: numbers, sections and conditions, colours, fractions, dates and times, Chinese numerals. */
class FormatCodeTest {

    private fun f(v: Double, code: String, lang: Lang = Lang.EN) = FormatCode.format(v, code, lang).text
    private val red = 0xFFFF0000
    /** 2026-09-29, a Tuesday, as an Excel serial. */
    private val day = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(2026, 9, 29)).toDouble()

    @Test
    fun digitsGroupingScalingAndPercent() {
        assertEquals("1,234,567.89", f(1234567.891, "#,##0.00"))
        assertEquals("1.5M", f(1_500_000.0, "0.0,,\"M\""))
        assertEquals("thousands with a trailing comma", "1,235", f(1234567.0, "#,##0,"))
        assertEquals("rounds as Excel does, from fifteen digits", "2.68", f(2.675, "0.00"))
        assertEquals("13", f(12.5, "0"))
        assertEquals("-13", f(-12.5, "0"))
        assertEquals("555-1234", f(5551234.0, "000-0000"))
        assertEquals("(555) 123-4567", f(5551234567.0, "(000) 000-0000"))
        assertEquals("extra digits go to the first placeholder", "12345", f(12345.0, "00"))
        assertEquals("5.", f(5.0, "#.##"))
        assertEquals(".5", f(0.5, "#.##"))
        assertEquals("12.50", f(12.5, ".00"))
        assertEquals("0.5", f(0.5, "0.0#"))
        assertEquals("0.57", f(0.567, "0.0#"))
        assertEquals("? keeps the width", "  1.5  ", f(1.5, "???.???"))
        assertEquals("26%", f(0.256, "0%"))
        assertEquals("25.60%", f(0.256, "0.00%"))
        assertEquals("no minus on a zero", "0.0", f(-0.01, "0.0"))
        assertEquals("00042", f(42.0, "00000"))
    }

    @Test
    fun sectionsConditionsAndColours() {
        assertEquals(Shown("-1,234.50", red), FormatCode.format(-1234.5, "#,##0.00;[Red]-#,##0.00", Lang.EN))
        assertEquals(Shown("1,234.50", null), FormatCode.format(1234.5, "#,##0.00;[Red]-#,##0.00", Lang.EN))
        assertEquals("the negative section shows no sign of its own", Shown("(5.00)", red), FormatCode.format(-5.0, "#,##0.00;[Red](#,##0.00)", Lang.EN))
        assertEquals("zero", f(0.0, "0;-0;\"zero\""))
        assertEquals("an empty section hides its values", "", f(0.0, "0.00;-0.00;"))
        assertEquals("", f(12.0, ";;;"))
        assertEquals("", FormatCode.formatText("hidden", ";;;").text)
        val big = "[>=1000000]0.0,,\"M\";[>=1000]0.0,\"K\";0"
        assertEquals("1.5M", f(1_500_000.0, big))
        assertEquals("2.5K", f(2500.0, big))
        assertEquals("999", f(999.0, big))
        assertEquals(Shown("50", red), FormatCode.format(50.0, "[<=100][Red]0;[Blue]0", Lang.EN))
        assertEquals(Shown("150", 0xFF0000FF), FormatCode.format(150.0, "[<=100][Red]0;[Blue]0", Lang.EN))
        assertEquals("[<0] shows negatives without the sign", Shown("5", red), FormatCode.format(-5.0, "[Red][<0]0;0", Lang.EN))
        assertEquals("no section applies: General", "50", f(50.0, "[>100]0"))
        assertEquals(0xFF008000, FormatCode.format(1.0, "[Color10]0").color)
        assertEquals("Chinese colour names", red, FormatCode.format(-1.0, "0;[红色]-0").color)
    }

    @Test
    fun currencyAccountingAndText() {
        assertEquals("¥1,234.50", f(1234.5, "\"¥\"#,##0.00"))
        assertEquals("€2,500", f(2500.0, "[\$€-407]#,##0"))
        assertEquals("-$1,234", f(-1234.0, "\$#,##0"))
        assertEquals(" $1,234.50 ", f(1234.5, "_(\"$\"* #,##0.00_)"))
        val accounting = "_ ￥* #,##0.00_ ;_ ￥* -#,##0.00_ ;_ ￥* \"-\"??_ ;_ @_ "
        assertEquals(" ￥1,234.50 ", f(1234.5, accounting))
        assertEquals(" ￥-1,234.50 ", f(-1234.5, accounting))
        assertEquals(" ￥-   ", f(0.0, accounting))
        assertEquals(" abc ", FormatCode.formatText("abc", accounting).text)
        assertEquals("text keeps its zeros", "00123", FormatCode.formatText("00123", "@").text)
        assertEquals("123", f(123.0, "@"))
        assertEquals("ID: 7", FormatCode.formatText("7", "\"ID: \"@").text)
        assertEquals("text: x", FormatCode.formatText("x", "0.00;-0.00;0.00;\"text: \"@").text)
        assertEquals("no text section: the text itself", "x", FormatCode.formatText("x", "0.00").text)
        assertTrue(FormatCode.isText("@"))
        assertFalse(FormatCode.isText("0;@"))
    }

    @Test
    fun scientificAndFractions() {
        assertEquals("1.23E+04", f(12345.0, "0.00E+00"))
        assertEquals("1.23E-04", f(0.000123, "0.00E+00"))
        assertEquals("engineering: exponents in threes", "12.3E+3", f(12345.0, "##0.0E+0"))
        assertEquals("1.00E+01", f(9.999, "0.00E+00"))
        assertEquals("0.00E+00", f(0.0, "0.00E+00"))
        assertEquals("1 1/2", f(1.5, "# ?/?"))
        assertEquals(" 1/4", f(0.25, "# ?/?"))
        assertEquals("3 14/99", f(Math.PI, "# ??/??"))
        assertEquals("a fixed denominator", "1 2/8", f(1.3, "# ?/8"))
        assertEquals("3/2", f(1.5, "?/?"))
        assertEquals("whole numbers blank the fraction", "3    ", f(3.0, "# ?/?"))
        assertEquals("0", f(0.0, "# ?/?").trim())
        assertEquals("-1 1/2", f(-1.5, "# ?/?"))
        assertEquals("an absurd denominator is capped, not a crash", "1 500000/1000000", f(1.5, "# ?/99999999999999999999"))
    }

    @Test
    fun datesAndTimes() {
        val noon = day + 0.5 + 5.0 / 86_400
        assertEquals("2026年9月29日", f(day, "yyyy\"年\"m\"月\"d\"日\";@"))
        assertEquals("n/a", FormatCode.formatText("n/a", "yyyy\"年\"m\"月\"d\"日\";@").text)
        assertEquals("2026-09-29 12:00:05", f(noon, "yyyy-mm-dd hh:mm:ss"))
        assertEquals("m after h is minutes, before s too", "12:00 05", f(noon, "h:mm ss"))
        assertEquals("9/29/26", f(day, "m/d/yy"))
        assertEquals("29-Sep-26", f(day, "d-mmm-yy"))
        assertEquals("Tuesday, September 29, 2026", f(day, "dddd, mmmm d, yyyy"))
        assertEquals("S", f(day, "mmmmm"))
        assertEquals("6:00 PM", f(0.75, "h:mm AM/PM"))
        assertEquals("6:00 am", f(0.25, "h:mm am/pm"))
        assertEquals("12:30 A", f(0.5 / 24, "h:mm A/P"))
        assertEquals("36:00:00", f(1.5, "[h]:mm:ss"))
        assertEquals("14:24", f(0.01, "[mm]:ss"))
        assertEquals("00:10.63", f(0.000123, "mm:ss.00"))
        assertEquals("seconds round, minutes do not", "23:59", f(0.99999, "hh:mm"))
        assertEquals("negative dates cannot show", "#####", f(-1.0, "yyyy-mm-dd"))
    }

    @Test
    fun namesFollowTheLanguageAndLocaleTags() {
        assertEquals("周二 9月", f(day, "ddd mmm", Lang.ZH_CN))
        assertEquals("星期二 九月", f(day, "dddd mmmm", Lang.ZH_CN))
        assertEquals("週二", f(day, "ddd", Lang.ZH_TW))
        assertEquals("火曜日", f(day, "dddd", Lang.JA))
        assertEquals("星期二", f(day, "aaaa"))
        assertEquals("二", f(day, "aaa"))
        assertEquals("a [$-804] tag names in Chinese whatever the app's language", "星期二", f(day, "[\$-804]dddd", Lang.EN))
        assertEquals("[$-409] in English", "Tue", f(day, "[\$-409]ddd", Lang.ZH_CN))
        assertEquals("下午6时00分", f(0.75, "上午/下午h\"时\"mm\"分\""))
        assertEquals("下午6:00", f(0.75, "[\$-804]AM/PMh:mm"))
        assertEquals("the system long date is the CJK one outside English", "2026年9月29日", f(day, "[\$-F800]dddd\\,\\ mmmm\\ dd\\,\\ yyyy", Lang.ZH_CN))
        assertEquals("Tuesday, September 29, 2026", f(day, "[\$-F800]dddd\\,\\ mmmm\\ dd\\,\\ yyyy", Lang.EN))
    }

    @Test
    fun chineseNumerals() {
        assertEquals("一千二百三十四", f(1234.0, "[DBNum1][\$-804]General"))
        assertEquals("壹仟贰佰叁拾肆", f(1234.0, "[DBNum2][\$-804]General"))
        assertEquals("十", f(10.0, "[DBNum1]General"))
        assertEquals("壹拾", f(10.0, "[DBNum2]General"))
        assertEquals("十万", f(100_000.0, "[DBNum1]General"))
        assertEquals("一千零五", f(1005.0, "[DBNum1]General"))
        assertEquals("一万零一", f(10_001.0, "[DBNum1]General"))
        assertEquals("一亿零一万", f(100_010_000.0, "[DBNum1]General"))
        assertEquals("十二.五", f(12.5, "[DBNum1]General"))
        assertEquals("人民币壹佰元", f(100.0, "\"人民币\"[DBNum2]General\"元\""))
        assertEquals("二〇二六年九月二十九日", f(day, "[DBNum1][\$-804]yyyy\"年\"m\"月\"d\"日\""))
        assertEquals("１２３", f(123.0, "[DBNum3]0"))
        // Digit placeholders too: the whole part in words, as TEXT(n, "[DBNum1]0") and LibreOffice show it.
        assertEquals("四万六千二百九十五", f(46295.0, "[DBNum1][\$-804]0"))
        assertEquals("壹佰贰拾叁元整", f(123.0, "[DBNum2]0\"元整\""))
        assertEquals("负一千零五.五〇", f(-1005.5, "[DBNum1]0.00;[DBNum1]\"负\"0.00"))
        assertEquals("〇.五", f(0.5, "[DBNum1]0.0"))
        assertEquals("grouped digits stay digit by digit", "四六,二九五", f(46295.0, "[DBNum1]#,##0"))
    }

    @Test
    fun generalAndBadCodes() {
        assertEquals("1.23457E+12", FormatCode.general(1234567890123.0))
        assertEquals("0.3", FormatCode.general(0.1 + 0.2))
        assertEquals("1234.56789", FormatCode.general(1234.56789012))
        assertEquals("-42", FormatCode.general(-42.0))
        assertFalse("unterminated quote", FormatCode.isValid("0.00\""))
        assertFalse("unterminated bracket", FormatCode.isValid("[Red0"))
        assertFalse("five sections", FormatCode.isValid("0;0;0;0;0"))
        assertFalse(FormatCode.isValid("[Color99]0"))
        assertFalse(FormatCode.isValid(""))
        assertTrue(FormatCode.isValid("#,##0.00;[Red]-#,##0.00"))
        assertEquals("an unreadable code shows General", "12.5", f(12.5, "0.00\""))
    }

    @Test
    fun decimalsStepWithinTheCode() {
        assertEquals("0.00,,\"M\"", FormatCode.withDecimals("0.0,,\"M\"", 1))
        assertEquals("0,,\"M\"", FormatCode.withDecimals("0.0,,\"M\"", -1))
        assertEquals("#,##0.0", FormatCode.withDecimals("#,##0", 1))
        assertEquals("#,##0.0;[Red]-#,##0.0", FormatCode.withDecimals("#,##0.00;[Red]-#,##0.00", -1))
        assertEquals("0.00%", FormatCode.withDecimals("0%", 2))
        assertEquals("0.000E+00", FormatCode.withDecimals("0.00E+00", 1))
        assertEquals("dates stay", "yyyy-mm-dd", FormatCode.withDecimals("yyyy-mm-dd", 1))
        assertEquals("quoted text is left alone", "0.0\" 0.0\"", FormatCode.withDecimals("0\" 0.0\"", 1))
    }

    @Test
    fun whatACodeIs() {
        assertEquals(NumberFormat.DATE, FormatCode.category("yyyy\"年\"m\"月\"d\"日\""))
        assertEquals(NumberFormat.TIME, FormatCode.category("h:mm"))
        assertEquals(NumberFormat.TIME, FormatCode.category("[h]:mm:ss"))
        assertEquals(NumberFormat.DATE_TIME, FormatCode.category("yyyy-mm-dd h:mm"))
        assertEquals(NumberFormat.PERCENT, FormatCode.category("0.0%"))
        assertEquals(NumberFormat.CURRENCY_CNY, FormatCode.category("\"¥\"#,##0"))
        assertEquals(NumberFormat.CURRENCY_USD, FormatCode.category("\$#,##0.00"))
        assertEquals(NumberFormat.THOUSANDS, FormatCode.category("#,##0"))
        assertEquals(NumberFormat.SCIENTIFIC, FormatCode.category("0.00E+00"))
        assertEquals(NumberFormat.GENERAL, FormatCode.category("@"))
        assertTrue(FormatCode.isDate("[h]:mm"))
        assertFalse(FormatCode.isDate("0.00"))
        assertEquals("# ?/?", FormatCode.builtIn(12))
        assertEquals("yyyy\"年\"m\"月\"d\"日\"", FormatCode.builtIn(31))
        assertEquals(49, FormatCode.builtInId("@"))
        assertEquals(46, FormatCode.builtInId("[h]:mm:ss"))
        assertNull("Chinese built-ins are written as custom codes", FormatCode.builtInId("yyyy\"年\"m\"月\"d\"日\""))
        assertNull(FormatCode.builtIn(100))
    }
}
