package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.HeaderFooters
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Headers and footers as Word and LibreOffice keep them: formatted lines, tab slots, page number fields, distances. */
class HeaderFieldsTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun entry(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no $name")
    }

    /** A Word file: a header with left / centre / right slots and both kinds of fields, a two-line centred footer, a Chinese-style centred 页眉 style. */
    private fun wordFile(header: String = HEADER, footer: String = FOOTER, styles: String = STYLES): ByteArray = zip(mapOf(
        "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
        "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId8" Type="$r/header" Target="header1.xml"/><Relationship Id="rId9" Type="$r/footer" Target="footer1.xml"/></Relationships>""",
        "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w" xmlns:r="$r"><w:body><w:p><w:r><w:t>Body</w:t></w:r></w:p>""" +
            """<w:sectPr><w:headerReference w:type="default" r:id="rId8"/><w:footerReference w:type="default" r:id="rId9"/><w:pgSz w:w="12240" w:h="15840"/>""" +
            """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="851" w:footer="992" w:gutter="0"/></w:sectPr></w:body></w:document>""",
        "word/header1.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:hdr xmlns:w="$w" xmlns:v="urn:schemas-microsoft-com:vml">$header</w:hdr>""",
        "word/footer1.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:ftr xmlns:w="$w">$footer</w:ftr>""",
        "word/styles.xml" to styles,
    ))

    @Test
    fun wordHeadersKeepTheirFieldsTabsLinesAndLooks() {
        val doc = DocxReader.read(ByteArrayInputStream(wordFile()))
        val header = doc.header!!
        assertEquals("Left title\tCentre\tPage ${HeaderFooters.PAGE} of ${HeaderFooters.PAGES}", header.plainText())
        assertEquals("a run's own look", true, header.runs.first { it.text == "Left title" }.format.bold)
        assertEquals("the field in its run's look", 14f, header.runs.first { it.text == HeaderFooters.PAGE }.format.fontSizePt)
        assertEquals(TextAlign.LEFT, header.format.align)
        val footer = doc.footer!!
        assertEquals("Footer line one\nFooter line two\nSecond paragraph", footer.plainText())
        assertEquals("the 页眉 style centres it", TextAlign.CENTER, footer.format.align)
        assertEquals("the style's size", 9f, footer.runs.first().format.fontSizePt)
        assertEquals(42.55f, doc.page.headerPt, 0.01f); assertEquals(49.6f, doc.page.footerPt, 0.01f)
    }

    @Test
    fun wordWatermarksAndFieldsWithoutResults() {
        val header = """<w:p><w:r><w:pict><v:shape><v:textpath string="CONFIDENTIAL"/></v:shape></w:pict></w:r></w:p>""" +
            """<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>""" +
            """<w:r><w:t xml:space="preserve"> / </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> DATE \@ "yyyy" </w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>2026</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>"""
        val doc = DocxReader.read(ByteArrayInputStream(wordFile(header = header)))
        assertEquals("Word's watermark lives in the header", "CONFIDENTIAL", doc.watermark)
        assertEquals("a page number with no result shown, another field's result", "${HeaderFooters.PAGE} / 2026", doc.header!!.plainText())
    }

    @Test
    fun docxWritesRealFieldsTabStopsAndDistances() {
        val doc = DocxReader.read(ByteArrayInputStream(wordFile()))
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(doc.header!!.plainText(), back.header!!.plainText())
        assertEquals(doc.footer!!.plainText(), back.footer!!.plainText())
        assertEquals(TextAlign.CENTER, back.footer!!.format.align)
        assertEquals(doc.page.headerPt, back.page.headerPt); assertEquals(doc.page.footerPt, back.page.footerPt)
        val parts = com.therealityteam.aeropages.format.OfficeXml.unzip(ByteArrayInputStream(bytes))
        val hdr = parts.entries.first { it.key.startsWith("word/header") }.value.toString(Charsets.UTF_8)
        assertTrue("Word works out the page number", hdr.contains("<w:instrText xml:space=\"preserve\"> PAGE </w:instrText>"))
        assertTrue(hdr.contains("> NUMPAGES <"))
        assertTrue("no token left for Word to show", !hdr.contains("{PAGE"))
        assertTrue("the slots' tab stops", hdr.contains("""<w:tab w:val="center" w:pos="4680"/><w:tab w:val="right" w:pos="9360"/>"""))
        val body = parts["word/document.xml"]!!.toString(Charsets.UTF_8)
        assertTrue("the header distance Word requires", body.contains("""w:header="851" w:footer="992" w:gutter="0""""))
    }

    @Test
    fun odtKeepsHeadersVariantsAndTheirDistances() {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Body"))
        doc.header = Paragraph(mutableListOf(TextRun("Report", CharacterFormat(bold = true)), TextRun("\t\tPage ${HeaderFooters.PAGE} of ${HeaderFooters.PAGES}")), ParagraphFormat())
        doc.footer = Paragraph(mutableListOf(TextRun("Line one\nLine two")), ParagraphFormat(align = TextAlign.CENTER))
        doc.differentOddEven = true
        doc.evenHeader = Paragraph.of("Even ${HeaderFooters.PAGE}")
        doc.differentFirstPage = true
        doc.firstHeader = Paragraph.of("Cover")
        doc.page.headerPt = 30f; doc.page.marginTopPt = 80f; doc.page.footerPt = 24f; doc.page.marginBottomPt = 60f
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        val styles = entry(bytes, "styles.xml")
        assertTrue(styles.contains("""<text:page-number text:select-page="current">1</text:page-number>"""))
        assertTrue(styles.contains("<text:page-count>1</text:page-count>"))
        assertTrue(styles.contains("<style:header-left>") && styles.contains("<style:header-first>"))
        assertTrue("the page's top margin is the header's distance", styles.contains("""fo:margin-top="30.00pt""""))
        val back = OdtReader.read(bytes)
        assertEquals(doc.header!!.plainText(), back.header!!.plainText())
        assertEquals(true, back.header!!.runs.first().format.bold)
        assertEquals("Line one\nLine two", back.footer!!.plainText())
        assertEquals(TextAlign.CENTER, back.footer!!.format.align)
        assertEquals("Even ${HeaderFooters.PAGE}", back.evenHeader!!.plainText()); assertTrue(back.differentOddEven)
        assertEquals("Cover", back.firstHeader!!.plainText()); assertTrue(back.differentFirstPage)
        assertEquals(30f, back.page.headerPt, 0.01f); assertEquals(80f, back.page.marginTopPt, 0.01f)
        assertEquals(24f, back.page.footerPt, 0.01f); assertEquals(60f, back.page.marginBottomPt, 0.01f)
    }

    @Test
    fun linesAndSlots() {
        val p = Paragraph(mutableListOf(TextRun("A\t", CharacterFormat(bold = true)), TextRun("B\tPage ${HeaderFooters.PAGE}/${HeaderFooters.PAGES}\nsecond")), ParagraphFormat())
        val lines = HeaderFooters.lines(p, 3, 12)
        assertEquals(2, lines.size)
        val slots = lines[0].slots!!
        assertEquals(listOf("A", "B", "Page 3/12"), slots.map { s -> s.joinToString("") { it.text } })
        assertEquals(true, slots[0][0].format.bold)
        assertNull("a line without tabs is placed by the paragraph's alignment", lines[1].slots)
        assertEquals("second", lines[1].runs.joinToString("") { it.text })
        assertEquals("only the right slot: two tabs", listOf("", "", "9"), HeaderFooters.lines(Paragraph.of("\t\t${HeaderFooters.PAGE}"), 9, 9)[0].slots!!.map { s -> s.joinToString("") { it.text } })
    }

    private companion object {
        val HEADER = """<w:p><w:pPr><w:pStyle w:val="Header"/><w:tabs><w:tab w:val="center" w:pos="4680"/><w:tab w:val="right" w:pos="9360"/></w:tabs></w:pPr>""" +
            """<w:r><w:rPr><w:b/></w:rPr><w:t>Left title</w:t></w:r><w:r><w:tab/><w:t>Centre</w:t></w:r><w:r><w:tab/><w:t xml:space="preserve">Page </w:t></w:r>""" +
            """<w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:instrText xml:space="preserve"> PAGE \* MERGEFORMAT </w:instrText></w:r>""" +
            """<w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:t>1</w:t></w:r><w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:fldChar w:fldCharType="end"/></w:r>""" +
            """<w:r><w:t xml:space="preserve"> of </w:t></w:r><w:fldSimple w:instr=" NUMPAGES "><w:r><w:t>2</w:t></w:r></w:fldSimple></w:p>"""
        val FOOTER = """<w:p><w:pPr><w:pStyle w:val="a4"/></w:pPr><w:r><w:t>Footer line one</w:t></w:r><w:r><w:br/><w:t>Footer line two</w:t></w:r></w:p>""" +
            """<w:p><w:pPr><w:pStyle w:val="a4"/></w:pPr><w:r><w:t>Second paragraph</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val="a4"/></w:pPr></w:p>"""
        val STYLES = """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Header"><w:name w:val="header"/><w:basedOn w:val="Normal"/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="a4"><w:name w:val="footer"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/></w:pPr><w:rPr><w:sz w:val="18"/></w:rPr></w:style></w:styles>"""
    }
}
