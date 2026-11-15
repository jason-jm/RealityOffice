package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.model.Sheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the spreadsheet formula engine. */
class FormulaEngineTest {

    /** Sheet with A1..A5 = 1,2,3,4,5; formula goes in B1, returns its display string. */
    private fun evalWithColumnA(formula: String, values: List<Double> = listOf(1.0, 2.0, 3.0, 4.0, 5.0)): String {
        val s = Sheet()
        values.forEachIndexed { r, v -> s.ensure(r, 0).text = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString() }
        s.ensure(0, 1).text = "=$formula"
        return FormulaEngine.display(s, 0, 1)
    }

    private fun eval(formula: String): String {
        val s = Sheet()
        s.ensure(0, 1).text = "=$formula"
        return FormulaEngine.display(s, 0, 1)
    }

    @Test fun aggregates() {
        assertEquals("15", evalWithColumnA("SUM(A1:A5)"))
        assertEquals("3", evalWithColumnA("MEDIAN(A1:A5)"))
        assertEquals("120", evalWithColumnA("PRODUCT(A1:A5)"))
        assertEquals("55", evalWithColumnA("SUMSQ(A1:A5)"))
        assertEquals("3", evalWithColumnA("AVERAGE(A1:A5)"))
    }

    @Test fun statistics() {
        // sample stdev of 1..5 = sqrt(2.5) ≈ 1.581139
        assertTrue(evalWithColumnA("STDEV(A1:A5)").startsWith("1.58"))
        assertEquals("2.5", evalWithColumnA("VAR(A1:A5)"))
        assertEquals("2", evalWithColumnA("VARP(A1:A5)"))
        assertEquals("2", evalWithColumnA("MODE(A1:A5)", listOf(2.0, 2.0, 3.0, 4.0, 5.0)))
    }

    @Test fun math() {
        assertEquals("3", eval("INT(3.7)"))
        assertEquals("1", eval("MOD(10,3)"))
        assertEquals("3", eval("ROUNDUP(2.1,0)"))
        assertEquals("2", eval("ROUNDDOWN(2.9,0)"))
        assertEquals("-2", eval("TRUNC(-2.9)"))
        assertEquals("8", eval("POWER(2,3)"))
        assertEquals("1", eval("SIGN(42)"))
        assertEquals("10", eval("CEILING(7,5)"))
        assertEquals("5", eval("FLOOR(7,5)"))
    }

    @Test fun logsAndTrig() {
        assertTrue(eval("PI()").startsWith("3.14159"))
        assertEquals("180", eval("DEGREES(PI())"))
        assertEquals("0", eval("SIN(0)"))
        assertEquals("1", eval("COS(0)"))
        assertEquals("1", eval("LOG10(10)"))
        assertEquals("3", eval("LOG(8,2)"))
        assertTrue(eval("EXP(1)").startsWith("2.71828"))
    }

    @Test fun logical() {
        assertEquals("FALSE", eval("XOR(1,0,1)"))
        assertEquals("TRUE", eval("XOR(1,0,0)"))
        assertEquals("TRUE", eval("TRUE()"))
        assertEquals("FALSE", eval("FALSE()"))
        assertEquals("7", eval("IF(1>0,7,9)"))
    }

    @Test fun dates() {
        // 2024-01-01 Excel serial = 45292
        assertEquals("45292", eval("DATE(2024,1,1)"))
        assertEquals("2024", eval("YEAR(DATE(2024,3,15))"))
        assertEquals("3", eval("MONTH(DATE(2024,3,15))"))
        assertEquals("15", eval("DAY(DATE(2024,3,15))"))
        assertEquals("30", eval("DAYS(DATE(2024,1,31),DATE(2024,1,1))"))
        // 2024-01-15 + 1 month = 2024-02-15
        assertEquals("2", eval("MONTH(EDATE(DATE(2024,1,15),1))"))
        // EOMONTH of Jan 2024 (+0) = 2024-01-31
        assertEquals("31", eval("DAY(EOMONTH(DATE(2024,1,15),0))"))
        // 2024-01-01 is a Monday → WEEKDAY type1 = 2
        assertEquals("2", eval("WEEKDAY(DATE(2024,1,1))"))
        // DATE month overflow rolls over: DATE(2024,13,1) = 2025-01-01
        assertEquals("2025", eval("YEAR(DATE(2024,13,1))"))
    }

    @Test fun textFunctions() {
        assertEquals("5", eval("LEN(\"hello\")"))
        assertEquals("hel", eval("LEFT(\"hello\",3)"))
        assertEquals("llo", eval("RIGHT(\"hello\",3)"))
        assertEquals("ell", eval("MID(\"hello\",2,3)"))
        assertEquals("HELLO", eval("UPPER(\"hello\")"))
        assertEquals("hello", eval("LOWER(\"HELLO\")"))
        assertEquals("Hello World", eval("PROPER(\"hello world\")"))
        assertEquals("a b", eval("TRIM(\"  a  b  \")"))
        assertEquals("foobar", eval("CONCAT(\"foo\",\"bar\")"))
        assertEquals("a-b-c", eval("TEXTJOIN(\"-\",1,\"a\",\"b\",\"c\")"))
        assertEquals("xyxy", eval("REPT(\"xy\",2)"))
        assertEquals("3", eval("FIND(\"l\",\"hello\")"))
        assertEquals("heXXo", eval("SUBSTITUTE(\"hello\",\"ll\",\"XX\")"))
        assertEquals("TRUE", eval("EXACT(\"abc\",\"abc\")"))
        assertEquals("42", eval("VALUE(\"42\")"))
    }

    @Test fun concatOperatorAndMixed() {
        assertEquals("ab", eval("\"a\"&\"b\""))
        assertEquals("Item3", eval("\"Item\"&(1+2)"))
        // number-producing formula still numeric
        assertEquals("6", eval("2*3"))
        // IF can return text
        assertEquals("yes", eval("IF(1>0,\"yes\",\"no\")"))
        assertEquals("no", eval("IF(1<0,\"yes\",\"no\")"))
        // cell text round-trips through a ref + concat
        val s = Sheet()
        s.ensure(0, 0).text = "Hello"
        s.ensure(0, 1).text = "=A1&\" World\""
        assertEquals("Hello World", FormulaEngine.display(s, 0, 1))
    }

    @Test fun lookups() {
        // Table A1:B3 = (1,"one"),(2,"two"),(3,"three"); column C unused.
        val s = Sheet()
        listOf(1 to "one", 2 to "two", 3 to "three").forEachIndexed { r, (n, w) ->
            s.ensure(r, 0).text = n.toString()
            s.ensure(r, 1).text = w
        }
        fun f(formula: String): String { s.ensure(5, 5).text = "=$formula"; return FormulaEngine.display(s, 5, 5) }

        assertEquals("two", f("VLOOKUP(2,A1:B3,2,0)"))      // exact
        assertEquals("three", f("VLOOKUP(3,A1:B3,2)"))      // approx (sorted)
        assertEquals("two", f("VLOOKUP(2.5,A1:B3,2)"))      // approx → largest ≤ 2.5
        assertEquals("2", f("MATCH(2,A1:A3,0)"))            // exact position
        assertEquals("2", f("MATCH(2.9,A1:A3,1)"))          // largest value ≤ 2.9 is 2, at position 2
        assertEquals("two", f("INDEX(B1:B3,2)"))            // 1-D index
        assertEquals("three", f("INDEX(A1:B3,3,2)"))        // 2-D index
        // VLOOKUP result usable in arithmetic context coerces to 0 (text), but numeric lookup works:
        assertEquals("20", f("VLOOKUP(2,A1:A3,1)*10"))
    }

    @Test fun hlookupWorks() {
        val s = Sheet()
        // Row1 = 1,2,3 ; Row2 = x,y,z
        listOf(1, 2, 3).forEachIndexed { c, n -> s.ensure(0, c).text = n.toString() }
        listOf("x", "y", "z").forEachIndexed { c, w -> s.ensure(1, c).text = w }
        s.ensure(5, 5).text = "=HLOOKUP(2,A1:C2,2,0)"
        assertEquals("y", FormulaEngine.display(s, 5, 5))
    }

    @Test fun moreFunctions() {
        val s = Sheet()
        // A = 1,2,3 ; B = 4,5,6 ; C2 blank
        listOf(1, 2, 3).forEachIndexed { r, v -> s.ensure(r, 0).text = v.toString() }
        listOf(4, 5, 6).forEachIndexed { r, v -> s.ensure(r, 1).text = v.toString() }
        s.ensure(0, 2).text = "x"; s.ensure(2, 2).text = "z" // C1,C3 set; C2 blank
        fun f(formula: String): String { s.ensure(5, 5).text = "=$formula"; return FormulaEngine.display(s, 5, 5) }

        assertEquals("b", f("CHOOSE(2,\"a\",\"b\",\"c\")"))
        assertEquals("32", f("SUMPRODUCT(A1:A3,B1:B3)"))   // 4+10+18
        assertEquals("3", f("LARGE(A1:A3,1)"))
        assertEquals("1", f("SMALL(A1:A3,1)"))
        assertEquals("2", f("SMALL(A1:A3,2)"))
        assertEquals("1", f("COUNTBLANK(C1:C3)"))           // only C2 blank
        assertEquals("2", f("INDIRECT(\"A2\")"))            // value of A2
        assertEquals("3", f("INDIRECT(\"A\"&3)"))           // dynamic A3
    }

    @Test fun multiCriteriaAndRank() {
        // A = region, B = amount, C = qty
        val s = Sheet()
        val rows = listOf(Triple("North", 100, 5), Triple("South", 200, 3), Triple("North", 300, 8), Triple("South", 50, 2))
        rows.forEachIndexed { r, (reg, amt, qty) ->
            s.ensure(r, 0).text = reg; s.ensure(r, 1).text = amt.toString(); s.ensure(r, 2).text = qty.toString()
        }
        fun f(formula: String): String { s.ensure(8, 8).text = "=$formula"; return FormulaEngine.display(s, 8, 8) }

        assertEquals("2", f("COUNTIFS(A1:A4,\"North\")"))
        assertEquals("1", f("COUNTIFS(A1:A4,\"North\",B1:B4,\">150\")"))       // North & >150 → row3
        assertEquals("400", f("SUMIFS(B1:B4,A1:A4,\"North\")"))                  // 100+300
        assertEquals("300", f("SUMIFS(B1:B4,A1:A4,\"North\",C1:C4,\">5\")"))     // North & qty>5 → 300
        assertEquals("125", f("AVERAGEIFS(B1:B4,A1:A4,\"South\")"))              // (200+50)/2
        assertEquals("2", f("RANK(200,B1:B4)"))                                  // 300>200 → rank 2 desc
        assertEquals("3", f("RANK(200,B1:B4,1)"))                                // ascending: 50,100,200 → 3
    }

    @Test fun crossSheetReferences() {
        val data = Sheet(name = "Data")
        data.ensure(0, 0).text = "100"   // A1
        data.ensure(1, 0).text = "200"   // A2
        data.ensure(2, 0).text = "300"   // A3
        val main = Sheet(name = "Main")
        val all = listOf(main, data)
        fun f(formula: String): String { main.ensure(5, 5).text = "=$formula"; return FormulaEngine.display(main, 5, 5, all) }

        assertEquals("100", f("Data!A1"))
        assertEquals("600", f("SUM(Data!A1:A3)"))
        assertEquals("300", f("Data!A1+Data!A2"))
        assertEquals("200", f("'Data'!A2"))               // quoted sheet name
        assertEquals("500", f("Data!A2+Data!A3+Data!A1-Data!A1"))
        // cross-sheet + local mix
        main.ensure(0, 0).text = "7"
        assertEquals("107", f("A1+Data!A1"))
    }

    @Test fun absoluteReferences() {
        val s = Sheet()
        listOf(10, 20, 30).forEachIndexed { r, v -> s.ensure(r, 0).text = v.toString() }
        fun f(formula: String): String { s.ensure(5, 5).text = "=$formula"; return FormulaEngine.display(s, 5, 5) }
        assertEquals("10", f("\$A\$1"))
        assertEquals("30", f("A\$3"))
        assertEquals("60", f("SUM(\$A\$1:\$A\$3)"))
        assertEquals("20", f("\$A2"))
    }

    @Test fun lazyFunctions() {
        // IFERROR catches a thrown error (FIND of a missing substring)
        assertEquals("-1", eval("IFERROR(FIND(\"z\",\"abc\"),-1)"))
        assertEquals("3", eval("IFERROR(FIND(\"c\",\"abc\"),-1)")) // no error → real result
        // short-circuit IF: untaken branch (which would throw) is never evaluated
        assertEquals("7", eval("IF(0,FIND(\"z\",\"a\"),7)"))
        // IFS: first true wins
        assertEquals("b", eval("IFS(1>2,\"a\",3>2,\"b\")"))
        // SWITCH with match and with default
        assertEquals("two", eval("SWITCH(2,1,\"one\",2,\"two\",\"def\")"))
        assertEquals("def", eval("SWITCH(9,1,\"one\",\"def\")"))
        // IFNA catches #N/A alone: FIND's #VALUE! goes through, as in Excel.
        assertEquals("no", eval("IFNA(NA(),\"no\")"))
        assertEquals("#VALUE!", eval("IFNA(FIND(\"z\",\"abc\"),\"no\")"))
    }

    @Test fun textCriteria() {
        val s = Sheet()
        listOf("apple", "banana", "apricot", "cherry").forEachIndexed { r, w -> s.ensure(r, 0).text = w }
        fun f(formula: String): String { s.ensure(5, 5).text = "=$formula"; return FormulaEngine.display(s, 5, 5) }
        assertEquals("2", f("COUNTIF(A1:A4,\"a*\")"))        // apple, apricot
        assertEquals("1", f("COUNTIF(A1:A4,\"banana\")"))
        assertEquals("3", f("COUNTIF(A1:A4,\"<>cherry\")"))
        assertEquals("1", f("COUNTIF(A1:A4,\"c?erry\")"))    // wildcard ?
    }

    /** A criteria is what its argument works out to: a cell, a comparison joined to a cell, a function. */
    @Test fun criteriaFromCellsAndFormulas() {
        val s = Sheet()
        listOf("North" to "10", "South" to "40", "North" to "25", "West" to "5").forEachIndexed { r, (a, b) -> s.ensure(r, 0).text = a; s.ensure(r, 1).text = b }
        s.ensure(0, 3).text = "North"; s.ensure(1, 3).text = "20"
        fun f(formula: String): String { s.ensure(9, 5).text = "=$formula"; return FormulaEngine.display(s, 9, 5) }
        assertEquals("2", f("COUNTIF(A1:A4,D1)"))
        assertEquals("35", f("SUMIF(A1:A4,D1,B1:B4)"))
        assertEquals("2", f("COUNTIF(B1:B4,\">\"&D2)"))
        assertEquals("65", f("SUMIF(B1:B4,\">=\"&D2)"))
        assertEquals("1", f("COUNTIFS(A1:A4,D1,B1:B4,\">\"&D2)"))
        assertEquals("25", f("SUMIFS(B1:B4,A1:A4,D1,B1:B4,\">\"&D2)"))
        assertEquals("17.5", f("AVERAGEIF(A1:A4,D1,B1:B4)"))
        assertEquals("1", f("COUNTIF(A1:A4,LEFT(\"Westward\",4))"))
    }

    @Test fun conditionals() {
        assertEquals("3", evalWithColumnA("COUNTIF(A1:A5,\">2\")"))
        assertEquals("12", evalWithColumnA("SUMIF(A1:A5,\">2\")"))
        assertEquals("4", evalWithColumnA("AVERAGEIF(A1:A5,\">=3\")"))
        assertEquals("2", evalWithColumnA("COUNTIF(A1:A5,\"<=2\")"))
        assertEquals("1", evalWithColumnA("COUNTIF(A1:A5,3)"))
    }
}
