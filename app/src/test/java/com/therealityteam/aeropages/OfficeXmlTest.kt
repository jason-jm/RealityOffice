package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeXmlTest {

    @Test fun escapeDropsForbiddenControlCharacters() {
        assertEquals("a&amp;b &lt;c&gt; &quot;d&quot;", OfficeXml.escape("a&b <c> \"d\""))
        assertEquals("tab\tok\nline", OfficeXml.escape("tab\tok\u0001\nline"))
        assertEquals("plain", OfficeXml.escape("plain"))
    }

    @Test fun decodeHandlesNamedAndNumericEntities() {
        assertEquals("a&b <c> \"d\" 'e'", OfficeXml.decode("a&amp;b &lt;c&gt; &quot;d&quot; &apos;e&apos;"))
        assertEquals("中A", OfficeXml.decode("&#20013;&#x41;"))
        assertEquals("&lt;", OfficeXml.decode("&amp;lt;"))
    }

    @Test fun xlsxWithPrefixedNamespaceStillReads() {
        fun zipOf(vararg entries: Pair<String, String>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
            return bos.toByteArray()
        }
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val bytes = zipOf(
            "xl/workbook.xml" to """<x:workbook xmlns:x="$ns" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><x:sheets><x:sheet name="Data" sheetId="1" r:id="rId1"/></x:sheets></x:workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<x:worksheet xmlns:x="$ns"><x:sheetData><x:row r="1"><x:c r="A1" t="inlineStr"><x:is><x:t>hi</x:t></x:is></x:c><x:c r="B1"><x:v>7</x:v></x:c></x:row></x:sheetData></x:worksheet>""",
        )
        val wb = XlsxReader.read(bytes)
        assertEquals("Data", wb.sheets[0].name)
        assertEquals("hi", wb.sheets[0].cell(0, 0)?.text)
        assertEquals("7", wb.sheets[0].cell(0, 1)?.text)
    }

    @Test fun writersStripControlCharactersSoOfficeOpensTheFile() {
        val doc = Document(mutableListOf(ParagraphBlock(Paragraph.of("bad\u0001char\u0002here"))))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        assertEquals("badcharhere", DocxReader.read(ByteArrayInputStream(bos.toByteArray())).paragraphs()[0].plainText())

        val wb = Workbook.blank()
        wb.sheets[0].ensure(0, 0).text = "x\u0007y"
        val xb = ByteArrayOutputStream()
        XlsxWriter.write(wb, xb)
        assertEquals("xy", XlsxReader.read(xb.toByteArray()).sheets[0].cell(0, 0)?.text)
    }

    @Test fun concurrentDocxWritesDoNotShareState() {
        // Two documents with different footnote tables written at once from separate threads.
        val a = Document(mutableListOf(ParagraphBlock(Paragraph.of("A"))))
        val ea = DocumentEditor(); ea.load(a); ea.setSelection(0, 1, 1); ea.insertFootnote(); ea.insertFootnote()
        val b = Document(mutableListOf(ParagraphBlock(Paragraph.of("B"))))
        val results = arrayOfNulls<ByteArray>(2)
        val threads = listOf(
            Thread { repeat(40) { val o = ByteArrayOutputStream(); DocxWriter.write(ea.document, o); results[0] = o.toByteArray() } },
            Thread { repeat(40) { val o = ByteArrayOutputStream(); DocxWriter.write(b, o); results[1] = o.toByteArray() } },
        )
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(2, DocxReader.read(ByteArrayInputStream(results[0]!!)).footnotes.size)
        assertEquals(0, DocxReader.read(ByteArrayInputStream(results[1]!!)).footnotes.size)
    }
}
