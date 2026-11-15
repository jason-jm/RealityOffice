package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.CachedValue
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Formulas' values as Excel's: TRUE and FALSE (not 1 and 0), its order across numbers, text and logical
 * values, its strict operators, its error values — and TRUE, FALSE and formulas' values in xlsx and ODS.
 */
class LogicalValuesTest {

    /** A1 = 7, A2 = TRUE (typed), A3 = "apple", A4 = 3, A5 blank, A6 = FALSE, A7 = "5" (text); B1:B4 a small lookup table. */
    private fun sheet(): Sheet {
        val s = Sheet(name = "Data")
        listOf("7", "TRUE", "apple", "3", "", "FALSE").forEachIndexed { r, t -> if (t.isNotEmpty()) s.ensure(r, 0).text = t }
        s.ensure(6, 0).apply { text = "5"; formatCode = "@" }
        listOf("pear" to "1", "plum" to "2", "fig" to "3").forEachIndexed { r, (k, v) -> s.ensure(r, 1).text = k; s.ensure(r, 2).text = v }
        return s
    }

    private fun Sheet.eval(formula: String): String {
        ensure(30, 9).text = "=$formula"
        return FormulaEngine.display(this, 30, 9)
    }

    @Test
    fun comparisonsAreTrueOrFalseInExcelsOrder() {
        val s = sheet()
        assertEquals("TRUE", s.eval("A1>5"))
        assertEquals("FALSE", s.eval("A1<>7"))
        // Numbers before text, text before FALSE and TRUE; a number never equals text or a logical value.
        assertEquals("TRUE", s.eval("1<\"a\""))
        assertEquals("TRUE", s.eval("\"zebra\"<FALSE"))
        assertEquals("FALSE", s.eval("TRUE=1"))
        assertEquals("FALSE", s.eval("A7=5"))
        assertEquals("TRUE", s.eval("\"APPLE\"=A3"))
        // A blank stands for 0, "" or FALSE, whatever it is compared with.
        assertEquals("TRUE", s.eval("A5=0"))
        assertEquals("TRUE", s.eval("A5=\"\""))
        assertEquals("TRUE", s.eval("A5=FALSE"))
        // Fifteen significant digits, as Excel holds numbers; a chain works left to right.
        assertEquals("TRUE", s.eval("0.1+0.2=0.3"))
        assertEquals("FALSE", s.eval("1<2<3"))
    }

    @Test
    fun logicalCellsAndAggregates() {
        val s = sheet()
        assertEquals("TRUE", s.eval("A2"))
        assertEquals("2", s.eval("A2+1"))
        assertEquals("1", s.eval("N(A2)"))
        // A range's (or a reference's) TRUE and FALSE count nothing; typed into the call they count.
        assertEquals("10", s.eval("SUM(A1:A7)"))
        assertEquals("7", s.eval("SUM(A1,A2)"))
        assertEquals("2", s.eval("SUM(TRUE,1)"))
        assertEquals("2", s.eval("COUNT(A1:A7)"))
        assertEquals("6", s.eval("COUNTA(A1:A7)"))
        assertEquals("FALSE", s.eval("AND(A1:A7)"))
        assertEquals("TRUE", s.eval("OR(A1:A7)"))
        assertEquals("#VALUE!", s.eval("AND(B1:B3)"))
        assertEquals("TRUE", s.eval("NOT(A5)"))
        assertEquals("TRUE", s.eval("ISLOGICAL(A2)"))
        assertEquals("FALSE", s.eval("ISNUMBER(A7)"))
        assertEquals("TRUE", s.eval("ISNONTEXT(A5)"))
        s.ensure(0, 3).text = "=1"
        assertEquals("TRUE", s.eval("ISFORMULA(D1)"))
        assertEquals("FALSE", s.eval("ISFORMULA(A1)"))
        assertEquals("ab", s.eval("\"a\"&IF(A1>5,\"b\")"))
        assertEquals("xTRUE", s.eval("\"x\"&A2"))
    }

    @Test
    fun ifAndOperatorsAsExcels() {
        val s = sheet()
        assertEquals("FALSE", s.eval("IF(A1>9,\"big\")"))
        assertEquals("0", s.eval("IF(A1>5,,1)"))
        assertEquals("yes", s.eval("IF(\"TRUE\",\"yes\",\"no\")"))
        assertEquals("#VALUE!", s.eval("IF(A3,1,2)"))
        // Operators take numbers, text reading as one, TRUE / FALSE and blanks; other text is #VALUE!.
        assertEquals("#VALUE!", s.eval("A3+1"))
        assertEquals("6", s.eval("A7+1"))
        assertEquals("1", s.eval("A5+1"))
        assertEquals("apple", s.eval("+A3"))
        // Text written as a number would be typed reads as that number, as Excel's arithmetic and VALUE take it.
        assertEquals("1235", s.eval("\"1,234\"+1"))
        assertEquals("0.5", s.eval("VALUE(\"50%\")"))
        assertEquals("45293", s.eval("\"2024-01-01\"+1"))
        assertEquals("4", s.eval("-2^2"))
    }

    @Test
    fun errorValuesAsExcelNamesThem() {
        val s = sheet()
        assertEquals("#NAME?", s.eval("NOSUCHFUNCTION(1)"))
        assertEquals("#NAME?", s.eval("nosuchname+1"))
        assertEquals("#N/A", s.eval("VLOOKUP(\"kiwi\",B1:C3,2,FALSE)"))
        assertEquals("#REF!", s.eval("VLOOKUP(\"fig\",B1:C3,3,FALSE)"))
        assertEquals("3", s.eval("VLOOKUP(\"FIG\",B1:C3,2,FALSE)"))
        assertEquals("#N/A", s.eval("MATCH(9,C1:C3,0)"))
        assertEquals("#VALUE!", s.eval("FIND(\"z\",\"abc\")"))
        assertEquals("#NUM!", s.eval("SQRT(-1)"))
        assertEquals("#DIV/0!", s.eval("0^-1"))
        assertEquals("#REF!", s.eval("#REF!+1"))
        assertEquals("#ERROR!", s.eval("1+*2"))
        assertEquals("#N/A", s.eval("NA()"))
        assertEquals("TRUE", s.eval("ISNA(MATCH(9,C1:C3,0))"))
        assertEquals("FALSE", s.eval("ISERR(NA())"))
        assertEquals("TRUE", s.eval("ISERR(1/0)"))
        assertEquals("7", s.eval("ERROR.TYPE(NA())"))
        assertEquals("2", s.eval("ERROR.TYPE(1/0)"))
        assertEquals("#N/A", s.eval("ERROR.TYPE(1)"))
        assertEquals("none", s.eval("IFNA(VLOOKUP(\"kiwi\",B1:C3,2,FALSE),\"none\")"))
        assertTrue(FormulaEngine.isError("#N/A"))
        assertFalse(FormulaEngine.isError("#hashtag"))
    }

    @Test
    fun criteriaMatchAsExcels() {
        val s = sheet()
        // A number to compare with matches numbers only: not TRUE, a blank or text.
        assertEquals("2", s.eval("COUNTIF(A1:A7,\">0\")"))
        assertEquals("1", s.eval("COUNTIF(A1:A7,\"<5\")"))
        assertEquals("1", s.eval("COUNTIF(A1:A7,TRUE)"))
        assertEquals("1", s.eval("COUNTIF(A1:A7,5)"))
        // Text compared with text; a date written as one.
        assertEquals("2", s.eval("COUNTIF(B1:B3,\">p\")"))
        s.ensure(10, 0).text = "45292"   // 2024-01-01
        s.ensure(11, 0).text = "45291"
        assertEquals("1", s.eval("COUNTIF(A11:A12,\">=2024-01-01\")"))
        assertEquals("10", s.eval("SUMIF(A1:A7,\"<>apple\")"))
    }

    @Test
    fun rangesWholeAndInExcelsOrder() {
        val s = sheet()
        assertEquals("pearplumfig", s.eval("CONCAT(B1:B3)"))
        assertEquals("7,TRUE,apple,3,FALSE,5", s.eval("TEXTJOIN(\",\",TRUE,A1:A7)"))
        assertEquals("7,TRUE,apple,3,,FALSE,5", s.eval("TEXTJOIN(\",\",FALSE,A1:A7)"))
        assertEquals("3, 7, apple, TRUE", s.eval("SORT(A1:A4)"))
    }

    @Test
    fun cachedValuesAreTyped() {
        val s = sheet()
        s.ensure(0, 4).text = "=A1/4"
        s.ensure(1, 4).text = "=A1>5"
        s.ensure(2, 4).text = "=UPPER(A3)"
        s.ensure(3, 4).text = "=1/0"
        assertEquals(CachedValue.Number(1.75), FormulaEngine.cached(s, 0, 4))
        assertEquals(CachedValue.Logical(true), FormulaEngine.cached(s, 1, 4))
        assertEquals(CachedValue.Text("APPLE"), FormulaEngine.cached(s, 2, 4))
        assertEquals(CachedValue.Error("#DIV/0!"), FormulaEngine.cached(s, 3, 4))
        assertEquals(null, FormulaEngine.cached(s, 0, 0))
    }

    private fun partOf(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val en = z.nextEntry ?: break; if (en.name == name) return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no $name")
    }

    /** The workbook with formulas of every kind in E1:E4. */
    private fun book(): Workbook {
        val s = sheet()
        s.ensure(0, 4).text = "=A1/3"
        s.ensure(1, 4).text = "=A1>5"
        s.ensure(2, 4).text = "=UPPER(A3)"
        s.ensure(3, 4).text = "=1/0"
        return Workbook(mutableListOf(s))
    }

    @Test
    fun xlsxKeepsLogicalCellsAndFormulasValues() {
        val wb = book()
        val bytes = ByteArrayOutputStream().also { out ->
            XlsxWriter.write(wb, out) { si, r, c -> FormulaEngine.cached(wb.sheets[si], r, c, wb.sheets) }
        }.toByteArray()
        val xml = partOf(bytes, "xl/worksheets/sheet1.xml")
        assertTrue(xml, xml.contains("""<c r="A2" t="b"><v>1</v></c>"""))
        assertTrue(xml, xml.contains("""<c r="A6" t="b"><v>0</v></c>"""))
        assertTrue(xml, xml.contains("""<c r="E1"><f>A1/3</f><v>2.3333333333333335</v></c>"""))
        assertTrue(xml, xml.contains("""<c r="E2" t="b"><f>A1&gt;5</f><v>1</v></c>"""))
        assertTrue(xml, xml.contains("""<c r="E3" t="str"><f>UPPER(A3)</f><v>APPLE</v></c>"""))
        // An error is left for the reader to work out.
        assertTrue(xml, xml.contains("""<c r="E4"><f>1/0</f></c>"""))
        assertTrue(partOf(bytes, "xl/workbook.xml").contains("""<calcPr fullCalcOnLoad="1"/>"""))
        val back = XlsxReader.read(bytes).sheets[0]
        assertEquals("TRUE", back.cell(1, 0)?.text)
        assertEquals("FALSE", back.cell(5, 0)?.text)
        assertEquals("=A1>5", back.cell(1, 4)?.text)
    }

    @Test
    fun odsKeepsBooleansAndFormulasValues() {
        val wb = book()
        val bytes = ByteArrayOutputStream().also { out ->
            OdsWriter.write(wb, out) { si, r, c -> FormulaEngine.cached(wb.sheets[si], r, c, wb.sheets) }
        }.toByteArray()
        val xml = partOf(bytes, "content.xml")
        // TRUE and FALSE: boolean values shown through a boolean style, as LibreOffice's are.
        assertTrue(xml, xml.contains("""<number:boolean-style style:name="Nbool"><number:boolean/></number:boolean-style>"""))
        assertTrue(xml, Regex("""office:value-type="boolean" office:boolean-value="true"><text:p>TRUE</text:p>""").containsMatchIn(xml))
        assertTrue(xml, xml.contains("""table:formula="of:=[.A1]&gt;5" office:value-type="boolean" office:boolean-value="true"><text:p>TRUE</text:p>"""))
        assertTrue(xml, xml.contains("""office:value="2.3333333333333335""""))
        assertTrue(xml, xml.contains("""table:formula="of:=UPPER([.A3])"><text:p>APPLE</text:p>"""))
        assertTrue(xml, xml.contains("""table:formula="of:=1/0"><text:p>#DIV/0!</text:p>"""))
        val back = OdsReader.read(bytes).sheets[0]
        assertEquals("TRUE", back.cell(1, 0)?.text)
        assertEquals("FALSE", back.cell(5, 0)?.text)
        assertEquals("TRUE", FormulaEngine.display(back, 1, 4))
        // The boolean style is how TRUE shows, not a number format the cell takes on.
        assertEquals(com.therealityteam.aeropages.model.NumberFormat.GENERAL, back.cell(1, 0)?.numberFormat)
        assertEquals(null, back.cell(1, 0)?.formatCode)
    }

    @Test
    fun odfFormulasHoldTheLogicalValuesAsOpenFormulasFunctions() {
        assertEquals("of:=IF([.A1]>1;TRUE();FALSE())", OdsWriter.toOdfFormula("=IF(A1>1,TRUE,false)"))
        assertEquals("of:=COUNTIF([.D2:.D5]; TRUE())", OdsWriter.toOdfFormula("=COUNTIF(D2:D5, TRUE)"))
        // Already a call, a sheet named TRUE, text: as they are.
        assertEquals("of:=TRUE()", OdsWriter.toOdfFormula("=TRUE()"))
        assertEquals("of:=\"TRUE\"&[TRUE.A1]", OdsWriter.toOdfFormula("=\"TRUE\"&TRUE!A1"))
        assertEquals("=IF(A1>1,TRUE,FALSE)", OdsReader.fromOdfFormula("of:=IF([.A1]>1;TRUE();FALSE())"))
        assertEquals("=\"TRUE()\"&TRUE", OdsReader.fromOdfFormula("of:=\"TRUE()\"&true()"))
    }

    @Test
    fun aParsedCellHoldingTrueIsTheLogicalValue() {
        val s = Sheet()
        s.ensure(0, 0).text = "TRUE"
        s.ensure(1, 0).apply { text = "TRUE"; formatCode = "@" }
        s.ensure(2, 0).text = "=A1"
        s.ensure(3, 0).text = "=ISLOGICAL(A2)"
        assertEquals("TRUE", FormulaEngine.display(s, 2, 0))
        assertEquals("text-formatted TRUE stays text", "FALSE", FormulaEngine.display(s, 3, 0))
        val (r, c) = A1.parse("A1")!!
        assertEquals(true, s.cell(r, c)?.storedLogical())
        assertEquals(null, s.cell(1, 0)?.storedLogical())
    }
}
