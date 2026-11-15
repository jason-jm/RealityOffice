package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.ChartBlock
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.SlideChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Charts in documents: Word's (a drawing naming a chart part) and ODF's chart objects, both ways; Insert → Chart. */
class DocumentChartsTest {

    private fun salesChart() = SlideChart(type = ChartType.LINE, title = "Sales",
        categories = mutableListOf("Q1", "Q2", "Q3"),
        series = mutableListOf(ChartSeries("North", mutableListOf(12f, 15f, 19f)), ChartSeries("South", mutableListOf(9f, 11f, 8f))))

    @Test
    fun wordsChartIsReadFromItsPart() {
        // As Word writes a chart: an inline drawing whose graphic data names the chart part.
        val body = """<w:p><w:r><w:t>Sales went up:</w:t></w:r></w:p><w:p><w:r><w:drawing><wp:inline><wp:extent cx="5486400" cy="3200400"/><wp:docPr id="1" name="Chart 1"/>""" +
            """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">""" +
            """<c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId8"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
        val xml = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$body</w:body></w:document>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId8" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="charts/chart1.xml"/></Relationships>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            listOf("word/document.xml" to xml, "word/_rels/document.xml.rels" to rels, "word/charts/chart1.xml" to PptxWriter.chartXml(salesChart()))
                .forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        val doc = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(2, doc.blocks.size)
        val chart = doc.blocks[1] as ChartBlock
        assertEquals("the drawing's size: 6 × 3.5 in", 432f, chart.widthPt, 0.01f)
        assertEquals(252f, chart.heightPt, 0.01f)
        assertEquals(ChartType.LINE, chart.chart.type)
        assertEquals(listOf("Q1", "Q2", "Q3"), chart.chart.categories)
        assertEquals(listOf(12f, 15f, 19f), chart.chart.series[0].values)
    }

    @Test
    fun chartsGoBackToWordAndOdf() {
        val doc = Document(mutableListOf(ParagraphBlock(Paragraph.of("Before")), ChartBlock(salesChart(), 400f, 240f), ParagraphBlock(Paragraph.of("After"))))
        val docx = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        val parts = OfficeXml.unzip(docx)
        val body = String(parts["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(body.contains("""<wp:extent cx="5080000" cy="3048000"/>"""))
        assertTrue(Regex("""<c:chart xmlns:c="[^"]+" r:id="(rId\d+)"/>""").containsMatchIn(body))
        assertTrue(String(parts["word/_rels/document.xml.rels"]!!, Charsets.UTF_8).contains("""relationships/chart" Target="charts/chart1.xml""""))
        assertTrue(String(parts["[Content_Types].xml"]!!, Charsets.UTF_8).contains("""<Override PartName="/word/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>"""))
        val back = DocxReader.read(ByteArrayInputStream(docx))
        val chart = back.blocks[1] as ChartBlock
        assertEquals(ChartType.LINE, chart.chart.type)
        assertEquals("Sales", chart.chart.title)
        assertEquals(listOf("Q1", "Q2", "Q3"), chart.chart.categories)
        assertEquals(listOf("North", "South"), chart.chart.series.map { it.name })
        assertEquals(salesChart().series.map { it.values }, chart.chart.series.map { it.values })
        assertEquals(400f, chart.widthPt, 0.01f)
        assertEquals("After", (back.blocks[2] as ParagraphBlock).paragraph.plainText())

        val odt = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        val odtParts = OfficeXml.unzip(odt)
        assertTrue(String(odtParts["META-INF/manifest.xml"]!!, Charsets.UTF_8).contains("""manifest:full-path="Object 1/" manifest:media-type="application/vnd.oasis.opendocument.chart""""))
        val odtBack = OdtReader.read(odt)
        val fromOdt = odtBack.blocks.filterIsInstance<ChartBlock>().single()
        assertEquals(ChartType.LINE, fromOdt.chart.type)
        assertEquals(listOf("Q1", "Q2", "Q3"), fromOdt.chart.categories)
        assertEquals(listOf(9f, 11f, 8f), fromOdt.chart.series[1].values)
        assertEquals(240f, fromOdt.heightPt, 0.01f)
        assertEquals("the chart's preview is no picture", 0, odtBack.blocks.count { it is com.therealityteam.aeropages.model.ImageBlock })
    }

    @Test
    fun insertingChangingAndDeletingACHart() {
        val e = DocumentEditor()
        e.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Intro")), ParagraphBlock(Paragraph.of("End")))))
        e.setSelection(0, 0, 0)
        e.insertChart()
        val block = e.document.blocks[1] as ChartBlock
        assertSame(block, e.selectedChart)
        assertEquals(ChartType.COLUMN, block.chart.type)
        e.setChartType(block, ChartType.PIE)
        assertEquals(ChartType.PIE, block.chart.type)
        e.editChart(block) { it.series[0].values[0] = 99f }
        assertEquals(99f, block.chart.series[0].values[0], 0f)
        e.undo()
        assertEquals("undo gives back the data", ChartType.PIE, (e.document.blocks[1] as ChartBlock).chart.type)
        assertNull("and drops the pick (it held the old block)", e.selectedChart)
        val now = e.document.blocks[1] as ChartBlock
        e.deleteChart(now)
        assertEquals(listOf("Intro", "End"), e.document.blocks.map { (it as ParagraphBlock).paragraph.plainText() })
        e.undo()
        assertTrue(e.document.blocks[1] is ChartBlock)
    }
}
