package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.ColumnFilter
import com.therealityteam.aeropages.model.FilterCondition
import com.therealityteam.aeropages.model.FilterCondition.Op
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Excel's AutoFilter: buttons on a header row, criteria on several columns at once, sorting under the header, xlsx. */
class AutoFilterTest {

    /** A1:C6 — Region, Product, Sales — with one blank product. */
    private fun editor() = SheetEditor().apply {
        load(Workbook.blank())
        listOf(
            listOf("Region", "Product", "Sales"),
            listOf("North", "Apple", "10"),
            listOf("South", "Pear", "40"),
            listOf("North", "Pear", "25"),
            listOf("West", "", "5"),
            listOf("South", "Apple", "30"),
        ).forEachIndexed { r, row -> row.forEachIndexed { c, t -> if (t.isNotEmpty()) setCell(r, c, t) } }
        select(2, 1)
    }

    @Test
    fun filterButtonsGoOnTheDataAroundTheCell() {
        val e = editor()
        assertTrue(e.toggleAutoFilter())
        val af = e.autoFilter!!
        assertEquals(listOf(0, 0, 5, 2), listOf(af.r1, af.c1, af.r2, af.c2))
        e.setColumnFilter(0, ColumnFilter(values = setOf("North")))
        assertEquals(setOf(2, 4, 5), e.hiddenRows)
        e.toggleAutoFilter()
        assertNull(e.autoFilter)
        assertTrue("off again: every row shows", e.hiddenRows.isEmpty())
        e.undo()
        assertNotNull(e.autoFilter)
        assertEquals(setOf(2, 4, 5), e.hiddenRows)
    }

    @Test
    fun columnsFilterTogetherAndListsCascade() {
        val e = editor()
        e.toggleAutoFilter()
        e.setColumnFilter(1, ColumnFilter(values = setOf("Pear")))                 // rows 2, 3
        e.setColumnFilter(2, ColumnFilter(condition = FilterCondition(Op.GREATER, "30")))   // rows 2
        assertEquals("only rows both columns pass", setOf(1, 3, 4, 5), e.hiddenRows)
        // Region's list shows only what the other columns let through.
        assertEquals(listOf("South"), e.filterValues(0).first)
        e.setColumnFilter(2, null)
        assertEquals(setOf(1, 4, 5), e.hiddenRows)
        val (products, blanks) = e.filterValues(1)
        assertEquals(listOf("Apple", "Pear"), products)
        assertTrue("the blank product is listed as (Blanks)", blanks)
        assertEquals("numbers by value, from the rows Product lets through", listOf("25", "40"), e.filterValues(2).first)
        e.setColumnFilter(1, ColumnFilter(values = setOf("Apple"), blanks = true))
        assertEquals(setOf(2, 3), e.hiddenRows)
        e.clearFilter()
        assertTrue(e.hiddenRows.isEmpty())
        assertNotNull("Clear keeps the buttons", e.autoFilter)
    }

    @Test
    fun reapplyAfterTheDataChanged() {
        val e = editor()
        e.select(2, 0)
        e.filterContains("th")   // a keyword filter puts the buttons on and filters Region
        assertNotNull(e.autoFilter)
        assertEquals(setOf(4), e.hiddenRows)   // North, South, North, South kept; West hidden
        e.setCell(4, 0, "Southwest")
        assertEquals("filters do not follow edits by themselves", setOf(4), e.hiddenRows)
        e.reapplyFilter()
        assertTrue(e.hiddenRows.isEmpty())
    }

    @Test
    fun sortingUnderTheHeader() {
        val e = editor()
        e.setCell(1, 3, "=C2*2")   // a formula beside the data
        e.select(0, 0); e.extendSelection(5, 3)
        e.toggleAutoFilter()
        e.setColumnFilter(0, ColumnFilter(values = setOf("North", "South")))
        e.sortFilterColumn(2, ascending = false)
        assertEquals("the header stays", "Sales", e.sheet.cell(0, 2)!!.text)
        assertEquals(listOf("40", "30", "25", "10", "5"), (1..5).map { e.sheet.cell(it, 2)!!.text })
        assertEquals("the formula moved with its row", "=C5*2", e.sheet.cell(4, 3)!!.text)
        assertEquals("20", e.display(4, 3))
        assertEquals("the criteria apply to the rows' new places", setOf(5), e.hiddenRows)
        e.sortFilterColumn(1, ascending = true)
        assertEquals("blanks last", "", e.sheet.cell(5, 1)?.text ?: "")
    }

    @Test
    fun theRangeMovesWithRowsAndColumns() {
        val e = editor()
        e.toggleAutoFilter()
        e.setColumnFilter(2, ColumnFilter(condition = FilterCondition(Op.LESS, "20")))
        e.select(0, 0); e.insertRowAt(0)
        val af = e.autoFilter!!
        assertEquals(1, af.r1); assertEquals(6, af.r2)
        e.deleteColAt(1)
        assertEquals(1, e.autoFilter!!.c2)
        assertNotNull("Sales' criteria moved to column B", e.columnFilter(1))
        assertNull(e.columnFilter(2))
    }

    @Test
    fun aProtectedSheetRefuses() {
        val e = editor()
        e.toggleProtection()
        assertFalse(e.toggleAutoFilter())
        assertNull(e.autoFilter)
    }

    private fun entry(bytes: ByteArray, name: String): String =
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.first { it.name == name }; z.readBytes().decodeToString() }

    @Test
    fun xlsxKeepsTheCriteria() {
        val e = editor()
        e.toggleAutoFilter()
        e.setColumnFilter(1, ColumnFilter(values = setOf("Pear"), blanks = true))
        e.setColumnFilter(0, ColumnFilter(condition = FilterCondition(Op.CONTAINS, "th")))
        e.setColumnFilter(2, ColumnFilter(condition = FilterCondition(Op.GREATER_OR_EQUAL, "5")))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(e.workbook, bos)
        val xml = entry(bos.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("""<autoFilter ref="A1:C6"><filterColumn colId="0"><customFilters><customFilter val="*th*"/></customFilters></filterColumn>"""))
        assertTrue(xml.contains("""<filterColumn colId="1"><filters blank="1"><filter val="Pear"/></filters></filterColumn>"""))
        assertTrue(xml.contains("""<customFilter operator="greaterThanOrEqual" val="5"/>"""))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        val af = back.autoFilter!!
        assertEquals(listOf(0, 0, 5, 2), listOf(af.r1, af.c1, af.r2, af.c2))
        assertEquals(e.autoFilter!!.columns, af.columns)
        assertEquals("the hidden rows come back as the filter's", e.hiddenRows, back.hiddenRows)
    }
}
