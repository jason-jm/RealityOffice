package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Text setting no colour of its own reads over what it sits on — LibreOffice's automatic colour, PowerPoint's colour map. */
class TextColorsTest {

    private val navy = 0xFF1F2A44
    private val white = 0xFFFFFFFF

    @Test
    fun inheritedColourWhereItReadsElseBlackOrWhite() {
        assertEquals(TextColors.LIGHT, TextColors.over(null, navy))
        assertEquals(TextColors.DARK, TextColors.over(null, white))
        assertEquals("a dark blue title reads on white", 0xFF2B579A, TextColors.over(0xFF2B579A, white))
        assertEquals("not on navy", TextColors.LIGHT, TextColors.over(0xFF2B579A, navy))
        assertEquals("white text on a white slide turns dark", TextColors.DARK, TextColors.over(white, white))
        assertEquals("over a picture (unknown) the inherited colour stays", 0xFF2B579A, TextColors.over(0xFF2B579A, null))
        assertEquals(TextColors.DARK, TextColors.over(null, null))
        assertEquals("mid grey takes dark text", TextColors.DARK, TextColors.over(null, 0xFF9E9E9E))
    }

    @Test
    fun slidesAndShapesTextDefaults() {
        val dark = Slide(title = SlideBox("Dark"), background = navy)
        val light = Slide(title = SlideBox("Light"))
        val onPicture = Slide(title = SlideBox("Photo")).apply { design = "Photo" }
        val p = Presentation(mutableListOf(dark, light, onPicture),
            designs = mutableListOf(SlideDesign("Photo", picture = SlideImage(byteArrayOf(1), "image/png", 1f, 1f))))
        assertEquals(TextColors.LIGHT, p.titleTextOn(dark).color)
        assertEquals(TextColors.DARK, p.bodyTextOn(light).color)
        assertEquals(TextColors.DARK, p.titleTextOn(onPicture).color)
        p.master.titleColor = 0xFF203864
        assertEquals("the master's dark blue flips on the dark slide", TextColors.LIGHT, p.titleTextOn(dark).color)
        assertEquals("and stays on the light one", 0xFF203864, p.titleTextOn(light).color)
        val paleBox = SlideShape(ShapeType.RECT, fill = 0xFFDDE6F2)
        val deepBox = SlideShape(ShapeType.RECT, fill = 0xFF203864)
        val textBox = SlideShape(ShapeType.TEXTBOX, fill = null)
        assertEquals("a pale shape on the dark slide keeps dark text", TextColors.DARK, p.shapeTextOn(dark, paleBox).color)
        assertEquals(TextColors.LIGHT, p.shapeTextOn(light, deepBox).color)
        assertEquals("a text box reads over the slide", TextColors.LIGHT, p.shapeTextOn(dark, textBox).color)
        val veil = SlideShape(ShapeType.RECT, fill = 0x33000000)
        assertEquals("a faint veil over white is light", TextColors.DARK, p.shapeTextOn(light, veil).color)
    }

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = String(z.readBytes()) } }
        return out
    }

    @Test
    fun pptxMapsDarkSlidesAndKeepsRunsAsTheyAre() {
        val p = Presentation(mutableListOf(
            Slide(title = SlideBox("Light"), body = SlideBox("Text")),
            Slide(title = SlideBox("Dark"), body = SlideBox("Text"), background = navy).apply {
                shapes.add(SlideShape(ShapeType.RECT, SlideBox("Pale box"), fill = 0xFFDDE6F2))
                shapes.add(SlideShape(ShapeType.TEXTBOX, SlideBox("Loose text"), fill = null, border = null))
            },
        ))
        p.master.bodyColor = 0xFF203864
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val files = parts(bytes)
        assertTrue(files["ppt/slides/slide1.xml"]!!.contains("<a:masterClrMapping/>"))
        val dark = files["ppt/slides/slide2.xml"]!!
        assertTrue("PowerPoint's dark map", dark.contains("""<a:overrideClrMapping bg1="dk1" tx1="lt1" bg2="dk2" tx2="lt2""""))
        assertTrue("the pale box's text stays dark", dark.contains("""<a:lvl1pPr><a:defRPr><a:solidFill><a:srgbClr val="1A1A1A"/>"""))
        assertTrue("the master's dark blue body turns white there", dark.contains("""<a:lvl1pPr><a:defRPr><a:solidFill><a:srgbClr val="FFFFFF"/>"""))
        assertTrue("unset master colours are the map's text colour", files["ppt/slideMasters/slideMaster1.xml"]!!.contains("""<p:titleStyle><a:lvl1pPr><a:defRPr b="1"><a:solidFill><a:schemeClr val="tx1"/>""") ||
            files["ppt/slideMasters/slideMaster1.xml"]!!.contains("""<p:titleStyle><a:lvl1pPr><a:defRPr><a:solidFill><a:schemeClr val="tx1"/>"""))
        val back = PptxReader.read(bytes)
        assertNull("the master's title colour still automatic", back.master.titleColor)
        assertEquals(0xFF203864, back.master.bodyColor)
        assertTrue("no run gained a colour", back.slides.all { s -> (listOf(s.title, s.body) + s.shapes.map { it.box }).all { b -> b.paragraph.runs.all { it.format.color == null } } })
    }

    @Test
    fun aPowerPointDarkSlidesTextColourIsLight() {
        val p = Presentation(mutableListOf(Slide(title = SlideBox("Dark"), background = navy)))
        val written = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        // A run coloured with the map's text colour, as PowerPoint writes one.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(written.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var data = zin.readBytes()
                    if (e.name == "ppt/slides/slide1.xml") data = String(data).replaceFirst(Regex("""<a:rPr([^>]*)/>"""), """<a:rPr$1><a:solidFill><a:schemeClr val="tx1"/></a:solidFill></a:rPr>""").toByteArray()
                    z.putNextEntry(ZipEntry(e.name)); z.write(data); z.closeEntry()
                }
            }
        }
        val back = PptxReader.read(out.toByteArray())
        assertEquals("tx1 on a slide mapping it to lt1", 0xFFFFFFFF, back.slides[0].title.paragraph.runs.first().format.color)
        assertFalse(TextColors.isDark(0xFFFFFFFF))
    }
}
