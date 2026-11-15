package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.ObjectClipboard
import com.therealityteam.aeropages.format.ImageSize
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pictures in a .doc — inline ones (a picture character, its PICF and shape in the Data stream) and
 * floating ones (a shape anchored in the text, its picture in the drawing group's store) — read from a
 * file LibreOffice wrote (`resources/doc/pictures.doc`, converted from a docx with a 200×100 PNG, a
 * 300×200 JPEG twice — once cropped — a floating 120×120 PNG and an 80×40 PNG in a table's cell).
 */
class DocPicturesTest {
    private val doc = DocReader.read(javaClass.getResourceAsStream("/doc/pictures.doc")!!)

    private fun kind(b: Block): String = when (b) {
        is ParagraphBlock -> b.paragraph.plainText()
        is ImageBlock -> b.mime
        is TableBlock -> "table"
        else -> b.javaClass.simpleName
    }

    private fun pixels(img: ImageBlock) = ImageSize.of(img.data)!!.let { (w, h) -> "${w.toInt()}x${h.toInt()}" }

    @Test
    fun picturesStandWhereTheTextHasThem() {
        assertEquals(listOf(
            "Pictures in a legacy Word file.",
            "Inline after text: ", "image/png",
            // A paragraph holding only a picture: the picture stands for it.
            "image/jpeg",
            "Cropped below (left 10%, top 20%, right 30%).", "image/jpeg",
            // A floating picture follows the paragraph it is anchored in.
            "A floating picture is anchored in this paragraph, to the right of its text.", "image/png",
            "table", "The end.",
        ), doc.blocks.map(::kind))
    }

    @Test
    fun inlinePicturesKeepTheirSizeAndCrop() {
        val (red, photo, cropped) = doc.blocks.filterIsInstance<ImageBlock>()
        assertEquals("200x100", pixels(red))
        assertEquals(144f, red.widthPt, 0.1f); assertEquals(72f, red.heightPt, 0.1f)
        assertEquals("300x200", pixels(photo))
        assertEquals(216f, photo.widthPt, 0.1f); assertEquals(144f, photo.heightPt, 0.1f)
        // The PICF's crop, in twips of the picture, as fractions; the size is what it leaves.
        assertEquals(0.1f, cropped.cropL, 0.005f); assertEquals(0.2f, cropped.cropT, 0.005f)
        assertEquals(0.3f, cropped.cropR, 0.005f); assertEquals(0f, cropped.cropB, 0.005f)
        assertEquals(150f, cropped.widthPt, 0.5f); assertEquals(80f, cropped.heightPt, 0.5f)
    }

    @Test
    fun floatingPicturesAndPicturesInCells() {
        val floating = doc.blocks.filterIsInstance<ImageBlock>()[3]
        assertEquals("120x120", pixels(floating))
        assertEquals("the anchor's frame", 90f, floating.widthPt, 0.5f); assertEquals(90f, floating.heightPt, 0.5f)
        val cell = (doc.blocks.first { it is TableBlock } as TableBlock).table.rows.single().cells[1]
        assertEquals("a cell of only a picture keeps one empty paragraph", listOf(""), cell.content.map { it.plainText() })
        val img = cell.images.single()
        assertEquals("80x40", pixels(img)); assertEquals(60f, img.widthPt, 0.1f); assertEquals(30f, img.heightPt, 0.1f)
    }

    @Test
    fun pickingCopyingAndDeletingPictures() {
        val e = DocumentEditor()
        e.load(DocReader.read(javaClass.getResourceAsStream("/doc/pictures.doc")!!))
        val red = e.document.blocks.filterIsInstance<ImageBlock>().first()
        e.selectImage(red)
        assertSame(red, e.selectedImage)
        assertEquals("a picked picture copies as one", "", e.copySelectedObject())
        assertTrue((ObjectClipboard.item as ObjectClipboard.Picture).data.contentEquals(red.data))
        val before = e.document.blocks.size
        e.cutSelectedObject()
        assertEquals("cut: copied and gone", before - 1, e.document.blocks.size)
        assertNull(e.selectedImage)
        e.undo()
        assertEquals(before, e.document.blocks.size)
        assertTrue(e.document.blocks[2] is ImageBlock)
        // A picture in a table's cell.
        val cell = { (e.document.blocks.first { it is TableBlock } as TableBlock).table.rows[0].cells[1] }
        val inCell = cell().images.single()
        e.selectImage(inCell)
        assertSame(inCell, e.selectedImage)
        e.deleteImage(inCell)
        assertTrue(cell().images.isEmpty())
        e.undo()
        assertEquals(1, cell().images.size)
        // A picture no longer in the document cannot be picked.
        e.selectImage(inCell)
        assertNull("undo restored a copy, not the deleted object", e.selectedImage)
    }
}
