package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.DropCap
import com.therealityteam.aeropages.model.DropCaps
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Word's drop cap: what it sets and how big, the commands and editing, docx (Word's frame paragraph), ODT and .doc. */
class DropCapTest {
    private val story = "Once upon a time the letters of a paragraph stood in a row, until the first of them grew three lines tall and the others made room for it beside. " +
        "Printers have set the first letter of a chapter so for centuries: large, sunk into the text, the lines beside it short until it ends and then running on across the column as before."

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    private fun doc(vararg caps: DropCap?) = Document().apply {
        caps.forEach { c -> blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun(story, CharacterFormat(fontSizePt = 12f))), ).also { it.format.dropCap = c })) }
    }

    @Test
    fun theLettersAndTheirSize() {
        val p = Paragraph.of("Word drop caps")
        assertEquals(0, DropCaps.length(p))
        p.format.dropCap = DropCap(length = 3)
        assertEquals("Wor", DropCaps.text(p))
        p.format.dropCap = DropCap(length = 9)
        assertEquals("never past the first word", "Word", DropCaps.text(p))
        assertEquals("a paragraph starting with a space has none", 0, DropCaps.length(Paragraph.of(" x").also { it.format.dropCap = DropCap() }))
        // Three lines 14.4 pt apart over 12 pt text: capitals (0.7 em) from the first line's cap height to the third's baseline.
        assertEquals((2 * 14.4f + 0.7f * 12f) / 0.7f, DropCaps.size("W", 3, 14.4f, 12f), 0.01f)
        assertTrue("a CJK character fills more of its em: smaller", DropCaps.size("春", 3, 14.4f, 12f) < DropCaps.size("W", 3, 14.4f, 12f))
        assertEquals("one line: the text's own size", 12f, DropCaps.size("W", 1, 14.4f, 12f), 0.01f)
        // A new drop cap keeps the opening quote or bracket with the letter, as Word does.
        assertEquals(1, DropCaps.firstLetters(Paragraph.of("Word")))
        assertEquals(2, DropCaps.firstLetters(Paragraph.of("\u201cTea,\u201d she said")))
        assertEquals(2, DropCaps.firstLetters(Paragraph.of("\uff081.1\uff09本季度")))
        assertEquals(0, DropCaps.firstLetters(Paragraph.of("\tTab")))
        val quoted = DocumentEditor().apply { load(Document().apply { blocks.add(ParagraphBlock(Paragraph.of("\u201cTea,\u201d she said, and poured it."))) }) }
        quoted.setSelection(0, 0, 0); quoted.setDropCap(DropCap())
        assertEquals("\u201cT", DropCaps.text(quoted.document.paragraphs()[0]))
    }

    @Test
    fun theCommandsAreOneStepAndEnterLeavesItBehind() {
        val d = doc(null).apply {
            blocks.add(ParagraphBlock(Paragraph.of("An item").also { it.format.listKind = ListKind.BULLET }))
            blocks.add(ParagraphBlock(Paragraph.of(" starts with a space")))
        }
        val e = DocumentEditor().apply { load(d) }
        e.setSelection(0, 5, 5)
        assertTrue(e.canDropCap)
        assertTrue(e.setDropCap(DropCap()))
        assertEquals(DropCap(), e.dropCap)
        assertFalse("already so: nothing recorded", e.setDropCap(DropCap()))
        assertTrue(e.setDropCap(DropCap(inMargin = true, lines = 14, distancePt = 200f)))
        assertEquals("lines and distance kept in Word's range", DropCap(inMargin = true, lines = 10, distancePt = 72f), e.dropCap)
        e.undo()
        assertEquals(DropCap(), e.dropCap)
        e.undo()
        assertNull(e.dropCap)
        e.setSelection(1, 0, 0); assertFalse("not on a list item", e.canDropCap); assertFalse(e.setDropCap(DropCap()))
        e.setSelection(2, 0, 0); assertFalse("not before a space", e.canDropCap)
        // Enter in the text: the drop cap stays with the first letters.
        e.setSelection(0, 0, 0); e.setDropCap(DropCap())
        e.splitParagraph(0, 20)
        assertEquals(DropCap(), e.document.paragraphs()[0].format.dropCap)
        assertNull(e.document.paragraphs()[1].format.dropCap)
        // Enter at the very start: the empty paragraph before it has none, the text keeps it.
        e.splitParagraph(0, 0)
        assertNull(e.document.paragraphs()[0].format.dropCap)
        assertEquals(DropCap(), e.document.paragraphs()[1].format.dropCap)
    }

    @Test
    fun docxKeepsItInWordsFrameParagraph() {
        val d = doc(DropCap(lines = 3, distancePt = 6f, fontFamily = "Georgia"), DropCap(inMargin = true, lines = 2))
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        val xml = part(bytes, "word/document.xml")
        assertTrue(xml.contains("""<w:framePr w:dropCap="drop" w:lines="3" w:hSpace="120""""))
        assertTrue(xml.contains("""w:dropCap="margin" w:lines="2""""))
        assertTrue("the letter in its frame, in its font and size", Regex("""<w:rFonts w:ascii="Georgia"[^>]*/><w:sz w:val="\d+"/>""").containsMatchIn(xml))
        assertTrue("the paragraph goes on without it", xml.contains(">nce upon a time"))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        val paras = back.paragraphs()
        assertEquals("the frames are not paragraphs of their own", 2, paras.size)
        assertEquals(story, paras[0].plainText())
        assertEquals(DropCap(lines = 3, distancePt = 6f, fontFamily = "Georgia", length = 1), paras[0].format.dropCap)
        assertEquals(DropCap(inMargin = true, lines = 2, length = 1), paras[1].format.dropCap)
        assertEquals("the letter in the text's size", 12f, paras[0].runs[0].format.fontSizePt)
    }

    @Test
    fun wordsOwnFrameIsMergedWithItsBookmarks() {
        val body = """<w:p><w:pPr><w:keepNext/><w:framePr w:dropCap="drop" w:lines="4" w:wrap="around" w:vAnchor="text" w:hAnchor="text"/><w:spacing w:after="0" w:line="1103" w:lineRule="exact"/><w:textAlignment w:val="baseline"/></w:pPr>""" +
            """<w:bookmarkStart w:id="0" w:name="Start"/><w:r><w:rPr><w:position w:val="-10"/><w:sz w:val="147"/></w:rPr><w:t>T</w:t></w:r><w:bookmarkEnd w:id="0"/></w:p>""" +
            """<w:p><w:r><w:rPr><w:sz w:val="24"/></w:rPr><w:t>he rest of the story.</w:t></w:r><w:bookmarkStart w:id="1" w:name="End"/><w:bookmarkEnd w:id="1"/></w:p>""" +
            // A frame before a table is a paragraph of its own.
            """<w:p><w:pPr><w:framePr w:dropCap="drop" w:lines="3"/></w:pPr><w:r><w:t>X</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(Document(), it) }.toByteArray()
        val docXml = part(bytes, "word/document.xml")
        val patched = docXml.replaceFirst(Regex("<w:body>.*?(<w:sectPr)", RegexOption.DOT_MATCHES_ALL), "<w:body>$body$1")
        val out = rezip(bytes, "word/document.xml", patched)
        val d = DocxReader.read(ByteArrayInputStream(out))
        val first = d.paragraphs()[0]
        assertEquals("The rest of the story.", first.plainText())
        assertEquals(4, first.format.dropCap?.lines)
        assertEquals(12f, first.runs[0].format.fontSizePt)
        assertEquals(listOf("Start" to 0, "End" to 22), first.bookmarks.filter { !it.end }.map { it.name to it.offset })
        assertEquals("X", d.paragraphs()[1].plainText()); assertNull(d.paragraphs()[1].format.dropCap)
    }

    private fun rezip(bytes: ByteArray, name: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zo ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val data = if (e.name == name) text.toByteArray() else z.readBytes()
                    zo.putNextEntry(java.util.zip.ZipEntry(e.name)); zo.write(data); zo.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun odtKeepsItAsLibreOfficeDoes() {
        val d = doc(DropCap(lines = 3, distancePt = 6f, fontFamily = "Georgia"))
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()
        val content = part(bytes, "content.xml")
        assertTrue(Regex("""<style:drop-cap style:lines="3" style:length="1" style:distance="[^"]+" style:style-name="T\d+"/>""").containsMatchIn(content))
        val back = OdtReader.read(bytes).paragraphs()[0]
        assertEquals(story, back.plainText())
        assertEquals(DropCap(lines = 3, distancePt = 6f, fontFamily = "Georgia", length = 1), back.format.dropCap)
        // "word": the first word.
        val word = OdtReader.read(bytes.let { rezip(it, "content.xml", content.replace("""style:length="1"""", """style:length="word"""")) }).paragraphs()[0]
        assertEquals("Once", DropCaps.text(word))
    }

    /** LibreOffice's .doc of [writeSample]'s docx: a three-line drop cap, then one in the margin over two lines. */
    @Test
    fun wordsBinaryFormatToo() {
        val f = File("src/test/resources/doc/drop-cap.doc")
        if (!f.exists()) return
        val paras = DocReader.read(f.inputStream()).paragraphs()
        val first = paras.first { it.format.dropCap != null }
        assertEquals(3, first.format.dropCap!!.lines)
        assertTrue(first.plainText().startsWith("Once upon"))
    }

    /** With `-Daero.sampleDir`: a drop cap three lines tall, one in the margin, and a CJK one, for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val d = doc(DropCap(lines = 3, distancePt = 4f), null, DropCap(inMargin = true, lines = 2)).apply {
            blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("春天来了，山坡上的草绿了，河边的柳树发芽了，燕子从南方飞回来，在屋檐下筑起了新的窝。孩子们脱下厚厚的冬衣，在田野里奔跑，放起了风筝。", CharacterFormat(fontSizePt = 12f))))
                .also { it.format.dropCap = DropCap(lines = 2) }))
        }
        File(dir, "drop-cap.docx").outputStream().use { DocxWriter.write(d, it) }
        File(dir, "drop-cap.odt").outputStream().use { OdtWriter.write(d, it) }
    }
}
