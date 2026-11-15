package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Word's New Style from formatting, and a document's own styles: listed, applied, updated, deleted, kept in docx and ODT. */
class OwnStylesTest {

    private fun editor(): DocumentEditor = DocumentEditor().apply {
        load(Document(mutableListOf(
            ParagraphBlock(Paragraph(mutableListOf(TextRun("Callout text", CharacterFormat(fontSizePt = 14f, bold = true, color = 0xFFC00000))),
                ParagraphFormat(align = TextAlign.CENTER, spaceAfterPt = 12f))),
            ParagraphBlock(Paragraph.of("Plain one")),
            ParagraphBlock(Paragraph.of("Plain two")),
        )))
        setSelection(0, 2, 2)
    }

    @Test
    fun aStyleIsMadeFromTheParagraphsLookAndAppliedToIt() {
        val e = editor()
        assertTrue("the built-in six are not the document's own", e.ownStyles.isEmpty())
        val id = e.createStyle("Callout")!!
        val d = e.document.styles[id]!!
        assertEquals("Callout", d.name)
        assertEquals("based on the paragraph's style", e.document.styles.idOf(ParagraphStyle.NORMAL), d.basedOn)
        assertEquals(14f, d.sizePt); assertEquals(true, d.bold); assertEquals(0xFFC00000, d.color)
        assertEquals(TextAlign.CENTER, d.align); assertEquals(12f, d.spaceAfterPt)
        assertNull("what the base already has is not repeated", d.italic)
        assertEquals(id, e.activeStyleId)
        assertEquals(listOf(id), e.ownStyles.map { it.id })
        // Applied elsewhere, the look comes with it.
        e.setSelection(1, 0, 0)
        e.setStyleId(id)
        val second = e.document.blocks[1].let { (it as ParagraphBlock).paragraph }
        assertEquals(TextAlign.CENTER, second.format.align)
        assertTrue(second.runs.first().format.bold)
        assertEquals(2, e.styleUses(id))
        e.undo(); e.undo()
        assertTrue("undone, the style goes", e.ownStyles.isEmpty())
    }

    @Test
    fun theNameMustBeNewAndNotEmpty() {
        val e = editor()
        assertEquals(DocumentEditor.StyleNameIssue.EMPTY, e.newStyleIssue("  "))
        assertEquals(DocumentEditor.StyleNameIssue.TAKEN, e.newStyleIssue("normal"))
        assertEquals(DocumentEditor.StyleNameIssue.TAKEN, e.newStyleIssue("Heading 1"))
        e.createStyle("Quote Box")
        assertEquals(DocumentEditor.StyleNameIssue.TAKEN, e.newStyleIssue("quote box"))
        assertNull(e.createStyle("Quote Box"))
        assertEquals("the id is the name without spaces", "QuoteBox", e.ownStyles.single().id)
    }

    @Test
    fun aHeadingsStyleStaysAHeadingAndADeletedStyleGivesWay() {
        val e = editor()
        e.setStyle(ParagraphStyle.HEADING1)
        val id = e.createStyle("Chapter")!!
        assertEquals("based on Heading 1: still a heading (navigation, contents)", ParagraphStyle.HEADING1, e.activeParagraph.format.style)
        e.setSelection(2, 0, 0); e.setStyleId(id)
        assertEquals(ParagraphStyle.HEADING1, (e.document.blocks[2] as ParagraphBlock).paragraph.format.style)
        e.deleteStyle(id)
        assertTrue(e.ownStyles.isEmpty())
        assertEquals("its paragraphs take its base", e.document.styles.idOf(ParagraphStyle.HEADING1), (e.document.blocks[2] as ParagraphBlock).paragraph.format.rawStyleId)
        e.undo()
        assertEquals(id, (e.document.blocks[2] as ParagraphBlock).paragraph.format.rawStyleId)
        e.deleteStyle(e.document.styles.idOf(ParagraphStyle.NORMAL))
        assertTrue("a built-in style is not deleted", e.document.styles[e.document.styles.idOf(ParagraphStyle.NORMAL)] != null)
    }

    @Test
    fun docxKeepsTheStyleAndItsParagraphs() {
        val e = editor()
        val id = e.createStyle("Callout")!!
        val out = ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray()
        val back = DocxReader.read(ByteArrayInputStream(out))
        val d = back.styles[id]!!
        assertEquals("Callout", d.name); assertEquals(14f, d.sizePt); assertEquals(true, d.bold); assertEquals(TextAlign.CENTER, d.align)
        assertEquals(id, (back.blocks[0] as ParagraphBlock).paragraph.format.rawStyleId)
        val again = DocumentEditor().apply { load(back) }
        assertEquals(listOf("Callout"), again.ownStyles.map { it.name })
    }

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    /** Two styles made here (one on Heading 1), one left unused: ODT names them, its paragraphs stand on them, all come back. */
    private fun styled(): DocumentEditor = editor().apply {
        createStyle("Callout Box")
        setSelection(1, 0, 0); setStyle(ParagraphStyle.HEADING1)
        createStyle("Chapter")
        setSelection(2, 0, 0); createStyle("Spare")
        setStyleId(document.styles.idOf(ParagraphStyle.NORMAL))
    }

    @Test
    fun odtNamesTheStylesAndItsParagraphsStandOnThem() {
        val e = styled()
        val out = ByteArrayOutputStream().also { OdtWriter.write(e.document, it) }.toByteArray()
        val styles = part(out, "styles.xml")
        assertTrue(styles.contains("""<style:style style:name="CalloutBox" style:display-name="Callout Box" style:family="paragraph" style:parent-style-name="Standard">"""))
        assertTrue("on Heading 1", styles.contains("""style:name="Chapter" style:display-name="Chapter" style:family="paragraph" style:parent-style-name="Heading_20_1""""))
        assertTrue("what it sets", styles.contains("""<style:paragraph-properties fo:text-align="center" fo:margin-top="0.00pt" fo:margin-bottom="12.00pt" fo:line-height="115%"/>"""))
        assertTrue("and no more: not italic, no face", Regex("""style:name="CalloutBox".*?</style:style>""").find(styles)!!.value.let { !it.contains("font-style") && !it.contains("font-family") })
        assertTrue(part(out, "content.xml").contains("""style:parent-style-name="CalloutBox""""))
        val back = OdtReader.read(out)
        val d = back.styles["CalloutBox"]!!
        assertEquals("Callout Box", d.name); assertEquals(14f, d.sizePt); assertEquals(true, d.bold); assertEquals(0xFFC00000, d.color)
        assertEquals(TextAlign.CENTER, d.align); assertEquals(12f, d.spaceAfterPt)
        assertEquals("CalloutBox", (back.blocks[0] as ParagraphBlock).paragraph.format.rawStyleId)
        val chapter = (back.blocks[1] as ParagraphBlock).paragraph.format
        assertEquals("Chapter", chapter.rawStyleId); assertEquals(ParagraphStyle.HEADING1, chapter.style)
        assertEquals(back.styles.idOf(ParagraphStyle.HEADING1), back.styles["Chapter"]!!.basedOn)
        assertNull("a built-in's paragraph has no own style", (back.blocks[2] as ParagraphBlock).paragraph.format.rawStyleId)
        val again = DocumentEditor().apply { load(back) }
        assertEquals("unused, Spare is kept too", listOf("Callout Box", "Chapter", "Spare"), again.ownStyles.map { it.name }.sorted())
        // And written again, the same.
        val twice = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(back, it) }.toByteArray())
        assertTrue(twice.styles["CalloutBox"]!!.sameAs(d))
    }

    /** LibreOffice's pool styles (they have a class) come in as its paragraphs use them; its indexes' never; one someone made always. */
    @Test
    fun libreOfficesPoolStylesComeInAsTheyAreUsed() {
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0""""
        val styles = """<office:document-styles $ns><office:styles>""" +
            """<style:style style:name="Standard" style:family="paragraph" style:class="text"/>""" +
            """<style:style style:name="Text_20_body" style:display-name="Text body" style:family="paragraph" style:parent-style-name="Standard" style:class="text"><style:paragraph-properties fo:margin-bottom="0.247cm" fo:line-height="115%"/></style:style>""" +
            """<style:style style:name="List" style:family="paragraph" style:parent-style-name="Text_20_body" style:class="list"/>""" +
            """<style:style style:name="Contents_20_1" style:display-name="Contents 1" style:family="paragraph" style:class="index"/>""" +
            """<style:style style:name="Note_20_box" style:display-name="Note box" style:family="paragraph" style:parent-style-name="Text_20_body"><style:text-properties fo:font-style="italic"/></style:style>""" +
            """</office:styles></office:document-styles>"""
        val content = """<office:document-content $ns><office:automatic-styles>""" +
            """<style:style style:name="P1" style:family="paragraph" style:parent-style-name="Text_20_body"><style:paragraph-properties fo:text-align="center"/></style:style>""" +
            """</office:automatic-styles><office:body><office:text>""" +
            """<text:p text:style-name="P1">Body text, centred</text:p><text:p text:style-name="Text_20_body">Body text</text:p><text:p text:style-name="Standard">Plain</text:p>""" +
            """</office:text></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            listOf("mimetype" to "application/vnd.oasis.opendocument.text", "styles.xml" to styles, "content.xml" to content)
                .forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        val doc = OdtReader.read(bos.toByteArray())
        val paras = doc.blocks.map { (it as ParagraphBlock).paragraph.format.rawStyleId }
        assertEquals(listOf("Textbody", "Textbody", null), paras)
        val body = doc.styles["Textbody"]!!
        assertEquals("Text body", body.name); assertEquals(1.15f, body.lineSpacing!!, 0.001f); assertEquals(7f, body.spaceAfterPt!!, 0.05f)
        assertEquals("the pool's List, unused, and the index style stay out; Note box, made by someone, comes in on its base",
            listOf("Note box", "Text body"), DocumentEditor().apply { load(doc) }.ownStyles.map { it.name }.sorted())
        assertEquals("Textbody", doc.styles["Notebox"]!!.basedOn); assertEquals(true, doc.styles["Notebox"]!!.italic)
    }

    /** With `-Daero.sampleDir`: the styled document as ODT and docx, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val doc = styled().document
        File(dir, "own-styles.odt").outputStream().use { OdtWriter.write(doc, it) }
        File(dir, "own-styles.docx").outputStream().use { DocxWriter.write(doc, it) }
    }
}
