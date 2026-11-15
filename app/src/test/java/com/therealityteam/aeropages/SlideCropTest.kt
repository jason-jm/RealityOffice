package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.ImageSize
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Cropping pictures on slides: PowerPoint's Crop (the scale kept), Crop to Aspect Ratio, a:srcRect and fo:clip. */
class SlideCropTest {

    private fun png(w: Int, h: Int, pxPerMetre: Int? = null) = TestPng.header(w, h, pxPerMetre)

    /** An editor with one 400 × 300 picture framed at 0.2, 0.2 — 0.4 × 0.3 of the slide — and selected. */
    private fun editor(): Pair<SlideEditor, SlideImage> {
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(Slide())))
        e.setImage(png(400, 300), "image/png", 400f, 300f)
        val img = e.selectedImage!!
        img.xPct = 0.2f; img.yPct = 0.2f; img.wPct = 0.4f; img.hPct = 0.3f
        return e to img
    }

    @Test
    fun cropKeepsThePictureWhereItIs() {
        val (e, img) = editor()
        e.cropImage(0.25f, 0f, 0f, 0f)
        assertEquals("the left quarter goes, the rest stays put", 0.3f, img.xPct, 1e-5f)
        assertEquals(0.3f, img.wPct, 1e-5f)
        assertEquals("height untouched", 0.3f, img.hPct, 1e-5f)
        e.cropImage(0.25f, 0.1f, 0.1f, 0f)
        assertEquals(0.3f, img.xPct, 1e-5f)
        assertEquals(0.4f * 0.65f, img.wPct, 1e-5f)
        assertEquals(0.2f + 0.03f, img.yPct, 1e-5f)
        assertEquals(0.27f, img.hPct, 1e-5f)
        e.resetImageCrop()
        assertFalse(img.cropped)
        assertEquals("the whole picture back at its scale", 0.2f, img.xPct, 1e-5f)
        assertEquals(0.4f, img.wPct, 1e-5f)
        assertEquals(0.3f, img.hPct, 1e-5f)
        e.undo()
        // Undo brings back the slide as it was (a copy): read the picture it holds now.
        val restored = e.slide.images.single()
        assertEquals(0.25f, restored.cropL, 0f)
        assertEquals(0.1f, restored.cropT, 0f)
        assertEquals(0.3f, restored.xPct, 1e-5f)
    }

    @Test
    fun cropToAspectRatio() {
        val (e, img) = editor()
        e.cropImageToAspect(1f)
        assertEquals("a 4:3 picture loses an eighth either side to be square", 0.125f, img.cropL, 1e-5f)
        assertEquals(0.125f, img.cropR, 1e-5f)
        assertEquals(0f, img.cropT, 0f)
        assertEquals(1f, img.visibleAspect, 1e-5f)
        e.cropImageToAspect(16f / 9f)
        assertEquals(0f, img.cropL, 0f)
        assertEquals((1f - (4f / 3f) / (16f / 9f)) / 2f, img.cropT, 1e-5f)
        assertEquals(16f / 9f, img.visibleAspect, 1e-4f)
    }

    @Test
    fun visiblePixels() {
        val img = SlideImage(ByteArray(0), "image/png", 100f, 50f, cropL = 0.1f, cropT = 0.1f, cropR = 0.2f)
        assertArrayEquals(intArrayOf(10, 5, 80, 50), img.visiblePixels(100, 50))
        // A crop of almost everything still leaves a pixel.
        val thin = SlideImage(ByteArray(0), "image/png", 10f, 10f, cropL = 0.5f, cropR = 0.5f)
        assertTrue(thin.visiblePixels(10, 10).let { it[2] > it[0] })
    }

    @Test
    fun pptxKeepsTheCrop() {
        val (e, img) = editor()
        e.cropImage(0.125f, 0f, 0.125f, 0.05f)
        val bos = ByteArrayOutputStream()
        PptxWriter.write(e.presentation, bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml, xml.contains("""<a:blip r:embed="""))
        assertTrue(xml, xml.contains("""<a:srcRect l="12500" r="12500" b="5000"/><a:stretch>"""))
        val back = PptxReader.read(bos.toByteArray()).slides[0].images.single()
        assertEquals(0.125f, back.cropL, 1e-5f)
        assertEquals(0.125f, back.cropR, 1e-5f)
        assertEquals(0.05f, back.cropB, 1e-5f)
        assertEquals(img.wPct, back.wPct, 1e-4f)
    }

    @Test
    fun aNegativeSrcRectIsNoCrop() {
        val (e, _) = editor()
        e.cropImage(0.2f, 0f, 0f, 0f)
        val bos = ByteArrayOutputStream()
        PptxWriter.write(e.presentation, bos)
        val parts = OfficeXml.unzip(bos.toByteArray())
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            parts.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(if (name == "ppt/slides/slide1.xml") String(data, Charsets.UTF_8).replace("""<a:srcRect l="20000"/>""", """<a:srcRect l="-5000" t="10000"/>""").toByteArray() else data)
                z.closeEntry()
            }
        }
        val back = PptxReader.read(out.toByteArray()).slides[0].images.single()
        assertEquals("a picture shrunk inside its frame is not cropped on that side", 0f, back.cropL, 0f)
        assertEquals(0.1f, back.cropT, 1e-5f)
    }

    @Test
    fun odpKeepsTheCropAsFoClip() {
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(Slide())))
        // 192 × 96 px at 144 dpi (5669 px / m): a natural size of 96 × 48 pt.
        e.setImage(png(192, 96, pxPerMetre = 5669), "image/png", 192f, 96f)
        e.cropImage(0.25f, 0f, 0f, 0.5f)
        val bos = ByteArrayOutputStream()
        OdpWriter.write(e.presentation, bos)
        val content = String(OfficeXml.unzip(bos.toByteArray())["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content, content.contains("""fo:clip="rect(0.00pt, 0.00pt, 24.00pt, 24.00pt)""""))
        val back = OdpReader.read(bos.toByteArray()).slides[0].images.single()
        assertEquals(0.25f, back.cropL, 1e-3f)
        assertEquals(0.5f, back.cropB, 1e-3f)
        assertEquals("the bitmap's own aspect, not the frame's", 2f, back.aspect, 1e-5f)
    }

    @Test
    fun declaredResolution() {
        assertEquals(144f, ImageSize.dpi(png(10, 10, pxPerMetre = 5669))!!, 0.1f)
        assertEquals(null, ImageSize.dpi(png(10, 10)))
        val jfif300 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF".toByteArray() +
            byteArrayOf(0, 1, 1, 1, 0x01, 0x2C.toByte(), 0x01, 0x2C.toByte(), 0, 0)
        assertEquals(300f, ImageSize.dpi(jfif300)!!, 0f)
        assertEquals(48f to 24f, ImageSize.naturalPt(png(64, 32)))
    }
}
