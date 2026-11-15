package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Dynamic-array functions in a non-spilling grid: UNIQUE / FILTER / SORT feed aggregates, INDEX and MATCH, and show comma-separated. */
class ArrayFunctionsTest {

    private fun sheet(): Sheet {
        val s = Sheet()
        listOf("Beijing", "Shanghai", "Beijing", "Shenzhen", "Shanghai", "Chengdu").forEachIndexed { r, t -> s.ensure(r, 0).text = t }
        listOf("10", "20", "30", "40", "5", "15").forEachIndexed { r, t -> s.ensure(r, 1).text = t }
        return s
    }

    private fun v(s: Sheet, ref: String, formula: String): String {
        val (r, c) = A1.parse(ref)!!
        s.ensure(r, c).text = formula
        return FormulaEngine.display(s, r, c, listOf(s))
    }

    @Test
    fun uniqueFilterSortAndTheirConsumers() {
        val s = sheet()
        assertEquals("Beijing, Shanghai, Shenzhen, Chengdu", v(s, "D1", "=UNIQUE(A1:A6)"))
        assertEquals("4", v(s, "D2", "=COUNTA(UNIQUE(A1:A6))"))
        assertEquals("Shanghai", v(s, "D3", "=INDEX(UNIQUE(A1:A6),2)"))
        assertEquals("3", v(s, "D4", "=MATCH(\"shenzhen\",UNIQUE(A1:A6),0)"))
        assertEquals("40", v(s, "D5", "=SUM(FILTER(B1:B6,A1:A6=\"Beijing\"))"))
        assertEquals("Shanghai, Beijing, Shenzhen", v(s, "D6", "=FILTER(A1:A6,B1:B6>15)"))
        assertEquals("none", v(s, "D7", "=FILTER(A1:A6,B1:B6>1000,\"none\")"))
        assertEquals("#CALC!", v(s, "D8", "=FILTER(A1:A6,B1:B6>1000)"))
        assertEquals("40, 30, 20, 15, 10, 5", v(s, "D9", "=SORT(B1:B6,1,-1)"))
        assertEquals("5", v(s, "D10", "=INDEX(SORT(B1:B6),1)"))
        assertEquals("Beijing, Chengdu, Shanghai, Shenzhen", v(s, "D11", "=SORT(UNIQUE(A1:A6))"))
        assertEquals("95", v(s, "D12", "=SUM(UNIQUE(B1:B6))-SUM(FILTER(B1:B6,A1:A6=\"Shanghai\"))")) // all six values are distinct (120); Shanghai rows 20 + 5
    }

    @Test
    fun multiColumnFilterKeepsWholeRowsAndComparisonsWorkOnText() {
        val s = sheet()
        assertEquals("Shanghai, 20, Beijing, 30, Shenzhen, 40", v(s, "D1", "=FILTER(A1:B6,B1:B6>15)"))
        assertEquals("2", v(s, "D2", "=COUNT(FILTER(B1:B6,A1:A6<>\"Beijing\"))-2"))
        assertEquals("3", v(s, "D3", "=MAX(FILTER(B1:B6,A1:A6>=\"Shanghai\"))/10*0.75"))
    }

    @Test
    fun xlsxRoundTripAddsAndStripsTheFutureFunctionPrefix() {
        val wb = Workbook(mutableListOf(sheet()))
        wb.sheets[0].ensure(0, 3).text = "=UNIQUE(A1:A6)"
        wb.sheets[0].ensure(1, 3).text = "=SUM(FILTER(B1:B6,A1:A6=\"Beijing\"))+XLOOKUP(\"Chengdu\",A1:A6,B1:B6)"
        wb.sheets[0].ensure(2, 3).text = "=SUM(B1:B6)"
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("<f>_xlfn.UNIQUE(A1:A6)</f>"))
        // FILTER and SORT as Excel writes them: `_xlfn._xlws.`.
        assertTrue(xml.contains("<f>SUM(_xlfn._xlws.FILTER(B1:B6,A1:A6=&quot;Beijing&quot;))+_xlfn.XLOOKUP(&quot;Chengdu&quot;,A1:A6,B1:B6)</f>"))
        assertTrue(xml.contains("<f>SUM(B1:B6)</f>"))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals("=UNIQUE(A1:A6)", back.cell(0, 3)?.text)
        assertEquals("=SUM(FILTER(B1:B6,A1:A6=\"Beijing\"))+XLOOKUP(\"Chengdu\",A1:A6,B1:B6)", back.cell(1, 3)?.text)
        assertEquals("55", FormulaEngine.display(back, 1, 3, listOf(back)))
    }
}
