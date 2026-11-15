package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.FloatAlign
import com.therealityteam.aeropages.model.FloatFrame
import com.therealityteam.aeropages.model.FloatPlacement
import com.therealityteam.aeropages.model.FloatPosition
import com.therealityteam.aeropages.model.FloatWrap
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Text set beside floating objects (square wrapping): which objects it goes beside, and on which side. */
class WrapAroundTest {

    @Test
    fun theTextGoesBesideSquareWrappedObjects() {
        val square = FloatPosition(0f, FloatFrame.COLUMN, null, 0f, FloatFrame.PARAGRAPH, null, FloatWrap.SQUARE)
        // A square-wrapped object that moves with its paragraph stands on the page now; one the text goes above and below stays in the flow.
        assertTrue(square.onPage)
        assertFalse(square.copy(wrap = FloatWrap.TOP_BOTTOM).onPage)
        // Room beside it: the text goes beside; too little: it steps over the object's height.
        assertTrue(FloatPlacement.textBeside(square, 150f, 450f))
        assertFalse(FloatPlacement.textKeepsClear(square, 150f, 450f))
        assertFalse(FloatPlacement.textBeside(square, 400f, 450f))
        assertTrue(FloatPlacement.textKeepsClear(square, 400f, 450f))
        assertFalse(FloatPlacement.textBeside(square.copy(wrap = FloatWrap.BEHIND), 150f, 450f))
        // On its wider side: a picture at the column's left leaves the lines its width and Word's distance on their left.
        assertEquals(159f to 0f, FloatPlacement.beside(100f, 150f, 100f, 450f, 9f))
        // At the right: the room on the lines' right.
        assertEquals(0f to 159f, FloatPlacement.beside(400f, 150f, 100f, 450f, 9f))
    }

    /** A picture [w] × [h] pixels: a diagonal blend from colour [rgb] to white, as a PNG (written by hand: no AWT here). */
    private fun picture(w: Int, h: Int, rgb: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) {
                val t = (x + y).toFloat() / (w + h)
                for (shift in intArrayOf(16, 8, 0)) { val c = (rgb shr shift) and 0xFF; raw.write((c + (255 - c) * t * 0.7f).toInt()) }
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val d = java.io.DataOutputStream(out)
            d.writeInt(data.size)
            val body = type.toByteArray() + data
            d.write(body)
            d.writeInt(java.util.zip.CRC32().apply { update(body) }.value.toInt())
        }
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
        chunk("IHDR", ihdr)
        chunk("IDAT", ByteArrayOutputStream().also { b -> java.util.zip.DeflaterOutputStream(b).use { it.write(raw.toByteArray()) } }.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun document(): Document {
        val text = "Square wrapping sets the text beside a picture: the lines it stands beside are shorter, and those below it run the full width again. " +
            "This paragraph is long enough to run past the picture, so a reader sees both the narrowed lines and the full ones that follow them. "
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Text beside pictures"))
        doc.addParagraph(Paragraph.of(text.repeat(2)))
        // A photo at the column's left, moving with the paragraph after it.
        doc.blocks.add(ImageBlock(picture(280, 240, 0x2E75B6), "image/png", 140f, 120f,
            floating = FloatPosition(0f, FloatFrame.COLUMN, null, 6f, FloatFrame.PARAGRAPH, null, FloatWrap.SQUARE)))
        doc.addParagraph(Paragraph.of(text.repeat(3)))
        // One at the right margin.
        doc.blocks.add(ImageBlock(picture(300, 200, 0xC55A11), "image/png", 150f, 100f,
            floating = FloatPosition(0f, FloatFrame.MARGIN, FloatAlign.END, 12f, FloatFrame.PARAGRAPH, null, FloatWrap.SQUARE)))
        doc.addParagraph(Paragraph.of(text.repeat(3)))
        // A centred paragraph beside a small picture at the left: its lines centred in the room beside it.
        doc.addParagraph(Paragraph.of(text.repeat(2)).also { it.format.align = com.therealityteam.aeropages.model.TextAlign.CENTER })
        doc.blocks.add(ImageBlock(picture(200, 160, 0x548235), "image/png", 100f, 80f,
            floating = FloatPosition(0f, FloatFrame.COLUMN, null, 0f, FloatFrame.PARAGRAPH, null, FloatWrap.SQUARE)))
        doc.addParagraph(Paragraph.of(text))
        return doc
    }

    /** With `-Daero.sampleDir`: the document as docx and ODT, for the canvas and LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "wrap-around.docx").outputStream().use { DocxWriter.write(document(), it) }
        File(dir, "wrap-around.odt").outputStream().use { OdtWriter.write(document(), it) }
    }
}
