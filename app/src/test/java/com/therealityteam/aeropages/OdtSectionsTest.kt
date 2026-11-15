package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sections — a page size, orientation or text columns of their own — kept in ODT as master pages, as LibreOffice keeps them. */
class OdtSectionsTest {

    private val a4 = PageSetup(595f, 842f)
    private val landscape2 = PageSetup(842f, 595f, columns = 2)

    /** Portrait, then landscape in two columns, then portrait again. */
    private fun doc(): Document {
        val d = Document()
        d.blocks.clear()
        listOf("Portrait", "Landscape one", "Landscape two", "Portrait again").forEach { d.blocks.add(ParagraphBlock(Paragraph.of(it))) }
        (d.blocks[0] as ParagraphBlock).paragraph.format.sectionBreak = a4.copy()
        (d.blocks[2] as ParagraphBlock).paragraph.format.sectionBreak = landscape2.copy()
        d.page.widthPt = 595f; d.page.heightPt = 842f
        return d
    }

    private fun breaks(d: Document) = d.blocks.map { (it as ParagraphBlock).paragraph.format.sectionBreak }

    @Test
    fun ourSectionsComeBack() {
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc(), it) }.toByteArray()
        val files = com.therealityteam.aeropages.format.OfficeXml.unzip(bytes)
        val styles = String(files["styles.xml"]!!)
        assertTrue(styles.contains("""<style:master-page style:name="Standard" style:page-layout-name="pm1">"""))
        assertTrue("the landscape pages a master of their own", styles.contains("""<style:master-page style:name="Section2" style:page-layout-name="pm2">"""))
        assertTrue("its two columns", styles.contains("""<style:columns fo:column-count="2""""))
        val content = String(files["content.xml"]!!)
        assertTrue(content.contains("""style:master-page-name="Section2""""))
        assertTrue("back to the first page's master", content.contains("""style:master-page-name="Standard""""))
        val back = OdtReader.read(bytes)
        assertEquals(listOf(a4, null, landscape2, null), breaks(back))
        assertEquals(595f, back.page.widthPt); assertEquals(842f, back.page.heightPt); assertEquals(1, back.page.columns)
    }

    @Test
    fun aWholeDocumentInColumns() {
        val d = Document()
        d.page.columns = 3
        val back = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray())
        assertEquals(3, back.page.columns)
        assertTrue(back.blocks.all { (it as? ParagraphBlock)?.paragraph?.format?.sectionBreak == null })
    }

    /** LibreOffice's way with a Word document's sections: a heading's style starting the landscape master, the columns a text section's. */
    @Test
    fun libreOfficesSections() {
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" office:version="1.3""""
        val styles = """<office:document-styles $ns><office:automatic-styles>""" +
            """<style:page-layout style:name="Mpm1"><style:page-layout-properties fo:page-width="21.001cm" fo:page-height="29.7cm" fo:margin-top="2.54cm" fo:margin-bottom="2.54cm" fo:margin-left="2.54cm" fo:margin-right="2.54cm"/></style:page-layout>""" +
            """<style:page-layout style:name="Mpm2"><style:page-layout-properties fo:page-width="29.7cm" fo:page-height="21.001cm" fo:margin-top="2.54cm" fo:margin-bottom="2.54cm" fo:margin-left="2.54cm" fo:margin-right="2.54cm"/></style:page-layout>""" +
            """</office:automatic-styles><office:master-styles><style:master-page style:name="Standard" style:page-layout-name="Mpm1"/>""" +
            """<style:master-page style:name="Converted1" style:page-layout-name="Mpm2"/></office:master-styles></office:document-styles>"""
        val content = """<office:document-content $ns><office:automatic-styles>""" +
            """<style:style style:name="P3" style:family="paragraph" style:parent-style-name="Heading_20_1" style:master-page-name="Converted1"/>""" +
            """<style:style style:name="Sect1" style:family="section"><style:section-properties><style:columns fo:column-count="2" fo:column-gap="1.27cm"/></style:section-properties></style:style>""" +
            """</office:automatic-styles><office:body><office:text><text:h text:outline-level="1">Portrait section</text:h><text:p>One column.</text:p>""" +
            """<text:section text:style-name="Sect1" text:name="TextSection"><text:h text:style-name="P3" text:outline-level="1">Landscape section</text:h>""" +
            """<text:p>Two columns.</text:p></text:section></office:text></office:body></office:document-content>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.text".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("styles.xml")); z.write(styles.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
        }
        val d = OdtReader.read(out.toByteArray())
        val first = (d.blocks[1] as ParagraphBlock).paragraph.format.sectionBreak!!
        assertTrue("the portrait section ends before the landscape heading", first.heightPt > first.widthPt)
        assertNull((d.blocks[0] as ParagraphBlock).paragraph.format.sectionBreak)
        assertTrue(d.page.widthPt > d.page.heightPt)
        assertEquals("its text section's two columns", 2, d.page.columns)
    }
}
