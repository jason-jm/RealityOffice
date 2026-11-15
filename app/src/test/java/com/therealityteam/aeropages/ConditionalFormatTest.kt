package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.CondEval
import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondStyle
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DatePeriod
import com.therealityteam.aeropages.model.IconSetKind
import com.therealityteam.aeropages.model.ScaleKind
import com.therealityteam.aeropages.model.ScalePoint
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * Conditional formats as Excel works them out: every kind of rule — values compared, text, blanks,
 * errors, duplicates, ranks, averages, dates, formulas — their looks combined in order, scales (colour,
 * bar, icon), and their way through xlsx.
 */
class ConditionalFormatTest {

    private val red = CondStyle(fill = 0xFFFFC7CE, font = 0xFF9C0006)

    /** A sheet with column A holding [values] (a string starting "=" is a formula). */
    private fun sheet(vararg values: String): Sheet {
        val s = Sheet()
        values.forEachIndexed { i, v -> if (v.isNotEmpty()) s.ensure(i, 0).text = v }
        return s
    }

    private fun eval(s: Sheet, today: LocalDate = LocalDate.of(2026, 10, 7)) = CondEval(s, object : CondEval.Values {
        override fun raw(row: Int, col: Int) = FormulaEngine.display(s, row, col)
        override fun evaluate(formula: String, row: Int, col: Int) = FormulaEngine.evaluateText(s, formula, at = row to col)
    }, today)

    /** Which of column A's first [n] rows the sheet's rules give a look. */
    private fun hits(s: Sheet, n: Int, today: LocalDate = LocalDate.of(2026, 10, 7)): List<Int> {
        val e = eval(s, today)
        return (0 until n).filter { e.look(it, 0) != null }
    }

    private fun rule(type: CondType, r2: Int, v1: Double = 0.0, v2: Double = 0.0, text: String = "", formula1: String? = null, percent: Boolean = false,
                     period: DatePeriod? = null, style: CondStyle = red, stop: Boolean = false) =
        CondFormat(0, 0, r2, 0, type, v1, v2, formula1 = formula1, text = text, style = style, percent = percent, period = period, stopIfTrue = stop)

    @Test
    fun valuesAreComparedAsExcelComparesThem() {
        val s = sheet("3", "7", "", "abc", "5")
        s.condFormats.add(rule(CondType.LESS, 4, 5.0))
        // A blank cell counts as 0 against a number; text is above every number.
        assertEquals(listOf(0, 2), hits(s, 5))
        s.condFormats[0] = rule(CondType.GREATER, 4, 5.0)
        assertEquals(listOf(1, 3), hits(s, 5))
        s.condFormats[0] = rule(CondType.BETWEEN, 4, 7.0, 4.0)   // the bounds either way round
        assertEquals(listOf(1, 4), hits(s, 5))
        s.condFormats[0] = rule(CondType.NOT_BETWEEN, 4, 4.0, 7.0)
        assertEquals(listOf(0, 2, 3), hits(s, 5))
        s.condFormats[0] = rule(CondType.GREATER_EQUAL, 4, 5.0)
        assertEquals(listOf(1, 3, 4), hits(s, 5))
        // A formula for the value: worked out at each cell.
        s.ensure(0, 1).text = "6"
        s.condFormats[0] = rule(CondType.LESS, 4, formula1 = "\$B\$1")
        assertEquals(listOf(0, 2, 4), hits(s, 5))
        s.condFormats[0] = rule(CondType.EQUAL, 4, formula1 = "\"ABC\"")
        assertEquals("text in any case", listOf(3), hits(s, 5))
    }

    @Test
    fun textBlanksAndErrors() {
        val s = sheet("Apple pie", "banana", "", "  ", "=1/0", "#hashtag", "pineapple")
        s.condFormats.add(rule(CondType.TEXT_CONTAINS, 6, text = "APPLE"))
        assertEquals(listOf(0, 6), hits(s, 7))
        s.condFormats[0] = rule(CondType.TEXT_BEGINS, 6, text = "pine")
        assertEquals(listOf(6), hits(s, 7))
        s.condFormats[0] = rule(CondType.TEXT_ENDS, 6, text = "NA")
        assertEquals(listOf(1), hits(s, 7))
        s.condFormats[0] = rule(CondType.BLANKS, 6)
        assertEquals("spaces count as blank", listOf(2, 3), hits(s, 7))
        s.condFormats[0] = rule(CondType.ERRORS, 6)
        assertEquals("a formula's error, not text that starts with #", listOf(4), hits(s, 7))
    }

    @Test
    fun duplicatesRanksAndAverages() {
        val s = sheet("10", "20", "10", "30", "Pear", "pear", "40", "")
        s.condFormats.add(rule(CondType.DUPLICATES, 7))
        assertEquals("in any case; blanks are no value", listOf(0, 2, 4, 5), hits(s, 8))
        s.condFormats[0] = rule(CondType.UNIQUES, 7)
        assertEquals(listOf(1, 3, 6), hits(s, 8))
        s.condFormats[0] = rule(CondType.TOP, 7, 2.0)
        assertEquals(listOf(3, 6), hits(s, 8))
        s.condFormats[0] = rule(CondType.BOTTOM, 7, 2.0)
        assertEquals("ties with the N-th count", listOf(0, 2), hits(s, 8))
        s.condFormats[0] = rule(CondType.TOP, 7, 40.0, percent = true)   // 40% of 5 numbers: 2
        assertEquals(listOf(3, 6), hits(s, 8))
        s.condFormats[0] = rule(CondType.ABOVE_AVERAGE, 7)   // average 22
        assertEquals(listOf(3, 6), hits(s, 8))
        s.condFormats[0] = rule(CondType.BELOW_AVERAGE, 7)
        assertEquals(listOf(0, 1, 2), hits(s, 8))
    }

    @Test
    fun datesAroundToday() {
        // Wednesday 2026-10-07: serials of the days around it.
        fun serial(d: LocalDate) = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d).toString()
        val today = LocalDate.of(2026, 10, 7)
        val s = sheet(serial(today), serial(today.minusDays(1)), serial(today.minusDays(6)), serial(today.minusDays(7)),
            serial(LocalDate.of(2026, 10, 4)), serial(LocalDate.of(2026, 10, 3)), serial(LocalDate.of(2026, 9, 30)), serial(LocalDate.of(2026, 11, 1)))
        fun period(p: DatePeriod): List<Int> { s.condFormats.clear(); s.condFormats.add(rule(CondType.DATE, 7, period = p)); return hits(s, 8, today) }
        assertEquals(listOf(0), period(DatePeriod.TODAY))
        assertEquals(listOf(1), period(DatePeriod.YESTERDAY))
        assertEquals("1 to 7 October", listOf(0, 1, 2, 4, 5), period(DatePeriod.LAST_7_DAYS))
        // Weeks start on Sunday: 4 October to 10 October.
        assertEquals(listOf(0, 1, 4), period(DatePeriod.THIS_WEEK))
        assertEquals("27 September to 3 October", listOf(2, 3, 5, 6), period(DatePeriod.LAST_WEEK))
        assertEquals(listOf(0, 1, 2, 4, 5), period(DatePeriod.THIS_MONTH))
        assertEquals(listOf(3, 6), period(DatePeriod.LAST_MONTH))
        assertEquals(listOf(7), period(DatePeriod.NEXT_MONTH))
    }

    @Test
    fun formulasLooksInOrderAndStopIfTrue() {
        val s = sheet("1", "2", "3", "4")
        listOf("Y", "N", "Y", "N").forEachIndexed { i, v -> s.ensure(i, 1).text = v }
        // A formula written for the range's top-left cell, its relative references moving down with each cell.
        s.condFormats.add(rule(CondType.FORMULA, 3, formula1 = "\$B1=\"Y\"", style = CondStyle(fill = 0xFF00FF00)))
        assertEquals(listOf(0, 2), hits(s, 4))
        // A second rule's look adds what the first does not set; where both set the fill, the first wins.
        s.condFormats.add(rule(CondType.GREATER, 3, 1.0, style = CondStyle(fill = 0xFFFF0000, bold = true)))
        val e = eval(s)
        assertEquals(CondStyle(fill = 0xFF00FF00, bold = true), e.look(2, 0)!!.style)
        assertEquals(CondStyle(fill = 0xFFFF0000, bold = true), e.look(1, 0)!!.style)
        assertNull(e.look(0, 0)!!.style.bold)
        // ROW() and COLUMN() count from the cell the rule is worked out for (banding every other row).
        val banded = sheet("a", "b", "c", "d")
        banded.condFormats.add(rule(CondType.FORMULA, 3, formula1 = "MOD(ROW(),2)=0"))
        assertEquals(listOf(1, 3), hits(banded, 4))
        // Stop if true: rules after one that matches are not tried.
        s.condFormats[0] = s.condFormats[0].copy(stopIfTrue = true)
        assertNull(eval(s).look(2, 0)!!.style.bold)
    }

    @Test
    fun scales() {
        val s = sheet("0", "25", "50", "75", "100")
        s.condFormats.add(CondFormat(0, 0, 4, 0, CondType.COLOR_SCALE, stops = listOf(
            ScalePoint(ScaleKind.MIN) to 0xFFF8696B, ScalePoint(ScaleKind.PERCENTILE, 50.0) to 0xFFFFEB84, ScalePoint(ScaleKind.MAX) to 0xFF63BE7B)))
        val e = eval(s)
        assertEquals(0xFFF8696B, e.look(0, 0)!!.style.fill)
        assertEquals(0xFFFFEB84, e.look(2, 0)!!.style.fill)
        assertEquals(0xFF63BE7B, e.look(4, 0)!!.style.fill)
        assertEquals(CondEval.blend(0xFFF8696B, 0xFFFFEB84, 0.5f), e.look(1, 0)!!.style.fill)
        // Data bars: within their shortest (10%) and longest (90%) lengths, Excel 2007's defaults.
        s.condFormats[0] = CondFormat(0, 0, 4, 0, CondType.DATA_BAR, color = 0xFF638EC6)
        val bars = eval(s)
        assertEquals(0.1f, bars.look(0, 0)!!.bar!!.fraction, 1e-4f)
        assertEquals(0.5f, bars.look(2, 0)!!.bar!!.fraction, 1e-4f)
        assertEquals(0.9f, bars.look(4, 0)!!.bar!!.fraction, 1e-4f)
        // Icons: thirds of the way by default; reversed; the value hidden when the set shows alone.
        s.condFormats[0] = CondFormat(0, 0, 4, 0, CondType.ICON_SET, icons = IconSetKind.ARROWS_3, showValue = false)
        val icons = eval(s)
        assertEquals(listOf(0, 0, 1, 2, 2), (0..4).map { icons.look(it, 0)!!.icon!!.index })
        assertTrue(icons.look(0, 0)!!.hideValue)
        s.condFormats[0] = s.condFormats[0].copy(reverse = true)
        assertEquals(listOf(2, 2, 1, 0, 0), (0..4).map { eval(s).look(it, 0)!!.icon!!.index })
    }

    /** One of every kind, as an xlsx file reads them back. */
    private fun everyKind(): List<CondFormat> {
        val st = CondStyle(fill = 0xFFFFC7CE, font = 0xFF9C0006, bold = true, italic = false, underline = true, strike = false)
        val plain = CondStyle(fill = 0xFFC6EFCE)
        return listOf(
            CondFormat(0, 0, 9, 0, CondType.GREATER, 5.0, style = st),
            CondFormat(0, 0, 9, 0, CondType.NOT_BETWEEN, 1.0, 3.0, style = plain, stopIfTrue = true),
            CondFormat(0, 0, 9, 0, CondType.LESS_EQUAL, formula1 = "\$C\$1", style = plain),
            CondFormat(0, 1, 9, 1, CondType.TEXT_CONTAINS, text = "a\"b", style = plain),
            CondFormat(0, 1, 9, 1, CondType.TEXT_ENDS, text = "z", style = plain),
            CondFormat(0, 1, 9, 1, CondType.BLANKS, style = plain),
            CondFormat(0, 1, 9, 1, CondType.NO_ERRORS, style = plain),
            CondFormat(0, 2, 9, 2, CondType.DUPLICATES, style = plain),
            CondFormat(0, 2, 9, 2, CondType.BOTTOM, 15.0, percent = true, style = plain),
            CondFormat(0, 2, 9, 2, CondType.BELOW_AVERAGE, orEqual = true, style = plain),
            CondFormat(0, 3, 9, 3, CondType.DATE, period = DatePeriod.LAST_WEEK, style = plain),
            CondFormat(0, 3, 9, 3, CondType.FORMULA, formula1 = "MOD(ROW(),2)=0", style = plain),
            CondFormat(0, 4, 9, 4, CondType.COLOR_SCALE, color = 0xFF63BE7B, color2 = 0xFFF8696B),
            CondFormat(0, 4, 9, 4, CondType.COLOR_SCALE, color = 0xFF63BE7B, color2 = 0xFFF8696B, stops = listOf(
                ScalePoint(ScaleKind.NUMBER, 0.0) to 0xFFF8696B, ScalePoint(ScaleKind.PERCENT, 50.0) to 0xFFFFFFFF, ScalePoint(ScaleKind.FORMULA, formula = "\$C\$2") to 0xFF63BE7B)),
            CondFormat(0, 5, 9, 5, CondType.DATA_BAR, color = 0xFF638EC6, barMin = 0, barMax = 100, showValue = false),
            CondFormat(0, 5, 9, 5, CondType.ICON_SET, icons = IconSetKind.RATING_5, reverse = true),
            CondFormat(0, 5, 9, 5, CondType.ICON_SET, icons = IconSetKind.FLAGS_3,
                iconPoints = listOf(ScalePoint(ScaleKind.NUMBER, 0.0), ScalePoint(ScaleKind.NUMBER, 10.0, gte = false), ScalePoint(ScaleKind.NUMBER, 20.0))),
        )
    }

    @Test
    fun everyKindGoesThroughXlsx() {
        val s = Sheet()
        s.ensure(0, 0).text = "1"
        s.condFormats.addAll(everyKind())
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(Workbook(mutableListOf(s)), it) }.toByteArray()
        val back = XlsxReader.read(bytes).sheets[0].condFormats
        // Rules come back in their order, each as it was (a scale's colour fields aside where its stops say it all).
        assertEquals(everyKind().size, back.size)
        everyKind().zip(back).forEachIndexed { i, (a, b) -> assertEquals("rule $i", a, b) }
        val xml = String(OfficeXml.unzip(ByteArrayInputStream(bytes))["xl/worksheets/sheet1.xml"]!!)
        // Excel's formulas beside the attribute-only rules (as Excel writes them, so older readers see them too).
        assertTrue(xml.contains("""<cfRule type="containsText" dxfId="1" priority="4" operator="containsText" text="a&quot;b"><formula>NOT(ISERROR(SEARCH(&quot;a&quot;&quot;b&quot;,B1)))</formula>"""))
        assertTrue(xml.contains("""timePeriod="lastWeek""""))
        val styles = String(OfficeXml.unzip(ByteArrayInputStream(bytes))["xl/styles.xml"]!!)
        assertTrue(styles.contains("""<dxf><font><b/><i val="0"/><strike val="0"/><u/><color rgb="FF9C0006"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/><bgColor rgb="FFFFC7CE"/></patternFill></fill></dxf>"""))
    }

    @Test
    fun excelsOwnFileReadsInPriorityOverEveryRange() {
        // Two blocks; the second's rule first in priority, over two ranges — its formula moved to the second's top-left.
        val sheetXml = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData>""" +
            """<conditionalFormatting sqref="A1:A5"><cfRule type="cellIs" dxfId="0" priority="2" operator="greaterThan"><formula>3</formula></cfRule></conditionalFormatting>""" +
            """<conditionalFormatting sqref="C1:C5 E3:E4"><cfRule type="expression" dxfId="0" priority="1"><formula>${'$'}B1&gt;C1</formula></cfRule>""" +
            """<cfRule type="dataBar" priority="3"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb="FF638EC6"/></dataBar>""" +
            """<extLst><ext uri="{B025F937-C7B1-47D3-B67F-A62EFF666E3E}"><x14:id xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main">{0001}</x14:id></ext></extLst></cfRule></conditionalFormatting>""" +
            """<extLst><ext uri="{78C0D931-6437-407d-A8EE-F0AAD7539E65}"><x14:conditionalFormattings xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main">""" +
            """<x14:conditionalFormatting><x14:cfRule type="dataBar" id="{0001}"><x14:dataBar minLength="0" maxLength="100" gradient="0"><x14:cfvo type="autoMin"/><x14:cfvo type="autoMax"/></x14:dataBar></x14:cfRule><xm:sqref>C1:C5 E3:E4</xm:sqref></x14:conditionalFormatting>""" +
            """<x14:conditionalFormatting><x14:cfRule type="iconSet" priority="4" id="{0002}"><x14:iconSet iconSet="3Stars"><x14:cfvo type="percent"><xm:f>0</xm:f></x14:cfvo><x14:cfvo type="percent"><xm:f>33</xm:f></x14:cfvo><x14:cfvo type="percent"><xm:f>67</xm:f></x14:cfvo></x14:iconSet></x14:cfRule><xm:sqref>G1:G5</xm:sqref></x14:conditionalFormatting>""" +
            """</x14:conditionalFormattings></ext></extLst></worksheet>"""
        val styles = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dxfs count="1"><dxf><font><color theme="5"/></font><fill><patternFill><bgColor indexed="10"/></patternFill></fill></dxf></dxfs></styleSheet>"""
        val wb = """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            mapOf("xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to rels, "xl/worksheets/sheet1.xml" to sheetXml, "xl/styles.xml" to styles).forEach { (n, b) ->
                z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry()
            }
        }
        val rules = XlsxReader.read(out.toByteArray()).sheets[0].condFormats
        assertEquals(listOf(CondType.FORMULA, CondType.FORMULA, CondType.GREATER, CondType.DATA_BAR, CondType.DATA_BAR, CondType.ICON_SET), rules.map { it.type })
        assertEquals("\$B1>C1", rules[0].formula1)
        assertEquals("moved to E3", "\$B3>E3", rules[1].formula1)
        assertEquals(listOf(2, 4), listOf(rules[1].r1, rules[1].c1))
        // Excel 2010's extension: its bars run the cell's whole width.
        assertEquals(0 to 100, rules[3].barMin to rules[3].barMax)
        assertEquals(IconSetKind.STARS_3, rules[5].icons)
        // The dxf's colours: indexed fill, a theme colour for the text (Office's default theme has none here: unset).
        assertEquals(0xFFFF0000, rules[2].style!!.fill)
        assertFalse(rules[2].look.fill == null)
    }

    @Test
    fun everyKindGoesThroughOds() {
        val s = Sheet(name = "Rules 1")
        s.ensure(0, 0).text = "1"
        s.condFormats.addAll(everyKind())
        val bytes = ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdsWriter.write(Workbook(mutableListOf(s)), it) }.toByteArray()
        val back = com.therealityteam.aeropages.format.odf.OdsReader.read(bytes).sheets[0].condFormats
        // What LibreOffice's form keeps: no Stop If True, no reversed icons or "above" thresholds, a blank test
        // as Excel's formula for one, and a look's traits only where it turns them on.
        fun kept(cf: CondFormat): CondFormat {
            val st = cf.style?.let { it.copy(bold = it.bold?.takeIf { b -> b }, italic = it.italic?.takeIf { b -> b }, underline = it.underline?.takeIf { b -> b }, strike = it.strike?.takeIf { b -> b }) }
            val base = cf.copy(stopIfTrue = false, reverse = false, style = st, iconPoints = cf.iconPoints.map { it.copy(gte = true) })
            return if (cf.type == CondType.BLANKS) base.copy(type = CondType.FORMULA, formula1 = "LEN(TRIM(" + com.therealityteam.aeropages.model.A1.ref(cf.r1, cf.c1) + "))=0") else base
        }
        assertEquals(everyKind().size, back.size)
        everyKind().map(::kept).zip(back).forEachIndexed { i, (a, b) -> assertEquals("rule $i", a, b) }
        val content = String(OfficeXml.unzip(ByteArrayInputStream(bytes))["content.xml"]!!)
        assertTrue(content.contains("""<calcext:conditional-format calcext:target-range-address="'Rules 1'.A1:'Rules 1'.A10"><calcext:condition calcext:apply-style-name="AeroCond1" calcext:value="&gt;5" calcext:base-cell-address="'Rules 1'.A1"/>"""))
        assertTrue(content.contains("""calcext:value="formula-is(MOD(ROW();2)=0)""""))
        val styles = String(OfficeXml.unzip(ByteArrayInputStream(bytes))["styles.xml"]!!)
        assertTrue(styles.contains("""<style:style style:name="AeroCond1" style:family="table-cell"><style:table-cell-properties fo:background-color="#FFC7CE"/>"""))
    }

    @Test
    fun libreOfficesOwnRulesRead() {
        // As LibreOffice writes them: display names for its styles (ConditionalStyle_5f_1), a formula relative to its base cell.
        val content = """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:calcext="urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0" """ +
            """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:office2="x"><office:body><office:spreadsheet>""" +
            """<table:table table:name="S"><table:table-row><table:table-cell office:value-type="float" office:value="1"><text:p>1</text:p></table:table-cell></table:table-row>""" +
            """<calcext:conditional-formats><calcext:conditional-format calcext:target-range-address="S.A2:S.A13 S.C2:S.C3">""" +
            """<calcext:condition calcext:apply-style-name="ConditionalStyle_1" calcext:value="between([.${'$'}D${'$'}1];&quot;z&quot;)" calcext:base-cell-address="S.A2"/>""" +
            """<calcext:condition calcext:apply-style-name="ConditionalStyle_1" calcext:value="formula-is([.B2]&gt;0)" calcext:base-cell-address="S.A2"/>""" +
            """<calcext:date-is calcext:style="ConditionalStyle_1" calcext:date="this-month"/>""" +
            """<calcext:color-scale><calcext:color-scale-entry calcext:value="0" calcext:type="minimum" calcext:color="#f8696b"/><calcext:color-scale-entry calcext:value="0" calcext:type="maximum" calcext:color="#63be7b"/></calcext:color-scale>""" +
            """</calcext:conditional-format></calcext:conditional-formats></table:table></office:spreadsheet></office:body></office:document-content>"""
        val styles = """<?xml version="1.0" encoding="UTF-8"?><office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"><office:styles>""" +
            """<style:style style:name="ConditionalStyle_5f_1" style:display-name="ConditionalStyle_1" style:family="table-cell" style:parent-style-name="Default">""" +
            """<style:table-cell-properties fo:background-color="#ffc7ce"/><style:text-properties fo:color="#9c0006" fo:font-weight="bold"/></style:style></office:styles></office:document-styles>"""
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to content, "styles.xml" to styles).forEach { (n, b) ->
                z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry()
            }
        }
        val rules = com.therealityteam.aeropages.format.odf.OdsReader.read(out.toByteArray()).sheets[0].condFormats
        assertEquals(8, rules.size)
        val look = CondStyle(fill = 0xFFFFC7CE, font = 0xFF9C0006, bold = true)
        assertEquals(CondFormat(1, 0, 12, 0, CondType.BETWEEN, formula1 = "\$D\$1", formula2 = "\"z\"", style = look), rules[0])
        assertEquals("the range's own rule, moved to C2", CondFormat(1, 2, 2, 2, CondType.FORMULA, formula1 = "D2>0", style = look), rules[3])
        assertEquals(CondType.DATE, rules[4].type)
        assertEquals(DatePeriod.THIS_MONTH, rules[4].period)
        assertEquals(CondFormat(1, 0, 12, 0, CondType.COLOR_SCALE, color = 0xFF63BE7B, color2 = 0xFFF8696B), rules[6])
    }

    @Test
    fun theEditorManagesRulesWithUndo() {
        val e = com.therealityteam.aeropages.editor.SheetEditor()
        listOf("3", "9", "12").forEachIndexed { i, v -> e.sheet.ensure(i, 0).text = v }
        e.select(0, 0); e.extendSelection(2, 0)
        e.addCondRule(CondFormat(0, 0, 0, 0, CondType.GREATER, 5.0, style = red))
        e.addCondRule(CondFormat(0, 0, 0, 0, CondType.GREATER, 10.0, style = CondStyle(fill = 0xFF00FF00)))
        e.select(5, 5)
        e.addCondRule(CondFormat(0, 0, 0, 0, CondType.DUPLICATES, style = red))
        // The rules over A1:A3 come first; the first that sets the fill wins.
        e.select(0, 0); e.extendSelection(2, 0)
        assertEquals(listOf(0, 1), e.condRules(false).map { it.first })
        assertEquals(3, e.condRules(true).size)
        assertEquals(0xFFFFC7CE, e.conditionalFill(2, 0))
        // Moved up, the second rule's green wins at 12; undone, the red again.
        e.moveCondRule(1, -1)
        assertEquals(0xFF00FF00, e.conditionalFill(2, 0))
        assertEquals(0xFFFFC7CE, e.conditionalFill(1, 0))
        e.undo()
        assertEquals(0xFFFFC7CE, e.conditionalFill(2, 0))
        // Stop If True on the first: the second (and the duplicate rule's place) no longer tried where it matches.
        e.setCondRuleStop(0, true)
        assertTrue(e.sheet.condFormats[0].stopIfTrue)
        e.removeCondRule(0)
        assertEquals(0xFF00FF00, e.conditionalFill(2, 0))
        assertNull(e.conditionalFill(1, 0))
        e.undo()
        assertEquals(3, e.sheet.condFormats.size)
        e.clearSheetCondFormats()
        assertTrue(e.sheet.condFormats.isEmpty())
        e.undo()
        assertEquals(3, e.sheet.condFormats.size)
    }
}
