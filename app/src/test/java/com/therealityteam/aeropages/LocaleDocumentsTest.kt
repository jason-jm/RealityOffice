package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaLocale
import com.therealityteam.aeropages.editor.TableText
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableFormulas
import com.therealityteam.aeropages.model.TableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Pages' tables in the reader's language: numbers read and written with its symbols, dates in its order, formulas typed its way. */
class LocaleDocumentsTest {
    private val de = NumberSymbols.of(Locale.GERMANY)
    private val fr = NumberSymbols.of(Locale.FRANCE)
    private val en = NumberSymbols.CANONICAL

    private fun table(vararg rows: List<String>) =
        Table(rows.map { r -> TableRow(r.map { TableCell(mutableListOf(Paragraph.of(it))) }.toMutableList()) }.toMutableList())

    @Test
    fun aGermanTableSumsItsOwnNumbers() {
        val t = table(listOf("Posten", "Betrag"), listOf("Papier", "1.234,50 €"), listOf("Stifte", "2,5"), listOf("Summe", ""))
        assertEquals(1237.0, TableFormulas.evaluate("SUM(ABOVE)", t, 3, 1, de)!!, 1e-9)
        assertEquals("1.237,00", TableFormulas.format(1237.0, "#,##0.00", de))
        assertEquals("the picture's text stays", "1.237,00 €", TableFormulas.format(1237.0, "#,##0.00 €", de))
        assertEquals("no picture: as few decimals as needed", "2,5", TableFormulas.format(2.5, null, de))
        assertEquals("the same cells in English read differently, as Word would", 1.234, TableFormulas.numberIn("1.234,50", en)!!, 1e-9)
        assertEquals("French groups with spaces, typed or no-break", 1234.5, TableFormulas.numberIn("1 234,5", fr)!!, 1e-9)
        assertEquals(1234.5, TableFormulas.numberIn("1 234,5 €", fr)!!, 1e-9)
        assertEquals("1 234,50", TableFormulas.format(1234.5, "#,##0.00", fr))
        assertEquals("a run of digits that is no grouping is not one number", 1.0, TableFormulas.numberIn("1,23", en)!!, 1e-9)
        assertNull(TableFormulas.numberIn("—", de))
    }

    @Test
    fun aFormulaIsTypedTheLanguagesWayAndKeptInTheStandardForm() {
        assertEquals("=SUM(B2,C2)*1.5", FormulaLocale.toCanonical("=SUM(B2;C2)*1,5", de))
        assertEquals("=SUM(B2;C2)*1,5", FormulaLocale.toLocal("=SUM(B2,C2)*1.5", de))
        assertEquals("=SUM(ABOVE)", FormulaLocale.toLocal("=SUM(ABOVE)", de))
    }

    @Test
    fun sortingReadsNumbersAndDatesTheLanguagesWay() {
        assertEquals(1234.5, TableText.number("1.234,5", de)!!, 1e-9)
        assertEquals(1234.5, TableText.number("¥1,234.5", en)!!, 1e-9)
        assertEquals("day first", 20261016L, TableText.date("16.10.2026", dayFirst = true))
        assertEquals(20261016L, TableText.date("16/10/2026", dayFirst = true))
        assertEquals("month first", 20261016L, TableText.date("10/16/2026", dayFirst = false))
        assertEquals("year first in every language", 20261016L, TableText.date("2026-10-16", dayFirst = true))
    }
}
