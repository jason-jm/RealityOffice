package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLinks
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Links and clicks in a slide show — to a slide, to the show's steps, to an address — through editing, pptx and ODP. */
class SlideLinksTest {

    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34,
        0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, 96, 96, 96, 0, 0, 0, 4, 0, 1, -10, 23, 56, 85, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    private fun titled(t: String) = Slide(title = SlideBox(t, CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER))

    /** An agenda linking to two parts (text, a picture), buttons for the show's steps, a web link; the parts linking back. */
    private fun deck(): Presentation {
        val agenda = titled("Agenda"); val a = titled("Part A"); val b = titled("Part B")
        agenda.body.paragraph.runs.clear()
        agenda.body.paragraph.runs.addAll(listOf(
            TextRun("Part A\n", CharacterFormat(fontSizePt = 24f, link = SlideLinks.toSlide(a))),
            TextRun("Part B\n", CharacterFormat(fontSizePt = 24f, link = SlideLinks.toSlide(b))),
            TextRun("site", CharacterFormat(fontSizePt = 24f, link = "https://example.com/"))))
        fun button(label: String, action: String) = SlideShape(ShapeType.ROUNDED, SlideBox(label, CharacterFormat(fontSizePt = 18f)), action = action)
        agenda.shapes.add(button("Next", SlideLinks.NEXT)); agenda.shapes.add(button("End", SlideLinks.END))
        agenda.images.add(SlideImage(png, "image/png", 1f, 1f, action = SlideLinks.toSlide(b)))
        a.shapes.add(button("Back", SlideLinks.toSlide(agenda))); a.shapes.add(button("Last", SlideLinks.LAST))
        b.shapes.add(button("Previous", SlideLinks.PREVIOUS)); b.shapes.add(button("First", SlideLinks.FIRST))
        return Presentation(mutableListOf(agenda, a, b))
    }

    /** Every link of [p] as where it goes: a slide by its index, else the link itself. */
    private fun shape(p: Presentation): List<String> = buildList {
        fun say(link: String?) = add(SlideLinks.indexIn(p, link)?.let { "slide $it" } ?: link.toString())
        p.slides.forEachIndexed { i, s ->
            s.body.paragraph.runs.filter { it.format.link != null && it.text.isNotBlank() }.forEach { add("$i text ${it.text.trim()}"); say(it.format.link) }
            s.shapes.forEach { add("$i shape ${it.box.text}"); say(it.action) }
            s.images.forEach { add("$i picture"); say(it.action) }
        }
    }

    @Test
    fun linksAndClicksGoThroughPptxAndOdp() {
        val d = deck()
        val expected = shape(d)
        assertTrue(expected.contains("slide 2"))
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(d, it) }.toByteArray()
        assertEquals(expected, shape(PptxReader.read(pptx)))
        val odp = ByteArrayOutputStream().also { OdpWriter.write(d, it) }.toByteArray()
        assertEquals(expected, shape(OdpReader.read(odp)))
    }

    @Test
    fun movingSlidesKeepsWhereLinksGo() {
        val d = deck()
        val partB = d.slides[2]
        // Moved to the front, the slide the agenda's second line goes to is still Part B.
        d.slides.removeAt(2); d.slides.add(0, partB)
        assertEquals(0, SlideLinks.indexIn(d, d.slides[1].body.paragraph.runs[1].format.link))
        val back = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(d, it) }.toByteArray())
        val toB = back.slides[1].body.paragraph.runs.first { it.text.startsWith("Part B") }.format.link
        assertEquals("Part B", back.slides[SlideLinks.indexIn(back, toB)!!].title.text)
        // A link to a slide that is gone is left out.
        d.slides.remove(partB)
        val gone = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(d, it) }.toByteArray())
        assertNull(gone.slides[0].body.paragraph.runs.firstOrNull { it.text.startsWith("Part B") }?.format?.link)
    }

    @Test
    fun aDuplicateIsASlideOfItsOwnAndCopiesAreTheSame() {
        val editor = SlideEditor()
        editor.load(deck())
        val first = editor.presentation.slides[0]
        assertEquals(first.id, first.copyOf().id)
        editor.select(0)
        editor.duplicateSlide()
        assertNotEquals(first.id, editor.presentation.slides[1].id)
        // Links to the original still go to the original.
        assertEquals(0, SlideLinks.indexIn(editor.presentation, editor.presentation.slides[2].shapes[0].action))
    }

    @Test
    fun aSelectedShapeTakesTheLinkAsItsClick() {
        val editor = SlideEditor()
        editor.load(deck())
        editor.select(1)
        val shape = editor.slide.shapes[1]
        editor.selectShape(shape)
        assertEquals(SlideLinks.LAST, editor.currentLink)
        editor.applyLink(SlideLinks.FIRST)
        assertEquals(SlideLinks.FIRST, shape.action)
        editor.undo()
        assertEquals(SlideLinks.LAST, editor.slide.shapes[1].action)
        // A picture, and taking a link off.
        editor.select(0)
        val img = editor.slide.images[0]
        editor.selectImage(img)
        editor.applyLink(null)
        assertNull(editor.slide.images[0].action)
    }

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun libreOfficesLinksToPagesAndItsClicks() {
        val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:script="urn:oasis:names:tc:opendocument:xmlns:script:1.0" """ +
            """xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0""""
        fun click(action: String, href: String? = null) = """<office:event-listeners><presentation:event-listener script:event-name="dom:click" presentation:action="$action"""" +
            (href?.let { """ xlink:href="$it" xlink:type="simple"""" } ?: "") + "/></office:event-listeners>"
        fun shapeWith(listeners: String) = """<draw:custom-shape svg:width="4cm" svg:height="2cm" svg:x="1cm" svg:y="1cm">$listeners<text:p>b</text:p><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>"""
        val content = """<office:document-content $ns><office:body><office:presentation>
            <draw:page draw:name="Agenda"><draw:frame svg:width="10cm" svg:height="3cm" svg:x="1cm" svg:y="5cm"><draw:text-box><text:p><text:a xlink:href="#Part B">to B</text:a></text:p></draw:text-box></draw:frame>
              ${shapeWith(click("show", "#Part A"))}${shapeWith(click("next-page"))}${shapeWith(click("stop"))}${shapeWith(click("show", "https://example.org/"))}${shapeWith(click("sound"))}</draw:page>
            <draw:page draw:name="Part A">${shapeWith(click("previous-page"))}${shapeWith(click("show", "#Slide 1"))}</draw:page>
            <draw:page draw:name="Part B">${shapeWith(click("first-page"))}${shapeWith(click("last-page"))}</draw:page>
            </office:presentation></office:body></office:document-content>"""
        val p = OdpReader.read(zipOf(mapOf("mimetype" to "application/vnd.oasis.opendocument.presentation", "content.xml" to content)))
        val agenda = p.slides[0]
        // The text box may be read as the page's body (no layouts say otherwise): its run wherever it went.
        val toB = (listOf(agenda.title, agenda.body) + agenda.shapes.map { it.box }).flatMap { it.paragraph.runs }.first { it.text == "to B" }
        assertEquals(2, SlideLinks.indexIn(p, toB.format.link))
        val clicks = agenda.shapes.filter { it.box.text == "b" }.map { it.action }
        assertEquals(listOf(SlideLinks.toSlide(p.slides[1]), SlideLinks.NEXT, SlideLinks.END, "https://example.org/", null), clicks)
        assertEquals(listOf(SlideLinks.PREVIOUS, SlideLinks.toSlide(p.slides[0])), p.slides[1].shapes.map { it.action })
        assertEquals(listOf(SlideLinks.FIRST, SlideLinks.LAST), p.slides[2].shapes.map { it.action })
    }

    private fun partOf(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no $name")
    }

    @Test
    fun powerPointsActionsAsItWritesThem() {
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        val slide1 = partOf(pptx, "ppt/slides/slide1.xml")
        assertTrue(slide1.contains("""action="ppaction://hlinkshowjump?jump=nextslide""""))
        assertTrue(slide1.contains("""action="ppaction://hlinkshowjump?jump=endshow""""))
        assertTrue(slide1.contains("""action="ppaction://hlinksldjump""""))
        val rels = partOf(pptx, "ppt/slides/_rels/slide1.xml.rels")
        assertTrue(rels.contains("""relationships/slide" Target="slide3.xml""""))
        // The last slide viewed is the previous one here; a file link is an address.
        val edited = slide1.replace("jump=nextslide", "jump=lastslideviewed")
        val repacked = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                ZipInputStream(ByteArrayInputStream(pptx)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val data = zin.readBytes()
                        z.putNextEntry(ZipEntry(e.name)); z.write(if (e.name == "ppt/slides/slide1.xml") edited.toByteArray() else data); z.closeEntry()
                    }
                }
            }
        }.toByteArray()
        assertEquals(SlideLinks.PREVIOUS, PptxReader.read(repacked).slides[0].shapes[0].action)
    }
}
