package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Verifies workbooks (multi-sheet, text/number/formula/number-format) survive a .xlsx round trip. */
class XlsxRoundTripTest {

    @Test
    fun multiSheetRoundTrip() {
        val s1 = Sheet(name = "数据")
        s1.ensure(0, 0).text = "名称"          // text
        s1.ensure(1, 0).text = "10"            // number
        s1.ensure(2, 0).text = "=A2*2"         // formula → 20
        s1.ensure(0, 1).apply { text = "0.5"; numberFormat = NumberFormat.PERCENT }
        s1.ensure(0, 2).apply { text = "1234.5"; numberFormat = NumberFormat.CURRENCY_CNY }

        val s2 = Sheet(name = "Summary")
        s2.ensure(0, 0).text = "hi"
        s2.ensure(0, 1).text = "=数据"  // not a cross-sheet ref (unsupported) — just plain text-ish; keep simple
        s2.ensure(1, 0).text = "3.14"

        val wb = Workbook(mutableListOf(s1, s2))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val back = XlsxReader.read(bos.toByteArray())

        assertEquals("two sheets", 2, back.sheets.size)
        assertEquals("sheet1 name", "数据", back.sheets[0].name)
        assertEquals("sheet2 name", "Summary", back.sheets[1].name)

        val a = back.sheets[0]
        assertEquals("text cell", "名称", a.cell(0, 0)?.text)
        assertEquals("number cell", "10", a.cell(1, 0)?.text)
        assertEquals("formula cell", "=A2*2", a.cell(2, 0)?.text)
        assertEquals("formula evaluates", "20", FormulaEngine.display(a, 2, 0))
        assertEquals("percent format", NumberFormat.PERCENT, a.cell(0, 1)?.numberFormat)
        assertEquals("currency format", NumberFormat.CURRENCY_CNY, a.cell(0, 2)?.numberFormat)

        assertEquals("sheet2 text", "hi", back.sheets[1].cell(0, 0)?.text)
        assertEquals("sheet2 number", "3.14", back.sheets[1].cell(1, 0)?.text)
    }

    @Test
    fun mergesWidthsAndFreezeRoundTrip() {
        val s = Sheet(name = "M")
        s.ensure(0, 0).text = "合并标题"
        s.merges.add(com.therealityteam.aeropages.model.MergeRegion(0, 0, 0, 3))
        s.colWidths[1] = 2f
        s.rowHeights[0] = 1.5f
        s.freezeRows = 1
        s.freezeCols = 2
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(Workbook(mutableListOf(s)), bos)
        val a = XlsxReader.read(bos.toByteArray()).sheets[0]

        assertEquals("one merge", 1, a.merges.size)
        assertEquals("merge c2", 3, a.merges[0].c2)
        assertTrue("merge covers", a.merges[0].covers(0, 2))
        assertEquals("col width factor", 2f, a.colWidthFactor(1), 0.05f)
        assertEquals("row height factor", 1.5f, a.rowHeightFactor(0), 0.05f)
        assertEquals("freeze rows", 1, a.freezeRows)
        assertEquals("freeze cols", 2, a.freezeCols)
    }

    @Test
    fun stylesRoundTrip() {
        val s = Sheet(name = "S")
        s.ensure(0, 0).apply { text = "粗体"; format.bold = true; format.color = 0xFFCC0000 }
        s.ensure(0, 1).apply { text = "斜体"; format.italic = true }
        val wb = Workbook(mutableListOf(s))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val a = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertTrue("bold kept", a.cell(0, 0)?.format?.bold == true)
        assertEquals("color kept", 0xFFCC0000, a.cell(0, 0)?.format?.color)
        assertTrue("italic kept", a.cell(0, 1)?.format?.italic == true)
    }

    @Test
    fun chartsCondFormatsAndCommentsRoundTrip() {
        val s = Sheet(name = "X")
        s.ensure(0, 0).text = "60"; s.ensure(1, 0).text = "40"
        // Chart
        s.charts.add(com.therealityteam.aeropages.model.SlideChart(
            type = com.therealityteam.aeropages.model.ChartType.LINE, title = "趋势",
            categories = mutableListOf("一月", "二月"),
            series = mutableListOf(com.therealityteam.aeropages.model.ChartSeries("销量", mutableListOf(10f, 20f))),
        ))
        // Conditional format: >50 green
        s.condFormats.add(com.therealityteam.aeropages.model.CondFormat(0, 0, 1, 0,
            com.therealityteam.aeropages.model.CondType.GREATER, 50.0, 0.0, 0xFFC6EFCE))
        // Comment
        s.ensure(0, 0).comment = "请核对该数值"

        val bos = ByteArrayOutputStream()
        XlsxWriter.write(Workbook(mutableListOf(s)), bos)
        val a = XlsxReader.read(bos.toByteArray()).sheets[0]

        assertEquals("one chart", 1, a.charts.size)
        assertEquals("chart type", com.therealityteam.aeropages.model.ChartType.LINE, a.charts[0].type)
        assertEquals("chart title", "趋势", a.charts[0].title)
        assertEquals("chart cats", listOf("一月", "二月"), a.charts[0].categories.toList())
        assertEquals("chart values", listOf(10f, 20f), a.charts[0].series[0].values.toList())

        assertEquals("one rule", 1, a.condFormats.size)
        val cf = a.condFormats[0]
        assertEquals("rule type", com.therealityteam.aeropages.model.CondType.GREATER, cf.type)
        assertEquals("rule v1", 50.0, cf.v1, 0.001)
        assertEquals("rule fill", 0xFFC6EFCE, cf.look.fill)
        assertTrue("rule covers", cf.covers(1, 0))

        assertEquals("comment kept", "请核对该数值", a.cell(0, 0)?.comment)
    }

    @Test
    fun betweenRuleRoundTrip() {
        val s = Sheet(name = "Y")
        s.ensure(0, 0).text = "5"
        s.condFormats.add(com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0,
            com.therealityteam.aeropages.model.CondType.BETWEEN, 1.0, 10.0, 0xFFFFC7CE))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(Workbook(mutableListOf(s)), bos)
        val cf = XlsxReader.read(bos.toByteArray()).sheets[0].condFormats.single()
        assertEquals(com.therealityteam.aeropages.model.CondType.BETWEEN, cf.type)
        assertEquals(1.0, cf.v1, 0.001)
        assertEquals(10.0, cf.v2, 0.001)
    }

    @Test
    fun bordersWrapAndVAlignRoundTrip() {
        val s = Sheet(name = "B")
        s.ensure(0, 0).apply { text = "边框"; bTop = true; bLeft = true }
        s.ensure(0, 1).apply { text = "换行的长文本"; wrap = true; vAlign = com.therealityteam.aeropages.model.VAlign.TOP }
        s.ensure(0, 2).apply { text = "底部"; vAlign = com.therealityteam.aeropages.model.VAlign.BOTTOM; bBottom = true; bRight = true }
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(Workbook(mutableListOf(s)), bos)
        val a = XlsxReader.read(bos.toByteArray()).sheets[0]

        assertTrue("top border", a.cell(0, 0)?.bTop == true)
        assertTrue("left border", a.cell(0, 0)?.bLeft == true)
        assertEquals("no bottom", false, a.cell(0, 0)?.bBottom)
        assertTrue("wrap kept", a.cell(0, 1)?.wrap == true)
        assertEquals("top valign", com.therealityteam.aeropages.model.VAlign.TOP, a.cell(0, 1)?.vAlign)
        assertEquals("bottom valign", com.therealityteam.aeropages.model.VAlign.BOTTOM, a.cell(0, 2)?.vAlign)
        assertTrue("bottom+right borders", a.cell(0, 2)?.bBottom == true && a.cell(0, 2)?.bRight == true)
    }

    @Test
    fun dataBarAndColorScaleRoundTrip() {
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        for (r in 0..2) s.ensure(r, 0).text = "${r * 10}"
        s.condFormats.add(com.therealityteam.aeropages.model.CondFormat(0, 0, 2, 0,
            com.therealityteam.aeropages.model.CondType.DATA_BAR, 0.0, 0.0, 0xFF638EC6L))
        s.condFormats.add(com.therealityteam.aeropages.model.CondFormat(0, 1, 2, 1,
            com.therealityteam.aeropages.model.CondType.COLOR_SCALE, 0.0, 0.0, 0xFF63C384L, 0xFFFFFFFFL))

        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val back = XlsxReader.read(bos.toByteArray())
        val bs = back.sheets[0]
        assertEquals(2, bs.condFormats.size)
        val bar = bs.condFormats.first { it.type == com.therealityteam.aeropages.model.CondType.DATA_BAR }
        assertEquals(0xFF638EC6L, bar.color)
        assertEquals(0 to 0, bar.r1 to bar.c1)
        val scale = bs.condFormats.first { it.type == com.therealityteam.aeropages.model.CondType.COLOR_SCALE }
        assertEquals(0xFF63C384L, scale.color)
        assertEquals(0xFFFFFFFFL, scale.color2)
    }
}
