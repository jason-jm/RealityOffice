package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.CellStyle
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.TableLooks
import com.therealityteam.aeropages.model.TableStyle
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Cell Styles gallery (each style sets only its own aspects) and Format as Table over the current region. */
class CellStylesTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    @Test
    fun aStyleSetsItsOwnAspectsOnly() {
        val e = editor()
        e.setCell(0, 0, "12.5")
        e.select(0, 0)
        e.toggleBold(); e.setHighlight(0xFFFFFF00); e.setNumberFormat(NumberFormat.PERCENT); e.setAlign(TextAlign.CENTER)
        e.applyCellStyle(CellStyle.GOOD)
        val a1 = e.sheet.cell(0, 0)!!
        assertEquals(0xFF006100, a1.format.color)
        assertEquals(0xFFC6EFCE, a1.format.highlight)
        assertFalse("the style's font is regular", a1.format.bold)
        assertEquals("number format kept", NumberFormat.PERCENT, a1.numberFormat)
        assertEquals("alignment kept", TextAlign.CENTER, a1.align)
        e.applyCellStyle(CellStyle.HEADING_1)
        assertTrue(a1.format.bold)
        assertEquals(15f, a1.format.fontSizePt)
        assertEquals(BorderLine(LineStyle.THICK, 0xFF4472C4), a1.borderBottom)
        assertNull(a1.borderTop)
        assertEquals("a heading keeps the fill", 0xFFC6EFCE, a1.format.highlight)
        e.applyCellStyle(CellStyle.TOTAL)
        assertEquals(BorderLine(LineStyle.THIN, 0xFF4472C4), a1.borderTop)
        assertEquals(BorderLine(LineStyle.DOUBLE, 0xFF4472C4), a1.borderBottom)
        e.applyCellStyle(CellStyle.COMMA)
        assertEquals(NumberFormat.THOUSANDS, a1.numberFormat)
        assertEquals(2, a1.decimals)
        assertEquals("12.50", e.display(0, 0))
        e.applyCellStyle(CellStyle.NORMAL)
        assertFalse(a1.format.bold)
        assertNull(a1.format.highlight)
        assertNull(a1.format.color)
        assertNull(a1.format.fontSizePt)
        assertNull(a1.borderBottom)
        assertEquals(NumberFormat.GENERAL, a1.numberFormat)
        assertNull(a1.align)
        assertEquals("12.5", a1.text)
        e.undo()
        assertEquals("one undo step per style", NumberFormat.THOUSANDS, e.sheet.cell(0, 0)!!.numberFormat)
    }

    @Test
    fun xlsxKeepsFontSizesFacesAndStrike() {
        val e = editor()
        e.setCell(0, 0, "Heading"); e.select(0, 0); e.applyCellStyle(CellStyle.HEADING_1)
        e.sheet.ensure(1, 0).apply { text = "small"; format.fontSizePt = 10.5f; format.fontFamily = "Arial"; format.strikethrough = true }
        e.setCell(2, 0, "plain")
        val bos = java.io.ByteArrayOutputStream()
        com.therealityteam.aeropages.format.xlsx.XlsxWriter.write(e.workbook, bos)
        val back = com.therealityteam.aeropages.format.xlsx.XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals("sizes used to be dropped on reading", 15f, back.cell(0, 0)!!.format.fontSizePt)
        assertTrue(back.cell(0, 0)!!.format.bold)
        assertEquals(BorderLine(LineStyle.THICK, 0xFF4472C4), back.cell(0, 0)!!.borderBottom)
        assertEquals(10.5f, back.cell(1, 0)!!.format.fontSizePt)
        assertEquals("Arial", back.cell(1, 0)!!.format.fontFamily)
        assertTrue(back.cell(1, 0)!!.format.strikethrough)
        assertNull("the workbook's own font is the default", back.cell(2, 0)?.format?.fontSizePt)
        assertNull(back.cell(2, 0)?.format?.fontFamily)
    }

    @Test
    fun themedStylesAndTheSelection() {
        val e = editor()
        e.select(0, 0); e.extendSelection(1, 1)
        e.applyCellStyle(CellStyle.ACCENT_2)
        for (r in 0..1) for (c in 0..1) {
            assertEquals(0xFFED7D31, e.sheet.cell(r, c)!!.format.highlight)
            assertEquals(0xFFFFFFFF, e.sheet.cell(r, c)!!.format.color)
        }
        assertEquals(1, CellStyle.ACCENT_2.accentIndex)
        assertEquals(5, CellStyle.ACCENT_6_20.accentIndex)
        assertEquals(-1, CellStyle.GOOD.accentIndex)
        e.toggleProtection()
        e.applyCellStyle(CellStyle.BAD)
        assertEquals("a protected sheet refuses", 0xFFED7D31, e.sheet.cell(0, 0)!!.format.highlight)
    }

    private fun data(e: SheetEditor) {
        // A1:C4 with a header, then a lone value further off.
        listOf(listOf("Region", "Q1", "Q2"), listOf("North", "10", "12"), listOf("South", "8", "9"), listOf("West", "5", "7"))
            .forEachIndexed { r, row -> row.forEachIndexed { c, t -> e.setCell(r, c, t) } }
        e.setCell(8, 6, "far")
    }

    @Test
    fun theCurrentRegionIsTheBlockAroundTheCell() {
        val e = editor()
        data(e)
        assertArrayEquals(intArrayOf(0, 0, 3, 2), e.currentRegion(2, 1))
        e.setCell(4, 3, "diag")   // touches C4 diagonally: joins the region
        assertArrayEquals(intArrayOf(0, 0, 4, 3), e.currentRegion(0, 0))
        assertArrayEquals(intArrayOf(8, 6, 8, 6), e.currentRegion(8, 6))
        assertNull(e.currentRegion(20, 20))
    }

    @Test
    fun formatAsTableFromOneCell() {
        val e = editor()
        data(e)
        e.select(2, 1)
        assertTrue(e.formatAsTable(TableStyle(0, medium = true)))
        assertEquals("the table becomes the selection", "A1:C4", e.selectionRef())
        // A real table in Excel's Medium 2, drawn by its style rather than painted on the cells.
        val t = e.sheet.tables.single()
        assertEquals("Table1", t.name)
        assertEquals("TableStyleMedium2", t.style)
        assertEquals(listOf("Region", "Q1", "Q2"), t.columnNames(e.sheet))
        assertNull("no direct fill", e.sheet.cell(0, 1)!!.format.highlight)
        val header = TableLooks.styled(e.sheet, 0, 1, e.sheet.cell(0, 1))!!
        assertTrue(header.format.bold)
        assertEquals(0xFF4472C4, header.format.highlight)
        assertEquals(0xFFFFFFFF, header.format.color)
        assertEquals("first data row banded", 0xFFD9E1F2, TableLooks.styled(e.sheet, 1, 0, e.sheet.cell(1, 0))!!.format.highlight)
        assertNull(TableLooks.styled(e.sheet, 2, 0, e.sheet.cell(2, 0))!!.format.highlight)
        assertEquals(0xFFD9E1F2, TableLooks.styled(e.sheet, 3, 2, e.sheet.cell(3, 2))!!.format.highlight)
        // Another look from inside the table restyles it; the light one rules the header and closes the table.
        assertTrue(e.formatAsTable(TableStyle(5, medium = false), headers = true, banded = false))
        assertEquals(1, e.sheet.tables.size)
        assertEquals("TableStyleLight7", t.style)
        val light = TableLooks.styled(e.sheet, 0, 1, e.sheet.cell(0, 1))!!
        assertNull(light.format.highlight)
        assertEquals(BorderLine(LineStyle.THIN, 0xFF70AD47), light.borderBottom)
        assertEquals(BorderLine(LineStyle.THIN, 0xFF70AD47), light.borderTop)
        assertEquals(BorderLine(LineStyle.THIN, 0xFF70AD47), TableLooks.styled(e.sheet, 3, 1, e.sheet.cell(3, 1))!!.borderBottom)
        assertNull("no stripes", TableLooks.styled(e.sheet, 1, 1, e.sheet.cell(1, 1))!!.format.highlight)
        e.undo()
        assertEquals("TableStyleMedium2", e.sheet.tables.single().style)
        e.undo()
        assertTrue(e.sheet.tables.isEmpty())
        e.select(20, 20)
        assertFalse("nothing to format", e.formatAsTable(TableStyle(0, medium = true)))
    }
}
