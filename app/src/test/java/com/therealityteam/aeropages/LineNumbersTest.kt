package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.LineNumberRestart
import com.therealityteam.aeropages.model.LineNumbering
import com.therealityteam.aeropages.model.LineNumbers
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Word's line numbers: how lines are counted, the commands, docx, ODT and .doc. */
class LineNumbersTest {

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    private fun doc(n: LineNumbering?) = Document().apply {
        page.lineNumbers = n
        repeat(4) { blocks.add(ParagraphBlock(Paragraph.of("Numbered lines, as a contract numbers them in the margin."))) }
    }

    @Test
    fun linesAreCountedAsWordCountsThem() {
        val a = PageSetup().also { it.lineNumbers = LineNumbering(restart = LineNumberRestart.PAGE) }
        val c = LineNumbers.Counter()
        c.page(a); assertEquals(listOf(1, 2, 3), List(3) { c.line() })
        c.page(a); assertEquals("each page starts again", 1, c.line())
        // Every fifth shown, from 3: all are counted.
        val b = PageSetup().also { it.lineNumbers = LineNumbering(start = 3, countBy = 5, restart = LineNumberRestart.SECTION) }
        c.page(b); assertEquals(listOf(0, 0, 5, 0, 0, 0, 0, 10), List(8) { c.line() })
        c.page(b); assertEquals("the same section goes on: 11 to 15", listOf(0, 0, 0, 0, 15), List(5) { c.line() })
        // A section without numbering counts nothing; a continuous one after it starts afresh.
        val none = PageSetup()
        c.page(none); assertFalse(c.numbered); assertEquals(0, c.line())
        val cont = PageSetup().also { it.lineNumbers = LineNumbering(restart = LineNumberRestart.CONTINUOUS) }
        c.page(cont); assertEquals(1, c.line()); c.page(cont); assertEquals("continuous goes on across pages", 2, c.line())
        assertEquals(18f, LineNumbers.distanceOf(LineNumbering()), 0f)
    }

    @Test
    fun theMenuAndTheDialogAreOneUndoStepEach() {
        val e = DocumentEditor().apply { load(doc(null)) }
        e.setLineNumberRestart(LineNumberRestart.CONTINUOUS)
        assertEquals(LineNumbering(restart = LineNumberRestart.CONTINUOUS), e.lineNumbering)
        e.setLineNumbers(LineNumbering(start = 5, countBy = 2, restart = LineNumberRestart.SECTION, distancePt = 24f))
        e.setLineNumbers(LineNumbering(start = 5, countBy = 2, restart = LineNumberRestart.SECTION, distancePt = 24f))   // the same: no step
        e.setLineNumberRestart(LineNumberRestart.PAGE)
        assertEquals("the menu keeps the other settings", LineNumbering(5, 2, LineNumberRestart.PAGE, 24f), e.lineNumbering)
        e.setSelection(1, 0, 0)
        e.toggleSuppressLineNumbers()
        assertTrue((e.document.blocks[1] as ParagraphBlock).paragraph.format.suppressLineNumbers)
        e.undo()
        assertFalse(e.lineNumbersSuppressed)
        e.undo(); e.undo()
        assertEquals(LineNumbering(restart = LineNumberRestart.CONTINUOUS), e.lineNumbering)
        e.setLineNumberRestart(null)
        assertNull(e.lineNumbering)
    }

    @Test
    fun docxKeepsTheSettingsAndTheSuppressedParagraph() {
        val n = LineNumbering(start = 5, countBy = 2, restart = LineNumberRestart.SECTION, distancePt = 24f)
        val d = doc(n).also { (it.blocks[2] as ParagraphBlock).paragraph.format.suppressLineNumbers = true }
        val out = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        val xml = part(out, "word/document.xml")
        assertTrue("Word writes the value before the first", xml.contains("""<w:lnNumType w:countBy="2" w:start="4" w:distance="480" w:restart="newSection"/>"""))
        assertTrue(xml.contains("<w:suppressLineNumbers/>"))
        val back = DocxReader.read(ByteArrayInputStream(out))
        assertEquals(n, back.page.lineNumbers)
        assertEquals(listOf(false, false, true, false), back.blocks.map { (it as ParagraphBlock).paragraph.format.suppressLineNumbers })
        // Word's own defaults: no start (1), no restart (each page), no distance (Auto).
        val plain = DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(doc(LineNumbering()), it) }.toByteArray()))
        assertEquals(LineNumbering(), plain.page.lineNumbers)
        assertNull(DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(doc(null), it) }.toByteArray())).page.lineNumbers)
    }

    @Test
    fun odtKeepsWhatItCanSay() {
        val n = LineNumbering(countBy = 5, restart = LineNumberRestart.CONTINUOUS, distancePt = 20f)
        val d = doc(n).also { (it.blocks[1] as ParagraphBlock).paragraph.format.suppressLineNumbers = true }
        val out = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()
        assertTrue(part(out, "styles.xml").contains("""<text:linenumbering-configuration text:number-lines="true" text:offset="20.00pt""""))
        assertTrue(part(out, "content.xml").contains("""text:number-lines="false""""))
        val back = OdtReader.read(out)
        assertEquals(n, back.page.lineNumbers)
        assertTrue((back.blocks[1] as ParagraphBlock).paragraph.format.suppressLineNumbers)
        assertFalse((back.blocks[0] as ParagraphBlock).paragraph.format.suppressLineNumbers)
        val page = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc(LineNumbering(distancePt = 18f)), it) }.toByteArray())
        assertEquals(LineNumberRestart.PAGE, page.page.lineNumbers?.restart)
        assertNull(OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc(null), it) }.toByteArray()).page.lineNumbers)
    }

    /** LibreOffice's .doc of [writeSample]'s docx: every second line from 5, each section, 24 pt from the text. */
    @Test
    fun wordsBinaryFormatToo() {
        val f = File("src/test/resources/doc/line-numbers.doc")
        if (!f.exists()) return
        val d = DocReader.read(f.inputStream())
        val n = d.page.lineNumbers!!
        assertEquals(2, n.countBy)
        assertEquals(24f, n.distancePt!!, 0.05f)
        assertTrue(d.blocks.filterIsInstance<ParagraphBlock>().any { it.paragraph.format.suppressLineNumbers })
    }

    /** With `-Daero.sampleDir`: a numbered document (every second line from 5, each section, 24 pt out, the third paragraph not numbered). */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val d = Document().apply {
            page.lineNumbers = LineNumbering(start = 5, countBy = 2, restart = LineNumberRestart.SECTION, distancePt = 24f)
            repeat(6) { k ->
                blocks.add(ParagraphBlock(Paragraph.of("Paragraph ${k + 1}: a few lines of text, long enough to wrap onto a second line in the page's width, so that the margin shows its numbers line by line.").also {
                    if (k == 2) it.format.suppressLineNumbers = true
                }))
            }
        }
        File(dir, "line-numbers.docx").outputStream().use { DocxWriter.write(d, it) }
        File(dir, "line-numbers.odt").outputStream().use { OdtWriter.write(d, it) }
    }
}
