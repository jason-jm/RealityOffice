package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.ModelDiff
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.LegendPos
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Chart elements: title, axis titles, legend position, gridlines and data labels — edited, undone and kept in files. */
class ChartElementsTest {

    private fun chart(type: ChartType = ChartType.COLUMN) = SlideChart(
        type = type, title = "Revenue",
        categories = mutableListOf("Q1", "Q2", "Q3"),
        series = mutableListOf(ChartSeries("North", mutableListOf(3f, 5f, 4f)), ChartSeries("South", mutableListOf(2f, 6f, 1f))),
        showLegend = true, showGrid = true, showValues = true,
        legendPos = LegendPos.LEFT, catAxisTitle = "Quarter", valAxisTitle = "€ thousands",
    )

    private fun assertElements(expected: SlideChart, back: SlideChart) {
        assertEquals(expected.type, back.type)
        assertEquals(expected.title, back.title)
        assertEquals(expected.catAxisTitle, back.catAxisTitle)
        assertEquals(expected.valAxisTitle, back.valAxisTitle)
        assertEquals(expected.showLegend, back.showLegend)
        if (expected.showLegend) assertEquals(expected.legendPos, back.legendPos)
        assertEquals(expected.showGrid, back.showGrid)
        assertEquals(expected.showValues, back.showValues)
    }

    private fun deckWith(vararg charts: SlideChart) = Presentation(mutableListOf(
        Slide(title = SlideBox("Charts"), body = SlideBox("")).apply { this.charts.addAll(charts) }))

    @Test
    fun powerPointKeepsEveryElement() {
        val column = chart()
        val bar = chart(ChartType.BAR).apply { legendPos = LegendPos.TOP; showValues = false }
        val scatter = chart(ChartType.SCATTER).apply { legendPos = LegendPos.RIGHT; showGrid = false }
        val pie = chart(ChartType.PIE).apply { legendPos = LegendPos.BOTTOM; catAxisTitle = ""; valAxisTitle = "" }
        val bos = ByteArrayOutputStream()
        PptxWriter.write(deckWith(column, bar, scatter, pie), bos)
        val back = PptxReader.read(bos.toByteArray()).slides[0].charts
        assertElements(column, back[0])
        assertElements(bar, back[1])
        assertElements(scatter, back[2])
        assertElements(pie, back[3])
    }

    @Test
    fun theChartXmlFollowsTheSchemaOrder() {
        val xml = PptxWriter.chartXml(chart(ChartType.BAR))
        // A bar chart's category axis stands on the left, its value axis along the bottom with the gridlines.
        assertTrue(xml.contains("""<c:catAx><c:axId val="111111111"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="l"/><c:title>"""))
        assertTrue(xml.contains("""<c:axPos val="b"/><c:majorGridlines/><c:title>"""))
        // Data labels come after the series and before gapWidth / axId.
        assertTrue(xml.contains("""</c:ser><c:dLbls><c:showLegendKey val="0"/><c:showVal val="1"/>"""))
        assertTrue(xml.contains("""</c:dLbls><c:gapWidth val="150"/><c:axId"""))
        assertTrue(xml.contains("""<c:legend><c:legendPos val="l"/>"""))
    }

    @Test
    fun aChartWithoutGridlinesOrLegendPositionReadsAsOfficeShowsIt() {
        val xml = """<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><c:chart>
            <c:plotArea><c:barChart><c:barDir val="col"/><c:ser><c:idx val="0"/><c:val><c:numRef><c:numCache><c:pt idx="0"><c:v>1</c:v></c:pt></c:numCache></c:numRef></c:val></c:ser></c:barChart>
            <c:catAx><c:axId val="1"/></c:catAx><c:valAx><c:axId val="2"/></c:valAx></c:plotArea><c:legend/></c:chart></c:chartSpace>"""
        val c = PptxReader.parseChartPart(xml.toByteArray())!!
        assertTrue(c.showLegend)
        assertEquals("OOXML's default legend position is the right", LegendPos.RIGHT, c.legendPos)
        assertFalse(c.showGrid); assertFalse(c.showValues)
        assertEquals("", c.catAxisTitle); assertEquals("", c.valAxisTitle)
    }

    @Test
    fun excelKeepsEveryElement() {
        val wb = Workbook.blank()
        val sheet = wb.sheets[0]
        listOf("", "North", "South").forEachIndexed { col, t -> sheet.ensure(0, col).text = t }
        listOf("Q1" to (3 to 2), "Q2" to (5 to 6), "Q3" to (4 to 1)).forEachIndexed { i, (q, v) ->
            sheet.ensure(i + 1, 0).text = q; sheet.ensure(i + 1, 1).text = v.first.toString(); sheet.ensure(i + 1, 2).text = v.second.toString()
        }
        val c = chart(ChartType.LINE).apply { sourceRef = "A1:C4" }
        sheet.charts.add(c)
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        assertElements(c, XlsxReader.read(bos.toByteArray()).sheets[0].charts[0])
    }

    @Test
    fun openDocumentKeepsEveryElement() {
        val column = chart()
        val right = chart(ChartType.AREA).apply { legendPos = LegendPos.RIGHT; showValues = false }
        val bos = ByteArrayOutputStream()
        OdpWriter.write(deckWith(column, right), bos)
        val obj = ZipInputStream(ByteArrayInputStream(bos.toByteArray())).let { z ->
            generateSequence { z.nextEntry }.first { it.name == "Object 1/content.xml" }; z.readBytes().decodeToString()
        }
        assertTrue(obj.contains("""<chart:legend chart:legend-position="start"/>"""))
        assertTrue(obj.contains("""chart:dimension="x" chart:name="primary-x" chart:style-name="axis"><chart:title><text:p>Quarter</text:p></chart:title>"""))
        val back = OdpReader.read(bos.toByteArray()).slides[0].charts
        assertElements(column, back[0])
        assertElements(right, back[1])
    }

    @Test
    fun slidesEditsTheElementsAsOneUndoStep() {
        val deck = deckWith(chart())
        val e = SlideEditor().apply { load(deck) }
        e.selectChart(e.presentation.slides[0].charts[0])
        e.setChartElements("Profit", "Month", "€", showLegend = true, legendPos = LegendPos.RIGHT)
        val c = e.presentation.slides[0].charts[0]
        assertEquals("Profit", c.title); assertEquals("Month", c.catAxisTitle); assertEquals(LegendPos.RIGHT, c.legendPos)
        e.undo()
        val u = e.presentation.slides[0].charts[0]
        assertEquals("Revenue", u.title); assertEquals("Quarter", u.catAxisTitle); assertEquals(LegendPos.LEFT, u.legendPos)
    }

    @Test
    fun sheetsEditsTheChartAsOneUndoStep() {
        val wb = Workbook.blank()
        wb.sheets[0].charts.add(chart())
        val e = SheetEditor().apply { load(wb) }
        e.updateChart(0) { it.type = ChartType.RADAR; it.valAxisTitle = "Score" }
        assertEquals(ChartType.RADAR, e.sheet.charts[0].type)
        e.undo()
        assertEquals(ChartType.COLUMN, e.sheet.charts[0].type)
        assertEquals("€ thousands", e.sheet.charts[0].valAxisTitle)
    }

    @Test
    fun undoSeesAxisTitleAndLegendChanges() {
        val a = chart(); val b = a.copyOf()
        assertTrue(ModelDiff.sameChart(a, b))
        b.valAxisTitle = "other"
        assertFalse(ModelDiff.sameChart(a, b))
        val d = a.copyOf().apply { legendPos = LegendPos.TOP }
        assertFalse(ModelDiff.sameChart(a, d))
    }
}
