package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
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

/** Cropped pictures in documents: Word's Crop (the scale kept), a:srcRect in docx, fo:clip in ODT, pictures in ODT cells. */
class DocumentCropTest {

    private fun pictures(ed: DocumentEditor) = ed.document.blocks.filterIsInstance<ImageBlock>()

    /** A document with one 400 × 300 picture and a table whose second cell holds [cellPicture]. */
    private fun docWith(picture: ImageBlock, cellPicture: ImageBlock): Document {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph.of("Before")))
        doc.blocks.add(picture)
        doc.blocks.add(TableBlock(Table(mutableListOf(TableRow(mutableListOf(
            TableCell(mutableListOf(Paragraph.of("text only"))),
            TableCell(mutableListOf(Paragraph.of("with picture")), images = mutableListOf(cellPicture)),
        ))))))
        doc.blocks.add(ParagraphBlock(Paragraph.of("After")))
        return doc
    }

    @Test
    fun cropKeepsTheScale() {
        val ed = DocumentEditor()
        ed.load(Document.blank())
        ed.insertImage(TestPng.header(400, 300), "image/png", 400f, 300f)
        val img = pictures(ed).single()
        ed.cropImage(img, 0.25f, 0f, 0f, 0f)
        assertEquals("a quarter of the width goes, the height stays", 300f, img.widthPt, 1e-3f)
        assertEquals(300f, img.heightPt, 1e-3f)
        ed.cropImage(img, 0.25f, 0.1f, 0.1f, 0f)
        assertEquals(400f * 0.65f, img.widthPt, 1e-3f)
        assertEquals(270f, img.heightPt, 1e-3f)
        ed.cropImage(img, 0f, 0f, 0f, 0f)
        assertFalse(img.cropped)
        assertEquals("the whole picture back at its size", 400f, img.widthPt, 1e-2f)
        assertEquals(300f, img.heightPt, 1e-2f)
        ed.undo()
        // Undo brings back copies: read the picture the document holds now.
        val back = pictures(ed).single()
        assertEquals(0.1f, back.cropT, 0f)
        assertEquals(270f, back.heightPt, 1e-3f)
        ed.scaleImage(back, 0.5f)
        assertEquals("zooming keeps the crop", 0.1f, back.cropT, 0f)
        assertEquals(130f, back.widthPt, 1e-3f)
        assertEquals(135f, back.heightPt, 1e-3f)
    }

    @Test
    fun zoomingKeepsTheAspect() {
        val ed = DocumentEditor()
        ed.load(Document.blank())
        ed.insertImage(TestPng.header(30, 30), "image/png", 30f, 30f)
        val icon = pictures(ed).single()
        ed.scaleImage(icon, 1.18f)
        assertEquals("a small square picture stays square (it became 48 × 35.4)", 35.4f, icon.widthPt, 1e-3f)
        assertEquals(35.4f, icon.heightPt, 1e-3f)
        repeat(8) { ed.scaleImage(icon, 0.85f) }
        assertEquals("shrinking stops at 16 pt", 16f, icon.widthPt, 1e-3f)
        assertEquals(16f, icon.heightPt, 1e-3f)
        ed.insertImage(TestPng.header(1000, 100), "image/png", 1000f, 100f)
        val wide = pictures(ed).single { it !== icon }
        ed.scaleImage(wide, 1.5f)
        assertEquals("growing stops at 1200 pt wide", 1200f, wide.widthPt, 1e-2f)
        assertEquals(120f, wide.heightPt, 1e-2f)
    }

    @Test
    fun docxKeepsTheCrop() {
        val png = TestPng.header(400, 300)
        val doc = docWith(
            ImageBlock(png, "image/png", 300f, 300f, cropL = 0.25f),
            ImageBlock(png, "image/png", 100f, 37.5f, cropB = 0.5f),
        )
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(xml, Regex("""<a:blip r:embed="rId\d+"/><a:srcRect l="25000"/><a:stretch>""").containsMatchIn(xml))
        assertTrue(xml, xml.contains("""<a:srcRect b="50000"/>"""))
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        val body = back.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(0.25f, body.cropL, 1e-5f)
        assertEquals(0f, body.cropB, 0f)
        assertEquals(300f, body.widthPt, 0.01f)
        val cell = back.blocks.filterIsInstance<TableBlock>().single().table.rows[0].cells[1].images.single()
        assertEquals(0.5f, cell.cropB, 1e-5f)
        assertEquals(37.5f, cell.heightPt, 0.01f)
    }

    @Test
    fun odtKeepsTheCropAndTheCellsPictures() {
        // 192 × 96 px at 144 dpi (5669 px / m): a natural size of 96 × 48 pt.
        val png = TestPng.header(192, 96, pxPerMetre = 5669)
        var doc = docWith(
            ImageBlock(png, "image/png", 72f, 48f, cropL = 0.25f),
            ImageBlock(png, "image/png", 96f, 24f, cropB = 0.5f),
        )
        repeat(2) { round ->
            val bos = ByteArrayOutputStream()
            OdtWriter.write(doc, bos)
            val content = String(OfficeXml.unzip(bos.toByteArray())["content.xml"]!!, Charsets.UTF_8)
            assertTrue(content, content.contains("""fo:clip="rect(0.00pt, 0.00pt, 0.00pt, 24.00pt)""""))
            assertTrue(content, content.contains("""fo:clip="rect(0.00pt, 0.00pt, 24.00pt, 0.00pt)""""))
            val cellXml = content.substringAfter("with picture").substringBefore("</table:table-cell>")
            assertTrue("the cell's picture is written inside the cell: $cellXml", cellXml.contains("<draw:frame"))
            doc = OdtReader.read(bos.toByteArray())
            val body = doc.blocks.filterIsInstance<ImageBlock>().single()
            assertEquals("round ${round + 1}", 0.25f, body.cropL, 1e-3f)
            assertEquals(72f, body.widthPt, 0.01f)
            val cell = doc.blocks.filterIsInstance<TableBlock>().single().table.rows[0].cells[1]
            assertEquals("the picture's paragraph adds no empty line", listOf("with picture"), cell.content.map { it.plainText() })
            assertEquals(0.5f, cell.images.single().cropB, 1e-3f)
            assertEquals(24f, cell.images.single().heightPt, 0.01f)
        }
    }
}
