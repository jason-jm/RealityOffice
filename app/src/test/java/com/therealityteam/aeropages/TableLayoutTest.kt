package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Tables of their own width in the text — left, indented, centred, right — and rows of their own height. */
class TableLayoutTest {

    /** A form-like document: a 200 pt table, one indented 36 pt, a centred one, a right one; rows at least 30 pt and exactly 18 pt. */
    private fun doc(): Document {
        val d = Document(mutableListOf())
        fun table(w: Float?, align: TextAlign = TextAlign.LEFT, indent: Float = 0f) = Table.of(2, 2).also { t ->
            t.widthPt = w; t.align = align; t.indentPt = indent
            t.rows.forEachIndexed { r, row -> row.cells.forEachIndexed { c, cell -> cell.content[0] = Paragraph.of("${'A' + c}${r + 1}") } }
        }
        d.blocks.add(TableBlock(table(200f).also { it.rows[0].heightPt = 30f }))
        d.addParagraph(Paragraph.of(""))
        d.blocks.add(TableBlock(table(150f, indent = 36f).also { it.rows[1].heightPt = 18f; it.rows[1].exactHeight = true }))
        d.addParagraph(Paragraph.of(""))
        d.blocks.add(TableBlock(table(240f, TextAlign.CENTER)))
        d.addParagraph(Paragraph.of(""))
        d.blocks.add(TableBlock(table(285f, TextAlign.RIGHT)))
        d.addParagraph(Paragraph.of(""))
        d.blocks.add(TableBlock(table(null)))
        return d
    }

    private fun tables(d: Document) = d.blocks.filterIsInstance<TableBlock>().map { it.table }

    private fun check(d: Document, where: String) {
        val t = tables(d)
        assertEquals(where, listOf(200f, 150f, 240f, 285f, null), t.map { it.widthPt?.let { w -> Math.round(w).toFloat() } })
        assertEquals(where, listOf(TextAlign.LEFT, TextAlign.LEFT, TextAlign.CENTER, TextAlign.RIGHT, TextAlign.LEFT), t.map { it.align })
        assertEquals(where, 36f, t[1].indentPt, 0.5f)
        assertEquals(where, 30f, t[0].rows[0].heightPt!!, 0.5f); assertEquals(false, t[0].rows[0].exactHeight)
        assertEquals(where, 18f, t[1].rows[1].heightPt!!, 0.5f); assertTrue(where, t[1].rows[1].exactHeight)
        assertNull(where, t[0].rows[1].heightPt)
    }

    @Test
    fun wordAndOdfKeepThem() {
        val d = doc()
        check(DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray())), "docx")
        check(OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()), "odt")
        // The sample LibreOffice made a .doc of (see writeSample).
        val doc = DocReader.read(File("src/test/resources/doc/table-layout.doc").inputStream())
        val t = tables(doc)
        assertEquals(listOf(TextAlign.LEFT, TextAlign.LEFT, TextAlign.CENTER, TextAlign.RIGHT), t.take(4).map { it.align })
        assertEquals(200f, t[0].widthPt!!, 2f); assertEquals(285f, t[3].widthPt!!, 2f)
        assertEquals(30f, t[0].rows[0].heightPt!!, 0.5f)
        assertTrue(t[1].rows[1].exactHeight)
    }

    @Test
    fun whereATableStandsAndHowTallItsRowsAre() {
        val t = Table.of(1, 1)
        assertEquals(0f, PageMetrics.tableOffset(t, 200f, 468f))
        t.align = TextAlign.CENTER; assertEquals(134f, PageMetrics.tableOffset(t, 200f, 468f))
        t.align = TextAlign.RIGHT; assertEquals(268f, PageMetrics.tableOffset(t, 200f, 468f))
        t.align = TextAlign.LEFT; t.indentPt = 400f
        assertEquals("an indent keeps the table on the page", 268f, PageMetrics.tableOffset(t, 200f, 468f))
        val row = TableRow(heightPt = 30f)
        assertEquals(30f, PageMetrics.rowHeight(row, 16f)); assertEquals(40f, PageMetrics.rowHeight(row, 40f))
        row.exactHeight = true
        assertEquals("an exact height whatever the text needs", 30f, PageMetrics.rowHeight(row, 40f))
    }

    /** With `-Daero.sampleDir`: the document as docx, for LibreOffice to make the .doc above of. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "table-layout.docx").outputStream().use { DocxWriter.write(doc(), it) }
        File(dir, "table-layout.odt").outputStream().use { OdtWriter.write(doc(), it) }
    }
}
