package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Pictures inside table cells stay in their cell through docx and are inserted there when the caret is in a table. */
class TableImagesTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 1, 0x2C, 0, 0, 0, 0xC8.toByte(), 8, 6, 0, 0, 0)

    @Test
    fun cellPicturesRoundTripInsideTheirCell() {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph.of("Before")))
        val table = Table(mutableListOf(TableRow(mutableListOf(
            TableCell(mutableListOf(Paragraph.of("text only"))),
            TableCell(mutableListOf(Paragraph.of("with picture")), images = mutableListOf(ImageBlock(png, "image/png", 300f, 200f))),
        ))))
        doc.blocks.add(TableBlock(table))
        doc.blocks.add(ParagraphBlock(Paragraph.of("After")))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val bytes = bos.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/document.xml"]!!, Charsets.UTF_8)
        val drawing = xml.indexOf("<w:drawing>")
        assertTrue("the drawing sits inside the second cell", drawing > xml.indexOf("with picture") && drawing < xml.indexOf("</w:tbl>"))
        assertTrue("scaled down to the cell width", xml.contains("""<wp:extent cx="2819400" cy="1879600"/>"""))
        assertTrue(OfficeXml.unzip(bytes).containsKey("word/media/image1.png"))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(listOf("ParagraphBlock", "TableBlock", "ParagraphBlock"), back.blocks.map { it.javaClass.simpleName })
        val cell = (back.blocks[1] as TableBlock).table.rows[0].cells[1]
        assertEquals("the picture's own paragraph adds no empty line", listOf("with picture"), cell.content.map { it.plainText() })
        assertEquals(1, cell.images.size); assertEquals("image/png", cell.images[0].mime); assertArrayEquals(png, cell.images[0].data)
        assertEquals(222f, cell.images[0].widthPt, 0.5f); assertEquals(148f, cell.images[0].heightPt, 0.5f)
        assertTrue((back.blocks[1] as TableBlock).table.rows[0].cells[0].images.isEmpty())
    }

    @Test
    fun insertingWhileTheCaretIsInACellTargetsTheCell() {
        val ed = DocumentEditor()
        ed.load(Document.blank())
        ed.setSelection(0, 0, 0); ed.replaceText(0, "", "Intro")
        ed.insertTable(1, 2)
        ed.setSelection(1, 0, 0) // first cell
        ed.insertImage(png, "image/png", 120f, 80f)
        val table = (ed.document.blocks[1] as TableBlock).table
        assertEquals(1, table.rows[0].cells[0].images.size)
        // Intro, the table and the paragraph after it (a document ends with one, as in Word).
        assertEquals(3, ed.document.blocks.size)
        ed.undo()
        assertTrue(table.rows[0].cells[0].images.isEmpty() || (ed.document.blocks[1] as TableBlock).table.rows[0].cells[0].images.isEmpty())
        ed.redo()
        assertEquals(1, (ed.document.blocks[1] as TableBlock).table.rows[0].cells[0].images.size)
        // Outside a table the picture is still its own block.
        ed.setSelection(0, 0, 0)
        ed.insertImage(png, "image/png", 120f, 80f)
        assertTrue(ed.document.blocks[1] is ImageBlock)
    }
}
