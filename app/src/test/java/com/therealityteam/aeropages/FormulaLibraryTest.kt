package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.model.Sheet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine's functions as Excel works them out — each value here checked against LibreOffice's where the two
 * agree (FormulaOracleTest), and against Excel's documented behaviour where LibreOffice departs from it.
 */
class FormulaLibraryTest {

    /** A: 10, 20, 30, blank, "text", TRUE; B: a key column 1, 3, 5, 7, 9 with names in C; D: 2, 4, 4, 4, 5, 5, 7, 9; E: errors and a blank. */
    private fun sheet(): Sheet {
        val s = Sheet(name = "S")
        fun col(c: Int, vararg v: String) = v.forEachIndexed { r, t -> if (t.isNotEmpty()) s.ensure(r, c).text = t }
        col(0, "10", "20", "30", "", "text", "TRUE")
        col(1, "1", "3", "5", "7", "9")
        col(2, "one", "two", "three", "four", "five")
        col(3, "2", "4", "4", "4", "5", "5", "7", "9")
        col(4, "=1/0", "=NA()", "", "7", "=\"\"")
        col(5, "-10000", "3000", "4200", "6800")
        return s
    }

    private fun Sheet.eval(formula: String): String {
        ensure(40, 9).text = "=$formula"
        return FormulaEngine.display(this, 40, 9)
    }

    /** A formula's number to full precision (the cell shows six decimals). */
    private fun Sheet.num(formula: String): Double {
        ensure(40, 9).text = "=$formula"
        return (FormulaEngine.cached(this, 40, 9) as com.therealityteam.aeropages.model.CachedValue.Number).value
    }

    @Test
    fun roundingIsOnFifteenDigitsAndHalvesGoAwayFromZero() {
        val s = sheet()
        assertEquals("-3", s.eval("ROUND(-2.5,0)"))
        assertEquals("1.01", s.eval("ROUND(1.005,2)"))
        assertEquals("1200", s.eval("ROUND(1234.5678,-2)"))
        assertEquals("0.3", s.eval("ROUNDUP(0.1+0.2,1)"))
        assertEquals("-1.9", s.eval("ROUNDDOWN(-1.99,1)"))
        assertEquals("-1", s.eval("INT(-0.5)"))
        assertEquals("1.5", s.eval("MROUND(1.3,0.5)"))
        assertEquals("#NUM!", s.eval("MROUND(5,-2)"))
        assertEquals("-3", s.eval("CEILING(-2.1,-1)"))
        assertEquals("-2", s.eval("CEILING.MATH(-2.1)"))
        assertEquals("-3", s.eval("CEILING.MATH(-2.1,1,1)"))
        assertEquals("2", s.eval("FLOOR.MATH(2.9,2)"))
        assertEquals("4", s.eval("EVEN(3)"))
        assertEquals("-3", s.eval("ODD(-2)"))
        assertEquals("1", s.eval("ODD(0)"))
    }

    @Test
    fun arraysWorkItemByItem() {
        val s = sheet()
        // The Chinese monthly income-tax formula: the largest of the bands' tax.
        s.ensure(20, 0).text = "20000"
        assertEquals("1590", s.eval("MAX((A21-5000)*{0.03,0.1,0.2,0.25,0.3,0.35,0.45}-{0,210,1410,2660,4410,7160,15160},0)"))
        assertEquals("10", s.eval("SUM({1,2;3,4})"))
        assertEquals("107", s.eval("SUMPRODUCT(B1:B5*D1:D5)"))
        assertEquals("24", s.eval("SUMPRODUCT((B1:B5>2)*B1:B5)"))
        assertEquals("3", s.eval("SUMPRODUCT(--(D1:D8=4))"))
        assertEquals("27", s.eval("SUM(B1:B5*D1:D5>8)+SUM(B1:B5)+2"))
        assertEquals("ab", s.eval("CONCAT({\"a\",\"b\"})"))
        assertEquals("He said \"hi\"", s.eval("\"He said \"\"hi\"\"\""))
        assertEquals("1000 and 50%", s.eval("1E3&\" and \"&TEXT(50%,\"0%\")"))
        assertEquals("-0.5", s.eval("-50%"))
    }

    @Test
    fun aBlankCellIsNotEmptyText() {
        val s = sheet()
        // A4 is blank; E5 holds ="": text, which arithmetic and conditions refuse.
        assertEquals("0", s.eval("A4"))
        assertEquals("1", s.eval("A4+1"))
        assertEquals("#VALUE!", s.eval("E5+1"))
        assertEquals("#VALUE!", s.eval("IF(E5,1,2)"))
        assertEquals("2", s.eval("IF(A4,1,2)"))
        assertEquals("TRUE", s.eval("ISBLANK(A4)"))
        assertEquals("FALSE", s.eval("ISBLANK(E5)"))
        assertEquals("TRUE", s.eval("ISTEXT(E5)"))
        assertEquals("TRUE", s.eval("E5=\"\""))
        assertEquals("TRUE", s.eval("A4=\"\""))
        assertEquals("FALSE", s.eval("E5=0"))
        assertEquals("TRUE", s.eval("A4=0"))
        assertEquals("5", s.eval("COUNTA(A1:A6)"))
        assertEquals("4", s.eval("COUNTA(E1:E5)"))
        assertEquals("2", s.eval("COUNTBLANK(E1:E5)"))
        assertEquals("1", s.eval("TYPE(A4)"))
        assertEquals("2", s.eval("TYPE(E5)"))
    }

    @Test
    fun errorsInARangeAreValuesWhereExcelTakesThemSo() {
        val s = sheet()
        assertEquals("3", s.eval("COUNTA(E1:E4)"))
        assertEquals("1", s.eval("COUNT(E1:E4)"))
        assertEquals("1", s.eval("COUNTIF(E1:E4,\"#N/A\")"))
        assertEquals("1", s.eval("COUNTIF(E1:E4,\">5\")"))
        // A lookup passes errors by; a sum over one fails with it, as Excel's do.
        assertEquals("4", s.eval("MATCH(7,E1:E4,0)"))
        assertEquals("#DIV/0!", s.eval("SUM(E1:E4)"))
        assertEquals("16", s.eval("TYPE(E1)"))
        assertEquals("5", s.eval("ROWS(E1:E5)"))
        assertEquals("TRUE", s.eval("ISFORMULA(E1)"))
        assertEquals("=1/0", s.eval("FORMULATEXT(E1)"))
        assertEquals("7", s.eval("INDEX(E1:E5,4)"))
        // A sum range's error counts only where the criteria pick it.
        assertEquals("7", s.eval("SUMIF(B1:B5,\">5\",E1:E5)"))
        assertEquals("#N/A", s.eval("SUMIF(B1:B5,3,E1:E5)"))
    }

    @Test
    fun lookupsAsExcels() {
        val s = sheet()
        assertEquals("2", s.eval("MATCH(\"B?nana\",{\"apple\",\"banana\"},0)"))
        assertEquals("three", s.eval("VLOOKUP(\"th*\",C1:C5,1,FALSE)"))
        assertEquals("#REF!", s.eval("INDEX(B1:B5,6)"))
        assertEquals("25", s.eval("SUM(INDEX(B1:C5,0,1))"))
        assertEquals("three", s.eval("INDEX(B1:C5,3,2)"))
        assertEquals("#VALUE!", s.eval("CHOOSE(4,\"a\",\"b\")"))
        assertEquals("two", s.eval("LOOKUP(4,B1:B5,C1:C5)"))
        assertEquals("five", s.eval("LOOKUP(10,B1:C5)"))
        assertEquals("B", s.eval("LOOKUP(86,{0,60,70,80,90},{\"F\",\"D\",\"C\",\"B\",\"A\"})"))
        assertEquals("2", s.eval("XMATCH(4,B1:B5,-1)"))
        assertEquals("3", s.eval("XMATCH(4,B1:B5,1)"))
        assertEquals("\$C\$2", s.eval("ADDRESS(2,3)"))
        assertEquals("R[2]C[3]", s.eval("ADDRESS(2,3,4,FALSE)"))
        assertEquals("'Q3 Sales'!\$A\$1", s.eval("ADDRESS(1,1,1,TRUE,\"Q3 Sales\")"))
    }

    @Test
    fun textAsExcels() {
        val s = sheet()
        assertEquals("O'Neil-Smith 2Nd", s.eval("PROPER(\"o'neil-smith 2nd\")"))
        assertEquals("Hello World", s.eval("PROPER(\"hello wORLD\")"))
        assertEquals("aba", s.eval("SUBSTITUTE(\"aaa\",\"a\",\"b\",2)"))
        assertEquals("abc", s.eval("SUBSTITUTE(\"abc\",\"\",\"x\")"))
        assertEquals("2", s.eval("SEARCH(\"h?l\",\"ohello\")"))
        assertEquals("5", s.eval("SEARCH(\"~?\",\"what?\")"))
        assertEquals("ab", s.eval("CLEAN(\"a\"&CHAR(7)&\"b\")"))
        assertEquals("1,234.6", s.eval("FIXED(1234.567,1)"))
        assertEquals("1200", s.eval("FIXED(1234.5,-2,TRUE)"))
        assertEquals("a-b", s.eval("TEXTBEFORE(\"a-b-c\",\"-\",-1)"))
        assertEquals("c", s.eval("TEXTAFTER(\"a-b-c\",\"-\",2)"))
        assertEquals("none", s.eval("TEXTAFTER(\"abc\",\"x\",1,0,0,\"none\")"))
        assertEquals("☃", s.eval("UNICHAR(9731)"))
        assertEquals("65", s.eval("UNICODE(\"A\")"))
        assertEquals("#VALUE!", s.eval("REPT(\"x\",-1)"))
    }

    @Test
    fun statisticsAsExcels() {
        val s = sheet()
        assertEquals("#DIV/0!", s.eval("AVERAGE(C1:C3)"))
        assertEquals("#N/A", s.eval("MODE(1,2,3)"))
        assertEquals("4", s.eval("MODE.SNGL(D1:D8)"))
        assertEquals(2.138089935299395, s.num("STDEV.S(D1:D8)"), 1e-12)
        assertEquals("4", s.eval("PERCENTILE(D1:D8,0.25)"))
        assertEquals("4", s.eval("QUARTILE(D1:D8,1)"))
        assertEquals("5", s.eval("PERCENTILE.INC(D1:D8,0.6)"))
        // AVERAGEA: TRUE is 1 and "text" 0; the blank is passed by — 61 / 5.
        assertEquals("12.2", s.eval("AVERAGEA(A1:A6)"))
        assertEquals("30", s.eval("MAXA(A1:A6)"))
        assertEquals("0", s.eval("MINA(A5:A6)"))
        // Descending, the three 4s hold ranks 5, 6 and 7.
        assertEquals("6", s.eval("RANK.AVG(4,D1:D8)"))
        assertEquals(Math.sqrt(3.0) / 2, s.num("CORREL(B1:B5,D1:D5)"), 1e-12)
        assertEquals(0.841344746068543, s.num("NORM.S.DIST(1,TRUE)"), 1e-12)
        assertEquals(1.33333, s.num("NORM.S.INV(0.908789)"), 1e-5)
    }

    @Test
    fun datesAndTimesAsExcels() {
        val s = sheet()
        assertEquals("36161", s.eval("DATE(99,1,1)"))
        assertEquals("1", s.eval("WEEKDAY(DATE(2024,1,1),2)"))
        assertEquals("0", s.eval("WEEKDAY(DATE(2024,1,1),3)"))
        assertEquals("53", s.eval("WEEKNUM(DATE(2024,12,31))"))
        assertEquals("1", s.eval("ISOWEEKNUM(DATE(2024,12,31))"))
        assertEquals(0.520833333333333, s.num("TIME(12,30,0)"), 1e-12)
        assertEquals("18", s.eval("HOUR(\"18:30\")"))
        assertEquals("45352", s.eval("DATEVALUE(\"2024-03-01\")"))
        assertEquals("0.5", s.eval("TIMEVALUE(\"12:00\")"))
        assertEquals("60", s.eval("DAYS360(DATE(2024,1,31),DATE(2024,3,31))"))
        assertEquals("1", s.eval("YEARFRAC(DATE(2024,1,1),DATE(2024,12,31))"))
        assertEquals("23", s.eval("NETWORKDAYS.INTL(DATE(2024,1,1),DATE(2024,1,31),\"0000110\")"))
        assertEquals("22", s.eval("NETWORKDAYS(DATE(2024,1,1),DATE(2024,1,31),DATE(2024,1,2))"))
        assertEquals("45299", s.eval("WORKDAY.INTL(DATE(2024,1,1),5,7)"))
    }

    @Test
    fun loansAndInvestmentsAsExcels() {
        val s = sheet()
        fun n(f: String) = s.num(f)
        assertEquals(-1073.64324602428, n("PMT(0.05/12,360,200000)"), 1e-9)
        assertEquals(-66.6666666666667, n("IPMT(0.1/12,1,36,8000)"), 1e-9)
        assertEquals(-191.470830884033, n("PPMT(0.1/12,1,36,8000)"), 1e-9)
        assertEquals(59777.1458511878, n("PV(0.08/12,240,-500)"), 1e-7)
        assertEquals(2581.40337406014, n("FV(0.06/12,10,-200,-500,1)"), 1e-9)
        assertEquals(1188.44341233522, n("NPV(0.1,-10000,3000,4200,6800)"), 1e-8)
        assertEquals(0.163405600688989, n("IRR(F1:F4)"), 1e-9)
        assertEquals(0.00770147248823326, n("RATE(48,-200,8000)"), 1e-12)
        assertEquals(10.5886444594232, n("NPER(0.01,-100,1000)"), 1e-9)
        assertEquals(480.0, n("DDB(2400,300,10,1)"), 1e-9)
        assertEquals(-11135.2321307508, n("CUMIPMT(0.09/12,30*12,125000,13,24,0)"), 1e-7)
        assertEquals(0.0535426673707582, n("EFFECT(0.0525,4)"), 1e-12)
    }

    @Test
    fun odfFormulasKeepArrayConstants() {
        assertEquals("of:=SUM({1;2|3;4})", OdsWriter.toOdfFormula("=SUM({1,2;3,4})"))
        assertEquals("=SUM({1,2;3,4})", OdsReader.fromOdfFormula("of:=SUM({1;2|3;4})"))
        assertEquals("of:=MAX([.A1]*{0.03;0.1};0)", OdsWriter.toOdfFormula("=MAX(A1*{0.03,0.1},0)"))
    }

    @Test
    fun odfNamesExcelsNewerFunctionsAsLibreOfficeDoes() {
        assertEquals("of:=COM.MICROSOFT.TEXTJOIN(\", \";TRUE();[.A1:.A3])+COM.MICROSOFT.CEILING(2.1;1)+LOG10(100)",
            OdsWriter.toOdfFormula("=TEXTJOIN(\", \",TRUE,A1:A3)+CEILING(2.1,1)+LOG10(100)"))
        assertEquals("of:=FORMULA([.A1])&LEGACY.NORMSDIST(1)", OdsWriter.toOdfFormula("=FORMULATEXT(A1)&NORMSDIST(1)"))
        assertEquals("=IFS(A1>0,1,TRUE,2)+FLOOR(2.9,1)", OdsReader.fromOdfFormula("of:=COM.MICROSOFT.IFS([.A1]>0;1;TRUE();2)+COM.MICROSOFT.FLOOR(2.9;1)"))
        assertEquals("=FORMULATEXT(A1)&NORMSINV(0.5)", OdsReader.fromOdfFormula("of:=FORMULA([.A1])&LEGACY.NORMSINV(0.5)"))
    }
}
