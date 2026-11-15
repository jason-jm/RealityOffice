package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.VAlign
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The XmlPull-based xlsx/pptx readers: a writer round trip plus hand-built packages whose parts
 * use foreign namespace prefixes, reversed attribute order, pretty-printing and self-closing vs
 * open/close forms — everything the old regex readers used to depend on.
 */
class XmlPullReadersTest {

    private val SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
    private val VT = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun xmlZip(vararg entries: Pair<String, String>): ByteArray =
        zipOf(*entries.map { (n, s) -> n to s.toByteArray(Charsets.UTF_8) }.toTypedArray())

    @Test fun xlsxWriterRoundTrip() {
        val s1 = Sheet(name = "数据")
        s1.ensure(0, 0).apply {
            text = "名称"; format.bold = true; format.color = 0xFFCC0000; format.highlight = 0xFFFFFF00
            align = TextAlign.CENTER; bTop = true; bLeft = true; wrap = true; vAlign = VAlign.TOP
        }
        s1.ensure(1, 0).text = "10"
        s1.ensure(2, 0).text = "=A2*2"
        s1.ensure(0, 1).apply { text = "0.5"; numberFormat = NumberFormat.PERCENT; decimals = 1 }
        s1.ensure(0, 2).apply { text = "1234.5"; numberFormat = NumberFormat.CURRENCY_CNY }
        s1.ensure(0, 3).apply { text = "a < b & \"c\""; comment = "请核对" }
        s1.merges.add(MergeRegion(3, 0, 3, 2))
        s1.colWidths[1] = 2f; s1.rowHeights[0] = 1.5f; s1.freezeRows = 1; s1.freezeCols = 1
        s1.condFormats.add(CondFormat(0, 0, 5, 0, CondType.BETWEEN, 1.0, 10.0, 0xFFFFC7CE))
        s1.condFormats.add(CondFormat(0, 1, 5, 1, CondType.DATA_BAR, 0.0, 0.0, 0xFF638EC6))
        s1.validations.add(DataValidation(0, 0, 4, 0, ValidationType.DECIMAL, 1.5, 9.5))
        s1.validations.add(DataValidation(0, 1, 4, 1, ValidationType.LIST, items = listOf("红", "绿")))
        s1.charts.add(SlideChart(type = ChartType.BAR, title = "趋势", categories = mutableListOf("一月", "二月"),
            series = mutableListOf(ChartSeries("销量", mutableListOf(10f, 20f))), colorSet = 1))
        val s2 = Sheet(name = "Summary")
        s2.ensure(0, 0).text = "hi"
        val wb = Workbook(mutableListOf(s1, s2))
        wb.names["销售额"] = "数据!A1:A3"

        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val back = XlsxReader.read(bos.toByteArray())

        assertEquals(listOf("数据", "Summary"), back.sheets.map { it.name })
        val a = back.sheets[0]
        val head = a.cell(0, 0)!!
        assertEquals("名称", head.text)
        assertTrue(head.format.bold); assertEquals(0xFFCC0000, head.format.color); assertEquals(0xFFFFFF00, head.format.highlight)
        assertEquals(TextAlign.CENTER, head.align); assertEquals(VAlign.TOP, head.vAlign); assertTrue(head.wrap)
        assertTrue(head.bTop && head.bLeft && !head.bRight && !head.bBottom)
        assertEquals("10", a.cell(1, 0)?.text)
        assertEquals("=A2*2", a.cell(2, 0)?.text)
        assertEquals(NumberFormat.PERCENT, a.cell(0, 1)?.numberFormat); assertEquals(1, a.cell(0, 1)?.decimals)
        assertEquals(NumberFormat.CURRENCY_CNY, a.cell(0, 2)?.numberFormat)
        assertEquals("a < b & \"c\"", a.cell(0, 3)?.text); assertEquals("请核对", a.cell(0, 3)?.comment)
        assertEquals(1, a.merges.size); assertEquals(2, a.merges[0].c2); assertEquals(3, a.merges[0].r1)
        assertEquals(2f, a.colWidthFactor(1), 0.05f); assertEquals(1.5f, a.rowHeightFactor(0), 0.05f)
        assertEquals(1, a.freezeRows); assertEquals(1, a.freezeCols)
        val between = a.condFormats.first { it.type == CondType.BETWEEN }
        assertEquals(1.0, between.v1, 1e-9); assertEquals(10.0, between.v2, 1e-9); assertEquals(0xFFFFC7CE, between.look.fill); assertEquals(5, between.r2)
        assertEquals(0xFF638EC6, a.condFormats.first { it.type == CondType.DATA_BAR }.color)
        val dec = a.validations.first { it.type == ValidationType.DECIMAL }
        assertEquals(1.5, dec.min, 1e-9); assertEquals(9.5, dec.max, 1e-9); assertEquals(4, dec.r2)
        assertEquals(listOf("红", "绿"), a.validations.first { it.type == ValidationType.LIST }.items)
        val chart = a.charts.single()
        assertEquals(ChartType.BAR, chart.type); assertEquals("趋势", chart.title)
        assertEquals(listOf("一月", "二月"), chart.categories.toList())
        assertEquals("销量", chart.series[0].name); assertEquals(listOf(10f, 20f), chart.series[0].values.toList())
        assertEquals(1, chart.colorSet); assertTrue(chart.showLegend)
        assertEquals("hi", back.sheets[1].cell(0, 0)?.text)
        assertEquals("数据!A1:A3", back.names["销售额"])
    }

    @Test fun xlsxWithForeignPrefixesAndReversedAttributes() {
        val workbook = """<?xml version="1.0" encoding="UTF-8"?>
            <ns0:workbook xmlns:ns0="$SS" xmlns:rel="$REL">
              <ns0:sheets>
                <ns0:sheet rel:id="rIdB" sheetId="2" name="Second" />
                <ns0:sheet sheetId="1" rel:id="rIdA" name="First"/>
              </ns0:sheets>
              <ns0:definedNames><ns0:definedName localSheetId="0" name="总计"> First!A1:A3 </ns0:definedName></ns0:definedNames>
            </ns0:workbook>"""
        val rels = """<Relationships xmlns="$PKG">
              <Relationship Target="/xl/worksheets/sheet7.xml" Type="$REL/worksheet" Id="rIdB"/>
              <Relationship Type="$REL/worksheet" Target="worksheets/sheet3.xml" Id="rIdA" />
              <Relationship Id="rIdS" Type="$REL/styles" Target="styles.xml"/>
            </Relationships>"""
        val shared = """<x:sst xmlns:x="$SS" count="3" uniqueCount="3">
              <x:si><x:t>plain</x:t></x:si>
              <x:si><x:r><x:rPr><x:b/></x:rPr><x:t>rich </x:t></x:r><x:r><x:t xml:space="preserve">text</x:t></x:r><x:rPh sb="0" eb="4"><x:t>ふりがな</x:t></x:rPh></x:si>
              <x:si><x:t>a &amp; b &lt; c</x:t></x:si>
            </x:sst>"""
        val styles = """<x:styleSheet xmlns:x="$SS">
              <x:numFmts count="1"><x:numFmt formatCode="#,##0.000" numFmtId="164"/></x:numFmts>
              <x:fonts count="3">
                <x:font><x:sz val="11"/></x:font>
                <x:font><x:b val="1"/><x:i/><x:u val="single"/><x:color rgb="FF00AA00"/></x:font>
                <x:font><x:b val="0"/><x:color theme="1"/></x:font>
              </x:fonts>
              <x:fills count="3"><x:fill><x:patternFill patternType="none"/></x:fill><x:fill><x:patternFill patternType="gray125"/></x:fill>
                <x:fill><x:patternFill patternType="solid"><x:fgColor rgb="FFFFFF00"/><x:bgColor indexed="64"/></x:patternFill></x:fill></x:fills>
              <x:borders count="2"><x:border><x:left/><x:right/><x:top/><x:bottom/></x:border>
                <x:border><x:left/><x:right style="thin"><x:color auto="1"/></x:right><x:top/><x:bottom style="medium"/></x:border></x:borders>
              <x:cellXfs count="4">
                <x:xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
                <x:xf borderId="1" fillId="2" fontId="1" numFmtId="164" applyAlignment="1">
                  <x:alignment wrapText="1" vertical="top" horizontal="center"/>
                </x:xf>
                <x:xf numFmtId="9" fontId="2" fillId="0" borderId="0"><x:alignment horizontal="right" vertical="bottom"/></x:xf>
                <x:xf numFmtId="14" fontId="0" fillId="0" borderId="0"></x:xf>
              </x:cellXfs>
              <x:dxfs count="2"><x:dxf><x:font><x:color rgb="FF9C0006"/></x:font><x:fill><x:patternFill><x:bgColor rgb="FFFFC7CE"/></x:patternFill></x:fill></x:dxf>
                <x:dxf><x:fill><x:patternFill><x:bgColor rgb="FFC6EFCE"/></x:patternFill></x:fill></x:dxf></x:dxfs>
            </x:styleSheet>"""
        val sheet3 = """<x:worksheet xmlns:x="$SS" xmlns:rel="$REL">
              <x:sheetViews><x:sheetView workbookViewId="0"><x:pane topLeftCell="C2" state="frozen" ySplit="1" xSplit="2"/></x:sheetView></x:sheetViews>
              <x:cols><x:col width="18.28125" customWidth="1" max="3" min="2"/></x:cols>
              <x:sheetData>
                <x:row ht="30" customHeight="1" r="1">
                  <x:c t="s" r="A1" s="1"><x:v>1</x:v></x:c>
                  <x:c r="B1" t="s"><x:v>2</x:v></x:c>
                  <x:c r="C1" t="inlineStr"><x:is><x:r><x:t>in</x:t></x:r><x:r><x:t>line</x:t></x:r></x:is></x:c>
                  <x:c r="D1" t="b" s="2"><x:v>1</x:v></x:c>
                  <x:c r="E1" t="e"><x:v>#DIV/0!</x:v></x:c>
                  <x:c r="F1" t="str"><x:f>"x"&amp;"y"</x:f><x:v>xy</x:v></x:c>
                  <x:c r="G1" s="3"><x:v>45449</x:v></x:c>
                  <x:c r="H1" s="1"/>
                </x:row>
                <x:row r="2"><x:c r="A2"><x:v>10</x:v></x:c><x:c r="B2"><x:f si="0" t="shared" ref="B2:B4">A2*2</x:f><x:v>20</x:v></x:c></x:row>
                <x:row r="3"><x:c r="A3"><x:v>20</x:v></x:c><x:c r="B3"><x:f si="0" t="shared"/><x:v>40</x:v></x:c></x:row>
                <x:row r="4"><x:c r="A4"><x:v>30</x:v></x:c><x:c r="B4"><x:f t="shared" si="0"></x:f><x:v>60</x:v></x:c><x:c r="ALM4"><x:v>999</x:v></x:c></x:row>
              </x:sheetData>
              <x:mergeCells count="1"><x:mergeCell ref="A6:C7"/></x:mergeCells>
              <x:conditionalFormatting pivot="0" sqref="A2:A4 C9"><x:cfRule priority="1" operator="greaterThan" dxfId="1" type="cellIs"><x:formula>15</x:formula></x:cfRule></x:conditionalFormatting>
              <x:conditionalFormatting sqref="B2:B4"><x:cfRule priority="2" type="colorScale"><x:colorScale><x:cfvo type="min"/><x:cfvo type="max"/><x:color rgb="FFFFFFFF"/><x:color rgb="63C384"/></x:colorScale></x:cfRule></x:conditionalFormatting>
              <x:dataValidations count="2">
                <x:dataValidation sqref="A2:A4" allowBlank="1" operator="between" type="whole"><x:formula1>1</x:formula1><x:formula2>100</x:formula2></x:dataValidation>
                <x:dataValidation type="list" sqref="C2"><x:formula1>"是,否"</x:formula1></x:dataValidation>
              </x:dataValidations>
            </x:worksheet>"""
        val sheet7 = """<x:worksheet xmlns:x="$SS"><x:sheetData><x:row r="1"><x:c r="A1" t="inlineStr"><x:is><x:t>second</x:t></x:is></x:c></x:row></x:sheetData></x:worksheet>"""
        val sheet7Rels = """<Relationships xmlns="$PKG"><Relationship Target="../comments9.xml" Id="rId1" Type="$REL/comments"/></Relationships>"""
        val comments = """<x:comments xmlns:x="$SS"><x:authors><x:author>A</x:author></x:authors><x:commentList>
              <x:comment authorId="0" ref="A1"><x:text><x:r><x:rPr><x:b/></x:rPr><x:t>A:</x:t></x:r><x:r><x:t xml:space="preserve"> check</x:t></x:r></x:text></x:comment>
            </x:commentList></x:comments>"""

        val wb = XlsxReader.read(xmlZip(
            "xl/workbook.xml" to workbook, "xl/_rels/workbook.xml.rels" to rels,
            "xl/sharedStrings.xml" to shared, "xl/styles.xml" to styles,
            "xl/worksheets/sheet3.xml" to sheet3, "xl/worksheets/sheet7.xml" to sheet7,
            "xl/worksheets/_rels/sheet7.xml.rels" to sheet7Rels, "xl/comments9.xml" to comments,
        ))

        // Sheet order from workbook.xml + rels (absolute and relative targets), not part numbering.
        assertEquals(listOf("Second", "First"), wb.sheets.map { it.name })
        assertEquals("second", wb.sheets[0].cell(0, 0)?.text)
        assertEquals("A: check", wb.sheets[0].cell(0, 0)?.comment)
        assertEquals("First!A1:A3", wb.names["总计"])
        val s = wb.sheets[1]
        // Shared strings: rich runs concatenated, phonetic hints dropped, entities decoded; inline runs concatenated.
        assertEquals("rich text", s.cell(0, 0)?.text)
        assertEquals("a & b < c", s.cell(0, 1)?.text)
        assertEquals("inline", s.cell(0, 2)?.text)
        assertEquals("a logical cell is TRUE, not the 1 written", "TRUE", s.cell(0, 3)?.text)
        assertEquals("#DIV/0!", s.cell(0, 4)?.text)
        assertEquals("=\"x\"&\"y\"", s.cell(0, 5)?.text)
        assertEquals(NumberFormat.DATE, s.cell(0, 6)?.numberFormat)
        // A style-only empty cell keeps the style it shows (its fill and borders), as Excel does; it holds no text.
        s.cell(0, 7)!!.let { h1 -> assertEquals("", h1.text); assertTrue(h1.format.highlight != null && h1.borderBottom != null) }
        assertNull("column 1000 is beyond the grid (would alias A5)", s.cell(4, 0))
        // Styles: numFmt with reversed attributes, font toggles with val, fill, borders, alignment.
        val a1 = s.cell(0, 0)!!
        assertEquals(NumberFormat.THOUSANDS, a1.numberFormat); assertEquals(3, a1.decimals)
        assertTrue(a1.format.bold && a1.format.italic && a1.format.underline)
        assertEquals(0xFF00AA00, a1.format.color); assertEquals(0xFFFFFF00, a1.format.highlight)
        assertTrue(a1.bRight && a1.bBottom && !a1.bTop && !a1.bLeft)
        assertTrue(a1.wrap); assertEquals(VAlign.TOP, a1.vAlign); assertEquals(TextAlign.CENTER, a1.align)
        val d1 = s.cell(0, 3)!!
        assertFalse("b val=0 is not bold", d1.format.bold)
        assertEquals(NumberFormat.PERCENT, d1.numberFormat); assertEquals(TextAlign.RIGHT, d1.align); assertEquals(VAlign.BOTTOM, d1.vAlign)
        // Shared formula master + followers (self-closing and open/close forms).
        assertEquals("=A2*2", s.cell(1, 1)?.text); assertEquals("=A3*2", s.cell(2, 1)?.text); assertEquals("=A4*2", s.cell(3, 1)?.text)
        // Freeze pane, column widths (min..max), row height, merge.
        assertEquals(2, s.freezeCols); assertEquals(1, s.freezeRows)
        assertEquals(2f, s.colWidthFactor(1), 0.01f); assertEquals(2f, s.colWidthFactor(2), 0.01f); assertEquals(1f, s.colWidthFactor(3), 0.01f)
        assertEquals(2f, s.rowHeightFactor(0), 0.01f)
        assertEquals(1, s.merges.size); assertTrue(s.merges[0].covers(6, 2))
        // Conditional formats (dxf colour, first sqref range) + validations.
        val gt = s.condFormats.first { it.type == CondType.GREATER }
        assertEquals(15.0, gt.v1, 1e-9); assertEquals(0xFFC6EFCE, gt.look.fill); assertEquals(1, gt.r1); assertEquals(3, gt.r2); assertEquals(0, gt.c2)
        val scale = s.condFormats.first { it.type == CondType.COLOR_SCALE }
        assertEquals(0xFF63C384, scale.color); assertEquals(0xFFFFFFFF, scale.color2)
        val whole = s.validations.first { it.type == ValidationType.INTEGER }
        assertEquals(1.0, whole.min, 1e-9); assertEquals(100.0, whole.max, 1e-9); assertEquals(3, whole.r2)
        assertEquals(listOf("是", "否"), s.validations.first { it.type == ValidationType.LIST }.items)
    }

    @Test fun pptxWithForeignPrefixesAndReversedAttributes() {
        val presentation = """<ns0:presentation xmlns:ns0="$P" xmlns:rel="$REL">
              <ns0:sldIdLst><ns0:sldId rel:id="rId9" id="257"/><ns0:sldId id="256" rel:id="rId3"/></ns0:sldIdLst>
              <ns0:sldSz cx="12192000" cy="6858000"/></ns0:presentation>"""
        val presRels = """<Relationships xmlns="$PKG">
              <Relationship Target="slides/slide1.xml" Id="rId3" Type="$REL/slide"/>
              <Relationship Type="$REL/slide" Id="rId9" Target="/ppt/slides/slide2.xml"/>
            </Relationships>"""
        val slide1 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns0:sld xmlns:ns0="$P" xmlns:d="$A" xmlns:rel="$REL" show="0">
  <ns0:cSld><ns0:spTree>
    <ns0:sp>
      <ns0:nvSpPr><ns0:cNvPr name="Title" id="2"/><ns0:cNvSpPr/><ns0:nvPr><ns0:ph type="ctrTitle"/></ns0:nvPr></ns0:nvSpPr>
      <ns0:spPr/>
      <ns0:txBody><d:bodyPr/><d:p><d:pPr algn="r"/><d:r><d:rPr sz="3200" b="1"/><d:t>Big </d:t></d:r><d:r><d:rPr i="1" lang="en"><d:solidFill><d:srgbClr val="FF0000"/></d:solidFill></d:rPr><d:t>red</d:t></d:r></d:p></ns0:txBody>
    </ns0:sp>
    <ns0:sp useBgFill="1">
      <ns0:nvSpPr><ns0:cNvPr id="3" name="Body"/><ns0:cNvSpPr/><ns0:nvPr><ns0:ph idx="1" type="body"/></ns0:nvPr></ns0:nvSpPr>
      <ns0:txBody><d:p><d:r><d:t>line one</d:t></d:r></d:p><d:p><d:r><d:rPr u="sng" strike="sngStrike" baseline="-25000" cap="small" spc="150"><d:highlight><d:srgbClr val="FFFF00"/></d:highlight><d:latin typeface="Arial"/><d:hlinkClick rel:id="rId7"/></d:rPr><d:t>link</d:t></d:r></d:p></ns0:txBody>
    </ns0:sp>
    <ns0:sp>
      <ns0:nvSpPr><ns0:cNvPr id="4" name="Shape"/><ns0:cNvSpPr/><ns0:nvPr/></ns0:nvSpPr>
      <ns0:spPr><d:xfrm flipH="1" rot="2700000"><d:off y="685800" x="1219200"/><d:ext cy="1371600" cx="2438400"/></d:xfrm>
        <d:prstGeom prst="ellipse"><d:avLst/></d:prstGeom><d:solidFill><d:srgbClr val="2B579A"/></d:solidFill>
        <d:ln w="12700"><d:noFill/></d:ln></ns0:spPr>
      <ns0:txBody><d:p><d:r><d:rPr><d:hlinkClick rel:id="rId7"/></d:rPr><d:t>go</d:t></d:r></d:p></ns0:txBody>
    </ns0:sp>
    <ns0:graphicFrame>
      <ns0:nvGraphicFramePr><ns0:cNvPr id="5" name="Table"/><ns0:cNvGraphicFramePr/><ns0:nvPr/></ns0:nvGraphicFramePr>
      <ns0:xfrm><d:off x="609600" y="3429000"/><d:ext cx="6096000" cy="1371600"/></ns0:xfrm>
      <d:graphic><d:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><d:tbl>
        <d:tblPr bandRow="1" firstRow="1"/><d:tblGrid><d:gridCol w="3048000"/><d:gridCol w="3048000"/></d:tblGrid>
        <d:tr h="685800"><d:tc><d:txBody><d:p><d:r><d:t>H1</d:t></d:r></d:p></d:txBody><d:tcPr/></d:tc><d:tc><d:txBody><d:p><d:r><d:t>H2</d:t></d:r></d:p></d:txBody></d:tc></d:tr>
        <d:tr h="685800"><d:tc><d:txBody><d:p><d:r><d:t>a</d:t></d:r></d:p></d:txBody></d:tc><d:tc><d:txBody><d:p><d:r><d:t>b</d:t></d:r></d:p></d:txBody></d:tc></d:tr>
      </d:tbl></d:graphicData></d:graphic>
    </ns0:graphicFrame>
    <ns0:pic><ns0:nvPicPr><ns0:cNvPr id="6" name="Pic"/><ns0:cNvPicPr/><ns0:nvPr/></ns0:nvPicPr>
      <ns0:blipFill><d:blip cstate="print" rel:embed="rId5"/><d:stretch><d:fillRect/></d:stretch></ns0:blipFill>
      <ns0:spPr><d:xfrm><d:off x="0" y="0"/><d:ext cx="914400" cy="914400"/></d:xfrm><d:prstGeom prst="rect"/></ns0:spPr></ns0:pic>
    <ns0:sp><ns0:nvSpPr><ns0:cNvPr id="7" name="Footer"/><ns0:cNvSpPr/><ns0:nvPr><ns0:ph sz="quarter" idx="10" type="ftr"/></ns0:nvPr></ns0:nvSpPr><ns0:txBody><d:p><d:r><d:t>机密</d:t></d:r></d:p></ns0:txBody></ns0:sp>
    <ns0:sp><ns0:nvSpPr><ns0:cNvPr id="8" name="Num"/><ns0:cNvSpPr/><ns0:nvPr><ns0:ph idx="12" type="sldNum"/></ns0:nvPr></ns0:nvSpPr><ns0:txBody><d:p><d:fld type="slidenum"><d:t>1</d:t></d:fld></d:p></ns0:txBody></ns0:sp>
  </ns0:spTree></ns0:cSld>
  <ns0:transition spd="slow" advTm="3000"><ns0:push dir="l"/></ns0:transition>
  <ns0:timing><ns0:tnLst><ns0:par><ns0:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><ns0:childTnLst><ns0:seq concurrent="1" nextAc="seek"><ns0:cTn id="2" dur="indefinite" nodeType="mainSeq"><ns0:childTnLst>
    <ns0:par><ns0:cTn id="3" fill="hold"><ns0:stCondLst><ns0:cond delay="indefinite"/></ns0:stCondLst><ns0:childTnLst>
      <ns0:par><ns0:cTn id="4" fill="hold"><ns0:stCondLst><ns0:cond delay="0"/></ns0:stCondLst><ns0:childTnLst>
        <ns0:par><ns0:cTn id="5" presetID="2" presetClass="entr" presetSubtype="4" fill="hold" nodeType="clickEffect"><ns0:stCondLst><ns0:cond delay="0"/></ns0:stCondLst><ns0:childTnLst>
          <ns0:anim calcmode="lin" valueType="num"><ns0:cBhvr><ns0:cTn id="6" dur="800" fill="hold"/><ns0:tgtEl><ns0:spTgt spid="2"/></ns0:tgtEl><ns0:attrNameLst><ns0:attrName>ppt_y</ns0:attrName></ns0:attrNameLst></ns0:cBhvr></ns0:anim>
        </ns0:childTnLst></ns0:cTn></ns0:par>
        <ns0:par><ns0:cTn id="7" presetID="53" presetClass="entr" presetSubtype="16" fill="hold" nodeType="withEffect"><ns0:stCondLst><ns0:cond delay="0"/></ns0:stCondLst><ns0:childTnLst>
          <ns0:animScale><ns0:cBhvr><ns0:cTn id="8" dur="450" fill="hold"/><ns0:tgtEl><ns0:spTgt spid="4"/></ns0:tgtEl></ns0:cBhvr></ns0:animScale>
        </ns0:childTnLst></ns0:cTn></ns0:par>
      </ns0:childTnLst></ns0:cTn></ns0:par>
      <ns0:par><ns0:cTn id="9" fill="hold"><ns0:stCondLst><ns0:cond delay="800"/></ns0:stCondLst><ns0:childTnLst>
        <ns0:par><ns0:cTn id="10" presetID="10" presetClass="entr" presetSubtype="0" fill="hold" nodeType="afterEffect"><ns0:stCondLst><ns0:cond delay="300"/></ns0:stCondLst><ns0:childTnLst>
          <ns0:animEffect transition="in" filter="fade"><ns0:cBhvr><ns0:cTn id="11" dur="1200"/><ns0:tgtEl><ns0:spTgt spid="5"/></ns0:tgtEl></ns0:cBhvr></ns0:animEffect>
        </ns0:childTnLst></ns0:cTn></ns0:par>
      </ns0:childTnLst></ns0:cTn></ns0:par>
    </ns0:childTnLst></ns0:cTn></ns0:par>
  </ns0:childTnLst></ns0:cTn></ns0:seq></ns0:childTnLst></ns0:cTn></ns0:par></ns0:tnLst></ns0:timing>
</ns0:sld>"""
        val slide1Rels = """<Relationships xmlns="$PKG">
              <Relationship Id="rId1" Type="$REL/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
              <Relationship Target="../media/image1.jpeg" Type="$REL/image" Id="rId5"/>
              <Relationship TargetMode="External" Target="https://example.com/?a=1&amp;b=2" Type="$REL/hyperlink" Id="rId7"/>
              <Relationship Target="../notesSlides/notesSlide1.xml" Id="rId8" Type="$REL/notesSlide"/>
            </Relationships>"""
        val notes1 = """<ns0:notes xmlns:ns0="$P" xmlns:d="$A"><ns0:cSld><ns0:spTree>
              <ns0:sp><ns0:nvSpPr><ns0:cNvPr id="2" name="n"/><ns0:cNvSpPr/><ns0:nvPr><ns0:ph type="sldNum"/></ns0:nvPr></ns0:nvSpPr><ns0:txBody><d:p><d:r><d:t>1</d:t></d:r></d:p></ns0:txBody></ns0:sp>
              <ns0:sp><ns0:nvSpPr><ns0:cNvPr id="3" name="b"/><ns0:cNvSpPr/><ns0:nvPr><ns0:ph idx="1" type="body"/></ns0:nvPr></ns0:nvSpPr><ns0:txBody><d:p><d:r><d:t>first note</d:t></d:r></d:p><d:p><d:r><d:t>second</d:t></d:r><d:r><d:t> note</d:t></d:r></d:p></ns0:txBody></ns0:sp>
            </ns0:spTree></ns0:cSld></ns0:notes>"""
        // No placeholders at all → plain paragraph scan; the chart frame is excluded from it.
        val slide2 = """<ns0:sld xmlns:ns0="$P" xmlns:d="$A" xmlns:rel="$REL"><ns0:cSld><ns0:spTree>
              <ns0:sp><ns0:nvSpPr><ns0:cNvPr id="2" name="t"/><ns0:cNvSpPr/><ns0:nvPr/></ns0:nvSpPr><ns0:spPr/><ns0:txBody><d:p><d:r><d:t>Fallback </d:t></d:r><d:r><d:t>title</d:t></d:r></d:p></ns0:txBody></ns0:sp>
              <ns0:sp><ns0:nvSpPr><ns0:cNvPr id="3" name="b"/><ns0:cNvSpPr/><ns0:nvPr/></ns0:nvSpPr><ns0:spPr/><ns0:txBody><d:p><d:r><d:t>body A</d:t></d:r></d:p><d:p><d:r><d:t>body B</d:t></d:r></d:p></ns0:txBody></ns0:sp>
              <ns0:graphicFrame><ns0:nvGraphicFramePr><ns0:cNvPr id="4" name="c"/><ns0:cNvGraphicFramePr/><ns0:nvPr/></ns0:nvGraphicFramePr>
                <ns0:xfrm><d:off x="1219200" y="1371600"/><d:ext cx="6096000" cy="3429000"/></ns0:xfrm>
                <d:graphic><d:graphicData uri="$C"><ch:chart xmlns:ch="$C" rel:id="rId2"/></d:graphicData></d:graphic></ns0:graphicFrame>
            </ns0:spTree></ns0:cSld><ns0:transition><ns0:wipe/></ns0:transition></ns0:sld>"""
        val slide2Rels = """<Relationships xmlns="$PKG"><Relationship Target="../charts/chart5.xml" Id="rId2" Type="$REL/chart"/></Relationships>"""
        val chart = """<ch:chartSpace xmlns:ch="$C" xmlns:d="$A"><ch:chart>
              <ch:title><ch:tx><ch:rich><d:p><d:r><d:t>占比</d:t></d:r></d:p></ch:rich></ch:tx></ch:title>
              <ch:plotArea><ch:pieChart><ch:varyColors val="1"/><ch:ser><ch:idx val="0"/><ch:order val="0"/>
                <ch:tx><ch:strRef><ch:f>Sheet1!${'$'}B${'$'}1</ch:f><ch:strCache><ch:ptCount val="1"/><ch:pt idx="0"><ch:v>份额</ch:v></ch:pt></ch:strCache></ch:strRef></ch:tx>
                <ch:dPt><ch:idx val="0"/><ch:spPr><d:solidFill><d:srgbClr val="c0504d"/></d:solidFill></ch:spPr></ch:dPt>
                <ch:cat><ch:strRef><ch:strCache><ch:pt idx="0"><ch:v>甲</ch:v></ch:pt><ch:pt idx="1"><ch:v>乙</ch:v></ch:pt></ch:strCache></ch:strRef></ch:cat>
                <ch:val><ch:numRef><ch:numCache><ch:pt idx="0"><ch:v>60</ch:v></ch:pt><ch:pt idx="1"><ch:v>40</ch:v></ch:pt></ch:numCache></ch:numRef></ch:val>
              </ch:ser></ch:pieChart></ch:plotArea><ch:legend><ch:legendPos val="b"/></ch:legend></ch:chart></ch:chartSpace>"""
        val custom = """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vtx="$VT">
              <property pid="2" fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" name="AeroComments"><vtx:lpwstr>0|first%3B slide;1|second %7C slide</vtx:lpwstr></property></Properties>"""

        val pres = PptxReader.read(zipOf(
            "ppt/presentation.xml" to presentation.toByteArray(), "ppt/_rels/presentation.xml.rels" to presRels.toByteArray(),
            "ppt/slides/slide1.xml" to slide1.toByteArray(), "ppt/slides/_rels/slide1.xml.rels" to slide1Rels.toByteArray(),
            "ppt/notesSlides/notesSlide1.xml" to notes1.toByteArray(), "ppt/media/image1.jpeg" to byteArrayOf(1, 2, 3),
            "ppt/slides/slide2.xml" to slide2.toByteArray(), "ppt/slides/_rels/slide2.xml.rels" to slide2Rels.toByteArray(),
            "ppt/charts/chart5.xml" to chart.toByteArray(), "docProps/custom.xml" to custom.toByteArray(),
        ))

        assertEquals(2, pres.slides.size)
        // Order follows sldIdLst (slide2 first), not part numbering.
        val fb = pres.slides[0]
        assertEquals("Fallback title", fb.title.text)
        assertEquals("body A\nbody B", fb.body.text)
        assertEquals("wipe is its own transition now", SlideTransition.WIPE, fb.transition)
        assertFalse(fb.hidden)
        val chartBack = fb.charts.single()
        assertEquals(ChartType.PIE, chartBack.type); assertEquals("占比", chartBack.title); assertEquals("份额", chartBack.series[0].name)
        assertEquals(listOf("甲", "乙"), chartBack.categories.toList()); assertEquals(listOf(60f, 40f), chartBack.series[0].values.toList())
        assertEquals(3, chartBack.colorSet); assertTrue(chartBack.showLegend)
        assertEquals(0.1f, chartBack.xPct, 0.001f); assertEquals(0.5f, chartBack.wPct, 0.001f)

        val s = pres.slides[1]
        assertEquals("Big red", s.title.text); assertEquals(TextAlign.RIGHT, s.title.align)
        val runs = s.title.paragraph.runs
        assertTrue(runs[0].format.bold); assertEquals(32f, runs[0].format.fontSizePt)
        assertTrue(runs[1].format.italic); assertEquals(0xFFFF0000, runs[1].format.color); assertEquals(40f, runs[1].format.fontSizePt)
        s.title.anims.single().let { a ->
            assertEquals(AnimEffect.FLY_IN, a.effect); assertEquals(800, a.durationMs); assertEquals(AnimDir.BOTTOM, a.dir)
            assertEquals(AnimStart.ON_CLICK, a.start); assertEquals(0, a.order)
        }
        assertEquals("line one\nlink", s.body.text)
        val link = s.body.paragraph.runs.last().format
        assertEquals("https://example.com/?a=1&b=2", link.link)
        assertTrue(link.underline && link.strikethrough && link.subscript && link.smallCaps)
        assertEquals(1.5f, link.letterSpacingPt); assertEquals("Arial", link.fontFamily); assertEquals(0xFFFFFF00, link.highlight); assertEquals(22f, link.fontSizePt)
        val shape = s.shapes.single()
        assertEquals(ShapeType.ELLIPSE, shape.type); assertEquals("go", shape.box.text)
        assertEquals("https://example.com/?a=1&b=2", shape.box.paragraph.runs[0].format.link)
        assertEquals(0xFF2B579A, shape.fill); assertNull("an outline noFill is not a shape noFill", shape.border)
        assertEquals(0.1f, shape.xPct, 0.001f); assertEquals(0.1f, shape.yPct, 0.001f); assertEquals(0.2f, shape.wPct, 0.001f); assertEquals(0.2f, shape.hPct, 0.001f)
        assertEquals(45f, shape.rotationDeg, 0.001f); assertTrue(shape.flipH); assertFalse(shape.flipV)
        shape.box.anims.single().let { a -> assertEquals(AnimEffect.ZOOM, a.effect); assertEquals(450, a.durationMs); assertEquals(AnimStart.WITH_PREVIOUS, a.start); assertEquals(1, a.order) }
        val table = s.tables.single()
        assertEquals(2, table.rows); assertEquals(2, table.cols); assertEquals("H2", table.cells[0][1].text); assertEquals("b", table.cells[1][1].text)
        assertTrue(table.headerRow && table.bandedRows)
        table.anims.single().let { a ->
            assertEquals(AnimEffect.FADE, a.effect); assertEquals(1200, a.durationMs); assertEquals(AnimStart.AFTER_PREVIOUS, a.start)
            assertEquals(300, a.delayMs); assertEquals(2, a.order)
        }
        assertEquals(0.05f, table.xPct, 0.001f); assertEquals(0.5f, table.yPct, 0.001f)
        assertEquals(1, s.images.size); assertEquals("image/jpeg", s.images[0].mime); assertArrayEquals(byteArrayOf(1, 2, 3), s.images[0].data)
        assertTrue(s.hidden); assertEquals(SlideTransition.PUSH, s.transition); assertEquals(3000, s.advanceMs)
        assertEquals("first note\nsecond note", s.notes)
        assertEquals("机密", pres.footer); assertTrue(pres.showSlideNumber)
        // Earlier versions' AeroComments property is still read.
        assertEquals(listOf("first; slide"), pres.slides[0].comments.map { it.text })
        assertEquals(listOf("second | slide"), pres.slides[1].comments.map { it.text })
    }

    @Test fun relocatedWorkbookKeepsItsSheetsStringsAndStylesWhenSavedAgain() {
        val bytes = xmlZip(
            "_rels/.rels" to """<Relationships xmlns="$PKG"><Relationship Id="main" Type="$REL/officeDocument" Target="/book/main.xml"/></Relationships>""",
            "book/main.xml" to """<workbook xmlns="$SS" xmlns:r="$REL"><sheets><sheet name="Budget" sheetId="1" r:id="one"/></sheets></workbook>""",
            "book/_rels/main.xml.rels" to """<Relationships xmlns="$PKG">
                <Relationship Id="one" Type="$REL/worksheet" Target="../data/june.xml"/>
                <Relationship Id="strings" Type="$REL/sharedStrings" Target="../text/dictionary.xml"/>
                <Relationship Id="style" Type="$REL/styles" Target="/look/formats.xml"/></Relationships>""",
            "text/dictionary.xml" to """<sst xmlns="$SS"><si><t>Income</t></si></sst>""",
            "look/formats.xml" to """<styleSheet xmlns="$SS"><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="10"/></cellXfs></styleSheet>""",
            "data/june.xml" to """<worksheet xmlns="$SS"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" s="1"><f>1/4</f><v>0.25</v></c></row></sheetData></worksheet>""",
        )
        val book = XlsxReader.read(bytes)
        val saved = ByteArrayOutputStream().also { XlsxWriter.write(book, it) }.toByteArray()
        for (s in listOf(book.sheets.single(), XlsxReader.read(saved).sheets.single())) {
            assertEquals("Budget", s.name)
            assertEquals("Income", s.cell(0, 0)?.text)
            assertEquals("=1/4", s.cell(0, 1)?.text)
            assertEquals(NumberFormat.PERCENT, s.cell(0, 1)?.numberFormat)
        }
    }

    @Test fun unreadableWorkbookIsNeverReturnedAsAnEmptyEditableSheet() {
        for (bytes in listOf(xmlZip("unrelated.xml" to "<root/>"),
            xmlZip("xl/worksheets/sheet1.xml" to "<other/>"))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { XlsxReader.read(bytes) }
        }
    }

    @Test fun partialWorkbooksAreMarkedForRecoveryButARealBlankSheetIsEditable() {
        val parts = arrayOf(
            "xl/workbook.xml" to """<workbook xmlns="$SS" xmlns:r="$REL"><sheets><sheet name="Found" r:id="ok"/><sheet name="Lost" r:id="missing"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="$PKG"><Relationship Id="ok" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$SS"><sheetData/></worksheet>""",
        )
        com.therealityteam.aeropages.format.XmlPull.takeSalvaged()
        assertEquals("Found", XlsxReader.read(xmlZip(*parts)).sheets.single().name)
        assertTrue(com.therealityteam.aeropages.format.XmlPull.takeSalvaged() > 0)
        val blank = ByteArrayOutputStream().also { XlsxWriter.write(Workbook.blank(), it) }.toByteArray()
        assertTrue(XlsxReader.read(blank).sheets.single().cells.isEmpty())
        assertEquals(0, com.therealityteam.aeropages.format.XmlPull.takeSalvaged())
    }

    @Test fun malformedPartsDegradeInsteadOfThrowing() {
        // Truncated worksheet / styles / shared strings: whatever was read before the break is kept.
        val sheet = """<worksheet xmlns="$SS"><sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="2"><c r="A2"><v>2</v></c><c r="B2"><v>"""
        val wb = XlsxReader.read(xmlZip(
            "xl/worksheets/sheet1.xml" to sheet,
            "xl/styles.xml" to "<styleSheet><fonts><font>",
            "xl/sharedStrings.xml" to "<sst><si><t>x</t></si><si><t>y",
        ))
        assertEquals(1, wb.sheets.size)
        assertEquals("1", wb.sheets[0].cell(0, 0)?.text)
        assertEquals("2", wb.sheets[0].cell(1, 0)?.text)
        assertNull(wb.sheets[0].cell(1, 1))
        // A broken slide keeps its position as an empty slide; the good one still parses.
        val good = """<p:sld xmlns:p="$P" xmlns:a="$A"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="t"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>ok</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
        val pres = PptxReader.read(xmlZip("ppt/slides/slide1.xml" to "<p:sld xmlns:p=\"$P\"><p:cSld><p:spTree><p:sp>", "ppt/slides/slide2.xml" to good))
        assertEquals(2, pres.slides.size)
        assertEquals("", pres.slides[0].title.text)
        assertEquals("ok", pres.slides[1].title.text)
        // An element left open (table cells whose txBody is never closed) is closed by the relaxed
        // re-parse; the table and everything after it still read correctly.
        val unclosed = """<p:sld xmlns:p="$P" xmlns:a="$A"><p:cSld><p:spTree>
            <p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="4" name="T"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="0" y="0"/><a:ext cx="6096000" cy="1371600"/></p:xfrm>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tblPr firstRow="1"/><a:tblGrid><a:gridCol w="1"/><a:gridCol w="1"/></a:tblGrid>
            <a:tr h="1"><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>a</a:t></a:r></a:p><a:tcPr/></a:tc><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>b</a:t></a:r></a:p><a:tcPr/></a:tc></a:tr>
            <a:tr h="1"><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>c</a:t></a:r></a:p><a:tcPr/></a:tc><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>d</a:t></a:r></a:p><a:tcPr/></a:tc></a:tr>
            </a:tbl></a:graphicData></a:graphic></p:graphicFrame>
            <p:sp><p:nvSpPr><p:cNvPr id="2" name="t"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>after</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        val t = PptxReader.read(xmlZip("ppt/slides/slide1.xml" to unclosed)).slides[0]
        assertEquals("after", t.title.text)
        val tbl = t.tables.single()
        assertEquals(2, tbl.rows); assertEquals(2, tbl.cols)
        assertEquals(listOf("a", "b", "c", "d"), tbl.cells.flatten().map { it.text })
    }

    @Test fun largeSheetStreams() {
        // 40k cells (200 rows × 200 cols) with shared strings: streamed cells + strings, no per-part DOM.
        val rows = 200
        val cols = 200
        val sst = buildString {
            append("<sst xmlns=\"$SS\">")
            repeat(cols) { append("<si><t>h").append(it).append("</t></si>") }
            append("</sst>")
        }
        val sheet = buildString {
            append("<worksheet xmlns=\"$SS\"><sheetData>")
            for (r in 1..rows) {
                append("<row r=\"").append(r).append("\">")
                for (c in 0 until cols) {
                    val ref = Workbook.colLabel(c) + r
                    if (r == 1) append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(c).append("</v></c>")
                    else append("<c r=\"").append(ref).append("\"><v>").append(r * c).append("</v></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val wb = XlsxReader.read(xmlZip("xl/sharedStrings.xml" to sst, "xl/worksheets/sheet1.xml" to sheet))
        val s = wb.sheets[0]
        assertEquals("h0", s.cell(0, 0)?.text)
        assertEquals("h199", s.cell(0, 199)?.text)
        assertEquals((rows * 199).toString(), s.cell(rows - 1, 199)?.text)
        assertEquals(rows * cols - cols, s.cells.values.count { it.text.toIntOrNull() != null })
        assertEquals(rows - 1 + 4, s.rowCount) // last zero-based row + 4
        assertEquals(cols + 1, s.colCount)
    }
}
