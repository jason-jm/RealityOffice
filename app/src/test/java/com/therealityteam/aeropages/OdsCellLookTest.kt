package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.VAlign
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** How cells look in an OpenDocument spreadsheet: styles its columns lend, empty cells with a look of their own. */
class OdsCellLookTest {

    private fun odsOf(content: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    /** A sheet as LibreOffice writes one: column B's cells share a style it names once, on the column. */
    private val libreOffice = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
<office:automatic-styles>
<number:date-style style:name="N41"><number:year number:style="long"/><number:text>年</number:text><number:month/><number:text>月</number:text><number:day/><number:text>日</number:text></number:date-style>
<style:style style:name="ce1" style:family="table-cell" style:data-style-name="N41"><style:table-cell-properties fo:background-color="#ffff00"/><style:text-properties fo:font-weight="bold"/></style:style>
<style:style style:name="ce2" style:family="table-cell"><style:table-cell-properties fo:background-color="#dae3f3" fo:border="0.74pt solid #000000"/></style:style>
<style:style style:name="ce3" style:family="table-cell"><style:table-cell-properties style:vertical-align="bottom"/></style:style>
</office:automatic-styles>
<office:body><office:spreadsheet><table:table table:name="Sheet1">
<table:table-column table:default-cell-style-name="ce3"/><table:table-column table:default-cell-style-name="ce1"/><table:table-column table:number-columns-repeated="16382"/>
<table:table-row><table:table-cell office:value-type="string"><text:p>Due</text:p></table:table-cell><table:table-cell office:value-type="date" office:date-value="2026-09-29"><text:p>2026年9月29日</text:p></table:table-cell><table:table-cell table:style-name="ce3" table:number-columns-repeated="16382"/></table:table-row>
<table:table-row><table:table-cell table:style-name="ce2" table:number-columns-repeated="3"/><table:table-cell table:style-name="ce3" table:number-columns-repeated="16381"/></table:table-row>
<table:table-row><table:table-cell office:value-type="string" office:string-value="00123"><text:p>00123 元</text:p></table:table-cell></table:table-row>
<table:table-row table:number-rows-repeated="1048573"><table:table-cell table:style-name="ce3" table:number-columns-repeated="16384"/></table:table-row>
</table:table></office:spreadsheet></office:body></office:document-content>"""

    @Test
    fun cellsTakeTheStyleTheirColumnLends() {
        val sheet = OdsReader.read(odsOf(libreOffice)).sheets[0]
        val due = sheet.cell(0, 1)!!
        assertEquals("the date as the serial the sheet keeps", "46294", due.text)
        assertEquals("yyyy\"年\"m\"月\"d\"日\"", due.formatCode)
        assertEquals(NumberFormat.DATE, due.numberFormat)
        assertTrue(due.format.bold); assertEquals(0xFFFFFF00, due.format.highlight)
        assertEquals("column A's style: bottom aligned", VAlign.BOTTOM, sheet.cell(0, 0)!!.vAlign)
        assertEquals("what was typed, not what the format shows", "00123", sheet.cell(2, 0)!!.text)
    }

    @Test
    fun emptyCellsKeepTheirLookButPaddingIsNotCells() {
        val sheet = OdsReader.read(odsOf(libreOffice)).sheets[0]
        for (c in 0..2) {
            val box = sheet.cell(1, c)
            assertTrue("B$c keeps its fill and borders", box != null && box.text.isEmpty() && box.format.highlight == 0xFFDAE3F3 && box.borderTop != null)
        }
        assertNull("a style that only aligns makes no cells", sheet.cell(1, 3))
        assertNull(sheet.cell(0, 5))
        assertTrue("the padding rows stay out of the sheet", sheet.rowCount < 100)
    }

    @Test
    fun emptyFormattedCellsSurviveASaveInOpenDocument() {
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        s.ensure(0, 0).text = "Header"
        // A form's empty answer boxes: a fill and a border, and one waiting for a date.
        s.ensure(1, 0).apply { format.highlight = 0xFFFFF2CC; borderBottom = BorderLine.THIN }
        s.ensure(1, 1).apply { format.highlight = 0xFFFFF2CC; borderBottom = BorderLine.THIN; formatCode = "yyyy-mm-dd"; numberFormat = NumberFormat.DATE }
        val bos = ByteArrayOutputStream()
        OdsWriter.write(wb, bos)
        val back = OdsReader.read(bos.toByteArray()).sheets[0]
        val a = back.cell(1, 0)!!
        assertEquals(0xFFFFF2CC, a.format.highlight); assertTrue(a.borderBottom != null)
        val b = back.cell(1, 1)!!
        assertEquals("yyyy-mm-dd", b.formatCode); assertEquals(0xFFFFF2CC, b.format.highlight)
    }
}
