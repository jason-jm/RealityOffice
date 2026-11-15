package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Excel format codes kept in OpenDocument spreadsheets: written as ODF number styles and read back as the same code. */
class NumberStylesTest {

    /** Codes that come back exactly as they went. */
    private val exact = listOf(
        "yyyy\"年\"m\"月\"d\"日\"", "dddd, mmmm d, yyyy", "#,##0.00;[Red]-#,##0.00", "0.0,,\"M\"", "[h]:mm:ss", "# ?/?",
        "0.00E+00", "[DBNum2][$-804]General", "@", "\"¥\"#,##0.00;[Red]\"¥\"-#,##0.00", "上午/下午h\"时\"mm\"分\"", "000-0000",
        "[>=1000000]0.0,,\"M\";[>=1000]0.0,\"K\";0", "0;-0;\"zero\"", "[$-804]yyyy\"年\"mmmm", "mmm d", "h:mm AM/PM", "[mm]:ss",
        "hh:mm:ss.00", "yyyy-mm-dd hh:mm:ss", "#,##0.0,\"千\"", "0.000E+00", "[Blue]0;[Red]-0;[Green]0", "[Color10]0.00",
        "[DBNum1][$-804]0", "aaaa", "dddd", "[$-409]mmmm d, yyyy", "# ??/??", "# ?/8", "000000", "0.00;[Red]-0.00;\"零\";\"备注：\"@",
        "yyyy\"年\"m\"月\"d\"日\";@", "0.00;[Red]-0.00;@",
    )

    /** Codes that come back spelled another way but show every value the same. */
    private val equivalent = listOf(
        "#,##0.00_);[Red](#,##0.00)", "@\" 元\"", "\"Tel: \"000-0000", "[$-804]aaaa", "[$-804]dddd", "0.00;-0.00;0.00;\"text: \"@",
    )

    private val values = listOf(-1234.5, 0.0, 0.5, 46294.75, 1234567.891)

    private fun workbookOf(codes: List<String>): Workbook = Workbook.blank().also { wb ->
        val sheet = wb.sheets[0]
        codes.forEachIndexed { i, code ->
            sheet.ensure(i, 0).text = code
            sheet.ensure(i, 1).apply { text = if (FormatCode.isText(code)) "00123" else "46294.75"; formatCode = code; numberFormat = FormatCode.category(code) }
        }
    }

    private fun throughOds(wb: Workbook): Workbook {
        val bos = ByteArrayOutputStream()
        OdsWriter.write(wb, bos)
        return OdsReader.read(bos.toByteArray())
    }

    @Test
    fun codesComeBackFromOpenDocument() {
        val back = throughOds(workbookOf(exact)).sheets[0]
        exact.forEachIndexed { i, code ->
            val cell = back.cell(i, 1)!!
            assertEquals(code, cell.formatCode)
            assertEquals("the value is kept too", if (FormatCode.isText(code)) "00123" else "46294.75", cell.text)
        }
        val same = throughOds(workbookOf(equivalent)).sheets[0]
        equivalent.forEachIndexed { i, code ->
            val kept = same.cell(i, 1)!!.formatCode!!
            for (v in values) assertEquals("$code as $kept, showing $v", FormatCode.format(v, code), FormatCode.format(v, kept))
        }
    }

    @Test
    fun plainCodesStayPlainFormats() {
        val plain = listOf("0.00" to (NumberFormat.NUMBER2 to 2), "#,##0" to (NumberFormat.THOUSANDS to 0), "0%" to (NumberFormat.PERCENT to 0),
            "\"¥\"#,##0.00" to (NumberFormat.CURRENCY_CNY to 2))
        val back = throughOds(workbookOf(plain.map { it.first })).sheets[0]
        plain.forEachIndexed { i, (code, fmt) ->
            val cell = back.cell(i, 1)!!
            assertNull(code, cell.formatCode)
            assertEquals(code, fmt, cell.numberFormat to cell.decimals)
        }
    }

    @Test
    fun stylesAreWrittenAsLibreOfficeWritesThem() {
        val bos = ByteArrayOutputStream()
        OdsWriter.write(workbookOf(listOf("#,##0.00;[Red]-#,##0.00", "[DBNum1][$-804]0", "[h]:mm:ss", "yyyy\"年\"m\"月\"d\"日\"")), bos)
        val content = entry(bos.toByteArray(), "content.xml")
        // The negative section is the style itself, the positive one a volatile style its map picks.
        assertTrue(content.contains("""<number:number-style style:name="Nc1P0" style:volatile="true">"""))
        assertTrue(content.contains("""<style:map style:condition="value()&gt;=0" style:apply-style-name="Nc1P0"/>"""))
        assertTrue(content.contains("""<style:text-properties fo:color="#FF0000"/><number:text>-</number:text>"""))
        // Chinese numerals with units: LibreOffice's "long" transliteration of 一.
        assertTrue(content.contains("""number:transliteration-format="一" number:transliteration-language="zh" number:transliteration-country="CN" number:transliteration-style="long""""))
        assertTrue(content.contains("""<number:time-style style:name="Nc3" number:truncate-on-overflow="false">"""))
        assertTrue(content.contains("""<number:year number:style="long"/><number:text>年</number:text><number:month/><number:text>月</number:text><number:day/><number:text>日</number:text>"""))
        // Beside each value, the text it shows.
        assertTrue(content.contains("<text:p>2026年9月29日</text:p>"))
    }

    @Test
    fun libreOfficeDatesAndTimesBecomeSerials() {
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0">
<office:automatic-styles>
<number:date-style style:name="N37" number:automatic-order="true"><number:month/><number:text>/</number:text><number:day/><number:text>/</number:text><number:year/></number:date-style>
<number:time-style style:name="N46" number:truncate-on-overflow="false"><number:hours/><number:text>:</number:text><number:minutes number:style="long"/></number:time-style>
<number:time-style style:name="N136"><number:am-pm/><number:hours/><number:text>时</number:text><number:minutes number:style="long"/><number:text>分</number:text></number:time-style>
<number:date-style style:name="N10036" number:language="zh" number:country="CN"><number:year number:style="long"/><number:text>年</number:text><number:month number:textual="true"/></number:date-style>
<number:number-style style:name="N108P0" style:volatile="true" number:language="en" number:country="US"><number:number number:decimal-places="2" number:min-decimal-places="2" number:min-integer-digits="1" number:grouping="true"/><number:text loext:blank-width-char=")"> </number:text></number:number-style>
<number:number-style style:name="N108" number:language="en" number:country="US"><style:text-properties fo:color="#ff0000"/><number:text>(</number:text><number:number number:decimal-places="2" number:min-decimal-places="2" number:min-integer-digits="1" number:grouping="true"/><number:text>)</number:text><style:map style:condition="value()&gt;=0" style:apply-style-name="N108P0"/></number:number-style>
<style:style style:name="ce1" style:family="table-cell" style:data-style-name="N37"/>
<style:style style:name="ce2" style:family="table-cell" style:data-style-name="N46"/>
<style:style style:name="ce3" style:family="table-cell" style:data-style-name="N10036"/>
<style:style style:name="ce4" style:family="table-cell" style:data-style-name="N108"/>
<style:style style:name="ce5" style:family="table-cell" style:data-style-name="N136"/>
</office:automatic-styles>
<office:body><office:spreadsheet><table:table table:name="Sheet1"><table:table-row>
<table:table-cell table:style-name="ce1" office:value-type="date" office:date-value="2026-09-29"><text:p>09/29/26</text:p></table:table-cell>
<table:table-cell table:style-name="ce2" office:value-type="time" office:time-value="PT36H30M00S"><text:p>36:30</text:p></table:table-cell>
<table:table-cell table:style-name="ce3" office:value-type="date" office:date-value="2026-09-29T18:00:00"><text:p>2026年9月</text:p></table:table-cell>
<table:table-cell table:style-name="ce4" office:value-type="float" office:value="-1234.5"><text:p>(1,234.50)</text:p></table:table-cell>
<table:table-cell office:value-type="boolean" office:boolean-value="true"><text:p>TRUE</text:p></table:table-cell>
<table:table-cell table:style-name="ce5" office:value-type="time" office:time-value="PT18H00M00S"><text:p>下午6时00分</text:p></table:table-cell>
</table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"""
        val sheet = OdsReader.read(odsOf(content)).sheets[0]
        assertEquals("46294", sheet.cell(0, 0)!!.text)
        assertEquals("m/d/yy", sheet.cell(0, 0)!!.formatCode)
        assertEquals("1.52083333333333", sheet.cell(0, 1)!!.text)
        assertEquals("[h]:mm", sheet.cell(0, 1)!!.formatCode)
        assertEquals("46294.75", sheet.cell(0, 2)!!.text)
        assertEquals("[$-804]yyyy\"年\"mmm", sheet.cell(0, 2)!!.formatCode)
        assertEquals("#,##0.00_);[Red](#,##0.00)", sheet.cell(0, 3)!!.formatCode)
        assertEquals("(1,234.50)", FormatCode.format(-1234.5, sheet.cell(0, 3)!!.formatCode!!).text)
        assertEquals("TRUE", sheet.cell(0, 4)!!.text)
        // LibreOffice's own Chinese time format names no language: its text says it.
        assertEquals("上午/下午h\"时\"mm\"分\"", sheet.cell(0, 5)!!.formatCode)
        assertEquals("0.75", sheet.cell(0, 5)!!.text)
    }

    /**
     * Opt-in (`-Daero.codesDir=<dir>`): the codes in an Excel workbook and in an OpenDocument one
     * written here, for LibreOffice to convert each into the other.
     */
    @Test
    fun writeCodeWorkbooks() {
        val dir = System.getProperty("aero.codesDir")
        assumeTrue("set -Daero.codesDir to write the workbooks", dir != null)
        val out = File(dir!!).apply { mkdirs() }
        val wb = workbookOf(exact + equivalent).also { it.sheets[0].colWidths[0] = 3.2f; it.sheets[0].colWidths[1] = 3.4f }
        File(out, "codes.xlsx").outputStream().use { XlsxWriter.write(wb, it) }
        File(out, "codes.ods").outputStream().use { OdsWriter.write(wb, it) }
        File(out, "codes.txt").writeText((exact + equivalent).joinToString("\n"))
    }

    /**
     * Opt-in, after [writeCodeWorkbooks] and LibreOffice's conversions of its files (`lo_ods/codes.ods`
     * from the Excel one, `lo_xlsx/codes.xlsx` from the OpenDocument one): each code as it came back
     * through LibreOffice either way, and whether it shows the sample values the same — `report.txt`.
     */
    @Test
    fun compareLibreOfficeConversions() {
        val dir = System.getProperty("aero.codesDir")?.let { File(it) }
        assumeTrue("set -Daero.codesDir after converting the workbooks", dir != null && File(dir, "lo_ods/codes.ods").exists())
        val codes = File(dir!!, "codes.txt").readLines()
        val fromOds = OdsReader.read(File(dir, "lo_ods/codes.ods").readBytes()).sheets[0]
        val fromXlsx = com.therealityteam.aeropages.format.xlsx.XlsxReader.read(File(dir, "lo_xlsx/codes.xlsx").readBytes()).sheets[0]
        val report = codes.mapIndexed { i, code ->
            fun look(c: com.therealityteam.aeropages.model.Cell?): String {
                val kept = c?.formatCode ?: return "plain ${c?.numberFormat}/${c?.decimals}"
                val same = values.all { v -> FormatCode.format(v, code) == FormatCode.format(v, kept) }
                return (if (kept == code) "=" else if (same) "≈ $kept" else "≠ $kept") + " [${c.text}]"
            }
            "$code\n   LO ods → us: ${look(fromOds.cell(i, 1))}\n   our ods → LO → xlsx: ${look(fromXlsx.cell(i, 1))}"
        }
        File(dir, "report.txt").writeText(report.joinToString("\n"))
    }

    private fun odsOf(content: String): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun entry(bytes: ByteArray, name: String): String {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = zin.nextEntry ?: break; if (e.name == name) return zin.readBytes().decodeToString() }
        error("no $name")
    }
}
