package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.ImageSize
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.ElementAnim
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pictures are positioned slide elements: real `a:xfrm` frames in pptx, header-derived pixel sizes, editor placement. */
class PptxPictureTest {

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun png(w: Int, h: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + "IHDR".toByteArray() + be32(w) + be32(h) + byteArrayOf(8, 6, 0, 0, 0)
    private fun gif(w: Int, h: Int): ByteArray =
        "GIF89a".toByteArray() + byteArrayOf(w.toByte(), (w shr 8).toByte(), h.toByte(), (h shr 8).toByte(), 0, 0, 0)
    private fun jpeg(w: Int, h: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + ByteArray(14) +
            byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte()) + ByteArray(10)

    @Test
    fun imageSizeFromHeaders() {
        assertEquals(400f to 200f, ImageSize.of(png(400, 200)))
        assertEquals(320f to 240f, ImageSize.of(gif(320, 240)))
        assertEquals(320f to 240f, ImageSize.of(jpeg(320, 240)))
        assertNull(ImageSize.of(byteArrayOf(1, 2, 3)))
        assertNull(ImageSize.of("not an image at all".toByteArray()))
    }

    @Test
    fun writerEmitsRealFramesAndReaderRecoversThem() {
        val slide = Slide()
        slide.images.add(SlideImage(png(400, 200), "image/png", 400f, 200f, 0.1f, 0.2f, 0.3f, 0.4f, mutableListOf(ElementAnim(AnimEffect.FADE, durationMs = 600))))
        slide.images.add(SlideImage(gif(320, 240), "image/gif", 320f, 240f, 0.6f, 0.55f, 0.35f, 0.4f))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(slide)), bos)
        val bytes = bos.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("""<a:off x="1219200" y="1371600"/><a:ext cx="3657600" cy="2743200"/>"""))
        assertTrue("no private hint in the alt text", xml.contains("""name="Picture 1"/>""") && !xml.contains("aero-anim"))
        assertTrue(xml.contains("""presetID="10" presetClass="entr""""))
        val back = PptxReader.read(bytes).slides[0]
        assertEquals(2, back.images.size)
        val a = back.images[0]
        assertEquals(0.1f, a.xPct, 0.0005f); assertEquals(0.2f, a.yPct, 0.0005f); assertEquals(0.3f, a.wPct, 0.0005f); assertEquals(0.4f, a.hPct, 0.0005f)
        a.anims.single().let { assertEquals(AnimEffect.FADE, it.effect); assertEquals(600, it.durationMs) }
        assertEquals(400f, a.widthPt); assertEquals(200f, a.heightPt)
        assertArrayEquals(png(400, 200), a.data)
        val b = back.images[1]
        assertEquals("image/gif", b.mime); assertEquals(0.6f, b.xPct, 0.0005f); assertEquals(0.35f, b.wPct, 0.0005f)
        assertTrue(b.anims.isEmpty()); assertEquals(320f, b.widthPt)
    }

    @Test
    fun readerParsesForeignPicFramesTimingAndBareBlips() {
        val slideXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title 1"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>Pics</a:t></a:r></a:p></p:txBody></p:sp>
<p:pic><p:nvPicPr><p:cNvPr id="5" name="Picture 4"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="6096000" y="0"/><a:ext cx="6096000" cy="3429000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>
<p:pic><p:nvPicPr><p:cNvPr id="6" name="Picture 5"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId3"/></p:blipFill><p:spPr/></p:pic>
<p:sp><p:nvSpPr><p:cNvPr id="7" name="Filled"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1000000" cy="1000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:blipFill><a:blip r:embed="rId4"/></a:blipFill></p:spPr></p:sp>
</p:spTree></p:cSld>
<p:timing><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst><p:seq concurrent="1" nextAc="seek"><p:cTn id="2" dur="indefinite" nodeType="mainSeq"><p:childTnLst>
<p:par><p:cTn id="3" fill="hold"><p:stCondLst><p:cond delay="indefinite"/></p:stCondLst><p:childTnLst><p:par><p:cTn id="4" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:par><p:cTn id="8" presetID="10" presetClass="entr" presetSubtype="0" fill="hold" nodeType="clickEffect"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:set><p:cBhvr><p:cTn id="9" dur="1" fill="hold"/><p:tgtEl><p:spTgt spid="5"/></p:tgtEl><p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="visible"/></p:to></p:set>
<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="10" dur="1200"/><p:tgtEl><p:spTgt spid="5"/></p:tgtEl></p:cBhvr></p:animEffect>
</p:childTnLst></p:cTn></p:par>
</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>
</p:childTnLst></p:cTn></p:seq></p:childTnLst></p:cTn></p:par></p:tnLst></p:timing>
</p:sld>"""
        val slideRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image1.png"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image2.jpg"/><Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image3.gif"/></Relationships>"""
        val presentation = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>"""
        val presRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            val parts = listOf(
                "ppt/presentation.xml" to presentation.toByteArray(), "ppt/_rels/presentation.xml.rels" to presRels.toByteArray(),
                "ppt/slides/slide1.xml" to slideXml.toByteArray(), "ppt/slides/_rels/slide1.xml.rels" to slideRels.toByteArray(),
                "ppt/media/image1.png" to png(800, 450), "ppt/media/image2.jpg" to jpeg(320, 240), "ppt/media/image3.gif" to gif(64, 64),
            )
            for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c); z.closeEntry() }
        }
        val slide = PptxReader.read(bos.toByteArray()).slides[0]
        assertEquals("Pics", slide.title.text)
        assertEquals(3, slide.images.size)
        val pic = slide.images[0]
        assertEquals(0.5f, pic.xPct, 0.0005f); assertEquals(0f, pic.yPct, 0.0005f); assertEquals(0.5f, pic.wPct, 0.0005f); assertEquals(0.5f, pic.hPct, 0.0005f)
        assertEquals(800f, pic.widthPt); assertEquals(450f, pic.heightPt)
        pic.anims.single().let { assertEquals(AnimEffect.FADE, it.effect); assertEquals(1200, it.durationMs) }
        val noFrame = slide.images[1]
        assertEquals("image/jpeg", noFrame.mime); assertEquals(0.25f, noFrame.xPct, 0.0005f); assertEquals(0.5f, noFrame.wPct, 0.0005f)
        assertEquals(320f, noFrame.widthPt); assertTrue(noFrame.anims.isEmpty())
        val bare = slide.images[2]
        assertEquals("image/gif", bare.mime); assertEquals(64f, bare.widthPt)
        assertEquals(1, slide.shapes.size) // the blip-filled rectangle is still a shape
    }

    @Test
    fun resizingNeverDistortsAPicture() {
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(Slide())))
        e.setImage(png(100, 100), "image/png", 100f, 100f)
        val img = e.selectedImage!!
        img.xPct = 0.6f; img.yPct = 0.1f; img.wPct = 0.1f; img.hPct = 0.1f * 16f / 9f
        e.scaleImage(10f) // stops at the slide's right edge
        assertEquals(1f, img.xPct + img.wPct, 0.001f); assertEquals(img.wPct * 16f / 9f, img.hPct, 0.001f)
        e.scaleImage(0.001f) // stops at 2% of the slide
        assertEquals(0.02f, img.wPct, 0.001f); assertEquals(0.02f * 16f / 9f, img.hPct, 0.001f)
        e.transformSelected(0f, 0f, 2f) // a two-hand pinch scales about the centre, proportions kept
        assertEquals(0.04f, img.wPct, 0.001f); assertEquals(0.04f * 16f / 9f, img.hPct, 0.001f)
        e.resizeSelected(0f, 0.9f) // a pull straight down grows both ways, until an edge (the right one here)
        assertEquals(1f, img.xPct + img.wPct, 0.001f); assertEquals(img.wPct * 16f / 9f, img.hPct, 0.001f)
    }

    @Test
    fun editorPlacesPicturesKeepsAspectAndUndoes() {
        val e = SlideEditor()
        // A body with text: no free content placeholder to take (ContentPlaceholderTest), so pictures cascade in the middle.
        e.load(Presentation(mutableListOf(Slide(body = com.therealityteam.aeropages.model.SlideBox("Points")))))
        e.setImage(png(400, 200), "image/png", 400f, 200f)
        val first = e.selectedImage!!
        assertEquals(1, e.slide.images.size)
        // A 2:1 picture on a 16:9 slide is height-limited to 42%: width = 0.42 * 2 / (16 / 9).
        assertEquals(0.42f, first.hPct, 0.001f); assertEquals(0.4725f, first.wPct, 0.001f)
        assertEquals((1f - 0.4725f) / 2f, first.xPct, 0.001f); assertEquals(0.5f, first.yPct, 0.001f)
        e.setImage(png(100, 100), "image/png", 100f, 100f)
        val second = e.selectedImage!!
        assertNotSame(first, second)
        assertEquals(0.42f, second.hPct, 0.001f); assertEquals(0.23625f, second.wPct, 0.001f)
        assertEquals((1f - 0.23625f) / 2f + 0.03f, second.xPct, 0.001f); assertEquals(0.53f, second.yPct, 0.001f)
        e.moveSelected(0.1f, 0.02f); e.moveSelected(0.1f, 0.02f) // one coalesced undo step
        assertEquals((1f - 0.23625f) / 2f + 0.23f, second.xPct, 0.001f); assertEquals(0.57f, second.yPct, 0.001f)
        e.moveSelected(0f, 0.5f) // clamped so the picture stays on the slide
        assertEquals(1f - 0.42f, second.yPct, 0.001f)
        e.moveSelected(0f, -0.01f)
        assertEquals(0.57f, second.yPct, 0.001f)
        e.resizeSelected(-0.05f, 0f) // the corner grip keeps a picture's proportions: narrower is shorter too
        assertEquals(0.18625f, second.wPct, 0.001f); assertEquals(0.42f * 0.18625f / 0.23625f, second.hPct, 0.001f)
        e.resizeSelected(0.1f, 0f) // growing stops at the slide's bottom edge, the proportions still kept
        assertEquals(1f, second.yPct + second.hPct, 0.001f); assertEquals(0.43f * 0.23625f / 0.42f, second.wPct, 0.001f)
        e.alignShape('T')
        assertEquals(0f, second.yPct, 0.001f)
        second.hPct = 0.2f // squeezed, as a file may have it
        e.resetImageAspect() // keeps the width, restores 1:1 (in slide fractions: h = w * 16 / 9)
        assertEquals(second.wPct * 16f / 9f, second.hPct, 0.001f)
        val w = second.wPct
        e.scaleImage(0.5f)
        assertEquals(w / 2f, second.wPct, 0.001f); assertEquals(w * 8f / 9f, second.hPct, 0.001f)
        e.deleteSelectedImage()
        assertEquals(1, e.slide.images.size); assertNull(e.selectedImage)
        e.undo() // delete
        assertEquals(2, e.slide.images.size)
        e.undo(); e.undo(); e.undo(); e.undo() // scale, aspect, align, the two resizes (one step)
        assertEquals(0.23625f, e.slide.images[1].wPct, 0.001f)
        e.undo() // both moves
        assertEquals(0.53f, e.slide.images[1].yPct, 0.001f)
        e.undo() // second insert
        assertEquals(1, e.slide.images.size)
        e.removeImage() // nothing selected: removes the last picture
        assertTrue(e.slide.images.isEmpty())
        e.undo()
        assertEquals(1, e.slide.images.size)
        // Selecting a picture clears the other selections and drives the object clipboard.
        e.selectImage(e.slide.images[0])
        e.duplicateSelectedObject()
        assertEquals(2, e.slide.images.size)
        assertEquals(e.slide.images[0].xPct + 0.03f, e.slide.images[1].xPct, 0.001f)
        assertTrue(e.selectedImage === e.slide.images[1])
    }
}
