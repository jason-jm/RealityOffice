package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Sheets charts are bound to a worksheet block: they follow the cells, shift with rows, re-bind, and round-trip as real formulas. */
class SheetChartSourceTest {

    private fun editor(sheetName: String = "Sheet1"): SheetEditor {
        val e = SheetEditor()
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        s.name = sheetName
        listOf("City", "Beijing", "Shanghai", "Shenzhen").forEachIndexed { r, t -> s.ensure(r, 0).text = t }
        listOf("Sales", "10", "20", "30").forEachIndexed { r, t -> s.ensure(r, 1).text = t }
        listOf("Cost", "4", "8", "12").forEachIndexed { r, t -> s.ensure(r, 3).text = t }
        e.load(wb)
        return e
    }

    private fun values(c: SlideChart) = c.series[0].values.toList()

    @Test
    fun rangeHelpers() {
        assertEquals(listOf(1, 1, 4, 1), A1.parseRange("Sheet1!\$B\$2:\$B\$5")!!.toList())
        assertEquals(listOf(0, 0, 0, 0), A1.parseRange("'Sales Q3'!A1")!!.toList())
        assertEquals(listOf(0, 0, 3, 2), A1.parseRange("C4:A1")!!.toList())
        assertNull(A1.parseRange("nope"))
        assertEquals("A1:C5", A1.rangeRef(0, 0, 4, 2)); assertEquals("B2", A1.rangeRef(1, 1, 1, 1))
        assertEquals("Sheet1!\$B\$2:\$B\$5", A1.absoluteRef("Sheet1", 1, 1, 4, 1))
        assertEquals("'Sales Q3'!\$A\$1", A1.absoluteRef("Sales Q3", 0, 0, 0, 0))
    }

    @Test
    fun chartFollowsItsCellsAndShiftsWithRows() {
        val e = editor()
        e.select(0, 0); e.extendSelection(3, 1)
        assertTrue(e.insertChartFromSelection(ChartType.COLUMN))
        val chart = e.sheet.charts[0]
        assertEquals("A1:B4", chart.sourceRef); assertTrue(chart.sourceHeader); assertTrue(chart.sourceCats)
        assertEquals(listOf("Beijing", "Shanghai", "Shenzhen"), chart.categories)
        assertEquals("Sales", chart.series[0].name); assertEquals(listOf(10f, 20f, 30f), values(chart))
        // Editing inside the block updates the chart; a formula counts through its value.
        e.select(2, 1); e.setCell(2, 1, "=5*5")
        assertEquals(listOf(10f, 25f, 30f), values(chart))
        e.select(3, 0); e.setCell(3, 0, "Chengdu")
        assertEquals(listOf("Beijing", "Shanghai", "Chengdu"), chart.categories)
        // A row inserted above the data grows the block; the new row's data is picked up.
        e.select(1, 0); e.insertRowAt(1)
        assertEquals("A1:B5", e.sheet.charts[0].sourceRef)
        e.select(1, 0); e.setCell(1, 0, "Wuhan"); e.select(1, 1); e.setCell(1, 1, "5")
        assertEquals(listOf("Wuhan", "Beijing", "Shanghai", "Chengdu"), e.sheet.charts[0].categories)
        assertEquals(listOf(5f, 10f, 25f, 30f), values(e.sheet.charts[0]))
        e.undo(); e.undo(); e.undo()
        assertEquals("A1:B4", e.sheet.charts[0].sourceRef)
        assertEquals(listOf(10f, 25f, 30f), values(e.sheet.charts[0]))
        // Deleting every row of the block unbinds the chart; whatever it last showed stays as static data.
        e.select(0, 0); e.deleteRowAt(0); e.select(0, 0); e.deleteRowAt(0); e.select(0, 0); e.deleteRowAt(0); e.select(0, 0); e.deleteRowAt(0)
        assertNull(e.sheet.charts[0].sourceRef)
        assertTrue(e.sheet.charts[0].series.isNotEmpty())
    }

    @Test
    fun reselectingTheDataSourceIsUndoable() {
        val e = editor()
        e.select(0, 0); e.extendSelection(3, 1)
        assertTrue(e.insertChartFromSelection(ChartType.LINE))
        e.select(0, 3); e.extendSelection(3, 0)
        assertTrue(e.setChartSource(0))
        val chart = e.sheet.charts[0]
        assertEquals("D1:D4", chart.sourceRef); assertFalse("a single column has no category column", chart.sourceCats); assertTrue(chart.sourceHeader)
        assertEquals("Cost", chart.series[0].name); assertEquals(listOf(4f, 8f, 12f), values(chart))
        assertEquals(3, chart.categories.size)
        e.undo()
        assertEquals("A1:B4", e.sheet.charts[0].sourceRef); assertEquals("Sales", e.sheet.charts[0].series[0].name)
        e.redo()
        assertEquals("D1:D4", e.sheet.charts[0].sourceRef)
        e.select(0, 0)
        assertFalse("a single cell is not a data block", e.setChartSource(0))
    }

    @Test
    fun xlsxWritesRangeFormulasAndRebindsOnRead() {
        val e = editor("Sales Q3")
        e.select(0, 0); e.extendSelection(3, 1)
        assertTrue(e.insertChartFromSelection(ChartType.COLUMN))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(e.workbook, bos)
        val bytes = bos.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["xl/charts/chart1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("<c:f>'Sales Q3'!\$A\$2:\$A\$4</c:f>"))
        assertTrue(xml.contains("<c:f>'Sales Q3'!\$B\$2:\$B\$4</c:f>"))
        assertTrue(xml.contains("<c:f>'Sales Q3'!\$B\$1</c:f>"))
        val back = XlsxReader.read(bytes)
        val chart = back.sheets[0].charts.single()
        assertEquals("A1:B4", chart.sourceRef); assertTrue(chart.sourceHeader); assertTrue(chart.sourceCats)
        assertEquals(listOf(10f, 20f, 30f), values(chart)); assertEquals("Sales", chart.series[0].name)
        // Once loaded, the chart is live again.
        val e2 = SheetEditor(); e2.load(back)
        e2.select(1, 1); e2.setCell(1, 1, "100")
        assertEquals(listOf(100f, 20f, 30f), values(e2.sheet.charts[0]))
        // A slide chart keeps its static data and never binds to a range.
        val slide = Slide(); slide.charts.add(SlideChart.sample())
        val pbos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(slide)), pbos)
        val pback = PptxReader.read(pbos.toByteArray()).slides[0].charts.single()
        assertNull(pback.sourceRef); assertEquals(3, pback.series.size)
    }
}
