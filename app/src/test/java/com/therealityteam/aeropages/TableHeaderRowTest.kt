package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Header rows (Word's "repeat as header row") survive docx / odt and toggle from the editor. */
class TableHeaderRowTest {

    private fun table(): Table = Table(mutableListOf(
        TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("Name"))), TableCell(mutableListOf(Paragraph.of("Qty")))), header = true),
        TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("Apple"))), TableCell(mutableListOf(Paragraph.of("3"))))),
        TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("Pear"))), TableCell(mutableListOf(Paragraph.of("5"))))),
    ))

    private fun docWithTable(): Document = Document().also {
        it.blocks.add(ParagraphBlock(Paragraph.of("Before")))
        it.blocks.add(TableBlock(table()))
    }

    private fun entry(bytes: ByteArray, name: String): String {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = zin.nextEntry ?: break; if (e.name == name) return zin.readBytes().decodeToString() }
        error("no $name")
    }

    @Test
    fun docxKeepsTheHeaderRow() {
        val bos = ByteArrayOutputStream()
        DocxWriter.write(docWithTable(), bos)
        val body = entry(bos.toByteArray(), "word/document.xml")
        assertEquals("only the header row carries tblHeader", 1, Regex("<w:trPr><w:tblHeader/></w:trPr>").findAll(body).count())
        assertTrue(body.indexOf("<w:tblHeader/>") < body.indexOf("Apple"))
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        val t = (back.blocks[1] as TableBlock).table
        assertEquals(listOf(true, false, false), t.rows.map { it.header })
        assertEquals(1, t.headerRowCount())
    }

    @Test
    fun odtKeepsTheHeaderRow() {
        val bos = ByteArrayOutputStream()
        OdtWriter.write(docWithTable(), bos)
        val content = entry(bos.toByteArray(), "content.xml")
        assertTrue(content.contains("<table:table-header-rows><table:table-row>"))
        assertEquals(1, Regex("<table:table-header-rows>").findAll(content).count())
        val back = OdtReader.read(bos.toByteArray())
        val t = (back.blocks[1] as TableBlock).table
        assertEquals(listOf(true, false, false), t.rows.map { it.header })
    }

    @Test
    fun onlyALeadingRunCountsAsRepeatingHeader() {
        val t = table()
        t.rows[0].header = false; t.rows[1].header = true
        assertEquals(0, t.headerRowCount())
        t.rows[0].header = true
        assertEquals(2, t.headerRowCount())
    }

    @Test
    fun toggleHeaderRowIsUndoable() {
        val ed = DocumentEditor().also { it.load(docWithTable()) }
        ed.focusParagraph(3)   // "Apple": the second row's first cell
        assertFalse(ed.activeRowIsHeader)
        ed.toggleHeaderRow()
        assertTrue(ed.activeRowIsHeader)
        val t = (ed.document.blocks[1] as TableBlock).table
        assertTrue(t.rows[1].header)
        ed.undo()
        assertFalse((ed.document.blocks[1] as TableBlock).table.rows[1].header)
        ed.redo()
        assertTrue((ed.document.blocks[1] as TableBlock).table.rows[1].header)
    }
}
