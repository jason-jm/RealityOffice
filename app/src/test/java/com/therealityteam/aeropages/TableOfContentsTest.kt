package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TocBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The table of contents: Heading 1–3 entries, written as Word's TOC content control + field and
 * ODF's table-of-content, and read back from both — ours and Word / LibreOffice files — as a live
 * contents block whose cached lines are not duplicated into the text.
 */
class TableOfContentsTest {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.NORMAL) = Paragraph.of(text, pFormat = ParagraphFormat(style = style))

    private fun sample(): Document {
        val d = Document(mutableListOf())
        d.blocks.add(TocBlock())
        d.addParagraph(para("Report", ParagraphStyle.TITLE))
        d.addParagraph(para("Introduction", ParagraphStyle.HEADING1))
        d.addParagraph(para("Some text."))
        d.addParagraph(para("Scope", ParagraphStyle.HEADING2))
        d.addParagraph(para("Details", ParagraphStyle.HEADING3))
        d.addParagraph(para("Results", ParagraphStyle.HEADING1))
        return d
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }
    private fun part(bytes: ByteArray, name: String): String {
        val z = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        error("no $name")
    }
    private fun texts(d: Document) = d.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() }

    @Test
    fun entriesAreHeadingsOneToThreeWithTheirLevels() {
        val entries = sample().tocEntries()
        assertEquals(listOf("Introduction", "Scope", "Details", "Results"), entries.map { it.text })
        assertEquals("Title is not a contents entry", listOf(0, 1, 2, 0), entries.map { it.level })
    }

    @Test
    fun docxWritesWordsTocControlAndReadsItBackLive() {
        val bos = ByteArrayOutputStream()
        DocxWriter.write(sample(), bos)
        val xml = part(bos.toByteArray(), "word/document.xml")
        assertTrue(xml.contains("""<w:docPartGallery w:val="Table of Contents"/>"""))
        assertTrue(xml.contains(""" TOC \o "1-3" \h \z \u """))
        assertTrue("Word refreshes it on open", xml.contains("""w:fldCharType="begin" w:dirty="true""""))
        assertTrue(xml.contains("""<w:pStyle w:val="TOC2"/>"""))
        assertTrue(xml.contains("""w:leader="dot""""))
        assertEquals(1, Regex("""w:fldCharType="end"""").findAll(xml).count())

        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertTrue("the contents come back as a live block", back.blocks.first() is TocBlock)
        assertEquals("cached entries are not duplicated as text", listOf("Report", "Introduction", "Some text.", "Scope", "Details", "Results"), texts(back))
        assertEquals(listOf("Introduction", "Scope", "Details", "Results"), back.tocEntries().map { it.text })
    }

    @Test
    fun wordMadeContentsBecomeALiveBlockAndOtherControlsKeepTheirText() {
        val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val document = """<w:document $w><w:body>
            <w:sdt><w:sdtPr><w:id w:val="-1"/><w:docPartObj><w:docPartGallery w:val="Table of Contents"/><w:docPartUnique/></w:docPartObj></w:sdtPr>
              <w:sdtContent>
                <w:p><w:pPr><w:pStyle w:val="TOCHeading"/></w:pPr><w:r><w:t>Contents</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="TOC1"/></w:pPr><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> TOC \o "1-3" \h \z \u </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>
                  <w:hyperlink w:anchor="_Toc1"><w:r><w:t>Chapter</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>1</w:t></w:r></w:hyperlink></w:p>
                <w:p><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>
              </w:sdtContent></w:sdt>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Chapter</w:t></w:r></w:p>
            <w:sdt><w:sdtPr><w:alias w:val="Note"/></w:sdtPr><w:sdtContent><w:p><w:r><w:t>Inside a plain control</w:t></w:r></w:p></w:sdtContent></w:sdt>
            <w:p><w:r><w:t>Body</w:t></w:r></w:p>
            </w:body></w:document>"""
        val contentTypes = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val d = DocxReader.read(ByteArrayInputStream(zipOf("[Content_Types].xml" to contentTypes, "word/document.xml" to document)))
        assertTrue(d.blocks.first() is TocBlock)
        assertEquals(listOf("Chapter", "Inside a plain control", "Body"), texts(d))
        assertEquals(ParagraphStyle.HEADING1, d.blocks.filterIsInstance<ParagraphBlock>().first().paragraph.format.style)
    }

    @Test
    fun odtWritesTableOfContentAndReadsItBackLive() {
        val bos = ByteArrayOutputStream()
        OdtWriter.write(sample(), bos)
        val bytes = bos.toByteArray()
        val xml = part(bytes, "content.xml")
        assertTrue(xml.contains("""<text:table-of-content-source text:outline-level="3">"""))
        assertTrue(xml.contains("""style:leader-char=".""""))
        // Each entry links to its heading's bookmark, as LibreOffice's own entries do.
        assertTrue(Regex("""<text:p text:style-name="Contents_20_2"><text:a xlink:type="simple" xlink:href="#(_Toc\d+)">Scope<text:tab/></text:a></text:p>""").find(xml)
            ?.let { m -> Regex("""<text:h [^>]*><text:bookmark-start text:name="${m.groupValues[1]}"/>(<text:span [^>]*>)?Scope""").containsMatchIn(xml) } == true)
        val back = OdtReader.read(bytes)
        assertTrue(back.blocks.first() is TocBlock)
        assertEquals(listOf("Report", "Introduction", "Some text.", "Scope", "Details", "Results"), texts(back))
    }

    @Test
    fun libreOfficeContentsAreReadAsALiveBlock() {
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0""""
        val content = """<office:document-content $ns><office:body><office:text>
            <text:table-of-content text:name="Table of Contents1"><text:table-of-content-source text:outline-level="10"><text:index-title-template>Table of Contents</text:index-title-template></text:table-of-content-source>
              <text:index-body><text:index-title><text:p>Table of Contents</text:p></text:index-title><text:p>Heading A<text:tab/>1</text:p></text:index-body></text:table-of-content>
            <text:h text:outline-level="1">Heading A</text:h><text:p>Text</text:p>
            </office:text></office:body></office:document-content>"""
        val d = OdtReader.read(zipOf("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to content))
        assertTrue(d.blocks.first() is TocBlock)
        assertEquals(listOf("Heading A", "Text"), texts(d))
    }
}
