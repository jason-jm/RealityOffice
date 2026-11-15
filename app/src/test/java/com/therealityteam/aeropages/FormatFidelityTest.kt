package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.NumberFormatting
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.FormatCode.Lang
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Number formats as Excel shows them — what FormatOracleTest found against LibreOffice and Excel's own rules. */
class FormatFidelityTest {

    private fun f(v: Double, code: String, lang: Lang = Lang.EN) = FormatCode.format(v, code, lang).text

    @Test
    fun amPmIsInTheLanguagesOwnWords() {
        assertEquals("6:00 下午", f(0.75, "h:mm AM/PM", Lang.ZH_CN))
        assertEquals("9:30 上午", f(0.395833333, "h:mm AM/PM", Lang.ZH_TW))
        assertEquals("6:00 午後", f(0.75, "h:mm AM/PM", Lang.JA))
        assertEquals("6:00 PM", f(0.75, "h:mm AM/PM", Lang.EN))
        // [$-409] keeps them English; A/P stays as written.
        assertEquals("6:00 PM", f(0.75, "[\$-409]h:mm AM/PM", Lang.ZH_CN))
        assertEquals("6:00 P", f(0.75, "h:mm A/P", Lang.ZH_CN))
    }

    @Test
    fun japaneseErasCountTheirOwnYears() {
        val code = "[\$-411]ggge\"年\"m\"月\"d\"日\""
        assertEquals("令和6年1月1日", f(45292.0, code))
        assertEquals("令6", f(45292.0, "[\$-411]gge"))
        assertEquals("R06", f(45292.0, "[\$-411]gee"))
        // 8 January 1989 opens Heisei; the day before is Showa 64.
        assertEquals("平成1", f(32516.0, "[\$-411]ggge"))
        assertEquals("昭和64", f(32515.0, "[\$-411]ggge"))
        // 1 January 1900 (serial 2 here: day 0 is 30 December 1899) is Meiji 33.
        assertEquals("明治33年", f(2.0, "[\$-411]ggge\"年\""))
        // Outside Japanese, e is the year.
        assertEquals("2024", f(45292.0, "e"))
    }

    @Test
    fun excelsBuiltInAccountingAndCurrencyFormats() {
        val acc = FormatCode.builtIn(41)!!
        assertEquals(" 1,234 ", f(1234.4, acc))
        assertEquals(" (1,234)", f(-1234.4, acc))
        assertEquals(" - ", f(0.0, acc))
        assertEquals(" 1,234.50 ", f(1234.5, FormatCode.builtIn(43)!!))
        assertEquals("(\$1,235)", f(-1234.5, FormatCode.builtIn(5)!!))
        // Written back as built-in ids only where every Excel shows them alike: a Chinese Excel shows 41–44 and 5–8
        // its own way (negatives -1,234, its ¥), so those go as the codes themselves.
        assertNull(FormatCode.builtInId(acc))
        assertNull(FormatCode.builtInId(FormatCode.builtIn(42)!!))
        assertNull(FormatCode.builtInId(FormatCode.builtIn(5)!!))
        assertEquals(2, FormatCode.builtInId("0.00"))
        assertEquals(20, FormatCode.builtInId("h:mm"))
    }

    @Test
    fun plainFormatsAsExcelsBuiltIns() {
        assertEquals("13:37", NumberFormatting.format(1234.5678, NumberFormat.TIME))
        assertEquals("0:00", NumberFormatting.format(45351.0, NumberFormat.TIME))
        assertEquals("123456.8%", NumberFormatting.format(1234.5678, NumberFormat.PERCENT, 1))
        // A number under a text-only code other than Text (@) shows as General.
        assertEquals("12", NumberFormatting.shown("12", Cell("12").apply { formatCode = "@\" kg\"" }).text)
        assertEquals("abc kg", NumberFormatting.shown("abc", Cell("abc").apply { formatCode = "@\" kg\"" }).text)
        assertEquals("007", NumberFormatting.shown("007", Cell("007").apply { formatCode = "@" }).text)
    }

    @Test
    fun odsKeepsLanguagesErasAndBlankDigits() {
        val s = Sheet(name = "S")
        val codes = listOf("[\$-409]h:mm AM/PM", "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)", "[\$-411]ggge\"年\"m\"月\"d\"日\"")
        codes.forEachIndexed { r, c -> s.ensure(r, 0).apply { text = "45292.75"; formatCode = c } }
        s.ensure(3, 0).apply { text = "0"; formatCode = codes[1] }
        val bytes = ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdsWriter.write(Workbook(mutableListOf(s)), it) }.toByteArray()
        val back = com.therealityteam.aeropages.format.odf.OdsReader.read(bytes).sheets[0]
        fun shown(r: Int, lang: Lang = Lang.ZH_CN) = FormatCode.format(back.cell(r, 0)!!.text.toDouble(), back.cell(r, 0)!!.formatCode!!, lang).text
        assertEquals("6:00 PM", shown(0))
        assertEquals(" 45,292.75 ", shown(1))
        assertEquals(" -   ", shown(3))
        assertEquals("令和6年1月1日", shown(2))
    }

    @Test
    fun scientificDecimalsReachTheFile() {
        val s = Sheet(name = "S")
        s.ensure(0, 0).apply { text = "1234.5678"; numberFormat = NumberFormat.SCIENTIFIC; decimals = 3 }
        s.ensure(1, 0).apply { text = "1234.5678"; numberFormat = NumberFormat.SCIENTIFIC }
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(Workbook(mutableListOf(s)), it) }.toByteArray()
        val styles = String(OfficeXml.unzip(bytes)["xl/styles.xml"]!!, Charsets.UTF_8)
        assertTrue(styles, styles.contains("formatCode=\"0.000E+00\""))
        val back = XlsxReader.read(bytes).sheets[0]
        assertEquals("1.235E+03", NumberFormatting.shown("1234.5678", back.cell(0, 0)).text)
        assertEquals("1.23E+03", NumberFormatting.shown("1234.5678", back.cell(1, 0)).text)
    }
}
