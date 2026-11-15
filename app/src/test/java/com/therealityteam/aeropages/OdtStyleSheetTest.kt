package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A text document's own defaults and heading styles — LibreOffice's Liberation Serif 12 pt, its relative heading sizes — read from ODT. */
class OdtStyleSheetTest {

    private val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
        """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
        """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" office:version="1.3""""

    /** An ODT shaped as LibreOffice writes one: its fonts declared, its default and heading styles, a heading and a paragraph. */
    private fun libreOfficeOdt(): ByteArray {
        val styles = """<?xml version="1.0" encoding="UTF-8"?><office:document-styles $ns>""" +
            """<office:font-face-decls><style:font-face style:name="Liberation Serif" svg:font-family="'Liberation Serif'"/>""" +
            """<style:font-face style:name="Liberation Sans1" svg:font-family="'Liberation Sans'"/></office:font-face-decls><office:styles>""" +
            """<style:default-style style:family="paragraph"><style:text-properties style:font-name="Liberation Serif" fo:font-size="12pt"/></style:default-style>""" +
            """<style:style style:name="Standard" style:family="paragraph" style:class="text"/>""" +
            """<style:style style:name="Heading" style:family="paragraph" style:parent-style-name="Standard"><style:text-properties style:font-name="Liberation Sans1" fo:font-size="14pt"/></style:style>""" +
            """<style:style style:name="Heading_20_1" style:display-name="Heading 1" style:family="paragraph" style:parent-style-name="Heading" style:default-outline-level="1">""" +
            """<style:text-properties fo:font-size="130%" fo:font-weight="bold" fo:color="#1f3864"/></style:style>""" +
            """<style:style style:name="Title" style:family="paragraph" style:parent-style-name="Heading"><style:paragraph-properties fo:text-align="center"/>""" +
            """<style:text-properties fo:font-size="28pt" fo:font-weight="bold"/></style:style></office:styles></office:document-styles>"""
        val content = """<?xml version="1.0" encoding="UTF-8"?><office:document-content $ns><office:body><office:text>""" +
            """<text:h text:style-name="Heading_20_1" text:outline-level="1">Findings</text:h><text:p text:style-name="Standard">Body text.</text:p>""" +
            """</office:text></office:body></office:document-content>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.text".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("styles.xml")); z.write(styles.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun libreOfficesDefaultsAndHeadings() {
        val doc = OdtReader.read(libreOfficeOdt())
        assertEquals("its default face, as its font declaration names it", "Liberation Serif", doc.styles.defaultFontFamily)
        assertEquals(12f, doc.styles.defaultSizePt)
        val h1 = doc.styles.defOf(ParagraphStyle.HEADING1)
        assertEquals("130% of its Heading's 14 pt", 18.2f, h1.sizePt!!, 0.01f)
        assertEquals("Liberation Sans", h1.fontFamily)
        assertEquals(true, h1.bold); assertEquals(0xFF1F3864, h1.color)
        val title = doc.styles.defOf(ParagraphStyle.TITLE)
        assertEquals(28f, title.sizePt); assertEquals(TextAlign.CENTER, title.align)
        val heading = (doc.blocks[0] as ParagraphBlock).paragraph
        assertEquals(18.2f, heading.runs.first().format.fontSizePt!!, 0.01f)
        val body = (doc.blocks[1] as ParagraphBlock).paragraph
        assertEquals("body text in the document's default face", "Liberation Serif", body.runs.first().format.fontFamily)
        assertEquals(12f, body.runs.first().format.fontSizePt)
        assertEquals("Liberation Serif", doc.styles.resolve(ParagraphStyle.NORMAL).fontFamily)
    }

    @Test
    fun aDocumentsOwnFaceAndStylesComeBack() {
        val doc = Document()
        doc.blocks.clear()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Heading")), ParagraphFormat(style = ParagraphStyle.HEADING1))))
        doc.blocks.add(ParagraphBlock(Paragraph.of("Body")))
        doc.styles.defaultFontFamily = "Cambria"; doc.styles.defaultSizePt = 12f
        doc.styles.defOf(ParagraphStyle.HEADING1).let { d -> doc.styles.put(d.copyOf().also { it.color = 0xFF2E75B6 }) }
        val back = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray())
        assertEquals("Cambria", back.styles.defaultFontFamily)
        assertEquals(12f, back.styles.defaultSizePt)
        assertEquals(0xFF2E75B6, back.styles.defOf(ParagraphStyle.HEADING1).color)
        // The app's own default stays the app's.
        val plain = Document()
        val again = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(plain, it) }.toByteArray())
        assertNull(again.styles.defaultFontFamily)
        assertTrue(again.styles.defOf(ParagraphStyle.HEADING1).sameAs(plain.styles.defOf(ParagraphStyle.HEADING1)))
    }
}
