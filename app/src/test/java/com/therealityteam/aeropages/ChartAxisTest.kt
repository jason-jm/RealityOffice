package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.AxisFormat
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartStyle
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.LegendPos
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Format Axis on a chart's value axis: fixed bounds and major unit, number formats — drawn, edited and kept in files. */
class ChartAxisTest {

    private fun ticks(a: ChartStyle.Axis) = a.ticks.map { Math.round(it * 1000) / 1000.0 }

    @Test
    fun fixedBoundsAndUnitsShapeTheAxis() {
        val both = ChartStyle.axis(1.0, 30.0, 0.0, 100.0, null)
        assertEquals(listOf(0.0, 25.0, 50.0, 75.0, 100.0), ticks(both))
        val unit = ChartStyle.axis(1.0, 30.0, 0.0, 100.0, 20.0)
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), ticks(unit))
        // Only the minimum fixed: zero is left out and the automatic top lands on the unit from it.
        val fromFifty = ChartStyle.axis(55.0, 95.0, 50.0, null, null)
        assertEquals(50.0, fromFifty.min, 0.0)
        assertEquals(listOf(50.0, 70.0, 90.0, 110.0), ticks(fromFifty))
        assertEquals("bars grow from the axis' bottom when zero is off it", 50.0, fromFifty.baseline, 0.0)
        assertEquals(-20.0, ChartStyle.Axis(-100.0, -20.0, 20.0).baseline, 0.0)
        assertEquals(0.0, ChartStyle.Axis(-10.0, 10.0, 5.0).baseline, 0.0)
        assertEquals("values beyond a fixed bound stop at it", 100.0, both.clamp(130.0), 0.0)
        // Contradictory bounds and absurd units fall back to automatic.
        val contradicting = ChartStyle.axis(1.0, 30.0, 10.0, 5.0, null)
        assertEquals(10.0, contradicting.min, 0.0)
        assertTrue(contradicting.max >= 30.0)
        assertEquals(ticks(ChartStyle.axis(1.0, 30.0)), ticks(ChartStyle.axis(1.0, 30.0, null, null, 0.0001)))
        // Nothing fixed: the old automatic axis.
        assertEquals(ticks(ChartStyle.axis(-3.0, 17.0)), ticks(ChartStyle.axis(-3.0, 17.0, null, null, null)))
    }

    @Test
    fun numberFormatsAsExcelWritesThem() {
        assertEquals("26%", ChartStyle.format(0.256, "0%"))
        assertEquals("25.6%", ChartStyle.format(0.256, "0.0%"))
        assertEquals("1,234,567.89", ChartStyle.format(1234567.891, "#,##0.00"))
        assertEquals("1.5M", ChartStyle.format(1_500_000.0, "0.0,,\"M\""))
        assertEquals("¥1,234", ChartStyle.format(1234.0, "\"¥\"#,##0"))
        assertEquals("-$1,234", ChartStyle.format(-1234.0, "\$#,##0"))
        assertEquals("€2,500", ChartStyle.format(2500.0, "[\$€-407]#,##0"))
        assertEquals("12 元", ChartStyle.format(12.0, "0\" 元\""))
        assertEquals("rounds half away from zero, as Excel", "13", ChartStyle.format(12.5, "0"))
        assertEquals("optional decimals", "0.5", ChartStyle.format(0.5, "0.0#"))
        assertEquals("0.57", ChartStyle.format(0.567, "0.0#"))
        assertEquals("positive section only", "3", ChartStyle.format(3.0, "0;(0)"))
        assertEquals("General", "12.35", ChartStyle.format(12.345, "General"))
        assertEquals("unreadable → automatic", ChartStyle.label(7.0), ChartStyle.format(7.0, "@"))
    }

    @Test
    fun formatCodesSurviveTheirParts() {
        for (code in listOf("0%", "0.0%", "\"¥\"#,##0", "#,##0.0#", "0.0,,\"M\"", "0.00", "#,##0"))
            assertEquals(code, ChartStyle.parseFormat(code)!!.code())
        assertNull(ChartStyle.parseFormat("General"))
    }

    @Test
    fun theDialogReadsBoundsUnitsAndPercentages() {
        assertEquals(AxisFormat(null, 100.0, null, "0%") to null, AxisFormat.parse("", "100", "", "0%"))
        assertEquals("50% is 0.5 on a percent axis", 0.5, AxisFormat.parse("50%", "", "", "0%").first!!.min!!, 1e-12)
        assertEquals("thousands commas", 1000.0, AxisFormat.parse("", "1,000", "", "General").first!!.max!!, 0.0)
        assertEquals(AxisFormat.Problem.NOT_A_NUMBER, AxisFormat.parse("abc", "", "", "General").second)
        assertEquals(AxisFormat.Problem.BOUNDS, AxisFormat.parse("10", "5", "", "General").second)
        assertEquals(AxisFormat.Problem.UNIT, AxisFormat.parse("", "", "0", "General").second)
        assertEquals("blank format is General", SlideChart.GENERAL, AxisFormat.parse("", "", "", "").first!!.format)
    }

    private fun chart(type: ChartType = ChartType.COLUMN) = SlideChart(
        type = type, title = "Share",
        categories = mutableListOf("Q1", "Q2", "Q3"),
        series = mutableListOf(ChartSeries("North", mutableListOf(0.3f, 0.55f, 0.4f))),
        legendPos = LegendPos.BOTTOM,
        valMin = 0.0, valMax = 1.0, valMajor = 0.25, valFormat = "0%",
    )

    private fun assertAxis(expected: SlideChart, back: SlideChart) {
        assertEquals(expected.valMin, back.valMin)
        assertEquals(expected.valMax, back.valMax)
        assertEquals(expected.valMajor, back.valMajor)
        assertEquals(expected.valFormat, back.valFormat)
    }

    private fun deckWith(vararg charts: SlideChart) = Presentation(mutableListOf(
        Slide(title = SlideBox("Charts"), body = SlideBox("")).apply { this.charts.addAll(charts) }))

    @Test
    fun officeFilesKeepTheAxis() {
        val column = chart()
        val bar = chart(ChartType.BAR).apply { valMin = null; valMajor = null; valFormat = "\"¥\"#,##0" }
        val scatter = chart(ChartType.SCATTER).apply { valFormat = "0.00" }
        val bos = ByteArrayOutputStream()
        PptxWriter.write(deckWith(column, bar, scatter), bos)
        val back = PptxReader.read(bos.toByteArray()).slides[0].charts
        assertAxis(column, back[0]); assertAxis(bar, back[1]); assertAxis(scatter, back[2])
        val xml = PptxWriter.chartXml(column)
        // CT_Scaling: orientation, max, min; numFmt after the title; majorUnit after crossAx.
        assertTrue(xml.contains("""<c:scaling><c:orientation val="minMax"/><c:max val="1"/><c:min val="0"/></c:scaling>"""))
        assertTrue(xml.contains("""<c:numFmt formatCode="0%" sourceLinked="0"/><c:crossAx val="111111111"/><c:majorUnit val="0.25"/></c:valAx>"""))

        val wb = Workbook.blank()
        val c = chart(ChartType.LINE).apply { valFormat = "#,##0.0" }
        wb.sheets[0].charts.add(c)
        val xbos = ByteArrayOutputStream()
        XlsxWriter.write(wb, xbos)
        assertAxis(c, XlsxReader.read(xbos.toByteArray()).sheets[0].charts[0])
    }

    @Test
    fun openDocumentKeepsTheAxis() {
        val percent = chart()
        val money = chart(ChartType.AREA).apply { valMin = -1.0; valFormat = "\"¥\"#,##0.00" }
        val plain = chart(ChartType.LINE).apply { valMin = null; valMax = null; valMajor = null; valFormat = SlideChart.GENERAL }
        val bos = ByteArrayOutputStream()
        OdpWriter.write(deckWith(percent, money, plain), bos)
        val obj = ZipInputStream(ByteArrayInputStream(bos.toByteArray())).let { z ->
            generateSequence { z.nextEntry }.first { it.name == "Object 1/content.xml" }; z.readBytes().decodeToString()
        }
        assertTrue(obj.contains("""<number:percentage-style style:name="Nval">"""))
        assertTrue(obj.contains("""chart:minimum="0" chart:maximum="1" chart:interval-major="0.25" chart:link-data-style-to-source="false""""))
        val back = OdpReader.read(bos.toByteArray()).slides[0].charts
        assertAxis(percent, back[0]); assertAxis(money, back[1]); assertAxis(plain, back[2])
    }

    @Test
    fun slidesSetsTheAxisAsOneUndoStep() {
        val deck = deckWith(chart().apply { valMin = null; valMax = null; valMajor = null; valFormat = SlideChart.GENERAL })
        val e = SlideEditor().apply { load(deck) }
        e.selectChart(e.presentation.slides[0].charts[0])
        e.setChartElements("Share", "", "", showLegend = true, legendPos = LegendPos.BOTTOM, axis = AxisFormat(0.0, 1.0, 0.2, "0%"))
        val c = e.presentation.slides[0].charts[0]
        assertEquals(AxisFormat(0.0, 1.0, 0.2, "0%"), c.axisFormat)
        e.undo()
        assertEquals(AxisFormat(null, null, null, SlideChart.GENERAL), e.presentation.slides[0].charts[0].axisFormat)
    }
}
