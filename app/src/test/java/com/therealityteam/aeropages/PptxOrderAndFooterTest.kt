package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PptxOrderAndFooterTest {

    private fun deck(vararg titles: String): Presentation =
        Presentation(titles.map { Slide(title = SlideBox(it, CharacterFormat(fontSizePt = 40f), TextAlign.CENTER)) }.toMutableList())

    private fun rezip(parts: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test fun slideOrderFollowsPresentationXmlNotPartNumbers() {
        val bos = ByteArrayOutputStream()
        PptxWriter.write(deck("一", "二", "三"), bos)
        val parts = OfficeXml.unzip(bos.toByteArray()).toMutableMap()
        // PowerPoint keeps slideN.xml names when the user reorders; only sldIdLst changes.
        val pres = parts["ppt/presentation.xml"]!!.toString(Charsets.UTF_8)
        val reordered = pres.replace(
            Regex("<p:sldIdLst>.*?</p:sldIdLst>"),
            """<p:sldIdLst><p:sldId id="257" r:id="rId4"/><p:sldId id="256" r:id="rId3"/><p:sldId id="258" r:id="rId5"/></p:sldIdLst>""",
        )
        parts["ppt/presentation.xml"] = reordered.toByteArray()
        val back = PptxReader.read(rezip(parts))
        assertEquals(listOf("二", "一", "三"), back.slides.map { it.title.text })
    }

    @Test fun footerAndSlideNumberRoundTrip() {
        val p = deck("首页", "第二页")
        p.footer = "机密 · 内部资料"
        p.showSlideNumber = true
        val bos = ByteArrayOutputStream()
        PptxWriter.write(p, bos)
        val back = PptxReader.read(bos.toByteArray())
        assertEquals("机密 · 内部资料", back.footer)
        assertTrue(back.showSlideNumber)
        assertEquals("footer is chrome, not body text", "", back.slides[0].body.text)
        assertEquals("首页", back.slides[0].title.text)
        assertEquals("notes untouched", "", back.slides[0].notes)
    }

    @Test fun elementIdsStayUniqueWithManyPictures() {
        val p = deck("图")
        val s = p.slides[0]
        repeat(7) { s.images.add(SlideImage(byteArrayOf(it.toByte()), "image/png", 100f, 80f)) }
        s.shapes.add(SlideShape(ShapeType.RECT)); s.shapes.add(SlideShape(ShapeType.ELLIPSE))
        s.tables.add(SlideTable.create(2, 2))
        p.footer = "f"; p.showSlideNumber = true
        val bos = ByteArrayOutputStream()
        PptxWriter.write(p, bos)
        val xml = OfficeXml.unzip(bos.toByteArray())["ppt/slides/slide1.xml"]!!.toString(Charsets.UTF_8)
        val ids = Regex("""<p:cNvPr id="(\d+)"""").findAll(xml).map { it.groupValues[1].toInt() }.toList()
        assertEquals("every element id unique: $ids", ids.size, ids.toSet().size)
        assertEquals(7, PptxReader.read(bos.toByteArray()).slides[0].images.size)
    }
}
