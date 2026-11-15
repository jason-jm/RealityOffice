package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** First-line (首行缩进), hanging (悬挂缩进) and right indents: laid out, read and written in every text format, edited. */
class ParagraphIndentTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val text = "中文段落首行缩进两个字符，这一段足够长，可以换到第二行。"

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun unzip(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no $name")
    }

    /**
     * Word's way: a "Body Indented" style indenting the first line two characters (as `firstLineChars`,
     * with the twips Word works out for its 10.5 pt), and paragraphs that inherit, override or clear it.
     */
    private fun wordDocx(): ByteArray {
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$w">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="21"/></w:rPr></w:rPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Body"><w:name w:val="Body Indented"/><w:basedOn w:val="Normal"/>""" +
            """<w:pPr><w:ind w:firstLineChars="200" w:firstLine="420"/></w:pPr></w:style></w:styles>"""
        fun p(pPr: String, halfPt: Int? = null) =
            "<w:p><w:pPr>$pPr</w:pPr><w:r>${halfPt?.let { "<w:rPr><w:sz w:val=\"$it\"/></w:rPr>" } ?: ""}<w:t>$text</w:t></w:r></w:p>"
        val body = listOf(
            p("""<w:pStyle w:val="Body"/>"""),                                                // the style's two characters of 10.5 pt
            p("""<w:pStyle w:val="Body"/>""", 32),                                            // … of its 16 pt text
            p("""<w:ind w:firstLineChars="200" w:firstLine="640"/>""", 32),                   // its own, 16 pt
            p("""<w:ind w:left="720" w:hanging="360"/>"""),                                   // hanging
            p("""<w:ind w:start="0" w:end="1440"/>"""),                                       // right (start/end spelling)
            p("""<w:pStyle w:val="Body"/><w:ind w:firstLineChars="0" w:firstLine="0"/>"""),  // the style's cleared
        ).joinToString("")
        return zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$w"><w:body>$body</w:body></w:document>""",
            "word/styles.xml" to styles,
        ))
    }

    private fun paras(doc: Document) = doc.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }

    /** (left, first line, right) of each paragraph. */
    private fun indents(doc: Document) = paras(doc).map { Triple(it.format.indentLeftPt, it.format.firstLineIndentPt, it.format.indentRightPt) }

    private val expected = listOf(
        Triple(0f, 21f, 0f), Triple(0f, 32f, 0f), Triple(0f, 32f, 0f),
        Triple(36f, -18f, 0f), Triple(0f, 0f, 72f), Triple(0f, 0f, 0f),
    )

    @Test
    fun wordIndentsInTwipsAndCharactersAndThroughStyles() {
        val doc = DocxReader.read(ByteArrayInputStream(wordDocx()))
        assertEquals(expected, indents(doc))
        val body = doc.styles["Body"]!!
        assertEquals(21f, body.firstLineIndentPt); assertEquals(null, body.indentLeftPt)
        assertEquals(21f, doc.styles.resolve("Body").firstLineIndentPt)
    }

    @Test
    fun docxKeepsEveryIndentAndTheStyles() {
        val doc = DocxReader.read(ByteArrayInputStream(wordDocx()))
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        assertEquals(expected, indents(DocxReader.read(ByteArrayInputStream(bytes))))
        val xml = unzip(bytes, "word/document.xml")
        assertTrue("a hanging indent as Word writes one", xml.contains("""<w:ind w:left="720" w:right="0" w:hanging="360"/>"""))
        assertTrue("the style's indent cleared explicitly", xml.contains("""<w:ind w:left="0" w:right="0" w:firstLine="0"/>"""))
        assertEquals("only the paragraphs that differ from their style say so", 5, Regex("<w:ind ").findAll(xml).count())
        assertTrue(unzip(bytes, "word/styles.xml").contains("""<w:ind w:firstLine="420"/>"""))
    }

    @Test
    fun odtKeepsEveryIndent() {
        val doc = DocxReader.read(ByteArrayInputStream(wordDocx()))
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        assertEquals(expected, indents(OdtReader.read(bytes)))
        val xml = unzip(bytes, "content.xml")
        assertTrue(xml.contains("""fo:text-indent="-18.00pt""""))
        assertTrue(xml.contains("""fo:margin-right="72.00pt""""))
    }

    @Test
    fun libreOfficesCharacterIndentIsTheParagraphFontSize() {
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
            """xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0" office:version="1.3""""
        // As LibreOffice 26.2 converts Word's firstLineChars: loext:text-indent in "ic", relative to the paragraph's size.
        val content = """<?xml version="1.0" encoding="UTF-8"?><office:document-content $ns><office:automatic-styles>""" +
            """<style:style style:name="P1" style:family="paragraph"><style:paragraph-properties style:auto-text-indent="false" loext:text-indent="2ic"/>""" +
            """<style:text-properties fo:font-size="16pt"/></style:style>""" +
            """<style:style style:name="P2" style:family="paragraph"><style:paragraph-properties fo:margin-left="1.27cm" fo:text-indent="-0.635cm" fo:margin-right="2.54cm"/></style:style>""" +
            """</office:automatic-styles><office:body><office:text>""" +
            """<text:p text:style-name="P1">$text</text:p><text:p text:style-name="P2">$text</text:p></office:text></office:body></office:document-content>"""
        val doc = OdtReader.read(zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to content)))
        assertEquals(listOf(Triple(0f, 32f, 0f), Triple(36f, -18f, 72f)), indents(doc))
    }

    @Test
    fun layoutGeometry() {
        fun of(left: Float, first: Float, right: Float = 0f, list: ListKind = ListKind.NONE) =
            PageMetrics.indents(ParagraphFormat(indentLeftPt = left, firstLineIndentPt = first, indentRightPt = right, listKind = list))
        assertArrayEquals("first line two characters in", floatArrayOf(0f, 21f, 0f, 0f), of(0f, 21f), 0f)
        assertArrayEquals("hanging: the first line at the paragraph's edge, the rest in", floatArrayOf(18f, 0f, 18f, 0f), of(36f, -18f), 0f)
        assertArrayEquals("a hanging line never goes into the margin", floatArrayOf(0f, 0f, 10f, 0f), of(10f, -18f), 0f)
        assertArrayEquals("right indent", floatArrayOf(36f, 0f, 0f, 72f), of(36f, 0f, 72f), 0f)
        assertArrayEquals("a list's marker stands out in its hanging indent", floatArrayOf(18f, 0f, 0f, 0f), of(36f, -18f, list = ListKind.BULLET), 0f)
        val list = ParagraphFormat(indentLeftPt = 36f, firstLineIndentPt = -18f, listKind = ListKind.NUMBER)
        assertEquals("the text at the left indent", 18f, PageMetrics.markerWidth(list))
        assertEquals("without a hanging indent, the usual marker column", PageMetrics.LIST_MARKER, PageMetrics.markerWidth(ParagraphFormat(listKind = ListKind.BULLET)))
        assertEquals(0f, PageMetrics.markerWidth(ParagraphFormat()))
    }

    @Test
    fun wordListsTakeTheirLevelsIndents() {
        val numbering = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="$w">""" +
            """<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>""" +
            """<w:lvl w:ilvl="1"><w:numFmt w:val="lowerLetter"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>""" +
            """<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$w">""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="720"/></w:pPr></w:style></w:styles>"""
        fun item(level: Int, numId: Int = 1, ind: String = "") =
            """<w:p><w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$numId"/></w:numPr>$ind</w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""
        val body = item(0) + item(1) + item(0, ind = """<w:ind w:left="1080" w:hanging="540"/>""") + item(0, numId = 0)
        val doc = DocxReader.read(ByteArrayInputStream(zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$w"><w:body>$body</w:body></w:document>""",
            "word/styles.xml" to styles, "word/numbering.xml" to numbering,
        ))))
        val items = paras(doc)
        assertEquals("the list level's indents before the List Paragraph style's", Triple(36f, -18f, 0f), indents(doc)[0])
        assertEquals(Triple(72f, -18f, 0f), indents(doc)[1])
        assertEquals("the item's own indents first", Triple(54f, -27f, 0f), indents(doc)[2])
        assertEquals("numId 0: no list, the style's indent", ListKind.NONE, items[3].format.listKind)
        assertEquals(Triple(36f, 0f, 0f), indents(doc)[3])
        assertEquals(listOf(ListKind.NUMBER, ListKind.NUMBER, ListKind.NUMBER), items.take(3).map { it.format.listKind })
        // Saved and read back, the items keep their indents (our numbering levels carry none).
        val back = DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()))
        assertEquals(indents(doc), indents(back))
        // Taken out of the list, an item drops the hanging indent its number stood in.
        val e = DocumentEditor().also { it.load(doc) }
        e.toggleNumber()
        assertEquals(ListKind.NONE, e.activeParagraph.format.listKind)
        assertEquals(36f, e.activeParagraph.format.indentLeftPt); assertEquals(0f, e.activeParagraph.format.firstLineIndentPt)
    }

    private fun editorWith(vararg sizes: Float): DocumentEditor = DocumentEditor().also { e ->
        e.load(Document(sizes.map { s -> ParagraphBlock(Paragraph(mutableListOf(TextRun(text, CharacterFormat(fontSizePt = s))))) }.toMutableList()))
    }

    @Test
    fun firstLineAndHangingIndentsKeepTheParagraphsEdge() {
        val e = editorWith(12f)
        val f = e.activeParagraph.format
        e.setFirstLineIndent(2f)
        assertEquals(0f, f.indentLeftPt); assertEquals(24f, f.firstLineIndentPt); assertEquals(2f, e.firstLineIndentChars)
        e.setFirstLineIndent(-2f)
        assertEquals("the first line where the paragraph began, the rest two characters in", 24f, f.indentLeftPt)
        assertEquals(-24f, f.firstLineIndentPt); assertEquals(0f, e.leftIndentChars)
        e.stepIndent(1f)
        assertEquals("the whole paragraph a character further in", 36f, f.indentLeftPt); assertEquals(1f, e.leftIndentChars)
        e.setFirstLineIndent(0f)
        assertEquals(12f, f.indentLeftPt); assertEquals(0f, f.firstLineIndentPt)
        e.stepIndent(-3f)
        assertEquals("never into the margin", 0f, f.indentLeftPt)
        e.stepIndent(2f, right = true)
        assertEquals(24f, f.indentRightPt); assertEquals(2f, e.rightIndentChars)
        e.undo()
        assertEquals(0f, e.activeParagraph.format.indentRightPt)
    }

    @Test
    fun stylesCarryTheirIndents() {
        val e = editorWith(11f)
        e.modifyStyle(ParagraphStyle.QUOTE) { it.indentLeftPt = 36f; it.indentRightPt = 36f }
        e.setStyle(ParagraphStyle.QUOTE)
        val f = e.activeParagraph.format
        assertEquals(36f, f.indentLeftPt); assertEquals(36f, f.indentRightPt)
        // Changing the style moves the paragraphs that still have its indents along.
        e.modifyStyle(ParagraphStyle.QUOTE) { it.indentLeftPt = 48f }
        assertEquals(48f, e.activeParagraph.format.indentLeftPt)
        e.setStyle(ParagraphStyle.NORMAL)
        assertEquals("a style without indents takes them away", 0f, e.activeParagraph.format.indentLeftPt)
        assertFalse(e.activeParagraph.format.indentRightPt != 0f)
    }
}
