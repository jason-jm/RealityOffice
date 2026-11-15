package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OdfMeta
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.OoxmlCore
import com.therealityteam.aeropages.format.W3cDates
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.DocProps
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** File → Info's properties: title, subject, author, keywords, comments, who saved it last, dates, saves — in every format. */
class DocPropsTest {

    private val props = DocProps(
        title = "Quarterly report", subject = "Sales & costs", author = "Ann Lee", keywords = "sales, Q3, 季度",
        description = "Draft for the board.\nSecond line.", lastModifiedBy = "Bob", created = 1_700_000_000_000L, modified = 1_700_000_600_000L, revision = 4,
    )

    @Test
    fun aSaveStampsThem() {
        val s = props.saved(1_800_000_000_000L, "Cy")
        assertEquals(1_700_000_000_000L, s.created); assertEquals(1_800_000_000_000L, s.modified); assertEquals(5, s.revision); assertEquals("Cy", s.lastModifiedBy)
        val first = DocProps().saved(42L, null)
        assertEquals(42L, first.created); assertEquals(1, first.revision); assertEquals("", first.lastModifiedBy)
        assertEquals(1_700_000_000_000L, W3cDates.parse("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_000L, W3cDates.parse("2023-11-14T22:13:20.000000000"))
        assertEquals(1_700_000_000_000L, W3cDates.parse("2023-11-15T06:13:20+08:00"))
    }

    @Test
    fun ooxmlCorePropertiesAsWordWritesThem() {
        val xml = String(OoxmlCore.write(props))
        assertTrue(xml, xml.contains("<dc:title>Quarterly report</dc:title>"))
        assertTrue(xml.contains("<dc:subject>Sales &amp; costs</dc:subject>"))
        assertTrue(xml.contains("<cp:keywords>sales, Q3, 季度</cp:keywords>"))
        assertTrue(xml.contains("""<dcterms:created xsi:type="dcterms:W3CDTF">2023-11-14T22:13:20Z</dcterms:created>"""))
        assertEquals(props, OoxmlCore.read(OoxmlCore.write(props)))
        // ODF keeps each keyword on its own.
        val meta = String(OdfMeta.write(props))
        assertTrue(meta, meta.contains("<meta:keyword>sales</meta:keyword><meta:keyword>Q3</meta:keyword><meta:keyword>季度</meta:keyword>"))
        assertTrue(meta.contains("<meta:initial-creator>Ann Lee</meta:initial-creator><dc:creator>Bob</dc:creator>"))
        assertEquals("well-formed: office:version once", 1, Regex("office:version=").findAll(meta).count())
        assertEquals(props, OdfMeta.read(OdfMeta.write(props)))
    }

    @Test
    fun everyFormatKeepsThem() {
        val doc = Document.blank().also { it.props = props }
        val docx = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        assertTrue(String(OfficeXml.unzip(docx)["[Content_Types].xml"]!!).contains("core-properties+xml"))
        assertTrue(String(OfficeXml.unzip(docx)["_rels/.rels"]!!).contains("metadata/core-properties"))
        assertEquals(props, DocxReader.read(ByteArrayInputStream(docx)).props)
        assertEquals(props, OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()).props)

        val wb = Workbook.blank().also { it.props = props }
        assertEquals(props, XlsxReader.read(ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()).props)
        assertEquals(props, OdsReader.read(ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()).props)

        val deck = Presentation.blank().also { it.props = props }
        assertEquals(props, PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()).props)
        assertEquals(props, OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()).props)
        // Copies (undo snapshots, the session's background save) carry them.
        assertEquals(props, doc.copyOf().props); assertEquals(props, wb.copyOf().props); assertEquals(props, deck.copyOf().props)
    }

    @Test
    fun word97KeepsThemInItsSummaryInformation() {
        // props.doc: LibreOffice's .doc of the sample below — its SummaryInformation property set.
        val p = com.therealityteam.aeropages.format.doc.DocReader.read(javaClass.getResourceAsStream("/doc/props.doc")!!).props
        assertEquals("Quarterly report", p.title); assertEquals("Sales & costs", p.subject); assertEquals("Ann Lee", p.author)
        assertTrue(p.keywords, p.keywords.contains("季度"))
        assertEquals("Bob", p.lastModifiedBy)
        // The day it was made (LibreOffice writes the time shifted by the machine's time zone; a FILETIME is UTC).
        assertTrue("${p.created}", kotlin.math.abs(p.created!! - 1_700_000_000_000L) <= 14 * 3_600_000L)
        // A stream that is not a property set gives no properties rather than failing.
        assertEquals(DocProps(), com.therealityteam.aeropages.format.doc.OleSummary.parse(ByteArray(10)))
    }

    /** With `-Daero.sampleDir`: a document with properties in docx and ODT, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val doc = Document.blank().also { it.props = props }
        File(dir, "props.docx").outputStream().use { DocxWriter.write(doc, it) }
        File(dir, "props.odt").outputStream().use { OdtWriter.write(doc, it) }
    }
}
