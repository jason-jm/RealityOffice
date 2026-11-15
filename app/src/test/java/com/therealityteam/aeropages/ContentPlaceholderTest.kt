package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.BoxFrame
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * PowerPoint's content placeholder: a table, chart, picture or video put on a Title and Content slide whose body is empty
 * goes into the placeholder, which then asks for no text and is not written.
 */
class ContentPlaceholderTest {
    private fun deck() = Presentation(mutableListOf(Slide(title = SlideBox("Results", CharacterFormat(fontSizePt = 36f)))))
    private val content = BoxFrame.defaultFor(SlideLayout.TITLE_CONTENT, false)

    @Test
    fun aTableChartOrPictureTakesTheFreePlaceholderOnlyOnce() {
        val e = SlideEditor().apply { load(deck()) }
        assertTrue(e.slide.contentFree)
        e.addTable(3, 4)
        val t = e.slide.tables.single()
        assertEquals(content.xPct, t.xPct, 1e-4f); assertEquals(content.yPct, t.yPct, 1e-4f); assertEquals(content.wPct, t.wPct, 1e-4f)
        assertEquals("three rows of 0.4 inch", 3 * 28.8f / e.presentation.slideSize.heightPt, t.hPct, 1e-4f)
        assertTrue(e.slide.contentTaken); assertFalse(e.slide.contentFree)
        // Taken: the next object goes where it always did.
        e.addChart(ChartType.COLUMN)
        assertFalse(e.slide.charts.single().xPct == content.xPct && e.slide.charts.single().wPct == content.wPct)
        // Undo the chart and the table: the placeholder is free again.
        e.undo(); e.undo()
        assertTrue(e.slide.contentFree)
        e.addChart(ChartType.PIE)
        e.slide.charts.single().let { c -> assertEquals(content.wPct, c.wPct, 1e-4f); assertEquals(content.hPct, c.hPct, 1e-4f) }
        e.undo()
        // A picture: as large as fits, keeping its shape, in the placeholder's middle.
        e.setImage(TestPng.blend(400, 200, 0x3366CC), "image/png", 400f, 200f)
        val img = e.slide.images.single()
        assertTrue("within the placeholder", img.wPct <= content.wPct + 1e-4f && img.hPct <= content.hPct + 1e-4f)
        assertTrue("as large as fits", kotlin.math.abs(img.wPct - content.wPct) < 1e-3f || kotlin.math.abs(img.hPct - content.hPct) < 1e-3f)
        assertEquals("centred in the placeholder", content.xPct + content.wPct / 2f, img.xPct + img.wPct / 2f, 1e-3f)
        assertEquals(content.yPct + content.hPct / 2f, img.yPct + img.hPct / 2f, 1e-3f)
        assertTrue(e.slide.contentTaken)
    }

    @Test
    fun aBodyWithTextOrASoundsIconLeavesThePlaceholderAlone() {
        val withText = SlideEditor().apply { load(deck()) }
        withText.slide.body.paragraph.runs.first().text = "A point"
        assertFalse(withText.slide.contentFree)
        withText.addTable(2, 2)
        assertEquals(0.10f, withText.slide.tables.single().xPct, 1e-4f)
        // A sound's icon sits small in the middle and does not take the placeholder.
        val e = SlideEditor().apply { load(deck()) }
        val tone = SlideMedia(SlideMedia.Kind.AUDIO, "audio/mp4", byteArrayOf(1, 2, 3), null, "tone.m4a")
        e.insertMedia(tone, TestPng.blend(64, 64, 0x888888), "image/png", 64f, 64f)
        assertFalse(e.slide.contentTaken)
        assertTrue(e.slide.contentFree)
        // A title-only slide, a blank one: nothing to take.
        val blank = SlideEditor().apply { load(Presentation(mutableListOf(Slide(layout = SlideLayout.BLANK)))) }
        assertFalse(blank.slide.contentFree)
    }

    @Test
    fun aTakenPlaceholderIsLeftOutOfPptxAndOdp() {
        val e = SlideEditor().apply { load(deck()) }
        e.addTable(3, 3)
        val p = e.presentation
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val slideXml = part(pptx, "ppt/slides/slide1.xml")
        assertFalse("no body placeholder over the table", slideXml.contains("name=\"Content\""))
        assertTrue("the table is the placeholder, as PowerPoint writes it", Regex("<p:graphicFrame>.*?<p:ph idx=\"1\"/>").containsMatchIn(slideXml))
        val odp = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        assertFalse(part(odp, "content.xml").contains("presentation:class=\"outline\""))
        for ((name, back) in listOf("pptx" to PptxReader.read(pptx), "odp" to OdpReader.read(odp))) {
            val s = back.slides.single()
            assertEquals("", s.body.text)
            assertEquals(1, s.tables.size)
            assertEquals(name, SlideLayout.TITLE_CONTENT, s.layout)
            assertTrue("$name: read back, it is still taken", s.contentTaken)
        }
        // Without the table the empty placeholder is written, so it asks for text again.
        val free = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        assertTrue(part(free, "ppt/slides/slide1.xml").contains("name=\"Content\""))
    }

    @Test
    fun aVideoInThePlaceholderIsWrittenAsIt() {
        val e = SlideEditor().apply { load(deck()) }
        val clip = SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", byteArrayOf(0, 0, 0, 24), null, "clip.mp4")
        e.insertMedia(clip, TestPng.blend(160, 90, 0x224466), "image/png", 160f, 90f)
        assertTrue(e.slide.contentTaken)
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(e.presentation, it) }.toByteArray()
        // CT_ApplicationNonVisualDrawingProps: the placeholder before the media.
        assertTrue(part(pptx, "ppt/slides/slide1.xml").contains("""<p:nvPr><p:ph idx="1"/><a:videoFile"""))
        val back = PptxReader.read(pptx).slides.single()
        assertEquals(SlideLayout.TITLE_CONTENT, back.layout)
        assertTrue(back.contentTaken)
    }

    private fun part(zip: ByteArray, name: String): String {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no $name")
    }
}
