package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * OpenDocument charts: embedded `Object N/content.xml` chart objects referenced from a slide
 * frame (odp) or a sheet's `table:shapes` (ods), listed in the manifest with the chart media
 * type, carrying their data in a local table and — for spreadsheets — cell ranges that bind
 * the chart to its block.
 */
class OdfChartTest {

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        val out = HashMap<String, String>()
        while (true) { val e = zin.nextEntry ?: break; out[e.name] = zin.readBytes().decodeToString() }
        return out
    }

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun sampleChart(type: ChartType, title: String, values: Boolean = false, legend: Boolean = true): SlideChart = SlideChart(
        type = type, title = title,
        categories = mutableListOf("Q1", "Q2", "Q3", "Q4"),
        series = mutableListOf(ChartSeries("North", mutableListOf(12f, 15.5f, 9f, 20f)), ChartSeries("South", mutableListOf(8f, 11f, 14f, 6.25f))),
        showLegend = legend, showGrid = true, showValues = values, colorSet = 1,
        xPct = 0.1f, yPct = 0.25f, wPct = 0.6f, hPct = 0.5f,
    )

    @Test
    fun odpChartsRoundTripAsEmbeddedObjects() {
        val deck = Presentation(mutableListOf(
            Slide(title = SlideBox("Charts"), body = SlideBox("")).apply {
                charts.add(sampleChart(ChartType.COLUMN, "Sales", values = true))
                charts.add(sampleChart(ChartType.PIE, "Share", legend = false).apply { xPct = 0.55f; yPct = 0.3f; wPct = 0.4f; hPct = 0.6f })
            },
            Slide(title = SlideBox("Bars"), body = SlideBox("")).apply { charts.add(sampleChart(ChartType.BAR, "Horizontal")) },
        ))
        val bos = ByteArrayOutputStream()
        OdpWriter.write(deck, bos)
        val bytes = bos.toByteArray()
        System.getProperty("aero.sampleDir")?.let { java.io.File(it).apply { mkdirs() }.resolve("charts.odp").writeBytes(bytes) }
        val parts = zipEntries(bytes)
        assertTrue("object part", parts.containsKey("Object 1/content.xml"))
        assertTrue("three objects", parts.containsKey("Object 3/content.xml"))
        val manifest = parts["META-INF/manifest.xml"]!!
        assertTrue("object dir entry", manifest.contains("""manifest:full-path="Object 1/" manifest:media-type="application/vnd.oasis.opendocument.chart""""))
        assertTrue("object content entry", manifest.contains("""manifest:full-path="Object 1/content.xml""""))
        val obj = parts["Object 1/content.xml"]!!
        assertTrue("bar class", obj.contains("""chart:class="chart:bar""""))
        assertTrue("title", obj.contains("<chart:title><text:p>Sales</text:p></chart:title>"))
        assertTrue("legend", obj.contains("<chart:legend"))
        assertTrue("values shown", obj.contains("""chart:data-label-number="value""""))
        assertTrue("local table header", obj.contains("<text:p>North</text:p>"))
        assertTrue("local table values", obj.contains("""office:value="15.5""""))
        assertTrue("content frame references the object", parts["content.xml"]!!.contains("""<draw:object xlink:href="./Object 1""""))
        assertTrue("horizontal bars", parts["Object 3/content.xml"]!!.contains("""chart:vertical="true""""))

        val back = OdpReader.read(bytes)
        val s1 = back.slides[0]
        assertEquals(2, s1.charts.size)
        val col = s1.charts[0]
        assertEquals(ChartType.COLUMN, col.type)
        assertEquals("Sales", col.title)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4"), col.categories)
        assertEquals(listOf("North", "South"), col.series.map { it.name })
        assertEquals(listOf(12f, 15.5f, 9f, 20f), col.series[0].values)
        assertEquals(6.25f, col.series[1].values[3], 0.0001f)
        assertTrue(col.showLegend); assertTrue(col.showGrid); assertTrue(col.showValues)
        assertEquals(0.1f, col.xPct, 0.01f); assertEquals(0.25f, col.yPct, 0.01f); assertEquals(0.6f, col.wPct, 0.01f); assertEquals(0.5f, col.hPct, 0.01f)
        assertNull("slide charts are static", col.sourceRef)
        val pie = s1.charts[1]
        assertEquals(ChartType.PIE, pie.type)
        assertFalse(pie.showLegend); assertFalse(pie.showValues)
        assertEquals(0.55f, pie.xPct, 0.01f)
        assertEquals(ChartType.BAR, back.slides[1].charts[0].type)
    }

    @Test
    fun odsChartsBindToTheirBlockAndKeepTheirData() {
        val wb = Workbook.blank()
        val sheet = wb.sheets[0]
        listOf("Region", "North", "South").forEachIndexed { c, t -> sheet.ensure(0, c).text = t }
        listOf("Q1" to (12 to 8), "Q2" to (15 to 11), "Q3" to (9 to 14), "Q4" to (20 to 6)).forEachIndexed { i, (q, v) ->
            sheet.ensure(i + 1, 0).text = q; sheet.ensure(i + 1, 1).text = v.first.toString(); sheet.ensure(i + 1, 2).text = v.second.toString()
        }
        val chart = SlideChart(type = ChartType.LINE, title = "Trend",
            categories = mutableListOf("Q1", "Q2", "Q3", "Q4"),
            series = mutableListOf(ChartSeries("North", mutableListOf(12f, 15f, 9f, 20f)), ChartSeries("South", mutableListOf(8f, 11f, 14f, 6f))),
            sourceRef = "A1:C5", sourceHeader = true, sourceCats = true)
        sheet.charts.add(chart)
        val bos = ByteArrayOutputStream()
        OdsWriter.write(wb, bos)
        val bytes = bos.toByteArray()
        System.getProperty("aero.sampleDir")?.let { java.io.File(it).apply { mkdirs() }.resolve("charts.ods").writeBytes(bytes) }
        val parts = zipEntries(bytes)
        val content = parts["content.xml"]!!
        assertTrue("shapes before the columns", content.indexOf("<table:shapes>") < content.indexOf("<table:table-column"))
        val obj = parts["Object 1/content.xml"]!!
        assertTrue("line class", obj.contains("""chart:class="chart:line""""))
        assertTrue("linked to the parent document", obj.contains("""xlink:href=".."""))
        assertTrue("plot area range", obj.contains("""table:cell-range-address="Sheet1.A1:Sheet1.C5""""))
        assertTrue("series range", obj.contains("""chart:values-cell-range-address="Sheet1.B2:Sheet1.B5""""))
        assertTrue("series label", obj.contains("""chart:label-cell-range-address="Sheet1.B1""""))
        assertTrue("categories", obj.contains("""<chart:categories table:cell-range-address="Sheet1.A2:Sheet1.A5"/>"""))

        val back = OdsReader.read(bytes)
        val bc = back.sheets[0].charts.single()
        assertEquals(ChartType.LINE, bc.type)
        assertEquals("Trend", bc.title)
        assertEquals("A1:C5", bc.sourceRef); assertTrue(bc.sourceHeader); assertTrue(bc.sourceCats)
        assertEquals(listOf("North", "South"), bc.series.map { it.name })
        assertEquals(listOf(12f, 15f, 9f, 20f), bc.series[0].values)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4"), bc.categories)
    }

    @Test
    fun calcMadeChartObjectIsRead() {
        // The shape LibreOffice Calc writes: chart:chart with xlink:href="..", data-source-has-labels
        // both, one series per column, styles in the object's own automatic styles, a local table.
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink""""
        val obj = """<office:document-content $ns><office:automatic-styles>
            <style:style style:name="ch2" style:family="chart"><style:chart-properties chart:vertical="true"/></style:style>
            <style:style style:name="ch7" style:family="chart"><style:chart-properties chart:data-label-number="value"/></style:style>
            </office:automatic-styles><office:body><office:chart>
            <chart:chart svg:width="16cm" svg:height="9cm" xlink:href=".." xlink:type="simple" chart:class="chart:bar">
             <chart:title><text:p>Units</text:p></chart:title>
             <chart:plot-area chart:style-name="ch2" table:cell-range-address="Data.${'$'}A${'$'}1:.${'$'}B${'$'}4" chart:data-source-has-labels="both">
              <chart:axis chart:dimension="x" chart:name="primary-x"><chart:categories table:cell-range-address="Data.${'$'}A${'$'}2:.${'$'}A${'$'}4"/></chart:axis>
              <chart:axis chart:dimension="y" chart:name="primary-y"/>
              <chart:series chart:style-name="ch7" chart:values-cell-range-address="Data.${'$'}B${'$'}2:.${'$'}B${'$'}4" chart:label-cell-range-address="Data.${'$'}B${'$'}1" chart:class="chart:bar"><chart:data-point chart:repeated="3"/></chart:series>
             </chart:plot-area>
             <table:table table:name="local-table"><table:table-header-columns><table:table-column/></table:table-header-columns><table:table-columns><table:table-column/></table:table-columns>
              <table:table-header-rows><table:table-row><table:table-cell><text:p/></table:table-cell><table:table-cell office:value-type="string"><text:p>Units</text:p></table:table-cell></table:table-row></table:table-header-rows>
              <table:table-rows>
               <table:table-row><table:table-cell office:value-type="string"><text:p>A</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="3"><text:p>3</text:p></table:table-cell></table:table-row>
               <table:table-row><table:table-cell office:value-type="string"><text:p>B</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="5"><text:p>5</text:p></table:table-cell></table:table-row>
               <table:table-row><table:table-cell office:value-type="string"><text:p>C</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="2"><text:p>2</text:p></table:table-cell></table:table-row>
              </table:table-rows></table:table>
            </chart:chart></office:chart></office:body></office:document-content>"""
        val content = """<office:document-content $ns><office:body><office:spreadsheet>
            <table:table table:name="Data"><table:shapes><draw:frame svg:width="16cm" svg:height="9cm" svg:x="1cm" svg:y="1cm"><draw:object xlink:href="./Object 1" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame></table:shapes>
            <table:table-column/><table:table-row><table:table-cell office:value-type="string"><text:p>Item</text:p></table:table-cell><table:table-cell office:value-type="string"><text:p>Units</text:p></table:table-cell></table:table-row>
            <table:table-row><table:table-cell office:value-type="string"><text:p>A</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="3"><text:p>3</text:p></table:table-cell></table:table-row>
            <table:table-row><table:table-cell office:value-type="string"><text:p>B</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="5"><text:p>5</text:p></table:table-cell></table:table-row>
            <table:table-row><table:table-cell office:value-type="string"><text:p>C</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="2"><text:p>2</text:p></table:table-cell></table:table-row>
            </table:table></office:spreadsheet></office:body></office:document-content>"""
        val wb = OdsReader.read(zipOf(mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to content, "Object 1/content.xml" to obj)))
        val c = wb.sheets[0].charts.single()
        assertEquals("vertical bars → horizontal bar chart", ChartType.BAR, c.type)
        assertEquals("Units", c.title)
        assertEquals(listOf("A", "B", "C"), c.categories)
        assertEquals("Units", c.series.single().name)
        assertEquals(listOf(3f, 5f, 2f), c.series[0].values)
        assertTrue("data labels", c.showValues)
        assertFalse("no legend element", c.showLegend)
        assertEquals("bound to the block (absolute refs stripped)", "A1:B4", c.sourceRef)
        assertTrue(c.sourceHeader); assertTrue(c.sourceCats)
    }

    @Test
    fun sheetNamesWithSpacesAreQuotedInRangesAndReadBack() {
        val wb = Workbook.blank()
        val sheet = wb.sheets[0]
        sheet.name = "Sales 2026"
        listOf("Q", "N").forEachIndexed { c, t -> sheet.ensure(0, c).text = t }
        for (i in 1..3) { sheet.ensure(i, 0).text = "Q$i"; sheet.ensure(i, 1).text = (i * 10).toString() }
        sheet.charts.add(SlideChart(type = ChartType.COLUMN, title = "T", categories = mutableListOf("Q1", "Q2", "Q3"),
            series = mutableListOf(ChartSeries("N", mutableListOf(10f, 20f, 30f))), sourceRef = "A1:B4"))
        val bos = ByteArrayOutputStream()
        OdsWriter.write(wb, bos)
        val parts = zipEntries(bos.toByteArray())
        val obj = parts["Object 1/content.xml"]!!
        assertTrue("quoted sheet name", obj.contains("""chart:values-cell-range-address="'Sales 2026'.B2:'Sales 2026'.B4""""))
        assertFalse("no layer attribute inside a spreadsheet", parts["content.xml"]!!.contains("draw:layer"))
        val back = OdsReader.read(bos.toByteArray())
        val c = back.sheets[0].charts.single()
        assertEquals("A1:B4", c.sourceRef)
        assertEquals(listOf(10f, 20f, 30f), c.series[0].values)
    }
}
