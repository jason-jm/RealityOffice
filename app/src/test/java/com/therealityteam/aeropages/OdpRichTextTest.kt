package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** ODP slides keep per-run formatting: differently styled stretches become text:span / text:a and read back as runs. */
class OdpRichTextTest {

    private fun contentXml(bytes: ByteArray): String {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) {
            val e = zin.nextEntry ?: break
            if (e.name == "content.xml") return zin.readBytes().decodeToString()
        }
        error("no content.xml")
    }

    @Test
    fun runsSurviveTheOdpRoundTrip() {
        val title = SlideBox(Paragraph(mutableListOf(
            TextRun("Bold red ", CharacterFormat(fontSizePt = 40f, bold = true, color = 0xFFFF0000)),
            TextRun("plain", CharacterFormat(fontSizePt = 40f)),
        ), ParagraphFormat(align = TextAlign.CENTER)))
        val body = SlideBox(Paragraph(mutableListOf(
            TextRun("Line one\nLine ", CharacterFormat(fontSizePt = 22f)),
            TextRun("two", CharacterFormat(fontSizePt = 22f, italic = true, color = 0xFF008000)),
            TextRun("\n", CharacterFormat(fontSizePt = 22f)),
            TextRun("A link", CharacterFormat(fontSizePt = 22f, link = "https://example.com")),
            TextRun(" and big", CharacterFormat(fontSizePt = 30f, underline = true)),
        )))
        val pres = Presentation(mutableListOf(Slide(title = title, body = body)))
        pres.slides[0].shapes.add(SlideShape(ShapeType.RECT, SlideBox(Paragraph(mutableListOf(
            TextRun("Shape ", CharacterFormat(fontSizePt = 18f)),
            TextRun("accent", CharacterFormat(fontSizePt = 18f, bold = true, color = 0xFF2B579A)),
        )))))

        val bos = ByteArrayOutputStream()
        OdpWriter.write(pres, bos)
        val xml = contentXml(bos.toByteArray())
        assertTrue("differing runs become spans", xml.contains("<text:span text:style-name=\"T"))
        assertTrue("a span can undo the paragraph's bold", xml.contains("fo:font-weight=\"normal\""))
        assertTrue(xml.contains("<text:a xlink:type=\"simple\" xlink:href=\"https://example.com\">"))
        assertTrue("lines are separate paragraphs", Regex("<text:p [^>]*>Line one</text:p>").containsMatchIn(xml))

        val back = OdpReader.read(bos.toByteArray())
        val t = back.slides[0].title.paragraph
        assertEquals("Bold red plain", t.plainText())
        assertEquals(TextAlign.CENTER, t.format.align)
        val boldRed = t.runs.first { it.text.startsWith("Bold") }
        assertTrue(boldRed.format.bold); assertEquals(0xFFFF0000, boldRed.format.color); assertEquals(40f, boldRed.format.fontSizePt)
        val plain = t.runs.first { it.text == "plain" }
        assertFalse("span switched the weight back to normal", plain.format.bold)
        assertNull("default colour again (black) is not an override", plain.format.color?.takeIf { it != 0xFF000000L })

        val b = back.slides[0].body.paragraph
        assertEquals("Line one\nLine two\nA link and big", b.plainText())
        val two = b.runs.first { it.text == "two" }
        assertTrue(two.format.italic); assertEquals(0xFF008000, two.format.color)
        assertEquals("https://example.com", b.runs.first { it.text == "A link" }.format.link)
        val big = b.runs.first { it.text == " and big" }
        assertTrue(big.format.underline); assertEquals(30f, big.format.fontSizePt)
        assertNull(b.runs.first { it.text.startsWith("Line one") }.format.link)

        val sh = back.slides[0].shapes[0].box.paragraph
        assertEquals("Shape accent", sh.plainText())
        assertTrue(sh.runs.first { it.text == "accent" }.format.bold)
        assertEquals(0xFF2B579A, sh.runs.first { it.text == "accent" }.format.color)
    }

    @Test
    fun uniformBoxWritesNoSpans() {
        val pres = Presentation(mutableListOf(Slide(
            title = SlideBox("Just a title", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
            body = SlideBox("one\ntwo", CharacterFormat(fontSizePt = 22f)),
        )))
        val bos = ByteArrayOutputStream()
        OdpWriter.write(pres, bos)
        val xml = contentXml(bos.toByteArray())
        assertFalse(xml.contains("<text:span"))
        val back = OdpReader.read(bos.toByteArray())
        assertEquals("one\ntwo", back.slides[0].body.text)
        assertTrue(back.slides[0].title.paragraph.runs.all { it.format.bold })
    }
}
