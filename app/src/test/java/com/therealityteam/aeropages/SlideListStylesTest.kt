package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bullets and numbering in pptx: written with PowerPoint's hanging indent, read from the slide, the layout or the master. */
class SlideListStylesTest {

    @Test
    fun listsAndShapeAlignmentRoundTrip() {
        val slide = Slide(title = SlideBox("Title", CharacterFormat(fontSizePt = 40f), TextAlign.CENTER), body = SlideBox("One\nTwo"))
        slide.body.listStyle = SlideListStyle.BULLET
        val numbered = SlideShape(ShapeType.ROUNDED, SlideBox("First\nSecond", CharacterFormat(fontSizePt = 18f), TextAlign.CENTER))
        numbered.box.listStyle = SlideListStyle.NUMBER
        val note = SlideShape(ShapeType.TEXTBOX, SlideBox("Left note", CharacterFormat(fontSizePt = 14f), TextAlign.LEFT))
        slide.shapes.add(numbered); slide.shapes.add(note)
        val bos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(slide)), bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("""<a:pPr marL="342900" indent="-342900" algn="l"><a:buFont typeface="Arial"/><a:buChar char="•"/></a:pPr>"""))
        assertTrue(xml.contains("""<a:pPr marL="457200" indent="-457200" algn="ctr"><a:buFont typeface="+mj-lt"/><a:buAutoNum type="arabicPeriod"/></a:pPr>"""))
        val back = PptxReader.read(bos.toByteArray()).slides[0]
        assertEquals(SlideListStyle.BULLET, back.body.listStyle)
        assertEquals(SlideListStyle.NONE, back.title.listStyle)
        assertEquals(SlideListStyle.NUMBER, back.shapes[0].box.listStyle)
        assertEquals("a shape's centred text stays centred (it came back left)", TextAlign.CENTER, back.shapes[0].box.align)
        assertEquals(SlideListStyle.NONE, back.shapes[1].box.listStyle)
        assertEquals(TextAlign.LEFT, back.shapes[1].box.align)
    }

    private val a = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
    private val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private val r = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val relsHead = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
    private fun rel(id: String, type: String, target: String) =
        "<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" Target=\"$target\"/>"
    private fun ph(id: Int, type: String?, idx: String?, lstStyle: String, paragraphs: String) = buildString {
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"ph$id\"/><p:cNvSpPr/><p:nvPr><p:ph")
        if (type != null) append(" type=\"$type\"")
        if (idx != null) append(" idx=\"$idx\"")
        append("/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle>$lstStyle</a:lstStyle>$paragraphs</p:txBody></p:sp>")
    }
    private fun paras(vararg lines: String, pPr: String = "") = lines.joinToString("") { "<a:p>$pPr<a:r><a:t>$it</a:t></a:r></a:p>" }

    @Test
    fun aDecksBulletsComeFromTheSlideTheLayoutOrTheMaster() {
        // The master's body style bullets its paragraphs (as PowerPoint's default master does); its title style does not.
        val master = "<p:sldMaster $a $p $r><p:cSld><p:spTree>" +
            ph(2, "title", null, "", "") + ph(3, "body", "1", "", "") +
            "</p:spTree></p:cSld><p:txStyles><p:titleStyle><a:lvl1pPr><a:buNone/><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle>" +
            "<p:bodyStyle><a:lvl1pPr marL=\"228600\" indent=\"-228600\"><a:buFont typeface=\"Arial\"/><a:buChar char=\"•\"/><a:defRPr sz=\"2800\"/></a:lvl1pPr></p:bodyStyle></p:txStyles></p:sldMaster>"
        // Layout 1 keeps the master's bullets; layout 2 (a section header) turns its body's off.
        val layout1 = "<p:sldLayout $a $p $r><p:cSld><p:spTree>" + ph(2, "title", null, "", "") + ph(3, null, "1", "", "") + "</p:spTree></p:cSld></p:sldLayout>"
        val layout2 = "<p:sldLayout $a $p $r><p:cSld><p:spTree>" + ph(2, "title", null, "", "") +
            ph(3, "body", "1", "<a:lvl1pPr><a:buNone/></a:lvl1pPr>", "") + "</p:spTree></p:cSld></p:sldLayout>"
        val slide = { body: String -> "<p:sld $a $p $r><p:cSld><p:spTree>" + ph(2, "title", null, "", paras("T")) + body + "</p:spTree></p:cSld></p:sld>" }
        val slides = listOf(
            1 to slide(ph(3, null, "1", "", paras("Inherits", "the master's bullets"))),
            2 to slide(ph(3, "body", "1", "", paras("A section", "subtitle"))),
            1 to slide(ph(3, null, "1", "", paras("Turned off", "on the slide", pPr = "<a:pPr><a:buNone/></a:pPr>"))),
            1 to slide(ph(3, null, "1", "", paras("", "Numbered", pPr = "<a:pPr><a:buAutoNum type=\"arabicPeriod\"/></a:pPr>"))),
        )
        val parts = linkedMapOf(
            "ppt/slideMasters/slideMaster1.xml" to master,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml") + "</Relationships>",
            "ppt/slideLayouts/slideLayout1.xml" to layout1,
            "ppt/slideLayouts/slideLayout2.xml" to layout2,
        )
        slides.forEachIndexed { i, (layout, xml) ->
            parts["ppt/slides/slide${i + 1}.xml"] = xml
            parts["ppt/slides/_rels/slide${i + 1}.xml.rels"] = relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout$layout.xml") + "</Relationships>"
        }
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        val deck = PptxReader.read(bos.toByteArray()).slides
        assertEquals("from the master's body style", SlideListStyle.BULLET, deck[0].body.listStyle)
        assertEquals(SlideListStyle.NONE, deck[0].title.listStyle)
        assertEquals("the layout turns them off", SlideListStyle.NONE, deck[1].body.listStyle)
        assertEquals("the slide turns them off", SlideListStyle.NONE, deck[2].body.listStyle)
        assertEquals("a blank first paragraph does not decide", SlideListStyle.NUMBER, deck[3].body.listStyle)
    }
}
