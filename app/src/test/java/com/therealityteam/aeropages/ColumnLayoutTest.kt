package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Text columns as Word sets them: how many, the space between them, a line between; the files and the command. */
class ColumnLayoutTest {

    private fun doc(cols: Int, gap: Float, line: Boolean) = Document().apply {
        page.columns = cols; page.columnGapPt = gap; page.columnLine = line
        repeat(40) { blocks.add(ParagraphBlock(Paragraph.of("Columns of text, as a newsletter sets them, with a line between."))) }
    }

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    @Test
    fun asManyColumnsAsFitAtWordsLeastWidth() {
        val pg = PageSetup(widthPt = 612f, marginLeftPt = 72f, marginRightPt = 72f)   // 468 pt of text
        pg.columns = 3; pg.columnGapPt = 36f
        assertEquals(3, PageMetrics.columnsOf(pg)); assertEquals(36f, PageMetrics.columnGapOf(pg), 0f)
        pg.columns = 6
        assertEquals("six would be 48 pt each", 6, PageMetrics.columnsOf(pg))
        pg.columnGapPt = 72f
        assertEquals("with 72 pt gaps five are just 36 pt", 5, PageMetrics.columnsOf(pg))
        pg.columns = 1
        assertEquals("one column: no gap", 0f, PageMetrics.columnGapOf(pg), 0f)
    }

    @Test
    fun docxKeepsTheSpaceAndTheLine() {
        val out = ByteArrayOutputStream().also { DocxWriter.write(doc(4, 18f, true), it) }.toByteArray()
        assertTrue(part(out, "word/document.xml").contains("""<w:cols w:num="4" w:space="360" w:sep="1"/>"""))
        val back = DocxReader.read(ByteArrayInputStream(out)).page
        assertEquals(4, back.columns); assertEquals(18f, back.columnGapPt, 0.01f); assertTrue(back.columnLine)
        // Word's own: no space given is half an inch; columns listed one by one (unequal widths) read as equal.
        fun read(cols: String) = DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(doc(2, 36f, false), it) }.toByteArray()
            .let { bytes -> rezip(bytes, "word/document.xml") { it.replace(Regex("<w:cols [^>]*/>"), cols) } })).page
        read("""<w:cols w:num="2"/>""").let { assertEquals(2, it.columns); assertEquals(36f, it.columnGapPt, 0.01f); assertFalse(it.columnLine) }
        read("""<w:cols w:equalWidth="0" w:sep="1"><w:col w:w="3000" w:space="425"/><w:col w:w="2000" w:space="425"/><w:col w:w="3000"/></w:cols>""").let {
            assertEquals(3, it.columns); assertEquals(21.25f, it.columnGapPt, 0.01f); assertTrue(it.columnLine)
        }
    }

    @Test
    fun odtKeepsTheGapAndTheSeparator() {
        val out = ByteArrayOutputStream().also { OdtWriter.write(doc(3, 24f, true), it) }.toByteArray()
        val styles = part(out, "styles.xml")
        assertTrue(styles.contains("""<style:columns fo:column-count="3" fo:column-gap="24.00pt"><style:column-sep"""))
        val back = OdtReader.read(out).page
        assertEquals(3, back.columns); assertEquals(24f, back.columnGapPt, 0.01f); assertTrue(back.columnLine)
        val plain = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc(2, 36f, false), it) }.toByteArray()).page
        assertEquals(2, plain.columns); assertFalse(plain.columnLine)
    }

    /** LibreOffice's .doc of [writeSample]'s docx: three columns 0.25 in apart with a line between. */
    @Test
    fun wordsBinaryFormatToo() {
        val f = File("src/test/resources/doc/columns-line.doc")
        if (!f.exists()) return
        val pg = DocReader.read(f.inputStream()).page
        assertEquals(3, pg.columns)
        assertEquals(18f, pg.columnGapPt, 0.05f)
        assertTrue(pg.columnLine)
    }

    @Test
    fun theDialogsCommandIsOneUndoStep() {
        val e = DocumentEditor().apply { load(doc(1, 36f, false)) }
        e.setColumnLayout(3, 18f, true, wholeDocument = true)
        assertEquals(3, e.document.page.columns); assertEquals(18f, e.document.page.columnGapPt, 0f); assertTrue(e.document.page.columnLine)
        e.undo()
        assertEquals(1, e.document.page.columns); assertFalse(e.document.page.columnLine)
        e.redo()
        e.setColumnLayout(3, 18f, true, wholeDocument = true)   // the same again: no step
        e.undo()
        assertEquals("the one step undone", 1, e.document.page.columns)
    }

    /** With `-Daero.sampleDir`: a three-column page with a line between, as docx and ODT, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "columns-line.docx").outputStream().use { DocxWriter.write(doc(3, 18f, true), it) }
        File(dir, "columns-line.odt").outputStream().use { OdtWriter.write(doc(3, 18f, true), it) }
    }

    /** [bytes] (a zip) with its [name] entry changed by [change]. */
    private fun rezip(bytes: ByteArray, name: String, change: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zo ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zi ->
                while (true) {
                    val e = zi.nextEntry ?: break
                    val data = zi.readBytes()
                    zo.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zo.write(if (e.name == name) change(data.decodeToString()).encodeToByteArray() else data)
                    zo.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
