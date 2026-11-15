package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.model.FieldDates
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** DATE and TIME fields show today (a letter template's date is the day it is written), as Word and LibreOffice show them. */
class FieldDatesTest {

    private val at = LocalDateTime.of(2026, 10, 8, 14, 5, 9)

    @Test
    fun picturesAsWordFormatsThem() {
        assertEquals("October 8, 2026", FieldDates.shown("TIME \\@ \"MMMM D, YYYY\"", at))
        assertEquals("2026年10月8日(木)", FieldDates.shown(" DATE \\@ \"yyyy年M月d日(aaa)\" \\* MERGEFORMAT ", at))
        assertEquals("2026年10月8日 星期四", FieldDates.shown("DATE \\@ \"yyyy'年'M'月'd'日' dddd\"", at))
        assertEquals("08/10/26 2:05 PM", FieldDates.shown("DATE \\@ \"dd/MM/yy h:mm AM/PM\"", at))
        assertEquals("14:05:09", FieldDates.shown("TIME \\@ \"HH:mm:ss\"", at))
        assertEquals("Thu, Oct 8", FieldDates.shown("date \\@ \"ddd, MMM d\"", at))
        assertEquals("10/8/2026", FieldDates.shown("DATE", at))
        assertEquals("2026/10/8", FieldDates.shown("DATE", at, cjk = true))
        // The dates a file keeps, and other fields: not today's.
        assertNull(FieldDates.shown("CREATEDATE \\@ \"yyyy\"", at))
        assertNull(FieldDates.shown("SAVEDATE", at))
        assertNull(FieldDates.shown("PAGE", at))
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun wordAndOdfDatesComeInAsToday() {
        val today = LocalDate.now()
        val w = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""
        val body = """<w:p><w:r><w:t xml:space="preserve">Date: </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> DATE \@ "yyyy-MM-dd" </w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>2013-04-09</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""" +
            """<w:p><w:fldSimple w:instr=" DATE \@ &quot;d/M/yyyy&quot; "><w:r><w:t>9/4/2013</w:t></w:r></w:fldSimple><w:r><w:t> then</w:t></w:r></w:p>""" +
            """<w:p><w:fldSimple w:instr=" CREATEDATE "><w:r><w:t>9/4/2013</w:t></w:r></w:fldSimple></w:p>"""
        val docx = zip(mapOf("[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<w:document $w><w:body>$body</w:body></w:document>"""))
        val d = DocxReader.read(ByteArrayInputStream(docx))
        val texts = d.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() }
        assertEquals("Date: $today", texts[0])
        assertEquals("${today.dayOfMonth}/${today.monthValue}/${today.year} then", texts[1])
        assertEquals("9/4/2013", texts[2])

        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0""""
        val content = """<office:document-content $ns><office:automatic-styles><number:date-style style:name="N1"><number:year number:style="long"/><number:text>.</number:text>""" +
            """<number:month number:style="long"/><number:text>.</number:text><number:day number:style="long"/></number:date-style></office:automatic-styles>""" +
            """<office:body><office:text><text:p><text:date style:data-style-name="N1" text:date-value="2013-04-09">2013.04.09</text:date></text:p>""" +
            """<text:p><text:date text:fixed="true" style:data-style-name="N1">2013.04.09</text:date></text:p></office:text></office:body></office:document-content>"""
        val odt = OdtReader.read(zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to content)))
        val o = odt.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() }
        assertEquals("%d.%02d.%02d".format(today.year, today.monthValue, today.dayOfMonth), o[0])
        assertEquals("2013.04.09", o[1])
    }
}
