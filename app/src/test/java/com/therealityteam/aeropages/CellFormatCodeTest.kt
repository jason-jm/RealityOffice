package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Custom number format codes on cells: the grid, colours, undo, Increase Decimal, Text cells, TEXT() and xlsx files. */
class CellFormatCodeTest {

    /** 2026-09-29 as an Excel serial. */
    private val day = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(2026, 9, 29))
    private val chineseDate = "yyyy\"年\"m\"月\"d\"日\""

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    @Test
    fun theGridShowsTheCodeAndItsColour() {
        val e = editor()
        e.setCell(0, 0, day.toString())
        e.setCell(1, 0, "-1234.5")
        e.select(0, 0)
        assertTrue(e.setFormatCode(chineseDate))
        assertEquals("2026年9月29日", e.display(0, 0))
        assertEquals("the code's kind is kept for what reads it", NumberFormat.DATE, e.sheet.cell(0, 0)!!.numberFormat)
        e.select(1, 0)
        assertTrue(e.setFormatCode("#,##0.00;[Red]-#,##0.00"))
        assertEquals("-1,234.50", e.display(1, 0))
        assertEquals(0xFFFF0000, e.displayColor(1, 0))
        e.undo()
        assertEquals("-1234.5", e.display(1, 0))
        assertNull(e.displayColor(1, 0))
        e.redo()
        assertEquals("-1,234.50", e.display(1, 0))
        assertFalse("an unreadable code is refused", e.setFormatCode("0.00\""))
        assertEquals("#,##0.00;[Red]-#,##0.00", e.activeFormatCode)
        e.setNumberFormat(NumberFormat.NUMBER2)
        assertNull("a plain format replaces the code", e.activeFormatCode)
        assertEquals("-1234.50", e.display(1, 0))
    }

    @Test
    fun increaseDecimalStepsInsideTheCode() {
        val e = editor()
        e.setCell(0, 0, "1500000")
        e.select(0, 0)
        e.setFormatCode("0.0,,\"M\"")
        assertEquals("1.5M", e.display(0, 0))
        e.adjustDecimals(1)
        assertEquals("0.00,,\"M\"", e.activeFormatCode)
        assertEquals("1.50M", e.display(0, 0))
        e.adjustDecimals(-1)
        e.adjustDecimals(-1)
        assertEquals("2M", e.display(0, 0))
    }

    @Test
    fun textCellsKeepWhatWasTyped() {
        val e = editor()
        e.select(0, 0)
        e.setFormatCode("@")
        e.setCell(0, 0, "00123")
        e.setCell(1, 0, "7")
        e.setCell(2, 0, "=SUM(A1:A2)")
        e.setCell(3, 0, "=A1+1")
        assertEquals("00123", e.display(0, 0))
        assertEquals("SUM passes text by, as Excel's does", "7", e.display(2, 0))
        assertEquals("arithmetic still reads it", "124", e.display(3, 0))
    }

    @Test
    fun theTextFunctionUsesTheSameCodes() {
        val e = editor()
        e.setCell(0, 0, "=TEXT($day,\"yyyy年m月d日\")")
        e.setCell(1, 0, "=TEXT(0.256,\"0.0%\")")
        e.setCell(2, 0, "=TEXT(1234.5,\"#,##0.00\")")
        e.setCell(3, 0, "=TEXT(\"abc\",\"0\")")
        e.setCell(4, 0, "=TEXT(5,\"[Red0\")")
        e.setCell(5, 0, "=TEXT(1234,\"[DBNum2][\$-804]General\")")
        e.setCell(6, 0, "=TEXT(1.5,\"[h]:mm\")")
        assertEquals("2026年9月29日", e.display(0, 0))
        assertEquals("25.6%", e.display(1, 0))
        assertEquals("1,234.50", e.display(2, 0))
        assertEquals("abc", e.display(3, 0))
        assertEquals("#VALUE!", e.display(4, 0))
        assertEquals("壹仟贰佰叁拾肆", e.display(5, 0))
        assertEquals("36:00", e.display(6, 0))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { out[it.name] = z.readBytes().decodeToString() } }
        return out
    }

    @Test
    fun xlsxKeepsCodesAndPlainFormatsStayPlain() {
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        s.ensure(0, 0).apply { text = day.toString(); formatCode = chineseDate; numberFormat = NumberFormat.DATE }
        s.ensure(0, 1).apply { text = "00123"; formatCode = "@" }
        s.ensure(0, 2).apply { text = "-5"; formatCode = "#,##0.00;[Red]-#,##0.00" }
        s.ensure(0, 3).apply { text = "0.256"; numberFormat = NumberFormat.PERCENT }
        s.ensure(0, 4).apply { text = "0.256"; numberFormat = NumberFormat.PERCENT; decimals = 0 }
        s.ensure(0, 5).apply { text = "1234.5"; numberFormat = NumberFormat.CURRENCY_CNY; decimals = 1 }
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val styles = unzip(bos.toByteArray())["xl/styles.xml"]!!
        assertTrue(styles.contains("formatCode=\"yyyy&quot;年&quot;m&quot;月&quot;d&quot;日&quot;\""))
        assertTrue("Text is Excel's built-in 49", styles.contains("numFmtId=\"49\""))
        assertTrue("the plain percentage shows one decimal in Excel too", styles.contains("formatCode=\"0.0%\""))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals(chineseDate, back.cell(0, 0)!!.formatCode)
        assertEquals(NumberFormat.DATE, back.cell(0, 0)!!.numberFormat)
        assertEquals("@", back.cell(0, 1)!!.formatCode)
        assertEquals("00123", back.cell(0, 1)!!.text)
        assertEquals("#,##0.00;[Red]-#,##0.00", back.cell(0, 2)!!.formatCode)
        for (col in 3..5) assertNull("plain formats read back plain", back.cell(0, col)!!.formatCode)
        assertEquals(NumberFormat.PERCENT, back.cell(0, 3)!!.numberFormat)
        assertEquals("the default place comes back explicit", 1, back.cell(0, 3)!!.decimals)
        assertEquals(0, back.cell(0, 4)!!.decimals)
        assertEquals(NumberFormat.CURRENCY_CNY, back.cell(0, 5)!!.numberFormat)
        assertEquals(1, back.cell(0, 5)!!.decimals)
    }

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun builtInFormatsFromExcelFilesShowAsThere() {
        // Chinese Excel's long date is built-in 31 (no <numFmt> for it); 9 is 0%, 49 Text, 12 # ?/?, 176 a custom code.
        val styles = """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="1"><numFmt numFmtId="176" formatCode="0.0,,&quot;M&quot;"/></numFmts>""" +
            """<fonts count="1"><font><sz val="11"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="1"><border/></borders><cellXfs count="6"><xf numFmtId="0"/><xf numFmtId="31" applyNumberFormat="1"/><xf numFmtId="9" applyNumberFormat="1"/>""" +
            """<xf numFmtId="49" applyNumberFormat="1"/><xf numFmtId="12" applyNumberFormat="1"/><xf numFmtId="176" applyNumberFormat="1"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1">""" +
            """<c r="A1" s="1"><v>$day</v></c><c r="B1" s="2"><v>0.256</v></c><c r="C1" s="3" t="inlineStr"><is><t>00123</t></is></c>""" +
            """<c r="D1" s="4"><v>1.5</v></c><c r="E1" s="5"><v>1500000</v></c></row></sheetData></worksheet>"""
        val wb = XlsxReader.read(zipOf(mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet,
        )))
        val e = SheetEditor().apply { load(wb) }
        assertEquals("2026年9月29日", e.display(0, 0))
        assertEquals("built-in 0% shows no decimals", "26%", e.display(0, 1))
        assertEquals("00123", e.display(0, 2))
        assertEquals("1 1/2", e.display(0, 3))
        assertEquals("1.5M", e.display(0, 4))
    }
}
