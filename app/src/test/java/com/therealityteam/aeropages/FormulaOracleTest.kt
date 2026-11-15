package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CachedValue
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * The formula engine against LibreOffice's (`-Daero.soffice=<its soffice>`): a workbook of formulas —
 * the functions the engine knows and the common ones it may not, with their edge cases, over a sheet of
 * numbers, text, TRUE / FALSE, blanks, dates and errors — is saved without values; LibreOffice works it out
 * and saves it as xlsx with each formula's value, and the two engines' values are compared cell by cell.
 * With `-Daero.corpusDir`, the spreadsheets there with formulas go through LibreOffice too, and our values
 * for its formulas are compared with its. The report is `aero-formula-oracle.txt` in the temp folder: one
 * line per formula on which the engines differ.
 */
class FormulaOracleTest {

    /** The data the cases read: numbers and mixed values (A), text (B), dates (C), a sorted key (D) and names (E), text numbers (F), errors (G), a sample (H), cash flows (I). */
    private fun data(): Sheet {
        val s = Sheet(name = "Data")
        fun col(c: Int, vararg v: String) = v.forEachIndexed { r, t -> if (t.isNotEmpty()) s.ensure(r, c).text = t }
        col(0, "10", "20", "30", "", "text", "TRUE", "-5", "0", "2.5", "1000")
        col(1, "apple", "Banana", "cherry", "", "Apple pie", "apple", "fig", "grape", "kiwi", "lemon")
        col(2, "45292", "45351", "45657", "44927", "45306.75", "45000", "45100", "36526", "47848", "45474")
        for (r in 0..9) s.ensure(r, 2).numberFormat = NumberFormat.DATE
        col(3, "1", "3", "5", "7", "9", "11", "13", "15", "17", "19")
        col(4, "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        col(5, "5", " 7 ", "1,234", "50%", "abc", "FALSE", "3.14159", "-0.5", "1E-20", "123456789")
        s.ensure(0, 5).formatCode = "@"; s.ensure(1, 5).formatCode = "@"; s.ensure(2, 5).formatCode = "@"; s.ensure(3, 5).formatCode = "@"
        col(6, "=1/0", "=NA()", "=SQRT(-1)", "=Data!B1+1", "", "7")
        col(7, "2", "4", "4", "4", "5", "5", "7", "9", "10", "12")
        col(8, "-10000", "3000", "4200", "6800", "-500")
        return s
    }

    /** The formulas compared, each as written after its `=`. */
    private val cases = listOf(
        // operators and coercion
        "1+2*3", "(1+2)*3", "2^10", "-2^2", "10/4", "7/0", "\"3\"+4", "\"abc\"+1", "TRUE+TRUE", "Data!A4+1", "Data!F1+1", "Data!F2+1",
        "Data!F3*2", "Data!F4*2", "0.1+0.2", "1/3", "2/3*3", "1E308*10", "50%", "\"a\"&1", "\"a\"&TRUE", "1&2", "\"x\"&Data!A4", "\"x\"&Data!C1",
        "1<2", "\"a\"<\"B\"", "\"a\"=\"A\"", "1=\"1\"", "TRUE>1", "Data!A4=0", "Data!A4=\"\"", "Data!A4=FALSE", "0.1+0.2=0.3", "\"10\"<\"9\"", "1<>1",
        "Data!B1>Data!A1", "Data!A6=TRUE", "Data!A6+1", "-Data!A6", "+Data!B1", "Data!G1+1", "Data!G2&\"\"",
        // math
        "ABS(-3)", "SQRT(16)", "SQRT(-1)", "POWER(2,0.5)", "POWER(0,-1)", "EXP(1)", "LN(10)", "LN(0)", "LOG10(1000)", "LOG(8,2)", "LOG(100)", "PI()",
        "SIN(PI()/2)", "COS(0)", "TAN(PI()/4)", "ASIN(1)", "ACOS(-1)", "ACOS(2)", "ATAN(1)", "ATAN2(1,1)", "ATAN2(-1,0)", "DEGREES(PI())", "RADIANS(180)",
        "INT(-2.5)", "INT(2.5)", "TRUNC(-2.7)", "TRUNC(3.14159,2)", "ROUND(2.5,0)", "ROUND(-2.5,0)", "ROUND(1234.5678,-2)", "ROUND(2.675,2)",
        "ROUND(1.005,2)", "ROUNDUP(2.1,0)", "ROUNDUP(-2.1,0)", "ROUNDUP(3.14159,3)", "ROUNDDOWN(2.9,0)", "ROUNDDOWN(-2.9,0)", "CEILING(2.1,1)",
        "CEILING(2.5,0.5)", "CEILING(-2.1,-1)", "FLOOR(2.9,1)", "FLOOR(-2.1,-1)", "FLOOR(2.5,0.5)", "MOD(10,3)", "MOD(-10,3)", "MOD(10,-3)", "MOD(5,0)",
        "MOD(5.5,2)", "SIGN(-4)", "SIGN(0)", "PRODUCT(1,2,3)", "PRODUCT(Data!A1:A3)", "SUMSQ(1,2,3)", "SUMPRODUCT(Data!D1:D3,Data!H1:H3)",
        "SUMPRODUCT(Data!A1:A10)", "RANDBETWEEN(3,3)", "QUOTIENT(7,2)", "QUOTIENT(-7,2)", "MROUND(10,3)", "MROUND(-10,-3)", "GCD(12,18)", "LCM(4,6)",
        "FACT(5)", "FACT(0)", "COMBIN(5,2)", "PERMUT(5,2)", "EVEN(3)", "EVEN(-3)", "ODD(2)", "ODD(-2)", "SQRTPI(1)", "CEILING.MATH(2.1)",
        "FLOOR.MATH(-2.1)", "ABS(Data!A4)", "SQRT(Data!F1)",
        // aggregates
        "SUM(Data!A1:A10)", "SUM(Data!A1:A10,TRUE)", "SUM(Data!A6)", "SUM(\"2\",1)", "SUM(Data!F1:F10)", "AVERAGE(Data!A1:A10)", "AVERAGE(Data!B1:B3)",
        "COUNT(Data!A1:A10)", "COUNT(Data!A1:A10,TRUE,\"2\")", "COUNTA(Data!A1:A10)", "COUNTBLANK(Data!A1:A10)", "MIN(Data!A1:A10)", "MAX(Data!A1:A10)",
        "MAX(Data!B1:B3)", "MEDIAN(Data!H1:H10)", "MEDIAN(Data!H1:H9)", "MODE(Data!H1:H10)", "STDEV(Data!H1:H10)", "STDEVP(Data!H1:H10)",
        "VAR(Data!H1:H10)", "VARP(Data!H1:H10)", "LARGE(Data!H1:H10,2)", "LARGE(Data!H1:H10,11)", "SMALL(Data!H1:H10,3)", "RANK(5,Data!H1:H10)",
        "RANK(5,Data!H1:H10,1)", "RANK(6,Data!H1:H10)", "RANK.EQ(4,Data!H1:H10)", "SUBTOTAL(9,Data!A1:A10)", "SUBTOTAL(1,Data!H1:H10)",
        "SUBTOTAL(2,Data!A1:A10)", "SUBTOTAL(3,Data!A1:A10)", "SUBTOTAL(104,Data!H1:H10)", "STDEV.S(Data!H1:H10)", "STDEV.P(Data!H1:H10)",
        "VAR.S(Data!H1:H10)", "VAR.P(Data!H1:H10)", "MODE.SNGL(Data!H1:H10)", "PERCENTILE(Data!H1:H10,0.25)", "PERCENTILE.INC(Data!H1:H10,0.9)",
        "QUARTILE(Data!H1:H10,1)", "QUARTILE.INC(Data!H1:H10,3)", "AVERAGEA(Data!A1:A10)", "MAXA(Data!A5:A8)", "MINA(Data!A5:A8)", "GEOMEAN(Data!H1:H10)",
        "HARMEAN(Data!H1:H10)", "AVEDEV(Data!H1:H10)", "DEVSQ(Data!H1:H10)", "CORREL(Data!D1:D10,Data!H1:H10)", "SLOPE(Data!H1:H10,Data!D1:D10)",
        "INTERCEPT(Data!H1:H10,Data!D1:D10)", "RSQ(Data!H1:H10,Data!D1:D10)", "FORECAST(21,Data!H1:H10,Data!D1:D10)", "RANK.AVG(4,Data!H1:H10)",
        "COUNTA(Data!G1:G5)",
        // conditional aggregates
        "COUNTIF(Data!A1:A10,\">5\")", "COUNTIF(Data!A1:A10,\"<5\")", "COUNTIF(Data!B1:B10,\"apple\")", "COUNTIF(Data!B1:B10,\"a*\")",
        "COUNTIF(Data!B1:B10,\"<>apple\")", "COUNTIF(Data!A1:A10,\"\")", "COUNTIF(Data!A1:A10,\"<>\")", "COUNTIF(Data!B1:B10,\"?ig\")",
        "COUNTIF(Data!A1:A10,TRUE)", "COUNTIF(Data!B1:B10,\">c\")", "COUNTIF(Data!A1:A10,\">=\"&Data!A2)", "COUNTIF(Data!F1:F10,5)",
        "COUNTIF(Data!C1:C10,\">=\"&DATE(2024,1,1))", "SUMIF(Data!B1:B10,\"apple\",Data!D1:D10)", "SUMIF(Data!A1:A10,\">10\")",
        "SUMIF(Data!B1:B10,\"*e*\",Data!A1:A10)", "AVERAGEIF(Data!A1:A10,\">0\")", "AVERAGEIF(Data!B1:B10,\"a*\",Data!D1:D10)",
        "COUNTIFS(Data!A1:A10,\">0\",Data!D1:D10,\"<10\")", "SUMIFS(Data!D1:D10,Data!B1:B10,\"a*\",Data!D1:D10,\">1\")",
        "AVERAGEIFS(Data!D1:D10,Data!H1:H10,\">4\")", "MAXIFS(Data!D1:D10,Data!H1:H10,\"<5\")", "MINIFS(Data!D1:D10,Data!H1:H10,\">5\")",
        // logical and information
        "IF(1,\"y\",\"n\")", "IF(0,\"y\")", "IF(\"TRUE\",1,2)", "IF(\"abc\",1,2)", "IF(Data!A4,1,2)", "IF(Data!A1>5,,1)", "AND(TRUE,1)", "AND(Data!A1:A10)",
        "AND(Data!B1:B3)", "OR(FALSE,0)", "OR(Data!A4:A5)", "NOT(0)", "NOT(Data!A4)", "XOR(TRUE,TRUE)", "XOR(1,0,1,1)", "IFERROR(1/0,\"e\")",
        "IFERROR(5,\"e\")", "IFNA(NA(),\"na\")", "IFNA(1/0,\"na\")", "IFS(1>2,\"a\",TRUE,\"b\")", "IFS(1>2,\"a\")", "SWITCH(2,1,\"a\",2,\"b\")",
        "SWITCH(3,1,\"a\",\"d\")", "SWITCH(3,1,\"a\")", "ISBLANK(Data!A4)", "ISBLANK(Data!G5)", "ISNUMBER(Data!F1)", "ISNUMBER(Data!A1)", "ISTEXT(Data!B1)",
        "ISTEXT(Data!A4)", "ISLOGICAL(Data!A6)", "ISERROR(Data!G1)", "ISERROR(Data!A1)", "ISERR(Data!G2)", "ISERR(Data!G1)", "ISNA(Data!G2)",
        "ISEVEN(4)", "ISEVEN(5.9)", "ISODD(3)", "ISNONTEXT(Data!A4)", "ISNONTEXT(Data!B1)", "ISFORMULA(Data!G1)", "ISFORMULA(Data!A1)",
        "ERROR.TYPE(Data!G1)", "ERROR.TYPE(Data!G2)", "ERROR.TYPE(1)", "N(TRUE)", "N(\"x\")", "N(Data!C1)", "T(\"x\")", "T(1)", "TRUE()", "FALSE()",
        "TYPE(1)", "TYPE(\"a\")", "TYPE(TRUE)", "TYPE(Data!G1)", "NA()", "ISREF(Data!A1)",
        // text
        "LEN(\"hello\")", "LEN(Data!A1)", "LEN(Data!C1)", "LEN(TRUE)", "LEFT(\"hello\",2)", "LEFT(\"hello\")", "LEFT(\"hi\",5)", "RIGHT(\"hello\",3)",
        "MID(\"hello\",2,3)", "MID(\"hello\",10,2)", "UPPER(\"abc\")", "LOWER(\"ABC\")", "PROPER(\"hello wORLD\")", "PROPER(\"o'neil-smith 2nd\")",
        "TRIM(\"  a  b  \")", "CONCATENATE(\"a\",1,TRUE)", "CONCAT(Data!B1:B3)", "TEXTJOIN(\"-\",TRUE,Data!B1:B5)", "TEXTJOIN(\",\",FALSE,Data!A3:A5)",
        "REPT(\"ab\",3)", "FIND(\"l\",\"hello\")", "FIND(\"L\",\"hello\")", "FIND(\"l\",\"hello\",4)", "SEARCH(\"L\",\"hello\")", "SEARCH(\"x\",\"hello\")",
        "SEARCH(\"h?l\",\"ohello\")", "SUBSTITUTE(\"aaa\",\"a\",\"b\")", "SUBSTITUTE(\"aaa\",\"a\",\"b\",2)", "REPLACE(\"hello\",2,3,\"XY\")",
        "EXACT(\"a\",\"A\")", "VALUE(\"12.5\")", "VALUE(\"abc\")", "VALUE(\"50%\")", "VALUE(\"1,234\")", "CHAR(65)", "CODE(\"A\")", "CODE(\"\")",
        "TEXT(1234.567,\"#,##0.00\")", "TEXT(0.25,\"0%\")", "TEXT(Data!C1,\"yyyy-mm-dd\")", "TEXT(Data!C5,\"hh:mm\")", "TEXT(-5,\"0;(0)\")",
        "TEXT(\"abc\",\"@\")", "CLEAN(\"a\"&CHAR(7)&\"b\")", "FIXED(1234.567,1)", "FIXED(1234.567,1,TRUE)", "DOLLAR(1234.5)", "UNICHAR(9731)",
        "UNICODE(\"A\")", "TEXTBEFORE(\"a-b-c\",\"-\")", "TEXTAFTER(\"a-b-c\",\"-\")", "NUMBERVALUE(\"1,234.5\")", "LEFT(1234,2)", "MID(Data!C1,1,3)",
        // lookup and reference
        "VLOOKUP(5,Data!D1:E10,2,FALSE)", "VLOOKUP(6,Data!D1:E10,2,TRUE)", "VLOOKUP(6,Data!D1:E10,2,FALSE)", "VLOOKUP(0,Data!D1:E10,2)",
        "VLOOKUP(\"BANANA\",Data!B1:D10,3,FALSE)", "VLOOKUP(5,Data!D1:E10,3,FALSE)", "VLOOKUP(\"5\",Data!D1:E10,2,FALSE)", "HLOOKUP(\"apple\",Data!B1:B10,2,FALSE)",
        "MATCH(7,Data!D1:D10,0)", "MATCH(8,Data!D1:D10,1)", "MATCH(8,Data!D1:D10)", "MATCH(\"banana\",Data!B1:B10,0)", "MATCH(\"b*\",Data!B1:B10,0)",
        "MATCH(99,Data!D1:D10,0)", "INDEX(Data!D1:E10,3,2)", "INDEX(Data!D1:D10,4)", "INDEX(Data!D1:D10,11)", "XLOOKUP(9,Data!D1:D10,Data!E1:E10)",
        "XLOOKUP(8,Data!D1:D10,Data!E1:E10,\"none\")", "XLOOKUP(8,Data!D1:D10,Data!E1:E10)", "CHOOSE(2,\"a\",\"b\",\"c\")", "CHOOSE(4,\"a\",\"b\")",
        "OFFSET(Data!A1,2,0)", "SUM(OFFSET(Data!D1,1,0,3,1))", "INDIRECT(\"Data!A2\")", "INDIRECT(\"Data!\"&\"B\"&3)", "ROW(Data!A5)", "COLUMN(Data!C1)",
        "ROWS(Data!A1:C5)", "COLUMNS(Data!A1:C5)", "LOOKUP(6,Data!D1:D10,Data!E1:E10)", "ADDRESS(2,3)", "ADDRESS(2,3,4)", "XMATCH(7,Data!D1:D10)",
        "COUNTA(UNIQUE(Data!B1:B10))", "SUM(FILTER(Data!D1:D10,Data!H1:H10>4))", "INDEX(SORT(Data!H1:H10),1)", "HYPERLINK(\"#Data!A1\",\"go\")",
        // dates and times
        "DATE(2024,2,29)", "DATE(2023,2,29)", "DATE(2024,13,1)", "DATE(2024,1,0)", "DATE(99,1,1)", "YEAR(Data!C1)", "MONTH(Data!C2)", "DAY(Data!C3)",
        "WEEKDAY(Data!C1)", "WEEKDAY(Data!C1,2)", "WEEKDAY(Data!C1,3)", "DAYS(Data!C3,Data!C1)", "EDATE(Data!C1,1)", "EDATE(DATE(2024,1,31),1)",
        "EDATE(DATE(2024,3,31),-1)", "EOMONTH(Data!C1,0)", "EOMONTH(DATE(2024,1,15),1)", "EOMONTH(DATE(2024,3,15),-13)", "DATEDIF(Data!C1,Data!C3,\"d\")",
        "DATEDIF(Data!C1,Data!C3,\"m\")", "DATEDIF(Data!C1,Data!C3,\"y\")", "DATEDIF(Data!C1,Data!C3,\"md\")", "DATEDIF(Data!C3,Data!C1,\"d\")",
        "NETWORKDAYS(Data!C1,Data!C3)", "NETWORKDAYS(Data!C3,Data!C1)", "WORKDAY(Data!C1,10)", "WORKDAY(Data!C1,-3)", "TIME(12,30,0)", "TIME(25,0,0)",
        "HOUR(Data!C5)", "MINUTE(Data!C5)", "SECOND(TIME(1,2,3))", "WEEKNUM(Data!C1)", "WEEKNUM(Data!C3)", "ISOWEEKNUM(Data!C1)",
        "YEARFRAC(Data!C1,Data!C3)", "DATEVALUE(\"2024-03-01\")", "TIMEVALUE(\"12:00\")", "YEAR(TODAY())>2000", "NOW()>TODAY()", "DAY(EOMONTH(DATE(2024,2,1),0))",
        // financial
        "PMT(0.05/12,360,200000)", "PMT(0,10,1000)", "PMT(0.1,5,-1000,0,1)", "IPMT(0.1/12,1,36,8000)", "PPMT(0.1/12,1,36,8000)", "PV(0.08/12,240,-500)",
        "FV(0.06/12,10,-200,-500,1)", "NPV(0.1,-10000,3000,4200,6800)", "NPV(0.1,Data!I2:I4)", "IRR(Data!I1:I4)", "RATE(48,-200,8000)", "NPER(0.01,-100,1000)",
        "SLN(30000,7500,10)", "DDB(2400,300,10,1)", "SYD(30000,7500,10,1)", "EFFECT(0.0525,4)", "NOMINAL(0.053543,4)",
        "CUMIPMT(0.09/12,30*12,125000,13,24,0)", "CUMPRINC(0.09/12,30*12,125000,13,24,0)", "DB(1000000,100000,6,1,7)", "DB(1000000,100000,6,7,7)",
        "DDB(2400,300,10,10)", "XNPV(0.09,Data!I1:I4,Data!C1:C4)", "IPMT(0.1,3,3,8000,0,1)", "FV(0,10,-100)", "PV(0.1,5,0,-1000)", "RATE(10,-100,800)",
        // more of what this engine added
        "NORM.S.DIST(1,TRUE)", "NORM.S.DIST(1,FALSE)", "NORM.DIST(42,40,1.5,TRUE)", "NORM.INV(0.908789,40,1.5)", "NORM.S.INV(0.908789)",
        "NORMSDIST(-1.5)", "STANDARDIZE(42,40,1.5)", "COVARIANCE.S(Data!D1:D10,Data!H1:H10)", "COVAR(Data!D1:D10,Data!H1:H10)",
        "PEARSON(Data!D1:D10,Data!H1:H10)", "PERCENTILE.EXC(Data!H1:H10,0.25)", "QUARTILE.EXC(Data!H1:H10,1)", "PERCENTILE(Data!H1:H10,1.5)",
        "MODE(1,2,3)", "MEDIAN(Data!B1:B3)", "STDEV(5)", "VAR.P(5)", "GEOMEAN(Data!A7:A9)",
        "DAYS360(DATE(2024,1,31),DATE(2024,3,31))", "DAYS360(DATE(2024,2,29),DATE(2024,3,31))", "DAYS360(DATE(2024,1,15),DATE(2024,3,31))",
        "DAYS360(DATE(2024,1,31),DATE(2024,3,31),TRUE)", "YEARFRAC(DATE(2024,2,29),DATE(2025,2,28),0)", "YEARFRAC(DATE(2023,1,1),DATE(2024,7,1),1)",
        "YEARFRAC(DATE(2024,1,1),DATE(2024,7,1),1)", "YEARFRAC(DATE(2023,6,1),DATE(2024,3,1),1)", "YEARFRAC(Data!C1,Data!C3,2)", "YEARFRAC(Data!C1,Data!C3,3)",
        "YEARFRAC(DATE(2024,1,31),DATE(2024,3,31),4)", "WEEKNUM(Data!C2,2)", "WEEKNUM(DATE(2023,1,1),1)", "WEEKNUM(DATE(2023,1,1),2)", "ISOWEEKNUM(DATE(2021,1,1))",
        "NETWORKDAYS(Data!C1,Data!C3,Data!C2)", "NETWORKDAYS.INTL(Data!C1,DATE(2024,1,31),11)", "NETWORKDAYS.INTL(Data!C1,DATE(2024,1,31),\"0000110\")",
        "WORKDAY(Data!C1,5,DATE(2024,1,3))", "WORKDAY.INTL(Data!C1,5,7)", "DATEVALUE(\"March 1, 2024\")", "TIMEVALUE(\"6:35 PM\")", "DATEVALUE(\"abc\")",
        "HOUR(\"18:30\")", "MINUTE(0.75+1/1440)", "TIME(0,0,-1)", "CEILING.MATH(-2.1)", "CEILING.MATH(-2.1,1,1)", "FLOOR.MATH(2.9,2)", "FLOOR.MATH(-2.1,1,1)",
        "MROUND(1.3,0.5)", "MROUND(5,-2)", "ROUNDUP(0.1+0.2,1)", "ROUNDDOWN(-1.99,1)", "INT(-0.5)", "SUM(INDEX(Data!D1:E10,0,1))", "INDEX(Data!D1:E10,2,0)",
        "INDEX(Data!A1:E1,1,3)", "TEXTBEFORE(\"a-b-c\",\"-\",-1)", "TEXTAFTER(\"a-b-c\",\"-\",2)", "TEXTAFTER(\"abc\",\"x\",1,0,0,\"none\")",
        "TEXTBEFORE(\"A-b\",\"B\",1,1)", "ADDRESS(2,3,1,FALSE)", "ADDRESS(2,3,4,FALSE)", "ADDRESS(1,1,1,TRUE,\"Q3 Sales\")", "XMATCH(8,Data!D1:D10,-1)",
        "XMATCH(8,Data!D1:D10,1)", "XMATCH(\"*n*\",Data!E1:E10,2)", "XMATCH(7,Data!H1:H10,0,-1)", "LOOKUP(4,Data!D1:E10)", "LOOKUP(\"c\",Data!B1:B3)",
        "TYPE(Data!A4)", "TYPE(\"\")", "ISREF(1)", "ISREF(Data!A1:B2)", "COUNTA(Data!G1:G6)", "COUNT(Data!G1:G6)", "COUNTBLANK(Data!G1:G6)",
        "COUNTIF(Data!G1:G6,\"#N/A\")", "COUNTIF(Data!G1:G6,\">5\")", "SUMIF(Data!G1:G6,\">5\",Data!D1:D6)", "SUMIF(Data!D1:D6,\">5\",Data!G1:G6)",
        "MATCH(7,Data!G1:G6,0)", "VLOOKUP(7,Data!G1:H6,2,FALSE)", "ROWS(Data!G1:G6)", "ROW(Data!G1)", "ISFORMULA(Data!G1)", "FORMULATEXT(Data!G1)",
        "INDEX(Data!G1:G6,6)", "SUM(Data!G1:G6)", "AVERAGEIFS(Data!D1:D10,Data!H1:H10,\">99\")", "AVERAGEIF(Data!A1:A10,\">999999\")",
        "SEARCH(\"~?\",\"what?\")", "SEARCH(\"*\",\"abc\")", "MATCH(\"B?nana\",Data!B1:B10,0)", "VLOOKUP(\"ki*\",Data!B1:D10,3,FALSE)",
        "COUNTIF(Data!B1:B10,\"*~**\")", "REPT(\"x\",-1)", "CHAR(0)", "UNICHAR(0)", "CLEAN(\"a b\")", "FIXED(-1234.5,0)", "FIXED(1234.5,-2)",
        "COMBIN(10,0)", "PERMUT(3,5)", "FACT(171)", "GCD(0,0)", "LCM(0,5)", "QUOTIENT(5,0)", "EVEN(0)", "ODD(0)", "ODD(1.5)", "EVEN(2.0001)",
        "1E3+1", "2.5E-1*4", "1e2", "10%%", "-50%", "Data!A1%",
    )

    /** Where LibreOffice departs from Excel — what Excel gives, and so the engine. */
    private val excelDiffers = mapOf(
        "\"a\"&TRUE" to "Excel joins TRUE as TRUE; LibreOffice as 1",
        "TRUE>1" to "Excel ranks TRUE above every number",
        "CONCATENATE(\"a\",1,TRUE)" to "Excel joins TRUE as TRUE",
        "LEN(TRUE)" to "Excel's TRUE is four letters long", "TYPE(TRUE)" to "Excel's TYPE of TRUE is 4",
        "SQRT(-1)" to "Excel: #NUM!", "POWER(0,-1)" to "Excel: #DIV/0!", "LN(0)" to "Excel: #NUM!", "LARGE(Data!H1:H10,11)" to "Excel: #NUM!",
        "VLOOKUP(5,Data!D1:E10,3,FALSE)" to "Excel: #REF!", "DATEDIF(Data!C3,Data!C1,\"d\")" to "Excel: #NUM!",
        "SUM(\"2\",1)" to "Excel takes text typed into SUM as a number", "COUNT(Data!A1:A10,TRUE,\"2\")" to "LibreOffice counts the TRUE in the range",
        "COUNTIF(Data!F1:F10,5)" to "Excel's COUNTIF matches the text 5 to 5", "VLOOKUP(\"5\",Data!D1:E10,2,FALSE)" to "Excel does not match the text 5 to 5",
        "NUMBERVALUE(\"1,234.5\")" to "Excel passes group separators by", "WEEKNUM(Data!C3)" to "Excel's week 1 holds 1 January (LibreOffice counts ISO weeks)",
        "CODE(\"\")" to "Excel: #VALUE!", "DOLLAR(1234.5)" to "the currency sign follows the device's region",
        "PERCENTILE(Data!H1:H10,1.5)" to "Excel: #NUM!", "MODE(1,2,3)" to "Excel: #N/A when nothing repeats", "MEDIAN(Data!B1:B3)" to "Excel: #NUM!",
        "GEOMEAN(Data!A7:A9)" to "Excel: #NUM!", "TIME(0,0,-1)" to "Excel: #NUM!", "PERMUT(3,5)" to "Excel: #NUM!", "FACT(171)" to "Excel: #NUM!",
        "QUOTIENT(5,0)" to "Excel: #DIV/0!", "MROUND(5,-2)" to "Excel: #NUM! when the signs differ", "CHAR(0)" to "Excel: #VALUE!", "UNICHAR(0)" to "Excel: #VALUE!",
        "DATEVALUE(\"March 1, 2024\")" to "LibreOffice's language reads no English month names", "TIMEVALUE(\"6:35 PM\")" to "LibreOffice's language reads no AM/PM",
        "ADDRESS(1,1,1,TRUE,\"Q3 Sales\")" to "LibreOffice writes its own sheet.cell form", "INDEX(Data!D1:E10,2,0)" to "a whole row: an array, which spills in Excel 365",
    ).plus(listOf("SUMPRODUCT(Data!A1:A10)", "SUM(Data!A1:A10)", "SUM(Data!A1:A10,TRUE)", "SUM(Data!A6)", "AVERAGE(Data!A1:A10)", "COUNT(Data!A1:A10)",
        "SUBTOTAL(9,Data!A1:A10)", "SUBTOTAL(2,Data!A1:A10)", "COUNTIF(Data!A1:A10,\"<5\")", "SUMIF(Data!B1:B10,\"*e*\",Data!A1:A10)",
        "AVERAGEIF(Data!A1:A10,\">0\")").associateWith { "a TRUE in a range is not a number to Excel (LibreOffice counts it as 1)" })

    @Test
    fun againstLibreOffice() {
        val soffice = System.getProperty("aero.soffice")
        org.junit.Assume.assumeTrue(soffice != null && File(soffice).canExecute())
        val tmp = File(System.getProperty("java.io.tmpdir") ?: ".")
        val dir = File(tmp, "aero-formula-oracle").apply { deleteRecursively(); mkdirs() }
        val profile = File(tmp, "aero-lo-profile").apply { mkdirs() }
        val report = ArrayList<String>()

        // The cases: our values, and LibreOffice's for the same workbook saved without any.
        val sheet = Sheet(name = "Cases")
        cases.forEachIndexed { i, f -> sheet.ensure(i, 0).apply { text = f; formatCode = "@" }; sheet.ensure(i, 1).text = "=$f" }
        val wb = Workbook(mutableListOf(data(), sheet))
        val src = File(dir, "cases.xlsx").apply { outputStream().use { XlsxWriter.write(wb, it) } }
        val out = File(dir, "lo").apply { mkdirs() }
        convert(soffice!!, profile, out, listOf(src))
        val theirs = File(out, "cases.xlsx").takeIf { it.exists() }?.let { cachedValues(it.readBytes()) }
        if (theirs == null) report.add("cases: LibreOffice did not convert")
        else {
            val memo = FormulaEngine.Memo()
            var same = 0
            val known = ArrayList<String>()
            val unknown = ArrayList<String>()
            cases.forEachIndexed { i, f ->
                val ours = FormulaEngine.cached(sheet, i, 1, wb.sheets, wb.names, memo)
                val lo = theirs[1]?.get("B${i + 1}")
                val d = differs(ours, lo)
                val line = "=$f: ours ${show(ours)} | LibreOffice ${show(lo)}${d ?: ""}"
                when {
                    // A function neither engine knows looks alike: it still needs doing.
                    ours == CachedValue.Error("#NAME?") -> unknown.add(line)
                    d == null -> same++
                    f in excelDiffers -> known.add("$line — ${excelDiffers[f]}")
                    else -> report.add(line)
                }
            }
            report.add(0, "cases: ${cases.size} formulas, $same alike, ${report.size} differ, ${known.size} where LibreOffice departs from Excel, ${unknown.size} unknown to the engine")
            if (unknown.isNotEmpty()) { report.add("— unknown to the engine:"); report.addAll(unknown) }
            if (known.isNotEmpty()) { report.add("— LibreOffice departs from Excel (the engine follows Excel):"); report.addAll(known) }
        }

        // Spreadsheets with formulas from a corpus: LibreOffice's values for its own reading of them.
        System.getProperty("aero.corpusDir")?.let { corpus ->
            val files = File(corpus).walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("xlsx", "xlsm", "xltx", "xltm") }
                .filter { f -> runCatching { hasFormulas(f.readBytes()) }.getOrDefault(false) }.toList()
            val cdir = File(dir, "corpus").apply { mkdirs() }
            // Same names in different folders: each converted from a copy of its own name.
            val copies = files.mapIndexed { i, f -> File(cdir, "c${i}.${f.extension}").also { f.copyTo(it, overwrite = true) } to f }
            val cout = File(cdir, "lo").apply { mkdirs() }
            convert(soffice, profile, cout, copies.map { it.first })
            for ((copy, f) in copies) {
                val lo = File(cout, copy.nameWithoutExtension + ".xlsx")
                val tag = f.path.removePrefix(corpus).trimStart('/')
                if (!lo.exists()) { report.add("$tag: not converted"); continue }
                val bytes = lo.readBytes()
                val read = runCatching { XlsxReader.read(bytes) }
                if (read.isFailure) { report.add("$tag: unreadable ${read.exceptionOrNull()}"); continue }
                val book = read.getOrThrow()
                val values = cachedValues(bytes)
                val memo = FormulaEngine.Memo()
                var n = 0; var same = 0
                val diffs = ArrayList<String>()
                book.sheets.forEachIndexed { si, s ->
                    s.cells.entries.sortedBy { it.key }.forEach { (k, c) ->
                        if (!c.text.startsWith("=")) return@forEach
                        val r = (k / 1000L).toInt(); val col = (k % 1000L).toInt()
                        if (col >= 1000) return@forEach
                        val ref = Workbook.colLabel(col) + (r + 1)
                        val theirsV = values[si]?.get(ref) ?: return@forEach
                        n++
                        val ours = FormulaEngine.cached(s, r, col, book.sheets, book.names, memo)
                        val d = differs(ours, theirsV)
                        if (d == null) same++ else diffs.add("${s.name}!$ref ${c.text}: ours ${show(ours)} | LibreOffice ${show(theirsV)}$d")
                    }
                }
                report.add("$tag: $n formulas, $same alike")
                diffs.take(20).forEach { report.add("  $it") }
                if (diffs.size > 20) report.add("  … ${diffs.size - 20} more")
            }
        }
        File(tmp, "aero-formula-oracle.txt").writeText(report.joinToString("\n"))
    }

    private fun convert(soffice: String, profile: File, out: File, inputs: List<File>) {
        if (inputs.isEmpty()) return
        val p = ProcessBuilder(listOf(soffice, "-env:UserInstallation=${profile.toURI()}", "--headless", "--convert-to", "xlsx", "--outdir", out.path) + inputs.map { it.path })
            .redirectErrorStream(true).redirectOutput(File(out, "soffice.log")).start()
        p.waitFor(20, java.util.concurrent.TimeUnit.MINUTES)
    }

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name.endsWith(".xml")) out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    private fun hasFormulas(bytes: ByteArray): Boolean = parts(bytes).any { (n, x) -> n.startsWith("xl/worksheets/") && x.contains("<f") }

    private val CELL = Regex("""<c r="([A-Z]+[0-9]+)"([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)

    /** The formula cells' values in an xlsx, per sheet (in workbook order) and cell reference. */
    private fun cachedValues(bytes: ByteArray): Map<Int, Map<String, CachedValue>> {
        val p = parts(bytes)
        val out = HashMap<Int, Map<String, CachedValue>>()
        var i = 1
        while (true) {
            val x = p["xl/worksheets/sheet$i.xml"] ?: break
            val m = HashMap<String, CachedValue>()
            for (c in CELL.findAll(x)) {
                val inner = c.groupValues[3]
                if (!inner.contains("<f")) continue
                val t = Regex("""\bt="(\w+)"""").find(c.groupValues[2])?.groupValues?.get(1)
                val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)?.let { unescape(it) }
                    ?: if (t == "str") "" else continue
                m[c.groupValues[1]] = when (t) {
                    "b" -> CachedValue.Logical(v == "1")
                    "e" -> CachedValue.Error(v)
                    "str", "s", "inlineStr" -> CachedValue.Text(v)
                    else -> v.toDoubleOrNull()?.let { CachedValue.Number(it) } ?: CachedValue.Text(v)
                }
            }
            out[i - 1] = m
            i++
        }
        return out
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Null when the two values are alike (numbers to nine significant digits), else a note on how they differ. */
    private fun differs(ours: CachedValue?, theirs: CachedValue?): String? = when {
        ours is CachedValue.Number && theirs is CachedValue.Number -> {
            val a = ours.value; val b = theirs.value
            if (a == b || abs(a - b) <= 1e-9 * maxOf(abs(a), abs(b), 1e-300) || abs(a - b) < 1e-12) null else ""
        }
        ours == theirs -> null
        // LibreOffice writes a formula's empty text as no value at all.
        ours is CachedValue.Text && ours.value.isEmpty() && theirs == null -> null
        ours is CachedValue.Error && theirs is CachedValue.Error -> " (both errors)"
        else -> ""
    }

    private fun show(v: CachedValue?): String = when (v) {
        null -> "∅"
        is CachedValue.Number -> FormulaEngine.numberText(v.value)
        is CachedValue.Text -> "\"" + v.value + "\""
        is CachedValue.Logical -> if (v.value) "TRUE" else "FALSE"
        is CachedValue.Error -> v.code
    }
}
